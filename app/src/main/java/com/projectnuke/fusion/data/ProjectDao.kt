package com.projectnuke.fusion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Projects provide an explicit local context boundary. Each conversation
 * belongs to at most ONE project (enforced by the relation primary key); one
 * project holds many conversations, instructions, and bounded shared files.
 *
 * Deleting a conversation cascades its relation away; deleting a project
 * cascades its relations while its conversations remain in normal history.
 */
@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProject(project: ProjectEntity): Long

    @Query("SELECT * FROM projects WHERE deletionPending = 0 ORDER BY updatedAt DESC, id DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE deletionPending = 0 ORDER BY updatedAt DESC, id DESC")
    suspend fun getProjects(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE deletionPending = 0 ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun getRecentProjectsBounded(limit: Int): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :projectId AND deletionPending = 0 LIMIT 1")
    suspend fun getProjectById(projectId: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :projectId LIMIT 1")
    suspend fun getProjectForDeletion(projectId: Long): ProjectEntity?

    @Query("SELECT id FROM projects WHERE deletionPending = 1 AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getPendingDeletionsAfter(afterId: Long, limit: Int): List<Long>

    @Query("UPDATE projects SET deletionPending = 1 WHERE id = :projectId AND deletionPending = 0")
    suspend fun beginDeletion(projectId: Long): Int

    @Query("DELETE FROM projects WHERE id = :projectId AND deletionPending = 1")
    suspend fun finishDeletion(projectId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM projects WHERE name = :name COLLATE NOCASE)")
    suspend fun hasProjectName(name: String): Boolean

    /** Reads membership, the mutable project row, and files under one snapshot. */
    @Transaction
    suspend fun getProjectContextForConversation(
        conversationId: Long,
        pendingProjectId: Long? = null,
    ): ProjectWithFiles? {
        val projectId = if (conversationId > 0L) {
            getProjectIdForConversation(conversationId)
        } else {
            pendingProjectId
        } ?: return null
        val project = getProjectById(projectId) ?: return null
        return ProjectWithFiles(project, getProjectFiles(projectId))
    }

    @Query("UPDATE projects SET name = :name, updatedAt = :updatedAt WHERE id = :projectId AND deletionPending = 0")
    suspend fun updateProjectName(projectId: Long, name: String, updatedAt: Long)

    @Query("UPDATE projects SET instructions = :instructions, updatedAt = :updatedAt WHERE id = :projectId AND deletionPending = 0")
    suspend fun updateProjectInstructions(projectId: Long, instructions: String, updatedAt: Long): Int

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun assignConversation(relation: ProjectConversationEntity)

    @Query("DELETE FROM project_conversations WHERE conversationId = :conversationId")
    suspend fun removeAssignment(conversationId: Long)

    @Query("SELECT projectId FROM project_conversations WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getProjectIdForConversation(conversationId: Long): Long?

    @Query(
        """
        SELECT conversations.* FROM conversations
        INNER JOIN project_conversations ON conversations.id = project_conversations.conversationId
        WHERE project_conversations.projectId = :projectId
        ORDER BY conversations.isPinned DESC, conversations.updatedAt DESC
        """
    )
    suspend fun getConversationsForProject(projectId: Long): List<ConversationEntity>

    @Query("SELECT conversations.* FROM conversations INNER JOIN project_conversations ON conversations.id = project_conversations.conversationId WHERE project_conversations.projectId = :projectId ORDER BY conversations.updatedAt DESC, conversations.id DESC LIMIT :limit")
    suspend fun getConversationsForProjectBounded(projectId: Long, limit: Int): List<ConversationEntity>

    @Query("SELECT conversations.* FROM conversations INNER JOIN project_conversations ON conversations.id = project_conversations.conversationId WHERE project_conversations.projectId = :projectId AND conversations.isArchived = 0 ORDER BY conversations.updatedAt DESC, conversations.id DESC LIMIT :limit")
    suspend fun getActiveConversationsForProjectBounded(projectId: Long, limit: Int): List<ConversationEntity>

    @Query("SELECT COUNT(*) FROM project_conversations WHERE projectId = :projectId")
    suspend fun getAssignmentCount(projectId: Long): Int

    @Query("SELECT * FROM project_files WHERE projectId = :projectId ORDER BY addedAt ASC, id ASC")
    suspend fun getProjectFiles(projectId: Long): List<ProjectFileEntity>

    @Query("SELECT * FROM project_files WHERE projectId = :projectId ORDER BY addedAt ASC, id ASC LIMIT :limit")
    suspend fun getProjectFilesBounded(projectId: Long, limit: Int): List<ProjectFileEntity>

    @Query("SELECT * FROM project_files WHERE projectId = :projectId ORDER BY addedAt DESC, id DESC LIMIT :limit")
    suspend fun getRecentProjectFilesBounded(projectId: Long, limit: Int): List<ProjectFileEntity>

    @Query("SELECT * FROM project_files WHERE id = :fileId LIMIT 1")
    suspend fun getProjectFileById(fileId: Long): ProjectFileEntity?

    @Query("SELECT * FROM project_files WHERE projectId = :projectId ORDER BY addedAt ASC, id ASC")
    fun observeProjectFiles(projectId: Long): Flow<List<ProjectFileEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProjectFile(file: ProjectFileEntity): Long

    /**
     * Duplicate detection, capacity enforcement, and insertion share one
     * Room transaction. SQLite serializes competing writers, so the bounded
     * eight-file contract cannot be exceeded by concurrent callers.
     */
    @Transaction
    suspend fun insertProjectFileBounded(file: ProjectFileEntity): BoundedProjectFileInsertResult {
        if (getProjectById(file.projectId) == null) {
            return BoundedProjectFileInsertResult.UnknownProject
        }
        getProjectFileByPath(file.projectId, file.localPath)?.let { existing ->
            return BoundedProjectFileInsertResult.Existing(existing.id)
        }
        if (countProjectFiles(file.projectId) >= ProjectFileContract.MAX_PROJECT_FILES) {
            return BoundedProjectFileInsertResult.CapacityReached
        }
        return BoundedProjectFileInsertResult.Inserted(insertProjectFile(file))
    }

    @Transaction
    suspend fun insertHandoffProjectFileBounded(file: ProjectFileEntity): BoundedProjectFileInsertResult {
        val existingReceipt = file.sourceHandoffEventId?.let { eventId ->
            getProjectFileByHandoffEventId(eventId)
        }
        if (existingReceipt != null) {
            return if (existingReceipt.projectId == file.projectId) BoundedProjectFileInsertResult.Existing(existingReceipt.id)
            else BoundedProjectFileInsertResult.UnknownProject
        }
        return insertProjectFileBounded(file)
    }

    @Query("DELETE FROM project_files WHERE id = :fileId")
    suspend fun deleteProjectFile(fileId: Long): Int

    @Query("SELECT * FROM project_files WHERE projectId = :projectId AND localPath = :localPath LIMIT 1")
    suspend fun getProjectFileByPath(projectId: Long, localPath: String): ProjectFileEntity?

    @Query("SELECT * FROM project_files WHERE sourceHandoffEventId = :eventId LIMIT 1")
    suspend fun getProjectFileByHandoffEventId(eventId: String): ProjectFileEntity?

    @Query("SELECT * FROM project_files WHERE sourceHandoffEventId LIKE :eventIdPrefix ORDER BY id LIMIT :limit")
    suspend fun getProjectFilesByHandoffPrefix(eventIdPrefix: String, limit: Int): List<ProjectFileEntity>

    @Query("SELECT COUNT(*) FROM project_files WHERE projectId = :projectId")
    suspend fun countProjectFiles(projectId: Long): Int

    @Query("SELECT localPath FROM project_files")
    suspend fun getAllProjectFilePaths(): List<String>
}

data class ProjectWithFiles(
    val project: ProjectEntity,
    val files: List<ProjectFileEntity>,
)
