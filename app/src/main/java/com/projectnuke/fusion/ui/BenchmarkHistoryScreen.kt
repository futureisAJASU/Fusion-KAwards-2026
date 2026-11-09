package com.projectnuke.fusion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.data.BenchmarkDao
import com.projectnuke.fusion.data.BenchmarkResultEntity
import com.projectnuke.fusion.util.FallbackCauseFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.widget.Toast
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

private val BenchmarkBg: Color
    @Composable get() = LocalFusionSemanticColors.current.background
private val BenchmarkCard: Color
    @Composable get() = LocalFusionSemanticColors.current.menu
private val BenchmarkPanel: Color
    @Composable get() = LocalFusionSemanticColors.current.panel
private val BenchmarkText: Color
    @Composable get() = LocalFusionSemanticColors.current.onSurface
private val BenchmarkSubtle: Color
    @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val BenchmarkAccent: Color
    @Composable get() = LocalFusionSemanticColors.current.primary
private val BenchmarkFail: Color
    @Composable get() = LocalFusionSemanticColors.current.error

@Composable
fun BenchmarkHistoryScreen(
    dao: BenchmarkDao,
    onBack: () -> Unit,
    initialModelFilter: String? = null
) {
    val allResults by dao.observeRecent(limit = 50).collectAsStateWithLifecycle(initialValue = emptyList())
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mtpFilter by remember { mutableStateOf("전체") }
    var acceleratorFilter by remember { mutableStateOf("전체") }
    var dateFilter by remember { mutableStateOf("전체") }
    var modelFilter by remember { mutableStateOf(initialModelFilter?.takeIf { it.isNotBlank() } ?: "전체") }
    var comparisonMode by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }

    DisposableEffect(Unit) {
        onDispose {
            comparisonMode = false
            selectedIds.clear()
            showClearConfirm = false
        }
    }

    fun leaveHistory() {
        comparisonMode = false
        selectedIds.clear()
        showClearConfirm = false
        onBack()
    }

    BackHandler(enabled = comparisonMode) {
        comparisonMode = false
        selectedIds.clear()
    }

    val now = System.currentTimeMillis()
    val models = remember(allResults) {
        listOf("전체") + allResults.map { it.modelName }.distinct().sorted()
    }

    val filtered = allResults.filter { result ->
        val dateOk = when (dateFilter) {
            "최근 1일" -> result.createdAt >= now - 24L * 60L * 60L * 1000L
            "최근 7일" -> result.createdAt >= now - 7L * 24L * 60L * 60L * 1000L
            "최근 30일" -> result.createdAt >= now - 30L * 24L * 60L * 60L * 1000L
            else -> true
        }
        val mtpOk = when (mtpFilter) {
            "MTP 켬" -> result.mtpRequested
            "MTP 끔" -> !result.mtpRequested
            else -> true
        }
        val acceleratorOk = acceleratorFilter == "전체" || result.accelerator.equals(acceleratorFilter, ignoreCase = true)
        val modelOk = modelFilter == "전체" || result.modelName == modelFilter
        mtpOk && acceleratorOk && dateOk && modelOk
    }

    val successful = filtered.filter { it.success }
    val hasDecodeSpeeds = successful.any { (it.decodeTokensPerSecond ?: 0f) > 0f }
    val primaryMetricLabel = if (hasDecodeSpeeds) "디코딩 속도" else "전체 속도"
    val speedValues = successful.mapNotNull { it.primaryComparisonSpeed(hasDecodeSpeeds) }.filter { it > 0f }
    val averageSpeed = speedValues.averageOrNull()
    val maxSpeed = speedValues.maxOrNull()
    val minSpeed = speedValues.minOrNull()
    val medianSpeed = speedValues.medianOrNull()
    val selectedResults = allResults.filter { it.id in selectedIds }

    if (comparisonMode) {
        BenchmarkComparisonScreen(
            results = selectedResults,
            onBack = {
                comparisonMode = false
                selectedIds.clear()
            }
        )
        return
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("벤치마크 기록을 삭제하시겠습니까?") },
            text = { Text("삭제된 벤치마크 기록은 복구할 수 없습니다.") },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("취소")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        scope.launch {
                            dao.deleteAll()
                            selectedIds.clear()
                            Toast.makeText(context, "벤치마크 기록을 삭제했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("삭제", color = BenchmarkFail)
                }
            },
            containerColor = BenchmarkPanel,
            titleContentColor = BenchmarkText,
            textContentColor = BenchmarkText
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().background(BenchmarkBg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { leaveHistory() }) { Text("뒤로", color = BenchmarkText) }
                Column {
                    Text("벤치마크 기록", color = BenchmarkText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("측정 결과를 조건별로 비교합니다.", color = BenchmarkSubtle, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    enabled = allResults.isNotEmpty(),
                    onClick = { showClearConfirm = true }
                ) {
                    Text("기록 삭제", color = if (allResults.isNotEmpty()) BenchmarkFail else BenchmarkSubtle)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SummaryCard("평균 $primaryMetricLabel", averageSpeed.formatSpeed(), Modifier.weight(1f))
                SummaryCard("최고 $primaryMetricLabel", maxSpeed.formatSpeed(), Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SummaryCard("최저 $primaryMetricLabel", minSpeed.formatSpeed(), Modifier.weight(1f))
                SummaryCard("중앙값 토큰 속도", medianSpeed.formatSpeed(), Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SummaryCard("속도 범위", formatSpeedRange(minSpeed, maxSpeed), Modifier.weight(1f))
                SummaryCard("측정 횟수", successful.size.toString(), Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "정확한 비교를 위해 같은 조건에서 여러 번 측정한 뒤 중앙값을 확인해 주세요.",
                color = BenchmarkSubtle,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "모바일 기기에서는 발열과 백그라운드 상태에 따라 측정값이 크게 달라질 수 있습니다.",
                color = BenchmarkSubtle,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "정확한 비교를 위해 MTP 꺼짐/켜짐을 번갈아 3회 이상 측정해 주세요.",
                color = BenchmarkSubtle,
                fontSize = 12.sp
            )
        }

        item {
            FilterRow("MTP", listOf("전체", "MTP 켬", "MTP 끔"), mtpFilter) { mtpFilter = it }
            FilterRow("가속기", listOf("전체", "GPU", "CPU"), acceleratorFilter) { acceleratorFilter = it }
            FilterRow("기간", listOf("전체", "최근 1일", "최근 7일", "최근 30일"), dateFilter) { dateFilter = it }
            FilterRow("모델", models, modelFilter) { modelFilter = it }
            if (!initialModelFilter.isNullOrBlank() && modelFilter != "전체") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "현재 모델 필터가 적용되었습니다: $modelFilter",
                        color = BenchmarkSubtle,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { modelFilter = "전체" }) {
                        Text("해제", color = BenchmarkAccent)
                    }
                }
            }
        }

        item {
            TextButton(
                enabled = selectedIds.size >= 2,
                onClick = { comparisonMode = true }
            ) {
                Text("선택 항목 비교", color = if (selectedIds.size >= 2) BenchmarkText else BenchmarkSubtle)
            }
        }

        if (filtered.isEmpty()) {
            item {
                Text("벤치마크 기록이 없습니다.", color = BenchmarkSubtle, modifier = Modifier.padding(20.dp))
            }
        } else {
            items(filtered, key = { it.id }) { result ->
                BenchmarkResultCard(
                    result = result,
                    selected = result.id in selectedIds,
                    onToggleSelected = {
                        if (result.id in selectedIds) selectedIds.remove(result.id) else selectedIds.add(result.id)
                    },
                    onCopy = {
                        clipboard.setText(AnnotatedString(result.toCopyText()))
                    }
                )
            }
        }
    }
}

@Composable
private fun BenchmarkComparisonScreen(
    results: List<BenchmarkResultEntity>,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().background(BenchmarkBg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("뒤로", color = BenchmarkText) }
                Text("벤치마크 비교", color = BenchmarkText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Text("토큰 속도 비교", color = BenchmarkText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            BenchmarkSpeedChart(results)
        }
        item {
            ComparisonTable(results)
        }
    }
}

@Composable
private fun BenchmarkSpeedChart(results: List<BenchmarkResultEntity>) {
    val accent = BenchmarkAccent
    val card = BenchmarkCard
    val labelColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hasDecodeSpeeds = results.any { (it.decodeTokensPerSecond ?: 0f) > 0f }
    val values = results.map { it.primaryComparisonSpeed(hasDecodeSpeeds) ?: 0f }
    val max = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    Canvas(
        modifier = Modifier.fillMaxWidth().height(180.dp).background(card, RoundedCornerShape(12.dp)).padding(10.dp)
    ) {
        val barWidth = size.width / (values.size.coerceAtLeast(1) * 2f)
        values.forEachIndexed { index, value ->
            val heightRatio = (value / max).coerceIn(0f, 1f)
            val left = index * barWidth * 2f + barWidth * 0.5f
            val barHeight = (size.height - 36f) * heightRatio
            drawRect(
                color = accent,
                topLeft = Offset(left, size.height - 24f - barHeight),
                size = Size(barWidth, barHeight)
            )
            drawContext.canvas.nativeCanvas.drawText(
                "#${index + 1}",
                left,
                size.height - 4f,
                android.graphics.Paint().apply {
                    color = labelColor
                    textSize = 24f
                }
            )
        }
    }
}

@Composable
private fun ComparisonTable(results: List<BenchmarkResultEntity>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        results.forEachIndexed { index, result ->
            Surface(shape = RoundedCornerShape(12.dp), color = BenchmarkCard, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("#${index + 1} · ${result.createdAt.formatDateTime()}", color = BenchmarkText, fontWeight = FontWeight.SemiBold)
                    Text("모델: ${result.modelName}", color = BenchmarkSubtle)
                    Text("모델 경로: ${result.modelPath ?: "없음"}", color = BenchmarkSubtle)
                    Text("설정 스냅샷: ${result.createdAt.formatDateTime()}", color = BenchmarkSubtle)
                    Text("요청 가속기: ${result.accelerator} · 실제 백엔드: ${result.selectedTextBackend ?: "확인 불가"}", color = BenchmarkSubtle)
                    Text("MTP 토글: ${if (result.mtpRequested) "켜짐" else "꺼짐"} · MTP 런타임 상태: ${result.mtpStatus}", color = BenchmarkSubtle)
                    Text("모델 설정 재적용: 수행됨 · 재적용 시간: ${result.modelLoadingMs?.let { "${it}ms" } ?: "측정 불가"}", color = BenchmarkSubtle)
                    Text("최대 출력=${result.maxTokens} · 온도=${result.temperature} · Top-K=${result.topK} · Top-P=${result.topP}", color = BenchmarkSubtle)
                    Text("첫 토큰 시간: ${result.firstTokenLatencyMs?.let { "${it}ms" } ?: "측정 불가"}", color = BenchmarkSubtle)
                    Text("총 생성 시간: ${result.totalGenerationMs}ms · 추정 출력 토큰 수: ${result.estimatedOutputTokens}", color = BenchmarkSubtle)
                    Text("전체 기준 토큰 속도: ${result.totalTokensPerSecond.formatSpeed()}", color = BenchmarkSubtle)
                    Text("디코딩 기준 토큰 속도: ${result.decodeTokensPerSecond.formatSpeed()}", color = BenchmarkText)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(12.dp), color = BenchmarkCard, modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, color = BenchmarkSubtle, fontSize = 12.sp)
            Text(value, color = BenchmarkText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FilterRow(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Text(title, color = BenchmarkSubtle, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.take(6).forEach { option ->
                Surface(
                    shape = RoundedCornerShape(999.dp),
                        color = if (selected == option) BenchmarkAccent else BenchmarkPanel,
                    modifier = Modifier.clickable { onSelect(option) }
                ) {
                    Text(
                        option,
                        color = if (selected == option) MaterialTheme.colorScheme.onPrimary else BenchmarkText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BenchmarkResultCard(
    result: BenchmarkResultEntity,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else BenchmarkCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.clickable { onToggleSelected() }.background(if (selected) BenchmarkAccent else BenchmarkPanel, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(if (selected) "선택됨" else "선택", color = if (selected) MaterialTheme.colorScheme.onPrimary else BenchmarkText, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(result.createdAt.formatDateTime(), color = BenchmarkSubtle, fontSize = 12.sp)
            }
            Text(result.modelName, color = BenchmarkText, fontWeight = FontWeight.SemiBold)
            if (!result.success) {
                Text("측정 실패", color = BenchmarkFail, fontWeight = FontWeight.SemiBold)
            }
            Text(result.appliedSettingsLine(), color = BenchmarkText, fontSize = 12.sp)
            Text("모델 설정을 다시 적용했습니다.", color = BenchmarkSubtle, fontSize = 12.sp)
            Text("설정 스냅샷: ${result.createdAt.formatDateTime()} · 모델 경로: ${result.modelPath ?: "없음"}", color = BenchmarkSubtle, fontSize = 12.sp)
            Text("요청 가속기: ${result.accelerator} · 실제 백엔드: ${result.selectedTextBackend ?: "확인 불가"} · MTP 런타임 상태: ${result.mtpStatus}", color = BenchmarkSubtle, fontSize = 12.sp)
            Text("최대 출력=${result.maxTokens} · 온도=${result.temperature} · Top-K=${result.topK} · Top-P=${result.topP}", color = BenchmarkSubtle, fontSize = 12.sp)
            Text("총 ${result.totalGenerationMs}ms · 첫 토큰 ${result.firstTokenLatencyMs?.let { "${it}ms" } ?: "측정 불가"} · ${result.estimatedOutputTokens} tokens", color = BenchmarkSubtle, fontSize = 12.sp)
            Text("전체 기준 토큰 속도: ${result.totalTokensPerSecond.formatSpeed()}", color = BenchmarkSubtle)
            Text("디코딩 기준 토큰 속도: ${result.decodeTokensPerSecond.formatSpeed()}", color = BenchmarkText)
            TextButton(onClick = onCopy) { Text("결과 복사", color = BenchmarkText) }
        }
    }
}

private fun List<Float>.averageOrNull(): Float? {
    if (isEmpty()) return null
    return (sum() / size)
}

private fun List<Float>.medianOrNull(): Float? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2f
    } else {
        sorted[middle]
    }
}

private fun BenchmarkResultEntity.primaryComparisonSpeed(preferDecode: Boolean): Float? {
    val decode = decodeTokensPerSecond?.takeIf { it > 0f }
    val total = totalTokensPerSecond.takeIf { it > 0f }
    return if (preferDecode) decode ?: total else total
}

private fun formatSpeedRange(minSpeed: Float?, maxSpeed: Float?): String {
    if (minSpeed == null || maxSpeed == null) return "측정 불가"
    return "${minSpeed.formatSpeed()} ~ ${maxSpeed.formatSpeed()}"
}

private fun BenchmarkResultEntity.appliedSettingsLine(): String {
    val mtpText = if (mtpRequested) "MTP $mtpStatus" else "MTP 꺼짐"
    var result = "적용된 설정: ${selectedTextBackend ?: accelerator} · $mtpText · 최대 출력=$maxTokens · 온도=$temperature · Top-K=$topK · Top-P=$topP"
    val rendered = FallbackCauseFormatter.renderStoredCodesForDisplay(fallbackEventCodes)
    if (rendered.isNotBlank()) {
        result += " · 폴백: $rendered"
    }
    return result
}
private fun BenchmarkResultEntity.technicalSettingsLine(): String {
    val mtpText = if (mtpRequested) "MTP $mtpStatus" else "MTP off"
    return "maxTokens=$maxTokens temp=$temperature topK=$topK topP=$topP"
}

private fun Float?.formatSpeed(): String {
    return this?.let { String.format(Locale.US, "%.1f tok/s", it) } ?: "측정 불가"
}

private fun Long.formatDateTime(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(this))
}

private fun BenchmarkResultEntity.toCopyText(): String {
    return buildString {
        appendLine("Fusion Benchmark Result")
        appendLine("Time: ${createdAt.formatDateTime()}")
        appendLine("Model: $modelName")
        appendLine("Path: ${modelPath ?: "none"}")
        appendLine("Settings snapshot: ${createdAt.formatDateTime()}")
        appendLine("Requested accelerator: $accelerator")
        appendLine("Actual backend: ${selectedTextBackend ?: "unknown"}")
        appendLine("MTP toggle: ${if (mtpRequested) "on" else "off"}")
        appendLine("MTP runtime status: $mtpStatus")
        if (!fallbackEventCodes.isNullOrBlank()) {
            val rendered = FallbackCauseFormatter.renderStoredCodesForDisplay(fallbackEventCodes)
            appendLine("폴백: $rendered")
        }
        if (samplerBackend.isNotBlank() && samplerBackend != "UNKNOWN") appendLine("Sampler backend: $samplerBackend")
        if (!selectedVisionBackend.isNullOrBlank()) appendLine("Selected vision backend: $selectedVisionBackend")
        appendLine("Initialized with MTP: $initializedWithMtp")
        if (nativeTtftSeconds != null) appendLine("Native tTFT: ${"%.3f".format(Locale.US, nativeTtftSeconds)}s")
        if (nativePrefillTokensPerSecond != null) appendLine("Native prefill tok/s: ${"%.2f".format(Locale.US, nativePrefillTokensPerSecond)}")
        if (nativeDecodeTokensPerSecond != null) appendLine("Native decode tok/s: ${"%.2f".format(Locale.US, nativeDecodeTokensPerSecond)}")
        if (nativeInitTimeSeconds != null) appendLine("Native init seconds: ${"%.3f".format(Locale.US, nativeInitTimeSeconds)}")
        appendLine("Applied settings: ${technicalSettingsLine()}")
        appendLine("Engine reload: performed")
        appendLine("Engine reload time: ${modelLoadingMs?.let { "${it}ms" } ?: "unavailable"}")
        appendLine("Settings: max=$maxTokens temp=$temperature topK=$topK topP=$topP")
        appendLine("First token: ${firstTokenLatencyMs?.let { "${it}ms" } ?: "unavailable"}")
        appendLine("Total generation: ${totalGenerationMs}ms")
        appendLine("Estimated output tokens: $estimatedOutputTokens")
        appendLine("Total tok/s: ${totalTokensPerSecond.formatSpeed()}")
        appendLine("Decode tok/s: ${decodeTokensPerSecond.formatSpeed()}")
        appendLine("Success: $success")
        if (!errorMessage.isNullOrBlank()) appendLine("Error: $errorMessage")
    }
}
