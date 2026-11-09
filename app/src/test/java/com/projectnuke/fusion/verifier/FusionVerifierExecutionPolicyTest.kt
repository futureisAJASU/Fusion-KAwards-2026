package com.projectnuke.fusion.verifier

import org.junit.Assert.assertEquals
import org.junit.Test

class FusionVerifierExecutionPolicyTest {
    @Test
    fun measuredPortablePolicyKeepsSequentialCpuRuntimeBounded() {
        assertEquals(2, FusionVerifierExecutionPolicy.INTRA_OP_THREADS)
        assertEquals(1, FusionVerifierExecutionPolicy.INTER_OP_THREADS)
        assertEquals("CPUExecutionProvider", FusionVerifierExecutionPolicy.EXECUTION_PROVIDER)
    }
}
