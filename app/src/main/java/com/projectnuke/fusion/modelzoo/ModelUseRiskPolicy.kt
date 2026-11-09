package com.projectnuke.fusion.modelzoo

enum class ModelUseRisk { DIRECT, CAUTION, BLOCKED }

data class ModelUseDecision(
    val spec: FusionModelSpec,
    val risk: ModelUseRisk,
    val recommendationTier: String,
    val recommendedMaxTokens: Int,
    val deviceRamClass: String,
    val message: String,
)

/**
 * Resolves the maxTokens value used for a selection preflight. The lookup is
 * keyed by the TARGET model's stable identity so a target with its own saved
 * profile is evaluated with its own effective value, never the currently
 * selected model's value. The single settings truth remains
 * LocalModelSettingsProfileStore; this helper only routes which identity is
 * queried.
 */
fun resolveTargetPreflightMaxTokens(
    targetSpec: FusionModelSpec,
    loadEffectiveMaxTokens: (modelId: String, localPath: String?) -> Int,
): Int = loadEffectiveMaxTokens(targetSpec.id, targetSpec.localPath)

/**
 * One selection gate shared by the model library and the legacy detail path.
 * Stable fit decides whether selection is appropriate; live memory only adds
 * a confirmation requirement and never upgrades an unsafe model.
 */
fun evaluateModelUseDecision(
    spec: FusionModelSpec,
    currentModelId: String?,
    isRunnableLocally: Boolean,
    totalRamBytes: Long,
    availableRamBytes: Long,
    lowMemory: Boolean,
    isLowRamDevice: Boolean,
    currentMaxTokens: Int,
): ModelUseDecision {
    if (spec.id == currentModelId) {
        return ModelUseDecision(spec, ModelUseRisk.BLOCKED, "사용 중", 0, "현재 모델", "이미 사용 중인 모델입니다.")
    }
    if (!isRunnableLocally) {
        val message = when {
            spec.availability == ModelAvailability.CUSTOM_IMPORTED && spec.externallyReferenced -> "파일 권한이 없거나 원본 파일이 이동되었습니다. 다시 연결해 주세요."
            spec.availability == ModelAvailability.CUSTOM_IMPORTED -> "모델 파일을 확인한 뒤 다시 시도해 주세요."
            spec.availability == ModelAvailability.NEEDS_CONVERSION -> "이 모델은 변환 후 사용할 수 있습니다."
            spec.availability == ModelAvailability.NEEDS_DOWNLOAD -> "모델 파일을 먼저 준비해 주세요."
            spec.availability == ModelAvailability.REMOTE_ONLY -> "이 모델은 원격 실행용입니다."
            else -> "현재 기기에서 바로 실행할 수 없는 모델입니다."
        }
        return ModelUseDecision(spec, ModelUseRisk.BLOCKED, "사용 불가", 0, "확인 필요", message)
    }

    val fit = stableFit(spec, totalRamBytes, isLowRamDevice)
    if (fit == StableDeviceFit.NOT_RECOMMENDED || fit == StableDeviceFit.UNSUPPORTED || fit == StableDeviceFit.REMOTE) {
        return ModelUseDecision(spec, ModelUseRisk.BLOCKED, stableFitLabel(fit), 0, ramClassLabel(totalRamBytes), "이 모델은 현재 기기에서 사용하는 것을 권장하지 않습니다.")
    }

    val risk = FusionModelMemoryPreflight.evaluate(
        spec = spec,
        totalRamBytes = totalRamBytes,
        availableRamBytes = availableRamBytes,
        currentMaxTokens = currentMaxTokens,
        lowMemory = lowMemory,
    )
    val dynamicBlocked = risk.level == FusionModelMemoryRiskLevel.UNAVAILABLE
    val needsConfirmation = fit == StableDeviceFit.MEMORY_HEADROOM_NEEDED ||
        lowMemory ||
        availableRamBytes in 1 until 2L * 1024L * 1024L * 1024L ||
        risk.level in setOf(
            FusionModelMemoryRiskLevel.CHECK_REQUIRED,
        )
    return ModelUseDecision(
        spec = spec,
        risk = when {
            dynamicBlocked -> ModelUseRisk.BLOCKED
            needsConfirmation -> ModelUseRisk.CAUTION
            else -> ModelUseRisk.DIRECT
        },
        recommendationTier = risk.label,
        recommendedMaxTokens = risk.recommendedMaxTokens,
        deviceRamClass = risk.ramClass.label,
        message = risk.summary,
    )
}

private fun ramClassLabel(totalRamBytes: Long): String = when {
    totalRamBytes < 10L * 1024L * 1024L * 1024L -> "8GB급"
    totalRamBytes < 14L * 1024L * 1024L * 1024L -> "12GB급"
    else -> "16GB급"
}

private fun stableFitLabel(fit: StableDeviceFit): String = when (fit) {
    StableDeviceFit.RECOMMENDED -> "권장"
    StableDeviceFit.MEMORY_HEADROOM_NEEDED -> "메모리 여유 필요"
    StableDeviceFit.NOT_RECOMMENDED -> "권장하지 않음"
    StableDeviceFit.REMOTE -> "원격 모델"
    StableDeviceFit.UNSUPPORTED -> "사용 어려움"
}
