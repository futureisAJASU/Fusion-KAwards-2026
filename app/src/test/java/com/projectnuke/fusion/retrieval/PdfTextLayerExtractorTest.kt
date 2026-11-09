package com.projectnuke.fusion.retrieval

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase B extraction matrix: the production PdfTextLayerExtractor pipeline
// driven through the fake engine. Boundaries are exact; silent truncation
// is impossible; every rejection is typed and truthful.
class PdfTextLayerExtractorTest {

    private fun tempDir(): File = Files.createTempDirectory("fusion-pdf-test").toFile()

    private suspend fun extractWith(
        engine: FakePdfTextLayerEngine,
        path: String,
    ): Pair<DocumentExtractionResult, PdfTextLayerExtractor> {
        val extractor = PdfTextLayerExtractor(engine)
        return extractor.extract(path, "application/pdf") to extractor
    }

    private fun ready(result: DocumentExtractionResult): ExtractedLocalDocument {
        assertTrue("expected READY but was ${(result as? DocumentExtractionResult.NotReady)?.status}", result is DocumentExtractionResult.Ready)
        return (result as DocumentExtractionResult.Ready).document
    }

    private fun status(result: DocumentExtractionResult): DocumentExtractionStatus =
        (result as DocumentExtractionResult.NotReady).status

    @Test
    fun `single page text pdf extracts with real page 1`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/one.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        engine.installTextPages(path, listOf("첫 페이지의 본문 내용"))
        val doc = ready(extractWith(engine, path).first)
        assertEquals(LocalDocumentKind.PDF_TEXT_LAYER, doc.kind)
        assertEquals(1, doc.pageCount)
        assertEquals(1, doc.textPageCount)
        assertEquals(1, doc.pages.size)
        assertEquals(1, doc.pages[0].pageNumber)
        assertEquals("첫 페이지의 본문 내용", doc.pages[0].text)
        assertTrue(doc.pages[0].contentHashHex.isNotBlank())
    }

    @Test
    fun `multi page text pdf preserves page order and identity`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/multi.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        engine.installTextPages(path, listOf("1장 첫머리", "2장 중간", "3장 끝맺음"))
        val doc = ready(extractWith(engine, path).first)
        assertEquals(3, doc.pageCount)
        assertEquals(3, doc.textPageCount)
        assertEquals(listOf(1, 2, 3), doc.pages.map { it.pageNumber })
        assertEquals(listOf("1장 첫머리", "2장 중간", "3장 끝맺음"), doc.pages.map { it.text })
        assertEquals(2, doc.pages[1].pageNumber)
        assertEquals("2장 중간", doc.pages[1].text)
    }

    @Test
    fun `blank pages are skipped without lying about text pages`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/blank.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        engine.install(
            path,
            pages = listOf(
                FakePdfTextLayerEngine.FakePage("내용"),
                FakePdfTextLayerEngine.FakePage("", 0),
                FakePdfTextLayerEngine.FakePage("", 0),
                FakePdfTextLayerEngine.FakePage("다시 내용"),
            ),
        )
        val doc = ready(extractWith(engine, path).first)
        assertEquals(4, doc.pageCount)
        assertEquals(2, doc.textPageCount)
        assertEquals(listOf(1, 4), doc.pages.map { it.pageNumber })
    }

    @Test
    fun `text plus images page preserves both truths`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/mixed.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        engine.install(
            path,
            pages = listOf(
                FakePdfTextLayerEngine.FakePage("텍스트가 있는 페이지", imageCount = 2),
                FakePdfTextLayerEngine.FakePage("", imageCount = 1),
                FakePdfTextLayerEngine.FakePage("끝 페이지", imageCount = 0),
            ),
        )
        val doc = ready(extractWith(engine, path).first)
        assertEquals(3, doc.pageCount)
        assertEquals(2, doc.textPageCount)
        assertEquals(1, doc.imageOnlyPageCount)
        assertEquals(3, doc.imageElementCount)
        assertEquals(1, doc.pages[0].pageNumber)
        assertTrue(doc.pages[0].hasImages)
        assertTrue(doc.pages[1].text.isEmpty())
        assertTrue(doc.pages[1].hasImages)
    }

    @Test
    fun `image only pdf classifies as image only no ocr`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/scanned.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        engine.install(
            path,
            pages = listOf(
                FakePdfTextLayerEngine.FakePage("", imageCount = 1),
                FakePdfTextLayerEngine.FakePage("", imageCount = 1),
            ),
        )
        assertEquals(DocumentExtractionStatus.IMAGE_ONLY_NO_OCR, status(extractWith(engine, path).first))
    }

    @Test
    fun `no text and no images classifies as no text layer`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/empty.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        engine.install(path, pages = listOf(FakePdfTextLayerEngine.FakePage("", 0)))
        assertEquals(DocumentExtractionStatus.NO_TEXT_LAYER, status(extractWith(engine, path).first))
    }

    @Test
    fun `password protected pdf classifies as password protected`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/locked.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        engine.install(path, pages = listOf(FakePdfTextLayerEngine.FakePage("비밀")), password = true)
        assertEquals(DocumentExtractionStatus.PASSWORD_PROTECTED, status(extractWith(engine, path).first))
    }

    @Test
    fun `malformed pdf classifies as unreadable`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/bad.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        engine.install(path, pages = emptyList(), unreadable = true)
        assertEquals(DocumentExtractionStatus.UNREADABLE, status(extractWith(engine, path).first))
    }

    @Test
    fun `missing file classifies as unreadable never extracts`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/missing.pdf"
        val engine = FakePdfTextLayerEngine()
        assertEquals(DocumentExtractionStatus.UNREADABLE, status(extractWith(engine, path).first))
    }

    @Test
    fun `page limit exact boundary accepts 256 and rejects 257`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/pages.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        val okPages = List(256) { "페이지 ${it + 1} 내용" }
        engine.installTextPages(path, okPages)
        val doc = ready(extractWith(engine, path).first)
        assertEquals(256, doc.pageCount)
        assertEquals(256, doc.textPageCount)

        val overPath = "${dir.absolutePath}/pages-over.pdf"
        engine.touchFile(overPath)
        engine.installTextPages(overPath, okPages + "257페이지 내용")
        assertEquals(DocumentExtractionStatus.TOO_MANY_PAGES, status(extractWith(engine, overPath).first))
    }

    @Test
    fun `total char limit exact boundary rejects only on overflow`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/chars.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        val page = "가".repeat(32_000)
        engine.installTextPages(path, List(16) { page })
        val doc = ready(extractWith(engine, path).first)
        assertEquals(512_000, doc.totalExtractedChars)
        assertEquals(16, doc.textPageCount)

        val overPath = "${dir.absolutePath}/chars-over.pdf"
        engine.touchFile(overPath)
        engine.installTextPages(overPath, List(16) { page } + "초과 문자")
        assertEquals(DocumentExtractionStatus.TOO_MUCH_TEXT, status(extractWith(engine, overPath).first))
    }

    @Test
    fun `page char limit rejects an oversized single page without truncation`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/longpage.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        engine.installTextPages(path, listOf("가".repeat(32_001)))
        assertEquals(DocumentExtractionStatus.TOO_MUCH_TEXT, status(extractWith(engine, path).first))
    }

    @Test
    fun `file size bound rejects oversized file before any extraction`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/huge.pdf"
        val engine = FakePdfTextLayerEngine()
        RandomAccessFile(path, "rw").use { raf ->
            raf.setLength(PdfDocumentBounds.MAX_PDF_FILE_BYTES + 1)
        }
        assertEquals(DocumentExtractionStatus.TOO_LARGE_FILE, status(extractWith(engine, path).first))
    }

    @Test
    fun `extraction is never silently truncated`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/full.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        val texts = List(8) { "문장 ${it + 1}: ".repeat(60).trim() }
        engine.installTextPages(path, texts)
        val doc = ready(extractWith(engine, path).first)
        assertEquals(texts.size, doc.pages.size)
        assertEquals(texts, doc.pages.map { it.text })
        // Total extracted chars is the sum of page texts (join separators are
        // presentation, not extracted content).
        assertEquals(texts.sumOf { it.length }, doc.totalExtractedChars)
    }

    @Test
    fun `extraction failure leaves no partial result claim`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/partial.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        // Page 2 raises a typed engine failure mid-document.
        val failing = object : PdfTextLayerEngine {
            private inner class Stub(override val pageCount: Int) : PdfTextLayerDocument {
                override suspend fun pageTextLayer(pageNumber: Int): PdfPageTextLayer? {
                    if (pageNumber == 2) {
                        throw PdfEngineStatusException(DocumentExtractionStatus.UNREADABLE)
                    }
                    return PdfPageTextLayer("1페이지 내용", 1, 0)
                }

                override fun close() = Unit
            }

            override suspend fun open(sourcePath: String): PdfTextLayerDocument = Stub(3)
        }
        val result = PdfTextLayerExtractor(failing).extract(path, "application/pdf")
        assertEquals(DocumentExtractionStatus.UNREADABLE, status(result))
    }

    @Test
    fun `extracted pdf text is conservatively normalized before hashing`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/normalized.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        // CRLF / control garbage / 4+ spaced artifacts are normalized; the
        // stored text and content hash reflect the NORMALIZED content.
        val raw = "첫 줄입니다.\u0000\u0001\r\n둘째 줄    끝"
        engine.installTextPages(path, listOf(raw))
        val doc = ready(extractWith(engine, path).first)
        assertEquals("첫 줄입니다.\n둘째 줄 끝", doc.pages[0].text)
        assertEquals("첫 줄입니다.\n둘째 줄 끝".length, doc.pages[0].textCharCount)
        assertEquals(
            LocalDocumentChunker.sha256Hex("첫 줄입니다.\n둘째 줄 끝"),
            doc.pages[0].contentHashHex,
        )
    }

    @Test
    fun `normalization never yields a truncated document under strict v1 policy`() = runTest {
        val dir = tempDir()
        val path = "${dir.absolutePath}/strict.pdf"
        val engine = FakePdfTextLayerEngine()
        engine.touchFile(path)
        engine.installTextPages(path, listOf("일반 본문", "추가 본문"))
        val doc = ready(extractWith(engine, path).first)
        assertTrue(!doc.truncated)
        // Strict policy is structural: a truncated=true document cannot exist
        // in the v1 model.
        try {
            doc.copy(truncated = true)
            assertTrue("truncated=true must be structurally impossible", false)
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `readability states map typed statuses truthfully`() {
        assertEquals(
            PdfReadabilityState.PDF_SUPPORTED,
            PdfReadabilityPolicy.state(DocumentExtractionStatus.READY),
        )
        assertEquals(
            PdfReadabilityState.PDF_NO_TEXT,
            PdfReadabilityPolicy.state(DocumentExtractionStatus.NO_TEXT_LAYER),
        )
        assertEquals(
            PdfReadabilityState.PDF_NO_TEXT,
            PdfReadabilityPolicy.state(DocumentExtractionStatus.IMAGE_ONLY_NO_OCR),
        )
        assertEquals(
            PdfReadabilityState.PDF_UNREADABLE,
            PdfReadabilityPolicy.state(DocumentExtractionStatus.PASSWORD_PROTECTED),
        )
        assertEquals(
            PdfReadabilityState.PDF_UNREADABLE,
            PdfReadabilityPolicy.state(DocumentExtractionStatus.UNREADABLE),
        )
        assertEquals(
            PdfReadabilityState.PDF_TOO_LARGE_OR_COMPLEX,
            PdfReadabilityPolicy.state(DocumentExtractionStatus.TOO_MANY_PAGES),
        )
        assertEquals(
            PdfReadabilityState.PDF_TOO_LARGE_OR_COMPLEX,
            PdfReadabilityPolicy.state(DocumentExtractionStatus.TOO_MUCH_TEXT),
        )
        assertEquals(
            PdfReadabilityState.PDF_TOO_LARGE_OR_COMPLEX,
            PdfReadabilityPolicy.state(DocumentExtractionStatus.TOO_LARGE_FILE),
        )
    }

    @Test
    fun `readability user copy never claims ocr or parser jargon`() {
        for (state in PdfReadabilityState.entries) {
            val copy = PdfReadabilityPolicy.userCopy(state)
            assertTrue(copy.isNotBlank())
            assertTrue(!copy.contains("OCR") && !copy.contains("Parser"))
            assertTrue(!copy.contains("Sandboxed") && !copy.contains("PdfPage"))
        }
        assertEquals(
            "이 PDF에서는 읽을 수 있는 텍스트를 찾지 못했습니다.",
            PdfReadabilityPolicy.userCopy(PdfReadabilityState.PDF_NO_TEXT),
        )
    }
}