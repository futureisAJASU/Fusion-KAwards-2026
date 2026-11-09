package com.projectnuke.fusion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCpuThreadPolicyTest {
    private fun signals(
        cores: Int = 8,
        lowRam: Boolean = false,
        saver: Boolean = false,
        thermal: Int = RuntimeCpuDeviceSignals.THERMAL_NONE,
    ) = RuntimeCpuDeviceSignals(cores, lowRam, saver, thermal)

    @Test
    fun autoDefaultsConservativelyAndScalesOnlyWithCpuAvailability() {
        assertEquals(1, RuntimeCpuThreadPolicy.resolve(RuntimePerformancePreference(), signals(4)).threadCount)
        assertEquals(2, RuntimeCpuThreadPolicy.resolve(RuntimePerformancePreference(), signals(8)).threadCount)
        assertEquals(3, RuntimeCpuThreadPolicy.resolve(RuntimePerformancePreference(), signals(12)).threadCount)
        val highCore = RuntimeCpuThreadPolicy.resolve(RuntimePerformancePreference(), signals(128))
        assertEquals(4, highCore.threadCount)
        assertEquals(8, highCore.deviceCeiling)
    }

    @Test
    fun autoReducesForLowRamPowerSaverAndModerateOrWorseThermalState() {
        val preference = RuntimePerformancePreference()
        assertEquals(1, RuntimeCpuThreadPolicy.resolve(preference, signals(lowRam = true)).threadCount)
        assertEquals(1, RuntimeCpuThreadPolicy.resolve(preference, signals(saver = true)).threadCount)
        assertEquals(1, RuntimeCpuThreadPolicy.resolve(preference, signals(thermal = RuntimeCpuDeviceSignals.THERMAL_MODERATE)).threadCount)
        assertEquals(1, RuntimeCpuThreadPolicy.resolve(preference, signals(thermal = 3)).threadCount)
    }

    @Test
    fun manualEndpointsAreOneThreadAndBoundedDeviceMaximumEvenInPowerSaver() {
        val device = signals(cores = 12, saver = true, thermal = 4)
        assertEquals(1, RuntimeCpuThreadPolicy.resolve(
            RuntimePerformancePreference(RuntimePerformanceMode.MANUAL, 0), device,
        ).threadCount)
        assertEquals(8, RuntimeCpuThreadPolicy.resolve(
            RuntimePerformancePreference(RuntimePerformanceMode.MANUAL, 100), device,
        ).threadCount)
    }

    @Test
    fun invalidProcessorCountNeverProducesAnInvalidThreadCount() {
        val result = RuntimeCpuThreadPolicy.resolve(RuntimePerformancePreference(), signals(cores = 0))
        assertEquals(1, result.deviceCeiling)
        assertEquals(1, result.threadCount)
        assertTrue(result.threadCount in 1..result.deviceCeiling)
    }

    @Test
    fun thermalDowngradeStaysForTheRuntimeGenerationAfterTheDeviceCools() {
        val preference = RuntimePerformancePreference()
        val normal = RuntimeCpuThreadPolicy.resolve(preference, signals(cores = 16))
        assertEquals(4, normal.threadCount)
        val hot = RuntimeCpuThreadPolicy.resolve(
            preference,
            signals(cores = 16, thermal = RuntimeCpuDeviceSignals.THERMAL_MODERATE),
            previous = normal,
            previousPreference = preference,
        )
        assertEquals(1, hot.threadCount)
        val coolButSameRuntime = RuntimeCpuThreadPolicy.resolve(
            preference,
            signals(cores = 16),
            previous = hot,
            previousPreference = preference,
        )
        assertEquals(1, coolButSameRuntime.threadCount)
        assertEquals(4, RuntimeCpuThreadPolicy.resolve(preference, signals(cores = 16)).threadCount)
    }

    @Test
    fun manualSliderStopsMapToEveryIntegerThreadCount() {
        val ceiling = 8
        val levels = (0..100).map { RuntimeCpuThreadPolicy.snapManualLevel(it, ceiling) }.distinct()
        assertEquals((1..ceiling).toSet(), levels.map { RuntimeCpuThreadPolicy.manualThreadCount(it, ceiling) }.toSet())
        assertEquals(1, RuntimeCpuThreadPolicy.manualThreadCount(0, ceiling))
        assertEquals(ceiling, RuntimeCpuThreadPolicy.manualThreadCount(100, ceiling))
    }
}
