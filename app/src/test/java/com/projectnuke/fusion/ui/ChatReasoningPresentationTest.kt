package com.projectnuke.fusion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReasoningPresentationTest {
    @Test fun `local presentation follows profile even when legacy preference disagrees`() {
        assertTrue(presentedChatReasoningEnabled(ChatGenerationMode.LOCAL_MODEL, false, true))
        assertFalse(presentedChatReasoningEnabled(ChatGenerationMode.LOCAL_MODEL, true, false))
    }

    @Test fun `external presentation retains external preference`() {
        assertFalse(presentedChatReasoningEnabled(ChatGenerationMode.EXTERNAL_AI_API, false, true))
        assertTrue(presentedChatReasoningEnabled(ChatGenerationMode.EXTERNAL_AI_API, true, false))
    }
}
