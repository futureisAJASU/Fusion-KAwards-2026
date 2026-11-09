package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SubmissionCommitWitnessDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(witness: SubmissionCommitWitnessEntity)

    @Query("SELECT * FROM submission_commit_witnesses WHERE token = :token LIMIT 1")
    suspend fun get(token: String): SubmissionCommitWitnessEntity?

    @Query("SELECT * FROM submission_commit_witnesses ORDER BY createdAt ASC, token ASC LIMIT :limit")
    suspend fun getBatch(limit: Int): List<SubmissionCommitWitnessEntity>

    /**
     * Keyset continuation of [getBatch] using the same stable
     * `(createdAt, token)` ordering. Strictly-greater-than keyset pagination
     * (never offsets) so concurrent deletions cannot shift rows under the
     * scan and skipped-then-reinserted rows are revisited on the next pass.
     * No schema change; this is a query-only addition.
     */
    @Query(
        "SELECT * FROM submission_commit_witnesses " +
            "WHERE (createdAt > :createdAt OR (createdAt = :createdAt AND token > :token)) " +
            "ORDER BY createdAt ASC, token ASC LIMIT :limit"
    )
    suspend fun getBatchAfter(
        createdAt: Long,
        token: String,
        limit: Int,
    ): List<SubmissionCommitWitnessEntity>

    @Query("DELETE FROM submission_commit_witnesses WHERE token = :token")
    suspend fun delete(token: String): Int
}
