package com.projectnuke.fusion.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4: when MTP was requested but a non-MTP engine later succeeds, the
 * status must be FALLBACK_DISABLED (never FAILED). FAILED is reserved for the
 * no-usable-Engine scenario, which does not pass through resolveMtpRuntimeStatus.
 */
class LiteRtFallbackStatusTest {

    private fun resolve(selected: Boolean, flagApplied: Boolean, attempted: Boolean) =
        resolveMtpRuntimeStatus(
            mtpRequested = true,
            mtpSupported = true,
            selectedMtpEnabled = selected,
            mtpFlagAppliedForMtp = flagApplied,
            mtpCapabilityResult = null,
            mtpSkippedByMemory = false,
            mtpAttempted = attempted
        )

    @Test
    fun `enable-settle failure then GPU plain success returns FALLBACK_DISABLED`() {
        // Flag failed to enable for the MTP candidate; nothing was attempted
        // before the plain GPU candidate succeeded.
        val status = resolve(selected = false, flagApplied = false, attempted = false)
        assertEquals(MtpRuntimeStatus.FALLBACK_DISABLED, status)
        val reason = resolveMtpFallbackReason(true, true, false, false, null, false, false)
        assertEquals("MTP flag application failed", reason)
    }

    @Test
    fun `enable-settle failure then CPU plain success returns FALLBACK_DISABLED`() {
        val status = resolve(selected = false, flagApplied = false, attempted = false)
        assertEquals(MtpRuntimeStatus.FALLBACK_DISABLED, status)
    }

    @Test
    fun `MTP init failure then GPU plain success returns FALLBACK_DISABLED`() {
        // MTP candidate was attempted, failed init, plain GPU succeeded.
        val status = resolve(selected = false, flagApplied = true, attempted = true)
        assertEquals(MtpRuntimeStatus.FALLBACK_DISABLED, status)
        val reason = resolveMtpFallbackReason(true, true, false, true, true, false, true)
        // Flag applied (set true), but init failed and selection fell back.
        assertEquals("MTP initialization failed, fell back to non-MTP", reason)
    }

    @Test
    fun `MTP and GPU plain failure then CPU success returns FALLBACK_DISABLED`() {
        // Combines two fallback events: an MTP_ENGINE_INIT_FAILED event plus a
        // GPU_TEXT_ENGINE_FAILED_CPU_SELECTED event, ending on a successful CPU
        // plain engine. Both events are retained in the snapshot's fallback list.
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(
                EngineCandidate("GPU", true),
                EngineCandidate("GPU", false),
                EngineCandidate("CPU", false)
            ),
            enableVisionBackend = false,
            configureFlag = { true },
tryCreate = { backendName, mtpEnabled, _ ->
                 if (backendName == "CPU" && !mtpEnabled) {
                     EngineCandidateAttempt.Success("cpu-plain")
                 } else {
                     EngineCandidateAttempt.InitializationFailed(IllegalStateException("$backendName-$mtpEnabled init failed"))
                 }
             }
        )
        assertNotNull(outcome.selection)
        assertEquals("cpu-plain", outcome.selection!!.engine)
        val reasons = outcome.fallbackEvents.map { it.reason }.toSet()
        assertTrue("MTP_ENGINE_INIT_FAILED should coexist", FallbackReason.MTP_ENGINE_INIT_FAILED in reasons)
        assertTrue(
            "GPU_TEXT_ENGINE_FAILED_CPU_SELECTED should coexist",
            FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED in reasons
        )
        // MTP requested, plain CPU succeeded → FALLBACK_DISABLED.
        val status = resolveMtpRuntimeStatus(
            mtpRequested = true, mtpSupported = true,
            selectedMtpEnabled = false, mtpFlagAppliedForMtp = true,
            mtpCapabilityResult = null, mtpSkippedByMemory = false, mtpAttempted = true
        )
        assertEquals(MtpRuntimeStatus.FALLBACK_DISABLED, status)
    }

    @Test
    fun `all candidates failing returns selection null and FAILED is set by the engine`() {
        // The ladder only surfaces null-selection + non-null failure for the
        // "all candidates exhaust" case; the engine's getOrCreateEngine
        // explicitly sets lastMtpStatus = FAILED before throwing in that path,
        // so resolveMtpRuntimeStatus is never invoked with all-failed inputs.
        val failure = IllegalStateException("all failed")
        val outcome = selectFirstWorkingEngine<String>(
            ladder = listOf(EngineCandidate("GPU", false), EngineCandidate("CPU", false)),
            enableVisionBackend = false,
            configureFlag = { true },
            tryCreate = { _, _, _ -> EngineCandidateAttempt.InitializationFailed(failure) }
        )
        assertNull(outcome.selection)
        assertEquals(failure, outcome.failure)
        // The resolver itself never transitions to FAILED for an engine-loadable
        // request: every reachable combination covers a successful engine init.
        // (FAILED remains only set by the engine pre-throw path.)
    }
}
