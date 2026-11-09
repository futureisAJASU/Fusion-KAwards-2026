package com.projectnuke.fusion.ui

import com.projectnuke.fusion.util.AttachmentRecord

/** Immutable project presentation data, owned by one conversation surface. */
internal data class ProjectSurfaceOwner(
    val conversationId: Long,
    val pendingProjectId: Long?,
)

internal data class ProjectSurfaceSnapshot(
    val owner: ProjectSurfaceOwner,
    val name: String?,
    val fileRecords: List<AttachmentRecord>,
    val context: com.projectnuke.fusion.chat.ProjectContextSnapshot? = null,
)

/** Never borrow a previous conversation's in-flight result for the new owner. */
internal fun visibleProjectSurfaceSnapshot(
    currentOwner: ProjectSurfaceOwner,
    snapshot: ProjectSurfaceSnapshot?,
): ProjectSurfaceSnapshot? = snapshot?.takeIf { it.owner == currentOwner }
