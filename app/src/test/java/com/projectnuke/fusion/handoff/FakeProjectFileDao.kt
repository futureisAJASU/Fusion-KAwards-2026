package com.projectnuke.fusion.handoff

import com.projectnuke.fusion.data.BoundedProjectFileInsertResult
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.ProjectConversationEntity
import com.projectnuke.fusion.data.ProjectDao
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileContract
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.data.ProjectWithFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Durable-effect stand-in for the real Room [ProjectDao].
 *
 * It reproduces the three behaviours the project document batch contract
 * depends on -- the row set, the eight-file capacity limit, and the unique
 * `(projectId, localPath)` / `sourceHandoffEventId` indices -- mirroring
 * `insertHandoffProjectFileBounded`. Methods outside that surface are
 * deliberately loud: these tests must not silently pass against a stub that
 * quietly returns plausible values.
 */
internal class FakeProjectDao {
    val projects = mutableMapOf<Long, ProjectEntity>()
    val rows = LinkedHashMap<String, ProjectFileEntity>()
    private var nextId = 1L

    suspend fun getProjectById(projectId: Long): ProjectEntity? = projects[projectId]

    suspend fun getProjectFilesByHandoffPrefix(prefix: String, limit: Int): List<ProjectFileEntity> =
        rows.values.filter { it.sourceHandoffEventId?.startsWith(prefix.removeSuffix("%")) == true }
            .take(limit)

    suspend fun countProjectFiles(projectId: Long): Int =
        rows.values.count { it.projectId == projectId }

    suspend fun getProjectFileByPath(projectId: Long, localPath: String): ProjectFileEntity? =
        rows.values.firstOrNull { it.projectId == projectId && it.localPath == localPath }

    suspend fun getProjectFileByHandoffEventId(eventId: String): ProjectFileEntity? =
        rows.values.firstOrNull { it.sourceHandoffEventId == eventId }

    suspend fun insertHandoffProjectFileBounded(
        file: ProjectFileEntity,
    ): BoundedProjectFileInsertResult {
        if (file.sourceHandoffEventId != null) {
            val receipt = getProjectFileByHandoffEventId(file.sourceHandoffEventId)
            if (receipt != null) {
                return if (receipt.projectId == file.projectId) {
                    BoundedProjectFileInsertResult.Existing(receipt.id)
                } else {
                    BoundedProjectFileInsertResult.UnknownProject
                }
            }
        }
        if (projects[file.projectId] == null) return BoundedProjectFileInsertResult.UnknownProject
        getProjectFileByPath(file.projectId, file.localPath)?.let {
            return BoundedProjectFileInsertResult.Existing(it.id)
        }
        if (countProjectFiles(file.projectId) >= ProjectFileContract.MAX_PROJECT_FILES) {
            return BoundedProjectFileInsertResult.CapacityReached
        }
        val id = nextId++
        rows["handoff-$id"] = file.copy(id = id)
        return BoundedProjectFileInsertResult.Inserted(id)
    }

    fun insertRow(projectId: Long, key: String, path: String) {
        val id = nextId++
        rows[key] = ProjectFileEntity(
            id = id, projectId = projectId, displayName = key,
            mimeType = "text/plain", localPath = path, addedAt = 1,
        )
    }

    /**
     * Rollback-aware commit seam with the exact contract the production fix
     * relies on: the block's mutations roll back when it throws
     * [ProjectFileBatchRollback] and are kept when it returns normally.
     */
    val rollbackAwareCommit: ProjectFileBatchCommit = ProjectFileBatchCommit { block ->
        val snapshot = LinkedHashMap(rows)
        try {
            block()
        } catch (rollback: ProjectFileBatchRollback) {
            rows.clear()
            rows.putAll(snapshot)
            false
        }
    }

    /** Room-shaped facade so production code can call the DAO directly. */
    val handle: ProjectDao = object : ProjectDao {
        override suspend fun insertProject(project: ProjectEntity): Long = error("unused")
        override fun observeProjects(): Flow<List<ProjectEntity>> = emptyFlow()
        override suspend fun getProjects(): List<ProjectEntity> = projects.values.toList()
        override suspend fun getRecentProjectsBounded(limit: Int): List<ProjectEntity> = emptyList()
        override suspend fun getProjectById(projectId: Long): ProjectEntity? =
            this@FakeProjectDao.getProjectById(projectId)

        override suspend fun getProjectForDeletion(projectId: Long): ProjectEntity? = projects[projectId]
        override suspend fun getPendingDeletionsAfter(afterId: Long, limit: Int): List<Long> = emptyList()
        override suspend fun beginDeletion(projectId: Long): Int = error("unused")
        override suspend fun finishDeletion(projectId: Long): Int = error("unused")
        override suspend fun hasProjectName(name: String): Boolean = error("unused")
        override suspend fun getProjectContextForConversation(
            conversationId: Long,
            pendingProjectId: Long?,
        ): ProjectWithFiles? = error("unused")

        override suspend fun updateProjectName(projectId: Long, name: String, updatedAt: Long) = Unit
        override suspend fun updateProjectInstructions(
            projectId: Long,
            instructions: String,
            updatedAt: Long,
        ): Int = error("unused")

        override suspend fun deleteProject(projectId: Long) = Unit
        override suspend fun assignConversation(relation: ProjectConversationEntity) = Unit
        override suspend fun removeAssignment(conversationId: Long) = Unit
        override suspend fun getProjectIdForConversation(conversationId: Long): Long? = error("unused")
        override suspend fun getConversationsForProject(projectId: Long): List<ConversationEntity> =
            error("unused")

        override suspend fun getConversationsForProjectBounded(
            projectId: Long,
            limit: Int,
        ): List<ConversationEntity> = error("unused")

        override suspend fun getActiveConversationsForProjectBounded(
            projectId: Long,
            limit: Int,
        ): List<ConversationEntity> = error("unused")

        override suspend fun getAssignmentCount(projectId: Long): Int = error("unused")
        override suspend fun getProjectFiles(projectId: Long): List<ProjectFileEntity> =
            rows.values.filter { it.projectId == projectId }

        override suspend fun getProjectFilesBounded(
            projectId: Long,
            limit: Int,
        ): List<ProjectFileEntity> = rows.values.filter { it.projectId == projectId }.take(limit)

        override suspend fun getRecentProjectFilesBounded(
            projectId: Long,
            limit: Int,
        ): List<ProjectFileEntity> = rows.values.filter { it.projectId == projectId }.takeLast(limit)

        override suspend fun getProjectFileById(fileId: Long): ProjectFileEntity? =
            rows.values.firstOrNull { it.id == fileId }

        override fun observeProjectFiles(projectId: Long): Flow<List<ProjectFileEntity>> = emptyFlow()
        override suspend fun insertProjectFile(file: ProjectFileEntity): Long = error("unused")
        override suspend fun insertProjectFileBounded(
            file: ProjectFileEntity,
        ): BoundedProjectFileInsertResult = insertHandoffProjectFileBounded(file)

        override suspend fun insertHandoffProjectFileBounded(
            file: ProjectFileEntity,
        ): BoundedProjectFileInsertResult = this@FakeProjectDao.insertHandoffProjectFileBounded(file)

        override suspend fun deleteProjectFile(fileId: Long): Int = error("unused")
        override suspend fun getProjectFileByPath(projectId: Long, localPath: String): ProjectFileEntity? =
            this@FakeProjectDao.getProjectFileByPath(projectId, localPath)

        override suspend fun getProjectFileByHandoffEventId(eventId: String): ProjectFileEntity? =
            this@FakeProjectDao.getProjectFileByHandoffEventId(eventId)

        override suspend fun getProjectFilesByHandoffPrefix(
            eventIdPrefix: String,
            limit: Int,
        ): List<ProjectFileEntity> = this@FakeProjectDao.getProjectFilesByHandoffPrefix(eventIdPrefix, limit)

        override suspend fun countProjectFiles(projectId: Long): Int =
            this@FakeProjectDao.countProjectFiles(projectId)

        override suspend fun getAllProjectFilePaths(): List<String> = rows.values.map { it.localPath }
    }
}