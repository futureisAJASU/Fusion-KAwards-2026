package com.projectnuke.fusion.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalTextHandoffSavedStateTest {
    @Test
    fun `legitimate text equal to legacy sentinel survives new typed state`() {
        val userText = ExternalTextHandoffSavedState.LEGACY_OVERFLOW_SENTINEL
        val encoded = ExternalTextHandoffSavedState.encode(userText)

        assertTrue(!encoded.overflow)
        assertEquals(
            userText,
            ExternalTextHandoffSavedState.restore(encoded.text, overflowMetadataPresent = true, overflow = encoded.overflow),
        )
    }

    @Test
    fun `oversized text has separate overflow metadata and is not truncated`() {
        val original = "x".repeat(ExternalTextHandoffSavedState.MAX_TEXT_CHARS + 1)
        val encoded = ExternalTextHandoffSavedState.encode(original)

        assertTrue(encoded.overflow)
        assertNull(encoded.text)
        assertNull(
            ExternalTextHandoffSavedState.restore(encoded.text, overflowMetadataPresent = true, overflow = true),
        )
    }

    @Test
    fun `legacy overflow sentinel remains recognized only without typed metadata`() {
        assertNull(
            ExternalTextHandoffSavedState.restore(
                ExternalTextHandoffSavedState.LEGACY_OVERFLOW_SENTINEL,
                overflowMetadataPresent = false,
                overflow = false,
            ),
        )
    }
}
