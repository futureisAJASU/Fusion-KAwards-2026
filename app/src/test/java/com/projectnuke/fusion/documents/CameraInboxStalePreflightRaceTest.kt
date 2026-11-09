package com.projectnuke.fusion.documents

import android.content.Context
import android.content.ContextWrapper
import com.projectnuke.fusion.inbox.InboxCapture
import com.projectnuke.fusion.inbox.InboxCaptureType
import com.projectnuke.fusion.inbox.InboxWriteResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P14-04: camera-to-Inbox stale preflight before the mutation gate
 * (CONFIRMED P1, FIXED).
 *
 * `CameraOcrInboxCaptureController.save` resolves the managed source file and
 * verifies the picker owner BEFORE acquiring the process-wide
 * [AttachmentReferenceMutationCoordinator] gate. The camera abandonment
 * sequence (settle picker owner, then gated discard) can land exactly in that
 * window: the file is physically deleted, and a resumed admission that commits
 * from the stale snapshot stores an Inbox row naming the deleted source,
 * because `InboxCapturePolicy.prepare` validates metadata only and never
 * re-establishes that the physical managed file remains readable.
 *
 * RED evidence (recorded 2026-10-11, before the fix): the
 * `stale preflight` interleaving below with the verbatim old commit sequence
 * (gated commit, no revalidation) failed with
 * "P14-04 RED: committed Inbox row references a deleted camera file
 * (row=.../camera_ocr.jpg, file exists=false)".
 *
 * The fix revalidates the source, the pending registration and the picker
 * owner INSIDE the gate ([commitValidatedCapture]). This file keeps a
 * positive control (the unvalidated shape still dangles, proving the harness
 * is sensitive) and GREEN regressions that drive the real production commit
 * through the identical interleaving.
 */
class CameraInboxStalePreflightRaceTest {

    private class FakeAttachmentContext(dir: File) : ContextWrapper(null) {
        private val attachmentDir = dir
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = attachmentDir
        override fun getFilesDir(): File = attachmentDir
    }

    private fun unusedRecognition(): suspend (String, (Int, Int) -> Unit) -> com.projectnuke.fusion
        .retrieval.LocalOcrResult = { _, _ ->
        throw UnsupportedOperationException("recognition is not part of this interleaving")
    }

    private fun testCapture(canonicalPath: String) = InboxCapture(
        type = InboxCaptureType.OCR,
        title = "ocr",
        content = "recognized text",
        managedFileId = canonicalPath,
        managedMimeType = "image/jpeg",
        sourceHandoffEventId = "token-a",
    )

    /**
     * Positive control: the OLD commit shape (gated, but no in-gate
     * revalidation) commits a dangling row when abandonment lands between the
     * preflight and the gate. This proves the interleaving harness below is
     * capable of producing the defect, so the GREEN tests are sensitive.
     */
    @Test
    fun `positive control - unvalidated gated commit dangles when abandonment wins the race`() =
        runBlocking {
            val root = Files.createTempDirectory("camera-inbox-preflight-control").toFile()
            val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(32)) }
            val context = FakeAttachmentContext(root)
            val cameraController = CameraKnowledgeCaptureController(
                context = context,
                recognizeImage = unusedRecognition(),
                isDurablyReferenced = { false },
            )
            AttachmentStorageManager.registerPendingAttachment(captured)
            val committedRows = mutableMapOf<String, String>()
            var pickerOwnerLive = true
            try {
                val preflightDone = CompletableDeferred<Unit>()
                val abandonDone = CompletableDeferred<Unit>()

                val admission = async(Dispatchers.Unconfined) {
                    val managedFile = AttachmentStorageManager
                        .resolveManagedAttachment(context, captured.canonicalPath)
                        ?: return@async InboxWriteResult.Failed
                    val canonicalPath = managedFile.canonicalPath
                    if (!pickerOwnerLive) return@async InboxWriteResult.Failed
                    val capture = testCapture(canonicalPath)
                    preflightDone.complete(Unit)
                    abandonDone.await()
                    // Verbatim old production shape: gated commit, NO revalidation.
                    AttachmentReferenceMutationCoordinator.withMutation {
                        withContext(NonCancellable) {
                            committedRows["token-a"] = capture.managedFileId!!
                            AttachmentStorageManager
                                .unregisterPendingAttachment(captured.canonicalPath)
                            InboxWriteResult.Stored("token-a")
                        }
                    }
                }

                val abandon = async(Dispatchers.Unconfined) {
                    preflightDone.await()
                    pickerOwnerLive = false
                    val discarded = withContext(Dispatchers.Unconfined) {
                        cameraController.discard(captured.canonicalPath)
                    }
                    assertTrue("abandonment must be licensed to delete the file here", discarded)
                    abandonDone.complete(Unit)
                }

                abandon.await()
                val result = admission.await()

                assertTrue(
                    "control: the unvalidated shape commits the row",
                    result is InboxWriteResult.Stored,
                )
                assertFalse("control: the source file is deleted", captured.exists())
                assertTrue(
                    "control: the harness produces the dangling row",
                    committedRows["token-a"] != null && !File(committedRows["token-a"]!!).exists(),
                )
            } finally {
                AttachmentStorageManager.unregisterPendingAttachment(captured.canonicalPath)
                root.deleteRecursively()
            }
        }

    /**
     * GREEN: the identical interleaving through the production
     * [commitValidatedCapture] refuses the commit. No Inbox row may reference
     * the deleted source.
     */
    @Test
    fun `production commit refuses a stale preflight after abandonment deleted the source`() =
        runBlocking {
            val root = Files.createTempDirectory("camera-inbox-preflight-green").toFile()
            val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(32)) }
            val context = FakeAttachmentContext(root)
            val cameraController = CameraKnowledgeCaptureController(
                context = context,
                recognizeImage = unusedRecognition(),
                isDurablyReferenced = { false },
            )
            AttachmentStorageManager.registerPendingAttachment(captured)
            val committedRows = mutableMapOf<String, String>()
            var pickerOwnerLive = true
            try {
                val canonical = captured.canonicalPath
                val preflightDone = CompletableDeferred<Unit>()
                val abandonDone = CompletableDeferred<Unit>()

                val admission = async(Dispatchers.Unconfined) {
                    // Preflight (outside the gate), as `save` performs it.
                    val managedFile = AttachmentStorageManager
                        .resolveManagedAttachment(context, captured.canonicalPath)
                        ?: return@async InboxWriteResult.Failed
                    val preflightCanonical = managedFile.canonicalPath
                    if (!pickerOwnerLive) return@async InboxWriteResult.Failed
                    val capture = testCapture(preflightCanonical)
                    preflightDone.complete(Unit)
                    abandonDone.await()
                    // Production gated commit with in-gate revalidation.
                    commitValidatedCapture(
                        conversationId = 7L,
                        eventId = "token-a",
                        path = captured.canonicalPath,
                        canonicalPath = preflightCanonical,
                        capture = capture,
                        now = 1L,
                        ownerMatches = { pickerOwnerLive },
                        captureStored = {
                            committedRows["token-a"] = it.managedFileId!!
                            InboxWriteResult.Stored("token-a")
                        },
                        resolveManaged = {
                            AttachmentStorageManager.resolveManagedAttachment(context, it)
                        },
                        isPendingRegistered = {
                            AttachmentStorageManager.isPendingAttachmentRegistered(it)
                        },
                        transferOwnership = {
                            AttachmentStorageManager.unregisterPendingAttachment(canonical)
                        },
                    )
                }

                val abandon = async(Dispatchers.Unconfined) {
                    preflightDone.await()
                    pickerOwnerLive = false
                    val discarded = withContext(Dispatchers.Unconfined) {
                        cameraController.discard(captured.canonicalPath)
                    }
                    assertTrue("abandonment must be licensed to delete the file here", discarded)
                    abandonDone.complete(Unit)
                }

                abandon.await()
                val result = admission.await()

                assertTrue(
                    "stale preflight must be refused, got $result",
                    result is InboxWriteResult.Failed,
                )
                assertNull("no Inbox row may be committed", committedRows["token-a"])
                assertFalse("the source file stays deleted by its abandonment", captured.exists())
            } finally {
                AttachmentStorageManager.unregisterPendingAttachment(captured.canonicalPath)
                root.deleteRecursively()
            }
        }

    @Test
    fun `production commit stores when nothing disturbed the preflight`() = runBlocking {
        val root = Files.createTempDirectory("camera-inbox-preflight-quiet").toFile()
        val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(32)) }
        val context = FakeAttachmentContext(root)
        val canonical = captured.canonicalPath
        AttachmentStorageManager.registerPendingAttachment(captured)
        val committedRows = mutableMapOf<String, String>()
        try {
            val result = commitValidatedCapture(
                conversationId = 7L,
                eventId = "token-a",
                path = canonical,
                canonicalPath = canonical,
                capture = testCapture(canonical),
                now = 1L,
                ownerMatches = { true },
                captureStored = {
                    committedRows["token-a"] = it.managedFileId!!
                    InboxWriteResult.Stored("token-a")
                },
                resolveManaged = { AttachmentStorageManager.resolveManagedAttachment(context, it) },
                isPendingRegistered = { AttachmentStorageManager.isPendingAttachmentRegistered(it) },
                transferOwnership = { AttachmentStorageManager.unregisterPendingAttachment(canonical) },
            )

            assertTrue("undisturbed admission must store, got $result", result is InboxWriteResult.Stored)
            assertEquals(canonical, committedRows["token-a"])
            assertTrue("adopted file must survive", captured.exists())
            assertFalse(
                "pending ownership must be settled by the adoption",
                AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
            )
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(canonical)
            root.deleteRecursively()
        }
    }

    @Test
    fun `production commit refuses when the picker owner was revoked before the gate`() =
        runBlocking {
            val root = Files.createTempDirectory("camera-inbox-preflight-owner").toFile()
            val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(32)) }
            val context = FakeAttachmentContext(root)
            val canonical = captured.canonicalPath
            AttachmentStorageManager.registerPendingAttachment(captured)
            val committedRows = mutableMapOf<String, String>()
            var transferCalls = 0
            try {
                val result = commitValidatedCapture(
                    conversationId = 7L,
                    eventId = "token-a",
                    path = canonical,
                    canonicalPath = canonical,
                    capture = testCapture(canonical),
                    now = 1L,
                    ownerMatches = { false },
                    captureStored = {
                        committedRows["token-a"] = it.managedFileId!!
                        InboxWriteResult.Stored("token-a")
                    },
                    resolveManaged = {
                        AttachmentStorageManager.resolveManagedAttachment(context, it)
                    },
                    isPendingRegistered = {
                        AttachmentStorageManager.isPendingAttachmentRegistered(it)
                    },
                    transferOwnership = { transferCalls += 1 },
                )

                assertTrue("revoked owner must be refused, got $result", result is InboxWriteResult.Failed)
                assertNull("no Inbox row may be committed", committedRows["token-a"])
                assertEquals("no ownership may be transferred", 0, transferCalls)
                assertTrue("the live source file must be preserved", captured.exists())
                assertTrue(
                    "the pending registration must survive for a later pass",
                    AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
                )
            } finally {
                AttachmentStorageManager.unregisterPendingAttachment(canonical)
                root.deleteRecursively()
            }
        }

    @Test
    fun `production commit refuses when the source file vanished but pending is still registered`() =
        runBlocking {
            val root = Files.createTempDirectory("camera-inbox-preflight-missing").toFile()
            val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(32)) }
            val context = FakeAttachmentContext(root)
            val canonical = captured.canonicalPath
            // Pending registration without a physical file: exactly what a
            // racy external delete (or a half-finished cleanup) leaves behind.
            AttachmentStorageManager.registerPendingAttachment(captured)
            assertTrue(captured.delete())
            val committedRows = mutableMapOf<String, String>()
            try {
                val result = commitValidatedCapture(
                    conversationId = 7L,
                    eventId = "token-a",
                    path = canonical,
                    canonicalPath = canonical,
                    capture = testCapture(canonical),
                    now = 1L,
                    ownerMatches = { true },
                    captureStored = {
                        committedRows["token-a"] = it.managedFileId!!
                        InboxWriteResult.Stored("token-a")
                    },
                    resolveManaged = {
                        AttachmentStorageManager.resolveManagedAttachment(context, it)
                    },
                    isPendingRegistered = {
                        AttachmentStorageManager.isPendingAttachmentRegistered(it)
                    },
                    transferOwnership = { AttachmentStorageManager.unregisterPendingAttachment(canonical) },
                )

                assertTrue("missing source must be refused, got $result", result is InboxWriteResult.Failed)
                assertNull("no Inbox row may reference the missing file", committedRows["token-a"])
            } finally {
                AttachmentStorageManager.unregisterPendingAttachment(canonical)
                root.deleteRecursively()
            }
        }
}
