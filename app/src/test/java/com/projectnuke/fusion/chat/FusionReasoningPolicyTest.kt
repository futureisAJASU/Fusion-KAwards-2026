package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionReasoningPolicyTest {
    @Test
    fun `enabled budgets change the model-facing semantic instruction`() {
        val small = fusionReasoningBudgetInstruction(true, 256)
        val large = fusionReasoningBudgetInstruction(true, 2048)
        assertTrue(small.contains("256"))
        assertTrue(large.contains("2048"))
        assertTrue(small != large)
    }

    @Test
    fun `disabled reasoning has no budget instruction`() {
        assertFalse(fusionReasoningBudgetInstruction(false, 2048).isNotBlank())
    }

    @Test
    fun `instruction is semantic input and is therefore budgeted`() {
        val instruction = fusionReasoningBudgetInstruction(true, 256)
        val fitted = FinalPromptBudgeter.fit(
            listOf(ChatMessage("system", instruction), ChatMessage("user", "질문")),
            FinalPromptBudget("model", false, 64, engineKvCapacityTokens = 4096),
        )
        assertTrue(fitted.messages.any { it.content == instruction })
    }
}
