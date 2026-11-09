package com.projectnuke.fusion.study

import com.projectnuke.fusion.data.MAX_ENABLED_STUDY_REVIEW_CARD_REMINDERS
import kotlinx.coroutines.sync.Mutex

sealed interface QuickReviewCardReminderDecision {
    data object Allowed : QuickReviewCardReminderDecision
    data object InvalidTime : QuickReviewCardReminderDecision
    data object TooFarAhead : QuickReviewCardReminderDecision
    data object CapacityReached : QuickReviewCardReminderDecision
}

object QuickReviewCardReminderPolicy {
    const val MAX_FUTURE_MILLIS = 365L * 24L * 60L * 60L * 1_000L

    fun scheduleDecision(at: Long, now: Long, alreadyEnabled: Boolean, enabledCount: Int): QuickReviewCardReminderDecision {
        if (now < 0L || at <= now) return QuickReviewCardReminderDecision.InvalidTime
        if (at - now > MAX_FUTURE_MILLIS) return QuickReviewCardReminderDecision.TooFarAhead
        if (!alreadyEnabled && enabledCount >= MAX_ENABLED_STUDY_REVIEW_CARD_REMINDERS) {
            return QuickReviewCardReminderDecision.CapacityReached
        }
        return QuickReviewCardReminderDecision.Allowed
    }

    fun shouldDeliver(
        enabled: Boolean,
        reminderAt: Long?,
        expectedAt: Long,
        reminderGeneration: Long,
        expectedGeneration: Long,
    ): Boolean = enabled && reminderAt == expectedAt && reminderGeneration == expectedGeneration
}

interface StudyReviewCardReminderScheduler {
    fun scheduleCard(cardId: String, at: Long, generation: Long)
    fun cancelCard(cardId: String)
}

/** Serializes reminder metadata, AlarmManager updates, and Project deletion in this process. */
object QuickReviewCardReminderMutationGate {
    internal val mutex = Mutex()
}
