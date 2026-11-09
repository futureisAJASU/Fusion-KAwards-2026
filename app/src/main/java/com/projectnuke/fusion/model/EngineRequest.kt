package com.projectnuke.fusion.model

/**
 * Engine-creation-time settings for a LiteRT-LM Engine.
 *
 * Every field here decides how the native Engine is built (backend selection,
 * KV cache capacity, speculative-decoding flag, vision backend). Changing any
 * field rebuilds the Engine; changing none of them reuses the loaded Engine.
 * Per-turn sampling/limits live in [ConversationOptions] instead.
 */
data class RequestedEngineProfile(
    val modelPath: String,
    val accelerator: AcceleratorMode,
    val mtpRequested: Boolean,
    /**
     * KV cache capacity in tokens, mapped to EngineConfig.maxNumTokens.
     * This is the combined input/output token capacity of the KV cache, NOT a
     * response length limit; the response cap lives in
     * [ConversationOptions.maxOutputToken]. Changing this value rebuilds the
     * Engine (the KV cache is allocated at engine creation).
     */
    val kvCacheCapacityTokens: Int,
    val enableVisionBackend: Boolean,
    /** Stable catalog provenance used by exact prompt-template compatibility. */
    val modelIdentity: String? = null,
    /** Resolved global CPU budget captured for the next engine/session acquisition. */
    val cpuThreadCount: Int = 0,
    val cpuPerformancePreference: RuntimePerformancePreference = RuntimePerformancePreference(),
)

/**
 * Per-turn generation options for a single Conversation.
 *
 * Changing any field never rebuilds the Engine; each request creates a new
 * Conversation with these options applied as the SamplerConfig.
 */
data class ConversationOptions(
    /**
     * Explicit app-level streaming output limit in tokens. The pinned
     * litertlm-android 0.14.0 API has no ConversationConfig.maxOutputToken,
     * so enforcement is implemented by the caller when this is non-null.
     */
    val maxOutputToken: Int? = null,
    val temperature: Float = 1.0f,
    val topK: Int = 64,
    val topP: Float = 0.95f,
    val seed: Int? = null,
)

/**
 * Backward-compatible migration: builds the engine-creation profile from a
 * [GenerationSettings] value. [modelPath] is the path as passed by the caller;
 * the engine resolves the runnable model before building its cache identity.
 */
fun GenerationSettings.toRequestedEngineProfile(
    modelPath: String,
    enableVisionBackend: Boolean,
    modelIdentity: String? = null,
): RequestedEngineProfile = RequestedEngineProfile(
    modelPath = modelPath,
    accelerator = accelerator,
    mtpRequested = speculativeDecodingEnabled == true,
    kvCacheCapacityTokens = kvCacheCapacityTokens.coerceAtLeast(KvCacheCapacityPolicy.MIN_CAPACITY).coerceAtMost(KvCacheCapacityPolicy.MAX_CAPACITY),
    enableVisionBackend = enableVisionBackend,
    modelIdentity = modelIdentity,
)

/**
 * Backward-compatible migration: builds the per-turn options from a
 * [GenerationSettings] value using zero prompt-size estimate.
 *
 * The default form must NOT pre-clamp `maxOutputToken` against the KV cache
 * using a heuristic prompt estimate: doing so with the broken "prompt ≈ output"
 * heuristic reduces the user-visible output cap from 4000 to 96 tokens
 * when defaults are used (maxTokens=4000, kvCacheCapacityTokens=4096).
 *
 * Runtime callers that know the actual fitted prompt size should use the
 * [toConversationOptions] overload so [KvCacheCapacityPolicy.validateOutputBudget]
 * is only applied when there is real prompt information to compare against.
 */
fun GenerationSettings.toConversationOptions(): ConversationOptions = toConversationOptions(
    estimatedPromptTokens = 0
)

/**
 * Overload that clamps the requested output budget against the KV cache capacity
 * using the caller's measured prompt-token estimate. Use this at the call site
 * where the actual fitted prompt is known (e.g., after
 * [com.projectnuke.fusion.chat.FinalPromptBudgeter.fit]). The output cap is
 * reduced only when the user-requested output plus the prompt estimate would
 * exceed the KV cache; otherwise the user-requested output is honored.
 *
 * Pass [estimatedPromptTokens] = 0 to skip the clamp entirely (the default
 * `toConversationOptions()` form does this).
 */
fun GenerationSettings.toConversationOptions(estimatedPromptTokens: Int): ConversationOptions {
    val validatedMaxOutput = KvCacheCapacityPolicy.validateOutputBudget(
        requestedOutputTokens = maxTokens.coerceAtLeast(1),
        kvCacheCapacityTokens = kvCacheCapacityTokens.coerceAtLeast(KvCacheCapacityPolicy.MIN_CAPACITY),
        estimatedPromptTokens = estimatedPromptTokens.coerceAtLeast(0)
    )
    return ConversationOptions(
        maxOutputToken = validatedMaxOutput,
        temperature = temperature,
        topK = topK,
        topP = topP,
        seed = null,
    )
}
