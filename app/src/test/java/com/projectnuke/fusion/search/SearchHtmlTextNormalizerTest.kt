package com.projectnuke.fusion.search

import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHtmlTextNormalizerTest {
    @Test
    fun `ordinary snippets preserve visible text entities and supplementary characters`() {
        assertEquals("nested title & text", normalizeSearchHtmlText("<b>nested <i>title</i></b> &amp; text"))
        assertEquals("A B C D", normalizeSearchHtmlText("A&#32;B&#x20;C&nbsp;D"))
        assertEquals("한글 😀 𐐷", normalizeSearchHtmlText("한글 😀 𐐷"))
        assertEquals("2 < 3 and x < y > z", normalizeSearchHtmlText("2 < 3 and x < y > z"))
        assertEquals("< &lt; &unknown;", normalizeSearchHtmlText("&lt; &amp;lt; &unknown;"))
        assertEquals("", normalizeSearchHtmlText(""))
    }

    @Test
    fun `malformed entities and bounded tag probes preserve literal content`() {
        assertEquals("&#x110000; &#999999999999999999999;", normalizeSearchHtmlText("&#x110000; &#999999999999999999999;"))
        assertEquals("prefix & suffix", normalizeSearchHtmlText("prefix & suffix"))
        assertEquals("<a<a<a<a", normalizeSearchHtmlText("<a<a<a<a"))
        assertEquals("before after", normalizeSearchHtmlText("before <div\n class=\"x\"> after"))
        assertEquals("quoted > text", normalizeSearchHtmlText("<span title='x > y'>quoted > text</span>"))
    }

    @Test(timeout = 10_000)
    fun `pathological near-limit tags and entities complete within a generous bound`() {
        val size = SearchHtmlTextBounds.MAX_INPUT_CHARS
        val malformedTags = "<a".repeat(size / 2)
        val malformedEntities = "&".repeat(size)
        val malformedNumeric = "&#x".repeat(size / 4)
        assertEquals(size, normalizeSearchHtmlText(malformedTags).length)
        assertEquals(size, normalizeSearchHtmlText(malformedEntities).length)
        assertTrue(normalizeSearchHtmlText(malformedNumeric).length <= size)
    }

    @Test(timeout = 10_000)
    fun `oversized direct input is capped and long tags do not cause suffix rescans`() {
        val input = "x".repeat(SearchHtmlTextBounds.MAX_INPUT_CHARS + 100)
        assertEquals(SearchHtmlTextBounds.MAX_INPUT_CHARS, normalizeSearchHtmlText(input).length)

        val longQuotedAttribute = "<span title=\"" + "<a".repeat(80_000) + "\">visible</span>"
        assertTrue(normalizeSearchHtmlText(longQuotedAttribute).isNotEmpty())
    }

    @Test
    fun `normalizer checks cancellation during bounded work`() {
        val checks = AtomicInteger()
        try {
            normalizeSearchHtmlText("x".repeat(100_000)) {
                if (checks.incrementAndGet() == 2) throw ExpectedCancellation()
            }
            throw AssertionError("expected cancellation callback")
        } catch (_: ExpectedCancellation) {
            assertTrue(checks.get() >= 2)
        }
    }

    @Test(timeout = 20_000)
    fun `warmed full input scaling remains bounded for malformed and ordinary HTML`() {
        val sizes = listOf(32 * 1024, 128 * 1024, 512 * 1024)
        val cases = listOf(
            "unclosed-tags" to { n: Int -> "<a".repeat(n / 2) },
            "unclosed-entities" to { n: Int -> "&".repeat(n) },
            "valid-inline-html" to { n: Int -> "<b>한글 &amp; text 😀</b> ".repeat(n / 25 + 1).take(n) },
        )
        val rows = ArrayList<String>()
        for ((label, makeInput) in cases) {
            for (size in sizes) {
                val input = makeInput(size)
                repeat(5) { normalizeSearchHtmlText(input) }
                val samples = List(9) {
                    var output = ""
                    val elapsed = measureNanoTime { output = normalizeSearchHtmlText(input) }
                    assertTrue("normalizer unexpectedly dropped content for $label/$size", output.isNotEmpty())
                    elapsed / 1_000_000.0
                }.sorted()
                rows += "$label inputChars=${input.length} fullInput=true medianMs=${"%.3f".format(java.util.Locale.ROOT, samples[samples.size / 2])}"
            }
        }
        System.out.println("P7_NORMALIZER_PERF jdk=${System.getProperty("java.version")} vendor=${System.getProperty("java.vendor")} ${rows.joinToString(" | ")}")
    }

    private class ExpectedCancellation : RuntimeException()
}
