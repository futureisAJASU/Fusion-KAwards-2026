package com.projectnuke.fusion.ui

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.projectnuke.fusion.data.BenchmarkResultEntity
import com.projectnuke.fusion.util.collectFusionSocInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FusionDeveloperLogSnapshot(
    val fullLogText: String,
    val errorReportText: String,
    val memoryStatusText: String,
    val abTestStatusText: String,
    val sections: FusionDeveloperLogSections,
)

data class FusionDeveloperLogSections(
    val currentStatus: String,
    val model: String,
    val deviceMemory: String,
    val benchmark: String,
    val settings: String,
    val savedMemory: String,
    val abTests: String,
    val recentErrors: String,
)

suspend fun buildFusionDeveloperLogSnapshotAsync(
    context: Context,
    prefs: SharedPreferences,
    benchmarkResults: List<BenchmarkResultEntity>,
    events: List<FusionDeveloperLogEvent>
): FusionDeveloperLogSnapshot {
    val abTestHistory = ModelAbTestHistoryStore.loadAsync(context)
    return buildFusionDeveloperLogSnapshotFromHistory(
        context = context,
        prefs = prefs,
        benchmarkResults = benchmarkResults,
        events = events,
        abTestHistory = abTestHistory
    )
}

suspend fun buildFusionDeveloperLogSnapshotFromHistory(
    context: Context,
    prefs: SharedPreferences,
    benchmarkResults: List<BenchmarkResultEntity>,
    events: List<FusionDeveloperLogEvent>,
    abTestHistory: List<StoredAbTestSession>
): FusionDeveloperLogSnapshot = withContext(Dispatchers.IO) {
    val runtimeSnapshot = resolveFusionRuntimeDiagnosticsSnapshot(context, prefs)
    val selectedModel = runtimeSnapshot.selectedModelName
    val selectedSpec = runtimeSnapshot.selectedSpec
    val effectiveSettings = runtimeSnapshot.effectiveProfile.generationSettings
    val accelerator = effectiveSettings.accelerator.name
    val mtpEnabled = effectiveSettings.speculativeDecodingEnabled == true
    val reasoningEnabled = runtimeSnapshot.effectiveProfile.reasoningEnabled
    val webSearchEnabled = runtimeSnapshot.webSearchEnabled
    val maxTokens = effectiveSettings.maxTokens
    val temperature = effectiveSettings.temperature
    val topK = effectiveSettings.topK
    val topP = effectiveSettings.topP
    val appInfo = resolveFusionDiagnosticsAppInfo(context)
    val socInfo = collectFusionSocInfo()
    val memoryInfo = ActivityManager.MemoryInfo().also {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(it)
    }
    val latestBenchmark = benchmarkResults.firstOrNull()
    val savedMemories = loadAllConversationMemoryCandidates(context)
    val savedSummaries = loadAllConversationSummaries(context)
    val memoryContext = buildSavedMemoryContext(context, prefs, currentConversationId = null)
    val latestAbTest = abTestHistory.firstOrNull()
    val abTestStatus = buildString {
        appendLine("A/B 테스트 기록 수: ${abTestHistory.size}개")
        appendLine("최근 A/B 테스트: ${latestAbTest?.let { formatTime(it.createdAt) } ?: "없음"}")
        appendLine("최근 대상 수: ${latestAbTest?.targetCount ?: 0}개")
        append("최근 실패 대상 수: ${latestAbTest?.failureCount ?: 0}개")
    }
    val memoryStatus = buildString {
        appendLine("메모리 사용: ${if (isSavedMemoryContextEnabled(prefs)) "켜짐" else "꺼짐"}")
        appendLine("저장된 메모리 수: ${savedMemories.size}개")
        appendLine("사용 중인 메모리 수: ${savedMemories.count { it.enabled && it.scope != MemoryScope.DISABLED }}개")
        appendLine("전체 대화 메모리 수: ${savedMemories.count { it.scope == MemoryScope.GLOBAL }}개")
        appendLine("대화 전용 메모리 수: ${savedMemories.count { it.scope == MemoryScope.CONVERSATION_ONLY }}개")
        appendLine("모델 전용 메모리 수: ${savedMemories.count { it.scope == MemoryScope.MODEL_ONLY }}개")
        appendLine("사용 안 함 메모리 수: ${savedMemories.count { !it.enabled || it.scope == MemoryScope.DISABLED }}개")
        appendLine("현재 미리보기 포함 메모리 수: ${memoryContext.itemCount}개")
        appendLine("대화 요약 수: ${savedSummaries.size}개")
        appendLine("메모리 컨텍스트 예상 문자 수: ${memoryContext.characterCount}")
        append("메모리 컨텍스트 잘림: ${memoryContext.trimmed}")
    }

    val sections = FusionDeveloperLogSections(
        currentStatus = listOf(
            "앱 버전: ${appInfo.versionName} (${appInfo.versionCode})",
            "기기: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "SoC/AP: ${socInfo.detectedSocVendor.name}",
        ).joinToString("\n"),
        model = listOf(
            "선택 모델: $selectedModel",
            "모델 패밀리: ${selectedSpec?.family?.name ?: "정보 없음"}",
            "런타임 형식: ${selectedSpec?.runtimeFormat?.name ?: "정보 없음"}",
            "가속기: $accelerator",
            "MTP: ${if (mtpEnabled) "켜짐" else "꺼짐"}",
        ).joinToString("\n"),
        deviceMemory = listOf(
            "전체 RAM: ${formatBytes(memoryInfo.totalMem)}",
            "사용 가능 RAM: ${formatBytes(memoryInfo.availMem)}",
            "저메모리 상태: ${memoryInfo.lowMemory}",
        ).joinToString("\n"),
        benchmark = listOf(
            "기록 수: ${benchmarkResults.size}개",
            "최근 모델: ${latestBenchmark?.modelName ?: "없음"}",
            "최근 decode tok/s: ${latestBenchmark?.decodeTokensPerSecond?.let { String.format(Locale.US, "%.1f", it) } ?: "정보 없음"}",
        ).joinToString("\n"),
        settings = listOf(
            "maxTokens=$maxTokens, temperature=$temperature, topK=$topK, topP=$topP",
            "Reasoning=${if (reasoningEnabled) "켜짐" else "꺼짐"}, Web Search=${if (webSearchEnabled) "켜짐" else "꺼짐"}",
        ).joinToString("\n"),
        savedMemory = memoryStatus,
        abTests = abTestStatus,
        recentErrors = formatRecentDeveloperErrors(events),
    )

    val full = buildString {
        appendLine("개발자 로그")
        appendSection("현재 상태", sections.currentStatus)
        appendSection("모델", sections.model)
        appendSection("메모리", sections.deviceMemory)
        appendSection("벤치마크", sections.benchmark)
        appendSection("설정", sections.settings)
        appendSection("저장된 메모리", sections.savedMemory)
        appendSection("A/B 테스트", sections.abTests)
        appendSection("최근 오류", sections.recentErrors)
    }.trimEnd()

    val errorReport = buildString {
        appendLine("Fusion 오류 보고서")
        appendLine("[저장된 메모리]")
        appendLine(memoryStatus)
        appendLine()
        appendLine("[A/B 테스트]")
        appendLine(abTestStatus)
        appendLine()
        append(sections.recentErrors)
    }.trimEnd()

    FusionDeveloperLogSnapshot(
        fullLogText = full,
        errorReportText = errorReport,
        memoryStatusText = memoryStatus,
        abTestStatusText = abTestStatus,
        sections = sections,
    )
}

internal fun formatRecentDeveloperErrors(events: List<FusionDeveloperLogEvent>): String {
    val recentErrors = events
        .filter { it.category.contains("error", ignoreCase = true) || it.category.contains("오류") }
        .take(20)
    if (recentErrors.isEmpty()) return "최근 오류가 없습니다."
    return recentErrors.joinToString("\n") { event ->
        "${formatTime(event.timestamp)} | ${event.category} | ${event.message}${event.technicalSummary?.let { " | $it" } ?: ""}"
    }
}

private fun StringBuilder.appendSection(title: String, contents: String) {
    appendLine("[$title]")
    appendLine(contents)
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.2f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.2f KB", bytes / kb)
        else -> "$bytes B"
    }
}
