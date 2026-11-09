package com.projectnuke.fusion.chat

import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.LlmEngine
import com.projectnuke.fusion.llm.RuntimeExecutionSnapshot
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.model.RequestedEngineProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 4 regression: the user-visible "compacting context" status must
 * fire exactly once per request, and only when actual semantic LLM
 * compaction work is about to begin.
 *
 * Required behavior:
 *   - below threshold             → onCompacting is never invoked
 *   - existing memory reused       → onCompacting is never invoked
 *   - real first compact batch     → onCompacting is invoked exactly once
 *   - multiple compact batches     → onCompacting is invoked exactly once
 *                                   (no duplicate / flickering transitions)
 *   - summary failure              → onCompacting is invoked at most once,
 *                                   and the caller's request continues
 *                                   through fallback trimming
 *
 * The exact user-facing Korean status text is locked to the constant
 * [LocalAutoCompactionStatus.COMPACTING].
 */
class LocalAutoCompactionStatusTest {

    private val policy = LocalCompactPolicy(enabled = true, triggerPercent = 80, recentHistoryPercent = 25)

    private fun history(turns: Int = 8, size: Int = 500): List<ChatMessage> = buildList {
        add(ChatMessage("system", "policy"))
        var id = 10L
        repeat(turns) { index ->
            add(ChatMessage("user", "u$index" + "x".repeat(size)))
            add(ChatMessage("assistant", "a$index" + "y".repeat(size)))
        }
        add(ChatMessage("user", "current"))
    }

    private val historyMessageIds: List<Long> = (10L..26L).toList()

    private fun settings(kvCapacity: Int) = GenerationSettings(
        maxTokens = 384,
        kvCacheCapacityTokens = kvCapacity,
    )

    /**
     * Fake engine that counts invocations and returns a fixed summary.
     * Used to simulate batched internal compact summary calls.
     */
    private class FakeCompactEngine(
        private val replies: List<String>,
    ) : LlmEngine {
        val calls = AtomicInteger(0)
        override suspend fun generate(
            messages: List<ChatMessage>,
            profile: RequestedEngineProfile,
            options: ConversationOptions,
        ): GenerationOutcome {
            val index = calls.getAndIncrement()
            val reply = replies.getOrNull(index) ?: replies.last()
            return GenerationOutcome.Success(
                text = reply,
                snapshot = RuntimeExecutionSnapshot(
                    requestedAccelerator = profile.accelerator,
                    selectedTextBackend = com.projectnuke.fusion.llm.RuntimeBackend.CPU,
                    selectedVisionBackend = null,
                    samplerBackend = com.projectnuke.fusion.llm.RuntimeComponentBackend.UNKNOWN,
                    mtpRequested = false,
                    mtpStatus = com.projectnuke.fusion.llm.MtpRuntimeStatus.OFF,
                    fallbackEvents = emptyList(),
                    modelFingerprint = com.projectnuke.fusion.llm.ModelFingerprintSummary(
                        canonicalPath = "/dev/null",
                        fileSize = 0L,
                        modifiedAt = 0L,
                        validationVersion = 1,
                        mtpSupported = false,
                    ),
                ),
            )
        }

        override fun unload() {
            // no-op for tests
        }
    }

    @Test
    fun `status constant uses formal Korean`() {
        assertEquals(
            "대화 컨텍스트를 압축하는 중입니다…",
            LocalAutoCompactionStatus.COMPACTING,
        )
    }

    @Test
    fun `below threshold never invokes the compacting status`() = runBlocking {
        val engine = FakeCompactEngine(replies = listOf("should not be called"))
        val compactingCalls = AtomicInteger(0)
        prepareLocalAutoCompactionWithStatus(
            conversationId = 1L,
            rawMessages = history(turns = 2, size = 10),
            historyMessageIds = historyMessageIds.take(4),
            kvCapacity = 8192,
            engine = engine,
            onCompacting = { compactingCalls.incrementAndGet() },
        )
        assertEquals(
            "below threshold must not invoke the compacting status",
            0,
            compactingCalls.get(),
        )
        assertEquals("LLM must not have been called", 0, engine.calls.get())
    }

    @Test
    fun `existing memory reuse without new compact never invokes the compacting status`() = runBlocking {
        val engine = FakeCompactEngine(replies = listOf("should not be called"))
        val existing = AutoCompactMemory(
            conversationId = 1L,
            summary = "old compact",
            compactedThroughMessageId = 13L,
            updatedAt = 1L,
            sourceTurnCount = 2,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(historyMessageIds, 13L),
        )
        val compactingCalls = AtomicInteger(0)
        val result = prepareLocalAutoCompactionWithStatus(
            conversationId = 1L,
            rawMessages = history(turns = 8, size = 1500),
            historyMessageIds = historyMessageIds,
            kvCapacity = 8192,
            engine = engine,
            onCompacting = { compactingCalls.incrementAndGet() },
            preloadedMemory = existing,
        )
        assertFalse("compact must not have advanced", result.compactUsed)
        assertEquals(
            "existing memory reuse must not invoke the compacting status",
            0,
            compactingCalls.get(),
        )
        assertEquals("LLM must not have been called", 0, engine.calls.get())
    }

    @Test
    fun `real first compact batch invokes the compacting status exactly once`() = runBlocking {
        val engine = FakeCompactEngine(
            replies = listOf("Goals:\n- ship a sample\n\nDecisions:\n- keep it simple"),
        )
        val compactingCalls = AtomicInteger(0)
        val result = prepareLocalAutoCompactionWithStatus(
            conversationId = 1L,
            rawMessages = history(turns = 8, size = 500),
            historyMessageIds = historyMessageIds,
            kvCapacity = 2048,
            engine = engine,
            onCompacting = { compactingCalls.incrementAndGet() },
        )
        assertTrue("compact should have succeeded", result.compactUsed)
        assertEquals(
            "compacting status must be invoked exactly once for the first batch",
            1,
            compactingCalls.get(),
        )
    }

    @Test
    fun `multiple compact batches invoke the compacting status exactly once`() = runBlocking {
        // 4 replies to support up to 4 bounded batches
        val engine = FakeCompactEngine(
            replies = listOf(
                "Goals:\n- first batch",
                "Goals:\n- second batch\n\nDecisions:\n- ship it",
                "Goals:\n- third batch",
                "Goals:\n- fourth batch",
            ),
        )
        val compactingCalls = AtomicInteger(0)
        val result = prepareLocalAutoCompactionWithStatus(
            conversationId = 1L,
            rawMessages = history(turns = 16, size = 800),
            historyMessageIds = (10L..42L).toList(),
            kvCapacity = 4096,
            engine = engine,
            onCompacting = { compactingCalls.incrementAndGet() },
        )
        assertTrue("compact should have succeeded", result.compactUsed)
        assertTrue("multi-batch compaction must have run more than one batch", engine.calls.get() > 1)
        assertEquals(
            "compacting status must NOT flicker or repeat across batches",
            1,
            compactingCalls.get(),
        )
    }

    @Test
    fun `summary failure invokes the compacting status at most once and falls back to trimming`() = runBlocking {
        // First reply invalid (only whitespace) — second reply would never be reached.
        val engine = FakeCompactEngine(replies = listOf("   "))
        val compactingCalls = AtomicInteger(0)
        val existing = AutoCompactMemory(
            conversationId = 1L,
            summary = "old compact",
            compactedThroughMessageId = 13L,
            updatedAt = 1L,
            sourceTurnCount = 2,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(historyMessageIds, 13L),
        )
        val result = prepareLocalAutoCompactionWithStatus(
            conversationId = 1L,
            rawMessages = history(turns = 8, size = 500),
            historyMessageIds = historyMessageIds,
            kvCapacity = 2048,
            engine = engine,
            onCompacting = { compactingCalls.incrementAndGet() },
            preloadedMemory = existing,
        )
        assertFalse("failed compact must not be reported as used", result.compactUsed)
        assertTrue("fallback trimming must be used", result.fallbackTrimmed)
        assertTrue("compacting status may have been fired at most once", compactingCalls.get() <= 1)
        assertEquals("previous valid memory must be preserved", existing, result.memory)
        // The caller can still continue; the prompt is the trimmed fallback projection.
        assertTrue(
            "request must continue with a usable projection (system + trimmed turns + current)",
            result.promptMessages.isNotEmpty(),
        )
    }

    /**
     * Test seam that mirrors the ChatScreen-side [prepareLocalCompactedMessages]
     * so the regression can lock down the compacting-status callback behavior
     * without spinning up the Compose UI layer.
     */
    private suspend fun prepareLocalAutoCompactionWithStatus(
        conversationId: Long,
        rawMessages: List<ChatMessage>,
        historyMessageIds: List<Long>,
        kvCapacity: Int,
        engine: LlmEngine,
        onCompacting: suspend () -> Unit,
        preloadedMemory: AutoCompactMemory? = null,
    ): PreparedLocalAutoCompaction {
        val compactingStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        val concreteSettings = settings(kvCapacity)
        val engineProfile = RequestedEngineProfile(
            modelPath = "/dev/null",
            accelerator = com.projectnuke.fusion.model.AcceleratorMode.CPU,
            mtpRequested = false,
            kvCacheCapacityTokens = kvCapacity,
            enableVisionBackend = false,
        )
        return prepareLocalAutoCompaction(
            conversationId = conversationId,
            rawMessages = rawMessages,
            historyMessageIds = historyMessageIds,
            settings = concreteSettings,
            compactPolicy = policy,
            existingMemory = preloadedMemory,
            summarize = { compactRequest, previousSummary, turns ->
                // Fire onCompacting before the FIRST batch only.
                if (compactingStarted.compareAndSet(false, true)) {
                    onCompacting()
                }
                val summaryRequest = AutoCompactionEngine.prepareSummaryRequest(
                    previousSummary = previousSummary,
                    turns = turns,
                    effectiveCapacityTokens = kvCapacity,
                    request = compactRequest,
                )
                if (summaryRequest == null) {
                    null
                } else {
                    val outcome = engine.generate(
                        messages = summaryRequest.messages,
                        profile = engineProfile,
                        options = summaryRequest.conversationOptions,
                    )
                    (outcome as? GenerationOutcome.Success)?.text
                }
            },
        )
    }
}
