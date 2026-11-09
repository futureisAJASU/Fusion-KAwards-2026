package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Issue 1: exact generation-request provenance for Work Plan MESSAGE
 * settlement (v26).
 *
 * One row per specialized MESSAGE attempt, keyed by the unique
 * [attemptId]. Written at the authoritative persistence boundary:
 *
 * - [requestId] is recorded when the exact generation request for the
 *   committed Work Plan submission is created (first wins);
 * - [assistantMessageId] is recorded when that exact request persists its
 *   assistant answer (first wins; cancellations/failures record nothing).
 *
 * Settlement requires all four links
 * attempt → submission → request → assistant to match the receipt. A
 * retry/regeneration under another requestId can never present its answer
 * as this attempt's, even for the same user message. Rows carry no titles
 * or excerpts — only identity links — and are deleted when their attempt
 * settles terminally, is cancelled/failed, or is superseded by a retry.
 */
@Entity(
    tableName = "project_work_plan_generation_witnesses",
    indices = [
        Index("requestId"),
        Index("stepId"),
    ],
)
data class ProjectWorkPlanGenerationWitnessEntity(
    @PrimaryKey val attemptId: String,
    val requestId: String,
    val planId: String,
    val stepId: String,
    val projectId: Long,
    val conversationId: Long,
    val userMessageId: Long,
    val assistantMessageId: Long?,
    val createdAt: Long,
)

@Dao
interface ProjectWorkPlanGenerationWitnessDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: ProjectWorkPlanGenerationWitnessEntity)

    @Query("SELECT * FROM project_work_plan_generation_witnesses WHERE attemptId = :attemptId LIMIT 1")
    suspend fun getByAttempt(attemptId: String): ProjectWorkPlanGenerationWitnessEntity?

    @Query("SELECT * FROM project_work_plan_generation_witnesses WHERE attemptId = :attemptId LIMIT 1")
    fun observeByAttempt(attemptId: String): Flow<ProjectWorkPlanGenerationWitnessEntity?>

    /**
     * Records the exact assistant produced by the bound request. First
     * wins: only the bound request's first persisted answer is linked, and
     * only while none is linked yet.
     */
    @Query(
        "UPDATE project_work_plan_generation_witnesses SET assistantMessageId = :assistantMessageId " +
            "WHERE attemptId = :attemptId AND requestId = :requestId AND assistantMessageId IS NULL",
    )
    suspend fun linkAssistant(attemptId: String, requestId: String, assistantMessageId: Long): Int

    @Query("DELETE FROM project_work_plan_generation_witnesses WHERE attemptId = :attemptId")
    suspend fun deleteByAttempt(attemptId: String): Int

    @Query("DELETE FROM project_work_plan_generation_witnesses WHERE planId = :planId AND stepId = :stepId")
    suspend fun deleteByStep(planId: String, stepId: String): Int
}
