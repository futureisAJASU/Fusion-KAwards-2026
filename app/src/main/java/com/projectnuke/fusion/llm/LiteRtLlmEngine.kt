package com.projectnuke.fusion.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.LocalGenerationFloatSettings
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.model.RuntimeCpuDeviceSignals
import com.projectnuke.fusion.model.RuntimeCpuResolutionReason
import com.projectnuke.fusion.model.RuntimeCpuThreadPolicy
import com.projectnuke.fusion.model.RuntimeCpuThreadResolution
import com.projectnuke.fusion.model.RuntimePerformancePreferenceStore
import com.projectnuke.fusion.modelzoo.KananaCompatibilityTemplateSource
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.FusionPromptAdapter
import com.projectnuke.fusion.modelzoo.KananaPromptTemplateCompatibility
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.ui.FusionDeveloperLogStore
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.FusionMemoryManager
import com.projectnuke.fusion.util.FusionRuntimeMemoryInfo
import com.projectnuke.fusion.util.ManagedModelPathPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal fun isLiteRtPromptTemplateFailure(error: Throwable): Boolean {
    val message = error.message.orEmpty()
    return message.contains("Failed to apply template", ignoreCase = true) ||
        message.contains("Template creation failed", ignoreCase = true) ||
        (message.contains("syntax error", ignoreCase = true) &&
            message.contains("generation", ignoreCase = true)) ||
        message.contains("unknown statement generation", ignoreCase = true)
}

internal fun isLiteRtIrrecoverableLoadException(error: Throwable): Boolean {
    if (isLiteRtPromptTemplateFailure(error)) return false
    val message = error.message.orEmpty()
    return message.contains("Failed to create engine", ignoreCase = true) ||
        message.contains("litert_compiled_model", ignoreCase = true) ||
        message.contains("INTERNAL", ignoreCase = true)
}

internal fun classifyLiteRtGenerationFailureKind(
    error: Throwable,
    isMultimodal: Boolean,
): FailureKind = when {
    error is OutOfMemoryError || error is VirtualMachineError || error is LinkageError ->
        FailureKind.MODEL_LOAD_FAILED
    isMultimodal && listOf("vision", "image", "multimodal").any {
        error.message.orEmpty().contains(it, ignoreCase = true)
    } -> FailureKind.MODEL_MULTIMODAL_UNSUPPORTED
    isLiteRtPromptTemplateFailure(error) -> FailureKind.PROMPT_TEMPLATE_INCOMPATIBLE
    isLiteRtIrrecoverableLoadException(error) -> FailureKind.MODEL_LOAD_FAILED
    error is IOException -> FailureKind.GENERATION_IO
    else -> FailureKind.GENERATION_INTERRUPTED
}

private fun nativeProfileIdentity(profile: RequestedEngineProfile): String = listOf(
    profile.modelIdentity ?: profile.modelPath?.let { File(it).name } ?: "unknown",
    profile.accelerator.name,
    "kv=${profile.kvCacheCapacityTokens}",
    "mtp=${profile.mtpRequested}",
).joinToString(":")

/**
 * Exception thrown when engine selection fails, carrying the attempt snapshot
 * so callers can construct a [GenerationOutcome.Failure] with the full
 * fallback context without rereading engine state.
 */
internal class EngineSelectionFailedException(
    val attemptSnapshot: RuntimeAttemptSnapshot,
    cause: Throwable? = null
) : RuntimeException("Engine selection failed", cause)

/**
 * Result of a single adoption attempt produced by [EngineReplacement.adopt].
 *
 * - [Published]: the new engine was published; the old (if any) was closed
 *   exactly once. [oldCloseError] carries a best-effort close failure that was
 *   logged but did NOT affect ownership (`true` is the clean success, so this
 *   is `null`).
 * - [NewEngineClosed]: the previous engine could NOT close cleanly after the
 *   new was constructed and published; both engines were closed to avoid an
 *   orphan, the loaded state is cleared, and the original fatal-[Throwable]
 *   is rethrown on available [onError]. [old] close count is exactly 1 and
 *   new close count is exactly 1 in this outcome.
 * - [NewEngineAdoptionFailed]: a new engine was constructed but the caller's
 *   own adoption step (publish / setLoaded) threw. The new engine was closed
 *   exactly once, the old was left untouched. Adoption failures are typed
 *   candidate failures, not fatal errors.
 */
internal sealed class EngineAdoptionOutcome<out T> {
    data class Published<T>(val engine: T) : EngineAdoptionOutcome<T>()
    data class NewEngineClosed<T>(val adoptFailure: Throwable) : EngineAdoptionOutcome<T>()
    data class NewEngineAdoptionFailed<T>(val adoptFailure: Throwable) : EngineAdoptionOutcome<T>()
}

/**
 * Phase 3 production owner/adoption helper used by [LiteRtLlmEngine].
 *
 * Defines AND tests the Engine ownership policy explicitly so the running
 * engine is closed exactly once and the loaded runtime state ends in a single
 * well-defined state on every path — including when `close()` throws or the
 * new engine cannot be adopted (publish step throws).
 *
 * Required invariants (asserted by [EngineLifecycleOwnershipTest]):
 *  - same Engine reuse -> old close count 0, new close count 0
 *  - successful replacement -> old close count 1, new close count 0
 *  - acquisition failure before new engine exists -> old close count 0
 *  - new engine created but cannot be adopted (publish throws) -> new closed 1,
 *    old untouched
 *  - old close throws -> no orphan new engine (new closed 1), loaded state
 *    cleared, failure propagated as [EngineAdoptionOutcome.NewEngineClosed]
 *
 * The helper is intentionally Android- and framework-free (it only uses the
 * provided closures) so it can be unit-tested without a real LiteRT-LM Engine.
 */
internal object EngineReplacement {
    /**
     * Atomically adopts [newEngine] while replacing [getOld] / publishing via
     * [publish] / releasing via [close].
     *
     * The sequence is exception-safe and preserves the ownership invariants:
     * 1. Capture the previous engine (if any) but do NOT close it yet.
     * 2. Publish the new engine via [publish]; the loaded state now references
     *    the new engine. If publish throws, the new engine is closed (report
     *    [NewEngineAdoptionFailed]) and the old engine / loaded state is left
     *    exactly as before the call.
     * 3. Close the old engine via [close]. If this throws, the new engine is
     *    closed to avoid orphaning it, the loaded state is cleared via
     *    [clear] so the system never observes a partial-swap state, and the
     *    original close failure is rethrown via [EngineAdoptionOutcome.NewEngineClosed].
     *
     * [close] is invoked at most once per engine. The new engine is never
     * closed on the success path.
     */
    fun <T> adopt(
        newEngine: T,
        getOld: () -> T?,
        publish: (T) -> Unit,
        clear: () -> Unit,
        close: (T) -> Unit
    ): EngineAdoptionOutcome<T> {
        val oldEngine = getOld()
        try {
            publish(newEngine)
        } catch (publishFailure: Throwable) {
            // Publish threw BEFORE the old engine was released; the new engine
            // was constructed but cannot be adopted. Close it so it isn't
            // orphaned, and leave the old engine (and the caller's loaded state)
            // untouched. Don't rethrow `Error` blindly: publish-failure should
            // be a typed candidate failure unless it is a fatal Error.)
            bestEffortClose(newEngine, close)
            if (publishFailure.isFatalError() || publishFailure is ThreadDeath) {
                // Truly fatal Error during publish: propagate per the
                // fatal-error policy. Recoverable failures become typed
                // NewEngineAdoptionFailed below.
                throw publishFailure
            }
            if (publishFailure is CancellationException) throw publishFailure
            return EngineAdoptionOutcome.NewEngineAdoptionFailed(publishFailure)
        }
        // The new engine is now published. If the new happens to be the same
        // Engine instance that was already loaded (reuse), do NOT close it.
        if (oldEngine != null && oldEngine !== newEngine) {
            try {
                close(oldEngine)
                return EngineAdoptionOutcome.Published(newEngine)
            } catch (oldCloseError: Throwable) {
                // Old close threw: the new engine is published but the old
                // engine could be in an inconsistent state. To guarantee "no
                // orphan new Engine", close the new engine (best-effort) and
                // clear the loaded state to a single well-defined empty value
                // so callers observe either a fully successful swap or an empty
                // loaded state — never a half-retired old engine with a live
                // new engine that no longer owns its resources.
                bestEffortClose(newEngine, close)
                bestEffortClear(clear)
                return EngineAdoptionOutcome.NewEngineClosed(oldCloseError)
            }
        }
        // No prior engine to close (or reuse match) — publish was the only effect.
        return EngineAdoptionOutcome.Published(newEngine)
    }

    /** Attempts [close] without letting a second failure escape the helper. */
    private fun <T> bestEffortClose(engine: T, close: (T) -> Unit) {
        try { close(engine) } catch (cleanupFailure: Throwable) {
            Log.w("FusionLiteRT", "Engine cleanup close() failed during adoption cleanup", cleanupFailure)
        }
    }

    /** Attempts [clear] without letting a clear failure escape the helper. */
    private fun bestEffortClear(clear: () -> Unit) {
        try { clear() } catch (clearFailure: Throwable) {
            Log.w("FusionLiteRT", "Loaded state clear() failed during adoption cleanup", clearFailure)
        }
    }
}

/** Wrapped rethrow inside [EngineReplacement.adopt] when the old close() fails. */
internal class EngineAdoptionException(cause: Throwable) :
    RuntimeException("Engine adoption failed after close() threw", cause)

/**
 * Returns `true` only for unrecoverable fatal [Throwable]s whose contract is to
 * propagate out of the candidate ladder entirely (out-of-memory, VM failure,
 * thread death, unsatisfied link). Recoverable native init exceptions return
 * `false` so they can become typed candidate failures.
 *
 * Do NOT catch [Throwable] indiscriminately — these are callers' only chance to
 * surface a true OOM/LinkageError on a memory-constrained device.
 */
internal fun Throwable.isFatalError(): Boolean =
    this is VirtualMachineError ||
        this is LinkageError ||
        this is ThreadDeath

/**
 * Runs a non-fatal-only [block], returning `null` when [block] throws a
 * recoverable [Throwable] and rethrowing only fatal [Error]s ([OutOfMemoryError],
 * [VirtualMachineError], [LinkageError], [ThreadDeath]). Use this on native
 * seams that previously used `runCatching` and swallowed OOM into `null`/`false`.
 */
internal inline fun <T> runNonFatal(block: () -> T): T? {
    return try { block() } catch (e: Throwable) {
        if (e.isFatalError()) throw e
        when (e) {
            is CancellationException -> throw e
            else -> null
        }
    }
}@OptIn(ExperimentalApi::class)
class LiteRtLlmEngine(
    private val context: Context,
    private val engineFactory: (EngineConfig) -> Engine = { config ->
        // Phase 3A: explicit partial-initialization cleanup. Once Engine(config)
        // has successfully returned an owned Engine object, ANY subsequent
        // initialization failure (recoverable Exception or fatal Error) must
        // perform a best-effort cleanup of that object before propagating the
        // original failure. The original failure is always primary; a cleanup
        // failure is logged and suppressed.
        val engine = Engine(config)
        try {
            engine.initialize()
        } catch (failure: Throwable) {
            try { engine.close() } catch (cleanupFailure: Throwable) {
                Log.w("FusionLiteRT", "Engine partial-init cleanup close() failed", cleanupFailure)
            }
            throw failure
        }
        engine
    },
    private val flagSetter: (Boolean) -> Boolean = { enabled ->
        // Phase 3A: do not swallow fatal Errors via runCatching. Failure to
        // settle the speculative-decoding flag is a typed candidate failure;
        // OOM / LinkageError must propagate.
        runNonFatal { ExperimentalFlags.enableSpeculativeDecoding = enabled } != null
    },
    private val flagReader: () -> Boolean? = {
        ExperimentalFlags.enableSpeculativeDecoding
    },
    private val failureMemoryStorage: MtpFailureMemoryStorage = NoopMtpFailureMemoryStorage,
    private val mtpCapabilityProbe: (modelPath: String) -> Boolean? = { modelPath ->
        // Phase 3A: capability probe must not hide fatal Errors as `null`
        // (the previous runCatching collapsed OOM into "no MTP support").
        runNonFatal { Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() } }
    },
    /**
     * Native LiteRT-LM minimum log severity. Production keeps [LogSeverity.ERROR]
     * to stay quiet; benchmark / debug mode may lower this to
     * [LogSeverity.WARNING] to surface component-placement diagnostics without
     * honoring unstable warning strings as permanent correctness state (Phase 9).
     */
    private val nativeMinLogSeverity: () -> LogSeverity = { LogSeverity.ERROR },
    /**
     * Clock for deterministic cooldown timing in tests and for production timing.
     * Injected to allow deterministic testing of failure memory cooldown boundaries.
     */
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val cpuDeviceSignals: () -> RuntimeCpuDeviceSignals = { RuntimeCpuDeviceSignals.observe(context) },
) : LlmEngine {

    private var loadedState: LoadedRuntimeState<Engine>? = null
    private val mtpFailureMemory = MtpFailureMemory(failureMemoryStorage, clock)
    @Volatile
    var lastMtpStatus: MtpRuntimeStatus = MtpRuntimeStatus.OFF
        private set
    
    @Volatile
    private var lastRecordedDurability: FailureMemoryDurability = FailureMemoryDurability.NotAttempted

    val lastRuntimeSelection: EngineSelectionRuntime?
        get() = loadedState?.runtimeSelection

    /** True when the currently loaded wrapper can satisfy this profile without a fresh Engine. */
    internal fun isRuntimeReusableFor(profile: RequestedEngineProfile): Boolean {
        val loaded = loadedState ?: return false
        val fingerprint = ModelFingerprint.of(profile.modelPath)
        return isLoadedKeyReusableFor(loaded.key, resolveCpuPerformanceProfile(profile), fingerprint)
    }

    private fun resolveCpuPerformanceProfile(profile: RequestedEngineProfile): RequestedEngineProfile {
        val preference = RuntimePerformancePreferenceStore(
            context.getSharedPreferences(RuntimePerformancePreferenceStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        ).read()
        val signals = cpuDeviceSignals()
        val activeCpuRuntime = loadedState?.takeIf {
            it.actualTextBackend == "CPU" || it.actualVisionBackend == "CPU"
        }
        val previous = activeCpuRuntime?.key?.cpuThreadCount?.takeIf { it > 0 }?.let {
            RuntimeCpuThreadResolution(
                threadCount = it,
                deviceCeiling = RuntimeCpuThreadPolicy.deviceCeiling(signals),
                reason = RuntimeCpuResolutionReason.RETAINED_FOR_RUNTIME_GENERATION,
            )
        }
        val resolution = RuntimeCpuThreadPolicy.resolve(
            preference = preference,
            signals = signals,
            previous = previous,
            previousPreference = activeCpuRuntime?.key?.cpuPerformancePreference,
        )
        return profile.copy(cpuThreadCount = resolution.threadCount, cpuPerformancePreference = preference)
    }

    private fun logRuntimeLifecycle(
        reason: String,
        profile: RequestedEngineProfile,
    ) {
        FusionMemoryManager.logRuntimeSnapshot(
            tag = "FusionRuntime",
            context = context,
            reason = reason,
            runtimeInfo = FusionRuntimeMemoryInfo(
                modelPath = profile.modelPath,
                modelFileSizeBytes = File(profile.modelPath).takeIf { it.isFile }?.length(),
                actualBackend = loadedState?.actualTextBackend,
                kvCapacityTokens = profile.kvCacheCapacityTokens,
            ),
        )
    }

    /**
     * Phase 1: request-local fallback events live here for the lifetime of one
     * generate/generateStreaming call. Combined with [loadedState].fallbackEvents
     * (stable acquisition events) at snapshot-build time. Never written back
     * into loadedState, so a reused Engine never inherits another request's
     * cooldown or skip events.
     */
    @Volatile
    private var currentRequestFallbackEvents: List<RuntimeFallbackEvent> = emptyList()

    /**
     * Phase 2: tracks whether the currently loaded Engine was freshly created
     * for the current request. When true, the stable [loadedState].fallbackEvents
     * are this request's own acquisition history and may be combined with
     * [currentRequestFallbackEvents] in snapshots. When false (reused engine),
     * only [currentRequestFallbackEvents] are current-execution events;
     * [loadedState].fallbackEvents is Engine provenance and must NOT appear
     * in the current snapshot.
     */
    @Volatile
    private var currentRequestUsesFreshEngine: Boolean = false

    /**
     * Updates the native LiteRT-LM minimum log severity at runtime. Used by
     * benchmark / debug mode to lower the threshold to WARNING so internal
     * component-placement (sampler) diagnostics surface; production mode keeps
     * ERROR so warnings are never honored as permanent correctness state.
     * Must only be called while the caller owns the exclusive runtime section.
     */
    fun setNativeMinLogSeverity(severity: LogSeverity) {
        Engine.setNativeMinLogSeverity(severity)
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        profile: RequestedEngineProfile,
        options: ConversationOptions
    ): GenerationOutcome {
        return generateStreaming(
            messages = messages,
            profile = profile,
            options = options,
            onToken = {}
        )
    }

    /**
     * Staged overload for UI callers that present real runtime progress.
     * [onStage] is invoked synchronously from the actual lifecycle points
     * (cold-acquisition gate, post-acquire, conversation creation, first
     * visible token) on the IO dispatcher; it must be thread-safe and fast.
     * A reusable warm runtime never emits [LocalRuntimeStage.ENGINE_ACQUIRING].
     */
    suspend fun generate(
        messages: List<ChatMessage>,
        profile: RequestedEngineProfile,
        options: ConversationOptions,
        onStage: ((LocalRuntimeStageEvent) -> Unit)?,
    ): GenerationOutcome {
        return generateStreaming(
            messages = messages,
            profile = profile,
            options = options,
            onToken = {},
            onStage = onStage,
        )
    }

    override suspend fun generateStreaming(
        messages: List<ChatMessage>,
        profile: RequestedEngineProfile,
        options: ConversationOptions,
        onToken: (String) -> Unit
    ): GenerationOutcome {
        return generateStreaming(
            messages = messages,
            profile = profile,
            options = options,
            onToken = onToken,
            onStage = null,
        )
    }

    /**
     * Staged overload for UI callers that present real runtime progress. See
     * [generate] for the emission contract.
     */
    suspend fun generateStreaming(
        messages: List<ChatMessage>,
        profile: RequestedEngineProfile,
        options: ConversationOptions,
        onToken: (String) -> Unit,
        onStage: ((LocalRuntimeStageEvent) -> Unit)?,
    ): GenerationOutcome {
        return withContext(Dispatchers.IO) {
            val modelFile = ManagedModelPathPolicy.resolveRunnableModel(context, profile.modelPath)
            if (modelFile == null) {
                Log.e("FusionEngine", "Selected model path is missing or unmanaged: ${File(profile.modelPath).name}")
                return@withContext GenerationOutcome.Failure(
                    kind = FailureKind.MODEL_NOT_FOUND,
                    message = "선택한 모델 파일을 찾을 수 없습니다. 모델을 다시 선택해 주세요."
                )
            }

            val resolvedProfile = profile.copy(modelPath = modelFile.absolutePath)
            logRuntimeLifecycle("REQUEST_ADMITTED", resolvedProfile)
            if (!isRuntimeReusableFor(resolvedProfile)) {
                logRuntimeLifecycle("ENGINE_ACQUIRE_BEGIN", resolvedProfile)
                onStage?.invoke(
                    LocalRuntimeStageEvent(
                        stage = LocalRuntimeStage.ENGINE_ACQUIRING,
                        requestedAccelerator = resolvedProfile.accelerator,
                        actualBackend = null,
                    )
                )
            }
            val engine = try {
                getOrCreateEngine(resolvedProfile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineSelectionFailedException) {
                return@withContext GenerationOutcome.Failure(
                    kind = FailureKind.MODEL_LOAD_FAILED,
                    message = "모델을 불러올 수 없습니다. 모델 설정을 확인한 뒤 다시 시도해 주세요.",
                    attemptSnapshot = e.attemptSnapshot
                )
            } catch (e: OutOfMemoryError) {
                lastMtpStatus = MtpRuntimeStatus.OFF
                return@withContext GenerationOutcome.Failure(
                    kind = FailureKind.MODEL_LOAD_FAILED,
                    message = "메모리가 부족하여 모델을 불러올 수 없습니다."
                )
            } catch (e: VirtualMachineError) {
                return@withContext GenerationOutcome.Failure(
                    kind = FailureKind.MODEL_LOAD_FAILED,
                    message = "런타임 오류로 모델을 불러올 수 없습니다."
                )
            } catch (e: LinkageError) {
                return@withContext GenerationOutcome.Failure(
                    kind = FailureKind.MODEL_LOAD_FAILED,
                    message = "런타임 오류로 모델을 불러올 수 없습니다."
                )
            } catch (e: Exception) {
                Log.e("FusionEngine", "LiteRT-LM engine init failed", e)
                return@withContext GenerationOutcome.Failure(
                    kind = FailureKind.MODEL_LOAD_FAILED,
                    message = "모델을 불러올 수 없습니다. 모델 설정을 확인한 뒤 다시 시도해 주세요."
                )
            }
            logRuntimeLifecycle("ENGINE_READY", resolvedProfile)
            onStage?.invoke(
                LocalRuntimeStageEvent(
                    stage = LocalRuntimeStage.ENGINE_READY,
                    requestedAccelerator = resolvedProfile.accelerator,
                    actualBackend = loadedState?.actualTextBackend,
                )
            )
            val requestContext = currentCoroutineContext()[GenerationRequestContext]
            earlyTerminationOutcome(requestContext)?.let { return@withContext it }
            currentCoroutineContext().ensureActive()
            logGenerationSettings(
                profile = resolvedProfile,
                options = options
            )

            val prepared = FusionPromptAdapters.prepareMessagesForModel(messages)
            val promptAdapter = FusionPromptAdapters.forFamily(prepared.modelFamily)
            val adaptedMessages = promptAdapter.buildMessages(prepared.messages)
            val systemText = buildSystemInstruction(adaptedMessages)
            val kananaPrompt = if (prepared.modelFamily == ModelFamily.KANANA) {
                buildKananaStructuredPrompt(adaptedMessages, systemText)
            } else {
                null
            }
            val promptText = kananaPrompt?.currentUserContent ?: buildPrompt(adaptedMessages)

            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(systemText),
                initialMessages = kananaPrompt?.toInitialLiteRtMessages().orEmpty(),
                samplerConfig = buildSamplerConfig(options)
            )

            earlyTerminationOutcome(requestContext)?.let { return@withContext it }
            currentCoroutineContext().ensureActive()

            val output = StringBuilder()
            var outputTruncated = false
            var nativeStats: GenerationBenchmarkStats? = null
            var firstVisibleTokenLogged = false

            try {
                val reason = KananaPromptTemplateCompatibility.targetReason(
                    modelIdentity = resolvedProfile.modelIdentity,
                    modelPath = resolvedProfile.modelPath,
                )
                val modelBasename = resolvedProfile.modelPath?.let { File(it).name }
                    ?: resolvedProfile.modelIdentity ?: "unknown"
                Log.i("FusionLiteRT", "Kanana prompt override: applied=${reason != KananaPromptTemplateCompatibility.OverrideReason.NONE} reason=${reason.name} model=$modelBasename")
                val conversation = withPromptTemplateOverride(
                    overrideTemplate = if (reason != KananaPromptTemplateCompatibility.OverrideReason.NONE) {
                        KananaCompatibilityTemplateSource.compatibilitySource(context)
                    } else {
                        null
                    },
                ) {
                    engine.createConversation(conversationConfig)
                }
                logRuntimeLifecycle("GENERATION_BEGIN", resolvedProfile)
                onStage?.invoke(
                    LocalRuntimeStageEvent(
                        stage = LocalRuntimeStage.GENERATION_STARTED,
                        requestedAccelerator = resolvedProfile.accelerator,
                        actualBackend = loadedState?.actualTextBackend,
                    )
                )
                val backgroundLifetime = requestContext?.backgroundLifetime
                val callerJob = currentCoroutineContext()[kotlinx.coroutines.Job]
                val foregroundLifetime = if (backgroundLifetime == null) {
                    NativeConversationRequestLifetime(
                        conversation = conversation,
                        requestContext = requestContext,
                        callerJob = callerJob,
                        runtimeProfileIdentity = nativeProfileIdentity(resolvedProfile),
                    )
                } else {
                    null
                }
                try {
                    if (backgroundLifetime != null) {
                        backgroundLifetime.nativeConversationCreated { conversation.cancelProcess() }
                    } else {
                        foregroundLifetime?.registerCancellation()
                    }

                    var outputLimitRetirementRequested = false
                    fun appendChunk(chunk: Message) {
                        if (outputLimitRetirementRequested) return
                        val token = chunk.toString()
                        output.append(token)
                        backgroundLifetime?.recordOutputChars(output.length)
                        if (!firstVisibleTokenLogged && token.any { !it.isWhitespace() }) {
                            firstVisibleTokenLogged = true
                            logRuntimeLifecycle("FIRST_VISIBLE_TOKEN", resolvedProfile)
                            onStage?.invoke(
                                LocalRuntimeStageEvent(
                                    stage = LocalRuntimeStage.FIRST_VISIBLE_TOKEN,
                                    requestedAccelerator = resolvedProfile.accelerator,
                                    actualBackend = loadedState?.actualTextBackend,
                                )
                            )
                        }
                        requestContext?.cancellationController?.publishPartial(
                            requestContext.requestKey,
                            output.toString(),
                        )
                        onToken(token)
                        if (isAppOutputLimitReached(options, output)) {
                            outputTruncated = true
                            outputLimitRetirementRequested = true
                            if (backgroundLifetime != null) {
                                backgroundLifetime.requestRetirement(BackgroundTerminationSource.OUTPUT_LIMIT)
                            } else {
                                foregroundLifetime?.requestRetirement("OUTPUT_LIMIT")
                            }
                        }
                    }

                    try {
                        if (backgroundLifetime != null) {
                            val started = backgroundLifetime.collectNativeMessages(
                                conversation = conversation,
                                message = promptText,
                                callerJob = callerJob,
                                onMessage = ::appendChunk,
                            )
                            if (!started) return@withContext GenerationOutcome.Empty
                        } else {
                            val started = foregroundLifetime?.collect(
                                startNative = { callback ->
                                    conversation.sendMessageAsync(
                                        promptText,
                                        object : MessageCallback {
                                            override fun onMessage(message: Message) = callback.onMessage(message)
                                            override fun onDone() = callback.onDone()
                                            override fun onError(throwable: Throwable) = callback.onError(throwable)
                                        },
                                    )
                                },
                                onMessage = ::appendChunk,
                            ) ?: false
                            if (!started) return@withContext GenerationOutcome.Empty
                        }
                    } catch (e: CancellationException) {
                        if (requestContext?.cancellationController?.intentFor(requestContext.requestKey) == GenerationTerminationIntent.USER_STOP) {
                            return@withContext userStopOutcome(promptAdapter, output.toString())
                        }
                        if (backgroundLifetime?.wasRetiredFor(BackgroundTerminationSource.TIMEOUT) == true) {
                            return@withContext GenerationOutcome.Empty
                        }
                        val outputLimitTerminal = backgroundLifetime?.wasRetiredFor(BackgroundTerminationSource.OUTPUT_LIMIT) == true ||
                            (outputLimitRetirementRequested && foregroundLifetime?.nativeTerminalObserved == true)
                        if (!outputLimitTerminal) {
                            throw e
                        }
                        Log.i("FusionLiteRT", "App output cap reached; native terminal observed")
                    } catch (e: Exception) {
                        val outputLimitTerminal = backgroundLifetime?.wasRetiredFor(BackgroundTerminationSource.OUTPUT_LIMIT) == true ||
                            (outputLimitRetirementRequested && foregroundLifetime?.nativeTerminalObserved == true)
                        if (!outputLimitTerminal) throw e
                        Log.i("FusionLiteRT", "App output cap reached; native terminal observed")
                    }
                    nativeStats = conversationBenchmarkStats(conversation)
                    if (backgroundLifetime?.wasRetiredFor(BackgroundTerminationSource.TIMEOUT) == true) {
                        return@withContext GenerationOutcome.Empty
                    }
                } finally {
                    if (backgroundLifetime != null) {
                        backgroundLifetime.closeConversation { conversation.close() }
                    } else {
                        foregroundLifetime?.closeConversation { conversation.close() }
                    }
                }
            } catch (e: CancellationException) {
                val requestContext = currentCoroutineContext()[GenerationRequestContext]
                if (requestContext?.cancellationController?.intentFor(requestContext.requestKey) == GenerationTerminationIntent.USER_STOP) {
                    return@withContext userStopOutcome(promptAdapter, output.toString())
                }
                throw e
            } catch (e: ThreadDeath) {
                // Truly fatal — propagate per the fatal-error policy.
                throw e
            } catch (e: Error) {
                // Phase 3D: OOM / VirtualMachineError / LinkageError during
                // generation are recoverable into a typed Failure (the user
                // may retry with a smaller context); they must NOT escape the
                // result contract while engine initialization swallows the
                // same Error. ThreadDeath is truly fatal and is rethrown above.
                Log.e("FusionEngine", "Fatal Error during LiteRT-LM generation", e)
                return@withContext classifyGenerationException(e, isMultimodal = false, profile = resolvedProfile)
            } catch (e: Exception) {
                Log.e("FusionEngine", "LiteRT-LM generation failed", e)
                return@withContext classifyGenerationException(e, isMultimodal = false, profile = resolvedProfile)
            } finally {
                logRuntimeLifecycle("GENERATION_TERMINAL", resolvedProfile)
            }

            val sanitized = promptAdapter.sanitizeOutput(output.toString())
            if (sanitized.isBlank()) {
                GenerationOutcome.Empty
            } else {
                GenerationOutcome.Success(
                    text = sanitized,
                    actualBackend = loadedState?.actualTextBackend,
                    truncated = outputTruncated,
                    stats = nativeStats,
                    snapshot = buildRuntimeExecutionSnapshot(profile)
                )
            }
        }
    }

    override suspend fun generateMultimodalStreaming(
        messages: List<ChatMessage>,
        profile: RequestedEngineProfile,
        options: ConversationOptions,
        imagePaths: List<String>,
        onToken: (String) -> Unit
    ): GenerationOutcome {
        return withContext(Dispatchers.IO) {
            val modelFile = ManagedModelPathPolicy.resolveRunnableModel(context, profile.modelPath)
            if (modelFile == null) {
                Log.e("FusionEngine", "Selected multimodal model path is missing or unmanaged: ${File(profile.modelPath).name}")
                return@withContext GenerationOutcome.Failure(
                    kind = FailureKind.MODEL_NOT_FOUND,
                    message = "선택한 모델 파일을 찾을 수 없습니다. 모델을 다시 선택해 주세요."
                )
            }

            val managedImagePaths = imagePaths.mapNotNull { path ->
                AttachmentStorageManager.resolveManagedAttachment(context, path)?.absolutePath
            }
            if (managedImagePaths.size != imagePaths.size) {
                return@withContext GenerationOutcome.Failure(
                    kind = FailureKind.IMAGE_NOT_FOUND,
                    message = "이미지 입력 처리 실패: 이미지 파일을 찾을 수 없습니다."
                )
            }

            val requestContext = currentCoroutineContext()[GenerationRequestContext]
            earlyTerminationOutcome(requestContext)?.let { return@withContext it }
            currentCoroutineContext().ensureActive()

            val resolvedProfile = profile.copy(modelPath = modelFile.absolutePath)
            val engine = try {
                getOrCreateEngine(resolvedProfile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineSelectionFailedException) {
                return@withContext GenerationOutcome.Failure(
                    kind = FailureKind.MODEL_LOAD_FAILED,
                    message = "모델을 불러올 수 없습니다. 모델 설정을 확인한 뒤 다시 시도해 주세요.",
                    attemptSnapshot = e.attemptSnapshot
                )
            } catch (e: OutOfMemoryError) {
                lastMtpStatus = MtpRuntimeStatus.OFF
                return@withContext GenerationOutcome.Failure(
                    kind = FailureKind.MODEL_LOAD_FAILED,
                    message = "메모리가 부족하여 모델을 불러올 수 없습니다."
                )
            } catch (e: VirtualMachineError) {
                return@withContext GenerationOutcome.Failure(
                    kind = FailureKind.MODEL_LOAD_FAILED,
                    message = "런타임 오류로 모델을 불러올 수 없습니다."
                )
            } catch (e: LinkageError) {
                return@withContext GenerationOutcome.Failure(
                    kind = FailureKind.MODEL_LOAD_FAILED,
                    message = "런타임 오류로 모델을 불러올 수 없습니다."
                )
            } catch (e: Exception) {
                if (isVisionBackendUnsupported(e)) {
                    return@withContext GenerationOutcome.Failure(
                        kind = FailureKind.MODEL_MULTIMODAL_UNSUPPORTED,
                        message = "이 모델은 이미지 입력을 지원하지 않습니다."
                    )
                }
                Log.e("FusionEngine", "LiteRT-LM multimodal engine init failed", e)
                return@withContext GenerationOutcome.Failure(
                    kind = FailureKind.MODEL_LOAD_FAILED,
                    message = "이미지 입력 처리 실패: 모델 설정을 확인한 뒤 다시 시도해 주세요."
                )
            }
            earlyTerminationOutcome(requestContext)?.let { return@withContext it }
            currentCoroutineContext().ensureActive()
            logGenerationSettings(
                profile = resolvedProfile,
                options = options
            )

            val prepared = FusionPromptAdapters.prepareMessagesForModel(messages)
            val promptAdapter = FusionPromptAdapters.forFamily(prepared.modelFamily)
            val adaptedMessages = promptAdapter.buildMessages(prepared.messages)
            val systemText = buildSystemInstruction(adaptedMessages)
            val kananaPrompt = if (prepared.modelFamily == ModelFamily.KANANA) {
                buildKananaStructuredPrompt(adaptedMessages, systemText)
            } else {
                null
            }
            val promptText = kananaPrompt?.currentUserContent ?: buildPrompt(adaptedMessages)

            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(systemText),
                initialMessages = kananaPrompt?.toInitialLiteRtMessages().orEmpty(),
                samplerConfig = buildSamplerConfig(options)
            )

            val contentParts = buildList<Content> {
                managedImagePaths.forEach { imagePath ->
                    add(Content.ImageFile(imagePath))
                }
                add(Content.Text(promptText))
            }

            earlyTerminationOutcome(requestContext)?.let { return@withContext it }
            currentCoroutineContext().ensureActive()

            val output = StringBuilder()
            var outputTruncated = false
            var nativeStats: GenerationBenchmarkStats? = null

            try {
                val reason = KananaPromptTemplateCompatibility.targetReason(
                    modelIdentity = resolvedProfile.modelIdentity,
                    modelPath = resolvedProfile.modelPath,
                )
                val modelBasename = resolvedProfile.modelPath?.let { File(it).name }
                    ?: resolvedProfile.modelIdentity ?: "unknown"
                Log.i("FusionLiteRT", "Kanana prompt override: applied=${reason != KananaPromptTemplateCompatibility.OverrideReason.NONE} reason=${reason.name} model=$modelBasename")
                val conversation = withPromptTemplateOverride(
                    overrideTemplate = if (reason != KananaPromptTemplateCompatibility.OverrideReason.NONE) {
                        KananaCompatibilityTemplateSource.compatibilitySource(context)
                    } else {
                        null
                    },
                ) {
                    engine.createConversation(conversationConfig)
                }
                val foregroundLifetime = NativeConversationRequestLifetime(
                    conversation = conversation,
                    requestContext = requestContext,
                    callerJob = currentCoroutineContext()[kotlinx.coroutines.Job],
                    runtimeProfileIdentity = nativeProfileIdentity(resolvedProfile),
                )
                try {
                    foregroundLifetime.registerCancellation()
                    var outputLimitRetirementRequested = false
                    fun appendChunk(chunk: Message) {
                        if (outputLimitRetirementRequested) return
                        val token = chunk.toString()
                        output.append(token)
                        requestContext?.cancellationController?.publishPartial(
                            requestContext.requestKey,
                            output.toString(),
                        )
                        onToken(token)
                        if (isAppOutputLimitReached(options, output)) {
                            outputTruncated = true
                            outputLimitRetirementRequested = true
                            foregroundLifetime.requestRetirement("OUTPUT_LIMIT")
                        }
                    }
                    try {
                        val started = foregroundLifetime.collect(
                            startNative = { callback ->
                                conversation.sendMessageAsync(
                                    Contents.of(contentParts),
                                    object : MessageCallback {
                                        override fun onMessage(message: Message) = callback.onMessage(message)
                                        override fun onDone() = callback.onDone()
                                        override fun onError(throwable: Throwable) = callback.onError(throwable)
                                    },
                                )
                            },
                            onMessage = ::appendChunk,
                        )
                        if (!started) return@withContext GenerationOutcome.Empty
                    } catch (e: CancellationException) {
                        if (requestContext?.cancellationController?.intentFor(requestContext.requestKey) == GenerationTerminationIntent.USER_STOP) {
                            return@withContext userStopOutcome(promptAdapter, output.toString())
                        }
                        if (!outputLimitRetirementRequested || !foregroundLifetime.nativeTerminalObserved) throw e
                        Log.i("FusionLiteRT", "App output cap reached; native terminal observed")
                    } catch (e: Exception) {
                        if (!outputLimitRetirementRequested || !foregroundLifetime.nativeTerminalObserved) throw e
                        Log.i("FusionLiteRT", "App output cap reached; native terminal observed")
                    }
                    nativeStats = conversationBenchmarkStats(conversation)
                } finally {
                    foregroundLifetime.closeConversation { conversation.close() }
                }
            } catch (e: CancellationException) {
                val requestContext = currentCoroutineContext()[GenerationRequestContext]
                if (requestContext?.cancellationController?.intentFor(requestContext.requestKey) == GenerationTerminationIntent.USER_STOP) {
                    return@withContext userStopOutcome(promptAdapter, output.toString())
                }
                throw e
            } catch (e: ThreadDeath) {
                // Truly fatal — propagate per the fatal-error policy.
                throw e
            } catch (e: Error) {
                // Phase 3D: OOM / VirtualMachineError / LinkageError during
                // multimodal generation recover into a typed Failure. ThreadDeath
                // is truly fatal and is rethrown above.
                Log.e("FusionEngine", "Fatal Error during LiteRT-LM multimodal generation", e)
                return@withContext classifyGenerationException(e, isMultimodal = true, profile = resolvedProfile)
            } catch (e: Exception) {
                Log.e("FusionEngine", "LiteRT-LM multimodal generation failed", e)
                return@withContext classifyGenerationException(e, isMultimodal = true, profile = resolvedProfile)
            }

            val sanitized = promptAdapter.sanitizeOutput(output.toString())
            if (sanitized.isBlank()) {
                GenerationOutcome.Empty
            } else {
                GenerationOutcome.Success(
                    text = sanitized,
                    actualBackend = loadedState?.actualTextBackend,
                    truncated = outputTruncated,
                    stats = nativeStats,
                    snapshot = buildRuntimeExecutionSnapshot(profile)
                )
            }
        }
    }

    private fun isVisionBackendUnsupported(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("vision", ignoreCase = true) ||
            message.contains("image", ignoreCase = true) ||
            message.contains("multimodal", ignoreCase = true)
    }

    private fun classifyGenerationException(
        error: Throwable,
        isMultimodal: Boolean,
        profile: RequestedEngineProfile
    ): GenerationOutcome.Failure {
        val kind = classifyLiteRtGenerationFailureKind(error, isMultimodal)
        // Capture the runtime which produced the failure before fatal cleanup
        // clears the live owner. The result must retain actual execution lineage.
        val loaded = loadedState
        // Phase 3D: fatal native-memory/VM/linkage failures during generation
        // leave the Engine in an unknown state. Unload it so the next request
        // creates a fresh Engine rather than silently reusing a potentially
        // invalid native runtime. Recoverable failures (IOException, etc.)
        // do NOT unload — the Engine may still be usable.
        if (error is OutOfMemoryError || error is VirtualMachineError || error is LinkageError) {
            unload()
        }
        val message = when (kind) {
            FailureKind.MODEL_MULTIMODAL_UNSUPPORTED -> "이 모델은 이미지 입력을 지원하지 않습니다."
            FailureKind.MODEL_LOAD_FAILED -> "모델을 불러올 수 없습니다. 모델 설정을 확인한 뒤 다시 시도해 주세요."
            FailureKind.PROMPT_TEMPLATE_INCOMPATIBLE -> "이 모델의 프롬프트 템플릿이 현재 LiteRT 버전과 호환되지 않습니다. 모델 설정을 확인한 뒤 다시 시도해 주세요."
            FailureKind.GENERATION_IO -> "모델 응답 중 입출력 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
            FailureKind.GENERATION_INTERRUPTED -> "모델 응답을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."
            else -> "모델 응답을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."
        }
        // Build the immutable generation-after-acquisition failure snapshot
        // from the currently loaded engine state. Callers will read this
        // snapshot, never `engine.lastMtpStatus` or `engine.lastRuntimeSelection`.
        // Phase 2 provenance semantics mirror buildRuntimeExecutionSnapshot:
        // only combine Engine provenance with current-request events when this
        // request created a fresh Engine. Reused engines carry historical
        // provenance that must not appear in the current failure snapshot.
        val fingerprint = ModelFingerprint.of(profile.modelPath)
        val snapshotEvents = if (currentRequestUsesFreshEngine) {
            combineFallbackEvents(
                loaded?.fallbackEvents ?: emptyList(),
                currentRequestFallbackEvents
            )
        } else {
            currentRequestFallbackEvents
        }
        val performancePreference = RuntimePerformancePreferenceStore(
            context.getSharedPreferences(RuntimePerformancePreferenceStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        ).read()
        val fallbackCpuThreads = RuntimeCpuThreadPolicy.resolve(
            performancePreference,
            RuntimeCpuDeviceSignals.observe(context),
        ).threadCount
        val failureSnapshot = if (loaded != null) {
            RuntimeFailureSnapshot(
                requestedAccelerator = profile.accelerator,
                selectedTextBackend = loaded.actualTextBackend.toRuntimeBackend(),
                selectedVisionBackend = loaded.actualVisionBackend?.toRuntimeBackend(),
                mtpRequested = profile.mtpRequested,
                mtpRuntimeStatus = loaded.mtpStatus,
                fallbackEventsFromAcquisition = snapshotEvents,
                modelFingerprint = ModelFingerprintSummary(
                    canonicalPath = fingerprint.canonicalPath,
                    fileSize = fingerprint.fileSize,
                    modifiedAt = fingerprint.modifiedAt,
                    validationVersion = fingerprint.validationVersion,
                    mtpSupported = fingerprint.mtpSupported
                ),
                failureKind = kind,
                cpuThreadCount = if (loaded.actualTextBackend == "CPU" || loaded.actualVisionBackend == "CPU") {
                    loaded.key.cpuThreadCount
                } else fallbackCpuThreads,
                cpuPerformanceMode = performancePreference.mode,
            )
        } else {
            null
        }
        return GenerationOutcome.Failure(
            kind = kind,
            message = message,
            attemptSnapshot = null,
            failureSnapshot = failureSnapshot
        )
    }

    private fun getOrCreateEngine(profile: RequestedEngineProfile): Engine {
        val acquisitionProfile = resolveCpuPerformanceProfile(profile)
        val coordinator = EngineAcquisitionCoordinator(
            mtpFailureMemory = mtpFailureMemory,
            mtpCapabilityProbe = mtpCapabilityProbe,
            configureFlag = { settleSpeculativeDecodingFlag(it) },
            engineFactory = { backendName, mtpEnabled, visionBackendIsCpu ->
                val backend = createLiteRtBackend(backendName, acquisitionProfile.cpuThreadCount)
                val result = createEngine(
                    modelPath = profile.modelPath,
                    backend = backend,
                    visionBackend = if (profile.enableVisionBackend) {
                        if (visionBackendIsCpu) createLiteRtBackend("CPU", acquisitionProfile.cpuThreadCount) else backend
                    } else {
                        null
                    },
                    maxNumTokens = profile.kvCacheCapacityTokens.coerceAtLeast(1)
                )
                if (result.isSuccess) {
                    EngineCandidateAttempt.Success(result.getOrThrow())
                } else {
                    EngineCandidateAttempt.InitializationFailed(result.exceptionOrNull() ?: IllegalStateException("Engine creation failed"))
                }
            },
            clock = clock,
            onPersistenceCompleted = { checkAndRecordDurabilityTransition() }
        )

        val outcome = coordinator.acquire(acquisitionProfile, loadedState)
        val outcomeFingerprint = outcome.fingerprint ?: ModelFingerprint.of(profile.modelPath)
        val outcomeCapabilityResult = outcome.mtpCapabilityResult

        // Phase 2: event ownership comes from the coordinator/selector's typed
        // engineAcquisitionEvents vs requestEvents fields, NOT from partitioning
        // the combined fallbackEvents by FallbackReason. The "same reason code
        // is stable for one engine and request-local for a reused engine" cannot
        // be distinguished by reason, so the selector tags ownership causally at
        // the point of selection.
        // - Fresh engine creation: LoadedRuntimeState.fallbackEvents <- engineAcquisitionEvents
        // - Loaded engine reuse:    LoadedRuntimeState.fallbackEvents unchanged;
        //   currentRequestFallbackEvents <- requestEvents
        val (stableAcquisitionEvents, requestLocalEvents, reusedLoadedEngine) =
            Triple(outcome.engineAcquisitionEvents, outcome.requestEvents, outcome.reusedLoadedEngine)

        // Update runtime state based on outcome
        val selectionResult = outcome.selection
        if (selectionResult != null) {
            val resolvedEngine = selectionResult.engine
            val selectedMtpEnabled = selectionResult.selectedMtpEnabled
            val mtpFlagAppliedForMtp = selectionResult.mtpFlagAppliedForMtp
            val actualTextBackend = selectionResult.backendName
            
            lastMtpStatus = resolveMtpRuntimeStatus(
                mtpRequested = profile.mtpRequested,
                mtpSupported = outcomeFingerprint.mtpSupported,
                selectedMtpEnabled = selectedMtpEnabled,
                mtpFlagAppliedForMtp = mtpFlagAppliedForMtp,
                mtpCapabilityResult = outcomeCapabilityResult,
                mtpSkippedByMemory = requestLocalEvents.any { it.reason == FallbackReason.MTP_SKIPPED_RECENT_FAILURE },
                mtpAttempted = outcomeCapabilityResult != null || outcome.fallbackEvents.any { it.attemptedMtpEnabled == true && it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED } || selectedMtpEnabled
            )
            
            val fallbackReason = resolveMtpFallbackReason(
                mtpRequested = profile.mtpRequested,
                mtpSupported = outcomeFingerprint.mtpSupported,
                selectedMtpEnabled = selectedMtpEnabled,
                mtpFlagAppliedForMtp = mtpFlagAppliedForMtp,
                mtpCapabilityResult = outcomeCapabilityResult,
                mtpSkippedByMemory = requestLocalEvents.any { it.reason == FallbackReason.MTP_SKIPPED_RECENT_FAILURE },
                mtpAttempted = outcomeCapabilityResult != null || outcome.fallbackEvents.any { it.attemptedMtpEnabled == true && it.reason == FallbackReason.MTP_ENGINE_INIT_FAILED } || selectedMtpEnabled
            )
            
            val initializedWithMtp = profile.mtpRequested && outcomeFingerprint.mtpSupported && selectedMtpEnabled && mtpFlagAppliedForMtp
            
            val runtimeSelection = EngineSelectionRuntime(
                requestedAccelerator = profile.accelerator.name,
                actualTextBackend = actualTextBackend,
                actualVisionBackend = selectionResult.visionBackend,
                requestedMtp = profile.mtpRequested,
                initializedWithMtp = initializedWithMtp,
                fallbackReason = outcome.fallbackEvents.firstOrNull()?.reason?.name ?: fallbackReason
            )

            // Phase 2 + Phase 3: Request-local events live in currentRequestFallbackEvents
            // Stable acquisition events go into LoadedRuntimeState.fallbackEvents
            // When reusing an engine, stable events AND actual vision backend are preserved.
            // Phase 3C: replacement adoption is exception-safe via the production
            // EngineReplacement.adopt helper — a throwing old close() can no
            // longer orphan the newly created Engine or leave the loaded state
            // pointing at a partially-retired Engine.
            val currentLoadedState = loadedState
            val isFreshEngine = currentLoadedState == null || currentLoadedState.engine != resolvedEngine
            // Phase 2: track fresh-engine status so snapshot builders know whether
            // loadedState.fallbackEvents (Engine provenance) may be combined with
            // currentRequestFallbackEvents (current execution events). A fresh
            // engine's provenance IS this request's execution history; a reused
            // engine's provenance is historical and must NOT appear in the current
            // snapshot.
            currentRequestUsesFreshEngine = isFreshEngine
            if (isFreshEngine) {
                currentRequestFallbackEvents = requestLocalEvents
                val newRuntimeState = LoadedRuntimeState(
                    engine = resolvedEngine,
                    key = EngineRuntimeKey(
                        fingerprint = outcomeFingerprint,
                        accelerator = profile.accelerator,
                        kvCacheCapacityTokens = profile.kvCacheCapacityTokens,
                        enableVisionBackend = profile.enableVisionBackend,
                        mtpEnabled = selectedMtpEnabled,
                        selectedBackend = actualTextBackend,
                        cpuThreadCount = if (actualTextBackend == "CPU" || selectionResult.visionBackend == "CPU") {
                            acquisitionProfile.cpuThreadCount
                        } else 0,
                        cpuPerformancePreference = if (actualTextBackend == "CPU" || selectionResult.visionBackend == "CPU") {
                            acquisitionProfile.cpuPerformancePreference
                        } else null,
                        selectedVisionBackend = selectionResult.visionBackend,
                    ),
                    mtpStatus = lastMtpStatus,
                    runtimeSelection = runtimeSelection,
                    actualTextBackend = actualTextBackend,
                    actualVisionBackend = selectionResult.visionBackend,
                    fallbackEvents = stableAcquisitionEvents
                )
                val adoption = EngineReplacement.adopt(
                    newEngine = resolvedEngine,
                    getOld = { loadedState?.engine },
                    publish = { loadedState = newRuntimeState },
                    clear = { loadedState = null },
                    close = { engine -> engine.close() }
                )
                if (adoption is EngineAdoptionOutcome.NewEngineClosed) {
                    // Old close() threw: new engine was closed, loaded state
                    // cleared. Surface the failure so the caller can convert it
                    // into a typed runtime failure (no orphan Engine leak).
                    throw EngineSelectionFailedException(
                        attemptSnapshot = RuntimeAttemptSnapshot(
                            requestedAccelerator = profile.accelerator,
                            fallbackEvents = outcome.fallbackEvents,
                            modelFingerprint = ModelFingerprintSummary(
                                canonicalPath = outcomeFingerprint.canonicalPath,
                                fileSize = outcomeFingerprint.fileSize,
                                modifiedAt = outcomeFingerprint.modifiedAt,
                                validationVersion = outcomeFingerprint.validationVersion,
                                mtpSupported = outcomeFingerprint.mtpSupported
                            ),
                            mtpRequested = profile.mtpRequested
                        ),
                        cause = adoption.adoptFailure
                    )
                }
            } else {
                // Reusing existing engine: preserve stable events AND actual vision backend
                currentRequestFallbackEvents = requestLocalEvents
                currentRequestUsesFreshEngine = false
                loadedState = currentLoadedState.copy(
                    // A preference transition may reuse the same thread count.
                    // Retention on the next request must use the admitted preference.
                    key = currentLoadedState.key.copy(
                        cpuPerformancePreference = if (actualTextBackend == "CPU" || currentLoadedState.actualVisionBackend == "CPU") {
                            acquisitionProfile.cpuPerformancePreference
                        } else null,
                    ),
                    mtpStatus = lastMtpStatus,
                    runtimeSelection = runtimeSelection,
                    actualTextBackend = actualTextBackend
                    // actualVisionBackend preserved from loaded state (Phase 3)
                    // fallbackEvents (stable) unchanged
                )
            }
            return resolvedEngine
        } else {
            // Failure path: no engine acquired
            currentRequestFallbackEvents = requestLocalEvents
            currentRequestUsesFreshEngine = false
            settleSpeculativeDecodingFlag(false)
            lastMtpStatus = MtpRuntimeStatus.FAILED
            val attemptSnapshot = outcome.failure?.let { failure ->
                if (failure is EngineSelectionFailedException) {
                    failure.attemptSnapshot
                } else {
                    RuntimeAttemptSnapshot(
                        requestedAccelerator = profile.accelerator,
                        fallbackEvents = outcome.fallbackEvents,
                        modelFingerprint = ModelFingerprintSummary(
                            canonicalPath = outcomeFingerprint.canonicalPath,
                            fileSize = outcomeFingerprint.fileSize,
                            modifiedAt = outcomeFingerprint.modifiedAt,
                            validationVersion = outcomeFingerprint.validationVersion,
                            mtpSupported = outcomeFingerprint.mtpSupported
                        ),
                        mtpRequested = profile.mtpRequested
                    )
                }
            } ?: RuntimeAttemptSnapshot(
                requestedAccelerator = profile.accelerator,
                fallbackEvents = outcome.fallbackEvents,
                modelFingerprint = ModelFingerprintSummary(
                    canonicalPath = outcomeFingerprint.canonicalPath,
                    fileSize = outcomeFingerprint.fileSize,
                    modifiedAt = outcomeFingerprint.modifiedAt,
                    validationVersion = outcomeFingerprint.validationVersion,
                    mtpSupported = outcomeFingerprint.mtpSupported
                ),
                mtpRequested = profile.mtpRequested
            )
            throw EngineSelectionFailedException(attemptSnapshot, outcome.failure)
        }
    }

    private fun createEngine(
        modelPath: String,
        backend: Backend,
        visionBackend: Backend?,
        maxNumTokens: Int
    ): Result<Engine> {
        // Phase 3A: never use runCatching here. runCatching collapses fatal
        // Errors (OutOfMemoryError, LinkageError, ThreadDeath) into ordinary
        // EngineCandidateAttempt.InitializationFailed events, so OOM on a
        // memory-constrained device looks like a normal backend init failure.
        // runNonFatal rethrows fatal Errors and keeps recoverable Exceptions as
        // Result.failure so they become typed candidate failures normally.
        return try {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = visionBackend,
                maxNumTokens = maxNumTokens,
                maxNumImages = if (visionBackend != null) 8 else null,
                cacheDir = context.cacheDir.absolutePath
            )
            Result.success(engineFactory(config))
        } catch (e: Throwable) {
            if (e.isFatalError() || e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Distills the vendor BenchmarkInfo of the last conversation run. Returns
     * null when the runtime reports none (e.g., no message was sent yet).
     */
    private fun conversationBenchmarkStats(conversation: Conversation): GenerationBenchmarkStats? {
        // runNonFatal, never runCatching: fatal Errors (OOM, LinkageError,
        // ThreadDeath) must propagate instead of collapsing into null stats
        // that hide memory pressure after successful tokens.
        return runNonFatal {
            val info = conversation.getBenchmarkInfo()
            GenerationBenchmarkStats(
                initTimeSeconds = info.initTimeInSecond,
                timeToFirstTokenSeconds = info.timeToFirstTokenInSecond,
                prefillTokenCount = info.lastPrefillTokenCount,
                decodeTokenCount = info.lastDecodeTokenCount,
                prefillTokensPerSecond = info.lastPrefillTokensPerSecond,
                decodeTokensPerSecond = info.lastDecodeTokensPerSecond
            )
        }
    }

    /**
     * Builds the immutable [RuntimeExecutionSnapshot] for the currently loaded
     * runtime. The fallback event list comes from the typed events captured
     * during candidate selection (Phase 3) rather than from a single fallback
     * reason string, so multiple concurrent fallbacks (MTP + backend) survive.
     * [samplerBackend] is UNKNOWN until a stable LiteRT-LM API reports sampler
     * placement (see Phase 9).
     *
     * Phase 2 provenance semantics:
     *  - [LoadedRuntimeState.fallbackEvents] is Engine provenance: the stable
     *    history of how this Engine originally came to exist.
     *  - [currentRequestFallbackEvents] is current-execution events: only the
     *    fallback/skip/failure events relevant to THIS request.
     *  - For a fresh Engine, provenance IS this request's acquisition history,
     *    so both lists are combined.
     *  - For a reused Engine, provenance is historical and must NOT appear in
     *    the current snapshot; only current-request events are included.
     */
    private fun buildRuntimeExecutionSnapshot(
        profile: RequestedEngineProfile
    ): RuntimeExecutionSnapshot? {
        val state = loadedState ?: return null
        val fingerprint = state.key.fingerprint
        // Phase 2: only combine Engine provenance with current-request events
        // when the Engine was freshly created for this request. Reused engines
        // must not replay old provenance as current execution truth.
        val snapshotEvents = if (currentRequestUsesFreshEngine) {
            combineFallbackEvents(state.fallbackEvents, currentRequestFallbackEvents)
        } else {
            currentRequestFallbackEvents
        }
        val performancePreference = RuntimePerformancePreferenceStore(
            context.getSharedPreferences(RuntimePerformancePreferenceStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        ).read()
        val fallbackCpuThreads = RuntimeCpuThreadPolicy.resolve(
            performancePreference,
            RuntimeCpuDeviceSignals.observe(context),
        ).threadCount
        return RuntimeExecutionSnapshot(
            requestedAccelerator = profile.accelerator,
            selectedTextBackend = state.actualTextBackend.toRuntimeBackend(),
            selectedVisionBackend = state.actualVisionBackend?.toRuntimeBackend(),
            samplerBackend = RuntimeComponentBackend.UNKNOWN,
            mtpRequested = profile.mtpRequested,
            mtpStatus = state.mtpStatus,
            fallbackEvents = snapshotEvents,
            modelFingerprint = ModelFingerprintSummary(
                canonicalPath = fingerprint.canonicalPath,
                fileSize = fingerprint.fileSize,
                modifiedAt = fingerprint.modifiedAt,
                validationVersion = fingerprint.validationVersion,
                mtpSupported = fingerprint.mtpSupported
            ),
            cpuThreadCount = if (state.actualTextBackend == "CPU" || state.actualVisionBackend == "CPU") {
                state.key.cpuThreadCount
            } else fallbackCpuThreads,
            cpuPerformanceMode = performancePreference.mode,
        )
    }

    /**
     * Phase 1: deterministic combination of stable Engine acquisition events
     * and request-local cooldown/skip events. Both lists are immutable.
     * Duplicate consecutive events are collapsed using an explicit bounded
     * deduplication policy: a duplicate is defined as having the same
     * [RuntimeFallbackEvent.reason], [RuntimeFallbackEvent.attemptedTextBackend],
     * [RuntimeFallbackEvent.attemptedMtpEnabled], and
     * [RuntimeFallbackEvent.attemptedVisionBackend]; no more than 2 consecutive
     * duplicates are kept.
     */
    private fun buildSamplerConfig(options: ConversationOptions): SamplerConfig {        val topK = options.topK.coerceAtLeast(1)
        val topP = LocalGenerationFloatSettings.topPOrDefault(options.topP).toDouble()
        val temperature = LocalGenerationFloatSettings.temperatureOrDefault(options.temperature).toDouble()
        return if (options.seed != null) {
            SamplerConfig(topK, topP, temperature, options.seed)
        } else {
            SamplerConfig(topK, topP, temperature)
        }
    }

    private fun logGenerationSettings(
        profile: RequestedEngineProfile,
        options: ConversationOptions
    ) {
        Log.i(
            "FusionLiteRT",
            buildString {
                appendLine("Generation settings before request")
                appendLine("modelPath=${File(profile.modelPath).name}")
                appendLine("accelerator=${profile.accelerator.name} (runtime EngineConfig.backend)")
                appendLine("kvCacheCapacityTokens=${profile.kvCacheCapacityTokens} (runtime EngineConfig.maxNumTokens)")
                appendLine("topK=${options.topK} (runtime SamplerConfig.topK)")
                appendLine("topP=${options.topP} (runtime SamplerConfig.topP)")
                appendLine("temperature=${options.temperature} (runtime SamplerConfig.temperature)")
                appendLine("MTP requested=${profile.mtpRequested} (runtime ExperimentalFlags.enableSpeculativeDecoding)")
                appendLine("visionBackend=${profile.enableVisionBackend} (runtime EngineConfig.visionBackend when true)")
            }.trimEnd()
        )
    }

    override fun unload() {
        try {
            loadedState?.engine?.close()
        } catch (throwable: Throwable) {
            Log.w("FusionEngine", "Failed to close LiteRT engine", throwable)
        }

        loadedState = null
        lastMtpStatus = MtpRuntimeStatus.OFF
        // Reset the global flag on unload; the next selection re-settles it
        // before any engine init, so a failed reset cannot poison a later load.
        runCatching {
            settleSpeculativeDecodingFlag(false)
        }
    }

    /**
     * Clears MTP failure memory for the given model path.
     * Call this when the model file has changed (size/mtime) or on explicit manual retry.
     */
    fun clearMtpFailureMemory(modelPath: String) {
        val canonicalPath = runCatching { File(modelPath).canonicalPath }.getOrElse { modelPath }
        mtpFailureMemory.clearForModel(canonicalPath)
        checkAndRecordDurabilityTransition()
    }

    /**
     * Clears all MTP failure memory (e.g., on explicit user retry).
     */
    fun clearAllMtpFailureMemory() {
        mtpFailureMemory.clearAll()
        checkAndRecordDurabilityTransition()
    }

    /**
     * Phase D: typed durability state of the Engine-owned failure memory.
     *
     *  - [FailureMemoryDurability.NotAttempted]: no save/clear operation has
     *    been attempted yet since the Engine was constructed.
     *  - [FailureMemoryDurability.Durable]: the last save/clear succeeded;
     *    cooldown is expected to survive a process restart.
     *  - [FailureMemoryDurability.InMemoryOnly]: the last save/clear either
     *    returned false or threw. Cooldown still works for the lifetime of
     *    this Engine instance but will NOT survive a restart. [cause]
     *    carries the typed exception (or a synthetic IllegalStateException
     *    when storage returned false without throwing) so callers can
     *    route the failure into diagnostics/developer logging.
     */
    fun failureMemoryDurability(): FailureMemoryDurability {
        val result = mtpFailureMemory.lastDurabilityResult()
        val cause = mtpFailureMemory.lastDurabilityException()
        return when (result) {
            null -> FailureMemoryDurability.NotAttempted
            true -> FailureMemoryDurability.Durable
            false -> FailureMemoryDurability.InMemoryOnly(
                cause = cause ?: IllegalStateException("Failure memory storage returned false")
            )
        }
    }

    /**
     * Checks for durability state transitions and records them to the developer log.
     * Called after operations that may modify failure memory persistence.
     */
    private fun checkAndRecordDurabilityTransition() {
        val current = failureMemoryDurability()
        if (current != lastRecordedDurability) {
            when {
                lastRecordedDurability == FailureMemoryDurability.Durable && current is FailureMemoryDurability.InMemoryOnly -> {
                    FusionDeveloperLogStore.recordDurabilityState(context, current)
                }
                lastRecordedDurability is FailureMemoryDurability.InMemoryOnly && current == FailureMemoryDurability.Durable -> {
                    FusionDeveloperLogStore.recordDurabilityState(context, current)
                }
                lastRecordedDurability == FailureMemoryDurability.NotAttempted && current != FailureMemoryDurability.NotAttempted -> {
                    FusionDeveloperLogStore.recordDurabilityState(context, current)
                }
            }
            lastRecordedDurability = current
        }
    }

    /**
     * Settles the speculative-decoding flag to [desired] and verifies the
     * result before any Engine initialization. Returns true only when the
     * flag reached the desired state:
     * - the setter must report success, and
     * - if the runtime exposes the flag value, the read-back must match.
     * A failed settle means the flag state is unknown; the caller must skip
     * the candidate instead of initializing an engine on faith.
     */
    private fun settleSpeculativeDecodingFlag(desired: Boolean): Boolean {
        val applied = flagSetter(desired)
        if (!applied) {
            Log.w(
                "FusionLiteRT",
                "Failed to ${if (desired) "enable" else "disable"} MTP speculative decoding flag"
            )
            return false
        }
        val readBack = runNonFatal { flagReader() }
        if (readBack != null && readBack != desired) {
            Log.w(
                "FusionLiteRT",
                "MTP speculative decoding flag read-back mismatch: desired=$desired actual=$readBack"
            )
            return false
        }
        return true
    }
}

enum class MtpRuntimeStatus {
    OFF,
    UNSUPPORTED,
    REQUESTED,
    INITIALIZED_WITH_MTP_REQUEST,
    RUNTIME_CONFIRMED_ACTIVE,
    FALLBACK_DISABLED,
    FAILED
}

/**
 * Phase D: typed durability state of the Engine-owned failure memory.
 *
 * Exposed through [LiteRtLlmEngine.failureMemoryDurability] and observable
 * through diagnostics/developer logging so the app never claims cooldown
 * will survive a restart when persistence is broken.
 */
sealed interface FailureMemoryDurability {
    /** No save/clear operation has been attempted yet. */
    data object NotAttempted : FailureMemoryDurability
    /** The last save/clear succeeded; cooldown is durable. */
    data object Durable : FailureMemoryDurability
    /**
     * The last save/clear either returned false or threw. Cooldown still
     * works in-memory for the lifetime of this Engine instance but will
     * NOT survive a restart. [cause] is the typed exception (or a
     * synthetic IllegalStateException when storage returned false without
     * throwing).
     */
    data class InMemoryOnly(val cause: Throwable) : FailureMemoryDurability
}

public data class EngineSelectionRuntime(
    val requestedAccelerator: String,
    val actualTextBackend: String,
    val actualVisionBackend: String?,
    val requestedMtp: Boolean,
    val initializedWithMtp: Boolean,
    val fallbackReason: String?
)

/**
 * Resolves the authoritative MTP runtime status from the selection evidence.
 *
 * A pre-Engine [Capabilities.hasSpeculativeDecodingSupport] capability check
 * cannot prove that speculative decoding was active during generation; it only
 * filters out candidates that definitely lack support. A successful MTP Engine
 * initialization therefore reports [MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST],
 * never `RUNTIME_CONFIRMED_ACTIVE`. The latter remains in the enum as a reserved
 * value that becomes reachable only if LiteRT-LM later exposes positive
 * execution evidence (e.g. drafted/accepted-token counters); absent such an
 * API it is deliberately unreachable from this resolver.
 *
 * Phase 4: [MtpRuntimeStatus.FAILED] is reserved for the no-usable-Engine case
 * (set explicitly in [LiteRtLlmEngine.getOrCreateEngine] before the throw). This
 * resolver is reached only after a usable Engine initialized, so any "MTP was
 * requested but a non-MTP engine succeeded" path returns
 * [MtpRuntimeStatus.FALLBACK_DISABLED] — including the flag-settlement-failed
 * path previously encoded as FAILED. The flag-settle-failed event carries the
 * [FallbackReason.SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED] reason.
 */
internal fun resolveMtpRuntimeStatus(
    mtpRequested: Boolean,
    mtpSupported: Boolean,
    selectedMtpEnabled: Boolean,
    mtpFlagAppliedForMtp: Boolean,
    mtpCapabilityResult: Boolean?,
    mtpSkippedByMemory: Boolean,
    mtpAttempted: Boolean
): MtpRuntimeStatus = when {
    !mtpRequested -> MtpRuntimeStatus.OFF
    !mtpSupported -> MtpRuntimeStatus.UNSUPPORTED
    selectedMtpEnabled && mtpFlagAppliedForMtp -> MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST
    mtpSkippedByMemory -> MtpRuntimeStatus.FALLBACK_DISABLED
    mtpAttempted -> MtpRuntimeStatus.FALLBACK_DISABLED
    else -> MtpRuntimeStatus.FALLBACK_DISABLED
}

internal fun resolveMtpFallbackReason(
    mtpRequested: Boolean,
    mtpSupported: Boolean,
    selectedMtpEnabled: Boolean,
    mtpFlagAppliedForMtp: Boolean,
    mtpCapabilityResult: Boolean?,
    mtpSkippedByMemory: Boolean,
    mtpAttempted: Boolean
): String? = when {
    mtpRequested && !mtpSupported -> "Model does not support MTP"
    mtpSkippedByMemory -> "MTP disabled due to previous failure"
    mtpAttempted && !selectedMtpEnabled && mtpCapabilityResult == false -> "MTP capability probe: no speculative decoding support"
    mtpAttempted && !selectedMtpEnabled -> "MTP initialization failed, fell back to non-MTP"
    mtpRequested && mtpSupported && !mtpFlagAppliedForMtp -> "MTP flag application failed"
    else -> null
}

/**
 * Builds the system instruction from the given messages. Deliberately takes no
 * settings: runtime settings (accelerator/MTP/KV capacity/sampling) must never
 * appear in prompt bytes, so MTP on/off produce identical prompts.
 */
internal fun buildSystemInstruction(messages: List<ChatMessage>): String {
    val systemMessages = messages
        .filter { it.role == "system" }
        .joinToString("\n\n") { it.content }

    return buildString {
        appendLine("당신은 기기 내에서 실행되는 AI 비서 Fusion입니다.")
        appendLine("한국어로 자연스럽게 답변하며 일관되게 존댓말을 사용합니다.")
        appendLine("모르는 내용은 모른다고 명확히 밝힙니다.")
        appendLine("추론이나 추정은 그 사실을 명확히 구분합니다.")

        if (systemMessages.isNotBlank()) {
            appendLine()
            appendLine(systemMessages)
        }
    }
}

/**
 * Builds the user turn prompt. Takes no settings for the same reason as
 * [buildSystemInstruction].
 */
internal fun buildPrompt(
    messages: List<ChatMessage>
): String {
    val nonSystemMessages = messages.filter { it.role != "system" }

    return buildString {
        nonSystemMessages.forEach { message ->
            when (message.role) {
                "user" -> {
                    appendLine("User:")
                    appendLine(message.content)
                    appendLine()
                }

                "assistant" -> {
                    appendLine("Assistant:")
                    appendLine(message.content)
                    appendLine()
                }
            }
        }

        appendLine("Assistant:")
    }
}

/**
 * Heuristic estimate of the token count in the accumulated streaming output
 * (~4 characters per token, matching the existing benchmark estimator). Used
 * only to enforce the app-level streaming output limit; the hard bound on
 * memory is the KV cache capacity, not this estimate.
 */
internal fun estimateStreamOutputTokensFromCharCount(charCount: Int): Int =
    (charCount / 4.0).toInt().coerceAtLeast(0)

internal fun estimateStreamOutputTokens(text: String): Int =
    estimateStreamOutputTokensFromCharCount(text.length)

internal fun isAppOutputLimitReached(options: ConversationOptions, accumulatedOutput: StringBuilder): Boolean {
    val limit = options.maxOutputToken ?: return false
    return estimateStreamOutputTokensFromCharCount(accumulatedOutput.length) >= limit
}

sealed interface EngineCandidateAttempt<out T> {
    data class Success<T>(val engine: T) : EngineCandidateAttempt<T>
    data class CapabilityRejected(val backendName: String, val mtpEnabled: Boolean) : EngineCandidateAttempt<Nothing>
    data class InitializationFailed(val throwable: Throwable) : EngineCandidateAttempt<Nothing>
}

internal data class EngineCandidate(
    val backend: String,
    val mtpEnabled: Boolean,
)

internal data class EngineSelectionResult<T>(
    val engine: T,
    val selectedMtpEnabled: Boolean,
    val mtpFlagAppliedForMtp: Boolean,
    val backendName: String,
    val visionBackend: String?,
    val fingerprint: ModelFingerprint? = null,
    val mtpCapabilityResult: Boolean? = null
)

/**
 * Outcome of iterating the candidate ladder: the first working selection (if
 * any), the last failure when nothing worked, and the typed fallback events
 * captured along the way. Replaces the previous `Pair<EngineSelectionResult<T>?, Throwable?>`
 * so app-level fallbacks are recorded as stable enums instead of English strings.
 *
 * Phase 2 ownership: events are split into:
 *  - [engineAcquisitionEvents]: the causal history of *this* engine's
 *    acquisition (filled only when an Engine was freshly created/selected).
 *    For loaded-engine reuse this list is empty — the reused Engine's stable
 *    acquisition history stays in [LoadedRuntimeState.fallbackEvents].
 *  - [requestEvents]: every event produced while processing the current
 *    request before reusing an existing Engine (e.g. MTP_UNSUPPORTED from a
 *    probe, flag-settlement failure, per-candidate failure-memory skips). For
 *    a fresh-Engine creation this list is empty unless a higher-priority
 *    candidate failed before the lower-priority one that produced the stable
 *    engine succeeded; in that case only the lower candidates' events are
 *    acquisition events of the new engine and the higher candidates' events
 *    are [requestEvents].
 *  - [reusedLoadedEngine]: true when the selected Engine came from the loaded
 *    runtime cache, false (or omitted) when a fresh Engine was created.
 *
 * [fallbackEvents] is preserved as the time-ordered union of both lists so
 * existing callers that only inspect the combined view continue to work.
 * Callers that need ownership MUST use the typed lists; never partition
 * [fallbackEvents] by [FallbackReason], which is a fragile classification
 * that fails when the same reason code is legitimate engine-acquisition
 * history for one engine and request-local for a reused engine.
 */
internal data class EngineSelectionOutcome<T>(
    val selection: EngineSelectionResult<T>?,
    val failure: Throwable?,
    val fallbackEvents: List<RuntimeFallbackEvent> = emptyList(),
    val fingerprint: ModelFingerprint? = null,
    val mtpCapabilityResult: Boolean? = null,
    val engineAcquisitionEvents: List<RuntimeFallbackEvent> = emptyList(),
    val requestEvents: List<RuntimeFallbackEvent> = emptyList(),
    val reusedLoadedEngine: Boolean = false
)

internal fun <T> selectFirstWorkingEngine(
    ladder: List<EngineCandidate>,
    enableVisionBackend: Boolean,
    configureFlag: (Boolean) -> Boolean,
    tryCreate: (backendName: String, mtpEnabled: Boolean, visionBackendIsCpu: Boolean) -> EngineCandidateAttempt<T>,
    loadedState: LoadedRuntimeState<T>? = null,
    fingerprint: ModelFingerprint? = null,
    accelerator: AcceleratorMode? = null,
    kvCacheCapacityTokens: Int? = null,
    enableVisionBackendProfile: Boolean? = null,
    mtpFailureMemory: MtpFailureMemoryPort? = null,
    profile: RequestedEngineProfile? = null,
    // Phase 6: the selector never reads a clock — cooldown timing lives inside
    // the MtpFailureMemory implementation behind [mtpFailureMemory]. The unused
    // `clock` parameter was removed rather than silently ignored.
    onPersistenceCompleted: (() -> Unit)? = null
): EngineSelectionOutcome<T> {
    // If loadedState reuse check is requested, all parameters must be provided
    val reuseEnabled = loadedState != null && fingerprint != null && accelerator != null && kvCacheCapacityTokens != null && enableVisionBackendProfile != null
    var mtpFlagAppliedForMtp = false
    var lastFailure: Throwable? = null
    val fallbackEvents = mutableListOf<RuntimeFallbackEvent>()
    var pendingGpuPlainFailure = false
    for (candidate in ladder) {
        // Check for exact loaded-engine reuse at this candidate level FIRST
        // This must happen before failure memory checks so that a live engine
        // can be reused even if lower candidates have cooldown records.
        if (reuseEnabled) {
            val candidateKey = EngineRuntimeKey(
                fingerprint = fingerprint!!,
                accelerator = accelerator!!,
                kvCacheCapacityTokens = kvCacheCapacityTokens!!,
                enableVisionBackend = enableVisionBackendProfile!!,
                mtpEnabled = candidate.mtpEnabled,
                selectedBackend = candidate.backend,
                cpuThreadCount = if (candidate.backend == "CPU" || loadedState!!.actualVisionBackend == "CPU") {
                    profile?.cpuThreadCount ?: 0
                } else 0,
                cpuPerformancePreference = if (candidate.backend == "CPU" || loadedState!!.actualVisionBackend == "CPU") {
                    profile?.cpuPerformancePreference
                } else null,
                selectedVisionBackend = loadedState!!.actualVisionBackend,
            )
            // Explicit field-by-field comparison to avoid any data class equality edge cases.
            // Fingerprint canonicalPath must match exactly.
            val keyMatch = loadedState!!.key.accelerator == candidateKey.accelerator &&
                loadedState!!.key.kvCacheCapacityTokens == candidateKey.kvCacheCapacityTokens &&
                loadedState!!.key.enableVisionBackend == candidateKey.enableVisionBackend &&
                loadedState!!.key.mtpEnabled == candidateKey.mtpEnabled &&
                loadedState!!.key.selectedBackend == candidateKey.selectedBackend &&
                loadedState!!.key.cpuThreadCount == candidateKey.cpuThreadCount &&
                loadedState!!.key.selectedVisionBackend == candidateKey.selectedVisionBackend &&
                loadedState!!.key.fingerprint.canonicalPath == candidateKey.fingerprint.canonicalPath &&
                loadedState!!.key.fingerprint.fileSize == candidateKey.fingerprint.fileSize &&
                loadedState!!.key.fingerprint.modifiedAt == candidateKey.fingerprint.modifiedAt &&
                loadedState!!.key.fingerprint.validationVersion == candidateKey.fingerprint.validationVersion &&
                loadedState!!.key.fingerprint.mtpSupported == candidateKey.fingerprint.mtpSupported
            if (keyMatch) {
                // Loaded-engine reuse: every event accumulated so far was
                // produced while processing the current request before reaching
                // the reuse candidate. Its stable acquisition history already
                // lives in LoadedRuntimeState.fallbackEvents and must not be
                // duplicated here, so engineAcquisitionEvents is empty and the
                // accumulated events become requestEvents.
                return EngineSelectionOutcome(
                    selection = EngineSelectionResult(
                        engine = loadedState!!.engine,
                        selectedMtpEnabled = candidate.mtpEnabled,
                        mtpFlagAppliedForMtp = loadedState!!.mtpStatus == MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST,
                        backendName = candidate.backend,
                        visionBackend = loadedState!!.actualVisionBackend,
                        fingerprint = fingerprint!!,
                        mtpCapabilityResult = null
                    ),
                    failure = null,
                    fallbackEvents = fallbackEvents,
                    requestEvents = fallbackEvents.toList(),
                    reusedLoadedEngine = true
                )
            }
        }

        // Check failure memory for this candidate BEFORE attempting it
        if (mtpFailureMemory != null && profile != null) {
            val fingerprint = fingerprint!!
            val candidateFingerprint = fingerprint
            if (candidate.mtpEnabled) {
                val skipReason = mtpFailureMemory.shouldSkipMtp(
                    modelPath = candidateFingerprint.canonicalPath,
                    backendName = candidate.backend,
                    mtpEnabled = true,
                    validationVersion = candidateFingerprint.validationVersion,
                    kvCacheCapacityTokens = profile.kvCacheCapacityTokens,
                    enableVisionBackend = profile.enableVisionBackend,
                    fileSize = candidateFingerprint.fileSize,
                    modifiedAt = candidateFingerprint.modifiedAt,
                    mtpSupported = candidateFingerprint.mtpSupported
                )
                if (skipReason != null) {
                    fallbackEvents += RuntimeFallbackEvent(
                        attemptedTextBackend = candidate.backend.toRuntimeBackend(),
                        attemptedMtpEnabled = true,
                        reason = FallbackReason.MTP_SKIPPED_RECENT_FAILURE
                    )
                    continue
                }
            } else {
                val skipReason = mtpFailureMemory.shouldSkipBackend(
                    modelPath = candidateFingerprint.canonicalPath,
                    backendName = candidate.backend,
                    validationVersion = candidateFingerprint.validationVersion,
                    kvCacheCapacityTokens = profile.kvCacheCapacityTokens,
                    enableVisionBackend = profile.enableVisionBackend,
                    fileSize = candidateFingerprint.fileSize,
                    modifiedAt = candidateFingerprint.modifiedAt,
                    mtpSupported = candidateFingerprint.mtpSupported
                )
                if (skipReason != null) {
                    fallbackEvents += RuntimeFallbackEvent(
                        attemptedTextBackend = candidate.backend.toRuntimeBackend(),
                        attemptedMtpEnabled = false,
                        reason = FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE
                    )
                    continue
                }
            }
        }
        // If we get here, the candidate is not skipped by failure memory
        // Notify persistence completion for any expired entries
        profile?.let { onPersistenceCompleted?.invoke() }

        val flagApplied = configureFlag(candidate.mtpEnabled)
        if (!flagApplied) {
            fallbackEvents += RuntimeFallbackEvent(
                attemptedTextBackend = candidate.backend.toRuntimeBackend(),
                attemptedMtpEnabled = candidate.mtpEnabled,
                reason = if (candidate.mtpEnabled) FallbackReason.SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED
                    else FallbackReason.SPECULATIVE_DISABLE_FLAG_SETTLEMENT_FAILED
            )
            lastFailure = IllegalStateException("Speculative decoding flag settle failed for mtp=${candidate.mtpEnabled}")
            continue
        }
        if (candidate.mtpEnabled) {
            mtpFlagAppliedForMtp = true
        }
        val attempt = tryCreate(candidate.backend, candidate.mtpEnabled, false)
        when (attempt) {
            is EngineCandidateAttempt.Success -> {
                if (pendingGpuPlainFailure && candidate.backend == "CPU" && !candidate.mtpEnabled) {
                    fallbackEvents += RuntimeFallbackEvent(
                        attemptedTextBackend = RuntimeBackend.GPU,
                        attemptedMtpEnabled = false,
                        selectedReplacementBackend = RuntimeBackend.CPU,
                        reason = FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED
                    )
                }
                return EngineSelectionOutcome(
                    selection = EngineSelectionResult(
                        engine = attempt.engine,
                        selectedMtpEnabled = candidate.mtpEnabled,
                        mtpFlagAppliedForMtp = mtpFlagAppliedForMtp,
                        backendName = candidate.backend,
                        visionBackend = if (enableVisionBackend) candidate.backend else null,
                        fingerprint = fingerprint,
                        mtpCapabilityResult = null
                    ),
                    failure = null,
                    fallbackEvents = fallbackEvents,
                    engineAcquisitionEvents = fallbackEvents.toList()
                )
            }
            is EngineCandidateAttempt.CapabilityRejected -> {
                // Capability rejection (e.g. MTP unsupported) must not
                // trigger a vision-backend retry for the same candidate.
                // Record MTP_UNSUPPORTED and move to the next candidate.
                fallbackEvents += RuntimeFallbackEvent(
                    attemptedTextBackend = attempt.backendName.toRuntimeBackend(),
                    attemptedMtpEnabled = attempt.mtpEnabled,
                    reason = FallbackReason.MTP_UNSUPPORTED
                )
                lastFailure = IllegalStateException("MTP capability probe: no speculative decoding support")
            }
            is EngineCandidateAttempt.InitializationFailed -> {
                lastFailure = attempt.throwable
                if (enableVisionBackend && candidate.backend == "GPU") {
                    val visionRetry = tryCreate(candidate.backend, candidate.mtpEnabled, true)
                    if (visionRetry is EngineCandidateAttempt.Success) {
                        fallbackEvents += RuntimeFallbackEvent(
                            attemptedTextBackend = RuntimeBackend.GPU,
                            attemptedVisionBackend = RuntimeBackend.GPU,
                            attemptedMtpEnabled = candidate.mtpEnabled,
                            selectedReplacementBackend = RuntimeBackend.CPU,
                            reason = FallbackReason.GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED
                        )
                        return EngineSelectionOutcome(
                            selection = EngineSelectionResult(
                                engine = visionRetry.engine,
                                selectedMtpEnabled = candidate.mtpEnabled,
                                mtpFlagAppliedForMtp = mtpFlagAppliedForMtp,
                                backendName = candidate.backend,
                                visionBackend = "CPU"
                            ),
                            failure = null,
                            fallbackEvents = fallbackEvents,
                            engineAcquisitionEvents = fallbackEvents.toList()
                        )
                    }
                    // Both first attempt and vision retry failed for a GPU candidate.
                    // Only set pendingGpuPlainFailure for plain GPU (non-MTP) failures.
                    // If GPU+MTP failed but plain GPU was skipped, don't set this flag.
                    if (!candidate.mtpEnabled) {
                        pendingGpuPlainFailure = true
                    }
                    if (candidate.mtpEnabled) {
                        fallbackEvents += RuntimeFallbackEvent(
                            attemptedTextBackend = candidate.backend.toRuntimeBackend(),
                            attemptedMtpEnabled = true,
                            reason = FallbackReason.MTP_ENGINE_INIT_FAILED
                        )
                    } else {
                        fallbackEvents += RuntimeFallbackEvent(
                            attemptedTextBackend = RuntimeBackend.GPU,
                            attemptedMtpEnabled = false,
                            reason = FallbackReason.BACKEND_ENGINE_INIT_FAILED
                        )
                    }
                } else {
                    if (candidate.mtpEnabled) {
                        fallbackEvents += RuntimeFallbackEvent(
                            attemptedTextBackend = candidate.backend.toRuntimeBackend(),
                            attemptedMtpEnabled = true,
                            reason = FallbackReason.MTP_ENGINE_INIT_FAILED
                        )
                    } else {
                        pendingGpuPlainFailure = candidate.backend == "GPU"
                        fallbackEvents += RuntimeFallbackEvent(
                            attemptedTextBackend = candidate.backend.toRuntimeBackend(),
                            attemptedMtpEnabled = false,
                            reason = FallbackReason.BACKEND_ENGINE_INIT_FAILED
                        )
                    }
                }
            }
        }
    }
    return EngineSelectionOutcome(
        selection = null,
        failure = lastFailure,
        fallbackEvents = fallbackEvents,
        // Failure path: no engine was selected, so there is no freshly created
        // engine ownership set. The accumulated events are the request-local
        // acquisition history of the failed attempt.
        requestEvents = fallbackEvents.toList()
    )
}

internal fun String.toRuntimeBackend(): RuntimeBackend = when (this) {
    "CPU" -> RuntimeBackend.CPU
    "GPU" -> RuntimeBackend.GPU
    else -> RuntimeBackend.UNKNOWN
}

internal fun buildEngineCandidateLadder(
    accelerator: AcceleratorMode,
    mtpRequested: Boolean,
    mtpSupported: Boolean
): List<EngineCandidate> {
    val canUseMtp = mtpRequested && mtpSupported
    return when (accelerator) {
        AcceleratorMode.CPU -> buildList {
            // CPU+MTP requires an explicit user MTP request (the AUTO policy
            // never enables MTP on CPU), so this stays as an explicit
            // experimental path only.
            if (canUseMtp) add(EngineCandidate("CPU", mtpEnabled = true))
            add(EngineCandidate("CPU", mtpEnabled = false))
        }
        AcceleratorMode.GPU -> buildList {
            if (canUseMtp) add(EngineCandidate("GPU", mtpEnabled = true))
            add(EngineCandidate("GPU", mtpEnabled = false))
        }
        AcceleratorMode.AUTO -> buildList {
            // Beta AUTO ladder: GPU+MTP -> GPU -> CPU. CPU+MTP is never an
            // automatic fallback, and the ladder stays at most 3 candidates
            // to avoid sequential engine inits after MTP failure.
            if (canUseMtp) add(EngineCandidate("GPU", mtpEnabled = true))
            add(EngineCandidate("GPU", mtpEnabled = false))
            add(EngineCandidate("CPU", mtpEnabled = false))
        }
    }
}

// Phase 6: the standalone `canReuseLoadedEngine` helper was removed. The
// loaded-engine reuse decision is inlined in `selectFirstWorkingEngine`, which
// compares the loaded Engine's EngineRuntimeKey against each *planned*
// candidate before the per-candidate failure-memory check — so a live,
// successfully initialized Engine is treated as alive and failure memory can
// only skip *new* initialization attempts, never an already-live engine.

interface MtpFailureMemoryStorage {
    fun load(): Map<String, String>
    fun save(entries: Map<String, String>): Boolean
    fun clear(): Boolean
}

object NoopMtpFailureMemoryStorage : MtpFailureMemoryStorage {
    override fun load(): Map<String, String> = emptyMap()
    override fun save(entries: Map<String, String>): Boolean = true
    override fun clear(): Boolean = true
}

/**
 * Groups failure memory entries by the model's immutable identity.
 * When a model is replaced at the same path (different file size,
 * modified time, validator version, or capability fingerprint),
 * all entries belonging to the old fingerprint are invalidated.
 */
internal data class FailureModelFingerprint(
    val canonicalPath: String,
    val fileSize: Long,
    val modifiedAt: Long,
    val validationVersion: Int,
    val mtpSupported: Boolean,
) {
    fun matches(other: FailureModelFingerprint): Boolean =
        canonicalPath == other.canonicalPath &&
            fileSize == other.fileSize &&
            modifiedAt == other.modifiedAt &&
            validationVersion == other.validationVersion &&
            mtpSupported == other.mtpSupported
}

/**
 * Port abstraction over [MtpFailureMemory] so unit tests can inject a
 * deterministic fake without subclassing the final class.
 */
internal interface MtpFailureMemoryPort {
    fun shouldSkipMtp(
        modelPath: String,
        backendName: String,
        mtpEnabled: Boolean,
        validationVersion: Int,
        kvCacheCapacityTokens: Int,
        enableVisionBackend: Boolean,
        fileSize: Long,
        modifiedAt: Long,
        mtpSupported: Boolean
    ): String?

    fun shouldSkipBackend(
        modelPath: String,
        backendName: String,
        validationVersion: Int,
        kvCacheCapacityTokens: Int,
        enableVisionBackend: Boolean,
        fileSize: Long,
        modifiedAt: Long,
        mtpSupported: Boolean
    ): String?

    fun recordFailure(
        modelPath: String,
        backendName: String,
        mtpEnabled: Boolean,
        validationVersion: Int,
        kvCacheCapacityTokens: Int,
        enableVisionBackend: Boolean,
        fileSize: Long,
        modifiedAt: Long,
        mtpSupported: Boolean,
        fallbackReason: String
    )
}

/**
 * Tracks MTP initialization failures to prevent repeated failed rebuilds.
 * Keyed by model fingerprint, actual backend, package capability
 * version, and engine settings. Entries are persisted through [storage].
 */
internal class MtpFailureMemory(
    private val storage: MtpFailureMemoryStorage = NoopMtpFailureMemoryStorage,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : MtpFailureMemoryPort {
    private data class FailureKey(
        val modelFingerprint: FailureModelFingerprint,
        val backendName: String,
        val mtpEnabled: Boolean,
        val kvCacheCapacityTokens: Int,
        val enableVisionBackend: Boolean
    )

    private data class FailureRecord(
        val failedAt: Long,
        val fallbackReason: String
    )

    private val failures = mutableMapOf<FailureKey, FailureRecord>()
    private val lock = Any()
    /** Last durability operation result: null=not attempted, true=success, false=failed */
    @Volatile private var lastDurabilityResult: Boolean? = null
    /** Last durability exception if any */
    @Volatile private var lastDurabilityException: Throwable? = null

    companion object {
        const val COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_ENTRIES = 32
        private const val KEY_SEPARATOR = "\u001f"

        private fun serializeKey(key: FailureKey): String = listOf(
            key.modelFingerprint.canonicalPath,
            key.modelFingerprint.fileSize.toString(),
            key.modelFingerprint.modifiedAt.toString(),
            key.modelFingerprint.validationVersion.toString(),
            key.modelFingerprint.mtpSupported.toString(),
            key.backendName,
            key.mtpEnabled.toString(),
            key.kvCacheCapacityTokens.toString(),
            key.enableVisionBackend.toString()
        ).joinToString(KEY_SEPARATOR)

        private fun deserializeKey(serialized: String): FailureKey? {
            val parts = serialized.split(KEY_SEPARATOR)
            if (parts.size != 9) return null
            return FailureKey(
                modelFingerprint = FailureModelFingerprint(
                    canonicalPath = parts[0],
                    fileSize = parts[1].toLongOrNull() ?: return null,
                    modifiedAt = parts[2].toLongOrNull() ?: return null,
                    validationVersion = parts[3].toIntOrNull() ?: return null,
                    mtpSupported = parts[4].toBooleanStrictOrNull() ?: return null
                ),
                backendName = parts[5],
                mtpEnabled = parts[6].toBooleanStrictOrNull() ?: return null,
                kvCacheCapacityTokens = parts[7].toIntOrNull() ?: return null,
                enableVisionBackend = parts[8].toBooleanStrictOrNull() ?: return null
            )
        }
    }

    init {
        // Phase D: storage.load() may throw (e.g., disk full, I/O error,
        // corrupt SharedPreferences). Catch and record the durability
        // failure so Engine construction does not crash. In-memory state
        // starts empty in that case — cooldown cannot restore from disk
        // but new failures persist in memory within the current session.
        try {
            storage.load().forEach { (serializedKey, serializedValue) ->
                val key = deserializeKey(serializedKey) ?: return@forEach
                val parts = serializedValue.split("|", limit = 2)
                val failedAt = parts.getOrNull(0)?.toLongOrNull() ?: return@forEach
                failures[key] = FailureRecord(failedAt = failedAt, fallbackReason = parts.getOrNull(1).orEmpty())
            }
        } catch (t: Throwable) {
            if (t.isFatalError()) throw t
            lastDurabilityResult = false
            lastDurabilityException = t
            Log.w("FusionLiteRT", "MtpFailureMemory storage.load() threw — continuing with empty in-memory state", t)
        }
    }

    /**
     * Checks if an MTP attempt should be skipped due to recent failure.
     * Returns the fallback reason if MTP should be skipped, null otherwise.
     */
    override fun shouldSkipMtp(
        modelPath: String,
        backendName: String,
        mtpEnabled: Boolean,
        validationVersion: Int,
        kvCacheCapacityTokens: Int,
        enableVisionBackend: Boolean,
        fileSize: Long,
        modifiedAt: Long,
        mtpSupported: Boolean
    ): String? = synchronized(lock) {
        val key = FailureKey(
            modelFingerprint = FailureModelFingerprint(
                canonicalPath = modelPath,
                fileSize = fileSize,
                modifiedAt = modifiedAt,
                validationVersion = validationVersion,
                mtpSupported = mtpSupported
            ),
            backendName = backendName,
            mtpEnabled = mtpEnabled,
            kvCacheCapacityTokens = kvCacheCapacityTokens,
            enableVisionBackend = enableVisionBackend
        )
        val record = failures[key]
        if (record != null) {
            val elapsed = clock() - record.failedAt
            if (elapsed < COOLDOWN_MS) {
                return record.fallbackReason
            } else {
                // Cooldown expired, allow retry
                failures.remove(key)
                persist()
            }
        }
        return null
    }

    /**
     * Checks if a plain backend (non-MTP) attempt should be skipped due to recent failure.
     * Returns the fallback reason if the backend should be skipped, null otherwise.
     */
    override fun shouldSkipBackend(
        modelPath: String,
        backendName: String,
        validationVersion: Int,
        kvCacheCapacityTokens: Int,
        enableVisionBackend: Boolean,
        fileSize: Long,
        modifiedAt: Long,
        mtpSupported: Boolean
    ): String? = synchronized(lock) {
        val key = FailureKey(
            modelFingerprint = FailureModelFingerprint(
                canonicalPath = modelPath,
                fileSize = fileSize,
                modifiedAt = modifiedAt,
                validationVersion = validationVersion,
                mtpSupported = mtpSupported
            ),
            backendName = backendName,
            mtpEnabled = false,
            kvCacheCapacityTokens = kvCacheCapacityTokens,
            enableVisionBackend = enableVisionBackend
        )
        val record = failures[key]
        if (record != null) {
            val elapsed = clock() - record.failedAt
            if (elapsed < COOLDOWN_MS) {
                return record.fallbackReason
            } else {
                failures.remove(key)
                persist()
            }
        }
        return null
    }

    /**
     * Records an MTP failure for the given key.
     */
    override fun recordFailure(
        modelPath: String,
        backendName: String,
        mtpEnabled: Boolean,
        validationVersion: Int,
        kvCacheCapacityTokens: Int,
        enableVisionBackend: Boolean,
        fileSize: Long,
        modifiedAt: Long,
        mtpSupported: Boolean,
        fallbackReason: String
    ) = synchronized(lock) {
        val fingerprint = FailureModelFingerprint(
            canonicalPath = modelPath,
            fileSize = fileSize,
            modifiedAt = modifiedAt,
            validationVersion = validationVersion,
            mtpSupported = mtpSupported
        )
        val key = FailureKey(
            modelFingerprint = fingerprint,
            backendName = backendName,
            mtpEnabled = mtpEnabled,
            kvCacheCapacityTokens = kvCacheCapacityTokens,
            enableVisionBackend = enableVisionBackend
        )
        // Invalidate stale entries for the same model path when the
        // fingerprint changed (e.g. model replaced at same path).
        failures.keys
            .filter { it.modelFingerprint.canonicalPath == fingerprint.canonicalPath && !it.modelFingerprint.matches(fingerprint) }
            .forEach { failures.remove(it) }
        failures[key] = FailureRecord(
            failedAt = clock(),
            fallbackReason = fallbackReason
        )
        persist()
        // Evict oldest if over limit
        if (failures.size > MAX_ENTRIES) {
            val oldestKey = failures.minByOrNull { it.value.failedAt }?.key
            oldestKey?.let { failures.remove(it) }
            persist()
        }
    }

    /**
     * Clears failure memory for a specific model (e.g., when model file changes).
     */
    fun clearForModel(modelPath: String) = synchronized(lock) {
        failures.keys.filter { it.modelFingerprint.canonicalPath == modelPath }.forEach { failures.remove(it) }
        persist()
    }

    /**
     * Clears all failure memory (e.g., on explicit manual retry).
     */
    fun clearAll() = synchronized(lock) {
        failures.clear()
        // Phase D: storage.clear() may throw OR return false. Both are
        // durability-failure outcomes; in-memory state is already cleared.
        try {
            val clearResult = storage.clear()
            lastDurabilityResult = clearResult
            if (!clearResult) {
                lastDurabilityException = IllegalStateException("Failed to clear failure memory storage")
            } else {
                lastDurabilityException = null
            }
        } catch (t: Throwable) {
            if (t.isFatalError()) throw t
            lastDurabilityResult = false
            lastDurabilityException = t
            Log.w("FusionLiteRT", "MtpFailureMemory storage.clear() threw — in-memory state still cleared", t)
        }
    }

    /**
     * Returns the result of the last durability operation.
     * null = no operation attempted yet
     * true = last save/clear succeeded
     * false = last save/clear failed
     */
    fun lastDurabilityResult(): Boolean? {
        return lastDurabilityResult
    }

    /**
     * Returns the exception from the last durability failure, if any.
     */
    fun lastDurabilityException(): Throwable? {
        return lastDurabilityException
    }

    /**
     * Returns true if the in-memory cooldown state is usable but the last
     * durability persistence failed, meaning the cooldown will not survive
     * process restart.
     */
    fun isDurabilityCompromised(): Boolean {
        return lastDurabilityResult == false
    }

    internal fun persistedEntryCount(): Int = synchronized(lock) {
        // Phase D: best-effort; a storage exception returns 0 so callers
        // can still verify in-memory operation in tests.
        try {
            storage.load().size
        } catch (t: Throwable) {
            if (t.isFatalError()) throw t
            0
        }
    }

    private fun persist() {
        // Phase D: storage.save() may throw (disk full, I/O error). Catch
        // and record the durability failure; in-memory state is still
        // authoritative for the current session so cooldown still works
        // for the lifetime of this Engine instance, but the app must
        // not claim it will survive restart.
        try {
            val result = storage.save(
                failures.entries.associate { (key, record) ->
                    serializeKey(key) to "${record.failedAt}|${record.fallbackReason}"
                }
            )
            lastDurabilityResult = result
            if (!result) {
                lastDurabilityException = IllegalStateException("Failed to save failure memory to storage")
            } else {
                lastDurabilityException = null
            }
        } catch (t: Throwable) {
            if (t.isFatalError()) throw t
            lastDurabilityResult = false
            lastDurabilityException = t
            Log.w("FusionLiteRT", "MtpFailureMemory storage.save() threw — in-memory state still usable", t)
        }
    }
}

/**
 * Phase 1: deterministic combination of stable Engine acquisition events
 * and request-local cooldown/skip events.
 */
/**
 * LiteRT-LM reads this process-global flag while creating a Conversation.
 * Keep it set only for that synchronous call, then restore the prior value
 * before any token generation or the next Conversation can run.
 */
@OptIn(ExperimentalApi::class)
internal inline fun <T> withPromptTemplateOverride(
    overrideTemplate: String?,
    block: () -> T,
): T = synchronized(promptTemplateOverrideLock) {
    val previous = ExperimentalFlags.overwritePromptTemplate
    try {
        ExperimentalFlags.overwritePromptTemplate = overrideTemplate
        block()
    } finally {
        ExperimentalFlags.overwritePromptTemplate = previous
    }
}

/** LiteRT exposes this setting process-wide; conversation creation must own it exclusively. */
private val promptTemplateOverrideLock = Any()

internal enum class StructuredLiteRtRole { USER, MODEL }

internal data class StructuredLiteRtMessage(
    val role: StructuredLiteRtRole,
    val content: String,
)

internal data class KananaStructuredPrompt(
    val systemInstruction: String,
    val initialMessages: List<StructuredLiteRtMessage>,
    val currentUserContent: String,
)

internal fun userStopOutcome(
    promptAdapter: FusionPromptAdapter,
    rawOutput: String,
): GenerationOutcome = promptAdapter.sanitizeOutput(rawOutput).let { partial ->
    GenerationOutcome.Cancelled(
        partialText = partial,
        terminationIntent = GenerationTerminationIntent.USER_STOP,
    )
}

private fun earlyTerminationOutcome(
    requestContext: GenerationRequestContext?,
): GenerationOutcome.Cancelled? {
    val intent = requestContext?.cancellationController?.intentFor(requestContext.requestKey)
        ?: return null
    val partialText = if (intent == GenerationTerminationIntent.USER_STOP) {
        requestContext.cancellationController.partialSnapshot(requestContext.requestKey).orEmpty()
    } else {
        ""
    }
    Log.i("FusionLiteRT", "Generation termination settled before native Conversation creation: ${intent.name}")
    return GenerationOutcome.Cancelled(partialText = partialText, terminationIntent = intent)
}

/**
 * Maps Fusion's semantic messages to the LiteRT-LM Conversation contract used
 * by the pinned Kanana template. The last user message is the one sent by
 * sendMessageAsync; it is deliberately excluded from initialMessages.
 */
internal fun buildKananaStructuredPrompt(
    messages: List<ChatMessage>,
    systemInstruction: String,
): KananaStructuredPrompt {
    val semanticMessages = messages.filter { it.role != "system" }
    val currentUserIndex = semanticMessages.indexOfLast { it.role == "user" }
    require(currentUserIndex >= 0) { "Kanana generation requires a current user message" }
    require(semanticMessages.drop(currentUserIndex + 1).isEmpty()) {
        "Kanana current user message must be the final semantic message"
    }

    val initialMessages = semanticMessages.take(currentUserIndex).map { message ->
        when (message.role) {
            "user" -> StructuredLiteRtMessage(StructuredLiteRtRole.USER, message.content)
            "assistant" -> StructuredLiteRtMessage(StructuredLiteRtRole.MODEL, message.content)
            else -> error("Unsupported Kanana semantic role: ${message.role}")
        }
    }
    return KananaStructuredPrompt(
        systemInstruction = systemInstruction,
        initialMessages = initialMessages,
        currentUserContent = semanticMessages[currentUserIndex].content,
    )
}

private fun KananaStructuredPrompt.toInitialLiteRtMessages(): List<Message> =
    initialMessages.map { message ->
        when (message.role) {
            StructuredLiteRtRole.USER -> Message.user(message.content)
            StructuredLiteRtRole.MODEL -> Message.model(message.content)
        }
    }

internal fun combineFallbackEvents(
    acquisitionEvents: List<RuntimeFallbackEvent>,
    requestLocalEvents: List<RuntimeFallbackEvent>
): List<RuntimeFallbackEvent> {
    val merged = listOfNotNull(acquisitionEvents, requestLocalEvents).flatten()
    if (merged.isEmpty()) return emptyList()
    val result = mutableListOf<RuntimeFallbackEvent>()
    for (i in merged.indices) {
        val event = merged[i]
        // First two items always pass.
        if (i < 2) {
            result.add(event)
            continue
        }
        // At this point result.size >= 2.
        val prev = result[result.lastIndex]
        if (!isDuplicateFallback(prev, event)) {
            result.add(event)
            continue
        }
        val prevPrev = result[result.lastIndex - 1]
        if (!isDuplicateFallback(prevPrev, event)) {
            result.add(event)
        }
        // Otherwise this is the 3rd+ consecutive duplicate — skip
    }
    return result
}

private fun isDuplicateFallback(a: RuntimeFallbackEvent, b: RuntimeFallbackEvent): Boolean =
    a.reason == b.reason &&
        a.attemptedTextBackend == b.attemptedTextBackend &&
        a.attemptedMtpEnabled == b.attemptedMtpEnabled &&
        a.attemptedVisionBackend == b.attemptedVisionBackend
