package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage

/**
 * Estimates prompt-side token count from a fitted message list.
 *
 * Used by the per-turn `toConversationOptions(estimatedPromptTokens)` path
 * so [com.projectnuke.fusion.model.KvCacheCapacityPolicy.validateOutputBudget]
 * is invoked with real fitted-prompt information rather than the
 * settings-time "prompt ≈ output" heuristic that previously clamped
 * `maxOutputToken` to ~96 tokens at default settings.
 *
 * Uses the same conservative script-aware estimate as local prompt fitting,
 * with per-message headroom for role/template tokens.
 */
internal object PromptTokenEstimator {

    /** Estimate tokens for a fitted-prompt message list. Never returns negative. */
    fun estimateTokens(messages: List<ChatMessage>): Int {
        return PromptTokenCost.estimateMessages(messages)
    }

    /** Estimate tokens for a single user-prompt string (no role overhead). */
    fun estimateTokensForString(content: String): Int =
        PromptTokenCost.estimateContent(content)
}
