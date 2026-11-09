package com.projectnuke.fusion.projects

import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.AnswerContextRoute
import com.projectnuke.fusion.chat.ProjectSourceScope
import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.ProjectWorkPlanEntity
import com.projectnuke.fusion.data.ProjectWorkPlanStepEntity
import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.ui.ResearchRefreshQueueCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPulsePolicyTest {
    private val now = 10_000L

    @Test fun includesOnlyExactProjectAndCurrentDurableState() {
        val result = ProjectPulsePolicy.project(
            1, "Alpha", now,
            plan(1, "active", "ACTIVE", 0),
            listOf(step("active-step", "active", 0), step("other-step", "other", 0)),
            listOf(task(1, 1), task(2, 2)),
            listOf(decision(1, "current", "ACTIVE"), decision(2, "other", "ACTIVE"), decision(1, "old", "SUPERSEDED")),
            emptyList(), emptyMap(),
            listOf(card("due", 1, now), card("future", 1, now + 1), card("other", 2, now)),
        )
        val planItem = result.sections.first().items.single() as ProjectPulseItem.WorkPlan
        assertEquals("active-step", planItem.stepId)
        assertEquals(setOf(1L), result.items.map { when (it) {
            is ProjectPulseItem.WorkPlan -> it.projectId
            is ProjectPulseItem.Task -> it.projectId
            is ProjectPulseItem.Decision -> it.projectId
            is ProjectPulseItem.Research -> it.projectId
            is ProjectPulseItem.ReviewCard -> it.projectId
        } }.toSet())
        assertFalse(result.items.filterIsInstance<ProjectPulseItem.Decision>().any { it.decisionId == "old" })
        assertEquals(listOf("due"), result.items.filterIsInstance<ProjectPulseItem.ReviewCard>().map { it.cardId })
    }

    @Test fun dueRemindersComeFirstAndUpdatedAtIsNotADeadline() {
        val result = ProjectPulsePolicy.project(1, "Alpha", now, null, emptyList(), listOf(
            task(1, 1, updated = now + 50_000),
            task(1, 2, due = now - 10),
            task(1, 3, due = now + 20),
        ), emptyList(), emptyList(), emptyMap(), emptyList())
        val items = result.items.filterIsInstance<ProjectPulseItem.Task>()
        assertEquals(listOf(2L, 1L, 3L), items.map { it.taskId })
        assertEquals(now - 10, items.first().dueAt)
        assertEquals(null, items[1].dueAt)
    }

    @Test fun researchUsesExistingProjectScopeAndAllSectionsAreBoundedAndStable() {
        val research = candidate(1, 10, 100)
        val result = ProjectPulsePolicy.project(
            1, "Alpha", now, plan(1, "p", "ACTIVE", 0), listOf(step("s", "p", 0)),
            (1L..8L).map { task(1, it, updated = it) },
            (1..8).map { decision(1, "d$it", "ACTIVE", created = it.toLong()) },
            listOf(research), mapOf(10L to 1L),
            (1..8).map { card("c$it", 1, now - 1, created = it.toLong()) },
        )
        assertTrue(result.sections.all { it.items.size <= MAX_PULSE_ITEMS_PER_SECTION })
        assertTrue(result.items.size <= MAX_PULSE_ITEMS_TOTAL)
        assertEquals(listOf("진행 중", "열린 할 일", "현재 결정", "다시 확인할 Research", "복습"), result.sections.map { it.title })
        assertEquals(listOf(8L, 7L, 6L, 5L), result.items.filterIsInstance<ProjectPulseItem.Task>().map { it.taskId })
        assertEquals(listOf(8L, 7L, 6L, 5L), result.items.filterIsInstance<ProjectPulseItem.Decision>().map { it.decisionId.removePrefix("d").toLong() })
        assertEquals(listOf(100L), result.items.filterIsInstance<ProjectPulseItem.Research>().map { it.messageId })
        val withoutMembership = ProjectPulsePolicy.project(
            1, "Alpha", now, null, emptyList(), emptyList(), emptyList(), listOf(research), mapOf(10L to 2L), emptyList())
        assertTrue(withoutMembership.items.none { it is ProjectPulseItem.Research })
        assertEquals(result, ProjectPulsePolicy.project(
            1, "Alpha", now, plan(1, "p", "ACTIVE", 0), listOf(step("s", "p", 0)),
            (1L..8L).map { task(1, it, updated = it) },
            (1..8).map { decision(1, "d$it", "ACTIVE", created = it.toLong()) },
            listOf(research), mapOf(10L to 1L),
            (1..8).map { card("c$it", 1, now - 1, created = it.toLong()) },
        ))
    }

    private fun plan(project: Long, id: String, status: String, current: Int) = ProjectWorkPlanEntity(
        id, project, "Plan", "", "", "", status, current, null, 1, 2)
    private fun step(id: String, planId: String, position: Int) = ProjectWorkPlanStepEntity(
        id, planId, position, "Step $position", "", "RESEARCH", "REVIEW", createdAt = 1, updatedAt = 2)
    private fun task(project: Long, id: Long, due: Long? = null, updated: Long = 1) = ProjectTaskEntity(
        id, project, "Task $id", "", createdAt = 1, updatedAt = updated, reminderAt = due, reminderEnabled = due != null)
    private fun decision(project: Long, id: String, status: String, created: Long = 1) = ProjectDecisionEntity(
        id, project, id, "", status, createdAt = created, updatedAt = created)
    private fun card(id: String, project: Long, due: Long, created: Long = 1) = StudyReviewCardEntity(
        id, "PROJECT", project, id, "back", "MANUAL", null, null, null, null, null,
        dueAt = due, createdAt = created, updatedAt = created)
    private fun candidate(project: Long, conversation: Long, message: Long) = ResearchRefreshQueueCandidate(
        conversation, message, 1, "Research", "question", "answer",
        AnswerContextReceipt.build(AnswerContextRoute.DETERMINISTIC, interactionMode = "RESEARCH", projectContextId = project),
        ProjectSourceScope(projectId = project), ageDays = 20)
}
