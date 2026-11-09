package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeBacklinkDaoAndroidTest {
    @Test
    fun `recent usage query honors archive access and bounded candidate contract`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.chatDao().insertConversation(ConversationEntity(1, "visible title", 1, 1))
            db.chatDao().insertConversation(ConversationEntity(2, "private archive title", 1, 1, isArchived = true))
            db.chatDao().insertMessage(MessageEntity(1, 1, "assistant", "answer [FUSION_LOCAL_RAG_SOURCES_JSON] [] [/FUSION_LOCAL_RAG_SOURCES_JSON]", 2))
            db.chatDao().insertMessage(MessageEntity(2, 2, "assistant", "archive [FUSION_LOCAL_RAG_SOURCES_JSON] [] [/FUSION_LOCAL_RAG_SOURCES_JSON]", 3))
            db.chatDao().insertMessage(MessageEntity(3, 1, "user", "user [FUSION_LOCAL_RAG_SOURCES_JSON]", 4))
            db.chatDao().insertMessage(MessageEntity(4, 1, "assistant", "no provenance", 5))

            val locked = db.knowledgeBacklinkDao().getRecentKnowledgeUsageCandidates(includeArchived = false, limit = 128)
            assertEquals(1, locked.size)
            assertEquals("visible title", locked.single().conversationTitle)

            val unlocked = db.knowledgeBacklinkDao().getRecentKnowledgeUsageCandidates(includeArchived = true, limit = 128)
            assertEquals(2, unlocked.size)
            assertTrue(unlocked.any { it.conversationTitle == "private archive title" })
        } finally {
            db.close()
        }
    }
}
