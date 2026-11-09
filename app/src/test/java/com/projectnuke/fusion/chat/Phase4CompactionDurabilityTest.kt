package com.projectnuke.fusion.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.test.PlatformSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 cross-system adversarial tests: conversation-state identity and
 * compaction durability across the retry/regenerate/switch seams.
 */
class Phase4CompactionDurabilityTest {

    private class TestContext(
        private val prefs: PlatformSharedPreferences = PlatformSharedPreferences(),
    ) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    private val policy = LocalCompactPolicy(enabled = true, triggerPercent = 80, recentHistoryPercent = 25)

    private fun history(turns: Int = 10, size: Int = 400): List<AutoCompactMessage> = buildList {
        add(AutoCompactMessage(1L, "system", "policy"))
        var id = 10L
        repeat(turns) { index ->
            add(AutoCompactMessage(id++, "user", "request-$index " + "x".repeat(size)))
            add(AutoCompactMessage(id++, "assistant", "answer-$index " + "y".repeat(size)))
        }
        add(AutoCompactMessage(id, "user", "current request"))
    }

    private fun branchIdsOf(messages: List<AutoCompactMessage>): List<Long> =
        messages.filter { it.role != "system" && it.id != messages.last().id }.map { it.id }

    // ?€?€ Durable compaction boundary monotonicity ?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€?€

    @Test
    fun `stale compaction write cannot regress the durable boundary`() {
        val store = AutoCompactMemoryStore(TestContext())
        val conversationId = 7L
        assertTrue(store.save(AutoCompactMemory(conversationId, "newer summary", 200L, 2000L, 12)))

        // A late/older compaction result (smaller boundary, older timestamp)
        // must never overwrite the newer durable boundary.
        assertFalse(store.save(AutoCompactMemory(conversationId, "stale summary", 100L, 1000L, 5)))

        val loaded = store.load(conversationId)
        assertNotNull(loaded)
        assertEquals(200L, loaded!!.compactedThroughMessageId)
        assertEquals("newer summary", loaded.summary)
    }

    @Test
    fun `same boundary re-save refreshes the persisted summary`() {
        val store = AutoCompactMemoryStore(TestContext())
        val conversationId = 7L
        assertTrue(store.save(AutoCompactMemory(conversationId, "first", 200L, 1000L, 5)))
        assertTrue(store.save(AutoCompactMemory(conversationId, "second", 200L, 2000L, 6)))
        assertEquals("second", store.load(conversationId)?.summary)
    }

    @Test
    fun `a newer boundary always replaces an older durable boundary`() {
        val store = AutoCompactMemoryStore(TestContext())
        val conversationId = 7L
        assertTrue(store.save(AutoCompactMemory(conversationId, "older", 100L, 1000L, 5)))
        assertTrue(store.save(AutoCompactMemory(conversationId, "newer", 300L, 2000L, 9)))
        assertEquals(300L, store.load(conversationId)?.compactedThroughMessageId)
    }

    @Test
    fun `failed incremental summarization keeps the previous valid memory`() = runBlocking {
        val messages = history()
        val branchIds = branchIdsOf(messages)
        // Boundary must be an assistant (boundary) id inside the eligible pool.
        val existingBoundary = branchIds[11]
        val existing = AutoCompactMemory(
            conversationId = 1L,
            summary = "Decisions: use model A.",
            compactedThroughMessageId = existingBoundary,
            updatedAt = 500L,
            sourceTurnCount = 4,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(branchIds, existingBoundary),
        )
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 8192,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            branchMessageIds = branchIds,
            trigger = AutoCompactionTrigger.MANUAL,
            summarize = { _, _, _ -> null },
        )
        assertTrue(result.summaryFailed)
        assertEquals(existingBoundary, result.memory?.compactedThroughMessageId)
        assertEquals("Decisions: use model A.", result.memory?.summary)
    }

    @Test
    fun `incremental compaction update input carries the previous compacted decisions`() = runBlocking {
        val messages = history()
        val branchIds = branchIdsOf(messages)
        // Boundary must be an assistant (boundary) id inside the eligible pool.
        val existingBoundary = branchIds[11]
        val existing = AutoCompactMemory(
            conversationId = 1L,
            summary = "Decisions: use model A. Do not use model C.",
            compactedThroughMessageId = existingBoundary,
            updatedAt = 500L,
            sourceTurnCount = 4,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(branchIds, existingBoundary),
        )
        var seenPrevious: String? = null
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 8192,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            branchMessageIds = branchIds,
            trigger = AutoCompactionTrigger.MANUAL,
            summarize = { _, previous, _ ->
                seenPrevious = previous
                "Decisions: use model A. Do not use model C. Use model B."
            },
        )
        assertTrue(result.compactUsed)
        assertNotNull(seenPrevious)
        assertTrue(
            "previous summary input must retain prior decisions: ${seenPrevious.orEmpty()}",
            seenPrevious!!.contains("use model A"),
        )
        assertTrue(result.memory!!.summary.contains("use model A"))
    }

    @Test
    fun `compacted summary is projected before post-boundary current turns`() = runBlocking {
        val messages = history()
        val branchIds = branchIdsOf(messages)
        // Boundary must be an assistant (boundary) id inside the eligible pool.
        val existingBoundary = branchIds[11]
        val existing = AutoCompactMemory(
            conversationId = 1L,
            summary = "Stale compacted summary: old value.",
            compactedThroughMessageId = existingBoundary,
            updatedAt = 500L,
            sourceTurnCount = 4,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(branchIds, existingBoundary),
        )
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 8192,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            branchMessageIds = branchIds,
            trigger = AutoCompactionTrigger.MANUAL,
            summarize = { _, _, _ -> "Stale compacted summary: old value." },
        )
        val projected = result.promptMessages
        val memoryIndex = projected.indexOfFirst { it.role == "system" && it.content.startsWith("[FUSION_AUTO_COMPACT]") }
        val lastTurnIndex = projected.indexOfLast { it.role != "system" && it.id != Long.MAX_VALUE }
        assertTrue(memoryIndex >= 0)
        assertTrue(
            "current evidence must be projected after the compacted summary",
            memoryIndex < lastTurnIndex,
        )
        assertTrue(projected[memoryIndex].content.contains("Stale compacted summary: old value."))
    }

    @Test
    fun `branch fingerprint is a strict prefix identity`() {
        val ids = listOf(10L, 11L, 12L, 13L)
        assertEquals(
            AutoCompactionBranchIdentity.fromMessageIds(listOf(10L, 11L)),
            AutoCompactionBranchIdentity.fromMessageIds(ids, 11L),
        )
        assertNotEquals(
            AutoCompactionBranchIdentity.fromMessageIds(ids, 11L),
            AutoCompactionBranchIdentity.fromMessageIds(ids, 12L),
        )
        // Distinct id lists must never collide, including boundary shifts.
        assertNotEquals(
            AutoCompactionBranchIdentity.fromMessageIds(listOf(1L, 23L)),
            AutoCompactionBranchIdentity.fromMessageIds(listOf(12L, 3L)),
        )
        assertNull(AutoCompactionBranchIdentity.fromMessageIds(listOf(-5L, -3L)))
    }

    @Test
    fun `memory whose boundary left the active branch is invalid`() {
        val branchIds = listOf(10L, 11L, 12L)
        val memory = AutoCompactMemory(
            conversationId = 1L,
            summary = "summary",
            compactedThroughMessageId = 99L,
            updatedAt = 1L,
            sourceTurnCount = 1,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(branchIds, 12L),
        )
        assertFalse(
            AutoCompactionEngine.isValidMemoryForActiveBranch(
                memory = memory,
                conversationId = 1L,
                activeBranchMessageIds = branchIds,
                recentHistoryPercent = 25,
            ),
        )
    }

    @Test
    fun `memory from another conversation cannot validate here`() {
        val branchIds = listOf(10L, 11L, 12L)
        val memory = AutoCompactMemory(
            conversationId = 2L,
            summary = "summary",
            compactedThroughMessageId = 12L,
            updatedAt = 1L,
            sourceTurnCount = 1,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(branchIds, 12L),
        )
        assertFalse(
            AutoCompactionEngine.isValidMemoryForActiveBranch(
                memory = memory,
                conversationId = 1L,
                activeBranchMessageIds = branchIds,
                recentHistoryPercent = 25,
            ),
        )
    }
}
