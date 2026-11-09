package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EvidenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidence(evidence: MessageEvidenceEntity)

    @Query("SELECT * FROM message_evidence WHERE messageId = :messageId")
    suspend fun getEvidenceForMessage(messageId: Long): MessageEvidenceEntity?

    @Query("SELECT * FROM message_evidence WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)")
    fun observeEvidenceForConversation(conversationId: Long): Flow<List<MessageEvidenceEntity>>

    @Query(
        "SELECT messageId, answerSha256, schemaVersion, verifierVersion, checkedAt, " +
            "LENGTH(reportJson) AS reportJsonLength " +
            "FROM message_evidence " +
            "WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)",
    )
    fun observeEvidenceRevisionsForConversation(conversationId: Long): Flow<List<MessageEvidenceRevision>>

    @Query("SELECT * FROM message_evidence WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)")
    suspend fun getEvidenceForConversation(conversationId: Long): List<MessageEvidenceEntity>

    @Query("DELETE FROM message_evidence WHERE messageId = :messageId")
    suspend fun deleteEvidenceForMessage(messageId: Long)
}
