package com.projectnuke.fusion.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderNotificationIdentityTest {
    @Test
    fun `reported UUID hash collision has distinct stable owner identities`() {
        val cardA = "00000000-0000-4000-8000-460909ca3012"
        val cardB = "00000000-0000-4000-8000-00f66c87acb4"

        assertEquals(-51_383_884, cardA.hashCode())
        assertEquals(cardA.hashCode(), cardB.hashCode())
        assertEquals(-1_162_219_084, ReminderNotificationIdentity.card(cardA).id)
        assertEquals(ReminderNotificationIdentity.card(cardA).id, ReminderNotificationIdentity.card(cardB).id)
        assertNotEquals(ReminderNotificationIdentity.card(cardA).tag, ReminderNotificationIdentity.card(cardB).tag)
        assertNotEquals(ReminderNotificationIdentity.card(cardA), ReminderNotificationIdentity.card(cardB))
    }

    @Test
    fun `long hash collision and cross-family integer collision stay owner-distinct`() {
        val taskA = ReminderNotificationIdentity.task(1L)
        val taskB = ReminderNotificationIdentity.task(1L shl 32)
        assertEquals(taskA.id, taskB.id)
        assertNotEquals(taskA.tag, taskB.tag)

        // "Aa" hashes to 2112; task id 198720 differs by the old family-base XOR.
        val collidingTask = ReminderNotificationIdentity.task(198_720L)
        val collidingCard = ReminderNotificationIdentity.card("Aa")
        assertEquals(1_180_043_328, collidingTask.id)
        assertEquals(collidingTask.id, collidingCard.id)
        assertNotEquals(collidingTask.tag, collidingCard.tag)
    }

    @Test
    fun `stable tags survive generation changes and legacy IDs remain derivable`() {
        val before = ReminderNotificationIdentity.card("review-card-7")
        val afterReschedule = ReminderNotificationIdentity.card("review-card-7")
        assertEquals(before, afterReschedule)
        assertEquals(before.id, before.legacyId)
        assertEquals("fusion:card:review-card-7", before.tag)
        assertEquals("fusion:task:17", ReminderNotificationIdentity.task(17L).tag)
    }

    @Test
    fun `valid integer edge values retain distinct logical owner tags`() {
        val zero = ReminderNotificationIdentity.task(0x46550000L)
        val negative = ReminderNotificationIdentity.task(0x80000000L)
        assertEquals(0, zero.id)
        assertTrue(negative.id < 0)
        assertNotEquals(zero.tag, negative.tag)
        assertThrows(IllegalArgumentException::class.java) { ReminderNotificationIdentity.task(0L) }
        assertThrows(IllegalArgumentException::class.java) { ReminderNotificationIdentity.card("") }
        assertThrows(IllegalArgumentException::class.java) { ReminderNotificationIdentity.card("x".repeat(81)) }
    }

    @Test
    fun `only untagged notifications in reminder channels are legacy`() {
        assertTrue(LegacyReminderNotificationPolicy.isLegacyUntagged(null, "project-task-reminders"))
        assertTrue(LegacyReminderNotificationPolicy.isLegacyUntagged(null, "study-card-reminders"))
        assertEquals(false, LegacyReminderNotificationPolicy.isLegacyUntagged("fusion:task:1", "project-task-reminders"))
        assertFalse(LegacyReminderNotificationPolicy.isLegacyUntagged(null, "unrelated-channel"))
    }
}
