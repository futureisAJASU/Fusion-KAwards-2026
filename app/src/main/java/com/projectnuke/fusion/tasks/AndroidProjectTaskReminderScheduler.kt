package com.projectnuke.fusion.tasks

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.projectnuke.fusion.MainActivity
import com.projectnuke.fusion.R
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.MAX_ENABLED_TASK_REMINDERS
import com.projectnuke.fusion.data.MAX_ENABLED_STUDY_REVIEW_CARD_REMINDERS
import com.projectnuke.fusion.study.StudyReviewCardReminderScheduler
import com.projectnuke.fusion.study.QuickReviewCardReminderMutationGate
import com.projectnuke.fusion.study.QuickReviewCardReminderPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.text.DateFormat
import java.util.Date

internal const val PROJECT_TASK_REMINDER_ACTION = "com.projectnuke.fusion.action.PROJECT_TASK_REMINDER"
private const val STUDY_CARD_REMINDER_ACTION = "com.projectnuke.fusion.action.STUDY_CARD_REMINDER"
private const val PROJECT_TASK_REMINDER_CHANNEL = LegacyReminderNotificationPolicy.TASK_CHANNEL_ID
private const val STUDY_CARD_REMINDER_CHANNEL = LegacyReminderNotificationPolicy.CARD_CHANNEL_ID

class AndroidProjectTaskReminderScheduler(private val context: Context) : ProjectTaskReminderScheduler, StudyReviewCardReminderScheduler {
    private val appContext = context.applicationContext

    override fun schedule(taskId: Long, at: Long, generation: Long) {
        require(taskId > 0L && at > 0L && generation >= 0L)
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(taskId, at, generation))
    }

    override fun cancel(taskId: Long) {
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pendingIntent(taskId, null, null))
        val notifications = appContext.getSystemService(NotificationManager::class.java)
            ?: throw IllegalStateException("Notification service is unavailable")
        cancelOwnedReminder(notifications, ReminderNotificationIdentity.task(taskId))
    }

    override fun scheduleCard(cardId: String, at: Long, generation: Long) {
        require(cardId.isNotBlank() && cardId.length <= 80 && at > 0L && generation >= 0L)
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, cardPendingIntent(cardId, at, generation))
    }

    override fun cancelCard(cardId: String) {
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(cardPendingIntent(cardId, null, null))
        val notifications = appContext.getSystemService(NotificationManager::class.java)
            ?: throw IllegalStateException("Notification service is unavailable")
        cancelOwnedReminder(notifications, ReminderNotificationIdentity.card(cardId))
    }

    internal fun cardPendingIntent(cardId: String, expectedAt: Long?, generation: Long?): PendingIntent {
        val intent = Intent(appContext, StudyReviewCardReminderReceiver::class.java).apply {
            action = STUDY_CARD_REMINDER_ACTION
            data = Uri.parse("fusion-study-card-reminder://${Uri.encode(cardId)}")
            putExtra(EXTRA_CARD_ID, cardId)
            if (expectedAt != null) putExtra(EXTRA_EXPECTED_AT, expectedAt)
            if (generation != null) putExtra(EXTRA_EXPECTED_GENERATION, generation)
        }
        return PendingIntent.getBroadcast(
            appContext,
            cardId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun pendingIntent(taskId: Long, expectedAt: Long?, generation: Long?): PendingIntent {
        val intent = Intent(appContext, ProjectTaskReminderReceiver::class.java).apply {
            action = PROJECT_TASK_REMINDER_ACTION
            data = Uri.parse("fusion-task-reminder://$taskId")
            putExtra(EXTRA_TASK_ID, taskId)
            if (expectedAt != null) putExtra(EXTRA_EXPECTED_AT, expectedAt)
            if (generation != null) putExtra(EXTRA_EXPECTED_GENERATION, generation)
        }
        return PendingIntent.getBroadcast(
            appContext,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Legacy untagged IDs are retained for old-alert cleanup and PendingIntent compatibility. */
        fun notificationId(taskId: Long): Int = ReminderNotificationIdentity.task(taskId).legacyId
        fun cardNotificationId(cardId: String): Int = ReminderNotificationIdentity.card(cardId).legacyId
    }

    /**
     * Bounded durable reconciliation for overdue enabled reminders when
     * notification delivery is viable (N05). Directly delivers each overdue
     * reminder via the shared [deliverOverdueTaskReminder] / [deliverOverdueCardReminder]
     * path, which atomically consumes via compare-and-consume on the persisted
     * expectedAt. No alarms with past timestamps are scheduled.
     */
    fun reconcileOverdue(database: AppDatabase, now: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                cleanupLegacyReminderNotifications(appContext)
                reconcilePendingReminderNotificationCleanup(
                    database, this@AndroidProjectTaskReminderScheduler, this@AndroidProjectTaskReminderScheduler,
                )
                if (!reminderNotificationsAllowed(appContext)) return@launch
                val tasks = ReminderReconciliationPolicy.filterDeliverableTasks(
                    database.projectTaskDao().getOverdueReminders(now, MAX_ENABLED_TASK_REMINDERS),
                    now,
                )
                tasks.forEach { task ->
                    deliverOverdueTaskReminder(database, appContext, task.id, task.reminderAt!!, task.reminderGeneration)
                }
                val cards = ReminderReconciliationPolicy.filterDeliverableCards(
                    database.studyReviewCardDao().getOverdueReminders(now, MAX_ENABLED_STUDY_REVIEW_CARD_REMINDERS),
                    now,
                )
                cards.forEach { card ->
                    deliverOverdueCardReminder(database, appContext, card.id, card.reminderAt!!, card.reminderGeneration)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("FusionReminder", "Could not reconcile overdue reminders", failure)
            }
        }
    }
}

/**
 * Shared delivery path for overdue task reminders. Used by both the
 * BroadcastReceiver and reconciliation. The notification is posted before
 * consuming the durable reminder, so a process death or posting failure leaves
 * it eligible for retry. Stable notification IDs make retries updates, and
 * setOnlyAlertOnce avoids repeating the alert sound.
 *
 * Cancellation, rescheduling, reconciliation, and receiver delivery share a
 * process-local mutation gate. The current row is checked again inside that
 * gate, then publication and compare-and-consume are serialized with edits.
 * This does not make Room and NotificationManager crash-atomic.
 */
internal suspend fun publishReminderBeforeConsume(
    publish: () -> Boolean,
    consume: suspend () -> Int,
): Boolean {
    if (!publish()) return false
    return consume() == 1
}

/**
 * Retries cleanup debt after a process interruption between the Room commit
 * and NotificationManager cancellation. Cancellation and any replacement
 * alarm are serialized with the same process-local generation gates.
 */
internal suspend fun reconcilePendingReminderNotificationCleanup(
    database: AppDatabase,
    taskScheduler: ProjectTaskReminderScheduler,
    cardScheduler: StudyReviewCardReminderScheduler,
) {
    try {
        ReminderDeletionCoordinator(database, taskScheduler, cardScheduler).reconcilePendingDeletions()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w("FusionReminder", "Could not reconcile reminder deletion intents", failure)
    }

    reconcileTaskNotificationCleanup(database, taskScheduler)
    reconcileCardNotificationCleanup(database, cardScheduler)
}

private const val MAX_PENDING_NOTIFICATION_CLEANUPS = 512
private const val MAX_NOTIFICATION_CLEANUP_ROUNDS = 16

private suspend fun reconcileTaskNotificationCleanup(
    database: AppDatabase,
    taskScheduler: ProjectTaskReminderScheduler,
) {
    val cursors = ReminderRecoveryCursorStore(database)
    var page = 0
    try {
        while (page < MAX_NOTIFICATION_CLEANUP_ROUNDS) {
            val pendingTasks = cursors.nextLongPage(
                key = ReminderRecoveryCursorKeys.TASK_NOTIFICATION_CLEANUP,
                limit = MAX_PENDING_NOTIFICATION_CLEANUPS,
                load = database.projectTaskDao()::getPendingNotificationCleanupAfter,
                idOf = { it.id },
            )
            if (pendingTasks.isEmpty()) break
            page++
            for (pending in pendingTasks) {
                currentCoroutineContext().ensureActive()
                try {
                    ProjectTaskReminderMutationGate.mutex.withLock {
                        val current = database.projectTaskDao().getById(pending.id) ?: return@withLock
                        if (!current.notificationCleanupPending || current.deletionPending ||
                            current.reminderGeneration != pending.reminderGeneration
                        ) return@withLock
                        taskScheduler.cancel(current.id)
                        if (current.reminderEnabled && current.reminderAt != null && current.state == "OPEN") {
                            taskScheduler.schedule(current.id, current.reminderAt, current.reminderGeneration)
                        }
                        database.projectTaskDao().clearNotificationCleanupPending(current.id, current.reminderGeneration)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("FusionReminder", "Could not clear task notification ${pending.id}", failure)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w("FusionReminder", "Could not enumerate task notification cleanup", failure)
    }
}

private suspend fun reconcileCardNotificationCleanup(
    database: AppDatabase,
    cardScheduler: StudyReviewCardReminderScheduler,
) {
    val cursors = ReminderRecoveryCursorStore(database)
    var page = 0
    try {
        while (page < MAX_NOTIFICATION_CLEANUP_ROUNDS) {
            val pendingCards = cursors.nextStringPage(
                key = ReminderRecoveryCursorKeys.CARD_NOTIFICATION_CLEANUP,
                limit = MAX_PENDING_NOTIFICATION_CLEANUPS,
                load = database.studyReviewCardDao()::getPendingNotificationCleanupAfter,
                idOf = { it.id },
            )
            if (pendingCards.isEmpty()) break
            page++
            for (pending in pendingCards) {
                currentCoroutineContext().ensureActive()
                try {
                    QuickReviewCardReminderMutationGate.mutex.withLock {
                        val current = database.studyReviewCardDao().get(pending.id) ?: return@withLock
                        if (!current.notificationCleanupPending || current.deletionPending ||
                            current.reminderGeneration != pending.reminderGeneration
                        ) return@withLock
                        cardScheduler.cancelCard(current.id)
                        if (current.reminderEnabled && current.reminderAt != null) {
                            cardScheduler.scheduleCard(current.id, current.reminderAt, current.reminderGeneration)
                        }
                        database.studyReviewCardDao().clearNotificationCleanupPending(current.id, current.reminderGeneration)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("FusionReminder", "Could not clear card notification ${pending.id}", failure)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w("FusionReminder", "Could not enumerate card notification cleanup", failure)
    }
}

suspend fun deliverOverdueTaskReminder(
    database: AppDatabase,
    context: Context,
    taskId: Long,
    expectedAt: Long,
    expectedGeneration: Long,
) {
    var currentTitle = ""
    publishCurrentReminderUnderGate(
        mutex = ProjectTaskReminderMutationGate.mutex,
        isCurrent = {
            val task = database.projectTaskDao().getById(taskId) ?: return@publishCurrentReminderUnderGate false
            val valid = ProjectTaskReminderPolicy.shouldDeliver(
                reminderEnabled = task.reminderEnabled,
                reminderAt = task.reminderAt,
                expectedAt = expectedAt,
                reminderGeneration = task.reminderGeneration,
                expectedGeneration = expectedGeneration,
                taskState = task.state,
            )
            if (valid) currentTitle = task.title
            valid && reminderNotificationsAllowed(context)
        },
        publish = { postTaskReminder(context.applicationContext, taskId, currentTitle, expectedAt) },
        consume = { database.projectTaskDao().consumeReminder(taskId, expectedAt, expectedGeneration) },
        discardPublished = { cancelPostedReminder(context.applicationContext, ReminderNotificationIdentity.task(taskId)) },
    )
}

/**
 * Shared delivery path for overdue study-review-card reminders.
 */
suspend fun deliverOverdueCardReminder(
    database: AppDatabase,
    context: Context,
    cardId: String,
    expectedAt: Long,
    expectedGeneration: Long,
) {
    var currentFront = ""
    publishCurrentReminderUnderGate(
        mutex = QuickReviewCardReminderMutationGate.mutex,
        isCurrent = {
            val card = database.studyReviewCardDao().get(cardId) ?: return@publishCurrentReminderUnderGate false
            val valid = QuickReviewCardReminderPolicy.shouldDeliver(
                card.reminderEnabled,
                card.reminderAt,
                expectedAt,
                card.reminderGeneration,
                expectedGeneration,
            )
            if (valid) currentFront = card.front
            valid && reminderNotificationsAllowed(context)
        },
        publish = { postStudyCardReminder(context.applicationContext, cardId, currentFront, expectedAt) },
        consume = { database.studyReviewCardDao().consumeReminder(cardId, expectedAt, expectedGeneration) },
        discardPublished = { cancelPostedReminder(context.applicationContext, ReminderNotificationIdentity.card(cardId)) },
    )
}

private const val EXTRA_TASK_ID = "task_id"
private const val EXTRA_CARD_ID = "card_id"
private const val EXTRA_EXPECTED_AT = "expected_at"
private const val EXTRA_EXPECTED_GENERATION = "expected_generation"

class ProjectTaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val expectedAt = intent.getLongExtra(EXTRA_EXPECTED_AT, -1L)
        val expectedGeneration = intent.getLongExtra(EXTRA_EXPECTED_GENERATION, -1L)
        if (taskId <= 0L || expectedAt <= 0L || expectedGeneration < 0L) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deliverOverdueTaskReminder(AppDatabase.getInstance(context), context, taskId, expectedAt, expectedGeneration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("FusionReminder", "Could not deliver task reminder", failure)
            } finally {
                result.finish()
            }
        }
    }
}

class StudyReviewCardReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cardId = intent.getStringExtra(EXTRA_CARD_ID)?.takeIf { it.isNotBlank() && it.length <= 80 } ?: return
        val expectedAt = intent.getLongExtra(EXTRA_EXPECTED_AT, -1L)
        val expectedGeneration = intent.getLongExtra(EXTRA_EXPECTED_GENERATION, -1L)
        if (expectedAt <= 0L || expectedGeneration < 0L) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deliverOverdueCardReminder(AppDatabase.getInstance(context), context, cardId, expectedAt, expectedGeneration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("FusionReminder", "Could not deliver study card reminder", failure)
            } finally {
                result.finish()
            }
        }
    }
}

class ProjectTaskReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = AppDatabase.getInstance(context)
                val scheduler = AndroidProjectTaskReminderScheduler(context.applicationContext)
                cleanupLegacyReminderNotifications(context.applicationContext)
                reconcilePendingReminderNotificationCleanup(database, scheduler, scheduler)
                database.projectTaskDao().getEnabledReminders(MAX_ENABLED_TASK_REMINDERS).forEach { task ->
                    try {
                        ProjectTaskReminderMutationGate.mutex.withLock {
                            val current = database.projectTaskDao().getById(task.id)
                            if (current?.reminderEnabled == true && current.reminderAt != null && current.state == "OPEN") {
                                scheduler.schedule(current.id, current.reminderAt, current.reminderGeneration)
                            } else {
                                scheduler.cancel(task.id)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Log.w("FusionReminder", "Could not restore a task reminder", failure)
                    }
                }
                database.studyReviewCardDao().getEnabledReminders(MAX_ENABLED_STUDY_REVIEW_CARD_REMINDERS).forEach { card ->
                    try {
                        QuickReviewCardReminderMutationGate.mutex.withLock {
                            val current = database.studyReviewCardDao().get(card.id)
                            if (current?.reminderEnabled == true && current.reminderAt != null) {
                                scheduler.scheduleCard(current.id, current.reminderAt, current.reminderGeneration)
                            } else {
                                scheduler.cancelCard(card.id)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Log.w("FusionReminder", "Could not restore a study card reminder", failure)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("FusionReminder", "Could not restore task reminders", failure)
            } finally {
                result.finish()
            }
        }
    }
}

private fun postTaskReminder(context: Context, taskId: Long, title: String, at: Long): Boolean {
    return postReminder(
        context = context,
        identity = ReminderNotificationIdentity.task(taskId),
        title = title,
        message = "다시 보기",
        at = at,
        channelId = PROJECT_TASK_REMINDER_CHANNEL,
        channelName = "Fusion 할 일 알림",
        taskId = taskId,
        cardId = null,
    )
}

private fun postStudyCardReminder(context: Context, cardId: String, front: String, at: Long): Boolean {
    return postReminder(
        context = context,
        identity = ReminderNotificationIdentity.card(cardId),
        title = front,
        message = "복습 카드 · 다시 보기",
        at = at,
        channelId = STUDY_CARD_REMINDER_CHANNEL,
        channelName = "Fusion 복습 알림",
        taskId = null,
        cardId = cardId,
    )
}

internal fun postReminder(
    context: Context,
    identity: ReminderNotificationIdentity,
    title: String,
    message: String,
    at: Long,
    channelId: String,
    channelName: String,
    taskId: Long?,
    cardId: String?,
): Boolean {
    if (!reminderNotificationsAllowed(context)) return false
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    if (Build.VERSION.SDK_INT >= 26) {
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        // createNotificationChannel preserves the user's existing choice.
        // Do not consume the durable reminder when its channel is disabled.
        if (!reminderChannelAllowsDelivery(manager.getNotificationChannel(channelId)?.importance)) {
            return false
        }
    }
    val openTarget = Intent(context, MainActivity::class.java).apply {
        // Stable identity for tap-to-target navigation (U05); only IDs,
        // never titles or content. Distinct data URIs keep the two reminder
        // families' PendingIntents from colliding.
        if (taskId != null) {
            putExtra(com.projectnuke.fusion.ReminderNavigationPolicy.EXTRA_TASK_ID, taskId)
            data = Uri.parse("fusion-reminder-task://$taskId")
        }
        if (cardId != null) {
            putExtra(com.projectnuke.fusion.ReminderNavigationPolicy.EXTRA_CARD_ID, cardId)
            data = Uri.parse("fusion-reminder-card://${Uri.encode(cardId)}")
        }
    }
    val notificationId = identity.id
    val openApp = PendingIntent.getActivity(
        context,
        notificationId,
        openTarget,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = Notification.Builder(context, channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title.take(120))
        .setContentText("$message · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))}")
        .setContentIntent(openApp)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .build()
    postOwnedReminder(manager, identity, notification)
    return true
}

internal fun reminderChannelAllowsDelivery(importance: Int?): Boolean =
    importance != null && importance != NotificationManager.IMPORTANCE_NONE

private fun cancelPostedReminder(context: Context, identity: ReminderNotificationIdentity) {
    context.getSystemService(NotificationManager::class.java)?.let { manager ->
        cancelOwnedReminder(manager, identity)
    }
}

/**
 * Removes only old untagged notifications in the two reminder-owned channels.
 * A colliding legacy integer never identifies which owner originally won, so
 * the old alert is discarded as ambiguous; new tagged owners are untouched.
 */
internal fun cleanupLegacyReminderNotifications(context: Context) {
    try {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (statusBarNotification in manager.activeNotifications) {
            if (!LegacyReminderNotificationPolicy.isLegacyUntagged(
                    statusBarNotification.tag,
                    statusBarNotification.notification.channelId,
                )
            ) continue
            try {
                manager.cancel(null, statusBarNotification.id)
            } catch (failure: Exception) {
                Log.w("FusionReminder", "Could not cancel a legacy reminder notification", failure)
            }
        }
    } catch (failure: Exception) {
        Log.w("FusionReminder", "Could not migrate legacy reminder notifications", failure)
    }
}

private fun reminderNotificationsAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false
    return context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
}
