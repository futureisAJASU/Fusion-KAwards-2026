package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairPhaseEPromptTokenEstimatorTest {

    @Test
    fun `estimateTokens returns 0 for an empty message list`() {
        assertEquals(0, PromptTokenEstimator.estimateTokens(emptyList()))
    }

    @Test
    fun `estimateTokens accounts for message overhead per message`() {
        // Each ASCII message estimates four tokens plus conservative template overhead.
        val messages = listOf(
            ChatMessage("user", "12345678"),
            ChatMessage("assistant", "abcdefgh"),
        )
        assertEquals(136, PromptTokenEstimator.estimateTokens(messages))
    }

    @Test
    fun `estimateTokensForString conservatively weights Korean and ASCII`() {
        assertEquals(0, PromptTokenEstimator.estimateTokensForString(""))
        assertEquals(2, PromptTokenEstimator.estimateTokensForString("abc"))
        assertEquals(2, PromptTokenEstimator.estimateTokensForString("abcd"))
        assertEquals(10, PromptTokenEstimator.estimateTokensForString("a".repeat(20)))
        assertEquals(8, PromptTokenEstimator.estimateTokensForString("가".repeat(4)))
    }

    @Test
    fun `estimateTokensForString never returns negative for unusual inputs`() {
        assertTrue(PromptTokenEstimator.estimateTokensForString("") >= 0)
        assertTrue(PromptTokenEstimator.estimateTokensForString("가".repeat(400)) >= 0)
    }

    @Test
    fun `estimateTokens grows monotonically with message length`() {
        val short = PromptTokenEstimator.estimateTokensForString("a".repeat(40))
        val long = PromptTokenEstimator.estimateTokensForString("a".repeat(400))
        assertTrue("longer text must yield more tokens", long > short)
    }

    @Test
    fun `many short messages use the same per-message ceiling as KV fitting`() {
        val messages = List(3) { ChatMessage("user", "x") }
        assertEquals(195, PromptTokenEstimator.estimateTokens(messages))
        assertEquals(
            PromptTokenEstimator.estimateTokens(messages),
            PromptTokenCost.estimateMessages(messages),
        )
    }
}
