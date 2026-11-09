package com.projectnuke.fusion.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProjectReviewSessionPolicyTest {
    @Test
    fun `review queue includes only eligible typed Pulse targets`() {
        val snapshot = snapshot(
            ProjectPulseItem.WorkPlan(1, "plan-review", "step-review", "검토 계획", "검토 단계", "REVIEW"),
            ProjectPulseItem.WorkPlan(1, "plan-running", "step-running", "진행 계획", "진행 단계", "RUNNING"),
            ProjectPulseItem.WorkPlan(1, "plan-failed", "step-failed", "실패 계획", "실패 단계", "FAILED"),
            ProjectPulseItem.Task(1, 10, "기한 지난 할 일", 900),
            ProjectPulseItem.Task(1, 11, "기한 전 할 일", 1100),
            ProjectPulseItem.ReviewCard(1, "due-card", "기한 지난 카드", 950),
            ProjectPulseItem.ReviewCard(1, "future-card", "기한 전 카드", 1050),
            ProjectPulseItem.Research(1, 30, 40, "Research 새로 확인"),
            ProjectPulseItem.Decision(1, "decision", "현재 결정"),
            now = 1000,
        )

        val items = ProjectReviewSessionPolicy.buildItems(snapshot)

        assertEquals(
            listOf(
                ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_REVIEW,
                ProjectReviewSessionPolicy.ProjectReviewKind.DUE_TASK,
                ProjectReviewSessionPolicy.ProjectReviewKind.DUE_REVIEW_CARD,
                ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_FAILED,
                ProjectReviewSessionPolicy.ProjectReviewKind.RESEARCH_REFRESH,
            ),
            items.map { it.kind },
        )
        assertFalse(items.any { it.title == "진행 계획" || it.title == "기한 전 할 일" || it.title == "기한 전 카드" })
        assertEquals(
            "알림 예정 시간이 지났어요",
            items.single { it.kind == ProjectReviewSessionPolicy.ProjectReviewKind.DUE_TASK }.subtitle,
        )
    }

    @Test
    fun `priority depends on enum and labels are presentation only`() {
        val kinds = ProjectReviewSessionPolicy.ProjectReviewKind.entries
        val prioritiesBeforeCopyChange = kinds.associateWith(ProjectReviewSessionPolicy::priority)
        val labels = kinds.associateWith(ProjectReviewSessionPolicy::labelFor)

        val priorityOrder = listOf(
            ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_REVIEW,
            ProjectReviewSessionPolicy.ProjectReviewKind.DUE_TASK,
            ProjectReviewSessionPolicy.ProjectReviewKind.DUE_REVIEW_CARD,
            ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_FAILED,
            ProjectReviewSessionPolicy.ProjectReviewKind.RESEARCH_REFRESH,
        )
        assertEquals(priorityOrder, kinds.sortedBy(ProjectReviewSessionPolicy::priority))
        assertEquals(listOf(0, 1, 2, 3, 4), priorityOrder.map(ProjectReviewSessionPolicy::priority))
        assertEquals("알림 확인", labels[ProjectReviewSessionPolicy.ProjectReviewKind.DUE_TASK])
        assertEquals("검토 필요", labels[ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_REVIEW])
        assertEquals("실패한 단계", labels[ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_FAILED])
        assertEquals("다시 확인할 Research", labels[ProjectReviewSessionPolicy.ProjectReviewKind.RESEARCH_REFRESH])
        assertEquals(prioritiesBeforeCopyChange, kinds.associateWith(ProjectReviewSessionPolicy::priority))
    }

    @Test
    fun `typed target identity survives queue construction`() {
        val items = ProjectReviewSessionPolicy.buildItems(snapshot(
            ProjectPulseItem.WorkPlan(7, "plan-1", "step-2", "plan", "step", "REVIEW"),
            ProjectPulseItem.Task(7, 88, "task", 999),
            ProjectPulseItem.Research(7, 44, 55, "research"),
            ProjectPulseItem.ReviewCard(7, "card-3", "card", 998),
            now = 1000,
        )).associateBy { it.title }

        assertEquals(ProjectReviewSessionPolicy.ProjectReviewTarget.WorkPlan(
            7, "plan-1", "step-2", ProjectReviewSessionPolicy.ProjectReviewKind.WORK_PLAN_REVIEW,
        ), items.getValue("plan").target)
        assertEquals(ProjectReviewSessionPolicy.ProjectReviewTarget.Task(7, 88), items.getValue("task").target)
        assertEquals(ProjectReviewSessionPolicy.ProjectReviewTarget.Research(7, 44, 55), items.getValue("research").target)
        assertEquals(ProjectReviewSessionPolicy.ProjectReviewTarget.ReviewCard(7, "card-3"), items.getValue("card").target)
    }

    @Test
    fun `skip and next each advance one and final index represents completion`() {
        val size = 3
        val afterSkip = ProjectReviewSessionPolicy.advance(0, size)
        val afterNext = ProjectReviewSessionPolicy.advance(1, size)

        assertEquals(1, afterSkip)
        assertEquals(2, afterNext)
        assertEquals(size, ProjectReviewSessionPolicy.advance(size - 1, size))
        assertEquals(0, ProjectReviewSessionPolicy.advance(0, 0))
    }

    private fun snapshot(vararg items: ProjectPulseItem, now: Long) = ProjectPulseSnapshot(
        projectId = 1,
        projectName = "Project",
        sections = listOf(ProjectPulseSection("pulse", items.toList())),
        generatedAtMillis = now,
    )
}
