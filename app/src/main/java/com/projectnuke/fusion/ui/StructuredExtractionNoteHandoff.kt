package com.projectnuke.fusion.ui

import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteEditorDraft

internal sealed interface StructuredExtractionNoteHandoff {
    data class Ready(val draft: SavedKnowledgeNoteEditorDraft) : StructuredExtractionNoteHandoff
    data object MissingSourceIdentity : StructuredExtractionNoteHandoff
    data object BodyTooLong : StructuredExtractionNoteHandoff
    data object ProjectNoLongerExists : StructuredExtractionNoteHandoff
}

internal fun StructuredExtractionResult.toNoteHandoff(
    projectId: Long? = null,
    projectName: String? = null,
): StructuredExtractionNoteHandoff {
    val identity = durableIdentity ?: return StructuredExtractionNoteHandoff.MissingSourceIdentity
    val body = StructuredExtractionPolicy.asPlainText(this)
    if (body.length > com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_BODY_CHARS) {
        return StructuredExtractionNoteHandoff.BodyTooLong
    }
    return StructuredExtractionNoteHandoff.Ready(
        SavedKnowledgeNoteEditorDraft(
            id = "",
            title = title.take(com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_TITLE_CHARS),
            body = body,
            references = listOf(
                com.projectnuke.fusion.knowledge.SavedKnowledgeNoteReferenceDraft(
                    identity,
                    sourceTitle.take(com.projectnuke.fusion.chat.SelectionContextSnapshot.MAX_TITLE_CHARS),
                ),
            ),
            projectId = projectId,
            projectName = projectName,
        ),
    )
}

internal suspend fun prepareStructuredExtractionNoteHandoff(
    result: StructuredExtractionResult,
    projectId: Long?,
    projectName: String?,
    resolveCurrentProjectName: suspend (Long) -> String?,
): StructuredExtractionNoteHandoff {
    val currentName = if (projectId == null) null else
        resolveCurrentProjectName(projectId) ?: return StructuredExtractionNoteHandoff.ProjectNoLongerExists
    return result.toNoteHandoff(projectId, currentName ?: projectName)
}
