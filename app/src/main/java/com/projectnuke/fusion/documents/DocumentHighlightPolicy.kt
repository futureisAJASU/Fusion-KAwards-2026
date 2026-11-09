package com.projectnuke.fusion.documents

import com.projectnuke.fusion.knowledge.SavedKnowledgePolicy

data class DocumentHighlightDraft(
    val projectId: Long,
    val projectFileId: Long,
    val documentTitle: String,
    val pageNumber: Int?,
    val sourceChunkId: String?,
    val sourceContentHashHex: String?,
    val quotedText: String,
    val userNote: String? = null,
)

data class PreparedDocumentHighlight(
    val projectId: Long,
    val projectFileId: Long,
    val title: String,
    val pageNumber: Int?,
    val sourceChunkId: String?,
    val sourceContentHashHex: String?,
    val quotedText: String,
    val userNote: String?,
)

sealed interface DocumentHighlightPreparation {
    data class Ready(val value: PreparedDocumentHighlight) : DocumentHighlightPreparation
    data object Invalid : DocumentHighlightPreparation
    data object SensitiveContent : DocumentHighlightPreparation
}

object DocumentHighlightPolicy {
    const val MAX_QUOTE_CHARS = 1_200
    const val MAX_NOTE_CHARS = 800
    const val MAX_SOURCE_ID_CHARS = 128
    const val MAX_HIGHLIGHTS = 512

    fun prepare(draft: DocumentHighlightDraft): DocumentHighlightPreparation {
        val title = draft.documentTitle.trim().take(SavedKnowledgePolicy.MAX_TITLE_CHARS)
        val quote = draft.quotedText.trim()
        val note = draft.userNote?.trim()?.takeIf(String::isNotEmpty)
        if (draft.projectId <= 0L || draft.projectFileId <= 0L || title.isBlank() ||
            quote.isBlank() || quote.length > MAX_QUOTE_CHARS || note?.length?.let { it > MAX_NOTE_CHARS } == true ||
            draft.pageNumber?.let { it <= 0 } == true ||
            draft.sourceChunkId?.let { it.isBlank() || it.length > MAX_SOURCE_ID_CHARS } == true ||
            draft.sourceContentHashHex?.let { it.length != 64 || !it.all { char -> char in '0'..'9' || char in 'a'..'f' } } == true
        ) return DocumentHighlightPreparation.Invalid
        if (SavedKnowledgePolicy.containsSensitiveMaterial("$title\n$quote\n${note.orEmpty()}")) {
            return DocumentHighlightPreparation.SensitiveContent
        }
        return DocumentHighlightPreparation.Ready(
            PreparedDocumentHighlight(
                draft.projectId, draft.projectFileId,
                if (draft.pageNumber == null) title else "$title · p.${draft.pageNumber}".take(SavedKnowledgePolicy.MAX_TITLE_CHARS),
                draft.pageNumber, draft.sourceChunkId, draft.sourceContentHashHex, quote, note,
            ),
        )
    }
}
