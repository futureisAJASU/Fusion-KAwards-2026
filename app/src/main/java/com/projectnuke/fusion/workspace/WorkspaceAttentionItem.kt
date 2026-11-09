package com.projectnuke.fusion.workspace

data class WorkspaceAttentionItem(
    val stableId: String,
    val kind: WorkspaceAttentionKind,
    val title: String,
    val subtitle: String,
    val projectId: Long?,
    val targetId: String,
    val priority: Int,
    val updatedAt: Long,
)
