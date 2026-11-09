package com.projectnuke.fusion.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceActionTargetPolicyTest {
    @Test
    fun workPlanAttentionTargetRetainsProjectPlanAndStepIdentity() {
        val target = WorkspaceActionTargetPolicy.workPlan(item(
            WorkspaceAttentionKind.WORK_PLAN_REVIEW, projectId = 41L, targetId = "plan-7:step-9",
        ))
        assertEquals(WorkspaceWorkPlanTarget(41L, "plan-7", "step-9"), target)
    }

    @Test
    fun taskAttentionTargetRetainsProjectAndTaskIdentity() {
        val target = WorkspaceActionTargetPolicy.task(item(
            WorkspaceAttentionKind.OPEN_TASK, projectId = 53L, targetId = "812",
        ))
        assertEquals(WorkspaceTaskTarget(53L, 812L), target)
    }

    @Test
    fun targetProjectIsTheProjectSelectedByNavigation() {
        val plan = WorkspaceActionTargetPolicy.workPlan(item(
            WorkspaceAttentionKind.WORK_PLAN_FAILED, projectId = 14L, targetId = "plan:step",
        ))
        val task = WorkspaceActionTargetPolicy.task(item(
            WorkspaceAttentionKind.OPEN_TASK, projectId = 19L, targetId = "27",
        ))
        // Production navigation assigns these exact, validated IDs to selectedProjectId.
        assertEquals(14L, assertNotNullAndReturn(plan).projectId)
        assertEquals(19L, assertNotNullAndReturn(task).projectId)
    }

    @Test
    fun malformedOrProjectlessRowsCannotNavigate() {
        assertNull(WorkspaceActionTargetPolicy.workPlan(item(
            WorkspaceAttentionKind.WORK_PLAN_REVIEW, projectId = null, targetId = "p:s",
        )))
        assertNull(WorkspaceActionTargetPolicy.workPlan(item(
            WorkspaceAttentionKind.WORK_PLAN_REVIEW, projectId = 4L, targetId = "p",
        )))
        assertNull(WorkspaceActionTargetPolicy.task(item(
            WorkspaceAttentionKind.OPEN_TASK, projectId = 4L, targetId = "not-a-task-id",
        )))
    }

    @Test
    fun staleOrMismatchedWorkPlanTargetsFailClosed() {
        val target = WorkspaceWorkPlanTarget(2L, "plan-a", "step-a")
        assertTrue(WorkspaceActionTargetPolicy.canOpenWorkPlan(target, true, 2L, "plan-a"))
        assertFalse(WorkspaceActionTargetPolicy.canOpenWorkPlan(target, false, 2L, "plan-a"))
        assertFalse(WorkspaceActionTargetPolicy.canOpenWorkPlan(target, true, null, "plan-a"))
        assertFalse(WorkspaceActionTargetPolicy.canOpenWorkPlan(target, true, 3L, "plan-a"))
        assertFalse(WorkspaceActionTargetPolicy.canOpenWorkPlan(target, true, 2L, null))
        assertFalse(WorkspaceActionTargetPolicy.canOpenWorkPlan(target, true, 2L, "another-plan"))
    }

    @Test
    fun staleMovedOrDeletedTaskTargetsFailClosedWithoutFallback() {
        val target = WorkspaceTaskTarget(8L, 12L)
        assertTrue(WorkspaceActionTargetPolicy.canOpenTask(target, true, 12L, 8L))
        assertFalse(WorkspaceActionTargetPolicy.canOpenTask(target, false, 12L, 8L))
        assertFalse(WorkspaceActionTargetPolicy.canOpenTask(target, true, null, null))
        assertFalse(WorkspaceActionTargetPolicy.canOpenTask(target, true, 13L, 8L))
        assertFalse(WorkspaceActionTargetPolicy.canOpenTask(target, true, 12L, 9L))
    }

    private fun item(
        kind: WorkspaceAttentionKind,
        projectId: Long?,
        targetId: String,
    ) = WorkspaceAttentionItem(
        stableId = "test",
        kind = kind,
        title = "target",
        subtitle = "",
        projectId = projectId,
        targetId = targetId,
        priority = 0,
        updatedAt = 0L,
    )

    private fun <T : Any> assertNotNullAndReturn(value: T?): T {
        assertNotNull(value)
        return requireNotNull(value)
    }
}
