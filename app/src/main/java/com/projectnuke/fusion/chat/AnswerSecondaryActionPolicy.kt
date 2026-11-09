package com.projectnuke.fusion.chat

import com.projectnuke.fusion.trust.TrustRoutingPresentationKind

/**
 * Next Action Flow v1B — answer secondary actions behind "더 보기".
 *
 * The compact primary rail (v1A) stays small (Progressive Disclosure). When
 * additional truthful contextual capabilities exist, ONE "더 보기" chip opens
 * a secondary action sheet. This PURE policy decides which secondary actions
 * are currently valid from typed/scalar state only — no DB access, no
 * callbacks, no content excerpts inside the policy.
 *
 * The secondary sheet is a SEPARATE product layer from the follow-up question
 * row (Layer 2) and from the primary rail; it never participates in
 * [NextActionSuggestionPolicy] or [DynamicFollowUpPolicy] ranking, and never
 * consumes the follow-up max-3 capacity.
 */
enum class AnswerSecondaryAction {
    ADD_TO_PROJECT,
    CHANGE_PROJECT,
    COMPARE_RESPONSES,
    OPEN_IN_TRANSLATION,
    FIND_RELATED_MATERIALS,
    EXTRACT_STRUCTURED,
    VIEW_ANSWER_CONTEXT,
    SAVE_TO_KNOWLEDGE,
    CREATE_PROJECT_TASK,
    CREATE_REVIEW_CARD,
    SAVE_AS_DECISION,
    SAVE_CHECKPOINT,
}

data class AnswerSecondaryActionContext(
    val persistedConversation: Boolean,
    val isProjectAssigned: Boolean,
    val responseVersionCount: Int,
    val visibleAnswerAvailable: Boolean,
    val translationAvailable: Boolean,
    val receiptAvailable: Boolean,
    val routingKind: TrustRoutingPresentationKind,
    val isStudyMode: Boolean,
    val isRetrySearchRecovery: Boolean,
    val answerStableForExtraction: Boolean = true,
)

object AnswerSecondaryActionPolicy {

    /** Bounded composer seed for "관련 자료 찾기". Composer-only, never auto-send. */
    const val RELATED_MATERIALS_PROMPT =
        "이 답변과 관련된 신뢰할 만한 자료를 찾아 핵심 내용과 출처를 정리해 주세요."

    fun actions(context: AnswerSecondaryActionContext): List<AnswerSecondaryAction> {
        if (!NextActionEligibilityPolicy.isEligible(
                routingKind = context.routingKind,
                isRetrySearchRecovery = context.isRetrySearchRecovery,
                isStudyMode = context.isStudyMode,
            )
        ) {
            return emptyList()
        }
        val result = ArrayList<AnswerSecondaryAction>(9)
        if (context.persistedConversation) {
            result += if (context.isProjectAssigned) {
                AnswerSecondaryAction.CHANGE_PROJECT
            } else {
                AnswerSecondaryAction.ADD_TO_PROJECT
            }
        }
        if (context.responseVersionCount >= 2) result += AnswerSecondaryAction.COMPARE_RESPONSES
        if (context.visibleAnswerAvailable && context.translationAvailable) {
            result += AnswerSecondaryAction.OPEN_IN_TRANSLATION
        }
        if (context.visibleAnswerAvailable) result += AnswerSecondaryAction.FIND_RELATED_MATERIALS
        if (canExtractStructured(context)) result += AnswerSecondaryAction.EXTRACT_STRUCTURED
        if (context.receiptAvailable) result += AnswerSecondaryAction.VIEW_ANSWER_CONTEXT
        if (canSaveKnowledge(context)) result += AnswerSecondaryAction.SAVE_TO_KNOWLEDGE
        if (canCreateProjectTask(context)) result += AnswerSecondaryAction.CREATE_PROJECT_TASK
        if (canSaveAsDecision(context)) result += AnswerSecondaryAction.SAVE_AS_DECISION
        if (canCreateReviewCard(context)) result += AnswerSecondaryAction.CREATE_REVIEW_CARD
        if (canSaveCheckpoint(context)) result += AnswerSecondaryAction.SAVE_CHECKPOINT
        return result
    }

    fun canSaveKnowledge(context: AnswerSecondaryActionContext): Boolean =
        context.persistedConversation && context.visibleAnswerAvailable &&
            NextActionEligibilityPolicy.isEligible(
                routingKind = context.routingKind,
                isRetrySearchRecovery = context.isRetrySearchRecovery,
                isStudyMode = context.isStudyMode,
            )

    fun canCreateProjectTask(context: AnswerSecondaryActionContext): Boolean =
        context.persistedConversation && context.isProjectAssigned && context.visibleAnswerAvailable &&
            NextActionEligibilityPolicy.isEligible(
                routingKind = context.routingKind,
                isRetrySearchRecovery = context.isRetrySearchRecovery,
                isStudyMode = context.isStudyMode,
            )

    fun canSaveAsDecision(context: AnswerSecondaryActionContext): Boolean =
        context.persistedConversation && context.isProjectAssigned && context.visibleAnswerAvailable &&
            context.answerStableForExtraction &&
            NextActionEligibilityPolicy.isEligible(
                routingKind = context.routingKind,
                isRetrySearchRecovery = context.isRetrySearchRecovery,
                isStudyMode = context.isStudyMode,
            )

    fun canCreateReviewCard(context: AnswerSecondaryActionContext): Boolean =
        context.persistedConversation && context.visibleAnswerAvailable &&
            NextActionEligibilityPolicy.isEligible(
                routingKind = context.routingKind,
                isRetrySearchRecovery = context.isRetrySearchRecovery,
                isStudyMode = context.isStudyMode,
            )

    fun canExtractStructured(context: AnswerSecondaryActionContext): Boolean =
        context.persistedConversation && context.visibleAnswerAvailable && context.answerStableForExtraction &&
            NextActionEligibilityPolicy.isEligible(
                routingKind = context.routingKind,
                isRetrySearchRecovery = context.isRetrySearchRecovery,
                isStudyMode = context.isStudyMode,
            )

    fun canSaveCheckpoint(context: AnswerSecondaryActionContext): Boolean =
        context.persistedConversation && context.visibleAnswerAvailable && context.answerStableForExtraction &&
            NextActionEligibilityPolicy.isEligible(
                routingKind = context.routingKind,
                isRetrySearchRecovery = context.isRetrySearchRecovery,
                isStudyMode = context.isStudyMode,
            )

    fun isProjectAction(action: AnswerSecondaryAction): Boolean =
        action == AnswerSecondaryAction.ADD_TO_PROJECT || action == AnswerSecondaryAction.CHANGE_PROJECT

    fun projectActionLabel(context: AnswerSecondaryActionContext): String =
        if (context.isProjectAssigned) "프로젝트 변경" else "프로젝트에 추가"

    fun projectActionSubtitle(context: AnswerSecondaryActionContext): String =
        if (context.isProjectAssigned) "대화를 다른 프로젝트로 옮깁니다." else "대화를 프로젝트에 묶습니다."

    /**
     * Execution guard for actions that target durable/version mutable state:
     * the live snapshot must still be valid at tap time or the action fails
     * closed (no stale captured group, no stale conversation mutation).
     */
    fun canExecuteCompareOnCurrent(responseVersionCount: Int, liveSnapshotAvailable: Boolean): Boolean =
        responseVersionCount >= 2 && liveSnapshotAvailable

    fun canExecuteProjectMutation(conversationIsPersisted: Boolean): Boolean = conversationIsPersisted

    fun relatedMaterialsPrompt(): String = RELATED_MATERIALS_PROMPT

    /**
     * Legacy-menu duplicate suppression: when the v1B contextual answer-action
     * layer owns OPEN_IN_TRANSLATION and/or COMPARE_RESPONSES for a given
     * answer, the SAME affordances must not also appear in the legacy message
     * ⋮ menu for that answer. For answers where the contextual layer is absent
     * (e.g. older / non-leader bubbles), the legacy menu keeps them.
     */
    fun shouldSuppressLegacyDuplicate(
        action: AnswerSecondaryAction,
        contextualActions: List<AnswerSecondaryAction>,
    ): Boolean =
        (action == AnswerSecondaryAction.OPEN_IN_TRANSLATION ||
            action == AnswerSecondaryAction.COMPARE_RESPONSES) &&
            action in contextualActions
}
