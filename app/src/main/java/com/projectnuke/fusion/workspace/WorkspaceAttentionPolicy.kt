package com.projectnuke.fusion.workspace

import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.projects.ProjectWorkPlanStepState
import com.projectnuke.fusion.ui.ResearchRefreshQueueCandidate
import com.projectnuke.fusion.ui.ResearchRefreshQueuePolicy

object WorkspaceAttentionPolicy {
    const val MAX_TOTAL_ATTENTION = 12
    const val MAX_PER_KIND = 4

    const val REFRESH_AGE_DAYS = ResearchRefreshQueuePolicy.DEFAULT_REFRESH_AGE_DAYS

    private val kindPriority: Map<WorkspaceAttentionKind, Int> = mapOf(
        WorkspaceAttentionKind.WORK_PLAN_REVIEW to 1,
        WorkspaceAttentionKind.OPEN_TASK to 2,
        WorkspaceAttentionKind.DUE_REVIEW_CARD to 3,
        WorkspaceAttentionKind.WORK_PLAN_FAILED to 4,
        WorkspaceAttentionKind.RESEARCH_REFRESH to 5,
        WorkspaceAttentionKind.INBOX_ITEM to 6,
    )

    fun priorityFor(kind: WorkspaceAttentionKind): Int = kindPriority[kind] ?: 99

    data class WorkPlanAttention(
        val planId: String,
        val stepId: String,
        val kind: WorkspaceAttentionKind,
        val title: String,
        val subtitle: String,
        val priority: Int,
        val updatedAt: Long,
        val projectId: Long = 0L,
    )

    fun selectWorkPlanAttention(
        activePlanSteps: List<PlanStepRow>,
    ): List<WorkPlanAttention> {
        val review = activePlanSteps.filter { it.state == ProjectWorkPlanStepState.REVIEW.name }
        val failed = activePlanSteps.filter { it.state == ProjectWorkPlanStepState.FAILED.name }
        val running = activePlanSteps.filter { it.state == ProjectWorkPlanStepState.RUNNING.name }
        // Do NOT include RUNNING as retryable attention; only REVIEW / FAILED
        val reviewItems = review.map {
            WorkPlanAttention(
                planId = it.planId,
                stepId = it.stepId,
                kind = WorkspaceAttentionKind.WORK_PLAN_REVIEW,
                title = it.title,
                subtitle = "작업 계획 · 확인 필요",
                priority = priorityFor(WorkspaceAttentionKind.WORK_PLAN_REVIEW),
                updatedAt = it.updatedAt,
                projectId = it.projectId,
            )
        }.take(MAX_PER_KIND)
        val failedItems = failed.map {
            WorkPlanAttention(
                planId = it.planId,
                stepId = it.stepId,
                kind = WorkspaceAttentionKind.WORK_PLAN_FAILED,
                title = it.title,
                subtitle = "작업 계획 · 실패",
                priority = priorityFor(WorkspaceAttentionKind.WORK_PLAN_FAILED),
                updatedAt = it.updatedAt,
                projectId = it.projectId,
            )
        }.take(MAX_PER_KIND)
        return (reviewItems + failedItems)
    }

    data class PlanStepRow(
        val planId: String,
        val stepId: String,
        val state: String,
        val title: String,
        val projectId: Long = 0L,
        val updatedAt: Long,
    )

    data class AttentionCandidate(
        val item: WorkspaceAttentionItem,
        val sourceKey: String,
    )

    fun selectTaskAttention(
        tasks: List<ProjectTaskEntity>,
        now: Long = System.currentTimeMillis(),
    ): List<AttentionCandidate> {
        val dueOverdue = tasks.filter {
            it.state == "OPEN" && it.reminderEnabled && it.reminderAt != null && it.reminderAt <= now
        }.sortedWith(
            compareBy<ProjectTaskEntity> { it.reminderAt!! }
                .thenByDescending { it.updatedAt }
                .thenBy { it.id }
        )
        val remainingOpen = tasks.filter {
            it.state == "OPEN" && !(it.reminderEnabled && it.reminderAt != null && it.reminderAt <= now)
        }.sortedWith(
            compareByDescending<ProjectTaskEntity> { it.updatedAt }
                .thenByDescending { it.id }
        )
        val selected = (dueOverdue + remainingOpen)
            .mapIndexed { index, task ->
                val isDue = task.reminderEnabled && task.reminderAt != null && task.reminderAt <= now
                val subtitle = if (isDue) "할 일 · ${task.reminderAt?.let { formatReminderTime(it, now) } ?: "기한"}" else "할 일 · 미완료"
                val priority = if (isDue) priorityFor(WorkspaceAttentionKind.OPEN_TASK) else priorityFor(WorkspaceAttentionKind.OPEN_TASK) + 100
                WorkspaceAttentionItem(
                    stableId = "task:${task.id}",
                    kind = WorkspaceAttentionKind.OPEN_TASK,
                    title = task.title,
                    subtitle = subtitle,
                    projectId = task.projectId,
                    targetId = task.id.toString(),
                    priority = priority,
                    updatedAt = task.updatedAt,
                )
            }
            .filterIndexed { index, _ -> index < MAX_PER_KIND }
        return selected.map { AttentionCandidate(it, "task:${it.projectId}:${it.targetId}") }.distinctBy { it.sourceKey }.take(MAX_PER_KIND)
    }

    fun selectReviewCardAttention(
        cards: List<StudyReviewCardEntity>,
        now: Long = System.currentTimeMillis(),
    ): List<AttentionCandidate> {
        val due = cards.filter { it.dueAt <= now }.sortedWith(
            compareBy<StudyReviewCardEntity> { it.dueAt }.thenByDescending { it.updatedAt }.thenBy { it.id }
        ).map {
            WorkspaceAttentionItem(
                stableId = "review:${it.id}",
                kind = WorkspaceAttentionKind.DUE_REVIEW_CARD,
                title = it.front,
                subtitle = "복습 카드 · 확인 필요",
                projectId = it.projectId,
                targetId = it.id,
                priority = priorityFor(WorkspaceAttentionKind.DUE_REVIEW_CARD),
                updatedAt = it.updatedAt,
            )
        }.take(MAX_PER_KIND)
        return due.map { AttentionCandidate(it, "review:${it.projectId}:${it.targetId}") }.distinctBy { it.sourceKey }.take(MAX_PER_KIND)
    }

    fun selectInboxAttention(
        items: List<InboxItemEntity>,
    ): List<AttentionCandidate> {
        // Prefer older items over extremely recent captures so Action Center
        // does not become another "recent items" list.
        val sorted = items.sortedWith(
            compareBy<InboxItemEntity> { it.updatedAt }.thenBy { it.id }
        )
        return sorted.map {
            WorkspaceAttentionItem(
                stableId = "inbox:${it.id}",
                kind = WorkspaceAttentionKind.INBOX_ITEM,
                title = it.title,
                subtitle = "Inbox · 정리 후보",
                projectId = null,
                targetId = it.id,
                priority = priorityFor(WorkspaceAttentionKind.INBOX_ITEM),
                updatedAt = it.updatedAt,
            )
        }.map { AttentionCandidate(it, "inbox:${it.targetId}") }.take(MAX_PER_KIND)
    }

    fun selectResearchRefreshAttention(
        candidates: List<ResearchRefreshQueueCandidate>,
    ): List<WorkspaceAttentionItem> = candidates.take(MAX_PER_KIND).map { candidate ->
        WorkspaceAttentionItem(
            stableId = candidate.stableId,
            kind = WorkspaceAttentionKind.RESEARCH_REFRESH,
            title = candidate.conversationTitle,
            subtitle = "마지막 조사 후 ${candidate.ageDays}일 · 다시 확인할 Research",
            projectId = null,
            targetId = "${candidate.sourceConversationId}:${candidate.sourceMessageId}",
            priority = priorityFor(WorkspaceAttentionKind.RESEARCH_REFRESH),
            updatedAt = candidate.sourceTimestamp,
        )
    }

    fun formatReminderTime(reminderAt: Long, now: Long): String {
        val diffMs = now - reminderAt
        return when {
            diffMs >= 24 * 60 * 60 * 1000L -> "${diffMs / (24 * 60 * 60 * 1000L)}일 전"
            diffMs >= 60 * 60 * 1000L -> "${diffMs / (60 * 60 * 1000L)}시간 전"
            diffMs >= 60 * 1000L -> "${diffMs / (60 * 1000L)}분 전"
            else -> "방금"
        }
    }
}
