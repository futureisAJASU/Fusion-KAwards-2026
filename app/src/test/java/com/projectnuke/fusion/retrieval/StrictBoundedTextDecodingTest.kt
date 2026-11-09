package com.projectnuke.fusion.retrieval

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrictBoundedTextDecodingTest {

    @Test
    fun `valid ASCII is admitted and extracted consistently`() {
        withTempFile("notes.txt", "plain ASCII text".toByteArray()) { file ->
            assertEquals(ProjectFileAdmission.ACCEPTED, LocalRagInputPolicy.admitWholeFile(file))
            assertEquals("plain ASCII text", PlainTextDocumentExtractor.fileReader(file.path, 100))
            assertEquals(
                "plain ASCII text",
                FileLocalAttachmentTextStore().readText(file.path, 100),
            )
        }
    }

    @Test
    fun `valid Korean UTF-8 is admitted and extracted consistently`() {
        val text = "한국어 문서입니다."
        withTempFile("notes.txt", text.toByteArray(Charsets.UTF_8)) { file ->
            assertEquals(ProjectFileAdmission.ACCEPTED, LocalRagInputPolicy.admitWholeFile(file))
            assertEquals(text, PlainTextDocumentExtractor.fileReader(file.path, 100))
            assertEquals(text, FileLocalAttachmentTextStore().readText(file.path, 100))
        }
    }

    @Test
    fun `UTF-8 BOM is stripped from admission and extraction`() {
        val text = "문서 본문"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        withTempFile("bom.txt", bom + text.toByteArray(Charsets.UTF_8)) { file ->
            assertEquals(ProjectFileAdmission.ACCEPTED, LocalRagInputPolicy.admitWholeFile(file))
            assertEquals(text, PlainTextDocumentExtractor.fileReader(file.path, 100))
            assertEquals(text, FileLocalAttachmentTextStore().readText(file.path, 100))
        }
    }

    @Test
    fun `malformed UTF-8 fails closed for admission and extraction`() {
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)
        withTempFile("malformed.txt", malformed) { file ->
            assertEquals(ProjectFileAdmission.UNREADABLE, LocalRagInputPolicy.admitWholeFile(file))
            assertNull(PlainTextDocumentExtractor.fileReader(file.path, 100))
            assertNull(FileLocalAttachmentTextStore().readText(file.path, 100))
        }
    }

    @Test
    fun `strict document admission and extraction agree on malformed UTF-8`() = runTest {
        val malformed = byteArrayOf(0xE3.toByte(), 0x81.toByte())
        withTempFileSuspending("malformed.txt", malformed) { file ->
            val admission = DocumentAdmissionPolicy.admit(
                path = file.path,
                mimeType = "text/plain",
                fileName = file.name,
                pdfAdmission = null,
                strictTextLimit = true,
            )
            assertEquals(
                DocumentAdmissionPolicy.DocumentAdmissionResult.Rejected(
                    DocumentExtractionStatus.UNREADABLE,
                ),
                admission,
            )
            assertNull(PlainTextDocumentExtractor.fileReader(file.path, LocalRagBounds.MAX_DOC_CHARS))
        }
    }

    @Test
    fun `maximum character boundary remains exact`() {
        val text = "a".repeat(LocalRagBounds.MAX_DOC_CHARS)
        withTempFile("max.txt", text.toByteArray(Charsets.UTF_8)) { file ->
            assertEquals(ProjectFileAdmission.ACCEPTED, LocalRagInputPolicy.admitWholeFile(file))
            assertEquals(text, PlainTextDocumentExtractor.fileReader(file.path, LocalRagBounds.MAX_DOC_CHARS))
        }
    }

    @Test
    fun `oversized document remains too large while extraction stays bounded`() {
        val text = "a".repeat(LocalRagBounds.MAX_DOC_CHARS + 1)
        withTempFile("oversized.txt", text.toByteArray(Charsets.UTF_8)) { file ->
            assertEquals(ProjectFileAdmission.TOO_LARGE, LocalRagInputPolicy.admitWholeFile(file))
            assertEquals(
                "a".repeat(LocalRagBounds.MAX_DOC_CHARS),
                PlainTextDocumentExtractor.fileReader(file.path, LocalRagBounds.MAX_DOC_CHARS),
            )
        }
    }

    @Test
    fun `empty document remains empty`() {
        withTempFile("empty.txt", ByteArray(0)) { file ->
            assertEquals(ProjectFileAdmission.EMPTY, LocalRagInputPolicy.admitWholeFile(file))
            assertNull(PlainTextDocumentExtractor.fileReader(file.path, 100))
            assertNull(FileLocalAttachmentTextStore().readText(file.path, 100))
        }
    }

    @Test
    fun `binary garbage does not become replacement-character text`() {
        val binary = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte(), 0x00)
        withTempFile("binary.bin", binary) { file ->
            assertEquals(ProjectFileAdmission.UNREADABLE, LocalRagInputPolicy.admitWholeFile(file))
            assertNull(PlainTextDocumentExtractor.fileReader(file.path, 100))
            assertNull(FileLocalAttachmentTextStore().readText(file.path, 100))
        }
    }

    private fun withTempFile(name: String, bytes: ByteArray, block: (File) -> Unit) {
        val directory = Files.createTempDirectory("strict-text-decoding").toFile()
        val file = File(directory, name)
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun withTempFileSuspending(
        name: String,
        bytes: ByteArray,
        block: suspend (File) -> Unit,
    ) {
        val directory = Files.createTempDirectory("strict-text-decoding").toFile()
        val file = File(directory, name)
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            directory.deleteRecursively()
        }
    }
}
