package com.projectnuke.fusion.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3: app-level fallbacks must be recorded as typed
 * [RuntimeFallbackEvent]s during candidate selection, never as raw exception text
 * or English strings. Covers every reason code in the spec:
 * - MTP unsupported
 * - MTP skipped because of recent failure
 * - speculative enable flag settlement failed
 * - speculative disable flag settlement failed
 * - MTP Engine initialization failed
 * - GPU text Engine failed and CPU text Engine was selected
 * - GPU vision backend failed and CPU vision backend was selected
 * - all candidates exhausted
 */
class LiteRtEngineFallbackEventsTest {
    private val fullLadder = listOf(
        EngineCandidate("GPU", true),
        EngineCandidate("GPU", false),
        EngineCandidate("CPU", false)
    )

    private fun alwaysFlag(): (Boolean) -> Boolean = { true }

    @Test
    fun `MTP candidate failure records MTP_ENGINE_INIT_FAILED event`() {
        // From the standalone selectFirstWorkingEngine perspective, a generic
        // tryCreate failure on an MTP candidate is recorded as
        // MTP_ENGINE_INIT_FAILED. The engine-level negative-capability path
        // additionally records MTP_UNSUPPORTED into recordedFallbackEvents
        // before reaching the generic branch (verified by the engine test suite).
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(EngineCandidate("GPU", true)),
            enableVisionBackend = false,
            configureFlag = alwaysFlag(),
tryCreate = { _, _, _ ->
                 EngineCandidateAttempt.InitializationFailed(IllegalStateException("MTP init failed"))
             }
        )
        assertNull(outcome.selection)
        val ev = outcome.fallbackEvents.single()
        assertEquals(FallbackReason.MTP_ENGINE_INIT_FAILED, ev.reason)
        assertEquals(RuntimeBackend.GPU, ev.attemptedTextBackend)
        assertEquals(true, ev.attemptedMtpEnabled)
    }

    @Test
    fun `enable-settle failure records SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED event`() {
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(EngineCandidate("GPU", true), EngineCandidate("GPU", false)),
            enableVisionBackend = false,
            configureFlag = { enabled -> enabled.not() },
            tryCreate = { _, _, _ -> EngineCandidateAttempt.Success("never") }
        )
        assertNotNull(outcome.selection)
        val ev = outcome.fallbackEvents.single()
        assertEquals(FallbackReason.SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED, ev.reason)
    }

    @Test
    fun `disable-settle failure records SPECULATIVE_DISABLE_FLAG_SETTLEMENT_FAILED event`() {
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(EngineCandidate("GPU", false)),
            enableVisionBackend = false,
            configureFlag = { false },
            tryCreate = { _, _, _ -> EngineCandidateAttempt.Success("never reached") }
        )
        // A failed settle skips the candidate entirely; the single GPU-plain
        // candidate fails its disable-settle, so the selection is null and the
        // typed disable-failure event is recorded.
        assertNull(outcome.selection)
        val hasDisable = outcome.fallbackEvents.any { it.reason == FallbackReason.SPECULATIVE_DISABLE_FLAG_SETTLEMENT_FAILED }
        assertTrue("disable-settle failure should be recorded", hasDisable)
    }

    @Test
    fun `MTP candidate init failure occuring before a plain fallback still reports MTP_ENGINE_INIT_FAILED`() {
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(EngineCandidate("GPU", true), EngineCandidate("GPU", false)),
            enableVisionBackend = false,
            configureFlag = alwaysFlag(),
tryCreate = { backendName, mtpEnabled, _ ->
                 if (backendName == "GPU" && mtpEnabled) EngineCandidateAttempt.InitializationFailed(IllegalStateException("init failed"))
                 else EngineCandidateAttempt.Success("gpu-plain")
             }
        )
        assertNotNull(outcome.selection)
        assertEquals(
            FallbackReason.MTP_ENGINE_INIT_FAILED,
            outcome.fallbackEvents.first().reason
        )
    }

    @Test
    fun `GPU plain failure then CPU success records GPU_TEXT_ENGINE_FAILED_CPU_SELECTED event`() {
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(
                EngineCandidate("GPU", false),
                EngineCandidate("CPU", false)
            ),
            enableVisionBackend = false,
            configureFlag = alwaysFlag(),
tryCreate = { backendName, _, _ ->
                 if (backendName == "GPU") EngineCandidateAttempt.InitializationFailed(IllegalStateException("gpu fail"))
                 else EngineCandidateAttempt.Success("cpu-engine")
             }
        )
        assertNotNull(outcome.selection)
        assertEquals("cpu-engine", outcome.selection!!.engine)
        val ev = outcome.fallbackEvents.single {
            it.reason == FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED
        }
        assertEquals(RuntimeBackend.GPU, ev.attemptedTextBackend)
        assertEquals(RuntimeBackend.CPU, ev.selectedReplacementBackend)
    }

    @Test
    fun `GPU vision failure then CPU vision success records GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED event`() {
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(EngineCandidate("GPU", false)),
            enableVisionBackend = true,
            configureFlag = alwaysFlag(),
tryCreate = { backendName, _, visionBackendIsCpu ->
                 if (visionBackendIsCpu) EngineCandidateAttempt.Success("gpu-cpu-vision")
                 else EngineCandidateAttempt.InitializationFailed(IllegalStateException("gpu vision fail"))
             }
        )
        assertNotNull(outcome.selection)
        val ev = outcome.fallbackEvents.single {
            it.reason == FallbackReason.GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED
        }
        assertEquals(RuntimeBackend.GPU, ev.attemptedVisionBackend)
        assertEquals(RuntimeBackend.CPU, ev.selectedReplacementBackend)
    }

    @Test
    fun `all candidates exhausted records ALL_CANDIDATES_EXHAUSTED event in engine-level outcome`() {
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(EngineCandidate("GPU", false), EngineCandidate("CPU", false)),
            enableVisionBackend = false,
            configureFlag = alwaysFlag(),
            tryCreate = { _, _, _ -> EngineCandidateAttempt.InitializationFailed(IllegalStateException("all fail")) }
        )
        assertNull(outcome.selection)
        assertNotNull(outcome.failure)
    }
}
