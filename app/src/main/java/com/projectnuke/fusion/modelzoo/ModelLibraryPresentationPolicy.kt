package com.projectnuke.fusion.modelzoo

/** Stable device fit intentionally excludes transient available-memory data. */
enum class StableDeviceFit {
    RECOMMENDED,
    MEMORY_HEADROOM_NEEDED,
    NOT_RECOMMENDED,
    REMOTE,
    UNSUPPORTED,
}

enum class ModelLibraryPrimaryAction(val label: String) {
    CURRENT("사용 중"),
    USE("사용하기"),
    DOWNLOAD("받기"),
    PREPARE("준비 방법"),
    REMOTE_INFO("사용 방법"),
    DETAILS("자세히"),
}

data class ModelLibraryPresentation(
    val statusLabel: String,
    val fitLabel: String?,
    val fit: StableDeviceFit,
    val primaryAction: ModelLibraryPrimaryAction,
    val primaryEnabled: Boolean,
    val description: String,
)

fun modelFamilyLabel(family: ModelFamily): String = when (family) {
    ModelFamily.GEMMA -> "Gemma"
    ModelFamily.QWEN -> "Qwen"
    ModelFamily.KANANA -> "Kanana"
    ModelFamily.LLAMA -> "Llama"
    ModelFamily.PHI -> "Phi"
    ModelFamily.KIMI -> "Kimi"
    ModelFamily.DEEPSEEK -> "DeepSeek"
    ModelFamily.MISTRAL -> "Mistral"
    ModelFamily.CUSTOM -> "기타 / 사용자 모델"
}

private fun unavailableImportedStatus(spec: FusionModelSpec): String = when {
    spec.externallyReferenced -> "다시 연결 필요"
    spec.copiedInternally || spec.localPath != null -> "모델 파일 확인 필요"
    else -> "모델 파일 확인 필요"
}

fun belongsInMyModels(spec: FusionModelSpec, currentModelId: String?, isRunnableLocally: Boolean): Boolean =
    spec.id == currentModelId ||
        isRunnableLocally ||
        spec.availability == ModelAvailability.CUSTOM_IMPORTED ||
        spec.externallyReferenced ||
        spec.copiedInternally ||
        spec.localPath != null ||
        spec.uriString != null

fun excludeCurrentFromRecommendations(
    specs: List<FusionModelSpec>,
    currentModelId: String?,
): List<FusionModelSpec> = specs.filter { it.id != currentModelId }

/**
 * Student recommendation eligibility. Broken imported/linked entries stay in
 * My Models ([belongsInMyModels]) but must not be recommended merely because
 * their stable hardware fit is RECOMMENDED or MEMORY_HEADROOM_NEEDED.
 *
 * Built-in NEEDS_DOWNLOAD entries may still be recommended when hardware-fit
 * policy allows; only non-runnable CUSTOM_IMPORTED entries are excluded here.
 * The current model is already excluded via [excludeCurrentFromRecommendations].
 */
fun eligibleForStudentRecommendation(
    spec: FusionModelSpec,
    isRunnableLocally: Boolean,
    isCurrent: Boolean = false,
): Boolean {
    if (isCurrent) return false
    if (spec.availability == ModelAvailability.CUSTOM_IMPORTED && !isRunnableLocally) return false
    return true
}

/**
 * Presentation truth for the student-facing catalog. Ranking is deliberately
 * based on hardware class and catalog metadata, not on cacheable RAM.
 */
fun presentModelForLibrary(
    spec: FusionModelSpec,
    totalRamBytes: Long,
    isLowRamDevice: Boolean,
    isRunnableLocally: Boolean,
    isCurrent: Boolean,
): ModelLibraryPresentation {
    if (isCurrent) {
        if (!isRunnableLocally) {
            return ModelLibraryPresentation(
                statusLabel = "현재 선택됨 · 모델 확인 필요",
                fitLabel = null,
                fit = stableFit(spec, totalRamBytes, isLowRamDevice),
                primaryAction = ModelLibraryPrimaryAction.DETAILS,
                primaryEnabled = true,
                description = spec.notes,
            )
        }
        return ModelLibraryPresentation(
            statusLabel = "사용 중",
            fitLabel = null,
            fit = stableFit(spec, totalRamBytes, isLowRamDevice),
            primaryAction = ModelLibraryPrimaryAction.CURRENT,
            primaryEnabled = false,
            description = spec.notes,
        )
    }

    val fit = stableFit(spec, totalRamBytes, isLowRamDevice)
    val status = when {
        isRunnableLocally -> "바로 사용 가능"
        spec.availability == ModelAvailability.CUSTOM_IMPORTED -> unavailableImportedStatus(spec)
        spec.availability == ModelAvailability.NEEDS_DOWNLOAD -> "모델 파일 필요"
        spec.availability == ModelAvailability.NEEDS_CONVERSION -> "변환 필요"
        spec.availability == ModelAvailability.REMOTE_ONLY -> "원격 모델"
        spec.availability == ModelAvailability.UNSUPPORTED_ON_DEVICE -> "이 기기에서 사용 어려움"
        else -> "모델 파일 필요"
    }
    val action = when {
        isRunnableLocally && fit != StableDeviceFit.NOT_RECOMMENDED -> ModelLibraryPrimaryAction.USE
        spec.availability == ModelAvailability.NEEDS_DOWNLOAD && !spec.directDownloadUrl.isNullOrBlank() -> ModelLibraryPrimaryAction.DOWNLOAD
        spec.availability == ModelAvailability.NEEDS_DOWNLOAD -> ModelLibraryPrimaryAction.PREPARE
        spec.availability == ModelAvailability.NEEDS_CONVERSION -> ModelLibraryPrimaryAction.PREPARE
        spec.availability == ModelAvailability.REMOTE_ONLY -> ModelLibraryPrimaryAction.REMOTE_INFO
        else -> ModelLibraryPrimaryAction.DETAILS
    }
    return ModelLibraryPresentation(
        statusLabel = status,
        fitLabel = when (fit) {
            StableDeviceFit.RECOMMENDED -> "내 기기에 추천"
            StableDeviceFit.MEMORY_HEADROOM_NEEDED -> "메모리 여유 필요"
            StableDeviceFit.NOT_RECOMMENDED -> "이 기기에서는 권장하지 않음"
            else -> null
        },
        fit = fit,
        primaryAction = action,
        primaryEnabled = action != ModelLibraryPrimaryAction.CURRENT,
        description = spec.notes,
    )
}

fun stableFit(
    spec: FusionModelSpec,
    totalRamBytes: Long,
    isLowRamDevice: Boolean,
): StableDeviceFit {
    if (spec.availability == ModelAvailability.REMOTE_ONLY ||
        spec.recommendedDeviceClass == ModelRecommendedDeviceClass.SERVER_ONLY
    ) return StableDeviceFit.REMOTE
    if (spec.availability == ModelAvailability.UNSUPPORTED_ON_DEVICE ||
        spec.memoryClass == ModelMemoryClass.SERVER
    ) return StableDeviceFit.UNSUPPORTED

    val totalRamGb = totalRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val stableClassGb = stableDeviceRamClassGb(totalRamBytes, isLowRamDevice)
    val recommendedRam = spec.recommendedRamGb ?: spec.minRecommendedRamGb ?: 8
    val minRam = spec.minRecommendedRamGb ?: recommendedRam
    return when {
        stableClassGb >= recommendedRam -> StableDeviceFit.RECOMMENDED
        stableClassGb >= minRam -> StableDeviceFit.MEMORY_HEADROOM_NEEDED
        else -> StableDeviceFit.NOT_RECOMMENDED
    }
}

/** Uses physical RAM bands with tolerance for Android-reported capacity. */
fun stableDeviceRamClassGb(totalRamBytes: Long, isLowRamDevice: Boolean): Int {
    if (isLowRamDevice || totalRamBytes <= 0L) return 8
    val totalRamGb = totalRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return when {
        totalRamGb < 10.0 -> 8
        totalRamGb < 14.0 -> 12
        else -> 16
    }
}

/** This is a warning only; it never changes [stableFit] or catalog ordering. */
fun liveMemoryPressureWarning(availableRamBytes: Long, lowMemory: Boolean): String? =
    if (lowMemory || (availableRamBytes > 0L && availableRamBytes < 2L * 1024L * 1024L * 1024L)) {
        "현재 메모리 여유가 적어요. 무거운 모델을 실행하기 전에 다른 앱을 닫으면 더 안정적이에요."
    } else {
        null
    }

fun modelLibraryMetadata(spec: FusionModelSpec): String {
    val locality = if (spec.availability == ModelAvailability.REMOTE_ONLY) "원격" else "로컬"
    val parameter = spec.parameterLabel.trim()
        .takeIf { it.isNotBlank() && !it.equals("custom", ignoreCase = true) }
    val size = spec.modelSizeEstimateGb?.let { "파일 약 ${if (it % 1f == 0f) it.toInt() else it}GB" }
    return listOfNotNull(locality, parameter, size).joinToString(" · ")
}
