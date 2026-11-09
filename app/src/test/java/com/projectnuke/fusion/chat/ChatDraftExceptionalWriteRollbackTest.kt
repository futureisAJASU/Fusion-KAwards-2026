package com.projectnuke.fusion.chat

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A draft transition that mutated the published in-memory map before its durable
 * write must restore the exact previous in-memory state when that write THROWS,
 * not only when it returns `false`.
 *
 * The original throwable must still escape so the owner terminates under the
 * existing fatal contract, and every accepted reply must still settle exactly
 * once. No sleep is used; ordering is controlled by test dispatchers and
 * [CompletableDeferred] gates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatDraftExceptionalWriteRollbackTest {

    private val richDraft = ComposerDraftState(
        rawInput = "old",
        pendingAttachments = listOf(
            PendingAttachmentIdentity(
                name = "a.png",
                mimeType = "image/png",
                localPath = "/tmp/a.png",
            ),
        ),
        version = 4L,
        importOwnership = ComposerImportOwnership("import-1", ComposerImportStatus.COPYING),
        activeSubmissionToken = "token-1",
        lastExternalHandoffId = "handoff-1",
    )

    @Test
    fun `mutate restores previous draft when durable write throws RuntimeException`() = runTest {
        val ownerFatal = CompletableDeferred<Throwable>()
        val ownerScope = ownedScope(testScheduler, ownerFatal)
        val machine = hydratedMachine(
            scope = ownerScope,
            restored = mapOf(1L to ComposerDraftState(rawInput = "old")),
            failure = { RuntimeException("durable write blew up") },
        )

        val result = async { machine.updateCritical(1L) { it.copy(rawInput = "new") } }

        assertFalse(withTimeout(1_000) { result.await() })
        assertTrue(withTimeout(1_000) { ownerFatal.await() } is RuntimeException)
        assertEquals("old", machine.drafts.value.getValue(1L).rawInput)
        ownerScope.cancel()
    }

    @Test
    fun `mutate restores previous draft when durable write throws LinkageError`() = runTest {
        val ownerFatal = CompletableDeferred<Throwable>()
        val ownerScope = ownedScope(testScheduler, ownerFatal)
        val machine = hydratedMachine(
            scope = ownerScope,
            restored = mapOf(1L to ComposerDraftState(rawInput = "old")),
            failure = { LinkageError("fatal durable write") },
        )

        val result = async { machine.updateCritical(1L) { it.copy(rawInput = "new") } }

        assertFalse(withTimeout(1_000) { result.await() })
        assertTrue(withTimeout(1_000) { ownerFatal.await() } is LinkageError)
        assertEquals("old", machine.drafts.value.getValue(1L).rawInput)
        ownerScope.cancel()
    }

    @Test
    fun `clear restores the exact previous draft when durable write throws`() = runTest {
        val ownerFatal = CompletableDeferred<Throwable>()
        val ownerScope = ownedScope(testScheduler, ownerFatal)
        val machine = hydratedMachine(
            scope = ownerScope,
            restored = mapOf(1L to richDraft),
            failure = { RuntimeException("clear write blew up") },
        )

        val result = async { machine.clearDraft(1L) }

        assertFalse(withTimeout(1_000) { result.await() })
        assertTrue(withTimeout(1_000) { ownerFatal.await() } is RuntimeException)
        assertEquals(richDraft, machine.drafts.value[1L])
        assertEquals(
            listOf("/tmp/a.png"),
            machine.drafts.value.getValue(1L).pendingAttachments.map { it.localPath },
        )
        assertEquals("token-1", machine.drafts.value.getValue(1L).activeSubmissionToken)
        assertEquals("handoff-1", machine.drafts.value.getValue(1L).lastExternalHandoffId)
        assertEquals(4L, machine.drafts.value.getValue(1L).version)
        ownerScope.cancel()
    }

    @Test
    fun `reconcileCommitted keeps committed state when durable write throws`() = runTest {
        val ownerFatal = CompletableDeferred<Throwable>()
        val ownerScope = ownedScope(testScheduler, ownerFatal)
        val machine = hydratedMachine(
            scope = ownerScope,
            restored = mapOf(9L to richDraft),
            failure = { RuntimeException("reconcile write blew up") },
        )

        val result = async {
            machine.reconcileCommittedSubmission(
                draftKey = 9L,
                token = "token-1",
                capturedRawInput = "old",
                committedPaths = setOf("/tmp/a.png"),
            )
        }

        assertFalse(withTimeout(1_000) { result.await() })
        assertTrue(withTimeout(1_000) { ownerFatal.await() } is RuntimeException)
        assertEquals(richDraft, machine.drafts.value[9L])
        assertEquals("old", machine.drafts.value.getValue(9L).rawInput)
        assertEquals(
            listOf("/tmp/a.png"),
            machine.drafts.value.getValue(9L).pendingAttachments.map { it.localPath },
        )
        assertEquals("token-1", machine.drafts.value.getValue(9L).activeSubmissionToken)
        ownerScope.cancel()
    }

    @Test
    fun `false durable write still rolls back and keeps the owner alive`() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val machine = ChatDraftStateMachine(
            store = FailWriteDraftStore(restored = mapOf(1L to ComposerDraftState(rawInput = "old"))),
            scope = ownerScope,
        )
        assertTrue(withTimeout(1_000) { machine.awaitHydration() })

        val cleared = async { machine.clearDraft(1L) }

        assertFalse(withTimeout(1_000) { cleared.await() })
        assertEquals("old", machine.drafts.value.getValue(1L).rawInput)

        val followUp = async { machine.updateImmediate(1L) { it.copy(rawInput = "alive") } }

        assertTrue(withTimeout(1_000) { followUp.await() })
        assertEquals("alive", machine.drafts.value.getValue(1L).rawInput)
        ownerScope.cancel()
    }

    @Test
    fun `deferred pre-hydration durable flush exception drops the phantom mutation`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val ownerFatal = CompletableDeferred<Throwable>()
        val ownerScope = ownedScope(testScheduler, ownerFatal)
        val durableDraft = ComposerDraftState(rawInput = "durable-old", version = 2L)
        val machine = ChatDraftStateMachine(
            store = GatedThrowingWriteDraftStore(
                loadGate = loadGate,
                restored = mapOf(7L to durableDraft),
                failure = { RuntimeException("deferred flush blew up") },
            ),
            scope = ownerScope,
        )
        runCurrent()

        val result = async { machine.updateCritical(7L) { it.copy(rawInput = "phantom") } }
        runCurrent()
        assertTrue(result.isActive)
        assertFalse(machine.drafts.value.containsKey(7L))

        loadGate.complete(Unit)
        runCurrent()

        assertFalse(withTimeout(1_000) { result.await() })
        assertTrue(withTimeout(1_000) { ownerFatal.await() } is RuntimeException)
        assertEquals("durable-old", machine.drafts.value.getValue(7L).rawInput)
        assertEquals(2L, machine.drafts.value.getValue(7L).version)
        ownerScope.cancel()
    }

    private suspend fun hydratedMachine(
        scope: CoroutineScope,
        restored: Map<Long, ComposerDraftState>,
        failure: () -> Throwable,
    ): ChatDraftStateMachine {
        val machine = ChatDraftStateMachine(
            store = ThrowingWriteDraftStore(restored = restored, failure = failure),
            scope = scope,
        )
        assertTrue(withTimeout(1_000) { machine.awaitHydration() })
        assertEquals(restored, machine.drafts.value)
        return machine
    }

    private fun ownedScope(
        scheduler: TestCoroutineScheduler,
        ownerFatal: CompletableDeferred<Throwable>,
    ): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(scheduler) +
                CoroutineExceptionHandler { _, e -> ownerFatal.complete(e) },
        )

    private class ThrowingWriteDraftStore(
        private val restored: Map<Long, ComposerDraftState>,
        private val failure: () -> Throwable,
    ) : PersistentComposerDraftStore(
        file = Files.createTempFile("fusion-draft-throwing-write", ".json").toFile(),
        resolveManagedAttachment = { null },
        registerPendingAttachment = { it.absolutePath },
    ) {
        override suspend fun load(): Map<Long, ComposerDraftState> = restored

        override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(restored)

        override suspend fun write(
            writeId: Long,
            drafts: Map<Long, ComposerDraftState>,
        ): Boolean = throw failure()
    }

    private class GatedThrowingWriteDraftStore(
        private val loadGate: CompletableDeferred<Unit>,
        private val restored: Map<Long, ComposerDraftState>,
        private val failure: () -> Throwable,
    ) : PersistentComposerDraftStore(
        file = Files.createTempFile("fusion-draft-deferred-throwing-write", ".json").toFile(),
        resolveManagedAttachment = { null },
        registerPendingAttachment = { it.absolutePath },
    ) {
        override suspend fun load(): Map<Long, ComposerDraftState> {
            loadGate.await()
            return restored
        }

        override suspend fun loadResult(): DraftStoreLoad {
            loadGate.await()
            return DraftStoreLoad.Valid(restored)
        }

        override suspend fun write(
            writeId: Long,
            drafts: Map<Long, ComposerDraftState>,
        ): Boolean = throw failure()
    }

    private class FailWriteDraftStore(
        private val restored: Map<Long, ComposerDraftState>,
    ) : PersistentComposerDraftStore(
        file = Files.createTempFile("fusion-draft-fail-write", ".json").toFile(),
        resolveManagedAttachment = { null },
        registerPendingAttachment = { it.absolutePath },
    ) {
        override suspend fun load(): Map<Long, ComposerDraftState> = restored

        override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(restored)

        override suspend fun write(
            writeId: Long,
            drafts: Map<Long, ComposerDraftState>,
        ): Boolean = false
    }
}
