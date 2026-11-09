package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.LocalCompactPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCompactionGuardedCostTest {
    private val policy = LocalCompactPolicy(
        enabled = true,
        triggerPercent = 80,
        recentHistoryPercent = 25,
    )

    @Test
    fun `DeepSeek threshold includes its pre-budget semantic policy`() {
        val capacity = 1024
        val messages = (0..3000 step 8).asSequence().map { filler ->
            listOf(
                AutoCompactMessage(-5L, "system", "FUSION_GENERATION_SETTINGS"),
                AutoCompactMessage(-4L, "system", "Fusion system rules"),
                AutoCompactMessage(-3L, "system", "FUSION_MODEL_FAMILY=DEEPSEEK"),
                AutoCompactMessage(-2L, "system", "[FUSION_AUTO_COMPACT]\n${"s".repeat(filler)}"),
                AutoCompactMessage(Long.MAX_VALUE, "user", "current request"),
            )
        }.first { candidate ->
            val raw = PromptTokenEstimator.estimateTokens(candidate.map(AutoCompactMessage::toChatMessage))
            val final = estimateFinalSemanticTokens(candidate.map(AutoCompactMessage::toChatMessage))
            (raw + 32) * 100 < capacity * policy.triggerPercent &&
                (final + 32) * 100 >= capacity * policy.triggerPercent
        }

        assertTrue(
            AutoCompactionEngine.shouldTrigger(messages, capacity, 0, policy),
        )
    }

    @Test
    fun `threshold uses guarded historical representation at every supported capacity`() {
        listOf(1024, 2048, 8192).forEach { capacity ->
            val messages = findMessages(capacity) { raw, guarded, threshold ->
                (raw + 32) * 100 < threshold && (guarded + 32) * 100 >= threshold
            }
            assertTrue(
                "guarded cost must cross the trigger while raw cost does not",
                AutoCompactionEngine.shouldTrigger(
                    messages = messages,
                    effectiveCapacityTokens = capacity,
                    requiredOutputTokens = 0,
                    policy = policy,
                ),
            )
        }
    }

    @Test
    fun `guarded fallback removes a whole turn when raw fallback would keep all`() = runBlocking {
        val capacity = 1024
        val messages = findMessages(capacity, includeAutoCompact = false) { raw, guarded, _ ->
            raw <= capacity - 32 && guarded > capacity - 32
        }
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = capacity,
            requiredOutputTokens = 0,
            policy = policy,
            existingMemory = null,
            summarize = { _, _, _ -> null },
        )

        assertTrue("considered=${result.compactConsidered}", result.compactConsidered)
        assertTrue("trimmed=${result.fallbackTrimmed}", result.fallbackTrimmed)
    }

    @Test
    fun `guarded fallback reports no trim when every post-memory turn fits`() = runBlocking {
        val capacity = 1024
        val messages = findMessages(capacity, includeAutoCompact = false) { _, guarded, threshold ->
            guarded <= capacity - 32 && (guarded + 32) * 100 >= threshold
        }
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = capacity,
            requiredOutputTokens = 0,
            policy = policy,
            existingMemory = null,
            summarize = { _, _, _ -> null },
        )

        assertTrue("considered=${result.compactConsidered}", result.compactConsidered)
        assertFalse("trimmed=${result.fallbackTrimmed}", result.fallbackTrimmed)
    }

    private fun findMessages(
        capacity: Int,
        includeAutoCompact: Boolean = true,
        predicate: (raw: Int, guarded: Int, threshold: Long) -> Boolean,
    ): List<AutoCompactMessage> {
        for (fillerLength in 0..100_000 step 8) {
            val messages = messagesWithFiller(fillerLength, includeAutoCompact)
            val raw = PromptTokenEstimator.estimateTokens(messages.map(AutoCompactMessage::toChatMessage))
            val guarded = estimateFinalSemanticTokens(messages.map(AutoCompactMessage::toChatMessage))
            val threshold = capacity.toLong() * policy.triggerPercent
            if (predicate(raw, guarded, threshold)) return messages
        }
        error("No targeted context-cost fixture for capacity $capacity")
    }

    private fun messagesWithFiller(fillerLength: Int, includeAutoCompact: Boolean): List<AutoCompactMessage> = buildList {
        add(AutoCompactMessage(-10L, "system", "[저장된 메모리]\n${"s".repeat(fillerLength)}"))
        add(AutoCompactMessage(-9L, "system", "[대화 요약]\nold manual context"))
        if (includeAutoCompact) {
            add(AutoCompactMessage(-8L, "system", "[FUSION_AUTO_COMPACT]\nauto context"))
        }
        repeat(4) { index ->
            add(AutoCompactMessage(index * 2L + 1L, "user", "user turn $index"))
            add(AutoCompactMessage(index * 2L + 2L, "assistant", "assistant turn $index"))
        }
        add(AutoCompactMessage(Long.MAX_VALUE, "user", "current request"))
    }
}
