package com.projectnuke.fusion.modelzoo

import android.app.ActivityManager
import android.content.Context
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.GenerationSettings

data class FusionModelProfile(
    val settings: GenerationSettings,
    val reasoningEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val lowMemoryRecommendation: String?,
    val enabled: Boolean = true
)

object FusionModelProfiles {
    fun recommended(context: Context, spec: FusionModelSpec): FusionModelProfile? {
        if (spec.availability == ModelAvailability.REMOTE_ONLY || spec.family == ModelFamily.KIMI) {
            return FusionModelProfile(
                settings = GenerationSettings(maxTokens = 2048, accelerator = AcceleratorMode.AUTO, speculativeDecodingEnabled = false),
                reasoningEnabled = false,
                webSearchEnabled = false,
                lowMemoryRecommendation = "이 모델은 원격 실행이 필요합니다.",
                enabled = false
            )
        }
        val lowMemory = totalRamGb(context) <= 8.5f
        val maxTokens = when {
            spec.family == ModelFamily.DEEPSEEK -> 1024
            spec.family == ModelFamily.PHI -> if (lowMemory) 1024 else 2048
            lowMemory -> spec.recommendedMaxTokens8Gb.takeIf { it > 0 }?.coerceIn(1024, 2048) ?: 2048
            else -> spec.recommendedMaxTokens12Gb.takeIf { it > 0 } ?: 4096
        }
        return FusionModelProfile(
            settings = GenerationSettings(
                maxTokens = maxTokens,
                topK = 64,
                topP = 0.95f,
                temperature = if (spec.family == ModelFamily.DEEPSEEK) 0.8f else 1.0f,
                accelerator = AcceleratorMode.GPU,
                reasoningBudgetTokens = if (spec.family == ModelFamily.DEEPSEEK) 1024 else 512,
                speculativeDecodingEnabled = false
            ),
            reasoningEnabled = FusionModelCatalog.reasoningCapabilityAvailable(spec) &&
                spec.family == ModelFamily.DEEPSEEK && !lowMemory,
            webSearchEnabled = false,
            lowMemoryRecommendation = when {
                lowMemory -> "저메모리 기기에서는 maxTokens를 낮게 유지하는 것을 권장합니다."
                spec.family == ModelFamily.DEEPSEEK -> "추론 출력이 길어질 수 있어 maxTokens를 낮게 유지하는 것을 권장합니다."
                else -> null
            }
        )
    }

    private fun totalRamGb(context: Context): Float {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        return info.totalMem / (1024f * 1024f * 1024f)
    }
}
