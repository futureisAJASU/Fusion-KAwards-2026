package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ResponseVersionCommitWitnessDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(witness: ResponseVersionCommitWitnessEntity)

    @Query("SELECT * FROM response_version_commit_witnesses ORDER BY createdAt ASC, messageId ASC LIMIT :limit")
    suspend fun getBatch(limit: Int): List<ResponseVersionCommitWitnessEntity>

    /**
     * Keyset continuation of [getBatch] using the same stable
     * `(createdAt, messageId)` ordering. Strictly-greater-than keyset
     * pagination (never offsets) so concurrent deletions cannot shift rows
     * under the scan. No schema change; this is a query-only addition.
     */
    @Query(
        "SELECT * FROM response_version_commit_witnesses " +
            "WHERE (createdAt > :createdAt OR (createdAt = :createdAt AND messageId > :messageId)) " +
            "ORDER BY createdAt ASC, messageId ASC LIMIT :limit"
    )
    suspend fun getBatchAfter(
        createdAt: Long,
        messageId: Long,
        limit: Int,
    ): List<ResponseVersionCommitWitnessEntity>

    @Query("SELECT * FROM response_version_commit_witnesses WHERE messageId = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: Long): ResponseVersionCommitWitnessEntity?

    @Query("DELETE FROM response_version_commit_witnesses WHERE messageId = :messageId")
    suspend fun delete(messageId: Long): Int

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: Long): MessageEntity?
}
