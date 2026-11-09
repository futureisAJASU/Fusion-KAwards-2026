package com.projectnuke.fusion.handoff

import com.projectnuke.fusion.retrieval.LocalRagBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Hybrid presentation policy: short payloads (<= 2048 UTF-8 bytes AND <= 24
// lines) go inline into the fresh composer; everything within the hard
// LocalRagBounds.MAX_DOC_CHARS ceiling uses the managed 공유된 텍스트.txt
// attachment flow; beyond the ceiling is rejected, never truncated.
class ExternalTextHandoffPresentationPolicyTest {

    private fun classify(text: String): ExternalTextHandoffPresentation =
        ExternalTextHandoffPresentationPolicy.classify(text)

    @Test
    fun `a single byte payload is inline`() {
        assertEquals(ExternalTextHandoffPresentation.INLINE_COMPOSER, classify("a"))
    }

    @Test
    fun `ordinary short korean text is inline`() {
        val text = "안녕하세요. 한국어로 된 짧은 공유 텍스트입니다."
        assertTrue(ExternalTextHandoffPresentationPolicy.utf8ByteSize(text) <= 2048)
        assertEquals(ExternalTextHandoffPresentation.INLINE_COMPOSER, classify(text))
    }

    @Test
    fun `exactly 2048 utf8 bytes stays inline within the line limit`() {
        val text = "a".repeat(ExternalTextHandoffPresentationPolicy.MAX_INLINE_UTF8_BYTES)
        assertEquals(2048, ExternalTextHandoffPresentationPolicy.utf8ByteSize(text))
        assertEquals(1, ExternalTextHandoffPresentationPolicy.logicalLineCount(text))
        assertEquals(ExternalTextHandoffPresentation.INLINE_COMPOSER, classify(text))
    }

    @Test
    fun `exactly 2048 utf8 bytes across 24 lines stays inline`() {
        // 23 lines of "a\n" (46 bytes) plus a final 2002-byte line.
        val text = "a\n".repeat(23) + "a".repeat(
            ExternalTextHandoffPresentationPolicy.MAX_INLINE_UTF8_BYTES - 46,
        )
        assertEquals(24, ExternalTextHandoffPresentationPolicy.logicalLineCount(text))
        assertEquals(2048, ExternalTextHandoffPresentationPolicy.utf8ByteSize(text))
        assertEquals(ExternalTextHandoffPresentation.INLINE_COMPOSER, classify(text))
    }

    @Test
    fun `2049 utf8 bytes become a managed attachment`() {
        val text = "a".repeat(ExternalTextHandoffPresentationPolicy.MAX_INLINE_UTF8_BYTES + 1)
        assertEquals(2049, ExternalTextHandoffPresentationPolicy.utf8ByteSize(text))
        assertEquals(ExternalTextHandoffPresentation.MANAGED_TEXT_ATTACHMENT, classify(text))
    }

    @Test
    fun `korean byte count is used rather than character count`() {
        // 700 Korean chars = 2100 UTF-8 bytes (character length is only 700).
        val text = "가".repeat(700)
        assertEquals(700, text.length)
        assertEquals(2100, ExternalTextHandoffPresentationPolicy.utf8ByteSize(text))
        assertEquals(ExternalTextHandoffPresentation.MANAGED_TEXT_ATTACHMENT, classify(text))
        // 680 Korean chars = 2040 bytes, still within the inline limit.
        assertEquals(2040, ExternalTextHandoffPresentationPolicy.utf8ByteSize("가".repeat(680)))
        assertEquals(ExternalTextHandoffPresentation.INLINE_COMPOSER, classify("가".repeat(680)))
    }

    @Test
    fun `exactly 24 lines stays inline when the byte limit passes`() {
        val text = List(24) { "line" }.joinToString("\n")
        assertEquals(24, ExternalTextHandoffPresentationPolicy.logicalLineCount(text))
        assertEquals(ExternalTextHandoffPresentation.INLINE_COMPOSER, classify(text))
    }

    @Test
    fun `25 lines become a managed attachment even with a tiny payload`() {
        val text = List(25) { "x" }.joinToString("\n")
        assertEquals(25, ExternalTextHandoffPresentationPolicy.logicalLineCount(text))
        assertEquals(ExternalTextHandoffPresentation.MANAGED_TEXT_ATTACHMENT, classify(text))
    }

    @Test
    fun `large text within max doc chars becomes a managed attachment`() {
        val text = "x".repeat(30_000)
        assertTrue(text.length <= LocalRagBounds.MAX_DOC_CHARS)
        assertEquals(ExternalTextHandoffPresentation.MANAGED_TEXT_ATTACHMENT, classify(text))
    }

    @Test
    fun `text beyond max doc chars is rejected too large`() {
        val ascii = "x".repeat(LocalRagBounds.MAX_DOC_CHARS + 1)
        assertEquals(ExternalTextHandoffPresentation.REJECT_TOO_LARGE, classify(ascii))
        // Korean chars count individually towards the ceiling; rejection must
        // fire on character length before any UTF-8 byte classification.
        val korean = "가".repeat(LocalRagBounds.MAX_DOC_CHARS + 1)
        assertEquals(ExternalTextHandoffPresentation.REJECT_TOO_LARGE, classify(korean))
    }

    @Test
    fun `payload classification never alters the text`() {
        val text = "공유 원문  \n두 번째 줄\n"
        val before = text
        classify(text)
        ExternalTextHandoffPresentationPolicy.utf8ByteSize(text)
        ExternalTextHandoffPresentationPolicy.logicalLineCount(text)
        assertEquals(before, text)
    }

    @Test
    fun `surrogate pairs count as four utf8 bytes`() {
        assertEquals(4, ExternalTextHandoffPresentationPolicy.utf8ByteSize("😀"))
        assertEquals(11, ExternalTextHandoffPresentationPolicy.utf8ByteSize("😀😀가"))
        // 500 pairs = 2000 bytes -> inline; 513 pairs = 2052 bytes -> attachment.
        assertEquals(
            ExternalTextHandoffPresentation.INLINE_COMPOSER,
            classify("😀".repeat(500)),
        )
        assertEquals(
            ExternalTextHandoffPresentation.MANAGED_TEXT_ATTACHMENT,
            classify("😀".repeat(513)),
        )
    }
}