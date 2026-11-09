package com.projectnuke.fusion.chat

import org.junit.Assert.*
import org.junit.Test

class AutoRoutingDisplayStateTest {
    @Test fun completedRequestKeepsItsChosenIdentity() {
        val state = ConversationGenerationState(isGenerating = true, activeRequestId = "frozen",
            autoSelectionExplanation = "자동 선택 · Kanana 1.3B")
        val completed = state.finished()
        assertFalse(completed.isGenerating)
        assertNull(completed.activeRequestId)
        assertEquals(state.autoSelectionExplanation, completed.autoSelectionExplanation)
    }
    @Test fun separateConversationsHaveSeparateDisplayIdentity() {
        val states = mapOf(1L to ConversationGenerationState(autoSelectionExplanation = "model A"),
            2L to ConversationGenerationState(autoSelectionExplanation = "model B"))
        assertEquals("model A", states.getValue(1).finished().autoSelectionExplanation)
        assertEquals("model B", states.getValue(2).autoSelectionExplanation)
        assertNull(ConversationGenerationState().autoSelectionExplanation)
    }
}
