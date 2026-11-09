package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.tasks.ProjectTaskPolicy
import com.projectnuke.fusion.tasks.ProjectTaskRepository
import com.projectnuke.fusion.tasks.ProjectTaskState
import com.projectnuke.fusion.tasks.ProjectTaskDraft
import com.projectnuke.fusion.tasks.ProjectTaskWriteResult
import com.projectnuke.fusion.tasks.ProjectTaskOwner
import com.projectnuke.fusion.tasks.ProjectTaskReminderScheduler
import com.projectnuke.fusion.tasks.ReminderDeletionCoordinator
import com.projectnuke.fusion.tasks.ReminderDeletionResult
import com.projectnuke.fusion.tasks.reconcilePendingReminderNotificationCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectTaskDaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun addEditCompleteDeleteAndProjectDeletionAreOwnedByProject() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
        val conversationId = database.chatDao().insertConversation(ConversationEntity(title = "C", createdAt = 1, updatedAt = 1))
        val messageId = database.chatDao().insertMessage(MessageEntity(conversationId = conversationId, role = "assistant", content = "visible", createdAt = 2))
        database.projectDao().assignConversation(ProjectConversationEntity(conversationId, projectId, 1))
        val proposal = (ProjectTaskPolicy.prepare(
            ProjectTaskPolicy.fromAnswer(projectId, "P", "title\nnote", conversationId, messageId),
            "title", "note", 3,
        ) as com.projectnuke.fusion.tasks.ProjectTaskPreparation.Ready).proposal
        assertEquals(ProjectTaskWriteResult.Stored, ProjectTaskRepository(database).save(proposal, false))
        val taskId = database.projectTaskDao().observeForProject(projectId).first().single().id

        assertEquals(1, database.projectTaskDao().update(taskId, projectId, "edited", "changed note", 4))
        assertEquals(1, database.projectTaskDao().setState(taskId, projectId, ProjectTaskState.DONE.name, 5))
        assertEquals(ProjectTaskState.DONE.name, database.projectTaskDao().get(taskId, projectId)?.state)
        database.chatDao().deleteConversation(conversationId)
        assertEquals("changed note", database.projectTaskDao().get(taskId, projectId)?.note)
        assertEquals(1, database.projectTaskDao().beginDeletion(taskId, projectId))
        val deleteGeneration = database.projectTaskDao().getById(taskId)!!.reminderGeneration
        assertEquals(1, database.projectTaskDao().finishDeletion(taskId, projectId, deleteGeneration))
        assertNull(database.projectTaskDao().get(taskId, projectId))

        val manual = ProjectTaskEntity(projectId = projectId, title = "manual", note = "", createdAt = 6, updatedAt = 6)
        database.projectTaskDao().insert(manual)
        database.projectDao().deleteProject(projectId)
        assertTrue(database.projectTaskDao().observeForProject(projectId).first().isEmpty())
    }

    @Test
    fun wrongProjectSourceAndDeletedSourceFailClosed() = runBlocking {
        val projectA = database.projectDao().insertProject(ProjectEntity(name = "A", createdAt = 1, updatedAt = 1))
        val projectB = database.projectDao().insertProject(ProjectEntity(name = "B", createdAt = 1, updatedAt = 1))
        val conversationId = database.chatDao().insertConversation(ConversationEntity(title = "C", createdAt = 1, updatedAt = 1))
        val messageId = database.chatDao().insertMessage(MessageEntity(conversationId = conversationId, role = "assistant", content = "visible", createdAt = 2))
        database.projectDao().assignConversation(ProjectConversationEntity(conversationId, projectA, 1))
        val wrongProject = (ProjectTaskPolicy.prepare(
            ProjectTaskPolicy.fromAnswer(projectB, "B", "task", conversationId, messageId), "task", "", 3,
        ) as com.projectnuke.fusion.tasks.ProjectTaskPreparation.Ready).proposal
        assertEquals(ProjectTaskWriteResult.ProjectUnavailable, ProjectTaskRepository(database).save(wrongProject, false))
        database.chatDao().deleteConversation(conversationId)
        assertEquals(ProjectTaskWriteResult.SourceUnavailable, ProjectTaskRepository(database).save(wrongProject.copy(projectId = projectA), false))
    }

    @Test
    fun durableTaskSurvivesDatabaseCloseAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "fusion-task-roundtrip-${System.currentTimeMillis()}.db"
        try {
            val first = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val projectId = first.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
            val proposal = (ProjectTaskPolicy.prepare(
                ProjectTaskDraft(projectId, "P", "review", "note"), "review", "note", 3,
            ) as com.projectnuke.fusion.tasks.ProjectTaskPreparation.Ready).proposal
            assertEquals(ProjectTaskWriteResult.Stored, ProjectTaskRepository(first).save(proposal, false))
            val taskId = first.projectTaskDao().observeForProject(projectId).first().single().id
            val reminderAt = System.currentTimeMillis() + 60_000
            assertEquals(ReminderUpdateResult.Updated, first.projectTaskDao().updateReminderBounded(taskId, projectId, reminderAt))
            first.close()

            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                val task = reopened.projectTaskDao().observeForProject(projectId).first().single()
                assertEquals("review", task.title)
                assertEquals("note", task.note)
                assertEquals(ProjectTaskState.OPEN.name, task.state)
                assertEquals(reminderAt, task.reminderAt)
                assertEquals(true, task.reminderEnabled)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun reminderMetadataReplacesCancelsAndIsRemovedWithItsTask() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "Reminder", createdAt = 1, updatedAt = 1))
        val now = System.currentTimeMillis()
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(projectId = projectId, title = "review", note = "", createdAt = now, updatedAt = now),
        )
        assertEquals(ReminderUpdateResult.Updated, database.projectTaskDao().updateReminderBounded(taskId, projectId, now + 60_000))
        val firstGeneration = database.projectTaskDao().get(taskId, projectId)!!.reminderGeneration
        assertEquals(ReminderUpdateResult.Updated, database.projectTaskDao().updateReminderBounded(taskId, projectId, now + 120_000))
        val secondGeneration = database.projectTaskDao().get(taskId, projectId)!!.reminderGeneration
        assertEquals(now + 120_000, database.projectTaskDao().get(taskId, projectId)?.reminderAt)
        assertEquals(0, database.projectTaskDao().consumeReminder(taskId, now + 60_000, firstGeneration))
        assertEquals(1, database.projectTaskDao().consumeReminder(taskId, now + 120_000, secondGeneration))
        assertEquals(false, database.projectTaskDao().get(taskId, projectId)?.reminderEnabled)
        assertEquals(ReminderUpdateResult.Updated, database.projectTaskDao().updateReminderBounded(taskId, projectId, now + 180_000))
        assertEquals(1, database.projectTaskDao().updateReminder(taskId, projectId, null, false))
        assertEquals(0, database.projectTaskDao().countEnabledReminders())
        assertEquals(1, database.projectTaskDao().beginDeletion(taskId, projectId))
        val deleteGeneration = database.projectTaskDao().getById(taskId)!!.reminderGeneration
        assertEquals(1, database.projectTaskDao().finishDeletion(taskId, projectId, deleteGeneration))
        assertNull(database.projectTaskDao().getById(taskId))
    }

    @Test
    fun completingATaskDisablesItsPersistedReminder() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "Reminder completion", createdAt = 1, updatedAt = 1))
        val now = System.currentTimeMillis()
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(projectId = projectId, title = "review", note = "", createdAt = now, updatedAt = now),
        )
        assertEquals(ReminderUpdateResult.Updated, database.projectTaskDao().updateReminderBounded(taskId, projectId, now + 60_000))
        assertEquals(1, database.projectTaskDao().setState(taskId, projectId, ProjectTaskState.DONE.name, now + 1))
        assertEquals(false, database.projectTaskDao().get(taskId, projectId)?.reminderEnabled)
        assertEquals(0, database.projectTaskDao().countEnabledReminders())
    }

    @Test
    fun getOverdueRemindersReturnsOnlyEnabledOverdueOpenTasks() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "Overdue test", createdAt = 1, updatedAt = 1))
        val now = 1_000_000L
        val overdueId = database.projectTaskDao().insert(
            ProjectTaskEntity(projectId = projectId, title = "overdue open", note = "", createdAt = 1, updatedAt = 1, reminderAt = 500L, reminderEnabled = true, state = "OPEN"),
        )
        database.projectTaskDao().insert(
            ProjectTaskEntity(projectId = projectId, title = "future", note = "", createdAt = 1, updatedAt = 1, reminderAt = 2_000_000L, reminderEnabled = true, state = "OPEN"),
        )
        val doneId = database.projectTaskDao().insert(
            ProjectTaskEntity(projectId = projectId, title = "done overdue", note = "", createdAt = 1, updatedAt = 1, reminderAt = 300L, reminderEnabled = true, state = "DONE"),
        )
        database.projectTaskDao().insert(
            ProjectTaskEntity(projectId = projectId, title = "disabled overdue", note = "", createdAt = 1, updatedAt = 1, reminderAt = 200L, reminderEnabled = false, state = "OPEN"),
        )

        val overdue = database.projectTaskDao().getOverdueReminders(now, MAX_ENABLED_TASK_REMINDERS)
        assertEquals(1, overdue.size)
        assertEquals(overdueId, overdue.single().id)
    }

    @Test
    fun consumeReminderIsAtomicCompareAndConsume() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "Race test", createdAt = 1, updatedAt = 1))
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(projectId = projectId, title = "race", note = "", createdAt = 1, updatedAt = 1, reminderAt = 500L, reminderEnabled = true, state = "OPEN"),
        )
        assertEquals(1, database.projectTaskDao().consumeReminder(taskId, 500L, 0L))
        assertEquals(0, database.projectTaskDao().consumeReminder(taskId, 500L, 0L))
        assertEquals(false, database.projectTaskDao().getById(taskId)?.reminderEnabled)
    }

    @Test
    fun consumedReminderNotificationIsDismissedWhenTaskCompletes() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(
                projectId = projectId,
                title = "already reminded",
                note = "",
                createdAt = 1,
                updatedAt = 1,
                state = ProjectTaskState.OPEN.name,
                reminderAt = 500L,
                reminderEnabled = false, // Delivery already consumed the durable generation.
                reminderGeneration = 1L,
            ),
        )
        val scheduler = RecordingTaskReminderScheduler().also { it.visibleNotifications += taskId }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = ProjectTaskOwner(database, projectId, scope, scheduler)
        try {
            owner.completeAndAwait(taskId, completed = true)
            assertEquals(ProjectTaskState.DONE.name, database.projectTaskDao().get(taskId, projectId)?.state)
            assertEquals(1, scheduler.cancelCalls)
            assertTrue(scheduler.visibleNotifications.isEmpty())
        } finally {
            owner.close()
            scope.cancel()
        }
    }

    @Test
    fun notificationCancellationFailureDoesNotUndoDurableTaskCompletionAndIsReported() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(projectId = projectId, title = "task", note = "", createdAt = 1, updatedAt = 1),
        )
        val scheduler = RecordingTaskReminderScheduler(throwOnCancel = true).also { it.visibleNotifications += taskId }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = ProjectTaskOwner(database, projectId, scope, scheduler)
        try {
            owner.completeAndAwait(taskId, completed = true)
            assertEquals(ProjectTaskState.DONE.name, database.projectTaskDao().get(taskId, projectId)?.state)
            assertEquals(1, scheduler.cancelCalls)
            assertTrue(scheduler.visibleNotifications.contains(taskId))
            assertTrue(owner.state.value.writeError.orEmpty().contains("알림을 지우지 못했습니다"))
            assertTrue(database.projectTaskDao().getById(taskId)?.notificationCleanupPending == true)
            val recoveryScheduler = RecordingTaskReminderScheduler().also { it.visibleNotifications += taskId }
            reconcilePendingReminderNotificationCleanup(
                database, recoveryScheduler, RecordingCardReminderScheduler(),
            )
            assertTrue(recoveryScheduler.visibleNotifications.isEmpty())
            assertEquals(false, database.projectTaskDao().getById(taskId)?.notificationCleanupPending)
        } finally {
            owner.close()
            scope.cancel()
        }
    }

    @Test
    fun deletingConsumedReminderClearsVisibleNotificationBeforeRemovingTask() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(
                projectId = projectId, title = "delete", note = "", createdAt = 1, updatedAt = 1,
                reminderAt = 5L, reminderEnabled = false, reminderGeneration = 1L,
            ),
        )
        val scheduler = RecordingTaskReminderScheduler().also { it.visibleNotifications += taskId }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = ProjectTaskOwner(database, projectId, scope, scheduler)
        try {
            owner.deleteAndAwait(taskId)
            assertNull(database.projectTaskDao().getById(taskId))
            assertTrue(scheduler.visibleNotifications.isEmpty())
        } finally {
            owner.close()
            scope.cancel()
        }
    }

    @Test
    fun failedNotificationCancellationLeavesARecoverableTaskDeletionIntent() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(projectId = projectId, title = "delete", note = "", createdAt = 1, updatedAt = 1),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = ProjectTaskOwner(database, projectId, scope, RecordingTaskReminderScheduler(throwOnCancel = true))
        try {
            owner.deleteAndAwait(taskId)
            val pending = database.projectTaskDao().getById(taskId)
            assertEquals(true, pending?.deletionPending)
            assertEquals(false, pending?.reminderEnabled)
            assertTrue(owner.state.value.writeError.orEmpty().contains("삭제 요청을 저장했습니다"))

            val recoveryScheduler = RecordingTaskReminderScheduler().also { it.visibleNotifications += taskId }
            reconcilePendingReminderNotificationCleanup(database, recoveryScheduler, RecordingCardReminderScheduler())
            assertNull(database.projectTaskDao().getById(taskId))
            assertTrue(recoveryScheduler.visibleNotifications.isEmpty())
        } finally {
            owner.close()
            scope.cancel()
        }
    }

    @Test
    fun cardCleanupRunsWhenTaskCleanupFails() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(
                projectId = projectId, title = "task", note = "", createdAt = 1, updatedAt = 1,
                notificationCleanupPending = true,
            ),
        )
        val cardId = "independent-card-cleanup"
        database.studyReviewCardDao().insert(
            StudyReviewCardEntity(
                id = cardId, scope = "PERSONAL", projectId = null, front = "f", back = "b",
                sourceKind = "USER_TEXT", sourceConversationId = null, sourceMessageId = null,
                sourceSavedKnowledgeId = null, sourceProjectId = null, sourceProjectFileId = null,
                dueAt = 1, createdAt = 1, updatedAt = 1, notificationCleanupPending = true,
            ),
        )
        val taskScheduler = RecordingTaskReminderScheduler(throwOnCancel = true)
        val cardScheduler = RecordingCardReminderScheduler().also { it.visibleNotifications += cardId }

        reconcilePendingReminderNotificationCleanup(database, taskScheduler, cardScheduler)

        assertTrue(database.projectTaskDao().getById(taskId)?.notificationCleanupPending == true)
        assertEquals(false, database.studyReviewCardDao().get(cardId)?.notificationCleanupPending)
        assertTrue(cardScheduler.visibleNotifications.isEmpty())
    }

    @Test
    fun taskDeleteIntentSurvivesRoomDeleteFailureAndIsRecovered() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(
                projectId = projectId, title = "task", note = "", createdAt = 1, updatedAt = 1,
                reminderAt = 500L, reminderEnabled = true,
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_task_delete BEFORE DELETE ON project_tasks BEGIN SELECT RAISE(ABORT, 'injected task delete failure'); END",
        )
        val scheduler = RecordingTaskReminderScheduler().also { it.visibleNotifications += taskId }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = ProjectTaskOwner(database, projectId, scope, scheduler)
        try {
            owner.deleteAndAwait(taskId)
            val pending = database.projectTaskDao().getById(taskId)
            assertEquals(true, pending?.deletionPending)
            assertEquals(false, pending?.reminderEnabled)
            assertTrue(scheduler.visibleNotifications.isEmpty())

            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_task_delete")
            reconcilePendingReminderNotificationCleanup(database, scheduler, RecordingCardReminderScheduler())
            assertNull(database.projectTaskDao().getById(taskId))
            assertTrue(scheduler.visibleNotifications.isEmpty())
        } finally {
            owner.close()
            scope.cancel()
        }
    }

    @Test
    fun taskDeletionIntentIsRecoverableAfterDatabaseCloseAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "task-delete-recovery-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        var opened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val projectId = opened.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
            val taskId = opened.projectTaskDao().insert(
                ProjectTaskEntity(
                    projectId = projectId, title = "task", note = "", createdAt = 1, updatedAt = 1,
                    reminderAt = 500L, reminderEnabled = true,
                ),
            )
            opened.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_task_delete BEFORE DELETE ON project_tasks BEGIN SELECT RAISE(ABORT, 'injected task delete failure'); END",
            )
            val initialScheduler = RecordingTaskReminderScheduler().also { it.visibleNotifications += taskId }
            assertEquals(
                ReminderDeletionResult.CLEANUP_PENDING,
                ReminderDeletionCoordinator(opened, initialScheduler).deleteTask(projectId, taskId),
            )
            assertEquals(true, opened.projectTaskDao().getById(taskId)?.deletionPending)
            opened.close()

            opened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            opened.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_task_delete")
            val recoveryScheduler = RecordingTaskReminderScheduler().also { it.visibleNotifications += taskId }
            reconcilePendingReminderNotificationCleanup(opened, recoveryScheduler, RecordingCardReminderScheduler())
            assertNull(opened.projectTaskDao().getById(taskId))
            assertTrue(recoveryScheduler.visibleNotifications.isEmpty())
            assertTrue(recoveryScheduler.cancelCalls > 0)
        } finally {
            opened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun pendingNotificationCleanupSurvivesDatabaseReopenAndIsReconciled() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "reminder-cleanup-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        var opened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val projectId = opened.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
            val taskId = opened.projectTaskDao().insert(
                ProjectTaskEntity(projectId = projectId, title = "done", note = "", createdAt = 1, updatedAt = 1, reminderGeneration = 1),
            )
            assertEquals(1, opened.projectTaskDao().setState(taskId, projectId, ProjectTaskState.DONE.name, 2))
            opened.studyReviewCardDao().insert(
                StudyReviewCardEntity(
                    id = "reviewed-after-reopen", scope = "PERSONAL", projectId = null,
                    front = "f", back = "b", sourceKind = "USER_TEXT", sourceConversationId = null,
                    sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null,
                    sourceProjectFileId = null, dueAt = 0, createdAt = 1, updatedAt = 1,
                ),
            )
            assertEquals(1, opened.studyReviewCardDao().recordReview("reviewed-after-reopen", 1, "HARD", 1_000, 2))
            opened.close()

            opened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val taskScheduler = RecordingTaskReminderScheduler().also { it.visibleNotifications += taskId }
            val cardScheduler = RecordingCardReminderScheduler().also { it.visibleNotifications += "reviewed-after-reopen" }
            reconcilePendingReminderNotificationCleanup(opened, taskScheduler, cardScheduler)

            assertEquals(false, opened.projectTaskDao().getById(taskId)?.notificationCleanupPending)
            assertEquals(false, opened.studyReviewCardDao().get("reviewed-after-reopen")?.notificationCleanupPending)
            assertTrue(taskScheduler.visibleNotifications.isEmpty())
            assertTrue(cardScheduler.visibleNotifications.isEmpty())
        } finally {
            opened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun cardCleanupRunsWhenThereIsNoTaskCleanupDebt() = runBlocking {
        val now = System.currentTimeMillis()
        val cardId = "card-without-task-cleanup"
        database.studyReviewCardDao().insert(
            StudyReviewCardEntity(
                id = cardId, scope = "PERSONAL", projectId = null,
                front = "f", back = "b", sourceKind = "USER_TEXT", sourceConversationId = null,
                sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null,
                sourceProjectFileId = null, dueAt = now, createdAt = now, updatedAt = now,
                notificationCleanupPending = true,
            ),
        )
        val taskScheduler = RecordingTaskReminderScheduler()
        val cardScheduler = RecordingCardReminderScheduler().also { it.visibleNotifications += cardId }

        // This invokes the production reconciliation function against actual Room.
        reconcilePendingReminderNotificationCleanup(database, taskScheduler, cardScheduler)

        assertEquals(0, taskScheduler.cancelCalls)
        assertTrue(cardScheduler.visibleNotifications.isEmpty())
        assertEquals(false, database.studyReviewCardDao().get(cardId)?.notificationCleanupPending)
    }

    @Test
    fun cleanupKeysetPassesPastFailingHeadAndMoreThanTwoPages() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
        val ids = (1..1_025).map { index ->
            database.projectTaskDao().insert(
                ProjectTaskEntity(
                    projectId = projectId, title = "task-$index", note = "", createdAt = index.toLong(),
                    updatedAt = index.toLong(), notificationCleanupPending = true,
                ),
            )
        }
        val cardId = "after-task-backlog"
        database.studyReviewCardDao().insert(
            StudyReviewCardEntity(
                id = cardId, scope = "PERSONAL", projectId = null, front = "f", back = "b",
                sourceKind = "USER_TEXT", sourceConversationId = null, sourceMessageId = null,
                sourceSavedKnowledgeId = null, sourceProjectId = null, sourceProjectFileId = null,
                dueAt = 1, createdAt = 1, updatedAt = 1, notificationCleanupPending = true,
            ),
        )
        val taskScheduler = RecordingTaskReminderScheduler(failingIds = setOf(ids.first()))
        val cardScheduler = RecordingCardReminderScheduler().also { it.visibleNotifications += cardId }

        reconcilePendingReminderNotificationCleanup(database, taskScheduler, cardScheduler)

        assertEquals(true, database.projectTaskDao().getById(ids.first())?.notificationCleanupPending)
        assertEquals(false, database.projectTaskDao().getById(ids.last())?.notificationCleanupPending)
        assertEquals(1, database.projectTaskDao().getPendingNotificationCleanupAfter(0L, 2_000).size)
        assertEquals(false, database.studyReviewCardDao().get(cardId)?.notificationCleanupPending)
        assertTrue(cardScheduler.visibleNotifications.isEmpty())
    }

    @Test
    fun projectDeleteIntentRecoversAfterCascadeFailureWithoutRestoringReminders() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "delete project", createdAt = 1, updatedAt = 1))
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(
                projectId = projectId, title = "task", note = "", createdAt = 1, updatedAt = 1,
                reminderAt = 500L, reminderEnabled = true,
            ),
        )
        val cardId = "project-delete-card"
        database.studyReviewCardDao().insert(
            StudyReviewCardEntity(
                id = cardId, scope = "PROJECT", projectId = projectId, front = "f", back = "b",
                sourceKind = "USER_TEXT", sourceConversationId = null, sourceMessageId = null,
                sourceSavedKnowledgeId = null, sourceProjectId = null, sourceProjectFileId = null,
                dueAt = 1, createdAt = 1, updatedAt = 1, reminderAt = 600L, reminderEnabled = true,
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_project_delete BEFORE DELETE ON projects BEGIN SELECT RAISE(ABORT, 'injected project delete failure'); END",
        )
        val taskScheduler = RecordingTaskReminderScheduler().also { it.visibleNotifications += taskId }
        val cardScheduler = RecordingCardReminderScheduler().also { it.visibleNotifications += cardId }
        val deletion = ReminderDeletionCoordinator(database, taskScheduler, cardScheduler)

        assertEquals(ReminderDeletionResult.CLEANUP_PENDING, deletion.deleteProject(projectId))
        assertEquals(true, database.projectDao().getProjectForDeletion(projectId)?.deletionPending)
        assertEquals(true, database.projectTaskDao().getById(taskId)?.deletionPending)
        assertEquals(false, database.projectTaskDao().getById(taskId)?.reminderEnabled)
        assertEquals(true, database.studyReviewCardDao().get(cardId)?.deletionPending)
        assertTrue(taskScheduler.visibleNotifications.isEmpty())
        assertTrue(cardScheduler.visibleNotifications.isEmpty())

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_project_delete")
        reconcilePendingReminderNotificationCleanup(database, taskScheduler, cardScheduler)
        assertNull(database.projectDao().getProjectForDeletion(projectId))
        assertNull(database.projectTaskDao().getById(taskId))
        assertNull(database.studyReviewCardDao().get(cardId))
    }

    private class RecordingTaskReminderScheduler(
        private val throwOnCancel: Boolean = false,
        private val failingIds: Set<Long> = emptySet(),
    ) : ProjectTaskReminderScheduler {
        val visibleNotifications = mutableSetOf<Long>()
        var cancelCalls = 0
        override fun schedule(taskId: Long, at: Long, generation: Long) = Unit
        override fun cancel(taskId: Long) {
            cancelCalls++
            if (throwOnCancel || taskId in failingIds) error("injected notification cancellation failure")
            visibleNotifications.remove(taskId)
        }
    }

    private class RecordingCardReminderScheduler : com.projectnuke.fusion.study.StudyReviewCardReminderScheduler {
        val visibleNotifications = mutableSetOf<String>()
        override fun scheduleCard(cardId: String, at: Long, generation: Long) = Unit
        override fun cancelCard(cardId: String) { visibleNotifications.remove(cardId) }
    }

    @Test
    fun oldGenerationCannotConsumeRescheduledReminderAtTheSameTime() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "Reminder generation", createdAt = 1, updatedAt = 1))
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(projectId = projectId, title = "same time", note = "", createdAt = 1, updatedAt = 1),
        )
        assertEquals(
            ReminderUpdateResult.Updated,
            database.projectTaskDao().updateReminderBounded(taskId, projectId, 500L),
        )
        val oldGeneration = database.projectTaskDao().get(taskId, projectId)!!.reminderGeneration
        assertEquals(1, database.projectTaskDao().updateReminder(taskId, projectId, 500L, true))
        val newGeneration = database.projectTaskDao().get(taskId, projectId)!!.reminderGeneration

        assertTrue(newGeneration > oldGeneration)
        assertEquals(0, database.projectTaskDao().consumeReminder(taskId, 500L, oldGeneration))
        assertEquals(1, database.projectTaskDao().consumeReminder(taskId, 500L, newGeneration))
        assertEquals(false, database.projectTaskDao().getById(taskId)?.reminderEnabled)
    }
}
