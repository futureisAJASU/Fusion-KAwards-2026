package com.projectnuke.fusion.documents

import android.content.Context
import android.content.ContextWrapper
import com.projectnuke.fusion.retrieval.IndexedDocumentPage
import com.projectnuke.fusion.retrieval.LocalOcrResult
import com.projectnuke.fusion.util.AttachmentReferenceAuthority
import com.projectnuke.fusion.util.AttachmentReferenceMutationCoordinator
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13-01 second-pass adversarial coverage for the fix itself.
 *
 * These exist to break the FIX, not to re-prove the original defect:
 *
 * 1. LOCK ORDER. `CameraKnowledgeCaptureController.discard` now acquires the
 *    process-wide reference-mutation gate and the controller `operationMutex`
 *    in that order. If any controller operation acquired `operationMutex`
 *    first and then reached for the gate, the two locks would invert and
 *    deadlock. `recognize` holds `operationMutex` for the whole OCR and must
 *    complete while another coroutine holds the gate.
 * 2. GATE PARTICIPATION. A discard started while an unrelated owner holds the
 *    gate must not touch the file or its pending registration until the gate
 *    is released.
 * 3. RECOVERY. A discard that fails closed must leave the file and its
 *    pending registration intact so a later, authority-complete cleanup pass
 *    can settle it — but the same retry must NOT delete a file that acquired
 *    a durable owner in the meantime.
 */
class CameraDiscardLockOrderAndRecoveryTest {

    private class FakeAttachmentContext(dir: File) : ContextWrapper(null) {
        private val attachmentDir = dir
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = attachmentDir
        override fun getFilesDir(): File = attachmentDir
    }

    private val recognition: suspend (String, (Int, Int) -> Unit) -> LocalOcrResult = { _, _ ->
        LocalOcrResult.Complete(listOf(IndexedDocumentPage(1, "recognized", 10, "h", false, 0, false)))
    }

    @Test
    fun `recognize never needs the reference mutation gate so no lock order inversion exists`() = runBlocking {
        val root = Files.createTempDirectory("camera-lockorder").toFile()
        val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(24)) }
        val context = FakeAttachmentContext(root)
        val controller = CameraKnowledgeCaptureController(context, recognition, { false })
        val canonical = captured.canonicalPath
        val gateHeld = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        try {
            // An unrelated owner (promotion / import / destructive GC) holds
            // the global gate for as long as it likes.
            val gateHolder = async(Dispatchers.Unconfined) {
                AttachmentReferenceMutationCoordinator.withMutation {
                    gateHeld.complete(Unit)
                    releaseGate.await()
                }
            }
            gateHeld.await()

            // recognize() must complete WITHOUT the gate. If the controller
            // ever acquired the gate while holding operationMutex, this would
            // deadlock and the test would time out instead of passing.
            val recognized = async(Dispatchers.Unconfined) { controller.recognize(canonical) }
            val state = withTimeout(TEST_TIMEOUT_MS) { recognized.await() }
            assertTrue(
                "ocr recognition must not depend on the reference mutation gate",
                state.status == CameraOcrStatus.PREVIEW,
            )

            releaseGate.complete(Unit)
            withTimeout(TEST_TIMEOUT_MS) { gateHolder.await() }
        } finally {
            releaseGate.complete(Unit)
            AttachmentStorageManager.unregisterPendingAttachment(canonical)
            root.deleteRecursively()
        }
    }

    @Test
    fun `a discard waits for the gate and then still settles correctly`() = runBlocking {
        val root = Files.createTempDirectory("camera-gate-wait").toFile()
        val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(24)) }
        val context = FakeAttachmentContext(root)
        val controller = CameraKnowledgeCaptureController(context, recognition, { false })
        val canonical = captured.canonicalPath
        AttachmentStorageManager.registerPendingAttachment(captured)
        val gateHeld = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        try {
            val gateHolder = async(Dispatchers.Unconfined) {
                AttachmentReferenceMutationCoordinator.withMutation {
                    gateHeld.complete(Unit)
                    releaseGate.await()
                }
            }
            gateHeld.await()

            val discard = async(Dispatchers.Unconfined) {
                withContext(Dispatchers.Unconfined) { controller.discard(canonical) }
            }
            // The discard is deterministically parked at the mutation gate: it
            // has started (unconfined) and cannot have reached any physical
            // decision, so nothing about the file may have changed yet.
            assertFalse("a discard must not complete while the gate is held", discard.isCompleted)
            assertTrue("no physical action may run while the gate is held", captured.exists())
            assertTrue(
                "no pending ownership may be settled while the gate is held",
                AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
            )

            releaseGate.complete(Unit)
            val discarded = withTimeout(TEST_TIMEOUT_MS) { discard.await() }
            withTimeout(TEST_TIMEOUT_MS) { gateHolder.await() }

            assertTrue("the discard must settle once the gate is released", discarded)
            assertFalse("the unreferenced copy must be released", captured.exists())
            assertFalse(
                "pending ownership must be settled",
                AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
            )
        } finally {
            releaseGate.complete(Unit)
            AttachmentStorageManager.unregisterPendingAttachment(canonical)
            root.deleteRecursively()
        }
    }

    @Test
    fun `a fail-closed discard is recovered later but never deletes a newly owned file`() = runBlocking {
        val root = Files.createTempDirectory("camera-recovery").toFile()
        val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(24)) }
        val context = FakeAttachmentContext(root)
        val canonical = captured.canonicalPath
        AttachmentStorageManager.registerPendingAttachment(captured)
        // Adurable owner appears between the two cleanup attempts.
        val durableProjectPaths = mutableSetOf<String>()
        val authority: suspend (String) -> Boolean? = { path -> path == canonical && path in durableProjectPaths }
        val controller = CameraKnowledgeCaptureController(context, recognition, authority)
        try {
            // 1. Authority unreadable: the first pass fails closed.
            val readOnlyAuthority = CameraKnowledgeCaptureController(
                context,
                recognition,
                { null },
            )
            val firstPass = readOnlyAuthority.discard(canonical)
            assertFalse("unreadable authority must fail closed", firstPass)
            assertTrue("fail-closed must preserve the file", captured.exists())
            assertTrue(
                "fail-closed must keep the pending registration for recovery",
                AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
            )

            // 2. The file acquires a durable owner before the retry. The retry
            //    must NOT delete it.
            durableProjectPaths += canonical
            assertFalse("a retry must not delete a file that gained a durable owner", controller.discard(canonical))
            assertTrue("the newly owned file must survive", captured.exists())
            assertTrue(
                "the newly owned file must stay pending-protected until its owner settles it",
                AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
            )

            // 3. While the pending registration is still held the file is a
            //    live process owner, so the authority-complete GC must also
            //    refuse to reclaim it: a failed delete is never escalated into
            //    an unguarded sweep.
            durableProjectPaths.clear()
            var swept = AttachmentStorageManager.cleanupUnreferencedAttachmentsUnderMutationGate(
                attachmentDir = root,
                listFiles = { listOf(captured) },
                messageAuthority = { AttachmentReferenceAuthority.Complete(emptySet()) },
                projectPaths = { emptyList() },
                durableDraftPaths = { emptySet() },
                pendingPaths = { AttachmentStorageManager.pendingAttachmentPathsSnapshot() },
                now = { System.currentTimeMillis() + 3_600_000L },
            )
            assertEquals("a pending-registered copy is a live owner for cleanup", 0, swept)
            assertTrue("pending ownership must keep the copy protected", captured.exists())

            // 4. Once ownership is finally settled (durable or absent) and no
            //    authority names the path, the authority-complete pass reclaims
            //    exactly that file.
            AttachmentStorageManager.unregisterPendingAttachment(canonical)
            swept = AttachmentStorageManager.cleanupUnreferencedAttachmentsUnderMutationGate(
                attachmentDir = root,
                listFiles = { listOf(captured) },
                messageAuthority = { AttachmentReferenceAuthority.Complete(emptySet()) },
                projectPaths = { emptyList() },
                durableDraftPaths = { emptySet() },
                pendingPaths = { AttachmentStorageManager.pendingAttachmentPathsSnapshot() },
                now = { System.currentTimeMillis() + 3_600_000L },
            )
            assertEquals("the settled orphan must be reclaimed once", 1, swept)
            assertFalse("the orphaned copy must be physically reclaimed", captured.exists())
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(canonical)
            root.deleteRecursively()
        }
    }

    private companion object {
        // A lock-order inversion would hang; a real timeout converts that into
        // a test failure instead of a wedged test worker.
        const val TEST_TIMEOUT_MS = 20_000L
    }
}
