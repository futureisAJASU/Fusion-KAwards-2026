package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.model.GenerationSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualAutoCompactionTest {

    private val enabled = LocalCompactPolicy(
        enabled = true,
        triggerPercent = 80,
        recentHistoryPercent = 25,
    )

    private fun branch(turns: Int = 6, size: Int = 20): List<AutoCompactMessage> = buildList {
        var id = 1L
        repeat(turns) { index ->
            add(AutoCompactMessage(id++, "user", "user-$index" + "u".repeat(size)))
            add(AutoCompactMessage(id++, "assistant", "assistant-$index" + "a".repeat(size)))
        }
        add(AutoCompactMessage(id, "user", "current"))
    }

    private fun compact(
        messages: List<AutoCompactMessage>,
        policy: LocalCompactPolicy = enabled,
        existing: AutoCompactMemory? = null,
        trigger: AutoCompactionTrigger = AutoCompactionTrigger.MANUAL,
        capacity: Int = 4096,
        summarize: suspend (AutoCompactGenerationRequest, String?, List<AutoCompactTurn>) -> String? =
            { _, _, _ -> "replacement summary" },
    ) = runBlocking {
        AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = capacity,
            requiredOutputTokens = 128,
            policy = policy,
            existingMemory = existing,
            branchMessageIds = messages.filter { it.role != "system" }.map { it.id },
            trigger = trigger,
            summarize = summarize,
        )
    }

    @Test
    fun `automatic enabled over threshold still compacts`() {
        val result = compact(
            messages = branch(size = 800),
            trigger = AutoCompactionTrigger.AUTOMATIC,
            capacity = 2048,
        )

        assertTrue(result.compactConsidered)
        assertTrue(result.compactUsed)
        assertNotNull(result.memory)
    }

    @Test
    fun `automatic enabled below threshold does not create memory`() {
        val calls = intArrayOf(0)
        val result = compact(
            messages = branch(),
            trigger = AutoCompactionTrigger.AUTOMATIC,
            summarize = { _, _, _ -> calls[0]++; "must not run" },
        )

        assertFalse(result.compactUsed)
        assertFalse(result.compactConsidered)
        assertEquals(0, calls[0])
    }

    @Test
    fun `automatic disabled does not create memory but valid memory remains projected`() {
        val first = compact(branch(), capacity = 4096)
        val existing = first.memory
        assertNotNull(existing)
        val disabled = compact(
            messages = branch(),
            policy = enabled.copy(enabled = false),
            existing = existing,
            trigger = AutoCompactionTrigger.AUTOMATIC,
            summarize = { _, _, _ -> error("automatic OFF must not summarize") },
        )

        assertFalse(disabled.compactUsed)
        assertEquals(existing, disabled.memory)
        assertTrue(disabled.promptMessages.any { it.content.startsWith("[FUSION_AUTO_COMPACT]") })
        assertTrue(disabled.promptMessages.any { it.content == "current" })
    }

    @Test
    fun `manual disabled below threshold can compact`() {
        val result = compact(
            messages = branch(),
            policy = enabled.copy(enabled = false),
            trigger = AutoCompactionTrigger.MANUAL,
        )

        assertTrue(result.compactUsed)
        assertNotNull(result.memory)
    }

    @Test
    fun `manual enabled below automatic threshold can compact`() {
        val result = compact(branch(), trigger = AutoCompactionTrigger.MANUAL)

        assertTrue(result.compactUsed)
    }

    @Test
    fun `summary failure is distinct from a genuine no eligible prefix`() {
        val failed = compact(
            messages = branch(),
            summarize = { _, _, _ -> null },
        )
        assertTrue(failed.compactConsidered)
        assertFalse(failed.compactUsed)
        assertTrue(failed.summaryFailed)

        val noEligible = compact(messages = branch(turns = 2))
        assertTrue(noEligible.compactConsidered)
        assertFalse(noEligible.compactUsed)
        assertFalse(noEligible.summaryFailed)
    }

    @Test
    fun `prepared local outcome distinguishes summary failure from no eligible prefix`() = runBlocking {
        val raw = branch(turns = 6).map { it.toChatMessage() }
        val failed = prepareLocalAutoCompaction(
            conversationId = 1L,
            rawMessages = raw,
            historyMessageIds = raw.filter { it.role != "system" }.indices.map { (it + 1).toLong() },
            settings = GenerationSettings(maxTokens = 128, kvCacheCapacityTokens = 4096),
            compactPolicy = enabled,
            existingMemory = null,
            trigger = AutoCompactionTrigger.MANUAL,
            summarize = { _, _, _ -> null },
        )
        assertEquals(LocalAutoCompactionOutcome.SUMMARY_FAILED, failed.outcome)

        val noEligibleRaw = branch(turns = 2).map { it.toChatMessage() }
        val noEligible = prepareLocalAutoCompaction(
            conversationId = 1L,
            rawMessages = noEligibleRaw,
            historyMessageIds = noEligibleRaw.filter { it.role != "system" }.indices.map { (it + 1).toLong() },
            settings = GenerationSettings(maxTokens = 128, kvCacheCapacityTokens = 4096),
            compactPolicy = enabled,
            existingMemory = null,
            trigger = AutoCompactionTrigger.MANUAL,
            summarize = { _, _, _ -> error("no eligible prefix must not summarize") },
        )
        assertEquals(LocalAutoCompactionOutcome.NO_ELIGIBLE_PREFIX, noEligible.outcome)
    }

    @Test
    fun `manual compaction keeps the recent completed turns raw`() {
        val messages = branch(turns = 6)
        val result = compact(messages)

        assertTrue(result.compactUsed)
        assertTrue(result.promptMessages.any { it.content.startsWith("user-4") })
        assertTrue(result.promptMessages.any { it.content.startsWith("assistant-4") })
        assertTrue(result.promptMessages.any { it.content.startsWith("user-5") })
        assertTrue(result.promptMessages.any { it.content.startsWith("assistant-5") })
    }

    @Test
    fun `manual compaction preserves a verbatim barrier and cannot cross it`() {
        val messages = listOf(
            AutoCompactMessage(1, "user", "u1"),
            AutoCompactMessage(2, "assistant", "a1"),
            AutoCompactMessage(3, "user", "orphan"),
            AutoCompactMessage(4, "user", "u2"),
            AutoCompactMessage(5, "assistant", "a2"),
            AutoCompactMessage(6, "user", "u3"),
            AutoCompactMessage(7, "assistant", "a3"),
            AutoCompactMessage(8, "user", "current"),
        )
        val result = compact(messages)
        val memory = result.memory

        assertTrue(result.compactUsed)
        assertEquals(2L, memory?.compactedThroughMessageId)
        assertTrue(result.promptMessages.any { it.content == "orphan" })
        assertTrue(result.promptMessages.any { it.content == "u2" })
        assertFalse(memory?.summary.orEmpty().contains("u2"))
    }

    @Test
    fun `manual compaction never spans multiple verbatim barriers`() {
        val messages = listOf(
            AutoCompactMessage(1, "user", "u1"),
            AutoCompactMessage(2, "assistant", "a1"),
            AutoCompactMessage(3, "user", "orphan user"),
            AutoCompactMessage(4, "user", "u2"),
            AutoCompactMessage(5, "assistant", "a2"),
            AutoCompactMessage(6, "assistant", "orphan assistant"),
            AutoCompactMessage(7, "user", "u3"),
            AutoCompactMessage(8, "assistant", "a3"),
            AutoCompactMessage(9, "user", "current"),
        )
        val result = compact(messages)

        assertTrue(result.compactUsed)
        assertEquals(2L, result.memory?.compactedThroughMessageId)
        assertFalse(result.memory!!.summary.contains("u2"))
        assertTrue(result.promptMessages.any { it.content == "orphan user" })
        assertTrue(result.promptMessages.any { it.content == "orphan assistant" })
    }

    @Test
    fun `marker-looking and empty verbatim payloads remain exact`() {
        val emptyMessages = listOf(
            AutoCompactMessage(1, "user", ""),
            AutoCompactMessage(2, "user", "u1"),
            AutoCompactMessage(3, "assistant", "a1"),
            AutoCompactMessage(4, "user", "u2"),
            AutoCompactMessage(5, "assistant", "a2"),
            AutoCompactMessage(6, "user", "u3"),
            AutoCompactMessage(7, "assistant", "a3"),
            AutoCompactMessage(8, "user", "current"),
        )
        val result = compact(
            emptyMessages,
            summarize = { _, _, _ ->
                "[FUSION_AUTO_COMPACT_REPRESENTATION]\n" +
                    "[COMPACTED_COMPLETE_TURNS]\n" +
                    "[VERBATIM_USER]\n" +
                    "[VERBATIM_ASSISTANT]"
            },
        )

        assertTrue(result.compactUsed)
        assertTrue(result.memory!!.summary.contains("[FUSION_AUTO_COMPACT_PAYLOAD_LENGTH=0]"))
        assertTrue(result.memory!!.summary.contains("[VERBATIM_USER]\n\n"))
        assertTrue(result.memory!!.summary.contains("[VERBATIM_ASSISTANT]"))
    }

    @Test
    fun `orphan between complete turns remains in chronological projection`() {
        val messages = listOf(
            AutoCompactMessage(1, "user", "u1"),
            AutoCompactMessage(2, "assistant", "a1"),
            AutoCompactMessage(3, "user", "orphan"),
            AutoCompactMessage(4, "user", "u2"),
            AutoCompactMessage(5, "assistant", "a2"),
            AutoCompactMessage(6, "user", "u3"),
            AutoCompactMessage(7, "assistant", "a3"),
            AutoCompactMessage(8, "user", "current"),
        )
        val result = compact(messages)
        val promptText = result.promptMessages.joinToString("\n") { it.content }

        assertTrue(promptText.indexOf("orphan") >= 0)
        assertTrue(promptText.indexOf("u2") > promptText.indexOf("orphan"))
        assertTrue(promptText.indexOf("current") > promptText.indexOf("u2"))
    }

    @Test
    fun `second generation preserves verbatim content and does not pass markers to llm`() {
        val firstMessages = listOf(
            AutoCompactMessage(1, "user", "orphan [COMPACTED_COMPLETE_TURNS]"),
            AutoCompactMessage(2, "user", "u1"),
            AutoCompactMessage(3, "assistant", "a1"),
            AutoCompactMessage(4, "user", "u2"),
            AutoCompactMessage(5, "assistant", "a2"),
            AutoCompactMessage(6, "user", "u3"),
            AutoCompactMessage(7, "assistant", "a3"),
            AutoCompactMessage(8, "user", "u4"),
            AutoCompactMessage(9, "assistant", "a4"),
            AutoCompactMessage(10, "user", "current"),
        )
        val first = compact(firstMessages)
        val firstMemory = first.memory
        assertNotNull(firstMemory)
        assertTrue(firstMemory!!.summary.contains("orphan [COMPACTED_COMPLETE_TURNS]"))

        var previousSummary: String? = null
        val secondMessages = firstMessages + listOf(
            AutoCompactMessage(11, "user", "u5"),
            AutoCompactMessage(12, "assistant", "a5"),
            AutoCompactMessage(13, "user", "u6"),
            AutoCompactMessage(14, "assistant", "a6"),
            AutoCompactMessage(15, "user", "current-2"),
        )
        val second = compact(
            messages = secondMessages,
            existing = firstMemory,
            summarize = { _, old, _ ->
                previousSummary = old
                "replacement without verbatim markers"
            },
        )

        assertTrue(second.compactUsed)
        assertFalse(previousSummary.orEmpty().contains("[VERBATIM_USER]"))
        assertTrue(second.memory!!.summary.contains("orphan [COMPACTED_COMPLETE_TURNS]"))
    }

    @Test
    fun `stale represented prefix is rejected before adoption`() = runBlocking {
        val messages = branch()
        val prepared = compact(messages)
        val memory = prepared.memory!!
        val changed = messages.map { message ->
            if (message.id == 1L) message.copy(content = "changed") else message
        }
        var persisted = false
        val adopted = adoptPreparedLocalAutoCompaction(
            preparation = PreparedLocalAutoCompaction(
                promptMessages = emptyList(),
                memory = memory,
                compactConsidered = true,
                compactUsed = true,
                fallbackTrimmed = false,
            ),
            persist = { persisted = true; true },
            fallback = { PreparedLocalAutoCompaction(emptyList(), null, false, false, false) },
            isCurrentBranchValid = {
                isAutoCompactMemoryCurrentForBranch(
                    memory = it,
                    conversationId = 1L,
                    capturedBranch = messages,
                    currentBranch = changed,
                    recentHistoryPercent = enabled.recentHistoryPercent,
                )
            },
        )

        assertFalse(persisted)
        assertFalse(adopted.compactUsed)
        assertEquals(null, adopted.memory)
        assertTrue(adopted.adoptionFailed)
    }

    @Test
    fun `compatible append after the represented prefix remains valid`() {
        val messages = branch()
        val prepared = compact(messages)
        val memory = prepared.memory!!
        val appended = messages + listOf(
            AutoCompactMessage(14, "user", "later user"),
            AutoCompactMessage(15, "assistant", "later answer"),
        )

        assertTrue(
            isAutoCompactMemoryCurrentForBranch(
                memory = memory,
                conversationId = 1L,
                capturedBranch = messages,
                currentBranch = appended,
                recentHistoryPercent = enabled.recentHistoryPercent,
            ),
        )
    }

    @Test
    fun `manual gate admits one operation and releases after completion`() = runBlocking {
        val gate = ManualCompactionOperationGate()
        assertTrue(gate.tryAcquire())
        val second = async {
            gate.tryAcquire()
        }
        delay(10)
        assertFalse(second.await())
        gate.release()
        assertTrue(gate.tryAcquire())
        gate.release()
    }
}
