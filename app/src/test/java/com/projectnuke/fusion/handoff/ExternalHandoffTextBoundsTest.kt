package com.projectnuke.fusion.handoff

import com.projectnuke.fusion.inbox.InboxCapturePolicy
import com.projectnuke.fusion.knowledge.SavedKnowledgePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5 regressions: external share exactness (C14). */
class ExternalHandoffTextBoundsTest {
    @Test fun `bounds match the durable store contracts`() {
        assertEquals(12_000, InboxCapturePolicy.MAX_CONTENT_CHARS)
        assertEquals(12_000, SavedKnowledgePolicy.MAX_TEXT_CHARS)
    }

    @Test fun `exact bound payload is storable, one unit above is too large`() {
        val atBound = "x".repeat(12_000)
        val aboveBound = "x".repeat(12_001)
        assertFalse(ExternalHandoffTextBounds.isTooLargeForInbox(atBound))
        assertTrue(ExternalHandoffTextBounds.isTooLargeForInbox(aboveBound))
        assertFalse(ExternalHandoffTextBounds.isTooLargeForKnowledge(atBound))
        assertTrue(ExternalHandoffTextBounds.isTooLargeForKnowledge(aboveBound))
    }

    @Test fun `multibyte text counts in the same characters`() {
        val atBound = "가".repeat(12_000)
        val aboveBound = "가나다".repeat(4_001)
        assertFalse(ExternalHandoffTextBounds.isTooLargeForInbox(atBound))
        assertTrue(ExternalHandoffTextBounds.isTooLargeForInbox(aboveBound))
        assertFalse(ExternalHandoffTextBounds.isTooLargeForKnowledge(atBound))
        assertTrue(ExternalHandoffTextBounds.isTooLargeForKnowledge(aboveBound))
    }

    @Test fun `trailing marker survives only when the full payload fits`() {
        val marker = "END-MARKER"
        val fitting = "x".repeat(12_000 - marker.length) + marker
        val overflowing = "x".repeat(12_000 - marker.length + 1) + marker
        assertFalse(ExternalHandoffTextBounds.isTooLargeForKnowledge(fitting))
        assertTrue(overflowing.endsWith(marker))
        assertTrue(ExternalHandoffTextBounds.isTooLargeForKnowledge(overflowing))
    }
}
