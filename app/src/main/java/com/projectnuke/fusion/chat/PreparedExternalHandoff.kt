package com.projectnuke.fusion.chat

/**
 * Coordinator-owned handoff payload. Its attachment paths are staged files,
 * not durable composer references, until ChatViewModel adopts the value.
 */
internal data class PreparedExternalHandoff(
    val eventId: String,
    val rawInput: String,
    val pendingAttachments: List<PendingAttachmentIdentity>,
    val generatedPaths: List<String>,
    /** Bounded, non-sensitive outcome copy committed with the adopted draft. */
    val partialOutcomeNotice: String? = null,
) {
    fun isBounded(): Boolean {
        if (eventId.length !in 1..MAX_EVENT_ID_CHARS) return false
        if (rawInput.length > PersistentComposerDraftStore.MAX_INPUT_CHARS) return false
        if (pendingAttachments.size > PersistentComposerDraftStore.MAX_ATTACHMENT_COUNT) return false
        if (generatedPaths.size > PersistentComposerDraftStore.MAX_ATTACHMENT_COUNT) return false
        if (partialOutcomeNotice != null && partialOutcomeNotice.length !in 1..MAX_PARTIAL_NOTICE_CHARS) return false
        if (generatedPaths.distinct().size != generatedPaths.size) return false
        if (pendingAttachments.map { it.localPath }.distinct().size != pendingAttachments.size) return false
        if (pendingAttachments.any { attachment ->
                attachment.name.length > PersistentComposerDraftStore.MAX_FIELD_CHARS ||
                    attachment.mimeType.length > PersistentComposerDraftStore.MAX_FIELD_CHARS ||
                    attachment.localPath.length !in 1..PersistentComposerDraftStore.MAX_PATH_CHARS
            }
        ) return false
        return generatedPaths.all { path -> path.length in 1..PersistentComposerDraftStore.MAX_PATH_CHARS }
    }

    companion object {
        const val MAX_EVENT_ID_CHARS = 128
        const val MAX_PARTIAL_NOTICE_CHARS = 512
    }
}

internal enum class ExternalHandoffAdoptionFailure {
    INVALID_PAYLOAD,
    COMPOSER_UNAVAILABLE,
    ACTIVE_COMPOSER_OWNER,
    GENERATION_SETTLEMENT_FAILED,
    DRAFT_REPLACEMENT_FAILED,
}

internal data class ExternalHandoffAdoptionResult(
    val success: Boolean,
    val oldPendingAttachmentPaths: List<String> = emptyList(),
    val failure: ExternalHandoffAdoptionFailure? = null,
)
