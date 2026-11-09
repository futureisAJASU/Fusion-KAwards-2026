package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.study.StudyReviewCardReminderScheduler
import com.projectnuke.fusion.tasks.ProjectTaskReminderScheduler
import com.projectnuke.fusion.tasks.ReminderRecoveryCursorKeys
import com.projectnuke.fusion.tasks.reconcilePendingReminderNotificationCleanup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P10-01 Room-backed wrap regression: when the persisted recovery cursor is
 * parked at (or beyond) the last pending row, one reconciliation invocation
 * must still process that reachable debt instead of returning empty.
 *
 * Instrumentation only: COMPILED / NOT EXECUTED when no Android target exists.
 */
@RunWith(AndroidJUnit4::class)
class ReminderRecoveryCursorWrapAndroidTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `task deletion parked at last pending id is recovered in the same invocation`() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(projectId = projectId, title = "t", note = "", createdAt = 1, updatedAt = 1),
        )
        assertEquals(1, database.projectTaskDao().beginDeletion(taskId, projectId))
        database.reminderRecoveryCursorDao().put(
            ReminderRecoveryCursorEntity(ReminderRecoveryCursorKeys.TASK_DELETION, taskId.toString()),
        )
        val taskScheduler = RecordingTaskScheduler().also { it.visible += taskId }
        val cardScheduler = RecordingCardScheduler()

        reconcilePendingReminderNotificationCleanup(database, taskScheduler, cardScheduler)

        assertNull(database.projectTaskDao().getById(taskId))
        assertTrue(taskScheduler.visible.isEmpty())
    }

    @Test
    fun `card deletion parked at last pending id is recovered in the same invocation`() = runBlocking {
        val cardId = "wrap-card-deletion"
        database.studyReviewCardDao().insert(
            StudyReviewCardEntity(
                id = cardId, scope = "PERSONAL", projectId = null,
                front = "f", back = "b", sourceKind = "USER_TEXT", sourceConversationId = null,
                sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null,
                sourceProjectFileId = null, dueAt = 1, createdAt = 1, updatedAt = 1,
            ),
        )
        assertEquals(1, database.studyReviewCardDao().beginDeletion(cardId))
        database.reminderRecoveryCursorDao().put(
            ReminderRecoveryCursorEntity(ReminderRecoveryCursorKeys.CARD_DELETION, cardId),
        )

        reconcilePendingReminderNotificationCleanup(database, RecordingTaskScheduler(), RecordingCardScheduler())

        assertNull(database.studyReviewCardDao().get(cardId))
    }

    @Test
    fun `project deletion parked at last pending id is recovered in the same invocation`() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
        assertEquals(1, database.projectDao().beginDeletion(projectId))
        database.reminderRecoveryCursorDao().put(
            ReminderRecoveryCursorEntity(ReminderRecoveryCursorKeys.PROJECT_DELETION, projectId.toString()),
        )

        reconcilePendingReminderNotificationCleanup(database, RecordingTaskScheduler(), RecordingCardScheduler())

        assertNull(database.projectDao().getProjectForDeletion(projectId))
    }

    @Test
    fun `task notification cleanup parked at last pending id is cleared in the same invocation`() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "P", createdAt = 1, updatedAt = 1))
        val taskId = database.projectTaskDao().insert(
            ProjectTaskEntity(
                projectId = projectId, title = "t", note = "", createdAt = 1, updatedAt = 1,
                notificationCleanupPending = true,
            ),
        )
        database.reminderRecoveryCursorDao().put(
            ReminderRecoveryCursorEntity(ReminderRecoveryCursorKeys.TASK_NOTIFICATION_CLEANUP, taskId.toString()),
        )
        val taskScheduler = RecordingTaskScheduler().also { it.visible += taskId }

        reconcilePendingReminderNotificationCleanup(database, taskScheduler, RecordingCardScheduler())

        assertEquals(false, database.projectTaskDao().getById(taskId)?.notificationCleanupPending)
        assertTrue(taskScheduler.visible.isEmpty())
    }

    @Test
    fun `card notification cleanup parked at last pending id is cleared in the same invocation`() = runBlocking {
        val cardId = "wrap-card-cleanup"
        database.studyReviewCardDao().insert(
            StudyReviewCardEntity(
                id = cardId, scope = "PERSONAL", projectId = null,
                front = "f", back = "b", sourceKind = "USER_TEXT", sourceConversationId = null,
                sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null,
                sourceProjectFileId = null, dueAt = 1, createdAt = 1, updatedAt = 1,
                notificationCleanupPending = true,
            ),
        )
        database.reminderRecoveryCursorDao().put(
            ReminderRecoveryCursorEntity(ReminderRecoveryCursorKeys.CARD_NOTIFICATION_CLEANUP, cardId),
        )
        val cardScheduler = RecordingCardScheduler().also { it.visible += cardId }

        reconcilePendingReminderNotificationCleanup(database, RecordingTaskScheduler(), cardScheduler)

        assertEquals(false, database.studyReviewCardDao().get(cardId)?.notificationCleanupPending)
        assertTrue(cardScheduler.visible.isEmpty())
    }

    private class RecordingTaskScheduler : ProjectTaskReminderScheduler {
        val visible = mutableSetOf<Long>()
        override fun schedule(taskId: Long, at: Long, generation: Long) = Unit
        override fun cancel(taskId: Long) {
            visible.remove(taskId)
        }
    }

    private class RecordingCardScheduler : StudyReviewCardReminderScheduler {
        val visible = mutableSetOf<String>()
        override fun scheduleCard(cardId: String, at: Long, generation: Long) = Unit
        override fun cancelCard(cardId: String) {
            visible.remove(cardId)
        }
    }
}
