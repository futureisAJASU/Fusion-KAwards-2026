package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.MessageEntity

/**
 * Exact answer-surface identity for answer-bound PRESENTATION state
 * (source-reveal toggle, secondary action sheet, context inspector).
 *
 * Keyed by conversationId + assistantMessageId + canonical content hash so
 * presentation state follows the exact persisted active answer:
 *  - response version A → B resets even when their visible content strings are
 *    identical (versions are distinct assistant messages);
 *  - conversation A → B resets even with identical assistant content;
 *  - the same exact active answer keeps its state across harmless
 *    recomposition (remember key compares equal);
 *  - regeneration to a new persisted assistant message id resets;
 *  - New Chat (conversationId <= 0) resets.
 *
 * Visible text alone is never used as identity. Never durable.
 */
data class AnswerSurfaceIdentity(
    val conversationId: Long,
    val assistantMessageId: Long,
    val contentHash: String,
) {
    companion object {
        /** Null/absent message yields a degenerate identity so previews stay stable. */
        fun of(conversationId: Long, message: MessageEntity?): AnswerSurfaceIdentity = when {
            message != null -> AnswerSurfaceIdentity(
                conversationId = conversationId,
                assistantMessageId = message.id,
                // Reuses the exact Evidence content-hash authority so one hash
                // computation serves evidence identity and answer surface state.
                contentHash = EvidenceAnswerIdentity.of(message).answerSha256,
            )
            else -> AnswerSurfaceIdentity(conversationId, -1L, "")
        }
    }
}

/** Eligibility for the single post-answer fact-check action surface. */
internal fun canOfferPostAnswerFactVerification(
    message: MessageEntity?,
    visibleAnswerAvailable: Boolean,
    evidenceStoreAvailable: Boolean,
    verifierAvailable: Boolean,
    translationSurfaceAvailable: Boolean,
    isStreaming: Boolean,
    isRegenerating: Boolean,
): Boolean =
    message?.role == "assistant" && message.id > 0L &&
        visibleAnswerAvailable && evidenceStoreAvailable && verifierAvailable &&
        translationSurfaceAvailable && !isStreaming && !isRegenerating

/**
 * Execution fence for answer-sheet mutations that target durable mutable
 * state (the existing project picker and its ProjectCoordinator remain the
 * ONLY mutation owner). A project action may proceed only while the exact
 * answer-surface identity the sheet was bound to is still the current one and
 * the owning conversation is still the same persisted conversation; otherwise
 * it fails closed (sheet closed, nothing mutated).
 */
object AnswerSurfaceIdentityFence {
    fun canOpenProjectPicker(
        liveIdentity: AnswerSurfaceIdentity,
        expectedIdentity: AnswerSurfaceIdentity,
        conversationPersisted: Boolean,
    ): Boolean = liveIdentity == expectedIdentity && conversationPersisted

    fun canOpenCompareSurface(
        liveIdentity: AnswerSurfaceIdentity,
        expectedIdentity: AnswerSurfaceIdentity?,
        liveResponseVersionGroupId: Long?,
        expectedResponseVersionGroupId: Long?,
        responseVersionCount: Int,
        liveSnapshotAvailable: Boolean,
    ): Boolean =
        expectedIdentity != null &&
            liveIdentity == expectedIdentity &&
            liveResponseVersionGroupId != null &&
            liveResponseVersionGroupId == expectedResponseVersionGroupId &&
            responseVersionCount >= 2 &&
            liveSnapshotAvailable
}

/** The production decision seam for the secondary-sheet project row. */
internal fun canExecuteProjectPickerAction(
    liveIdentity: AnswerSurfaceIdentity,
    expectedIdentity: AnswerSurfaceIdentity?,
    conversationPersisted: Boolean,
): Boolean = expectedIdentity?.let { expected ->
    AnswerSurfaceIdentityFence.canOpenProjectPicker(
        liveIdentity = liveIdentity,
        expectedIdentity = expected,
        conversationPersisted = conversationPersisted,
    )
} == true
