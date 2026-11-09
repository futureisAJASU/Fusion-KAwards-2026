package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.ComposerDraftState
import com.projectnuke.fusion.chat.DraftStoreLoad
import com.projectnuke.fusion.chat.MainDispatcherIsolationRule
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.chat.PersistentComposerDraftStore
import com.projectnuke.fusion.chat.SubmissionAdmissionResult
import com.projectnuke.fusion.chat.cancelForTest
import com.projectnuke.fusion.data.SubmissionCommitWitnessCodec
import com.projectnuke.fusion.data.SubmissionCommitWitnessDao
import com.projectnuke.fusion.data.SubmissionCommitWitnessEntity
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * P17-03 Room commit evidence and cascading recovery failures.
 *
 * Drives the real production recovery [recoverSchema8SubmissionWitnesses]
 * (the startup path from `FusionApp`) against real draft reconciliation, with
 * failure injection at the DAO boundary, the reconcile/unregister seams, and
 * — for 03-A — a real failing durable draft store. The Room user-message row
 * itself is represented by its durable commit evidence (the witness row):
 * exactly-once witness deletion plus exactly-once draft consumption is the
 * observable steady-state proof that recovery can neither duplicate nor lose
 * the commit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WitnessDoubleFailureTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule { scheduler ->
        UnconfinedTestDispatcher(scheduler)
    }

    private fun witness(
        token: String,
        draftKey: Long,
        captured: String,
        paths: List<String>,
        createdAt: Long = 1L,
    ) = SubmissionCommitWitnessEntity(
        token = token,
        draftKey = draftKey,
        committedConversationId = 41L,
        userMessageId = 52L,
        capturedRawInput = captured,
        committedPathsJson = SubmissionCommitWitnessCodec.encodePaths(paths)!!,
        createdAt = createdAt,
    )

    private suspend fun holdToken(
        vm: ChatViewModel,
        draftKey: Long,
        token: String,
        rawInput: String,
        paths: List<String> = emptyList(),
    ) {
        assertTrue(
            vm.draftMachine.updateCritical(draftKey) {
                it.copy(
                    rawInput = rawInput,
                    pendingAttachments = paths.map { path ->
                        PendingAttachmentIdentity(path.substringAfterLast("/"), "text/plain", path)
                    },
                    activeSubmissionToken = token,
                )
            }
        )
    }

    // P17-03-A — reconciliation fails twice (real durable-store failures),
    // then succeeds: one consumption, one witness deletion, no premature
    // evidence removal.
    @Test
    fun `P17-03-A double reconciliation failure preserves evidence then converges`() = runBlocking {
        val backing = Files.createTempFile("fusion-p17-03a", ".json").toFile()
        val store = FailWriteDraftStore(backing)
        val vm = ChatViewModel.forTesting(store)
        try {
            store.releaseLoad(emptyMap())
            assertTrue(vm.draftMachine.awaitHydration())
            val draftKey = 401L
            val token = "s8:double-a"
            val path = "/managed/double-a.txt"
            holdToken(vm, draftKey, token, "captured-a", listOf(path))
            val dao = ScriptedWitnessDao(mutableListOf(witness(token, draftKey, "captured-a", listOf(path))))
            val unregistered = mutableListOf<String>()

            store.failNextWrites(2)
            repeat(2) { attempt ->
                recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
                assertEquals(
                    "attempt ${attempt + 1}: failed reconciliation must preserve token, got ${vm.draft(draftKey).activeSubmissionToken}",
                    token,
                    vm.draft(draftKey).activeSubmissionToken,
                )
                assertEquals("captured-a", vm.draft(draftKey).rawInput)
                assertEquals(1, dao.remaining)
                assertTrue(unregistered.isEmpty())
            }

            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertNull(vm.draft(draftKey).activeSubmissionToken)
            assertEquals("", vm.draft(draftKey).rawInput)
            assertEquals(listOf(token), dao.deletedTokens)
            assertEquals(listOf(path), unregistered)
            assertEquals(0, dao.remaining)

            // Repeating successful recovery is idempotent: no second
            // consumption, no second witness deletion, no second unregister.
            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals("", vm.draft(draftKey).rawInput)
            assertEquals(listOf(token), dao.deletedTokens)
            assertEquals(listOf(path), unregistered)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-03-B — reconciliation succeeds but witness deletion throws: retry
    // is idempotent and the witness is eventually deleted exactly once.
    @Test
    fun `P17-03-B witness deletion failure retries idempotently`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val draftKey = 402L
            val token = "s8:delete-fails-b"
            val path = "/managed/delete-fails-b.txt"
            holdToken(vm, draftKey, token, "captured-b", listOf(path))
            val dao = ScriptedWitnessDao(mutableListOf(witness(token, draftKey, "captured-b", listOf(path))))
            dao.failNextDeletes(1)
            val unregistered = mutableListOf<String>()

            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals("consumed input must stay consumed", "", vm.draft(draftKey).rawInput)
            assertNull(vm.draft(draftKey).activeSubmissionToken)
            assertEquals("witness must survive the failed delete", 1, dao.remaining)
            assertTrue(unregistered.isEmpty())

            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals(listOf(token), dao.deletedTokens)
            assertEquals(listOf(path), unregistered)
            assertEquals("input must not be consumed twice", "", vm.draft(draftKey).rawInput)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-03-C — witness deletion fails twice: repeated recovery cannot
    // reinsert the commit or clear newer composer content.
    @Test
    fun `P17-03-C double delete failure never reinserts or clears newer edits`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val draftKey = 403L
            val token = "s8:delete-twice-c"
            val path = "/managed/delete-twice-c.txt"
            holdToken(vm, draftKey, token, "captured-c", listOf(path))
            val dao = ScriptedWitnessDao(mutableListOf(witness(token, draftKey, "captured-c", listOf(path))))
            dao.failNextDeletes(2)
            val unregistered = mutableListOf<String>()

            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals("", vm.draft(draftKey).rawInput)

            // Newer composer edits land while the witness is stuck.
            assertTrue(vm.draftMachine.updateCritical(draftKey) { it.copy(rawInput = "newer-c") })

            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals("second failed delete must preserve newer edits", "newer-c", vm.draft(draftKey).rawInput)
            assertEquals(1, dao.remaining)
            assertTrue(unregistered.isEmpty())

            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals(listOf(token), dao.deletedTokens)
            assertEquals("newer edits must survive the eventual delete", "newer-c", vm.draft(draftKey).rawInput)
            assertEquals(listOf(path), unregistered)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-03-D — witness lookup fails: the token is not settled on a guessed
    // absence. A later healthy lookup distinguishes the committed submission
    // (witness present, reconciled through the batch) from the uncommitted
    // one (no witness, settled witness-less).
    @Test
    fun `P17-03-D failed witness lookup preserves token then distinguishes correctly`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val uncommittedKey = 4042L
            val uncommittedToken = "s8:uncommitted-d"
            holdToken(vm, uncommittedKey, uncommittedToken, "keep-d")
            val dao = ScriptedWitnessDao(mutableListOf())
            val unregistered = mutableListOf<String>()

            // Phase 1: the lookup fails, so the token must be preserved — a
            // failed read is not proof that the Room transaction never committed.
            dao.failGetTokens += uncommittedToken
            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals(
                "failed lookup must preserve the token, got ${vm.draft(uncommittedKey).activeSubmissionToken}",
                uncommittedToken,
                vm.draft(uncommittedKey).activeSubmissionToken,
            )
            assertTrue(unregistered.isEmpty())

            // Phase 2: healthy reads. A committed submission (witness present)
            // reconciles through the batch; the uncommitted one settles
            // witness-less with its text preserved.
            val committedKey = 4041L
            val committedToken = "s8:committed-d"
            val committedPath = "/managed/committed-d.txt"
            holdToken(vm, committedKey, committedToken, "captured-d", listOf(committedPath))
            dao.insert(witness(committedToken, committedKey, "captured-d", listOf(committedPath)))
            dao.failGetTokens.clear()
            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            // Committed: reconciled through its witness (input consumed).
            assertNull(vm.draft(committedKey).activeSubmissionToken)
            assertEquals("", vm.draft(committedKey).rawInput)
            // Uncommitted: witness-less settle clears only ownership.
            assertNull(vm.draft(uncommittedKey).activeSubmissionToken)
            assertEquals("keep-d", vm.draft(uncommittedKey).rawInput)
            assertEquals(listOf(committedPath), unregistered)

            val next = vm.beginSubmissionResult(uncommittedKey)
            assertTrue(next is SubmissionAdmissionResult.Accepted)
            assertTrue(
                vm.settleSubmissionOwner(uncommittedKey, (next as SubmissionAdmissionResult.Accepted).snapshot.token)
            )
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-03-E — malformed witness payload: the corrupt row is preserved and
    // never blocks other valid recovery items in the batch.
    @Test
    fun `P17-03-E malformed witness is preserved while healthy items progress`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val bad = SubmissionCommitWitnessEntity(
                token = "s8:malformed-e",
                draftKey = 4051L,
                committedConversationId = 41L,
                userMessageId = 53L,
                capturedRawInput = "old",
                committedPathsJson = "not-json{{{",
                createdAt = 1L,
            )
            val goodPath = "/managed/good-e.txt"
            val dao = ScriptedWitnessDao(
                mutableListOf(bad, witness("s8:good-e", 4052L, "committed-e", listOf(goodPath), createdAt = 2L))
            )
            val unregistered = mutableListOf<String>()
            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals(listOf(goodPath), unregistered)
            assertEquals(1, dao.remaining)
            assertEquals("s8:malformed-e", dao.remainingTokens.single())

            // The corrupt row is preserved forever (evidence is never silently
            // discarded); healthy work is never repeated.
            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals(listOf(goodPath), unregistered)
            assertEquals(1, dao.remaining)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-03-F — attachment cleanup fails after witness deletion: the retry
    // window is observably lost (no durable evidence remains), but the leak
    // is process-local only — registrations are GC protection, never user
    // data, and evaporate on restart.
    @Test
    fun `P17-03-F unregister failure after delete loses retry but leaks nothing durable`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val draftKey = 406L
            val token = "s8:unregister-fails-f"
            val path = "/managed/unregister-fails-f.txt"
            holdToken(vm, draftKey, token, "captured-f", listOf(path))
            val dao = ScriptedWitnessDao(mutableListOf(witness(token, draftKey, "captured-f", listOf(path))))
            val unregistered = mutableListOf<String>()
            var unregisterAttempts = 0
            recoverSchema8SubmissionWitnesses(vm, dao) { candidate ->
                unregisterAttempts++
                if (unregisterAttempts == 1) throw RuntimeException("registration backend unavailable")
                unregistered += candidate
            }
            // The commit itself converged exactly once...
            assertNull(vm.draft(draftKey).activeSubmissionToken)
            assertEquals("", vm.draft(draftKey).rawInput)
            assertEquals(listOf(token), dao.deletedTokens)
            // ...but the registration cleanup has no durable retry payload left.
            assertEquals(0, dao.remaining)
            assertTrue("failed unregister must not be delivered", unregistered.isEmpty())

            // A later pass is a safe no-op: nothing is duplicated, nothing crashes.
            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals("", vm.draft(draftKey).rawInput)
            assertEquals(listOf(token), dao.deletedTokens)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-03-G — reconciliation races with newer draft edits: recovery
    // consumes only the original committed state; newer text and live
    // attachment ownership survive, including overlapping paths.
    @Test
    fun `P17-03-G superseded witness preserves newer text and adopted paths`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val draftKey = 407L
            val sharedPath = "/managed/shared-g.txt"
            val newPath = "/managed/new-g.txt"
            val stalePath = "/managed/stale-g.txt"
            // Post-commit user state: newer text B, overlapping path adopted.
            assertTrue(
                vm.draftMachine.updateCritical(draftKey) {
                    it.copy(
                        rawInput = "newer text B",
                        pendingAttachments = listOf(
                            PendingAttachmentIdentity("shared-g.txt", "text/plain", sharedPath),
                            PendingAttachmentIdentity("new-g.txt", "text/plain", newPath),
                        ),
                    )
                }
            )
            val dao = ScriptedWitnessDao(
                mutableListOf(
                    witness("s8:old-g", draftKey, "original text A", listOf(sharedPath, stalePath))
                )
            )
            val unregistered = mutableListOf<String>()
            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals("newer text B must survive old witness recovery", "newer text B", vm.draft(draftKey).rawInput)
            assertEquals(
                setOf(sharedPath, newPath),
                vm.draft(draftKey).pendingAttachments.map { it.localPath }.toSet(),
            )
            assertEquals(
                "only the unadopted committed path may be unregistered",
                listOf(stalePath),
                unregistered,
            )
            assertEquals(0, dao.remaining)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // P17-03-H — zero-row witness deletion: a `delete()` affecting zero rows
    // is treated as success. The observable outcome stays safe because the
    // downstream unregister is idempotent; the row (if still present) is
    // retried without damage.
    @Test
    fun `P17-03-H zero-row witness delete stays safe across retries`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val draftKey = 408L
            val token = "s8:zero-row-h"
            val path = "/managed/zero-row-h.txt"
            holdToken(vm, draftKey, token, "captured-h", listOf(path))
            val dao = ScriptedWitnessDao(mutableListOf(witness(token, draftKey, "captured-h", listOf(path))))
            dao.zeroRowDelete = true
            val unregistered = mutableListOf<String>()

            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals("", vm.draft(draftKey).rawInput)
            assertEquals(listOf(path), unregistered)
            assertEquals("zero-row delete keeps the row for retry", 1, dao.remaining)

            recoverSchema8SubmissionWitnesses(vm, dao) { unregistered += it }
            assertEquals("consumed input must not be consumed again", "", vm.draft(draftKey).rawInput)
            assertEquals(
                "duplicate unregister delivery is harmless (idempotent set-remove)",
                listOf(path, path),
                unregistered,
            )
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    // Negative control: wrong-token settlement fails without disturbing the
    // live owner, and double settlement reports false cleanly.
    @Test
    fun `P17-03-NC wrong token and double settlement stay exact`() = runBlocking {
        val vm = ChatViewModel()
        try {
            val draftKey = 409L
            val token = "s8:control-nc"
            holdToken(vm, draftKey, token, "control")
            assertTrue(!vm.settleSubmissionOwner(draftKey, "s8:foreign"))
            assertEquals(token, vm.draft(draftKey).activeSubmissionToken)
            assertTrue(vm.settleSubmissionOwner(draftKey, token))
            assertTrue(!vm.settleSubmissionOwner(draftKey, token))
            assertNull(vm.draft(draftKey).activeSubmissionToken)
            assertEquals("control", vm.draft(draftKey).rawInput)
        } finally {
            vm.cancelForTest(mainDispatcherRule.dispatcher.scheduler)
        }
    }

    private class ScriptedWitnessDao(
        private val rows: MutableList<SubmissionCommitWitnessEntity>,
    ) : SubmissionCommitWitnessDao {
        val deletedTokens = mutableListOf<String>()
        val failGetTokens = mutableSetOf<String>()
        var failNextDeleteError: Throwable? = null
        var zeroRowDelete = false

        val remaining: Int get() = rows.size
        val remainingTokens: List<String>
            get() = rows.sortedWith(compareBy({ it.createdAt }, { it.token })).map { it.token }

        private fun ordered(): List<SubmissionCommitWitnessEntity> =
            rows.sortedWith(compareBy({ it.createdAt }, { it.token }))

        fun failNextDeletes(count: Int) {
            pendingDeleteFailures = count
        }

        private var pendingDeleteFailures = 0

        override suspend fun insert(witness: SubmissionCommitWitnessEntity) {
            rows.removeAll { it.token == witness.token }
            rows += witness
        }

        override suspend fun get(token: String): SubmissionCommitWitnessEntity? {
            if (token in failGetTokens) throw RuntimeException("witness lookup unavailable for $token")
            return rows.firstOrNull { it.token == token }
        }

        override suspend fun getBatch(limit: Int): List<SubmissionCommitWitnessEntity> =
            ordered().take(limit)

        override suspend fun getBatchAfter(
            createdAt: Long,
            token: String,
            limit: Int,
        ): List<SubmissionCommitWitnessEntity> = ordered()
            .dropWhile { it.createdAt < createdAt || (it.createdAt == createdAt && it.token <= token) }
            .take(limit)

        override suspend fun delete(token: String): Int {
            if (pendingDeleteFailures > 0) {
                pendingDeleteFailures--
                throw failNextDeleteError ?: RuntimeException("witness delete unavailable")
            }
            if (zeroRowDelete) return 0
            return if (rows.removeAll { it.token == token }) {
                deletedTokens += token
                1
            } else {
                0
            }
        }
    }

    private class FailWriteDraftStore(
        backingFile: java.io.File = Files.createTempFile("fusion-p17-03", ".json").toFile(),
    ) : PersistentComposerDraftStore(
        file = backingFile,
        resolveManagedAttachment = { null },
        registerPendingAttachment = { it.absolutePath },
    ) {
        private val loadDeferred = CompletableDeferred<Map<Long, ComposerDraftState>>()
        var failNext = 0

        fun releaseLoad(data: Map<Long, ComposerDraftState>) {
            loadDeferred.complete(data)
        }

        fun failNextWrites(count: Int) {
            failNext += count
        }

        override suspend fun load(): Map<Long, ComposerDraftState> = loadDeferred.await()

        override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(loadDeferred.await())

        override suspend fun write(writeId: Long, drafts: Map<Long, ComposerDraftState>): Boolean {
            if (failNext > 0) {
                failNext--
                return false
            }
            return super.write(writeId, drafts)
        }
    }
}
