package com.projectnuke.fusion.documents

import android.content.Context
import android.content.ContextWrapper
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.ComposerDraftState
import com.projectnuke.fusion.chat.DurableDraftReferences
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.chat.PersistentComposerDraftStore
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.util.AttachmentReferenceMutationCoordinator
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * P14-05: camera-to-chat draft adoption versus abandonment
 * (CONFIRMED P1, FIXED).
 *
 * Camera-to-chat admission ran as two separate draft state-machine commands
 * with a suspension gap between them: `beginAttachmentCopy` (PICKER_OPEN ->
 * COPYING) and `completeAttachmentImport` (COPYING -> durable draft
 * attachment). A camera abandonment landing in that gap observed no durable
 * draft reference (the attachment is committed only by the second command)
 * and a non-PICKER_OPEN owner (so the picker settlement was skipped),
 * deleted the pending file under the gate, and the resumed second command
 * then committed a draft attachment naming the deleted file.
 *
 * The fix is [ChatViewModel.adoptCameraAttachment]: both commands run inside
 * ONE reference-mutation gate transaction, so the abandonment's gated
 * discard either runs first (it then settles the still-PICKER_OPEN owner and
 * `begin` refuses) or after the adoption (the durable draft file names the
 * path and the discard preserves it). The abandonment settlement was also
 * broadened from PICKER_OPEN-only to same-token-any-status, so an
 * abandonment after `begin` still releases the owner instead of stranding a
 * COPYING owner that would reject every later import.
 *
 * The harness uses the real [ChatViewModel] (real state machine + real
 * temp-file draft store), the real gate, the real
 * [CameraKnowledgeCaptureController.discard], the real pending registry, and
 * the production draft-file authority
 * ([PersistentComposerDraftStore.durableDraftReferences]). Only the Room
 * project/inbox/message authorities are absent (host JVM); the
 * Room-vs-host distinction is recorded in the P14 report.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraChatAdoptionAbandonmentRaceTest {

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

    private inner class Fixture(val root: File) {
        val context = FakeAttachmentContext(root)
        val storeFile = File(root, "composer_drafts_v2.json")
        val store = PersistentComposerDraftStore(
            file = storeFile,
            resolveManagedAttachment = { path ->
                AttachmentStorageManager.resolveManagedAttachment(context, path)
            },
            registerPendingAttachment = { AttachmentStorageManager.registerPendingAttachment(it) },
        )
        val authorityHasPath: suspend (String) -> Boolean? = { path ->
            when (val refs = PersistentComposerDraftStore.durableDraftReferences(context)) {
                is DurableDraftReferences.Available ->
                    refs.paths.any { it == File(path).canonicalPath }
                is DurableDraftReferences.Unknown -> null
            }
        }
        val cameraController = CameraKnowledgeCaptureController(
            context = context,
            recognizeImage = unusedRecognition(),
            isDurablyReferenced = authorityHasPath,
        )
        val captured = root.resolve("camera_ocr.jpg").apply { writeBytes(ByteArray(32)) }
        val canonical: String = captured.canonicalPath
    }

    private fun Fixture.draftPaths(): Set<String> =
        when (val refs = PersistentComposerDraftStore.durableDraftReferences(context)) {
            is DurableDraftReferences.Available -> refs.paths
            is DurableDraftReferences.Unknown -> error("draft authority must stay readable")
        }

    /**
     * Raw durable-draft evidence: the store file's JSON for [conversationId]
     * names [canonicalPath]. Unlike the filtered authority (which only
     * reports physically present files), the raw artifact shows a committed
     * reference even after its file was deleted — exactly the dangling state.
     */
    private fun Fixture.rawDraftHasAttachment(conversationId: Long, canonicalPath: String): Boolean {
        val array = org.json.JSONArray(storeFile.readText())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            if (item.getLong("id") != conversationId) continue
            val attachments = item.getJSONArray("attachments")
            for (j in 0 until attachments.length()) {
                val path = attachments.getJSONObject(j).getString("path")
                if (runCatching { File(path).canonicalPath }.getOrNull() == canonicalPath) {
                    return true
                }
            }
        }
        return false
    }

    private suspend fun Fixture.attachment(name: String, path: String) =
        PendingAttachmentIdentity(name = name, mimeType = "image/jpeg", localPath = path)

    /** Production abandonment shape (post-P14-05: same-token-any-status settle). */
    private suspend fun Fixture.abandon(
        vm: ChatViewModel,
        conversationId: Long,
        token: String,
    ): Pair<Boolean, Boolean> {
        val owner = vm.draft(conversationId).importOwnership
        val settled: Boolean
        if (owner?.token == token) {
            vm.settleAttachmentImportAfterCallback(conversationId, token)
            mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
            settled = vm.draft(conversationId).importOwnership == null
        } else {
            settled = owner == null
        }
        val discarded = cameraController.discard(canonical)
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        return settled to discarded
    }

    private suspend fun newVm(fixture: Fixture): ChatViewModel =
        ChatViewModel(draftStoreOverride = fixture.store).also {
            viewModels += it
            // Startup synchronization: with a real store the owner's
            // hydration suspends on real IO, which `advanceUntilIdle` cannot
            // pump. A reply-based command completes only after hydration and
            // every prior command are processed, so every later publish is
            // guaranteed visible — no late-applied owner may surface
            // mid-interleaving and invalidate the barriers below.
            assertTrue("draft machine must start", it.syncDraftMachine())
        }

    /**
     * FIFO synchronization with the serialized draft owner. Reply-based
     * commands complete only after every previously accepted command has
     * been processed — including fire-and-forget publishes and any real-IO
     * draft write the owner is suspended in, which a bare
     * `advanceUntilIdle` cannot observe. Returns false when the owner no
     * longer accepts commands. Each probe carries a fresh marker so the
     * transform always applies (an unchanged transform replies false).
     */
    private var syncSeq = 0L

    private suspend fun ChatViewModel.syncDraftMachine(): Boolean {
        syncSeq += 1
        val marker = syncSeq
        return withTimeoutOrNull(SYNC_TIMEOUT_MS) {
            draftMachine.updateImmediate(SYNC_CONVERSATION_ID) {
                it.copy(rawInput = "sync-$marker")
            }
        } == true
    }

    private companion object {
        const val SYNC_CONVERSATION_ID = 999_999L
        const val SYNC_TIMEOUT_MS = 15_000L
    }

    /**
     * Positive control: the OLD camera sequence (`begin`, gap, `complete`
     * with no gate) commits a dangling draft attachment when abandonment
     * lands in the gap. Proves the harness reproduces the defect.
     */
    @Test
    fun `control - ungated begin-then-complete dangles when abandonment lands in the gap`() =
        runBlocking {
            val fixture = Fixture(Files.createTempDirectory("camera-chat-control").toFile())
            try {
                val vm = newVm(fixture)
                val conversationId = 21L
                val owner = vm.beginAttachmentImport(conversationId)
                assertTrue("owner publish must apply", vm.syncDraftMachine())
                assertTrue(vm.pendingPickerImportMatches(conversationId, owner.token))
                AttachmentStorageManager.registerPendingAttachment(fixture.captured)
                val began = CompletableDeferred<Unit>()
                val abandoned = CompletableDeferred<Unit>()

                val adoption = async(Dispatchers.Unconfined) {
                    assertTrue(vm.beginAttachmentCopy(conversationId, owner.token))
                    mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
                    began.complete(Unit)
                    abandoned.await()
                    vm.completeAttachmentImport(
                        conversationId,
                        owner.token,
                        listOf(fixture.attachment("camera_ocr.jpg", fixture.canonical)),
                    )
                }
                val abandon = async(Dispatchers.Unconfined) {
                    began.await()
                    // Old settle: PICKER_OPEN only — skipped, owner is COPYING.
                    val pending = vm.pendingPickerImport()
                    if (pending?.first == conversationId && pending.second.token == owner.token) {
                        vm.settleAttachmentImportAfterCallback(conversationId, owner.token)
                    }
                    val discarded = withContext(Dispatchers.Unconfined) {
                        fixture.cameraController.discard(fixture.canonical)
                    }
                    assertTrue("abandonment must be licensed mid-gap", discarded)
                    abandoned.complete(Unit)
                }

                abandon.await()
                val accepted = adoption.await()
                mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

                assertTrue("control: the late commit still accepts", accepted)
                assertTrue(
                    "control: the durable draft file names the path",
                    fixture.rawDraftHasAttachment(conversationId, fixture.canonical),
                )
                assertFalse(
                    "control: the harness produces the dangling draft attachment",
                    fixture.captured.exists(),
                )
            } finally {
                AttachmentStorageManager.unregisterPendingAttachment(fixture.captured.canonicalPath)
                fixture.root.deleteRecursively()
            }
        }

    @Test
    fun `gated adoption refuses when abandonment settled first`() = runBlocking {
        val fixture = Fixture(Files.createTempDirectory("camera-chat-abandon-first").toFile())
        try {
            val vm = newVm(fixture)
            val conversationId = 22L
            val owner = vm.beginAttachmentImport(conversationId)
            assertTrue("owner publish must apply", vm.syncDraftMachine())
            assertTrue(vm.pendingPickerImportMatches(conversationId, owner.token))
            AttachmentStorageManager.registerPendingAttachment(fixture.captured)

            val (settled, discarded) = fixture.abandon(vm, conversationId, owner.token)
            mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
            assertTrue(
                "abandonment must settle the owner, draft=${vm.draft(conversationId)}",
                settled,
            )
            assertTrue("abandonment must be licensed to delete the file", discarded)
            assertFalse("the file is released by the licensed abandon", fixture.captured.exists())

            val accepted = vm.adoptCameraAttachment(
                conversationId,
                owner.token,
                listOf(fixture.attachment("camera_ocr.jpg", fixture.canonical)),
            )
            mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

            assertFalse("adoption must refuse after the owner was settled", accepted)
            assertFalse(
                "no draft attachment may be committed",
                fixture.rawDraftHasAttachment(conversationId, fixture.canonical),
            )
            assertNull(vm.draft(conversationId).importOwnership)
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(fixture.captured.canonicalPath)
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `gated adoption preserves the file against a later abandonment`() = runBlocking {
        val fixture = Fixture(Files.createTempDirectory("camera-chat-adopt-first").toFile())
        try {
            val vm = newVm(fixture)
            val conversationId = 23L
            val owner = vm.beginAttachmentImport(conversationId)
            assertTrue("owner publish must apply", vm.syncDraftMachine())
            assertTrue(vm.pendingPickerImportMatches(conversationId, owner.token))
            AttachmentStorageManager.registerPendingAttachment(fixture.captured)

            val accepted = vm.adoptCameraAttachment(
                conversationId,
                owner.token,
                listOf(fixture.attachment("camera_ocr.jpg", fixture.canonical)),
            )
            mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
            assertTrue("undisturbed adoption must accept", accepted)

            fixture.abandon(vm, conversationId, owner.token)
            mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

            assertTrue("the adopted file must survive the abandon", fixture.captured.exists())
            assertTrue(
                "the committed draft attachment must remain",
                fixture.canonical in fixture.draftPaths(),
            )
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(fixture.captured.canonicalPath)
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `contended adoption and abandonment converge on a consistent state`() = runBlocking {
        val fixture = Fixture(Files.createTempDirectory("camera-chat-contended").toFile())
        try {
            val vm = newVm(fixture)
            val conversationId = 24L
            val owner = vm.beginAttachmentImport(conversationId)
            assertTrue("owner publish must apply", vm.syncDraftMachine())
            assertTrue(vm.pendingPickerImportMatches(conversationId, owner.token))
            AttachmentStorageManager.registerPendingAttachment(fixture.captured)

            // Hold the gate so both sides queue on the real serialization
            // seam, then release: whatever order the mutex grants, the end
            // state must satisfy the file/reference integrity invariant.
            val hold = CompletableDeferred<Unit>()
            val holder = async(Dispatchers.Unconfined) {
                AttachmentReferenceMutationCoordinator.withMutation {
                    hold.await()
                }
            }
            // Ensure the holder owns the gate before queuing contenders.
            withContext(Dispatchers.Unconfined) { kotlinx.coroutines.yield() }
            val adoption = async(Dispatchers.Unconfined) {
                vm.adoptCameraAttachment(
                    conversationId,
                    owner.token,
                    listOf(fixture.attachment("camera_ocr.jpg", fixture.canonical)),
                )
            }
            val abandon = async(Dispatchers.Unconfined) {
                fixture.abandon(vm, conversationId, owner.token)
            }
            withContext(Dispatchers.Unconfined) { kotlinx.coroutines.yield() }
            hold.complete(Unit)
            val accepted = adoption.await()
            abandon.await()
            holder.await()
            mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

            // The abandonment's ungated settle always runs before either side
            // can begin under the gate, so the adoption must refuse; the
            // invariant holds in every order.
            assertFalse("contended adoption must refuse once the owner is settled", accepted)
            assertFalse(
                "no draft attachment may be committed",
                fixture.rawDraftHasAttachment(conversationId, fixture.canonical),
            )
            assertNull(vm.draft(conversationId).importOwnership)
            assertTrue(
                "draft machine must survive contention, draft=${vm.draft(conversationId)}",
                vm.draftMachine.awaitHydration(),
            )
            // Retry stays possible: the owner is settled, not stranded.
            val retry = vm.beginAttachmentImport(conversationId)
            // Synchronize with the serialized owner through a reply-based
            // command: it completes only after every prior command (including
            // the fire-and-forget retry publish) has been processed. A bare
            // `advanceUntilIdle` cannot do this — the owner may be suspended
            // in a real-IO draft write that only a channel reply observes.
            assertTrue(
                "draft machine must still process commands after contention",
                vm.syncDraftMachine(),
            )
            assertEquals(
                "retry owner must be published, got ${vm.draft(conversationId)}",
                retry.token,
                vm.draft(conversationId).importOwnership?.token,
            )
            assertTrue(vm.pendingPickerImportMatches(conversationId, retry.token))
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(fixture.captured.canonicalPath)
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `abandonment after begin releases a COPYING owner and refuses the late commit`() =
        runBlocking {
            val fixture = Fixture(Files.createTempDirectory("camera-chat-copying").toFile())
            try {
                val vm = newVm(fixture)
                val conversationId = 25L
                val owner = vm.beginAttachmentImport(conversationId)
            assertTrue("owner publish must apply", vm.syncDraftMachine())
            assertTrue(vm.pendingPickerImportMatches(conversationId, owner.token))
                AttachmentStorageManager.registerPendingAttachment(fixture.captured)
                assertTrue(vm.beginAttachmentCopy(conversationId, owner.token))
                mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

                fixture.abandon(vm, conversationId, owner.token)
                mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

                assertNull(
                    "a COPYING owner of the same token must be released",
                    vm.draft(conversationId).importOwnership,
                )
                assertFalse("the unreferenced file is released", fixture.captured.exists())
                val late = vm.completeAttachmentImport(
                    conversationId,
                    owner.token,
                    listOf(fixture.attachment("camera_ocr.jpg", fixture.canonical)),
                )
                assertFalse("the late commit must refuse", late)
                assertFalse(
                    "no draft attachment may be committed",
                    fixture.rawDraftHasAttachment(conversationId, fixture.canonical),
                )
            } finally {
                AttachmentStorageManager.unregisterPendingAttachment(fixture.captured.canonicalPath)
                fixture.root.deleteRecursively()
            }
        }
}
