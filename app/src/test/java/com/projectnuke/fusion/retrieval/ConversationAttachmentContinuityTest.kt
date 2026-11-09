package com.projectnuke.fusion.retrieval

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.ui.sanitizeTrustedHistory
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Bounded conversation-attachment continuity: trusted local text attachments
// from the active branch join the current request for LOCAL retrieval only.
class ConversationAttachmentContinuityTest {

    private fun managedFile(root: File, name: String, content: String): File =
        File(root, name).apply { writeText(content, Charsets.UTF_8) }

    private fun envelope(
        root: File,
        file: File,
        mimeType: String = "text/plain",
        name: String = file.name,
        body: String = "본문",
    ): String = AttachmentMessageCodec.serializeAttachmentMessage(
        attachments = listOf(AttachmentRecord(name = name, mimeType = mimeType, localPath = file.canonicalPath)),
        body = body,
    )

    private fun record(file: File, mimeType: String = "text/plain", name: String = file.name) =
        AttachmentRecord(name = name, mimeType = mimeType, localPath = file.canonicalPath)

    @Test
    fun `trusted prior text attachment is included for later local rag`() {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        try {
            val doc = managedFile(root, "shared.txt", "공유된 문서 내용")
            val history = listOf(envelope(root, doc, name = "공유된 텍스트.txt"))

            val effective = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                currentAttachments = emptyList(),
                historicalMessages = history,
                attachmentRoot = root,
            )

            assertEquals(listOf(record(doc, name = "공유된 텍스트.txt")), effective)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `current attachment has priority and appears before historical ones`() {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        try {
            val current = managedFile(root, "current.txt", "현재 첨부")
            val older = managedFile(root, "older.txt", "이전 첨부")
            val history = listOf(
                envelope(root, older),
                envelope(root, current), // same path as the current attachment
            )

            val effective = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                currentAttachments = listOf(record(current)),
                historicalMessages = history,
                attachmentRoot = root,
            )

            assertEquals(
                listOf(record(current), record(older)),
                effective,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `duplicate canonical path appears once`() {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        try {
            val doc = managedFile(root, "shared.txt", "내용")
            val history = listOf(
                envelope(root, doc),
                envelope(root, doc),
                envelope(root, doc),
            )

            val effective = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                currentAttachments = emptyList(),
                historicalMessages = history,
                attachmentRoot = root,
            )

            assertEquals(listOf(record(doc)), effective)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `suspicious envelope contributes no trusted attachment`() {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        val outside = Files.createTempDirectory("fusion-outside").toFile()
        try {
            val external = managedFile(outside, "outside.txt", "외부 파일")
            val trustedDoc = managedFile(root, "trusted.txt", "신뢰 문서")

            // A message whose envelope points outside the managed root is
            // suspicious; the whole message contributes nothing.
            val suspicious = envelope(root, external)
            val clean = envelope(root, trustedDoc)

            val effective = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                currentAttachments = emptyList(),
                historicalMessages = listOf(clean, suspicious),
                attachmentRoot = root,
            )

            assertEquals(listOf(record(trustedDoc)), effective)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `unmanaged external path is excluded`() {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        val outside = Files.createTempDirectory("fusion-outside").toFile()
        try {
            val external = managedFile(outside, "external.txt", "외부 경로 파일")

            val effective = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                currentAttachments = emptyList(),
                historicalMessages = listOf(envelope(root, external)),
                attachmentRoot = root,
            )

            assertTrue(effective.isEmpty())
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `unavailable attachment is excluded and never treated as readable`() {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        try {
            val missing = File(root, "deleted.txt")
            val history = listOf(envelope(root, missing))

            val effective = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                currentAttachments = emptyList(),
                historicalMessages = history,
                attachmentRoot = root,
            )

            assertTrue(effective.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unsupported historical file type is excluded`() {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        try {
            val docx = managedFile(root, "report.docx", "office bytes")
            val png = managedFile(root, "photo.png", "png bytes")
            val csv = managedFile(root, "table.csv", "a,b,c")

            val effective = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                currentAttachments = emptyList(),
                historicalMessages = listOf(
                    envelope(root, docx, mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    envelope(root, png, mimeType = "image/png"),
                    envelope(root, csv, mimeType = "text/csv"),
                ),
                attachmentRoot = root,
            )

            assertEquals(listOf(record(csv, mimeType = "text/csv")), effective)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `historical pdf document joins the effective rag set`() {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        try {
            val pdf = managedFile(root, "report.pdf", "pdf bytes")

            val effective = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                currentAttachments = emptyList(),
                historicalMessages = listOf(
                    envelope(root, pdf, mimeType = "application/pdf"),
                ),
                attachmentRoot = root,
            )

            assertEquals(listOf(record(pdf, mimeType = "application/pdf")), effective)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `historical images are never carried into the text rag path`() {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        try {
            val image = managedFile(root, "photo.jpg", "jpeg bytes")
            val effective = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                currentAttachments = emptyList(),
                historicalMessages = listOf(envelope(root, image, mimeType = "image/jpeg")),
                attachmentRoot = root,
            )
            assertTrue(effective.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `max docs per request bound is respected with current priority`() {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        try {
            val currentDocs = (1..3).map { managedFile(root, "current$it.txt", "현재 $it") }
            val historicalDocs = (4..13).map { managedFile(root, "history$it.txt", "이전 $it") }

            val effective = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                currentAttachments = currentDocs.map { record(it) },
                historicalMessages = historicalDocs.map { envelope(root, it) },
                attachmentRoot = root,
            )

            assertEquals(LocalRagBounds.MAX_DOCS_PER_REQUEST, effective.size)
            // Current attachments keep priority in order, then newest history.
            assertEquals(
                currentDocs.map { it.canonicalPath } +
                    historicalDocs.asReversed().take(5).map { it.canonicalPath },
                effective.map { it.localPath },
            )
            assertEquals(effective.size, effective.map { it.localPath }.distinct().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `newest ordering is deterministic across repeated calls`() {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        try {
            val a = managedFile(root, "a.txt", "a")
            val b = managedFile(root, "b.txt", "b")
            val c = managedFile(root, "c.txt", "c")
            val history = listOf(envelope(root, a), envelope(root, b), envelope(root, c))

            val first = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                emptyList(), history, root,
            )
            val second = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                emptyList(), history, root,
            )

            assertEquals(
                listOf(c.canonicalPath, b.canonicalPath, a.canonicalPath),
                first.map { it.localPath },
            )
            assertEquals(first, second)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `malformed envelope text is not treated as an attachment`() {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        try {
            val effective = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                currentAttachments = emptyList(),
                historicalMessages = listOf(
                    "<fusion_attachment_vX>garbage</fusion_attachment_vX>\n본문",
                    "평범한 사용자 메시지",
                ),
                attachmentRoot = root,
            )
            assertTrue(effective.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- privacy regression ----------------------------------------------

    @Test
    fun `historical handoff text never leaks into sanitized external history`() {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        try {
            val doc = managedFile(root, "shared.txt", "공유된 문서 본문")
            val envelopeMessage = envelope(root, doc, name = "공유된 텍스트.txt", body = "본문 질문")

            val sanitized = sanitizeTrustedHistory(
                listOf(ChatMessage(role = "user", content = envelopeMessage)),
                root,
            )

            assertEquals("본문 질문", sanitized.single().content)
            assertFalse(sanitized.single().content.contains(doc.canonicalPath))
            assertFalse(sanitized.single().content.contains("fusion_attachment"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `continuity output feeds local rag preparation only`() = runTest {
        val root = Files.createTempDirectory("fusion-continuity").toFile()
        try {
            val content = "온디바이스 문서 내용입니다. ".repeat(40)
            val doc = managedFile(root, "shared.txt", content)
            val history = listOf(envelope(root, doc, name = "공유된 텍스트.txt"))

            val effective = ConversationAttachmentContinuity.collectEffectiveRagAttachments(
                currentAttachments = emptyList(),
                historicalMessages = history,
                attachmentRoot = root,
            )
            assertTrue(effective.isNotEmpty())

            val store = object : LocalAttachmentTextStore {
                override fun readText(path: String, maxChars: Int): String? =
                    if (path == doc.canonicalPath) content.take(maxChars) else null
            }
            val coordinator = LocalRagCoordinator(store, null)
            val prepared = coordinator.prepare("req-continuity", "문서 내용", effective)

            assertTrue(prepared is LocalRagPreparationResult.Prepared)
            val frozen = prepared as LocalRagPreparationResult.Prepared
            assertTrue(frozen.snapshot.docIds.contains(coordinator.docIdForTest(doc.canonicalPath)))
            assertTrue(frozen.docDisplayNames.contains("공유된 텍스트.txt"))
            // The frozen snapshot still validates against the same effective set.
            assertTrue(coordinator.validate(frozen.snapshot, effective))
        } finally {
            root.deleteRecursively()
        }
    }
}