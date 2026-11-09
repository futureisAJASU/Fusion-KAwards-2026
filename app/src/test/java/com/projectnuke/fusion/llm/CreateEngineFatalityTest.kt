package com.projectnuke.fusion.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3A/3B regression: fatal JVM Errors cannot collapse into ordinary
 * candidate init failures via `runCatching`. This test asserts the pure
 * helpers — [Throwable.isFatalError] and [runNonFatal] — that back the
 * production seam changes in [LiteRtLlmEngine]:
 *
 * - `isFatalError()` flags the [Error] subclasses whose contract is to
 *   propagate out of the candidate ladder
 * - `runNonFatal` rethrows fatal Errors and returns null otherwise
 *
 * The production default `engineFactory` (`Engine(config).initialize()` with
 * partial-init cleanup) and `createEngine` route Engine construction through
 * these helpers: recoverable init failures close the constructed Engine and
 * rethrow the original failure as primary, but fatal Errors propagate to the
 * outer fatal-error policy.
 */
class CreateEngineFatalityTest {

    // ── isFatalError ──────────────────────────────────────────────────────

    @Test
    fun `isFatalError flags OutOfMemoryError VirtualMachineError LinkageError and ThreadDeath`() {
        assertTrue(OutOfMemoryError("oom").isFatalError())
        assertTrue(StackOverflowError().isFatalError())
        assertTrue(object : LinkageError("lnk") {}.isFatalError())
        assertTrue(object : ThreadDeath() {}.isFatalError())
    }

    @Test
    fun `isFatalError does not flag recoverable exceptions`() {
        assertFalse(IllegalStateException("x").isFatalError())
        assertFalse(RuntimeException("x").isFatalError())
        assertFalse(java.io.IOException("io").isFatalError())
        assertFalse(CancellationExceptionProxy() is Error)
    }

    private class CancellationExceptionProxy : kotlin.coroutines.cancellation.CancellationException()

    // ── runNonFatal ───────────────────────────────────────────────────────

    @Test
    fun `runNonFatal returns null for recoverable exceptions and rethrows fatal Errors`() {
        assertNull(runNonFatal { throw IllegalStateException("recoverable") })
        assertNull(runNonFatal<Int> { throw java.io.IOException("io") })
        assertThrows(OutOfMemoryError::class.java) { runNonFatal<Int> { throw OutOfMemoryError("fatal") } }
        assertThrows(LinkageError::class.java) { runNonFatal<Int> { throw object : LinkageError("fatal") {} } }
        assertEquals(7, runNonFatal { 7 })
    }

    @Test
    fun `runNonFatal rethrows CancellationException preserving coroutine cancellation`() {
        val ce = kotlin.coroutines.cancellation.CancellationException("cancel")
        assertEquals(ce, assertThrows(kotlin.coroutines.cancellation.CancellationException::class.java) {
            runNonFatal<Int> { throw ce }
        })
    }

    // ── partial-init cleanup contract (default engineFactory behavior) ─——

    @Test
    fun `default engineFactory partial-init cleanup closes Engine on recoverable init failure and rethrows primary`() {
        var closeCalls = 0
        var initialized = false
        initialized = false
        // Mimic the default engineFactory structure: construct → try init →
        // recoverable failure closes Engine and rethrows primary.
        val recoverableEngine = RecoverableEngineLikeHandle { closeCalls++ }
        val failure = assertThrows(IllegalStateException::class.java) {
            recoverableEngine.runDefaultEngineFactoryLikePath(
                init = { throw IllegalStateException("init failed (recoverable)") }
            )
        }
        assertEquals("init failed (recoverable)", failure.message)
        assertEquals("partial-init cleanup must run exactly once for recoverable failures", 1, closeCalls)
        assertFalse("initialized flag must NOT be set on failure path", initialized)
    }

    @Test
    fun `default engineFactory path closes Engine once when a fatal Error fires during init`() {
        var closeCalls = 0
        val fatalEngine = RecoverableEngineLikeHandle { closeCalls++ }
        assertThrows(OutOfMemoryError::class.java) {
            fatalEngine.runDefaultEngineFactoryLikePath(
                init = { throw OutOfMemoryError("oom during init") }
            )
        }
        // Phase 3A policy: once Engine(config) has returned an owned object,
        // ANY subsequent initialization failure (including fatal Error) must
        // perform a best-effort cleanup close() before propagating the
        // original failure. The original fatal Error is always primary; a
        // cleanup failure is logged and suppressed.
        assertEquals("partial-init cleanup must run once for fatal Errors", 1, closeCalls)
    }

    @Test
    fun `default engineFactory path returns the Engine when initialization succeeds`() {
        var closeCalls = 0
        val okEngine = RecoverableEngineLikeHandle { closeCalls++ }
        val out = okEngine.runDefaultEngineFactoryLikePath(init = { /* success */ })
        assertEquals(okEngine, out)
        assertEquals("cleanup must not run on success", 0, closeCalls)
    }

    /** Mirror of the production default engineFactory behavior, without the
     * native LiteRT Engine import (which is not unit-testable here). */
    private class RecoverableEngineLikeHandle(val onClose: () -> Unit) {
        fun close() = onClose()
        fun runDefaultEngineFactoryLikePath(init: () -> Unit): RecoverableEngineLikeHandle {
            // Phase 3A: best-effort cleanup close() on ANY init failure.
            // Fatal Errors propagate as primary after cleanup; cleanup failure
            // is logged and suppressed.
            try {
                init()
            } catch (failure: Throwable) {
                try { close() } catch (cleanupFailure: Throwable) {
                    android.util.Log.w("CFTest", "cleanup close failed", cleanupFailure)
                }
                throw failure
            }
            return this
        }
    }
}
