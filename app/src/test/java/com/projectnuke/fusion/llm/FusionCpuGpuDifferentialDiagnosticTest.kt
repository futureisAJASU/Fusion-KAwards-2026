package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.AcceleratorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionCpuGpuDifferentialDiagnosticTest {
    @Test
    fun `differential plan fixes identical deterministic CPU and GPU inputs`() {
        val plan = FusionCpuGpuDifferentialPlan(
            modelPath = "/models/kanana.litertlm",
            modelIdentity = "kanana",
            promptSha256 = "prompt-sha256",
            promptTemplateIdentity = "paired-kanana-template",
        )

        assertEquals(1, plan.conversationOptions.topK)
        assertEquals(1f, plan.conversationOptions.topP)
        assertEquals(0f, plan.conversationOptions.temperature)
        assertEquals(0, plan.conversationOptions.seed)
        assertEquals(false, plan.profile(AcceleratorMode.CPU).mtpRequested)
        assertEquals(false, plan.profile(AcceleratorMode.GPU).mtpRequested)
        assertEquals(plan.profile(AcceleratorMode.CPU).kvCacheCapacityTokens, plan.profile(AcceleratorMode.GPU).kvCacheCapacityTokens)
    }

    @Test
    fun `diagnostic records bounded output identity without logging prompt`() {
        val observation = observeFusionCpuGpuDifferentialOutput(
            actualBackend = "GPU",
            output = "  hello\n  world  ",
            runtimeDiagnostics = "backend=GPU;kv=2048",
        )

        assertEquals("GPU", observation.actualBackend)
        assertTrue(observation.nonEmptyOutput)
        assertEquals("hello world".length, observation.outputLength)
        assertEquals(
            "B94D27B9934D3E08A52E52D7DA7DABFAC484EFE37A5380EE9088F7ACE2EFCDE9",
            observation.normalizedOutputSha256,
        )
        assertEquals("backend=GPU;kv=2048", observation.runtimeDiagnostics)
    }

    @Test
    fun `blank output is not accepted as nonempty differential evidence`() {
        val observation = observeFusionCpuGpuDifferentialOutput("CPU", " \n\t")
        assertFalse(observation.nonEmptyOutput)
        assertEquals(0, observation.outputLength)
    }
}
