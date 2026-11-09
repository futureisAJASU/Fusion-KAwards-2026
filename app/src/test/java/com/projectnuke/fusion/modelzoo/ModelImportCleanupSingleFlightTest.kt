package com.projectnuke.fusion.modelzoo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F11-05: the recovery single-flight claim must never stay occupied when the
 * launched coroutine never enters its body.
 *
 * The ownership flag is claimed before the coroutine starts, so releasing it
 * from a body-local `finally` was unsound: a scope whose job is already
 * canceled -- or one canceled between launch and dispatch -- may never enter the
 * body, leaving the flag occupied forever and permanently refusing every later
 * recovery request. The claim is now released from Job completion, which fires
 * for completion, failure and cancellation alike and runs exactly once.
 *
 * The coordinator is built through the production constructor with a stubbed
 * validator, so the cleanup gate itself is exercised unmodified.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelImportCleanupSingleFlightTest {

    @Test
    fun `already canceled scope releases the claim so a later request can run`() = runTest {
        var invocations = 0
        val underTest = coordinator()
        val deadScope = CoroutineScope(Job().apply { cancel() } + UnconfinedTestDispatcher(testScheduler))

        // Acceptance is not the contract under test; the claim being freed is.
        underTest.requestCleanup(deadScope) { invocations++ }
        advanceUntilIdle()
        assertEquals(
            "a body in an already-canceled scope must never run",
            0,
            invocations,
        )

        assertTrue(
            "the claim must not stay occupied by a body that never ran",
            underTest.requestCleanup(this) { invocations++ },
        )
        advanceUntilIdle()
        assertEquals(1, invocations)
    }

    @Test
    fun `cancellation during execution still releases the claim`() = runTest {
        var invocations = 0
        val underTest = coordinator()
        val liveScope = CoroutineScope(coroutineContext + Job())

        assertTrue(
            underTest.requestCleanup(liveScope) {
                invocations++
                throw CancellationException("stop")
            },
        )
        advanceUntilIdle()
        assertEquals(1, invocations)

        assertTrue(underTest.requestCleanup(liveScope) { invocations++ })
        advanceUntilIdle()
        assertEquals(2, invocations)
    }

    @Test
    fun `normal completion releases the claim`() = runTest {
        var invocations = 0
        val underTest = coordinator()

        assertTrue(underTest.requestCleanup(this) { invocations++ })
        advanceUntilIdle()
        assertEquals(1, invocations)
        assertTrue(
            "a completed pass must free the gate",
            underTest.requestCleanup(this) { invocations++ },
        )
        advanceUntilIdle()
        assertEquals(2, invocations)
    }

    @Test
    fun `fatal error in the body still releases the claim`() = runTest {
        var invocations = 0
        val underTest = coordinator()
        // A dedicated TestScope with an exception handler observes the fatal
        // Error without letting it escape and abort the test body, so the gate
        // state can be asserted AFTER the body died.
        val captured = CompletableDeferred<Throwable>()
        val fatalScope = CoroutineScope(
            coroutineContext + Job() + Dispatchers.Unconfined +
                CoroutineExceptionHandler { _, error -> captured.complete(error) },
        )

        assertTrue(
            underTest.requestCleanup(fatalScope) {
                invocations++
                throw LinkageError("broken runtime")
            },
        )
        advanceUntilIdle()

        assertTrue(
            "a fatal Error must be reported, not swallowed",
            captured.isCompleted && captured.getCompleted() is LinkageError,
        )
        assertTrue(
            "a fatal error must not wedge the recovery gate",
            underTest.requestCleanup(this) { invocations++ },
        )
        advanceUntilIdle()
        assertEquals("the later request must actually run", 2, invocations)
    }

    @Test
    fun `a request that arrives while a pass is active is refused not queued`() = runTest {
        var invocations = 0
        val underTest = coordinator()
        val bodyStarted = CompletableDeferred<Unit>()
        val releaseBody = CompletableDeferred<Unit>()

        val job = launch {
            underTest.requestCleanup(this) {
                invocations++
                bodyStarted.complete(Unit)
                releaseBody.await()
            }
        }
        runCurrent()
        assertTrue(bodyStarted.isCompleted)

        assertFalse(
            "a concurrent request must be refused, not run alongside the active pass",
            underTest.requestCleanup(this) { invocations++ },
        )

        releaseBody.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertEquals("only the accepted pass may run", 1, invocations)
    }

    private fun coordinator() = ModelImportCoordinator(
        modelDirectory = File(System.getProperty("java.io.tmpdir"), "fusion-p11-models"),
        openSource = { null },
        validator = LiteRtLmValidator { false },
    )
}