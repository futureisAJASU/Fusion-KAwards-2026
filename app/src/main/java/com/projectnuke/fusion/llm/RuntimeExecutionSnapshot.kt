package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.RuntimePerformanceMode

/**
 * Backend actually selected for text inference by the runtime candidate
 * ladder. [UNKNOWN] covers the case where the runtime reported no backend or
 * the value could not be observed.
 */
enum class RuntimeBackend {
    CPU,
    GPU,
    UNKNOWN
}

/**
 * Backend selected for a runtime component (e.g. the sampler). Distinct from
 * [RuntimeBackend] because [Backend.GPU] success does not imply every component
 * executed on GPU; [UNKNOWN] is reported unless a stable LiteRT-LM API confirms
 * the component's placement.
 */
enum class RuntimeComponentBackend {
    CPU,
    GPU,
    UNKNOWN
}

/**
 * Stable, machine-readable model fingerprint summary carried on every
 * successful generation so callers (metrics, persistence, A/B results) can
 * observe the exact model identity without reading mutable engine state.
 */
data class ModelFingerprintSummary(
    val canonicalPath: String,
    val fileSize: Long,
    val modifiedAt: Long,
    val validationVersion: Int,
    val mtpSupported: Boolean
)

/**
 * Stable reason codes for app-level runtime fallbacks. Never derived from raw
 * native exception text. Phase 3 populates the full set; Phase 2 introduces
 * the stable surface so callers persist codes rather than English strings.
 */
enum class FallbackReason {
    MTP_UNSUPPORTED,
    MTP_SKIPPED_RECENT_FAILURE,
    BACKEND_SKIPPED_RECENT_FAILURE,
    BACKEND_ENGINE_INIT_FAILED,
    SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED,
    SPECULATIVE_DISABLE_FLAG_SETTLEMENT_FAILED,
    MTP_ENGINE_INIT_FAILED,
    GPU_TEXT_ENGINE_FAILED_CPU_SELECTED,
    GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED,
    ALL_CANDIDATES_EXHAUSTED,
    ALL_CANDIDATES_SKIPPED_RECENT_FAILURE
}

/**
 * One app-level runtime fallback captured during candidate selection. Records,
 * when applicable, the attempted text backend, the attempted vision backend,
 * the attempted MTP state, the selected replacement backend, and a stable
 * reason code. Native exception text is never exposed to users via this type.
 */
data class RuntimeFallbackEvent(
    val attemptedTextBackend: RuntimeBackend? = null,
    val attemptedVisionBackend: RuntimeBackend? = null,
    val attemptedMtpEnabled: Boolean? = null,
    val selectedReplacementBackend: RuntimeBackend? = null,
    val reason: FallbackReason
)

/**
 * One immutable snapshot of the runtime execution that produced a successful
 * generation. The single source of truth for visible metrics, benchmark
 * persistence, A/B result persistence, copied diagnostics and developer logs.
 * Callers must NOT reread [LiteRtLlmEngine.lastMtpStatus] or
 * [LiteRtLlmEngine.lastRuntimeSelection] after generation to reconstruct it.
 */
data class RuntimeExecutionSnapshot(
    val requestedAccelerator: AcceleratorMode,
    val selectedTextBackend: RuntimeBackend,
    val selectedVisionBackend: RuntimeBackend?,
    val samplerBackend: RuntimeComponentBackend,
    val mtpRequested: Boolean,
    val mtpStatus: MtpRuntimeStatus,
    val fallbackEvents: List<RuntimeFallbackEvent>,
    val modelFingerprint: ModelFingerprintSummary,
    /** CPU execution budget, or the current CPU fallback budget for a GPU run. */
    val cpuThreadCount: Int? = null,
    val cpuPerformanceMode: RuntimePerformanceMode? = null,
)

/**
 * One immutable snapshot of a failed engine acquisition attempt. Captures
 * the requested accelerator, the fallback events that occurred during
 * candidate selection, and the model fingerprint. Unlike [RuntimeExecutionSnapshot],
 * this does not require a successful engine selection and is attached to
 * [GenerationOutcome.Failure] so callers never reread engine state after failure.
 */
data class RuntimeAttemptSnapshot(
    val requestedAccelerator: AcceleratorMode,
    val fallbackEvents: List<RuntimeFallbackEvent>,
    val modelFingerprint: ModelFingerprintSummary,
    val mtpRequested: Boolean
)

/**
 * One immutable snapshot of a failed generation that happened *after* a
 * successful Engine acquisition — i.e. the engine was selected and created
 * but the generation itself crashed. This carries the full acquisition
 * lineage (so callers see the requested/applied/MTP state and the fallback
 * events that led up to the running engine) plus the [FailureKind] of the
 * generation error, so consumers never reread mutable engine state.
 *
 * Distinct from [RuntimeAttemptSnapshot]:
 *  - [RuntimeAttemptSnapshot] = acquisition failed before backend selection.
 *    No engine was selected; `selectedTextBackend` is meaningless.
 *  - [RuntimeFailureSnapshot] = acquisition succeeded and an Engine was
 *    selected; the generation then failed. `selectedTextBackend` and
 *    `mtpStatus` reflect the running engine that was used.
 */
data class RuntimeFailureSnapshot(
    val requestedAccelerator: AcceleratorMode,
    val selectedTextBackend: RuntimeBackend,
    val selectedVisionBackend: RuntimeBackend?,
    val mtpRequested: Boolean,
    val mtpRuntimeStatus: MtpRuntimeStatus,
    val fallbackEventsFromAcquisition: List<RuntimeFallbackEvent>,
    val modelFingerprint: ModelFingerprintSummary,
    val failureKind: FailureKind,
    val cpuThreadCount: Int? = null,
    val cpuPerformanceMode: RuntimePerformanceMode? = null,
)

/**
 * Infers the MTP runtime status from the fallback events in a failed attempt.
 * Returns FAILED if all candidates exhausted, FALLBACK_DISABLED if MTP was
 * attempted but fell back, UNSUPPORTED if MTP capability was rejected,
 * OFF if MTP was not requested.
 */
fun RuntimeAttemptSnapshot.inferredMtpStatus(): MtpRuntimeStatus = when {
    !mtpRequested -> MtpRuntimeStatus.OFF
    // Terminal failures take precedence. An all-candidates-exhausted or
    // all-candidates-skipped failure means the entire acquisition ladder
    // was defeated — even if an earlier MTP candidate contributed a
    // cooldown or init-failure event, the terminal conclusion is FAILED.
    fallbackEvents.any { it.reason == FallbackReason.ALL_CANDIDATES_EXHAUSTED } -> MtpRuntimeStatus.FAILED
    fallbackEvents.any { it.reason == FallbackReason.ALL_CANDIDATES_SKIPPED_RECENT_FAILURE } -> MtpRuntimeStatus.FAILED
    fallbackEvents.any { it.reason == FallbackReason.MTP_UNSUPPORTED } -> MtpRuntimeStatus.UNSUPPORTED
    fallbackEvents.any { it.reason == FallbackReason.MTP_SKIPPED_RECENT_FAILURE } -> MtpRuntimeStatus.FALLBACK_DISABLED
    fallbackEvents.any { it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED } -> MtpRuntimeStatus.FALLBACK_DISABLED
    else -> MtpRuntimeStatus.FAILED
}
