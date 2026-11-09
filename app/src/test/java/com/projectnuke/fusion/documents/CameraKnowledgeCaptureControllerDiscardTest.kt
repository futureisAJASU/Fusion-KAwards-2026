package com.projectnuke.fusion.documents

import android.content.Context
import android.content.ContextWrapper
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P11-07 / P12-03 ownership gate for `CameraKnowledgeCaptureController.discard`.
 *
 * `discard` is the physical-release tail of the camera abandon path (ChatScreen
 * abandon-on-conversation-switch, camera->project failure, OCR cancellation). It
 * used to delete the file unconditionally; once a capture was durably adopted
 * (inbox / project) a cancel/failed-adopt path could delete a file
 * a durable DB row still names => orphan row / data loss. The fix fail-closes:
 * only a path that still carries process pending-copy ownership AND is named
 * by no durable authority may be physically released.
 *
 * P13-01 additionally requires the durable authority to be re-read INSIDE the
 * process-wide reference-mutation gate, so these tests inject the authority
 * explicitly to keep the two preconditions observable independently.
 */
class CameraKnowledgeCaptureControllerDiscardTest {

    /** Minimal Context: routes the attachment root to a temp dir. */
    private class FakeAttachmentContext(dir: File) : ContextWrapper(null) {
        private val attachmentDir = dir
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = attachmentDir
        override fun getFilesDir(): File = attachmentDir
    }

    @Test
    fun `discard preserves a file adopted out of pending ownership`() = runTest {
        val root = Files.createTempDirectory("camera-discard-adopted").toFile()
        val file = root.resolve("camera.jpg").apply { writeText("photo") }
        try {
            AttachmentStorageManager.registerPendingAttachment(file)
            val canonical = checkNotNull(AttachmentStorageManager.pendingAttachmentPathsSnapshot()) {
                "registration must be visible"
            }.single()
            // Simulate the durable adoption that transferOwnership performs:
            // pending-copy ownership is released to the durable owner.
            AttachmentStorageManager.unregisterPendingAttachment(canonical)

            val controller = CameraKnowledgeCaptureController(
                FakeAttachmentContext(root),
                isDurablyReferenced = { false },
            )
            val deleted = controller.discard(canonical)

            assertFalse("discard must not physically release an adopted file", deleted)
            assertTrue("adopted file must survive abandon", file.exists())
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(file.canonicalPath)
            root.deleteRecursively()
        }
    }

    @Test
    fun `discard deletes a file still owned by pending copy`() = runTest {
        val root = Files.createTempDirectory("camera-discard-pending").toFile()
        val file = root.resolve("camera.jpg").apply { writeText("photo") }
        try {
            AttachmentStorageManager.registerPendingAttachment(file)
            val canonical = checkNotNull(AttachmentStorageManager.pendingAttachmentPathsSnapshot()).single()

            val controller = CameraKnowledgeCaptureController(
                FakeAttachmentContext(root),
                isDurablyReferenced = { false },
            )
            val outcome = controller.discardWithOutcome(canonical)

            assertEquals(CameraCaptureDiscardResult.DELETED, outcome)
            assertFalse("pending-owned file must be gone after discard", file.exists())
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(file.canonicalPath)
            root.deleteRecursively()
        }
    }

    @Test
    fun `discard fails closed when the durable authority is unreadable`() = runTest {
        val root = Files.createTempDirectory("camera-discard-authority-unreadable").toFile()
        val file = root.resolve("camera.jpg").apply { writeText("photo") }
        try {
            AttachmentStorageManager.registerPendingAttachment(file)
            val canonical = checkNotNull(AttachmentStorageManager.pendingAttachmentPathsSnapshot()).single()

            val controller = CameraKnowledgeCaptureController(
                FakeAttachmentContext(root),
                isDurablyReferenced = { null },
            )
            val outcome = controller.discardWithOutcome(canonical)

            assertEquals(CameraCaptureDiscardResult.AUTHORITY_UNAVAILABLE, outcome)
            assertTrue("unreadable authority must preserve the physical file", file.exists())
            assertTrue(
                "unreadable authority must keep the pending-copy registration",
                AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
            )
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(file.canonicalPath)
            root.deleteRecursively()
        }
    }

    @Test
    fun `discard classifies newly durable ownership as terminal preservation`() = runTest {
        val root = Files.createTempDirectory("camera-discard-referenced").toFile()
        val file = root.resolve("camera.jpg").apply { writeText("photo") }
        try {
            AttachmentStorageManager.registerPendingAttachment(file)
            val canonical = file.canonicalPath
            val controller = CameraKnowledgeCaptureController(
                FakeAttachmentContext(root),
                isDurablyReferenced = { true },
            )

            assertEquals(CameraCaptureDiscardResult.DURABLY_REFERENCED, controller.discardWithOutcome(canonical))
            assertTrue("a new durable owner always preserves the file", file.exists())
            assertTrue("the pending registration remains with its current owner", AttachmentStorageManager.isPendingAttachmentRegistered(canonical))
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(file.canonicalPath)
            root.deleteRecursively()
        }
    }
}
