package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationCheckpointRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: ConversationCheckpointRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = ConversationCheckpointRepository(db, clock = { 40L })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `save requires assistant message owned by conversation and search respects archive access`() = runBlocking {
        val activeId = db.chatDao().insertConversation(ConversationEntity(title = "Active", createdAt = 1, updatedAt = 1))
        val archivedId = db.chatDao().insertConversation(ConversationEntity(title = "Archived", createdAt = 2, updatedAt = 2, isArchived = true))
        val activeMessage = db.chatDao().insertMessage(MessageEntity(conversationId = activeId, role = "assistant", content = "answer", createdAt = 3))
        val archivedMessage = db.chatDao().insertMessage(MessageEntity(conversationId = archivedId, role = "assistant", content = "answer", createdAt = 4))
        assertEquals(CheckpointWriteResult.MissingSource, repository.save(activeId, archivedMessage, "wrong owner", ""))
        assertEquals(CheckpointWriteResult.MissingSource, repository.save(activeId, activeMessage, " ", ""))
        assertTrue(repository.save(activeId, activeMessage, "HBM thermal checkpoint", "Review later") is CheckpointWriteResult.Saved)
        assertTrue(repository.save(archivedId, archivedMessage, "Archived thermal checkpoint", "") is CheckpointWriteResult.Saved)

        assertEquals(1, db.conversationCheckpointDao().search("thermal", false, 32).size)
        assertEquals(2, db.conversationCheckpointDao().search("thermal", true, 32).size)
        db.chatDao().deleteMessageById(activeMessage)
        assertTrue(db.conversationCheckpointDao().search("thermal", true, 32).none { it.conversationId == activeId })
    }

    @Test
    fun `rename returns success only when UPDATE affects exactly one row`() = runBlocking {
        val convId = db.chatDao().insertConversation(ConversationEntity(title = "C", createdAt = 1, updatedAt = 1))
        val msgId = db.chatDao().insertMessage(MessageEntity(conversationId = convId, role = "assistant", content = "answer", createdAt = 2))
        val id = db.conversationCheckpointDao().insert(
            ConversationCheckpointEntity(conversationId = convId, messageId = msgId, title = "original", createdAt = 3),
        )

        assertTrue(repository.rename(id, convId, "renamed title", "renamed note"))
        val updated = db.conversationCheckpointDao().get(id)!!
        assertEquals("renamed title", updated.title)
        assertEquals("renamed note", updated.note)

        val otherConv = db.chatDao().insertConversation(ConversationEntity(title = "Other", createdAt = 1, updatedAt = 1))
        assertFalse(repository.rename(id, otherConv, "wrong owner", ""))
    }

    @Test
    fun `rename returns failure for deleted stale checkpoint via zero-row UPDATE`() = runBlocking {
        val convId = db.chatDao().insertConversation(ConversationEntity(title = "C", createdAt = 1, updatedAt = 1))
        val msgId = db.chatDao().insertMessage(MessageEntity(conversationId = convId, role = "assistant", content = "answer", createdAt = 2))
        val id = db.conversationCheckpointDao().insert(
            ConversationCheckpointEntity(conversationId = convId, messageId = msgId, title = "original", createdAt = 3),
        )
        assertEquals(1, db.conversationCheckpointDao().rename(id, convId, "orphaned", ""))
        assertEquals(0, db.conversationCheckpointDao().rename(id, convId, "ghost", "ghost"))
        assertFalse(repository.rename(id, convId, "no row", ""))
    }
}
