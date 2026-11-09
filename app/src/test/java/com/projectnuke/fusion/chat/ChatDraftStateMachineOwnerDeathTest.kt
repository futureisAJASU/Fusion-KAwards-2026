package com.projectnuke.fusion.chat

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDraftStateMachineOwnerDeathTest {

    @Test
    fun `reply APIs fail deterministically after owner death`() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val machine = ChatDraftStateMachine(store = null, scope = ownerScope)
        ownerScope.cancel()

        assertFalse(withTimeout(1_000) { machine.updateImmediate(1L) { it.copy(rawInput = "x") } })
        assertFalse(withTimeout(1_000) { machine.updateCritical(1L) { it.copy(rawInput = "x") } })
        assertFalse(withTimeout(1_000) { machine.clearDraft(1L) })
        assertFalse(withTimeout(1_000) {
            machine.reconcileCommittedSubmission(1L, "token", "x", emptySet())
        })
        assertFalse(withTimeout(1_000) { machine.awaitHydration() })
    }

    @Test
    fun `queued reply settles when owner is cancelled before processing`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val machine = ChatDraftStateMachine(GatedDraftStore(loadGate), ownerScope)
        val result = async {
            machine.updateCritical(2L) { it.copy(rawInput = "queued") }
        }

        runCurrent()
        assertTrue(result.isActive)
        ownerScope.cancel()

        assertFalse(withTimeout(1_000) { result.await() })
    }

    @Test
    fun `commands fail while owner is cancelled during hydration`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val machine = ChatDraftStateMachine(GatedDraftStore(loadGate), ownerScope)
        val hydration = async { machine.awaitHydration() }

        runCurrent()
        assertTrue(hydration.isActive)
        ownerScope.cancel()

        assertFalse(withTimeout(1_000) { hydration.await() })
        assertFalse(machine.clearDraft(3L))
        assertFalse(machine.reconcileCommittedSubmission(3L, "token", "x", emptySet()))
    }

    @Test
    fun `deferred durable reply settles when owner dies during hydration write`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        val writeGate = CompletableDeferred<Unit>()
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val machine = ChatDraftStateMachine(
            store = DeferredWriteDraftStore(loadGate, writeStarted, writeGate),
            scope = ownerScope,
        )
        val result = async {
            machine.updateCritical(4L) { it.copy(rawInput = "durable") }
        }

        runCurrent()
        loadGate.complete(Unit)
        runCurrent()
        writeStarted.await()
        assertTrue(result.isActive)

        ownerScope.cancel()
        assertFalse(withTimeout(1_000) { result.await() })
    }

    @Test
    fun `fatal error during durable mutation releases caller`() = runTest {
        val ownerFatal = CompletableDeferred<Throwable>()
        val ownerScope = ownedScope(testScheduler, ownerFatal)
        val machine = ChatDraftStateMachine(
            store = ThrowingWriteDraftStore({ LinkageError("fatal durable write") }),
            scope = ownerScope,
        )
        val result = async { machine.updateCritical(1L) { it.copy(rawInput = "fatal") } }

        runCurrent()

        assertFalse(withTimeout(1_000) { result.await() })
        assertTrue(withTimeout(1_000) { ownerFatal.await() } is LinkageError)
        ownerScope.cancel()
    }

    @Test
    fun `runtime exception during durable mutation releases caller`() = runTest {
        val ownerFatal = CompletableDeferred<Throwable>()
        val ownerScope = ownedScope(testScheduler, ownerFatal)
        val machine = ChatDraftStateMachine(
            store = ThrowingWriteDraftStore({ RuntimeException("unexpected durable write failure") }),
            scope = ownerScope,
        )
        val result = async { machine.updateCritical(2L) { it.copy(rawInput = "runtime") } }

        runCurrent()

        assertFalse(withTimeout(1_000) { result.await() })
        assertTrue(withTimeout(1_000) { ownerFatal.await() } is RuntimeException)
        ownerScope.cancel()
    }

    @Test
    fun `queued command behind failed in-flight command settles unavailable`() = runTest {
        val writeGate = CompletableDeferred<Unit>()
        val ownerFatal = CompletableDeferred<Throwable>()
        val ownerScope = ownedScope(testScheduler, ownerFatal)
        val machine = ChatDraftStateMachine(
            store = ThrowingWriteDraftStore({ LinkageError("fatal durable write") }, writeGate),
            scope = ownerScope,
        )
        val first = async { machine.updateCritical(1L) { it.copy(rawInput = "first") } }

        runCurrent()
        assertTrue(first.isActive)

        val second = async { machine.updateCritical(2L) { it.copy(rawInput = "second") } }
        runCurrent()
        assertTrue(second.isActive)

        writeGate.complete(Unit)
        runCurrent()

        assertFalse(withTimeout(1_000) { first.await() })
        assertFalse(withTimeout(1_000) { second.await() })
        assertTrue(withTimeout(1_000) { ownerFatal.await() } is LinkageError)
        ownerScope.cancel()
    }

    @Test
    fun `fatal error terminates owner instead of continuing`() = runTest {
        val ownerFatal = CompletableDeferred<Throwable>()
        val ownerScope = ownedScope(testScheduler, ownerFatal)
        val machine = ChatDraftStateMachine(
            store = ThrowingWriteDraftStore({ LinkageError("fatal durable write") }),
            scope = ownerScope,
        )
        val result = async { machine.updateCritical(1L) { it.copy(rawInput = "fatal") } }

        runCurrent()

        assertFalse(withTimeout(1_000) { result.await() })
        assertTrue(withTimeout(1_000) { ownerFatal.await() } is LinkageError)
        assertFalse(machine.updateImmediate(1L) { it.copy(rawInput = "after-fatal") })
        ownerScope.cancel()
    }

    private fun ownedScope(
        testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        ownerFatal: CompletableDeferred<Throwable>,
    ): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, e -> ownerFatal.complete(e) },
        )

    @Test
    fun `normal write failure rolls back and keeps owner alive`() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val machine = ChatDraftStateMachine(store = FailWriteDraftStore(), scope = ownerScope)
        val result = async { machine.updateCritical(1L) { it.copy(rawInput = "rollback-me") } }

        runCurrent()

        assertFalse(result.await())
        assertFalse(machine.drafts.value.containsKey(1L))

        val followUp = async { machine.updateImmediate(1L) { it.copy(rawInput = "alive") } }
        runCurrent()
        assertTrue(followUp.await())
        ownerScope.cancel()
    }

    private class ThrowingWriteDraftStore(
        private val failure: () -> Throwable,
        private val writeGate: CompletableDeferred<Unit>? = null,
    ) : PersistentComposerDraftStore(
        file = Files.createTempFile("fusion-owner-death-fatal", ".json").toFile(),
        resolveManagedAttachment = { null },
        registerPendingAttachment = { it.absolutePath },
    ) {
        override suspend fun load(): Map<Long, ComposerDraftState> = emptyMap()

        override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(emptyMap())

        override suspend fun write(writeId: Long, drafts: Map<Long, ComposerDraftState>): Boolean {
            writeGate?.await()
            throw failure()
        }
    }

    private class FailWriteDraftStore : PersistentComposerDraftStore(
        file = Files.createTempFile("fusion-owner-death-fail", ".json").toFile(),
        resolveManagedAttachment = { null },
        registerPendingAttachment = { it.absolutePath },
    ) {
        override suspend fun load(): Map<Long, ComposerDraftState> = emptyMap()

        override suspend fun loadResult(): DraftStoreLoad = DraftStoreLoad.Valid(emptyMap())

        override suspend fun write(writeId: Long, drafts: Map<Long, ComposerDraftState>): Boolean = false
    }

    private class GatedDraftStore(
        private val loadGate: CompletableDeferred<Unit>,
    ) : PersistentComposerDraftStore(
        file = Files.createTempFile("fusion-owner-death", ".json").toFile(),
        resolveManagedAttachment = { null },
        registerPendingAttachment = { it.absolutePath },
    ) {
        override suspend fun load(): Map<Long, ComposerDraftState> {
            loadGate.await()
            return emptyMap()
        }

        override suspend fun loadResult(): DraftStoreLoad {
            loadGate.await()
            return DraftStoreLoad.Valid(emptyMap())
        }
    }

    private class DeferredWriteDraftStore(
        private val loadGate: CompletableDeferred<Unit>,
        private val writeStarted: CompletableDeferred<Unit>,
        private val writeGate: CompletableDeferred<Unit>,
    ) : PersistentComposerDraftStore(
        file = Files.createTempFile("fusion-owner-death-write", ".json").toFile(),
        resolveManagedAttachment = { null },
        registerPendingAttachment = { it.absolutePath },
    ) {
        override suspend fun load(): Map<Long, ComposerDraftState> {
            loadGate.await()
            return emptyMap()
        }

        override suspend fun loadResult(): DraftStoreLoad {
            loadGate.await()
            return DraftStoreLoad.Valid(emptyMap())
        }

        override suspend fun write(writeId: Long, drafts: Map<Long, ComposerDraftState>): Boolean {
            writeStarted.complete(Unit)
            writeGate.await()
            return true
        }
    }
}
