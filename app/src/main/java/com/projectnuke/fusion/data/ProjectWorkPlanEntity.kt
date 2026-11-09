package com.projectnuke.fusion.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "project_work_plans",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("projectId"),
        Index(value = ["projectId", "status", "updatedAt"]),
        Index(value = ["projectId", "archivedAt"]),
    ],
)
data class ProjectWorkPlanEntity(
    @PrimaryKey val id: String,
    val projectId: Long,
    val title: String,
    val goal: String,
    val desiredOutput: String,
    val constraintText: String,
    val status: String,
    val currentStepPosition: Int,
    val archivedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "project_work_plan_steps",
    foreignKeys = [
        ForeignKey(
            entity = ProjectWorkPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProjectTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["linkedTaskId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("planId"),
        Index(value = ["planId", "position"], unique = true),
        Index(value = ["linkedTaskId"], unique = true),
    ],
)
data class ProjectWorkPlanStepEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val position: Int,
    val title: String,
    val instruction: String,
    val type: String,
    val state: String,
    @ColumnInfo(defaultValue = "0") val attemptCount: Int = 0,
    val activeAttemptId: String? = null,
    val resultKind: String? = null,
    val resultId: String? = null,
    @ColumnInfo(defaultValue = "''") val previousResultRefs: String = "",
    val linkedTaskId: Long? = null,
    @ColumnInfo(defaultValue = "0") val interrupted: Boolean = false,
    val failureKind: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface ProjectWorkPlanDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlan(plan: ProjectWorkPlanEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSteps(steps: List<ProjectWorkPlanStepEntity>)

    @Transaction
    suspend fun insertPlanWithSteps(plan: ProjectWorkPlanEntity, steps: List<ProjectWorkPlanStepEntity>) {
        insertPlan(plan)
        insertSteps(steps)
    }

    @Query("SELECT * FROM project_work_plans WHERE projectId = :projectId AND archivedAt IS NULL ORDER BY updatedAt DESC, createdAt DESC LIMIT :limit")
    fun observePlans(projectId: Long, limit: Int = 32): Flow<List<ProjectWorkPlanEntity>>

    @Query("SELECT * FROM project_work_plans WHERE projectId = :projectId AND archivedAt IS NULL AND status = 'ACTIVE' ORDER BY updatedAt DESC, createdAt DESC LIMIT 1")
    suspend fun getActivePlan(projectId: Long): ProjectWorkPlanEntity?

    @Query("SELECT * FROM project_work_plans WHERE projectId = :projectId ORDER BY updatedAt DESC, createdAt DESC LIMIT :limit")
    suspend fun getPlans(projectId: Long, limit: Int = 32): List<ProjectWorkPlanEntity>

    @Query("SELECT * FROM project_work_plans WHERE projectId = :projectId AND archivedAt IS NULL ORDER BY updatedAt DESC, createdAt DESC LIMIT :limit")
    suspend fun getPlansExcludingArchived(projectId: Long, limit: Int = 32): List<ProjectWorkPlanEntity>

    @Query("SELECT * FROM project_work_plans WHERE id = :planId AND projectId = :projectId LIMIT 1")
    suspend fun getPlan(planId: String, projectId: Long): ProjectWorkPlanEntity?

    @Query("SELECT * FROM project_work_plans WHERE id = :planId LIMIT 1")
    suspend fun getPlanById(planId: String): ProjectWorkPlanEntity?

    @Query("SELECT * FROM project_work_plan_steps WHERE planId = :planId ORDER BY position ASC")
    suspend fun getSteps(planId: String): List<ProjectWorkPlanStepEntity>

    @Query("SELECT * FROM project_work_plan_steps WHERE id = :stepId AND planId = :planId LIMIT 1")
    suspend fun getStep(stepId: String, planId: String): ProjectWorkPlanStepEntity?

    @Query("UPDATE project_work_plans SET title = :title, goal = :goal, desiredOutput = :desiredOutput, constraintText = :constraintText, updatedAt = :updatedAt WHERE id = :planId AND projectId = :projectId AND status IN ('ACTIVE', 'PAUSED') AND archivedAt IS NULL")
    suspend fun updatePlanText(planId: String, projectId: Long, title: String, goal: String, desiredOutput: String, constraintText: String, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET position = :position, updatedAt = :updatedAt WHERE id = :stepId AND planId = :planId")
    suspend fun updatePosition(planId: String, stepId: String, position: Int, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET state = :state, updatedAt = :updatedAt WHERE id = :stepId AND planId = :planId AND activeAttemptId IS NULL")
    suspend fun updateStepState(planId: String, stepId: String, state: String, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET title = :title, instruction = :instruction, updatedAt = :updatedAt WHERE id = :stepId AND planId = :planId AND state IN ('PENDING', 'READY', 'FAILED') AND activeAttemptId IS NULL")
    suspend fun updateStepText(planId: String, stepId: String, title: String, instruction: String, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET state = 'RUNNING', activeAttemptId = :attemptId, interrupted = 0, updatedAt = :updatedAt WHERE id = :stepId AND planId = :planId AND position = :position AND state IN ('READY', 'FAILED', 'REVIEW') AND activeAttemptId IS NULL")
    suspend fun acquireStep(planId: String, stepId: String, position: Int, attemptId: String, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET state = 'REVIEW', attemptCount = attemptCount + 1, activeAttemptId = NULL, resultKind = :resultKind, resultId = :resultId, previousResultRefs = :previousResultRefs, interrupted = 0, failureKind = NULL, updatedAt = :updatedAt WHERE id = :stepId AND planId = :planId AND state = 'RUNNING' AND activeAttemptId = :attemptId AND attemptCount < :maxAttempts")
    suspend fun finishStepAttempt(planId: String, stepId: String, attemptId: String, resultKind: String?, resultId: String?, previousResultRefs: String, maxAttempts: Int, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET state = 'FAILED', attemptCount = attemptCount + 1, activeAttemptId = NULL, interrupted = 0, failureKind = :failureKind, updatedAt = :updatedAt WHERE id = :stepId AND planId = :planId AND state = 'RUNNING' AND activeAttemptId = :attemptId AND attemptCount < :maxAttempts")
    suspend fun failStepAttempt(planId: String, stepId: String, attemptId: String, failureKind: String, maxAttempts: Int, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET state = 'READY', activeAttemptId = NULL, interrupted = 0, failureKind = NULL, updatedAt = :updatedAt WHERE id = :stepId AND planId = :planId AND state = 'RUNNING' AND activeAttemptId = :attemptId")
    suspend fun cancelStepAttempt(planId: String, stepId: String, attemptId: String, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET state = 'READY', activeAttemptId = NULL, interrupted = 1, failureKind = NULL, updatedAt = :updatedAt WHERE planId = :planId AND state = 'RUNNING'")
    suspend fun recoverRunningSteps(planId: String, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET state = 'READY', activeAttemptId = NULL, interrupted = 0, failureKind = NULL, updatedAt = :updatedAt WHERE planId = :planId AND state = 'RUNNING'")
    suspend fun releaseRunningSteps(planId: String, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET state = 'COMPLETED', activeAttemptId = NULL, updatedAt = :updatedAt WHERE id = :stepId AND planId = :planId AND state = 'REVIEW' AND activeAttemptId IS NULL AND linkedTaskId IS NULL")
    suspend fun completeReviewedStep(planId: String, stepId: String, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET state = 'COMPLETED', updatedAt = :updatedAt WHERE id = :stepId AND planId = :planId AND position = :position AND type = 'MANUAL' AND state = 'READY' AND activeAttemptId IS NULL")
    suspend fun completeManualStep(planId: String, stepId: String, position: Int, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET state = 'SKIPPED', activeAttemptId = NULL, updatedAt = :updatedAt WHERE id = :stepId AND planId = :planId AND position = :position AND state IN ('READY', 'REVIEW', 'FAILED') AND activeAttemptId IS NULL")
    suspend fun skipStep(planId: String, stepId: String, position: Int, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET state = 'READY', updatedAt = :updatedAt WHERE planId = :planId AND position = :position AND state = 'PENDING'")
    suspend fun readyNextStep(planId: String, position: Int, updatedAt: Long): Int

    @Query("UPDATE project_work_plans SET currentStepPosition = :position, status = :status, updatedAt = :updatedAt WHERE id = :planId AND projectId = :projectId AND archivedAt IS NULL")
    suspend fun updateProgress(planId: String, projectId: Long, position: Int, status: String, updatedAt: Long): Int

    @Query("UPDATE project_work_plans SET status = :status, updatedAt = :updatedAt WHERE id = :planId AND projectId = :projectId AND status IN ('ACTIVE', 'PAUSED') AND archivedAt IS NULL")
    suspend fun updateStatus(planId: String, projectId: Long, status: String, updatedAt: Long): Int

    @Query("UPDATE project_work_plans SET archivedAt = :archivedAt, updatedAt = :updatedAt WHERE id = :planId AND projectId = :projectId AND status IN ('COMPLETED', 'PAUSED', 'CANCELLED') AND archivedAt IS NULL")
    suspend fun archivePlan(planId: String, projectId: Long, archivedAt: Long, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET linkedTaskId = :taskId, resultKind = 'TASK', resultId = :taskRef, previousResultRefs = :previousResultRefs, state = 'REVIEW', attemptCount = attemptCount + 1, activeAttemptId = NULL, failureKind = NULL, updatedAt = :updatedAt WHERE id = :stepId AND planId = :planId AND state = 'RUNNING' AND activeAttemptId = :attemptId AND attemptCount < :maxAttempts")
    suspend fun linkTask(planId: String, stepId: String, taskId: Long, taskRef: String, attemptId: String, previousResultRefs: String, maxAttempts: Int, updatedAt: Long): Int

    @Query("UPDATE project_work_plan_steps SET state = 'COMPLETED', activeAttemptId = NULL, updatedAt = :updatedAt WHERE planId = :planId AND linkedTaskId = :taskId AND state IN ('RUNNING', 'REVIEW', 'READY')")
    suspend fun completeLinkedTask(planId: String, taskId: Long, updatedAt: Long): Int

    @Query("SELECT * FROM project_work_plan_steps WHERE linkedTaskId = :taskId LIMIT 1")
    suspend fun getStepForTask(taskId: Long): ProjectWorkPlanStepEntity?

    @Query("UPDATE project_work_plan_steps SET resultKind = 'DECISION', resultId = :decisionId, previousResultRefs = :previousResultRefs, state = 'REVIEW', attemptCount = attemptCount + 1, activeAttemptId = NULL, failureKind = NULL, updatedAt = :updatedAt WHERE id = :stepId AND planId = :planId AND type = 'PROPOSE_DECISION' AND state = 'RUNNING' AND activeAttemptId = :attemptId AND attemptCount < :maxAttempts")
    suspend fun attachDecisionResult(planId: String, stepId: String, attemptId: String, decisionId: String, previousResultRefs: String, maxAttempts: Int, updatedAt: Long): Int
}
