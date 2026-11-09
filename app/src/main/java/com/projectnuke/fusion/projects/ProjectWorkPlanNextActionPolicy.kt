package com.projectnuke.fusion.projects

/** The one action available for the durable current Work Plan step. */
sealed interface ProjectWorkPlanNextAction {
    data class Start(val stepId: String) : ProjectWorkPlanNextAction
    data class Retry(val stepId: String) : ProjectWorkPlanNextAction
    data class ManualConfirmationRequired(val stepId: String) : ProjectWorkPlanNextAction
    data class ReviewRequired(val stepId: String) : ProjectWorkPlanNextAction
    data class AlreadyRunning(val stepId: String) : ProjectWorkPlanNextAction
    data object Paused : ProjectWorkPlanNextAction
    data object Finished : ProjectWorkPlanNextAction
    data object Stale : ProjectWorkPlanNextAction
    data class Blocked(val reason: BlockReason) : ProjectWorkPlanNextAction

    enum class BlockReason { ARCHIVED, NOT_READY, ATTEMPT_LIMIT, LINKED_TASK, INVALID_STATE }
}

/**
 * Pure action policy for the user-triggered single-step entry point.
 * `expectedStepId` is the durable row ID from the visible action; it fences
 * a stale tap if the current position changed after that UI was rendered.
 */
object ProjectWorkPlanNextActionPolicy {
    fun resolve(
        current: ProjectWorkPlanWithSteps?,
        expectedStepId: String,
    ): ProjectWorkPlanNextAction {
        val plan = current?.plan ?: return ProjectWorkPlanNextAction.Stale
        if (plan.id.isBlank() || plan.projectId <= 0L || expectedStepId.isBlank()) {
            return ProjectWorkPlanNextAction.Stale
        }
        if (plan.archivedAt != null) {
            return ProjectWorkPlanNextAction.Blocked(ProjectWorkPlanNextAction.BlockReason.ARCHIVED)
        }
        when (plan.status) {
            ProjectWorkPlanStatus.PAUSED.name -> return ProjectWorkPlanNextAction.Paused
            ProjectWorkPlanStatus.CANCELLED.name,
            ProjectWorkPlanStatus.COMPLETED.name,
            -> return ProjectWorkPlanNextAction.Finished
            ProjectWorkPlanStatus.ACTIVE.name -> Unit
            else -> return ProjectWorkPlanNextAction.Blocked(ProjectWorkPlanNextAction.BlockReason.INVALID_STATE)
        }

        val candidates = current.steps.filter { it.position == plan.currentStepPosition }
        val step = candidates.singleOrNull() ?: return ProjectWorkPlanNextAction.Stale
        if (step.planId != plan.id || step.id != expectedStepId) return ProjectWorkPlanNextAction.Stale
        if (step.activeAttemptId != null || step.state == ProjectWorkPlanStepState.RUNNING.name) {
            return ProjectWorkPlanNextAction.AlreadyRunning(step.id)
        }
        val type = ProjectWorkPlanStepType.fromModelValue(step.type)
            ?: return ProjectWorkPlanNextAction.Blocked(ProjectWorkPlanNextAction.BlockReason.INVALID_STATE)
        if (step.state == ProjectWorkPlanStepState.REVIEW.name) {
            return ProjectWorkPlanNextAction.ReviewRequired(step.id)
        }
        if (step.linkedTaskId != null) {
            return ProjectWorkPlanNextAction.Blocked(ProjectWorkPlanNextAction.BlockReason.LINKED_TASK)
        }
        return when (step.state) {
            ProjectWorkPlanStepState.READY.name -> when {
                type == ProjectWorkPlanStepType.MANUAL ->
                    ProjectWorkPlanNextAction.ManualConfirmationRequired(step.id)
                step.attemptCount >= ProjectWorkPlanRepository.MAX_STEP_ATTEMPTS ->
                    ProjectWorkPlanNextAction.Blocked(ProjectWorkPlanNextAction.BlockReason.ATTEMPT_LIMIT)
                else -> ProjectWorkPlanNextAction.Start(step.id)
            }
            ProjectWorkPlanStepState.FAILED.name -> when {
                type == ProjectWorkPlanStepType.MANUAL ->
                    ProjectWorkPlanNextAction.Blocked(ProjectWorkPlanNextAction.BlockReason.INVALID_STATE)
                step.attemptCount >= ProjectWorkPlanRepository.MAX_STEP_ATTEMPTS ->
                    ProjectWorkPlanNextAction.Blocked(ProjectWorkPlanNextAction.BlockReason.ATTEMPT_LIMIT)
                else -> ProjectWorkPlanNextAction.Retry(step.id)
            }
            else -> ProjectWorkPlanNextAction.Blocked(ProjectWorkPlanNextAction.BlockReason.NOT_READY)
        }
    }
}
