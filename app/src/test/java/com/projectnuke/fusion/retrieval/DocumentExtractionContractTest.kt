package com.projectnuke.fusion.retrieval

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Phase A contract tests: unified local document extraction contracts.
// Plain text keeps the exact existing semantics; typed statuses never lie.
class DocumentExtractionContractTest {

    private fun textDoc(text: String): ExtractedLocalDocument = ExtractedLocalDocument(
        kind = LocalDocumentKind.PLAIN_TEXT,
        pageCount = 1,
        textPageCount = if (text.isBlank()) 0 else 1,
        imageOnlyPageCount = 0,
        imageElementCount = 0,
        totalExtractedChars = text.length,
        pages = listOf(
            ExtractedDocumentPage(
                pageNumber = 1,
                text = text,
                textCharCount = text.length,
                contentHashHex = "h",
                hasImages = false,
                imageElementCount = 0,
            ),
        ),
    )

    @Test
    fun `plain text stays one logical document page - no fake pdf page`() {
        val doc = textDoc("사과 보고서 내용")
        assertEquals(LocalDocumentKind.PLAIN_TEXT, doc.kind)
        assertEquals(1, doc.pageCount)
        assertEquals(1, doc.pages.size)
        assertEquals(1, doc.pages[0].pageNumber)
        assertTrue(!doc.isPdf)
    }

    @Test
    fun `pdf pages are 1-based`() {
        ExtractedDocumentPage(1, "a", 1, "h", false, 0)
        ExtractedDocumentPage(256, "a", 1, "h", false, 0)
        try {
            ExtractedDocumentPage(0, "a", 1, "h", false, 0)
            fail("0-based page must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            ExtractedDocumentPage(-1, "a", 1, "h", false, 0)
            fail("negative page must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `kind policy routes pdf by mime and extension`() {
        assertTrue(LocalDocumentKindPolicy.isPdf("application/pdf", "paper.pdf"))
        assertTrue(LocalDocumentKindPolicy.isPdf(null, "paper.PDF"))
        assertTrue(LocalDocumentKindPolicy.isPdf("APPlication/PdF", "x"))
        assertTrue(!LocalDocumentKindPolicy.isPdf("text/plain", "paper.pdf".substringBeforeLast('.') + ".txt"))
        assertTrue(!LocalDocumentKindPolicy.isPdf("text/plain", "notes.md"))
        assertTrue(!LocalDocumentKindPolicy.isPdf("application/pdf".replace("pdf", "doc"), "notes.md"))
    }

    @Test
    fun `registry routes text to text extractor and unknown to none`() {
        val registry = LocalDocumentExtractorRegistry(textExtractor = PlainTextDocumentExtractor())
        assertTrue(registry.extractorFor("text/plain", "a.txt") is PlainTextDocumentExtractor)
        assertTrue(registry.extractorFor("application/json", "b.json") is PlainTextDocumentExtractor)
        assertNull(registry.extractorFor("application/pdf", "b.pdf"))
        assertNull(registry.extractorFor("image/png", "c.png"))
        assertNull(registry.extractorFor("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "d.docx"))
    }

    @Test
    fun `plain text extractor preserves 32000 char contract`() = runTest {
        val texts = mutableMapOf<String, String>()
        val extractor = PlainTextDocumentExtractor { path, maxChars ->
            texts[path]?.take(maxChars)
        }
        val long = "가".repeat(40_000)
        texts["/doc/long.txt"] = long
        val result = extractor.extract("/doc/long.txt", "text/plain") as DocumentExtractionResult.Ready
        assertEquals(LocalDocumentKind.PLAIN_TEXT, result.document.kind)
        assertEquals(LocalRagBounds.MAX_DOC_CHARS, result.document.totalExtractedChars)
        assertEquals(1, result.document.pages.size)
        assertEquals(LocalRagBounds.MAX_DOC_CHARS, result.document.pages[0].text.length)
    }

    @Test
    fun `plain text extractor fails closed on unreadable source`() = runTest {
        val extractor = PlainTextDocumentExtractor { _, _ -> null }
        val result = extractor.extract("/doc/missing.txt", "text/plain")
        assertEquals(DocumentExtractionStatus.UNREADABLE, (result as DocumentExtractionResult.NotReady).status)
        val blank = extractor.extract("", "text/plain")
        assertEquals(DocumentExtractionStatus.UNREADABLE, (blank as DocumentExtractionResult.NotReady).status)
    }

    @Test
    fun `status family is typed and bounded`() {
        val expected = setOf(
            DocumentExtractionStatus.READY,
            DocumentExtractionStatus.NO_TEXT_LAYER,
            DocumentExtractionStatus.IMAGE_ONLY_NO_OCR,
            DocumentExtractionStatus.PASSWORD_PROTECTED,
            DocumentExtractionStatus.TOO_MANY_PAGES,
            DocumentExtractionStatus.TOO_MUCH_TEXT,
            DocumentExtractionStatus.TOO_LARGE_FILE,
            DocumentExtractionStatus.UNREADABLE,
            DocumentExtractionStatus.UNSUPPORTED,
        )
        assertEquals(expected, DocumentExtractionStatus.entries.toSet())
    }

    @Test
    fun `pdf bounds are explicit product constants`() {
        assertEquals(32L * 1024 * 1024, PdfDocumentBounds.MAX_PDF_FILE_BYTES)
        assertEquals(256, PdfDocumentBounds.MAX_PDF_PAGES)
        assertEquals(512_000, PdfDocumentBounds.MAX_PDF_EXTRACTED_CHARS)
        assertEquals(32_000, PdfDocumentBounds.MAX_PDF_PAGE_CHARS)
    }

    @Test
    fun `status policy copy never leaks implementation jargon`() {
        val forbidden = listOf("SandboxedPdfLoader", "PdfRendererPreV", "sidecar", "chunk", "ranker", "PdfPageTextContent")
        for (status in DocumentExtractionStatus.entries) {
            val message = DocumentStatusPolicy.statusMessage(status)
            assertTrue(message.isNotBlank())
            for (word in forbidden) {
                assertTrue("$message must not contain $word", !message.contains(word))
            }
        }
    }

    @Test
    fun `status policy carries required human copy`() {
        assertEquals(
            "이 PDF는 스캔 이미지로 되어 있어 현재 텍스트를 읽을 수 없습니다.",
            DocumentStatusPolicy.statusMessage(DocumentExtractionStatus.IMAGE_ONLY_NO_OCR),
        )
        assertEquals(
            "암호가 걸린 PDF는 아직 읽을 수 없습니다.",
            DocumentStatusPolicy.statusMessage(DocumentExtractionStatus.PASSWORD_PROTECTED),
        )
        assertEquals(
            "이 PDF는 현재 지원하는 페이지 수를 초과했습니다.",
            DocumentStatusPolicy.statusMessage(DocumentExtractionStatus.TOO_MANY_PAGES),
        )
        assertEquals(
            "이 PDF의 텍스트가 현재 문서 처리 한도를 초과했습니다.",
            DocumentStatusPolicy.statusMessage(DocumentExtractionStatus.TOO_MUCH_TEXT),
        )
    }

    @Test
    fun `document summary is deterministic`() {
        assertEquals("텍스트 사용 가능", DocumentStatusPolicy.documentSummary(textDoc("내용")))
        assertEquals(
            "42쪽 중 36쪽의 텍스트를 사용할 수 있어요.",
            DocumentStatusPolicy.documentSummary(
                ExtractedLocalDocument(
                    kind = LocalDocumentKind.PDF_TEXT_LAYER,
                    pageCount = 42,
                    textPageCount = 36,
                    imageOnlyPageCount = 6,
                    imageElementCount = 6,
                    totalExtractedChars = 10_000,
                    pages = emptyList(),
                ),
            ),
        )
        assertEquals("32쪽", DocumentStatusPolicy.pageLabel(32))
        assertEquals("paper.pdf · 32쪽", DocumentStatusPolicy.pageProvenanceLabel("paper.pdf", 32))
        assertEquals(
            "PDF · 42쪽 · 이미지 전용 6쪽",
            DocumentStatusPolicy.inventoryPdfLabel(
                ExtractedLocalDocument(
                    kind = LocalDocumentKind.PDF_TEXT_LAYER,
                    pageCount = 42,
                    textPageCount = 36,
                    imageOnlyPageCount = 6,
                    imageElementCount = 9,
                    totalExtractedChars = 10_000,
                    pages = emptyList(),
                ),
            ),
        )
        assertEquals(
            "PDF · 스캔 이미지 · OCR 필요",
            DocumentStatusPolicy.inventoryPdfLabel(
                ExtractedLocalDocument(
                    kind = LocalDocumentKind.PDF_TEXT_LAYER,
                    pageCount = 3,
                    textPageCount = 0,
                    imageOnlyPageCount = 3,
                    imageElementCount = 3,
                    totalExtractedChars = 0,
                    pages = emptyList(),
                ),
            ),
        )
        assertEquals(
            "PDF · 42쪽 · 이미지 전용 6쪽",
            DocumentStatusPolicy.inventoryPdfLabel(
                DocumentIndexStatusSnapshot(
                    sidecarFormatVersion = DocumentIndexContract.STATUS_SIDECAR_FORMAT_VERSION,
                    documentIndexVersion = DocumentIndexContract.DOCUMENT_INDEX_VERSION,
                    kind = LocalDocumentKind.PDF_TEXT_LAYER,
                    pageCount = 42,
                    textPageCount = 36,
                    imageOnlyPageCount = 6,
                    imageElementCount = 9,
                    totalExtractedChars = 10_000,
                    sourceSizeBytes = 100,
                    sourceLastModifiedMillis = 200,
                    indexFileSizeBytes = 300,
                    indexFileLastModifiedMillis = 400,
                ),
            ),
        )
    }
}
