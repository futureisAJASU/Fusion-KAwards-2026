package com.projectnuke.fusion.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLlmEngineMtpTest {
    private val mtpLadder = listOf(
        EngineCandidate("GPU", true),
        EngineCandidate("GPU", false),
        EngineCandidate("CPU", true),
        EngineCandidate("CPU", false)
    )

    private fun alwaysFlag(): (Boolean) -> Boolean = { true }

    @Test
    fun `selects first working candidate with MTP enabled`() {
        val selection = selectFirstWorkingEngine<String>(
            ladder = mtpLadder,
            enableVisionBackend = false,
            configureFlag = alwaysFlag(),
            tryCreate = { backendName, mtpEnabled, visionBackendIsCpu ->
                if (backendName == "GPU" && mtpEnabled) {
                    EngineCandidateAttempt.Success("gpu-mtp-engine")
                } else {
                    EngineCandidateAttempt.InitializationFailed(IllegalStateException("not reached"))
                }
            }
        )
        val result = selection.selection
        assertNotNull(result)
        assertEquals("gpu-mtp-engine", result!!.engine)
        assertTrue(result.selectedMtpEnabled)
        assertTrue(result.mtpFlagAppliedForMtp)
        assertNull(selection.failure)
    }

    @Test
    fun `MTP engine init failure falls back to non-MTP engine`() {
        val attempts = mutableListOf<String>()
        val selection = selectFirstWorkingEngine<String>(
            ladder = mtpLadder,
            enableVisionBackend = false,
            configureFlag = alwaysFlag(),
            tryCreate = { backendName, mtpEnabled, visionBackendIsCpu ->
                attempts += "$backendName-mtp=$mtpEnabled"
                if (backendName == "GPU" && !mtpEnabled) {
                    EngineCandidateAttempt.Success("gpu-plain-engine")
                } else {
                    EngineCandidateAttempt.InitializationFailed(IllegalStateException("init failed"))
                }
            }
        )
        val result = selection.selection
        assertNotNull(result)
        assertEquals("gpu-plain-engine", result!!.engine)
        assertFalse(result.selectedMtpEnabled)
        assertTrue(result.mtpFlagAppliedForMtp)
        assertEquals(listOf("GPU-mtp=true", "GPU-mtp=false"), attempts)
    }

    @Test
    fun `enable failure skips the MTP candidate but lets the plain fallback run`() {
        val flagCalls = mutableListOf<Boolean>()
        val attempts = mutableListOf<Pair<String, Boolean>>()
        val selection = selectFirstWorkingEngine<String>(
            ladder = mtpLadder,
            enableVisionBackend = false,
            configureFlag = { enabled -> flagCalls += enabled; !enabled },
            tryCreate = { backendName, mtpEnabled, visionBackendIsCpu ->
                attempts += (backendName to mtpEnabled)
                EngineCandidateAttempt.Success("$backendName-mtp=$mtpEnabled")
            }
        )
        val result = selection.selection
        assertNotNull(result)
        assertEquals("GPU-mtp=false", result!!.engine)
        assertFalse(result.selectedMtpEnabled)
        assertFalse(result.mtpFlagAppliedForMtp)
        // Enable settle failed: MTP candidate skipped before any attempt.
        assertEquals(listOf(true, false), flagCalls)
        assertEquals(listOf("GPU" to false), attempts)
    }

    @Test
    fun `disable failure skips the plain candidate and keeps MTP`() {
        val flagCalls = mutableListOf<Boolean>()
        val attempts = mutableListOf<Pair<String, Boolean>>()
        val selection = selectFirstWorkingEngine<String>(
            ladder = mtpLadder,
            enableVisionBackend = false,
            configureFlag = { enabled -> flagCalls += enabled; enabled },
            tryCreate = { backendName, mtpEnabled, visionBackendIsCpu ->
                attempts += (backendName to mtpEnabled)
                if (mtpEnabled && backendName == "CPU") {
                    EngineCandidateAttempt.Success("cpu-mtp-engine")
                } else {
                    EngineCandidateAttempt.InitializationFailed(IllegalStateException("init failed"))
                }
            }
        )
        val result = selection.selection
        assertNotNull(result)
        assertEquals("cpu-mtp-engine", result!!.engine)
        assertTrue(result.selectedMtpEnabled)
        assertTrue(result.mtpFlagAppliedForMtp)
        // GPU+MTP failed init; GPU-plain disabled-settle failed and was skipped;
        // CPU+MTP enabled and succeeded.
        assertEquals(listOf(true, false, true), flagCalls)
        assertEquals(listOf("GPU" to true, "CPU" to true), attempts)
    }

    @Test
    fun `never initializes when the flag cannot be settled for any candidate`() {
        val selection = selectFirstWorkingEngine<String>(
            ladder = mtpLadder,
            enableVisionBackend = false,
            configureFlag = { false },
            tryCreate = { _, _, _ -> EngineCandidateAttempt.Success("unreachable") }
        )
        assertNull(selection.selection)
        assertNotNull(selection.failure)
        assertTrue(selection.failure is IllegalStateException)
    }

    @Test
    fun `GPU vision failure retries with CPU vision backend`() {
        var cpuVisionRetry = false
        val selection = selectFirstWorkingEngine<String>(
            ladder = mtpLadder,
            enableVisionBackend = true,
            configureFlag = alwaysFlag(),
            tryCreate = { backendName, mtpEnabled, visionBackendIsCpu ->
                if (visionBackendIsCpu) {
                    cpuVisionRetry = true
                    EngineCandidateAttempt.Success("gpu-engine-cpu-vision")
                } else {
                    EngineCandidateAttempt.InitializationFailed(IllegalStateException("gpu vision init failed"))
                }
            }
        )
        val result = selection.selection
        assertNotNull(result)
        assertTrue(cpuVisionRetry)
        assertEquals("gpu-engine-cpu-vision", result!!.engine)
        assertTrue(result.selectedMtpEnabled)
    }

    @Test
    fun `all candidates failing returns null and last failure`() {
        val failure = IllegalStateException("all failed")
        val selection = selectFirstWorkingEngine<String>(
            ladder = mtpLadder,
            enableVisionBackend = false,
            configureFlag = alwaysFlag(),
            tryCreate = { _, _, _ -> EngineCandidateAttempt.InitializationFailed(failure) }
        )
        assertNull(selection.selection)
        assertEquals(failure, selection.failure)
    }

    @Test
    fun `flag is settled before each candidate attempt`() {
        val events = mutableListOf<String>()
        selectFirstWorkingEngine<String>(
            ladder = mtpLadder,
            enableVisionBackend = false,
            configureFlag = { enabled -> events += "flag:$enabled"; true },
tryCreate = { backendName, mtpEnabled, visionBackendIsCpu ->
                 events += "create:$backendName-$mtpEnabled"
                 EngineCandidateAttempt.InitializationFailed(IllegalStateException("fail"))
             }
        )
        assertEquals(
            listOf(
                "flag:true", "create:GPU-true",
                "flag:false", "create:GPU-false",
                "flag:true", "create:CPU-true",
                "flag:false", "create:CPU-false"
            ),
            events
        )
    }
}