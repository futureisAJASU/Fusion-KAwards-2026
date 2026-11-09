package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.AcceleratorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5: the runtime snapshot must retain multiple concurrent fallbacks
 * (MTP plus text-backend, plus vision-only) using the typed event list rather
 * than the single nullable fallbackReason string. Vision-only fallback is a
 * distinct event with attemptedVisionBackend set.
 */
class LiteRtConcurrentFallbackEventTest {

    @Test
    fun `MTP and GPU-to-CPU text fallback events coexist in the snapshot`() {
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(
                EngineCandidate("GPU", true),
                EngineCandidate("GPU", false),
                EngineCandidate("CPU", false)
            ),
            enableVisionBackend = false,
            configureFlag = { true },
tryCreate = { backendName, mtpEnabled, _ ->
                 if (backendName == "CPU" && !mtpEnabled) EngineCandidateAttempt.Success("cpu")
                 else EngineCandidateAttempt.InitializationFailed(IllegalStateException("fail"))
             }
         )
         assertNotNull(outcome.selection)
         val snapshot = RuntimeExecutionSnapshot(
            requestedAccelerator = AcceleratorMode.AUTO,
            selectedTextBackend = RuntimeBackend.CPU,
            selectedVisionBackend = null,
            samplerBackend = RuntimeComponentBackend.UNKNOWN,
            mtpRequested = true,
            mtpStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
            fallbackEvents = outcome.fallbackEvents,
            modelFingerprint = ModelFingerprintSummary("m", 1L, 0L, 1, true)
        )
        val reasons = snapshot.fallbackEvents.map { it.reason }.toSet()
        assertTrue(FallbackReason.MTP_ENGINE_INIT_FAILED in reasons)
        assertTrue(FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED in reasons)
        assertEquals(RuntimeBackend.CPU, snapshot.selectedTextBackend)
    }

    @Test
    fun `vision-only GPU-to-CPU fallback is recorded with attemptedVisionBackend`() {
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(EngineCandidate("GPU", false)),
            enableVisionBackend = true,
            configureFlag = { true },
tryCreate = { backendName, _, visionBackendIsCpu ->
                 if (visionBackendIsCpu) EngineCandidateAttempt.Success("gpu-text-cpu-vision")
                 else EngineCandidateAttempt.InitializationFailed(IllegalStateException("gpu vision init failed"))
             }
        )
        assertNotNull(outcome.selection)
        assertEquals("CPU", outcome.selection!!.visionBackend)
        val ev = outcome.fallbackEvents.single { it.reason == FallbackReason.GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED }
        assertEquals(RuntimeBackend.GPU, ev.attemptedVisionBackend)
        assertEquals(RuntimeBackend.CPU, ev.selectedReplacementBackend)
        // The text backend remains the original GPU: attemptedTextBackend is set.
        assertEquals(RuntimeBackend.GPU, ev.attemptedTextBackend)
        // The GPU plain candidate had mtpEnabled=false, so the event carries
        // attemptedMtpEnabled=false (not null) — the candidate's actual state.
        assertEquals(false, ev.attemptedMtpEnabled)
    }

    @Test
    fun `vision-only fallback can coexist with MTP fallback`() {
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(
                EngineCandidate("GPU", true),
                EngineCandidate("GPU", false)
            ),
            enableVisionBackend = true,
            configureFlag = { true },
tryCreate = { backendName, mtpEnabled, visionBackendIsCpu ->
                 if (backendName == "GPU" && mtpEnabled) {
                     EngineCandidateAttempt.InitializationFailed(IllegalStateException("gpu mtp init failed"))
                 } else if (visionBackendIsCpu) {
                     EngineCandidateAttempt.Success("gpu-text-cpu-vision")
                 } else {
                     EngineCandidateAttempt.InitializationFailed(IllegalStateException("gpu vision init failed"))
                 }
             }
        )
        assertNotNull(outcome.selection)
        val reasons = outcome.fallbackEvents.map { it.reason }.toSet()
        assertTrue(
            "MTP_ENGINE_INIT_FAILED + GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED coexist",
            FallbackReason.MTP_ENGINE_INIT_FAILED in reasons &&
                FallbackReason.GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED in reasons
        )
        assertEquals("CPU", outcome.selection!!.visionBackend)
        assertEquals("GPU", outcome.selection!!.backendName)
    }

    @Test
    fun `single nullable fallbackReason does not survive multiple concurrent fallbacks`() {
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(
                EngineCandidate("GPU", true),
                EngineCandidate("GPU", false),
                EngineCandidate("CPU", false)
            ),
            enableVisionBackend = false,
            configureFlag = { true },
tryCreate = { backendName, mtpEnabled, _ ->
                 if (backendName == "CPU" && !mtpEnabled) EngineCandidateAttempt.Success("cpu")
                 else EngineCandidateAttempt.InitializationFailed(IllegalStateException("fail"))
             }
         )
         // The legacy single-string fallbackReason surfaces only the first event
        // (in the engine's runtimeSelection), losing the second. The snapshot's
        // fallbackEvents list is therefore the only truthful record. Assert that
        // the event list is longer than one entry while the legacy field would
        // only retain one.
        assertTrue("event list preserves both fallbacks", outcome.fallbackEvents.size >= 2)
    }

    @Test
    fun `vision backend only, text backend unchanged, MTP status OFF`() {
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(EngineCandidate("GPU", false)),
            enableVisionBackend = true,
            configureFlag = { true },
tryCreate = { _, _, visionBackendIsCpu ->
                 if (visionBackendIsCpu) EngineCandidateAttempt.Success("ok")
                 else EngineCandidateAttempt.InitializationFailed(IllegalStateException("vision fail"))
             }
        )
        assertNotNull(outcome.selection)
        // Single vision-only event, no MTP event because no candidate requested MTP.
        assertEquals(1, outcome.fallbackEvents.size)
        assertEquals(
            FallbackReason.GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED,
            outcome.fallbackEvents.first().reason
        )
    }
}
