package com.projectnuke.fusion.tasks

import android.util.Log
import androidx.room.withTransaction
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.MAX_PROJECT_TASKS
import com.projectnuke.fusion.data.MAX_STUDY_REVIEW_CARDS
import com.projectnuke.fusion.study.QuickReviewCardReminderMutationGate
import com.projectnuke.fusion.study.StudyReviewCardReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ReminderDeletionResult {
    DELETED,
    NOT_FOUND,
    CLEANUP_PENDING,
    FAILED,
}

/**
 * Coordinates Room-owned reminder deletion with AlarmManager and
 * NotificationManager. Room cannot atomically commit with those system
 * services, so the deletion flag is committed first and acts as a durable
 * outbox entry. A pending row is never eligible for delivery or restoration.
 */
internal class ReminderDeletionCoordinator(
    private val database: AppDatabase,
    private val taskScheduler: ProjectTaskReminderScheduler,
    private val cardScheduler: StudyReviewCardReminderScheduler? = null,
) {
    suspend fun deleteTask(projectId: Long, taskId: Long): ReminderDeletionResult = withContext(Dispatchers.IO) {
        try {
            ProjectTaskReminderMutationGate.mutex.withLock {
                val taskDao = database.projectTaskDao()
                val existing = taskDao.get(taskId, projectId) ?: return@withLock ReminderDeletionResult.NOT_FOUND
                if (!existing.deletionPending && taskDao.beginDeletion(taskId, projectId) != 1) {
                    return@withLock ReminderDeletionResult.NOT_FOUND
                }
                withContext(NonCancellable) { finishTaskDeletion(projectId, taskId) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Could not start task deletion for $taskId", failure)
            if (database.projectTaskDao().getById(taskId)?.deletionPending == true) {
                ReminderDeletionResult.CLEANUP_PENDING
            } else ReminderDeletionResult.FAILED
        }
    }

    suspend fun deleteCard(cardId: String): ReminderDeletionResult = withContext(Dispatchers.IO) {
        try {
            val scheduler = checkNotNull(cardScheduler) { "Study card scheduler is required" }
            QuickReviewCardReminderMutationGate.mutex.withLock {
                val cardDao = database.studyReviewCardDao()
                val existing = cardDao.get(cardId) ?: return@withLock ReminderDeletionResult.NOT_FOUND
                if (!existing.deletionPending && cardDao.beginDeletion(cardId) != 1) {
                    return@withLock ReminderDeletionResult.NOT_FOUND
                }
                withContext(NonCancellable) { finishCardDeletion(cardId, scheduler) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Could not start card deletion for $cardId", failure)
            if (database.studyReviewCardDao().get(cardId)?.deletionPending == true) {
                ReminderDeletionResult.CLEANUP_PENDING
            } else ReminderDeletionResult.FAILED
        }
    }

    suspend fun deleteProject(projectId: Long): ReminderDeletionResult = withContext(Dispatchers.IO) {
        try {
            checkNotNull(cardScheduler) { "Study card scheduler is required" }
            ProjectTaskReminderMutationGate.mutex.withLock {
                QuickReviewCardReminderMutationGate.mutex.withLock {
                    val project = database.projectDao().getProjectForDeletion(projectId)
                        ?: return@withLock ReminderDeletionResult.NOT_FOUND
                    if (!project.deletionPending) {
                        database.withTransaction {
                            check(database.projectDao().beginDeletion(projectId) == 1)
                            database.projectTaskDao().beginProjectChildrenDeletion(projectId)
                            database.studyReviewCardDao().beginProjectChildrenDeletion(projectId)
                        }
                    }
                    withContext(NonCancellable) { finishProjectDeletion(projectId) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Could not start project deletion for $projectId", failure)
            if (database.projectDao().getProjectForDeletion(projectId)?.deletionPending == true) {
                ReminderDeletionResult.CLEANUP_PENDING
            } else ReminderDeletionResult.FAILED
        }
    }

    private suspend fun finishTaskDeletion(projectId: Long, taskId: Long): ReminderDeletionResult {
        val taskDao = database.projectTaskDao()
        val current = taskDao.get(taskId, projectId) ?: return ReminderDeletionResult.DELETED
        if (!current.deletionPending) return ReminderDeletionResult.CLEANUP_PENDING
        return try {
            taskScheduler.cancel(taskId)
            if (taskDao.finishDeletion(taskId, projectId, current.reminderGeneration) == 1 ||
                taskDao.getById(taskId) == null
            ) ReminderDeletionResult.DELETED else ReminderDeletionResult.CLEANUP_PENDING
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Task reminder deletion remains pending for $taskId", failure)
            ReminderDeletionResult.CLEANUP_PENDING
        }
    }

    private suspend fun finishCardDeletion(
        cardId: String,
        scheduler: StudyReviewCardReminderScheduler = checkNotNull(cardScheduler) { "Study card scheduler is required" },
    ): ReminderDeletionResult {
        val cardDao = database.studyReviewCardDao()
        val current = cardDao.get(cardId) ?: return ReminderDeletionResult.DELETED
        if (!current.deletionPending) return ReminderDeletionResult.CLEANUP_PENDING
        return try {
            scheduler.cancelCard(cardId)
            if (cardDao.finishDeletion(cardId, current.reminderGeneration) == 1 ||
                cardDao.get(cardId) == null
            ) ReminderDeletionResult.DELETED else ReminderDeletionResult.CLEANUP_PENDING
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Study card reminder deletion remains pending for $cardId", failure)
            ReminderDeletionResult.CLEANUP_PENDING
        }
    }

    private suspend fun finishProjectDeletion(projectId: Long): ReminderDeletionResult {
        val scheduler = checkNotNull(cardScheduler) { "Study card scheduler is required" }
        val project = database.projectDao().getProjectForDeletion(projectId)
            ?: return ReminderDeletionResult.DELETED
        if (!project.deletionPending) return ReminderDeletionResult.CLEANUP_PENDING
        var allCanceled = true
        val tasks = database.projectTaskDao().getForProjectBounded(projectId, MAX_PROJECT_TASKS)
        for (task in tasks) {
            currentCoroutineContext().ensureActive()
            try {
                taskScheduler.cancel(task.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                allCanceled = false
                Log.w(TAG, "Project task reminder cleanup remains pending for ${task.id}", failure)
            }
        }
        val cards = database.studyReviewCardDao().getProjectCardsForDeletion(projectId, MAX_STUDY_REVIEW_CARDS)
        for (card in cards) {
            currentCoroutineContext().ensureActive()
            try {
                scheduler.cancelCard(card.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                allCanceled = false
                Log.w(TAG, "Project card reminder cleanup remains pending for ${card.id}", failure)
            }
        }
        if (!allCanceled) return ReminderDeletionResult.CLEANUP_PENDING
        return try {
            if (database.projectDao().finishDeletion(projectId) == 1 ||
                database.projectDao().getProjectForDeletion(projectId) == null
            ) ReminderDeletionResult.DELETED else ReminderDeletionResult.CLEANUP_PENDING
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Project deletion remains pending for $projectId", failure)
            ReminderDeletionResult.CLEANUP_PENDING
        }
    }

    suspend fun reconcilePendingDeletions() = withContext(Dispatchers.IO) {
        val scheduler = checkNotNull(cardScheduler) { "Study card scheduler is required" }
        val cursors = ReminderRecoveryCursorStore(database)
        var projectPages = 0
        try {
            while (projectPages < MAX_PROJECT_RECOVERY_PAGES) {
                currentCoroutineContext().ensureActive()
                val projects = cursors.nextLongPage(
                    key = ReminderRecoveryCursorKeys.PROJECT_DELETION,
                    limit = PROJECT_PAGE_SIZE,
                    load = database.projectDao()::getPendingDeletionsAfter,
                    idOf = { it },
                )
                if (projects.isEmpty()) break
                projectPages++
                for (projectId in projects) {
                    currentCoroutineContext().ensureActive()
                    try {
                        ProjectTaskReminderMutationGate.mutex.withLock {
                            QuickReviewCardReminderMutationGate.mutex.withLock {
                                withContext(NonCancellable) { finishProjectDeletion(projectId) }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Log.w(TAG, "Could not recover project deletion $projectId", failure)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Could not enumerate pending project deletion intents", failure)
        }

        var taskPages = 0
        try {
            while (taskPages < MAX_RECOVERY_PAGES) {
                currentCoroutineContext().ensureActive()
                val tasks = cursors.nextLongPage(
                    key = ReminderRecoveryCursorKeys.TASK_DELETION,
                    limit = PAGE_SIZE,
                    load = database.projectTaskDao()::getPendingDeletionAfter,
                    idOf = { it.id },
                )
                if (tasks.isEmpty()) break
                taskPages++
                for (task in tasks) {
                    currentCoroutineContext().ensureActive()
                    try {
                        ProjectTaskReminderMutationGate.mutex.withLock {
                            withContext(NonCancellable) { finishTaskDeletion(task.projectId, task.id) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Log.w(TAG, "Could not recover task deletion ${task.id}", failure)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Could not enumerate pending task deletion intents", failure)
        }

        var cardPages = 0
        try {
            while (cardPages < MAX_RECOVERY_PAGES) {
                currentCoroutineContext().ensureActive()
                val cards = cursors.nextStringPage(
                    key = ReminderRecoveryCursorKeys.CARD_DELETION,
                    limit = PAGE_SIZE,
                    load = database.studyReviewCardDao()::getPendingDeletionAfter,
                    idOf = { it.id },
                )
                if (cards.isEmpty()) break
                cardPages++
                for (card in cards) {
                    currentCoroutineContext().ensureActive()
                    try {
                        QuickReviewCardReminderMutationGate.mutex.withLock {
                            withContext(NonCancellable) { finishCardDeletion(card.id, scheduler) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Log.w(TAG, "Could not recover card deletion ${card.id}", failure)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Could not enumerate pending card deletion intents", failure)
        }
    }

    private companion object {
        const val TAG = "FusionReminder"
        const val PAGE_SIZE = 128
        const val MAX_RECOVERY_PAGES = 16
        const val PROJECT_PAGE_SIZE = 2
        const val MAX_PROJECT_RECOVERY_PAGES = 2
    }
}
