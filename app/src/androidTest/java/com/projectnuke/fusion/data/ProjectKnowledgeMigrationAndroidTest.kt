package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Android Room migration source for the v6 -> v7 project knowledge schema. */
@RunWith(AndroidJUnit4::class)
class ProjectKnowledgeMigrationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    var helper: MigrationTestHelper = MigrationTestHelper(instrumentation, AppDatabase::class.java)

    @Test
    @Throws(IOException::class)
    fun `migrate v6 to v7 preserves membership and project conversation rows`() {
        val db = helper.createDatabase("fusion-project-knowledge-test-db", 6)
        db.execSQL("INSERT INTO conversations (id, title, createdAt, updatedAt, isPinned, isArchived) VALUES (1, 'keep', 1, 2, 0, 0)")
        db.execSQL("INSERT INTO projects (id, name, createdAt, updatedAt) VALUES (1, 'study', 3, 4)")
        db.execSQL("INSERT INTO project_conversations (conversationId, projectId, assignedAt) VALUES (1, 1, 5)")
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "fusion-project-knowledge-test-db",
            7,
            true,
            AppDatabase.Migration6To7,
            AppDatabase.Migration7To8,
        )
        migrated.query("SELECT instructions FROM projects WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
        }
        migrated.query("PRAGMA foreign_key_list(project_files)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("projects", cursor.getString(cursor.getColumnIndexOrThrow("table")))
            assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
        }
        migrated.close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val appDb = Room.databaseBuilder(context, AppDatabase::class.java, "fusion-project-knowledge-test-db")
            .addMigrations(*appDatabaseMigrationsForTest())
            .build()
        try {
            runBlocking {
                assertNotNull(appDb.chatDao().getConversationById(1L))
                assertEquals(1L, appDb.projectDao().getProjectIdForConversation(1L))
                val fileId = appDb.projectDao().insertProjectFile(
                    ProjectFileEntity(0, 1, "notes.md", "text/markdown", "/managed/notes", 6),
                )
                assertTrue(fileId > 0L)
                appDb.projectDao().deleteProject(1L)
                assertNotNull(appDb.chatDao().getConversationById(1L))
                assertEquals(0, appDb.projectDao().countProjectFiles(1L))
            }
        } finally {
            appDb.close()
        }
    }
}
