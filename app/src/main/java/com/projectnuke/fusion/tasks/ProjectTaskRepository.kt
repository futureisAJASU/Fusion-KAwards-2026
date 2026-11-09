package com.projectnuke.fusion.tasks

import androidx.room.withTransaction
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.ProjectTaskInsertResult
import com.projectnuke.fusion.data.ReminderUpdateResult as DatabaseReminderUpdateResult
import com.projectnuke.fusion.projects.ProjectWorkPlanRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

sealed interface ProjectTaskWriteResult {
    data object Stored : ProjectTaskWriteResult
    data object ProjectUnavailable : ProjectTaskWriteResult
    data object SourceUnavailable : ProjectTaskWriteResult
    data object AccessUnavailable : ProjectTaskWriteResult
    data object SensitiveContent : ProjectTaskWriteResult
    data object Invalid : ProjectTaskWriteResult
    data object CapacityReached : ProjectTaskWriteResult
    data object Failed : ProjectTaskWriteResult
}

fun interface ProjectTaskWriter {
    suspend fun save(proposal: ProjectTaskProposal, archiveAccessIncluded: Boolean): ProjectTaskWriteResult
}

class ProjectTaskRepository(private val database: AppDatabase) : ProjectTaskWriter {
    override suspend fun save(
        proposal: ProjectTaskProposal,
        archiveAccessIncluded: Boolean,
    ): ProjectTaskWriteResult = withContext(Dispatchers.IO) {
        try {
            // Source authority is validated inside the SAME Room transaction as
            // the durable INSERT. Validating outside left a window in which the
            // conversation could be deleted, archived, or reassigned to another
            // project between the check and the write, letting a stale
            // provenance record claim membership it no longer holds. The
            // capacity check inside insertBounded stays atomic as before.
            val conversationId = proposal.sourceConversationId
            val messageId = proposal.sourceMessageId
            // Validation that does not read durable source state stays outside
            // the transaction; only authority checks move inside it (below).
            when (
                ProjectTaskPolicy.prepare(
                    ProjectTaskDraft(
                        proposal.projectId, "", proposal.title, proposal.note,
                        proposal.sourceConversationId, proposal.sourceMessageId,
                    ),
                    proposal.title,
                    proposal.note,
                    proposal.createdAt,
                )
            ) {
                ProjectTaskPreparation.Invalid -> return@withContext ProjectTaskWriteResult.Invalid
                ProjectTaskPreparation.SensitiveContent -> return@withContext ProjectTaskWriteResult.SensitiveContent
                is ProjectTaskPreparation.Ready -> Unit
            }
            database.withTransaction {
                if (database.projectDao().getProjectById(proposal.projectId) == null) {
                    return@withTransaction ProjectTaskWriteResult.ProjectUnavailable
                }
                if (conversationId != null && messageId != null) {
                    val conversation = database.chatDao().getConversationById(conversationId)
                        ?: return@withTransaction ProjectTaskWriteResult.SourceUnavailable
                    if (conversation.isArchived && !archiveAccessIncluded) {
                        return@withTransaction ProjectTaskWriteResult.AccessUnavailable
                    }
                    if (database.projectDao().getProjectIdForConversation(conversationId) != proposal.projectId) {
                        return@withTransaction ProjectTaskWriteResult.ProjectUnavailable
                    }
                    val answer = database.savedKnowledgeDao().getSourceAnswer(messageId, conversationId)
                        ?.takeIf { it.role == "assistant" }
                        ?: return@withTransaction ProjectTaskWriteResult.SourceUnavailable
                    if (answer.messageId <= 0L) return@withTransaction ProjectTaskWriteResult.SourceUnavailable
                }
                when (database.projectTaskDao().insertBounded(ProjectTaskPolicy.entity(proposal))) {
                    is ProjectTaskInsertResult.Inserted -> ProjectTaskWriteResult.Stored
                    ProjectTaskInsertResult.CapacityReached -> ProjectTaskWriteResult.CapacityReached
                    ProjectTaskInsertResult.ProjectUnavailable -> ProjectTaskWriteResult.ProjectUnavailable
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProjectTaskWriteResult.Failed
        }
    }

    suspend fun edit(projectId: Long, taskId: Long, title: String, note: String): Boolean = withContext(Dispatchers.IO) {
        val prepared = ProjectTaskPolicy.prepare(
            ProjectTaskDraft(projectId, "", title, note), title, note, System.currentTimeMillis(),
        )
        if (prepared !is ProjectTaskPreparation.Ready || database.projectTaskDao().get(taskId, projectId) == null) return@withContext false
        database.projectTaskDao().update(taskId, projectId, prepared.proposal.title, prepared.proposal.note, prepared.proposal.createdAt) == 1
    }

    suspend fun setState(projectId: Long, taskId: Long, state: ProjectTaskState): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val changed = database.projectTaskDao().setState(taskId, projectId, state.name, System.currentTimeMillis()) == 1
            if (changed && state == ProjectTaskState.DONE) {
                ProjectWorkPlanRepository(database).taskCompletedInTransaction(taskId)
            }
            changed
        }
    }

}

data class ProjectTaskListState(
    val tasks: List<ProjectTaskEntity> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val writeError: String? = null,
)

/** Project-scoped state and write owner used by the compact Project section. */
class ProjectTaskOwner(
    private val database: AppDatabase,
    private val projectId: Long,
    private val scope: CoroutineScope,
    private val reminderScheduler: ProjectTaskReminderScheduler,
) {
    private val repository = ProjectTaskRepository(database)
    private val reminderMutationMutex = ProjectTaskReminderMutationGate.mutex
    private val mutableState = MutableStateFlow(ProjectTaskListState())
    val state = mutableState.asStateFlow()
    private val observeJob = scope.launch {
        try {
            database.projectTaskDao().observeForProject(projectId, MAX_PROJECT_TASKS).collect { tasks ->
                mutableState.value = mutableState.value.copy(tasks = tasks.take(MAX_PROJECT_TASKS), loading = false, failed = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = ProjectTaskListState(loading = false, failed = true)
        }
    }

    fun addManual(title: String, note: String, onResult: (Boolean) -> Unit = {}) {
        val draft = ProjectTaskDraft(projectId, "", title, note)
        val prepared = ProjectTaskPolicy.prepare(draft, title, note, System.currentTimeMillis())
        if (prepared !is ProjectTaskPreparation.Ready) {
            mutableState.value = mutableState.value.copy(writeError = "제목을 확인하고 민감한 값을 제거해 주세요.")
            onResult(false)
            return
        }
        scope.launch {
            try {
                val result = repository.save(prepared.proposal, archiveAccessIncluded = false)
                val stored = result == ProjectTaskWriteResult.Stored
                mutableState.value = mutableState.value.copy(writeError = result.messageOrNull())
                onResult(stored)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(writeError = "할 일을 저장하지 못했습니다.")
                onResult(false)
            }
        }
    }

    fun edit(task: ProjectTaskEntity, title: String, note: String, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            val success = try { repository.edit(projectId, task.id, title, note) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            mutableState.value = mutableState.value.copy(writeError = if (success) null else "할 일을 저장하지 못했습니다.")
            onResult(success)
        }
    }

    fun complete(taskId: Long, completed: Boolean) {
        scope.launch {
            completeAndAwait(taskId, completed)
        }
    }

    /** Shared owner path; also gives Room instrumentation a deterministic completion boundary. */
    internal suspend fun completeAndAwait(taskId: Long, completed: Boolean) {
        reminderMutationMutex.withLock {
            val success = try {
                repository.setState(projectId, taskId, if (completed) ProjectTaskState.DONE else ProjectTaskState.OPEN)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            var reminderCleanupFailed = false
            if (success && completed) {
                // The row is already durably DONE and its generation has
                // advanced. Always clear the stable notification ID: a
                // consumed reminder has reminderEnabled=false but can still
                // be visible in NotificationManager.
                reminderCleanupFailed = withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        reminderScheduler.cancel(taskId)
                        val current = database.projectTaskDao().getById(taskId)
                        if (current?.notificationCleanupPending == true) {
                            database.projectTaskDao().clearNotificationCleanupPending(taskId, current.reminderGeneration) != 1
                        } else false
                    }
                    catch (_: Exception) { true }
                }
            }
            val error = when {
                !success -> "할 일 상태를 저장하지 못했습니다."
                reminderCleanupFailed -> "할 일은 완료했지만 알림을 지우지 못했습니다. 알림을 직접 닫아 주세요."
                else -> null
            }
            mutableState.value = mutableState.value.copy(writeError = error)
        }
    }

    fun scheduleReminder(taskId: Long, at: Long, notificationsAllowed: Boolean) {
        scope.launch {
            reminderMutationMutex.withLock {
                if (!notificationsAllowed) {
                    mutableState.value = mutableState.value.copy(writeError = ReminderUpdateResult.PermissionUnavailable.messageOrNull())
                    return@withLock
                }
                val task = try {
                    database.projectTaskDao().get(taskId, projectId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(writeError = ReminderUpdateResult.Failed.messageOrNull())
                    return@withLock
                }
                val result = if (task == null) {
                    ReminderUpdateResult.UnknownTask
                } else if (task.state != ProjectTaskState.OPEN.name) {
                    ReminderUpdateResult.TaskComplete
                } else {
                    val decision = ProjectTaskReminderPolicy.scheduleDecision(
                        at, System.currentTimeMillis(), task.reminderEnabled,
                        try { database.projectTaskDao().countEnabledReminders() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { -1 },
                    )
                    when (decision) {
                        ReminderScheduleDecision.InvalidTime -> ReminderUpdateResult.InvalidTime
                        ReminderScheduleDecision.TooFarAhead -> ReminderUpdateResult.TooFarAhead
                        ReminderScheduleDecision.CapacityReached -> ReminderUpdateResult.CapacityReached
                        ReminderScheduleDecision.Allowed -> persistAndSchedule(task, at)
                    }
                }
                mutableState.value = mutableState.value.copy(writeError = result.messageOrNull())
            }
        }
    }

    private suspend fun persistAndSchedule(task: ProjectTaskEntity, at: Long): ReminderUpdateResult {
        return try {
            when (database.projectTaskDao().updateReminderBounded(task.id, projectId, at)) {
                DatabaseReminderUpdateResult.UnknownTask -> ReminderUpdateResult.UnknownTask
                DatabaseReminderUpdateResult.TaskComplete -> ReminderUpdateResult.TaskComplete
                DatabaseReminderUpdateResult.CapacityReached -> ReminderUpdateResult.CapacityReached
                DatabaseReminderUpdateResult.Updated -> {
                    try {
                        // Clear a notification from the previous reminder
                        // before installing the replacement alarm. Delivery
                        // uses the same mutation gate, so an old generation
                        // cannot publish after this cleanup and overwrite it.
                        reminderScheduler.cancel(task.id)
                        val updated = database.projectTaskDao().get(task.id, projectId)
                            ?: throw IllegalStateException("Task disappeared while scheduling a reminder")
                        reminderScheduler.schedule(task.id, at, updated.reminderGeneration)
                        if (updated.notificationCleanupPending &&
                            database.projectTaskDao().clearNotificationCleanupPending(task.id, updated.reminderGeneration) != 1
                        ) throw IllegalStateException("Task reminder cleanup could not be settled")
                        ReminderUpdateResult.Updated
                    } catch (cancelled: CancellationException) {
                        restoreAfterScheduleFailure(task)
                        throw cancelled
                    } catch (_: Exception) {
                        restoreAfterScheduleFailure(task)
                        ReminderUpdateResult.Failed
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReminderUpdateResult.Failed
        }
    }

    private suspend fun restoreAfterScheduleFailure(task: ProjectTaskEntity) {
        withContext(NonCancellable) {
            try {
                database.projectTaskDao().updateReminder(task.id, projectId, task.reminderAt, task.reminderEnabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Continue to cancel or restore the system alarm even if storage is unavailable.
            }
            try {
                if (task.reminderEnabled && task.reminderAt != null) {
                    val restored = database.projectTaskDao().get(task.id, projectId)
                    if (restored?.reminderEnabled == true && restored.reminderAt == task.reminderAt) {
                        reminderScheduler.cancel(task.id)
                        reminderScheduler.schedule(task.id, task.reminderAt, restored.reminderGeneration)
                    } else {
                        reminderScheduler.cancel(task.id)
                    }
                    val settled = database.projectTaskDao().getById(task.id)
                    if (settled?.notificationCleanupPending == true) {
                        database.projectTaskDao().clearNotificationCleanupPending(task.id, settled.reminderGeneration)
                    }
                } else {
                    reminderScheduler.cancel(task.id)
                    val settled = database.projectTaskDao().getById(task.id)
                    if (settled?.notificationCleanupPending == true) {
                        database.projectTaskDao().clearNotificationCleanupPending(task.id, settled.reminderGeneration)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // DB state is authoritative; receivers reject any stale alarm.
            }
        }
    }

    fun disableReminder(taskId: Long) {
        updateReminderState(taskId, clearTime = false)
    }

    fun cancelReminder(taskId: Long) {
        updateReminderState(taskId, clearTime = true)
    }

    private fun updateReminderState(taskId: Long, clearTime: Boolean) {
        scope.launch {
            reminderMutationMutex.withLock {
                val outcome = try {
                    val task = database.projectTaskDao().get(taskId, projectId)
                    if (task == null) {
                        ReminderUpdateResult.UnknownTask
                    } else {
                        val changed = database.projectTaskDao().updateReminder(
                            taskId, projectId, if (clearTime) null else task.reminderAt, false,
                        ) == 1
                        if (!changed) ReminderUpdateResult.UnknownTask else {
                            val cleaned = try {
                                reminderScheduler.cancel(taskId)
                                val updated = database.projectTaskDao().getById(taskId)
                                updated == null || !updated.notificationCleanupPending ||
                                    database.projectTaskDao().clearNotificationCleanupPending(taskId, updated.reminderGeneration) == 1
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            if (cleaned) ReminderUpdateResult.Updated
                            else ReminderUpdateResult.NotificationCleanupFailed
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ReminderUpdateResult.Failed
                }
                mutableState.value = mutableState.value.copy(writeError = outcome.messageOrNull())
            }
        }
    }

    fun notificationPermissionUnavailable() = reportReminderResult(ReminderUpdateResult.PermissionUnavailable)

    private fun reportReminderResult(result: ReminderUpdateResult) {
        mutableState.value = mutableState.value.copy(writeError = result.messageOrNull())
    }

    fun delete(taskId: Long) {
        scope.launch {
            deleteAndAwait(taskId)
        }
    }

    internal suspend fun deleteAndAwait(taskId: Long) {
        val result = ReminderDeletionCoordinator(database, reminderScheduler)
            .deleteTask(projectId, taskId)
        mutableState.value = mutableState.value.copy(writeError = when (result) {
            ReminderDeletionResult.DELETED -> null
            ReminderDeletionResult.NOT_FOUND -> "할 일을 삭제하지 못했습니다."
            ReminderDeletionResult.CLEANUP_PENDING -> "삭제 요청을 저장했습니다. 알림 정리가 끝나면 삭제가 완료됩니다."
            ReminderDeletionResult.FAILED -> "할 일을 삭제하지 못했습니다. 다시 시도해 주세요."
        })
    }

    fun close() = observeJob.cancel()

    private companion object { const val MAX_PROJECT_TASKS = com.projectnuke.fusion.data.MAX_PROJECT_TASKS }
}

private fun ProjectTaskWriteResult.messageOrNull(): String? = when (this) {
    ProjectTaskWriteResult.Stored -> null
    ProjectTaskWriteResult.ProjectUnavailable -> "프로젝트가 없어 할 일을 저장하지 못했습니다."
    ProjectTaskWriteResult.SourceUnavailable -> "원본 답변을 확인할 수 없어 저장하지 않았습니다."
    ProjectTaskWriteResult.AccessUnavailable -> "아카이브 접근이 만료되어 저장하지 않았습니다."
    ProjectTaskWriteResult.SensitiveContent -> "민감한 값이 포함되어 저장하지 않았습니다."
    ProjectTaskWriteResult.Invalid -> "제목과 내용을 확인해 주세요."
    ProjectTaskWriteResult.CapacityReached -> "프로젝트에 저장할 수 있는 할 일 수에 도달했습니다."
    ProjectTaskWriteResult.Failed -> "할 일을 저장하지 못했습니다. 다시 시도해 주세요."
}

private fun ReminderUpdateResult.messageOrNull(): String? = when (this) {
    ReminderUpdateResult.Updated -> null
    ReminderUpdateResult.UnknownTask -> "할 일을 찾을 수 없어 알림을 변경하지 않았습니다."
    ReminderUpdateResult.TaskComplete -> "완료한 할 일에는 알림을 설정할 수 없습니다."
    ReminderUpdateResult.PermissionUnavailable -> "알림 권한이 없어 예약하지 않았습니다. 설정에서 알림을 허용해 주세요."
    ReminderUpdateResult.InvalidTime -> "현재보다 늦은 시간을 선택해 주세요."
    ReminderUpdateResult.TooFarAhead -> "알림은 1년 이내의 시간으로 설정해 주세요."
    ReminderUpdateResult.CapacityReached -> "예약할 수 있는 알림 수에 도달했습니다."
    ReminderUpdateResult.Failed -> "알림을 저장하지 못했습니다. 다시 시도해 주세요."
    ReminderUpdateResult.NotificationCleanupFailed -> "알림 설정은 변경했지만 이전 알림을 지우지 못했습니다. 직접 닫아 주세요."
}
