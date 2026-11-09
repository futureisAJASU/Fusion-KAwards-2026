package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicFollowUpRuntimePolicyTest {
    @Test
    fun `qualification timeout override applies only to debug builds and stays within production bound`() {
        assertEquals(
            150L,
            DynamicFollowUpRuntimePolicy.resolveTimeoutMs(
                productionTimeoutMs = DynamicFollowUpPolicy.TIMEOUT_MS,
                qualificationOverrideMs = 150L,
                isDebugBuild = true,
            ),
        )
        assertEquals(
            DynamicFollowUpPolicy.TIMEOUT_MS,
            DynamicFollowUpRuntimePolicy.resolveTimeoutMs(
                productionTimeoutMs = DynamicFollowUpPolicy.TIMEOUT_MS,
                qualificationOverrideMs = 150L,
                isDebugBuild = false,
            ),
        )
        assertEquals(
            DynamicFollowUpPolicy.TIMEOUT_MS,
            DynamicFollowUpRuntimePolicy.resolveTimeoutMs(
                productionTimeoutMs = DynamicFollowUpPolicy.TIMEOUT_MS,
                qualificationOverrideMs = DynamicFollowUpPolicy.TIMEOUT_MS + 1L,
                isDebugBuild = true,
            ),
        )
    }
}
