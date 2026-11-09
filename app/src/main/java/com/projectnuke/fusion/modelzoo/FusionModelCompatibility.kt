package com.projectnuke.fusion.modelzoo

import android.content.Context
import com.projectnuke.fusion.model.AcceleratorMode
import java.io.File

enum class FusionAcceleratorConfidence(val label: String) {
    VERIFIED("일반 경로"),
    EXPERIMENTAL_UNVERIFIED("Kanana GPU 실험 경로 · CPU/GPU 비교 전"),
}

data class FusionModelCompatibilityReport(
    val formatLabel: String,
    val familyLabel: String,
    val localExecutionStatus: String,
    val memoryWarning: String?,
    val recommendedMaxTokens: Int,
    val recommendedAccelerator: AcceleratorMode,
    val acceleratorConfidence: FusionAcceleratorConfidence,
    val mtpRecommendation: String,
    val npuCandidateStatus: String,
    val summary: String
)

object FusionModelCompatibility {
    fun check(context: Context, spec: FusionModelSpec): FusionModelCompatibilityReport {
        val format = spec.runtimeFormat.takeIf { it != ModelRuntimeFormat.UNKNOWN }
            ?: FusionModelCatalog.runtimeFormatForFile(spec.fileName ?: spec.localPath.orEmpty())
        val snapshot = FusionModelMemoryPreflight.snapshot(context)
        val recommendedTokens = FusionModelMemoryPreflight.recommendedTokens(spec, snapshot.totalMem, snapshot.availMem)
        val risk = FusionModelMemoryPreflight.evaluate(
            spec = spec,
            totalRamBytes = snapshot.totalMem,
            availableRamBytes = snapshot.availMem,
            currentMaxTokens = recommendedTokens,
            lowMemory = snapshot.lowMemory
        )
        val status = executionStatus(spec, format)
        val summary = when (status) {
            "실행 가능" -> "이 모델은 현재 Fusion에서 바로 실행할 수 있습니다."
            "변환 필요" -> "이 모델은 변환 후 사용할 수 있습니다."
            "원격 실행 필요" -> "이 모델은 원격 실행이 필요합니다."
            else -> "실행 전에 모델 파일을 다시 확인해 주세요."
        }
        return FusionModelCompatibilityReport(
            formatLabel = format.name,
            familyLabel = spec.family.name,
            localExecutionStatus = status,
            memoryWarning = risk.takeIf { it.level != FusionModelMemoryRiskLevel.RECOMMENDED }?.summary,
            recommendedMaxTokens = risk.recommendedMaxTokens,
            recommendedAccelerator = when {
                spec.family == ModelFamily.KANANA -> AcceleratorMode.CPU
                spec.canRunLocalRuntime -> AcceleratorMode.GPU
                else -> AcceleratorMode.AUTO
            },
            acceleratorConfidence = if (spec.family == ModelFamily.KANANA) {
                FusionAcceleratorConfidence.EXPERIMENTAL_UNVERIFIED
            } else {
                FusionAcceleratorConfidence.VERIFIED
            },
            mtpRecommendation = if (spec.recommendedMtpEnabled && spec.family == ModelFamily.GEMMA) "권장" else "끄기 권장",
            npuCandidateStatus = if (spec.supportsNpuCandidate) "NPU 후보" else "지원 확인 필요",
            summary = summary
        )
    }

    fun canUseCurrentLiteRtPath(spec: FusionModelSpec): Boolean {
        if (spec.externallyReferenced && spec.localPath.isNullOrBlank()) return false
        val path = spec.localPath
        return spec.canRunLocalRuntime && (path == null || File(path).exists())
    }

    private fun executionStatus(spec: FusionModelSpec, format: ModelRuntimeFormat): String {
        if (spec.availability == ModelAvailability.REMOTE_ONLY) return "원격 실행 필요"
        if (format == ModelRuntimeFormat.NEEDS_CONVERSION || spec.availability == ModelAvailability.NEEDS_CONVERSION) return "변환 필요"
        if (spec.externallyReferenced && spec.localPath.isNullOrBlank()) return "가져오기 필요"
        if (format == ModelRuntimeFormat.LITERT_LM) return "실행 가능"
        if (format == ModelRuntimeFormat.MEDIAPIPE_LLM) return "현재 실행 엔진 미지원"
        return "지원 확인 필요"
    }
}
