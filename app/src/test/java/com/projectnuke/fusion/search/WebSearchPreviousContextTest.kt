package com.projectnuke.fusion.search

import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 9: previous private context must not influence outbound queries,
// while ordinary context-aware rewriting is unchanged.
class WebSearchPreviousContextTest {

    private fun attachedUserContent(token: String): String =
        AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord("secret.pdf", "application/pdf", "/tmp/secret.pdf")),
            "summarize this $token",
        )

    @Test
    fun `attachment bearing previous turn detected`() {
        assertTrue(isAttachmentBearingUserTurn(attachedUserContent("SECRET-TOKEN-1")))
        assertFalse(isAttachmentBearingUserTurn("plain previous question"))
        assertFalse(isAttachmentBearingUserTurn(""))
    }

    @Test
    fun `private previous context yields no previous material`() {
        assertNull(approvedPreviousUserMessage("summarize this", true))
        assertNull(approvedPreviousUserMessage(null, true))
    }

    @Test
    fun `ordinary previous context flows through unchanged`() {
        assertEquals("previous question", approvedPreviousUserMessage("  previous question  ", false))
        assertEquals(null, approvedPreviousUserMessage(null, false))
        assertEquals("", approvedPreviousUserMessage("   ", false))
    }
}
