package com.projectnuke.fusion.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.viewModelScope
import com.projectnuke.fusion.ui.CommittedDraftReconciliationDebt
import com.projectnuke.fusion.ui.CommittedDraftReconciliationDebtStore
import com.projectnuke.fusion.ui.DraftReconciliationOwner
import com.projectnuke.fusion.ui.DraftReconciliationResult
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelDraftHydrationTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    private val testDispatcher get() = mainDispatcherRule.dispatcher
    private var vm: ChatViewModel? = null

    @After
    fun tearDown() {
        vm?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        vm = null
    }

    @Test
    fun `clear during load tombstones cannot be resurrected by hydration`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }

        vm.updateDraftText(1L, "draft during load")
        val clear = async { vm.clearDraft(1L) }
        testDispatcher.scheduler.runCurrent()
        assertTrue(clear.isActive)

        storeWrapper.releaseLoad(mapOf(1L to ComposerDraftState(rawInput = "stale restored", version = 1)))
        assertTrue(clear.await())

        assertEquals("", vm.draft(1L).rawInput)
        assertTrue(vm.draft(1L).pendingAttachments.isEmpty())
    }

    @Test
    fun `update during load wins over restored data`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }

        vm.updateDraftText(1L, "local update")

        storeWrapper.releaseLoad(mapOf(1L to ComposerDraftState(rawInput = "stale restored", version = 1)))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("local update", vm.draft(1L).rawInput)
    }

    @Test
    fun `stale load completion cannot restore a deleted draft`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }

        vm.updateDraftText(1L, "draft")
        val clear = async { vm.clearDraft(1L) }
        testDispatcher.scheduler.runCurrent()

        storeWrapper.releaseLoad(mapOf(1L to ComposerDraftState(rawInput = "stale", version = 1)))
        assertTrue(clear.await())

        assertEquals("", vm.draft(1L).rawInput)
    }

    @Test
    fun `tombstone before hydration blocks stale persistence of restored draft`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }

        val clear = async { vm.clearDraft(1L) }
        testDispatcher.scheduler.runCurrent()

        storeWrapper.releaseLoad(mapOf(1L to ComposerDraftState(rawInput = "stale", version = 1)))
        assertTrue(clear.await())

        val persisted = storeWrapper.latestWritten
        assertTrue(persisted == null || !persisted.containsKey(1L))
    }

    @Test
    fun `mutation after tombstone before hydration is rejected`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }

        val clear = async { vm.clearDraft(1L) }
        testDispatcher.scheduler.runCurrent()
        val blocked = async { vm.completeAttachmentImport(1L, "token", emptyList()) }
        testDispatcher.scheduler.runCurrent()
        assertTrue(blocked.isActive)

        storeWrapper.releaseLoad(mapOf(1L to ComposerDraftState(rawInput = "stale", version = 1)))
        assertTrue(clear.await())
        assertFalse(blocked.await())

        assertEquals("", vm.draft(1L).rawInput)
    }

    @Test
    fun `beginSubmission cannot deadlock before hydration`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }
        vm.updateDraftText(1L, "hello")

        var snapshot: ComposerSubmissionSnapshot? = null
        val submission = async {
            snapshot = withTimeout(2_000) { vm.beginSubmission(1L) }
        }
        testDispatcher.scheduler.runCurrent()
        assertNull("must remain pending without deadlock", snapshot)
        assertTrue(submission.isActive)

        storeWrapper.releaseLoad(emptyMap())
        submission.await()
        assertNotNull(snapshot)
        assertEquals("hello", snapshot?.rawInput)
        ManualCompactionOperationRegistry.releaseSubmission(1L, requireNotNull(snapshot).token)
    }

    @Test
    fun `attachment admission waits for readable hydration before claiming ownership`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val restoredToken = "schema8-restored-submission"
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }

        val admission = async {
            vm.tryBeginAttachmentImport(91L)
        }
        testDispatcher.scheduler.runCurrent()

        assertTrue(admission.isActive)
        assertNull(vm.draft(91L).importOwnership)
        // A pending admission must not expose a provisional process lease.
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(91L, "pre-hydration-probe"))
        ManualCompactionOperationRegistry.releaseSubmission(91L, "pre-hydration-probe")

        storeWrapper.releaseLoad(
            mapOf(
                91L to ComposerDraftState(activeSubmissionToken = restoredToken),
            ),
        )
        val result = admission.await()
        assertEquals(
            AttachmentImportAdmission.Rejected(AttachmentImportRejection.EXISTING_SUBMISSION),
            result,
        )
        assertEquals(restoredToken, vm.draft(91L).activeSubmissionToken)
        assertNull(vm.draft(91L).importOwnership)
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(91L, "post-admission-probe"))
        ManualCompactionOperationRegistry.releaseSubmission(91L, "post-admission-probe")
    }

    @Test
    fun `attachment admission releases registry lease when owner command is unavailable`() = runTest {
        val vm = ChatViewModel().also { this@ChatViewModelDraftHydrationTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        vm.scope.cancel()

        val result = vm.tryBeginAttachmentImport(92L)
        assertEquals(
            AttachmentImportAdmission.Rejected(AttachmentImportRejection.OWNER_PERSISTENCE_REJECTED),
            result,
        )
        assertNull(vm.draft(92L).importOwnership)
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(92L, "after-failure-probe"))
        ManualCompactionOperationRegistry.releaseSubmission(92L, "after-failure-probe")
    }

    @Test
    fun `failed copy transition releases an orphaned import lease`() = runTest {
        val vm = ChatViewModel().also { this@ChatViewModelDraftHydrationTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        val admission = vm.tryBeginAttachmentImport(93L)
        val owner = (admission as AttachmentImportAdmission.Accepted).owner

        assertTrue(vm.draftMachine.updateCritical(93L) { it.copy(importOwnership = null) })
        assertFalse(vm.beginAttachmentCopy(93L, owner.token))
        vm.settleAttachmentImportAfterCallback(93L, owner.token)

        assertNull(vm.draft(93L).importOwnership)
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(93L, "after-copy-failure-probe"))
        ManualCompactionOperationRegistry.releaseSubmission(93L, "after-copy-failure-probe")
    }

    @Test
    fun `submission rejects pathological draft import owner without registry truth`() = runTest {
        val vm = ChatViewModel().also { this@ChatViewModelDraftHydrationTest.vm = it }
        assertTrue(vm.draftMachine.awaitHydration())
        val orphanToken = "draft-only-import-owner"
        assertTrue(
            vm.draftMachine.updateCritical(94L) {
                it.copy(
                    importOwnership = ComposerImportOwnership(
                        token = orphanToken,
                        status = ComposerImportStatus.PICKER_OPEN,
                    ),
                )
            },
        )

        val result = vm.beginSubmissionResult(94L)
        assertEquals(
            SubmissionAdmissionResult.Rejected(SubmissionRejectionReason.IMPORT_OWNER_ACTIVE),
            result,
        )
        assertNull(vm.draft(94L).activeSubmissionToken)
        assertEquals(orphanToken, vm.draft(94L).importOwnership?.token)
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(94L, "after-send-defense-probe"))
        ManualCompactionOperationRegistry.releaseSubmission(94L, "after-send-defense-probe")
    }

    @Test
    fun `attachment completion cannot deadlock before hydration`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }
        val owner = vm.beginAttachmentImport(1L)

        var accepted: Boolean? = null
        val completion = async {
            accepted = withTimeout(2_000) {
                vm.completeAttachmentImport(
                    1L, owner.token,
                    listOf(PendingAttachmentIdentity("a", "text/plain", "/managed/a")),
                )
            }
        }
        testDispatcher.scheduler.runCurrent()
        assertTrue(completion.isActive)

        storeWrapper.releaseLoad(emptyMap())
        completion.await()
        assertEquals(true, accepted)
        assertEquals(listOf("/managed/a"), vm.draft(1L).pendingAttachments.map { it.localPath })
    }

    @Test
    fun `attachment removal cannot deadlock before hydration`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }
        val owner = vm.beginAttachmentImport(1L)
        val imported = async {
            vm.completeAttachmentImport(
                1L, owner.token,
                listOf(PendingAttachmentIdentity("a", "text/plain", "/managed/a")),
            )
        }
        testDispatcher.scheduler.runCurrent()
        assertTrue(imported.isActive)

        var removed: Boolean? = null
        val removal = async {
            imported.await()
            removed = withTimeout(2_000) { vm.removePendingAttachment(1L, "/managed/a") }
        }
        testDispatcher.scheduler.runCurrent()
        assertTrue(removal.isActive)

        storeWrapper.releaseLoad(emptyMap())
        removal.await()
        assertEquals(true, removed)
        assertTrue(vm.draft(1L).pendingAttachments.isEmpty())
    }

    @Test
    fun `committed reconciliation cannot deadlock before hydration`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }
        vm.updateDraftText(1L, "send me")

        var snapshot: ComposerSubmissionSnapshot? = null
        val submission = async {
            snapshot = withTimeout(2_000) { vm.beginSubmission(1L) }
        }
        testDispatcher.scheduler.runCurrent()
        assertTrue(submission.isActive)

        storeWrapper.releaseLoad(emptyMap())
        submission.await()

        var reconciled: Boolean? = null
        val reconcile = async {
            reconciled = withTimeout(2_000) {
                vm.reconcileCommittedSubmission(requireNotNull(snapshot), emptyList())
            }
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, reconciled)
        assertEquals("", vm.draft(1L).rawInput)
        assertNull(vm.draft(1L).activeSubmissionToken)
        ManualCompactionOperationRegistry.releaseSubmission(1L, requireNotNull(snapshot).token)
    }

    @Test
    fun `process recreation reconciles durable debt only after hydration`() = runTest {
        val dir = Files.createTempDirectory("fusion-process-recovery").toFile()
        val draftFile = dir.resolve("composer_drafts.json")
        val debtFile = dir.resolve("committed_draft_reconciliation_debt.json")
        val committedAttachment = dir.resolve("committed.txt").apply { writeText("committed") }
        val pendingAttachment = dir.resolve("pending.txt").apply { writeText("pending") }
        val seedStore = PersistentComposerDraftStore(
            file = draftFile,
            resolveManagedAttachment = { path -> File(path).takeIf { it.isFile } },
            registerPendingAttachment = { it.absolutePath },
        )
        seedStore.write(
            mapOf(
                77L to ComposerDraftState(
                    rawInput = "captured input",
                    pendingAttachments = listOf(
                        PendingAttachmentIdentity("committed", "text/plain", committedAttachment.absolutePath),
                        PendingAttachmentIdentity("pending", "text/plain", pendingAttachment.absolutePath),
                    ),
                    activeSubmissionToken = "token-T",
                    version = 4L,
                ),
            )
        )
        CommittedDraftReconciliationDebtStore.record(
            debtFile,
            CommittedDraftReconciliationDebt(
                draftKey = 77L,
                token = "token-T",
                capturedRawInput = "captured input",
                committedPaths = setOf(committedAttachment.absolutePath),
                attempts = 0,
                lastAttemptAt = 0L,
            ),
        )

        val loadGate = CompletableDeferred<Unit>()
        val store = object : PersistentComposerDraftStore(
            file = draftFile,
            resolveManagedAttachment = { path -> File(path).takeIf { it.isFile } },
            registerPendingAttachment = { it.absolutePath },
        ) {
            override suspend fun load(): Map<Long, ComposerDraftState> {
                loadGate.await()
                return super.load()
            }

            override suspend fun loadResult(): DraftStoreLoad {
                loadGate.await()
                return super.loadResult()
            }
        }
        val vm = ChatViewModel.forTesting(store).also { this@ChatViewModelDraftHydrationTest.vm = it }
        val retry = async {
            CommittedDraftReconciliationDebtStore.retry(
                owner = DraftReconciliationOwner { debt ->
                    val before = vm.draft(debt.draftKey)
                    DraftReconciliationResult(
                        success = vm.draftMachine.reconcileCommittedSubmission(
                            draftKey = debt.draftKey,
                            token = debt.token,
                            capturedRawInput = debt.capturedRawInput,
                            committedPaths = debt.committedPaths,
                        ),
                        releasePaths = before.activeSubmissionToken == null ||
                            before.activeSubmissionToken == debt.token,
                    )
                },
                unregisterPath = {},
                file = debtFile,
            )
        }
        testDispatcher.scheduler.runCurrent()
        assertTrue("reconciliation must wait for hydration", retry.isActive)

        loadGate.complete(Unit)
        val outcome = retry.await()
        assertEquals(1, outcome.reconciled)
        assertTrue(outcome.persistenceOk)
        assertEquals("", vm.draft(77L).rawInput)
        assertNull(vm.draft(77L).activeSubmissionToken)
        assertEquals(
            listOf(pendingAttachment.absolutePath),
            vm.draft(77L).pendingAttachments.map { it.localPath },
        )
        assertTrue(CommittedDraftReconciliationDebtStore.load(debtFile).isEmpty())

        val subsequent = vm.beginSubmission(77L)
        assertNotNull("recovered draft must admit the next submission", subsequent)
        assertEquals("", subsequent?.rawInput)
        vm.settleSubmissionOwner(77L, requireNotNull(subsequent).token)
        dir.deleteRecursively()
    }

    @Test
    fun `ambiguous no-debt restored token is never automatically cleared`() = runTest {
        // Crash window: token persisted, Room commit succeeded, died before debt record.
        // No matching debt exists, so fail-safe must preserve the token (no duplication risk).
        val dir = Files.createTempDirectory("fusion-orphan-recovery").toFile()
        val debtFile = dir.resolve("committed_draft_reconciliation_debt.json")
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }
        storeWrapper.releaseLoad(
            mapOf(
                88L to ComposerDraftState(
                    rawInput = "keep this input",
                    pendingAttachments = listOf(
                        PendingAttachmentIdentity("retained", "text/plain", "/managed/retained"),
                    ),
                    activeSubmissionToken = "token-orphan",
                    version = 9L,
                ),
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // No debt file exists: startup retry is a no-op and must not clear the token.
        val outcome = CommittedDraftReconciliationDebtStore.retry(
            owner = DraftReconciliationOwner { debt ->
                DraftReconciliationResult(
                    success = vm.draftMachine.reconcileCommittedSubmission(
                        debt.draftKey, debt.token, debt.capturedRawInput, debt.committedPaths,
                    ),
                    releasePaths = false,
                )
            },
            unregisterPath = {},
            file = debtFile,
        )
        assertEquals(0, outcome.reconciled)
        assertTrue(outcome.persistenceOk)
        assertEquals("keep this input", vm.draft(88L).rawInput)
        assertTrue(vm.draft(88L).pendingAttachments.isNotEmpty())
        assertEquals(
            "ambiguous no-debt token must fail safe and remain",
            "token-orphan",
            vm.draft(88L).activeSubmissionToken,
        )
        dir.deleteRecursively()
    }

    @Test
    fun `startup recovery never clears a newer token for stale debt`() = runTest {
        val dir = Files.createTempDirectory("fusion-stale-debt").toFile()
        val debtFile = dir.resolve("committed_draft_reconciliation_debt.json")
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }
        storeWrapper.releaseLoad(
            mapOf(
                99L to ComposerDraftState(
                    rawInput = "newer draft",
                    activeSubmissionToken = "token-A",
                ),
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.draftMachine.updateCritical(99L) { it.copy(activeSubmissionToken = "token-B") })
        CommittedDraftReconciliationDebtStore.record(
            debtFile,
            CommittedDraftReconciliationDebt(99L, "token-A", "newer draft", emptySet(), 0, 0L),
        )
        val outcome = CommittedDraftReconciliationDebtStore.retry(
            owner = DraftReconciliationOwner { debt ->
                DraftReconciliationResult(
                    success = vm.draftMachine.reconcileCommittedSubmission(
                        debt.draftKey, debt.token, debt.capturedRawInput, debt.committedPaths,
                    ),
                    releasePaths = false,
                )
            },
            unregisterPath = {},
            file = debtFile,
        )
        // Stale debt A reconciles as already-resolved (owner mismatch) but must not clear B.
        assertEquals("token-B", vm.draft(99L).activeSubmissionToken)
        assertTrue(outcome.persistenceOk)
        assertTrue(CommittedDraftReconciliationDebtStore.load(debtFile).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `reconcile queued before hydration waits and cannot return already-reconciled`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }
        // Queue reconciliation before hydration completes: it must not return true
        // from an unhydrated empty map as "already reconciled".
        val reconcile = async {
            withTimeout(2_000) {
                vm.draftMachine.reconcileCommittedSubmission(55L, "token-T", "hello", emptySet())
            }
        }
        testDispatcher.scheduler.runCurrent()
        assertTrue("reconciliation must wait for hydration", reconcile.isActive)
        // Hydrate with a matching owned draft; exact reconciliation must then clear it.
        storeWrapper.releaseLoad(
            mapOf(55L to ComposerDraftState(rawInput = "hello", activeSubmissionToken = "token-T")),
        )
        assertTrue(reconcile.await())
        assertNull(vm.draft(55L).activeSubmissionToken)
        assertEquals("", vm.draft(55L).rawInput)
    }

    @Test
    fun `multiple pre-hydration mutations compose in order`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }

        vm.updateDraftText(1L, "one")
        vm.appendQuickPrompt(1L, " two") { base, prompt -> base + prompt }
        vm.appendQuickPrompt(1L, " three") { base, prompt -> base + prompt }

        storeWrapper.releaseLoad(mapOf(1L to ComposerDraftState(rawInput = "stale", version = 7)))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("one two three", vm.draft(1L).rawInput)
        assertEquals(3L, vm.draft(1L).version)
    }

    @Test
    fun `delayed noncritical mutation survives hydration completion`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }

        vm.updateDraftText(1L, "kept update")
        val owner = vm.beginAttachmentImport(2L)

        storeWrapper.releaseLoad(
            mapOf(
                2L to ComposerDraftState(rawInput = "restored B", version = 1),
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("kept update", vm.draft(1L).rawInput)
        assertEquals("restored B", vm.draft(2L).rawInput)
        assertEquals(owner.token, vm.draft(2L).importOwnership?.token)
    }

    @Test
    fun `write failure before hydration completes deferred replies with false`() = runTest {
        val storeWrapper = FailingWriteDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }

        val clear = async { vm.clearDraft(1L) }
        testDispatcher.scheduler.runCurrent()
        assertTrue(clear.isActive)

        storeWrapper.releaseLoad(emptyMap())
        assertFalse(clear.await())
        assertEquals("", vm.draft(1L).rawInput)
    }

    @Test
    fun `concurrent debounced and critical writes are serialized by writeId`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }

        vm.updateDraftText(1L, "debounced A")
        vm.updateDraftText(2L, "debounced B")
        storeWrapper.releaseLoad(emptyMap())
        val addAttachmentJob = async {
            val owner = vm.beginAttachmentImport(3L)
            vm.beginAttachmentCopy(3L, owner.token)
            vm.completeAttachmentImport(
                3L, owner.token,
                listOf(PendingAttachmentIdentity("a", "text/plain", "/managed/a"))
            )
        }
        vm.updateDraftText(4L, "debounced C")

        addAttachmentJob.await()
        testDispatcher.scheduler.advanceUntilIdle()

        val sorted = storeWrapper.writeIds.sorted()
        assertEquals(sorted, storeWrapper.writeIds)
    }

    @Test
    fun `failed critical write after hydration rolls back the in-memory mutation`() = runTest {
        val storeWrapper = FlakyWriteDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }
        vm.updateDraftText(1L, "hello")

        storeWrapper.releaseLoad(emptyMap())
        testDispatcher.scheduler.advanceUntilIdle()

        val submission = async {
            storeWrapper.failWrites = true
            withTimeout(2_000) { vm.beginSubmission(1L) }
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(submission.await())
        assertEquals("hello", vm.draft(1L).rawInput)
        assertNull(vm.draft(1L).activeSubmissionToken)
    }

    @Test
    fun `post-commit cancellation releases the matching attachment lease before propagating`() = runTest {
        val managed = Files.createTempFile("fusion-managed-attachment", ".jpg").toFile()
        val storeWrapper = PostCommitBarrierDraftStore(managed)
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }
        storeWrapper.releaseLoad(emptyMap())
        assertTrue(vm.draftMachine.awaitHydration())

        val conversationId = 203L
        val admission = vm.tryBeginAttachmentImport(conversationId) as AttachmentImportAdmission.Accepted
        val owner = admission.owner
        val attachment = PendingAttachmentIdentity("camera.jpg", "image/jpeg", managed.canonicalPath)
        val completion = async { vm.completeAttachmentImport(conversationId, owner.token, listOf(attachment)) }

        storeWrapper.committed.await()
        assertFalse("the accepted durable command is paused before its acknowledgement", completion.isCompleted)
        completion.cancel()
        assertFalse("the protected boundary is still awaiting its acknowledgement", completion.isCompleted)

        storeWrapper.releaseCommit.complete(Unit)
        completion.join()
        assertTrue("cancellation is still visible to the caller", completion.isCancelled)
        assertTrue("the durable draft contains the committed attachment", storeWrapper.durableHasAttachment(conversationId, managed.canonicalPath))
        assertNull("the process draft owner is cleared", vm.draft(conversationId).importOwnership)
        assertTrue(
            "cancellation is delivered only after the old lease is released",
            ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, "fresh-import-probe"),
        )
        ManualCompactionOperationRegistry.releaseSubmission(conversationId, "fresh-import-probe")
        managed.delete()
    }

    @Test
    fun `beginSubmission leaks no submission token when the critical write fails`() = runTest {
        val storeWrapper = FailingWriteDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }
        vm.updateDraftText(1L, "hello")

        var snapshot: ComposerSubmissionSnapshot? = null
        val submission = async {
            snapshot = withTimeout(2_000) { vm.beginSubmission(1L) }
        }
        testDispatcher.scheduler.runCurrent()
        storeWrapper.releaseLoad(emptyMap())
        submission.await()

        assertNull(snapshot)
        assertEquals("hello", vm.draft(1L).rawInput)
        assertNull(vm.draft(1L).activeSubmissionToken)
    }

    @Test
    fun `beginAttachmentCopy reports the exact in-memory outcome`() = runTest {
        val storeWrapper = DelayedHydrationDraftStore()
        val vm = ChatViewModel.forTesting(storeWrapper).also { this@ChatViewModelDraftHydrationTest.vm = it }

        val owner = vm.beginAttachmentImport(1L)
        storeWrapper.releaseLoad(emptyMap())
        testDispatcher.scheduler.advanceUntilIdle()

        val copied = async {
            withTimeout(2_000) { vm.beginAttachmentCopy(1L, owner.token) }
        }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, copied.await())

        val stale = async {
            withTimeout(2_000) { vm.beginAttachmentCopy(1L, "stale-token") }
        }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, stale.await())
        assertEquals(ComposerImportStatus.COPYING, vm.draft(1L).importOwnership?.status)
    }
}

private class FlakyWriteDraftStore : PersistentComposerDraftStore(
    file = Files.createTempFile("fusion-drafts", ".json").toFile(),
    resolveManagedAttachment = { null },
    registerPendingAttachment = { it.absolutePath },
) {
    private val loadDeferred = CompletableDeferred<Map<Long, ComposerDraftState>>()
    var failWrites = false

    fun releaseLoad(data: Map<Long, ComposerDraftState>) {
        loadDeferred.complete(data)
    }

    override suspend fun load(): Map<Long, ComposerDraftState> = loadDeferred.await()

    override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(loadDeferred.await())

    override suspend fun write(writeId: Long, drafts: Map<Long, ComposerDraftState>): Boolean =
        !failWrites
}

private class DelayedHydrationDraftStore : PersistentComposerDraftStore(
    file = Files.createTempFile("fusion-drafts", ".json").toFile(),
    resolveManagedAttachment = { null },
    registerPendingAttachment = { it.absolutePath },
) {
    private val loadDeferred = CompletableDeferred<Map<Long, ComposerDraftState>>()
    val writeIds = mutableListOf<Long>()
    var latestWritten: Map<Long, ComposerDraftState>? = null
        private set

    fun releaseLoad(data: Map<Long, ComposerDraftState>) {
        loadDeferred.complete(data)
    }

    override suspend fun load(): Map<Long, ComposerDraftState> = loadDeferred.await()

    override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(loadDeferred.await())

    override suspend fun write(writeId: Long, drafts: Map<Long, ComposerDraftState>): Boolean {
        writeIds.add(writeId)
        latestWritten = drafts
        return true
    }
}

private class FailingWriteDraftStore : PersistentComposerDraftStore(
    file = Files.createTempFile("fusion-drafts", ".json").toFile(),
    resolveManagedAttachment = { null },
    registerPendingAttachment = { it.absolutePath },
) {
    private val loadDeferred = CompletableDeferred<Map<Long, ComposerDraftState>>()
    var failWrites = true

    fun releaseLoad(data: Map<Long, ComposerDraftState>) {
        loadDeferred.complete(data)
    }

    override suspend fun load(): Map<Long, ComposerDraftState> = loadDeferred.await()

    override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(loadDeferred.await())

    override suspend fun write(writeId: Long, drafts: Map<Long, ComposerDraftState>): Boolean = !failWrites
}

private class PostCommitBarrierDraftStore(
    private val managedAttachment: File,
) : PersistentComposerDraftStore(
    file = Files.createTempFile("fusion-post-commit-drafts", ".json").toFile(),
    resolveManagedAttachment = { File(it).takeIf(File::exists) },
    registerPendingAttachment = { it.absolutePath },
) {
    private val loadDeferred = CompletableDeferred<Map<Long, ComposerDraftState>>()
    val committed = CompletableDeferred<Unit>()
    val releaseCommit = CompletableDeferred<Unit>()

    fun releaseLoad(data: Map<Long, ComposerDraftState>) {
        loadDeferred.complete(data)
    }

    override suspend fun load(): Map<Long, ComposerDraftState> = loadDeferred.await()
    override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(loadDeferred.await())

    override suspend fun write(writeId: Long, drafts: Map<Long, ComposerDraftState>): Boolean {
        val persisted = super.write(writeId, drafts)
        val committedDraft = drafts.values.any { draft ->
            draft.importOwnership == null && draft.pendingAttachments.any {
                it.localPath == managedAttachment.canonicalPath
            }
        }
        if (persisted && committedDraft) {
            committed.complete(Unit)
            releaseCommit.await()
        }
        return persisted
    }

    suspend fun durableHasAttachment(conversationId: Long, path: String): Boolean =
        (super.loadResult() as? DraftStoreLoad.Valid)?.drafts?.get(conversationId)
            ?.pendingAttachments?.any { it.localPath == path } == true
}
