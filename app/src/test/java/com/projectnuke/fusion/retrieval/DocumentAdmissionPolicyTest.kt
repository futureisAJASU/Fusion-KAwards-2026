package com.projectnuke.fusion.retrieval

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase F admission seam: picker / share / project flows share ONE typed
// admission rule. PDFs require READY extraction; text keeps the strict
// handoff contract; nothing unusable is ever adopted as readable.
class DocumentAdmissionPolicyTest {

    private fun tempFile(name: String, content: String): File {
        val dir = Files.createTempDirectory("fusion-admission").toFile()
        return File(dir, name).apply { writeText(content) }
    }

    private fun pdfAdmission(
        status: DocumentExtractionStatus? = DocumentExtractionStatus.READY,
    ): suspend (String, String?) -> DocumentAdmissionPolicy.DocumentAdmissionResult = { _, _ ->
        if (status == DocumentExtractionStatus.READY) {
            DocumentAdmissionPolicy.DocumentAdmissionResult.Accepted
        } else {
            DocumentAdmissionPolicy.DocumentAdmissionResult.Rejected(
                status ?: DocumentExtractionStatus.UNSUPPORTED,
            )
        }
    }

    @Test
    fun `readable pdf is admitted`() = runTest {
        val file = tempFile("paper.pdf", "pdf")
        val result = DocumentAdmissionPolicy.admit(
            file.path, "application/pdf", "paper.pdf",
            pdfAdmission = pdfAdmission(DocumentExtractionStatus.READY),
            strictTextLimit = false,
        )
        assertTrue(result is DocumentAdmissionPolicy.DocumentAdmissionResult.Accepted)
    }

    @Test
    fun `scanned pdf rejects as image only no ocr`() = runTest {
        val file = tempFile("scanned.pdf", "pdf")
        val result = DocumentAdmissionPolicy.admit(
            file.path, "application/pdf", "scanned.pdf",
            pdfAdmission = pdfAdmission(DocumentExtractionStatus.IMAGE_ONLY_NO_OCR),
            strictTextLimit = false,
        )
        assertEquals(
            DocumentExtractionStatus.IMAGE_ONLY_NO_OCR,
            (result as DocumentAdmissionPolicy.DocumentAdmissionResult.Rejected).status,
        )
    }

    @Test
    fun `password pdf rejects truthfully`() = runTest {
        val file = tempFile("locked.pdf", "pdf")
        val result = DocumentAdmissionPolicy.admit(
            file.path, "application/pdf", "locked.pdf",
            pdfAdmission = pdfAdmission(DocumentExtractionStatus.PASSWORD_PROTECTED),
            strictTextLimit = false,
        )
        assertEquals(
            DocumentExtractionStatus.PASSWORD_PROTECTED,
            (result as DocumentAdmissionPolicy.DocumentAdmissionResult.Rejected).status,
        )
    }

    @Test
    fun `pdf with no wiring is unsupported - no unsafe fallback`() = runTest {
        val file = tempFile("paper.pdf", "pdf")
        val result = DocumentAdmissionPolicy.admit(
            file.path, "application/pdf", "paper.pdf",
            pdfAdmission = null,
            strictTextLimit = false,
        )
        assertEquals(
            DocumentExtractionStatus.UNSUPPORTED,
            (result as DocumentAdmissionPolicy.DocumentAdmissionResult.Rejected).status,
        )
    }

    @Test
    fun `strict text limit rejects oversized shared text`() = runTest {
        val file = tempFile("big.txt", "x".repeat(LocalRagBounds.MAX_DOC_CHARS + 1))
        val result = DocumentAdmissionPolicy.admit(
            file.path, "text/plain", "big.txt",
            pdfAdmission = null,
            strictTextLimit = true,
        )
        assertEquals(
            DocumentExtractionStatus.TOO_MUCH_TEXT,
            (result as DocumentAdmissionPolicy.DocumentAdmissionResult.Rejected).status,
        )
    }

    @Test
    fun `strict text limit accepts bounded shared text`() = runTest {
        val file = tempFile("ok.txt", "정상 크기")
        val result = DocumentAdmissionPolicy.admit(
            file.path, "text/plain", "ok.txt",
            pdfAdmission = null,
            strictTextLimit = true,
        )
        assertTrue(result is DocumentAdmissionPolicy.DocumentAdmissionResult.Accepted)
    }

    @Test
    fun `picker mode keeps the existing relaxed text behavior`() = runTest {
        // 40k chars exceed the staged 32k contract but the picker's existing
        // semantics read bounded text at RAG time; only PDFs are gated here.
        val file = tempFile("long.txt", "x".repeat(40_000))
        val result = DocumentAdmissionPolicy.admit(
            file.path, "text/plain", "long.txt",
            pdfAdmission = null,
            strictTextLimit = false,
        )
        assertTrue(result is DocumentAdmissionPolicy.DocumentAdmissionResult.Accepted)
    }

    @Test
    fun `unsupported kinds reject with typed status`() = runTest {
        val file = tempFile("doc.docx", "office")
        val result = DocumentAdmissionPolicy.admit(
            file.path, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "doc.docx",
            pdfAdmission = null,
            strictTextLimit = false,
        )
        assertEquals(
            DocumentExtractionStatus.UNSUPPORTED,
            (result as DocumentAdmissionPolicy.DocumentAdmissionResult.Rejected).status,
        )
    }
}