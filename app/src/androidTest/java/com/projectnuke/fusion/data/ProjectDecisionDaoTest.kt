package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectDecisionDaoTest {
    @Test
    fun `decision history persists replacement and cascades with its project`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "project-decisions-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val projectId = database.projectDao().insertProject(
                ProjectEntity(name = "decision project", createdAt = 1, updatedAt = 1),
            )
            val prior = ProjectDecisionEntity(
                id = "prior-${System.nanoTime()}", projectId = projectId, title = "Use INT8",
                rationale = "Smaller artifact", status = "ACTIVE", createdAt = 2, updatedAt = 2,
            )
            assertEquals(ProjectDecisionWriteResult.Inserted(prior.id), database.projectDecisionDao().insertDecision(prior))
            val next = prior.copy(
                id = "next-${System.nanoTime()}", title = "Use INT4", rationale = "Final validation passed",
                supersedesDecisionId = prior.id, createdAt = 3, updatedAt = 3,
            )
            assertEquals(ProjectDecisionWriteResult.Inserted(next.id), database.projectDecisionDao().insertDecision(next))
            assertEquals("SUPERSEDED", database.projectDecisionDao().get(prior.id, projectId)?.status)
            assertEquals(prior.id, database.projectDecisionDao().get(next.id, projectId)?.supersedesDecisionId)
            assertEquals(listOf(next.id, prior.id), database.projectDecisionDao().observeRecent(projectId, 32).first().map { it.id })
            database.close()

            database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(2, database.projectDecisionDao().getRecent(projectId, 32).size)
            database.projectDao().deleteProject(projectId)
            assertNull(database.projectDecisionDao().get(prior.id, projectId))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `replacement rejects inactive or foreign project decisions`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "project-decision-fence-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val first = database.projectDao().insertProject(ProjectEntity(name = "first", createdAt = 1, updatedAt = 1))
            val second = database.projectDao().insertProject(ProjectEntity(name = "second", createdAt = 1, updatedAt = 1))
            val prior = ProjectDecisionEntity("prior", first, "old", "", "ACTIVE", createdAt = 2, updatedAt = 2)
            database.projectDecisionDao().insert(prior)
            val foreignAttempt = database.projectDecisionDao().insertDecision(
                ProjectDecisionEntity("foreign", second, "new", "", "ACTIVE", supersedesDecisionId = prior.id, createdAt = 3, updatedAt = 3),
            )
            assertEquals(ProjectDecisionWriteResult.SupersededDecisionMissing, foreignAttempt)
            database.projectDecisionDao().markSuperseded(first, prior.id, 4)
            val inactiveAttempt = database.projectDecisionDao().insertDecision(
                ProjectDecisionEntity("inactive", first, "new", "", "ACTIVE", supersedesDecisionId = prior.id, createdAt = 5, updatedAt = 5),
            )
            assertEquals(ProjectDecisionWriteResult.SupersededDecisionInactive, inactiveAttempt)
            assertNull(database.projectDecisionDao().get("inactive", first))
            assertEquals(0, database.projectDecisionDao().count(second))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
