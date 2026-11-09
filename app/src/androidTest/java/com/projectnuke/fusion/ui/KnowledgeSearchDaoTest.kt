package com.projectnuke.fusion.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectnuke.fusion.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KnowledgeSearchDaoTest {
    @Test fun existingRowsHaveBoundedStablePrivacyFilteredSearch() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val chat = db.chatDao()
            val search = db.knowledgeSearchDao()
            val locked = chat.insertConversation(ConversationEntity(title = "private Fusion", createdAt = 0, updatedAt = 100, isArchived = true))
            chat.insertMessage(MessageEntity(conversationId = locked, role = "user", content = "secret", createdAt = 0))
            repeat(12) { chat.insertConversation(ConversationEntity(title = "Fusion", createdAt = 0, updatedAt = it.toLong())) }
            val rows = search.searchConversationTitles("Fusion", false, 8)
            assertEquals(8, rows.size)
            assertFalse(rows.any { it.id == locked })
            assertEquals(rows, search.searchConversationTitles("Fusion", false, 8))
            assertTrue(search.getSearchMessages(rows.map { it.id }, 8).isEmpty())
            chat.deleteConversation(rows.first().id)
            assertNull(chat.getConversationById(rows.first().id))
            assertEquals(locked, search.searchConversationTitles("private", true, 8).single().id)
        } finally { db.close() }
    }
}
