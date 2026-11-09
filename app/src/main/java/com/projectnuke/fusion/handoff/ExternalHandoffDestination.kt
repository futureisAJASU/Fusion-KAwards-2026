package com.projectnuke.fusion.handoff

import java.net.URI

enum class ExternalHandoffDestination { CHAT, INBOX, SAVED_KNOWLEDGE, PROJECT }

data class ExternalHandoffSelection(
    val eventId: String,
    val destination: ExternalHandoffDestination,
    val projectId: Long? = null,
)

object ExternalHandoffDestinationPolicy {
    fun sharedUrl(text: String): String? {
        val candidate = text.trim()
        if (candidate.isBlank() || candidate.any { it.isWhitespace() } || candidate.length > 2_048) return null
        return try {
            val uri = URI(candidate)
            if ((uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank() && uri.userInfo == null
            ) uri.toASCIIString() else null
        } catch (_: Exception) {
            null
        }
    }

    fun accepts(selection: ExternalHandoffSelection, handoff: ExternalHandoff): Boolean {
        if (selection.eventId != handoff.id || selection.eventId.isBlank() || selection.eventId.length > 80) return false
        return when (selection.destination) {
            ExternalHandoffDestination.CHAT,
            ExternalHandoffDestination.INBOX -> selection.projectId == null
            ExternalHandoffDestination.SAVED_KNOWLEDGE -> handoff is ExternalTextHandoff && selection.projectId == null
            ExternalHandoffDestination.PROJECT -> selection.projectId?.let { it > 0L } == true
        }
    }
}

/**
 * Exactness gate for externally shared text (C14). Externally supplied user
 * text must never be silently truncated and reported as successfully stored:
 * payloads beyond the durable bound are explicitly too large. Mirrors the
 * downstream exactness contracts ([InboxCapturePolicy.prepare] rejects
 * over-bound content; the knowledge path stores exact text).
 */
internal object ExternalHandoffTextBounds {
    fun isTooLargeForInbox(text: String): Boolean =
        com.projectnuke.fusion.inbox.InboxCapturePolicy.MAX_CONTENT_CHARS.let { bound ->
            text.length > bound
        }

    fun isTooLargeForKnowledge(text: String): Boolean =
        com.projectnuke.fusion.knowledge.SavedKnowledgePolicy.MAX_TEXT_CHARS.let { bound ->
            text.length > bound
        }
}
