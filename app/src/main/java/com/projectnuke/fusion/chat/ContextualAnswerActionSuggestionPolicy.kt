package com.projectnuke.fusion.chat

/** One direct action chosen from state and provenance already owned by the answer surface. */
internal enum class ContextualAnswerActionSuggestion(val label: String) {
    RESEARCH_REFRESH("현재 정보로 다시 확인"),
    EXTRACT_STRUCTURED("구조화해서 추출"),
    COMPARE_RESPONSES("답변 비교"),
    SAVE_TO_KNOWLEDGE("저장 지식에 저장"),
}

internal data class ContextualAnswerActionContext(
    val availableActions: Set<AnswerSecondaryAction>,
    val isResearchResult: Boolean,
    val researchRefreshAvailable: Boolean,
    val answerLooksTabular: Boolean,
    val hasAnswerReceipt: Boolean,
)

/**
 * Deterministic, fail-closed recommendation. A single existing action is
 * promoted only when explicit answer state makes it useful; all other actions
 * remain in the existing overflow sheet.
 */
internal object ContextualAnswerActionSuggestionPolicy {
    fun suggest(context: ContextualAnswerActionContext): ContextualAnswerActionSuggestion? {
        if (context.isResearchResult && context.researchRefreshAvailable) {
            return ContextualAnswerActionSuggestion.RESEARCH_REFRESH
        }
        if (context.answerLooksTabular && AnswerSecondaryAction.EXTRACT_STRUCTURED in context.availableActions) {
            return ContextualAnswerActionSuggestion.EXTRACT_STRUCTURED
        }
        if (AnswerSecondaryAction.COMPARE_RESPONSES in context.availableActions) {
            return ContextualAnswerActionSuggestion.COMPARE_RESPONSES
        }
        if (context.hasAnswerReceipt && AnswerSecondaryAction.SAVE_TO_KNOWLEDGE in context.availableActions) {
            return ContextualAnswerActionSuggestion.SAVE_TO_KNOWLEDGE
        }
        return null
    }

    /**
     * Single-ownership promotion. When a suggestion is promoted to the compact
     * rail, the SAME secondary action must be removed from the overflow sheet
     * for that answer so it is never shown twice. `RESEARCH_REFRESH` maps to no
     * secondary action because it has its own overflow row; the caller
     * suppresses that row while the suggestion owns it.
     */
    fun promotedSecondaryAction(
        suggestion: ContextualAnswerActionSuggestion,
    ): AnswerSecondaryAction? = when (suggestion) {
        ContextualAnswerActionSuggestion.EXTRACT_STRUCTURED -> AnswerSecondaryAction.EXTRACT_STRUCTURED
        ContextualAnswerActionSuggestion.COMPARE_RESPONSES -> AnswerSecondaryAction.COMPARE_RESPONSES
        ContextualAnswerActionSuggestion.SAVE_TO_KNOWLEDGE -> AnswerSecondaryAction.SAVE_TO_KNOWLEDGE
        ContextualAnswerActionSuggestion.RESEARCH_REFRESH -> null
    }

    /**
     * Secondary actions that remain in the overflow sheet for this answer once
     * the promoted suggestion has been removed, so the promoted capability is
     * offered by exactly one surface. With no suggestion the full list is
     * returned unchanged (the caller's own eligibility decision still governs).
     */
    fun remainingSecondaryActions(
        actions: List<AnswerSecondaryAction>,
        suggestion: ContextualAnswerActionSuggestion?,
    ): List<AnswerSecondaryAction> {
        val promoted = suggestion?.let { promotedSecondaryAction(it) }
        return actions.filterNot { it == promoted }
    }

    /**
     * The research-refresh overflow row is owned by the promoted rail suggestion
     * when that suggestion IS research refresh; otherwise the overflow provider
     * owns it. Keeps the row single-owner without inline caller branching.
     */
    fun researchRefreshOwnedByOverflow(
        suggestion: ContextualAnswerActionSuggestion?,
        providerAvailable: Boolean,
    ): Boolean = providerAvailable && suggestion != ContextualAnswerActionSuggestion.RESEARCH_REFRESH
}
