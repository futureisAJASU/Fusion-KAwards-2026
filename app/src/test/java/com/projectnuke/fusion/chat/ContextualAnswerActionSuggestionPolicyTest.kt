package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextualAnswerActionSuggestionPolicyTest {
    @Test fun researchRefreshNeedsExplicitResearchReceiptAndRunnableAction() {
        val selected = ContextualAnswerActionSuggestionPolicy.suggest(
            ContextualAnswerActionContext(
                availableActions = setOf(AnswerSecondaryAction.SAVE_TO_KNOWLEDGE),
                isResearchResult = true,
                researchRefreshAvailable = true,
                answerLooksTabular = false,
                hasAnswerReceipt = true,
            ),
        )
        assertEquals(ContextualAnswerActionSuggestion.RESEARCH_REFRESH, selected)
        assertNull(
            ContextualAnswerActionSuggestionPolicy.suggest(
                ContextualAnswerActionContext(
                    availableActions = emptySet(),
                    isResearchResult = true,
                    researchRefreshAvailable = false,
                    answerLooksTabular = false,
                    hasAnswerReceipt = true,
                ),
            ),
        )
    }

    @Test fun promotesOnlyOneActionAndUsesSpecificSignalsBeforeSave() {
        assertEquals(
            ContextualAnswerActionSuggestion.EXTRACT_STRUCTURED,
            ContextualAnswerActionSuggestionPolicy.suggest(
                ContextualAnswerActionContext(
                    availableActions = setOf(
                        AnswerSecondaryAction.EXTRACT_STRUCTURED,
                        AnswerSecondaryAction.COMPARE_RESPONSES,
                        AnswerSecondaryAction.SAVE_TO_KNOWLEDGE,
                    ),
                    isResearchResult = false,
                    researchRefreshAvailable = true,
                    answerLooksTabular = true,
                    hasAnswerReceipt = true,
                ),
            ),
        )
        assertEquals(
            ContextualAnswerActionSuggestion.COMPARE_RESPONSES,
            ContextualAnswerActionSuggestionPolicy.suggest(
                ContextualAnswerActionContext(
                    availableActions = setOf(AnswerSecondaryAction.COMPARE_RESPONSES, AnswerSecondaryAction.SAVE_TO_KNOWLEDGE),
                    isResearchResult = false,
                    researchRefreshAvailable = false,
                    answerLooksTabular = false,
                    hasAnswerReceipt = true,
                ),
            ),
        )
        assertEquals(
            ContextualAnswerActionSuggestion.SAVE_TO_KNOWLEDGE,
            ContextualAnswerActionSuggestionPolicy.suggest(
                ContextualAnswerActionContext(
                    availableActions = setOf(AnswerSecondaryAction.SAVE_TO_KNOWLEDGE),
                    isResearchResult = false,
                    researchRefreshAvailable = false,
                    answerLooksTabular = false,
                    hasAnswerReceipt = true,
                ),
            ),
        )
    }

    @Test fun doesNotPromoteUnprovenOrUnavailableActions() {
        assertNull(
            ContextualAnswerActionSuggestionPolicy.suggest(
                ContextualAnswerActionContext(
                    availableActions = setOf(AnswerSecondaryAction.EXTRACT_STRUCTURED),
                    isResearchResult = false,
                    researchRefreshAvailable = false,
                    answerLooksTabular = false,
                    hasAnswerReceipt = false,
                ),
            ),
        )
        assertNull(
            ContextualAnswerActionSuggestionPolicy.suggest(
                ContextualAnswerActionContext(
                    availableActions = setOf(AnswerSecondaryAction.SAVE_TO_KNOWLEDGE),
                    isResearchResult = false,
                    researchRefreshAvailable = false,
                    answerLooksTabular = false,
                    hasAnswerReceipt = false,
                ),
            ),
        )
    }
}
