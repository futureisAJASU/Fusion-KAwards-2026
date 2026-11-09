package com.projectnuke.fusion.data

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
    tableName = "project_decisions",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProjectDecisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["supersedesDecisionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["projectId", "status", "updatedAt"]),
        Index(value = ["projectId", "createdAt"]),
        Index("supersedesDecisionId"),
    ],
)
data class ProjectDecisionEntity(
    @PrimaryKey val id: String,
    val projectId: Long,
    val title: String,
    val rationale: String,
    val status: String,
    val supersedesDecisionId: String? = null,
    /** Provenance IDs intentionally survive source deletion as historical facts. */
    val sourceConversationId: Long? = null,
    val sourceMessageId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface ProjectDecisionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(decision: ProjectDecisionEntity)

    @Query("SELECT * FROM project_decisions WHERE projectId = :projectId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(projectId: Long, limit: Int): Flow<List<ProjectDecisionEntity>>

    @Query("SELECT * FROM project_decisions WHERE projectId = :projectId AND status = 'ACTIVE' ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeActive(projectId: Long, limit: Int): Flow<List<ProjectDecisionEntity>>

    @Query("SELECT * FROM project_decisions WHERE projectId = :projectId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecent(projectId: Long, limit: Int): List<ProjectDecisionEntity>

    @Query("SELECT * FROM project_decisions WHERE projectId = :projectId AND status = 'ACTIVE' ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getActiveDecisions(projectId: Long, limit: Int): List<ProjectDecisionEntity>

    @Query("SELECT * FROM project_decisions WHERE id = :id AND projectId = :projectId LIMIT 1")
    suspend fun get(id: String, projectId: Long): ProjectDecisionEntity?

    @Query("SELECT COUNT(*) FROM project_decisions WHERE projectId = :projectId")
    suspend fun count(projectId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM projects WHERE id = :projectId)")
    suspend fun projectExists(projectId: Long): Boolean

    @Query("UPDATE project_decisions SET status = 'SUPERSEDED', updatedAt = :updatedAt WHERE id = :oldId AND projectId = :projectId AND status = 'ACTIVE'")
    suspend fun markSuperseded(projectId: Long, oldId: String, updatedAt: Long): Int

    @Transaction
    suspend fun insertDecision(decision: ProjectDecisionEntity): ProjectDecisionWriteResult {
        if (!projectExists(decision.projectId)) return ProjectDecisionWriteResult.ProjectMissing
        if (count(decision.projectId) >= MAX_PROJECT_DECISIONS) return ProjectDecisionWriteResult.CapacityReached
        val oldId = decision.supersedesDecisionId
        if (oldId != null) {
            val old = get(oldId, decision.projectId)
                ?: return ProjectDecisionWriteResult.SupersededDecisionMissing
            if (old.status != "ACTIVE") return ProjectDecisionWriteResult.SupersededDecisionInactive
            if (markSuperseded(decision.projectId, oldId, decision.updatedAt) != 1) {
                return ProjectDecisionWriteResult.SupersededDecisionInactive
            }
        }
        insert(decision)
        return ProjectDecisionWriteResult.Inserted(decision.id)
    }
}

sealed interface ProjectDecisionWriteResult {
    data class Inserted(val id: String) : ProjectDecisionWriteResult
    data object ProjectMissing : ProjectDecisionWriteResult
    data object SupersededDecisionMissing : ProjectDecisionWriteResult
    data object SupersededDecisionInactive : ProjectDecisionWriteResult
    data object CapacityReached : ProjectDecisionWriteResult
}

const val MAX_PROJECT_DECISIONS = 256
