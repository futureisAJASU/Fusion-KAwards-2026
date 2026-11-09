package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ProjectContextSnapshot
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.projects.ProjectWorkPlanAttempt
import com.projectnuke.fusion.projects.ProjectWorkPlanNextAction
import com.projectnuke.fusion.projects.ProjectWorkPlanNextActionPolicy
import com.projectnuke.fusion.projects.ProjectWorkPlanPreparedAction
import com.projectnuke.fusion.projects.ProjectWorkPlanHandoffPolicy
import com.projectnuke.fusion.projects.ProjectWorkPlanHandoffPreparation
import com.projectnuke.fusion.projects.ProjectWorkPlanRepository
import com.projectnuke.fusion.projects.ProjectWorkPlanWithSteps
import com.projectnuke.fusion.projects.ProjectWorkPlanStepState
import com.projectnuke.fusion.projects.ProjectWorkPlanStepType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Repository and Project-context seam used by the non-visual one-step owner. */
internal interface ProjectWorkPlanNextStepGateway {
    suspend fun load(planId: String, projectId: Long): ProjectWorkPlanWithSteps?
    suspend fun startStep(planId: String, stepId: String, projectId: Long): ProjectWorkPlanAttempt?
    suspend fun cancelAttempt(attempt: ProjectWorkPlanAttempt): Boolean
    suspend fun completeManualStep(planId: String, stepId: String, projectId: Long): Boolean
    suspend fun completeReviewedStep(planId: String, stepId: String, projectId: Long): Boolean
    suspend fun freezeProjectContext(projectId: Long): ProjectContextSnapshot?
}

/** Production adapter; normal reads remain observational and never recover RUNNING attempts. */
internal class ProjectWorkPlanRepositoryNextStepGateway(
    private val repository: ProjectWorkPlanRepository,
    private val database: AppDatabase,
) : ProjectWorkPlanNextStepGateway {
    override suspend fun load(planId: String, projectId: Long) =
        repository.load(planId, projectId, recover = false)

    override suspend fun startStep(planId: String, stepId: String, projectId: Long) =
        repository.startStep(planId, stepId, projectId)

    override suspend fun cancelAttempt(attempt: ProjectWorkPlanAttempt) = repository.cancelAttempt(attempt)

    override suspend fun completeManualStep(planId: String, stepId: String, projectId: Long) =
        repository.completeManualStep(planId, stepId, projectId)

    override suspend fun completeReviewedStep(planId: String, stepId: String, projectId: Long) =
        repository.completeReviewedStep(planId, stepId, projectId)

    override suspend fun freezeProjectContext(projectId: Long): ProjectContextSnapshot? = withContext(Dispatchers.IO) {
        val project = database.projectDao().getProjectById(projectId) ?: return@withContext null
        val files = database.projectDao().getProjectFiles(projectId)
        ProjectContextSnapshot.from(project, files)
    }
}

/**
 * Owns one durable attempt until its typed destination or confirmation UI
 * accepts it. It never settles a result or invokes a second plan step.
 */
internal class ProjectWorkPlanNextStepCoordinator(
    private val gateway: ProjectWorkPlanNextStepGateway,
    private val dispatch: suspend (ProjectWorkPlanPreparedAction) -> Boolean,
) {
    sealed interface Outcome {
        data class Dispatched(val action: ProjectWorkPlanPreparedAction) : Outcome
        data class NeedsTaskConfirmation(val attempt: ProjectWorkPlanAttempt) : Outcome
        data class NeedsDecisionConfirmation(val attempt: ProjectWorkPlanAttempt) : Outcome
        data class ManualConfirmationRequired(val stepId: String) : Outcome
        data class ReviewRequired(val stepId: String) : Outcome
        data class AlreadyRunning(val stepId: String) : Outcome
        data object Paused : Outcome
        data object Finished : Outcome
        data object Stale : Outcome
        data class Blocked(val reason: ProjectWorkPlanNextAction.BlockReason) : Outcome
        data class Rejected(val message: String) : Outcome
    }

    private data class AttemptKey(val projectId: Long, val planId: String, val stepId: String)
    private val attemptFlights = ConcurrentHashMap<AttemptKey, Mutex>()

    /** Primary CTA. The rendered step ID is revalidated before acquisition. */
    suspend fun runNextStep(
        projectId: Long,
        planId: String,
        expectedStepId: String,
    ): Outcome {
        val initial = load(planId, projectId) ?: return Outcome.Stale
        val decision = ProjectWorkPlanNextActionPolicy.resolve(initial, expectedStepId)
        if (decision !is ProjectWorkPlanNextAction.Start && decision !is ProjectWorkPlanNextAction.Retry) {
            return decision.toOutcome()
        }
        return executeExpectedAction(projectId, planId, expectedStepId, decision)
    }

    /** Explicit secondary retry for a result already in REVIEW. */
    suspend fun retryReviewedStep(
        projectId: Long,
        planId: String,
        expectedStepId: String,
    ): Outcome {
        val initial = load(planId, projectId) ?: return Outcome.Stale
        val action = ProjectWorkPlanNextActionPolicy.resolve(initial, expectedStepId)
        if (action !is ProjectWorkPlanNextAction.ReviewRequired) return action.toOutcome()
        val step = initial.steps.singleOrNull { it.id == expectedStepId } ?: return Outcome.Stale
        if (step.type == ProjectWorkPlanStepType.MANUAL.name || step.linkedTaskId != null) {
            return Outcome.Blocked(ProjectWorkPlanNextAction.BlockReason.LINKED_TASK)
        }
        if (step.attemptCount >= ProjectWorkPlanRepository.MAX_STEP_ATTEMPTS) {
            return Outcome.Blocked(ProjectWorkPlanNextAction.BlockReason.ATTEMPT_LIMIT)
        }
        return executeExpectedAction(projectId, planId, expectedStepId, action)
    }

    /** Existing explicit overflow action: prepare a Task confirmation for the exact current step. */
    suspend fun prepareTaskConversion(
        projectId: Long,
        planId: String,
        expectedStepId: String,
    ): Outcome {
        val initial = load(planId, projectId) ?: return Outcome.Stale
        val step = initial.steps.singleOrNull { it.id == expectedStepId } ?: return Outcome.Stale
        if (initial.plan.status != com.projectnuke.fusion.projects.ProjectWorkPlanStatus.ACTIVE.name ||
            initial.plan.archivedAt != null || step.position != initial.plan.currentStepPosition ||
            step.state !in setOf(
                ProjectWorkPlanStepState.READY.name,
                ProjectWorkPlanStepState.REVIEW.name,
                ProjectWorkPlanStepState.FAILED.name,
            )
        ) return ProjectWorkPlanNextActionPolicy.resolve(initial, expectedStepId).toOutcome()
        if (step.linkedTaskId != null) return Outcome.Blocked(ProjectWorkPlanNextAction.BlockReason.LINKED_TASK)
        if (step.activeAttemptId != null) return Outcome.AlreadyRunning(step.id)
        if (step.attemptCount >= ProjectWorkPlanRepository.MAX_STEP_ATTEMPTS) {
            return Outcome.Blocked(ProjectWorkPlanNextAction.BlockReason.ATTEMPT_LIMIT)
        }
        return acquireWithFlight(projectId, planId, expectedStepId) {
            val latest = load(planId, projectId) ?: return@acquireWithFlight Outcome.Stale
            val latestStep = latest.steps.singleOrNull { it.id == expectedStepId } ?: return@acquireWithFlight Outcome.Stale
            if (latest.plan.status != com.projectnuke.fusion.projects.ProjectWorkPlanStatus.ACTIVE.name ||
                latest.plan.archivedAt != null || latestStep.position != latest.plan.currentStepPosition ||
                latestStep.state != step.state || latestStep.linkedTaskId != null || latestStep.activeAttemptId != null
            ) return@acquireWithFlight ProjectWorkPlanNextActionPolicy.resolve(latest, expectedStepId).toOutcome()
            val attempt = gateway.startStep(planId, expectedStepId, projectId)
                ?: return@acquireWithFlight currentOutcome(planId, projectId, expectedStepId)
            handoffAttempt(attempt, taskConversion = true)
        }
    }

    /** Called only after the user confirms the manual-completion dialog. */
    suspend fun completeManualStep(
        projectId: Long,
        planId: String,
        expectedStepId: String,
    ): Boolean {
        val current = load(planId, projectId) ?: return false
        if (ProjectWorkPlanNextActionPolicy.resolve(current, expectedStepId) !is
            ProjectWorkPlanNextAction.ManualConfirmationRequired
        ) return false
        return gateway.completeManualStep(planId, expectedStepId, projectId)
    }

    /** Explicit REVIEW acceptance settles the current step and never starts its successor. */
    suspend fun completeReviewedStep(
        projectId: Long,
        planId: String,
        expectedStepId: String,
    ): Boolean {
        val current = load(planId, projectId) ?: return false
        if (ProjectWorkPlanNextActionPolicy.resolve(current, expectedStepId) !is
            ProjectWorkPlanNextAction.ReviewRequired
        ) return false
        if (current.steps.singleOrNull { it.id == expectedStepId }?.linkedTaskId != null) return false
        return gateway.completeReviewedStep(planId, expectedStepId, projectId)
    }

    /** Releases only this exact attempt, even when the calling UI owner is being cancelled. */
    suspend fun cancelAttempt(attempt: ProjectWorkPlanAttempt): Boolean = try {
        cancelExactAttemptNonCancellably(attempt)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun executeExpectedAction(
        projectId: Long,
        planId: String,
        stepId: String,
        expectedAction: ProjectWorkPlanNextAction,
    ): Outcome = acquireWithFlight(projectId, planId, stepId) {
        val latest = load(planId, projectId) ?: return@acquireWithFlight Outcome.Stale
        val latestAction = ProjectWorkPlanNextActionPolicy.resolve(latest, stepId)
        if (latestAction != expectedAction) return@acquireWithFlight latestAction.toOutcome()
        val attempt = gateway.startStep(planId, stepId, projectId)
            ?: return@acquireWithFlight currentOutcome(planId, projectId, stepId)
        handoffAttempt(attempt)
    }

    private suspend fun acquireWithFlight(
        projectId: Long,
        planId: String,
        stepId: String,
        operation: suspend () -> Outcome,
    ): Outcome {
        val key = AttemptKey(projectId, planId, stepId)
        val mutex = attemptFlights.computeIfAbsent(key) { Mutex() }
        if (!mutex.tryLock()) return Outcome.AlreadyRunning(stepId)
        return try {
            operation()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun handoffAttempt(
        attempt: ProjectWorkPlanAttempt,
        taskConversion: Boolean = false,
    ): Outcome {
        var specializedOwnerAccepted = false
        try {
            if (taskConversion || attempt.type == ProjectWorkPlanStepType.CREATE_TASK) {
                // The result returns to a local confirmation owner. Its composition
                // lifetime is fenced separately and releases this attempt on disposal.
                return Outcome.NeedsTaskConfirmation(attempt)
            }
            if (attempt.type == ProjectWorkPlanStepType.PROPOSE_DECISION) {
                return Outcome.NeedsDecisionConfirmation(attempt)
            }
            if (attempt.type == ProjectWorkPlanStepType.MANUAL) {
                releaseBeforeTransfer(attempt)
                return Outcome.Rejected("직접 수행 단계는 별도 완료 확인이 필요합니다.")
            }

            // ASK_WITH_PROJECT_CONTEXT freezes its exact Project context once
            // at the attempt boundary. No later send path reloads this data.
            val projectContext = if (attempt.type == ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT) {
                gateway.freezeProjectContext(attempt.projectId)
            } else {
                null
            }
            val preparation = ProjectWorkPlanHandoffPolicy.prepare(attempt, projectContext)
            val action = (preparation as? ProjectWorkPlanHandoffPreparation.Ready)?.action
                ?: run {
                    releaseBeforeTransfer(attempt)
                    return Outcome.Rejected("이 단계 요청을 준비하지 못했습니다. 계획을 다시 확인해 주세요.")
                }
            if (!dispatch(action)) {
                releaseBeforeTransfer(attempt)
                return Outcome.Rejected("단계를 준비하지 못했습니다. 이 단계는 다시 시작할 수 있습니다.")
            }
            // From this exact accepted return onward, the specialized owner
            // controls the attempt lifecycle. Surface disposal cannot release it.
            specializedOwnerAccepted = true
            return Outcome.Dispatched(action)
        } catch (cancelled: CancellationException) {
            if (!specializedOwnerAccepted) releaseOnCancellation(attempt)
            throw cancelled
        } catch (_: Exception) {
            if (!specializedOwnerAccepted) releaseBeforeTransfer(attempt)
            return Outcome.Rejected("단계 요청을 저장하지 못했습니다. 완료로 표시하지 않았습니다.")
        }
    }

    private suspend fun currentOutcome(planId: String, projectId: Long, stepId: String): Outcome {
        val current = load(planId, projectId) ?: return Outcome.Stale
        return ProjectWorkPlanNextActionPolicy.resolve(current, stepId).toOutcome()
    }

    private suspend fun load(planId: String, projectId: Long): ProjectWorkPlanWithSteps? = try {
        gateway.load(planId, projectId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun releaseBeforeTransfer(attempt: ProjectWorkPlanAttempt) {
        cancelAttempt(attempt)
    }

    private suspend fun releaseOnCancellation(attempt: ProjectWorkPlanAttempt) {
        try {
            cancelAttempt(attempt)
        } catch (_: CancellationException) {
            // Keep the original cancellation from the pre-transfer operation.
        } catch (_: Exception) {
            // Keep the original cancellation. Recovery remains explicit.
        }
    }

    private suspend fun cancelExactAttemptNonCancellably(attempt: ProjectWorkPlanAttempt): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            gateway.cancelAttempt(attempt)
        }

    private fun ProjectWorkPlanNextAction.toOutcome(): Outcome = when (this) {
        is ProjectWorkPlanNextAction.Start,
        is ProjectWorkPlanNextAction.Retry,
        -> Outcome.Stale
        is ProjectWorkPlanNextAction.ManualConfirmationRequired -> Outcome.ManualConfirmationRequired(stepId)
        is ProjectWorkPlanNextAction.ReviewRequired -> Outcome.ReviewRequired(stepId)
        is ProjectWorkPlanNextAction.AlreadyRunning -> Outcome.AlreadyRunning(stepId)
        ProjectWorkPlanNextAction.Paused -> Outcome.Paused
        ProjectWorkPlanNextAction.Finished -> Outcome.Finished
        ProjectWorkPlanNextAction.Stale -> Outcome.Stale
        is ProjectWorkPlanNextAction.Blocked -> Outcome.Blocked(reason)
    }
}
