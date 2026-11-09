package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.RequestedEngineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 regression: explicit fallback-event ownership.
 *
 * The wrapper-layer (EngineAcquisitionCoordinator) returns explicit
 * `engineAcquisitionEvents` vs `requestEvents` plus a `reusedLoadedEngine`
 * flag rather than a single list that callers later partition by
 * [FallbackReason]. Partitioning by reason is incorrect because the same
 * reason can be a freshly-created Engine's acquisition history OR a
 * current-request event depending on which Engine was selected.
 *
 * Tests exercise the production coordinator (which wraps the production
 * `selectFirstWorkingEngine`) plus the loaded-state reuse path; they do not
 * duplicate the selector implementation.
 */
class Phase2FallbackEventOwnershipTest {

    private data class FakeEngineHandle(val backend: String)

    private class CountingFailureMemory : MtpFailureMemoryPort {
        var skipMtpResult: String? = null
        var skipBackendResult: String? = null
        val skipMtpCalls = mutableListOf<String>()
        val skipBackendCalls = mutableListOf<String>()
        val recordFailures = mutableListOf<String>()
        override fun shouldSkipMtp(
            modelPath: String, backendName: String, mtpEnabled: Boolean,
            validationVersion: Int, kvCacheCapacityTokens: Int, enableVisionBackend: Boolean,
            fileSize: Long, modifiedAt: Long, mtpSupported: Boolean
        ): String? { skipMtpCalls += backendName; return skipMtpResult }
        override fun shouldSkipBackend(
            modelPath: String, backendName: String,
            validationVersion: Int, kvCacheCapacityTokens: Int, enableVisionBackend: Boolean,
            fileSize: Long, modifiedAt: Long, mtpSupported: Boolean
        ): String? { skipBackendCalls += backendName; return skipBackendResult }
        override fun recordFailure(
            modelPath: String, backendName: String, mtpEnabled: Boolean,
            validationVersion: Int, kvCacheCapacityTokens: Int, enableVisionBackend: Boolean,
            fileSize: Long, modifiedAt: Long, mtpSupported: Boolean, fallbackReason: String
        ) { recordFailures += "$backendName:mtp=$mtpEnabled" }
    }

    private val fakeFingerprint = ModelFingerprint(
        canonicalPath = "model.bin",
        fileSize = 100L,
        modifiedAt = 1000L,
        validationVersion = 1,
        mtpSupported = true
    )

    private val gpuPlainProfile = RequestedEngineProfile(
        modelPath = "model.bin",
        accelerator = AcceleratorMode.AUTO,
        kvCacheCapacityTokens = 2048,
        mtpRequested = true,
        enableVisionBackend = false
    )

    private fun loadedGpuPlain(engine: FakeEngineHandle, fallbackEvents: List<RuntimeFallbackEvent> = emptyList()) =
        LoadedRuntimeState(
            engine = engine,
            key = EngineRuntimeKey(
                fingerprint = fakeFingerprint,
                accelerator = AcceleratorMode.AUTO,
                kvCacheCapacityTokens = 2048,
                enableVisionBackend = false,
                mtpEnabled = false,
                selectedBackend = "GPU"
            ),
            mtpStatus = MtpRuntimeStatus.OFF,
            runtimeSelection = EngineSelectionRuntime("AUTO", "GPU", null, true, false, null),
            actualTextBackend = "GPU",
            actualVisionBackend = null,
            fallbackEvents = fallbackEvents
        )

    private fun coordinator(
        memory: CountingFailureMemory = CountingFailureMemory(),
        probe: (String) -> Boolean? = { true },
        flag: (Boolean) -> Boolean = { true },
        factory: (String, Boolean, Boolean) -> EngineCandidateAttempt<FakeEngineHandle> = { backend, _, _ ->
            EngineCandidateAttempt.Success(FakeEngineHandle(backend))
        }
    ) = EngineAcquisitionCoordinator(
        mtpFailureMemory = memory,
        mtpCapabilityProbe = probe,
        configureFlag = flag,
        engineFactory = factory,
        clock = { 0L },
        fingerprintResolver = { fakeFingerprint }
    )

    // ?�?� Case 1: loaded plain GPU + current GPU+MTP capability probe rejects
    //            ??plain GPU reused; MTP_UNSUPPORTED is a request event ?�?�?�?�

    @Test
    fun `loaded plain GPU reused after MTP probe rejects puts MTP_UNSUPPORTED in requestEvents not engineAcquisitionEvents`() {
        val loadedEngine = FakeEngineHandle("GPU")
        val loadedState = loadedGpuPlain(loadedEngine)

        // Probe rejects MTP; factory would create a fresh engine ??but the
        // plain-GPU loaded engine must be reused before that.
        val coord = coordinator(
            probe = { false },
            factory = { backend, _, _ -> EngineCandidateAttempt.Success(FakeEngineHandle("$backend-new")) }
        )

        val outcome = coord.acquire(gpuPlainProfile, loadedState)

        assertNotNull(outcome.selection)
        assertEquals(loadedEngine, outcome.selection!!.engine)
        assertTrue("selection must report reused loaded engine", outcome.reusedLoadedEngine)
        // MTP_UNSUPPORTED event MUST be present (this is the bug fixed here).
        val mtpEvents = outcome.requestEvents.filter { it.reason == FallbackReason.MTP_UNSUPPORTED }
        assertEquals(1, mtpEvents.size)
        // The reused engine's stable acquisition history must NOT include the
        // request-local MTP_UNSUPPORTED event.
        assertFalse(outcome.engineAcquisitionEvents.any { it.reason == FallbackReason.MTP_UNSUPPORTED })
        // The combined view still contains MTP_UNSUPPORTED.
        assertTrue(outcome.fallbackEvents.any { it.reason == FallbackReason.MTP_UNSUPPORTED })
    }

    // ?�?� Case 2: higher MTP flag-settlement fails + lower live plain GPU reused
    //            ??SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED is a request event

    @Test
    fun `MTP flag settlement failure before reuse of loaded plain GPU is a request event`() {
        val loadedEngine = FakeEngineHandle("GPU")
        val loadedState = loadedGpuPlain(loadedEngine)

        // Flag settlement fails only when enabling MTP (true), succeeds otherwise.
        val coord = coordinator(
            probe = { true },
            flag = { enabled -> !enabled },
            factory = { backend, _, _ -> EngineCandidateAttempt.Success(FakeEngineHandle("$backend-new")) }
        )

        val outcome = coord.acquire(gpuPlainProfile, loadedState)

        assertNotNull(outcome.selection)
        assertEquals(loadedEngine, outcome.selection!!.engine)
        assertTrue(outcome.reusedLoadedEngine)
        assertTrue(outcome.requestEvents.any { it.reason == FallbackReason.SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED })
        assertFalse(outcome.engineAcquisitionEvents.any { it.reason == FallbackReason.SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED })
    }

    // ?�?� Case 3: a second clean request does not inherit the prior request-local
    //            event, and repeated reuse does not grow the loaded history ?�?�

    @Test
    fun `second clean request after a reused request does not carry the prior request-local event`() {
        val loadedEngine = FakeEngineHandle("GPU")
        val loadedState = loadedGpuPlain(loadedEngine)

        val coord = coordinator(
            probe = { false },
            factory = { backend, _, _ -> EngineCandidateAttempt.Success(FakeEngineHandle("$backend-new")) }
        )

        // First request: MTP probe rejects on the plain-GPU loaded engine;
        // the MTP_UNSUPPORTED event is request-local (not engine provenance).
        val first = coord.acquire(gpuPlainProfile, loadedState)
        assertTrue(first.reusedLoadedEngine)
        assertTrue(first.requestEvents.any { it.reason == FallbackReason.MTP_UNSUPPORTED })

        // Second request: MTP is now explicitly OFF (clean request) while
        // reusing the same loaded plain-GPU engine. The prior request-local
        // MTP_UNSUPPORTED must NOT appear in either requestEvents or
        // engineAcquisitionEvents.
        val cleanProfile = gpuPlainProfile.copy(mtpRequested = false)
        val second = coord.acquire(cleanProfile, loadedState)
        assertTrue(second.reusedLoadedEngine)
        assertFalse(second.engineAcquisitionEvents.any { it.reason == FallbackReason.MTP_UNSUPPORTED })
        assertFalse(second.requestEvents.any { it.reason == FallbackReason.MTP_UNSUPPORTED })
    }

    @Test
    fun `repeated reuse keeps the loaded finished-state fallback-event list unchanged in size`() {
        val loadedEngine = FakeEngineHandle("GPU")
        // Start with a known small stable history on the loaded engine (e.g.
        // the engine's original MTP init failure that produced this plain GPU).
        val stableHistory = listOf(
            RuntimeFallbackEvent(
                attemptedTextBackend = RuntimeBackend.GPU,
                attemptedMtpEnabled = true,
                reason = FallbackReason.MTP_ENGINE_INIT_FAILED
            )
        )
        val loadedState = loadedGpuPlain(loadedEngine, fallbackEvents = stableHistory)

        val coord = coordinator(
            probe = { false },
            factory = { backend, _, _ -> EngineCandidateAttempt.Success(FakeEngineHandle("$backend-new")) }
        )

        repeat(3) {
            val outcome = coord.acquire(gpuPlainProfile, loadedState)
            assertTrue(outcome.reusedLoadedEngine)
            // Engine-acquisition events for a reuse must be empty ??the loaded
            // stable history never grows from a reused request.
            assertEquals(0, outcome.engineAcquisitionEvents.size)
            // The loaded stable history we passed in is untouched (not returned
            // by the coordinator as engineAcquisitionEvents).
            assertEquals(stableHistory.size, loadedState.fallbackEvents.size)
        }
    }

    // ?�?� Lazy failure-memory lookup regression: candidate 1 success means
    //    candidate 2/3 failure-memory lookups do NOT happen ?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�

    @Test
    fun `first candidate success skips failure-memory lookups for later candidates`() {
        val memory = CountingFailureMemory()
        // GPU+MTP ladder candidate succeeds immediately (probe true, factory ok).
        val coord = coordinator(
            memory = memory,
            probe = { true },
            factory = { backend, mtp, _ -> EngineCandidateAttempt.Success(FakeEngineHandle("$backend-mtp=$mtp")) }
        )

        val outcome = coord.acquire(gpuPlainProfile, null)
        assertNotNull(outcome.selection)
        assertTrue(outcome.selection!!.selectedMtpEnabled)

        // Candidate 1 (GPU+MTP) succeeded. The only MTP failure-memory lookup
        // that may have happened is the GPU+MTP candidate's ??i.e. exactly one
        // MTP check on "GPU". The GPU-plain and CPU-plain (backend-skip)
        // lookups for later candidates MUST NOT have happened.
        assertEquals(1, memory.skipMtpCalls.size)
        assertEquals("GPU", memory.skipMtpCalls.single())
        assertEquals(0, memory.skipBackendCalls.size)
        // No MTP problem was observed by candidates that followed; no skip events.
        assertFalse(outcome.fallbackEvents.any { it.reason == FallbackReason.MTP_SKIPPED_RECENT_FAILURE })
        assertFalse(outcome.fallbackEvents.any { it.reason == FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE })
    }

    // ?�?� Fresh-create success path: ALL events are engine-acquisition events ?�

    @Test
    fun `fresh GPU+MTP init failure then plain GPU success tags events as engineAcquisitionEvents`() {
        val memory = CountingFailureMemory()
        val coord = coordinator(
            memory = memory,
            probe = { true },
            factory = { backend, mtp, _ ->
                if (mtp && backend == "GPU") {
                    EngineCandidateAttempt.InitializationFailed(RuntimeException("gpu+mtp init failed"))
                } else {
                    EngineCandidateAttempt.Success(FakeEngineHandle("$backend-plain"))
                }
            }
        )

        val outcome = coord.acquire(gpuPlainProfile, null)
        assertNotNull(outcome.selection)
        assertFalse(outcome.reusedLoadedEngine)
        // The MTP_ENGINE_INIT_FAILED event is the freshly created Engine's
        // causal acquisition history ??request-local events stay empty.
        assertFalse(outcome.engineAcquisitionEvents.isEmpty())
        assertTrue(outcome.engineAcquisitionEvents.any { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED })
        // No request-local events when an engine was freshly created.
        assertEquals(0, outcome.requestEvents.filter { it.reason != FallbackReason.MTP_ENGINE_INIT_FAILED }.size
            // The MTP_ENGINE_INIT_FAILED event belongs to the acquisition history; the request set is empty here.
            .let { outcome.requestEvents.size })
    }
    // ?�?� Wrapper-level provenance tests A/B/C ?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�
    // These verify the full engine-acquisition ??snapshot-ownership flow at
    // the coordinator (wrapper) level:
    //  - A: fresh engine creation with MTP failure ??engineAcquisitionEvents
    //    carries the MTP failure (Engine provenance = this request's history)
    //  - B: reused engine with MTP explicitly OFF ??requestEvents stays empty;
    //    engineAcquisitionEvents stays empty; no MTP failure appears in either
    //  - C: fresh engine creation again with MTP ON ??engineAcquisitionEvents
    //    carries ONLY the current MTP init result (no stale MTP failure)

    @Test
    fun `A fresh engine with MTP failure carries MTP failure in engineAcquisitionEvents`() {
        val memory = CountingFailureMemory()
        val coord = coordinator(
            memory = memory,
            probe = { true },
            factory = { backend, mtp, _ ->
                if (mtp && backend == "GPU") {
                    EngineCandidateAttempt.InitializationFailed(RuntimeException("gpu+mtp init failed"))
                } else {
                    EngineCandidateAttempt.Success(FakeEngineHandle("$backend-plain"))
                }
            }
        )

        val outcome = coord.acquire(gpuPlainProfile, null)
        assertNotNull(outcome.selection)
        assertFalse(outcome.reusedLoadedEngine)
        // A: engineAcquisitionEvents carries the MTP failure (this request's
        // acquisition history IS the Engine provenance for a fresh engine).
        assertTrue(outcome.engineAcquisitionEvents.any { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED })
        assertEquals(0, outcome.requestEvents.size)
    }

    @Test
    fun `B reused engine with MTP OFF produces no MTP events`() {
        val loadedEngine = FakeEngineHandle("GPU")
        val loadedState = loadedGpuPlain(loadedEngine)

        val coord = coordinator(
            probe = { true },
            factory = { backend, _, _ -> EngineCandidateAttempt.Success(FakeEngineHandle("$backend-new")) }
        )

        // Reuse the loaded plain-GPU with MTP explicitly OFF.
        val cleanProfile = gpuPlainProfile.copy(mtpRequested = false)
        val outcome = coord.acquire(cleanProfile, loadedState)
        assertTrue(outcome.reusedLoadedEngine)
        // B: no MTP events at all when MTP is OFF.
        assertFalse(outcome.engineAcquisitionEvents.any { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED })
        assertFalse(outcome.requestEvents.any { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED })
        assertFalse(outcome.requestEvents.any { it.reason == FallbackReason.MTP_UNSUPPORTED })
    }

    @Test
    fun `C fresh engine with MTP ON carries only current MTP result`() {
        val memory = CountingFailureMemory()
        // Simulate a fresh engine creation where MTP init fails then plain
        // GPU succeeds. The engineAcquisitionEvents must carry ONLY the
        // current request's MTP failure, not any stale history.
        val coord = coordinator(
            memory = memory,
            probe = { true },
            factory = { backend, mtp, _ ->
                if (mtp && backend == "GPU") {
                    EngineCandidateAttempt.InitializationFailed(RuntimeException("gpu+mtp init failed"))
                } else {
                    EngineCandidateAttempt.Success(FakeEngineHandle("$backend-plain"))
                }
            }
        )

        val outcome = coord.acquire(gpuPlainProfile, null)
        assertNotNull(outcome.selection)
        assertFalse(outcome.reusedLoadedEngine)
        // C: engineAcquisitionEvents carries ONLY the current MTP init failure.
        val mtpFailures = outcome.engineAcquisitionEvents.filter { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED }
        assertEquals("C: only one MTP failure event for this fresh engine", 1, mtpFailures.size)
        assertTrue(mtpFailures.single().attemptedMtpEnabled == true)
        assertTrue(mtpFailures.single().attemptedTextBackend == RuntimeBackend.GPU)
    }
}
