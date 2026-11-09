package com.projectnuke.fusion.documents

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentHighlightRepositoryTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(AppDatabase.Migration18To19Sql.statements.last())
            }
        }).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `saved highlight is searchable knowledge and cascades with its source file`() = runBlocking {
        val projectId = database.projectDao().insertProject(ProjectEntity(name = "Project", createdAt = 1, updatedAt = 1))
        val fileId = database.projectDao().insertProjectFile(
            ProjectFileEntity(projectId = projectId, displayName = "paper.pdf", mimeType = "application/pdf", localPath = "managed-id", addedAt = 1),
        )
        val result = DocumentHighlightRepository(database).save(
            DocumentHighlightDraft(projectId, fileId, "paper.pdf", 2, "page:2", "cd".repeat(32), "bounded quote", "why this matters"),
        ) as DocumentHighlightWriteResult.Stored
        val saved = database.savedKnowledgeDao().getById(result.savedKnowledgeId)
        assertNotNull(saved)
        assertEquals("bounded quote", saved?.savedText)
        val source = database.documentHighlightSourceDao().getByKnowledgeId(result.savedKnowledgeId)
        assertNotNull(source)
        assertEquals(2, source?.pageNumber)
        assertEquals("why this matters", source?.userNote)

        database.projectDao().deleteProjectFile(fileId)
        assertNull(database.savedKnowledgeDao().getById(result.savedKnowledgeId))
        assertNull(database.documentHighlightSourceDao().getByKnowledgeId(result.savedKnowledgeId))
    }
}
