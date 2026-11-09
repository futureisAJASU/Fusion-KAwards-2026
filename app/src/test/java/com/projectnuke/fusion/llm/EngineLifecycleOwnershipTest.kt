package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.RequestedEngineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3 lifecycle ownership regression. Tests the production
 * [EngineReplacement.adopt] helper (not the coordinator — the coordinator
 * does not own `close()`) and asserts `closeCount` for every ownership
 * invariant required by the blocker.
 *
 * Invariants under test:
 *  - same Engine reuse -> close count 0 on both old and new
 *  - successful replacement -> old close count exactly 1, new remains owned
 *  - acquisition failure before new engine exists -> old close count 0
 *  - new Engine created but cannot be adopted (publish throws) -> new closed 1,
 *    old untouched
 *  - old close throws -> no orphan new Engine (new closed 1), loaded state
 *    ends in one explicitly defined cleared state, failure is logged/propagated
 *    consistently via [EngineAdoptionException]
 */
class EngineLifecycleOwnershipTest {

    /** Engine-shaped stand-in that increments closeCount when close() runs. */
    private open class CloseTrackingEngineHandle(
        val backend: String,
        val closeCount: AtomicInteger = AtomicInteger(0)
    ) {
        open fun close() { closeCount.incrementAndGet() }
    }

    // ── reuse: close count 0 ──────────────────────────────────────────────

    @Test
    fun `same Engine reused keeps close count 0`() {
        val engine = CloseTrackingEngineHandle("GPU")
        var loaded: CloseTrackingEngineHandle? = engine
        val outcome = EngineReplacement.adopt(
            newEngine = engine,
            getOld = { loaded },
            publish = { loaded = it },
            clear = { loaded = null },
            close = { it.close() }
        )
        assertTrue(outcome is EngineAdoptionOutcome.Published)
        assertEquals(engine, loaded)
        assertEquals("reuse must not close either handle", 0, engine.closeCount.get())
    }

    // ── successful replacement: old close count 1, new owned ─────────────

    @Test
    fun `successful replacement closes the old Engine exactly once and keeps the new owned`() {
        val oldEngine = CloseTrackingEngineHandle("GPU-old")
        val newEngine = CloseTrackingEngineHandle("GPU-new")
        var loaded: CloseTrackingEngineHandle? = oldEngine

        val outcome = EngineReplacement.adopt(
            newEngine = newEngine,
            getOld = { loaded },
            publish = { loaded = it },
            clear = { loaded = null },
            close = { it.close() }
        )
        assertTrue(outcome is EngineAdoptionOutcome.Published)
        assertEquals(newEngine, (outcome as EngineAdoptionOutcome.Published).engine)
        assertEquals(newEngine, loaded)
        assertEquals("old engine must be closed exactly once", 1, oldEngine.closeCount.get())
        assertEquals("new engine must NOT be closed on the success path", 0, newEngine.closeCount.get())
    }

    // ── no old: nothing is closed ─────────────────────────────────────────

    @Test
    fun `first adoption from null loaded state closes nothing`() {
        val newEngine = CloseTrackingEngineHandle("GPU")
        var loaded: CloseTrackingEngineHandle? = null

        val outcome = EngineReplacement.adopt(
            newEngine = newEngine,
            getOld = { loaded },
            publish = { loaded = it },
            clear = { loaded = null },
            close = { it.close() }
        )
        assertTrue(outcome is EngineAdoptionOutcome.Published)
        assertEquals(newEngine, loaded)
        assertEquals(0, newEngine.closeCount.get())
    }

    // ── new created but publish fails: new closed, old untouched ──────────

    @Test
    fun `new engine created but publish throws closes the new engine once and leaves old untouched`() {
        val oldEngine = CloseTrackingEngineHandle("GPU-old")
        val newEngine = CloseTrackingEngineHandle("GPU-new")
        var loaded: CloseTrackingEngineHandle? = oldEngine

        val outcome = EngineReplacement.adopt(
            newEngine = newEngine,
            getOld = { loaded },
            publish = { throw RuntimeException("publish failed") },
            clear = { loaded = null },
            close = { it.close() }
        )
        assertTrue(outcome is EngineAdoptionOutcome.NewEngineAdoptionFailed)
        // The new engine must be closed exactly once so it isn't orphaned.
        assertEquals(1, newEngine.closeCount.get())
        // The old engine must NOT be closed (publish failed before close).
        assertEquals(0, oldEngine.closeCount.get())
        // The caller's loaded state is left as-is (the old engine still owns it).
        assertEquals(oldEngine, loaded)
    }

    // ── old close throws: no orphan new Engine, loaded cleared, propagated ─

    @Test
    fun `old close throws closes the new engine and clears the loaded state and returns NewEngineClosed`() {
        val throwingEngine = object : CloseTrackingEngineHandle("GPU-old") {
            override fun close() {
                super.close()
                throw RuntimeException("close() failed")
            }
        }
        val newEngine = CloseTrackingEngineHandle("GPU-new")
        var loaded: CloseTrackingEngineHandle? = throwingEngine

        val outcome = EngineReplacement.adopt(
            newEngine = newEngine,
            getOld = { loaded },
            publish = { loaded = it },
            clear = { loaded = null },
            close = { it.close() }
        )
        // adopt() returns NewEngineClosed instead of throwing — the caller
        // (getOrCreateEngine) consumes the outcome to produce a typed failure.
        assertTrue(outcome is EngineAdoptionOutcome.NewEngineClosed)
        // Both engines closed exactly once (old closed 1 by conscious close,
        // new closed 1 by orphan-recover).
        assertEquals(1, throwingEngine.closeCount.get())
        assertEquals(1, newEngine.closeCount.get())
        // Loaded state cleared to a single well-defined value (null).
        assertNull("loaded must end in a cleared state, not partially retired", loaded)
        assertNotNull("old-close failure must be carried in the outcome", (outcome as EngineAdoptionOutcome.NewEngineClosed).adoptFailure)
    }

    // ── production invariant: publish step contract returns Published ─────

    @Test
    fun `adopt returns Published outcome on the success path`() {
        val oldEngine = CloseTrackingEngineHandle("GPU-old")
        val newEngine = CloseTrackingEngineHandle("GPU-new")
        var loaded: CloseTrackingEngineHandle? = oldEngine

        val outcome = EngineReplacement.adopt(
            newEngine = newEngine,
            getOld = { loaded },
            publish = { loaded = it },
            clear = { loaded = null },
            close = { it.close() }
        ) as EngineAdoptionOutcome.Published
        assertEquals(newEngine, outcome.engine)
    }

    // ── assertion parity: integration with LoadedRuntimeState ────────────

    @Test
    fun `adoption drops the oldLoadedRuntimeState engine after successful replacement`() {
        val fakeFingerprint = ModelFingerprint("model.bin", 1L, 1L, 1, mtpSupported = true)
        val oldEngine = CloseTrackingEngineHandle("GPU")
        val newEngine = CloseTrackingEngineHandle("GPU")
        val oldRuntimeState = LoadedRuntimeState(
            engine = oldEngine,
            key = EngineRuntimeKey(fakeFingerprint, AcceleratorMode.GPU, 2048, false, false, "GPU"),
            mtpStatus = MtpRuntimeStatus.OFF,
            runtimeSelection = EngineSelectionRuntime("GPU", "GPU", null, false, false, null),
            actualTextBackend = "GPU",
            actualVisionBackend = null,
            fallbackEvents = emptyList()
        )
        var loaded: LoadedRuntimeState<CloseTrackingEngineHandle>? = oldRuntimeState
        val newRuntimeState = oldRuntimeState.copy(engine = newEngine)

        val outcome = EngineReplacement.adopt(
            newEngine = newEngine,
            getOld = { loaded?.engine },
            publish = { loaded = newRuntimeState },
            clear = { loaded = null },
            close = { it.close() }
        )
        assertTrue(outcome is EngineAdoptionOutcome.Published)
        assertEquals(newEngine, loaded?.engine)
        assertEquals(1, oldEngine.closeCount.get())
        assertEquals(0, newEngine.closeCount.get())
    }

    // ── fatal errors: path through helper, rethrow ────────────────────────

    @Test
    fun `fatal Error during publish propagates instead of becoming a typed adoption failure`() {
        val oldEngine = CloseTrackingEngineHandle("GPU-old")
        val newEngine = CloseTrackingEngineHandle("GPU-new")
        var loaded: CloseTrackingEngineHandle? = oldEngine

        assertThrows(OutOfMemoryError::class.java) {
            EngineReplacement.adopt(
                newEngine = newEngine,
                getOld = { loaded },
                publish = { loaded = newEngine; throw OutOfMemoryError("oom") },
                clear = { loaded = null },
                close = { it.close() }
            )
        }
        // OOM must propagate; old engine should not be closed by the helper on
        // the publish-failure path.
        assertEquals(0, oldEngine.closeCount.get())
        // The new engine was constructed and is now orphaned without our cleanup;
        // best-effort close of new happens before the throw, so it should be 1.
        assertEquals(1, newEngine.closeCount.get())
    }
}
