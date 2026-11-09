package com.projectnuke.fusion.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionMemoryManagerTest {
    @Test
    fun `unknown zero available memory is conservative for benchmark`() {
        val snapshot = FusionMemorySnapshot(
            totalMem = 16L * 1024L * 1024L * 1024L,
            availMem = 0L,
            lowMemory = false,
            threshold = 512L * 1024L * 1024L,
        )

        assertEquals(1024, FusionMemoryManager.recommendedBenchmarkMaxTokens(snapshot, 8192))
        assertTrue(FusionMemoryManager.shouldBlockBenchmark(snapshot))
    }

    @Test
    fun `healthy 8GB memory keeps idle runtime for reuse`() {
        val snapshot = FusionMemorySnapshot(
            totalMem = 8L * 1024L * 1024L * 1024L,
            availMem = 3L * 1024L * 1024L * 1024L,
            lowMemory = false,
            threshold = 512L * 1024L * 1024L,
        )

        assertFalse(
            FusionMemoryManager.shouldReleaseIdleRuntime(
                snapshot,
                FusionRuntimeMemoryInfo(
                    modelFileSizeBytes = 1_315_000_000L,
                    actualBackend = "GPU",
                ),
            ),
        )
    }

    @Test
    fun `high pressure 8GB memory releases idle runtime`() {
        val snapshot = FusionMemorySnapshot(
            totalMem = 8L * 1024L * 1024L * 1024L,
            availMem = 1L * 1024L * 1024L * 1024L,
            lowMemory = false,
            threshold = 512L * 1024L * 1024L,
        )

        assertTrue(
            FusionMemoryManager.shouldReleaseIdleRuntime(
                snapshot,
                FusionRuntimeMemoryInfo(
                    modelFileSizeBytes = 1_315_000_000L,
                    actualBackend = "GPU",
                    kvCapacityTokens = 2048,
                ),
            ),
        )
    }

    @Test
    fun `larger than 8GB device does not use lowRAM idle-unload policy`() {
        val snapshot = FusionMemorySnapshot(
            totalMem = 12L * 1024L * 1024L * 1024L,
            availMem = 1L * 1024L * 1024L * 1024L,
            lowMemory = true,
            threshold = 512L * 1024L * 1024L,
        )

        assertFalse(FusionMemoryManager.shouldReleaseIdleRuntime(snapshot))
    }
}
