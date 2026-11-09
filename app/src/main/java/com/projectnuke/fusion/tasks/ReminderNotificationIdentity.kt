package com.projectnuke.fusion.tasks

import android.app.Notification

/**
 * NotificationManager identifies a notification by the pair (tag, id).
 * The tag carries the durable logical owner; the integer remains stable for
 * compatibility with PendingIntent request codes and the legacy migration.
 */
internal data class ReminderNotificationIdentity(
    val tag: String,
    val id: Int,
    val legacyId: Int,
) {
    companion object {
        private const val TASK_NOTIFICATION_BASE = 0x46550000
        private const val CARD_NOTIFICATION_BASE = 0x46560000

        fun task(taskId: Long): ReminderNotificationIdentity {
            require(taskId > 0L)
            return ReminderNotificationIdentity(
                tag = "fusion:task:$taskId",
                id = TASK_NOTIFICATION_BASE xor taskId.hashCode(),
                legacyId = TASK_NOTIFICATION_BASE xor taskId.hashCode(),
            )
        }

        fun card(cardId: String): ReminderNotificationIdentity {
            require(cardId.isNotBlank() && cardId.length <= 80)
            val oldId = CARD_NOTIFICATION_BASE xor cardId.hashCode()
            return ReminderNotificationIdentity(
                tag = "fusion:card:$cardId",
                id = oldId,
                legacyId = oldId,
            )
        }
    }
}

/** Only these app-owned channels were used by the historical untagged API. */
internal object LegacyReminderNotificationPolicy {
    const val TASK_CHANNEL_ID = "project-task-reminders"
    const val CARD_CHANNEL_ID = "study-card-reminders"

    fun isLegacyUntagged(tag: String?, channelId: String?): Boolean =
        tag == null && (channelId == TASK_CHANNEL_ID || channelId == CARD_CHANNEL_ID)
}

/**
 * Forwards notification API calls through the exact logical-owner identity.
 * The old untagged identity is removed first so an upgrade cannot leave both
 * representations visible for the same owner. If that removal fails, the
 * caller treats publication as failed and leaves the durable reminder retryable.
 */
internal fun postOwnedReminder(
    manager: android.app.NotificationManager,
    identity: ReminderNotificationIdentity,
    notification: Notification,
) {
    manager.cancel(identity.legacyId)
    manager.notify(identity.tag, identity.id, notification)
}

/** Cancel both the new tagged owner and its former untagged identity. */
internal fun cancelOwnedReminder(
    manager: android.app.NotificationManager,
    identity: ReminderNotificationIdentity,
) {
    manager.cancel(identity.tag, identity.id)
    manager.cancel(identity.legacyId)
}
