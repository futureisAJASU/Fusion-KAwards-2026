package com.projectnuke.fusion.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.AnswerContextRoute
import com.projectnuke.fusion.chat.ChatInteractionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResearchRefreshDaoTest {
    @Test
    fun `refresh backlink appears only after a research answer and obeys archive access`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "research-refresh-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val sourceId = database.chatDao().insertConversation(
                ConversationEntity(title = "Research", createdAt = 1, updatedAt = 2),
            )
            val targetId = database.chatDao().insertConversation(
                ConversationEntity(
                    title = "Research", createdAt = 10, updatedAt = 12, isArchived = true,
                    branchedFromConversationId = sourceId, branchedFromMessageId = 20,
                    refreshedFromConversationId = sourceId, refreshedFromMessageId = 20,
                ),
            )
            val receipt = AnswerContextReceipt.build(
                route = AnswerContextRoute.LOCAL_MODEL,
                interactionMode = ChatInteractionMode.RESEARCH.name,
            )
            val researchAnswer = AnswerContextReceipt.encode(receipt)!!
            database.chatDao().insertMessage(MessageEntity(
                id = 20, conversationId = sourceId, role = "assistant", content = researchAnswer, createdAt = 2,
            ))
            database.chatDao().insertMessage(MessageEntity(
                id = 21, conversationId = targetId, role = "assistant", content = researchAnswer, createdAt = 2,
            )) // copied source answer is not a completed refresh
            assertEquals(emptyList<ResearchRefreshTarget>(), ResearchRefreshRepository(database.researchRefreshDao())
                .targetsFrom(sourceId, 20, includeArchived = true))

            database.chatDao().insertMessage(MessageEntity(
                conversationId = targetId, role = "assistant", content = researchAnswer, createdAt = 12,
            ))
            val repository = ResearchRefreshRepository(database.researchRefreshDao())
            assertEquals(emptyList<ResearchRefreshTarget>(), repository.targetsFrom(sourceId, 20, includeArchived = false))
            assertEquals(targetId, repository.targetsFrom(sourceId, 20, includeArchived = true).single().id)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
