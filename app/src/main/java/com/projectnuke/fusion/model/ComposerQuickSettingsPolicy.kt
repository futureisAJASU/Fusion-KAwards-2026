package com.projectnuke.fusion.model

import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.modelzoo.FusionModelSpec
import com.projectnuke.fusion.modelzoo.FusionModelCatalog

/**
 * Pure contract for the Composer Quick Settings surface.
 *
 * The UI reads the effective per-model profile, builds one of these patches,
 * and writes it through the existing LocalModelSettingsProfileStore seam. This
 * object owns no durable state and intentionally avoids SharedPreferences so
 * Quick/Simple/Advanced/Runtime keep one profile truth.
 */
object ComposerQuickSettingsPolicy {
    const val STYLE_HELPER_TEXT: String =
        "답변 스타일은 표현의 다양성에 영향을 줍니다. 사실성이나 지능을 보장하지 않습니다."

    const val GPU_HELPER_TEXT: String = "지원되는 모델에서 더 빠를 수 있어요."
    const val CPU_HELPER_TEXT: String =
        "GPU에서 출력이 이상하거나 실행 문제가 있을 때 사용해 보세요. 속도는 더 느릴 수 있어요."
    const val KANANA_CPU_RECOMMENDATION_TEXT: String =
        "이 모델은 CPU 실행을 권장해요. GPU에서 문제가 발생하면 CPU를 사용해 보세요."

    val answerStyles: List<ComposerQuickAnswerStyle> = ComposerQuickAnswerStyle.entries
    val outputLengths: List<ComposerQuickOutputLength> = ComposerQuickOutputLength.entries
    val acceleratorChoices: List<ComposerQuickAcceleratorChoice> = ComposerQuickAcceleratorChoice.entries

    fun samplingPreset(
        style: ComposerQuickAnswerStyle,
        modelFamilyKey: String?,
    ): ComposerQuickSamplingPreset {
        val deepSeek = modelFamilyKey.isDeepSeekFamilyKey()
        return when (style) {
            ComposerQuickAnswerStyle.CAREFUL -> ComposerQuickSamplingPreset(
                temperature = 0.35f,
                topP = 0.85f,
                topK = 32,
            )
            ComposerQuickAnswerStyle.BALANCED -> if (deepSeek) {
                ComposerQuickSamplingPreset(
                    temperature = 0.80f,
                    topP = 0.95f,
                    topK = 64,
                )
            } else {
                ComposerQuickSamplingPreset(
                    temperature = 1.00f,
                    topP = 0.95f,
                    topK = 64,
                )
            }
            ComposerQuickAnswerStyle.CREATIVE -> if (deepSeek) {
                ComposerQuickSamplingPreset(
                    temperature = 1.00f,
                    topP = 0.98f,
                    topK = 96,
                )
            } else {
                ComposerQuickSamplingPreset(
                    temperature = 1.20f,
                    topP = 0.98f,
                    topK = 96,
                )
            }
        }
    }

    fun applyAnswerStyle(
        current: LocalSettingsEditValues,
        style: ComposerQuickAnswerStyle,
        modelFamilyKey: String?,
    ): LocalSettingsEditValues {
        val preset = samplingPreset(style, modelFamilyKey)
        return current.copy(
            settings = current.settings.copy(
                temperature = preset.temperature,
                topP = preset.topP,
                topK = preset.topK,
            ),
        )
    }

    fun outputLengthOptions(maxSupportedOutputTokens: Int?): List<ComposerQuickOutputLengthOption> =
        outputLengths.map { length ->
            ComposerQuickOutputLengthOption(
                length = length,
                enabled = maxSupportedOutputTokens == null || length.tokens <= maxSupportedOutputTokens,
            )
        }

    fun applyOutputLength(
        current: LocalSettingsEditValues,
        length: ComposerQuickOutputLength,
        maxSupportedOutputTokens: Int?,
    ): LocalSettingsEditValues? {
        if (maxSupportedOutputTokens != null && length.tokens > maxSupportedOutputTokens) return null
        return current.copy(settings = current.settings.copy(maxTokens = length.tokens))
    }

    fun applyAccelerator(
        current: LocalSettingsEditValues,
        choice: ComposerQuickAcceleratorChoice,
    ): LocalSettingsEditValues = current.copy(
        settings = current.settings.copy(accelerator = choice.mode),
    )

    fun kananaCpuRecommendationText(modelFamilyKey: String?): String? =
        KANANA_CPU_RECOMMENDATION_TEXT.takeIf { modelFamilyKey.isKananaFamilyKey() }

    fun shouldShowQuickSettings(mode: ComposerQuickGenerationMode): Boolean =
        mode == ComposerQuickGenerationMode.LOCAL_MODEL

    /** Pure capability truth shared with catalog, presets, and request capture. */
    fun reasoningCapabilityAvailable(spec: FusionModelSpec?): Boolean =
        FusionModelCatalog.reasoningCapabilityAvailable(spec)

    fun shouldClosePanel(
        previous: ComposerQuickSettingsTarget?,
        current: ComposerQuickSettingsTarget?,
        mode: ComposerQuickGenerationMode,
    ): Boolean {
        if (!shouldShowQuickSettings(mode)) return true
        if (previous == null) return false
        return previous != current
    }

    fun shouldAllowWrite(
        captured: ComposerQuickSettingsTarget,
        current: ComposerQuickSettingsTarget,
        mode: ComposerQuickGenerationMode,
    ): Boolean = shouldShowQuickSettings(mode) && captured == current

    fun composerGenerationStatusText(
        status: ComposerQuickGenerationStatus?,
        reasoningActive: Boolean,
    ): String? {
        if (reasoningActive) return "◌ 더 깊게 생각하는 중..."
        return when (status) {
            null, ComposerQuickGenerationStatus.IDLE -> null
            ComposerQuickGenerationStatus.PREPARING_MODEL -> "◌ 모델 준비 중..."
            ComposerQuickGenerationStatus.LOADING_GPU_MODEL -> "◌ GPU로 모델 불러오는 중..."
            ComposerQuickGenerationStatus.LOADING_CPU_MODEL -> "◌ CPU로 모델 불러오는 중..."
            ComposerQuickGenerationStatus.PROCESSING_PROMPT -> "◌ 질문 처리 중..."
            ComposerQuickGenerationStatus.GENERATING_RESPONSE -> "◌ 답변 생성 중..."
        }
    }
}

data class ComposerQuickSamplingPreset(
    val temperature: Float,
    val topP: Float,
    val topK: Int,
)

enum class ComposerQuickAnswerStyle(val label: String) {
    CAREFUL("공부"),
    BALANCED("균형"),
    CREATIVE("아이디어"),
}

enum class ComposerQuickOutputLength(
    val tokens: Int,
    val label: String,
) {
    SHORT(512, "짧게"),
    MEDIUM(1024, "보통"),
    DETAILED(2048, "자세히"),
    VERY_DETAILED(4096, "매우 자세히"),
}

data class ComposerQuickOutputLengthOption(
    val length: ComposerQuickOutputLength,
    val enabled: Boolean,
)

enum class ComposerQuickAcceleratorChoice(
    val mode: AcceleratorMode,
    val label: String,
    val helperText: String,
) {
    GPU(
        mode = AcceleratorMode.GPU,
        label = "GPU",
        helperText = ComposerQuickSettingsPolicy.GPU_HELPER_TEXT,
    ),
    CPU(
        mode = AcceleratorMode.CPU,
        label = "CPU",
        helperText = ComposerQuickSettingsPolicy.CPU_HELPER_TEXT,
    ),
}

enum class ComposerQuickGenerationMode {
    LOCAL_MODEL,
    EXTERNAL_AI_API,
}

data class ComposerQuickSettingsTarget(
    val stableModelId: String,
    val selectedPath: String?,
    val displayIdentity: String,
)

enum class ComposerQuickGenerationStatus {
    IDLE,
    PREPARING_MODEL,
    LOADING_GPU_MODEL,
    LOADING_CPU_MODEL,
    PROCESSING_PROMPT,
    GENERATING_RESPONSE,
}

private fun String?.isDeepSeekFamilyKey(): Boolean =
    this?.contains("deepseek", ignoreCase = true) == true

private fun String?.isKananaFamilyKey(): Boolean =
    this?.contains("kanana", ignoreCase = true) == true
