package com.projectnuke.fusion.modelzoo

import android.content.Context
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.util.FusionMemoryManager
import kotlin.math.max

enum class FusionDeviceRamClass(val label: String) {
    VERY_LOW("6GB 미만"),
    LOW("6GB급"),
    MID("8GB급"),
    HIGH("12GB급"),
    ULTRA("16GB 이상"),
    UNKNOWN("확인 불가")
}

enum class FusionModelMemoryRiskLevel(val label: String, val rank: Int) {
    RECOMMENDED("권장", 0),
    CHECK_REQUIRED("확인 필요", 1),
    CAUTION("주의 필요", 2),
    HEAVY("무거움", 3),
    NOT_RECOMMENDED("권장하지 않음", 4),
    UNAVAILABLE("실행 불가", 5)
}

data class FusionModelMemoryRisk(
    val level: FusionModelMemoryRiskLevel,
    val ramClass: FusionDeviceRamClass,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val modelFileSizeBytes: Long?,
    val estimatedRequiredBytes: Long?,
    val recommendedRamBytes: Long?,
    val recommendedMaxTokens: Int,
    val reasons: List<String>,
    val actions: List<String>
) {
    val label: String get() = level.label
    val summary: String get() = reasons.firstOrNull() ?: "현재 기기 상태를 기준으로 실행 위험도를 확인했습니다."
}

enum class FusionModelRuntimeAdmissionAction {
    APPLY_SAFE_RECOMMENDATION,
    USE_CPU,
    KEEP_CURRENT_SETTINGS,
    CANCEL,
}

data class FusionModelRuntimeAdmission(
    val risk: FusionModelMemoryRisk,
    val requiresDecision: Boolean,
    val actions: List<FusionModelRuntimeAdmissionAction>,
    val safeMaxOutputTokens: Int,
    val safeKvCapacityTokens: Int,
)

object FusionModelMemoryPreflight {
    private const val Gib = 1024L * 1024L * 1024L

    fun classifyDeviceRam(totalRamBytes: Long): FusionDeviceRamClass = when {
        totalRamBytes <= 0L -> FusionDeviceRamClass.UNKNOWN
        totalRamBytes < 6L * Gib -> FusionDeviceRamClass.VERY_LOW
        totalRamBytes < 8L * Gib -> FusionDeviceRamClass.LOW
        totalRamBytes < 12L * Gib -> FusionDeviceRamClass.MID
        totalRamBytes < 16L * Gib -> FusionDeviceRamClass.HIGH
        else -> FusionDeviceRamClass.ULTRA
    }

    fun snapshot(context: Context) = FusionMemoryManager.getMemorySnapshot(context)

    fun recommendedTokens(spec: FusionModelSpec, totalRamBytes: Long, availableRamBytes: Long): Int {
        if (spec.availability == ModelAvailability.REMOTE_ONLY || spec.memoryClass == ModelMemoryClass.SERVER) return 0
        val fileSizeBytes = modelFileSizeBytes(spec) ?: 0L
        val pressure = if (totalRamBytes > 0L) fileSizeBytes.toDouble() / totalRamBytes else 1.0
        val base = when {
            pressure >= 0.55 -> 1024
            pressure >= 0.40 -> 1536
            pressure >= 0.30 -> 2048
            totalRamBytes < 8L * Gib -> 1536
            totalRamBytes < 12L * Gib -> 2048
            totalRamBytes < 16L * Gib -> 4096
            else -> 6144
        }
        return if (availableRamBytes in 1 until 2560L * 1024L * 1024L) {
            (base / 2).coerceAtLeast(1024)
        } else {
            base
        }
    }

    fun evaluate(
        spec: FusionModelSpec,
        totalRamBytes: Long,
        availableRamBytes: Long,
        currentMaxTokens: Int,
        lowMemory: Boolean = false,
        currentKvCapacityTokens: Int? = null,
        accelerator: AcceleratorMode? = null,
    ): FusionModelMemoryRisk {
        val ramClass = classifyDeviceRam(totalRamBytes)
        val fileSizeBytes = modelFileSizeBytes(spec)
        val recommendedRamBytes = spec.recommendedRamGb?.takeIf { it > 0 }?.toLong()?.times(Gib)
        val recommendedMaxTokens = recommendedTokens(spec, totalRamBytes, availableRamBytes)
        val tokenBufferTokens = max(currentMaxTokens, currentKvCapacityTokens ?: 1024)
        val tokenBufferBytes = tokenBufferTokens.toLong() * 512L * 1024L
        val estimatedRequiredBytes = recommendedRamBytes ?: fileSizeBytes?.let { (it * 1.6).toLong() + tokenBufferBytes }
        val reasons = mutableListOf<String>()
        val actions = mutableListOf<String>()

        fun result(level: FusionModelMemoryRiskLevel) = FusionModelMemoryRisk(
            level = level,
            ramClass = ramClass,
            totalRamBytes = totalRamBytes,
            availableRamBytes = availableRamBytes,
            modelFileSizeBytes = fileSizeBytes,
            estimatedRequiredBytes = estimatedRequiredBytes,
            recommendedRamBytes = recommendedRamBytes,
            recommendedMaxTokens = recommendedMaxTokens,
            reasons = reasons.distinct(),
            actions = actions.distinct()
        )

        when (spec.availability) {
            ModelAvailability.REMOTE_ONLY -> {
                reasons += "이 모델은 로컬 실행 대상이 아닙니다."
                actions += "원격 또는 PC 환경에서 실행해 주세요."
                return result(FusionModelMemoryRiskLevel.UNAVAILABLE)
            }
            ModelAvailability.UNSUPPORTED_ON_DEVICE -> {
                reasons += "현재 런타임에서 지원되지 않는 모델입니다."
                actions += "지원되는 형식의 모델을 선택해 주세요."
                return result(FusionModelMemoryRiskLevel.UNAVAILABLE)
            }
            else -> Unit
        }
        if (totalRamBytes <= 0L || availableRamBytes <= 0L) {
            reasons += "기기 메모리 정보를 확인할 수 없습니다."
            actions += "모델 실행 전 기기 상태를 직접 확인해 주세요."
            return result(FusionModelMemoryRiskLevel.CHECK_REQUIRED)
        }

        var level = FusionModelMemoryRiskLevel.RECOMMENDED
        fun raise(candidate: FusionModelMemoryRiskLevel) {
            if (candidate.rank > level.rank) level = candidate
        }

        if (spec.availability == ModelAvailability.NEEDS_DOWNLOAD) {
            reasons += "모델 파일이 아직 준비되지 않았습니다."
            actions += "모델 파일을 먼저 준비해 주세요."
            raise(FusionModelMemoryRiskLevel.CHECK_REQUIRED)
        }
        if (spec.availability == ModelAvailability.NEEDS_CONVERSION ||
            spec.runtimeFormat == ModelRuntimeFormat.NEEDS_CONVERSION
        ) {
            reasons += "현재 런타임에서 바로 실행하려면 변환이 필요할 수 있습니다."
            actions += "지원 형식으로 변환한 뒤 호환성을 확인해 주세요."
            raise(FusionModelMemoryRiskLevel.CHECK_REQUIRED)
        }
        if (lowMemory || availableRamBytes < 1536L * 1024L * 1024L) {
            reasons += "현재 사용 가능한 메모리가 낮습니다."
            actions += "다른 앱을 정리한 뒤 다시 시도해 주세요."
            raise(FusionModelMemoryRiskLevel.HEAVY)
        }
        estimatedRequiredBytes?.let { required ->
            when {
                required > availableRamBytes * 0.85 -> raise(FusionModelMemoryRiskLevel.NOT_RECOMMENDED)
                required > availableRamBytes * 0.65 -> raise(FusionModelMemoryRiskLevel.HEAVY)
                required > availableRamBytes * 0.45 -> raise(FusionModelMemoryRiskLevel.CAUTION)
            }
            if (required > availableRamBytes) {
                reasons += "현재 사용 가능한 메모리가 모델 권장량보다 낮습니다."
                actions += "더 작은 모델을 우선 사용하는 것을 권장합니다."
            }
        }
        fileSizeBytes?.let { size ->
            when {
                size >= totalRamBytes * 0.55 -> raise(FusionModelMemoryRiskLevel.NOT_RECOMMENDED)
                size >= totalRamBytes * 0.40 -> raise(FusionModelMemoryRiskLevel.HEAVY)
                size >= totalRamBytes * 0.30 -> raise(FusionModelMemoryRiskLevel.CAUTION)
            }
        }
        if (recommendedMaxTokens > 0 && currentMaxTokens > recommendedMaxTokens * 1.5) {
            reasons += "현재 최대 토큰 수가 기기 권장값보다 높습니다."
            actions += "최대 토큰 수를 낮추는 것이 좋습니다."
            raise(FusionModelMemoryRiskLevel.CAUTION)
        }
        if (reasons.isEmpty()) reasons += "현재 기기 메모리 기준으로 실행 가능한 범위입니다."
        if (level == FusionModelMemoryRiskLevel.HEAVY) reasons += "총 메모리와 가용 메모리를 기준으로 무거운 모델로 분류됩니다."
        if (level == FusionModelMemoryRiskLevel.CAUTION) reasons += "현재 기기 메모리 기준으로 주의가 필요합니다."
        return result(level)
    }

    private fun modelFileSizeBytes(spec: FusionModelSpec): Long? =
        spec.fileSizeBytes ?: spec.modelSizeEstimateGb?.let { (it * Gib).toLong() }
}

private const val FusionMemoryPolicyGib = 1024L * 1024L * 1024L

/**
 * Uses the existing memory preflight result to decide whether a fresh engine
 * acquisition needs an explicit user choice. It does not clamp settings or
 * mutate persisted profiles; the selected action is applied by the caller.
 */
fun evaluateFusionModelRuntimeAdmission(
    spec: FusionModelSpec,
    totalRamBytes: Long,
    availableRamBytes: Long,
    lowMemory: Boolean,
    maxOutputTokens: Int,
    kvCapacityTokens: Int,
    accelerator: AcceleratorMode,
    runtimeReusable: Boolean,
): FusionModelRuntimeAdmission {
    val risk = FusionModelMemoryPreflight.evaluate(
        spec = spec,
        totalRamBytes = totalRamBytes,
        availableRamBytes = availableRamBytes,
        currentMaxTokens = maxOutputTokens,
        lowMemory = lowMemory,
        currentKvCapacityTokens = kvCapacityTokens,
        accelerator = accelerator,
    )
    val safeDevice = totalRamBytes in 1..(12L * FusionMemoryPolicyGib)
    val safeMaxOutputTokens = if (safeDevice && spec.family == ModelFamily.KANANA) {
        1024
    } else {
        risk.recommendedMaxTokens.coerceIn(512, 1024.coerceAtLeast(risk.recommendedMaxTokens))
    }
    val safeKvCapacityTokens = if (safeDevice && spec.family == ModelFamily.KANANA) {
        2048
    } else {
        maxOf(1024, minOf(kvCapacityTokens, safeMaxOutputTokens * 2))
    }
    val dangerous = !runtimeReusable && (
        lowMemory ||
            risk.level.rank >= FusionModelMemoryRiskLevel.CAUTION.rank ||
            availableRamBytes in 1 until 2L * FusionMemoryPolicyGib
        )
    val actions = if (dangerous) buildList {
        add(FusionModelRuntimeAdmissionAction.APPLY_SAFE_RECOMMENDATION)
        if (accelerator != AcceleratorMode.CPU) add(FusionModelRuntimeAdmissionAction.USE_CPU)
        add(FusionModelRuntimeAdmissionAction.KEEP_CURRENT_SETTINGS)
        add(FusionModelRuntimeAdmissionAction.CANCEL)
    } else {
        emptyList()
    }
    return FusionModelRuntimeAdmission(
        risk = risk,
        requiresDecision = dangerous,
        actions = actions,
        safeMaxOutputTokens = safeMaxOutputTokens,
        safeKvCapacityTokens = safeKvCapacityTokens,
    )
}
