package com.projectnuke.fusion.documents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13-03: a stale camera callback must not clear a newer camera session.
 *
 * The Compose-level camera UI state (`cameraOutputPath`,
 * `cameraOriginConversationId`, `cameraImportToken`, `cameraProjectTargetId`,
 * `cameraAdoptionInFlight`) is shared by every capture in one composition, so
 * an older capture's delayed callback must be identity-checked before it
 * touches it.
 *
 * The capture controller already path-guarded its own internal state; the
 * Compose-level reset did not. [CameraCaptureSession] is the single identity
 * seam every Compose-level camera-session mutation goes through.
 */
class CameraCaptureSessionIdentityTest {

    private val current = CameraCaptureSession(
        path = "/attachments/camera_ocr_B.jpg",
        originConversationId = 12L,
        importToken = "token-B",
    )

    @Test
    fun `the owning operation may clear its own session`() {
        val owner = CameraCaptureSession(
            path = "/attachments/camera_ocr_B.jpg",
            originConversationId = 12L,
            importToken = "token-B",
        )
        assertTrue(owner.owns(current))
    }

    @Test
    fun `a stale token cannot clear a newer capture in the same conversation`() {
        // Same conversation ID, different capture: conversation ID alone is
        // not an identity.
        val stale = CameraCaptureSession(
            path = "/attachments/camera_ocr_B.jpg",
            originConversationId = 12L,
            importToken = "token-A",
        )
        assertFalse(stale.owns(current))
    }

    @Test
    fun `a stale path cannot clear a newer capture`() {
        val stale = CameraCaptureSession(
            path = "/attachments/camera_ocr_A.jpg",
            originConversationId = 12L,
            importToken = "token-B",
        )
        assertFalse(stale.owns(current))
    }

    @Test
    fun `a callback from another conversation cannot clear the current capture`() {
        val stale = CameraCaptureSession(
            path = "/attachments/camera_ocr_B.jpg",
            originConversationId = 41L,
            importToken = "token-B",
        )
        assertFalse(stale.owns(current))
    }

    @Test
    fun `an all-null session cannot clear a live capture`() {
        assertFalse(CameraCaptureSession(path = null, originConversationId = null, importToken = null).owns(current))
    }

    @Test
    fun `identical-but-restored session identity is still accepted`() {
        // Activity recreation restores the same path/token pair, so the owner
        // remains able to settle its own capture instead of leaking it.
        val restored = CameraCaptureSession(
            path = "/attachments/camera_ocr_B.jpg",
            originConversationId = 12L,
            importToken = "token-B",
        )
        assertTrue(restored.owns(current))
    }
}
