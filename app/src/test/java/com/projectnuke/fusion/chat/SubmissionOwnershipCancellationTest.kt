package com.projectnuke.fusion.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import com.projectnuke.fusion.ui.runDeletionWorkerAfterManualBarrier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

/**
 * P16 adversarial ownership boundaries: submission admission, settlement,
 * committed-message reconciliation, draft clear and conversation deletion
 * versus cancelled state-machine acknowledgements.
 *
 * Every test drives real production methods ([ChatViewModel.beginSubmissionResult],
 * [ChatViewModel.settleSubmissionOwner], [ChatViewModel.reconcileCommittedSubmission],
 * [ChatViewModel.clearDraft], [ConversationDeletionCoordinator.delete]) with the real
 * [ChatDraftStateMachine], a real [PersistentComposerDraftStore] file and the real
 * [ManualCompactionOperationRegistry], pausing the durable write after the accepted
 * command is committed in memory but before its caller receives the acknowledgement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubmissionOwnershipCancellationTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    private var vm: ChatViewModel? = null
    private var store: GateWriteDraftStore? = null

    @Before
    fun setUp() {
        val s = GateWriteDraftStore()
        store = s
        vm = ChatViewModel.forTesting(s).also {
            // Hydration completes before any test body proceeds.
        }
    }

    @After
    fun tearDown() {
        vm?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        vm = null
        store = null
    }

    private suspend fun hydratedVm(): ChatViewModel {
        val v = requireNotNull(vm)
        requireNotNull(store).releaseLoad(emptyMap())
        assertTrue(v.draftMachine.awaitHydration())
        return v
    }

    private fun probeAcquires(conversationId: Long, probe: String = "probe-${conversationId}-${System.nanoTime()}"): Boolean {
        val acquired = ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, probe)
        if (acquired) ManualCompactionOperationRegistry.releaseSubmission(conversationId, probe)
        return acquired
    }

    @Test
    fun `P16-01 cancelled admission converges without a stranded token`() = runTest {
        val v = hydratedVm()
        val s = requireNotNull(store)
        val conversationId = 101L

        s.armGate()
        val admission = async { v.beginSubmissionResult(conversationId) }
        s.writeEntered.await()
        assertTrue("accepted command is paused before its acknowledgement", admission.isActive)

        admission.cancel()
        s.releaseGate()
        try {
            admission.join()
            assertTrue("cancellation is still visible to the caller", admission.isCancelled)

            assertNull(
                "no durable submission token may survive a cancelled admission",
                v.draft(conversationId).activeSubmissionToken,
            )
            assertTrue("process registry lease must not survive a cancelled admission", probeAcquires(conversationId))
            val next = v.beginSubmissionResult(conversationId)
            assertTrue("next Send must be admittable, got $next", next is SubmissionAdmissionResult.Accepted)
            val token = (next as SubmissionAdmissionResult.Accepted).snapshot.token
            assertTrue(v.settleSubmissionOwner(conversationId, token))
        } finally {
            v.draft(conversationId).activeSubmissionToken?.let { stranded ->
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, stranded)
            }
        }
    }

    @Test
    fun `P16-02 cancelled settlement converges without a stale lease`() = runTest {
        val v = hydratedVm()
        val conversationId = 102L

        val first = v.beginSubmissionResult(conversationId)
        assertTrue(first is SubmissionAdmissionResult.Accepted)
        val token = (first as SubmissionAdmissionResult.Accepted).snapshot.token

        val s = requireNotNull(store)
        s.armGate()
        val settle = async { v.settleSubmissionOwner(conversationId, token) }
        s.writeEntered.await()
        assertTrue("settlement is paused before its acknowledgement", settle.isActive)

        settle.cancel()
        s.releaseGate()
        try {
            settle.join()
            assertTrue("cancellation is still visible to the caller", settle.isCancelled)
            assertNull("durable token must be cleared", v.draft(conversationId).activeSubmissionToken)
            assertTrue("no process-lifetime stale lease may survive settlement", probeAcquires(conversationId))
        } finally {
            ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
        }
    }

    @Test
    fun `P16-03 cancelled reconciliation converges without a stale lease`() = runTest {
        val v = hydratedVm()
        val s = requireNotNull(store)
        val conversationId = 103L

        v.updateDraftText(conversationId, "hello")
        val first = v.beginSubmissionResult(conversationId)
        assertTrue(first is SubmissionAdmissionResult.Accepted)
        val snapshot = (first as SubmissionAdmissionResult.Accepted).snapshot
        assertEquals("hello", snapshot.rawInput)

        s.armGate()
        val reconcile = async { v.reconcileCommittedSubmission(snapshot, emptyList()) }
        s.writeEntered.await()
        assertTrue("reconciliation is paused before its acknowledgement", reconcile.isActive)

        reconcile.cancel()
        s.releaseGate()
        try {
            reconcile.join()
            assertTrue("cancellation is still visible to the caller", reconcile.isCancelled)
            assertNull("durable token must be reconciled away", v.draft(conversationId).activeSubmissionToken)
            assertEquals("committed input must be consumed", "", v.draft(conversationId).rawInput)
            assertTrue("no process-lifetime stale lease may survive reconciliation", probeAcquires(conversationId))
        } finally {
            ManualCompactionOperationRegistry.releaseSubmission(conversationId, snapshot.token)
        }
    }

    @Test
    fun `P16-04A cancelled clear converges without stranded leases`() = runTest {
        val v = hydratedVm()
        val s = requireNotNull(store)
        val conversationId = 104L

        val first = v.beginSubmissionResult(conversationId)
        assertTrue(first is SubmissionAdmissionResult.Accepted)
        val token = (first as SubmissionAdmissionResult.Accepted).snapshot.token

        s.armGate()
        val clear = async { v.clearDraft(conversationId) }
        s.writeEntered.await()
        assertTrue("clear is paused before its acknowledgement", clear.isActive)

        clear.cancel()
        s.releaseGate()
        try {
            clear.join()
            assertTrue("cancellation is still visible to the caller", clear.isCancelled)
            assertTrue("no process-lifetime stale lease may survive a committed clear", probeAcquires(conversationId))
        } finally {
            ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
        }
    }

    @Test
    fun `P16-04B submission admitted between snapshot and clear leaves no stranded lease`() = runTest {
        val v = hydratedVm()
        val s = requireNotNull(store)
        val blockerId = 109L
        val conversationId = 105L

        // Blocker admission occupies the single state-machine consumer inside a gated write.
        s.armGate()
        val blocker = async { v.beginSubmissionResult(blockerId) }
        s.writeEntered.await()

        // Victim admission queues behind the blocked consumer: accepted by the
        // registry but not yet consumed, so a racy clear snapshot cannot see it.
        val victim = async { v.beginSubmissionResult(conversationId) }
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertTrue("victim admission is queued behind the blocked consumer", victim.isActive)

        // Racy clear snapshots the pre-victim draft, then queues behind the victim.
        val clear = async { v.clearDraft(conversationId) }
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        s.releaseGate()
        val victimResult = victim.await()
        assertTrue("victim admission commits, got $victimResult", victimResult is SubmissionAdmissionResult.Accepted)
        val victimToken = (victimResult as SubmissionAdmissionResult.Accepted).snapshot.token
        try {
            assertTrue("clear commits", clear.await())
            assertNull("draft is cleared", v.draft(conversationId).activeSubmissionToken)
            assertTrue(
                "the cleared victim lease must not strand",
                probeAcquires(conversationId, "post-clear-probe"),
            )
        } finally {
            ManualCompactionOperationRegistry.releaseSubmission(conversationId, victimToken)
            val blockerResult = blocker.await()
            if (blockerResult is SubmissionAdmissionResult.Accepted) {
                v.settleSubmissionOwner(blockerId, blockerResult.snapshot.token)
            }
        }
    }

    @Test
    fun `P16-05 deletion after a cancelled admission leaves no ghost draft`() = runTest {
        advanceUntilIdle()
        val v = hydratedVm()
        val s = requireNotNull(store)
        val blockerId = 119L
        val conversationId = 106L

        // Blocker admission parks the single state-machine consumer inside a
        // gated write, so the victim admission below is accepted by the
        // process registry and queued but NOT yet consumed.
        s.armGate()
        val blocker = async { v.beginSubmissionResult(blockerId) }
        s.writeEntered.await()

        val victim = async { v.beginSubmissionResult(conversationId) }
        advanceUntilIdle()
        assertTrue("victim admission is queued behind the blocked consumer", victim.isActive)
        victim.cancel()

        // Deletion barrier becomes active while the accepted victim command is
        // still queued; the worker mirrors the production settleTarget, which
        // only clears when the currently visible draft carries state.
        val barrier = ManualCompactionOperationRegistry.beginDeletion(conversationId)
        var settleCalls = 0
        var cleanupCalls = 0
        val coordinator = ConversationDeletionCoordinator()
        val deletion = async {
            runDeletionWorkerAfterManualBarrier(
                conversationId = conversationId,
                barrier = barrier,
                startWorker = {
                    async {
                        coordinator.delete(
                            conversationId = conversationId,
                            cancelAndJoin = { barrier.cancelAndJoin() },
                            exists = { false },
                            commitDelete = { error("must not commit for an absent conversation") },
                            settleTarget = {
                                settleCalls++
                                v.clear(conversationId)
                                val draft = v.draft(conversationId)
                                if (draft.rawInput.isNotEmpty() || draft.pendingAttachments.isNotEmpty() ||
                                    draft.importOwnership != null || draft.activeSubmissionToken != null
                                ) {
                                    check(v.clearDraft(conversationId))
                                }
                            },
                            cleanupDerivedData = { cleanupCalls++ },
                            recordCleanupDebt = { error("no debt expected on the clean path") },
                        )
                    }
                },
            )
        }
        advanceUntilIdle()

        s.releaseGate()
        advanceUntilIdle()
        victim.join()
        val blockerResult = blocker.await()
        if (blockerResult is SubmissionAdmissionResult.Accepted) {
            v.settleSubmissionOwner(blockerId, blockerResult.snapshot.token)
        }
        val result = deletion.await()
        try {
            assertTrue("deletion settles cleanly, got $result", result.isDeletedOrAbsent())
            assertEquals(1, settleCalls)
            assertEquals(1, cleanupCalls)
            assertNull(
                "no late command may republish a draft for the deleted conversation",
                v.draft(conversationId).activeSubmissionToken,
            )
            assertTrue("no orphan live registry lease may remain", probeAcquires(conversationId, "post-delete-probe"))
        } finally {
            v.draft(conversationId).activeSubmissionToken?.let { ghost ->
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, ghost)
            }
        }
    }

    @Test
    fun `P16-01 negative control admission rejected while an owner is live`() = runTest {
        val v = hydratedVm()
        val conversationId = 107L

        val first = v.beginSubmissionResult(conversationId)
        assertTrue(first is SubmissionAdmissionResult.Accepted)
        val token = (first as SubmissionAdmissionResult.Accepted).snapshot.token
        try {
            val second = v.beginSubmissionResult(conversationId)
            assertTrue(second is SubmissionAdmissionResult.Rejected)
            assertEquals(
                SubmissionRejectionReason.EXISTING_SUBMISSION_OWNER,
                (second as SubmissionAdmissionResult.Rejected).reason,
            )
        } finally {
            assertTrue(v.settleSubmissionOwner(conversationId, token))
        }
    }

    @Test
    fun `P16-01 cancel before enqueue leaves no stranded token`() = runTest {
        val v = hydratedVm()
        val conversationId = 111L

        val admission = async { v.beginSubmissionResult(conversationId) }
        admission.cancel()
        try {
            admission.await()
            assertTrue("expected cancellation", false)
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected: a caller that never started must not own anything.
        }
        assertNull(
            "no durable submission token may survive a pre-enqueue cancellation",
            v.draft(conversationId).activeSubmissionToken,
        )
        assertTrue("registry must stay clean", probeAcquires(conversationId))
        val next = v.beginSubmissionResult(conversationId)
        assertTrue(next is SubmissionAdmissionResult.Accepted)
        assertTrue(v.settleSubmissionOwner(conversationId, (next as SubmissionAdmissionResult.Accepted).snapshot.token))
    }

    @Test
    fun `P16-02 settlement matrix wrong token double settle`() = runTest {
        val v = hydratedVm()
        val conversationId = 112L

        val first = v.beginSubmissionResult(conversationId)
        assertTrue(first is SubmissionAdmissionResult.Accepted)
        val token = (first as SubmissionAdmissionResult.Accepted).snapshot.token

        // Wrong (newer-or-foreign) token: must fail without disturbing the live owner.
        assertTrue(!v.settleSubmissionOwner(conversationId, "foreign-token"))
        assertEquals(token, v.draft(conversationId).activeSubmissionToken)
        assertTrue(!probeAcquires(conversationId, "matrix-probe-live"))

        // Exact settlement succeeds; a second settlement reports false cleanly.
        assertTrue(v.settleSubmissionOwner(conversationId, token))
        assertTrue(!v.settleSubmissionOwner(conversationId, token))
        assertTrue("no stale lease after double settlement", probeAcquires(conversationId))
    }

    @Test
    fun `P16-01 restart reload sees no phantom token after converged rollback`() = runTest {
        val v = hydratedVm()
        val s = requireNotNull(store)
        val conversationId = 113L

        s.armGate()
        val admission = async { v.beginSubmissionResult(conversationId) }
        s.writeEntered.await()
        admission.cancel()
        s.releaseGate()
        admission.join()
        assertTrue(admission.isCancelled)
        assertNull(v.draft(conversationId).activeSubmissionToken)

        // Simulate process restart: a fresh store over the same durable file
        // must not restore a phantom submission owner.
        val reloaded = PersistentComposerDraftStore(
            s.backingFile,
            { null },
            { it.absolutePath },
        ).loadResult()
        assertTrue(reloaded is DraftStoreLoad.Valid)
        assertNull(
            "restarted durable state must carry no phantom token",
            (reloaded as DraftStoreLoad.Valid).drafts[conversationId]?.activeSubmissionToken,
        )
        assertTrue(probeAcquires(conversationId))
    }
}

/** Draft store whose next durable write pauses after the file is staged, before the ack. */
private class GateWriteDraftStore(
    val backingFile: java.io.File = Files.createTempFile("fusion-p16-drafts", ".json").toFile(),
) : PersistentComposerDraftStore(
    file = backingFile,
    resolveManagedAttachment = { null },
    registerPendingAttachment = { it.absolutePath },
) {
    private val loadDeferred = CompletableDeferred<Map<Long, ComposerDraftState>>()
    private var gate: CompletableDeferred<Unit>? = null
    val writeEntered = CompletableDeferred<Unit>()

    fun releaseLoad(data: Map<Long, ComposerDraftState>) {
        loadDeferred.complete(data)
    }

    fun armGate() {
        gate = CompletableDeferred()
    }

    fun releaseGate() {
        val g = synchronized(this) {
            val current = gate
            gate = null
            current
        } ?: return
        g.complete(Unit)
    }

    override suspend fun load(): Map<Long, ComposerDraftState> = loadDeferred.await()

    override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(loadDeferred.await())

    override suspend fun write(writeId: Long, drafts: Map<Long, ComposerDraftState>): Boolean {
        val g = synchronized(this) { gate }
        if (g != null) {
            writeEntered.complete(Unit)
            g.await()
        }
        return super.write(writeId, drafts)
    }
}
