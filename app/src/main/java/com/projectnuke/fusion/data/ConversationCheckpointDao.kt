package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationCheckpointDao {
    @Insert
    suspend fun insert(checkpoint: ConversationCheckpointEntity): Long

    @Query("SELECT * FROM conversation_checkpoints WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeForConversation(conversationId: Long, limit: Int = 64): Flow<List<ConversationCheckpointEntity>>

    @Query("SELECT * FROM conversation_checkpoints WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun listForConversation(conversationId: Long, limit: Int = 64): List<ConversationCheckpointEntity>

    @Query("SELECT * FROM conversation_checkpoints WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): ConversationCheckpointEntity?

    @Query("SELECT COUNT(*) FROM conversation_checkpoints WHERE conversationId = :conversationId")
    suspend fun countForConversation(conversationId: Long): Int

    @Query("SELECT * FROM conversation_checkpoints WHERE id = :id AND conversationId = :conversationId LIMIT 1")
    suspend fun getOwned(id: Long, conversationId: Long): ConversationCheckpointEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :messageId AND conversationId = :conversationId AND role = 'assistant')")
    suspend fun hasAssistantAnchor(conversationId: Long, messageId: Long): Boolean

    @Query("UPDATE conversation_checkpoints SET title = :title, note = :note WHERE id = :id AND conversationId = :conversationId")
    suspend fun rename(id: Long, conversationId: Long, title: String, note: String): Int

    @Query("DELETE FROM conversation_checkpoints WHERE id = :id AND conversationId = :conversationId")
    suspend fun delete(id: Long, conversationId: Long)

    @Query(
        """
        SELECT cp.id, cp.conversationId, cp.messageId, cp.title, cp.createdAt, cp.note,
               c.title AS conversationTitle, c.isArchived AS isArchived
        FROM conversation_checkpoints cp
        JOIN conversations c ON c.id = cp.conversationId
        JOIN messages m ON m.id = cp.messageId AND m.conversationId = cp.conversationId
        WHERE (cp.title LIKE '%' || :query || '%' ESCAPE '\'
            OR cp.note LIKE '%' || :query || '%' ESCAPE '\')
          AND (:includeArchived = 1 OR c.isArchived = 0)
        ORDER BY cp.createdAt DESC, cp.id DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, includeArchived: Boolean, limit: Int): List<ConversationCheckpointSearchRow>
}
