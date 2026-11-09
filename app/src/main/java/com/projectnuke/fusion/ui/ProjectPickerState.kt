package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ProjectEntity

/**
 * Identity of one project-picker load: the conversation the picker was
 * opened for, plus a strictly monotonic generation that also distinguishes
 * same-conversation reopens.
 */
internal data class ProjectPickerLoadOwner(
    val conversationId: Long,
    val generation: Long,
)

/** Completed picker data bound to the exact load that produced it. */
internal data class ProjectPickerLoadResult(
    val owner: ProjectPickerLoadOwner,
    val projects: List<ProjectEntity>,
    val assignment: Long?,
)

/**
 * Publication gate for a completed picker load. Accepted only while the load
 * is still the current owner AND that owner still belongs to the current
 * conversation. A newer open (different generation), a dismissal (no current
 * owner), and navigation to another conversation all reject the publish.
 */
internal fun canPublishProjectPickerLoad(
    currentOwner: ProjectPickerLoadOwner?,
    currentConversationId: Long,
    result: ProjectPickerLoadResult,
): Boolean {
    val current = currentOwner ?: return false
    return result.owner == current && current.conversationId == currentConversationId
}

/** The picker surface is exposed only while its owner is the current conversation. */
internal fun isProjectPickerOwnerVisible(
    owner: ProjectPickerLoadOwner?,
    currentConversationId: Long,
): Boolean = owner?.conversationId == currentConversationId

/**
 * The conversation a picker action may mutate: the visible owner's
 * conversation, or null (fail-closed) when no owner is installed or it no
 * longer belongs to the current conversation.
 */
internal fun projectPickerActionTarget(
    owner: ProjectPickerLoadOwner?,
    currentConversationId: Long,
): Long? = owner?.takeIf { it.conversationId == currentConversationId }?.conversationId
