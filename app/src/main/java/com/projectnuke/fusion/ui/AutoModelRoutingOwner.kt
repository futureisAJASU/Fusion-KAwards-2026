package com.projectnuke.fusion.ui

import android.content.Context
import android.os.Build
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.model.*
import com.projectnuke.fusion.modelzoo.*
import com.projectnuke.fusion.util.ManagedModelPathPolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Request owned IO. Does not write selections, initialize engines, or call a provider. */
internal class AutoModelRoutingOwner(context: Context) {
    private val app = context.applicationContext
    suspend fun resolve(request: GenerationRequestSnapshot): GenerationRequestSnapshot? = withContext(Dispatchers.IO) {
        if (!request.autoRoutingEnabled || request.autoSelectionExplanation != null || request.generationModeKey != ChatGenerationMode.LOCAL_MODEL.name) return@withContext request
        val memory = FusionModelMemoryPreflight.snapshot(app)
        val signals = RuntimeCpuDeviceSignals.observe(app)
        val now = System.currentTimeMillis()
        val appVersion = app.packageManager.getPackageInfo(app.packageName, 0).versionName
        val benchmarks = AppDatabase.getInstance(app).benchmarkDao().observeRecent(64).first()
        val specs = FusionModelCatalog.all(app).take(128)
        val pairs = specs.mapNotNull { spec ->
            currentCoroutineContext().ensureActive()
            val path = spec.localPath ?: spec.fileName?.let { File(ManagedModelPathPolicy.getModelDirectory(app), it).path }
            val file = ManagedModelPathPolicy.resolveRunnableModel(app, path)?.takeIf { it.length() > 0 } ?: return@mapNotNull null
            if (!spec.canRunLocalRuntime || spec.isRemoteOnly) return@mapNotNull null
            val risk = FusionModelMemoryPreflight.evaluate(spec.copy(fileSizeBytes = file.length()), memory.totalMem,
                memory.availMem, request.settings.maxTokens, memory.lowMemory,
                request.settings.kvCacheCapacityTokens, request.settings.accelerator)
            val measured = benchmarks.firstOrNull { it.success && it.modelPath == file.path &&
                it.deviceModel == Build.MODEL && it.appVersion == appVersion &&
                it.createdAt >= file.lastModified() && it.accelerator == AcceleratorMode.AUTO.name &&
                it.maxTokens == request.settings.maxTokens && it.reasoningEnabled == request.reasoningEnabled }
            spec to AutoModelCandidate(spec.id, file.path, spec.displayName, file.length(), true,
                risk.level.rank < FusionModelMemoryRiskLevel.NOT_RECOMMENDED.rank &&
                    (risk.estimatedRequiredBytes ?: Long.MAX_VALUE) <= memory.availMem,
                spec.supportsVision, measured?.nativeDecodeTokensPerSecond, measured?.createdAt ?: 0,
                reasoning = spec.supportsReasoning)
        }
        val decision = AutoModelRoutingPolicy.choose(pairs.map { it.second }, request.multimodalImagePaths.isNotEmpty(),
            memory.lowMemory, signals, request.autoPerformancePreference, now,
            reasoningRequired = request.reasoningEnabled) ?: return@withContext null
        currentCoroutineContext().ensureActive()
        val spec = pairs.first { it.first.id == decision.candidate.id }.first
        // Hybrid precedence: request-semantic sampling stays with the request;
        // model-dependent runtime is re-resolved for final model B.
        val settingsPrefs = app.getSharedPreferences(
            RuntimePerformancePreferenceStore.PREFERENCES_NAME, Context.MODE_PRIVATE,
        )
        val profileDocument = LocalModelSettingsProfileStore(settingsPrefs).read()
        val finalModelProfile = LocalModelSettingsResolver.resolve(
            modelId = spec.id,
            modelRecommendation = recommendedLocalModelRecommendation(spec, memory.lowMemory),
            perModelOverride = profileDocument.modelOverrides[spec.id],
            globalDefaults = profileDocument.globalDefaults,
            deviceIsLowRam = memory.lowMemory,
        )
        val routed = AutoRoutedSettingsPolicy.resolve(
            requestSettings = request.settings,
            requestReasoningEnabled = request.reasoningEnabled,
            finalModelProfile = finalModelProfile,
            decisionAccelerator = decision.accelerator,
            finalReasoningCapable = spec.supportsReasoning,
        )
        request.copy(selectedModelId = spec.displayName, selectedModelPath = decision.candidate.path,
            selectedModelStableId = spec.id, modelFamily = spec.family,
            reasoningCapabilityAvailable = spec.supportsReasoning,
            reasoningEnabled = routed.reasoningEnabled,
            settings = routed.settings,
            contextMode = routed.contextMode,
            compactPolicy = routed.compactPolicy,
            autoSelectionExplanation = "자동 선택 · ${spec.displayName}\n${decision.reason}")
    }
}
