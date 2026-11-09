package com.projectnuke.fusion.tasks

import com.projectnuke.fusion.data.MAX_ENABLED_TASK_REMINDERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTaskReminderPolicyTest {
    @Test
    fun acceptsBoundedFutureReminderAndReplacementAtCapacity() {
        assertEquals(
            ReminderScheduleDecision.Allowed,
            ProjectTaskReminderPolicy.scheduleDecision(11_000, 10_000, false, 0),
        )
        assertEquals(
            ReminderScheduleDecision.Allowed,
            ProjectTaskReminderPolicy.scheduleDecision(20_000, 10_000, true, MAX_ENABLED_TASK_REMINDERS),
        )
    }

    @Test
    fun rejectsPastAndOutOfRangeTimes() {
        assertEquals(ReminderScheduleDecision.InvalidTime, ProjectTaskReminderPolicy.scheduleDecision(10_000, 10_000, false, 0))
        assertEquals(ReminderScheduleDecision.InvalidTime, ProjectTaskReminderPolicy.scheduleDecision(9_999, 10_000, false, 0))
        assertEquals(
            ReminderScheduleDecision.TooFarAhead,
            ProjectTaskReminderPolicy.scheduleDecision(10_000 + ProjectTaskReminderPolicy.MAX_FUTURE_MILLIS + 1, 10_000, false, 0),
        )
    }

    @Test
    fun capacityIsBoundedForNewReminders() {
        assertEquals(
            ReminderScheduleDecision.CapacityReached,
            ProjectTaskReminderPolicy.scheduleDecision(11_000, 10_000, false, MAX_ENABLED_TASK_REMINDERS),
        )
    }

    @Test
    fun deliveryRequiresTheCurrentEnabledOpenReminder() {
        assertTrue(ProjectTaskReminderPolicy.shouldDeliver(true, 20, 20, 4, 4, ProjectTaskState.OPEN.name))
        assertFalse(ProjectTaskReminderPolicy.shouldDeliver(false, 20, 20, 4, 4, ProjectTaskState.OPEN.name))
        assertFalse(ProjectTaskReminderPolicy.shouldDeliver(true, 21, 20, 4, 4, ProjectTaskState.OPEN.name))
        assertFalse(ProjectTaskReminderPolicy.shouldDeliver(true, 20, 20, 4, 5, ProjectTaskState.OPEN.name))
        assertFalse(ProjectTaskReminderPolicy.shouldDeliver(true, 20, 20, 4, 4, ProjectTaskState.DONE.name))
        assertFalse(ProjectTaskReminderPolicy.shouldDeliver(true, null, 20, 4, 4, ProjectTaskState.OPEN.name))
    }

    @Test
    fun requestsRuntimePermissionOnlyOnAndroidThirteenWhenMissing() {
        assertFalse(ProjectTaskReminderPolicy.shouldRequestNotificationPermission(32, false))
        assertFalse(ProjectTaskReminderPolicy.shouldRequestNotificationPermission(33, true))
        assertTrue(ProjectTaskReminderPolicy.shouldRequestNotificationPermission(33, false))
    }
}
