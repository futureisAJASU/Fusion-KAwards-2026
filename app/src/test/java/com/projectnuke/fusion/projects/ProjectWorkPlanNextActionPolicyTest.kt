package com.projectnuke.fusion.projects

import com.projectnuke.fusion.data.ProjectWorkPlanEntity
import com.projectnuke.fusion.data.ProjectWorkPlanStepEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectWorkPlanNextActionPolicyTest {
    @Test fun `ready active step starts one attempt`() {
        assertEquals(ProjectWorkPlanNextAction.Start("step-1"), decide())
    }

    @Test fun `failed active step retries below limit only`() {
        assertEquals(
            ProjectWorkPlanNextAction.Retry("step-1"),
            decide(state = ProjectWorkPlanStepState.FAILED.name, attemptCount = ProjectWorkPlanRepository.MAX_STEP_ATTEMPTS - 1),
        )
        assertEquals(
            ProjectWorkPlanNextAction.Blocked(ProjectWorkPlanNextAction.BlockReason.ATTEMPT_LIMIT),
            decide(state = ProjectWorkPlanStepState.FAILED.name, attemptCount = ProjectWorkPlanRepository.MAX_STEP_ATTEMPTS),
        )
    }

    @Test fun `review and running never start another primary attempt`() {
        assertEquals(
            ProjectWorkPlanNextAction.ReviewRequired("step-1"),
            decide(state = ProjectWorkPlanStepState.REVIEW.name),
        )
        assertEquals(
            ProjectWorkPlanNextAction.AlreadyRunning("step-1"),
            decide(state = ProjectWorkPlanStepState.RUNNING.name, activeAttemptId = "attempt-1"),
        )
    }

    @Test fun `manual step requests explicit completion without acquiring an attempt`() {
        assertEquals(
            ProjectWorkPlanNextAction.ManualConfirmationRequired("step-1"),
            decide(type = ProjectWorkPlanStepType.MANUAL.name),
        )
    }

    @Test fun `pending paused cancelled completed and archived states cannot execute`() {
        assertEquals(
            ProjectWorkPlanNextAction.Blocked(ProjectWorkPlanNextAction.BlockReason.NOT_READY),
            decide(state = ProjectWorkPlanStepState.PENDING.name),
        )
        assertEquals(ProjectWorkPlanNextAction.Paused, decide(planStatus = ProjectWorkPlanStatus.PAUSED.name))
        assertEquals(ProjectWorkPlanNextAction.Finished, decide(planStatus = ProjectWorkPlanStatus.CANCELLED.name))
        assertEquals(ProjectWorkPlanNextAction.Finished, decide(planStatus = ProjectWorkPlanStatus.COMPLETED.name))
        assertEquals(ProjectWorkPlanNextAction.Blocked(ProjectWorkPlanNextAction.BlockReason.ARCHIVED), decide(archivedAt = 50L))
    }

    @Test fun `expected durable step mismatch is stale`() {
        assertEquals(ProjectWorkPlanNextAction.Stale, decide(expectedStepId = "rendered-old-step"))
    }

    private fun decide(
        planStatus: String = ProjectWorkPlanStatus.ACTIVE.name,
        archivedAt: Long? = null,
        state: String = ProjectWorkPlanStepState.READY.name,
        type: String = ProjectWorkPlanStepType.RESEARCH.name,
        attemptCount: Int = 0,
        activeAttemptId: String? = null,
        expectedStepId: String = "step-1",
    ): ProjectWorkPlanNextAction = ProjectWorkPlanNextActionPolicy.resolve(
        ProjectWorkPlanWithSteps(
            plan = ProjectWorkPlanEntity(
                id = "plan-1", projectId = 7L, title = "Plan", goal = "Goal", desiredOutput = "",
                constraintText = "", status = planStatus, currentStepPosition = 0, archivedAt = archivedAt,
                createdAt = 1L, updatedAt = 1L,
            ),
            steps = listOf(
                ProjectWorkPlanStepEntity(
                    id = "step-1", planId = "plan-1", position = 0, title = "Step", instruction = "Do it",
                    type = type, state = state, attemptCount = attemptCount, activeAttemptId = activeAttemptId,
                    createdAt = 1L, updatedAt = 1L,
                ),
            ),
        ),
        expectedStepId,
    )
}
