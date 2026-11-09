package com.projectnuke.fusion.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Section 16: conservative deterministic PDF text normalization.
class NormalizedPdfTextTest {

    @Test
    fun `crlf cr and form feed normalize to lf`() {
        // Form feed (page-break byte) maps to a line break.
        assertEquals("a\nb\nc\nc", NormalizedPdfText.normalize("a\r\nb\rc\u000Cc"))
    }

    @Test
    fun `nul and control garbage are removed while tab survives`() {
        assertEquals("가나다\t라\t마\n다음", NormalizedPdfText.normalize("가\u0000나\u0001다\t라\u001F\t마\n다음"))
    }

    @Test
    fun `runs of four or more spaces collapse conservatively`() {
        // 4+ -> single space (double-space alignment artifacts).
        assertEquals("a b", NormalizedPdfText.normalize("a    b"))
        assertEquals("가 다", NormalizedPdfText.normalize("가        다"))
        // 1-3 spaces preserved so column/table separation is not rewritten.
        assertEquals("a b", NormalizedPdfText.normalize("a b"))
        assertEquals("a  b", NormalizedPdfText.normalize("a  b"))
        assertEquals("a   b", NormalizedPdfText.normalize("a   b"))
    }

    @Test
    fun `trailing whitespace per line is trimmed but leading indentation is kept`() {
        assertEquals("  retain\nkeep", NormalizedPdfText.normalize("  retain   \nkeep\t"))
    }

    @Test
    fun `empty input stays empty and normalization is idempotent`() {
        assertEquals("", NormalizedPdfText.normalize(""))
        val once = NormalizedPdfText.normalize("라인1\r\n라인2   \n라인3\u0000라인3")
        assertEquals("라인1\n라인2\n라인3라인3", once)
        assertEquals(once, NormalizedPdfText.normalize(once))
    }

    @Test
    fun `deterministic repeated extraction is byte-stable`() {
        val raw = "Column A\u0000  col-a   \r\nColumn B  col-b\r\n"
        // \u0000 removed; the remaining 2/3-space runs are preserved
        // conservatively (only 4+ space runs collapse).
        repeat(3) {
            assertEquals("Column A  col-a\nColumn B  col-b\n", NormalizedPdfText.normalize(raw))
            val a = NormalizedPdfText.normalize(raw)
            val b = NormalizedPdfText.normalize(raw)
            assertEquals(a, b)
        }
    }

    @Test
    fun `hybrid control-runs are handled without throwing on grapheme splits`() {
        // Exercise the char-iteration path with multi-byte Korean + controls.
        val out = NormalizedPdfText.normalize("가나다\u0000라마바\r\n섹션\tA\u001F\u0001B")
        assertTrue(out.length <= "가나다라마바섹션\tAB".length + 6)
        assertTrue(!out.contains('\u0000'))
        assertTrue(!out.contains('\u0001'))
        assertTrue(!out.contains('\u001F'))
        assertTrue(!out.contains('\r'))
    }
}