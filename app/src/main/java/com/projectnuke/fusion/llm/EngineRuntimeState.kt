package com.projectnuke.fusion.llm

import com.google.ai.edge.litertlm.Engine
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.model.RuntimePerformancePreference
import com.projectnuke.fusion.modelzoo.LiteRtLmPackageValidator
import java.io.File

/**
 * Canonical identity of a model file at load time: the resolved path plus the
 * file attributes and package capabilities that determine whether a previously
 * loaded engine can be reused. Any change (file replaced, drafter capability
 * changed, validator version bumped) invalidates the loaded runtime.
 */
internal data class ModelFingerprint(
    val canonicalPath: String,
    val fileSize: Long,
    val modifiedAt: Long,
    val validationVersion: Int,
    val mtpSupported: Boolean
) {
    companion object {
        fun of(modelPath: String): ModelFingerprint {
            val file = File(modelPath)
            val canonicalPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
            val validation = LiteRtLmPackageValidator.validate(file).getOrNull()
            return ModelFingerprint(
                canonicalPath = canonicalPath,
                fileSize = runCatching { file.length() }.getOrDefault(0L),
                modifiedAt = runCatching { file.lastModified() }.getOrDefault(0L),
                validationVersion = validation?.validationVersion ?: 0,
                mtpSupported = validation?.hasDrafter == true
            )
        }
    }
}

/**
 * Typed engine identity: everything that requires an Engine (re)build. This is
 * the successor of the old concatenated string cache key, so a change in any
 * field means the loaded engine no longer matches the requested profile.
 */
internal data class EngineRuntimeKey(
    val fingerprint: ModelFingerprint,
    val accelerator: AcceleratorMode,
    val kvCacheCapacityTokens: Int,
    val enableVisionBackend: Boolean,
    val mtpEnabled: Boolean,
    val selectedBackend: String,
    /** Zero when no selected runtime component uses CPU; inactive fallback changes do not reload GPU. */
    val cpuThreadCount: Int = 0,
    val cpuPerformancePreference: RuntimePerformancePreference? = null,
    /** A GPU text engine may still have an active CPU vision fallback. */
    val selectedVisionBackend: String? = null,
)

/**
 * Pure reuse-gate decision: true when the engine built for [loadedKey] can
 * satisfy [profile] without a fresh Engine build.
 *
 * Every [EngineRuntimeKey] field that [profile] determines participates, so a
 * caller can rely on `true` to mean "no cold build" (warm-only background
 * work, RAM admission that assumes reuse, acquiring-stage reporting).
 * Deliberately conservative in one direction: when MTP was requested but the
 * loaded engine runs without it because failure memory skipped the MTP rung,
 * the candidate ladder could still reuse the loaded engine at its plain rung,
 * yet this gate returns false. Predicting that would require consulting
 * failure memory; erring toward "rebuild" only skips opportunistic work and
 * never admits a cold build as warm.
 */
internal fun isLoadedKeyReusableFor(
    loadedKey: EngineRuntimeKey,
    profile: RequestedEngineProfile,
    fingerprint: ModelFingerprint,
): Boolean {
    if (loadedKey.accelerator != profile.accelerator) return false
    if (loadedKey.kvCacheCapacityTokens != profile.kvCacheCapacityTokens) return false
    if (loadedKey.enableVisionBackend != profile.enableVisionBackend) return false
    if (loadedKey.fingerprint != fingerprint) return false
    val cpuExecutionActive = loadedKey.selectedBackend == "CPU" || loadedKey.selectedVisionBackend == "CPU"
    val requestedCpuThreads = if (cpuExecutionActive) profile.cpuThreadCount else 0
    if (loadedKey.cpuThreadCount != requestedCpuThreads) return false
    // MTP participates in the Engine build: compare against the effective
    // request, since models without drafter support never build MTP engines
    // regardless of the requested flag.
    val effectiveMtpRequested = profile.mtpRequested && fingerprint.mtpSupported
    if (loadedKey.mtpEnabled != effectiveMtpRequested) return false
    return true
}

/**
 * The one loaded native runtime plus the typed identity it was built for.
 * Replaces the scattered engine/backend/status fields so reuse decisions and
 * status reporting read from a single consistent snapshot.
 */
internal data class LoadedRuntimeState<T>(
    val engine: T,
    val key: EngineRuntimeKey,
    val mtpStatus: MtpRuntimeStatus,
    val runtimeSelection: EngineSelectionRuntime,
    val actualTextBackend: String,
    val actualVisionBackend: String?,
    /**
     * Stable events belonging to the Engine acquisition (e.g. MTP_ENGINE_INIT_FAILED,
     * GPU_TEXT_ENGINE_FAILED_CPU_SELECTED). Set once on fresh Engine init and never
     * mutated. Request-local cooldown/skip events do NOT live here — they are carried
     * in [LiteRtLlmEngine.currentRequestFallbackEvents] for the lifetime of a single
     * generate call and are combined with this list at snapshot-build time.
     */
    val fallbackEvents: List<RuntimeFallbackEvent> = emptyList()
)
