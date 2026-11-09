package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.util.HistoryMessageWithId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedHistoryCompactionIdentityTest {
    @Test
    fun `compaction boundary and branch fingerprint use exact surviving durable ids`() = runBlocking {
        val historyItems = listOf(
            HistoryMessageWithId(101L, ChatMessage("user", "u1" + "x".repeat(700))),
            HistoryMessageWithId(102L, ChatMessage("assistant", "a1" + "y".repeat(700))),
            HistoryMessageWithId(105L, ChatMessage("user", "u3" + "x".repeat(700))),
            HistoryMessageWithId(106L, ChatMessage("assistant", "a3" + "y".repeat(700))),
            HistoryMessageWithId(107L, ChatMessage("user", "u4" + "x".repeat(700))),
            HistoryMessageWithId(108L, ChatMessage("assistant", "a4" + "y".repeat(700))),
        )
        val rawMessages = listOf(
            ChatMessage("system", "FUSION_MODEL_FAMILY=QWEN"),
            *historyItems.map(HistoryMessageWithId::message).toTypedArray(),
            ChatMessage("user", "current"),
        )

        val result = prepareLocalAutoCompactionFromCapturedHistory(
            conversationId = 9L,
            rawMessages = rawMessages,
            historyItems = historyItems,
            settings = GenerationSettings(maxTokens = 256, kvCacheCapacityTokens = 2048),
            compactPolicy = LocalCompactPolicy(enabled = true, triggerPercent = 60, recentHistoryPercent = 15),
            existingMemory = null,
            summarize = { _, _, turns ->
                assertTrue(turns.all { it.user.id in listOf(101L, 105L, 107L) })
                "summary"
            },
        )

        val memory = requireNotNull(result.memory)
        assertTrue(result.compactUsed)
        assertTrue(memory.compactedThroughMessageId in listOf(102L, 106L))
        assertEquals(
            AutoCompactionBranchIdentity.fromMessageIds(historyItems.map { it.durableMessageId }, memory.compactedThroughMessageId),
            memory.branchFingerprint,
        )
        assertTrue(memory.compactedThroughMessageId != 104L)
    }
}
