package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPromptCapacityPolicyTest {
    @Test
    fun `history and final fitting share reserve for supported output sizes`() {
        listOf(512, 4096, 8192, 32000).forEach { maxTokens ->
            val reserve = ExternalPromptCapacityPolicy.effectiveOutputReserveChars(maxTokens)
            val available = ExternalPromptCapacityPolicy.availablePromptChars(maxTokens)
            assertEquals(ExternalPromptCapacityPolicy.CONTEXT_LIMIT_CHARS - reserve, available)
            assertEquals(
                available,
                FinalPromptBudgeter.computeLimit(FinalPromptBudget(null, true, maxTokens)) - reserve,
            )
        }
    }

    @Test
    fun `large output reserve remains capped consistently`() {
        val reserve = ExternalPromptCapacityPolicy.effectiveOutputReserveChars(32000)
        assertEquals(24000, reserve)
        assertTrue(ExternalPromptCapacityPolicy.availablePromptChars(32000) > 0)
    }
}
