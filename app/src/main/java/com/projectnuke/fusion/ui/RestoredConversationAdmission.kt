package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ConversationEntity
import kotlinx.coroutines.CancellationException

/** Durable admission result for a SavedStateHandle conversation restore. */
internal enum class RestoredConversationAdmission {
    ALLOW,
    REJECT_MISSING,
    REJECT_LOCKED_ARCHIVED,
    REJECT_UNREADABLE,
}

internal fun evaluateRestoredConversationAdmission(
    conversation: ConversationEntity?,
    archiveLockEnabled: Boolean,
    lookupSucceeded: Boolean = true,
): RestoredConversationAdmission = when {
    !lookupSucceeded -> RestoredConversationAdmission.REJECT_UNREADABLE
    conversation == null -> RestoredConversationAdmission.REJECT_MISSING
    conversation.isArchived && archiveLockEnabled -> RestoredConversationAdmission.REJECT_LOCKED_ARCHIVED
    else -> RestoredConversationAdmission.ALLOW
}

/**
 * Runs the durable restored-conversation lookup and classifies its failures:
 * only an ordinary Exception maps to REJECT_UNREADABLE. Cancellation and
 * fatal Errors propagate: a runtime failure is not durable evidence that the
 * restored conversation is invalid, so it must not authorize the New Chat
 * selection fallback.
 */
internal suspend fun restoredConversationAdmissionOrUnreadable(
    conversationLookup: suspend () -> ConversationEntity?,
    archiveLockEnabled: Boolean,
): RestoredConversationAdmission = try {
    evaluateRestoredConversationAdmission(
        conversation = conversationLookup(),
        archiveLockEnabled = archiveLockEnabled,
    )
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    RestoredConversationAdmission.REJECT_UNREADABLE
}

/** Chat content is hidden while the originally restored owner is unresolved. */
internal fun isRestoredConversationSurfaceReady(
    currentConversationId: Long,
    pendingRestoredConversationId: Long?,
): Boolean = pendingRestoredConversationId == null ||
    currentConversationId != pendingRestoredConversationId
