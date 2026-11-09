package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-P1 Task A — single-ownership promotion.
 *
 * When a contextual suggestion is promoted to the compact rail, the SAME
 * secondary action must be removed from the overflow sheet for that answer, so
 * the action is never rendered twice. Pure mapping only.
 */
class ContextualAnswerActionPromotionTest {

    @Test
    fun `extract structured promotion owns the same secondary action`() {
        assertEquals(
            AnswerSecondaryAction.EXTRACT_STRUCTURED,
            ContextualAnswerActionSuggestionPolicy.promotedSecondaryAction(
                ContextualAnswerActionSuggestion.EXTRACT_STRUCTURED,
            ),
        )
    }

    @Test
    fun `compare promotion owns the same secondary action`() {
        assertEquals(
            AnswerSecondaryAction.COMPARE_RESPONSES,
            ContextualAnswerActionSuggestionPolicy.promotedSecondaryAction(
                ContextualAnswerActionSuggestion.COMPARE_RESPONSES,
            ),
        )
    }

    @Test
    fun `save to knowledge promotion owns the same secondary action`() {
        assertEquals(
            AnswerSecondaryAction.SAVE_TO_KNOWLEDGE,
            ContextualAnswerActionSuggestionPolicy.promotedSecondaryAction(
                ContextualAnswerActionSuggestion.SAVE_TO_KNOWLEDGE,
            ),
        )
    }

    @Test
    fun `research refresh promotion maps to no secondary action`() {
        // Its own overflow row is suppressed by the caller instead.
        assertNull(
            ContextualAnswerActionSuggestionPolicy.promotedSecondaryAction(
                ContextualAnswerActionSuggestion.RESEARCH_REFRESH,
            ),
        )
    }

    @Test
    fun `promoted action is removed from the displayed overflow set`() {
        val secondary = listOf(
            AnswerSecondaryAction.SAVE_TO_KNOWLEDGE,
            AnswerSecondaryAction.COMPARE_RESPONSES,
            AnswerSecondaryAction.EXTRACT_STRUCTURED,
        )
        val promoted = ContextualAnswerActionSuggestionPolicy.promotedSecondaryAction(
            ContextualAnswerActionSuggestion.COMPARE_RESPONSES,
        )
        val displayed = if (promoted == null) secondary else secondary.filterNot { it == promoted }
        assertFalse(AnswerSecondaryAction.COMPARE_RESPONSES in displayed)
        assertTrue(AnswerSecondaryAction.SAVE_TO_KNOWLEDGE in displayed)
        assertTrue(AnswerSecondaryAction.EXTRACT_STRUCTURED in displayed)
    }

    @Test
    fun `remaining secondary actions remove exactly the promoted action`() {
        val secondary = listOf(
            AnswerSecondaryAction.SAVE_TO_KNOWLEDGE,
            AnswerSecondaryAction.COMPARE_RESPONSES,
            AnswerSecondaryAction.EXTRACT_STRUCTURED,
        )
        val remaining = ContextualAnswerActionSuggestionPolicy.remainingSecondaryActions(
            secondary,
            ContextualAnswerActionSuggestion.COMPARE_RESPONSES,
        )
        assertFalse(AnswerSecondaryAction.COMPARE_RESPONSES in remaining)
        assertTrue(AnswerSecondaryAction.SAVE_TO_KNOWLEDGE in remaining)
        assertTrue(AnswerSecondaryAction.EXTRACT_STRUCTURED in remaining)
    }

    @Test
    fun `remaining secondary actions with no suggestion leave the list unchanged`() {
        val secondary = listOf(
            AnswerSecondaryAction.SAVE_TO_KNOWLEDGE,
            AnswerSecondaryAction.COMPARE_RESPONSES,
        )
        assertEquals(
            secondary,
            ContextualAnswerActionSuggestionPolicy.remainingSecondaryActions(secondary, null),
        )
    }

    @Test
    fun `research refresh suggestion removes nothing from the sheet`() {
        val secondary = listOf(AnswerSecondaryAction.SAVE_TO_KNOWLEDGE)
        assertEquals(
            secondary,
            ContextualAnswerActionSuggestionPolicy.remainingSecondaryActions(
                secondary,
                ContextualAnswerActionSuggestion.RESEARCH_REFRESH,
            ),
        )
    }

    @Test
    fun `research refresh overflow row is owned unless the rail suggestion owns it`() {
        assertFalse(
            ContextualAnswerActionSuggestionPolicy.researchRefreshOwnedByOverflow(
                suggestion = ContextualAnswerActionSuggestion.RESEARCH_REFRESH,
                providerAvailable = true,
            ),
        )
        assertTrue(
            ContextualAnswerActionSuggestionPolicy.researchRefreshOwnedByOverflow(
                suggestion = ContextualAnswerActionSuggestion.COMPARE_RESPONSES,
                providerAvailable = true,
            ),
        )
        assertTrue(
            ContextualAnswerActionSuggestionPolicy.researchRefreshOwnedByOverflow(
                suggestion = null,
                providerAvailable = true,
            ),
        )
        assertFalse(
            ContextualAnswerActionSuggestionPolicy.researchRefreshOwnedByOverflow(
                suggestion = ContextualAnswerActionSuggestion.COMPARE_RESPONSES,
                providerAvailable = false,
            ),
        )
    }

    @Test
    fun `no action is offered by both the promoted suggestion and the sheet`() {
        val secondary = listOf(
            AnswerSecondaryAction.SAVE_TO_KNOWLEDGE,
            AnswerSecondaryAction.COMPARE_RESPONSES,
        )
        AnswerSecondaryAction.entries.forEach { candidate ->
            val suggested = when (candidate) {
                AnswerSecondaryAction.COMPARE_RESPONSES -> ContextualAnswerActionSuggestion.COMPARE_RESPONSES
                AnswerSecondaryAction.SAVE_TO_KNOWLEDGE -> ContextualAnswerActionSuggestion.SAVE_TO_KNOWLEDGE
                AnswerSecondaryAction.EXTRACT_STRUCTURED -> ContextualAnswerActionSuggestion.EXTRACT_STRUCTURED
                else -> null
            }
            if (suggested == null) return@forEach
            val promoted = ContextualAnswerActionSuggestionPolicy.promotedSecondaryAction(suggested)
            val displayed = secondary.filterNot { it == promoted }
            assertFalse(
                "promoted action $candidate must not also be in the sheet",
                candidate in displayed,
            )
        }
    }
}
