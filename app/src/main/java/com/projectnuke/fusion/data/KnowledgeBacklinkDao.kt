package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface KnowledgeBacklinkDao {
    @Query(
        """
        SELECT messages.id AS messageId, messages.conversationId AS conversationId,
               messages.content AS content, messages.createdAt AS createdAt,
               conversations.title AS conversationTitle
        FROM (
            SELECT id, conversationId, role, content, createdAt
            FROM messages
            ORDER BY id DESC
            LIMIT 2000
        ) AS messages
        INNER JOIN conversations ON conversations.id = messages.conversationId
        WHERE messages.role = 'assistant'
          AND instr(messages.content, '[FUSION_LOCAL_RAG_SOURCES_JSON]') > 0
          AND length(messages.content) <= 80000
          AND (conversations.isArchived = 0 OR :includeArchived = 1)
        ORDER BY messages.createdAt DESC, messages.id DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentKnowledgeUsageCandidates(includeArchived: Boolean, limit: Int): List<KnowledgeUsageCandidate>

    @Query(
        """
        SELECT messages.id AS messageId, messages.conversationId AS conversationId,
               messages.content AS content, messages.createdAt AS createdAt,
               conversations.title AS conversationTitle
        FROM messages INNER JOIN conversations ON conversations.id = messages.conversationId
        WHERE messages.id = :messageId AND messages.conversationId = :conversationId
          AND messages.role = 'assistant' AND length(messages.content) <= 80000
          AND (conversations.isArchived = 0 OR :includeArchived = 1)
        LIMIT 1
        """
    )
    suspend fun getAccessibleUsageCandidate(
        messageId: Long,
        conversationId: Long,
        includeArchived: Boolean,
    ): KnowledgeUsageCandidate?
}
