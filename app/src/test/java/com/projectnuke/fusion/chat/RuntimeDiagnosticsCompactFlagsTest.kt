package com.projectnuke.fusion.chat

import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.MtpRuntimeStatus
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.ui.buildRuntimeDiagnostics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7 alignment tests for `buildRuntimeDiagnostics`.
 *
 * The compact decision is owned by the algorithm (see AutoCompactionEngine.prepare);
 * the UI only propagates the three booleans:
 *  - compactConsidered  → projected crossed the decision path this request
 *  - compactUsed        → a NEW validated LLM summary was committed this request
 *                         (existing-memory reuse does NOT set this)
 *  - fallbackTrimmed    → deterministic whole-turn trimming was used because
 *                         compact was unavailable / failed / insufficient
 *
 * These tests pin down the propagation contract: the UI never flips the truth
 * value the algorithm produced.
 */
class RuntimeDiagnosticsCompactFlagsTest {

    private fun settings() = GenerationSettings(
        kvCacheCapacityTokens = 2048,
        accelerator = AcceleratorMode.GPU,
        speculativeDecodingEnabled = false,
    )

    private fun cancelled() = GenerationOutcome.Cancelled()

    @Test
    fun `compactConsidered propagates true when algorithm triggered`() {
        val diag = buildRuntimeDiagnostics(
            outcome = cancelled(),
            settings = settings(),
            modelId = "m",
            modelPath = "p",
            compactPreparation = PreparedLocalAutoCompaction(
                promptMessages = emptyList(),
                memory = null,
                compactConsidered = true,
                compactUsed = false,
                fallbackTrimmed = false,
            ),
        )
        assertNotNull(diag)
        assertEquals(true, diag!!.compactConsidered)
        assertEquals(false, diag.compactUsed)
        assertEquals(false, diag.fallbackTrimmed)
    }

    @Test
    fun `compactUsed propagates true only when new summary committed`() {
        val diag = buildRuntimeDiagnostics(
            outcome = cancelled(),
            settings = settings(),
            modelId = "m",
            modelPath = "p",
            compactPreparation = PreparedLocalAutoCompaction(
                promptMessages = emptyList(),
                memory = null,
                compactConsidered = true,
                compactUsed = true,
                fallbackTrimmed = false,
            ),
        )
        assertTrue(diag!!.compactUsed == true)
        assertFalse(diag.fallbackTrimmed == true)
    }

    @Test
    fun `existing-memory reuse propagates considered but not used`() {
        // existing memory is valid and covers everything → algorithm
        // never calls the LLM. compactUsed must NOT be flipped true by the UI.
        val diag = buildRuntimeDiagnostics(
            outcome = cancelled(),
            settings = settings(),
            modelId = "m",
            modelPath = "p",
            compactPreparation = PreparedLocalAutoCompaction(
                promptMessages = emptyList(),
                memory = null,
                compactConsidered = true,
                compactUsed = false,
                fallbackTrimmed = false,
            ),
        )
        assertEquals(true, diag!!.compactConsidered)
        assertEquals(false, diag.compactUsed)
        assertEquals(false, diag.fallbackTrimmed)
    }

    @Test
    fun `fallbackTrimmed propagates when algorithm used deterministic trimming`() {
        // LLM failed or eligible pool was empty: algorithm fell back to
        // whole-turn trimming. compactUsed stays false.
        val diag = buildRuntimeDiagnostics(
            outcome = cancelled(),
            settings = settings(),
            modelId = "m",
            modelPath = "p",
            compactPreparation = PreparedLocalAutoCompaction(
                promptMessages = emptyList(),
                memory = null,
                compactConsidered = true,
                compactUsed = false,
                fallbackTrimmed = true,
            ),
        )
        assertEquals(true, diag!!.compactConsidered)
        assertEquals(false, diag.compactUsed)
        assertEquals(true, diag.fallbackTrimmed)
    }

    @Test
    fun `truncated compact success is considered but not used and reports fallback trimming`() = runBlocking {
        val messages = buildList {
            add(AutoCompactMessage(1L, "system", "policy"))
            var id = 10L
            repeat(8) { index ->
                add(AutoCompactMessage(id++, "user", "u$index" + "x".repeat(500)))
                add(AutoCompactMessage(id++, "assistant", "a$index" + "y".repeat(500)))
            }
            add(AutoCompactMessage(id, "user", "current"))
        }
        val existing = AutoCompactMemory(
            conversationId = 1L,
            summary = "old compact",
            compactedThroughMessageId = 13L,
            updatedAt = 1L,
            sourceTurnCount = 2,
        )
        val compact = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 2048,
            requiredOutputTokens = 384,
            policy = LocalCompactPolicy(enabled = true, triggerPercent = 80, recentHistoryPercent = 25),
            existingMemory = existing,
            summarize = { _, _, _ ->
                usableAutoCompactionGenerationText(
                    GenerationOutcome.Success(
                        text = "Goals:\n- valid-looking but incomplete",
                        truncated = true,
                    ),
                )
            },
        )
        val diagnostics = buildRuntimeDiagnostics(
            outcome = GenerationOutcome.Cancelled(),
            settings = settings(),
            modelId = "m",
            modelPath = "p",
            compactPreparation = PreparedLocalAutoCompaction(
                promptMessages = compact.promptMessages.map(AutoCompactMessage::toChatMessage),
                memory = compact.memory,
                compactConsidered = compact.compactConsidered,
                compactUsed = compact.compactUsed,
                fallbackTrimmed = compact.fallbackTrimmed,
            ),
        )
        assertEquals(true, diagnostics!!.compactConsidered)
        assertEquals(false, diagnostics.compactUsed)
        assertEquals(true, diagnostics.fallbackTrimmed)
        assertEquals(13L, compact.memory?.compactedThroughMessageId)
    }

    @Test
    fun `truncated compact success reports no fallback trim when all raw turns fit`() = runBlocking {
        val messages = listOf(
            AutoCompactMessage(1L, "system", "policy"),
            AutoCompactMessage(10L, "user", "small user"),
            AutoCompactMessage(11L, "assistant", "small assistant"),
            AutoCompactMessage(12L, "user", "current"),
        )
        val compact = AutoCompactionEngine.prepare(
            conversationId = 1L,
            messages = messages,
            effectiveCapacityTokens = 700,
            requiredOutputTokens = 384,
            policy = LocalCompactPolicy(enabled = true, triggerPercent = 60, recentHistoryPercent = 25),
            existingMemory = null,
            summarize = { _, _, _ -> null },
        )

        val diagnostics = buildRuntimeDiagnostics(
            outcome = cancelled(),
            settings = settings(),
            modelId = "m",
            modelPath = "p",
            compactPreparation = PreparedLocalAutoCompaction(
                promptMessages = compact.promptMessages.map(AutoCompactMessage::toChatMessage),
                memory = compact.memory,
                compactConsidered = compact.compactConsidered,
                compactUsed = compact.compactUsed,
                fallbackTrimmed = compact.fallbackTrimmed,
            ),
        )

        assertEquals(true, diagnostics!!.compactConsidered)
        assertEquals(false, diagnostics.compactUsed)
        assertEquals(false, diagnostics.fallbackTrimmed)
    }

    @Test
    fun `no compact preparation yields null flags`() {
        // compactPreparation is null → algorithm didn't run; all three
        // diagnostics must be null so the UI cannot accidentally render
        // an outdated truth value.
        val diag = buildRuntimeDiagnostics(
            outcome = cancelled(),
            settings = settings(),
            modelId = "m",
            modelPath = "p",
        )
        assertNotNull(diag)
        assertEquals(null, diag!!.compactConsidered)
        assertEquals(null, diag.compactUsed)
        assertEquals(null, diag.fallbackTrimmed)
    }

    @Test
    fun `algorithm disabled propagates all three flags false`() {
        // Policy OFF → algorithm never triggered, never compacted, never
        // trimmed. compactConsidered must be false (not null) so the UI
        // knows the decision path explicitly opted out.
        val diag = buildRuntimeDiagnostics(
            outcome = cancelled(),
            settings = settings(),
            modelId = "m",
            modelPath = "p",
            compactPreparation = PreparedLocalAutoCompaction(
                promptMessages = emptyList(),
                memory = null,
                compactConsidered = false,
                compactUsed = false,
                fallbackTrimmed = false,
            ),
        )
        assertEquals(false, diag!!.compactConsidered)
        assertEquals(false, diag.compactUsed)
        assertEquals(false, diag.fallbackTrimmed)
    }

    @Test
    fun `compactUsed and fallbackTrimmed are mutually exclusive in propagation`() {
        // The algorithm never produces a result with both true. The UI must
        // also never end up showing both true. Pin the propagation so a
        // future bug cannot fabricate both.
        val consideredAndUsed = buildRuntimeDiagnostics(
            outcome = cancelled(),
            settings = settings(),
            modelId = "m",
            modelPath = "p",
            compactPreparation = PreparedLocalAutoCompaction(
                promptMessages = emptyList(),
                memory = null,
                compactConsidered = true,
                compactUsed = true,
                fallbackTrimmed = false,
            ),
        )
        assertTrue(consideredAndUsed!!.compactUsed == true)
        assertFalse(consideredAndUsed.fallbackTrimmed == true)

        val consideredAndFallback = buildRuntimeDiagnostics(
            outcome = cancelled(),
            settings = settings(),
            modelId = "m",
            modelPath = "p",
            compactPreparation = PreparedLocalAutoCompaction(
                promptMessages = emptyList(),
                memory = null,
                compactConsidered = true,
                compactUsed = false,
                fallbackTrimmed = true,
            ),
        )
        assertFalse(consideredAndFallback!!.compactUsed == true)
        assertTrue(consideredAndFallback.fallbackTrimmed == true)
    }

    @Test
    fun `compactPolicy enabled flag propagates independently`() {
        // compactPolicy carries the user-visible "Auto Compact ON/OFF"
        // toggle. The UI must surface it even when compactPreparation is
        // null (algorithm didn't run this request).
        val onDiag = buildRuntimeDiagnostics(
            outcome = cancelled(),
            settings = settings(),
            modelId = "m",
            modelPath = "p",
            compactPolicy = LocalCompactPolicy(enabled = true),
        )
        assertEquals(true, onDiag!!.compactEnabled)

        val offDiag = buildRuntimeDiagnostics(
            outcome = cancelled(),
            settings = settings(),
            modelId = "m",
            modelPath = "p",
            compactPolicy = LocalCompactPolicy(enabled = false),
        )
        assertEquals(false, offDiag!!.compactEnabled)
    }

    @Test
    fun `mtp status defaults to OFF when no snapshots provided`() {
        // Pin the conservative default so the UI never inherits stale
        // engine state from a previous request.
        val diag = buildRuntimeDiagnostics(
            outcome = cancelled(),
            settings = settings(),
            modelId = "m",
            modelPath = "p",
        )
        assertEquals(MtpRuntimeStatus.OFF, diag!!.mtpRuntimeStatus)
    }
}
