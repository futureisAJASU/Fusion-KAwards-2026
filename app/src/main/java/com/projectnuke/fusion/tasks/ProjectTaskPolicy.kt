package com.projectnuke.fusion.tasks

import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.knowledge.SavedKnowledgePolicy

enum class ProjectTaskState { OPEN, DONE }

data class ProjectTaskDraft(
    val projectId: Long,
    val projectName: String,
    val title: String,
    val note: String,
    val sourceConversationId: Long? = null,
    val sourceMessageId: Long? = null,
)

data class ProjectTaskProposal(
    val projectId: Long,
    val title: String,
    val note: String,
    val sourceConversationId: Long?,
    val sourceMessageId: Long?,
    val createdAt: Long,
)

sealed interface ProjectTaskPreparation {
    data class Ready(val proposal: ProjectTaskProposal) : ProjectTaskPreparation
    data object Invalid : ProjectTaskPreparation
    data object SensitiveContent : ProjectTaskPreparation
}

object ProjectTaskPolicy {
    const val MAX_TITLE_CHARS = 200
    const val MAX_NOTE_CHARS = 4_000

    fun fromAnswer(
        projectId: Long,
        projectName: String,
        visibleAnswer: String,
        conversationId: Long,
        messageId: Long,
    ): ProjectTaskDraft {
        val boundedAnswer = visibleAnswer.take(MAX_TITLE_CHARS + MAX_NOTE_CHARS + 1)
        val splitAt = boundedAnswer.indexOf('\n')
        val title = (if (splitAt >= 0) boundedAnswer.substring(0, splitAt) else boundedAnswer)
            .trim().take(MAX_TITLE_CHARS)
            .ifBlank { "새 할 일" }
        val note = if (splitAt >= 0) boundedAnswer.substring(splitAt + 1).take(MAX_NOTE_CHARS) else ""
        return ProjectTaskDraft(projectId, projectName, title, note, conversationId, messageId)
    }

    fun prepare(draft: ProjectTaskDraft, title: String, note: String, now: Long): ProjectTaskPreparation {
        if (draft.projectId <= 0L || now < 0L ||
            ((draft.sourceConversationId == null) != (draft.sourceMessageId == null)) ||
            draft.sourceConversationId?.let { it <= 0L } == true || draft.sourceMessageId?.let { it <= 0L } == true
        ) return ProjectTaskPreparation.Invalid
        val boundedTitle = title.trim().take(MAX_TITLE_CHARS)
        val boundedNote = note.trim().take(MAX_NOTE_CHARS)
        if (boundedTitle.isBlank()) return ProjectTaskPreparation.Invalid
        if (SavedKnowledgePolicy.containsSensitiveMaterial("$boundedTitle\n$boundedNote")) {
            return ProjectTaskPreparation.SensitiveContent
        }
        return ProjectTaskPreparation.Ready(
            ProjectTaskProposal(
                projectId = draft.projectId,
                title = boundedTitle,
                note = boundedNote,
                sourceConversationId = draft.sourceConversationId,
                sourceMessageId = draft.sourceMessageId,
                createdAt = now,
            ),
        )
    }

    fun entity(proposal: ProjectTaskProposal): ProjectTaskEntity = ProjectTaskEntity(
        projectId = proposal.projectId,
        title = proposal.title,
        note = proposal.note,
        state = ProjectTaskState.OPEN.name,
        sourceConversationId = proposal.sourceConversationId,
        sourceMessageId = proposal.sourceMessageId,
        createdAt = proposal.createdAt,
        updatedAt = proposal.createdAt,
    )
}
