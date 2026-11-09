package com.projectnuke.fusion.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxCapturePolicyTest {
    @Test
    fun `prepares bounded local text without assigning a project`() {
        val prepared = InboxCapturePolicy.prepare(
            InboxCapture(InboxCaptureType.NOTE, "  quick note ", "  keep for later  "),
            now = 12L,
            id = "inbox-12",
        ) as InboxPreparation.Ready

        assertEquals("quick note", prepared.item.title)
        assertEquals("keep for later", prepared.item.content)
        assertEquals("NOTE", prepared.item.captureType)
        assertEquals(12L, prepared.item.createdAt)
        assertTrue(prepared.item.originalUri == null)
        assertFalse(prepared.item.content.isEmpty())
    }

    @Test
    fun `rejects unsupported and credential-bearing urls`() {
        assertTrue(InboxCapturePolicy.prepare(
            InboxCapture(InboxCaptureType.URL, "local", "text", "file:///secret"), 1,
        ) is InboxPreparation.Invalid)
        assertTrue(InboxCapturePolicy.prepare(
            InboxCapture(InboxCaptureType.URL, "private", "text", "https://user@example.com/private"), 1,
        ) is InboxPreparation.Invalid)
        assertTrue(InboxCapturePolicy.prepare(
            InboxCapture(InboxCaptureType.URL, "credential", "https://example.com/?access_token=abcdefghijklmnop", "https://example.com/?access_token=abcdefghijklmnop"), 1,
        ) is InboxPreparation.SensitiveContent)
        assertTrue(InboxCapturePolicy.prepare(
            InboxCapture(InboxCaptureType.URL, "missing", "https://example.com"), 1,
        ) is InboxPreparation.Invalid)
    }

    @Test
    fun `rejects secrets and oversized content`() {
        assertTrue(InboxCapturePolicy.prepare(
            InboxCapture(InboxCaptureType.NOTE, "key", "api_key=abcdefghijklmnop"), 1,
        ) is InboxPreparation.SensitiveContent)
        assertTrue(InboxCapturePolicy.prepare(
            InboxCapture(InboxCaptureType.NOTE, "long", "x".repeat(InboxCapturePolicy.MAX_CONTENT_CHARS + 1)), 1,
        ) is InboxPreparation.Invalid)
    }

    @Test
    fun `accepts bounded OCR text with a managed image identity`() {
        val prepared = InboxCapturePolicy.prepare(
            InboxCapture(
                type = InboxCaptureType.OCR,
                title = "camera capture",
                content = "recognized words",
                managedFileId = "managed/camera-image.jpg",
                managedMimeType = "image/jpeg",
                sourceHandoffEventId = "capture-event",
            ),
            now = 7,
            id = "capture-event",
        ) as InboxPreparation.Ready

        assertEquals("OCR", prepared.item.captureType)
        assertEquals("recognized words", prepared.item.content)
        assertEquals("image/jpeg", prepared.item.managedMimeType)
        assertEquals("capture-event", prepared.item.sourceHandoffEventId)
    }

    @Test
    fun `web clip keeps entered URL separate from validated effective source metadata`() {
        val prepared = InboxCapturePolicy.prepare(
            InboxCapture(
                type = InboxCaptureType.WEB_CLIP,
                title = "Captured page",
                content = "Fetched text",
                originalUri = "https://entered.example/article",
                effectiveSourceUri = "https://content.example/final",
            ),
            now = 9,
            id = "web-clip-9",
        ) as InboxPreparation.Ready

        assertEquals("https://entered.example/article", prepared.item.originalUri)
        assertEquals("https://content.example/final", prepared.item.effectiveSourceUri)
        assertTrue(
            InboxCapturePolicy.prepare(
                InboxCapture(
                    type = InboxCaptureType.WEB_CLIP,
                    title = "Bad source",
                    content = "Fetched text",
                    originalUri = "https://entered.example/article",
                    effectiveSourceUri = "file:///private",
                ),
                now = 9,
            ) is InboxPreparation.Invalid,
        )
    }
}
