package com.projectnuke.fusion.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickReviewCardReminderPolicyTest {
    @Test
    fun `reminder times are future bounded and capacity limited`() {
        assertEquals(
            QuickReviewCardReminderDecision.InvalidTime,
            QuickReviewCardReminderPolicy.scheduleDecision(10, 10, false, 0),
        )
        assertEquals(
            QuickReviewCardReminderDecision.TooFarAhead,
            QuickReviewCardReminderPolicy.scheduleDecision(
                12 + QuickReviewCardReminderPolicy.MAX_FUTURE_MILLIS, 11, false, 0,
            ),
        )
        assertEquals(
            QuickReviewCardReminderDecision.CapacityReached,
            QuickReviewCardReminderPolicy.scheduleDecision(12, 11, false, 128),
        )
        assertEquals(
            QuickReviewCardReminderDecision.Allowed,
            QuickReviewCardReminderPolicy.scheduleDecision(12, 11, true, 128),
        )
    }

    @Test
    fun `delivery requires enabled card and exact frozen reminder time`() {
        assertTrue(QuickReviewCardReminderPolicy.shouldDeliver(true, 20, 20, 4, 4))
        assertFalse(QuickReviewCardReminderPolicy.shouldDeliver(false, 20, 20, 4, 4))
        assertFalse(QuickReviewCardReminderPolicy.shouldDeliver(true, 21, 20, 4, 4))
        assertFalse(QuickReviewCardReminderPolicy.shouldDeliver(true, 20, 20, 4, 5))
        assertFalse(QuickReviewCardReminderPolicy.shouldDeliver(true, null, 20, 4, 4))
    }
}
