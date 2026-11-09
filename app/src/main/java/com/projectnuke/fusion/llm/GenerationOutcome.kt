package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.AcceleratorMode

/**
 * Typed result for every LLM generation call. Failures must never be encoded
 * as assistant text. [Success] is a completed answer; [Cancelled] is a
 * distinct user-visible termination outcome and may carry an authorized
 * partial answer.
 */
sealed interface GenerationOutcome {
    /**
     * A successful generation. [text] contains the model's reply (sanitized
     * by the prompt adapter). [actualBackend] reports the backend that the
     * runtime actually used, when reported by the engine — retained for
     * transitional callers; new callers should read [snapshot].selectedTextBackend.
     * [truncated] is true when the app-level streaming output limit cut the
     * reply short. [stats] carries the native runtime benchmark snapshot of
     * the last generation when the runtime reports one. [snapshot] is the
     * immutable runtime execution snapshot that is the single source of truth
     * for runtime state; callers must not reread mutable engine state after
     * generation to reconstruct the result.
     */
    data class Success(
        val text: String,
        val actualBackend: String? = null,
        val truncated: Boolean = false,
        val stats: GenerationBenchmarkStats? = null,
        val snapshot: RuntimeExecutionSnapshot? = null
    ) : GenerationOutcome

    /**
     * Generation was cancelled before completing. [partialText] is sanitized
     * output and may be durably adopted only when the upper layer has recorded
     * a typed USER_STOP for this exact request. Structural cancellations must
     * not persist it, and blank partial text is never stored.
     */
    data class Cancelled(
        val partialText: String = "",
        val terminationIntent: GenerationTerminationIntent? = null,
    ) : GenerationOutcome

    /**
     * Generation completed with no usable output. Must not be stored as a
     * fabricated assistant answer.
     */
    data object Empty : GenerationOutcome

    /**
     * Generation failed with a recoverable error. [kind] is a stable
     * machine-readable category; [message] is a user-safe localized
     * explanation never populated from raw server/native output.
     *
     * [attemptSnapshot] carries the immutable acquisition-failure snapshot
     * so callers never reread engine state after an *acquisition* failure.
     * Null when acquisition succeeded but the generation itself crashed.
     *
     * [failureSnapshot] carries the immutable generation-after-acquisition
     * failure snapshot — the requested accelerator, the selected text/vision
     * backend of the running engine, the MTP runtime status, the fallback
     * events from acquisition, and the generation failure kind. Null when
     * the failure happened during acquisition, not after.
     *
     * Exactly one of [attemptSnapshot] / [failureSnapshot] is non-null for
     * any typed failure; the legacy constructor keeps [attemptSnapshot]
     * nullable for older call sites that have not yet been migrated.
     */
    data class Failure(
        val kind: FailureKind,
        val message: String,
        val attemptSnapshot: RuntimeAttemptSnapshot? = null,
        val failureSnapshot: RuntimeFailureSnapshot? = null
    ) : GenerationOutcome
}

/**
 * Stable failure categories shared across engines and callers. Avoid leaking
 * raw vendor strings through the public API.
 */
enum class FailureKind {
    MODEL_NOT_FOUND,
    MODEL_LOAD_FAILED,
    MODEL_MULTIMODAL_UNSUPPORTED,
    IMAGE_NOT_FOUND,
    GENERATION_IO,
    GENERATION_INTERRUPTED,
    PROMPT_TEMPLATE_INCOMPATIBLE,
    UNKNOWN
}

/**
 * Native runtime benchmark snapshot of the last generation, when the runtime
 * reports one. Distilled from the vendor BenchmarkInfo so callers never touch
 * vendor types; zeros mean the runtime did not measure that metric.
 */
data class GenerationBenchmarkStats(
    val initTimeSeconds: Double,
    val timeToFirstTokenSeconds: Double,
    val prefillTokenCount: Int,
    val decodeTokenCount: Int,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double
)
