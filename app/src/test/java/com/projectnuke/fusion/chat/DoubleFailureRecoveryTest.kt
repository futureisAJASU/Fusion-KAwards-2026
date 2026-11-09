package com.projectnuke.fusion.chat

import com.projectnuke.fusion.data.SubmissionCommitWitnessDao
import com.projectnuke.fusion.data.SubmissionCommitWitnessEntity
import com.projectnuke.fusion.ui.recoverSchema8SubmissionWitnesses
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * P17-01 double-failure recovery: cancellation followed by rollback failure.
 *
 * P16-01 rolls a cancelled admission back inside `NonCancellable` but ignores
 * the rollback's durable result (`runCatching { updateCritical(...) }` plus an
 * unconditional exact-token lease release). A failed rollback therefore leaves
 * the durable submission token `T` present with no process lease: the next
 * Send is rejected (`EXISTING_SUBMISSION_OWNER`), which is fail-closed but
 * must still converge once the fault disappears.
 *
 * Every test drives real production methods ([ChatViewModel.beginSubmissionResult],
 * [ChatViewModel.settleSubmissionOwner], [ChatViewModel.clearDraft] and the real
 * startup recovery [recoverSchema8SubmissionWitnesses]) with the real
 * [ChatDraftStateMachine], a real [PersistentComposerDraftStore] file and the real
 * [ManualCompactionOperationRegistry]. Failure injection scripts durable-write
 * outcomes only; no production logic is bypassed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DoubleFailureRecoveryTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    private var vm: ChatViewModel? = null
    private var vm2: ChatViewModel? = null
    private var store: ScriptedDraftStore? = null
    private var store2: ScriptedDraftStore? = null

    @Before
    fun setUp() {
        val s = ScriptedDraftStore()
        store = s
        vm = ChatViewModel.forTesting(s)
    }

    @After
    fun tearDown() {
        vm?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        vm2?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        vm = null
        vm2 = null
        store = null
        store2 = null
    }

    private suspend fun hydratedVm(): ChatViewModel {
        val v = requireNotNull(vm)
        requireNotNull(store).releaseLoad(emptyMap())
        assertTrue(v.draftMachine.awaitHydration())
        return v
    }

    private fun probeAcquires(conversationId: Long, probe: String = "p17-probe"): Boolean {
        val acquired = ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, probe)
        if (acquired) ManualCompactionOperationRegistry.releaseSubmission(conversationId, probe)
        return acquired
    }

    /**
     * Strands token T exactly like production does when the P16-01 rollback's
     * durable write fails: admission commits under a write gate, the caller is
     * cancelled, the gate releases, and the next (rollback) write fails.
     * Returns the stranded token.
     */
    private suspend fun kotlinx.coroutines.CoroutineScope.strandTokenByFailedRollback(
        v: ChatViewModel,
        s: ScriptedDraftStore,
        conversationId: Long,
    ): String {
        // Write #1 (admission commit) succeeds; write #2 (exact-token
        // rollback) fails durably. The consumer is serial, so indexing is
        // deterministic.
        s.armGate()
        s.failWriteNumbers(2)
        val admission = async { v.beginSubmissionResult(conversationId) }
        s.writeEntered.await()
        admission.cancel()
        s.releaseGate()
        admission.join()
        assertTrue("cancellation is still visible to the caller", admission.isCancelled)
        val stranded = v.draft(conversationId).activeSubmissionToken
        assertTrue(
            "failed rollback must leave the durable token present, got $stranded",
            stranded != null && stranded.startsWith("s8:"),
        )
        return stranded!!
    }

    // P17-01-A — transient rollback failure: the rollback write fails once,
    // the next startup-style recovery succeeds.
    @Test
    fun `P17-01-A transient rollback failure stays fail-closed then converges`() = runTest {
        val v = hydratedVm()
        val s = requireNotNull(store)
        val conversationId = 201L
        assertTrue(
            v.draftMachine.updateCritical(conversationId) {
                it.copy(
                    rawInput = "unsent user text",
                    pendingAttachments = listOf(
                        PendingAttachmentIdentity("keep.txt", "text/plain", "/managed/keep.txt")
                    ),
                )
            }
        )
        try {
            val stranded = strandTokenByFailedRollback(v, s, conversationId)

            // Stage 1 (after original failure + failed rollback): fail-closed.
            assertEquals("user text must survive the failed rollback", "unsent user text", v.draft(conversationId).rawInput)
            assertEquals(1, v.draft(conversationId).pendingAttachments.size)
            assertTrue("lease must be released even though the token survives", probeAcquires(conversationId))
            val rejected = v.beginSubmissionResult(conversationId)
            assertTrue("next Send must be rejected while ownership is uncertain, got $rejected", rejected is SubmissionAdmissionResult.Rejected)
            assertEquals(
                SubmissionRejectionReason.EXISTING_SUBMISSION_OWNER,
                (rejected as SubmissionAdmissionResult.Rejected).reason,
            )
            assertEquals("no duplicate submission may be admitted", stranded, v.draft(conversationId).activeSubmissionToken)

            // Stage 2 (fault removed, startup recovery runs): convergence.
            recoverSchema8SubmissionWitnesses(v, EmptyWitnessDao()) {}
            assertNull("stale token must be removed", v.draft(conversationId).activeSubmissionToken)
            assertEquals("user text must survive recovery", "unsent user text", v.draft(conversationId).rawInput)
            assertEquals(1, v.draft(conversationId).pendingAttachments.size)
            val next = v.beginSubmissionResult(conversationId)
            assertTrue("Send must be available again, got $next", next is SubmissionAdmissionResult.Accepted)
            val token = (next as SubmissionAdmissionResult.Accepted).snapshot.token
            assertTrue(v.settleSubmissionOwner(conversationId, token))

            // Repeating successful recovery is idempotent.
            recoverSchema8SubmissionWitnesses(v, EmptyWitnessDao()) {}
            assertNull(v.draft(conversationId).activeSubmissionToken)
        } finally {
            v.draft(conversationId).activeSubmissionToken?.let { token ->
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
            }
        }
    }

    // P17-01-B — two consecutive failures: rollback fails and the next repair
    // attempt also fails; a third attempt succeeds.
    @Test
    fun `P17-01-B two consecutive failures stay safe then converge`() = runTest {
        val v = hydratedVm()
        val s = requireNotNull(store)
        val conversationId = 202L
        assertTrue(
            v.draftMachine.updateCritical(conversationId) { it.copy(rawInput = "second-failure text") }
        )
        try {
            val stranded = strandTokenByFailedRollback(v, s, conversationId)

            // Stage 2 (first repair attempt fails durably): still safe.
            s.failNextWrites(1)
            recoverSchema8SubmissionWitnesses(v, EmptyWitnessDao()) {}
            assertEquals(
                "failed repair must preserve the token for retry, got ${v.draft(conversationId).activeSubmissionToken}",
                stranded,
                v.draft(conversationId).activeSubmissionToken,
            )
            assertEquals("second-failure text", v.draft(conversationId).rawInput)
            val stillRejected = v.beginSubmissionResult(conversationId)
            assertTrue(stillRejected is SubmissionAdmissionResult.Rejected)
            assertEquals(
                SubmissionRejectionReason.EXISTING_SUBMISSION_OWNER,
                (stillRejected as SubmissionAdmissionResult.Rejected).reason,
            )

            // Stage 3 (fault removed): convergence with no duplicate.
            recoverSchema8SubmissionWitnesses(v, EmptyWitnessDao()) {}
            assertNull(v.draft(conversationId).activeSubmissionToken)
            assertEquals("second-failure text", v.draft(conversationId).rawInput)
            val next = v.beginSubmissionResult(conversationId)
            assertTrue("Send must converge, got $next", next is SubmissionAdmissionResult.Accepted)
            assertTrue(
                v.settleSubmissionOwner(conversationId, (next as SubmissionAdmissionResult.Accepted).snapshot.token)
            )
        } finally {
            v.draft(conversationId).activeSubmissionToken?.let { token ->
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
            }
        }
    }

    // P17-01-C — process restart: after the failed rollback, a fresh store and
    // state over the same durable file converge through the startup path. No
    // process-local registry state is carried across the boundary.
    @Test
    fun `P17-01-C restart reload converges stranded token through startup recovery`() = runTest {
        val v = hydratedVm()
        val s = requireNotNull(store)
        val conversationId = 203L
        assertTrue(
            v.draftMachine.updateCritical(conversationId) { it.copy(rawInput = "restart text") }
        )
        val stranded = strandTokenByFailedRollback(v, s, conversationId)
        assertTrue("registry must already be lease-free (nothing carried across restart)", probeAcquires(conversationId))

        // Durable file really carries the stranded token.
        val fileLoad = PersistentComposerDraftStore(
            s.backingFile,
            { null },
            { it.absolutePath },
        ).loadResult()
        assertTrue(fileLoad is DraftStoreLoad.Valid)
        val durableToken = (fileLoad as DraftStoreLoad.Valid).drafts[conversationId]?.activeSubmissionToken
        assertEquals("stranded token must be durable, got $durableToken", stranded, durableToken)

        // Simulated process death: the old owner is gone.
        v.cancelForTest(mainDispatcherRule.dispatcher.scheduler)

        // Simulated restart: fresh store + fresh VM over the same file, fresh
        // (empty) process registries for the new owner.
        val restartStore = ScriptedDraftStore(s.backingFile)
        store2 = restartStore
        val restarted = ChatViewModel.forTesting(restartStore)
        vm2 = restarted
        restartStore.releaseLoad((fileLoad as DraftStoreLoad.Valid).drafts)
        assertTrue(restarted.draftMachine.awaitHydration())
        try {
            assertEquals(stranded, restarted.draft(conversationId).activeSubmissionToken)
            // The fresh token never committed a Room transaction, so no
            // witness exists; the startup repair settles exactly that token.
            recoverSchema8SubmissionWitnesses(restarted, EmptyWitnessDao()) {}
            assertNull(restarted.draft(conversationId).activeSubmissionToken)
            assertEquals("restart text", restarted.draft(conversationId).rawInput)
            val next = restarted.beginSubmissionResult(conversationId)
            assertTrue("Send must be available after restart recovery, got $next", next is SubmissionAdmissionResult.Accepted)
            assertTrue(
                restarted.settleSubmissionOwner(conversationId, (next as SubmissionAdmissionResult.Accepted).snapshot.token)
            )
        } finally {
            restarted.draft(conversationId).activeSubmissionToken?.let { token ->
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
            }
        }
    }

    // P17-01-D — concurrent newer owner: after a failed rollback strands T, an
    // explicit draft clear converges, a newer owner U is admitted, and stale
    // cleanup of T cannot clear U.
    @Test
    fun `P17-01-D stale cleanup of T cannot clear newer owner U`() = runTest {
        val v = hydratedVm()
        val s = requireNotNull(store)
        val conversationId = 204L
        assertTrue(
            v.draftMachine.updateCritical(conversationId) { it.copy(rawInput = "original text") }
        )
        try {
            val strandedT = strandTokenByFailedRollback(v, s, conversationId)

            // While-alive convergence through the explicit user path: clearing
            // the composer removes the stale owner.
            assertTrue("clear must converge the stale draft", v.clearDraft(conversationId))
            assertNull(v.draft(conversationId).activeSubmissionToken)

            // A newer valid owner is admitted.
            val admitted = v.beginSubmissionResult(conversationId)
            assertTrue(admitted is SubmissionAdmissionResult.Accepted)
            val tokenU = (admitted as SubmissionAdmissionResult.Accepted).snapshot.token

            // Stale cleanup of T is a safe no-op: exact-token only.
            assertTrue(!v.settleSubmissionOwner(conversationId, strandedT))
            assertEquals("newer owner U must survive stale cleanup", tokenU, v.draft(conversationId).activeSubmissionToken)
            assertTrue("U's live lease must survive stale cleanup", !probeAcquires(conversationId, "d-probe-live"))

            assertTrue(v.settleSubmissionOwner(conversationId, tokenU))
            assertTrue("registry must be clean after U settles", probeAcquires(conversationId))
        } finally {
            v.draft(conversationId).activeSubmissionToken?.let { token ->
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
            }
        }
    }

    // P17-01-E — unreadable durable state: a failed write is never confused
    // with proof of absent ownership, and a failed witness read never settles
    // a token based on a guessed absence.
    @Test
    fun `P17-01-E unreadable store rejects admission and preserves ownership`() = runTest {
        // Fresh VM whose durable load is corrupt: hydration health is unreadable.
        val corruptStore = ScriptedDraftStore()
        corruptStore.scriptedLoad = DraftStoreLoad.Corrupt
        val corruptVm = ChatViewModel.forTesting(corruptStore)
        vm2 = corruptVm
        assertTrue(corruptVm.draftMachine.awaitHydration())
        try {
            assertTrue(!shouldProcessReconciliationDebts(corruptVm.draftHealthNow()))
            val rejected = corruptVm.beginSubmissionResult(205L)
            assertTrue("unreadable ownership must reject Send, got $rejected", rejected is SubmissionAdmissionResult.Rejected)
            assertEquals(
                SubmissionRejectionReason.DRAFT_STORE_UNREADABLE,
                (rejected as SubmissionAdmissionResult.Rejected).reason,
            )
            assertTrue("no lease may be acquired from an unreadable store", probeAcquires(205L))
            // Recovery is a safe no-op while ownership is unreadable.
            recoverSchema8SubmissionWitnesses(corruptVm, EmptyWitnessDao()) {}
            assertTrue(probeAcquires(205L))
        } finally {
            corruptVm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
            vm2 = null
        }

        // Failed witness read: the stranded token must not be settled on a
        // guessed absence; a later healthy read converges.
        val v = hydratedVm()
        val s = requireNotNull(store)
        val conversationId = 206L
        assertTrue(
            v.draftMachine.updateCritical(conversationId) { it.copy(rawInput = "witness-read text") }
        )
        try {
            val stranded = strandTokenByFailedRollback(v, s, conversationId)
            val failingDao = GetFailingWitnessDao(RuntimeException("witness table unavailable"))
            recoverSchema8SubmissionWitnesses(v, failingDao) {}
            assertEquals(
                "failed witness read must preserve the token, got ${v.draft(conversationId).activeSubmissionToken}",
                stranded,
                v.draft(conversationId).activeSubmissionToken,
            )
            recoverSchema8SubmissionWitnesses(v, EmptyWitnessDao()) {}
            assertNull(v.draft(conversationId).activeSubmissionToken)
            val next = v.beginSubmissionResult(conversationId)
            assertTrue(next is SubmissionAdmissionResult.Accepted)
            assertTrue(
                v.settleSubmissionOwner(conversationId, (next as SubmissionAdmissionResult.Accepted).snapshot.token)
            )
        } finally {
            v.draft(conversationId).activeSubmissionToken?.let { token ->
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, token)
            }
        }
    }

    private class EmptyWitnessDao : SubmissionCommitWitnessDao {
        override suspend fun insert(witness: SubmissionCommitWitnessEntity) = Unit
        override suspend fun get(token: String): SubmissionCommitWitnessEntity? = null
        override suspend fun getBatch(limit: Int): List<SubmissionCommitWitnessEntity> = emptyList()
        override suspend fun getBatchAfter(
            createdAt: Long,
            token: String,
            limit: Int,
        ): List<SubmissionCommitWitnessEntity> = emptyList()
        override suspend fun delete(token: String): Int = 0
    }

    private class GetFailingWitnessDao(val failure: Throwable) : SubmissionCommitWitnessDao {
        override suspend fun insert(witness: SubmissionCommitWitnessEntity) = Unit
        override suspend fun get(token: String): SubmissionCommitWitnessEntity? = throw failure
        override suspend fun getBatch(limit: Int): List<SubmissionCommitWitnessEntity> = emptyList()
        override suspend fun getBatchAfter(
            createdAt: Long,
            token: String,
            limit: Int,
        ): List<SubmissionCommitWitnessEntity> = emptyList()
        override suspend fun delete(token: String): Int = 0
    }
}

/** Draft store with a one-shot write gate plus scripted durable-write outcomes. */
private class ScriptedDraftStore(
    val backingFile: java.io.File = Files.createTempFile("fusion-p17-drafts", ".json").toFile(),
) : PersistentComposerDraftStore(
    file = backingFile,
    resolveManagedAttachment = { null },
    registerPendingAttachment = { it.absolutePath },
) {
    private val loadDeferred = CompletableDeferred<Map<Long, ComposerDraftState>>()
    var scriptedLoad: DraftStoreLoad? = null
    private var gate: CompletableDeferred<Unit>? = null
    val writeEntered = CompletableDeferred<Unit>()
    private var writeCount = 0
    private val failNumbers = mutableSetOf<Int>()
    private var failNext = 0

    fun releaseLoad(data: Map<Long, ComposerDraftState>) {
        loadDeferred.complete(data)
    }

    fun armGate() {
        gate = CompletableDeferred()
        writeCount = 0
    }

    fun releaseGate() {
        val g = synchronized(this) {
            val current = gate
            gate = null
            current
        } ?: return
        g.complete(Unit)
    }

    fun failNextWrites(count: Int) {
        failNext += count
    }

    fun failWriteNumbers(vararg numbers: Int) {
        failNumbers += numbers.toSet()
    }

    override suspend fun load(): Map<Long, ComposerDraftState> {
        (scriptedLoad as? DraftStoreLoad.Valid)?.let { return it.drafts }
        return loadDeferred.await()
    }

    override suspend fun loadResult(): DraftStoreLoad =
        scriptedLoad ?: DraftStoreLoad.Valid(loadDeferred.await())

    override suspend fun write(writeId: Long, drafts: Map<Long, ComposerDraftState>): Boolean {
        val g = synchronized(this) { gate }
        if (g != null) {
            writeEntered.complete(Unit)
            g.await()
        }
        val n = synchronized(this) { ++writeCount }
        synchronized(this) {
            if (n in failNumbers) {
                failNumbers.remove(n)
                return false
            }
            if (failNext > 0) {
                failNext--
                return false
            }
        }
        return super.write(writeId, drafts)
    }
}
