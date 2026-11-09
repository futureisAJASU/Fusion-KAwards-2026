package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.knowledge.SavedKnowledgeDraft
import com.projectnuke.fusion.knowledge.SavedKnowledgePolicy
import com.projectnuke.fusion.knowledge.SavedKnowledgeScope
import com.projectnuke.fusion.knowledge.SavedKnowledgeRepository
import com.projectnuke.fusion.knowledge.SavedKnowledgeWriteResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedKnowledgeDaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun projectSnapshotRoundTripsAndSurvivesSourceConversationDeletionThenCascadesWithProject() = runBlocking {
        val projectId = database.projectDao().insertProject(
            ProjectEntity(name = "P", instructions = "", createdAt = 1, updatedAt = 1),
        )
        val conversationId = database.chatDao().insertConversation(
            ConversationEntity(title = "C", createdAt = 1, updatedAt = 1),
        )
        val messageId = database.chatDao().insertMessage(
            MessageEntity(conversationId = conversationId, role = "assistant", content = "visible answer", createdAt = 2),
        )
        database.projectDao().assignConversation(ProjectConversationEntity(conversationId, projectId, 1))
        val draft = SavedKnowledgeDraft("answer", "visible answer", conversationId, messageId, projectId, "P")
        val proposal = (SavedKnowledgePolicy.prepare(draft, draft.title, SavedKnowledgeScope.PROJECT, 3, "stable-id")
            as com.projectnuke.fusion.knowledge.SavedKnowledgePreparation.Ready).proposal

        assertEquals(SavedKnowledgeWriteResult.Stored, SavedKnowledgeRepository(database).save(proposal, false))
        val restored = database.savedKnowledgeDao().getById("stable-id")
        assertNotNull(restored)
        assertEquals("visible answer", restored?.savedText)
        assertEquals("PROJECT", restored?.scope)
        assertEquals(projectId, restored?.projectId)

        database.chatDao().deleteConversation(conversationId)
        assertEquals("visible answer", database.savedKnowledgeDao().getById("stable-id")?.savedText)
        database.projectDao().deleteProject(projectId)
        assertNull(database.savedKnowledgeDao().getById("stable-id"))
    }

    @Test
    fun personalSnapshotSurvivesSourceDeletionAndDoesNotKeepAProjectBinding() = runBlocking {
        val conversationId = database.chatDao().insertConversation(
            ConversationEntity(title = "C", createdAt = 1, updatedAt = 1),
        )
        val messageId = database.chatDao().insertMessage(
            MessageEntity(conversationId = conversationId, role = "assistant", content = "copy", createdAt = 2),
        )
        val draft = SavedKnowledgeDraft("answer", "copy", conversationId, messageId, null, null)
        val proposal = (SavedKnowledgePolicy.prepare(draft, "answer", SavedKnowledgeScope.PERSONAL, 3, "personal-id")
            as com.projectnuke.fusion.knowledge.SavedKnowledgePreparation.Ready).proposal

        assertEquals(SavedKnowledgeWriteResult.Stored, SavedKnowledgeRepository(database).save(proposal, false))
        database.chatDao().deleteConversation(conversationId)
        val saved = database.savedKnowledgeDao().getById("personal-id")
        assertEquals("copy", saved?.savedText)
        assertNull(saved?.projectId)
        assertTrue(saved?.sourceConversationId == conversationId)
    }
}
