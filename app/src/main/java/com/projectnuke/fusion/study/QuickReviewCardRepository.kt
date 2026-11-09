package com.projectnuke.fusion.study

import androidx.room.withTransaction
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ReviewCardInsertResult
import com.projectnuke.fusion.data.ReviewCardReminderUpdateResult
import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.knowledge.SavedKnowledgeAccessGate
import com.projectnuke.fusion.tasks.ProjectTaskReminderScheduler
import com.projectnuke.fusion.tasks.ReminderDeletionCoordinator
import com.projectnuke.fusion.tasks.ReminderDeletionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface QuickReviewCardWriteResult {
    data object Stored : QuickReviewCardWriteResult
    data object Invalid : QuickReviewCardWriteResult
    data object SensitiveContent : QuickReviewCardWriteResult
    data object SourceUnavailable : QuickReviewCardWriteResult
    data object AccessUnavailable : QuickReviewCardWriteResult
    data object ProjectUnavailable : QuickReviewCardWriteResult
    data object CapacityReached : QuickReviewCardWriteResult
    data object Failed : QuickReviewCardWriteResult
}

sealed interface QuickReviewRatingResult {
    data object Updated : QuickReviewRatingResult
    data object UpdatedReminderCleanupFailed : QuickReviewRatingResult
    data object UnknownCard : QuickReviewRatingResult
    data object NotDue : QuickReviewRatingResult
    data object Failed : QuickReviewRatingResult
}

sealed interface QuickReviewCardReminderResult {
    data object Updated : QuickReviewCardReminderResult
    data object UnknownCard : QuickReviewCardReminderResult
    data object PermissionUnavailable : QuickReviewCardReminderResult
    data object InvalidTime : QuickReviewCardReminderResult
    data object TooFarAhead : QuickReviewCardReminderResult
    data object CapacityReached : QuickReviewCardReminderResult
    data object Failed : QuickReviewCardReminderResult
    data object NotificationCleanupFailed : QuickReviewCardReminderResult
}

enum class QuickReviewCardDeleteResult { DELETED, NOT_FOUND, CLEANUP_PENDING, FAILED }

fun interface QuickReviewCardWriter {
    suspend fun save(proposal: QuickReviewCardProposal, archiveAccessIncluded: Boolean): QuickReviewCardWriteResult
}

/** Room owner revalidates source identity and scope before accepting the reviewed copy. */
class QuickReviewCardRepository(
    private val database: AppDatabase,
    private val reminderScheduler: StudyReviewCardReminderScheduler,
) : QuickReviewCardWriter {
    override suspend fun save(
        proposal: QuickReviewCardProposal,
        archiveAccessIncluded: Boolean,
    ): QuickReviewCardWriteResult = withContext(Dispatchers.IO) {
        try {
            database.withTransaction {
                if (proposal.scope == ReviewCardScope.PROJECT) {
                    val projectId = proposal.projectId ?: return@withTransaction QuickReviewCardWriteResult.ProjectUnavailable
                    if (database.projectDao().getProjectById(projectId) == null) {
                        return@withTransaction QuickReviewCardWriteResult.ProjectUnavailable
                    }
                }
                val conversation = proposal.sourceConversationId?.let { id ->
                    database.chatDao().getConversationById(id)
                        ?: return@withTransaction QuickReviewCardWriteResult.SourceUnavailable
                }
                if (conversation?.isArchived == true && !archiveAccessIncluded) {
                    return@withTransaction QuickReviewCardWriteResult.AccessUnavailable
                }
                if (proposal.sourceKind == ReviewCardOrigin.ASSISTANT_ANSWER) {
                    val conversationId = proposal.sourceConversationId ?: return@withTransaction QuickReviewCardWriteResult.SourceUnavailable
                    val messageId = proposal.sourceMessageId ?: return@withTransaction QuickReviewCardWriteResult.SourceUnavailable
                    val answer = database.savedKnowledgeDao().getSourceAnswer(messageId, conversationId)
                        ?.takeIf { it.messageId == messageId && it.conversationId == conversationId && it.role == "assistant" }
                        ?: return@withTransaction QuickReviewCardWriteResult.SourceUnavailable
                    if (answer.messageId <= 0L) return@withTransaction QuickReviewCardWriteResult.SourceUnavailable
                }
                if (proposal.sourceKind == ReviewCardOrigin.SAVED_KNOWLEDGE) {
                    val sourceId = proposal.sourceSavedKnowledgeId ?: return@withTransaction QuickReviewCardWriteResult.SourceUnavailable
                    val savedSource = database.savedKnowledgeDao().getById(sourceId)
                    if (savedSource == null) {
                        return@withTransaction QuickReviewCardWriteResult.SourceUnavailable
                    }
                    // Authority-bound derived knowledge: re-check the original
                    // archive source (own + linked USER_NOTE sources). Cancellation
                    // and fatal Error propagate; recoverable read failure fails closed.
                    val accessible = try {
                        SavedKnowledgeAccessGate.isRowAccessible(database, savedSource, archiveAccessIncluded)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    if (!accessible) {
                        return@withTransaction QuickReviewCardWriteResult.AccessUnavailable
                    }
                }
                if (proposal.sourceKind == ReviewCardOrigin.PROJECT_DOCUMENT) {
                    val sourceProjectId = proposal.sourceProjectId ?: return@withTransaction QuickReviewCardWriteResult.SourceUnavailable
                    val fileId = proposal.sourceProjectFileId ?: return@withTransaction QuickReviewCardWriteResult.SourceUnavailable
                    if (database.projectDao().getProjectById(sourceProjectId) == null ||
                        database.projectDao().getProjectFiles(sourceProjectId).none { it.id == fileId }
                    ) return@withTransaction QuickReviewCardWriteResult.SourceUnavailable
                    if (proposal.scope == ReviewCardScope.PROJECT && proposal.projectId != sourceProjectId) {
                        return@withTransaction QuickReviewCardWriteResult.ProjectUnavailable
                    }
                }
                if (proposal.scope == ReviewCardScope.PROJECT && conversation != null &&
                    proposal.sourceKind in setOf(ReviewCardOrigin.STUDY_TOPIC, ReviewCardOrigin.ASSISTANT_ANSWER) &&
                    database.projectDao().getProjectIdForConversation(conversation.id) != proposal.projectId
                ) return@withTransaction QuickReviewCardWriteResult.ProjectUnavailable
                when (database.studyReviewCardDao().insertBounded(QuickReviewCardPolicy.toEntity(proposal))) {
                    ReviewCardInsertResult.Inserted -> QuickReviewCardWriteResult.Stored
                    ReviewCardInsertResult.CapacityReached -> QuickReviewCardWriteResult.CapacityReached
                    ReviewCardInsertResult.ProjectUnavailable -> QuickReviewCardWriteResult.ProjectUnavailable
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            QuickReviewCardWriteResult.Failed
        }
    }

    fun observeQueue(projectId: Long?): Flow<List<StudyReviewCardEntity>> =
        (if (projectId == null) database.studyReviewCardDao().observePersonal(QuickReviewCardPolicy.MAX_QUEUE_CARDS)
        else database.studyReviewCardDao().observeProject(projectId, QuickReviewCardPolicy.MAX_QUEUE_CARDS))
            .map { it.take(QuickReviewCardPolicy.MAX_QUEUE_CARDS) }

    suspend fun review(id: String, rating: ReviewCardRating, now: Long = System.currentTimeMillis()): QuickReviewRatingResult = withContext(Dispatchers.IO) {
        QuickReviewCardReminderMutationGate.mutex.withLock {
            try {
                val card = database.studyReviewCardDao().get(id) ?: return@withLock QuickReviewRatingResult.UnknownCard
                if (card.dueAt > now) return@withLock QuickReviewRatingResult.NotDue
                val decision = QuickReviewCardPolicy.rate(card, rating, now)
                if (decision !is ReviewCardDecision.Updated) return@withLock QuickReviewRatingResult.Failed
                val updated = decision.card
                if (database.studyReviewCardDao().recordReview(
                        updated.id, updated.reviewCount, checkNotNull(updated.lastRating), updated.dueAt, now,
                    ) == 1
                ) {
                    // Reminder delivery sets reminderEnabled=false after
                    // posting, but that does not dismiss the visible alert.
                    // Clear the stable notification ID after every durable
                    // review, under the shared generation gate.
                    val canceled = withContext(NonCancellable) {
                        try {
                            reminderScheduler.cancelCard(id)
                            val current = database.studyReviewCardDao().get(id)
                            current == null || !current.notificationCleanupPending ||
                                database.studyReviewCardDao().clearNotificationCleanupPending(id, current.reminderGeneration) == 1
                        }
                        catch (_: Exception) { false }
                    }
                    if (canceled) QuickReviewRatingResult.Updated
                    else QuickReviewRatingResult.UpdatedReminderCleanupFailed
                } else QuickReviewRatingResult.NotDue
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                QuickReviewRatingResult.Failed
            }
        }
    }

    suspend fun scheduleReminder(
        id: String,
        at: Long,
        now: Long = System.currentTimeMillis(),
        notificationsAllowed: Boolean,
    ): QuickReviewCardReminderResult = withContext(Dispatchers.IO) {
        QuickReviewCardReminderMutationGate.mutex.withLock {
            if (!notificationsAllowed) return@withLock QuickReviewCardReminderResult.PermissionUnavailable
            try {
                val dao = database.studyReviewCardDao()
                val card = dao.get(id) ?: return@withLock QuickReviewCardReminderResult.UnknownCard
                when (QuickReviewCardReminderPolicy.scheduleDecision(
                    at, now, card.reminderEnabled, dao.countEnabledReminders(),
                )) {
                    QuickReviewCardReminderDecision.InvalidTime -> return@withLock QuickReviewCardReminderResult.InvalidTime
                    QuickReviewCardReminderDecision.TooFarAhead -> return@withLock QuickReviewCardReminderResult.TooFarAhead
                    QuickReviewCardReminderDecision.CapacityReached -> return@withLock QuickReviewCardReminderResult.CapacityReached
                    QuickReviewCardReminderDecision.Allowed -> Unit
                }
                when (dao.updateReminderBounded(id, at)) {
                    ReviewCardReminderUpdateResult.UnknownCard -> return@withLock QuickReviewCardReminderResult.UnknownCard
                    ReviewCardReminderUpdateResult.CapacityReached -> return@withLock QuickReviewCardReminderResult.CapacityReached
                    ReviewCardReminderUpdateResult.Updated -> Unit
                }
                try {
                    // Remove any notification from the previous card reminder
                    // before installing the replacement alarm. Delivery and
                    // reminder mutations share this repository's gate.
                    reminderScheduler.cancelCard(id)
                    val updated = dao.get(id)
                        ?: throw IllegalStateException("Study review card disappeared while scheduling a reminder")
                    reminderScheduler.scheduleCard(id, at, updated.reminderGeneration)
                    if (updated.notificationCleanupPending &&
                        dao.clearNotificationCleanupPending(id, updated.reminderGeneration) != 1
                    ) throw IllegalStateException("Study card reminder cleanup could not be settled")
                    QuickReviewCardReminderResult.Updated
                } catch (cancelled: CancellationException) {
                    restoreReminder(dao, card)
                    throw cancelled
                } catch (_: Exception) {
                    restoreReminder(dao, card)
                    QuickReviewCardReminderResult.Failed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                QuickReviewCardReminderResult.Failed
            }
        }
    }

    private suspend fun restoreReminder(dao: com.projectnuke.fusion.data.StudyReviewCardDao, previous: StudyReviewCardEntity) {
        withContext(NonCancellable) {
            dao.updateReminder(previous.id, previous.reminderAt, previous.reminderEnabled)
            try {
                if (previous.reminderEnabled && previous.reminderAt != null) {
                    val restored = dao.get(previous.id)
                    if (restored?.reminderEnabled == true && restored.reminderAt == previous.reminderAt) {
                        reminderScheduler.cancelCard(previous.id)
                        reminderScheduler.scheduleCard(previous.id, previous.reminderAt, restored.reminderGeneration)
                    } else {
                        reminderScheduler.cancelCard(previous.id)
                    }
                    val settled = dao.get(previous.id)
                    if (settled?.notificationCleanupPending == true) {
                        dao.clearNotificationCleanupPending(previous.id, settled.reminderGeneration)
                    }
                } else {
                    reminderScheduler.cancelCard(previous.id)
                    val settled = dao.get(previous.id)
                    if (settled?.notificationCleanupPending == true) {
                        dao.clearNotificationCleanupPending(previous.id, settled.reminderGeneration)
                    }
                }
            } catch (_: Exception) {
                // The persisted reminder metadata remains the source of truth for boot restoration.
            }
        }
    }

    suspend fun disableReminder(id: String, clearTime: Boolean = false): QuickReviewCardReminderResult = withContext(Dispatchers.IO) {
        QuickReviewCardReminderMutationGate.mutex.withLock {
            try {
                val dao = database.studyReviewCardDao()
                val card = dao.get(id) ?: return@withLock QuickReviewCardReminderResult.UnknownCard
                val changed = dao.updateReminder(id, if (clearTime) null else card.reminderAt, false)
                if (changed != 1) return@withLock QuickReviewCardReminderResult.UnknownCard
                val cleaned = try {
                    reminderScheduler.cancelCard(id)
                    val updated = database.studyReviewCardDao().get(id)
                    updated == null || !updated.notificationCleanupPending ||
                        database.studyReviewCardDao().clearNotificationCleanupPending(id, updated.reminderGeneration) == 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (cleaned) QuickReviewCardReminderResult.Updated
                else QuickReviewCardReminderResult.NotificationCleanupFailed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                QuickReviewCardReminderResult.Failed
            }
        }
    }

    suspend fun delete(id: String): QuickReviewCardDeleteResult = withContext(Dispatchers.IO) {
        when (ReminderDeletionCoordinator(database, noTaskScheduler, reminderScheduler).deleteCard(id)) {
            ReminderDeletionResult.DELETED -> QuickReviewCardDeleteResult.DELETED
            ReminderDeletionResult.NOT_FOUND -> QuickReviewCardDeleteResult.NOT_FOUND
            ReminderDeletionResult.CLEANUP_PENDING -> QuickReviewCardDeleteResult.CLEANUP_PENDING
            ReminderDeletionResult.FAILED -> QuickReviewCardDeleteResult.FAILED
        }
    }

    private companion object {
        val noTaskScheduler = object : ProjectTaskReminderScheduler {
            override fun schedule(taskId: Long, at: Long, generation: Long) = Unit
            override fun cancel(taskId: Long) = Unit
        }
    }
}
