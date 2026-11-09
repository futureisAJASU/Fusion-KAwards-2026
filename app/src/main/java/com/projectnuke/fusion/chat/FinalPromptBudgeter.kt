package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.KvCacheCapacityPolicy

internal enum class LegacyPromptModelScale {
    TWO_B,
    FOUR_B,
    OTHER,
}

private val legacyTwoBToken = Regex(
    pattern = """(?:^|[\s._/-])e?2b(?=$|[\s._/-])""",
    options = setOf(RegexOption.IGNORE_CASE),
)

private val legacyFourBToken = Regex(
    pattern = """(?:^|[\s._/-])e?4b(?=$|[\s._/-])""",
    options = setOf(RegexOption.IGNORE_CASE),
)

internal fun classifyLegacyPromptModelScale(modelId: String?): LegacyPromptModelScale = when {
    modelId != null && legacyTwoBToken.containsMatchIn(modelId) -> LegacyPromptModelScale.TWO_B
    modelId != null && legacyFourBToken.containsMatchIn(modelId) -> LegacyPromptModelScale.FOUR_B
    else -> LegacyPromptModelScale.OTHER
}

internal data class FinalPromptBudget(
    val modelId: String?,
    val external: Boolean,
    val maxOutputTokens: Int,
    val externaModelContextChars: Int? = null,
    /**
     * LiteRT-LM `EngineConfig.maxNumTokens`: the combined input + output KV
     * capacity in tokens, used for local (on-device) model fitting only.
     *
     * When non-null, local [FinalPromptBudgeter.fit] validates the final
     * request against:
     *   estimated prompt tokens + applied output tokens + safety margin
     *   <= usable Engine KV capacity
     *
     * When null for a local request, the legacy model-name char heuristic
     * is used as a degraded fallback (never zeroes the prompt budget).
     *
     * External-provider requests ignore this field entirely.
     */
    val engineKvCapacityTokens: Int? = null,
    /**
     * Conservative token headroom reserved between prompt+output and the KV
     * capacity. Defaults to a small safety margin that mirrors the app's
     * existing settings-time invariant.
     */
    val safetyMarginTokens: Int = FinalPromptBudgeter.DEFAULT_SAFETY_MARGIN_TOKENS,
    val minimumUsefulOutputTokens: Int = FinalPromptBudgeter.MIN_USEFUL_OUTPUT_TOKENS,
    /** Host quality status for each complete model-facing evidence prefix. */
    val searchEvidenceStatusByCount: Map<Int, String> = emptyMap(),
)

internal object FinalPromptBudgeter {
    const val DEFAULT_SAFETY_MARGIN_TOKENS = 128
    /** Minimum tested local answer allowance for short multi-sentence output. */
    const val MIN_USEFUL_OUTPUT_TOKENS = 256

    /**
     * Conservative settings-time warning for a non-empty local prompt. Even
     * the smallest prompt needs token space in addition to the requested
     * output and the KV safety margin, so equality between KV and output is
     * already a reduction-risk configuration.
     */
    fun manualCapacityMayReduceOutput(
        manualCapacityTokens: Int,
        requestedOutputTokens: Int,
    ): Boolean = manualCapacityTokens > 0 && requestedOutputTokens > 0 &&
        manualCapacityTokens <= requestedOutputTokens + DEFAULT_SAFETY_MARGIN_TOKENS + 1

    fun fit(messages: List<ChatMessage>, budget: FinalPromptBudget): FittedMessages {
        val requestedOutputTokens = budget.maxOutputTokens.coerceAtLeast(0)
        val localKv = budget.engineKvCapacityTokens
            ?.takeIf { !budget.external && it > 0 }
            ?.coerceIn(KvCacheCapacityPolicy.MIN_CAPACITY, KvCacheCapacityPolicy.MAX_CAPACITY)
        return if (localKv != null) {
            fitLocalKv(messages = messages, budget = budget, capacityTokens = localKv, requestedOutputTokens = requestedOutputTokens)
        } else {
            fitCharBudget(messages = messages, budget = budget, requestedOutputTokens = requestedOutputTokens)
        }
    }

    /**
     * Char-budget path for external-provider requests and legacy local requests
     * that have no explicit Engine KV capacity. The requested-output reserve is
     * never allowed to starve the prompt entirely through the model-name
     * heuristic alone (the original Gemma 4 E2B-it regression).
     */
    private fun fitCharBudget(
        messages: List<ChatMessage>,
        budget: FinalPromptBudget,
        requestedOutputTokens: Int,
    ): FittedMessages {
        val limit = computeLimit(budget)

        // ── Step 1: trim history against the char budget using the requested
        // output reserve. For external/legacy-local paths the reserve is never
        // allowed to zero the prompt budget through the requested output alone
        // (the original Gemma E2B regression). For KV-aware local paths the
        // reserve is the real KV contract; later we re-clamp the applied output
        // based on the *fitted* prompt so a too-large mandatory prompt STILL
        // returns TooLarge.
        val effectiveReserveChars = if (budget.external) {
            ExternalPromptCapacityPolicy.effectiveOutputReserveChars(requestedOutputTokens)
        } else {
            val requestedReserveChars = requestedOutputTokens * PromptTokenCost.CHARS_PER_TOKEN
            requestedReserveChars.coerceAtMost((limit / 2).coerceAtLeast(0))
        }
        val available = (limit - effectiveReserveChars).coerceAtLeast(0)

        val bounded = messages

        if (bounded.sumOf { it.content.length + PromptTokenCost.MESSAGE_OVERHEAD_CHARS } <= available) {
            return FittedMessages(bounded, false, requestedOutputTokens, OutputAdjustmentReason.None)
        }

        val mandatory = bounded.filter { it.role == "system" } +
            bounded.lastOrNull { it.role == "user" }.let { listOfNotNull(it) }
        val current = mandatory.lastOrNull { it.role == "user" }
        val mandatoryBytes = mandatory.sumOf { it.content.length + PromptTokenCost.MESSAGE_OVERHEAD_CHARS }

        // If the mandatory prompt already exceeds the available char budget,
        // descending into history-trimming won't help; we may still be able to
        // reduce the output (KV-aware) before declaring TooLarge.
        if (mandatoryBytes <= available) {
            var remaining = available - mandatoryBytes
            val nonMandatory = bounded.filter { it.role != "system" && it !== current }
            val turns = coherentTurns(nonMandatory).asReversed()
            val selected = mutableListOf<List<ChatMessage>>()

            for (turn in turns) {
                val cost = turn.sumOf { it.content.length + PromptTokenCost.MESSAGE_OVERHEAD_CHARS }
                if (cost > remaining) break
                selected += turn
                remaining -= cost
            }

            val fittedMessages = buildList {
                addAll(bounded.filter { it.role == "system" })
                addAll(selected.asReversed().flatten())
                current?.let { add(it) }
            }
            return FittedMessages(fittedMessages, false, requestedOutputTokens, OutputAdjustmentReason.None)
        }

        return FittedMessages(emptyList(), true, requestedOutputTokens, OutputAdjustmentReason.None)
    }

    fun computeLimit(budget: FinalPromptBudget): Int =
        if (budget.externaModelContextChars != null && budget.externaModelContextChars > 0) {
            budget.externaModelContextChars
        } else if (budget.external) {
            ExternalPromptCapacityPolicy.CONTEXT_LIMIT_CHARS
        } else when (classifyLegacyPromptModelScale(budget.modelId)) {
            LegacyPromptModelScale.TWO_B -> 16_000
            LegacyPromptModelScale.FOUR_B -> 24_000
            LegacyPromptModelScale.OTHER -> 20_000
        }

    /**
     * KV-aware local (on-device) fitting. For local LiteRT requests with an
     * explicit Engine KV capacity, the authoritative hard capacity is the
     * Engine prompt+output KV budget — the model-name char heuristic must not
     * discard usable history by reserving the entire requested output against
     * it.
     *
     * Constraint enforced here:
     *   promptTokens + appliedOutputTokens + safetyMarginTokens <= capacityTokens
     *
     * System messages and the current user prompt are mandatory. Older history
     * is dropped as WHOLE coherent user/assistant turns, newest-first, only
     * when the full requested output would otherwise overflow. Search evidence
     * is compacted as complete records before output is reduced. If the minimum
     * useful output cannot fit, this returns TooLarge before LiteRT invocation.
     */
    private fun fitLocalKv(
        messages: List<ChatMessage>,
        budget: FinalPromptBudget,
        capacityTokens: Int,
        requestedOutputTokens: Int,
    ): FittedMessages {
        val usableTokens = (capacityTokens - budget.safetyMarginTokens.coerceAtLeast(0)).coerceAtLeast(1)
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        if (lastUserIndex < 0) {
            return FittedMessages(emptyList(), true, requestedOutputTokens, OutputAdjustmentReason.None)
        }

        val systemMessages = messages.filter { it.role == "system" }
        val originalFinalUser = messages[lastUserIndex]
        val turns = coherentTurns(messages.subList(0, lastUserIndex).filter { it.role != "system" })
        val requested = requestedOutputTokens.coerceAtLeast(1)
        val usefulFloor = budget.minimumUsefulOutputTokens.coerceIn(1, requested)
        val variants = SearchEvidencePromptCompactor.compactedUserContents(
            content = originalFinalUser.content,
            evidenceStatusByCount = budget.searchEvidenceStatusByCount,
        )

        // Preserve the requested output allowance and the largest complete
        // evidence set that fits. Add recent history only if it leaves the
        // full requested output budget available.
        for (content in variants) {
            val finalUser = originalFinalUser.copy(content = content)
            val mandatoryTokens = tokensOf(systemMessages + finalUser)
            if (mandatoryTokens + requested > usableTokens) continue

            var promptTokens = mandatoryTokens
            val keptTurns = mutableListOf<List<ChatMessage>>()
            for (turn in turns.asReversed()) {
                val turnTokens = tokensOf(turn)
                if (promptTokens + turnTokens + requested > usableTokens) break
                keptTurns += turn
                promptTokens += turnTokens
            }
            val fittedMessages = buildList {
                addAll(systemMessages)
                keptTurns.asReversed().flatten().forEach(::add)
                add(finalUser)
            }
            return FittedMessages(
                messages = fittedMessages,
                isTooLarge = false,
                appliedOutputTokens = requested,
                adjustmentReason = OutputAdjustmentReason.None,
                admittedSearchEvidenceCount = SearchEvidencePromptCompactor.admittedResultCount(content),
            )
        }

        // All old history is already removed here. Evidence variants reduce
        // complete answer-sufficient result records before output is reduced;
        // a sub-floor request never reaches LiteRT.
        for (content in variants) {
            val finalUser = originalFinalUser.copy(content = content)
            val mandatoryTokens = tokensOf(systemMessages + finalUser)
            val availableOutput = usableTokens - mandatoryTokens
            if (availableOutput < usefulFloor) continue
            val appliedOutput = minOf(requested, availableOutput)
            return FittedMessages(
                messages = systemMessages + finalUser,
                isTooLarge = false,
                appliedOutputTokens = appliedOutput,
                adjustmentReason = if (appliedOutput < requested) {
                    OutputAdjustmentReason.ReducedForKvCapacity
                } else {
                    OutputAdjustmentReason.None
                },
                admittedSearchEvidenceCount = SearchEvidencePromptCompactor.admittedResultCount(content),
            )
        }
        return FittedMessages(emptyList(), true, requestedOutputTokens, OutputAdjustmentReason.None)
    }

    private fun tokensOf(message: ChatMessage): Int =
        PromptTokenCost.estimateMessage(message.content)

    private fun tokensOf(messages: List<ChatMessage>): Int = messages.sumOf(::tokensOf)

    /**
     * Groups history messages into whole coherent user/assistant turns so a
     * KV-trim never splits a user message from its assistant answer. Orphan and
     * duplicate assistant messages (branch selections should already have been
     * resolved upstream) are omitted, mirroring [PromptHistoryBudgeter].
     */
    private fun coherentTurns(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val turns = mutableListOf<MutableList<ChatMessage>>()
        messages.forEach { message ->
            when (message.role) {
                "user" -> turns += mutableListOf(message)
                "assistant" -> {
                    val current = turns.lastOrNull()
                    if (current != null && current.firstOrNull()?.role == "user" && current.none { it.role == "assistant" }) {
                        current += message
                    }
                }
            }
        }
        return turns
    }

}

internal enum class OutputAdjustmentReason {
    None,
    ReducedForKvCapacity,
}
