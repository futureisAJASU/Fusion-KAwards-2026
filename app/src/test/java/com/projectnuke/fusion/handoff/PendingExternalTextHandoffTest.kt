package com.projectnuke.fusion.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// One-shot event slot ownership: an external Intent is consumed exactly once
// across recomposition, configuration changes and surface switches.
class PendingExternalTextHandoffTest {

    private fun event(id: String, text: String = "payload") =
        ExternalTextHandoff(id = id, source = ExternalTextHandoffSource.SHARE_TEXT, text = text)

    @Test
    fun `single pending event is consumed exactly once`() {
        val slot = PendingExternalTextHandoff()
        val first = event("evt-1")

        slot.submit(first)

        assertEquals(first, slot.pending)
        assertEquals(first, slot.consume())
        assertNull(slot.pending)
        assertNull(slot.consume())
    }

    @Test
    fun `identical text from two intentional shares stays two distinct events and newest wins`() {
        val slot = PendingExternalTextHandoff()
        val first = event("evt-1", "같은 내용")
        val second = event("evt-2", "같은 내용")

        slot.submit(first)
        slot.submit(second)

        assertEquals(second, slot.pending)
        assertEquals(second, slot.consume())
        assertNull(slot.consume())
    }

    @Test
    fun `consume on an empty slot returns null`() {
        val slot = PendingExternalTextHandoff()
        assertNull(slot.pending)
        assertNull(slot.consume())
    }

    @Test
    fun `process text and share text events keep distinct sources while pending`() {
        val slot = PendingExternalTextHandoff()
        val share = event("share-1")
        val process = ExternalTextHandoff(
            id = "process-1",
            source = ExternalTextHandoffSource.PROCESS_TEXT,
            text = "선택 텍스트",
        )

        slot.submit(share)
        slot.submit(process)

        assertEquals(ExternalTextHandoffSource.PROCESS_TEXT, slot.pending?.source)
        assertEquals(process, slot.consume())
        assertNull(slot.consume())
    }
}