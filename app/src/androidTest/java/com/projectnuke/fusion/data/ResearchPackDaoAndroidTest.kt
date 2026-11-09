package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResearchPackDaoAndroidTest {
    @Test
    fun `answer lookup is assistant scoped and enforces archive access`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.chatDao().insertConversation(ConversationEntity(1, "visible title", 1, 1))
            db.chatDao().insertConversation(ConversationEntity(2, "locked title", 1, 1, isArchived = true))
            db.chatDao().insertMessage(MessageEntity(1, 1, "assistant", "answer", 2))
            db.chatDao().insertMessage(MessageEntity(2, 1, "user", "user input", 3))
            db.chatDao().insertMessage(MessageEntity(3, 2, "assistant", "archived answer", 4))

            val visible = db.researchPackDao().getAccessibleAnswer(1, 1, includeArchived = false)
            assertNotNull(visible)
            assertEquals("visible title", visible!!.conversationTitle)
            assertNull(db.researchPackDao().getAccessibleAnswer(2, 1, includeArchived = false))
            assertNull(db.researchPackDao().getAccessibleAnswer(2, 2, includeArchived = false))
            assertEquals(
                "locked title",
                db.researchPackDao().getAccessibleAnswer(3, 2, includeArchived = true)?.conversationTitle,
            )
        } finally {
            db.close()
        }
    }
}
