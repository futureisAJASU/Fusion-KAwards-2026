package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Query
import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.ChatInteractionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ResearchRefreshCandidate(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val answerContent: String,
)

data class ResearchRefreshTarget(
    val id: Long,
    val title: String,
    val updatedAt: Long,
)

@Dao
interface ResearchRefreshDao {
    @Query(
        """
        SELECT messages.content FROM messages
        INNER JOIN conversations ON conversations.id = messages.conversationId
        WHERE messages.conversationId = :sourceConversationId AND messages.id = :sourceMessageId
          AND messages.role = 'assistant' AND length(messages.content) <= 250000
          AND (conversations.isArchived = 0 OR :includeArchived = 1)
        LIMIT 1
        """,
    )
    suspend fun sourceAnswer(
        sourceConversationId: Long,
        sourceMessageId: Long,
        includeArchived: Boolean,
    ): String?

    @Query(
        """
        SELECT conversations.id AS id, conversations.title AS title, messages.createdAt AS updatedAt,
               messages.content AS answerContent
        FROM conversations
        INNER JOIN messages ON messages.conversationId = conversations.id
        WHERE conversations.refreshedFromConversationId = :sourceConversationId
          AND conversations.refreshedFromMessageId = :sourceMessageId
          AND messages.role = 'assistant' AND messages.createdAt > conversations.createdAt
          AND length(messages.content) <= 250000
          AND (conversations.isArchived = 0 OR :includeArchived = 1)
        ORDER BY conversations.createdAt DESC, messages.createdAt DESC, conversations.id DESC
        LIMIT :limit
        """,
    )
    suspend fun targetsFrom(
        sourceConversationId: Long,
        sourceMessageId: Long,
        includeArchived: Boolean,
        limit: Int,
    ): List<ResearchRefreshCandidate>
}

class ResearchRefreshRepository(private val dao: ResearchRefreshDao) {
    suspend fun targetsFrom(
        sourceConversationId: Long,
        sourceMessageId: Long,
        includeArchived: Boolean,
        limit: Int = MAX_RELATIONSHIPS,
    ): List<ResearchRefreshTarget> = withContext(Dispatchers.IO) {
        require(sourceConversationId > 0L && sourceMessageId > 0L)
        // Eligible/distinct-first selection: the SQL window returns raw
        // assistant rows (any mode, duplicates per conversation), so a tiny
        // raw LIMIT can starve older eligible Research relationships behind
        // normal messages or same-conversation duplicates. Page the raw
        // window in bounded growing prefixes until enough eligible distinct
        // relationships are found or the window is exhausted. Every fetch is
        // bounded; total scanned rows never exceed the final prefix.
        val want = limit.coerceIn(1, MAX_RELATIONSHIPS)
        var eligible: List<ResearchRefreshCandidate> = emptyList()
        for (fetch in FETCH_PREFIXES) {
            val rows = dao.targetsFrom(sourceConversationId, sourceMessageId, includeArchived, fetch)
            eligible = rows
                .filter { AnswerContextReceipt.parse(it.answerContent)?.interactionMode == ChatInteractionMode.RESEARCH.name }
                .distinctBy { it.id }
            if (eligible.size >= want || rows.size < fetch) break
        }
        eligible.take(want).map { ResearchRefreshTarget(it.id, it.title, it.updatedAt) }
    }

    companion object {
        const val MAX_RELATIONSHIPS = 3
        internal val FETCH_PREFIXES = listOf(32, 64, 128, 256)
    }
}
