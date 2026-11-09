package com.projectnuke.fusion.llm

import com.google.ai.edge.litertlm.Backend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtBackendPolicyTest {
    @Test
    fun cpuAndFallbackCpuUseResolvedThreadsWhileGpuDoesNotReceiveCpuBudget() {
        val cpu = createLiteRtBackend("CPU", 1) as Backend.CPU
        val fallbackCpu = createLiteRtBackend("CPU", 3) as Backend.CPU
        val gpu = createLiteRtBackend("GPU", 8)

        assertEquals(1, cpu.threadCount)
        assertEquals(3, fallbackCpu.threadCount)
        assertTrue(gpu is Backend.GPU)
    }
}
