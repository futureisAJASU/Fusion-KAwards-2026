package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCheckpointPolicyTest {
    @Test
    fun `checkpoint text trims and enforces code point bounds`() {
        assertEquals("anchor", boundedCheckpointText("  anchor  ", 20).value)
        assertTrue(boundedCheckpointText("a".repeat(80), CONVERSATION_CHECKPOINT_MAX_TITLE_CODE_POINTS).valid)
        assertFalse(boundedCheckpointText("a".repeat(81), CONVERSATION_CHECKPOINT_MAX_TITLE_CODE_POINTS).valid)
        assertTrue(boundedCheckpointText("😀".repeat(80), CONVERSATION_CHECKPOINT_MAX_TITLE_CODE_POINTS).valid)
        assertFalse(boundedCheckpointText("  ", 80).valid)
    }

    @Test
    fun `default title uses first line and remains bounded`() {
        assertEquals("HBM thermal discussion", defaultCheckpointTitle("HBM thermal discussion\nsecond line"))
        assertTrue(defaultCheckpointTitle("x".repeat(200)).codePointCount(0, defaultCheckpointTitle("x".repeat(200)).length) <= 80)
    }
}
