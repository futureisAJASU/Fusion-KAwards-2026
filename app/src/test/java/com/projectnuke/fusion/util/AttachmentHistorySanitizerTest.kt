package com.projectnuke.fusion.util

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.ui.sanitizeTrustedHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AttachmentHistorySanitizerTest {

    private fun makeV3Tag(record: AttachmentRecord): String {
        return AttachmentMessageCodec.serializeAttachmentMessage(listOf(record), "")
            .substringBefore("\n\n")
    }

    @Test
    fun `trusted v3 user message returns body only`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val child = File(root, "photo.jpg")
        child.writeText("data")
        val tag = makeV3Tag(AttachmentRecord("photo.jpg", "image/jpeg", child.canonicalPath))
        val raw = "$tag Hello world"
        val messages = listOf(ChatMessage(role = "user", content = raw))
        val result = sanitizeTrustedHistory(messages, root)
        assertEquals(1, result.size)
        assertEquals("Hello world", result[0].content.trim())
        root.deleteRecursively()
    }

    @Test
    fun `typed attachment sanitization preserves the original durable id`() {
        val root = Files.createTempDirectory("test_attachments_identity").toFile()
        val child = File(root, "photo.jpg")
        child.writeText("data")
        val tag = makeV3Tag(AttachmentRecord("photo.jpg", "image/jpeg", child.canonicalPath))
        val result = sanitizeTrustedHistory(
            listOf(
                HistoryMessageWithId(
                    durableMessageId = 9001L,
                    message = ChatMessage("user", "$tag body"),
                )
            ),
            root,
        )
        assertEquals(listOf(9001L), result.map { it.durableMessageId })
        assertEquals("body", result.single().message.content.trim())
        root.deleteRecursively()
    }

    @Test
    fun `unavailable managed v3 message returns body only`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val missing = File(root, "missing.txt")
        val tag = makeV3Tag(AttachmentRecord("missing.txt", "text/plain", missing.canonicalPath))
        val raw = "$tag body text"
        val messages = listOf(ChatMessage(role = "user", content = raw))
        val result = sanitizeTrustedHistory(messages, root)
        assertEquals(1, result.size)
        assertEquals("body text", result[0].content.trim())
        root.deleteRecursively()
    }

    @Test
    fun `valid legacy v2 managed message returns body only`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val child = File(root, "doc.txt")
        child.writeText("data")
        val safePath = child.canonicalPath.replace("\\", "/")
        val raw = "<fusion_attachment_v2>doc.txt|text/plain|$safePath</fusion_attachment_v2> after"
        val messages = listOf(ChatMessage(role = "user", content = raw))
        val result = sanitizeTrustedHistory(messages, root)
        assertEquals(1, result.size)
        assertEquals("after", result[0].content.trim())
        root.deleteRecursively()
    }

    @Test
    fun `valid legacy v1 managed message returns body only`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val child = File(root, "doc.txt")
        child.writeText("data")
        val safePath = child.canonicalPath.replace("\\", "/")
        val raw = "<fusion_attachment>\nname=doc.txt\nmime=text/plain\npath=$safePath\n</fusion_attachment>\nBody text"
        val messages = listOf(ChatMessage(role = "user", content = raw))
        val result = sanitizeTrustedHistory(messages, root)
        assertEquals(1, result.size)
        assertEquals("Body text", result[0].content.trim())
        root.deleteRecursively()
    }

    @Test
    fun `suspicious outside-root envelope preserves complete literal text`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val outside = Files.createTempFile("outside", ".txt").toFile()
        outside.writeText("data")
        val tag = makeV3Tag(AttachmentRecord("outside.txt", "text/plain", outside.canonicalPath))
        val raw = "$tag body"
        val messages = listOf(ChatMessage(role = "user", content = raw))
        val result = sanitizeTrustedHistory(messages, root)
        assertEquals(1, result.size)
        assertEquals(raw, result[0].content)
        root.deleteRecursively()
        outside.delete()
    }

    @Test
    fun `directory path envelope preserves complete literal text`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val dir = File(root, "subdir")
        dir.mkdirs()
        val tag = makeV3Tag(AttachmentRecord("subdir", "inode/directory", dir.canonicalPath))
        val raw = "$tag body"
        val messages = listOf(ChatMessage(role = "user", content = raw))
        val result = sanitizeTrustedHistory(messages, root)
        assertEquals(1, result.size)
        assertEquals(raw, result[0].content)
        root.deleteRecursively()
    }

    @Test
    fun `malformed envelope preserves complete literal text`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val raw = "<fusion_attachment_v3>NOT_VALID_BASE64!!!</fusion_attachment_v3> body"
        val messages = listOf(ChatMessage(role = "user", content = raw))
        val result = sanitizeTrustedHistory(messages, root)
        assertEquals(1, result.size)
        assertEquals(raw, result[0].content)
        root.deleteRecursively()
    }

    @Test
    fun `assistant message has metrics and thinking stripped`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val raw = "<fusion_metrics>t=1.2s</fusion_metrics><fusion_thinking>thinking text</fusion_thinking><fusion_answer>answer</fusion_answer>"
        val messages = listOf(ChatMessage(role = "assistant", content = raw))
        val result = sanitizeTrustedHistory(messages, root)
        assertEquals(1, result.size)
        assertEquals("answer", result[0].content)
        root.deleteRecursively()
    }

    @Test
    fun `local assistant history uses shared authority and fails closed for unknown native tags`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val normalized = sanitizeTrustedHistory(
            listOf(
                ChatMessage("user", "old user A"),
                ChatMessage("assistant", "<fusion_thinking>secret</fusion_thinking><fusion_answer>answer</fusion_answer>"),
            ),
            root,
        )
        val unknownLegacy = sanitizeTrustedHistory(
            listOf(
                ChatMessage("user", "old user A"),
                ChatMessage("assistant", "<think>secret</think>answer"),
            ),
            root,
        )
        val qwenLegacy = sanitizeTrustedHistory(
            listOf(
                ChatMessage("user", "old user A"),
                ChatMessage("assistant", "<think>secret</think>answer"),
            ),
            root,
            FusionPromptAdapters.forFamily(ModelFamily.QWEN).explicitReasoningTags,
        )
        val deepSeekLegacy = sanitizeTrustedHistory(
            listOf(
                ChatMessage("user", "old user A"),
                ChatMessage("assistant", "<thinking>secret</thinking>answer"),
            ),
            root,
            FusionPromptAdapters.forFamily(ModelFamily.DEEPSEEK).explicitReasoningTags,
        )
        assertEquals(listOf("old user A", "answer"), normalized.map { it.content })
        assertTrue(unknownLegacy.isEmpty())
        assertEquals(listOf("old user A", "answer"), qwenLegacy.map { it.content })
        assertEquals(listOf("old user A", "answer"), deepSeekLegacy.map { it.content })
        assertTrue(unknownLegacy.none { it.role == "assistant" && it.content.isBlank() })
        root.deleteRecursively()
    }

    @Test
    fun `multiple messages preserve role and order`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val messages = listOf(
            ChatMessage(role = "system", content = "system msg"),
            ChatMessage(role = "user", content = "user msg"),
            ChatMessage(role = "assistant", content = "assistant msg")
        )
        val result = sanitizeTrustedHistory(messages, root)
        assertEquals(3, result.size)
        assertEquals("system", result[0].role)
        assertEquals("system msg", result[0].content)
        assertEquals("user", result[1].role)
        assertEquals("user msg", result[1].content)
        assertEquals("assistant", result[2].role)
        assertEquals("assistant msg", result[2].content)
        root.deleteRecursively()
    }

    @Test
    fun `sanitized history contains no managed path or Base64 payload for genuine attachments`() {
        val root = Files.createTempDirectory("test_attachments").toFile()
        val child = File(root, "secret.txt")
        child.writeText("data")
        val record = AttachmentRecord("secret.txt", "text/plain", child.canonicalPath)
        val tag = makeV3Tag(record)
        val raw = "$tag Hello"
        val messages = listOf(ChatMessage(role = "user", content = raw))
        val result = sanitizeTrustedHistory(messages, root)
        assertFalse(result[0].content.contains(child.canonicalPath))
        assertFalse(result[0].content.contains("fusion_attachment_v3"))
        assertEquals("Hello", result[0].content.trim())
        root.deleteRecursively()
    }
}
