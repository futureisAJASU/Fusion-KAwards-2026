package com.projectnuke.fusion.ui

import com.projectnuke.fusion.retrieval.DocumentExtractionStatus
import com.projectnuke.fusion.retrieval.ProjectFileAdmission
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFilePolicyTest {
    @Test
    fun `managed promotion rejects oversized file and accepts valid file without copying`() {
        val root = Files.createTempDirectory("fusion-project-promotion").toFile()
        try {
            val oversized = root.resolve("history.txt").apply {
                writeText("x".repeat(com.projectnuke.fusion.retrieval.LocalRagBounds.MAX_DOC_CHARS + 1))
            }
            val valid = root.resolve("valid.txt").apply { writeText("valid") }

            val rejected = ProjectFilePolicy.admitManagedFile(
                root,
                "text/plain",
                oversized.name,
                oversized.absolutePath,
            )
            val accepted = ProjectFilePolicy.admitManagedFile(
                root,
                "text/plain",
                valid.name,
                valid.absolutePath,
            )

            assertEquals(ProjectFileAdmission.TOO_LARGE, rejected.status)
            assertEquals(ProjectFileAdmission.ACCEPTED, accepted.status)
            assertEquals(valid.canonicalPath, accepted.trustedFile?.canonicalPath)
            assertTrue(valid.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `pdf project admission follows document bounds not the text size rule`() {
        val root = Files.createTempDirectory("fusion-project-promotion").toFile()
        try {
            // A valid-ish 100-page-equivalent sized PDF text preview must NOT
            // be rejected by the 32,000-char WHOLE-FILE rule.
            val pdf = root.resolve("paper.pdf").apply {
                writeText("x".repeat(40_000))
            }
            val admitted = ProjectFilePolicy.admitManagedFile(
                root,
                "application/pdf",
                pdf.name,
                pdf.absolutePath,
                pdfStatus = DocumentExtractionStatus.READY,
            )
            assertEquals(ProjectFileAdmission.ACCEPTED, admitted.status)
            assertEquals(pdf.canonicalPath, admitted.trustedFile?.canonicalPath)
            assertEquals(DocumentExtractionStatus.READY, admitted.documentStatus)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `typed pdf statuses reject with truthful mapping`() {
        val root = Files.createTempDirectory("fusion-project-promotion").toFile()
        try {
            val scanned = root.resolve("scanned.pdf").apply { writeText("x") }
            val tooMany = root.resolve("huge.pdf").apply { writeText("x") }
            val locked = root.resolve("locked.pdf").apply { writeText("x") }

            val scannedResult = ProjectFilePolicy.admitManagedFile(
                root, "application/pdf", scanned.name, scanned.absolutePath,
                pdfStatus = DocumentExtractionStatus.IMAGE_ONLY_NO_OCR,
            )
            val pagesResult = ProjectFilePolicy.admitManagedFile(
                root, "application/pdf", tooMany.name, tooMany.absolutePath,
                pdfStatus = DocumentExtractionStatus.TOO_MANY_PAGES,
            )
            val lockedResult = ProjectFilePolicy.admitManagedFile(
                root, "application/pdf", locked.name, locked.absolutePath,
                pdfStatus = DocumentExtractionStatus.PASSWORD_PROTECTED,
            )

            assertEquals(ProjectFileAdmission.ACCEPTED, scannedResult.status)
            assertEquals(ProjectFileAdmission.TOO_LARGE, pagesResult.status)
            assertEquals(ProjectFileAdmission.UNREADABLE, lockedResult.status)
            assertEquals(scanned.canonicalFile, scannedResult.trustedFile)
            // Typed copy reaches the user.
            assertEquals(
                "이 PDF는 스캔 이미지로 되어 있어 현재 텍스트를 읽을 수 없습니다.",
                ProjectFilePolicy.admissionMessage(scannedResult),
            )
            assertEquals(
                "암호가 걸린 PDF는 아직 읽을 수 없습니다.",
                ProjectFilePolicy.admissionMessage(lockedResult),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `pdf without resolved status is never accepted`() {
        val root = Files.createTempDirectory("fusion-project-promotion").toFile()
        try {
            val pdf = root.resolve("unknown.pdf").apply { writeText("x") }
            val result = ProjectFilePolicy.admitManagedFile(
                root,
                "application/pdf",
                pdf.name,
                pdf.absolutePath,
            )
            assertEquals(ProjectFileAdmission.UNREADABLE, result.status)
            assertNull(result.trustedFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
