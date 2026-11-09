package com.projectnuke.fusion.chat

import com.projectnuke.fusion.trust.TrustRoutingPresentationKind

/**
 * Next Action Flow v1A — answer utility rail.
 *
 * A compact post-answer action rail that connects EXISTING systems into one
 * coherent flow:
 *  - 사용한 자료 → reveals/toggles the already-present web/local source
 *    presentation for this active answer/version (zero network, zero
 *    retrieval, zero parsing, zero generation);
 *  - 근거 확인 → opens the SAME single durable Evidence ownership path used by
 *    the existing Evidence action;
 *  - 이 주제로 공부 → enters the existing Study interaction mode only (no
 *    composer append, no auto-send, no generation).
 *
 * This policy is PURE: it only decides WHICH direct actions are eligible for a
 * given visible answer. It never composes with the semantic follow-up row
 * ([DynamicFollowUpPolicy.composeRow] / [NextActionSuggestionPolicy]) and
 * therefore never consumes the max-3 question capacity — direct utility
 * actions and follow-up questions are two distinct layers by construction.
 *
 * Raw ids are the only identity used: the caller binds these actions to the
 * exact active assistant message/response-version, never to list position.
 */
enum class AnswerUtilityAction {
    SOURCES,
    EVIDENCE,
    STUDY,
}

/**
 * Presentation tier for one eligible answer action. Exactly one tier owns an
 * action for a given answer, so the SAME action can never be rendered by two
 * surfaces at once.
 *
 *  - PRIMARY stays on the always-visible compact rail: the frequent,
 *    answer-scoped controls (existing-source access and the Evidence action
 *    only when it is genuinely available).
 *  - OVERFLOW is owned by the single message overflow (⋮) alongside the other
 *    less frequent utilities (translation, comparison, sharing, project
 *    operations). The Study entry point lives here by design.
 */
enum class AnswerActionTier {
    PRIMARY,
    OVERFLOW,
}

data class AnswerUtilityRailContext(
    val routingKind: TrustRoutingPresentationKind,
    val isRetrySearchRecovery: Boolean,
    val isStudyMode: Boolean,
    val hasWebSources: Boolean,
    val hasLocalRagSources: Boolean,
    val evidenceAvailable: Boolean,
)

object AnswerUtilityRailPolicy {

    /** True when at least one direct utility action is eligible. */
    fun isEligible(context: AnswerUtilityRailContext): Boolean = actions(context).isNotEmpty()

    /**
     * Ordered direct actions for the active visible answer, in presentation
     * order. The whole rail is suppressed for trust abstentions, failed-search
     * recovery and STUDY mode (typed eligibility, never string matching) —
     * exactly the same gate the follow-up row uses, so the two layers can
     * never diverge on mode policy.
     */
    fun actions(context: AnswerUtilityRailContext): List<AnswerUtilityAction> {
        if (!NextActionEligibilityPolicy.isEligible(
                routingKind = context.routingKind,
                isRetrySearchRecovery = context.isRetrySearchRecovery,
                isStudyMode = context.isStudyMode,
            )
        ) {
            return emptyList()
        }
        val result = ArrayList<AnswerUtilityAction>(3)
        if (context.hasWebSources || context.hasLocalRagSources) result += AnswerUtilityAction.SOURCES
        if (context.evidenceAvailable) result += AnswerUtilityAction.EVIDENCE
        if (!context.isStudyMode) result += AnswerUtilityAction.STUDY
        return result
    }

    /**
     * Single-ownership presentation tier. Total and disjoint: every eligible
     * action belongs to exactly one tier, so the rail (PRIMARY) and the message
     * overflow (OVERFLOW) never advertise the same action twice.
     */
    fun tier(action: AnswerUtilityAction): AnswerActionTier = when (action) {
        AnswerUtilityAction.SOURCES,
        AnswerUtilityAction.EVIDENCE,
        -> AnswerActionTier.PRIMARY
        AnswerUtilityAction.STUDY -> AnswerActionTier.OVERFLOW
    }

    /** PRIMARY-tier actions for the compact rail of the given answer. */
    fun primaryActions(context: AnswerUtilityRailContext): List<AnswerUtilityAction> =
        actions(context).filter { tier(it) == AnswerActionTier.PRIMARY }

    /** OVERFLOW-tier actions owned by the single message overflow (⋮). */
    fun overflowActions(context: AnswerUtilityRailContext): List<AnswerUtilityAction> =
        actions(context).filter { tier(it) == AnswerActionTier.OVERFLOW }

    /**
     * PRIMARY-tier projection of an ALREADY-eligible action list. The caller
     * that gates eligibility itself (e.g. on `showNextActions`) uses this so the
     * compact rail never re-implements the partition with inline branching.
     */
    fun primaryActionsOf(actions: List<AnswerUtilityAction>): List<AnswerUtilityAction> =
        actions.filter { tier(it) == AnswerActionTier.PRIMARY }

    /** OVERFLOW-tier projection of an already-eligible action list. */
    fun overflowActionsOf(actions: List<AnswerUtilityAction>): List<AnswerUtilityAction> =
        actions.filter { tier(it) == AnswerActionTier.OVERFLOW }

    fun isOverflow(action: AnswerUtilityAction): Boolean =
        tier(action) == AnswerActionTier.OVERFLOW

    /** Pure presentation mapping: the action only reveals existing source presentation. */
    fun isSourcePresentationToggle(action: AnswerUtilityAction): Boolean =
        action == AnswerUtilityAction.SOURCES
}