package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ProjectContextSnapshot
import com.projectnuke.fusion.data.ProjectWorkPlanEntity
import com.projectnuke.fusion.data.ProjectWorkPlanStepEntity
import com.projectnuke.fusion.projects.ProjectWorkPlanAttempt
import com.projectnuke.fusion.projects.ProjectWorkPlanStepState
import com.projectnuke.fusion.projects.ProjectWorkPlanStepType
import com.projectnuke.fusion.projects.ProjectWorkPlanStatus
import com.projectnuke.fusion.projects.ProjectWorkPlanWithSteps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectWorkPlanNextStepCoordinatorTest {
    @Test fun `one next-step call dispatches one attempt and stops before the pending successor`() = runTest {
        val gateway = FakeGateway(planWithSteps())
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { true }

        val result = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)

        assertTrue(result is ProjectWorkPlanNextStepCoordinator.Outcome.Dispatched)
        assertEquals(
            com.projectnuke.fusion.projects.ProjectWorkPlanDestination.RESEARCH_CHAT,
            (result as ProjectWorkPlanNextStepCoordinator.Outcome.Dispatched).action.destination,
        )
        assertEquals(1, gateway.startedAttempts.size)
        assertEquals(ProjectWorkPlanStepState.RUNNING.name, gateway.current!!.steps[0].state)
        assertEquals(ProjectWorkPlanStepState.PENDING.name, gateway.current!!.steps[1].state)
    }

    @Test fun `dispatch rejection releases only the attempt acquired by this action`() = runTest {
        val gateway = FakeGateway(planWithSteps())
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { false }

        val result = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)

        assertTrue(result is ProjectWorkPlanNextStepCoordinator.Outcome.Rejected)
        assertEquals(listOf(gateway.startedAttempts.single().attemptId), gateway.cancelledAttemptIds)
    }

    @Test fun `accepted dispatch transfers ownership and later surface disposal does not cancel it`() = runTest {
        val gateway = FakeGateway(planWithSteps())
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { true }

        val result = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)

        assertTrue(result is ProjectWorkPlanNextStepCoordinator.Outcome.Dispatched)
        assertTrue(gateway.cancelledAttemptIds.isEmpty())
    }

    @Test fun `duplicate tap cannot acquire a second attempt while dispatch is pending`() = runTest {
        val gateway = FakeGateway(planWithSteps())
        val dispatchEntered = CompletableDeferred<Unit>()
        val allowDispatchToReturn = CompletableDeferred<Unit>()
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) {
            dispatchEntered.complete(Unit)
            allowDispatchToReturn.await()
            true
        }
        val first = async { coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID) }
        dispatchEntered.await()

        val duplicate = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)
        allowDispatchToReturn.complete(Unit)

        assertTrue(duplicate is ProjectWorkPlanNextStepCoordinator.Outcome.AlreadyRunning)
        assertTrue(first.await() is ProjectWorkPlanNextStepCoordinator.Outcome.Dispatched)
        assertEquals(1, gateway.startedAttempts.size)
    }

    @Test fun `cancellation before dispatch acceptance cancels exact attempt and propagates`() = runTest {
        val gateway = FakeGateway(planWithSteps())
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) {
            throw CancellationException("test cancellation")
        }

        try {
            coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)
            throw AssertionError("CancellationException should propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf(gateway.startedAttempts.single().attemptId), gateway.cancelledAttemptIds)
        }
    }

    @Test fun `stale rendered step id is rejected without acquiring`() = runTest {
        val gateway = FakeGateway(planWithSteps(currentPosition = 1))
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { true }

        val result = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)

        assertEquals(ProjectWorkPlanNextStepCoordinator.Outcome.Stale, result)
        assertTrue(gateway.startedAttempts.isEmpty())
    }

    @Test fun `plan pause after render is re-read before acquisition`() = runTest {
        val gateway = FakeGateway(planWithSteps()).apply {
            mutateOnLoad = 2L to { details -> details.copy(plan = details.plan.copy(status = ProjectWorkPlanStatus.PAUSED.name)) }
        }
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { true }

        val result = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)

        assertEquals(ProjectWorkPlanNextStepCoordinator.Outcome.Paused, result)
        assertTrue(gateway.startedAttempts.isEmpty())
    }

    @Test fun `current step change after render cannot redirect the tap to another step`() = runTest {
        val gateway = FakeGateway(planWithSteps()).apply {
            mutateOnLoad = 2L to { details ->
                details.copy(
                    plan = details.plan.copy(currentStepPosition = 1),
                    steps = details.steps.map { step ->
                        if (step.position == 1) step.copy(state = ProjectWorkPlanStepState.READY.name) else step
                    },
                )
            }
        }
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { true }

        val result = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)

        assertEquals(ProjectWorkPlanNextStepCoordinator.Outcome.Stale, result)
        assertTrue(gateway.startedAttempts.isEmpty())
    }

    @Test fun `linked task appearing after render blocks acquisition`() = runTest {
        val gateway = FakeGateway(planWithSteps()).apply {
            mutateOnLoad = 2L to { details ->
                details.copy(steps = details.steps.map { step ->
                    if (step.id == FIRST_STEP_ID) step.copy(linkedTaskId = 901L) else step
                })
            }
        }
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { true }

        val result = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)

        assertEquals(
            ProjectWorkPlanNextStepCoordinator.Outcome.Blocked(
                com.projectnuke.fusion.projects.ProjectWorkPlanNextAction.BlockReason.LINKED_TASK,
            ),
            result,
        )
        assertTrue(gateway.startedAttempts.isEmpty())
    }

    @Test fun `review retry remains explicit and primary tap does not reacquire review`() = runTest {
        val gateway = FakeGateway(planWithSteps(firstState = ProjectWorkPlanStepState.REVIEW.name))
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { true }

        val primary = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)
        val retry = coordinator.retryReviewedStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)

        assertTrue(primary is ProjectWorkPlanNextStepCoordinator.Outcome.ReviewRequired)
        assertTrue(retry is ProjectWorkPlanNextStepCoordinator.Outcome.Dispatched)
        assertEquals(1, gateway.startedAttempts.size)
    }

    @Test fun `manual step requires a separate explicit confirmation and uses no attempt`() = runTest {
        val gateway = FakeGateway(planWithSteps(firstType = ProjectWorkPlanStepType.MANUAL.name))
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { true }

        val action = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)
        assertTrue(action is ProjectWorkPlanNextStepCoordinator.Outcome.ManualConfirmationRequired)
        assertTrue(gateway.startedAttempts.isEmpty())

        assertTrue(coordinator.completeManualStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID))
        assertTrue(gateway.startedAttempts.isEmpty())
        assertEquals(1, gateway.manualCompletions)
        assertEquals(ProjectWorkPlanStepState.READY.name, gateway.current!!.steps[1].state)
    }

    @Test fun `review completion readies successor without invoking another attempt`() = runTest {
        val gateway = FakeGateway(planWithSteps(firstState = ProjectWorkPlanStepState.REVIEW.name))
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { true }

        assertTrue(coordinator.completeReviewedStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID))

        assertEquals(1, gateway.reviewCompletions)
        assertTrue(gateway.startedAttempts.isEmpty())
        assertEquals(1, gateway.current!!.plan.currentStepPosition)
        assertEquals(ProjectWorkPlanStepState.READY.name, gateway.current!!.steps[1].state)
    }

    @Test fun `ask freezes project context once into its prepared action`() = runTest {
        val gateway = FakeGateway(planWithSteps(firstType = ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT.name))
        var accepted: com.projectnuke.fusion.projects.ProjectWorkPlanPreparedAction? = null
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { action -> accepted = action; true }

        val result = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)

        assertTrue(result is ProjectWorkPlanNextStepCoordinator.Outcome.Dispatched)
        assertEquals(1, gateway.projectContextReads)
        assertEquals(listOf(101L), accepted?.requestSnapshot?.projectSourceIds)
        assertFalse(accepted?.requestSnapshot?.webSearchEnabled ?: true)
        assertEquals("DEFERRED", accepted?.requestSnapshot?.egressState)
    }

    @Test fun `task and decision step types stop at their existing confirmation owners`() = runTest {
        listOf(
            ProjectWorkPlanStepType.CREATE_TASK to ProjectWorkPlanNextStepCoordinator.Outcome.NeedsTaskConfirmation::class,
            ProjectWorkPlanStepType.PROPOSE_DECISION to ProjectWorkPlanNextStepCoordinator.Outcome.NeedsDecisionConfirmation::class,
        ).forEach { (type, expectedOutcome) ->
            val gateway = FakeGateway(planWithSteps(firstType = type.name))
            val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { false }

            val result = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)

            assertEquals(expectedOutcome, result::class)
            assertEquals(1, gateway.startedAttempts.size)
            val runningStep = gateway.current!!.steps.single { it.id == FIRST_STEP_ID }
            assertEquals(ProjectWorkPlanStepState.RUNNING.name, runningStep.state)
            assertEquals(
                gateway.startedAttempts.single().attemptId,
                runningStep.activeAttemptId,
            )
            assertTrue(gateway.cancelledAttemptIds.isEmpty())
        }
    }

    @Test fun `duplicate pending-confirmation cleanup is harmless and cannot cancel a newer attempt`() = runTest {
        val gateway = FakeGateway(planWithSteps(firstType = ProjectWorkPlanStepType.CREATE_TASK.name))
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { false }
        val firstOutcome = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)
            as ProjectWorkPlanNextStepCoordinator.Outcome.NeedsTaskConfirmation

        assertTrue(coordinator.cancelAttempt(firstOutcome.attempt))
        assertFalse(coordinator.cancelAttempt(firstOutcome.attempt))
        val retryOutcome = coordinator.runNextStep(PROJECT_ID, PLAN_ID, FIRST_STEP_ID)
            as ProjectWorkPlanNextStepCoordinator.Outcome.NeedsTaskConfirmation

        assertTrue(retryOutcome.attempt.attemptId != firstOutcome.attempt.attemptId)
        assertFalse(coordinator.cancelAttempt(firstOutcome.attempt))
        val currentStep = gateway.current!!.steps.single { it.id == FIRST_STEP_ID }
        assertEquals(ProjectWorkPlanStepState.RUNNING.name, currentStep.state)
        assertEquals(retryOutcome.attempt.attemptId, currentStep.activeAttemptId)
        assertEquals(listOf(firstOutcome.attempt.attemptId), gateway.cancelledAttemptIds)
    }

    private class FakeGateway(var current: ProjectWorkPlanWithSteps?) : ProjectWorkPlanNextStepGateway {
        val startedAttempts = mutableListOf<ProjectWorkPlanAttempt>()
        val cancelledAttemptIds = mutableListOf<String>()
        var projectContextReads = 0
        var manualCompletions = 0
        var reviewCompletions = 0
        var loadCount = 0L
        var mutateOnLoad: Pair<Long, (ProjectWorkPlanWithSteps) -> ProjectWorkPlanWithSteps>? = null

        override suspend fun load(planId: String, projectId: Long): ProjectWorkPlanWithSteps? {
            loadCount++
            mutateOnLoad?.takeIf { it.first == loadCount }?.let { (_, mutation) ->
                current = current?.let(mutation)
                mutateOnLoad = null
            }
            return current?.takeIf { it.plan.id == planId && it.plan.projectId == projectId }
        }

        override suspend fun startStep(planId: String, stepId: String, projectId: Long): ProjectWorkPlanAttempt? {
            val details = load(planId, projectId) ?: return null
            val step = details.steps.singleOrNull { it.id == stepId } ?: return null
            if (step.position != details.plan.currentStepPosition || step.state !in setOf(
                    ProjectWorkPlanStepState.READY.name, ProjectWorkPlanStepState.FAILED.name, ProjectWorkPlanStepState.REVIEW.name,
                ) || step.activeAttemptId != null
            ) return null
            val attempt = ProjectWorkPlanAttempt(
                planId = planId, stepId = stepId, attemptId = "attempt-${startedAttempts.size + 1}", projectId = projectId,
                type = ProjectWorkPlanStepType.fromModelValue(step.type) ?: return null,
                title = step.title, instruction = step.instruction, planTitle = details.plan.title, goal = details.plan.goal,
                desiredOutput = details.plan.desiredOutput, constraintText = details.plan.constraintText,
            )
            startedAttempts += attempt
            current = details.copy(steps = details.steps.map { row ->
                if (row.id == stepId) row.copy(state = ProjectWorkPlanStepState.RUNNING.name, activeAttemptId = attempt.attemptId)
                else row
            })
            return attempt
        }

        override suspend fun cancelAttempt(attempt: ProjectWorkPlanAttempt): Boolean {
            val details = current ?: return false
            val step = details.steps.singleOrNull { it.id == attempt.stepId } ?: return false
            if (step.activeAttemptId != attempt.attemptId || step.state != ProjectWorkPlanStepState.RUNNING.name) return false
            cancelledAttemptIds += attempt.attemptId
            current = details.copy(steps = details.steps.map { row ->
                if (row.id == attempt.stepId) row.copy(state = ProjectWorkPlanStepState.FAILED.name, activeAttemptId = null)
                else row
            })
            return true
        }

        override suspend fun completeManualStep(planId: String, stepId: String, projectId: Long): Boolean {
            val details = load(planId, projectId) ?: return false
            val step = details.steps.singleOrNull { it.id == stepId } ?: return false
            if (step.position != details.plan.currentStepPosition || step.type != ProjectWorkPlanStepType.MANUAL.name ||
                step.state != ProjectWorkPlanStepState.READY.name || details.plan.status != ProjectWorkPlanStatus.ACTIVE.name
            ) return false
            manualCompletions++
            advance(details, stepId)
            return true
        }

        override suspend fun completeReviewedStep(planId: String, stepId: String, projectId: Long): Boolean {
            val details = load(planId, projectId) ?: return false
            val step = details.steps.singleOrNull { it.id == stepId } ?: return false
            if (step.position != details.plan.currentStepPosition || step.state != ProjectWorkPlanStepState.REVIEW.name ||
                details.plan.status != ProjectWorkPlanStatus.ACTIVE.name
            ) return false
            reviewCompletions++
            advance(details, stepId)
            return true
        }

        override suspend fun freezeProjectContext(projectId: Long): ProjectContextSnapshot? {
            projectContextReads++
            return ProjectContextSnapshot(
                projectId = projectId, instructions = "Frozen", files = listOf(
                    com.projectnuke.fusion.chat.ProjectFileSnapshot(101L, "source.md", "text/markdown", "/source.md"),
                ),
            )
        }

        private fun advance(details: ProjectWorkPlanWithSteps, stepId: String) {
            val nextPosition = details.plan.currentStepPosition + 1
            current = details.copy(
                plan = details.plan.copy(
                    currentStepPosition = nextPosition,
                    status = if (nextPosition >= details.steps.size) ProjectWorkPlanStatus.COMPLETED.name else details.plan.status,
                ),
                steps = details.steps.map { step ->
                    when {
                        step.id == stepId -> step.copy(state = ProjectWorkPlanStepState.COMPLETED.name)
                        step.position == nextPosition -> step.copy(state = ProjectWorkPlanStepState.READY.name)
                        else -> step
                    }
                },
            )
        }
    }

    companion object {
        private const val PROJECT_ID = 7L
        private const val PLAN_ID = "plan-1"
        private const val FIRST_STEP_ID = "step-1"

        private fun planWithSteps(
            currentPosition: Int = 0,
            firstState: String = ProjectWorkPlanStepState.READY.name,
            firstType: String = ProjectWorkPlanStepType.RESEARCH.name,
        ) = ProjectWorkPlanWithSteps(
            plan = ProjectWorkPlanEntity(
                id = PLAN_ID, projectId = PROJECT_ID, title = "Plan", goal = "Goal", desiredOutput = "",
                constraintText = "", status = ProjectWorkPlanStatus.ACTIVE.name,
                currentStepPosition = currentPosition, archivedAt = null, createdAt = 1L, updatedAt = 1L,
            ),
            steps = listOf(
                ProjectWorkPlanStepEntity(
                    id = FIRST_STEP_ID, planId = PLAN_ID, position = 0, title = "First", instruction = "Check sources",
                    type = firstType, state = firstState, resultKind = "MESSAGE", resultId = "20",
                    createdAt = 1L, updatedAt = 1L,
                ),
                ProjectWorkPlanStepEntity(
                    id = "step-2", planId = PLAN_ID, position = 1, title = "Second", instruction = "Draft",
                    type = ProjectWorkPlanStepType.WRITE_DRAFT.name, state = ProjectWorkPlanStepState.PENDING.name,
                    createdAt = 1L, updatedAt = 1L,
                ),
            ),
        )
    }
}
