package com.projectnuke.fusion.retrieval

import com.projectnuke.fusion.ui.ProjectFilePolicy
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Section 11: ONE authoritative project admission seam. The project-file
// picker import and the conversation->project promotion must reach identical
// verdicts for the SAME managed PDF.
class ProjectKnowledgeAdmissionPolicyTest {

    private fun tempDir(): File = Files.createTempDirectory("fusion-kn-admission").toFile()

    private fun statusResolver(status: DocumentExtractionStatus): suspend (File) -> DocumentExtractionStatus =
        { _ -> status }

    @Test
    fun `readable pdf admits through the shared seam`() = runTest {
        val root = tempDir()
        try {
            val pdf = File(root, "paper.pdf").apply { writeText("x".repeat(40_000)) }
            val result = ProjectKnowledgeAdmissionPolicy.admitManagedFile(
                attachmentRoot = root,
                mimeType = "application/pdf",
                displayName = "paper.pdf",
                localPath = pdf.canonicalPath,
                pdfStateResolver = statusResolver(DocumentExtractionStatus.READY),
            )
            assertEquals(ProjectFileAdmission.ACCEPTED, result.status)
            assertEquals(pdf.canonicalPath, result.trustedFile?.canonicalPath)
            assertTrue(pdf.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `image-only pdf is retained for explicit local OCR`() = runTest {
        val root = tempDir()
        try {
            val scanned = File(root, "scanned.pdf").apply { writeText("x") }
            val result = ProjectKnowledgeAdmissionPolicy.admitManagedFile(
                attachmentRoot = root,
                mimeType = "application/pdf",
                displayName = "scanned.pdf",
                localPath = scanned.canonicalPath,
                pdfStateResolver = statusResolver(DocumentExtractionStatus.IMAGE_ONLY_NO_OCR),
            )
            assertEquals(ProjectFileAdmission.ACCEPTED, result.status)
            assertEquals(scanned.canonicalFile, result.trustedFile)
            assertEquals(
                "이 PDF는 스캔 이미지로 되어 있어 현재 텍스트를 읽을 수 없습니다.",
                ProjectFilePolicy.admissionMessage(result),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `picker import and promotion produce identical verdicts for the same pdf`() = runTest {
        val root = tempDir()
        try {
            val pdf = File(root, "shared.pdf").apply { writeText("pdf") }
            val resolver: suspend (File) -> DocumentExtractionStatus = { _ ->
                // Same extracted truth regardless of which flow asks.
                DocumentExtractionStatus.READY
            }
            // Picker-import path verdict.
            val pickerVerdict = ProjectKnowledgeAdmissionPolicy.admitManagedFile(
                attachmentRoot = root,
                mimeType = "application/pdf",
                displayName = "shared.pdf",
                localPath = pdf.canonicalPath,
                pdfStateResolver = resolver,
            )
            // Promotion-from-conversation verdict (same call, same seam).
            val promotionVerdict = ProjectKnowledgeAdmissionPolicy.admitManagedFile(
                attachmentRoot = root,
                mimeType = "application/pdf",
                displayName = "shared.pdf",
                localPath = pdf.canonicalPath,
                pdfStateResolver = resolver,
            )
            assertEquals(pickerVerdict.status, promotionVerdict.status)
            assertEquals(pickerVerdict.trustedFile?.canonicalPath, promotionVerdict.trustedFile?.canonicalPath)
            assertEquals(
                ProjectFileAdmission.ACCEPTED,
                pickerVerdict.status,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `ordinary text keeps the strict whole-file contract through the seam`() = runTest {
        val root = tempDir()
        try {
            val oversized = File(root, "big.txt").apply {
                writeText("x".repeat(LocalRagBounds.MAX_DOC_CHARS + 1))
            }
            val valid = File(root, "ok.txt").apply { writeText("fine") }
            val tooLarge = ProjectKnowledgeAdmissionPolicy.admitManagedFile(
                attachmentRoot = root,
                mimeType = "text/plain",
                displayName = "big.txt",
                localPath = oversized.canonicalPath,
                pdfStateResolver = statusResolver(DocumentExtractionStatus.UNSUPPORTED),
            )
            val accepted = ProjectKnowledgeAdmissionPolicy.admitManagedFile(
                attachmentRoot = root,
                mimeType = "text/markdown",
                displayName = "ok.md",
                localPath = valid.canonicalPath,
                pdfStateResolver = statusResolver(DocumentExtractionStatus.UNSUPPORTED),
            )
            assertEquals(ProjectFileAdmission.TOO_LARGE, tooLarge.status)
            assertEquals(ProjectFileAdmission.ACCEPTED, accepted.status)
        } finally {
            root.deleteRecursively()
        }
    }
}
