package com.projectnuke.fusion.util

import com.projectnuke.fusion.llm.MtpRuntimeStatus
import com.projectnuke.fusion.llm.RuntimeAttemptSnapshot
import com.projectnuke.fusion.llm.RuntimeBackend
import com.projectnuke.fusion.llm.RuntimeExecutionSnapshot
import com.projectnuke.fusion.model.GenerationSettings
import kotlin.math.absoluteValue

data class EffectiveRuntimeSettings(
    val modelName: String,
    val modelPath: String?,
    val acceleratorRequested: String,
    val actualBackend: String,
    val mtpEnabled: Boolean,
    val mtpStatus: MtpRuntimeStatus,
    val maxTokens: Int,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val reasoningEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val settingsRevision: Int,
    val engineRevision: Int
)

fun buildEffectiveRuntimeSettings(
    modelName: String,
    modelPath: String?,
    settings: GenerationSettings,
    reasoningEnabled: Boolean,
    webSearchEnabled: Boolean,
    mtpStatus: MtpRuntimeStatus,
    actualBackend: String? = null,
    actualVisionBackend: String? = null
): EffectiveRuntimeSettings {
    val requestedBackend = settings.accelerator.name
    val revisionSource = listOf(
        modelName,
        modelPath.orEmpty(),
        requestedBackend,
        settings.speculativeDecodingEnabled?.toString().orEmpty(),
        settings.maxTokens.toString(),
        settings.temperature.toString(),
        settings.topK.toString(),
        settings.topP.toString(),
        settings.reasoningBudgetTokens.toString()
    ).joinToString("|")
    val revision = revisionSource.hashCode().absoluteValue

    return EffectiveRuntimeSettings(
        modelName = modelName,
        modelPath = modelPath,
        acceleratorRequested = requestedBackend,
        actualBackend = actualBackend ?: requestedBackend,
        mtpEnabled = settings.speculativeDecodingEnabled == true,
        mtpStatus = mtpStatus,
        maxTokens = settings.maxTokens,
        temperature = settings.temperature,
        topK = settings.topK,
        topP = settings.topP,
        reasoningEnabled = reasoningEnabled,
        webSearchEnabled = webSearchEnabled,
        settingsRevision = revision,
        engineRevision = revision
    )
}

fun MtpRuntimeStatus.toKoreanMtpStatus(): String {
    return when (this) {
        MtpRuntimeStatus.OFF -> "꺼짐"
        MtpRuntimeStatus.REQUESTED -> "요청됨"
        MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST -> "MTP 요청으로 초기화됨"
        MtpRuntimeStatus.RUNTIME_CONFIRMED_ACTIVE -> "실행 중 활성화됨"
        MtpRuntimeStatus.UNSUPPORTED -> "미지원"
        MtpRuntimeStatus.FALLBACK_DISABLED -> "대체 비활성"
        MtpRuntimeStatus.FAILED -> "적용 실패"
    }
}

fun buildEffectiveSettingsLine(settings: EffectiveRuntimeSettings): String {
    return "settings rev ${settings.settingsRevision} · ${settings.actualBackend} · MTP ${settings.mtpStatus.toKoreanMtpStatus()} · max ${settings.maxTokens} · temp ${settings.temperature} · topK ${settings.topK} · topP ${settings.topP}"
}

/**
 * Builds the explicit "적용: ..." line that distinguishes the *applied*
 * runtime (selected backend and effective MTP status) from the *requested*
 * accelerator label shown on the first metrics line. Reads the immutable
 * RuntimeExecutionSnapshot fields so it can never contradict the
 * [buildEffectiveSettingsLine] string (which is built from the same effective
 * settings), and never re-reads mutable engine state.
 *
 * Returns null when there is no observable applied state (e.g. preview engines
 * or a generation that did not reach Engine init).
 */
fun buildAppliedRuntimeLine(
    actualBackend: String?,
    mtpStatus: MtpRuntimeStatus,
    mtpRequested: Boolean,
    fallbackEventCodes: String? = null
): String? {
    if (actualBackend == null) return null
    var result = "적용: $actualBackend · MTP ${mtpStatus.toKoreanMtpStatus()}"
    if (mtpRequested && fallbackEventCodes != null && fallbackEventCodes.isNotBlank()) {
        val fallbackDisplay = FallbackCauseFormatter.renderStoredCodesForDisplay(fallbackEventCodes)
        if (fallbackDisplay.isNotBlank()) {
            result += " · 폴백: $fallbackDisplay"
        }
    }
    return result
}

/**
 * Builds the applied runtime line with fallback summary from a successful execution snapshot.
 */
fun buildAppliedRuntimeLine(snapshot: RuntimeExecutionSnapshot): String? {
    if (snapshot.selectedTextBackend == RuntimeBackend.UNKNOWN) return null
    val fallbackSummary = FallbackCauseFormatter.formatFallbackSummary(snapshot)
    var result = "적용: ${snapshot.selectedTextBackend.name} · MTP ${snapshot.mtpStatus.toKoreanMtpStatus()}"
    snapshot.cpuThreadCount?.let { threads ->
        val mode = when (snapshot.cpuPerformanceMode) {
            com.projectnuke.fusion.model.RuntimePerformanceMode.AUTO -> "자동"
            com.projectnuke.fusion.model.RuntimePerformanceMode.MANUAL -> "사용자 설정"
            null -> "설정"
        }
        result += if (snapshot.selectedTextBackend == RuntimeBackend.CPU) {
            " · CPU ${com.projectnuke.fusion.model.RuntimeCpuThreadPolicy.threadCountLabel(threads)} · $mode"
        } else {
            " · CPU fallback budget ${com.projectnuke.fusion.model.RuntimeCpuThreadPolicy.threadCountLabel(threads)} · $mode"
        }
    }
    if (snapshot.mtpRequested && fallbackSummary.isNotBlank()) {
        result += " · 폴백: $fallbackSummary"
    }
    if (snapshot.selectedVisionBackend != null) {
        result += " · 비전: ${snapshot.selectedVisionBackend.name}"
    }
    return result
}

/**
 * Builds the applied runtime line with fallback summary from a failed attempt snapshot.
 */
fun buildAppliedRuntimeLine(snapshot: RuntimeAttemptSnapshot): String? {
    val fallbackSummary = FallbackCauseFormatter.formatAttemptFallbackSummary(snapshot)
    var result = "요청: ${snapshot.requestedAccelerator.name} · MTP ${if (snapshot.mtpRequested) "요청" else "꺼짐"}"
    if (fallbackSummary.isNotBlank()) {
        result += " · 폴백: $fallbackSummary"
    }
    return result
}
