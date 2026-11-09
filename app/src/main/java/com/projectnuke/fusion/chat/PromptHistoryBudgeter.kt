package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage

internal data class PromptHistoryBudgetRequest(
    val history: List<ChatMessage>,
    val modelId: String?,
    val generationModeKey: String,
    val maxOutputTokens: Int,
    val currentRequestChars: Int,
    val attachmentCount: Int,
    val webSearchPlanned: Boolean,
    val summaryText: String? = null,
    /**
     * Explicit Engine KV capacity in tokens for LOCAL (on-device) LiteRT
     * requests. When non-null the selection shares the same token-capacity
     * model as [FinalPromptBudgeter] (prompt + output + safety margin <= KV);
     * when null the legacy model-name char heuristic is used (external /
     * legacy local callers).
     */
    val engineKvCapacityTokens: Int? = null,
    /** Exact current request when available; otherwise its length is costed conservatively. */
    val currentRequestText: String? = null,
)

internal data class PromptHistorySelection(
    val messages: List<ChatMessage>,
    val omittedTurnCount: Int,
    val usedSummary: Boolean,
)

internal object PromptHistoryBudgeter {
    private const val MAX_SUMMARY_CHARS = 1_500
    private const val MESSAGE_OVERHEAD_CHARS = 32
    private const val ATTACHMENT_RESERVE_TOKENS = 1_000
    private const val WEB_RESERVE_TOKENS = 3_000
    private const val KV_SAFETY_MARGIN_TOKENS = FinalPromptBudgeter.DEFAULT_SAFETY_MARGIN_TOKENS

    fun select(request: PromptHistoryBudgetRequest): PromptHistorySelection {
        // Host-only FUSION markers must never participate in history cost
        // calculations: runtime metadata must not change which semantic turns
        // survive. Exact internal system-marker forms only (see
        // [FusionHostMarkerPolicy]).
        val semanticRequest = request.copy(
            history = request.history.filterNot(FusionHostMarkerPolicy::isHostOnlyMarker)
        )
        val kv = semanticRequest.engineKvCapacityTokens
            ?.takeIf { it > 0 && !semanticRequest.generationModeKey.contains("EXTERNAL", ignoreCase = true) }
        return if (kv != null) {
            selectForKv(semanticRequest, kv)
        } else {
            selectForChars(semanticRequest)
        }
    }

    private fun selectForChars(request: PromptHistoryBudgetRequest): PromptHistorySelection {
        val contextChars = contextBudgetChars(request.modelId, request.generationModeKey)
        val remaining = if (request.generationModeKey.contains("EXTERNAL", ignoreCase = true)) {
            ExternalPromptCapacityPolicy.availablePromptChars(
                maxOutputTokens = request.maxOutputTokens,
                currentRequestChars = request.currentRequestChars,
                attachmentCount = request.attachmentCount,
                webSearchPlanned = request.webSearchPlanned,
            )
        } else {
            (contextChars - request.currentRequestChars.coerceAtLeast(0) -
                request.maxOutputTokens.coerceAtLeast(0) * 4 -
                request.attachmentCount.coerceAtLeast(0) * 2_000 -
                if (request.webSearchPlanned) 6_000 else 0).coerceAtLeast(0)
        }
        // Mandatory content may consume the entire context. Never invent history
        // capacity that would exceed the selected provider/model budget.
        var remainingChars = remaining

        val systemMessages = request.history
            .filter { it.role == "system" }
            .toList()
        systemMessages.forEach { remainingChars -= cost(it) }

        val turns = coherentTurns(request.history.filter { it.role != "system" })
        val selectedReversed = mutableListOf<List<ChatMessage>>()
        var omitted = 0
        val newestFirst = turns.asReversed()
        for ((index, turn) in newestFirst.withIndex()) {
            val turnCost = turn.sumOf(::cost)
            if (turnCost <= remainingChars) {
                selectedReversed += turn
                remainingChars -= turnCost
            } else {
                omitted += newestFirst.size - index
                break
            }
        }

        val selected = selectedReversed.asReversed().flatten()
        val summary = buildSummary(request.summaryText, omitted, remainingChars, ::cost)

        return PromptHistorySelection(
            messages = buildList {
                addAll(systemMessages)
                if (summary != null) add(summary)
                addAll(selected)
            },
            omittedTurnCount = omitted,
            usedSummary = summary != null,
        )
    }

    /**
     * KV-aware local selection uses the same token estimator and safety margin
     * as [FinalPromptBudgeter]: system messages are mandatory, history turns are
     * kept whole (user+assistant pairs) newest-first while
     *   systemTokens + historyTokens + currentRequestTokens + outputTokens +
     *   attachmentsTokens + webTokens + safetyMargin <= engineKvCapacityTokens
     * The requested output reserve is capped at the usable capacity so a huge
     * requested output alone never zeroes every history turn.
     */
    private fun selectForKv(request: PromptHistoryBudgetRequest, kvCapacityTokens: Int): PromptHistorySelection {
        val usableTokens = (kvCapacityTokens - KV_SAFETY_MARGIN_TOKENS).coerceAtLeast(1)

        val systemMessages = request.history
            .filter { it.role == "system" }
            .toList()
        val systemTokens = systemMessages.sumOf(::costInTokens)

        val turns = coherentTurns(request.history.filter { it.role != "system" })

        val currentRequestTokens = request.currentRequestText?.let { PromptTokenCost.estimateMessage(it) }
            ?: (PromptTokenCost.estimateUnknownContentLength(request.currentRequestChars) + PromptTokenCost.MESSAGE_OVERHEAD_TOKENS)
        val attachmentTokens = request.attachmentCount.coerceAtLeast(0) * ATTACHMENT_RESERVE_TOKENS
        val webTokens = if (request.webSearchPlanned) WEB_RESERVE_TOKENS else 0
        val requestedReserve =
            currentRequestTokens + request.maxOutputTokens.coerceAtLeast(0) + attachmentTokens + webTokens
        val reserveTokens = requestedReserve.coerceAtMost(usableTokens)

        var remaining = (usableTokens - systemTokens - reserveTokens).coerceAtLeast(0)

        val selectedReversed = mutableListOf<List<ChatMessage>>()
        var omitted = 0
        val newestFirst = turns.asReversed()
        for ((index, turn) in newestFirst.withIndex()) {
            val cost = tokensOf(turn)
            if (cost <= remaining) {
                selectedReversed += turn
                remaining -= cost
            } else {
                omitted += newestFirst.size - index
                break
            }
        }

        val selected = selectedReversed.asReversed().flatten()
        val summary = buildSummary(request.summaryText, omitted, remaining, ::costInTokens)

        return PromptHistorySelection(
            messages = buildList {
                addAll(systemMessages)
                if (summary != null) add(summary)
                addAll(selected)
            },
            omittedTurnCount = omitted,
            usedSummary = summary != null,
        )
    }

    private fun buildSummary(
        summaryText: String?,
        omitted: Int,
        remaining: Int,
        cost: (ChatMessage) -> Int,
    ): ChatMessage? = summaryText
        ?.trim()
        ?.take(MAX_SUMMARY_CHARS)
        ?.takeIf { it.isNotBlank() && omitted > 0 }
        ?.let { ChatMessage(role = "system", content = "OLDER_CONVERSATION_SUMMARY\n$it") }
        ?.takeIf { cost(it) <= remaining }

    private fun tokensOf(messages: List<ChatMessage>): Int = messages.sumOf(::costInTokens)

    private fun costInTokens(message: ChatMessage): Int = PromptTokenCost.estimateMessage(message.content)

    private fun contextBudgetChars(modelId: String?, generationModeKey: String): Int {
        if (generationModeKey.contains("EXTERNAL", ignoreCase = true)) return ExternalPromptCapacityPolicy.CONTEXT_LIMIT_CHARS
        val normalized = modelId.orEmpty().lowercase()
        return when {
            "e4b" in normalized || "4b" in normalized -> 24_000
            "e2b" in normalized || "2b" in normalized -> 16_000
            else -> 20_000
        }
    }

    private fun coherentTurns(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val turns = mutableListOf<MutableList<ChatMessage>>()
        messages.forEach { message ->
            when (message.role) {
                "user" -> turns += mutableListOf(message)
                "assistant" -> {
                    val current = turns.lastOrNull()
                    if (current != null && current.firstOrNull()?.role == "user" &&
                        current.none { it.role == "assistant" }
                    ) {
                        current += message
                    }
                    // Orphan and duplicate assistant branches are omitted. Branch selection
                    // must happen before this budget boundary.
                }
            }
        }
        return turns
    }

    private fun cost(message: ChatMessage): Int = message.content.length + PromptTokenCost.MESSAGE_OVERHEAD_CHARS
}
