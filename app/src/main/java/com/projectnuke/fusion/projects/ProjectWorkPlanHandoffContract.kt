package com.projectnuke.fusion.projects

import com.projectnuke.fusion.chat.ProjectContextSnapshot
import com.projectnuke.fusion.chat.SelectionContextSnapshot
import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceSnapshot
import com.projectnuke.fusion.chat.SourceComparisonRequestPolicy
import com.projectnuke.fusion.chat.SourceComparisonRequestPreparation

/**
 * Typed destinations a prepared Work Plan step hands off into. The Work Plan
 * coordinates existing Fusion feature owners; it never emulates them with
 * prompt text and never executes them itself.
 */
enum class ProjectWorkPlanDestination {
    /** Real Research Mode: existing mode owner, run policy and search controls. */
    RESEARCH_CHAT,

    /** Composer bound to the real frozen Project context owner. */
    PROJECT_CONTEXT_CHAT,

    /** Editable composer/draft output step; never auto-sent, never auto-saved. */
    DRAFT_COMPOSER,

    /** Existing Search/Selection workspace feeding SourceComparisonRequestPolicy. */
    SOURCE_SELECTION_COMPARE,

    /** Existing Search/Selection workspace feeding StructuredExtractionSurface. */
    SOURCE_SELECTION_EXTRACTION,

    /** Existing Search/Selection workspace freezing real Project knowledge. */
    KNOWLEDGE_SELECTION,
}

/**
 * Immutable, fully identified handoff prepared by the Work Plan execution
 * policy. Carries the exact attempt identity (planId + stepId + attemptId +
 * projectId), the frozen step instruction, the typed destination, the frozen
 * composer seed and, when the flow already froze a bounded source selection,
 * that exact [SelectionContextSnapshot]. Never a mutable UI index.
 */
data class ProjectWorkPlanPreparedAction(
    val attempt: ProjectWorkPlanAttempt,
    val destination: ProjectWorkPlanDestination,
    val seedText: String,
    val requestSnapshot: ProjectWorkPlanActionSnapshot,
    val frozenSelection: SelectionContextSnapshot? = null,
    /**
     * Finding C: immutable Project authority frozen at the execution
     * boundary for ASK_WITH_PROJECT_CONTEXT. The send path consumes this
     * snapshot after validating the frozen resources remain accessible;
     * it never reloads a newer snapshot. Null for destinations that do
     * not use Project context.
     */
    val frozenProjectContext: ProjectContextSnapshot? = null,
) {
    val planId: String get() = attempt.planId
    val stepId: String get() = attempt.stepId
    val attemptId: String get() = attempt.attemptId
    val projectId: Long get() = attempt.projectId
    val stepType: ProjectWorkPlanStepType get() = attempt.type
}

sealed interface ProjectWorkPlanHandoffPreparation {
    data class Ready(val action: ProjectWorkPlanPreparedAction) : ProjectWorkPlanHandoffPreparation
    data object UnsupportedStep : ProjectWorkPlanHandoffPreparation
    data object InvalidAttempt : ProjectWorkPlanHandoffPreparation
}

/**
 * Pure execution policy between the plan and the real feature owners. It
 * only prepares typed handoffs: it never sends, never enables web search,
 * never selects sources and never settles an attempt.
 */
object ProjectWorkPlanHandoffPolicy {
    const val MAX_SEED_CHARS = 4_800

    fun prepare(
        attempt: ProjectWorkPlanAttempt,
        projectContext: ProjectContextSnapshot? = null,
    ): ProjectWorkPlanHandoffPreparation {
        if (attempt.planId.isBlank() || attempt.stepId.isBlank() || attempt.attemptId.isBlank() ||
            attempt.projectId <= 0L || attempt.instruction.isBlank()
        ) {
            return ProjectWorkPlanHandoffPreparation.InvalidAttempt
        }
        val instruction = attempt.instruction.trim()
            .take(ProjectWorkPlanProposalPolicy.MAX_STEP_INSTRUCTION_CHARS)
        if (instruction.isBlank()) return ProjectWorkPlanHandoffPreparation.InvalidAttempt
        val destination = when (attempt.type) {
            ProjectWorkPlanStepType.RESEARCH -> ProjectWorkPlanDestination.RESEARCH_CHAT
            ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT -> ProjectWorkPlanDestination.PROJECT_CONTEXT_CHAT
            ProjectWorkPlanStepType.COMPARE_SOURCES -> ProjectWorkPlanDestination.SOURCE_SELECTION_COMPARE
            ProjectWorkPlanStepType.STRUCTURED_EXTRACT -> ProjectWorkPlanDestination.SOURCE_SELECTION_EXTRACTION
            ProjectWorkPlanStepType.REVIEW_KNOWLEDGE -> ProjectWorkPlanDestination.KNOWLEDGE_SELECTION
            ProjectWorkPlanStepType.WRITE_DRAFT -> ProjectWorkPlanDestination.DRAFT_COMPOSER
            ProjectWorkPlanStepType.CREATE_TASK,
            ProjectWorkPlanStepType.PROPOSE_DECISION,
            ProjectWorkPlanStepType.MANUAL,
            -> return ProjectWorkPlanHandoffPreparation.UnsupportedStep
        }
        val requestSnapshot = ProjectWorkPlanActionSnapshot(
            planId = attempt.planId,
            stepId = attempt.stepId,
            attemptId = attempt.attemptId,
            projectId = attempt.projectId,
            stepType = attempt.type,
            instruction = instruction,
            projectSourceIds = frozenProjectSourceIds(attempt, projectContext)
                ?: return ProjectWorkPlanHandoffPreparation.InvalidAttempt,
            interactionMode = if (destination == ProjectWorkPlanDestination.RESEARCH_CHAT) "RESEARCH" else "CHAT",
            workflowPreset = null,
            webSearchEnabled = false,
            egressState = "DEFERRED",
        )
        if (!requestSnapshot.isValid()) return ProjectWorkPlanHandoffPreparation.InvalidAttempt
        return ProjectWorkPlanHandoffPreparation.Ready(
            ProjectWorkPlanPreparedAction(
                attempt = attempt,
                destination = destination,
                seedText = instruction.take(MAX_SEED_CHARS),
                requestSnapshot = requestSnapshot,
                frozenSelection = null,
                frozenProjectContext = projectContext
                    ?.takeIf { destination == ProjectWorkPlanDestination.PROJECT_CONTEXT_CHAT },
            ),
        )
    }

    /**
     * Finding C: the ASK_WITH_PROJECT_CONTEXT snapshot becomes real
     * request authority — the frozen file identities travel in the action
     * snapshot. Other destinations carry no Project authority.
     */
    private fun frozenProjectSourceIds(
        attempt: ProjectWorkPlanAttempt,
        projectContext: ProjectContextSnapshot?,
    ): List<Long>? {
        if (attempt.type != ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT) return emptyList()
        val frozen = projectContext ?: return null
        if (frozen.projectId != attempt.projectId) return null
        val ids = ProjectWorkPlanProjectContextPolicy.frozenFileIds(frozen)
        if (frozen.files.size > ProjectWorkPlanProjectContextPolicy.MAX_FROZEN_FILES) return null
        return ids
    }

    /**
     * Every destination hands the user a prepared, reviewable request. The
     * Work Plan itself never sends, generates or settles anything.
     */
    fun needsUserSend(destination: ProjectWorkPlanDestination): Boolean = true

    /** Exact comparison composer text: the frozen step instruction plus the existing policy text. */
    fun compareSeed(instruction: String): String {
        val frozen = instruction.trim().take(ProjectWorkPlanProposalPolicy.MAX_STEP_INSTRUCTION_CHARS)
        return (frozen + "\n\n" + SourceComparisonRequestPolicy.COMPOSER_TEXT).take(MAX_SEED_CHARS)
    }

    /**
     * Freezes a user-approved comparison selection onto the prepared action.
     * Fails closed on the wrong destination or outside the exact 2..4 bounds;
     * the frozen snapshot can never be widened afterwards.
     */
    fun prepareCompare(
        action: ProjectWorkPlanPreparedAction,
        selection: SelectionContextSnapshot,
    ): ProjectWorkPlanPreparedAction? {
        if (action.destination != ProjectWorkPlanDestination.SOURCE_SELECTION_COMPARE) return null
        if (SourceComparisonRequestPolicy.prepare(selection) !is SourceComparisonRequestPreparation.Ready) return null
        if (action.frozenSelection != null) return null
        return action.copy(
            frozenSelection = selection,
            seedText = compareSeed(action.attempt.instruction),
        )
    }

    /** Freezes the exact knowledge selection for an ask-with-selection handoff. */
    fun prepareKnowledgeQuestion(
        action: ProjectWorkPlanPreparedAction,
        selection: SelectionContextSnapshot,
    ): ProjectWorkPlanPreparedAction? {
        if (action.destination != ProjectWorkPlanDestination.KNOWLEDGE_SELECTION) return null
        if (selection.sources.isEmpty()) return null
        if (action.frozenSelection != null) return null
        return action.copy(
            frozenSelection = selection,
            seedText = action.attempt.instruction
                .trim()
                .take(ProjectWorkPlanProposalPolicy.MAX_STEP_INSTRUCTION_CHARS)
                .take(MAX_SEED_CHARS),
        )
    }

    /** Extraction requires exactly one user-chosen eligible source; never a default. */
    fun prepareExtraction(
        action: ProjectWorkPlanPreparedAction,
        selection: SelectionContextSnapshot,
    ): SelectionSourceSnapshot? {
        if (action.destination != ProjectWorkPlanDestination.SOURCE_SELECTION_EXTRACTION) return null
        if (action.frozenSelection != null) return null
        return selection.sources.singleOrNull()
    }

    /**
     * Finding G: durable identity of an accepted extraction. The full
     * source identity plus the explicit extraction type are hashed — never
     * lossy prefix truncation — so long identities sharing a prefix and
     * multiple extraction types stay distinct. The details row keyed by
     * this id retains the metadata needed to resolve/display the source.
     */
    fun extractionResultId(identity: SelectionSourceIdentity, type: String): String? =
        ProjectWorkPlanResultDetails.extractionResultId(identity, type)

    /**
     * Finding D: durable identity of an accepted multi-source selection.
     * Replaces the process-local snapshot UUID with a hash over the actual
     * selected source identities.
     */
    fun selectionResultId(identities: List<SelectionSourceIdentity>): String? =
        ProjectWorkPlanResultDetails.selectionResultId(identities)
}

/**
 * Typed completion receipt returned by a specialized feature owner to the
 * exact active Work Plan attempt. The repository is the only settlement
 * authority; it validates [planId] + [stepId] + [attemptId] + [projectId] +
 * step type plus the result-specific durable identity before anything moves
 * to REVIEW.
 */
data class ProjectWorkPlanResultReceipt(
    val planId: String,
    val stepId: String,
    val attemptId: String,
    val projectId: Long,
    val stepType: ProjectWorkPlanStepType,
    val resultKind: String,
    val resultId: String,
    val conversationId: Long? = null,
    val userMessageId: Long? = null,
    /**
     * Finding B: exact submission/request ownership. The durable
     * submission witness token for the committed Work Plan user message
     * and the generation request id created for that submission. Legacy
     * callers may omit them; origin proof is still enforced durably.
     */
    val submissionToken: String? = null,
    val requestId: String? = null,
) {
    companion object {
        const val KIND_MESSAGE = "MESSAGE"
        const val KIND_SELECTION = "SELECTION"
        const val KIND_EXTRACTION = "EXTRACTION"
        val KINDS = setOf(KIND_MESSAGE, KIND_SELECTION, KIND_EXTRACTION)
        const val MAX_RESULT_ID_CHARS = 180
    }
}

/** Durable plan row facts the receipt validator needs. */
data class ProjectWorkPlanReceiptPlanState(
    val projectId: Long,
    val status: String,
    val archivedAt: Long?,
)

/** Durable step row facts the receipt validator needs. */
data class ProjectWorkPlanReceiptStepState(
    val planId: String,
    val type: String?,
    val state: String,
    val activeAttemptId: String?,
    val attemptCount: Int,
    val linkedTaskId: Long?,
)

enum class ProjectWorkPlanReceiptRejection {
    MALFORMED,
    WRONG_PLAN,
    WRONG_STEP,
    WRONG_ATTEMPT,
    WRONG_STEP_TYPE,
    WRONG_RESULT_KIND,
    ATTEMPT_NOT_ACTIVE,
    PLAN_NOT_ACTIVE,
    PLAN_ARCHIVED,
    ATTEMPTS_EXHAUSTED,
    MISSING_MESSAGE_AUTHORITY,
}

sealed interface ProjectWorkPlanReceiptVerdict {
    data object Accept : ProjectWorkPlanReceiptVerdict
    data class Reject(val reason: ProjectWorkPlanReceiptRejection) : ProjectWorkPlanReceiptVerdict
}

/**
 * Fail-closed validation authority for specialized result receipts. A stale
 * previous attempt, another plan's result, a cancelled/retried step, a
 * duplicate settlement or a callback after plan archive/cancel can never
 * settle. Pure so every fencing rule is unit-testable without Room.
 */
object ProjectWorkPlanReceiptPolicy {
    /**
     * Finding E: explicit fail-closed result-kind compatibility. Task,
     * Decision, and Manual steps have no generic receipt kind: they keep
     * their dedicated settlement mechanisms and any specialized receipt
     * for them is rejected without mutating the step.
     */
    private val ALLOWED_RESULT_KINDS: Map<ProjectWorkPlanStepType, Set<String>> = mapOf(
        ProjectWorkPlanStepType.RESEARCH to setOf(ProjectWorkPlanResultReceipt.KIND_MESSAGE),
        ProjectWorkPlanStepType.COMPARE_SOURCES to setOf(ProjectWorkPlanResultReceipt.KIND_MESSAGE),
        ProjectWorkPlanStepType.ASK_WITH_PROJECT_CONTEXT to setOf(ProjectWorkPlanResultReceipt.KIND_MESSAGE),
        ProjectWorkPlanStepType.WRITE_DRAFT to setOf(ProjectWorkPlanResultReceipt.KIND_MESSAGE),
        ProjectWorkPlanStepType.STRUCTURED_EXTRACT to setOf(ProjectWorkPlanResultReceipt.KIND_EXTRACTION),
        ProjectWorkPlanStepType.REVIEW_KNOWLEDGE to setOf(
            ProjectWorkPlanResultReceipt.KIND_SELECTION,
            ProjectWorkPlanResultReceipt.KIND_MESSAGE,
        ),
    )

    fun allowedResultKinds(stepType: ProjectWorkPlanStepType): Set<String> =
        ALLOWED_RESULT_KINDS[stepType].orEmpty()

    fun validate(
        receipt: ProjectWorkPlanResultReceipt,
        plan: ProjectWorkPlanReceiptPlanState?,
        step: ProjectWorkPlanReceiptStepState?,
    ): ProjectWorkPlanReceiptVerdict {
        shapeError(receipt)?.let { return ProjectWorkPlanReceiptVerdict.Reject(it) }
        if (plan == null || plan.projectId != receipt.projectId) {
            return ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_PLAN)
        }
        if (plan.archivedAt != null) {
            return ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.PLAN_ARCHIVED)
        }
        if (plan.status != ProjectWorkPlanStatus.ACTIVE.name) {
            return ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.PLAN_NOT_ACTIVE)
        }
        if (step == null || step.planId != receipt.planId) {
            return ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_STEP)
        }
        val stepType = ProjectWorkPlanStepType.fromModelValue(step.type.orEmpty())
        if (stepType == null || stepType != receipt.stepType) {
            return ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_STEP_TYPE)
        }
        if (step.linkedTaskId != null) {
            return ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_STEP)
        }
        if (receipt.resultKind !in allowedResultKinds(receipt.stepType)) {
            return ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_RESULT_KIND)
        }
        if (step.attemptCount >= ProjectWorkPlanRepository.MAX_STEP_ATTEMPTS) {
            return ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.ATTEMPTS_EXHAUSTED)
        }
        if (step.state != ProjectWorkPlanStepState.RUNNING.name) {
            return ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.ATTEMPT_NOT_ACTIVE)
        }
        if (step.activeAttemptId != receipt.attemptId) {
            return ProjectWorkPlanReceiptVerdict.Reject(ProjectWorkPlanReceiptRejection.WRONG_ATTEMPT)
        }
        return ProjectWorkPlanReceiptVerdict.Accept
    }

    private fun shapeError(receipt: ProjectWorkPlanResultReceipt): ProjectWorkPlanReceiptRejection? {
        if (receipt.planId.isBlank() || receipt.stepId.isBlank() || receipt.attemptId.isBlank() ||
            receipt.projectId <= 0L || receipt.stepType !in ProjectWorkPlanStepType.entries
        ) {
            return ProjectWorkPlanReceiptRejection.MALFORMED
        }
        if (receipt.resultKind !in ProjectWorkPlanResultReceipt.KINDS) {
            return ProjectWorkPlanReceiptRejection.MALFORMED
        }
        if (receipt.resultId.isBlank() || receipt.resultId.length > ProjectWorkPlanResultReceipt.MAX_RESULT_ID_CHARS) {
            return ProjectWorkPlanReceiptRejection.MALFORMED
        }
        receipt.submissionToken?.let { token ->
            if (token.isBlank() || token.length > 128) return ProjectWorkPlanReceiptRejection.MALFORMED
        }
        receipt.requestId?.let { requestId ->
            if (requestId.isBlank() || requestId.length > 80) return ProjectWorkPlanReceiptRejection.MALFORMED
        }
        if (receipt.resultKind == ProjectWorkPlanResultReceipt.KIND_MESSAGE) {
            val conversationId = receipt.conversationId
            val userMessageId = receipt.userMessageId
            val assistantMessageId = receipt.resultId.toLongOrNull()
            if (conversationId == null || conversationId <= 0L ||
                userMessageId == null || userMessageId <= 0L ||
                assistantMessageId == null || assistantMessageId <= userMessageId
            ) {
                return ProjectWorkPlanReceiptRejection.MALFORMED
            }
        }
        return null
    }

    fun attemptResultFor(rejection: ProjectWorkPlanReceiptRejection): ProjectWorkPlanAttemptResult =
        when (rejection) {
            ProjectWorkPlanReceiptRejection.MALFORMED,
            ProjectWorkPlanReceiptRejection.WRONG_STEP_TYPE,
            ProjectWorkPlanReceiptRejection.WRONG_RESULT_KIND,
            -> ProjectWorkPlanAttemptResult.INVALID
            ProjectWorkPlanReceiptRejection.ATTEMPTS_EXHAUSTED -> ProjectWorkPlanAttemptResult.ATTEMPT_LIMIT
            // A missing message authority means the durable answer belongs to
            // another conversation/project or no longer exists: stale, never applied.
            else -> ProjectWorkPlanAttemptResult.STALE
        }
}
