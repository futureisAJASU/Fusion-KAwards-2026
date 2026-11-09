package com.projectnuke.fusion.chat

import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.LlmEngine
import com.projectnuke.fusion.llm.ModelFingerprintSummary
import com.projectnuke.fusion.llm.MtpRuntimeStatus
import com.projectnuke.fusion.llm.RuntimeBackend
import com.projectnuke.fusion.llm.RuntimeComponentBackend
import com.projectnuke.fusion.llm.RuntimeExecutionSnapshot
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.model.RequestedEngineProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 6 regression: the divider placement, the compact status,
 * and the algorithm's boundary advancement must agree on the
 * single source of truth: the CURRENT VALID [AutoCompactMemory].
 *
 * These scenarios cover the full end-to-end path:
 *   - successful first compact
 *   - existing-memory reuse without a new compact
 *   - incremental compact (boundary advances to a later message)
 *   - failed compact (no boundary advancement)
 *   - branch change (memory invalidated, divider hidden)
 *   - Auto Compact OFF (memory remains authoritative for projection)
 *
 * Tests are pure-Kotlin and do NOT spin up the Compose UI; they
 * verify the pure timeline-placement state and branch validity,
 * which is the same source the UI divider consumes.
 */
class AutoCompactionRequestBranchTruthfulTest {

    private val policy = LocalCompactPolicy(enabled = true, triggerPercent = 80, recentHistoryPercent = 25)

    private fun history(turns: Int = 8, size: Int = 500): List<AutoCompactMessage> = buildList {
        add(AutoCompactMessage(1, "system", "policy"))
        var id = 10L
        repeat(turns) { index ->
            add(AutoCompactMessage(id++, "user", "u$index" + "x".repeat(size)))
            add(AutoCompactMessage(id++, "assistant", "a$index" + "y".repeat(size)))
        }
        add(AutoCompactMessage(id, "user", "current"))
    }

    private val historyMessageIds: List<Long> = (10L..26L).toList()

    private fun settings(kvCapacity: Int) = GenerationSettings(
        maxTokens = 384,
        kvCacheCapacityTokens = kvCapacity,
    )

    /**
     * Resolve the divider index that the UI would render right now,
     * given the algorithm's [AutoCompactResult] and the active
     * branch message-id list. Returns -1 when the divider is hidden.
     */
    private fun resolveDividerIndex(
        result: AutoCompactResult,
        activeBranchMessageIds: List<Long>,
        recentHistoryPercent: Int,
    ): Int {
        if (!result.compactUsed && result.memory == null) return -1
        val memory = result.memory ?: return -1
        val valid = AutoCompactionEngine.isValidMemoryForActiveBranch(
            memory = memory,
            conversationId = memory.conversationId,
            activeBranchMessageIds = activeBranchMessageIds,
            recentHistoryPercent = recentHistoryPercent,
        )
        if (!valid) return -1
        return AutoCompactionEngine.locateCompactBoundaryIndex(memory, activeBranchMessageIds)
    }

    private class ScriptedEngine(
        private val replies: List<String>,
    ) : LlmEngine {
        val calls = AtomicInteger(0)
        override suspend fun generate(
            messages: List<ChatMessage>,
            profile: RequestedEngineProfile,
            options: ConversationOptions,
        ): GenerationOutcome {
            val reply = replies.getOrNull(calls.getAndIncrement()) ?: replies.last()
            return GenerationOutcome.Success(
                text = reply,
                snapshot = RuntimeExecutionSnapshot(
                    requestedAccelerator = profile.accelerator,
                    selectedTextBackend = RuntimeBackend.CPU,
                    selectedVisionBackend = null,
                    samplerBackend = RuntimeComponentBackend.UNKNOWN,
                    mtpRequested = false,
                    mtpStatus = MtpRuntimeStatus.OFF,
                    fallbackEvents = emptyList(),
                    modelFingerprint = ModelFingerprintSummary(
                        canonicalPath = "/dev/null",
                        fileSize = 0L,
                        modifiedAt = 0L,
                        validationVersion = 1,
                        mtpSupported = false,
                    ),
                ),
            )
        }

        override fun unload() {}
    }

    @Test
    fun `successful first compact shows the divider at the new boundary`() = runBlocking {
        val engine = ScriptedEngine(replies = listOf("Goals:\n- ship it"))
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = history(turns = 8, size = 500),
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            branchMessageIds = historyMessageIds,
            summarize = { _, _, _ -> "Goals:\n- ship it" },
        )
        assertTrue("compact must report compactUsed=true", result.compactUsed)
        assertNotNull(result.memory)

        val dividerIndex = resolveDividerIndex(
            result = result,
            activeBranchMessageIds = historyMessageIds,
            recentHistoryPercent = policy.recentHistoryPercent,
        )
        assertTrue(
            "divider must point at a valid index inside the active timeline",
            dividerIndex in 0 until historyMessageIds.lastIndex,
        )
        assertEquals(
            "divider must point exactly at the new boundary message id",
            historyMessageIds[dividerIndex],
            result.memory!!.compactedThroughMessageId,
        )
    }

    @Test
    fun `existing memory reuse does not advance boundary and divider stays`() = runBlocking {
        val existing = AutoCompactMemory(
            conversationId = 1L,
            summary = "old compact",
            compactedThroughMessageId = 13L,
            updatedAt = 1L,
            sourceTurnCount = 2,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(historyMessageIds, 13L),
        )
        val engine = ScriptedEngine(replies = listOf("should not be called"))
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = history(turns = 8, size = 1500),
            effectiveCapacityTokens = 8192,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            branchMessageIds = historyMessageIds,
            summarize = { _, _, _ ->
                error("existing memory reuse must not call the LLM")
            },
        )
        assertFalse(result.compactConsidered)
        assertFalse(result.compactUsed)
        assertEquals("memory must remain at its existing boundary", existing, result.memory)
        assertEquals(0, engine.calls.get())

        val dividerIndex = resolveDividerIndex(
            result = result,
            activeBranchMessageIds = historyMessageIds,
            recentHistoryPercent = policy.recentHistoryPercent,
        )
        assertEquals(
            "divider must remain at the existing boundary",
            historyMessageIds.indexOf(13L),
            dividerIndex,
        )
    }

    @Test
    fun `incremental compact advances boundary and divider moves`() = runBlocking {
        val first = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = history(turns = 8, size = 1500),
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            branchMessageIds = historyMessageIds,
            summarize = { _, _, _ -> "Goals:\n- first batch" },
        )
        assertTrue("first compact must succeed", first.compactUsed)
        val firstMemory = first.memory
        assertNotNull(firstMemory)
        val firstDivider = resolveDividerIndex(
            result = first,
            activeBranchMessageIds = historyMessageIds,
            recentHistoryPercent = policy.recentHistoryPercent,
        )

        // Add enough turns so the recent-history window grows and there is
        // at least one new completed turn past the existing boundary.
        val extendedHistory = history(turns = 12, size = 1500)
        val extendedIds = (10L..33L).toList()
        val second = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = extendedHistory,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = firstMemory,
            branchMessageIds = extendedIds,
            summarize = { _, _, _ -> "Goals:\n- extended" },
        )
        assertTrue("incremental compact must succeed", second.compactUsed)
        val secondMemory = second.memory
        assertNotNull(secondMemory)
        assertTrue(
            "incremental compact must advance the boundary to a later message id",
            secondMemory!!.compactedThroughMessageId > firstMemory!!.compactedThroughMessageId,
        )
        val secondDivider = resolveDividerIndex(
            result = second,
            activeBranchMessageIds = extendedIds,
            recentHistoryPercent = policy.recentHistoryPercent,
        )
        assertEquals(
            "divider must move to the new boundary",
            extendedIds[secondDivider],
            secondMemory.compactedThroughMessageId,
        )
        assertTrue(
            "new divider must sit strictly past the old divider in the timeline",
            secondDivider > firstDivider,
        )
    }

    @Test
    fun `failed compact preserves boundary and divider stays at the previous boundary`() = runBlocking {
        val existing = AutoCompactMemory(
            conversationId = 1L,
            summary = "old compact",
            compactedThroughMessageId = 13L,
            updatedAt = 1L,
            sourceTurnCount = 2,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(historyMessageIds, 13L),
        )
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = history(turns = 8, size = 500),
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            branchMessageIds = historyMessageIds,
            summarize = { _, _, _ -> "<fusion_thinking>only reasoning, no summary body</fusion_thinking>" },
        )
        assertFalse(result.compactUsed)
        assertTrue(result.compactConsidered)
        assertEquals("previous valid memory must be preserved", existing, result.memory)
        assertEquals(
            "previous boundary must remain at exactly the previous compactedThroughMessageId",
            13L,
            result.memory?.compactedThroughMessageId,
        )

        val dividerIndex = resolveDividerIndex(
            result = result,
            activeBranchMessageIds = historyMessageIds,
            recentHistoryPercent = policy.recentHistoryPercent,
        )
        assertEquals(
            "divider must stay at the previous boundary when compact fails",
            historyMessageIds.indexOf(13L),
            dividerIndex,
        )
    }

    @Test
    fun `branch change inside summarized prefix invalidates memory and hides divider`() = runBlocking {
        val goodBranch = historyMessageIds
        val existing = AutoCompactMemory(
            conversationId = 1L,
            summary = "branch A",
            compactedThroughMessageId = 13L,
            updatedAt = 1L,
            sourceTurnCount = 2,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(goodBranch, 13L),
        )
        val mutatedBranch = goodBranch.toMutableList().also { it[1] = 30L }
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = history(turns = 8, size = 500),
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            branchMessageIds = mutatedBranch,
            summarize = { _, _, _ -> "branch B" },
        )
        assertTrue(result.compactUsed)
        assertNotNull(result.memory)
        // New memory carries the new branch fingerprint; old memory must
        // NOT be silently reused.
        assertEquals(
            AutoCompactionBranchIdentity.fromMessageIds(mutatedBranch, result.memory!!.compactedThroughMessageId),
            result.memory?.branchFingerprint,
        )

        // The UI rendering decision must observe the active branch, not
        // the old branch. When the UI re-validates using the old
        // existing-memory branch fingerprint against the new active
        // branch, validity must be false and the divider hidden.
        assertFalse(
            "old memory must NOT validate against the new active branch",
            AutoCompactionEngine.isValidMemoryForActiveBranch(
                memory = existing,
                conversationId = 1L,
                activeBranchMessageIds = mutatedBranch,
                recentHistoryPercent = policy.recentHistoryPercent,
            ),
        )

        // When the UI loads the freshly updated memory, validity holds
        // and the divider is placed at the new boundary.
        val dividerIndex = resolveDividerIndex(
            result = result,
            activeBranchMessageIds = mutatedBranch,
            recentHistoryPercent = policy.recentHistoryPercent,
        )
        assertTrue(dividerIndex in 0 until mutatedBranch.lastIndex)
    }

    @Test
    fun `auto compact OFF keeps valid memory authoritative without creating a new one`() = runBlocking {
        val existing = AutoCompactMemory(
            conversationId = 1L,
            summary = "dormant compact",
            compactedThroughMessageId = 13L,
            updatedAt = 1L,
            sourceTurnCount = 2,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(historyMessageIds, 13L),
        )
        val offPolicy = policy.copy(enabled = false)
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = history(turns = 4, size = 20),
            effectiveCapacityTokens = 1024,
            requiredOutputTokens = 384,
            policy = offPolicy,
            existingMemory = existing,
            summarize = { _, _, _ -> error("disabled compact must not summarize") },
        )
        assertTrue(result.promptMessages.any { it.content.startsWith("[FUSION_AUTO_COMPACT]") })
        assertFalse(result.promptMessages.any { it.content.startsWith("u0") })
        assertEquals(existing, result.memory)
        assertFalse(
            "policy enabled flag must be false",
            offPolicy.enabled,
        )
        val uiRendersDivider = resolveDividerIndex(
            result = result,
            activeBranchMessageIds = historyMessageIds,
            recentHistoryPercent = offPolicy.recentHistoryPercent,
        ) >= 0
        assertTrue(
            "a valid persisted memory remains model-authoritative when automatic compaction is OFF",
            uiRendersDivider,
        )
    }

    @Test
    fun `divider source-of-truth is the current valid memory only`() = runBlocking {
        // No memory: divider must be -1.
        val noMemory = AutoCompactResult(
            promptMessages = emptyList(),
            memory = null,
            compactConsidered = false,
            compactUsed = false,
            fallbackTrimmed = false,
        )
        assertEquals(
            -1,
            resolveDividerIndex(
                result = noMemory,
                activeBranchMessageIds = historyMessageIds,
                recentHistoryPercent = policy.recentHistoryPercent,
            ),
        )

        // Valid memory: divider must point at the boundary.
        val goodMemory = AutoCompactMemory(
            conversationId = 1L,
            summary = "summary",
            compactedThroughMessageId = 13L,
            updatedAt = 1L,
            sourceTurnCount = 2,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(historyMessageIds, 13L),
        )
        val withMemory = AutoCompactResult(
            promptMessages = emptyList(),
            memory = goodMemory,
            compactConsidered = false,
            compactUsed = false,
            fallbackTrimmed = false,
        )
        val idx = resolveDividerIndex(
            result = withMemory,
            activeBranchMessageIds = historyMessageIds,
            recentHistoryPercent = policy.recentHistoryPercent,
        )
        assertEquals(historyMessageIds.indexOf(13L), idx)

        // Invalid memory (branch change): divider must be -1 even
        // though the memory object is non-null.
        val invalidMemory = goodMemory.copy(branchFingerprint = "deadbeef")
        val withInvalidMemory = AutoCompactResult(
            promptMessages = emptyList(),
            memory = invalidMemory,
            compactConsidered = false,
            compactUsed = false,
            fallbackTrimmed = false,
        )
        assertEquals(
            -1,
            resolveDividerIndex(
                result = withInvalidMemory,
                activeBranchMessageIds = historyMessageIds,
                recentHistoryPercent = policy.recentHistoryPercent,
            ),
        )
    }

    @Test
    fun `divider string is never injected into the prompt projection`() = runBlocking {
        val engine = ScriptedEngine(replies = listOf("Goals:\n- ship it"))
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = history(turns = 8, size = 500),
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            branchMessageIds = historyMessageIds,
            summarize = { _, _, _ -> "Goals:\n- ship it" },
        )
        // The compact summary text is allowed in the prompt (it is the
        // replacement for the compacted history). The divider label
        // string itself must never appear.
        result.promptMessages.forEach { message ->
            assertFalse(
                "divider label must never be injected into the model prompt",
                message.content.contains("컨텍스트가 압축되었습니다"),
            )
        }
    }
}
