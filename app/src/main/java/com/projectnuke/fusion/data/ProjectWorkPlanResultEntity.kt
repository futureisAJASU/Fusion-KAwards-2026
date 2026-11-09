package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Issues 2 + 4: durable SELECTION/EXTRACTION result provenance (v26).
 *
 * Never stores a process-local snapshot UUID or raw source excerpts. One
 * row per specialized attempt, keyed by the unique [attemptId], carrying
 * the bounded content fingerprint ([resultId]) plus the JSON of actual
 * selected source identities + display titles.
 *
 * The fingerprint is deterministic and may repeat across attempts — it is
 * indexed, never globally unique. Lifecycle ownership is enforced by
 * foreign keys: deleting the owning Work Plan or step cascades its result
 * provenance automatically, so no titles survive their plan.
 */
@Entity(
    tableName = "project_work_plan_result_sources",
    foreignKeys = [
        ForeignKey(
            entity = ProjectWorkPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProjectWorkPlanStepEntity::class,
            parentColumns = ["id"],
            childColumns = ["stepId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("planId"),
        Index("stepId"),
        Index("resultId"),
    ],
)
data class ProjectWorkPlanResultSourceEntity(
    @PrimaryKey val attemptId: String,
    val resultId: String,
    val planId: String,
    val stepId: String,
    val projectId: Long,
    val stepType: String,
    val resultKind: String,
    val extractionType: String?,
    val sourcesJson: String,
    val createdAt: Long,
)

@Dao
interface ProjectWorkPlanResultSourceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: ProjectWorkPlanResultSourceEntity)

    @Query("SELECT * FROM project_work_plan_result_sources WHERE attemptId = :attemptId LIMIT 1")
    suspend fun getByAttempt(attemptId: String): ProjectWorkPlanResultSourceEntity?

    @Query("SELECT * FROM project_work_plan_result_sources WHERE resultId = :resultId ORDER BY createdAt DESC, attemptId DESC LIMIT :limit")
    suspend fun getByResultId(resultId: String, limit: Int = 8): List<ProjectWorkPlanResultSourceEntity>

    @Query("SELECT * FROM project_work_plan_result_sources WHERE stepId = :stepId AND planId = :planId ORDER BY createdAt DESC, attemptId DESC LIMIT :limit")
    suspend fun getByStep(planId: String, stepId: String, limit: Int = 8): List<ProjectWorkPlanResultSourceEntity>
}
