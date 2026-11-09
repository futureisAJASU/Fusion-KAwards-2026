package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY isPinned DESC, updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY isPinned DESC, updatedAt DESC, id DESC LIMIT :limit")
    fun observeConversationsLimited(limit: Int): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isArchived = 1 ORDER BY isPinned DESC, updatedAt DESC")
    fun observeArchivedConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isArchived = 1 ORDER BY isPinned DESC, updatedAt DESC, id DESC LIMIT :limit")
    fun observeArchivedConversationsLimited(limit: Int): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT DISTINCT conversationId
        FROM messages
        WHERE
            CASE
                WHEN instr(content, '<fusion_metrics>') > 0 THEN substr(content, 1, instr(content, '<fusion_metrics>') - 1)
                WHEN instr(content, '<fusion_thinking>') > 0 THEN substr(content, 1, instr(content, '<fusion_thinking>') - 1)
                WHEN instr(content, '<fusion_attachment_v2>') > 0 THEN substr(content, 1, instr(content, '<fusion_attachment_v2>') - 1)
                WHEN instr(content, '<fusion_attachment>') > 0 THEN substr(content, 1, instr(content, '<fusion_attachment>') - 1)
                ELSE content
            END
            LIKE '%' || :query || '%' ESCAPE '\'
        """
    )
    fun observeConversationIdsMatchingMessages(query: String): Flow<List<Long>>

    @Query(
        """
        SELECT DISTINCT conversationId
        FROM messages
        WHERE
            CASE
                WHEN instr(content, '<fusion_metrics>') > 0 THEN substr(content, 1, instr(content, '<fusion_metrics>') - 1)
                WHEN instr(content, '<fusion_thinking>') > 0 THEN substr(content, 1, instr(content, '<fusion_thinking>') - 1)
                WHEN instr(content, '<fusion_attachment_v2>') > 0 THEN substr(content, 1, instr(content, '<fusion_attachment_v2>') - 1)
                WHEN instr(content, '<fusion_attachment>') > 0 THEN substr(content, 1, instr(content, '<fusion_attachment>') - 1)
                ELSE content
            END
            LIKE '%' || :query || '%' ESCAPE '\'
        LIMIT :limit
        """
    )
    fun observeConversationIdsMatchingMessagesLimited(query: String, limit: Int): Flow<List<Long>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT :limit) ORDER BY createdAt ASC, id ASC")
    fun observeMessagesLatestLimited(conversationId: Long, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    fun observeMessageCountForConversation(conversationId: Long): Flow<Int>

    @Query("SELECT id, conversationId, role, createdAt FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeMessageIdentitiesForConversation(conversationId: Long): Flow<List<MessageIdentity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessagesForConversation(conversationId: Long): List<MessageEntity>


    @Query("SELECT id, conversationId, role, createdAt FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessageIdentitiesForConversation(conversationId: Long): List<MessageIdentity>

    @Query("SELECT id, conversationId, role, createdAt FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageIdentityById(messageId: Long): MessageIdentity?

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: Long): MessageEntity?

    @Query("SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT :limit) ORDER BY createdAt ASC, id ASC")
    suspend fun getMessagesLatestLimited(conversationId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT content FROM messages")
    suspend fun getAllMessageContents(): List<String>

    @Query("SELECT id, content FROM messages WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getMessageContentPage(afterId: Long, limit: Int): List<MessageContentPageRow>

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversationById(conversationId: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY isPinned DESC, updatedAt DESC LIMIT 1")
    suspend fun getLatestConversation(): ConversationEntity?

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun getConversationCount(): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int

    @Query("SELECT COUNT(*) FROM conversations WHERE isArchived = 1")
    suspend fun getArchivedConversationCount(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMessageCountForConversation(conversationId: Long): Int

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :conversationId")
    suspend fun updateConversationTime(conversationId: Long, updatedAt: Long)

    @Query("UPDATE messages SET content = :content WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: Long, content: String)

    @Query("UPDATE conversations SET isPinned = :isPinned WHERE id = :conversationId")
    suspend fun updateConversationPinned(conversationId: Long, isPinned: Boolean)

    @Query("UPDATE conversations SET title = :title WHERE id = :conversationId")
    suspend fun updateConversationTitle(conversationId: Long, title: String)

    @Query("UPDATE conversations SET isArchived = 1 WHERE id = :conversationId")
    suspend fun archiveConversation(conversationId: Long)

    @Query("UPDATE conversations SET isArchived = :archived WHERE id = :conversationId")
    suspend fun setConversationArchived(conversationId: Long, archived: Boolean)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: Long)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)

    /**
     * Coarse candidate selector for visible-content search. Matches anywhere in
     * raw content (visible body AND hidden envelopes/metadata); callers must
     * post-filter rows with the canonical visible-content contract so hidden
     * metadata matches never surface. Bounded pagination keeps hidden-metadata
     * false positives from starving real visible matches.
     */
    @Query(
        """
        SELECT conversationId, content
        FROM messages
        WHERE content LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY conversationId ASC, id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getMessageContentHitsLike(query: String, limit: Int, offset: Int): List<MessageContentHit>
}

fun escapeSqlLikeQuery(query: String): String {
    return buildString {
        query.forEach { char ->
            when (char) {
                '\\', '%', '_' -> {
                    append('\\')
                    append(char)
                }
                else -> append(char)
            }
        }
    }
}
