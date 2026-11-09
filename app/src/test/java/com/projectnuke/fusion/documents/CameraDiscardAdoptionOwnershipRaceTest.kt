package com.projectnuke.fusion.documents

import android.content.Context
import android.content.ContextWrapper
import com.projectnuke.fusion.ui.ProjectFileImportCoordinator
import com.projectnuke.fusion.ui.ProjectFileImportResult
import com.projectnuke.fusion.util.AttachmentImportResult
import com.projectnuke.fusion.util.AttachmentReferenceMutationCoordinator
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13-01 regression: camera discard versus durable adoption.
 *
 * Camera abandonment (`CameraKnowledgeCaptureController.discard`) releases a
 * physical managed file. A durable adoption (camera -> Project via
 * [ProjectFileImportCoordinator], camera -> Inbox via
 * `CameraOcrInboxCaptureController`) commits its durable row first and settles
 * pending-copy ownership afterwards, so between those two steps the file is
 * BOTH durably referenced AND still pending-registered. A discard that ran on a
 * plain pending-registration snapshot therefore deleted a file the committed
 * row named => orphan ProjectFileEntity / Inbox row and physical data loss.
 *
 * The harness proves the interleaving without any wall-clock sleep: the durable
 * commit and the pending settlement are separated by an explicit
 * `CompletableDeferred`, the register is the real
 * [AttachmentStorageManager] pending set, the file is a real managed file on a
 * real attachment root, and both the discard and the adoption are the real
 * production implementations.
 *
 * The gate proofs deliberately inject an authority that names nothing
 * (`{ _ -> false }`): the ONLY thing that may then stop the delete is the
 * shared serialization seam, so a gate regression cannot be masked by the
 * authority fail-closed.
 */
class CameraDiscardAdoptionOwnershipRaceTest {

    private class FakeAttachmentContext(dir: File) : ContextWrapper(null) {
        private val attachmentDir = dir
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = attachmentDir
        override fun getFilesDir(): File = attachmentDir
    }

    /** OCR recognition is not part of this interleaving. */
    private fun unusedRecognition(): suspend (String, (Int, Int) -> Unit) -> com.projectnuke.fusion
        .retrieval.LocalOcrResult = { _, _ ->
        throw UnsupportedOperationException("recognition is not part of this interleaving")
    }

    /** Gate proof: the durable authority names nothing. */
    private val noDurableReference: suspend (String) -> Boolean? = { false }

    @Test
    fun `camera discard must not delete a file between durable project commit and pending settlement`() =
        runBlocking {
            val root = Files.createTempDirectory("camera-discard-project-race").toFile()
            val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(32)) }
            val context = FakeAttachmentContext(root)
            val controller = CameraKnowledgeCaptureController(
                context = context,
                recognizeImage = unusedRecognition(),
                isDurablyReferenced = noDurableReference,
            )
            val canonical = captured.canonicalPath
            // Production pending-copy ownership for a freshly captured file.
            AttachmentStorageManager.registerPendingAttachment(captured)
            val durableProjectPaths = mutableSetOf<String>()
            try {
                val durableCommitted = CompletableDeferred<Unit>()
                val settleOwnership = CompletableDeferred<Unit>()

                // Coroutine A - durable Project adoption of the very same
                // physical file (camera -> Project passes the capture itself).
                val adoption = async(Dispatchers.Unconfined) {
                    ProjectFileImportCoordinator(
                        deletePendingFile = { path ->
                            AttachmentStorageManager.deletePendingAttachmentFile(context, path)
                        },
                        unregisterPending = AttachmentStorageManager::unregisterPendingAttachment,
                    ).copyAndPersist(
                        copy = { AttachmentImportResult.Success(captured, captured.length()) },
                        persist = {
                            // 1. the durable ProjectFileEntity row commits...
                            durableProjectPaths += canonical
                            // 2. ...then the coroutine pauses BEFORE the
                            //    pending-copy ownership is settled.
                            durableCommitted.complete(Unit)
                            settleOwnership.await()
                            "adopted"
                        },
                        isAdopted = { it == "adopted" },
                        isDurablyReferenced = { it in durableProjectPaths },
                    )
                }

                // 3. The durable row is committed and pending ownership is
                //    still unsettled.
                durableCommitted.await()

                // 4. Coroutine B - camera abandonment for the same capture
                //    while the adoption is still between commit and settlement.
                val discard = async(Dispatchers.Unconfined) {
                    withContext(Dispatchers.Unconfined) { controller.discard(canonical) }
                }

                // 5. The adoption settles pending ownership and finishes.
                settleOwnership.complete(Unit)
                val importResult = adoption.await()
                val discarded = discard.await()

                assertTrue(
                    "durable adoption must succeed",
                    importResult is ProjectFileImportResult.Persisted,
                )
                assertTrue(
                    "the durable Project reference must exist",
                    canonical in durableProjectPaths,
                )
                assertFalse(
                    "camera discard must not delete a file a durable Project reference names",
                    discarded,
                )
                assertTrue(
                    "the physically adopted file must survive the overlapping discard",
                    captured.exists(),
                )
                assertFalse(
                    "pending ownership must be settled by the adoption, not by a discard",
                    AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
                )
            } finally {
                AttachmentStorageManager.unregisterPendingAttachment(canonical)
                root.deleteRecursively()
            }
        }

    /**
     * Camera -> Inbox has the same ownership shape. `CameraOcrInboxCaptureController.save`
     * commits the durable Inbox row and settles pending ownership inside ONE
     * reference-mutation gate transaction, so an overlapping discard must land
     * either before the durable write (which then fails admission on the
     * missing source) or after the settlement (which removes the pending
     * registration and leaves nothing to delete).
     */
    @Test
    fun `camera discard must not delete a file between durable inbox commit and pending settlement`() =
        runBlocking {
            val root = Files.createTempDirectory("camera-discard-inbox-race").toFile()
            val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(32)) }
            val context = FakeAttachmentContext(root)
            val controller = CameraKnowledgeCaptureController(
                context = context,
                recognizeImage = unusedRecognition(),
                isDurablyReferenced = noDurableReference,
            )
            val canonical = captured.canonicalPath
            AttachmentStorageManager.registerPendingAttachment(captured)
            val durableInboxPaths = mutableSetOf<String>()
            try {
                val durableCommitted = CompletableDeferred<Unit>()
                val settleOwnership = CompletableDeferred<Unit>()

                // Coroutine A - the production camera -> Inbox ownership
                // transfer shape: durable row commit, then pending settlement,
                // both under one process-wide reference-mutation gate.
                val adoption = async(Dispatchers.Unconfined) {
                    AttachmentReferenceMutationCoordinator.withMutation {
                        withContext(NonCancellable) {
                            durableInboxPaths += canonical
                            durableCommitted.complete(Unit)
                            settleOwnership.await()
                            // transferOwnership(): pending settlement.
                            AttachmentStorageManager.unregisterPendingAttachment(canonical)
                            "stored"
                        }
                    }
                }

                durableCommitted.await()

                val discard = async(Dispatchers.Unconfined) {
                    withContext(Dispatchers.Unconfined) { controller.discard(canonical) }
                }

                settleOwnership.complete(Unit)
                val inboxResult = adoption.await()
                val discarded = discard.await()

                assertEquals("stored", inboxResult)
                assertTrue(
                    "the durable Inbox reference must exist",
                    canonical in durableInboxPaths,
                )
                assertFalse(
                    "camera discard must not delete a file a durable Inbox reference names",
                    discarded,
                )
                assertTrue(
                    "the physically adopted file must survive the overlapping discard",
                    captured.exists(),
                )
                assertFalse(
                    "pending ownership must be settled by the adoption, not by a discard",
                    AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
                )
            } finally {
                AttachmentStorageManager.unregisterPendingAttachment(canonical)
                root.deleteRecursively()
            }
        }

    // ---- P13-01 durable-authority fail-closed (principle B/C/D) -------------

    @Test
    fun `discard preserves a file another durably referenced managed attachment owns`() = runBlocking {
        val root = Files.createTempDirectory("camera-discard-durable-owner").toFile()
        val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(32)) }
        val context = FakeAttachmentContext(root)
        try {
            AttachmentStorageManager.registerPendingAttachment(captured)
            // A pending registration is NOT exclusive physical ownership: the
            // same file may already be named by a durable row.
            val controller = CameraKnowledgeCaptureController(
                context = context,
                recognizeImage = unusedRecognition(),
                isDurablyReferenced = { true },
            )

            val deleted = controller.discard(captured.canonicalPath)

            assertFalse("durable authority must outrank local pending bookkeeping", deleted)
            assertTrue("durably referenced file must survive", captured.exists())
            assertTrue(
                "pending-copy registration must survive for a recoverable later pass",
                AttachmentStorageManager.isPendingAttachmentRegistered(captured.canonicalPath),
            )
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(captured.canonicalPath)
            root.deleteRecursively()
        }
    }

    @Test
    fun `discard fails closed when durable authority cannot be read`() = runBlocking {
        val root = Files.createTempDirectory("camera-discard-authority-unreadable").toFile()
        val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(32)) }
        val context = FakeAttachmentContext(root)
        try {
            AttachmentStorageManager.registerPendingAttachment(captured)
            val controller = CameraKnowledgeCaptureController(
                context = context,
                recognizeImage = unusedRecognition(),
                isDurablyReferenced = { null },
            )

            val deleted = controller.discard(captured.canonicalPath)

            assertFalse("unreadable durable authority must never license a delete", deleted)
            assertTrue("unreadable authority must preserve the physical file", captured.exists())
            assertTrue(
                "unreadable authority must keep the pending-copy registration",
                AttachmentStorageManager.isPendingAttachmentRegistered(captured.canonicalPath),
            )
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(captured.canonicalPath)
            root.deleteRecursively()
        }
    }
}
