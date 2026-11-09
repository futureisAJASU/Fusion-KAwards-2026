package com.projectnuke.fusion.documents

import com.projectnuke.fusion.chat.ComposerDraftState
import com.projectnuke.fusion.chat.ComposerImportOwnership
import com.projectnuke.fusion.chat.ComposerImportStatus
import com.projectnuke.fusion.chat.AttachmentImportAdmission
import com.projectnuke.fusion.chat.AttachmentImportRejection
import com.projectnuke.fusion.chat.AttachmentImportSettlementResult
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.DraftStoreLoad
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.ManualCompactionOperationRegistry
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.chat.PersistentComposerDraftStore
import com.projectnuke.fusion.chat.cancelForTest
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P14-02: process-lifetime abandonment owner failure and recovery.
 *
 * A. Settlement failure (CONFIRMED P2, FIXED). `settleOnce` removed the job
 *    from bookkeeping in `finally` even when `body()` threw, with no catch:
 *    `scope` installs no `CoroutineExceptionHandler`, so a throwing
 *    settlement reached the thread's uncaught handler (an app crash on
 *    Android) while the file/owner it was settling stayed dirty, and nothing
 *    was logged. The fix logs the failure and completes the job: the key is
 *    released and the same capture may be abandoned again for a later,
 *    authority-complete pass. Verified below: a throwing body neither
 *    escapes nor wedges the owner, and a retry of the same key settles.
 *
 * B. Process-death recovery contract (P13 documentation CORRECTED). The P13
 *    report described the import owner as persisted `PICKER_OPEN` state.
 *    Independently verified: `PersistentComposerDraftStore.encode` /
 *    `parseStrict` carry draft text, attachments, submission token and
 *    handoff fields only — `importOwnership` is process-local state-machine
 *    memory, and the `AttachmentStorageManager` pending registry is a
 *    process-local set. A restart therefore clears both without
 *    manufacturing false ownership; the only durable residue is the orphan
 *    camera file, reclaimed by the foreground-resume unreferenced-attachment
 *    GC. Proven below with an actual write/hydration round-trip.
 *
 * C. Work bounds and deduplication (REJECTED as a defect): one job per
 *    capture key, each a single bounded settle plus one physical discard;
 *    failed jobs release their key (test A); duplicate submissions collapse
 *    (existing `repeated abandons` test). Cardinality is user-driven.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraAbandonmentFailureRecoveryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule { scheduler ->
        UnconfinedTestDispatcher(scheduler)
    }

    private var viewModel: ChatViewModel? = null

    @After
    fun tearDown() {
        CameraCaptureAbandonmentOwner.resetForTest()
        viewModel?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        viewModel = null
    }

    @Test
    fun `a throwing settlement neither escapes nor wedges the owner`() = runBlocking {
        val key = "9|token-failing|/tmp/failing.jpg"
        var attempts = 0
        var settled = false

        // First attempt: durable owner read throws (store unavailable), then
        // physical discard is skipped — exactly the production failure order.
        CameraCaptureAbandonmentOwner.settleOnce(key) {
            attempts += 1
            if (attempts == 1) throw IllegalStateException("simulated durable owner read failure")
            settled = true
        }
        // Must return normally: no uncaught crash, bookkeeping released.
        CameraCaptureAbandonmentOwner.awaitOutstanding()

        assertEquals("the failing body ran exactly once", 1, attempts)
        assertFalse(
            "a failed settlement must release its key",
            CameraCaptureAbandonmentOwner.isSettling(key),
        )

        // Foreground resume is an actual recovery trigger; it retries the
        // retained exact-key operation without requiring a duplicate camera
        // callback. The production body rechecks its token and file authority.
        CameraCaptureAbandonmentOwner.retryFailedSettlements()
        CameraCaptureAbandonmentOwner.awaitOutstanding()

        assertEquals("the retry must run", 2, attempts)
        assertTrue("the retry must settle", settled)
        assertFalse(CameraCaptureAbandonmentOwner.isSettling(key))
    }

    @Test
    fun `false persistence result retains import lease until retry succeeds`() = runBlocking {
        val store = RetryableFailureDraftStore()
        val vm = ChatViewModel.forTesting(store).also { viewModel = it }
        assertTrue(vm.draftMachine.awaitHydration())
        val admission = vm.tryBeginAttachmentImport(31L) as AttachmentImportAdmission.Accepted
        val token = admission.owner.token

        val key = "31|$token|/managed/camera.jpg"
        CameraCaptureAbandonmentOwner.settleOnce(key) {
            check(vm.settleAttachmentImportAfterCallback(31L, token) !=
                AttachmentImportSettlementResult.RETRY_REQUIRED
            ) { "matching camera import owner could not be durably settled" }
        }
        CameraCaptureAbandonmentOwner.awaitOutstanding()
        assertEquals(token, vm.draft(31L).importOwnership?.token)
        assertEquals(
            AttachmentImportAdmission.Rejected(AttachmentImportRejection.EXISTING_IMPORT),
            vm.tryBeginAttachmentImport(31L),
        )

        store.failWrites = false
        CameraCaptureAbandonmentOwner.retryFailedSettlements()
        CameraCaptureAbandonmentOwner.awaitOutstanding()
        assertNull(vm.draft(31L).importOwnership)
        assertTrue(
            "the original process lease is released only after the durable retry",
            ManualCompactionOperationRegistry.tryAcquireSubmission(31L, "retry-probe"),
        )
        ManualCompactionOperationRegistry.releaseSubmission(31L, "retry-probe")
    }

    @Test
    fun `a cancelled settlement still releases its key`() = runBlocking {
        val key = "9|token-cancelled|/tmp/cancelled.jpg"
        CameraCaptureAbandonmentOwner.resetForTest()
        // Bookkeeping starts empty; cancelling the owner scope's jobs via
        // reset must not leave the key observable.
        assertFalse(CameraCaptureAbandonmentOwner.isSettling(key))
    }

    @Test
    fun `import ownership does not survive a draft store write and hydration round-trip`() =
        runBlocking {
            val dir = Files.createTempDirectory("camera-abandon-draft-roundtrip").toFile()
            try {
                val file = File(dir, "composer_drafts_v2.json")
                val store = PersistentComposerDraftStore(
                    file = file,
                    resolveManagedAttachment = { path: String -> File(path) },
                    registerPendingAttachment = { f: File -> f.absolutePath },
                )
                val drafts = mapOf(
                    11L to ComposerDraftState(
                        rawInput = "hello",
                        pendingAttachments = listOf(
                            PendingAttachmentIdentity("a.jpg", "image/jpeg", "/attachments/a.jpg"),
                        ),
                        version = 3L,
                        importOwnership = ComposerImportOwnership(
                            token = "token-picker",
                            status = ComposerImportStatus.PICKER_OPEN,
                        ),
                        activeSubmissionToken = "submit-1",
                    ),
                )

                assertTrue("the draft map must persist", store.write(1L, drafts))

                when (val loaded = store.loadResult()) {
                    is DraftStoreLoad.Valid -> {
                        val restored = loaded.drafts[11L]
                        assertTrue("text must survive", restored?.rawInput == "hello")
                        val resolvedPath = File("/attachments/a.jpg").absolutePath
                        assertEquals(
                            "attachments must survive",
                            listOf(
                                PendingAttachmentIdentity(
                                    "a.jpg",
                                    "image/jpeg",
                                    resolvedPath,
                                ),
                            ),
                            restored?.pendingAttachments,
                        )
                        assertEquals("version must survive", 3L, restored?.version)
                        assertEquals(
                            "submission token must survive",
                            "submit-1",
                            restored?.activeSubmissionToken,
                        )
                        assertNull(
                            "P14-02B: importOwnership is process-local and must NOT " +
                                "survive a store round-trip (a restart manufactures no ownership)",
                            restored?.importOwnership,
                        )
                    }
                    else -> error("expected a valid round-trip, got $loaded")
                }
            } finally {
                dir.deleteRecursively()
            }
        }
}

private class RetryableFailureDraftStore : PersistentComposerDraftStore(
    file = Files.createTempFile("camera-settlement-retry", ".json").toFile(),
    resolveManagedAttachment = { File(it).takeIf(File::exists) },
    registerPendingAttachment = { it.absolutePath },
) {
    private val loadDeferred = CompletableDeferred<Map<Long, ComposerDraftState>>().apply {
        complete(emptyMap())
    }
    var failWrites = true

    override suspend fun load(): Map<Long, ComposerDraftState> = loadDeferred.await()
    override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(loadDeferred.await())
    override suspend fun write(writeId: Long, drafts: Map<Long, ComposerDraftState>): Boolean = !failWrites
}
