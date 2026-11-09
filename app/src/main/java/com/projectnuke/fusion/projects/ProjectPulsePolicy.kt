package com.projectnuke.fusion.projects

import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.ProjectWorkPlanEntity
import com.projectnuke.fusion.data.ProjectWorkPlanStepEntity
import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.ui.ResearchRefreshQueueCandidate

const val MAX_PULSE_ITEMS_PER_SECTION = 4
const val MAX_PULSE_ITEMS_TOTAL = 20

sealed interface ProjectPulseItem {
    data class WorkPlan(
        val projectId: Long, val planId: String, val stepId: String?, val title: String,
        val stepTitle: String?, val stepState: String?,
    ) : ProjectPulseItem
    data class Task(val projectId: Long, val taskId: Long, val title: String, val dueAt: Long?) : ProjectPulseItem
    data class Decision(val projectId: Long, val decisionId: String, val title: String) : ProjectPulseItem
    data class Research(val projectId: Long, val conversationId: Long, val messageId: Long, val title: String) : ProjectPulseItem
    data class ReviewCard(val projectId: Long, val cardId: String, val title: String, val dueAt: Long) : ProjectPulseItem
}

data class ProjectPulseSection(val title: String, val items: List<ProjectPulseItem>)
data class ProjectPulseSnapshot(
    val projectId: Long,
    val projectName: String,
    val sections: List<ProjectPulseSection>,
    val generatedAtMillis: Long = 0L,
    val researchQueueCoverageIncomplete: Boolean = false,
) {
    val items: List<ProjectPulseItem> get() = sections.flatMap { it.items }
}

object ProjectPulsePolicy {
    fun project(
        projectId: Long,
        projectName: String,
        now: Long,
        activePlan: ProjectWorkPlanEntity?,
        planSteps: List<ProjectWorkPlanStepEntity>,
        openTasks: List<ProjectTaskEntity>,
        activeDecisions: List<ProjectDecisionEntity>,
        researchCandidates: List<ResearchRefreshQueueCandidate>,
        researchProjectIds: Map<Long, Long?>,
        reviewCards: List<StudyReviewCardEntity>,
        researchQueueCoverageIncomplete: Boolean = false,
    ): ProjectPulseSnapshot {
        require(projectId > 0L)
        val currentStep = activePlan?.takeIf { it.projectId == projectId && it.status == "ACTIVE" && it.archivedAt == null }
            ?.let { plan -> planSteps.firstOrNull { it.planId == plan.id && it.position == plan.currentStepPosition } }
        val planItem = activePlan?.takeIf { it.projectId == projectId && it.status == "ACTIVE" && it.archivedAt == null }
            ?.let { plan -> ProjectPulseItem.WorkPlan(projectId, plan.id, currentStep?.id, plan.title, currentStep?.title, currentStep?.state) }
        val tasks = openTasks.asSequence()
            .filter { it.projectId == projectId && it.state == "OPEN" }
            .distinctBy { it.id }
            .sortedWith(compareBy<ProjectTaskEntity> { dueReminderOrder(it, now) }
                .thenBy { dueReminderTime(it, now) }
                .thenByDescending { it.updatedAt }
                .thenByDescending { it.id })
            .take(MAX_PULSE_ITEMS_PER_SECTION)
            .map { task ->
                val dueAt = task.reminderAt?.takeIf { task.reminderEnabled && it <= now }
                ProjectPulseItem.Task(projectId, task.id, task.title, dueAt)
            }.toList()
        val decisions = activeDecisions.asSequence()
            .filter { it.projectId == projectId && it.status == "ACTIVE" }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<ProjectDecisionEntity> { it.createdAt }.thenByDescending { it.id })
            .take(MAX_PULSE_ITEMS_PER_SECTION)
            .map { ProjectPulseItem.Decision(projectId, it.id, it.title) }.toList()
        val research = researchCandidates.asSequence()
            .filter { it.frozenScope.projectId == projectId && it.receipt.projectContextId == projectId &&
                researchProjectIds[it.sourceConversationId] == projectId }
            .distinctBy { it.sourceConversationId to it.sourceMessageId }
            .take(MAX_PULSE_ITEMS_PER_SECTION)
            .map { ProjectPulseItem.Research(projectId, it.sourceConversationId, it.sourceMessageId, it.conversationTitle) }
            .toList()
        val reviews = reviewCards.asSequence()
            .filter { it.scope == "PROJECT" && it.projectId == projectId && it.dueAt <= now }
            .distinctBy { it.id }
            .sortedWith(compareBy<StudyReviewCardEntity> { it.dueAt }.thenBy { it.createdAt }.thenBy { it.id })
            .take(MAX_PULSE_ITEMS_PER_SECTION)
            .map { ProjectPulseItem.ReviewCard(projectId, it.id, it.front, it.dueAt) }.toList()
        val ordered = listOfNotNull(
            planItem?.let { ProjectPulseSection("진행 중", listOf(it)) },
            tasks.takeIf { it.isNotEmpty() }?.let { ProjectPulseSection("열린 할 일", it) },
            decisions.takeIf { it.isNotEmpty() }?.let { ProjectPulseSection("현재 결정", it) },
            research.takeIf { it.isNotEmpty() }?.let { ProjectPulseSection("다시 확인할 Research", it) },
            reviews.takeIf { it.isNotEmpty() }?.let { ProjectPulseSection("복습", it) },
        )
        var remaining = MAX_PULSE_ITEMS_TOTAL
        val bounded = ordered.mapNotNull { section ->
            if (remaining <= 0) return@mapNotNull null
            val items = section.items.take(remaining)
            remaining -= items.size
            section.copy(items = items).takeIf { items.isNotEmpty() }
        }
        return ProjectPulseSnapshot(
            projectId = projectId,
            projectName = projectName,
            sections = bounded,
            generatedAtMillis = now,
            researchQueueCoverageIncomplete = researchQueueCoverageIncomplete,
        )
    }

    private fun dueReminderOrder(task: ProjectTaskEntity, now: Long): Int =
        if (task.reminderEnabled && task.reminderAt != null && task.reminderAt <= now) 0 else 1

    private fun dueReminderTime(task: ProjectTaskEntity, now: Long): Long =
        if (task.reminderEnabled && task.reminderAt != null && task.reminderAt <= now) task.reminderAt else Long.MAX_VALUE
}
