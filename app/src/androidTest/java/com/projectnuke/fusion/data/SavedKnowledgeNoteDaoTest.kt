package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteEditorDraft
import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteReferenceDraft
import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteRepository
import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteSaveResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedKnowledgeNoteDaoTest {
    @Test
    fun `explicit extracted note is saved only to a live selected project`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "extracted-note-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val projectId = database.projectDao().insertProject(ProjectEntity(name = "HBM", createdAt = 1, updatedAt = 1))
            val reference = SavedKnowledgeNoteReferenceDraft(
                SelectionSourceIdentity(SelectionSourceKind.PROJECT, projectId = projectId), "HBM",
            )
            val repository = SavedKnowledgeNoteRepository(database)
            val saved = repository.save(
                noteId = "", title = "Extracted table", body = "Item\tValue\nSpeed\t3.2 GHz",
                references = listOf(reference), now = 2, includeArchived = false, projectId = projectId,
            ) as SavedKnowledgeNoteSaveResult.Saved

            val entity = database.savedKnowledgeDao().getById(saved.noteId)!!
            assertEquals("PROJECT", entity.scope)
            assertEquals(projectId, entity.projectId)
            assertEquals(listOf("p:$projectId"), repository.load(saved.noteId)!!.references.map { it.identity.stableKey() })
            assertEquals(
                SavedKnowledgeNoteSaveResult.ProjectUnavailable,
                repository.save(
                    noteId = "", title = "Stale destination", body = "Body",
                    references = listOf(reference), now = 3, includeArchived = false,
                    projectId = projectId + 99L,
                ),
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `editable body and separate source identities survive reopen and orphan gracefully`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "saved-note-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val projectId = database.projectDao().insertProject(ProjectEntity(name = "HBM", createdAt = 1, updatedAt = 1))
            database.savedKnowledgeDao().insert(
                SavedKnowledgeEntity(
                    id = "source", scope = "PERSONAL", projectId = null, title = "Captured answer",
                    savedText = "Original captured text", provenanceType = "ASSISTANT_ANSWER",
                    sourceConversationId = null, sourceMessageId = null, createdAt = 2, updatedAt = 2,
                ),
            )
            val repository = SavedKnowledgeNoteRepository(database)
            val draft = repository.prepareFromSavedKnowledge("source", includeArchived = false)!!
            val projectReference = SavedKnowledgeNoteReferenceDraft(
                SelectionSourceIdentity(SelectionSourceKind.PROJECT, projectId = projectId), "HBM",
            )
            val saved = repository.save(
                noteId = "", title = "Editable note", body = "# Revised\nIndependent body",
                references = draft.references + projectReference, now = 3, includeArchived = false,
                sourceSavedKnowledgeId = draft.sourceSavedKnowledgeId,
            ) as SavedKnowledgeNoteSaveResult.Saved
            database.close()

            database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val reopened = SavedKnowledgeNoteRepository(database).load(saved.noteId)!!
            assertEquals("# Revised\nIndependent body", reopened.body)
            assertEquals(listOf("s:source", "p:$projectId"), reopened.references.map { it.identity.stableKey() })

            database.projectDao().deleteProject(projectId)
            val orphanEdit = SavedKnowledgeNoteRepository(database).save(
                noteId = saved.noteId, title = "Still editable", body = "Source may be missing",
                references = reopened.references, now = 4, includeArchived = false,
            )
            assertEquals(SavedKnowledgeNoteSaveResult.Saved(saved.noteId), orphanEdit)
            assertEquals(listOf("s:source", "p:$projectId"), SavedKnowledgeNoteRepository(database)
                .load(saved.noteId)!!.references.map { it.identity.stableKey() })
            database.savedKnowledgeDao().delete(saved.noteId)
            assertTrue(database.savedKnowledgeNoteSourceDao().getForNote(saved.noteId, 8).isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
