package com.projectnuke.fusion

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.projectnuke.fusion.util.normalizeUserVisibleName

/**
 * Pinned launcher shortcut for one persisted conversation ("홈 화면에 추가").
 *
 * Identity is stable: `fusion-conversation-<conversationId>`. The shortcut
 * carries ONLY the conversation ID extra plus the bounded presentation
 * label — never conversation text, prompts, attachments or private
 * content. Launching opens that conversation with no generation; a deleted
 * target fails safely with a bounded message.
 */

/** One-shot shortcut navigation event, owned by MainActivity. */
data class ConversationShortcutNavigation(
    val conversationId: Long,
    val eventId: String,
)

/**
 * Single-owner pending slot for shortcut navigation (same one-shot spirit
 * as the external-handoff slot, but a separate family): submit stores the
 * newest event, consume clears it so recreation can never re-navigate.
 */
class PendingConversationShortcut {

    private var current: ConversationShortcutNavigation? = null

    val pending: ConversationShortcutNavigation?
        get() = current

    fun submit(event: ConversationShortcutNavigation) {
        current = event
    }

    fun consume(): ConversationShortcutNavigation? {
        val consumed = current
        current = null
        return consumed
    }
}

data class ConversationShortcutRequest(
    val conversationId: Long,
    val label: String,
)

enum class ShortcutPinOutcome {
    REQUESTED,
    UNSUPPORTED,
    INVALID,
    ERROR,
}

object ConversationShortcut {
    const val EXTRA_CONVERSATION_ID = "com.projectnuke.fusion.extra.OPEN_CONVERSATION_ID"

    fun shortcutId(conversationId: Long): String = "fusion-conversation-$conversationId"

    fun isPinnable(conversationId: Long): Boolean = conversationId > 0L

    fun parseConversationId(extraValue: Long?): Long? =
        extraValue?.takeIf { it > 0L }

    fun boundedLabel(title: String): String =
        normalizeUserVisibleName(title.ifBlank { null }, "새 대화", 48)

    /**
     * Pure request for pinning. Null when the conversation is not persisted
     * (NEW_CONVERSATION_ID/unsaved chat can never produce a shortcut).
     */
    fun buildRequest(conversationId: Long, title: String): ConversationShortcutRequest? {
        if (!isPinnable(conversationId)) return null
        return ConversationShortcutRequest(conversationId, boundedLabel(title))
    }

    /**
     * Android pin request. Reports REQUESTED when the system confirmation
     * was asked for (pinning itself is confirmed by the launcher, never
     * faked here), UNSUPPORTED when the launcher cannot pin.
     */
    fun requestPin(context: Context, request: ConversationShortcutRequest): ShortcutPinOutcome {
        if (!isPinnable(request.conversationId)) return ShortcutPinOutcome.INVALID
        return try {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                return ShortcutPinOutcome.UNSUPPORTED
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_CONVERSATION_ID, request.conversationId)
            }
            val info = ShortcutInfoCompat.Builder(context, shortcutId(request.conversationId))
                .setShortLabel(request.label)
                .setLongLabel(request.label)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.requestPinShortcut(context, info, null)
            ShortcutPinOutcome.REQUESTED
        } catch (_: Exception) {
            ShortcutPinOutcome.ERROR
        }
    }
}
