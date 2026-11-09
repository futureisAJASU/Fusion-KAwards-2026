package com.projectnuke.fusion.model

enum class AcceleratorMode {
    AUTO,
    GPU,
    CPU
}

data class GenerationSettings(
    val maxTokens: Int = 4000,
    val kvCacheCapacityTokens: Int = 4096,
    val topK: Int = 64,
    val topP: Float = 0.95f,
    val temperature: Float = 1.0f,
    val accelerator: AcceleratorMode = AcceleratorMode.GPU,
    val reasoningBudgetTokens: Int = 512,
    val speculativeDecodingEnabled: Boolean? = null
)

/**
 * Policy for KV cache capacity defaults and limits.
 * Centralized so callers don't scatter hardcoded values.
 */
object KvCacheCapacityPolicy {
    /**
     * Default KV cache capacity in TOKENS (not bytes).
     *
     * This value maps directly to LiteRT-LM `EngineConfig.maxNumTokens`, which is
     * the combined input + output token capacity of the KV cache. The actual
     * memory footprint depends on the model's layer count, head count, head
     * dimension and cache dtype — it is NOT "4 bytes per token" and cannot be
     * derived from this number alone. (The previous comment claiming
     * "4K tokens ≈ ~16KB at 4 bytes/token" was off by orders of magnitude for
     * every real model and must not be used for memory planning.)
     */
    const val DEFAULT_CAPACITY = 4096

    /** Minimum KV cache capacity in tokens. */
    const val MIN_CAPACITY = 1024

    /** Maximum KV cache capacity in tokens (32K tokens). */
    const val MAX_CAPACITY = 32768

    /**
     * Returns the default KV cache capacity for a model based on its recommended settings.
     * Falls back to [DEFAULT_CAPACITY] if no model-specific recommendation exists.
     */
    fun defaultForModel(recommendedMaxTokens8Gb: Int): Int {
        // KV cache should be at least large enough for prompt + output
        // Use 2x the recommended output as a heuristic for prompt+output budget
        return (recommendedMaxTokens8Gb * 2).coerceIn(MIN_CAPACITY, MAX_CAPACITY)
    }

    /**
     * Validates that the requested output budget fits within the KV cache capacity.
     * Returns the adjusted output budget if it exceeds capacity, or the original if it fits.
     * The adjustment ensures: promptTokens + outputTokens <= kvCacheCapacity
     * Since we don't know prompt tokens at settings-save time, we use a heuristic:
     * assume prompt ≈ output budget for the check, so 2 * output <= capacity.
     */
    fun validateOutputBudget(
        requestedOutputTokens: Int,
        kvCacheCapacityTokens: Int,
        estimatedPromptTokens: Int = 0
    ): Int {
        val maxOutputForCapacity = (kvCacheCapacityTokens - estimatedPromptTokens).coerceAtLeast(1)
        return requestedOutputTokens.coerceAtMost(maxOutputForCapacity)
    }

    /**
     * Returns the KV cache capacity to use for low-memory benchmark mode.
     * Uses a reduced capacity to avoid OOM during benchmark runs.
     */
    fun lowMemoryBenchmarkCapacity(): Int = MIN_CAPACITY
}
