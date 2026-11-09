package com.projectnuke.fusion.modelzoo

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the coordinator-owned asynchronous cleanup gate (P2.6).
 *
 * The gate itself is dispatcher-agnostic and Context-free; production wires
 * the real catalog/IO body through [ModelImportCoordinator.requestCleanupAbandoned].
 * Every test below observes only the refusal/acceptance contract and the
 * body invocation count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelImportCoordinatorCleanupTest {

    private fun coordinator(): ModelImportCoordinator {
        val dir = Files.createTempDirectory("fusion-cleanup-gate").toFile()
        dir.deleteRecursively()
        return ModelImportCoordinator(
            modelDirectory = dir,
            openSource = { null },
        )
    }

    @Test
    fun `two concurrent cleanup requests run the recovery body once`() = runTest {
        val gate = coordinator()
        var calls = 0
        val release = CompletableDeferred<Unit>()

        assertTrue(gate.requestCleanup(this) {
            calls++
            release.await()
        })
        // The first pass owns the gate while suspended; a repeated resume
        // is refused instead of queueing unbounded work.
        assertFalse(gate.requestCleanup(this) { calls++ })

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test
    fun `gate releases after success and next cleanup executes`() = runTest {
        val gate = coordinator()
        var calls = 0

        assertTrue(gate.requestCleanup(this) { calls++ })
        advanceUntilIdle()
        assertEquals(1, calls)

        assertTrue(gate.requestCleanup(this) { calls++ })
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test
    fun `gate releases after exception and next cleanup executes`() = runTest {
        val gate = coordinator()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, e -> errors += e },
        )
        try {
            var calls = 0
            assertTrue(gate.requestCleanup(scope) {
                calls++
                throw IllegalStateException("boom")
            })
            advanceUntilIdle()
            assertEquals(1, calls)
            assertEquals(1, errors.size)

            assertTrue(gate.requestCleanup(scope) { calls++ })
            advanceUntilIdle()
            assertEquals(2, calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `gate releases after cancellation and next cleanup executes`() = runTest {
        val gate = coordinator()
        var calls = 0
        var child: Job? = null
        val gate2 = CompletableDeferred<Unit>()

        assertTrue(gate.requestCleanup(this) {
            calls++
            // Intrinsic context: the launched child itself, never the
            // lexical TestScope receiver.
            child = currentCoroutineContext()[Job]
            gate2.await()
        })
        runCurrent()
        assertFalse(gate.requestCleanup(this) { calls++ })

        child!!.cancelAndJoin()
        assertEquals(1, calls)

        assertTrue(gate.requestCleanup(this) { calls++ })
        advanceUntilIdle()
        assertEquals(2, calls)
    }
}
