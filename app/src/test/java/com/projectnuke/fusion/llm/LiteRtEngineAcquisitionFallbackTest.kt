package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.RequestedEngineProfile
import org.junit.Assert.*
import org.junit.Test

/**
 * Dummy class for testing - used as the generic type for selectFirstWorkingEngine.
 * Must not extend Engine (which is final).
 */
internal class DummyEngine

class LiteRtEngineAcquisitionFallbackTest {

    private val dummyEngine = DummyEngine()

    @Test
    fun case1_capabilityRejectedMTP_recordsMTP_UNSUPPORTED_andFallsThrough() {
        val ladder = listOf(
            EngineCandidate("GPU", mtpEnabled = true),
            EngineCandidate("GPU", mtpEnabled = false)
        )
        val configureCalls = mutableListOf<Boolean>()
        val outcome = selectFirstWorkingEngine<DummyEngine>(
            ladder,
            enableVisionBackend = false,
            configureFlag = { flag: Boolean ->
                configureCalls.add(flag)
                true
            },
            tryCreate = { _: String, mtpEnabled: Boolean, _: Boolean ->
                if (mtpEnabled) EngineCandidateAttempt.CapabilityRejected("GPU", true)
                else EngineCandidateAttempt.Success(dummyEngine)
            } as (String, Boolean, Boolean) -> EngineCandidateAttempt<DummyEngine>
        )

        assertNotNull("selection should not be null", outcome.selection)
        assertEquals(false, outcome.selection!!.selectedMtpEnabled)
        assertEquals(1, outcome.fallbackEvents.size)
        val event = outcome.fallbackEvents[0]
        assertEquals(RuntimeBackend.GPU, event.attemptedTextBackend)
        assertEquals(true, event.attemptedMtpEnabled)
        assertEquals(FallbackReason.MTP_UNSUPPORTED, event.reason)
        assertNull(event.selectedReplacementBackend)
        assertTrue(configureCalls.contains(true))
        assertTrue(configureCalls.contains(false))
    }

    @Test
    fun case5_MTPFlagAgreeSettlementFails_recordsSPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED_thenPlainSucceeds() {
        val ladder = listOf(
            EngineCandidate("GPU", mtpEnabled = true),
            EngineCandidate("GPU", mtpEnabled = false)
        )
        var mtpConfigureAttempted = false

        val outcome = selectFirstWorkingEngine<DummyEngine>(
            ladder = ladder,
            enableVisionBackend = false,
            configureFlag = { mtp: Boolean ->
                if (mtp) {
                    mtpConfigureAttempted = true
                    false
                } else {
                    true
                }
            },
            tryCreate = { _: String, _: Boolean, _: Boolean ->
                EngineCandidateAttempt.Success(dummyEngine)
            } as (String, Boolean, Boolean) -> EngineCandidateAttempt<DummyEngine>
        )

        assertNotNull(outcome.selection)
        assertEquals(false, outcome.selection!!.selectedMtpEnabled)
        assertTrue(mtpConfigureAttempted)
        assertEquals(1, outcome.fallbackEvents.size)
        val event = outcome.fallbackEvents[0]
        assertEquals(RuntimeBackend.GPU, event.attemptedTextBackend)
        assertEquals(true, event.attemptedMtpEnabled)
        assertEquals(FallbackReason.SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED, event.reason)
    }

    @Test
    fun case6_disableFlagFails_recordsSPECULATIVE_DISABLE_FLAG_SETTLEMENT_FAILED_skips() {
        val ladder = listOf(
            EngineCandidate("GPU", mtpEnabled = false),
            EngineCandidate("CPU", mtpEnabled = false)
        )
        var gpuPlainDisableFailed = false

        val configureFlag: (Boolean) -> Boolean = { flag: Boolean ->
            if ((flag == false) && !gpuPlainDisableFailed) {
                gpuPlainDisableFailed = true
                false
            } else {
                true
            }
        }

        val outcome = selectFirstWorkingEngine<DummyEngine>(
            ladder = ladder,
            enableVisionBackend = false,
            configureFlag = configureFlag,
            tryCreate = { _: String, _: Boolean, _: Boolean ->
                EngineCandidateAttempt.Success(dummyEngine)
            } as (String, Boolean, Boolean) -> EngineCandidateAttempt<DummyEngine>
        )

        assertNotNull(outcome.selection)
        assertEquals("CPU", outcome.selection!!.backendName)
        assertEquals(1, outcome.fallbackEvents.size)
        val event = outcome.fallbackEvents[0]
        assertEquals(RuntimeBackend.GPU, event.attemptedTextBackend)
        assertEquals(false, event.attemptedMtpEnabled)
        assertEquals(FallbackReason.SPECULATIVE_DISABLE_FLAG_SETTLEMENT_FAILED, event.reason)
    }

    @Test
    fun case7_allCandidatesInitFail_selectionIsNull_3FallbackEvents() {
        val ladder = listOf(
            EngineCandidate("GPU", mtpEnabled = true),
            EngineCandidate("GPU", mtpEnabled = false),
            EngineCandidate("CPU", mtpEnabled = false)
        )

        val outcome = selectFirstWorkingEngine<DummyEngine>(
            ladder = ladder,
            enableVisionBackend = false,
            configureFlag = { true },
            tryCreate = { _: String, mtpEnabled: Boolean, _: Boolean ->
                EngineCandidateAttempt.InitializationFailed(
                    RuntimeException("init failure mtp=$mtpEnabled")
                )
            } as (String, Boolean, Boolean) -> EngineCandidateAttempt<DummyEngine>
        )

        assertNull(outcome.selection)
        assertNotNull(outcome.failure)
        assertEquals(3, outcome.fallbackEvents.size)

        val e0 = outcome.fallbackEvents[0]
        assertEquals(RuntimeBackend.GPU, e0.attemptedTextBackend)
        assertEquals(true, e0.attemptedMtpEnabled)
        assertEquals(FallbackReason.MTP_ENGINE_INIT_FAILED, e0.reason)

        val e1 = outcome.fallbackEvents[1]
        assertEquals(RuntimeBackend.GPU, e1.attemptedTextBackend)
        assertEquals(false, e1.attemptedMtpEnabled)
        assertEquals(FallbackReason.BACKEND_ENGINE_INIT_FAILED, e1.reason)

        val e2 = outcome.fallbackEvents[2]
        assertEquals(RuntimeBackend.CPU, e2.attemptedTextBackend)
        assertEquals(false, e2.attemptedMtpEnabled)
        assertEquals(FallbackReason.BACKEND_ENGINE_INIT_FAILED, e2.reason)
    }

    @Test
    fun case11_emptyLadderYieldsNilSelection_0Events() {
        val outcome = selectFirstWorkingEngine<DummyEngine>(
            ladder = emptyList(),
            enableVisionBackend = false,
            configureFlag = { true },
            tryCreate = { _: String, _: Boolean, _: Boolean ->
                EngineCandidateAttempt.Success(dummyEngine)
            } as (String, Boolean, Boolean) -> EngineCandidateAttempt<DummyEngine>
        )

        assertNull(outcome.selection)
        assertNull(outcome.failure)
        assertEquals(0, outcome.fallbackEvents.size)
    }

    @Test
    fun case4_MTP_GPU_initFails_plainGPUSucceeds_MTP_ENGINE_INIT_FAILED() {
        val ladder = listOf(
            EngineCandidate("GPU", mtpEnabled = true),
            EngineCandidate("GPU", mtpEnabled = false)
        )

        val outcome = selectFirstWorkingEngine<DummyEngine>(
            ladder = ladder,
            enableVisionBackend = false,
            configureFlag = { true },
            tryCreate = { _: String, mtpEnabled: Boolean, _: Boolean ->
                if (mtpEnabled) EngineCandidateAttempt.InitializationFailed(RuntimeException("MTP init fail"))
                else EngineCandidateAttempt.Success(dummyEngine)
            } as (String, Boolean, Boolean) -> EngineCandidateAttempt<DummyEngine>
        )

        assertNotNull(outcome.selection)
        assertEquals(false, outcome.selection!!.selectedMtpEnabled)
        assertEquals("GPU", outcome.selection!!.backendName)
        assertEquals(1, outcome.fallbackEvents.size)
        val event = outcome.fallbackEvents[0]
        assertEquals(RuntimeBackend.GPU, event.attemptedTextBackend)
        assertEquals(true, event.attemptedMtpEnabled)
        assertEquals(FallbackReason.MTP_ENGINE_INIT_FAILED, event.reason)
    }

    @Test
    fun case8_GPU_plain_fails_then_CPU_succeeds_BACKEND_INIT_FAILED_and_GPU_TO_CPU_bridge() {
        val ladder = listOf(
            EngineCandidate("GPU", mtpEnabled = false),
            EngineCandidate("CPU", mtpEnabled = false)
        )

        val outcome = selectFirstWorkingEngine<DummyEngine>(
            ladder = ladder,
            enableVisionBackend = false,
            configureFlag = { true },
            tryCreate = { backendName: String, mtpEnabled: Boolean, _: Boolean ->
                when {
                    backendName == "GPU" && (mtpEnabled == false) ->
                        EngineCandidateAttempt.InitializationFailed(RuntimeException("GPU init fail"))
                    backendName == "CPU" && (mtpEnabled == false) ->
                        EngineCandidateAttempt.Success(dummyEngine)
                    else -> error("unexpected")
                }
            } as (String, Boolean, Boolean) -> EngineCandidateAttempt<DummyEngine>
        )

        assertNotNull(outcome.selection)
        assertEquals("CPU", outcome.selection!!.backendName)
        assertEquals(2, outcome.fallbackEvents.size)

        val initFailedEvent = outcome.fallbackEvents[0]
        assertEquals(RuntimeBackend.GPU, initFailedEvent.attemptedTextBackend)
        assertEquals(false, initFailedEvent.attemptedMtpEnabled)
        assertEquals(FallbackReason.BACKEND_ENGINE_INIT_FAILED, initFailedEvent.reason)

        val bridgeEvent = outcome.fallbackEvents[1]
        assertEquals(RuntimeBackend.GPU, bridgeEvent.attemptedTextBackend)
        assertEquals(false, bridgeEvent.attemptedMtpEnabled)
        assertEquals(RuntimeBackend.CPU, bridgeEvent.selectedReplacementBackend)
        assertEquals(FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED, bridgeEvent.reason)
    }

    @Test
    fun `GPU_MTP_plain_CPU_all_fail_only_backend_failures_no_bridge_events`() {
        val ladder = listOf(
            EngineCandidate("GPU", mtpEnabled = true),
            EngineCandidate("GPU", mtpEnabled = false),
            EngineCandidate("CPU", mtpEnabled = false)
        )

        val outcome = selectFirstWorkingEngine<DummyEngine>(
            ladder = ladder,
            enableVisionBackend = false,
            configureFlag = { true },
            tryCreate = { _: String, _: Boolean, _: Boolean ->
                EngineCandidateAttempt.InitializationFailed(RuntimeException("all fail"))
            } as (String, Boolean, Boolean) -> EngineCandidateAttempt<DummyEngine>
        )

        assertNull(outcome.selection)
        assertEquals(3, outcome.fallbackEvents.size)
        val allReasons = outcome.fallbackEvents.map { it.reason }
        assertTrue(
            allReasons.all {
                it == FallbackReason.MTP_ENGINE_INIT_FAILED ||
                    it == FallbackReason.BACKEND_ENGINE_INIT_FAILED
            }
        )
        outcome.fallbackEvents.forEach { event ->
            assertNull(event.selectedReplacementBackend)
        }
    }

    @Test
    fun case9_GPU_vision_backend_fails_then_CPU_vision_succeeds() {
        val ladder = listOf(
            EngineCandidate("GPU", mtpEnabled = false)
        )

        val outcome = selectFirstWorkingEngine<DummyEngine>(
            ladder = ladder,
            enableVisionBackend = true,
            configureFlag = { true },
            tryCreate = { _: String, _: Boolean, visionCpu: Boolean ->
                if (!visionCpu) EngineCandidateAttempt.InitializationFailed(RuntimeException("GPU vision fail"))
                else EngineCandidateAttempt.Success(dummyEngine)
            } as (String, Boolean, Boolean) -> EngineCandidateAttempt<DummyEngine>
        )

        assertNotNull(outcome.selection)
        assertEquals("GPU", outcome.selection!!.backendName)
        assertEquals("CPU", outcome.selection!!.visionBackend)
        assertEquals(1, outcome.fallbackEvents.size)
        val event = outcome.fallbackEvents[0]
        assertEquals(FallbackReason.GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED, event.reason)
        assertEquals(RuntimeBackend.GPU, event.attemptedTextBackend)
        assertEquals(RuntimeBackend.GPU, event.attemptedVisionBackend)
        assertEquals(RuntimeBackend.CPU, event.selectedReplacementBackend)
    }

    @Test
    fun case10_GPU_plain_fails_CPU_also_fails_no_bridge_event() {
        val ladder = listOf(
            EngineCandidate("GPU", mtpEnabled = false),
            EngineCandidate("CPU", mtpEnabled = false)
        )

        val outcome = selectFirstWorkingEngine<DummyEngine>(
            ladder = ladder,
            enableVisionBackend = false,
            configureFlag = { true },
            tryCreate = { backendName: String, mtpEnabled: Boolean, _: Boolean ->
                EngineCandidateAttempt.InitializationFailed(RuntimeException("$backendName init fail"))
            } as (String, Boolean, Boolean) -> EngineCandidateAttempt<DummyEngine>
        )

        assertNull(outcome.selection)
        assertEquals(2, outcome.fallbackEvents.size)
        val allReasons = outcome.fallbackEvents.map { it.reason }
        assertTrue(allReasons.all { it == FallbackReason.BACKEND_ENGINE_INIT_FAILED })
        outcome.fallbackEvents.forEach { assertNull(it.selectedReplacementBackend) }
    }

    @Test
    fun case12_exact_match_reuse_cached_engine_without_ladder() {
        val ladder = listOf(
            EngineCandidate("GPU", mtpEnabled = true),
            EngineCandidate("GPU", mtpEnabled = false)
        )

        val outcome = selectFirstWorkingEngine<DummyEngine>(
            ladder = ladder,
            enableVisionBackend = false,
            configureFlag = { true },
            tryCreate = { _: String, _: Boolean, _: Boolean ->
                EngineCandidateAttempt.Success(dummyEngine)
            } as (String, Boolean, Boolean) -> EngineCandidateAttempt<DummyEngine>
        )

        assertNotNull(outcome.selection)
        assertEquals(true, outcome.selection!!.selectedMtpEnabled)
    }
}