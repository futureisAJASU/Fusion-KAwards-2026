package com.projectnuke.fusion.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.RequestedEngineProfile

/**
 * Production acquisition coordinator extracted from [LiteRtLlmEngine.getOrCreateEngine].
 *
 * Encapsulates the full candidate selection and fallback logic so it can be
 * tested with injected dependencies without depending on the native Engine.
 * The coordinator is stateless (except for the failure memory which is
 * passed in) and deterministic given the same inputs.
 *
 * Generic over the engine handle type: production uses the native [Engine],
 * unit tests use a plain fake handle.
 */
internal class EngineAcquisitionCoordinator<T>(
    private val mtpFailureMemory: MtpFailureMemoryPort,
    private val mtpCapabilityProbe: (String) -> Boolean?,
    private val configureFlag: (Boolean) -> Boolean,
    private val engineFactory: (backendName: String, mtpEnabled: Boolean, visionBackendIsCpu: Boolean) -> EngineCandidateAttempt<T>,
    /**
     * Deterministic clock retained on the construction signature so callers
     * (production and tests) keep a single injection point for time. The
     * coordinator itself performs no time arithmetic — cooldown timing lives
     * entirely inside the [MtpFailureMemoryPort] implementation, which owns its
     * own clock. Kept intentionally rather than removed so a future coordinator
     * -level timing decision has an existing seam.
     */
    @Suppress("unused") private val clock: () -> Long = { System.currentTimeMillis() },
    private val onPersistenceCompleted: () -> Unit = {},
    private val fingerprintResolver: (String) -> ModelFingerprint = { ModelFingerprint.of(it) }
) {

    /**
     * Attempts to acquire an engine for the given profile.
     *
     * Returns the selected engine on success, or an [EngineSelectionOutcome]
     * with the failure and fallback events. The caller is responsible for
     * updating its own loaded state based on the outcome.
     */
    fun acquire(profile: RequestedEngineProfile, loadedState: LoadedRuntimeState<T>?): EngineSelectionOutcome<T> {
        val mtpRequested = profile.mtpRequested
        val fingerprint = fingerprintResolver(profile.modelPath)
        val mtpSupported = fingerprint.mtpSupported

        val plannedCandidates = buildEngineCandidateLadder(
            accelerator = profile.accelerator,
            mtpRequested = mtpRequested,
            mtpSupported = mtpSupported
        )

        // NOTE: We no longer pre-filter candidates through failure memory here.
        // Failure memory is now checked lazily per-candidate inside selectFirstWorkingEngine
        // so that only actually-reached candidates generate fallback events.
        val ladder = plannedCandidates

        // Delegate to the selector with loaded-state reuse check at each candidate
        var mtpProbeResult: Boolean? = null
        val selection = selectFirstWorkingEngine(
            ladder = ladder,
            enableVisionBackend = profile.enableVisionBackend,
            configureFlag = configureFlag,
            tryCreate = { backend, mtp, visionCpu ->
                if (mtp) {
                    val probeResult = mtpCapabilityProbe(profile.modelPath)
                    mtpProbeResult = probeResult
                    if (probeResult == false) {
                        EngineCandidateAttempt.CapabilityRejected(
                            backendName = backend,
                            mtpEnabled = true
                        )
                    } else {
                        engineFactory(backend, mtp, visionCpu)
                    }
                } else {
                    engineFactory(backend, mtp, visionCpu)
                }
            },
            loadedState = loadedState,
            fingerprint = fingerprint,
            accelerator = profile.accelerator,
            kvCacheCapacityTokens = profile.kvCacheCapacityTokens,
            enableVisionBackendProfile = profile.enableVisionBackend,
            mtpFailureMemory = mtpFailureMemory,
            profile = profile,
            onPersistenceCompleted = onPersistenceCompleted
        )
        // Preserve probe result truthfully: true → true, false → false, null → null
        // Do NOT infer capability from MTP initialization success
        val mtpCapabilityResult = when {
            !mtpRequested || !mtpSupported -> null
            mtpProbeResult == false -> false
            mtpProbeResult == true -> true
            else -> null
        }

        // The selector is the sole producer of fallback events; the coordinator
        // only appends terminal events below.
        val allEvents = selection.fallbackEvents

        // Record MTP failure only when MTP was actually attempted and produced a typed init failure
        val mtpInitFailedEvent = selection.fallbackEvents.filter {
            it.attemptedMtpEnabled == true && it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED
        }.lastOrNull()
        if (mtpInitFailedEvent != null) {
            val mtpBackendName = mtpInitFailedEvent.attemptedTextBackend?.name ?: (plannedCandidates.firstOrNull { it.mtpEnabled && it.backend == "GPU" }?.backend ?: "GPU")
            mtpFailureMemory.recordFailure(
                modelPath = fingerprint.canonicalPath,
                backendName = mtpBackendName,
                mtpEnabled = true,
                validationVersion = fingerprint.validationVersion,
                kvCacheCapacityTokens = profile.kvCacheCapacityTokens,
                enableVisionBackend = profile.enableVisionBackend,
                fileSize = fingerprint.fileSize,
                modifiedAt = fingerprint.modifiedAt,
                mtpSupported = fingerprint.mtpSupported,
                fallbackReason = mtpInitFailedEvent.attemptedVisionBackend?.name?.let { "MTP initialization failed (vision=$it)" } ?: "MTP initialization failed"
            )
            onPersistenceCompleted()
        }

        // Record plain backend failures with accurate typed identity
        for (ev in selection.fallbackEvents) {
            if (ev.attemptedMtpEnabled == false &&
                ev.attemptedTextBackend != null &&
                ev.reason == FallbackReason.BACKEND_ENGINE_INIT_FAILED
            ) {
                mtpFailureMemory.recordFailure(
                    modelPath = fingerprint.canonicalPath,
                    backendName = ev.attemptedTextBackend!!.name,
                    mtpEnabled = false,
                    validationVersion = fingerprint.validationVersion,
                    kvCacheCapacityTokens = profile.kvCacheCapacityTokens,
                    enableVisionBackend = profile.enableVisionBackend,
                    fileSize = fingerprint.fileSize,
                    modifiedAt = fingerprint.modifiedAt,
                    mtpSupported = fingerprint.mtpSupported,
                    fallbackReason = ev.attemptedVisionBackend?.name?.let { "Backend initialization failed (vision=$it)" } ?: "Backend initialization failed"
                )
                onPersistenceCompleted()
            }
        }

        // Add terminal all-candidates-exhausted event when candidates were attempted
        // or all candidates failed terminal failures (flag settlement, capability probe, factory)
        var finalEvents = allEvents
        val candidatesReachedTerminal = selection.fallbackEvents.any {
            it.reason == FallbackReason.BACKEND_ENGINE_INIT_FAILED ||
            it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED ||
            it.reason == FallbackReason.SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED ||
            it.reason == FallbackReason.SPECULATIVE_DISABLE_FLAG_SETTLEMENT_FAILED ||
            it.reason == FallbackReason.MTP_UNSUPPORTED
        }
        if (selection.selection == null && selection.failure != null && candidatesReachedTerminal) {
            finalEvents = allEvents + RuntimeFallbackEvent(
                reason = FallbackReason.ALL_CANDIDATES_EXHAUSTED
            )
        }

        // Detect all-candidates-skipped-by-failure-memory: no selection, no terminal failure,
        // but fallback events exist and are all skip events (not terminal failures).
        var finalFailure = selection.failure
        val allSkippedByFailureMemory = selection.selection == null &&
            selection.failure == null &&
            selection.fallbackEvents.isNotEmpty() &&
            selection.fallbackEvents.all { it.reason == FallbackReason.MTP_SKIPPED_RECENT_FAILURE || it.reason == FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE }
        if (allSkippedByFailureMemory) {
            finalEvents = finalEvents + RuntimeFallbackEvent(
                reason = FallbackReason.ALL_CANDIDATES_SKIPPED_RECENT_FAILURE
            )
            val attemptSnapshot = RuntimeAttemptSnapshot(
                requestedAccelerator = profile.accelerator,
                fallbackEvents = finalEvents,
                modelFingerprint = ModelFingerprintSummary(
                    canonicalPath = fingerprint.canonicalPath,
                    fileSize = fingerprint.fileSize,
                    modifiedAt = fingerprint.modifiedAt,
                    validationVersion = fingerprint.validationVersion,
                    mtpSupported = fingerprint.mtpSupported
                ),
                mtpRequested = profile.mtpRequested
            )
            finalFailure = EngineSelectionFailedException(attemptSnapshot)
        }

        // Build selection results with the resolved fingerprint and capability result
        val selectionResultWithData = selection.selection?.let {
            EngineSelectionResult(
                engine = it.engine,
                selectedMtpEnabled = it.selectedMtpEnabled,
                mtpFlagAppliedForMtp = it.mtpFlagAppliedForMtp,
                backendName = it.backendName,
                visionBackend = it.visionBackend,
                fingerprint = fingerprint,
                mtpCapabilityResult = mtpCapabilityResult
            )
        }

        // Phase 2: preserve explicit ownership of events. The selector tags
        // engineAcquisitionEvents vs requestEvents at the point of selection;
        // the coordinator forwards them verbatim so LiteRtLlmEngine no longer
        // partitions by FallbackReason (the fragile "stable reason vs
        // request-local reason" classification).
        // - When a selection was made, the selector's ownership sets are the
        //   truth: engineAcquisitionEvents for a freshly created engine (empty
        //   for reuse), requestEvents for the current request's skips/probes.
        // - When selection failed, the terminal events appended below are
        //   request-local (no engine's stable history exists for a failed
        //   attempt), so they extend requestEvents and leave
        //   engineAcquisitionEvents empty.
        val (engineAcquisitionEvents, requestEvents) = if (selection.selection != null) {
            selection.engineAcquisitionEvents to selection.requestEvents
        } else {
            val extraTerminal = finalEvents.size - selection.fallbackEvents.size
            val appendedTerminalEvents = if (extraTerminal > 0) finalEvents.takeLast(extraTerminal) else emptyList()
            emptyList<RuntimeFallbackEvent>() to (selection.requestEvents + appendedTerminalEvents)
        }

        return EngineSelectionOutcome(
            selection = selectionResultWithData,
            failure = finalFailure,
            fallbackEvents = finalEvents,
            fingerprint = fingerprint,
            mtpCapabilityResult = mtpCapabilityResult,
            engineAcquisitionEvents = engineAcquisitionEvents,
            requestEvents = requestEvents,
            reusedLoadedEngine = selection.reusedLoadedEngine
        )
    }
}