package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Query

data class ResearchPackStoredAnswer(
    val messageId: Long,
    val conversationId: Long,
    val content: String,
    val createdAt: Long,
    val conversationTitle: String,
)

@Dao
interface ResearchPackDao {
    @Query(
        """
        SELECT messages.id AS messageId, messages.conversationId AS conversationId,
               messages.content AS content, messages.createdAt AS createdAt,
               conversations.title AS conversationTitle
        FROM messages INNER JOIN conversations ON conversations.id = messages.conversationId
        WHERE messages.id = :messageId AND messages.conversationId = :conversationId
          AND messages.role = 'assistant' AND length(messages.content) <= 250000
          AND (conversations.isArchived = 0 OR :includeArchived = 1)
        LIMIT 1
        """
    )
    suspend fun getAccessibleAnswer(
        messageId: Long,
        conversationId: Long,
        includeArchived: Boolean,
    ): ResearchPackStoredAnswer?
}
