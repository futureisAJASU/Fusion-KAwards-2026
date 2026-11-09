package com.projectnuke.fusion.retrieval

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase D+E matrix: page-aware local RAG. Real page numbers survive through
// chunking, candidate selection, retrieval, snapshot and fail-closed
// selected-page validation. Plain-text semantics stay untouched.
class PageAwareRagTest {

    private class FakeIndexSource(
        private val indexes: Map<String, PdfDocumentIndex>,
        private val statuses: Map<String, DocumentExtractionStatus> = emptyMap(),
        internal var livePages: MutableMap<String, Map<Int, String>> = mutableMapOf(),
    ) : LocalDocumentIndexSource {
        var openCalls = 0

        override suspend fun obtain(canonicalPath: String, mimeType: String?): DocumentIndexResult {
            statuses[canonicalPath]?.let { return DocumentIndexResult.NotReady(it) }
            return indexes[canonicalPath]?.let { DocumentIndexResult.Ready(it) }
                ?: DocumentIndexResult.NotReady(DocumentExtractionStatus.UNREADABLE)
        }

        override suspend fun reextractPages(
            canonicalPath: String,
            pageNumbers: Set<Int>,
        ): Map<Int, ExtractedDocumentPage>? {
            openCalls++
            val live = livePages[canonicalPath] ?: return null
            return live.filterKeys { it in pageNumbers }.mapValues { (page, text) ->
                ExtractedDocumentPage(
                    pageNumber = page,
                    text = text,
                    textCharCount = text.length,
                    contentHashHex = LocalDocumentChunker.sha256Hex(text),
                    hasImages = false,
                    imageElementCount = 0,
                )
            }
        }
    }

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

    private fun record(path: String, name: String = File(path).name, mime: String = "application/pdf") =
        com.projectnuke.fusion.util.AttachmentRecord(name, mime, path)

    private class FakeTextStore(
        private val files: Map<String, String>,
    ) : LocalAttachmentTextStore {
        override fun readText(path: String, maxChars: Int): String? = files[path]?.take(maxChars)
    }

    // ------------------------------------------------------------------ D2

    @Test
    fun `pdf page chunks never cross page boundaries`() {
        val chunks = LocalDocumentPageChunker.chunkPages(
            "d", "paper.pdf",
            listOf(
                IndexedDocumentPage(1, "a".repeat(3_000), 3_000, "h1", false, 0),
                IndexedDocumentPage(2, "b".repeat(3_000), 3_000, "h2", false, 0),
            ),
        )
        assertTrue(chunks.size > 2)
        val page1Chunks = chunks.filter { it.pageNumber == 1 }
        val page2Chunks = chunks.filter { it.pageNumber == 2 }
        assertEquals(3, page1Chunks.size)
        assertEquals(3, page2Chunks.size)
        assertTrue("chunk1 starts at page-local offset 0", page1Chunks[0].startOffset == 0)
        assertTrue("first chunk is 1200 chars", page1Chunks[0].text.length == 1_200)
        // No chunk mixes content from two pages.
        for (chunk in chunks) {
            val expectedStart = if (chunk.pageNumber == 1) 'a' else 'b'
            assertTrue(chunk.text.all { it == expectedStart })
        }
    }

    @Test
    fun `pdf chunk index is deterministic per page`() {
        val chunks = LocalDocumentPageChunker.chunkPages(
            "d", "paper.pdf",
            listOf(
                IndexedDocumentPage(5, "text".repeat(500), 2_000, "h", false, 0),
            ),
        )
        assertTrue(chunks.all { it.chunkIndex >= 5 * LocalRagBounds.MAX_CHUNKS_PER_DOC })
        assertEquals(chunks.map { it.chunkIndex }.distinct().size, chunks.size)
        val reChunked = LocalDocumentPageChunker.chunkPages(
            "d", "paper.pdf",
            listOf(
                IndexedDocumentPage(5, "text".repeat(500), 2_000, "h", false, 0),
            ),
        )
        assertEquals(chunks.map { it.chunkIndex }, reChunked.map { it.chunkIndex })
        assertEquals(chunks.map { it.contentHashHex }, reChunked.map { it.contentHashHex })
    }

    @Test
    fun `single chunk per short page with real page number`() {
        val chunks = LocalDocumentPageChunker.chunkPages(
            "d", "paper.pdf",
            listOf(IndexedDocumentPage(32, "짧은 페이지", 6, "h", false, 0)),
        )
        assertEquals(1, chunks.size)
        assertEquals(32, chunks[0].pageNumber)
        assertEquals("짧은 페이지", chunks[0].text)
    }

    // ------------------------------------------------------------------ D3

    @Test
    fun `targeted lexical query finds the correct page`() {
        val index = indexFor(
            listOf(
                1 to "회사의 연혁과 설립 배경에 대한 소개",
                2 to "사과 농장의 수확량과 재배 방법 설명",
                3 to "인사 정책과 복지 제도 정리",
            ),
        )
        val pages = PdfPageCandidatePolicy.selectPages("사과 수확", index, DocumentRetrievalIntent.TARGETED)
        assertTrue(pages.contains(2))
        assertTrue(pages.size <= LocalRagBounds.MAX_PDF_PAGE_CANDIDATES)
    }

    @Test
    fun `adjacency window is bounded and included pages stay in range`() {
        val pages = (1..60).map { it to "페이지 $it: 문서의 일반적인 내용을 이곳에 기록한다." }
        val index = indexFor(pages)
        val selected = PdfPageCandidatePolicy.selectPages("페이지 30", index, DocumentRetrievalIntent.TARGETED)
        assertTrue(selected.contains(30))
        assertTrue(selected.contains(29) || selected.contains(31))
        assertTrue(selected.all { it in 1..60 })
        assertTrue(selected.size <= LocalRagBounds.MAX_PDF_PAGE_CANDIDATES)
        assertEquals(selected, selected.distinct())
    }

    @Test
    fun `candidate priority is preserved into pdf chunking so a strong-hit page is never starved`() = runTest {
        // Deterministic fixture: page 2 is the STRONGEST lexical hit but sits
        // after two very long weak/adjacency pages (1 and 3). Under the old
        // physical-order materialization, page 1's ~29 chunks would consume
        // the per-document chunk budget and page 2's relevant chunk (which
        // carries both query tokens) would never enter the chunk pool.
        val strongTokens = "사과 재배"
        val page1 = "사과" + "x".repeat(PdfDocumentBounds.MAX_PDF_PAGE_CHARS - 2)
        val page2 = "y".repeat(3_400) + "$strongTokens 방법" + "z".repeat(100)
        val page3 = "재배" + "x".repeat(PdfDocumentBounds.MAX_PDF_PAGE_CHARS - 2)
        val index = indexFor(
            listOf(
                1 to page1,
                2 to page2,
                3 to page3,
                4 to "무관한 내용 A",
                5 to "무관한 내용 B",
            ),
        )

        // Candidate policy puts the strong-hit page FIRST.
        val ordered = PdfPageCandidatePolicy.selectPages(
            "$strongTokens",
            index,
            DocumentRetrievalIntent.TARGETED,
        )
        assertEquals(2, ordered.first())
        assertTrue(ordered.size <= LocalRagBounds.MAX_PDF_PAGE_CANDIDATES)

        val byPageNumber = index.pages.associateBy { it.pageNumber }

        // Fixed pipeline: chunk in candidate priority order -> page 2's
        // relevant chunk survives.
        val orderedChunks = LocalDocumentPageChunker.chunkPages(
            "d-priority",
            "priority.pdf",
            ordered.mapNotNull(byPageNumber::get),
        )
        assertTrue(
            "strong-hit page chunk must be in the ordered chunk pool",
            orderedChunks.any { it.pageNumber == 2 && it.text.contains(strongTokens) },
        )

        // Old bug reproduction: physical-order materialization of the same
        // candidates starves page 2's relevant chunk.
        val physicalOrderCandidates = index.pages.filter { it.pageNumber in setOf(1, 2, 3) }
        val physicalChunks = LocalDocumentPageChunker.chunkPages(
            "d-priority",
            "priority.pdf",
            physicalOrderCandidates,
        )
        assertTrue(
            "physical-order chunking must starve the strong-hit relevant chunk",
            physicalChunks.none { it.pageNumber == 2 && it.text.contains(strongTokens) },
        )

        // End-to-end: retrieval can still select the strong page through the
        // production coordinator (ordered candidate pipeline + lexical-first).
        val dir = Files.createTempDirectory("fusion-rag-priority").toFile()
        val path = File(dir, "priority.pdf").apply { writeText("x") }
        val coordinator = LocalRagCoordinator(
            textStore = FakeTextStore(emptyMap()),
            pdfIndexSource = FakeIndexSource(mapOf(path.canonicalPath to index)),
        )
        val result = coordinator.prepare("req-priority", strongTokens, listOf(record(path.canonicalPath)))
        assertTrue(result is LocalRagPreparationResult.Prepared)
        val selections = (result as LocalRagPreparationResult.Prepared).selections
        assertTrue(
            "retrieval must select the strong-hit page chunk",
            selections.any { it.chunk.pageNumber == 2 && it.chunk.text.contains(strongTokens) },
        )
        // Lexical-first preserved: page 2 selection outranks the weak page 1 hit.
        val page2Sel = selections.first { it.chunk.pageNumber == 2 && it.chunk.text.contains(strongTokens) }
        val page1Sel = selections.firstOrNull { it.chunk.pageNumber == 1 }
        assertTrue(selections.indexOf(page2Sel) < (page1Sel?.let { selections.indexOf(it) } ?: Int.MAX_VALUE))

        // Invariants remain frozen.
        assertEquals(32, LocalRagBounds.MAX_CHUNKS_PER_DOC)
        assertEquals(12, LocalRagBounds.MAX_PDF_PAGE_CANDIDATES)
        // Chunks never cross page boundaries and carry real 1-based pages.
        assertTrue(orderedChunks.all { it.pageNumber in 1..5 })
        assertTrue(orderedChunks.all { chunk ->
            val page = byPageNumber[chunk.pageNumber]
            page != null && chunk.text.isNotEmpty() && page.text.contains(chunk.text.take(50))
        })
        assertTrue(orderedChunks.map { it.pageNumber }.distinct().size >= 2)
    }

    @Test
    fun `no-hit query falls back to deterministic representative spread`() {
        val pages = (1..100).map { it to "페이지 $it: 무관한 일반 텍스트 내용이다." }
        val index = indexFor(pages)
        val selected = PdfPageCandidatePolicy.selectPages("우주 왕복선 바나나", index, DocumentRetrievalIntent.TARGETED)
        assertTrue(selected.isNotEmpty())
        assertTrue(selected.size <= LocalRagBounds.MAX_PDF_PAGE_CANDIDATES)
        assertTrue(selected.contains(1))
        assertTrue(selected.contains(100))
        // Deterministic: identical input -> identical output.
        val again = PdfPageCandidatePolicy.selectPages("우주 왕복선 바나나", index, DocumentRetrievalIntent.TARGETED)
        assertEquals(selected, again)
    }

    @Test
    fun `overview on large pdf chooses representative pages and never all of them`() {
        val pages = (1..200).map { it to "페이지 $it: 보고서 본문 내용이 계속된다. ".repeat(20) }
        val index = indexFor(pages)
        val selected = PdfPageCandidatePolicy.selectPages("요약해줘", index, DocumentRetrievalIntent.OVERVIEW)
        assertTrue(selected.size <= LocalRagBounds.MAX_PDF_PAGE_CANDIDATES)
        assertTrue(selected.size >= 2)
        assertTrue(!selected.containsAll(pages.map { it.first }))
    }

    @Test
    fun `overview on small pdf selects everything (full coverage possible)`() {
        val index = indexFor(listOf(1 to "짧은", 2 to "내용", 3 to "문서"))
        val selected = PdfPageCandidatePolicy.selectPages("전체 요약해줘", index, DocumentRetrievalIntent.OVERVIEW)
        assertEquals(listOf(1, 2, 3), selected)
    }

    @Test
    fun `candidate selection never exceeds twelve pages per pdf`() {
        val pages = (1..120).map { it to "검색어 등장 페이지 $it 내용 ".repeat(40) }
        val index = indexFor(pages)
        for (intent in DocumentRetrievalIntent.entries) {
            val selected = PdfPageCandidatePolicy.selectPages("검색어", index, intent)
            assertTrue(selected.size <= LocalRagBounds.MAX_PDF_PAGE_CANDIDATES)
        }
    }

    // ------------------------------------------------------------------ D4

    @Test
    fun `intent classification is rule-based and deterministic`() {
        assertEquals(DocumentRetrievalIntent.OVERVIEW, DocumentRetrievalIntentPolicy.classify("이 pdf 전체 요약해줘"))
        assertEquals(DocumentRetrievalIntent.OVERVIEW, DocumentRetrievalIntentPolicy.classify("핵심만 요약해 주세요"))
        assertEquals(DocumentRetrievalIntent.COMPARE, DocumentRetrievalIntentPolicy.classify("두 문서의 차이를 비교해줘"))
        assertEquals(DocumentRetrievalIntent.COMPARE, DocumentRetrievalIntentPolicy.classify("모순을 찾아 주세요"))
        assertEquals(DocumentRetrievalIntent.TARGETED, DocumentRetrievalIntentPolicy.classify("사과 재배 방법 질문"))
    }

    @Test
    fun `coverage truth distinguishes full from representative excerpts`() {
        val index = indexFor(listOf(1 to "페이지 일", 2 to "페이지 이", 3 to "페이지 삼"))
        val docId = "doc-coverage"
        fun keys(vararg pages: Int): Set<String> = pages.flatMap { page ->
            DocumentRetrievalIntentPolicy.expectedPageChunkKeys(
                docId, page, index.pages.first { it.pageNumber == page }.text.length,
            )
        }.toSet()
        assertFalse(DocumentRetrievalIntentPolicy.hasFullTextCoverage(index, docId, keys(1, 2)))
        assertTrue(DocumentRetrievalIntentPolicy.hasFullTextCoverage(index, docId, keys(1, 2, 3)))
        assertFalse(DocumentRetrievalIntentPolicy.hasFullTextCoverage(index, docId, emptySet()))
    }

    @Test
    fun `partially consumed long page never counts as full coverage`() {
        // 6,000-char single page -> 6 chunks; selecting only the first chunk
        // must not read as full text coverage (C07).
        val longText = "가".repeat(6_000)
        val index = indexFor(listOf(1 to longText))
        val docId = "doc-long"
        val expected = DocumentRetrievalIntentPolicy.expectedPageChunkKeys(docId, 1, longText.length)
        assertTrue(expected.size > 1)
        val firstOnly = setOf(expected.first())
        assertFalse(DocumentRetrievalIntentPolicy.hasFullTextCoverage(index, docId, firstOnly))
        assertTrue(DocumentRetrievalIntentPolicy.hasFullTextCoverage(index, docId, expected))
        // Middle chunk missing is still partial.
        assertFalse(
            DocumentRetrievalIntentPolicy.hasFullTextCoverage(index, docId, expected.drop(1).toSet()),
        )
    }

    // ------------------------------------------------------------- D4+RAG

    @Test
    fun `prepared pdf selections carry real pages and coverage map`() = runTest {
        val dir = Files.createTempDirectory("fusion-rag").toFile()
        val path = File(dir, "paper.pdf").apply { writeText("x") }
        val index = indexFor(
            listOf(
                1 to "사과 수확량 보고서 첫 페이지입니다.",
                2 to "사과 시장 전망 두 번째 페이지입니다.",
                3 to "...",
                4 to "...",
                5 to "...",
            ),
        )
        val coordinator = LocalRagCoordinator(
            textStore = FakeTextStore(emptyMap()),
            pdfIndexSource = FakeIndexSource(mapOf(path.canonicalPath to index)),
        )
        val result = coordinator.prepare("req", "사과 수확량", listOf(record(path.canonicalPath)))
        assertTrue(result is LocalRagPreparationResult.Prepared)
        val prepared = result as LocalRagPreparationResult.Prepared
        assertTrue(prepared.selections.isNotEmpty())
        assertTrue(prepared.selections.all { it.chunk.pageNumber != null })
        assertTrue(prepared.selections.all { it.source.pageNumber != null })
        // Snapshot keeps page identity aligned per chunk.
        assertEquals(
            prepared.selections.map { it.chunk.pageNumber },
            prepared.snapshot.pageNumbers,
        )
        // A large PDF was only partially selected: coverage must be FALSE so
        // the model never claims full-document coverage.
        assertEquals(false, prepared.coverageByDoc[prepared.selections[0].chunk.docId])
        val prompt = LocalRagPromptPolicy.assemble("사과 요약", prepared.selections, prepared.coverageByDoc)
        assertTrue(prompt.contains("대표 발췌"))
        assertFalse(LocalRagPromptPolicy.containsRawMarkers(prompt))
    }

    @Test
    fun `small fully-selected pdf gets truthful full-coverage framing`() = runTest {
        val dir = Files.createTempDirectory("fusion-rag").toFile()
        val path = File(dir, "short.pdf").apply { writeText("x") }
        // Every text page mentions the query, and the whole document fits the
        // prompt contract: full-document coverage is REAL and may be framed
        // as such.
        val index = indexFor(listOf(1 to "짧은 요약 문서", 2 to "요약 추가 내용"))
        val coordinator = LocalRagCoordinator(
            textStore = FakeTextStore(emptyMap()),
            pdfIndexSource = FakeIndexSource(mapOf(path.canonicalPath to index)),
        )
        val result = coordinator.prepare("req-full", "요약", listOf(record(path.canonicalPath)))
        assertTrue(result is LocalRagPreparationResult.Prepared)
        val prepared = result as LocalRagPreparationResult.Prepared
        assertEquals(true, prepared.coverageByDoc[prepared.selections[0].chunk.docId])
        val prompt = LocalRagPromptPolicy.assemble("요약", prepared.selections, prepared.coverageByDoc)
        assertFalse(prompt.contains("대표 발췌"))
    }

    @Test
    fun `partially selected small pdf never claims full coverage`() = runTest {
        val dir = Files.createTempDirectory("fusion-rag").toFile()
        val path = File(dir, "short.pdf").apply { writeText("x") }
        // Page 2 does not match the query: only page 1 is selected, so even a
        // small document must be framed as partial — truth beats convenience.
        val index = indexFor(listOf(1 to "짧은 요약 문서", 2 to "무관한 내용"))
        val coordinator = LocalRagCoordinator(
            textStore = FakeTextStore(emptyMap()),
            pdfIndexSource = FakeIndexSource(mapOf(path.canonicalPath to index)),
        )
        val result = coordinator.prepare("req-partial", "요약", listOf(record(path.canonicalPath)))
        assertTrue(result is LocalRagPreparationResult.Prepared)
        val prepared = result as LocalRagPreparationResult.Prepared
        val docId = prepared.selections[0].chunk.docId
        assertEquals(false, prepared.coverageByDoc[docId])
        val prompt = LocalRagPromptPolicy.assemble("요약", prepared.selections, prepared.coverageByDoc)
        assertTrue(prompt.contains("대표 발췌"))
    }

    @Test
    fun `text contract unchanged - no pages, no coverage map`() = runTest {
        val dir = Files.createTempDirectory("fusion-rag").toFile()
        val path = File(dir, "notes.txt").apply { writeText("사과 농장 보고서 내용이다. ".repeat(40)) }
        val coordinator = LocalRagCoordinator(
            textStore = FakeTextStore(mapOf(path.canonicalPath to "사과 농장 보고서 내용이다. ".repeat(40))),
        )
        val result = coordinator.prepare("req-txt", "사과", listOf(record(path.canonicalPath, "notes.txt", "text/plain")))
        assertTrue(result is LocalRagPreparationResult.Prepared)
        val prepared = result as LocalRagPreparationResult.Prepared
        assertTrue(prepared.selections.all { it.chunk.pageNumber == null })
        assertTrue(prepared.snapshot.pageNumbers.all { it == null })
        assertTrue(prepared.coverageByDoc.isEmpty())
        val prompt = LocalRagPromptPolicy.assemble("사과", prepared.selections, prepared.coverageByDoc)
        assertFalse(prompt.contains("page="))
        assertFalse(prompt.contains("대표 발췌"))
    }

    @Test
    fun `pdf and text canonical dedupe keeps one chunk pool entry`() = runTest {
        val dir = Files.createTempDirectory("fusion-rag").toFile()
        val pdfPath = File(dir, "dup.pdf").apply { writeText("x") }
        val index = indexFor(listOf(1 to "사과 문서"))
        val coordinator = LocalRagCoordinator(
            textStore = FakeTextStore(emptyMap()),
            pdfIndexSource = FakeIndexSource(mapOf(pdfPath.canonicalPath to index)),
        )
        val result = coordinator.prepare(
            "req-dup",
            "사과",
            listOf(
                record(pdfPath.canonicalPath),
                record(pdfPath.canonicalPath),
            ),
        )
        assertTrue(result is LocalRagPreparationResult.Prepared)
        val docIds = (result as LocalRagPreparationResult.Prepared).selections.map { it.chunk.docId }.distinct()
        assertEquals(1, docIds.size)
    }

    // ------------------------------------------------------------------ D5

    @Test
    fun `two relevant pdfs are both represented in compare`() = runTest {
        val dir = Files.createTempDirectory("fusion-rag").toFile()
        val pathA = File(dir, "a.pdf").apply { writeText("x") }
        val pathB = File(dir, "b.pdf").apply { writeText("x") }
        val indexA = indexFor((1..20).map { it to "주장 A: 사과 가격이 오른다. 근거 $it" })
        val indexB = indexFor((1..20).map { it to "주장 B: 사과 가격이 내린다. 근거 $it" })
        val coordinator = LocalRagCoordinator(
            textStore = FakeTextStore(emptyMap()),
            pdfIndexSource = FakeIndexSource(
                mapOf(pathA.canonicalPath to indexA, pathB.canonicalPath to indexB),
            ),
        )
        val result = coordinator.prepare(
            "req-compare",
            "두 문서의 사과 가격 주장을 비교해줘",
            listOf(record(pathA.canonicalPath, "a.pdf"), record(pathB.canonicalPath, "b.pdf")),
        )
        assertTrue(result is LocalRagPreparationResult.Prepared)
        val selections = (result as LocalRagPreparationResult.Prepared).selections
        val docs = selections.map { it.source.displayName }.toSet()
        assertTrue("both relevant docs must appear: $docs", docs.containsAll(setOf("a.pdf", "b.pdf")))
    }

    @Test
    fun `one small pdf cannot monopolize compare when another relevant exists`() = runTest {
        val dir = Files.createTempDirectory("fusion-rag").toFile()
        val pathA = File(dir, "big.pdf").apply { writeText("x") }
        val pathB = File(dir, "small.md").apply { writeText("x") }
        val bigIndex = indexFor((1..60).map { it to "공통 주제: 사과 시장 분석 내용이 계속된다. 항목 $it" })
        val coordinator = LocalRagCoordinator(
            textStore = FakeTextStore(mapOf(pathB.canonicalPath to "사과 시장 분석 다른 견해")),
            pdfIndexSource = FakeIndexSource(mapOf(pathA.canonicalPath to bigIndex)),
        )
        val result = coordinator.prepare(
            "req-monopoly",
            "사과 시장 두 자료 비교",
            listOf(record(pathA.canonicalPath, "big.pdf"), record(pathB.canonicalPath, "small.md", "text/markdown")),
        )
        assertTrue(result is LocalRagPreparationResult.Prepared)
        val selections = (result as LocalRagPreparationResult.Prepared).selections
        val names = selections.map { it.source.displayName }
        assertTrue("md doc must be represented: $names", names.contains("small.md"))
        // The giant PDF may keep at most MAX_FINAL_SELECTIONS_PER_DOC slots
        // while the other relevant document exists; the final boundary is
        // still FINAL_TOP_K (5) at most.
        assertTrue(selections.size <= LocalRagBounds.FINAL_TOP_K)
        assertEquals(1, names.count { it == "small.md" })
        assertTrue(names.count { it == "big.pdf" } <= LocalRagBounds.MAX_FINAL_SELECTIONS_PER_DOC)
    }

    @Test
    fun `irrelevant second document is never forced into compare`() = runTest {
        val dir = Files.createTempDirectory("fusion-rag").toFile()
        val pathA = File(dir, "relevant.pdf").apply { writeText("x") }
        val pathB = File(dir, "irrelevant.pdf").apply { writeText("x") }
        val indexA = indexFor(listOf(1 to "사과 농장 수확량 보고서 내용입니다."))
        val indexB = indexFor(listOf(1 to "코스모스 꽃 재배 일기 내용입니다."))
        val coordinator = LocalRagCoordinator(
            textStore = FakeTextStore(emptyMap()),
            pdfIndexSource = FakeIndexSource(
                mapOf(pathA.canonicalPath to indexA, pathB.canonicalPath to indexB),
            ),
        )
        val result = coordinator.prepare(
            "req-notforce",
            "사과 수확량을 두 문서로 비교해줘",
            listOf(record(pathA.canonicalPath, "relevant.pdf"), record(pathB.canonicalPath, "irrelevant.pdf")),
        )
        assertTrue(result is LocalRagPreparationResult.Prepared)
        val selections = (result as LocalRagPreparationResult.Prepared).selections
        val names = selections.map { it.source.displayName }
        assertTrue(names.contains("relevant.pdf"))
        // The irrelevant doc may appear only if it had positive signal;
        // assert it never crowds out the relevant one.
        if (names.contains("irrelevant.pdf")) {
            assertTrue(selections.first { it.source.displayName == "relevant.pdf" }.rank <
                selections.first { it.source.displayName == "irrelevant.pdf" }.rank)
        } else {
            assertTrue("single relevant doc allowed all slots", names.all { it == "relevant.pdf" })
        }
    }

    // ------------------------------------------------------------------ E

    @Test
    fun `unchanged selected page validates against source`() = runTest {
        val dir = Files.createTempDirectory("fusion-rag").toFile()
        val path = File(dir, "paper.pdf").apply { writeText("x") }
        val index = indexFor(listOf(1 to "사과 페이지", 2 to "배 페이지", 3 to "포도 페이지"))
        val live = mutableMapOf(path.canonicalPath to mapOf(1 to "사과 페이지", 2 to "배 페이지", 3 to "포도 페이지"))
        val source = FakeIndexSource(mapOf(path.canonicalPath to index), livePages = live)
        val coordinator = LocalRagCoordinator(FakeTextStore(emptyMap()), pdfIndexSource = source)
        val prepared = coordinator.prepare("req-v", "사과", listOf(record(path.canonicalPath)))
            as LocalRagPreparationResult.Prepared
        assertTrue(coordinator.validate(prepared.snapshot, listOf(record(path.canonicalPath))))
        // Only the selected page(s) were re-opened, not the whole document.
        assertTrue(source.openCalls <= 1)
    }

    @Test
    fun `selected page changed - fail closed`() = runTest {
        val dir = Files.createTempDirectory("fusion-rag").toFile()
        val path = File(dir, "paper.pdf").apply { writeText("x") }
        val index = indexFor(listOf(1 to "사과 페이지", 2 to "배 페이지"))
        val live = mutableMapOf(path.canonicalPath to mapOf(1 to "사과 페이지", 2 to "배 페이지"))
        val source = FakeIndexSource(mapOf(path.canonicalPath to index), livePages = live)
        val coordinator = LocalRagCoordinator(FakeTextStore(emptyMap()), pdfIndexSource = source)
        val prepared = coordinator.prepare("req-v2", "사과", listOf(record(path.canonicalPath)))
            as LocalRagPreparationResult.Prepared
        // The source PDF's selected page changed.
        live[path.canonicalPath] = mapOf(1 to "완전히 다른 내용", 2 to "배 페이지")
        assertFalse(coordinator.validate(prepared.snapshot, listOf(record(path.canonicalPath))))
    }

    @Test
    fun `pdf removed - fail closed`() = runTest {
        val dir = Files.createTempDirectory("fusion-rag").toFile()
        val path = File(dir, "paper.pdf").apply { writeText("x") }
        val index = indexFor(listOf(1 to "사과 페이지"))
        val source = FakeIndexSource(mapOf(path.canonicalPath to index), livePages = mutableMapOf(path.canonicalPath to mapOf(1 to "사과 페이지")))
        val coordinator = LocalRagCoordinator(FakeTextStore(emptyMap()), pdfIndexSource = source)
        val prepared = coordinator.prepare("req-v3", "사과", listOf(record(path.canonicalPath)))
            as LocalRagPreparationResult.Prepared
        source.livePages.remove(path.canonicalPath)
        assertFalse(coordinator.validate(prepared.snapshot, listOf(record(path.canonicalPath))))
    }

    @Test
    fun `stale cache cannot authorize generation gate`() = runTest {
        val dir = Files.createTempDirectory("fusion-rag").toFile()
        val path = File(dir, "paper.pdf").apply { writeText("x") }
        val index = indexFor(listOf(1 to "사과 페이지"))
        val live = mutableMapOf(path.canonicalPath to mapOf(1 to "사과 페이지"))
        val source = FakeIndexSource(mapOf(path.canonicalPath to index), livePages = live)
        val coordinator = LocalRagCoordinator(FakeTextStore(emptyMap()), pdfIndexSource = source)
        val attachments = listOf(record(path.canonicalPath))
        val prepared = coordinator.prepare("req-gate", "사과", attachments)
        assertTrue(prepared is LocalRagPreparationResult.Prepared)
        assertTrue(selectRagForGeneration(prepared, attachments, coordinator::validate) != null)
        live[path.canonicalPath] = mapOf(1 to "변경된 내용")
        assertNull(selectRagForGeneration(prepared, attachments, coordinator::validate))
    }

    @Test
    fun `frozen request bounds are unchanged by pdf support`() {
        assertEquals(8, LocalRagBounds.MAX_DOCS_PER_REQUEST)
        assertEquals(6_000, LocalRagBounds.MAX_PROMPT_EXCERPT_CHARS)
        assertEquals(5, LocalRagBounds.FINAL_TOP_K)
        assertEquals(32_000, LocalRagBounds.MAX_DOC_CHARS)
    }
}