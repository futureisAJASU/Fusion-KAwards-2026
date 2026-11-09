package com.projectnuke.fusion.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalHandoffDestinationPolicyTest {
    @Test
    fun `recognizes only a complete http or https link`() {
        assertEquals("https://example.com/path", ExternalHandoffDestinationPolicy.sharedUrl(" https://example.com/path "))
        assertNull(ExternalHandoffDestinationPolicy.sharedUrl("file:///tmp/a"))
        assertNull(ExternalHandoffDestinationPolicy.sharedUrl("http://"))
        assertNull(ExternalHandoffDestinationPolicy.sharedUrl("see https://example.com"))
        assertNull(ExternalHandoffDestinationPolicy.sharedUrl("https://user@example.com"))
    }

    @Test
    fun `project must be explicit and knowledge destination accepts text only`() {
        val text = ExternalTextHandoff("event-1", ExternalTextHandoffSource.SHARE_TEXT, "hello")
        assertTrue(ExternalHandoffDestinationPolicy.accepts(
            ExternalHandoffSelection("event-1", ExternalHandoffDestination.PROJECT, 7), text,
        ))
        assertFalse(ExternalHandoffDestinationPolicy.accepts(
            ExternalHandoffSelection("event-1", ExternalHandoffDestination.PROJECT), text,
        ))
        val document = ExternalDocumentHandoff("event-2", listOf("content://provider/a.txt"), "text/plain", ExternalDocumentHandoffSource.SHARE_STREAM)
        assertFalse(ExternalHandoffDestinationPolicy.accepts(
            ExternalHandoffSelection("event-2", ExternalHandoffDestination.SAVED_KNOWLEDGE), document,
        ))
    }
}
