package com.projectnuke.fusion.retrieval

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase G: PDF page provenance survives the persisted source-metadata
// envelope; old answers without pageNumber stay fully readable and old
// offset fields are never reinterpreted as page information.
class PageProvenanceMetadataTest {

    private fun pdfEntry(pageNumber: Int?) = LocalRagSourceEntry(
        fileName = "SF2_process.pdf",
        attachmentId = "att-1",
        chunkIndex = 32,
        startOffset = 40,
        endOffset = 900,
        contentHashHex = "h".repeat(64),
        excerpt = "공정 프로세스 발췌 내용",
        rank = 0,
        lexicalScore = 3,
        semanticScore = 0.8,
        pageNumber = pageNumber,
    )

    @Test
    fun `pdf page number survives the persisted envelope`() {
        val content = LocalRagSourceMetadata.append("답변", listOf(pdfEntry(32)))
        val parsed = LocalRagSourceMetadata.parse(content)
        assertEquals(1, parsed.size)
        assertEquals(32, parsed[0].pageNumber)
        assertEquals("SF2_process.pdf", parsed[0].fileName)
    }

    @Test
    fun `old persisted entries without pageNumber stay fully readable`() {
        // Simulate an envelope persisted before PDF support existed.
        val array = JSONArray()
        array.put(
            JSONObject()
                .put("fileName", "notes.txt")
                .put("attachmentId", "att-9")
                .put("chunkIndex", 0)
                .put("startOffset", 0)
                .put("endOffset", 100)
                .put("contentHashHex", "a".repeat(64))
                .put("excerpt", "옛날 발췌")
                .put("rank", 0)
                .put("lexicalScore", 1)
                .put("semanticScore", JSONObject.NULL),
        )
        val content = "답변\n\n[FUSION_LOCAL_RAG_SOURCES_JSON]\n$array\n[/FUSION_LOCAL_RAG_SOURCES_JSON]"
        val parsed = LocalRagSourceMetadata.parse(content)
        assertEquals(1, parsed.size)
        assertNull(parsed[0].pageNumber)
        assertEquals("notes.txt", parsed[0].fileName)
        assertEquals("옛날 발췌", parsed[0].excerpt)
        assertEquals(0, parsed[0].startOffset)
    }

    @Test
    fun `text entries stay byte-identical in the envelope`() {
        val textEntry = pdfEntry(null)
        val withPage = LocalRagSourceMetadata.append("답변", listOf(pdfEntry(3)))
        val withoutPage = LocalRagSourceMetadata.append("답변", listOf(textEntry))
        // The page field is only serialized when present.
        assertTrue(withPage.contains("\"pageNumber\":3"))
        assertTrue(!withoutPage.contains("pageNumber"))
        // Plain-text round trip preserves null (never a fake page).
        assertNull(LocalRagSourceMetadata.parse(withoutPage)[0].pageNumber)
    }

    @Test
    fun `old offsets are never reinterpreted as pdf pages`() {
        // A legacy text entry with large offsets must NOT gain a page number.
        val legacy = LocalRagSourceEntry(
            fileName = "report.txt",
            attachmentId = "att-7",
            chunkIndex = 30,
            startOffset = 29_000,
            endOffset = 29_500,
            contentHashHex = "b".repeat(64),
            excerpt = "긴 문자 문서의 뒤쪽 부분",
            rank = 4,
            lexicalScore = 2,
            semanticScore = null,
        )
        val content = LocalRagSourceMetadata.append("답변", listOf(legacy))
        assertNull(LocalRagSourceMetadata.parse(content)[0].pageNumber)
        assertEquals(29_000, LocalRagSourceMetadata.parse(content)[0].startOffset)
    }

    @Test
    fun `page provenance label is display copy not a url`() {
        assertEquals("32쪽", DocumentStatusPolicy.pageLabel(32))
        assertEquals("SF2_process.pdf · 32쪽", DocumentStatusPolicy.pageProvenanceLabel("SF2_process.pdf", 32))
        assertTrue(!DocumentStatusPolicy.pageProvenanceLabel("SF2_process.pdf", 32).contains("http"))
        assertTrue(!DocumentStatusPolicy.pageProvenanceLabel("SF2_process.pdf", 32).contains("검증"))
    }
}