package com.projectnuke.fusion.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraKnowledgeCapturePolicyTest {
    @Test
    fun `capture size is strictly bounded`() {
        assertFalse(CameraKnowledgeCapturePolicy.acceptsCaptureBytes(0))
        assertTrue(CameraKnowledgeCapturePolicy.acceptsCaptureBytes(1))
        assertTrue(CameraKnowledgeCapturePolicy.acceptsCaptureBytes(CameraKnowledgeCapturePolicy.MAX_CAPTURE_BYTES))
        assertFalse(CameraKnowledgeCapturePolicy.acceptsCaptureBytes(CameraKnowledgeCapturePolicy.MAX_CAPTURE_BYTES + 1))
    }

    @Test
    fun `preview is bounded and reports truncation`() {
        val full = "x".repeat(CameraKnowledgeCapturePolicy.MAX_PREVIEW_CHARS + 7)
        val (preview, truncated) = CameraKnowledgeCapturePolicy.preview(full)
        assertEquals(CameraKnowledgeCapturePolicy.MAX_PREVIEW_CHARS, preview.length)
        assertTrue(truncated)
        assertEquals("short", CameraKnowledgeCapturePolicy.preview("short").first)
        assertFalse(CameraKnowledgeCapturePolicy.preview("short").second)
    }
}
