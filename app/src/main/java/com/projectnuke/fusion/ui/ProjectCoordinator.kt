package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ProjectConversationEntity
import com.projectnuke.fusion.data.ProjectDao
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.data.BoundedProjectFileInsertResult
import com.projectnuke.fusion.chat.ProjectContextSnapshot
import com.projectnuke.fusion.chat.ProjectInstructionPromptPolicy
import com.projectnuke.fusion.util.normalizeUserVisibleName
import kotlinx.coroutines.CancellationException

/**
 * One conversation belongs to at most one project; one project holds many
 * conversations, instructions, and explicit local files. Deleting a project removes only its relations and files —
 * conversations remain in normal history. Conversation deletion cascades
 * its relation away via foreign keys.
 */
internal enum class ProjectFailure {
    EMPTY_NAME,
    INSTRUCTIONS_TOO_LONG,
    PROJECT_FILE_LIMIT,
    UNSUPPORTED_PROJECT_FILE,
    UNKNOWN_PROJECT,
    UNKNOWN_CONVERSATION,
    INVALID_CONVERSATION,
    PROJECT_FILE_NOT_FOUND,
    EMPTY_PROJECT_FILE,
    PROJECT_FILE_TOO_LARGE,
    UNREADABLE_PROJECT_FILE,
    DB_ERROR,
}

internal sealed interface ProjectOutcome<out T> {
    data class Ok<T>(val value: T) : ProjectOutcome<T>
    data class Failure(val reason: ProjectFailure) : ProjectOutcome<Nothing>
}

internal class ProjectCoordinator(
    private val projectDao: ProjectDao,
    private val chatDao: ChatDao,
    private val runInTransaction: suspend (suspend () -> Long) -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deleteProjectSafely: (suspend (Long) -> Boolean)? = null,
) {
    suspend fun createProject(name: String, assignConversationId: Long? = null): ProjectOutcome<Long> {
        if (name.trim().isEmpty()) return ProjectOutcome.Failure(ProjectFailure.EMPTY_NAME)
        val conversationId = assignConversationId?.takeIf { it > 0L }
        if (assignConversationId != null && conversationId == null) {
            return ProjectOutcome.Failure(ProjectFailure.INVALID_CONVERSATION)
        }
        // Validate before creating anything: no orphan project rows and no
        // invalid relations are ever fabricated.
        if (conversationId != null && chatDao.getConversationById(conversationId) == null) {
            return ProjectOutcome.Failure(ProjectFailure.UNKNOWN_CONVERSATION)
        }
        return try {
            val now = clock()
            val normalized = projectName(name)
            // The project row and its optional initial assignment commit as
            // ONE transaction: a failed assignment leaves no orphan project.
            // Production passes `db.withTransaction { ... }` as
            // [runInTransaction] (same seam as ConversationBranchCoordinator).
            val projectId = runInTransaction {
                val id = projectDao.insertProject(
                    ProjectEntity(name = normalized, createdAt = now, updatedAt = now),
                )
                if (conversationId != null) {
                    projectDao.assignConversation(
                        ProjectConversationEntity(
                            conversationId = conversationId,
                            projectId = id,
                            assignedAt = clock(),
                        ),
                    )
                }
                id
            }
            ProjectOutcome.Ok(projectId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ProjectOutcome.Failure(ProjectFailure.DB_ERROR)
        }
    }

    suspend fun assignConversation(projectId: Long, conversationId: Long): ProjectOutcome<Unit> {
        if (conversationId <= 0L) return ProjectOutcome.Failure(ProjectFailure.INVALID_CONVERSATION)
        return try {
            projectDao.getProjectById(projectId)
                ?: return ProjectOutcome.Failure(ProjectFailure.UNKNOWN_PROJECT)
            chatDao.getConversationById(conversationId)
                ?: return ProjectOutcome.Failure(ProjectFailure.UNKNOWN_CONVERSATION)
            projectDao.assignConversation(
                ProjectConversationEntity(
                    conversationId = conversationId,
                    projectId = projectId,
                    assignedAt = clock(),
                ),
            )
            ProjectOutcome.Ok(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ProjectOutcome.Failure(ProjectFailure.DB_ERROR)
        }
    }

    suspend fun updateInstructions(projectId: Long, rawInstructions: String): ProjectOutcome<Unit> {
        if (!ProjectInstructionPromptPolicy.isWithinLimit(rawInstructions)) {
            return ProjectOutcome.Failure(ProjectFailure.INSTRUCTIONS_TOO_LONG)
        }
        return try {
            val updatedRows = projectDao.updateProjectInstructions(
                projectId = projectId,
                instructions = ProjectInstructionPromptPolicy.normalizeForStorage(rawInstructions),
                updatedAt = clock(),
            )
            if (updatedRows == 1) {
                ProjectOutcome.Ok(Unit)
            } else {
                ProjectOutcome.Failure(ProjectFailure.UNKNOWN_PROJECT)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ProjectOutcome.Failure(ProjectFailure.DB_ERROR)
        }
    }

    /** Captures one internally consistent project state for a request. */
    suspend fun loadContextSnapshot(
        conversationId: Long,
        pendingProjectId: Long? = null,
    ): ProjectContextSnapshot? {
        val rows = projectDao.getProjectContextForConversation(conversationId, pendingProjectId) ?: return null
        return ProjectContextSnapshot.from(rows.project, rows.files)
    }

    suspend fun insertProjectFile(file: ProjectFileEntity): ProjectOutcome<Long> {
        return try {
            if (file.localPath.isBlank() || !ProjectFilePolicy.isSupported(file.mimeType, file.displayName)) {
                ProjectOutcome.Failure(ProjectFailure.UNSUPPORTED_PROJECT_FILE)
            } else when (val result = projectDao.insertProjectFileBounded(file)) {
                is BoundedProjectFileInsertResult.Inserted -> ProjectOutcome.Ok(result.id)
                is BoundedProjectFileInsertResult.Existing -> ProjectOutcome.Ok(result.id)
                BoundedProjectFileInsertResult.CapacityReached ->
                    ProjectOutcome.Failure(ProjectFailure.PROJECT_FILE_LIMIT)
                BoundedProjectFileInsertResult.UnknownProject ->
                    ProjectOutcome.Failure(ProjectFailure.UNKNOWN_PROJECT)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ProjectOutcome.Failure(ProjectFailure.DB_ERROR)
        }
    }

    suspend fun removeProjectFile(fileId: Long): ProjectOutcome<Unit> {
        if (fileId <= 0L) return ProjectOutcome.Failure(ProjectFailure.PROJECT_FILE_NOT_FOUND)
        return try {
            if (projectDao.deleteProjectFile(fileId) == 1) {
                ProjectOutcome.Ok(Unit)
            } else {
                ProjectOutcome.Failure(ProjectFailure.PROJECT_FILE_NOT_FOUND)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ProjectOutcome.Failure(ProjectFailure.DB_ERROR)
        }
    }

    suspend fun removeAssignment(conversationId: Long): ProjectOutcome<Unit> {
        if (conversationId <= 0L) return ProjectOutcome.Failure(ProjectFailure.INVALID_CONVERSATION)
        return try {
            projectDao.removeAssignment(conversationId)
            ProjectOutcome.Ok(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ProjectOutcome.Failure(ProjectFailure.DB_ERROR)
        }
    }

    suspend fun deleteProject(projectId: Long): ProjectOutcome<Unit> {
        val deleteSafely = deleteProjectSafely
            ?: return ProjectOutcome.Failure(ProjectFailure.DB_ERROR)
        return try {
            if (deleteSafely(projectId)) ProjectOutcome.Ok(Unit)
            else ProjectOutcome.Failure(ProjectFailure.DB_ERROR)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ProjectOutcome.Failure(ProjectFailure.DB_ERROR)
        }
    }

    companion object {
        fun projectName(raw: String): String =
            normalizeUserVisibleName(raw.ifBlank { null }, "새 프로젝트", 80)
    }
}
