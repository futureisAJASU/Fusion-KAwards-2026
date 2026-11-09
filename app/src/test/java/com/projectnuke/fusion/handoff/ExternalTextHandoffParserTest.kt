package com.projectnuke.fusion.handoff

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// External intent parsing policy: the pure overload is the tested truth and
// MainActivity's Intent overload is a thin binder over it.
class ExternalTextHandoffParserTest {

    private val ids = listOf("id-1", "id-2", "id-3", "id-4", "id-5")
    private var idIndex = 0

    private fun idFactory() = { ids[idIndex++ % ids.size] }

    /** Plain CharSequence stand-in (the same shape as a String or Spanned
     *  extra): toString() must be the only trusted conversion. */
    private class FakeCharSequence(private val value: String) : CharSequence {
        override val length: Int get() = value.length
        override fun get(index: Int): Char = value[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            value.subSequence(startIndex, endIndex)
        override fun toString(): String = value
    }

    // ---- accepted ---------------------------------------------------------

    @Test
    fun `send with text plain extra is accepted`() {
        val event = ExternalTextHandoffParser.parse(
            action = Intent.ACTION_SEND,
            mimeType = "text/plain",
            shareTextExtra = "공유할 문단 내용",
            processTextExtra = null,
            newEventId = idFactory(),
        )
        assertNotNull(event)
        assertEquals(ExternalTextHandoffSource.SHARE_TEXT, event!!.source)
        assertEquals("공유할 문단 내용", event.text)
        assertEquals("id-1", event.id)
    }

    @Test
    fun `process text with text plain extra is accepted`() {
        val event = ExternalTextHandoffParser.parse(
            action = Intent.ACTION_PROCESS_TEXT,
            mimeType = "text/plain",
            shareTextExtra = null,
            processTextExtra = "선택된 텍스트",
            newEventId = idFactory(),
        )
        assertNotNull(event)
        assertEquals(ExternalTextHandoffSource.PROCESS_TEXT, event!!.source)
        assertEquals("선택된 텍스트", event.text)
    }

    @Test
    fun `mime with charset parameters is still accepted`() {
        val event = ExternalTextHandoffParser.parse(
            action = Intent.ACTION_SEND,
            mimeType = "text/plain; charset=utf-8",
            shareTextExtra = "본문",
            processTextExtra = null,
            newEventId = idFactory(),
        )
        assertNotNull(event)
    }

    // ---- rejected ---------------------------------------------------------

    @Test
    fun `blank text is rejected`() {
        assertNull(
            ExternalTextHandoffParser.parse(
                Intent.ACTION_SEND, "text/plain", "   \n\t ", null, idFactory(),
            )
        )
        assertNull(
            ExternalTextHandoffParser.parse(
                Intent.ACTION_PROCESS_TEXT, "text/plain", null, "", idFactory(),
            )
        )
    }

    @Test
    fun `missing extra is rejected`() {
        assertNull(
            ExternalTextHandoffParser.parse(
                Intent.ACTION_SEND, "text/plain", null, null, idFactory(),
            )
        )
        assertNull(
            ExternalTextHandoffParser.parse(
                Intent.ACTION_PROCESS_TEXT, "text/plain", null, null, idFactory(),
            )
        )
    }

    @Test
    fun `process text does not fall back to the share text extra`() {
        assertNull(
            ExternalTextHandoffParser.parse(
                Intent.ACTION_PROCESS_TEXT, "text/plain", "본문 only", null, idFactory(),
            )
        )
    }

    @Test
    fun `unsupported action is rejected`() {
        assertNull(
            ExternalTextHandoffParser.parse(
                Intent.ACTION_VIEW, "text/plain", "본문", null, idFactory(),
            )
        )
    }

    @Test
    fun `unsupported mime is rejected`() {
        assertNull(
            ExternalTextHandoffParser.parse(Intent.ACTION_SEND, "image/png", "본문", null, idFactory()),
        )
        assertNull(
            ExternalTextHandoffParser.parse(Intent.ACTION_SEND, "application/pdf", "본문", null, idFactory()),
        )
        assertNull(
            ExternalTextHandoffParser.parse(Intent.ACTION_SEND, "text/html", "본문", null, idFactory()),
        )
        assertNull(
            ExternalTextHandoffParser.parse(Intent.ACTION_SEND, null, "본문", null, idFactory()),
        )
        assertNull(
            ExternalTextHandoffParser.parse(Intent.ACTION_PROCESS_TEXT, "image/png", null, "본문", idFactory()),
        )
        assertNull(
            ExternalTextHandoffParser.parse(Intent.ACTION_PROCESS_TEXT, "application/pdf", null, "본문", idFactory()),
        )
    }

    @Test
    fun `send multiple and file sharing are rejected`() {
        assertNull(
            ExternalTextHandoffParser.parse(
                Intent.ACTION_SEND_MULTIPLE, "text/plain", "본문", null, idFactory(),
            )
        )
        assertNull(
            ExternalTextHandoffParser.parse(
                Intent.ACTION_SEND, "application/octet-stream", "본문", null, idFactory(),
            )
        )
    }

    // ---- conversion / content fidelity -----------------------------------

    @Test
    fun `charsequence converts safely to plain string`() {
        val event = ExternalTextHandoffParser.parse(
            Intent.ACTION_SEND,
            "text/plain",
            FakeCharSequence("Chrome 선택 텍스트"),
            null,
            idFactory(),
        )
        assertEquals("Chrome 선택 텍스트", event!!.text)
    }

    @Test
    fun `html-like alternate text is treated as literal user content not rich html`() {
        // The HTML alternate extra is never read: even if the plain extra were
        // HTML markup (some senders pre-escape), it is preserved verbatim and
        // never interpreted or trusted.
        val markup = FakeCharSequence("<b>굵게</b> &amp; <a href=\"x\">링크</a>")
        val event = ExternalTextHandoffParser.parse(
            Intent.ACTION_SEND,
            "text/plain",
            markup,
            null,
            idFactory(),
        )
        assertEquals("<b>굵게</b> &amp; <a href=\"x\">링크</a>", event!!.text)
        assertTrue(event.text.contains("<b>"))
    }

    @Test
    fun `internal whitespace is preserved`() {
        val event = ExternalTextHandoffParser.parse(
            Intent.ACTION_SEND,
            "text/plain",
            "첫째 줄\n\n둘째   줄\t끝",
            null,
            idFactory(),
        )
        assertEquals("첫째 줄\n\n둘째   줄\t끝", event!!.text)
    }

    @Test
    fun `leading and trailing whitespace around real content is preserved`() {
        val event = ExternalTextHandoffParser.parse(
            Intent.ACTION_SEND,
            "text/plain",
            "  본문 내용  ",
            null,
            idFactory(),
        )
        assertEquals("  본문 내용  ", event!!.text)
    }

    // ---- one-shot event identity -----------------------------------------

    @Test
    fun `identical text from two intents produces two distinct event ids`() {
        val first = ExternalTextHandoffParser.parse(
            Intent.ACTION_SEND, "text/plain", "같은 내용", null, idFactory(),
        )
        val second = ExternalTextHandoffParser.parse(
            Intent.ACTION_SEND, "text/plain", "같은 내용", null, idFactory(),
        )
        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first!!.id, second!!.id)
        assertEquals(first.text, second.text)
    }

    @Test
    fun `different sources with identical text remain distinct events`() {
        val share = ExternalTextHandoffParser.parse(
            Intent.ACTION_SEND, "text/plain", "같은 내용", null, idFactory(),
        )
        val process = ExternalTextHandoffParser.parse(
            Intent.ACTION_PROCESS_TEXT, "text/plain", null, "같은 내용", idFactory(),
        )
        assertNotEquals(share!!.id, process!!.id)
        assertNotEquals(share.source, process.source)
    }
}