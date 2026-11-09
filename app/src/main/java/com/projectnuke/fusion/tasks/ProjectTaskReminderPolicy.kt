package com.projectnuke.fusion.tasks

import com.projectnuke.fusion.data.MAX_ENABLED_TASK_REMINDERS
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ReminderScheduleDecision {
    data object Allowed : ReminderScheduleDecision
    data object InvalidTime : ReminderScheduleDecision
    data object TooFarAhead : ReminderScheduleDecision
    data object CapacityReached : ReminderScheduleDecision
}

sealed interface ReminderUpdateResult {
    data object Updated : ReminderUpdateResult
    data object UnknownTask : ReminderUpdateResult
    data object TaskComplete : ReminderUpdateResult
    data object PermissionUnavailable : ReminderUpdateResult
    data object InvalidTime : ReminderUpdateResult
    data object TooFarAhead : ReminderUpdateResult
    data object CapacityReached : ReminderUpdateResult
    data object Failed : ReminderUpdateResult
    data object NotificationCleanupFailed : ReminderUpdateResult
}

object ProjectTaskReminderPolicy {
    const val MAX_FUTURE_MILLIS = 365L * 24L * 60L * 60L * 1_000L

    fun scheduleDecision(at: Long, now: Long, alreadyEnabled: Boolean, enabledCount: Int): ReminderScheduleDecision {
        if (now < 0L || at <= now) return ReminderScheduleDecision.InvalidTime
        if (at - now > MAX_FUTURE_MILLIS) return ReminderScheduleDecision.TooFarAhead
        if (!alreadyEnabled && enabledCount >= MAX_ENABLED_TASK_REMINDERS) return ReminderScheduleDecision.CapacityReached
        return ReminderScheduleDecision.Allowed
    }

    fun shouldDeliver(
        reminderEnabled: Boolean,
        reminderAt: Long?,
        expectedAt: Long,
        reminderGeneration: Long,
        expectedGeneration: Long,
        taskState: String,
    ): Boolean = reminderEnabled && reminderAt == expectedAt && reminderGeneration == expectedGeneration &&
        taskState == ProjectTaskState.OPEN.name

    fun shouldRequestNotificationPermission(sdkInt: Int, permissionGranted: Boolean): Boolean =
        sdkInt >= 33 && !permissionGranted
}

/** Serializes task reminder delivery with every in-process reminder mutation. */
object ProjectTaskReminderMutationGate {
    internal val mutex = Mutex()
}

/**
 * Keep the row check, notification publication, and durable compare-and-consume
 * inside the same mutation gate used by cancellation and rescheduling. Room and
 * NotificationManager are separate stores, so this is process-local ordering,
 * not crash-atomicity. A failed compare removes only the notification published
 * by this serialized attempt, preventing a stale row from leaving a visible
 * reminder after a concurrent database-only invalidation.
 */
internal suspend fun publishCurrentReminderUnderGate(
    mutex: Mutex,
    isCurrent: suspend () -> Boolean,
    publish: suspend () -> Boolean,
    consume: suspend () -> Int,
    discardPublished: () -> Unit,
): Boolean = mutex.withLock {
    if (!isCurrent()) return@withLock false
    if (!publish()) return@withLock false
    if (consume() == 1) return@withLock true
    discardPublished()
    false
}

interface ProjectTaskReminderScheduler {
    fun schedule(taskId: Long, at: Long, generation: Long)
    fun cancel(taskId: Long)
}
