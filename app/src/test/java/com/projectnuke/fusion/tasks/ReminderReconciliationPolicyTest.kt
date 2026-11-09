package com.projectnuke.fusion.tasks

import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.StudyReviewCardEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderReconciliationPolicyTest {
    private val NOW = 1_000_000L

    @Test
    fun isOverdueClassifiesReminderTimeCorrectly() {
        assertTrue(ReminderReconciliationPolicy.isOverdue(500L, NOW))
        assertTrue(ReminderReconciliationPolicy.isOverdue(NOW, NOW))
        assertFalse(ReminderReconciliationPolicy.isOverdue(NOW + 1, NOW))
        assertFalse(ReminderReconciliationPolicy.isOverdue(null, NOW))
    }

    @Test
    fun disabledOrMissingReminderChannelKeepsDurableReminderUnconsumed() = runBlocking {
        assertFalse(reminderChannelAllowsDelivery(android.app.NotificationManager.IMPORTANCE_NONE))
        assertFalse(reminderChannelAllowsDelivery(null))
        assertTrue(reminderChannelAllowsDelivery(android.app.NotificationManager.IMPORTANCE_DEFAULT))

        var consumes = 0
        val delivered = publishReminderBeforeConsume(
            publish = { reminderChannelAllowsDelivery(android.app.NotificationManager.IMPORTANCE_NONE) },
            consume = { consumes++; 1 },
        )

        assertFalse(delivered)
        assertEquals(0, consumes)
    }

    @Test
    fun filterDeliverableTasksIncludesOnlyOpenEnabledOverdueTasks() {
        val tasks = listOf(
            ProjectTaskEntity(id = 1, projectId = 10, title = "open", note = "", createdAt = 0, updatedAt = 0, reminderAt = 500L, reminderEnabled = true, state = "OPEN"),
            ProjectTaskEntity(id = 2, projectId = 10, title = "done", note = "", createdAt = 0, updatedAt = 0, reminderAt = 500L, reminderEnabled = true, state = "DONE"),
            ProjectTaskEntity(id = 3, projectId = 10, title = "future", note = "", createdAt = 0, updatedAt = 0, reminderAt = 2_000_000L, reminderEnabled = true, state = "OPEN"),
            ProjectTaskEntity(id = 4, projectId = 10, title = "disabled", note = "", createdAt = 0, updatedAt = 0, reminderAt = 500L, reminderEnabled = false, state = "OPEN"),
            ProjectTaskEntity(id = 5, projectId = 10, title = "null-reminder", note = "", createdAt = 0, updatedAt = 0, reminderAt = null, reminderEnabled = true, state = "OPEN"),
        )
        val result = ReminderReconciliationPolicy.filterDeliverableTasks(tasks, NOW)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun filterDeliverableCardsIncludesOnlyEnabledOverdueCards() {
        val cards = listOf(
            StudyReviewCardEntity(id = "card-1", scope = "PERSONAL", projectId = null, front = "f", back = "b", sourceKind = "", sourceConversationId = null, sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null, sourceProjectFileId = null, dueAt = 0L, createdAt = 0L, updatedAt = 0L, reminderAt = 500L, reminderEnabled = true),
            StudyReviewCardEntity(id = "card-2", scope = "PERSONAL", projectId = null, front = "f", back = "b", sourceKind = "", sourceConversationId = null, sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null, sourceProjectFileId = null, dueAt = 0L, createdAt = 0L, updatedAt = 0L, reminderAt = 2_000_000L, reminderEnabled = true),
            StudyReviewCardEntity(id = "card-3", scope = "PERSONAL", projectId = null, front = "f", back = "b", sourceKind = "", sourceConversationId = null, sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null, sourceProjectFileId = null, dueAt = 0L, createdAt = 0L, updatedAt = 0L, reminderAt = 500L, reminderEnabled = false),
            StudyReviewCardEntity(id = "card-4", scope = "PERSONAL", projectId = null, front = "f", back = "b", sourceKind = "", sourceConversationId = null, sourceMessageId = null, sourceSavedKnowledgeId = null, sourceProjectId = null, sourceProjectFileId = null, dueAt = 0L, createdAt = 0L, updatedAt = 0L, reminderAt = null, reminderEnabled = true),
        )
        val result = ReminderReconciliationPolicy.filterDeliverableCards(cards, NOW)
        assertEquals(listOf("card-1"), result.map { it.id })
    }

    @Test
    fun filterIsBestEffortAndCollectsAllDeliverableRows() {
        val tasks = List(3) { i ->
            ProjectTaskEntity(
                id = (i + 1L),
                projectId = 10,
                title = "task $i",
                note = "",
                createdAt = 0,
                updatedAt = 0,
                reminderAt = 500L,
                reminderEnabled = true,
                state = "OPEN",
            )
        }
        assertEquals(3, ReminderReconciliationPolicy.filterDeliverableTasks(tasks, NOW).size)
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertEquals(emptyList<ProjectTaskEntity>(), ReminderReconciliationPolicy.filterDeliverableTasks(emptyList(), NOW))
        assertEquals(emptyList<StudyReviewCardEntity>(), ReminderReconciliationPolicy.filterDeliverableCards(emptyList(), NOW))
    }

    @Test
    fun rescheduledReminderWithStaleExpectedAtIsNotDeliverableByStaleRows() {
        val tasks = listOf(
            ProjectTaskEntity(id = 1, projectId = 10, title = "rescheduled", note = "", createdAt = 0, updatedAt = 0, reminderAt = 900L, reminderEnabled = true, state = "OPEN"),
        )
        // Old expectedAt (500L) no longer matches persisted reminderAt (900L) → shouldDeliver returns false
        assertFalse(
            ProjectTaskReminderPolicy.shouldDeliver(
                reminderEnabled = true, reminderAt = 900L, expectedAt = 500L,
                reminderGeneration = 2L, expectedGeneration = 1L, taskState = "OPEN",
            )
        )
        // New expectedAt (900L) matches → deliverable
        assertTrue(
            ProjectTaskReminderPolicy.shouldDeliver(
                reminderEnabled = true, reminderAt = 900L, expectedAt = 900L,
                reminderGeneration = 2L, expectedGeneration = 2L, taskState = "OPEN",
            )
        )
    }

    @Test
    fun sameReminderTimeWithNewGenerationRejectsOldDelivery() {
        assertFalse(
            ProjectTaskReminderPolicy.shouldDeliver(
                reminderEnabled = true,
                reminderAt = 900L,
                expectedAt = 900L,
                reminderGeneration = 3L,
                expectedGeneration = 2L,
                taskState = "OPEN",
            ),
        )
    }

    @Test
    fun notificationFailureKeepsDurableReminderEligibleForRetry() = runBlocking {
        var reminderEnabled = true
        var consumes = 0
        val delivered = publishReminderBeforeConsume(
            publish = { false },
            consume = { consumes++; reminderEnabled = false; 1 },
        )
        assertFalse(delivered)
        assertEquals(0, consumes)
        assertTrue(reminderEnabled)

        try {
            publishReminderBeforeConsume(
                publish = { throw SecurityException("permission revoked during notify") },
                consume = { consumes++; reminderEnabled = false; 1 },
            )
            throw AssertionError("posting exception should propagate")
        } catch (_: SecurityException) {
            // Receiver logs the delivery failure; the durable reminder remains enabled.
        }
        assertEquals(0, consumes)
        assertTrue(reminderEnabled)

        val retry = publishReminderBeforeConsume(
            publish = { true },
            consume = { consumes++; reminderEnabled = false; 1 },
        )
        assertTrue(retry)
        assertEquals(1, consumes)
        assertFalse(reminderEnabled)
    }

    @Test
    fun crashAfterNotificationPostLeavesReminderRetryableAndConsumptionFollowsPost() = runBlocking {
        var reminderEnabled = true
        val events = mutableListOf<String>()
        try {
            publishReminderBeforeConsume(
                publish = { events += "posted"; true },
                consume = {
                    events += "consume-started"
                    throw java.io.IOException("simulated process interruption")
                },
            )
        } catch (_: java.io.IOException) {
            // A retry may replace the stable notification ID and then consume.
        }
        assertEquals(listOf("posted", "consume-started"), events)
        assertTrue(reminderEnabled)

        val retried = publishReminderBeforeConsume(
            publish = { events += "reposted"; true },
            consume = { events += "consumed"; reminderEnabled = false; 1 },
        )
        assertTrue(retried)
        assertEquals(listOf("posted", "consume-started", "reposted", "consumed"), events)
        assertFalse(reminderEnabled)
    }

    @Test
    fun sameTargetTimeDoesNotAuthorizeAStaleGeneration() {
        assertFalse(
            ProjectTaskReminderPolicy.shouldDeliver(
                reminderEnabled = true,
                reminderAt = NOW,
                expectedAt = NOW,
                reminderGeneration = 8L,
                expectedGeneration = 7L,
                taskState = "OPEN",
            ),
        )
    }
}
