package com.projectnuke.fusion

/**
 * Reminder-tap deep link (U05). Task/review notifications carry stable
 * identity so tapping navigates to the referenced target instead of merely
 * opening the app root. Only identity travels in the Intent — never titles,
 * notes, or card content. A deleted/stale target resolves truthfully with a
 * bounded message.
 */

/** One-shot reminder navigation event, owned by MainActivity. */
data class ReminderNavigation(
    val taskId: Long?,
    val cardId: String?,
    val eventId: String,
)

/**
 * Single-owner pending slot for reminder navigation (same one-shot spirit
 * as the shortcut/handoff slots): submit stores the newest event, consume
 * clears it so recreation can never re-navigate.
 */
class PendingReminderNavigation {

    private var current: ReminderNavigation? = null

    val pending: ReminderNavigation?
        get() = current

    fun submit(event: ReminderNavigation) {
        current = event
    }

    fun consume(): ReminderNavigation? {
        val consumed = current
        current = null
        return consumed
    }
}

object ReminderNavigationPolicy {
    const val EXTRA_TASK_ID = "com.projectnuke.fusion.extra.REMINDER_TASK_ID"
    const val EXTRA_CARD_ID = "com.projectnuke.fusion.extra.REMINDER_CARD_ID"

    fun parse(taskId: Long?, cardId: String?): ReminderNavigation? {
        val cleanTask = taskId?.takeIf { it > 0L }
        val cleanCard = cardId?.takeIf { it.isNotBlank() && it.length <= 80 }
        if (cleanTask == null && cleanCard == null) return null
        return ReminderNavigation(
            taskId = cleanTask,
            cardId = cleanCard,
            eventId = java.util.UUID.randomUUID().toString(),
        )
    }
}

internal enum class ReminderReviewCardDestination {
    PROJECT_CARD,
    PERSONAL_CARD,
}

/** Source/archive gate for the card detail shown after a reminder tap. */
internal fun resolveReminderReviewCardDestination(
    scope: String,
    projectId: Long?,
    projectExists: Boolean,
    projectDeleting: Boolean,
    deletionPending: Boolean,
    sourceConversationId: Long?,
    sourceConversationArchived: Boolean?,
    sourceSavedKnowledgeId: String?,
    savedKnowledgeAccessible: Boolean,
    archiveAllowed: Boolean,
): ReminderReviewCardDestination? {
    if (deletionPending) return null
    return when (scope) {
        "PROJECT" -> if (projectId != null && projectExists && !projectDeleting) {
            ReminderReviewCardDestination.PROJECT_CARD
        } else {
            null
        }
        "PERSONAL" -> {
            val conversationAccessible = sourceConversationId != null &&
                sourceConversationArchived != null && (!sourceConversationArchived || archiveAllowed)
            val savedSourceAccessible = sourceSavedKnowledgeId != null && savedKnowledgeAccessible
            val hasSource = sourceConversationId != null || sourceSavedKnowledgeId != null
            if (conversationAccessible || savedSourceAccessible || !hasSource) {
                ReminderReviewCardDestination.PERSONAL_CARD
            } else {
                null
            }
        }
        else -> null
    }
}
