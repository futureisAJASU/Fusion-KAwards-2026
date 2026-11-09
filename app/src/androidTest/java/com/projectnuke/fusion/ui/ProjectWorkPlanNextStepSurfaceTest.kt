package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.projectnuke.fusion.chat.ProjectContextSnapshot
import com.projectnuke.fusion.projects.ProjectWorkPlanAttempt
import com.projectnuke.fusion.projects.ProjectWorkPlanStepState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.projectnuke.fusion.projects.ProjectWorkPlanNextAction
import com.projectnuke.fusion.projects.ProjectWorkPlanStepType
import com.projectnuke.fusion.projects.ProjectWorkPlanWithSteps

class ProjectWorkPlanNextStepSurfaceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readyHasOnePrimaryActionAndReviewCannotStartAnotherAttempt() {
        var clicks = 0
        compose.setContent {
            var action by remember { mutableStateOf<ProjectWorkPlanNextAction>(ProjectWorkPlanNextAction.Start("step-1")) }
            MaterialTheme {
                Column {
                    ProjectWorkPlanNextStepActionButton(action = action, isBusy = false, onClick = { clicks++ })
                    androidx.compose.material3.TextButton(onClick = {
                        action = ProjectWorkPlanNextAction.ReviewRequired("step-1")
                    }) { androidx.compose.material3.Text("test-review-state") }
                }
            }
        }

        compose.onNodeWithText("다음 단계 실행").assertIsDisplayed().performClick()
        compose.onNodeWithText("test-review-state").performClick()
        compose.onNodeWithText("결과 검토 필요").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun manualActionRequiresASeparateConfirmationBeforeCompletion() {
        var confirmed = 0
        compose.setContent {
            var showConfirmation by remember { mutableStateOf(false) }
            MaterialTheme {
                Column {
                    ProjectWorkPlanNextStepActionButton(
                        action = ProjectWorkPlanNextAction.ManualConfirmationRequired("step-1"),
                        isBusy = false,
                        onClick = { showConfirmation = true },
                    )
                    if (showConfirmation) {
                        ProjectWorkPlanManualConfirmationDialog(
                            stepTitle = "직접 확인",
                            isBusy = false,
                            enabled = true,
                            onDismiss = { showConfirmation = false },
                            onConfirm = { confirmed++; showConfirmation = false },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("직접 수행 단계").performClick()
        compose.onNodeWithText("직접 확인 단계를 직접 수행했나요?").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertEquals(0, confirmed) }

        compose.onNodeWithText("직접 수행 단계").performClick()
        compose.onNodeWithText("직접 수행 완료 표시").performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun removingTaskConfirmationHostReleasesOnlyItsRunningAttempt() =
        assertConfirmationHostRemovalReleases(ProjectWorkPlanStepType.CREATE_TASK)

    @Test fun removingDecisionConfirmationHostReleasesOnlyItsRunningAttempt() =
        assertConfirmationHostRemovalReleases(ProjectWorkPlanStepType.PROPOSE_DECISION)

    @Test fun removingSurfaceDoesNotReleaseAnAttemptWithoutLocalConfirmationOwnership() {
        val attempt = confirmationAttempt(ProjectWorkPlanStepType.RESEARCH)
        val gateway = ConfirmationGateway(attempt)
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { true }
        var ownerVisible by mutableStateOf(true)

        compose.setContent {
            MaterialTheme {
                Column {
                    ProjectWorkPlanPendingConfirmationOwner(attempt = null, coordinator = coordinator)
                    if (ownerVisible) Button(onClick = { ownerVisible = false }) { androidx.compose.material3.Text("close-surface") }
                }
            }
        }

        compose.onNodeWithText("close-surface").performClick()
        compose.runOnIdle {
            assertEquals(null, gateway.cancelledAttemptId)
            assertEquals(ProjectWorkPlanStepState.RUNNING.name, gateway.state)
            assertEquals(attempt.attemptId, gateway.activeAttemptId)
        }
    }

    private fun assertConfirmationHostRemovalReleases(type: ProjectWorkPlanStepType) {
        val attempt = confirmationAttempt(type)
        val gateway = ConfirmationGateway(attempt)
        val coordinator = ProjectWorkPlanNextStepCoordinator(gateway) { false }
        var hostVisible by mutableStateOf(true)

        compose.setContent {
            MaterialTheme {
                Column {
                    if (hostVisible) {
                        ProjectWorkPlanPendingConfirmationOwner(attempt = attempt, coordinator = coordinator)
                    }
                    Button(onClick = { hostVisible = false }) { androidx.compose.material3.Text("remove-confirmation-host") }
                }
            }
        }

        compose.onNodeWithText("remove-confirmation-host").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            gateway.state == ProjectWorkPlanStepState.FAILED.name
        }
        compose.runOnIdle {
            assertEquals(attempt.attemptId, gateway.cancelledAttemptId)
            assertEquals(null, gateway.activeAttemptId)
        }
    }

    private fun confirmationAttempt(type: ProjectWorkPlanStepType) = ProjectWorkPlanAttempt(
        planId = "plan",
        stepId = "step",
        attemptId = "attempt-${type.name}",
        projectId = 10L,
        type = type,
        title = "Step",
        instruction = "Instruction",
        planTitle = "Plan",
        goal = "Goal",
        desiredOutput = "",
        constraintText = "",
    )

    private class ConfirmationGateway(attempt: ProjectWorkPlanAttempt) : ProjectWorkPlanNextStepGateway {
        @Volatile var state = ProjectWorkPlanStepState.RUNNING.name
        @Volatile var activeAttemptId: String? = attempt.attemptId
        @Volatile var cancelledAttemptId: String? = null

        override suspend fun load(planId: String, projectId: Long): ProjectWorkPlanWithSteps? = null
        override suspend fun startStep(planId: String, stepId: String, projectId: Long): ProjectWorkPlanAttempt? = null
        override suspend fun cancelAttempt(attempt: ProjectWorkPlanAttempt): Boolean {
            if (activeAttemptId != attempt.attemptId || state != ProjectWorkPlanStepState.RUNNING.name) return false
            cancelledAttemptId = attempt.attemptId
            activeAttemptId = null
            state = ProjectWorkPlanStepState.FAILED.name
            return true
        }
        override suspend fun completeManualStep(planId: String, stepId: String, projectId: Long): Boolean = false
        override suspend fun completeReviewedStep(planId: String, stepId: String, projectId: Long): Boolean = false
        override suspend fun freezeProjectContext(projectId: Long): ProjectContextSnapshot? = null
    }
}
