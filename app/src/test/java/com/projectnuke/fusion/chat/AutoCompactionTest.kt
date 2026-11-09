package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.llm.buildPrompt
import com.projectnuke.fusion.llm.buildSystemInstruction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCompactionTest {
    private fun history(turns: Int = 8, size: Int = 200): List<AutoCompactMessage> = buildList {
        add(AutoCompactMessage(1, "system", "policy"))
        var id = 10L
        repeat(turns) { index ->
            add(AutoCompactMessage(id++, "user", "u$index" + "x".repeat(size)))
            add(AutoCompactMessage(id++, "assistant", "a$index" + "y".repeat(size)))
        }
        add(AutoCompactMessage(id, "user", "current"))
    }

    private val policy = LocalCompactPolicy(enabled = true, triggerPercent = 80, recentHistoryPercent = 25)

    @Test
    fun `below threshold does not compact`() = runBlocking {
        val messages = history(2, 10)
        val result = AutoCompactionEngine.prepare(1, messages, 8192, 384, policy, null) { _, _, _ -> "summary" }
        assertFalse(result.compactConsidered)
        assertFalse(result.compactUsed)
        assertEquals(messages, result.promptMessages)
    }

    @Test
    fun `enabled but not triggered preserves orphan and complete messages in exact order`() = runBlocking {
        val messages = listOf(
            AutoCompactMessage(1L, "system", "policy"),
            AutoCompactMessage(10L, "user", "orphan A"),
            AutoCompactMessage(11L, "user", "request B"),
            AutoCompactMessage(12L, "assistant", "answer B"),
            AutoCompactMessage(13L, "user", "current C"),
        )
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            summarize = { _, _, _ -> error("must not compact") },
        )

        assertFalse(result.compactConsidered)
        assertEquals(messages, result.promptMessages)
    }

    @Test
    fun `triggered compaction retains orphan verbatim and fingerprints the represented prefix`() = runBlocking {
        val messages = buildList {
            add(AutoCompactMessage(1L, "system", "policy"))
            add(AutoCompactMessage(10L, "user", "orphan A"))
            var id = 11L
            repeat(10) { index ->
                add(AutoCompactMessage(id++, "user", "request-$index" + "x".repeat(700)))
                add(AutoCompactMessage(id++, "assistant", "answer-$index" + "y".repeat(700)))
            }
            add(AutoCompactMessage(id, "user", "current C"))
        }
        val branchIds = messages.filter { it.role != "system" && it.id != messages.last().id }.map { it.id }
        val summarizedTurns = mutableListOf<AutoCompactTurn>()
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            branchMessageIds = branchIds,
            summarize = { _, _, turns ->
                summarizedTurns += turns
                "compact without orphan"
            },
        )

        assertTrue(result.compactUsed)
        assertTrue(summarizedTurns.isNotEmpty())
        assertTrue(summarizedTurns.none { it.user.id == 10L })
        val serialized = buildSystemInstruction(
            result.promptMessages.map(AutoCompactMessage::toChatMessage),
        ) + "\n" + buildPrompt(result.promptMessages.map(AutoCompactMessage::toChatMessage))
        assertTrue(serialized.contains("orphan A"))
        assertTrue("compactUsed=${result.compactUsed}, memory=${result.memory}, prompt=${result.promptMessages}", result.promptMessages.any { it.content.contains("compact without orphan") })
        val boundary = result.memory!!.compactedThroughMessageId
        assertEquals(
            AutoCompactionBranchIdentity.fromMessageIds(branchIds, boundary),
            result.memory.branchFingerprint,
        )
        assertTrue(serialized.indexOf("orphan A") < serialized.indexOf("compact without orphan"))
    }

    @Test
    fun `final serialization preserves orphan before compacted history before current user`() = runBlocking {
        val messages = buildList {
            add(AutoCompactMessage(10L, "user", "A"))
            add(AutoCompactMessage(11L, "user", "B"))
            add(AutoCompactMessage(12L, "assistant", "answer B"))
            repeat(8) { index ->
                add(AutoCompactMessage(20L + index * 2, "user", "padding user $index" + "x".repeat(700)))
                add(AutoCompactMessage(21L + index * 2, "assistant", "padding answer $index" + "y".repeat(700)))
            }
            add(AutoCompactMessage(103L, "user", "C"))
        }
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            summarize = { _, _, _ -> "summary of B" },
        )
        val finalMessages = HistoricalMemoryPromptPolicy.prepare(
            result.promptMessages.map(AutoCompactMessage::toChatMessage),
        )
        val serialized = buildSystemInstruction(finalMessages) +
            "\n" + buildPrompt(finalMessages)

        assertTrue(result.compactUsed)
        val orphanIndex = serialized.indexOf("[VERBATIM_USER]\nA")
        val summaryIndex = serialized.indexOf("summary of B")
        val currentIndex = serialized.indexOf("User:\nC")
        assertTrue(orphanIndex >= 0)
        assertTrue(summaryIndex >= 0)
        assertTrue(currentIndex >= 0)
        assertTrue(orphanIndex < summaryIndex)
        assertTrue(summaryIndex < currentIndex)
        assertTrue(serialized.indexOf("[VERBATIM_USER]") >= 0)
        assertTrue(serialized.indexOf("summary of B") >= 0)
        assertTrue(serialized.indexOf("User:\nC") >= 0)
        assertTrue(serialized.contains("[VERBATIM_USER]"))
    }

    @Test
    fun `first generation stops at an interleaved orphan barrier`() = runBlocking {
        val messages = listOf(
            AutoCompactMessage(10L, "user", "T1 user" + "x".repeat(700)),
            AutoCompactMessage(11L, "assistant", "T1 answer" + "y".repeat(700)),
            AutoCompactMessage(12L, "user", "orphan between turns"),
            AutoCompactMessage(13L, "user", "T2 user" + "x".repeat(700)),
            AutoCompactMessage(14L, "assistant", "T2 answer" + "y".repeat(700)),
            AutoCompactMessage(15L, "user", "T3 user" + "x".repeat(700)),
            AutoCompactMessage(16L, "assistant", "T3 answer" + "y".repeat(700)),
            AutoCompactMessage(17L, "user", "T4 user" + "x".repeat(700)),
            AutoCompactMessage(18L, "assistant", "T4 answer" + "y".repeat(700)),
            AutoCompactMessage(19L, "user", "current"),
        )
        val summarized = mutableListOf<List<Long>>()
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            branchMessageIds = messages.dropLast(1).map { it.id },
            summarize = { _, _, turns ->
                summarized += turns.map { it.boundaryMessageId }
                "summary T1 only"
            },
        )
        val finalMessages = HistoricalMemoryPromptPolicy.prepare(
            result.promptMessages.map(AutoCompactMessage::toChatMessage),
        )
        val serialized = buildSystemInstruction(finalMessages) + "\n" + buildPrompt(finalMessages)
        val summaryIndex = serialized.indexOf("summary T1 only")
        val orphanIndex = serialized.indexOf("User:\norphan between turns")
        val t2Index = serialized.indexOf("User:\nT2 user")
        val currentIndex = serialized.indexOf("User:\ncurrent")
        assertTrue(result.compactUsed)
        assertEquals(listOf(listOf(11L)), summarized)
        assertEquals(11L, result.memory!!.compactedThroughMessageId)
        assertEquals(
            AutoCompactionBranchIdentity.fromMessageIds(messages.dropLast(1).map { it.id }, 11L),
            result.memory.branchFingerprint,
        )
        assertTrue(summaryIndex >= 0)
        assertTrue(orphanIndex >= 0)
        assertTrue(t2Index >= 0)
        assertTrue(currentIndex >= 0)
        assertTrue(summaryIndex < orphanIndex)
        assertTrue(orphanIndex < t2Index)
        assertTrue(t2Index < currentIndex)
    }

    @Test
    fun `incremental compaction stops before an interleaved orphan after existing boundary`() = runBlocking {
        val messages = listOf(
            AutoCompactMessage(1L, "user", "T0 user" + "x".repeat(700)),
            AutoCompactMessage(2L, "assistant", "T0 answer" + "y".repeat(700)),
            AutoCompactMessage(3L, "user", "T1 user" + "x".repeat(700)),
            AutoCompactMessage(4L, "assistant", "T1 answer" + "y".repeat(700)),
            AutoCompactMessage(5L, "user", "orphan after T1"),
            AutoCompactMessage(6L, "user", "T2 user" + "x".repeat(700)),
            AutoCompactMessage(7L, "assistant", "T2 answer" + "y".repeat(700)),
            AutoCompactMessage(8L, "user", "T3 user" + "x".repeat(700)),
            AutoCompactMessage(9L, "assistant", "T3 answer" + "y".repeat(700)),
            AutoCompactMessage(10L, "user", "current"),
        )
        val existing = AutoCompactMemory(
            conversationId = 1L,
            summary = "summary T0",
            compactedThroughMessageId = 2L,
            updatedAt = 1L,
            sourceTurnCount = 1,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(
                messages.dropLast(1).map { it.id },
                2L,
            ),
        )
        val summarized = mutableListOf<List<Long>>()
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            branchMessageIds = messages.dropLast(1).map { it.id },
            summarize = { _, _, turns ->
                summarized += turns.map { it.boundaryMessageId }
                "summary T1 only"
            },
        )
        val finalMessages = HistoricalMemoryPromptPolicy.prepare(
            result.promptMessages.map(AutoCompactMessage::toChatMessage),
        )
        val serialized = buildSystemInstruction(finalMessages) + "\n" + buildPrompt(finalMessages)
        val summaryIndex = serialized.indexOf("summary T1 only")
        val orphanIndex = serialized.indexOf("User:\norphan after T1")
        val t2Index = serialized.indexOf("User:\nT2 user")
        assertTrue(result.compactUsed)
        assertEquals(listOf(listOf(4L)), summarized)
        assertEquals(4L, result.memory!!.compactedThroughMessageId)
        assertEquals(
            AutoCompactionBranchIdentity.fromMessageIds(messages.dropLast(1).map { it.id }, 4L),
            result.memory.branchFingerprint,
        )
        assertTrue(summaryIndex >= 0)
        assertTrue(orphanIndex >= 0)
        assertTrue(t2Index >= 0)
        assertTrue(summaryIndex < orphanIndex)
        assertTrue(orphanIndex < t2Index)
    }

    @Test
    fun `multiple verbatim barriers never cross into one compact summary`() = runBlocking {
        val messages = listOf(
            AutoCompactMessage(10L, "user", "T1 user" + "x".repeat(700)),
            AutoCompactMessage(11L, "assistant", "T1 answer" + "y".repeat(700)),
            AutoCompactMessage(12L, "user", "orphan user A"),
            AutoCompactMessage(13L, "user", "T2 user" + "x".repeat(700)),
            AutoCompactMessage(14L, "assistant", "T2 answer" + "y".repeat(700)),
            AutoCompactMessage(15L, "assistant", "orphan assistant B"),
            AutoCompactMessage(16L, "user", "T3 user" + "x".repeat(700)),
            AutoCompactMessage(17L, "assistant", "T3 answer" + "y".repeat(700)),
            AutoCompactMessage(18L, "user", "T4 user" + "x".repeat(700)),
            AutoCompactMessage(19L, "assistant", "T4 answer" + "y".repeat(700)),
            AutoCompactMessage(20L, "user", "current"),
        )
        val summarized = mutableListOf<Long>()
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            branchMessageIds = messages.dropLast(1).map { it.id },
            summarize = { _, _, turns ->
                summarized += turns.flatMap { it.messages }.map { it.id }
                "summary before barriers"
            },
        )
        val finalMessages = HistoricalMemoryPromptPolicy.prepare(
            result.promptMessages.map(AutoCompactMessage::toChatMessage),
        )
        val serialized = buildSystemInstruction(finalMessages) + "\n" + buildPrompt(finalMessages)
        assertTrue(result.compactUsed)
        assertEquals(listOf(10L, 11L), summarized)
        assertEquals(11L, result.memory!!.compactedThroughMessageId)
        assertTrue(serialized.indexOf("summary before barriers") >= 0)
        assertTrue(serialized.indexOf("User:\norphan user A") >= 0)
        assertTrue(serialized.indexOf("Assistant:\norphan assistant B") >= 0)
        assertTrue(serialized.indexOf("User:\norphan user A") < serialized.indexOf("User:\nT2 user"))
        assertTrue(serialized.indexOf("User:\nT2 user") < serialized.indexOf("Assistant:\norphan assistant B"))
        assertTrue(serialized.indexOf("Assistant:\norphan assistant B") < serialized.indexOf("User:\nT3 user"))
    }

    @Test
    fun `existing historical sources keep orphan chronology in final serialization`() = runBlocking {
        val oldTurn = listOf(
            AutoCompactMessage(1L, "user", "old user"),
            AutoCompactMessage(2L, "assistant", "old answer"),
        )
        val messages = listOf(
            AutoCompactMessage(3L, "system", "[저장된 메모리]\nsaved memory context"),
            AutoCompactMessage(4L, "system", "[대화 요약]\nmanual conversation summary"),
        ) + oldTurn + listOf(
            AutoCompactMessage(10L, "user", "A"),
            AutoCompactMessage(11L, "user", "B"),
            AutoCompactMessage(12L, "assistant", "answer B"),
            AutoCompactMessage(13L, "user", "C"),
        )
        val existing = AutoCompactMemory(1L, "old compact", 2L, 1L, 1)
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 512,
            requiredOutputTokens = 128,
            policy = policy,
            existingMemory = existing,
            summarize = { _, _, _ -> error("verbatim gap must not be crossed") },
        )
        val finalMessages = HistoricalMemoryPromptPolicy.prepare(
            result.promptMessages.map(AutoCompactMessage::toChatMessage),
        )
        val serialized = buildSystemInstruction(finalMessages) + "\n" + buildPrompt(finalMessages)

        assertTrue(serialized.contains("[FUSION_HISTORICAL_MEMORY_BEGIN:SAVED]"))
        assertTrue(serialized.contains("[FUSION_HISTORICAL_MEMORY_BEGIN:MANUAL_SUMMARY]"))
        assertTrue(serialized.contains("[FUSION_HISTORICAL_MEMORY_BEGIN:AUTO_COMPACT]"))
        val savedIndex = serialized.indexOf("[FUSION_HISTORICAL_MEMORY_BEGIN:SAVED]")
        val manualIndex = serialized.indexOf("[FUSION_HISTORICAL_MEMORY_BEGIN:MANUAL_SUMMARY]")
        val compactIndex = serialized.indexOf("[FUSION_HISTORICAL_MEMORY_BEGIN:AUTO_COMPACT]")
        val orphanIndex = serialized.indexOf("User:\nA")
        val nextUserIndex = serialized.indexOf("User:\nB")
        val currentIndex = serialized.indexOf("User:\nC")
        assertTrue(savedIndex >= 0)
        assertTrue(manualIndex >= 0)
        assertTrue(compactIndex >= 0)
        assertTrue(orphanIndex >= 0)
        assertTrue(nextUserIndex >= 0)
        assertTrue(currentIndex >= 0)
        assertTrue(savedIndex < orphanIndex)
        assertTrue(manualIndex < orphanIndex)
        assertTrue(compactIndex < orphanIndex)
        assertTrue(orphanIndex < nextUserIndex)
        assertTrue(nextUserIndex < currentIndex)
        assertEquals(existing, result.memory)
    }

    @Test
    fun `incremental compaction preserves first generation verbatim and strips it from llm input`() = runBlocking {
        val firstMessages = buildList {
            add(AutoCompactMessage(10L, "user", "A"))
            var id = 11L
            repeat(12) { index ->
                add(AutoCompactMessage(id++, "user", "B-$index" + "x".repeat(700)))
                add(AutoCompactMessage(id++, "assistant", "BA-$index" + "y".repeat(700)))
            }
            add(AutoCompactMessage(id, "user", "C"))
        }
        val first = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = firstMessages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            branchMessageIds = firstMessages.dropLast(1).map { it.id },
            summarize = { _, _, _ -> "first complete-turn payload" },
        )
        val firstMemory = first.memory
        assertNotNull(firstMemory)
        assertTrue(firstMemory!!.summary.contains("[VERBATIM_USER]\nA"))

        val secondMessages = firstMessages + listOf(
            AutoCompactMessage(100L, "assistant", "answer C"),
            AutoCompactMessage(101L, "user", "D" + "x".repeat(700)),
            AutoCompactMessage(102L, "assistant", "DA" + "y".repeat(700)),
            AutoCompactMessage(103L, "user", "E" + "x".repeat(700)),
            AutoCompactMessage(104L, "assistant", "EA" + "y".repeat(700)),
            AutoCompactMessage(105L, "user", "current second"),
        )
        val previousSummaries = mutableListOf<String?>()
        val second = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = secondMessages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = firstMemory,
            branchMessageIds = secondMessages.dropLast(1).map { it.id },
            summarize = { _, previous, _ ->
                previousSummaries += previous
                "replacement without preserved orphan"
            },
        )

        assertTrue(second.compactUsed)
        assertTrue(previousSummaries.isNotEmpty())
        assertTrue(previousSummaries.all { it?.contains("[VERBATIM_USER]") == false })
        assertTrue(second.memory!!.summary.contains("[VERBATIM_USER]\nA"))
        val finalMessages = HistoricalMemoryPromptPolicy.prepare(
            second.promptMessages.map(AutoCompactMessage::toChatMessage),
        )
        val serialized = buildSystemInstruction(finalMessages) + "\n" + buildPrompt(finalMessages)
        val orphanIndex = serialized.indexOf("[VERBATIM_USER]\nA")
        val summaryIndex = serialized.indexOf("replacement without preserved orphan")
        val currentIndex = serialized.indexOf("User:\ncurrent second")
        assertTrue(orphanIndex >= 0)
        assertTrue(summaryIndex >= 0)
        assertTrue(currentIndex >= 0)
        assertTrue(orphanIndex < summaryIndex)
        assertTrue(summaryIndex < currentIndex)
        assertEquals(
            AutoCompactionBranchIdentity.fromMessageIds(
                secondMessages.dropLast(1).map { it.id },
                second.memory!!.compactedThroughMessageId,
            ),
            second.memory!!.branchFingerprint,
        )
    }

    @Test
    fun `incremental compaction preserves orphan assistant exactly`() = runBlocking {
        val messages = buildList {
            add(AutoCompactMessage(1L, "assistant", "orphan assistant"))
            var id = 10L
            repeat(12) { index ->
                add(AutoCompactMessage(id++, "user", "user-$index" + "x".repeat(700)))
                add(AutoCompactMessage(id++, "assistant", "answer-$index" + "y".repeat(700)))
            }
            add(AutoCompactMessage(id, "user", "current"))
        }
        val first = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            summarize = { _, _, _ -> "first summary" },
        )
        val second = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages + listOf(
                AutoCompactMessage(100L, "assistant", "current answer"),
                AutoCompactMessage(101L, "user", "later" + "x".repeat(700)),
                AutoCompactMessage(102L, "assistant", "later answer" + "y".repeat(700)),
                AutoCompactMessage(103L, "user", "new current"),
            ),
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = first.memory,
            summarize = { _, _, _ -> "second summary without orphan assistant" },
        )
        assertTrue(second.compactUsed)
        assertTrue(second.memory!!.summary.contains("[VERBATIM_ASSISTANT]\norphan assistant"))
    }

    @Test
    fun `framed historical representation round trips marker collisions and incremental payloads`() = runBlocking {
        val cases = listOf(
            "user" to "[COMPACTED_COMPLETE_TURNS]",
            "user" to "[VERBATIM_USER]",
            "assistant" to "[VERBATIM_ASSISTANT]",
            "user" to "[FUSION_AUTO_COMPACT_REPRESENTATION]\n\n[COMPACTED_COMPLETE_TURNS]\n[VERBATIM_USER]",
            "assistant" to "",
            "user" to "앞뒤\n[VERBATIM_ASSISTANT]\n중간",
        )

        cases.forEachIndexed { caseIndex, (role, payload) ->
            val firstMessages = buildList {
                add(AutoCompactMessage(1L, role, payload))
                var id = 10L
                repeat(14) { index ->
                    add(AutoCompactMessage(id++, "user", "user-$index" + "x".repeat(700)))
                    add(AutoCompactMessage(id++, "assistant", "answer-$index" + "y".repeat(700)))
                }
                add(AutoCompactMessage(id, "user", "current-$caseIndex"))
            }
            val firstSummary =
                "first [VERBATIM_USER]\n[COMPACTED_COMPLETE_TURNS]\n[VERBATIM_ASSISTANT]"
            val first = AutoCompactionEngine.prepare(
                conversationId = caseIndex.toLong() + 1L,
                messages = firstMessages,
                effectiveCapacityTokens = 2048,
                requiredOutputTokens = 384,
                policy = policy,
                existingMemory = null,
                branchMessageIds = firstMessages.dropLast(1).map { it.id },
                summarize = { _, _, _ -> firstSummary },
            )
            assertTrue("case=$caseIndex", first.compactUsed)
            val firstMemory = first.memory
            assertNotNull("case=$caseIndex", firstMemory)
            val marker = if (role == "user") "[VERBATIM_USER]" else "[VERBATIM_ASSISTANT]"
            assertTrue(
                "case=$caseIndex summary=${firstMemory!!.summary}",
                firstMemory.summary.contains("$marker\n$payload"),
            )

            val secondMessages = firstMessages + buildList {
                var id = 1_000L
                add(AutoCompactMessage(id++, "assistant", "answer current-$caseIndex"))
                repeat(4) { index ->
                    add(AutoCompactMessage(id++, "user", "later-$index" + "x".repeat(700)))
                    add(AutoCompactMessage(id++, "assistant", "later-answer-$index" + "y".repeat(700)))
                }
                add(AutoCompactMessage(id, "user", "current second-$caseIndex"))
            }
            val previousSummaries = mutableListOf<String?>()
            val second = AutoCompactionEngine.prepare(
                conversationId = caseIndex.toLong() + 1L,
                messages = secondMessages,
                effectiveCapacityTokens = 2048,
                requiredOutputTokens = 384,
                policy = policy,
                existingMemory = firstMemory,
                branchMessageIds = secondMessages.dropLast(1).map { it.id },
                summarize = { _, previous, _ ->
                    previousSummaries += previous
                    "replacement [COMPACTED_COMPLETE_TURNS]\n[VERBATIM_USER]\n[VERBATIM_ASSISTANT]"
                },
            )
            assertTrue("case=$caseIndex", second.compactUsed)
            assertTrue("case=$caseIndex previous=$previousSummaries", previousSummaries.contains(firstSummary))
            val secondMemory = second.memory
            assertNotNull("case=$caseIndex", secondMemory)
            assertTrue(
                "case=$caseIndex summary=${secondMemory!!.summary}",
                secondMemory.summary.contains("$marker\n$payload"),
            )
            assertTrue(secondMemory.summary.contains("replacement [COMPACTED_COMPLETE_TURNS]"))
            assertEquals(
                AutoCompactionBranchIdentity.fromMessageIds(
                    secondMessages.dropLast(1).map { it.id },
                    secondMemory.compactedThroughMessageId,
                ),
                secondMemory.branchFingerprint,
            )
        }
    }

    @Test
    fun `cancellation-shaped consecutive users lose no semantic message`() = runBlocking {
        val messages = listOf(
            AutoCompactMessage(10L, "user", "user A"),
            AutoCompactMessage(11L, "user", "user B"),
            AutoCompactMessage(12L, "assistant", "assistant B"),
            AutoCompactMessage(13L, "user", "user C"),
        )
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 8192,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            summarize = { _, _, _ -> error("must not compact") },
        )
        assertEquals(messages, result.promptMessages)
    }

    @Test
    fun `orphan assistant remains verbatim and does not shift adjacent pairing`() = runBlocking {
        val messages = listOf(
            AutoCompactMessage(10L, "assistant", "orphan assistant"),
            AutoCompactMessage(11L, "user", "user B"),
            AutoCompactMessage(12L, "assistant", "assistant B"),
            AutoCompactMessage(13L, "assistant", "duplicate assistant"),
            AutoCompactMessage(14L, "user", "current C"),
        )
        val summarized = mutableListOf<AutoCompactTurn>()
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 256,
            requiredOutputTokens = 32,
            policy = policy,
            existingMemory = null,
            summarize = { _, _, turns ->
                summarized += turns
                "summary"
            },
        )
        assertTrue(result.promptMessages.any { it.content == "orphan assistant" })
        assertTrue(result.promptMessages.any { it.content == "duplicate assistant" })
        assertTrue(summarized.all { it.user.id == 11L && it.assistant.id == 12L })
    }

    @Test
    fun `disabled auto compact remains exact identity with unmatched semantic messages`() = runBlocking {
        val messages = listOf(
            AutoCompactMessage(10L, "user", "orphan A"),
            AutoCompactMessage(11L, "user", "user B"),
            AutoCompactMessage(12L, "assistant", "assistant B"),
            AutoCompactMessage(13L, "user", "current C"),
        )
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 1,
            requiredOutputTokens = 384,
            policy = policy.copy(enabled = false),
            existingMemory = null,
            summarize = { _, _, _ -> error("disabled auto compact must not run") },
        )
        assertEquals(messages, result.promptMessages)
        assertFalse(result.compactConsidered)
    }

    @Test
    fun `at threshold triggers deterministically`() = runBlocking {
        val messages = history(8, 2000)
        assertTrue(AutoCompactionEngine.shouldTrigger(messages, 8192, 384, policy))
        val result = AutoCompactionEngine.prepare(1, messages, 8192, 384, policy, null) { _, _, _ -> "summary" }
        assertTrue(result.compactConsidered)
        assertTrue(result.compactUsed)
    }

    @Test
    fun `compaction summarizes oldest complete turns and preserves recent window`() = runBlocking {
        val messages = history()
        val summarized = mutableListOf<AutoCompactTurn>()
        var calls = 0
        val result = AutoCompactionEngine.prepare(42, messages, 1400, 384, policy, null) { request, previous, turns ->
            assertEquals(384, request.maxOutputTokens)
            if (calls++ == 0) assertEquals(null, previous) else assertNotNull(previous)
            summarized += turns
            "compact summary"
        }
        assertTrue(summarized.isNotEmpty())
        assertTrue(summarized.first().user.content.startsWith("u0"))
        assertTrue(result.promptMessages.any { it.content.contains("compact summary") })
        assertTrue(result.promptMessages.any { it.content.contains("u7") })
        assertTrue(result.promptMessages.any { it.content == "current" })
        assertNotNull(result.memory)
        assertEquals(42L, result.memory?.conversationId)
    }

    @Test
    fun `compaction receives turns that normal KV history trimming would discard`() = runBlocking {
        val fullHistory = history(turns = 20, size = 500)
        val trimmed = PromptHistoryBudgeter.select(
            PromptHistoryBudgetRequest(
                history = fullHistory.map(AutoCompactMessage::toChatMessage),
                modelId = "custom-model",
                generationModeKey = "LOCAL",
                maxOutputTokens = 384,
                currentRequestChars = 100,
                attachmentCount = 0,
                webSearchPlanned = false,
                engineKvCapacityTokens = 1024,
            )
        )
        assertTrue(trimmed.messages.none { it.content.startsWith("u0") })

        val summarized = mutableListOf<AutoCompactTurn>()
        AutoCompactionEngine.prepare(1, fullHistory, 4096, 384, policy, null) { _, _, turns ->
            summarized += turns
            "summary"
        }
        assertTrue(summarized.any { it.user.content.startsWith("u0") })
        assertTrue(summarized.any { it.user.content.startsWith("u14") })
    }

    @Test
    fun `original database projection messages are not mutated`() = runBlocking {
        val messages = history()
        val copy = messages.toList()
        AutoCompactionEngine.prepare(1, messages, 1024, 384, policy, null) { _, _, _ -> "runtime" }
        assertEquals(copy, messages)
        assertFalse(messages.any { it.content == "runtime" })
    }

    @Test
    fun `existing compact memory participates below the trigger`() = runBlocking {
        val existing = AutoCompactMemory(1, "old compact", 13L, 1L, 2)
        val result = AutoCompactionEngine.prepare(1, history(2, 10), 8192, 384, policy, existing) { _, _, _ ->
            error("compact must not run below the trigger")
        }
        assertTrue(result.promptMessages.any { it.content == "[FUSION_AUTO_COMPACT]\nold compact" })
        assertFalse(result.promptMessages.any { it.content.startsWith("u0") })
        assertFalse(result.promptMessages.any { it.content.startsWith("a0") })
        assertTrue(result.promptMessages.any { it.content == "current" })
        assertEquals(1, result.promptMessages.count { it.content.startsWith("[FUSION_AUTO_COMPACT]") })
        assertEquals(existing, result.memory)
        assertFalse(result.compactConsidered)
    }

    @Test
    fun `existing compact memory below projected trigger needs no new compact`() = runBlocking {
        val existing = AutoCompactMemory(1, "old compact", 25L, 1L, 6)
        val result = AutoCompactionEngine.prepare(1, history(8, 2000), 8192, 384, policy, existing) { _, _, _ ->
            error("projected context is below the trigger")
        }
        assertFalse(result.compactConsidered)
        assertFalse(result.fallbackTrimmed)
        assertTrue(result.promptMessages.any { it.content == "[FUSION_AUTO_COMPACT]\nold compact" })
        assertFalse(result.promptMessages.any { it.content.startsWith("u0") })
        assertFalse(result.promptMessages.any { it.content.startsWith("a0") })
        assertEquals(1, result.promptMessages.count { it.content.startsWith("[FUSION_AUTO_COMPACT]") })
        assertEquals(existing, result.memory)
    }

    @Test
    fun `disabled automatic compact keeps existing projection without creating a new memory`() = runBlocking {
        val existing = AutoCompactMemory(1, "dormant compact", 13L, 1L, 2)
        val result = AutoCompactionEngine.prepare(
            conversationId = 1,
            messages = history(4, 20),
            effectiveCapacityTokens = 1024,
            requiredOutputTokens = 384,
            policy = policy.copy(enabled = false),
            existingMemory = existing,
            summarize = { _, _, _ -> error("disabled compact must not run") },
        )
        assertTrue(result.promptMessages.any { it.content.startsWith("[FUSION_AUTO_COMPACT]") })
        assertFalse(result.promptMessages.any { it.content.startsWith("u0") })
        assertEquals(existing, result.memory)
        assertFalse(result.compactConsidered)
    }

    @Test
    fun `existing projection below trigger does not call summarizer`() = runBlocking {
        val existing = AutoCompactMemory(1, "stable compact", 25L, 1L, 6)
        var calls = 0
        val result = AutoCompactionEngine.prepare(
            conversationId = 1,
            messages = history(8, 2000),
            effectiveCapacityTokens = 8192,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            summarize = { _, _, _ ->
                calls++
                "unexpected"
            },
        )
        assertEquals(0, calls)
        assertFalse(result.compactConsidered)
        assertTrue(result.promptMessages.any { it.content == "[FUSION_AUTO_COMPACT]\nstable compact" })
    }

    @Test
    fun `recent history policy change invalidates existing compact memory`() = runBlocking {
        val existing = AutoCompactMemory(1, "old policy compact", 13L, 1L, 2)
        var previousSummary: String? = "not-called"
        val result = AutoCompactionEngine.prepare(
            conversationId = 1,
            messages = history(8, 2000),
            effectiveCapacityTokens = 8192,
            requiredOutputTokens = 384,
            policy = policy.copy(recentHistoryPercent = 50),
            existingMemory = existing,
            summarize = { _, previous, _ ->
                previousSummary = previous
                "rebuilt for new recent policy"
            },
        )
        assertEquals(null, previousSummary)
        assertFalse(result.promptMessages.any { it.content.contains("old policy compact") })
        assertEquals(50, result.memory?.recentHistoryPercent)
    }

    @Test
    fun `existing compact memory survives summary failure`() = runBlocking {
        val existing = AutoCompactMemory(1, "old compact", 13L, 1L, 2)
        val result = AutoCompactionEngine.prepare(1, history(8, 2000), 4096, 384, policy, existing) { _, _, _ -> null }
        assertTrue(result.compactConsidered)
        assertFalse(result.compactUsed)
        assertTrue(result.promptMessages.any { it.content == "[FUSION_AUTO_COMPACT]\nold compact" })
        assertFalse(result.promptMessages.any { it.content.startsWith("u0") })
        assertFalse(result.promptMessages.any { it.content.startsWith("a0") })
        assertEquals(1, result.promptMessages.count { it.content.startsWith("[FUSION_AUTO_COMPACT]") })
        assertEquals(existing, result.memory)
    }

    @Test
    fun `incremental compact uses only turns beyond the boundary`() = runBlocking {
        // Use larger turn content so the trigger fires against a small capacity
        // even after the AUTO_COMPACT system prompt is included in the batch.
        val messages = history(turns = 8, size = 500)
        val existing = AutoCompactMemory(1, "old compact", 13L, 1L, 2)
        val previousSummaries = mutableListOf<String?>()
        val newTurns = mutableListOf<AutoCompactTurn>()
        val result = AutoCompactionEngine.prepare(1, messages, 2048, 384, policy, existing) { _, previous, turns ->
            previousSummaries += previous
            newTurns += turns
            "updated compact"
        }
        assertTrue(previousSummaries.isNotEmpty())
        // First batch must receive the existing compact memory as previous summary
        assertEquals("old compact", previousSummaries.first())
        // Subsequent batches may receive the running summary updated by prior batches.
        previousSummaries.drop(1).forEach { prev ->
            assertEquals("updated compact", prev)
        }
        // Every accepted batch must consist of turns strictly past the previous boundary
        assertTrue(newTurns.all { it.boundaryMessageId > 13L })
        assertEquals("updated compact", result.memory?.summary)
        assertFalse(result.promptMessages.any { it.content.startsWith("u0") })
        assertFalse(result.promptMessages.any { it.content.startsWith("a0") })
        assertTrue(result.promptMessages.any { it.content.startsWith("u7") })
        assertEquals(1, result.promptMessages.count { it.content.startsWith("[FUSION_AUTO_COMPACT]") })
    }

    @Test
    fun `incremental batches serialize the prior replacement summary into every later request`() = runBlocking {
        val messages = history(turns = 20, size = 500)
        val existing = AutoCompactMemory(1, "summary seed", 13L, 1L, 2)
        val serializedRequests = mutableListOf<String>()
        val previousSummaries = mutableListOf<String?>()
        var callIndex = 0

        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            summarize = { request, previous, turns ->
                val prepared = AutoCompactionEngine.prepareSummaryRequest(
                    previousSummary = previous,
                    turns = turns,
                    effectiveCapacityTokens = 2048,
                    request = request,
                ) ?: error("production-selected compact batch must be serializable")
                serializedRequests += buildSystemInstruction(prepared.messages) + "\n" +
                    buildPrompt(prepared.messages)
                previousSummaries += previous
                "summary ${('A'.code + callIndex++).toChar()}"
            },
        )

        assertTrue("the bounded input must require at least three batches", serializedRequests.size >= 3)
        assertEquals("summary seed", previousSummaries.first())
        assertTrue(serializedRequests.first().contains("EXISTING COMPACT SUMMARY:\nsummary seed"))
        assertTrue(serializedRequests[1].contains("summary A"))
        assertTrue(serializedRequests[2].contains("summary B"))
        assertEquals("summary ${('A'.code + callIndex - 1).toChar()}", result.memory?.summary)
    }

    @Test
    fun `compact boundary follows semantic order when message ids are non monotonic`() = runBlocking {
        fun nonMonotonicHistory(extraTurn: Boolean = false): List<AutoCompactMessage> = buildList {
            add(AutoCompactMessage(1L, "system", "policy"))
            add(AutoCompactMessage(10L, "user", "u1" + "x".repeat(2000)))
            add(AutoCompactMessage(100L, "assistant", "a1" + "y".repeat(2000)))
            add(AutoCompactMessage(12L, "user", "u2" + "x".repeat(2000)))
            add(AutoCompactMessage(13L, "assistant", "a2" + "y".repeat(2000)))
            add(AutoCompactMessage(14L, "user", "u3" + "x".repeat(2000)))
            add(AutoCompactMessage(15L, "assistant", "a3" + "y".repeat(2000)))
            add(AutoCompactMessage(16L, "user", "u4" + "x".repeat(2000)))
            add(AutoCompactMessage(17L, "assistant", "a4" + "y".repeat(2000)))
            if (extraTurn) {
                add(AutoCompactMessage(18L, "user", "u5" + "x".repeat(2000)))
                add(AutoCompactMessage(19L, "assistant", "a5" + "y".repeat(2000)))
            }
            add(AutoCompactMessage(if (extraTurn) 20L else 18L, "user", "current"))
        }

        val branch = listOf(10L, 100L, 12L, 13L, 14L, 15L, 16L, 17L)
        val summarizedBoundaries = mutableListOf<Long>()
        val first = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = nonMonotonicHistory(),
            effectiveCapacityTokens = 4096,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            branchMessageIds = branch,
            summarize = { _, _, turns ->
                summarizedBoundaries += turns.map(AutoCompactTurn::boundaryMessageId)
                "summary"
            },
        )

        assertEquals(listOf(100L, 13L), summarizedBoundaries)
        assertEquals(13L, first.memory?.compactedThroughMessageId)
        assertTrue(first.promptMessages.any { it.content.startsWith("u3") })
        assertFalse(first.promptMessages.any { it.content.startsWith("u1") })
        assertFalse(first.promptMessages.any { it.content.startsWith("u2") })

        val laterTurn = mutableListOf<Long>()
        val appended = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = nonMonotonicHistory(extraTurn = true),
            effectiveCapacityTokens = 4096,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = first.memory,
            branchMessageIds = branch + listOf(18L, 19L),
            summarize = { _, _, turns ->
                laterTurn += turns.map(AutoCompactTurn::boundaryMessageId)
                "updated"
            },
        )
        assertEquals(listOf(15L), laterTurn)
        assertFalse(appended.promptMessages.any { it.content.startsWith("u3") })
        assertTrue(appended.promptMessages.any { it.content.startsWith("u4") })

        val earlierMutation = branch.toMutableList().apply { this[1] = 101L }
        var reusedSummary: String? = "not-called"
        AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = nonMonotonicHistory(),
            effectiveCapacityTokens = 4096,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = first.memory,
            branchMessageIds = earlierMutation,
            summarize = { _, previous, _ ->
                reusedSummary = previous
                "rebuilt"
            },
        )
        assertEquals(null, reusedSummary)

        val laterMutation = branch.toMutableList().apply { this[5] = 150L }
        val stillValid = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = nonMonotonicHistory(),
            effectiveCapacityTokens = 4096,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = first.memory,
            branchMessageIds = laterMutation,
            summarize = { _, _, _ -> error("later branch mutation must not rebuild prefix") },
        )
        assertEquals(first.memory, stillValid.memory)
    }

    @Test
    fun `summary failure falls back to whole-turn trim and continues`() = runBlocking {
        val result = AutoCompactionEngine.prepare(1, history(), 1024, 384, policy, null) { _, _, _ -> null }
        assertTrue(result.compactConsidered)
        assertFalse(result.compactUsed)
        assertTrue(result.fallbackTrimmed)
        assertEquals("current", result.promptMessages.last().content)
        val completedMessages = result.promptMessages
            .filter { it.role != "system" }
            .dropLast(1)
        assertEquals(0, completedMessages.size % 2)
        assertTrue(completedMessages.chunked(2).all { pair ->
            pair.size == 2 && pair[0].role == "user" && pair[1].role == "assistant"
        })
    }

    @Test
    fun `fallback path reports false when every raw turn still fits`() = runBlocking {
        val lowTriggerPolicy = policy.copy(triggerPercent = 60)
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = history(turns = 2, size = 10),
            effectiveCapacityTokens = 700,
            requiredOutputTokens = 384,
            policy = lowTriggerPolicy,
            existingMemory = null,
            summarize = { _, _, _ -> null },
        )

        assertTrue(result.compactConsidered)
        assertFalse(result.compactUsed)
        assertFalse("fallbackTrimmed must mean an actual omitted raw turn", result.fallbackTrimmed)
        assertEquals("current", result.promptMessages.last().content)
    }

    @Test
    fun `long compact input is summarized in KV bounded oldest first batches`() = runBlocking {
        val capacity = 2048
        val messages = history(turns = 20, size = 500)
        val batchSizes = mutableListOf<Int>()
        val batchInputs = mutableListOf<Int>()
        val result = AutoCompactionEngine.prepare(
            conversationId = 1,
            messages = messages,
            effectiveCapacityTokens = capacity,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            summarize = { request, previous, turns ->
                batchSizes += turns.size
                val prepared = AutoCompactionEngine.prepareSummaryRequest(
                    previousSummary = previous,
                    turns = turns,
                    effectiveCapacityTokens = capacity,
                    request = request,
                ) ?: error("bounded summary preparation must accept the selected batch")
                batchInputs += PromptTokenEstimator.estimateTokens(prepared.messages) +
                    prepared.conversationOptions.maxOutputToken!! + 32
                turns.forEach { turn ->
                    assertTrue(prepared.messages.any { it.content == turn.user.content })
                    assertTrue(prepared.messages.any { it.content == turn.assistant.content })
                }
                assertEquals(384, prepared.conversationOptions.maxOutputToken)
                "summary-${batchSizes.size}"
            },
        )

        assertTrue(batchSizes.size > 1)
        assertTrue(batchSizes.all { it > 0 })
        assertTrue(batchInputs.all { it <= capacity })
        assertTrue(result.compactUsed)
        assertEquals(39L, result.memory?.compactedThroughMessageId)
    }

    @Test
    fun `shared local compaction preparation returns compact diagnostics`() = runBlocking {
        val rawMessages = listOf(
            ChatMessage("system", "policy"),
            ChatMessage("user", "old-user"),
            ChatMessage("assistant", "old-assistant"),
            ChatMessage("user", "current"),
        )
        val result = prepareLocalAutoCompaction(
            conversationId = 1L,
            rawMessages = rawMessages,
            historyMessageIds = listOf(10L, 11L),
            settings = GenerationSettings(maxTokens = 384, kvCacheCapacityTokens = 4096),
            compactPolicy = LocalCompactPolicy(enabled = false),
            existingMemory = null,
            summarize = { _, _, _ -> error("disabled compaction must not summarize") },
        )
        assertEquals(rawMessages, result.promptMessages)
        assertFalse(result.compactConsidered)
        assertFalse(result.compactUsed)
        assertFalse(result.fallbackTrimmed)
    }

    @Test
    fun `compact callback serialized prompt contains every accepted source turn`() = runBlocking {
        val messages = history(turns = 10, size = 1000)
        val serializedBatches = mutableListOf<Pair<String, List<String>>>()
        assertTrue(AutoCompactionEngine.shouldTrigger(messages, 4608, 384, policy))

        AutoCompactionEngine.prepare(
            conversationId = 1,
            messages = messages,
            effectiveCapacityTokens = 4608,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            summarize = { _, previousSummary, turns ->
                val serialized = buildPrompt(buildList {
                    add(ChatMessage("system", AutoCompactionEngine.AUTO_COMPACT_SYSTEM_PROMPT))
                    previousSummary?.let { add(ChatMessage("system", it)) }
                    addAll(turns.flatMap(AutoCompactTurn::messages).map(AutoCompactMessage::toChatMessage))
                })
                serializedBatches += serialized to turns.map { it.user.content }
                turns.joinToString("|") { it.user.content }
            },
        )

        val acceptedUsers = serializedBatches.flatMap { it.second }
        assertEquals(7, acceptedUsers.distinct().size)
        serializedBatches.forEach { (prompt, sourceUsers) ->
            sourceUsers.forEach { sourceUser -> assertTrue(prompt.contains(sourceUser)) }
        }
    }

    @Test
    fun `branch change invalidates old compact memory`() = runBlocking {
        val branchIds = (10L..25L).toList()
        val existing = AutoCompactMemory(
            conversationId = 1,
            summary = "branch A",
            compactedThroughMessageId = 13L,
            updatedAt = 1L,
            sourceTurnCount = 2,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(branchIds, 13L),
        )
        val changedPrefix = branchIds.toMutableList().apply { this[2] = 30L }
        var previousSummary: String? = "not-called"
        val result = AutoCompactionEngine.prepare(
            conversationId = 1,
            messages = history(8, 2000),
            effectiveCapacityTokens = 8192,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            branchMessageIds = changedPrefix,
            summarize = { _, previous, _ ->
                previousSummary = previous
                "branch B"
            },
        )
        assertNull(previousSummary)
        assertTrue(result.promptMessages.any { it.content == "[FUSION_AUTO_COMPACT]\nbranch B" })
        assertFalse(result.promptMessages.any { it.content.contains("branch A") })
        assertEquals(
            AutoCompactionBranchIdentity.fromMessageIds(changedPrefix, result.memory!!.compactedThroughMessageId),
            result.memory?.branchFingerprint,
        )
    }

    @Test
    fun `compact memory remains valid when later turns are appended`() = runBlocking {
        val initialBranch = (10L..17L).toList()
        val existing = AutoCompactMemory(
            conversationId = 1,
            summary = "prefix summary",
            compactedThroughMessageId = 13L,
            updatedAt = 1L,
            sourceTurnCount = 2,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(initialBranch, 13L),
        )
        val result = AutoCompactionEngine.prepare(
            conversationId = 1,
            messages = history(6, 10),
            effectiveCapacityTokens = 8192,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            branchMessageIds = (10L..21L).toList(),
            summarize = { _, _, _ -> error("append must not rebuild the prefix") },
        )
        assertEquals(existing, result.memory)
        assertTrue(result.promptMessages.any { it.content == "[FUSION_AUTO_COMPACT]\nprefix summary" })
    }

    @Test
    fun `response change after compact boundary does not invalidate memory`() = runBlocking {
        val branchIds = (10L..17L).toList()
        val existing = AutoCompactMemory(
            conversationId = 1,
            summary = "prefix summary",
            compactedThroughMessageId = 13L,
            updatedAt = 1L,
            sourceTurnCount = 2,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(branchIds, 13L),
        )
        val changedLaterBranch = branchIds.toMutableList().apply { this[5] = 30L }
        val result = AutoCompactionEngine.prepare(
            conversationId = 1,
            messages = history(4, 10),
            effectiveCapacityTokens = 8192,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            branchMessageIds = changedLaterBranch,
            summarize = { _, _, _ -> error("later branch changes must not rebuild the prefix") },
        )
        assertEquals(existing, result.memory)
        assertTrue(result.promptMessages.any { it.content == "[FUSION_AUTO_COMPACT]\nprefix summary" })
    }

    @Test
    fun `host markers do not change trigger semantics`() {
        val base = history(4, 1000)
        val withMarkers = listOf(
            AutoCompactMessage(2, "system", "FUSION_GENERATION_SETTINGS\n" + "x".repeat(10000)),
            AutoCompactMessage(3, "system", "FUSION_MODEL_FAMILY=GEMMA"),
        ) + base
        assertEquals(
            AutoCompactionEngine.shouldTrigger(base, 8192, 384, policy),
            AutoCompactionEngine.shouldTrigger(withMarkers, 8192, 384, policy),
        )
    }
}
