package com.projectnuke.fusion.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repair Phase A regression tests for the production candidate-selection
 * coordinator (`selectFirstWorkingEngine`). These tests exercise the real
 * coordinator used by [LiteRtLlmEngine.getOrCreateEngine] — never through
 * a duplicate / mock-specific implementation — and assert behavioural
 * invariants (factory call counts, attempt sequences, fallback events,
 * state transitions), not just object construction.
 */
class RepairPhaseACandidateLadderTest {

    private val autoLadder = listOf(
        EngineCandidate("GPU", mtpEnabled = true),
        EngineCandidate("GPU", mtpEnabled = false),
        EngineCandidate("CPU", mtpEnabled = false)
    )

    private fun alwaysFlag(): (Boolean) -> Boolean = { true }

    // ── A1 — Capability false returns CapabilityRejected ─────────────────

    @Test
    fun `A1 capability false does not invoke engine factory for that candidate`() {
        val factoryCalls = mutableListOf<Pair<String, Boolean>>()
        val outcome = selectFirstWorkingEngine<String>(
            ladder = autoLadder,
            enableVisionBackend = false,
            configureFlag = alwaysFlag(),
            tryCreate = { backendName, mtpEnabled, _ ->
                // Capability rejection must be returned by the lambda; nothing
                // further may run for that candidate.
                if (mtpEnabled) {
                    EngineCandidateAttempt.CapabilityRejected(backendName, mtpEnabled)
                } else {
                    factoryCalls += (backendName to mtpEnabled)
                    EngineCandidateAttempt.Success("$backendName-nonMtp")
                }
            }
        )

        // The GPU+MTP CapabilityRejected skipped. The next candidate (GPU
        // plain) succeeds immediately, so the CPU candidate is never tried.
        // No factory call should appear with mtpEnabled=true.
        assertEquals(0, factoryCalls.count { it.second })
        assertEquals(listOf("GPU" to false), factoryCalls)

        // Exactly one MTP_UNSUPPORTED event was emitted by the rejected MTP
        // candidate. MTP_ENGINE_INIT_FAILED must NOT appear — capability
        // rejection is distinct from init failure.
        val reasons = outcome.fallbackEvents.map { it.reason }
        assertEquals(listOf<FallbackReason>(FallbackReason.MTP_UNSUPPORTED), reasons)
        assertFalse(outcome.fallbackEvents.any { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED })
        assertNotNull(outcome.selection)
        assertEquals("GPU-nonMtp", outcome.selection!!.engine)
        assertFalse(outcome.selection!!.selectedMtpEnabled)
    }

    @Test
    fun `A1 vision retry never fires for a capability-rejected candidate`() {
        var visionRetryCalls = 0
        val outcome = selectFirstWorkingEngine<String>(
            ladder = autoLadder,
            enableVisionBackend = true,
            configureFlag = alwaysFlag(),
            tryCreate = { backendName, mtpEnabled, visionBackendIsCpu ->
                if (mtpEnabled && !visionBackendIsCpu) {
                    EngineCandidateAttempt.CapabilityRejected(backendName, mtpEnabled)
                } else if (visionBackendIsCpu) {
                    visionRetryCalls += 1
                    EngineCandidateAttempt.Success("$backendName-cpu-vision")
                } else {
                    EngineCandidateAttempt.Success("$backendName-plain")
                }
            }
        )
        assertEquals(0, visionRetryCalls)
        assertNotNull(outcome.selection)
    }

    @Test
    fun `A1 MTP_ENGINE_INIT_FAILED count = 0 when capability rejects MTP candidate`() {
        val outcome = selectFirstWorkingEngine<String>(
            ladder = autoLadder,
            enableVisionBackend = false,
            configureFlag = alwaysFlag(),
            tryCreate = { backendName, mtpEnabled, _ ->
                if (mtpEnabled) {
                    EngineCandidateAttempt.CapabilityRejected(backendName, mtpEnabled)
                } else {
                    EngineCandidateAttempt.Success("$backendName-nonMtp")
                }
            }
        )
        assertEquals(
            0,
            outcome.fallbackEvents.count { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED }
        )
        assertEquals(
            1,
            outcome.fallbackEvents.count { it.reason == FallbackReason.MTP_UNSUPPORTED }
        )
    }

    // ── A3 — pendingGpuPlainFailure restriction ──────────────────────────

    @Test
    fun `A3 GPU+MTP failure does NOT generate GPU_TEXT_ENGINE_FAILED_CPU_SELECTED event`() {
        val attempts = mutableListOf<Pair<String, Boolean>>()
        val outcome = selectFirstWorkingEngine<String>(
            ladder = autoLadder,
            enableVisionBackend = false,
            configureFlag = alwaysFlag(),
            tryCreate = { backendName, mtpEnabled, _ ->
                attempts += (backendName to mtpEnabled)
                // GPU+MTP fails, GPU plain SKIPPED (e.g. filtered out), CPU success.
                when {
                    backendName == "GPU" && mtpEnabled -> {
                        EngineCandidateAttempt.InitializationFailed(RuntimeException("mtp init failed"))
                    }
                    else -> EngineCandidateAttempt.Success("$backendName-plain")
                }
            }
        )

        // GPU+MTP failed; no retry on it; success on GPU plain (auto-ladder order).
        assertEquals(listOf("GPU" to true, "GPU" to false), attempts)

        // No false plain-GPU fallback event: GPU+MTP failure does not poison the
        // pendingGpuPlainFailure flag.
        assertFalse(outcome.fallbackEvents.any { it.reason == FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED })
        assertTrue(outcome.fallbackEvents.any { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED })
    }

    @Test
    fun `A3 plain GPU failure then CPU success records GPU_TEXT_ENGINE_FAILED_CPU_SELECTED`() {
        val attempts = mutableListOf<Pair<String, Boolean>>()
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(
                EngineCandidate("GPU", mtpEnabled = false),
                EngineCandidate("CPU", mtpEnabled = false)
            ),
            enableVisionBackend = false,
            configureFlag = alwaysFlag(),
            tryCreate = { backendName, mtpEnabled, _ ->
                attempts += (backendName to mtpEnabled)
                when (backendName) {
                    "GPU" -> EngineCandidateAttempt.InitializationFailed(RuntimeException("gpu plain init failed"))
                    else -> EngineCandidateAttempt.Success("$backendName-plain")
                }
            }
        )
        assertEquals("CPU-plain", outcome.selection?.engine)
        assertTrue(outcome.fallbackEvents.any { it.reason == FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED })
        assertEquals(
            listOf("GPU" to false, "CPU" to false),
            attempts
        )
    }

    @Test
    fun `A3 MTP-only GPU failure with vision backend disabled does not set pendingGpuPlainFailure`() {
        val events: List<RuntimeFallbackEvent> = selectFirstWorkingEngine<String>(
            ladder = listOf(
                EngineCandidate("GPU", mtpEnabled = true),
                EngineCandidate("GPU", mtpEnabled = false),
                EngineCandidate("CPU", mtpEnabled = false)
            ),
            enableVisionBackend = false,
            configureFlag = alwaysFlag(),
            tryCreate = { backendName, mtpEnabled, _ ->
                if (backendName == "GPU" && mtpEnabled) {
                    EngineCandidateAttempt.InitializationFailed(RuntimeException("gpu+mtp init failed"))
                } else {
                    EngineCandidateAttempt.Success("$backendName-plain")
                }
            }
        ).fallbackEvents

        // GPU+MTP fails → MTP_ENGINE_INIT_FAILED recorded; GPU plain success
        // must NOT be reinterpreted as "failure-then-fallback", so no
        // GPU_TEXT_ENGINE_FAILED_CPU_SELECTED event.
        assertTrue(events.any { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED })
        assertFalse(events.any { it.reason == FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED })
    }
}
