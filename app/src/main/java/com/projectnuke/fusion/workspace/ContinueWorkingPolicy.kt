package com.projectnuke.fusion.workspace

import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectTaskShortcutRow
import com.projectnuke.fusion.inbox.InboxCaptureType

enum class ContinueWorkKind { PROJECT, TASK, INBOX }

data class ContinueWorkCandidate(
    val stableId: String,
    val kind: ContinueWorkKind,
    val title: String,
    val subtitle: String,
    val projectId: Long? = null,
    val taskId: Long? = null,
    val inboxItemId: String? = null,
    val updatedAt: Long,
)

object ContinueWorkingPolicy {
    const val MAX_SHORTCUTS = 3
    const val QUERY_LIMIT_PER_KIND = 4

    fun select(
        projects: List<ProjectEntity>,
        openTasks: List<ProjectTaskShortcutRow>,
        inboxItems: List<InboxItemEntity>,
    ): List<ContinueWorkCandidate> {
        val candidates = buildList {
            projects.sortedWith(compareByDescending<ProjectEntity> { it.updatedAt }.thenByDescending { it.id })
                .take(QUERY_LIMIT_PER_KIND).forEach { project ->
                add(ContinueWorkCandidate(
                    stableId = "project:${project.id}", kind = ContinueWorkKind.PROJECT,
                    title = project.name, subtitle = "Project", projectId = project.id,
                    updatedAt = project.updatedAt,
                ))
            }
            openTasks.filter { it.id > 0L && it.projectId > 0L && it.title.isNotBlank() }
                .sortedWith(compareByDescending<ProjectTaskShortcutRow> { it.updatedAt }.thenByDescending { it.id })
                .take(QUERY_LIMIT_PER_KIND).forEach { task ->
                    add(ContinueWorkCandidate(
                        stableId = "task:${task.id}", kind = ContinueWorkKind.TASK,
                        title = task.title, subtitle = task.projectName,
                        projectId = task.projectId, taskId = task.id, updatedAt = task.updatedAt,
                    ))
                }
            inboxItems.sortedWith(compareByDescending<InboxItemEntity> { it.updatedAt }.thenByDescending { it.id })
                .take(QUERY_LIMIT_PER_KIND).forEach { item ->
                    add(ContinueWorkCandidate(
                        stableId = "inbox:${item.id}", kind = ContinueWorkKind.INBOX,
                        title = item.title,
                        subtitle = "Inbox · ${InboxCaptureType.values().firstOrNull { it.name == item.captureType }?.displayLabel ?: "자료"}",
                        inboxItemId = item.id, updatedAt = item.updatedAt,
                    ))
                }
        }
        return candidates.distinctBy(ContinueWorkCandidate::stableId)
            .sortedWith(compareByDescending<ContinueWorkCandidate> { it.updatedAt }
                .thenBy { it.kind.ordinal }.thenBy { it.stableId })
            .take(MAX_SHORTCUTS)
    }
}
