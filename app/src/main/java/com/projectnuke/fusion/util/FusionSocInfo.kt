package com.projectnuke.fusion.util

import android.os.Build

enum class FusionSocVendor {
    EXYNOS,
    SNAPDRAGON,
    MEDIATEK,
    TENSOR,
    UNKNOWN
}

data class FusionSocInfo(
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val sdkInt: Int,
    val socManufacturer: String,
    val socModel: String,
    val hardware: String,
    val board: String,
    val supportedAbis: List<String>,
    val detectedSocVendor: FusionSocVendor
) {
    val vendorLabel: String
        get() = when (detectedSocVendor) {
            FusionSocVendor.EXYNOS -> "Exynos"
            FusionSocVendor.SNAPDRAGON -> "Snapdragon"
            FusionSocVendor.MEDIATEK -> "MediaTek"
            FusionSocVendor.TENSOR -> "Google Tensor"
            FusionSocVendor.UNKNOWN -> "알 수 없음"
        }

    val compactSocLabel: String
        get() = socModel.takeIf { it.isMeaningfulBuildValue() } ?: "$vendorLabel 계열"
}

fun collectFusionSocInfo(): FusionSocInfo {
    val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MANUFACTURER.orEmpty()
    } else {
        ""
    }
    val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.orEmpty()
    } else {
        ""
    }
    val hardware = Build.HARDWARE.orEmpty()
    val board = Build.BOARD.orEmpty()
    val manufacturer = Build.MANUFACTURER.orEmpty()
    val model = Build.MODEL.orEmpty()
    val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
    val detectedVendor = detectFusionSocVendor(
        listOf(socManufacturer, socModel, hardware, board, manufacturer, model) + abis
    )
    return FusionSocInfo(
        deviceManufacturer = manufacturer,
        deviceModel = model,
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
        socManufacturer = socManufacturer,
        socModel = socModel,
        hardware = hardware,
        board = board,
        supportedAbis = abis,
        detectedSocVendor = detectedVendor
    )
}

fun fusionNpuNoteTitle(vendor: FusionSocVendor): String = when (vendor) {
    FusionSocVendor.EXYNOS -> "Exynos NPU 참고"
    FusionSocVendor.SNAPDRAGON -> "Snapdragon NPU 참고"
    FusionSocVendor.MEDIATEK -> "MediaTek NPU 참고"
    FusionSocVendor.TENSOR -> "Tensor TPU 참고"
    FusionSocVendor.UNKNOWN -> "NPU/GPU/CPU 참고"
}

fun fusionNpuNoteText(vendor: FusionSocVendor): String = when (vendor) {
    FusionSocVendor.EXYNOS ->
        "이 기기는 Exynos 계열로 감지되었습니다. Exynos AI Studio 변환 후 NPU 실행 후보로 검토할 수 있습니다. 현재 앱에서 NPU 실행을 보장하지는 않습니다."
    FusionSocVendor.SNAPDRAGON ->
        "이 기기는 Snapdragon 계열로 감지되었습니다. Qualcomm AI Hub 또는 Qualcomm Neural Processing SDK를 통한 NPU/DSP 실행 후보로 검토할 수 있습니다. 현재 앱에서 Snapdragon NPU 실행을 보장하지는 않습니다."
    FusionSocVendor.MEDIATEK ->
        "이 기기는 MediaTek 계열로 감지되었습니다. APU/NPU 실행 가능성은 사용 가능한 런타임과 변환 도구에 따라 달라집니다. 현재 앱에서 NPU 실행을 보장하지는 않습니다."
    FusionSocVendor.TENSOR ->
        "이 기기는 Google Tensor 계열로 감지되었습니다. TPU/NPU 실행 가능성은 사용 가능한 런타임과 변환 도구에 따라 달라집니다. 현재 앱에서 전용 가속 실행을 보장하지는 않습니다."
    FusionSocVendor.UNKNOWN ->
        "기기 AP 정보를 확인할 수 없습니다. NPU 실행 가능성은 기기와 런타임 지원 여부에 따라 달라집니다. 현재 앱에서 NPU 실행을 보장하지는 않습니다."
}

fun fusionNpuCandidateLabel(vendor: FusionSocVendor, supportsNpuCandidate: Boolean): String {
    if (!supportsNpuCandidate) return "현재 모델은 전용 NPU 실행 후보로 확인되지 않았습니다."
    return when (vendor) {
        FusionSocVendor.EXYNOS -> "Exynos AI Studio 변환 후보"
        FusionSocVendor.SNAPDRAGON -> "Qualcomm AI Hub/QNN 변환 후보"
        FusionSocVendor.MEDIATEK -> "APU/NPU 변환 후보"
        FusionSocVendor.TENSOR -> "Tensor 가속 후보"
        FusionSocVendor.UNKNOWN -> "NPU 변환 후보"
    }
}

private fun detectFusionSocVendor(values: List<String>): FusionSocVendor {
    val normalized = values
        .filter { it.isMeaningfulBuildValue() }
        .joinToString(" ")
        .lowercase()

    return when {
        normalized.containsAny("qualcomm", "snapdragon", "qcom", "kona", "lahaina", "taro", "kalama", "pineapple") ->
            FusionSocVendor.SNAPDRAGON
        normalized.containsAny("samsung", "exynos", "s5e") ->
            FusionSocVendor.EXYNOS
        normalized.containsAny("mediatek", "dimensity", "mt") ->
            FusionSocVendor.MEDIATEK
        normalized.containsAny("google", "tensor", "gs") ->
            FusionSocVendor.TENSOR
        else -> FusionSocVendor.UNKNOWN
    }
}

private fun String?.isMeaningfulBuildValue(): Boolean {
    val value = this?.trim().orEmpty()
    return value.isNotBlank() && !value.equals("unknown", ignoreCase = true)
}

private fun String.containsAny(vararg needles: String): Boolean {
    return needles.any { contains(it) }
}
