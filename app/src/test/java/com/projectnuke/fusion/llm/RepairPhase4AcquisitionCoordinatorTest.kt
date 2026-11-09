package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class RepairPhase4AcquisitionCoordinatorTest {

    private data class FakeEngineHandle(val backend: String)

    private class RecordingStorage : MtpFailureMemoryStorage {
        val saves = mutableListOf<Map<String, String>>()
        private var stored: Map<String, String> = emptyMap()
        override fun load(): Map<String, String> = stored
        override fun save(entries: Map<String, String>): Boolean {
            stored = entries
            saves += entries
            return true
        }

        override fun clear(): Boolean {
            stored = emptyMap()
            return true
        }
    }

    private data class RecordedFailure(
        val modelPath: String,
        val backendName: String,
        val mtpEnabled: Boolean,
        val fallbackReason: String
    )

    private class FakeMtpFailureMemory : MtpFailureMemoryPort {
        var shouldSkipMtpResult: FallbackReason? = null
        var shouldSkipBackendResult: FallbackReason? = null

        /** When set, backend skips apply only to this backend name. */
        var skipBackendName: String? = null

        val recordedFailures = mutableListOf<RecordedFailure>()

        override fun shouldSkipMtp(
            modelPath: String,
            backendName: String,
            mtpEnabled: Boolean,
            validationVersion: Int,
            kvCacheCapacityTokens: Int,
            enableVisionBackend: Boolean,
            fileSize: Long,
            modifiedAt: Long,
            mtpSupported: Boolean
        ): String? = shouldSkipMtpResult?.name

        override fun shouldSkipBackend(
            modelPath: String,
            backendName: String,
            validationVersion: Int,
            kvCacheCapacityTokens: Int,
            enableVisionBackend: Boolean,
            fileSize: Long,
            modifiedAt: Long,
            mtpSupported: Boolean
        ): String? =
            if (skipBackendName == null || backendName == skipBackendName) shouldSkipBackendResult?.name else null

        override fun recordFailure(
            modelPath: String,
            backendName: String,
            mtpEnabled: Boolean,
            validationVersion: Int,
            kvCacheCapacityTokens: Int,
            enableVisionBackend: Boolean,
            fileSize: Long,
            modifiedAt: Long,
            mtpSupported: Boolean,
            fallbackReason: String
        ) {
            recordedFailures += RecordedFailure(modelPath, backendName, mtpEnabled, fallbackReason)
        }
    }

    /**
     * Deterministic fingerprint (mtpSupported = true) so the MTP ladder is
     * exercised without touching the filesystem.
     */
    private val fakeFingerprint = ModelFingerprint(
        canonicalPath = "test_model.bin",
        fileSize = 1024L,
        modifiedAt = 1000L,
        validationVersion = 1,
        mtpSupported = true
    )

    private fun <T> createCoordinator(
        memory: MtpFailureMemoryPort = FakeMtpFailureMemory(),
        probe: (String) -> Boolean? = { true },
        flag: (Boolean) -> Boolean = { true },
        factory: (String, Boolean, Boolean) -> EngineCandidateAttempt<T> = { backend, _, _ ->
            EngineCandidateAttempt.Success(FakeEngineHandle(backend) as T)
        },
        fingerprintResolver: (String) -> ModelFingerprint = { fakeFingerprint }
    ) = EngineAcquisitionCoordinator(
        mtpFailureMemory = memory,
        mtpCapabilityProbe = probe,
        configureFlag = flag,
        engineFactory = factory,
        clock = { 1000L },
        fingerprintResolver = fingerprintResolver
    )

    private val defaultProfile = RequestedEngineProfile(
        modelPath = "test_model.bin",
        accelerator = AcceleratorMode.AUTO,
        kvCacheCapacityTokens = 2048,
        mtpRequested = true,
        enableVisionBackend = false
    )

    private fun loadedState(
        engine: FakeEngineHandle,
        backend: String,
        mtpEnabled: Boolean,
        fingerprint: ModelFingerprint = fakeFingerprint,
        accelerator: AcceleratorMode = defaultProfile.accelerator,
        kvCacheCapacityTokens: Int = defaultProfile.kvCacheCapacityTokens,
        enableVisionBackend: Boolean = defaultProfile.enableVisionBackend
    ) = LoadedRuntimeState(
        engine = engine,
        key = EngineRuntimeKey(
            fingerprint = fingerprint,
            accelerator = accelerator,
            kvCacheCapacityTokens = kvCacheCapacityTokens,
            enableVisionBackend = enableVisionBackend,
            mtpEnabled = mtpEnabled,
            selectedBackend = backend
        ),
        mtpStatus = MtpRuntimeStatus.OFF,
        runtimeSelection = EngineSelectionRuntime("AUTO", backend, null, true, true, null),
        actualTextBackend = backend,
        actualVisionBackend = null,
        fallbackEvents = emptyList()
    )

    // ── Phase 1: deterministic cooldown / capability / failure semantics ────

    @Test
    fun `MTP init failure enters cooldown and is retried only after the window`() {
        val now = AtomicLong(0L)
        val memory = MtpFailureMemory(storage = RecordingStorage(), clock = { now.get() })
        val mtpAttempts = mutableListOf<Boolean>()
        val coordinator = createCoordinator(
            memory = memory,
            factory = { backend, mtp, _ ->
                if (mtp) {
                    mtpAttempts += true
                    EngineCandidateAttempt.InitializationFailed(RuntimeException("MTP init failed"))
                } else {
                    EngineCandidateAttempt.Success(FakeEngineHandle(backend))
                }
            }
        )

        val outcome1 = coordinator.acquire(defaultProfile, null)
        assertNotNull(outcome1.selection)
        assertEquals(false, outcome1.selection!!.selectedMtpEnabled)
        assertTrue(outcome1.fallbackEvents.any { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED })
        assertEquals(1, mtpAttempts.size)

        now.set(60_000L)
        val outcome2 = coordinator.acquire(defaultProfile, null)
        assertEquals(false, outcome2.selection!!.selectedMtpEnabled)
        assertTrue(outcome2.fallbackEvents.any { it.reason == FallbackReason.MTP_SKIPPED_RECENT_FAILURE })
        assertEquals(1, mtpAttempts.size)

        now.set(MtpFailureMemory.COOLDOWN_MS + 1)
        val outcome3 = coordinator.acquire(defaultProfile, null)
        assertEquals(false, outcome3.selection!!.selectedMtpEnabled)
        assertEquals(2, mtpAttempts.size)
    }

    @Test
    fun `runtime capability rejection probe returns false results in CapabilityRejected`() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val coordinator = createCoordinator(
            probe = { false },
            factory = { backend, mtp, _ ->
                calls += backend to mtp
                EngineCandidateAttempt.Success(FakeEngineHandle(backend))
            }
        )

        val outcome = coordinator.acquire(defaultProfile, null)

        assertEquals(false, outcome.selection!!.selectedMtpEnabled)
        assertFalse(calls.any { it.second })
        assertEquals(1, outcome.fallbackEvents.count { it.reason == FallbackReason.MTP_UNSUPPORTED })
        assertFalse(outcome.fallbackEvents.any { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED })
        assertEquals(false, outcome.mtpCapabilityResult)
    }

    @Test
    fun `runtime capability confirmed probe returns true results in MTP engine initialized`() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val coordinator = createCoordinator(
            probe = { true },
            factory = { backend, mtp, _ ->
                calls += backend to mtp
                EngineCandidateAttempt.Success(FakeEngineHandle("$backend-mtp=$mtp"))
            }
        )

        val outcome = coordinator.acquire(defaultProfile, null)

        assertEquals(true, outcome.selection!!.selectedMtpEnabled)
        assertTrue(calls.any { it.second }) // GPU+MTP was called
        assertEquals(true, outcome.mtpCapabilityResult)
    }

    @Test
    fun `runtime capability unknown probe returns null and successful MTP init`() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val coordinator = createCoordinator(
            probe = { null },
            factory = { backend, mtp, _ ->
                calls += backend to mtp
                EngineCandidateAttempt.Success(FakeEngineHandle("$backend-mtp=$mtp"))
            }
        )

        val outcome = coordinator.acquire(defaultProfile, null)

        assertEquals(true, outcome.selection!!.selectedMtpEnabled)
        assertTrue(calls.any { it.second }) // GPU+MTP was called and succeeded
        // Probe was null, capability result must be null (not inferred from init success)
        assertEquals(null, outcome.mtpCapabilityResult)
    }

    @Test
    fun `runtime capability unknown probe returns null and failed MTP init falls back`() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val coordinator = createCoordinator(
            probe = { null },
            factory = { backend, mtp, _ ->
                calls += backend to mtp
                if (mtp) {
                    EngineCandidateAttempt.InitializationFailed(RuntimeException("MTP init failed"))
                } else {
                    EngineCandidateAttempt.Success(FakeEngineHandle("$backend-mtp=$mtp"))
                }
            }
        )

        val outcome = coordinator.acquire(defaultProfile, null)

        assertEquals(false, outcome.selection!!.selectedMtpEnabled)
        assertTrue(calls.any { it.second }) // GPU+MTP was attempted
        assertTrue(calls.any { it.first == "GPU" && !it.second }) // plain GPU called
        // Probe was null, capability result must be null (not inferred from init failure)
        assertEquals(null, outcome.mtpCapabilityResult)
        assertTrue(outcome.fallbackEvents.any { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED })
    }

    @Test
    fun `MTP init failure is recorded with typed backend identity and exhausts once`() {
        val memory = FakeMtpFailureMemory()
        val coordinator = createCoordinator(
            memory = memory,
            factory = { _, _, _ -> EngineCandidateAttempt.InitializationFailed(RuntimeException("boom")) }
        )

        val outcome = coordinator.acquire(defaultProfile, null)

        assertNull(outcome.selection)
        assertNotNull(outcome.failure)
        assertTrue(memory.recordedFailures.any { it.mtpEnabled && it.backendName == "GPU" })
        assertEquals(
            listOf("GPU" to true, "GPU" to false, "CPU" to false),
            memory.recordedFailures.map { it.backendName to it.mtpEnabled }
        )
        assertEquals(1, outcome.fallbackEvents.count { it.reason == FallbackReason.ALL_CANDIDATES_EXHAUSTED })
    }

    @Test
    fun `cooldown skip does not refresh the recorded failure time`() {
        val now = AtomicLong(0L)
        val storage = RecordingStorage()
        val memory = MtpFailureMemory(storage = storage, clock = { now.get() })
        val coordinator = createCoordinator(
            memory = memory,
            factory = { backend, mtp, _ ->
                if (mtp) {
                    EngineCandidateAttempt.InitializationFailed(RuntimeException("MTP init failed"))
                } else {
                    EngineCandidateAttempt.Success(FakeEngineHandle(backend))
                }
            }
        )

        coordinator.acquire(defaultProfile, null)
        assertEquals(1, storage.saves.size)
        val firstFailedAt = storage.saves.last().values.first().substringBefore('|')

        now.set(30_000L)
        val outcome = coordinator.acquire(defaultProfile, null)
        assertTrue(outcome.fallbackEvents.any { it.reason == FallbackReason.MTP_SKIPPED_RECENT_FAILURE })
        assertEquals(1, storage.saves.size)
        assertEquals(firstFailedAt, storage.saves.last().values.first().substringBefore('|'))
    }

    // ── Existing acquisition semantics ──────────────────────────────────────

    @Test
    fun `GPU plain failure falls back to CPU success`() {
        var callCount = 0
        val coordinator = createCoordinator(
            factory = { backend, _, _ ->
                callCount++
                if (backend == "GPU") EngineCandidateAttempt.InitializationFailed(RuntimeException("GPU Fail"))
                else EngineCandidateAttempt.Success(FakeEngineHandle(backend))
            }
        )

        val profile = defaultProfile.copy(mtpRequested = false)
        val outcome = coordinator.acquire(profile, null)

        assertNotNull(outcome.selection)
        assertEquals("CPU", outcome.selection!!.backendName)
        assertTrue(outcome.fallbackEvents.any { it.reason == FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED })
    }

    @Test
    fun `GPU+MTP attempted and fails, plain GPU skipped by failure memory, CPU plain succeeds`() {
        val memory = FakeMtpFailureMemory().apply {
            shouldSkipBackendResult = FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE
            skipBackendName = "GPU"
        }

        val calls = mutableListOf<Pair<String, Boolean>>()
        val coordinator = createCoordinator(
            memory = memory,
            factory = { backend, mtp, _ ->
                calls += backend to mtp
                if (mtp) {
                    EngineCandidateAttempt.InitializationFailed(RuntimeException("MTP init failed"))
                } else {
                    EngineCandidateAttempt.Success(FakeEngineHandle("$backend-mtp=$mtp"))
                }
            }
        )

        val outcome = coordinator.acquire(defaultProfile, null)

        assertNotNull(outcome.selection)
        assertEquals("CPU", outcome.selection!!.backendName)
        assertFalse(outcome.selection!!.selectedMtpEnabled)

        // MTP factory call = 1 (GPU+MTP attempted and failed)
        // Plain GPU factory call = 0 (skipped by failure memory)
        // CPU factory call = 1 (succeeded)
        assertEquals(listOf("GPU" to true, "CPU" to false), calls)

        assertTrue(outcome.fallbackEvents.any { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED })
        assertTrue(outcome.fallbackEvents.any { it.reason == FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE && it.attemptedTextBackend == RuntimeBackend.GPU })
        assertFalse(outcome.fallbackEvents.any { it.reason == FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED })
    }

    @Test
    fun `all candidates filtered by cooldown reuses compatible loaded engine`() {
        val memory = FakeMtpFailureMemory().apply {
            shouldSkipMtpResult = FallbackReason.MTP_SKIPPED_RECENT_FAILURE
            shouldSkipBackendResult = FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE
        }

        val engine = FakeEngineHandle("GPU")
        val state = loadedState(engine, backend = "GPU", mtpEnabled = false)

        var factoryCalls = 0
        val coordinator = createCoordinator(
            memory = memory,
            factory = { backend, _, _ ->
                factoryCalls++
                EngineCandidateAttempt.Success(FakeEngineHandle(backend))
            }
        )
        val outcome = coordinator.acquire(defaultProfile, state)

        assertNotNull(outcome.selection)
        assertEquals(engine, outcome.selection!!.engine)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `incompatible loaded engine is rejected when all candidates skipped`() {
        val memory = FakeMtpFailureMemory().apply {
            shouldSkipMtpResult = FallbackReason.MTP_SKIPPED_RECENT_FAILURE
            shouldSkipBackendResult = FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE
        }

        val state = loadedState(
            FakeEngineHandle("GPU"),
            backend = "GPU",
            mtpEnabled = false,
            fingerprint = fakeFingerprint.copy(canonicalPath = "different_model.bin")
        )

        val coordinator = createCoordinator<FakeEngineHandle>(memory = memory)
        val outcome = coordinator.acquire(defaultProfile, state)

        assertNull(outcome.selection)
        assertNotNull(outcome.failure)
        val failure = outcome.failure as? EngineSelectionFailedException
        assertNotNull(failure)
        assertTrue(failure!!.attemptSnapshot.fallbackEvents.any { it.reason == FallbackReason.ALL_CANDIDATES_SKIPPED_RECENT_FAILURE })
    }

    @Test
    fun `request local event isolation`() {
        val memory = FakeMtpFailureMemory().apply {
            shouldSkipBackendResult = FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE
            skipBackendName = "GPU"
        }

        val state = loadedState(FakeEngineHandle("CPU"), backend = "CPU", mtpEnabled = false)
        val coordinator = createCoordinator<FakeEngineHandle>(memory = memory, probe = { false })

        val outcomeA = coordinator.acquire(defaultProfile.copy(accelerator = AcceleratorMode.AUTO), state)
        assertTrue(outcomeA.fallbackEvents.any { it.reason == FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE })

        val outcomeB = coordinator.acquire(defaultProfile.copy(accelerator = AcceleratorMode.CPU), state)
        assertFalse(outcomeB.fallbackEvents.any { it.reason == FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE })
    }

    @Test
    fun `output only change (maxOutputToken) does not recreate engine`() {
        var factoryCalls = 0
        val coordinator = createCoordinator(
            factory = { backend, _, _ ->
                factoryCalls++
                EngineCandidateAttempt.Success(FakeEngineHandle(backend))
            }
        )

        // Request A: generation output = 2048, KV capacity = 8192
        val profileA = defaultProfile.copy(kvCacheCapacityTokens = 8192)
        val outcomeA = coordinator.acquire(profileA, null)
        val engineA = outcomeA.selection!!.engine
        val backendA = outcomeA.selection!!.backendName
        val mtpA = outcomeA.selection!!.selectedMtpEnabled

        val state = loadedState(
            engineA,
            backend = backendA,
            mtpEnabled = mtpA,
            kvCacheCapacityTokens = profileA.kvCacheCapacityTokens,
            enableVisionBackend = profileA.enableVisionBackend
        )

        // Request B: generation output = 4096, KV capacity = 8192 (same KV, different output)
        // Note: maxOutputToken is in ConversationOptions, not RequestedEngineProfile
        // The profile should be identical
        val profileB = profileA.copy()
        val outcomeB = coordinator.acquire(profileB, state)

        assertEquals(engineA, outcomeB.selection!!.engine)
        assertEquals(backendA, outcomeB.selection!!.backendName)
        assertEquals(mtpA, outcomeB.selection!!.selectedMtpEnabled)
        assertEquals(1, factoryCalls)
    }

    @Test
    fun `KV capacity change alters engine identity`() {
        var factoryCalls = 0
        val coordinator = createCoordinator(
            factory = { backend, _, _ ->
                factoryCalls++
                EngineCandidateAttempt.Success(FakeEngineHandle("$backend-$factoryCalls"))
            }
        )

        val profile1 = defaultProfile.copy(kvCacheCapacityTokens = 8192)
        val outcome1 = coordinator.acquire(profile1, null)
        val engine1 = outcome1.selection!!.engine

        val state = loadedState(
            engine1,
            backend = outcome1.selection!!.backendName,
            mtpEnabled = outcome1.selection!!.selectedMtpEnabled,
            kvCacheCapacityTokens = profile1.kvCacheCapacityTokens,
            enableVisionBackend = profile1.enableVisionBackend
        )

        val profile2 = profile1.copy(kvCacheCapacityTokens = 4096)
        val outcome2 = coordinator.acquire(profile2, state)

        assertNotEquals(engine1, outcome2.selection!!.engine)
        assertEquals(2, factoryCalls)
    }

    // ── Phase 2: candidate-aware fallback reuse ───────────────────────────────

    @Test
    fun `runtime capability false with existing plain GPU reuses loaded engine`() {
        // First acquisition: GPU+MTP rejected by capability probe, plain GPU created
        var factoryCalls = 0
        val calls = mutableListOf<Pair<String, Boolean>>()
        val coordinator = createCoordinator(
            probe = { false },
            factory = { backend, mtp, _ ->
                calls += backend to mtp
                factoryCalls++
                EngineCandidateAttempt.Success(FakeEngineHandle("$backend-mtp=$mtp"))
            }
        )

        val outcome1 = coordinator.acquire(defaultProfile, null)
        assertEquals(false, outcome1.selection!!.selectedMtpEnabled)
        assertEquals("GPU", outcome1.selection!!.backendName)
        assertEquals(1, factoryCalls)
        assertEquals(listOf("GPU" to false), calls) // Only plain GPU factory called

        // Second identical acquisition: GPU+MTP rejected again, plain GPU reused
        val state = loadedState(
            outcome1.selection!!.engine,
            backend = outcome1.selection!!.backendName,
            mtpEnabled = outcome1.selection!!.selectedMtpEnabled
        )
        val outcome2 = coordinator.acquire(defaultProfile, state)

        assertEquals(false, outcome2.selection!!.selectedMtpEnabled)
        assertEquals("GPU", outcome2.selection!!.backendName)
        assertEquals(outcome1.selection!!.engine, outcome2.selection!!.engine)
        assertEquals(1, factoryCalls) // Factory NOT called again for plain GPU
        assertEquals(listOf("GPU" to false), calls)
    }

    @Test
    fun `higher priority flag settlement failure with existing lower candidate reuses loaded engine`() {
        // Higher candidate (GPU+MTP) fails flag settlement, lower candidate (plain GPU) exists and is reused
        var factoryCalls = 0
        val calls = mutableListOf<Pair<String, Boolean>>()
        val coordinator = createCoordinator(
            flag = { mtpEnabled -> !mtpEnabled }, // Fail only for MTP-enabled
            factory = { backend, mtp, _ ->
                calls += backend to mtp
                factoryCalls++
                EngineCandidateAttempt.Success(FakeEngineHandle("$backend-mtp=$mtp"))
            }
        )

        val outcome1 = coordinator.acquire(defaultProfile, null)
        assertEquals(false, outcome1.selection!!.selectedMtpEnabled)
        assertEquals("GPU", outcome1.selection!!.backendName)
        assertEquals(1, factoryCalls)
        assertEquals(listOf("GPU" to false), calls) // Only plain GPU factory called
        assertTrue(outcome1.fallbackEvents.any { it.reason == FallbackReason.SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED })

        // Second acquisition: same profile, GPU+MTP fails flag settlement again, plain GPU reused
        val state = loadedState(
            outcome1.selection!!.engine,
            backend = outcome1.selection!!.backendName,
            mtpEnabled = outcome1.selection!!.selectedMtpEnabled
        )
        val outcome2 = coordinator.acquire(defaultProfile, state)

        assertEquals(false, outcome2.selection!!.selectedMtpEnabled)
        assertEquals("GPU", outcome2.selection!!.backendName)
        assertEquals(outcome1.selection!!.engine, outcome2.selection!!.engine)
        assertEquals(1, factoryCalls) // Factory NOT called again
        assertEquals(listOf("GPU" to false), calls)
        assertTrue(outcome2.fallbackEvents.any { it.reason == FallbackReason.SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED })
    }

    @Test
    fun `MTP cooldown with loaded plain candidate reuses loaded engine`() {
        val now = AtomicLong(0L)
        val memory = MtpFailureMemory(storage = RecordingStorage(), clock = { now.get() })
        var factoryCalls = 0
        val calls = mutableListOf<Pair<String, Boolean>>()
        val coordinator = createCoordinator(
            memory = memory,
            factory = { backend, mtp, _ ->
                calls += backend to mtp
                factoryCalls++
                if (mtp) {
                    EngineCandidateAttempt.InitializationFailed(RuntimeException("MTP init failed"))
                } else {
                    EngineCandidateAttempt.Success(FakeEngineHandle("$backend-mtp=$mtp"))
                }
            }
        )

        // First acquisition: GPU+MTP fails init, plain GPU created
        val outcome1 = coordinator.acquire(defaultProfile, null)
        assertEquals(false, outcome1.selection!!.selectedMtpEnabled)
        assertEquals("GPU", outcome1.selection!!.backendName)
        assertEquals(2, factoryCalls) // GPU+MTP attempted (failed) + plain GPU created
        assertEquals(listOf("GPU" to true, "GPU" to false), calls)
        assertTrue(outcome1.fallbackEvents.any { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED })

        // Second acquisition within cooldown: GPU+MTP skipped by cooldown, plain GPU reused
        now.set(30_000L) // Within cooldown
        val state = loadedState(
            outcome1.selection!!.engine,
            backend = outcome1.selection!!.backendName,
            mtpEnabled = outcome1.selection!!.selectedMtpEnabled
        )
        val outcome2 = coordinator.acquire(defaultProfile, state)

        assertEquals(false, outcome2.selection!!.selectedMtpEnabled)
        assertEquals("GPU", outcome2.selection!!.backendName)
        assertEquals(outcome1.selection!!.engine, outcome2.selection!!.engine)
        assertEquals(2, factoryCalls) // Factory NOT called again for plain GPU
        assertEquals(listOf("GPU" to true, "GPU" to false), calls)
        assertTrue(outcome2.fallbackEvents.any { it.reason == FallbackReason.MTP_SKIPPED_RECENT_FAILURE })
    }

    @Test
    fun `higher priority candidate becomes eligible again after cooldown expiry`() {
        val now = AtomicLong(0L)
        val memory = MtpFailureMemory(storage = RecordingStorage(), clock = { now.get() })
        var factoryCalls = 0
        val calls = mutableListOf<Pair<String, Boolean>>()
        var mtpShouldFail = true
        val coordinator = createCoordinator(
            memory = memory,
            factory = { backend, mtp, _ ->
                calls += backend to mtp
                factoryCalls++
                if (mtp && mtpShouldFail) {
                    EngineCandidateAttempt.InitializationFailed(RuntimeException("MTP init failed"))
                } else {
                    EngineCandidateAttempt.Success(FakeEngineHandle("$backend-mtp=$mtp"))
                }
            }
        )

        // First acquisition: GPU+MTP fails init, plain GPU created
        val outcome1 = coordinator.acquire(defaultProfile, null)
        assertEquals(false, outcome1.selection!!.selectedMtpEnabled) // Falls back to plain GPU
        assertEquals("GPU", outcome1.selection!!.backendName)
        assertEquals(2, factoryCalls) // GPU+MTP failed + plain GPU succeeded
        assertEquals(listOf("GPU" to true, "GPU" to false), calls)

        // Within cooldown: MTP skipped, plain GPU reused
        now.set(30_000L)
        val state = loadedState(
            outcome1.selection!!.engine,
            backend = outcome1.selection!!.backendName,
            mtpEnabled = outcome1.selection!!.selectedMtpEnabled
        )
        val outcome2 = coordinator.acquire(defaultProfile, state)
        assertEquals(false, outcome2.selection!!.selectedMtpEnabled)
        assertEquals("GPU", outcome2.selection!!.backendName)
        assertEquals(outcome1.selection!!.engine, outcome2.selection!!.engine)
        assertEquals(2, factoryCalls) // Factory NOT called again
        assertEquals(listOf("GPU" to true, "GPU" to false), calls)
        assertTrue(outcome2.fallbackEvents.any { it.reason == FallbackReason.MTP_SKIPPED_RECENT_FAILURE })

        // After cooldown expiry: GPU+MTP attempted again and succeeds
        mtpShouldFail = false
        now.set(MtpFailureMemory.COOLDOWN_MS + 1)
        val outcome3 = coordinator.acquire(defaultProfile, state)
        assertEquals(true, outcome3.selection!!.selectedMtpEnabled) // GPU+MTP attempted and succeeds
        assertEquals("GPU", outcome3.selection!!.backendName)
        assertEquals(3, factoryCalls) // New GPU+MTP engine created (MTP success)
        assertNotEquals(outcome2.selection!!.engine, outcome3.selection!!.engine)
        assertEquals(listOf("GPU" to true, "GPU" to false, "GPU" to true), calls)
    }

    // ── Phase 7: terminal exhaustion ────────────────────────────────────────────

    @Test
    fun `terminal exhaustion produces ALL_CANDIDATES_EXHAUSTED not ALL_CANDIDATES_SKIPPED_RECENT_FAILURE`() {
        // When all candidates fail through initialization (not cooldown), we should get EXHAUSTED
        val coordinator = createCoordinator(
            factory = { backend, mtp, _ ->
                EngineCandidateAttempt.InitializationFailed(RuntimeException("$backend init failed"))
            }
        )

        val outcome = coordinator.acquire(defaultProfile, null)

        assertNull(outcome.selection)
        assertNotNull(outcome.failure)
        assertTrue(outcome.fallbackEvents.any { it.reason == FallbackReason.ALL_CANDIDATES_EXHAUSTED })
        // Should NOT have ALL_CANDIDATES_SKIPPED_RECENT_FAILURE since these are fresh failures
        assertFalse(outcome.fallbackEvents.any { it.reason == FallbackReason.ALL_CANDIDATES_SKIPPED_RECENT_FAILURE })
    }

    @Test
    fun `flag settlement failure for all candidates produces terminal exhaustion`() {
        // When all candidates fail flag settlement (not factory), we should get EXHAUSTED
        val coordinator = createCoordinator(
            flag = { false }, // Fail flag settlement for all candidates
            factory = { backend, mtp, _ ->
                EngineCandidateAttempt.Success(FakeEngineHandle("$backend-mtp=$mtp"))
            }
        )

        val outcome = coordinator.acquire(defaultProfile, null)

        assertNull(outcome.selection)
        assertNotNull(outcome.failure)
        assertTrue(outcome.fallbackEvents.any { it.reason == FallbackReason.ALL_CANDIDATES_EXHAUSTED })
        // Should have SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED and SPECULATIVE_DISABLE_FLAG_SETTLEMENT_FAILED
        // Default profile AUTO has 3 candidates: GPU+MTP, GPU, CPU -> 3 flag settlement failures
        assertEquals(3, outcome.fallbackEvents.count { it.reason == FallbackReason.SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED || it.reason == FallbackReason.SPECULATIVE_DISABLE_FLAG_SETTLEMENT_FAILED })
    }
}
