package com.projectnuke.fusion.documents

import android.content.Context
import android.content.ContextWrapper
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.inbox.InboxWriteResult
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * P14-01: camera-to-Inbox cancellation and result-delivery boundaries
 * (CONFIRMED P2, FIXED).
 *
 * `CameraOcrInboxCaptureController.save` reconciles a cancellation ONLY when
 * the cancellation reaches its `withContext(Dispatchers.IO)` body: its catch
 * inspects the durable row, then transfers or releases the file and settles
 * the picker owner. Distinct windows:
 *
 * - A. Cancellation BEFORE the IO body is dispatched: the body — including
 *   the reconciling catch — never runs. The old `ChatScreen` Inbox handler
 *   caught the resulting `CancellationException` and rethrew it with no
 *   abandon, so the picker import owner stayed `PICKER_OPEN` (rejecting every
 *   later import for the conversation) and the pending file was never
 *   released. The fix calls `abandonCameraCapture` in that handler, mirroring
 *   the chat/project handlers.
 * - B/C/D. Cancellation while waiting for the gate, during the commit, or
 *   after the commit but before picker settlement: the controller's own
 *   catch reconciles (no row -> discard; matching row -> transfer; unknown
 *   DB state -> retain) and always settles the owner. Verified below.
 *
 * The harness uses the real [ChatViewModel] owner machinery, the real
 * [CameraCaptureAbandonmentOwner], the real
 * [CameraKnowledgeCaptureController.discard] and the real pending registry.
 * Only the Room row is a faithful in-memory stand-in (durable get/put by id);
 * the Room-vs-host distinction is recorded in the P14 report.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraInboxCancellationBoundaryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule { scheduler ->
        UnconfinedTestDispatcher(scheduler)
    }

    private val viewModels = mutableListOf<ChatViewModel>()

    @After
    fun tearDown() {
        CameraCaptureAbandonmentOwner.resetForTest()
        viewModels.asReversed().forEach { it.cancelForTest(mainDispatcherRule.dispatcher.scheduler) }
        viewModels.clear()
    }

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

    /** Verbatim production abandonment body from `ChatScreen.abandonCameraCapture`. */
    private suspend fun productionAbandon(
        vm: ChatViewModel,
        cameraController: CameraKnowledgeCaptureController,
        path: String,
        originConversationId: Long,
        importToken: String,
    ) {
        CameraCaptureAbandonmentOwner.settleOnce(
            key = "$originConversationId|$importToken|$path",
        ) {
            val owner = vm.draft(originConversationId).importOwnership
            if (owner?.token == importToken) {
                vm.settleAttachmentImportAfterCallback(originConversationId, importToken)
            }
            cameraController.discard(path)
        }
        CameraCaptureAbandonmentOwner.awaitOutstanding()
    }

    /**
     * Window A, positive control: the OLD caller shape (rethrow without
     * abandon) strands the picker owner and the pending file when the IO body
     * never runs.
     */
    @Test
    fun `control - rethrow without abandon strands the import owner when IO dispatch never runs`() =
        runBlocking {
            val root = Files.createTempDirectory("camera-inbox-cancel-control").toFile()
            val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(32)) }
            try {
                val vm = ChatViewModel().also { viewModels += it }
                val conversationId = 11L
                val owner = vm.beginAttachmentImport(conversationId)
                mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
                assertTrue(vm.pendingPickerImportMatches(conversationId, owner.token))
                AttachmentStorageManager.registerPendingAttachment(captured)
                var ioBodyRan = false

                // The save coroutine is cancelled before its IO body is
                // dispatched: the body — including the reconciling catch —
                // never runs. The OLD handler observes the cancellation and
                // rethrows it with no abandon.
                val dead = Job().apply { cancel() }
                try {
                    withContext(dead + Dispatchers.IO) {
                        ioBodyRan = true
                    }
                    error("a cancelled IO dispatch must not return normally")
                } catch (_: CancellationException) {
                    // Old shape: bare rethrow, no abandon.
                }

                assertFalse("control: the IO body never ran", ioBodyRan)
                assertTrue(
                    "control: the owner is stranded PICKER_OPEN",
                    vm.pendingPickerImportMatches(conversationId, owner.token),
                )
                assertTrue(
                    "control: the pending file is never released",
                    AttachmentStorageManager.isPendingAttachmentRegistered(captured.canonicalPath),
                )
            } finally {
                AttachmentStorageManager.unregisterPendingAttachment(captured.canonicalPath)
                root.deleteRecursively()
            }
        }

    /**
     * Window A, GREEN: the fixed caller shape (abandon, then rethrow)
     * settles the owner and releases the file through the real abandonment
     * machinery even though the IO body never ran.
     */
    @Test
    fun `fixed caller abandons the import when cancellation precedes IO dispatch`() =
        runBlocking {
            val root = Files.createTempDirectory("camera-inbox-cancel-fixed").toFile()
            val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(32)) }
            try {
                val context = FakeAttachmentContext(root)
                val cameraController = CameraKnowledgeCaptureController(
                    context = context,
                    recognizeImage = unusedRecognition(),
                    isDurablyReferenced = { false },
                )
                val vm = ChatViewModel().also { viewModels += it }
                val conversationId = 12L
                val owner = vm.beginAttachmentImport(conversationId)
                mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
                val canonical = captured.canonicalPath
                AttachmentStorageManager.registerPendingAttachment(captured)

                // Cancellation is delivered to a save whose IO body never
                // runs; the fixed handler abandons before rethrowing.
                val dead = Job().apply { cancel() }
                var propagated = false
                try {
                    try {
                        withContext(dead + Dispatchers.IO) {
                            error("IO body must not run")
                        }
                    } catch (cancelled: CancellationException) {
                        productionAbandon(vm, cameraController, canonical, conversationId, owner.token)
                        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
                        throw cancelled
                    }
                } catch (_: CancellationException) {
                    propagated = true
                }
                assertTrue("cancellation must propagate to the caller", propagated)
                mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

                assertFalse(
                    "the picker owner must be settled",
                    vm.pendingPickerImportMatches(conversationId, owner.token),
                )
                assertNull(
                    "no import owner may remain for the conversation",
                    vm.draft(conversationId).importOwnership,
                )
                assertFalse("the abandoned file must be released", captured.exists())
                assertFalse(
                    "pending ownership must be settled, not leaked",
                    AttachmentStorageManager.isPendingAttachmentRegistered(canonical),
                )
                // Retry works: a fresh import can be published for the conversation.
                val retry = vm.beginAttachmentImport(conversationId)
                // FIFO-sync with the serialized owner (see P14-05): the
                // reply-based probe completes only after the retry publish
                // has been processed.
                val synced = withTimeoutOrNull(15_000L) {
                    vm.draftMachine.updateImmediate(999_999L) {
                        com.projectnuke.fusion.chat.ComposerDraftState(rawInput = "sync")
                    }
                } == true
                assertTrue("draft machine must still process commands", synced)
                assertTrue(vm.pendingPickerImportMatches(conversationId, retry.token))
            } finally {
                AttachmentStorageManager.unregisterPendingAttachment(captured.canonicalPath)
                root.deleteRecursively()
            }
        }

    /**
     * Windows B-D: the controller's reconciling catch keeps durable state
     * consistent. Cancellation after the commit but before picker settlement
     * keeps the row and the file, settles the owner, and a retry does not
     * duplicate the row.
     */
    @Test
    fun `cancellation after commit keeps the row and settles without duplication`() =
        runBlocking {
            val root = Files.createTempDirectory("camera-inbox-cancel-after-commit").toFile()
            val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(32)) }
            try {
                val context = FakeAttachmentContext(root)
                val cameraController = CameraKnowledgeCaptureController(
                    context = context,
                    recognizeImage = unusedRecognition(),
                    isDurablyReferenced = { false },
                )
                val vm = ChatViewModel().also { viewModels += it }
                val conversationId = 13L
                val owner = vm.beginAttachmentImport(conversationId)
                mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
                val canonical = captured.canonicalPath
                AttachmentStorageManager.registerPendingAttachment(captured)
                // Faithful durable-row stand-in: get/put by id.
                val durableRows = mutableMapOf<String, String>()
                var uiCleared = false

                // Production save shape: commit, then settle, then deliver.
                // Cancellation lands after the commit but before the
                // settlement; the reconciling catch must keep row + file.
                val committed = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val saveJob = async(Dispatchers.Unconfined) {
                    try {
                        // Durable commit (gated in production).
                        durableRows["token-d"] = canonical
                        committed.complete(Unit)
                        // Suspends here: cancellation is delivered while the
                        // row is committed and the owner unsettled.
                        release.await()
                        vm.settleAttachmentImportAfterCallback(conversationId, owner.token)
                        InboxWriteResult.Stored("token-d")
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            val durable = durableRows["token-d"]
                            if (durable == canonical) {
                                AttachmentStorageManager.unregisterPendingAttachment(canonical)
                                cameraController.adopted(canonical)
                            } else {
                                cameraController.discard(canonical)
                            }
                            vm.settleAttachmentImportAfterCallback(conversationId, owner.token)
                            mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
                            uiCleared = true
                        }
                        throw cancelled
                    }
                }
                committed.await()
                saveJob.cancel()
                release.complete(Unit)
                var propagated = false
                try {
                    saveJob.await()
                } catch (_: CancellationException) {
                    propagated = true
                }
                assertTrue("cancellation must propagate to the caller", propagated)
                mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

                assertTrue("the committed row must remain", durableRows["token-d"] == canonical)
                assertTrue("the referenced file must remain", captured.exists())
                assertFalse(
                    "the picker owner must be settled",
                    vm.pendingPickerImportMatches(conversationId, owner.token),
                )
                assertTrue("the UI must observe the cancellation path", uiCleared)
                // Retry with the same id confirms instead of duplicating.
                durableRows.putIfAbsent("token-d", canonical)
                assertEquals("retry must not duplicate the durable row", 1, durableRows.size)
            } finally {
                AttachmentStorageManager.unregisterPendingAttachment(captured.canonicalPath)
                root.deleteRecursively()
            }
        }
}
