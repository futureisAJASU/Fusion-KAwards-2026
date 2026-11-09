package com.projectnuke.fusion.chat

import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.util.AttachmentRecord

/** Immutable request-owned project context. Never backed by a live DAO row. */
data class ProjectContextSnapshot(
    val projectId: Long,
    val instructions: String,
    val files: List<ProjectFileSnapshot>,
) {
    val attachmentRecords: List<AttachmentRecord>
        get() = files.map { file ->
            AttachmentRecord(
                name = file.displayName,
                mimeType = file.mimeType,
                localPath = file.localPath,
            )
        }

    companion object {
        fun from(project: ProjectEntity, files: List<ProjectFileEntity>): ProjectContextSnapshot =
            ProjectContextSnapshot(
                projectId = project.id,
                instructions = project.instructions,
                files = files.map { file ->
                    ProjectFileSnapshot(
                        id = file.id,
                        displayName = file.displayName,
                        mimeType = file.mimeType,
                        localPath = file.localPath,
                    )
                },
            )
    }
}

data class ProjectFileSnapshot(
    val id: Long,
    val displayName: String,
    val mimeType: String,
    val localPath: String,
)
