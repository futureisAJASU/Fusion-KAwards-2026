package com.projectnuke.fusion.tasks

import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.StudyReviewCardEntity

/**
 * Bounded durable reconciliation for reminders that fired while notification
 * delivery was unavailable (N05). Defines one truthful policy shared by both
 * project-task and study-review-card reminder flows.
 *
 * Reconciliation does NOT schedule alarms with past timestamps. Instead it
 * delegates to the shared [AndroidProjectTaskReminderScheduler] delivery
 * path, which atomically consumes the reminder via compare-and-consume on
 * the persisted expectedAt. The BroadcastReceiver and reconciliation share
 * this single delivery path, giving exactly-once semantics without a lock.
 *
 * The DAO query already constrains to enabled reminders whose [reminderAt]
 * is in the past. [filterDeliverableTasks] / [filterDeliverableCards]
 * provide pure-logic pre-filtering for host testing.
 */
object ReminderReconciliationPolicy {
    fun isOverdue(reminderAt: Long?, now: Long): Boolean = reminderAt != null && reminderAt <= now

    /**
     * Pure-logic filter for host testing: given a candidate list (already
     * fetched as overdue by the DAO), returns rows whose durable state
     * still makes them deliverable.
     */
    fun filterDeliverableTasks(tasks: List<ProjectTaskEntity>, now: Long): List<ProjectTaskEntity> =
        tasks.filter { task ->
            isOverdue(task.reminderAt, now) &&
                task.reminderEnabled &&
                task.reminderAt != null &&
                task.state == ProjectTaskState.OPEN.name
        }

    fun filterDeliverableCards(cards: List<StudyReviewCardEntity>, now: Long): List<StudyReviewCardEntity> =
        cards.filter { card ->
            isOverdue(card.reminderAt, now) &&
                card.reminderEnabled &&
                card.reminderAt != null
        }
}
