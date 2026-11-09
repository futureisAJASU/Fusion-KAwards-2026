package com.projectnuke.fusion.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 adversarial tests: entity-like lexical tokens bind only to their own
 * entity. Adjacent nested names (S24/S24FE, P1/P1A, Hangul organization
 * suffixes) must not receive positive lexical support for one another, while
 * legitimate family/version prefixes (gemma -> gemma3) keep matching.
 */
class Phase5RagNestedEntityTest {

    private fun chunk(docId: String, text: String, chunkIndex: Int = 1) = LocalDocumentChunk(
        docId = docId,
        displayName = "$docId.txt",
        chunkIndex = chunkIndex,
        startOffset = 0,
        endOffset = text.length,
        contentHashHex = "hash-$docId-$chunkIndex",
        text = text,
    )

    private fun indexFor(pages: List<Pair<Int, String>>): PdfDocumentIndex = PdfDocumentIndex(
        metadata = DocumentIndexMetadata(
            version = DocumentIndexContract.DOCUMENT_INDEX_VERSION,
            kind = LocalDocumentKind.PDF_TEXT_LAYER,
            pageCount = pages.size,
            textPageCount = pages.count { it.second.isNotEmpty() },
            imageOnlyPageCount = pages.count { it.second.isEmpty() },
            imageElementCount = 0,
            totalExtractedChars = pages.sumOf { it.second.length },
            sourceSizeBytes = 1,
            sourceLastModifiedMillis = 1,
        ),
        pages = pages.map { (n, text) ->
            IndexedDocumentPage(
                pageNumber = n,
                text = text,
                textCharCount = text.length,
                contentHashHex = LocalDocumentChunker.sha256Hex(text),
                hasImages = text.isEmpty(),
                imageElementCount = 0,
            )
        },
    )

    // ── C1 S24 / S24FE ─────────────────────────────────────────────────────

    @Test
    fun `C1 exact S24 document outranks adjacent S24FE regardless of ordering`() {
        val exact = chunk("att-b", "S24 price is 799")
        val adjacent = chunk("att-a", "S24FE price is 999")
        listOf(
            listOf(exact, adjacent),
            listOf(adjacent, exact),
            listOf(exact.copy(docId = "att-0"), adjacent.copy(docId = "att-z")),
            listOf(adjacent.copy(docId = "att-0"), exact.copy(docId = "att-z")),
        ).forEach { chunks ->
            val selections = LocalRagRetrieval.retrieve(query = "S24 price", chunks = chunks)
            assertTrue(selections.isNotEmpty())
            assertEquals(
                "the exact S24 entity must win for ${chunks.map { it.docId }}",
                "S24 price is 799",
                selections.first().chunk.text,
            )
        }
    }

    @Test
    fun `C1 adjacent S24FE gains no entity support for an S24 query`() {
        assertEquals(0, LocalRagLexical.score("S24", "S24FE price is 999"))
        assertEquals(1, LocalRagLexical.score("S24", "S24 price is 799"))
    }

    // ── C2 P1 / P1A ────────────────────────────────────────────────────────

    @Test
    fun `C2 exact P1 document outranks adjacent P1A`() {
        val exact = chunk("att-b", "P1 price is 100")
        val adjacent = chunk("att-a", "P1A price is 300")
        listOf(listOf(exact, adjacent), listOf(adjacent, exact)).forEach { chunks ->
            val selections = LocalRagRetrieval.retrieve(query = "P1 price", chunks = chunks)
            assertEquals(
                "the exact P1 entity must win",
                true,
                selections.first().chunk.text == "P1 price is 100",
            )
        }
        assertEquals(0, LocalRagLexical.score("P1", "P1A price is 300"))
    }

    // ── C3 Hangul nested entity ────────────────────────────────────────────

    @Test
    fun `C3 exact Hangul entity outranks a nested longer organization name`() {
        val exact = chunk("att-b", "삼성전자 가격은 200")
        val nested = chunk("att-a", "삼성전자서비스의 가격은 100")
        val selections = LocalRagRetrieval.retrieve(query = "삼성전자 가격", chunks = listOf(nested, exact))
        assertEquals(
            "the longer adjacent organization must not tie the exact entity",
            "삼성전자 가격은 200",
            selections.first().chunk.text,
        )
        assertTrue(
            "the nested organization must score strictly below the exact entity",
            selections.first { it.chunk.text == "삼성전자 가격은 200" }.lexicalScore >
                selections.first { it.chunk.text == "삼성전자서비스의 가격은 100" }.lexicalScore,
        )
    }

    @Test
    fun `C3 Hangul agglutination still matches`() {
        assertTrue(LocalRagLexical.score("사과", "사과는 빨간 과일이다") > 0)
        assertEquals(1, LocalRagLexical.score("사과", "사과는 빨간 과일이다"))
    }

    // ── C4 family-prefix compatibility ─────────────────────────────────────

    @Test
    fun `C4 legitimate model family prefix still matches`() {
        assertEquals(1, LocalRagLexical.score("gemma", "gemma3 price is 100"))
        assertEquals(1, LocalRagLexical.score("gemma", "gemma-3b price is 100"))
        // A category-level query must still find the versioned family member.
        val selections = LocalRagRetrieval.retrieve(
            query = "gemma price",
            chunks = listOf(chunk("att-a", "gemma3 price is 100")),
        )
        assertTrue(selections.isNotEmpty())
        assertEquals("att-a", selections.first().chunk.docId)
    }

    @Test
    fun `C4 exact entity query is not a family prefix`() {
        assertEquals(0, LocalRagLexical.score("S24", "S24FE price is 999"))
        assertEquals(0, LocalRagLexical.score("P1", "P10 price is 999"))
    }

    @Test
    fun `C4 digit-bearing memory generation does not bind the next generation`() {
        assertEquals(0, LocalRagLexical.score("HBM4", "HBM4E price is 999"))
        assertEquals(1, LocalRagLexical.score("HBM4", "HBM4 price is 799"))
    }

    @Test
    fun `C4 Roman numeral model does not bind the next numeral`() {
        val exact = chunk("att-b", "Artemis II price is 100")
        val adjacent = chunk("att-a", "Artemis III price is 300")
        val selections = LocalRagRetrieval.retrieve(query = "Artemis II price", chunks = listOf(adjacent, exact))
        assertEquals("Artemis II price is 100", selections.first().chunk.text)
        assertEquals(0, LocalRagLexical.score("II", "Artemis III price is 300"))
    }

    // ── C5 PDF page preselection ───────────────────────────────────────────

    @Test
    fun `C5 PDF page preselection prefers the exact entity page`() {
        val index = indexFor(
            listOf(
                1 to "S24FE price is 999",
                2 to "S24 price is 799",
            ),
        )
        val pages = PdfPageCandidatePolicy.selectPages(
            query = "S24 price",
            index = index,
            intent = DocumentRetrievalIntent.TARGETED,
        )
        assertEquals(2, pages.first())
    }

    // ── C6 semantic-ranker interaction ────────────────────────────────────

    @Test
    fun `C6 false adjacent lexical hit cannot starve the correct semantic candidate`() {
        val adjacent = chunk("att-a", "S24FE price is 999")
        val correctButLexicalZero = chunk("att-z", "the flagship handset costs 799")
        val ranker = object : LocalSemanticRanker {
            override fun score(query: String, chunkText: String): Double? =
                if (chunkText == correctButLexicalZero.text) 0.95 else 0.10
        }
        val selections = LocalRagRetrieval.retrieve(
            query = "S24",
            chunks = listOf(adjacent, correctButLexicalZero),
            topK = 1,
            semanticRanker = ranker,
        )
        assertEquals(
            "the wrong adjacent entity must not own the only slot",
            correctButLexicalZero.docId,
            selections.first().chunk.docId,
        )
    }
}
