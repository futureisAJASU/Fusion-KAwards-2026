package com.projectnuke.fusion.model

import org.junit.Assert.*
import org.junit.Test

class AutoModelRoutingPolicyTest {
    private val now = 1_000_000_000L
    private val small = AutoModelCandidate("a", "/a.litertlm", "A", 100, true, true, false)
    private val large = small.copy(id = "b", bytes = 200, path = "/b.litertlm", vision = true)
    private fun choose(items: List<AutoModelCandidate> = listOf(large, small), vision: Boolean = false,
        low: Boolean = false, power: Boolean = false, thermal: Int = 0, preference: RuntimePerformancePreference = RuntimePerformancePreference()) =
        AutoModelRoutingPolicy.choose(items, vision, low, RuntimeCpuDeviceSignals(8, powerSaveMode = power, thermalStatus = thermal), preference, now)
    @Test fun noRunnableModels() { assertNull(choose(listOf(small.copy(runnable = false)))) }
    @Test fun lowMemoryFailsClosed() { assertNull(choose(low = true)); assertNull(choose(listOf(small.copy(memoryAllowed = false)))) }
    @Test fun normalUsesSmallStableArtifact() { assertEquals("a", choose()!!.candidate.id); assertEquals(AcceleratorMode.AUTO, choose()!!.accelerator) }
    @Test fun powerSaverUsesConservativeRuntime() { assertEquals(AcceleratorMode.CPU, choose(power = true)!!.accelerator) }
    @Test fun thermalUsesConservativeRuntime() { assertEquals(AcceleratorMode.CPU, choose(thermal = 2)!!.accelerator) }
    @Test fun capabilityMismatchNeverSelected() { assertEquals("b", choose(vision = true)!!.candidate.id); assertNull(choose(listOf(small), vision = true)) }
    @Test fun currentMeasurementsCanRank() { assertEquals("b", choose(listOf(small.copy(measuredRate = 10.0, measuredAt = now), large.copy(measuredRate = 20.0, measuredAt = now)))!!.candidate.id) }
    @Test fun staleOrIncompleteHistoryDoesNotClaimFastest() { assertEquals("a", choose(listOf(small, large.copy(measuredRate = 100.0, measuredAt = now)))!!.candidate.id)
        assertEquals("a", choose(listOf(small.copy(measuredRate = 1.0, measuredAt = 1), large.copy(measuredRate = 100.0, measuredAt = 1)))!!.candidate.id) }
    @Test fun preferenceIsApplied() { assertEquals(AcceleratorMode.CPU, choose(preference = RuntimePerformancePreference(RuntimePerformanceMode.MANUAL, 10))!!.accelerator) }
    @Test fun externalSuggestionNeedsNonPrivateContentAndMetadata() { assertFalse(AutoModelRoutingPolicy.maySuggestExternal(true, true)); assertFalse(AutoModelRoutingPolicy.maySuggestExternal(false, false)); assertTrue(AutoModelRoutingPolicy.maySuggestExternal(false, true)) }
    @Test fun decisionRemainsImmutableWhenInputsChange() { val items = mutableListOf(small, large); val frozen = choose(items)!!; items.clear(); assertEquals(small, frozen.candidate) }
    @Test fun requestedReasoningCannotSilentlySelectANonReasoningModel() {
        val decision = AutoModelRoutingPolicy.choose(listOf(small, large.copy(reasoning = true)), false, false,
            RuntimeCpuDeviceSignals(8), RuntimePerformancePreference(), now, reasoningRequired = true)
        assertEquals("b", decision!!.candidate.id)
        assertNull(AutoModelRoutingPolicy.choose(listOf(small), false, false, RuntimeCpuDeviceSignals(8),
            RuntimePerformancePreference(), now, reasoningRequired = true))
    }
    @Test fun powerSavingDoesNotOverrideRequestedCapabilities() {
        val decision = AutoModelRoutingPolicy.choose(listOf(small, large.copy(reasoning = true)), true, false,
            RuntimeCpuDeviceSignals(8, powerSaveMode = true), RuntimePerformancePreference(), now, reasoningRequired = true)
        assertEquals("b", decision!!.candidate.id)
        assertEquals(AcceleratorMode.CPU, decision.accelerator)
    }
}
