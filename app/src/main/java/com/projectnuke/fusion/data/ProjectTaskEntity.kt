package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.ColumnInfo
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
    tableName = "project_tasks",
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
        Index(value = ["projectId", "state", "updatedAt"]),
        Index(value = ["reminderEnabled", "reminderAt"]),
    ],
)
data class ProjectTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val title: String,
    val note: String,
    val state: String = "OPEN",
    val sourceConversationId: Long? = null,
    val sourceMessageId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val reminderAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val reminderEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "0") val reminderGeneration: Long = 0L,
    @ColumnInfo(defaultValue = "0") val notificationCleanupPending: Boolean = false,
    @ColumnInfo(defaultValue = "0") val deletionPending: Boolean = false,
)

@Dao
interface ProjectTaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: ProjectTaskEntity): Long

    @Query("SELECT COUNT(*) FROM project_tasks WHERE projectId = :projectId")
    suspend fun countForProject(projectId: Long): Int

    @Query("SELECT * FROM project_tasks WHERE projectId = :projectId ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun getForProjectBounded(projectId: Long, limit: Int): List<ProjectTaskEntity>

    @Query("SELECT * FROM project_tasks WHERE projectId = :projectId AND state = 'OPEN' AND deletionPending = 0 ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun getOpenForProjectBounded(projectId: Long, limit: Int): List<ProjectTaskEntity>

    @Query("SELECT * FROM project_tasks WHERE projectId = :projectId AND state = 'OPEN' AND deletionPending = 0 ORDER BY CASE WHEN reminderEnabled = 1 AND reminderAt IS NOT NULL AND reminderAt <= :now THEN 0 ELSE 1 END, CASE WHEN reminderEnabled = 1 AND reminderAt IS NOT NULL AND reminderAt <= :now THEN reminderAt ELSE 9223372036854775807 END, updatedAt DESC, id DESC LIMIT :limit")
    suspend fun getOpenForProjectPulseBounded(projectId: Long, now: Long, limit: Int): List<ProjectTaskEntity>

    @Query("SELECT * FROM project_tasks WHERE projectId = :projectId AND deletionPending = 0 ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun getRecentForProjectBounded(projectId: Long, limit: Int): List<ProjectTaskEntity>

    @Transaction
    suspend fun insertBounded(task: ProjectTaskEntity): ProjectTaskInsertResult {
        if (isProjectDeleting(task.projectId)) return ProjectTaskInsertResult.ProjectUnavailable
        if (countForProject(task.projectId) >= MAX_PROJECT_TASKS) return ProjectTaskInsertResult.CapacityReached
        return ProjectTaskInsertResult.Inserted(insert(task))
    }

    @Query("SELECT EXISTS(SELECT 1 FROM projects WHERE id = :projectId AND deletionPending = 1)")
    suspend fun isProjectDeleting(projectId: Long): Boolean

    @Query("SELECT * FROM project_tasks WHERE projectId = :projectId AND deletionPending = 0 ORDER BY CASE WHEN state = 'OPEN' THEN 0 ELSE 1 END, updatedAt DESC, id DESC LIMIT :limit")
    fun observeForProject(projectId: Long, limit: Int = 64): Flow<List<ProjectTaskEntity>>

    @Query("SELECT * FROM project_tasks WHERE id = :taskId AND projectId = :projectId LIMIT 1")
    suspend fun get(taskId: Long, projectId: Long): ProjectTaskEntity?

    @Query("UPDATE project_tasks SET title = :title, note = :note, updatedAt = :updatedAt WHERE id = :taskId AND projectId = :projectId AND deletionPending = 0")
    suspend fun update(taskId: Long, projectId: Long, title: String, note: String, updatedAt: Long): Int

    @Query("UPDATE project_tasks SET state = :state, updatedAt = :updatedAt, reminderEnabled = CASE WHEN :state = 'DONE' THEN 0 ELSE reminderEnabled END, reminderGeneration = CASE WHEN :state = 'DONE' THEN reminderGeneration + 1 ELSE reminderGeneration END, notificationCleanupPending = CASE WHEN :state = 'DONE' THEN 1 ELSE notificationCleanupPending END WHERE id = :taskId AND projectId = :projectId AND deletionPending = 0")
    suspend fun setState(taskId: Long, projectId: Long, state: String, updatedAt: Long): Int

    @Query("SELECT * FROM project_tasks WHERE notificationCleanupPending = 1 AND deletionPending = 0 AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getPendingNotificationCleanupAfter(afterId: Long, limit: Int): List<ProjectTaskEntity>

    @Query("SELECT * FROM project_tasks WHERE deletionPending = 1 AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getPendingDeletionAfter(afterId: Long, limit: Int): List<ProjectTaskEntity>

    @Query("UPDATE project_tasks SET deletionPending = 1, reminderEnabled = 0, reminderAt = NULL, reminderGeneration = reminderGeneration + 1, notificationCleanupPending = 0 WHERE id = :taskId AND projectId = :projectId AND deletionPending = 0")
    suspend fun beginDeletion(taskId: Long, projectId: Long): Int

    @Query("DELETE FROM project_tasks WHERE id = :taskId AND projectId = :projectId AND deletionPending = 1 AND reminderGeneration = :expectedGeneration")
    suspend fun finishDeletion(taskId: Long, projectId: Long, expectedGeneration: Long): Int

    @Query("UPDATE project_tasks SET deletionPending = 1, reminderEnabled = 0, reminderAt = NULL, reminderGeneration = reminderGeneration + 1, notificationCleanupPending = 0 WHERE projectId = :projectId AND deletionPending = 0")
    suspend fun beginProjectChildrenDeletion(projectId: Long): Int

    @Query("UPDATE project_tasks SET notificationCleanupPending = 0 WHERE id = :taskId AND reminderGeneration = :expectedGeneration AND notificationCleanupPending = 1 AND deletionPending = 0")
    suspend fun clearNotificationCleanupPending(taskId: Long, expectedGeneration: Long): Int

    @Query("UPDATE project_tasks SET notificationCleanupPending = 1 WHERE id = :taskId AND projectId = :projectId AND deletionPending = 0 AND projectId IN (SELECT id FROM projects WHERE deletionPending = 0)")
    suspend fun markNotificationCleanupPending(taskId: Long, projectId: Long): Int

    @Query("DELETE FROM project_tasks WHERE id = :taskId AND projectId = :projectId AND deletionPending = 1")
    suspend fun delete(taskId: Long, projectId: Long): Int

    @Query("SELECT * FROM project_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getById(taskId: Long): ProjectTaskEntity?

    @Query("SELECT project_tasks.id, project_tasks.projectId, project_tasks.title, projects.name AS projectName, project_tasks.updatedAt FROM project_tasks INNER JOIN projects ON projects.id = project_tasks.projectId WHERE project_tasks.state = 'OPEN' AND project_tasks.deletionPending = 0 AND projects.deletionPending = 0 ORDER BY project_tasks.updatedAt DESC, project_tasks.id DESC LIMIT :limit")
    suspend fun getRecentOpenShortcuts(limit: Int): List<ProjectTaskShortcutRow>

    @Query("SELECT project_tasks.* FROM project_tasks INNER JOIN projects ON projects.id = project_tasks.projectId WHERE project_tasks.reminderEnabled = 1 AND project_tasks.reminderAt IS NOT NULL AND project_tasks.deletionPending = 0 AND projects.deletionPending = 0 ORDER BY project_tasks.reminderAt ASC, project_tasks.id ASC LIMIT :limit")
    suspend fun getEnabledReminders(limit: Int): List<ProjectTaskEntity>

    @Query("SELECT project_tasks.* FROM project_tasks INNER JOIN projects ON projects.id = project_tasks.projectId WHERE project_tasks.reminderEnabled = 1 AND project_tasks.reminderAt IS NOT NULL AND project_tasks.reminderAt <= :now AND project_tasks.state = 'OPEN' AND project_tasks.deletionPending = 0 AND projects.deletionPending = 0 ORDER BY project_tasks.reminderAt ASC, project_tasks.id ASC LIMIT :limit")
    suspend fun getOverdueReminders(now: Long, limit: Int): List<ProjectTaskEntity>

    @Query("SELECT id FROM project_tasks WHERE projectId = :projectId ORDER BY id ASC LIMIT :limit")
    suspend fun getReminderTaskIdsForProject(projectId: Long, limit: Int): List<Long>

    @Query("SELECT COUNT(*) FROM project_tasks INNER JOIN projects ON projects.id = project_tasks.projectId WHERE project_tasks.reminderEnabled = 1 AND project_tasks.deletionPending = 0 AND projects.deletionPending = 0")
    suspend fun countEnabledReminders(): Int

    @Query("UPDATE project_tasks SET reminderAt = :at, reminderEnabled = :enabled, reminderGeneration = reminderGeneration + 1, notificationCleanupPending = 1 WHERE id = :taskId AND projectId = :projectId AND deletionPending = 0 AND projectId IN (SELECT id FROM projects WHERE deletionPending = 0)")
    suspend fun updateReminder(taskId: Long, projectId: Long, at: Long?, enabled: Boolean): Int

    @Query("UPDATE project_tasks SET reminderEnabled = 0 WHERE id = :taskId AND reminderAt = :expectedAt AND reminderGeneration = :expectedGeneration AND reminderEnabled = 1 AND state = 'OPEN' AND deletionPending = 0 AND projectId IN (SELECT id FROM projects WHERE deletionPending = 0)")
    suspend fun consumeReminder(taskId: Long, expectedAt: Long, expectedGeneration: Long): Int

    @Transaction
    suspend fun updateReminderBounded(taskId: Long, projectId: Long, at: Long): ReminderUpdateResult {
        val existing = get(taskId, projectId) ?: return ReminderUpdateResult.UnknownTask
        if (existing.state != "OPEN") return ReminderUpdateResult.TaskComplete
        if (!existing.reminderEnabled && countEnabledReminders() >= MAX_ENABLED_TASK_REMINDERS) {
            return ReminderUpdateResult.CapacityReached
        }
        val updated = updateReminder(taskId, projectId, at, true)
        return if (updated == 1) ReminderUpdateResult.Updated else ReminderUpdateResult.UnknownTask
    }
}

data class ProjectTaskShortcutRow(
    val id: Long,
    val projectId: Long,
    val title: String,
    val projectName: String,
    val updatedAt: Long,
)

sealed interface ProjectTaskInsertResult {
    data class Inserted(val id: Long) : ProjectTaskInsertResult
    data object CapacityReached : ProjectTaskInsertResult
    data object ProjectUnavailable : ProjectTaskInsertResult
}

const val MAX_PROJECT_TASKS = 64
const val MAX_ENABLED_TASK_REMINDERS = 128

sealed interface ReminderUpdateResult {
    data object Updated : ReminderUpdateResult
    data object UnknownTask : ReminderUpdateResult
    data object TaskComplete : ReminderUpdateResult
    data object CapacityReached : ReminderUpdateResult
}
