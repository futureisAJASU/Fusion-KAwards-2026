package com.projectnuke.fusion.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentHighlightPolicyTest {
    private val hash = "ab".repeat(32)

    @Test
    fun `prepared highlight retains the exact page and source identity`() {
        val result = DocumentHighlightPolicy.prepare(
            DocumentHighlightDraft(2, 7, "paper.pdf", 4, "page:4", hash, "  quoted passage  ", "review later"),
        ) as DocumentHighlightPreparation.Ready
        assertEquals("paper.pdf · p.4", result.value.title)
        assertEquals(4, result.value.pageNumber)
        assertEquals("page:4", result.value.sourceChunkId)
        assertEquals(hash, result.value.sourceContentHashHex)
        assertEquals("quoted passage", result.value.quotedText)
        assertEquals("review later", result.value.userNote)
    }

    @Test
    fun `invalid anchors and oversized quotes fail without inventing positions`() {
        assertTrue(DocumentHighlightPolicy.prepare(DocumentHighlightDraft(1, 2, "doc", 0, null, null, "quote")) is DocumentHighlightPreparation.Invalid)
        assertTrue(DocumentHighlightPolicy.prepare(DocumentHighlightDraft(1, 2, "doc", null, null, "not-a-hash", "quote")) is DocumentHighlightPreparation.Invalid)
        assertTrue(DocumentHighlightPolicy.prepare(DocumentHighlightDraft(1, 2, "doc", null, null, null, "q".repeat(1_201))) is DocumentHighlightPreparation.Invalid)
        val noPage = DocumentHighlightPolicy.prepare(DocumentHighlightDraft(1, 2, "text.txt", null, null, hash, "quote"))
        assertTrue(noPage is DocumentHighlightPreparation.Ready)
        assertEquals(null, (noPage as DocumentHighlightPreparation.Ready).value.pageNumber)
    }
}
