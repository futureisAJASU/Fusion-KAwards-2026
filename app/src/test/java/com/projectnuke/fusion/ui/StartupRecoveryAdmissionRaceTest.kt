package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.ComposerDraftState
import com.projectnuke.fusion.chat.DraftStoreLoad
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.ManualCompactionOperationRegistry
import com.projectnuke.fusion.chat.PersistentComposerDraftStore
import com.projectnuke.fusion.chat.SubmissionAdmissionResult
import com.projectnuke.fusion.chat.SubmissionRejectionReason
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.data.SubmissionCommitWitnessDao
import com.projectnuke.fusion.data.SubmissionCommitWitnessEntity
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * P18-01 startup recovery versus newly admitted submissions.
 *
 * `FusionApp` runs [recoverSchema8SubmissionWitnesses] in its own
 * `LaunchedEffect` while `ChatScreen` exposes a Send action whose admission
 * ([ChatViewModel.beginSubmissionResult]) is gated only on readable hydration.
 * The final pass of startup recovery settles every durable schema-8 token that
 * has no Room commit witness. A token admitted in *this* process also has no
 * witness until its Room transaction commits, so without a liveness
 * distinction that pass can reclaim a live submission as if it were an
 * abandoned previous-process owner: durable ownership is cleared, the
 * process-local lease is released and a second Send becomes admissible while
 * the original request is still running.
 *
 * Every test drives the real production entrypoints: the real
 * [ChatViewModel.beginSubmissionResult], the real
 * [recoverSchema8SubmissionWitnesses], the real
 * [ChatViewModel.settleSubmissionOwner] / [ChatViewModel.reconcileCommittedSubmission]
 * and the real [commitAndSettleUserSubmission] commit boundary, with the real
 * draft state machine over a real durable draft file and the real
 * [ManualCompactionOperationRegistry]. The only seam is the Room witness DAO,
 * scripted to suspend at a production suspension point (a DAO await).
 *
 * Note on [probeAcquires]: it returns true when the conversation's submission
 * registry is FREE (the probe lease was acquirable), false when some owner
 * already holds it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupRecoveryAdmissionRaceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    private var vm: ChatViewModel? = null
    private var store: TestDraftStore? = null

    @Before
    fun setUp() {
        val s = TestDraftStore()
        store = s
        vm = ChatViewModel.forTesting(s)
    }

    @After
    fun tearDown() {
        vm?.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        vm = null
        store = null
    }

    private suspend fun hydratedVm(
        drafts: Map<Long, ComposerDraftState> = emptyMap(),
    ): ChatViewModel {
        val v = requireNotNull(vm)
        requireNotNull(store).releaseLoad(drafts)
        assertTrue(v.draftMachine.awaitHydration())
        return v
    }

    /** True while the conversation's submission registry is free. */
    private fun registryFree(conversationId: Long, probe: String = "p18-probe"): Boolean {
        val acquired = ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, probe)
        if (acquired) ManualCompactionOperationRegistry.releaseSubmission(conversationId, probe)
        return acquired
    }

    private fun releaseDraftLeases(v: ChatViewModel, vararg conversationIds: Long) {
        for (key in conversationIds) {
            v.draft(key).activeSubmissionToken?.let { token ->
                ManualCompactionOperationRegistry.releaseSubmission(key, token)
            }
        }
    }

    // P18-01-A — a live, still-running submission must never be reclaimed by
    // startup recovery, and a second Send must stay unadmittable until the
    // original request completes.
    @Test
    fun `P18-01-A startup recovery must not reclaim a live admitted submission`() = runTest {
        val conversationId = 301L
        val v = hydratedVm(mapOf(conversationId to ComposerDraftState(rawInput = "hello")))
        try {
            assertEquals("hello", v.draft(conversationId).rawInput)

            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val dao = GatedWitnessDao(
                batchGate = {
                    parked.complete(Unit)
                    gate.await()
                },
            )
            // Recovery is suspended at its Room witness-table query — the real
            // production suspension point of the startup scan.
            val recovery = async { recoverSchema8SubmissionWitnesses(v, dao) {} }
            lateinit var snapshot: com.projectnuke.fusion.chat.ComposerSubmissionSnapshot
            try {
                parked.await()

                val admission = v.beginSubmissionResult(conversationId)
                assertTrue(
                    "Send must be admitted while startup recovery is still running, got $admission",
                    admission is SubmissionAdmissionResult.Accepted,
                )
                snapshot = (admission as SubmissionAdmissionResult.Accepted).snapshot
                assertEquals(snapshot.token, v.draft(conversationId).activeSubmissionToken)
                assertFalse(
                    "the admitted submission must own the process lease before recovery resumes",
                    registryFree(conversationId),
                )

                gate.complete(Unit)
                recovery.await()

                assertEquals(
                    "a live submission token must not be reclaimed as an abandoned owner",
                    snapshot.token,
                    v.draft(conversationId).activeSubmissionToken,
                )
                assertFalse(
                    "the live process lease must not be silently released by recovery",
                    registryFree(conversationId),
                )
                val second = v.beginSubmissionResult(conversationId)
                assertTrue(
                    "a second Send must not be admitted while the original request is live, got $second",
                    second is SubmissionAdmissionResult.Rejected,
                )
                assertEquals(
                    SubmissionRejectionReason.EXISTING_SUBMISSION_OWNER,
                    (second as SubmissionAdmissionResult.Rejected).reason,
                )
            } finally {
                gate.complete(Unit)
                recovery.join()
            }

            // The original request reaches the real commit boundary: exactly
            // one Room user message, then the normal reconciliation settles it.
            val messages = mutableListOf<Long>()
            val commitResult = commitAndSettleUserSubmission(
                state = SubmissionCommitState(
                    conversationId = conversationId,
                    witnessPayload = SubmissionCommitWitnessPayload(
                        token = snapshot.token,
                        draftKey = conversationId,
                        capturedRawInput = snapshot.rawInput,
                        committedPaths = emptyList(),
                        createdAt = 1L,
                    ),
                ),
                parentJob = coroutineContext[kotlinx.coroutines.Job],
                runInTransaction = { block -> block() },
                createConversation = { conversationId },
                insertUserMessageReturningId = { messages += 1L; 7L },
                insertSubmissionWitness = { witness -> dao.insert(witness) },
                publishConversation = {},
                reconcileCommittedDraft = {
                    val reconciled = v.reconcileCommittedSubmission(snapshot, emptyList())
                    if (reconciled) dao.delete(snapshot.token)
                    reconciled
                },
                updateConversationTimestamp = {},
            )
            assertEquals(listOf(1L), messages)
            assertFalse(commitResult.reconciliationFailed)
            assertNull(v.draft(conversationId).activeSubmissionToken)
            assertEquals(
                "the committed turn must clear the composer exactly once",
                "",
                v.draft(conversationId).rawInput,
            )
            assertTrue(
                "the registry must be free once the single submission settles",
                registryFree(conversationId),
            )
            assertTrue(dao.witnesses.isEmpty())

            // Liveness: the next Send is admitted normally after completion.
            val next = v.beginSubmissionResult(conversationId)
            assertTrue("Send must be available after convergence, got $next", next is SubmissionAdmissionResult.Accepted)
            val nextToken = (next as SubmissionAdmissionResult.Accepted).snapshot.token
            assertTrue(v.settleSubmissionOwner(conversationId, nextToken))
            assertTrue(registryFree(conversationId))
        } finally {
            releaseDraftLeases(v, conversationId)
        }
    }

    // P18-01-B — the negative witness read is only valid for the lifetime of
    // the owner it is about to settle: a legitimate commit lands between the
    // lookup and the settlement decision.
    @Test
    fun `P18-01-B stale no-witness read cannot settle a committing owner`() = runTest {
        val conversationId = 302L
        val v = hydratedVm(mapOf(conversationId to ComposerDraftState(rawInput = "hello")))
        try {
            val witnessParked = CompletableDeferred<Unit>()
            val witnessGate = CompletableDeferred<Unit>()
            val reconcileReached = CompletableDeferred<Unit>()
            val reconcileGate = CompletableDeferred<Unit>()
            val dao = GatedWitnessDao(
                getGate = {
                    // Lookup result captured, then suspended: the settlement
                    // decision happens strictly after this point.
                    witnessParked.complete(Unit)
                    witnessGate.await()
                },
            )

            val admission = v.beginSubmissionResult(conversationId)
            assertTrue(admission is SubmissionAdmissionResult.Accepted)
            val snapshot = (admission as SubmissionAdmissionResult.Accepted).snapshot

            val recovery = async { recoverSchema8SubmissionWitnesses(v, dao) {} }
            val messages = mutableListOf<Long>()
            var commit: kotlinx.coroutines.Deferred<SubmissionCommitResult>? = null
            try {
                witnessParked.await()

                // The original request commits its Room transaction while
                // recovery holds the stale null lookup result, and parks
                // between the committed transaction and the reconciliation.
                commit = async {
                    commitAndSettleUserSubmission(
                        state = SubmissionCommitState(
                            conversationId = conversationId,
                            witnessPayload = SubmissionCommitWitnessPayload(
                                token = snapshot.token,
                                draftKey = conversationId,
                                capturedRawInput = snapshot.rawInput,
                                committedPaths = emptyList(),
                                createdAt = 1L,
                            ),
                        ),
                        parentJob = coroutineContext[kotlinx.coroutines.Job],
                        runInTransaction = { block -> block() },
                        createConversation = { conversationId },
                        insertUserMessageReturningId = { messages += 1L; 7L },
                        insertSubmissionWitness = { witness -> dao.insert(witness) },
                        publishConversation = {},
                        reconcileCommittedDraft = {
                            reconcileReached.complete(Unit)
                            reconcileGate.await()
                            val reconciled = v.reconcileCommittedSubmission(snapshot, emptyList())
                            if (reconciled) dao.delete(snapshot.token)
                            reconciled
                        },
                        updateConversationTimestamp = {},
                    )
                }
                reconcileReached.await()
                assertEquals(
                    "the Room transaction must be durable before recovery decides",
                    1,
                    dao.witnesses.size,
                )

                witnessGate.complete(Unit)
                recovery.await()

                assertEquals(
                    "recovery must not settle an owner whose commit already happened",
                    snapshot.token,
                    v.draft(conversationId).activeSubmissionToken,
                )
                assertFalse(
                    "recovery must not release the committing owner's lease",
                    registryFree(conversationId),
                )
            } finally {
                witnessGate.complete(Unit)
                reconcileGate.complete(Unit)
                recovery.join()
                commit?.join()
            }

            checkNotNull(commit).await()

            assertEquals(listOf(1L), messages)
            assertNull(v.draft(conversationId).activeSubmissionToken)
            assertEquals(
                "the real reconciliation must clear the committed composer text",
                "",
                v.draft(conversationId).rawInput,
            )
            assertTrue(dao.witnesses.isEmpty())
            assertTrue(registryFree(conversationId))
        } finally {
            releaseDraftLeases(v, conversationId)
        }
    }

    // P18-01-D — two independent identities in one recovery pass: a persisted
    // abandoned owner must still be reclaimed while a live owner is preserved.
    @Test
    fun `P18-01-D recovery reclaims abandoned owner and preserves live owner`() = runTest {
        val abandonedKey = 303L
        val liveKey = 304L
        val v = hydratedVm(
            mapOf(abandonedKey to ComposerDraftState(rawInput = "stuck", activeSubmissionToken = "s8:abandoned")),
        )
        try {
            assertTrue(
                "an abandoned previous-process token must hold no lease",
                registryFree(abandonedKey),
            )

            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val dao = GatedWitnessDao(
                batchGate = {
                    parked.complete(Unit)
                    gate.await()
                },
            )
            val recovery = async { recoverSchema8SubmissionWitnesses(v, dao) {} }
            lateinit var liveToken: String
            try {
                parked.await()

                val admission = v.beginSubmissionResult(liveKey)
                assertTrue(admission is SubmissionAdmissionResult.Accepted)
                liveToken = (admission as SubmissionAdmissionResult.Accepted).snapshot.token

                gate.complete(Unit)
                recovery.await()
            } finally {
                gate.complete(Unit)
                recovery.join()
            }

            assertNull(
                "an abandoned previous-process owner must still be reclaimed",
                v.draft(abandonedKey).activeSubmissionToken,
            )
            assertEquals(
                "a live owner must not be reclaimed by the same pass",
                liveToken,
                v.draft(liveKey).activeSubmissionToken,
            )
            assertFalse("the live lease must survive", registryFree(liveKey))
            val rejected = v.beginSubmissionResult(liveKey)
            assertTrue("the live conversation must stay single-owner, got $rejected", rejected is SubmissionAdmissionResult.Rejected)
        } finally {
            releaseDraftLeases(v, abandonedKey, liveKey)
        }
    }

    // P18-01-F — cancellation of the original request while startup recovery
    // waits: exact-token convergence with no orphan lease, no ghost token and
    // no unsafe second admission afterwards.
    @Test
    fun `P18-01-F cancellation during recovery converges without residue`() = runTest {
        val conversationId = 305L
        val v = hydratedVm(mapOf(conversationId to ComposerDraftState(rawInput = "cancelled")))
        try {
            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val dao = GatedWitnessDao(
                getGate = {
                    parked.complete(Unit)
                    gate.await()
                },
            )
            val admission = v.beginSubmissionResult(conversationId)
            assertTrue(admission is SubmissionAdmissionResult.Accepted)
            val token = (admission as SubmissionAdmissionResult.Accepted).snapshot.token

            val recovery = async { recoverSchema8SubmissionWitnesses(v, dao) {} }
            try {
                parked.await()

                // The original request is cancelled: production converges
                // through settleFailedRequestBoundary -> settleSubmissionOwner.
                assertTrue(v.settleSubmissionOwner(conversationId, token))
                assertNull(v.draft(conversationId).activeSubmissionToken)

                gate.complete(Unit)
                recovery.await()
            } finally {
                gate.complete(Unit)
                recovery.join()
            }

            assertNull(v.draft(conversationId).activeSubmissionToken)
            assertEquals("cancelled text must survive", "cancelled", v.draft(conversationId).rawInput)
            assertTrue(
                "no orphan process lease may survive cancellation during recovery",
                registryFree(conversationId),
            )
            val next = v.beginSubmissionResult(conversationId)
            assertTrue("Send must be admissible again, got $next", next is SubmissionAdmissionResult.Accepted)
            val nextToken = (next as SubmissionAdmissionResult.Accepted).snapshot.token
            assertTrue(v.settleSubmissionOwner(conversationId, nextToken))
            assertTrue(registryFree(conversationId))
        } finally {
            releaseDraftLeases(v, conversationId)
        }
    }

    // P18-01-G — a witness-query failure must stay fail-closed for a stranded
    // owner, must not settle a live owner, and must not block unrelated
    // conversations from converging once the fault clears.
    @Test
    fun `P18-01-G witness read failure stays fail-closed and bounded`() = runTest {
        val strandedKey = 306L
        val liveKey = 307L
        val v = hydratedVm(
            mapOf(strandedKey to ComposerDraftState(rawInput = "stranded", activeSubmissionToken = "s8:stranded")),
        )
        try {
            val failingDao = object : SubmissionCommitWitnessDao {
                override suspend fun insert(witness: SubmissionCommitWitnessEntity) = Unit
                override suspend fun get(token: String): SubmissionCommitWitnessEntity? =
                    throw RuntimeException("witness table unavailable")
                override suspend fun getBatch(limit: Int): List<SubmissionCommitWitnessEntity> = emptyList()
                override suspend fun getBatchAfter(
                    createdAt: Long,
                    token: String,
                    limit: Int,
                ): List<SubmissionCommitWitnessEntity> = emptyList()
                override suspend fun delete(token: String): Int = 0
            }
            val admission = v.beginSubmissionResult(liveKey)
            assertTrue(admission is SubmissionAdmissionResult.Accepted)
            val liveToken = (admission as SubmissionAdmissionResult.Accepted).snapshot.token

            // Bounded failure: recovery returns instead of crashing, and no
            // owner is settled on a guessed absence.
            recoverSchema8SubmissionWitnesses(v, failingDao) {}
            assertEquals("s8:stranded", v.draft(strandedKey).activeSubmissionToken)
            assertEquals(liveToken, v.draft(liveKey).activeSubmissionToken)
            assertFalse(registryFree(liveKey))

            // Fault cleared: unrelated stranded work converges, the live owner
            // is still preserved.
            recoverSchema8SubmissionWitnesses(v, GatedWitnessDao()) {}
            assertNull(
                "the stranded owner must converge once the fault clears",
                v.draft(strandedKey).activeSubmissionToken,
            )
            assertEquals(liveToken, v.draft(liveKey).activeSubmissionToken)
            assertFalse(registryFree(liveKey))
        } finally {
            releaseDraftLeases(v, strandedKey, liveKey)
        }
    }

    // P18-01-H — recovery of one conversation must not block admission to an
    // independent conversation while it is still running.
    @Test
    fun `P18-01-H recovery does not block independent conversation admission`() = runTest {
        val busyKey = 308L
        val independentKey = 309L
        val v = hydratedVm(
            mapOf(busyKey to ComposerDraftState(rawInput = "old", activeSubmissionToken = "s8:busy-old")),
        )
        try {
            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val dao = GatedWitnessDao(
                batchGate = {
                    parked.complete(Unit)
                    gate.await()
                },
            )
            val recovery = async { recoverSchema8SubmissionWitnesses(v, dao) {} }
            lateinit var token: String
            try {
                parked.await()

                val admission = v.beginSubmissionResult(independentKey)
                assertTrue(
                    "an independent conversation must be admittable during startup recovery, got $admission",
                    admission is SubmissionAdmissionResult.Accepted,
                )
                token = (admission as SubmissionAdmissionResult.Accepted).snapshot.token

                gate.complete(Unit)
                recovery.await()
            } finally {
                gate.complete(Unit)
                recovery.join()
            }

            assertNull("the busy conversation's abandoned owner still converges", v.draft(busyKey).activeSubmissionToken)
            assertEquals(token, v.draft(independentKey).activeSubmissionToken)
        } finally {
            releaseDraftLeases(v, busyKey, independentKey)
        }
    }

    /**
     * Real durable draft file (valid JSON from the start so writes never take
     * the corruption-quarantine path) with a scripted initial hydration load.
     */
    private class TestDraftStore(
        file: File = Files.createTempFile("fusion-p18-drafts", ".json").toFile(),
    ) : PersistentComposerDraftStore(
        file = file,
        resolveManagedAttachment = { path -> File(path) },
        registerPendingAttachment = { it.absolutePath },
    ) {
        private val initial = CompletableDeferred<Map<Long, ComposerDraftState>>()

        init {
            // A valid empty artifact: hydration is explicit, writes are normal.
            file.writeText("[]")
        }

        fun releaseLoad(drafts: Map<Long, ComposerDraftState>) {
            initial.complete(drafts)
        }

        override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(initial.await())
    }

    /**
     * Room witness DAO seam.
     *
     * [get] captures the lookup result *before* suspending on [getGate]: that
     * models production, where the DAO call has already returned its null
     * result and the coroutine is resumed later for the settlement decision.
     */
    private class GatedWitnessDao(
        private val batchGate: suspend () -> Unit = {},
        private val getGate: suspend (String) -> Unit = {},
    ) : SubmissionCommitWitnessDao {
        val witnesses = LinkedHashMap<String, SubmissionCommitWitnessEntity>()

        override suspend fun insert(witness: SubmissionCommitWitnessEntity) {
            witnesses[witness.token] = witness
        }

        override suspend fun get(token: String): SubmissionCommitWitnessEntity? {
            val result = witnesses[token]
            getGate(token)
            return result
        }

        override suspend fun getBatch(limit: Int): List<SubmissionCommitWitnessEntity> {
            batchGate()
            return witnesses.values
                .sortedWith(compareBy({ it.createdAt }, { it.token }))
                .take(limit)
        }

        override suspend fun getBatchAfter(
            createdAt: Long,
            token: String,
            limit: Int,
        ): List<SubmissionCommitWitnessEntity> = witnesses.values
            .sortedWith(compareBy({ it.createdAt }, { it.token }))
            .filter { it.createdAt > createdAt || (it.createdAt == createdAt && it.token > token) }
            .take(limit)

        override suspend fun delete(token: String): Int =
            if (witnesses.remove(token) != null) 1 else 0
    }
}
