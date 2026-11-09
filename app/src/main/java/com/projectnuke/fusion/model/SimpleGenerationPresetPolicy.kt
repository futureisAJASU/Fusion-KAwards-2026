package com.projectnuke.fusion.model

import com.projectnuke.fusion.modelzoo.FusionModelSpec
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.ModelAvailability
import com.projectnuke.fusion.modelzoo.ModelFamily

enum class SimpleResponseStyle(val label: String, val description: String) {
    CAREFUL("신중하게", "표현을 더 일관되게 유지해요. 공부·요약·정리할 때 좋아요."),
    BALANCED("균형 있게", "일관성과 다양한 표현을 균형 있게 사용해요."),
    CREATIVE("창의적으로", "아이디어와 표현을 더 다양하게 시도해요. 글쓰기·브레인스토밍에 좋아요."),
    CUSTOM("사용자 설정", "고급 설정에서 직접 조절한 값을 사용하고 있어요."),
}

enum class SimpleAnswerLength(val label: String, val tokens: Int) {
    SHORT("짧게", 512),
    NORMAL("보통", 1024),
    DETAILED("자세히", 2048),
    VERY_DETAILED("매우 자세히", 4096),
    CUSTOM("사용자 설정", 0),
}

data class SimpleStyleValues(val temperature: Float, val topP: Float, val topK: Int)

data class SimpleLengthAvailability(val enabled: Boolean, val helper: String? = null)

fun simpleStyleValues(style: SimpleResponseStyle, family: ModelFamily?): SimpleStyleValues? {
    val balancedTemperature = if (family == ModelFamily.DEEPSEEK) 0.80f else 1.00f
    return when (style) {
        SimpleResponseStyle.CAREFUL -> SimpleStyleValues(0.35f, 0.85f, 32)
        SimpleResponseStyle.BALANCED -> SimpleStyleValues(balancedTemperature, 0.95f, 64)
        SimpleResponseStyle.CREATIVE -> SimpleStyleValues((balancedTemperature + 0.20f).coerceAtMost(1.20f), 0.98f, 96)
        SimpleResponseStyle.CUSTOM -> null
    }
}

fun applySimpleStyle(
    values: LocalSettingsEditValues,
    style: SimpleResponseStyle,
    family: ModelFamily?,
): LocalSettingsEditValues {
    val preset = requireNotNull(simpleStyleValues(style, family))
    return values.copy(settings = values.settings.copy(
        temperature = preset.temperature,
        topP = preset.topP,
        topK = preset.topK,
    ))
}

fun applySimpleLength(values: LocalSettingsEditValues, length: SimpleAnswerLength): LocalSettingsEditValues =
    values.copy(settings = values.settings.copy(maxTokens = length.tokens))

/**
 * Beginner-facing accelerator control. Writes the SAME
 * [LocalSettingsEditValues.settings.accelerator] truth that Advanced Settings
 * edits, through the same `onWrite` / profile-store path — there is no second
 * preference and no silent divergence.
 */
fun applySimpleAccelerator(values: LocalSettingsEditValues, accelerator: AcceleratorMode): LocalSettingsEditValues =
    values.copy(settings = values.settings.copy(accelerator = accelerator))

fun detectSimpleStyle(values: LocalSettingsEditValues, family: ModelFamily?): SimpleResponseStyle =
    SimpleResponseStyle.entries.firstOrNull { style ->
        simpleStyleValues(style, family)?.let { expected ->
            values.settings.topK == expected.topK &&
                kotlin.math.abs(values.settings.topP - expected.topP) <= 0.01f &&
                kotlin.math.abs(values.settings.temperature - expected.temperature) <= 0.01f
        } == true
    } ?: SimpleResponseStyle.CUSTOM

fun detectSimpleLength(values: LocalSettingsEditValues): SimpleAnswerLength? =
    SimpleAnswerLength.entries.firstOrNull { it != SimpleAnswerLength.CUSTOM && it.tokens == values.settings.maxTokens }

fun simpleLengthLabel(values: LocalSettingsEditValues): String =
    detectSimpleLength(values)?.label ?: "사용자 설정 · ${values.settings.maxTokens} 토큰"

fun stableRecommendedOutputCap(
    spec: FusionModelSpec?,
    totalRamBytes: Long,
    isLowRamDevice: Boolean,
): Int {
    if (spec == null || spec.availability == ModelAvailability.CUSTOM_IMPORTED) return 1024
    val eightGbClass = isLowRamDevice || totalRamBytes < 12L * 1024L * 1024L * 1024L
    return (if (eightGbClass) spec.recommendedMaxTokens8Gb else spec.recommendedMaxTokens12Gb)
        .takeIf { it > 0 } ?: 1024
}

fun recommendedSimpleLengthForCap(cap: Int): SimpleAnswerLength =
    SimpleAnswerLength.entries
        .filter { it != SimpleAnswerLength.CUSTOM && it.tokens <= cap }
        .maxByOrNull { it.tokens }
        ?: SimpleAnswerLength.SHORT

fun simpleLengthAvailability(
    length: SimpleAnswerLength,
    stableCap: Int,
    values: LocalSettingsEditValues,
): SimpleLengthAvailability = when {
    length.tokens > stableCap -> SimpleLengthAvailability(false, "이 모델과 기기에서는 이 길이를 권장하지 않아요.")
    values.contextMode == LocalContextMode.MANUAL &&
        KvCacheCapacityPolicy.defaultForModel(length.tokens) > values.manualKvCapacityTokens ->
        SimpleLengthAvailability(false, "현재 고급 설정의 컨텍스트 용량으로는 이 길이를 사용할 수 없어요.")
    else -> SimpleLengthAvailability(true)
}

fun supportsSimpleReasoning(spec: FusionModelSpec?): Boolean =
    FusionModelCatalog.reasoningCapabilityAvailable(spec)

fun applyRecommendedSimpleReasoning(
    values: LocalSettingsEditValues,
    supportsReasoning: Boolean,
    recommendedReasoningEnabled: Boolean,
): LocalSettingsEditValues = if (supportsReasoning && recommendedReasoningEnabled) {
    requireNotNull(applyReasoningEffort(values, ReasoningEffortPreset.LOW, supportsReasoning = supportsReasoning))
} else {
    setReasoningEnabled(values, false)
}

/**
 * Typed compatibility signal for the CPU helper: Kanana-family models carry a
 * CPU recommendation from both the model-compatibility policy (experimental,
 * unverified GPU path) and the 8 GB safe recommendation. Family-typed only;
 * no filename checks.
 */
fun shouldRecommendCpuForCompatibility(spec: FusionModelSpec?): Boolean =
    spec != null && spec.family == ModelFamily.KANANA

fun simpleResponseSummary(values: LocalSettingsEditValues, spec: FusionModelSpec?): String = buildString {
    append(detectSimpleStyle(values, spec?.family).label)
    append(" · ")
    append(simpleLengthLabel(values))
    if (supportsSimpleReasoning(spec)) {
        append(" · ")
        append(reasoningEffortDisplayLabel(values))
    }
}

fun writeSimpleModelOverride(
    store: LocalModelSettingsProfileStore,
    modelId: String,
    current: LocalSettingsEditValues,
    transform: (LocalSettingsEditValues) -> LocalSettingsEditValues,
): Boolean = store.writeModelOverride(modelId, transform(current).toCompleteLocalModelSettingsOverride())

fun writeSimpleModelOverrideFromLatest(
    store: LocalModelSettingsProfileStore,
    modelId: String,
    readCurrent: () -> LocalSettingsEditValues,
    transform: (LocalSettingsEditValues) -> LocalSettingsEditValues,
): Boolean = writeSimpleModelOverride(store, modelId, readCurrent(), transform)
