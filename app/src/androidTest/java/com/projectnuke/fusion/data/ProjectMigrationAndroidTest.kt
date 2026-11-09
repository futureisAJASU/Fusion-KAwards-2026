package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Real Room v5-to-v6 migration test using MigrationTestHelper.
 * Runs on API 32+ emulator/device to verify the projects tables.
 *
 * Verifies:
 * 1. Create v5 database with conversations/messages
 * 2. Run Migration5To6 and validate v6 schema
 * 3. Open through production AppDatabase (all migrations)
 * 4. Legacy rows preserved; project DAO round-trip incl. cascades
 */
@RunWith(AndroidJUnit4::class)
class ProjectMigrationAndroidTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    var helper: MigrationTestHelper = MigrationTestHelper(
        instrumentation,
        AppDatabase::class.java
    )

    @Test
    @Throws(IOException::class)
    fun `migrate v5 to v6 creates project tables and preserves history`() {
        val db = helper.createDatabase("fusion-projects-test-db", 5)
        db.execSQL(
            "INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) VALUES (1, 'keep me', 1000, 2000, 1, 0)"
        )
        db.execSQL(
            "INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES (1, 1, 'user', 'hello', 1000)"
        )
        db.close()

        val migratedDb = helper.runMigrationsAndValidate("fusion-projects-test-db", 6, true, AppDatabase.Migration5To6)
        val columns = mutableSetOf<String>()
        migratedDb.query("PRAGMA table_info(projects)").use { cursor ->
            while (cursor.moveToNext()) columns.add(cursor.getString(1))
        }
        assertTrue(columns.containsAll(setOf("id", "name", "createdAt", "updatedAt")))
        val relationColumns = mutableSetOf<String>()
        migratedDb.query("PRAGMA table_info(project_conversations)").use { cursor ->
            while (cursor.moveToNext()) relationColumns.add(cursor.getString(1))
        }
        assertTrue(relationColumns.containsAll(setOf("conversationId", "projectId", "assignedAt")))
        migratedDb.close()

        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val appDb = Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "fusion-projects-test-db"
        ).addMigrations(*appDatabaseMigrationsForTest()).build()
        try {
            runBlocking {
                val chatDao = appDb.chatDao()
                val projectDao = appDb.projectDao()
                val legacy = chatDao.getConversationById(1L)
                assertNotNull(legacy)
                assertEquals("keep me", legacy!!.title)

                val projectId = projectDao.insertProject(
                    ProjectEntity(name = "study", createdAt = 1L, updatedAt = 1L)
                )
                projectDao.assignConversation(
                    ProjectConversationEntity(conversationId = 1L, projectId = projectId, assignedAt = 1L)
                )
                assertEquals(projectId, projectDao.getProjectIdForConversation(1L))
                assertEquals(1, projectDao.getConversationsForProject(projectId).size)

                // Deleting the project preserves its conversations.
                projectDao.deleteProject(projectId)
                assertNull(projectDao.getProjectIdForConversation(1L))
                assertNotNull(chatDao.getConversationById(1L))

                // Deleting a conversation drops its relation.
                val second = projectDao.insertProject(
                    ProjectEntity(name = "work", createdAt = 2L, updatedAt = 2L)
                )
                projectDao.assignConversation(
                    ProjectConversationEntity(conversationId = 1L, projectId = second, assignedAt = 2L)
                )
                chatDao.deleteConversation(1L)
                assertNull(projectDao.getProjectIdForConversation(1L))
            }
        } finally {
            appDb.close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v28 to v29 preserves reminder rows and defaults deletion intents`() {
        val db = helper.createDatabase("fusion-reminder-delete-intent-v28", 28)
        db.execSQL("INSERT INTO projects(id, name, instructions, createdAt, updatedAt) VALUES (1, 'keep', '', 1, 2)")
        db.execSQL("INSERT INTO project_tasks(id, projectId, title, note, state, createdAt, updatedAt, reminderAt, reminderEnabled, reminderGeneration, notificationCleanupPending) VALUES (7, 1, 'task', '', 'OPEN', 1, 2, 5000, 1, 4, 0)")
        db.execSQL("INSERT INTO study_review_cards(id, scope, projectId, front, back, sourceKind, reviewCount, dueAt, createdAt, updatedAt, reminderAt, reminderEnabled, reminderGeneration, notificationCleanupPending) VALUES ('card-7', 'PROJECT', 1, 'front', 'back', 'USER_TEXT', 0, 10, 1, 2, 6000, 1, 5, 0)")
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "fusion-reminder-delete-intent-v28", 29, true, AppDatabase.Migration28To29,
        )
        migrated.query("SELECT name, deletionPending FROM projects WHERE id = 1").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("keep", rows.getString(0))
            assertEquals(0, rows.getInt(1))
        }
        migrated.query("SELECT reminderAt, reminderEnabled, reminderGeneration, deletionPending FROM project_tasks WHERE id = 7").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(5000L, rows.getLong(0))
            assertEquals(1, rows.getInt(1))
            assertEquals(4L, rows.getLong(2))
            assertEquals(0, rows.getInt(3))
        }
        migrated.query("SELECT reminderAt, reminderEnabled, reminderGeneration, deletionPending FROM study_review_cards WHERE id = 'card-7'").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(6000L, rows.getLong(0))
            assertEquals(1, rows.getInt(1))
            assertEquals(5L, rows.getLong(2))
            assertEquals(0, rows.getInt(3))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate v29 to v30 adds recovery cursors without changing reminder owners`() {
        val name = "fusion-reminder-recovery-cursor-v29"
        val db = helper.createDatabase(name, 29)
        db.execSQL("INSERT INTO projects(id, name, instructions, createdAt, updatedAt, deletionPending) VALUES (1, 'keep', '', 1, 2, 0)")
        db.execSQL("INSERT INTO project_tasks(id, projectId, title, note, state, createdAt, updatedAt, reminderAt, reminderEnabled, reminderGeneration, notificationCleanupPending, deletionPending) VALUES (7, 1, 'task', '', 'OPEN', 1, 2, 5000, 1, 4, 0, 0)")
        db.close()

        val migrated = helper.runMigrationsAndValidate(name, 30, true, AppDatabase.Migration29To30)
        migrated.query("SELECT name FROM projects WHERE id = 1").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("keep", rows.getString(0))
        }
        migrated.query("SELECT reminderAt, reminderEnabled, reminderGeneration FROM project_tasks WHERE id = 7").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(5000L, rows.getLong(0))
            assertEquals(1, rows.getInt(1))
            assertEquals(4L, rows.getLong(2))
        }
        migrated.query("SELECT COUNT(*) FROM reminder_recovery_cursors").use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(0, rows.getInt(0))
        }
        migrated.close()
    }
}
