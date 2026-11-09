package com.projectnuke.fusion.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingExternalHandoffTest {

    @Test
    fun `single event consumed exactly once across kinds`() {
        val slot = PendingExternalHandoff()
        val text = ExternalTextHandoff(id = "t-1", source = ExternalTextHandoffSource.SHARE_TEXT, text = "hi")
        slot.submit(text)
        assertEquals(text, slot.pending)
        assertEquals(text, slot.consume())
        assertNull(slot.pending)
        assertNull(slot.consume())
    }

    @Test
    fun `identical uri list with different ids treated as separate events newest wins`() {
        val slot = PendingExternalHandoff()
        val first = ExternalDocumentHandoff(
            id = "d-1",
            uriStrings = listOf("content://x/same.txt"),
            declaredMimeType = "text/plain",
            source = ExternalDocumentHandoffSource.SHARE_STREAM,
        )
        val second = first.copy(id = "d-2")
        slot.submit(first)
        slot.submit(second)
        assertEquals(second, slot.pending)
        assertEquals(second, slot.consume())
        assertNull(slot.consume())
    }

    @Test
    fun `document supersedes unconsumed text newest wins`() {
        val slot = PendingExternalHandoff()
        slot.submit(ExternalTextHandoff(id = "t-1", source = ExternalTextHandoffSource.SHARE_TEXT, text = "a"))
        val doc = ExternalDocumentHandoff(
            id = "d-1",
            uriStrings = listOf("content://x/a.txt"),
            declaredMimeType = "text/plain",
            source = ExternalDocumentHandoffSource.SHARE_STREAM,
        )
        slot.submit(doc)
        assertEquals(doc, slot.pending)
        assertNotNull(slot.consume())
        assertTrue(slot.pending == null)
    }
}
