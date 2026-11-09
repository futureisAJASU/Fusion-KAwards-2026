package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.study.StudyReviewCardReminderScheduler
import com.projectnuke.fusion.tasks.ProjectTaskReminderScheduler
import com.projectnuke.fusion.tasks.ReminderDeletionCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderRecoveryFairnessAndroidTest {
    @Test
    fun `later project deletion is reached on next bounded pass when first page keeps failing`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val taskIds = mutableListOf<Long>()
            val projectIds = (1..5).map { index ->
                val projectId = database.projectDao().insertProject(
                    ProjectEntity(name = "fairness-$index", createdAt = index.toLong(), updatedAt = index.toLong()),
                )
                taskIds += database.projectTaskDao().insert(
                    ProjectTaskEntity(
                        projectId = projectId,
                        title = "task-$index",
                        note = "",
                        createdAt = index.toLong(),
                        updatedAt = index.toLong(),
                    ),
                )
                assertNotNull(database.projectDao().getProjectForDeletion(projectId))
                projectId
            }

            projectIds.forEach { projectId ->
                database.projectDao().beginDeletion(projectId)
                database.projectTaskDao().beginProjectChildrenDeletion(projectId)
            }

            val failingTaskIds = taskIds.take(4).toSet()
            val scheduler = object : ProjectTaskReminderScheduler, StudyReviewCardReminderScheduler {
                override fun schedule(taskId: Long, at: Long, generation: Long) = Unit
                override fun cancel(taskId: Long) {
                    if (taskId in failingTaskIds) error("injected cancellation failure for $taskId")
                }
                override fun scheduleCard(cardId: String, at: Long, generation: Long) = Unit
                override fun cancelCard(cardId: String) = Unit
            }
            val coordinator = ReminderDeletionCoordinator(database, scheduler, scheduler)

            coordinator.reconcilePendingDeletions()
            assertNotNull(database.projectDao().getProjectForDeletion(projectIds.last()))

            coordinator.reconcilePendingDeletions()
            assertNull(database.projectDao().getProjectForDeletion(projectIds.last()))
            projectIds.take(4).forEach { assertNotNull(database.projectDao().getProjectForDeletion(it)) }
        } finally {
            database.close()
        }
    }
}
