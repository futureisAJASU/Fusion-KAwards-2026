package com.projectnuke.fusion.model

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.FusionModelSpec
import com.projectnuke.fusion.modelzoo.ModelAvailability
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.modelzoo.ModelMemoryClass
import com.projectnuke.fusion.modelzoo.ModelRecommendedDeviceClass
import com.projectnuke.fusion.modelzoo.ModelRuntimeFormat
import com.projectnuke.fusion.util.SharedPreferencesDurability
import com.projectnuke.fusion.util.SharedPreferencesScopedLock
import com.projectnuke.fusion.util.FusionMemoryManager
import com.projectnuke.fusion.util.exactIntOrNull
import com.projectnuke.fusion.util.restoreKeys
import com.projectnuke.fusion.util.snapshotKeys
import java.io.File
import org.json.JSONObject

enum class LocalSettingsScope { MODEL, GLOBAL }

fun shouldPersistGlobalLocalSettings(scope: LocalSettingsScope): Boolean =
    scope == LocalSettingsScope.GLOBAL

enum class LocalContextMode { AUTO, MANUAL }

data class LocalCompactPolicy(
    val enabled: Boolean = true,
    val triggerPercent: Int = 80,
    val recentHistoryPercent: Int = 25,
) {
    fun normalized(): LocalCompactPolicy = copy(
        triggerPercent = triggerPercent.coerceIn(60, 95),
        recentHistoryPercent = recentHistoryPercent.coerceIn(15, 50),
    )
}

/**
 * A persisted profile is written as a complete model-specific snapshot by the
 * settings dialog for this release. Nullable fields remain for old/sparse
 * documents and safe forward-compatible reads.
 */
data class LocalModelSettingsOverride(
    val maxOutputTokens: Int? = null,
    val temperature: Float? = null,
    val topK: Int? = null,
    val topP: Float? = null,
    val reasoningBudgetTokens: Int? = null,
    val reasoningEnabled: Boolean? = null,
    val accelerator: AcceleratorMode? = null,
    val speculativeDecodingEnabled: Boolean? = null,
    val contextMode: LocalContextMode? = null,
    val manualKvCapacityTokens: Int? = null,
    val compactPolicy: LocalCompactPolicy? = null,
)

private fun LocalModelSettingsOverride.hasValidSamplerFloats(): Boolean =
    (temperature == null || LocalGenerationFloatSettings.isValidTemperature(temperature)) &&
        (topP == null || LocalGenerationFloatSettings.isValidTopP(topP))

data class LocalModelSettingsDocument(
    val globalDefaults: LocalModelSettingsOverride? = null,
    val modelOverrides: Map<String, LocalModelSettingsOverride> = emptyMap(),
)

/**
 * Resolves the persistent local-model settings key. A path is only a lookup
 * hint for imported metadata; it is never returned as the profile identity.
 */
fun resolveStableLocalModelId(
    selectedModel: String,
    selectedModelPath: String?,
    catalog: List<FusionModelSpec>,
): String {
    catalog.firstOrNull { it.id == selectedModel }?.let { return it.id }

    val selectedPathName = selectedModelPath
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it).name }
    catalog.firstOrNull { spec ->
        spec.availability == ModelAvailability.CUSTOM_IMPORTED &&
            selectedModelPath != null &&
            spec.localPath == selectedModelPath
    }?.let { return it.id }

    catalog.filter { it.availability != ModelAvailability.CUSTOM_IMPORTED }
        .firstOrNull { spec ->
            spec.displayName == selectedModel ||
                (selectedPathName != null && spec.fileName == selectedPathName)
        }?.let { return it.id }

    return selectedModel
}

data class CapturedLocalModelIdentity(
    val stableId: String?,
    val family: ModelFamily?,
    val reasoningCapabilityAvailable: Boolean = false,
)

fun resolveCapturedLocalModelIdentity(
    context: Context,
    selectedModel: String?,
    selectedModelPath: String?,
): CapturedLocalModelIdentity = resolveCapturedLocalModelIdentity(
    selectedModel = selectedModel,
    selectedModelPath = selectedModelPath,
    catalog = FusionModelCatalog.all(context),
)

/** Resolves both immutable local-request identity values at capture time. */
fun resolveCapturedLocalModelIdentity(
    selectedModel: String?,
    selectedModelPath: String?,
    catalog: List<FusionModelSpec>,
): CapturedLocalModelIdentity {
    if (selectedModel.isNullOrBlank()) return CapturedLocalModelIdentity(null, null)
    catalog.firstOrNull { it.id == selectedModel }?.let {
        return CapturedLocalModelIdentity(
            stableId = it.id,
            family = it.family,
            reasoningCapabilityAvailable = FusionModelCatalog.reasoningCapabilityAvailable(it),
        )
    }
    catalog.firstOrNull {
        it.availability == ModelAvailability.CUSTOM_IMPORTED &&
            selectedModelPath != null && it.localPath == selectedModelPath
    }?.let {
        return CapturedLocalModelIdentity(
            stableId = it.id,
            family = it.family,
            reasoningCapabilityAvailable = FusionModelCatalog.reasoningCapabilityAvailable(it),
        )
    }
    val displayMatches = catalog.filter { it.displayName == selectedModel }
    displayMatches.singleOrNull()?.let {
        return CapturedLocalModelIdentity(
            stableId = it.id,
            family = it.family,
            reasoningCapabilityAvailable = FusionModelCatalog.reasoningCapabilityAvailable(it),
        )
    }

    if (selectedModelPath != null) {
        val modelFile = File(selectedModelPath)
        if (modelFile.isFile && modelFile.exists()) {
            val family = FusionModelCatalog.resolveImportedFamily(modelFile.name, modelFile.length(), ModelFamily.CUSTOM)
            return CapturedLocalModelIdentity(selectedModel, family)
        }
    }

    return CapturedLocalModelIdentity(selectedModel, null)
}

/**
 * Explicit file-system evidence for the defensive catalog-miss fallback.
 *
 * Pure seam input: the Android wrapper observes a real [File] once, while
 * unit tests supply synthetic evidence. Both paths execute the identical
 * resolution algorithm in [resolveRuntimeAdmissionModelSpecPure].
 */
data class RuntimeAdmissionFileEvidence(
    val fileName: String,
    val sizeBytes: Long,
    val exists: Boolean,
    val isFile: Boolean,
)

/** Canonical transient-spec builder shared by wrapper and pure seam. */
internal fun buildTransientRuntimeAdmissionSpec(
    selectedModelStableId: String?,
    selectedModelId: String?,
    modelPath: String,
    fileName: String,
    fileSize: Long,
    format: ModelRuntimeFormat,
    family: ModelFamily,
): FusionModelSpec = FusionModelSpec(
    id = selectedModelStableId ?: "transient-${fileName}",
    displayName = selectedModelId ?: fileName,
    family = family,
    parameterLabel = "Custom",
    runtimeFormat = format,
    availability = ModelAvailability.CUSTOM_IMPORTED,
    memoryClass = ModelMemoryClass.MEDIUM,
    recommendedDeviceClass = ModelRecommendedDeviceClass.RAM_8GB_SAFE,
    recommendedMaxTokens8Gb = 2048,
    recommendedMaxTokens12Gb = 4096,
    recommendedMtpEnabled = family == ModelFamily.GEMMA,
    recommendedReasoningEnabled = false,
    supportsVision = false,
    // A transient/custom spec has no explicit validated capability metadata.
    supportsReasoning = false,
    supportsNpuCandidate = family != ModelFamily.KIMI,
    notes = "Runtime-synthesized admission spec for catalog-miss local model.",
    fileName = fileName,
    localPath = modelPath,
    minRecommendedRamGb = 8,
    recommendedRamGb = 8,
    localExecutionWarning = "가져온 모델입니다. 실제 호환성은 기기에서 확인해야 합니다.",
    sourceLabel = "사용자 가져오기",
    originalFileName = fileName,
    fileSizeBytes = fileSize.takeIf { it > 0L },
    addedAt = System.currentTimeMillis(),
    lastCheckedAt = System.currentTimeMillis(),
    externallyReferenced = false,
    copiedInternally = true
)

/**
 * Resolves a runtime admission target spec for a local model.
 *
 * Defensive catalog-miss resolver: when catalog metadata is missing but a valid
 * local runnable file exists, synthesizes a transient admission spec from the
 * actual file using canonical inference.
 *
 * Semantics:
 * 1. Match persisted catalog by stable ID
 * 2. Match by local path
 * 3. Match by unambiguous display identity
 * 4. If still missing AND modelPath is a real managed runnable file:
 *      synthesize a transient admission spec from the actual file
 * 5. Otherwise return null explicitly
 *
 * This is ONLY defensive admission truth - it does NOT mutate persistent catalog state.
 */
fun resolveRuntimeAdmissionModelSpecPure(
    catalog: List<FusionModelSpec>,
    selectedModelStableId: String?,
    selectedModelId: String?,
    selectedModelPath: String?,
    modelPath: String?,
    fileEvidence: RuntimeAdmissionFileEvidence?,
): FusionModelSpec? {
    catalog.firstOrNull { it.id == selectedModelStableId }?.let { return it }
    if (selectedModelPath != null) {
        catalog.firstOrNull { it.localPath == selectedModelPath }?.let { return it }
    }
    if (selectedModelId != null) {
        catalog.filter { it.displayName == selectedModelId }
            .singleOrNull()?.let { return it }
    }

    if (modelPath != null && fileEvidence != null && fileEvidence.exists && fileEvidence.isFile) {
        val format = FusionModelCatalog.runtimeFormatForFile(fileEvidence.fileName)
        if (format == ModelRuntimeFormat.LITERT_LM) {
            val family = FusionModelCatalog.resolveImportedFamily(
                fileEvidence.fileName,
                fileEvidence.sizeBytes,
                ModelFamily.CUSTOM
            )
            Log.i(
                "FusionModel",
                "RUNTIME_SPEC_FALLBACK basename=${fileEvidence.fileName} " +
                    "size=${fileEvidence.sizeBytes} format=${format.name} family=${family.name}"
            )
            return buildTransientRuntimeAdmissionSpec(
                selectedModelStableId = selectedModelStableId,
                selectedModelId = selectedModelId,
                modelPath = modelPath,
                fileName = fileEvidence.fileName,
                fileSize = fileEvidence.sizeBytes,
                format = format,
                family = family,
            )
        }
    }

    return null
}

fun resolveRuntimeAdmissionModelSpec(
    context: Context,
    selectedModelStableId: String?,
    selectedModelId: String?,
    selectedModelPath: String?,
    modelPath: String?,
): FusionModelSpec? {
    val catalog = FusionModelCatalog.all(context)
    val fileEvidence = modelPath?.let { path ->
        val modelFile = File(path)
        RuntimeAdmissionFileEvidence(
            fileName = modelFile.name,
            sizeBytes = runCatching { modelFile.length() }.getOrDefault(0L),
            exists = modelFile.exists(),
            isFile = modelFile.isFile,
        )
    }
    return resolveRuntimeAdmissionModelSpecPure(
        catalog = catalog,
        selectedModelStableId = selectedModelStableId,
        selectedModelId = selectedModelId,
        selectedModelPath = selectedModelPath,
        modelPath = modelPath,
        fileEvidence = fileEvidence,
    )
}

fun resolveStableLocalModelId(
    context: Context,
    selectedModel: String,
    selectedModelPath: String?,
): String = resolveStableLocalModelId(
    selectedModel = selectedModel,
    selectedModelPath = selectedModelPath,
    catalog = FusionModelCatalog.all(context),
)

enum class LocalDeviceRamTier { RAM_8GB_CLASS, RAM_12GB_OR_MORE }

fun resolveLocalDeviceRamTier(
    totalRamBytes: Long,
    isLowRamDevice: Boolean = false,
    systemLowMemory: Boolean = false,
): LocalDeviceRamTier = if (
    isLowRamDevice || systemLowMemory || totalRamBytes <= 0L ||
    totalRamBytes < 12L * 1024L * 1024L * 1024L
) {
    LocalDeviceRamTier.RAM_8GB_CLASS
} else {
    LocalDeviceRamTier.RAM_12GB_OR_MORE
}

data class LocalModelRecommendationContext(
    val recommendation: LocalModelRecommendation?,
    val deviceIsLowRam: Boolean,
)

/**
 * Pure recommendation-context seam: identical algorithm for the Android
 * wrapper and unit tests. The wrapper performs catalog/File/memory IO and
 * delegates here; tests supply explicit catalog, RAM, and file evidence.
 */
fun resolveLocalModelRecommendationContextPure(
    catalog: List<FusionModelSpec>,
    selectedModel: String,
    selectedModelPath: String?,
    totalRamBytes: Long,
    isLowRamDevice: Boolean = false,
    systemLowMemory: Boolean = false,
    fileEvidence: RuntimeAdmissionFileEvidence? = null,
): LocalModelRecommendationContext {
    val modelId = resolveStableLocalModelId(selectedModel, selectedModelPath, catalog)
    val spec = catalog.firstOrNull { it.id == modelId }
        ?: resolveRuntimeAdmissionModelSpecPure(
            catalog = catalog,
            selectedModelStableId = modelId,
            selectedModelId = selectedModel,
            selectedModelPath = selectedModelPath,
            modelPath = selectedModelPath,
            fileEvidence = fileEvidence,
        )
    val lowRam = resolveLocalDeviceRamTier(
        totalRamBytes = totalRamBytes,
        isLowRamDevice = isLowRamDevice,
        systemLowMemory = systemLowMemory,
    ) == LocalDeviceRamTier.RAM_8GB_CLASS
    return LocalModelRecommendationContext(
        recommendation = spec?.let { recommendedLocalModelRecommendation(it, lowRam) },
        deviceIsLowRam = lowRam,
    )
}

fun resolveLocalModelRecommendationContext(
    context: Context,
    selectedModel: String,
    selectedModelPath: String?,
): LocalModelRecommendationContext {
    val catalog = FusionModelCatalog.all(context)
    val fileEvidence = selectedModelPath?.let { path ->
        val modelFile = File(path)
        RuntimeAdmissionFileEvidence(
            fileName = modelFile.name,
            sizeBytes = runCatching { modelFile.length() }.getOrDefault(0L),
            exists = modelFile.exists(),
            isFile = modelFile.isFile,
        )
    }
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memorySnapshot = FusionMemoryManager.getMemorySnapshot(context)
    return resolveLocalModelRecommendationContextPure(
        catalog = catalog,
        selectedModel = selectedModel,
        selectedModelPath = selectedModelPath,
        totalRamBytes = memorySnapshot.totalMem,
        isLowRamDevice = activityManager?.isLowRamDevice == true,
        systemLowMemory = memorySnapshot.lowMemory,
        fileEvidence = fileEvidence,
    )
}

/** Pure recommendation seam for the effective local profile resolver. */
internal fun recommendedLocalModelRecommendation(
    spec: FusionModelSpec,
    lowRam: Boolean,
): LocalModelRecommendation {
    val output = if (lowRam && spec.family == ModelFamily.KANANA) {
        1024
    } else if (lowRam) {
        spec.recommendedMaxTokens8Gb
    } else {
        spec.recommendedMaxTokens12Gb
    }
    val kvCapacity = if (lowRam && spec.family == ModelFamily.KANANA) {
        2048
    } else {
        KvCacheCapacityPolicy.defaultForModel(output.coerceAtLeast(1024))
    }
    return LocalModelRecommendation(
        recommendedMaxOutputTokens = output,
        recommendedKvCapacityTokens = kvCapacity,
        reasoningEnabled = spec.recommendedReasoningEnabled,
        mtpEnabled = if (lowRam && spec.family == ModelFamily.KANANA) false else spec.recommendedMtpEnabled,
        recommendedAccelerator = if (lowRam && spec.family == ModelFamily.KANANA) AcceleratorMode.CPU else null,
    )
}

fun resolveEffectiveLocalModelProfile(
    context: Context,
    prefs: SharedPreferences,
    selectedModel: String,
    selectedModelPath: String?,
): EffectiveLocalModelProfile {
    val catalog = FusionModelCatalog.all(context)
    val modelId = resolveStableLocalModelId(selectedModel, selectedModelPath, catalog)
    val recommendationContext = resolveLocalModelRecommendationContext(context, selectedModel, selectedModelPath)
    val store = LocalModelSettingsProfileStore(prefs)
    val document = store.read()
    return LocalModelSettingsResolver.resolve(
        modelId = modelId,
        modelRecommendation = recommendationContext.recommendation,
        perModelOverride = document.modelOverrides[modelId],
        globalDefaults = store.globalDefaultsOrLegacy(),
        deviceIsLowRam = recommendationContext.deviceIsLowRam,
    )
}

data class LocalSettingsProvenance(
    val output: String,
    val context: String,
    val compact: String,
)

data class EffectiveLocalModelProfile(
    val modelId: String,
    val generationSettings: GenerationSettings,
    val reasoningEnabled: Boolean,
    val contextMode: LocalContextMode,
    val effectiveKvCapacityTokens: Int,
    val compactPolicy: LocalCompactPolicy,
    val provenance: LocalSettingsProvenance,
)

data class LocalSettingsEditValues(
    val settings: GenerationSettings,
    val reasoningEnabled: Boolean,
    val contextMode: LocalContextMode,
    val manualKvCapacityTokens: Int,
    val compactPolicy: LocalCompactPolicy,
    val effectiveAutoKvCapacityTokens: Int = 0,
)

fun LocalSettingsEditValues.toCompleteLocalModelSettingsOverride(): LocalModelSettingsOverride =
    LocalModelSettingsOverride(
        maxOutputTokens = settings.maxTokens,
        temperature = settings.temperature,
        topK = settings.topK,
        topP = settings.topP,
        reasoningBudgetTokens = settings.reasoningBudgetTokens,
        reasoningEnabled = reasoningEnabled,
        accelerator = settings.accelerator,
        speculativeDecodingEnabled = settings.speculativeDecodingEnabled,
        contextMode = contextMode,
        manualKvCapacityTokens = manualKvCapacityTokens,
        compactPolicy = compactPolicy,
    )

data class LocalSettingsEditBuffer(
    val model: LocalSettingsEditValues,
    val global: LocalSettingsEditValues,
) {
    fun active(scope: LocalSettingsScope): LocalSettingsEditValues =
        if (scope == LocalSettingsScope.MODEL) model else global

    fun update(scope: LocalSettingsScope, value: LocalSettingsEditValues): LocalSettingsEditBuffer =
        if (scope == LocalSettingsScope.MODEL) copy(model = value) else copy(global = value)
}

data class LocalSettingsEditState(
    val buffer: LocalSettingsEditBuffer,
    val inheritedModel: LocalSettingsEditValues,
    val hasExplicitModelOverride: Boolean,
)

fun shouldDeleteModelOverride(resetRequested: Boolean, modelEditedAfterReset: Boolean): Boolean =
    resetRequested && !modelEditedAfterReset

fun resolveSavedManualKvCapacityTokens(
    modelOverride: LocalModelSettingsOverride?,
    globalDefaults: LocalModelSettingsOverride?,
): Int = (
    modelOverride?.manualKvCapacityTokens
        ?: globalDefaults?.manualKvCapacityTokens
        ?: KvCacheCapacityPolicy.DEFAULT_CAPACITY
    ).coerceIn(KvCacheCapacityPolicy.MIN_CAPACITY, KvCacheCapacityPolicy.MAX_CAPACITY)

/** GLOBAL edits represent user defaults, never the selected model's recommendation. */
fun resolveGlobalLocalSettingsProfile(
    globalDefaults: LocalModelSettingsOverride?,
    deviceIsLowRam: Boolean = false,
): EffectiveLocalModelProfile = LocalModelSettingsResolver.resolve(
    modelId = "global",
    modelRecommendation = null,
    globalDefaults = globalDefaults,
    deviceIsLowRam = deviceIsLowRam,
)

fun resolveLocalSettingsEditState(
    context: Context,
    prefs: SharedPreferences,
    selectedModel: String,
    selectedModelPath: String?,
): LocalSettingsEditState {
    val catalog = FusionModelCatalog.all(context)
    val modelId = resolveStableLocalModelId(selectedModel, selectedModelPath, catalog)
    val modelProfile = resolveEffectiveLocalModelProfile(context, prefs, selectedModel, selectedModelPath)
    val recommendationContext = resolveLocalModelRecommendationContext(context, selectedModel, selectedModelPath)
    val lowRam = recommendationContext.deviceIsLowRam
    val recommendation = recommendationContext.recommendation
    val store = LocalModelSettingsProfileStore(prefs)
    val document = store.read()
    val globalDefaults = store.globalDefaultsOrLegacy()
    val globalProfile = resolveGlobalLocalSettingsProfile(globalDefaults, lowRam)
    val inheritedModelProfile = LocalModelSettingsResolver.resolve(
        modelId = modelId,
        modelRecommendation = recommendation,
        globalDefaults = globalDefaults,
        deviceIsLowRam = lowRam,
    )
    val savedGlobalManualCapacity = resolveSavedManualKvCapacityTokens(
        modelOverride = null,
        globalDefaults = globalDefaults,
    )
    val savedModelManualCapacity = resolveSavedManualKvCapacityTokens(
        modelOverride = document.modelOverrides[modelId],
        globalDefaults = globalDefaults,
    )
    fun EffectiveLocalModelProfile.toEditValues(savedManualCapacity: Int) = LocalSettingsEditValues(
        settings = generationSettings,
        reasoningEnabled = reasoningEnabled,
        contextMode = contextMode,
        manualKvCapacityTokens = savedManualCapacity,
        compactPolicy = compactPolicy,
        effectiveAutoKvCapacityTokens = effectiveKvCapacityTokens,
    )
    return LocalSettingsEditState(
        buffer = LocalSettingsEditBuffer(
            model = modelProfile.toEditValues(savedModelManualCapacity),
            global = globalProfile.toEditValues(savedGlobalManualCapacity),
        ),
        inheritedModel = inheritedModelProfile.toEditValues(savedGlobalManualCapacity),
        hasExplicitModelOverride = document.modelOverrides.containsKey(modelId),
    )
}

fun resolveAutoKvCapacityTokens(
    maxOutputTokens: Int,
    modelRecommendation: LocalModelRecommendation?,
    deviceIsLowRam: Boolean,
): Int {
    val recommendedCapacity = modelRecommendation?.recommendedKvCapacityTokens
        ?.takeIf { it > 0 }
        ?.let { if (deviceIsLowRam) minOf(it, 4096) else it }
        ?: KvCacheCapacityPolicy.DEFAULT_CAPACITY
    val outputRequiredCapacity = KvCacheCapacityPolicy.defaultForModel(maxOutputTokens.coerceAtLeast(1))
    val autoCapacityLimit = if (deviceIsLowRam) 4096 else KvCacheCapacityPolicy.MAX_CAPACITY
    return maxOf(recommendedCapacity, outputRequiredCapacity)
        .coerceIn(KvCacheCapacityPolicy.MIN_CAPACITY, autoCapacityLimit)
}

fun resolveLocalAutoKvCapacityPreview(
    context: Context,
    selectedModel: String,
    selectedModelPath: String?,
    maxOutputTokens: Int,
    scope: LocalSettingsScope,
): Int {
    val recommendationContext = resolveLocalModelRecommendationContext(context, selectedModel, selectedModelPath)
    return resolveAutoKvCapacityTokens(
        maxOutputTokens = maxOutputTokens,
        modelRecommendation = if (scope == LocalSettingsScope.MODEL) {
            recommendationContext.recommendation
        } else {
            null
        },
        deviceIsLowRam = recommendationContext.deviceIsLowRam,
    )
}

/** Pure precedence and validation boundary used by every local request owner. */
object LocalModelSettingsResolver {
    fun resolve(
        modelId: String,
        modelRecommendation: LocalModelRecommendation? = null,
        perModelOverride: LocalModelSettingsOverride? = null,
        globalDefaults: LocalModelSettingsOverride? = null,
        deviceIsLowRam: Boolean = false,
    ): EffectiveLocalModelProfile {
        val recommendation = modelRecommendation ?: LocalModelRecommendation()
        val safe = LocalModelSettingsOverride(
            maxOutputTokens = 1024,
            temperature = 1.0f,
            topK = 64,
            topP = 0.95f,
            reasoningBudgetTokens = 512,
            reasoningEnabled = false,
            accelerator = AcceleratorMode.GPU,
            speculativeDecodingEnabled = false,
            contextMode = LocalContextMode.AUTO,
            compactPolicy = LocalCompactPolicy(),
        )
        fun <T> pick(
            model: T?,
            global: T?,
            recommended: T?,
            fallback: T,
        ): Pair<T, String> = when {
            model != null -> model to "model"
            global != null -> global to "global"
            recommended != null -> recommended to "recommendation"
            else -> fallback to "fallback"
        }

        val (maxOutput, outputSource) = pick(
            perModelOverride?.maxOutputTokens,
            globalDefaults?.maxOutputTokens,
            if (recommendation.recommendedMaxOutputTokens > 0) {
                recommendation.recommendedMaxOutputTokens.takeIf { !deviceIsLowRam || it <= 1024 } ?: 1024
            } else null,
            safe.maxOutputTokens!!,
        )
        val (temperature, _) = pick(perModelOverride?.temperature, globalDefaults?.temperature, null, safe.temperature!!)
        val (topK, _) = pick(perModelOverride?.topK, globalDefaults?.topK, null, safe.topK!!)
        val (topP, _) = pick(perModelOverride?.topP, globalDefaults?.topP, null, safe.topP!!)
        val (reasoningBudget, _) = pick(perModelOverride?.reasoningBudgetTokens, globalDefaults?.reasoningBudgetTokens, null, safe.reasoningBudgetTokens!!)
        val (reasoningEnabled, _) = pick(perModelOverride?.reasoningEnabled, globalDefaults?.reasoningEnabled, recommendation.reasoningEnabled, safe.reasoningEnabled!!)
        val (accelerator, _) = pick(
            perModelOverride?.accelerator,
            globalDefaults?.accelerator,
            recommendation.recommendedAccelerator,
            safe.accelerator!!,
        )
        val (mtp, _) = pick(perModelOverride?.speculativeDecodingEnabled, globalDefaults?.speculativeDecodingEnabled, recommendation.mtpEnabled, safe.speculativeDecodingEnabled!!)
        val (contextMode, contextSource) = pick(
            perModelOverride?.contextMode,
            globalDefaults?.contextMode,
            LocalContextMode.AUTO,
            safe.contextMode!!,
        )
        val autoCapacity = resolveAutoKvCapacityTokens(
            maxOutputTokens = maxOutput,
            modelRecommendation = recommendation,
            deviceIsLowRam = deviceIsLowRam,
        )
        val autoCapacityLimit = if (deviceIsLowRam) 4096 else KvCacheCapacityPolicy.MAX_CAPACITY
        val recommendedCapacity = recommendation.recommendedKvCapacityTokens
            .takeIf { it > 0 }
            ?.let { if (deviceIsLowRam) minOf(it, 4096) else it }
            ?: KvCacheCapacityPolicy.DEFAULT_CAPACITY
        val manualCapacity = perModelOverride?.manualKvCapacityTokens
            ?: globalDefaults?.manualKvCapacityTokens
        val effectiveCapacity = when (contextMode) {
            LocalContextMode.MANUAL -> (manualCapacity ?: recommendedCapacity)
                .coerceIn(KvCacheCapacityPolicy.MIN_CAPACITY, KvCacheCapacityPolicy.MAX_CAPACITY)
            LocalContextMode.AUTO -> autoCapacity.coerceIn(
                KvCacheCapacityPolicy.MIN_CAPACITY,
                autoCapacityLimit,
            )
        }
        val (compactPolicy, compactSource) = pick(
            perModelOverride?.compactPolicy?.normalized(),
            globalDefaults?.compactPolicy?.normalized(),
            null,
            LocalCompactPolicy(),
        )
        return EffectiveLocalModelProfile(
            modelId = modelId,
            generationSettings = GenerationSettings(
                maxTokens = maxOutput.coerceIn(1, 32_000),
                kvCacheCapacityTokens = effectiveCapacity,
                topK = LocalGenerationSettingBounds.clampTopK(topK),
                topP = LocalGenerationFloatSettings.topPOrDefault(topP),
                temperature = LocalGenerationFloatSettings.temperatureOrDefault(temperature),
                accelerator = accelerator,
                reasoningBudgetTokens = LocalGenerationSettingBounds.clampReasoningBudget(reasoningBudget),
                speculativeDecodingEnabled = mtp,
            ),
            reasoningEnabled = reasoningEnabled,
            contextMode = contextMode,
            effectiveKvCapacityTokens = effectiveCapacity,
            compactPolicy = compactPolicy,
            provenance = LocalSettingsProvenance(outputSource, contextSource, compactSource),
        )
    }
}

data class LocalModelRecommendation(
    val recommendedMaxOutputTokens: Int = 0,
    val recommendedKvCapacityTokens: Int = 0,
    val reasoningEnabled: Boolean? = null,
    val mtpEnabled: Boolean? = null,
    val recommendedAccelerator: AcceleratorMode? = null,
)

object LocalModelSettingsProfileCodec {
    const val CURRENT_VERSION = 1

    fun encode(document: LocalModelSettingsDocument): String = JSONObject().apply {
        put("version", CURRENT_VERSION)
        document.globalDefaults?.let { put("global", encodeOverride(it)) }
        put("models", JSONObject().apply {
            document.modelOverrides.forEach { (id, override) -> put(id, encodeOverride(override)) }
        })
    }.toString()

    fun decode(raw: String?): LocalModelSettingsDocument {
        if (raw.isNullOrBlank()) return LocalModelSettingsDocument()
        return try {
            val root = JSONObject(raw)
            if (root.optInt("version", -1) > CURRENT_VERSION) return LocalModelSettingsDocument()
            val global = root.optJSONObject("global")?.let(::decodeOverride)
            val models = buildMap {
                root.optJSONObject("models")?.keys()?.forEach { id ->
                    if (id.isNotBlank()) root.optJSONObject("models")?.optJSONObject(id)?.let { put(id, decodeOverride(it)) }
                }
            }
            LocalModelSettingsDocument(global, models)
        } catch (_: Exception) {
            LocalModelSettingsDocument()
        }
    }

    /**
     * Mutation-only decode. Display reads intentionally keep the forgiving
     * compatibility fallback above, but a writer-impossible present value is
     * corrupt and must not become an empty/default mutation base.
     */
    internal fun decodeStrict(raw: String?): LocalModelSettingsDocument? {
        if (raw.isNullOrBlank()) return LocalModelSettingsDocument()
        return decodeRecoverableProfileOrNull {
            val root = JSONObject(raw)
            val version = root.strictIntOrNull("version")
            if (version != null && version > CURRENT_VERSION) return null

            val global = if (root.has("global") && !root.isNull("global")) {
                root.optJSONObject("global")?.let(::decodeOverrideStrict) ?: return null
            } else null

            val modelsObject = if (root.has("models") && !root.isNull("models")) {
                root.optJSONObject("models") ?: return null
            } else null
            val models = mutableMapOf<String, LocalModelSettingsOverride>()
            if (modelsObject != null) {
                val keys = modelsObject.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    if (id.isBlank() || modelsObject.isNull(id)) return null
                    val value = modelsObject.optJSONObject(id) ?: return null
                    models[id] = decodeOverrideStrict(value)
                }
            }
            LocalModelSettingsDocument(global, models)
        }
    }

    private fun encodeOverride(value: LocalModelSettingsOverride): JSONObject = JSONObject().apply {
        value.maxOutputTokens?.let { put("maxOutputTokens", it) }
        value.temperature?.let { put("temperature", it.toDouble()) }
        value.topK?.let { put("topK", it) }
        value.topP?.let { put("topP", it.toDouble()) }
        value.reasoningBudgetTokens?.let { put("reasoningBudgetTokens", it) }
        value.reasoningEnabled?.let { put("reasoningEnabled", it) }
        value.accelerator?.let { put("accelerator", it.name) }
        value.speculativeDecodingEnabled?.let { put("mtp", it) }
        value.contextMode?.let { put("contextMode", it.name) }
        value.manualKvCapacityTokens?.let { put("manualKvCapacityTokens", it) }
        value.compactPolicy?.let {
            put("compact", JSONObject().apply {
                put("enabled", it.enabled)
                put("triggerPercent", it.triggerPercent)
                put("recentHistoryPercent", it.recentHistoryPercent)
            })
        }
    }

    private fun decodeOverride(value: JSONObject): LocalModelSettingsOverride = LocalModelSettingsOverride(
        maxOutputTokens = value.intOrNull("maxOutputTokens"),
        temperature = value.doubleOrNull("temperature")?.toFloat(),
        topK = value.intOrNull("topK"),
        topP = value.doubleOrNull("topP")?.toFloat(),
        reasoningBudgetTokens = value.intOrNull("reasoningBudgetTokens"),
        reasoningEnabled = value.booleanOrNull("reasoningEnabled"),
        accelerator = value.stringOrNull("accelerator")?.let { runCatching { AcceleratorMode.valueOf(it) }.getOrNull() },
        speculativeDecodingEnabled = value.booleanOrNull("mtp"),
        contextMode = value.stringOrNull("contextMode")?.let { runCatching { LocalContextMode.valueOf(it) }.getOrNull() },
        manualKvCapacityTokens = value.intOrNull("manualKvCapacityTokens"),
        compactPolicy = value.optJSONObject("compact")?.let {
            LocalCompactPolicy(
                enabled = it.optBoolean("enabled", true),
                triggerPercent = it.optInt("triggerPercent", 80),
                recentHistoryPercent = it.optInt("recentHistoryPercent", 25),
            ).normalized()
        },
    )

    private fun decodeOverrideStrict(value: JSONObject): LocalModelSettingsOverride = LocalModelSettingsOverride(
        maxOutputTokens = value.strictIntOrNull("maxOutputTokens"),
        temperature = value.strictBoundedFloatOrNull("temperature", 0f, 2f),
        topK = value.strictIntOrNull("topK"),
        topP = value.strictBoundedFloatOrNull("topP", 0f, 1f),
        reasoningBudgetTokens = value.strictIntOrNull("reasoningBudgetTokens"),
        reasoningEnabled = value.strictBooleanOrNull("reasoningEnabled"),
        accelerator = value.strictStringOrNull("accelerator")?.takeIf { it.isNotBlank() }
            ?.let(AcceleratorMode::valueOf),
        speculativeDecodingEnabled = value.strictBooleanOrNull("mtp"),
        contextMode = value.strictStringOrNull("contextMode")?.takeIf { it.isNotBlank() }
            ?.let(LocalContextMode::valueOf),
        manualKvCapacityTokens = value.strictIntOrNull("manualKvCapacityTokens"),
        compactPolicy = if (value.has("compact") && !value.isNull("compact")) {
            val compact = value.optJSONObject("compact") ?: throw IllegalArgumentException("compact")
            LocalCompactPolicy(
                enabled = compact.strictBooleanOrNull("enabled") ?: true,
                triggerPercent = compact.strictIntOrNull("triggerPercent") ?: 80,
                recentHistoryPercent = compact.strictIntOrNull("recentHistoryPercent") ?: 25,
            ).normalized()
        } else null,
    )

    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.doubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
    private fun JSONObject.booleanOrNull(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null
    private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.strictIntOrNull(key: String): Int? = if (!has(key) || isNull(key)) {
        null
    } else {
        exactIntOrNull(get(key)) ?: throw IllegalArgumentException(key)
    }

    private fun JSONObject.strictBoundedFloatOrNull(key: String, min: Float, max: Float): Float? {
        if (!has(key) || isNull(key)) return null
        val value = (get(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toFloat()
            ?: throw IllegalArgumentException(key)
        if (!value.isFinite() || value !in min..max) throw IllegalArgumentException(key)
        return value
    }

    private fun JSONObject.strictBooleanOrNull(key: String): Boolean? = if (!has(key) || isNull(key)) {
        null
    } else {
        get(key) as? Boolean ?: throw IllegalArgumentException(key)
    }

    private fun JSONObject.strictStringOrNull(key: String): String? = if (!has(key) || isNull(key)) {
        null
    } else {
        get(key) as? String ?: throw IllegalArgumentException(key)
    }
}

internal inline fun <T> decodeRecoverableProfileOrNull(block: () -> T?): T? = try {
    block()
} catch (_: Exception) {
    null
}

internal sealed interface SettingsProfileLoad {
    data object Missing : SettingsProfileLoad
    data class Valid(val document: LocalModelSettingsDocument) : SettingsProfileLoad
    data object Corrupt : SettingsProfileLoad
}

class LocalModelSettingsProfileStore(private val prefs: SharedPreferences) {
    /** Quick controls change only their own field; sparse and complete overrides stay intact. */
    fun writeModelReasoning(modelId: String, enabled: Boolean): Boolean =
        updateModelField(modelId) { it.copy(reasoningEnabled = enabled) }

    fun writeModelMtp(modelId: String, enabled: Boolean): Boolean =
        updateModelField(modelId) { it.copy(speculativeDecodingEnabled = enabled) }

    private fun updateModelField(
        modelId: String,
        transform: (LocalModelSettingsOverride) -> LocalModelSettingsOverride,
    ): Boolean = SharedPreferencesScopedLock.withKeyLock(prefs, KEY) {
        if (modelId.isBlank()) return@withKeyLock false
        val base = mutationBase() ?: return@withKeyLock false
        val current = base.modelOverrides[modelId] ?: LocalModelSettingsOverride()
        writeDocument(base.copy(modelOverrides = base.modelOverrides + (modelId to transform(current))))
    }

    fun read(): LocalModelSettingsDocument = if (SharedPreferencesDurability.isUnknown(prefs, KEY)) {
        LocalModelSettingsDocument()
    } else {
        LocalModelSettingsProfileCodec.decode(prefs.getString(KEY, null))
    }

    /**
     * Typed load: Missing means nothing was ever stored; Corrupt means bytes
     * exist but do not form a valid document. Reads never destroy evidence.
     */
    internal fun load(): SettingsProfileLoad {
        if (SharedPreferencesDurability.isUnknown(prefs, KEY)) return SettingsProfileLoad.Corrupt
        val raw = prefs.getString(KEY, null) ?: return SettingsProfileLoad.Missing
        if (raw.isBlank()) return SettingsProfileLoad.Missing
        return LocalModelSettingsProfileCodec.decodeStrict(raw)
            ?.let(SettingsProfileLoad::Valid)
            ?: SettingsProfileLoad.Corrupt
    }

    /** Compatibility source for upgrades that predate the profile document. */
    fun globalDefaultsOrLegacy(): LocalModelSettingsOverride? {
        read().globalDefaults?.let { return it }
        if (!prefs.contains(LocalGenerationSettingsPolicy.KEY_SETTINGS_MIGRATION_STATE)) {
            LocalGenerationSettingsPolicy.fromPrefs(prefs)
        }
        if (prefs.getString(LocalGenerationSettingsPolicy.KEY_SETTINGS_MIGRATION_STATE, null) != "legacy") {
            return null
        }
        val settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        return LocalModelSettingsOverride(
            maxOutputTokens = settings.maxTokens,
            temperature = settings.temperature,
            topK = settings.topK,
            topP = settings.topP,
            reasoningBudgetTokens = settings.reasoningBudgetTokens,
            reasoningEnabled = prefs.getBoolean("reasoning_enabled", false),
            accelerator = settings.accelerator,
            speculativeDecodingEnabled = settings.speculativeDecodingEnabled ?: false,
            // Existing explicit KV settings remain effective on upgrade.
            contextMode = LocalContextMode.MANUAL,
            manualKvCapacityTokens = settings.kvCacheCapacityTokens,
            compactPolicy = LocalCompactPolicy(),
        )
    }

    /**
     * Every mutation takes one snapshot read and serializes read-modify-write
     * under the process-wide key-scoped [SharedPreferencesScopedLock], so that
     * ANY two stores sharing the same [SharedPreferences] and profile [KEY]
     * serialize their snapshot -> commit -> recovery sequence regardless of
     * store instance. Corrupt state is quarantined into a bounded backup key
     * before replacement instead of being silently replaced as empty. A failed
     * commit restores the process-visible snapshot when possible; if restoration
     * also fails, the profile is marked unknown and all later mutations fail
     * closed.
     */
    fun writeGlobalDefaults(value: LocalModelSettingsOverride): Boolean =
        SharedPreferencesScopedLock.withKeyLock(prefs, KEY) {
            if (!value.hasValidSamplerFloats()) return@withKeyLock false
            val base = mutationBase() ?: return@withKeyLock false
            writeDocument(base.copy(globalDefaults = value))
        }

    fun writeModelOverride(modelId: String, value: LocalModelSettingsOverride): Boolean =
        SharedPreferencesScopedLock.withKeyLock(prefs, KEY) {
            if (modelId.isBlank() || !value.hasValidSamplerFloats()) return@withKeyLock false
            val base = mutationBase() ?: return@withKeyLock false
            writeDocument(base.copy(modelOverrides = base.modelOverrides + (modelId to value)))
        }

    fun deleteModelOverride(modelId: String): Boolean =
        SharedPreferencesScopedLock.withKeyLock(prefs, KEY) {
            val base = mutationBase() ?: return@withKeyLock false
            writeDocument(base.copy(modelOverrides = base.modelOverrides - modelId))
        }

    fun removeModel(modelId: String): Boolean = deleteModelOverride(modelId)

    private fun mutationBase(): LocalModelSettingsDocument? {
        if (SharedPreferencesDurability.isUnknown(prefs, KEY)) return null
        return when (val load = load()) {
            is SettingsProfileLoad.Valid -> load.document
            is SettingsProfileLoad.Missing -> LocalModelSettingsDocument()
            is SettingsProfileLoad.Corrupt -> {
                if (!quarantineCorrupt()) null else LocalModelSettingsDocument()
            }
        }
    }

    private fun quarantineCorrupt(): Boolean {
        val raw = prefs.getString(KEY, null) ?: return true
        val snapshot = prefs.snapshotKeys(setOf(KEY_QUARANTINE))
        val committed = prefs.edit().putString(KEY_QUARANTINE, raw).commit()
        if (!committed && !prefs.restoreKeys(snapshot)) {
            SharedPreferencesDurability.markUnknown(prefs, KEY)
        }
        return committed
    }

    private fun writeDocument(document: LocalModelSettingsDocument): Boolean {
        if (document.globalDefaults?.hasValidSamplerFloats() == false ||
            document.modelOverrides.values.any { !it.hasValidSamplerFloats() }
        ) return false
        val snapshot = prefs.snapshotKeys(setOf(KEY))
        val committed = prefs.edit()
            .putString(KEY, LocalModelSettingsProfileCodec.encode(document))
            .commit()
        if (!committed && !prefs.restoreKeys(snapshot)) {
            SharedPreferencesDurability.markUnknown(prefs, KEY)
        }
        return committed
    }

    companion object {
        const val KEY = "local_model_settings_profiles"
        private const val KEY_QUARANTINE = "local_model_settings_profiles.corrupt.bak"
    }
}
