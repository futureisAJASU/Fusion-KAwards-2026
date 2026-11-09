package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.ConversationOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtOutputLimitTest {

    @Test
    fun `estimator maps characters to tokens with a 4-to-1 heuristic`() {
        assertEquals(0, estimateStreamOutputTokens(""))
        assertEquals(0, estimateStreamOutputTokens("abc"))
        assertEquals(1, estimateStreamOutputTokens("abcd"))
        assertEquals(5, estimateStreamOutputTokens("a".repeat(20)))
        assertEquals(100, estimateStreamOutputTokens("가".repeat(400)))
    }

    @Test
    fun `char count estimator preserves exact boundary semantics`() {
        assertEquals(0, estimateStreamOutputTokensFromCharCount(0))
        assertEquals(0, estimateStreamOutputTokensFromCharCount(1))
        assertEquals(0, estimateStreamOutputTokensFromCharCount(2))
        assertEquals(0, estimateStreamOutputTokensFromCharCount(3))
        assertEquals(1, estimateStreamOutputTokensFromCharCount(4))
        assertEquals(1, estimateStreamOutputTokensFromCharCount(7))
        assertEquals(2, estimateStreamOutputTokensFromCharCount(8))
        assertEquals(2, estimateStreamOutputTokensFromCharCount(9))
        assertEquals(250_000, estimateStreamOutputTokensFromCharCount(1_000_000))
    }

    @Test
    fun `string and char count estimators agree for representative lengths`() {
        listOf(0, 1, 3, 4, 7, 8, 9, 20, 400, 1_000_003).forEach { charCount ->
            assertEquals(
                estimateStreamOutputTokens("x".repeat(charCount)),
                estimateStreamOutputTokensFromCharCount(charCount),
            )
        }
    }

    @Test
    fun `limit is reached only when the estimate crosses the cap`() {
        val options = ConversationOptions(maxOutputToken = 100)
        val shortOutput = StringBuilder("a".repeat(399))
        val atCap = StringBuilder("a".repeat(400))
        val overCap = StringBuilder("a".repeat(401))
        assertFalse(isAppOutputLimitReached(options, shortOutput))
        assertTrue(isAppOutputLimitReached(options, atCap))
        assertTrue(isAppOutputLimitReached(options, overCap))
    }

    @Test
    fun `null output limit never truncates`() {
        val options = ConversationOptions(maxOutputToken = null)
        assertFalse(isAppOutputLimitReached(options, StringBuilder("a".repeat(100_000))))
    }

    @Test
    fun `zero and negative caps truncate immediately but never misbehave`() {
        assertTrue(isAppOutputLimitReached(ConversationOptions(maxOutputToken = 0), StringBuilder()))
        assertFalse(isAppOutputLimitReached(ConversationOptions(maxOutputToken = null), StringBuilder()))
    }
}
