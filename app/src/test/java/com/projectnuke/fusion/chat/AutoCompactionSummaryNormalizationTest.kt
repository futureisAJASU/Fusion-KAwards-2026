package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.llm.FailureKind
import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.LlmEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 regression: a compact summary is successful only after the
 * algorithm has selected source turns, the bounded LLM request actually
 * executed, the raw LLM output was normalized + validated, the persistent
 * memory was updated, and the semantic boundary advanced.
 *
 * A non-blank LLM reply is not, by itself, a successful compact.
 *
 * Required behaviors:
 *   - raw normal summary text is accepted and the boundary advances
 *   - reasoning/thinking wrappers (e.g. <think>...</think>) are stripped
 *     before the persisted summary is decided
 *   - reasoning-only output (no actual summary body) is rejected; the
 *     boundary does NOT advance and the previous valid memory is preserved
 *   - blank / garbage output is rejected; boundary does NOT advance;
 *     previous valid memory is preserved
 *   - an invalid update with an existing valid memory preserves the
 *     previous memory and does NOT advance the boundary
 */
class AutoCompactionSummaryNormalizationTest {

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

    private fun existingBoundaryMemory(boundary: Long): AutoCompactMemory =
        AutoCompactMemory(
            conversationId = 1L,
            summary = "old compact",
            compactedThroughMessageId = boundary,
            updatedAt = 1L,
            sourceTurnCount = 2,
        )

    private class FixedOutcomeEngine(
        private val outcome: GenerationOutcome,
    ) : LlmEngine {
        override suspend fun generate(
            messages: List<com.projectnuke.fusion.model.ChatMessage>,
            profile: RequestedEngineProfile,
            options: ConversationOptions,
        ): GenerationOutcome = outcome

        override fun unload() = Unit
    }

    private fun compactResultFromEngine(outcome: GenerationOutcome) = runBlocking {
        val engine = FixedOutcomeEngine(outcome)
        val existing = existingBoundaryMemory(boundary = 13L)
        AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = history(),
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            summarize = { _, _, _ ->
                usableAutoCompactionGenerationText(
                    engine.generate(
                        messages = emptyList(),
                        profile = RequestedEngineProfile("test", AcceleratorMode.CPU, false, 2048, false),
                        options = ConversationOptions(maxOutputToken = 384),
                    ),
                )
            },
        )
    }

    @Test
    fun `raw normal summary is accepted and boundary advances`() = runBlocking {
        val messages = history()
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            summarize = { _, _, _ ->
                "Goals:\n- build a sample app\n\nDecisions:\n- keep it simple\n\nImportant context:\nnone\n\nOpen items:\n- ship it"
            },
        )
        assertTrue(result.compactUsed)
        assertNotNull(result.memory)
        assertTrue(result.memory!!.summary.startsWith("Goals:"))
        assertFalse(result.memory!!.summary.contains("<think>"))
        assertEquals(1, result.promptMessages.count { it.content.startsWith("[FUSION_AUTO_COMPACT]") })
    }

    @Test
    fun `reasoning prefix is stripped and only the summary is persisted`() = runBlocking {
        val messages = history()
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            summarize = { _, _, _ ->
                "<fusion_thinking>I should list goals, decisions, important context, and open items. " +
                    "The user wants to ship. The decisions are: build it. The open items: ship it.</fusion_thinking>\n" +
                    "Goals:\n- ship a sample\n\nDecisions:\n- keep it simple\n\nImportant context:\nnone\n\nOpen items:\n- ship it"
            },
        )
        assertTrue("compact should succeed when summary is recoverable", result.compactUsed)
        val persisted = result.memory?.summary
        assertNotNull(persisted)
        assertFalse("reasoning text must never leak into persisted memory", persisted!!.contains("<think>"))
        assertFalse(persisted.contains("I should list"))
        assertTrue(persisted.startsWith("Goals:"))
        assertTrue(persisted.contains("ship a sample"))
    }

    @Test
    fun `fusion thinking wrapper is stripped and only the summary is persisted`() = runBlocking {
        val messages = history()
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = null,
            summarize = { _, _, _ ->
                "<fusion_thinking>internal notes about the turns</fusion_thinking>\n" +
                    "<fusion_answer>Goals:\n- x\n\nDecisions:\n- y</fusion_answer>"
            },
        )
        assertTrue(result.compactUsed)
        val persisted = result.memory?.summary
        assertNotNull(persisted)
        assertFalse(persisted!!.contains("fusion_thinking"))
        assertFalse(persisted.contains("internal notes"))
        assertTrue(persisted.contains("Goals:"))
    }

    @Test
    fun `reasoning only output is rejected and boundary is unchanged`() = runBlocking {
        val messages = history()
        val existing = existingBoundaryMemory(boundary = 13L)
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            summarize = { _, _, _ ->
                "<fusion_thinking>This is internal reasoning with no actual summary body. Just thinking aloud.</fusion_thinking>"
            },
        )
        assertFalse("reasoning-only output must not be a successful compact", result.compactUsed)
        assertEquals("previous valid memory must be preserved", existing, result.memory)
        // boundary must remain exactly where the existing memory set it
        assertEquals(13L, result.memory?.compactedThroughMessageId)
        // The previous memory is still injected into the prompt projection
        assertTrue(result.promptMessages.any { it.content.contains("old compact") })
        // And the previous boundary's assistant turn is still excluded from projection
        assertFalse(result.promptMessages.any { it.content.startsWith("u2") })
    }

    @Test
    fun `truncated success is invalid and deterministic fallback preserves the old boundary`() {
        val result = compactResultFromEngine(
            GenerationOutcome.Success(
                text = "Goals:\n- apparently complete but cut off",
                truncated = true,
            ),
        )
        assertFalse(result.compactUsed)
        assertTrue(result.fallbackTrimmed)
        assertEquals(13L, result.memory?.compactedThroughMessageId)
        assertEquals("old compact", result.memory?.summary)
        assertTrue(result.promptMessages.any { it.content.startsWith("u7") })
    }

    @Test
    fun `empty and failed outcomes cannot advance the compact boundary`() {
        listOf<GenerationOutcome>(
            GenerationOutcome.Empty,
            GenerationOutcome.Failure(FailureKind.GENERATION_IO, "failed"),
        ).forEach { outcome ->
            val result = compactResultFromEngine(outcome)
            assertFalse(result.compactUsed)
            assertTrue(result.fallbackTrimmed)
            assertEquals(13L, result.memory?.compactedThroughMessageId)
            assertEquals("old compact", result.memory?.summary)
        }
    }

    @Test
    fun `blank output is rejected and boundary is unchanged`() = runBlocking {
        val messages = history()
        val existing = existingBoundaryMemory(boundary = 13L)
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            summarize = { _, _, _ -> "" },
        )
        assertFalse(result.compactUsed)
        assertEquals(existing, result.memory)
        assertEquals(13L, result.memory?.compactedThroughMessageId)
    }

    @Test
    fun `null summarizer output is rejected and boundary is unchanged`() = runBlocking {
        val messages = history()
        val existing = existingBoundaryMemory(boundary = 13L)
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            summarize = { _, _, _ -> null },
        )
        assertFalse(result.compactUsed)
        assertEquals(existing, result.memory)
        assertEquals(13L, result.memory?.compactedThroughMessageId)
    }

    @Test
    fun `garbage output with no usable summary text is rejected`() = runBlocking {
        val messages = history()
        val existing = existingBoundaryMemory(boundary = 13L)
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            summarize = { _, _, _ -> "*** !!! ??? ... " },
        )
        assertFalse(result.compactUsed)
        assertEquals(existing, result.memory)
        assertEquals(13L, result.memory?.compactedThroughMessageId)
    }

    @Test
    fun `invalid update preserves previous memory when batch attempt fails`() = runBlocking {
        val messages = history()
        val existing = existingBoundaryMemory(boundary = 13L)
        // First batch returns a valid summary; second batch returns invalid output.
        // The persistent boundary must NOT advance to the second batch's
        // (unsuccessful) end, and the previous memory must remain.
        var firstBatch = true
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 1024,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            summarize = { _, _, _ ->
                if (firstBatch) {
                    firstBatch = false
                    "Goals:\n- first batch summary"
                } else {
                    // reasoning-only on the second batch - invalid update
                    "<fusion_thinking>internal notes only</fusion_thinking>"
                }
            },
        )
        assertFalse("a failed batch must not produce a successful compact", result.compactUsed)
        assertEquals("previous valid memory must be preserved", existing, result.memory)
        assertEquals(13L, result.memory?.compactedThroughMessageId)
    }

    @Test
    fun `normalizer strips thinking and rejects output over the persistence bound`() {
        val normalizer = AutoCompactionSummaryNormalizer
        val veryLong = "x".repeat(AutoCompactionSummaryNormalizer.MAX_CODE_POINTS + 100)
        val raw = "<fusion_thinking>r</fusion_thinking>" + veryLong
        val normalized = normalizer.normalize(raw)
        assertNull("overlong normalized output must be rejected, not silently truncated", normalized)
    }

    @Test
    fun `overlong non-truncated output preserves memory and boundary`() = runBlocking {
        val existing = existingBoundaryMemory(boundary = 13L)
        val result = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = history(),
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = policy,
            existingMemory = existing,
            summarize = { _, _, _ ->
                "Goals:\n" + "x".repeat(AutoCompactionSummaryNormalizer.MAX_CODE_POINTS + 100)
            },
        )

        assertFalse(result.compactUsed)
        assertTrue(result.fallbackTrimmed)
        assertEquals(existing, result.memory)
        assertEquals(13L, result.memory?.compactedThroughMessageId)
        assertTrue(result.promptMessages.any { it.content.startsWith("u7") })
    }

    @Test
    fun `normalizer rejects pure reasoning output`() {
        assertNull(AutoCompactionSummaryNormalizer.normalize("<fusion_thinking>only reasoning here</fusion_thinking>"))
        assertNull(AutoCompactionSummaryNormalizer.normalize("<fusion_thinking>notes only</fusion_thinking>"))
    }

    @Test
    fun `normalizer rejects unclosed reasoning wrappers`() {
        assertNull(AutoCompactionSummaryNormalizer.normalize("<fusion_thinking>reasoning with no closing tag"))
        assertNull(
            AutoCompactionSummaryNormalizer.normalize(
                "<fusion_thinking>internal notes without closing tag",
            ),
        )
    }

    @Test
    fun `normalizer rejects meta narration without a usable summary`() {
        assertNull(AutoCompactionSummaryNormalizer.normalize("Reasoning:\n- internal notes only"))
        assertNull(AutoCompactionSummaryNormalizer.normalize("Analysis: deciding what to summarize"))
        assertNull(AutoCompactionSummaryNormalizer.normalize("Internal notes: no summary yet"))
    }

    @Test
    fun `normalizer strips native reasoning only when the model family declares it`() {
        assertEquals(
            "summary",
            AutoCompactionSummaryNormalizer.normalize(
                "<think>native reasoning</think>summary",
                reasoningTags = setOf("think"),
            ),
        )
        assertEquals(
            "<think>literal</think>summary",
            AutoCompactionSummaryNormalizer.normalize("<think>literal</think>summary"),
        )
    }

    @Test
    fun `normalizer rejects blank or whitespace-only output`() {
        assertNull(AutoCompactionSummaryNormalizer.normalize(""))
        assertNull(AutoCompactionSummaryNormalizer.normalize("   \n\t  "))
        assertNull(AutoCompactionSummaryNormalizer.normalize(null))
    }

    @Test
    fun `normalizer rejects garbage with no useful summary content`() {
        assertNull(AutoCompactionSummaryNormalizer.normalize("*** !!! ???"))
        assertNull(AutoCompactionSummaryNormalizer.normalize("..."))
        assertNull(AutoCompactionSummaryNormalizer.normalize("-"))
    }
}
