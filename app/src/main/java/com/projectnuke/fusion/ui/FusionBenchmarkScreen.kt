package com.projectnuke.fusion.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.BenchmarkDao
import com.projectnuke.fusion.data.BenchmarkResultEntity
import com.projectnuke.fusion.llm.ChatGenerationRunningException
import com.projectnuke.fusion.llm.EngineSelectionRuntime
import com.projectnuke.fusion.llm.FallbackReason
import com.projectnuke.fusion.llm.FusionRuntimeLock
import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.LiteRtLlmEngine
import com.google.ai.edge.litertlm.LogSeverity
import com.projectnuke.fusion.llm.FusionRuntimeManager
import com.projectnuke.fusion.llm.MtpRuntimeStatus
import com.projectnuke.fusion.llm.GenerationBenchmarkStats
import com.projectnuke.fusion.llm.RuntimeAttemptSnapshot
import com.projectnuke.fusion.llm.RuntimeFailureSnapshot
import com.projectnuke.fusion.llm.inferredMtpStatus
import com.projectnuke.fusion.llm.RuntimeComponentBackend
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.LocalGenerationSettingsPolicy
import com.projectnuke.fusion.model.KvCacheCapacityPolicy
import com.projectnuke.fusion.model.resolveEffectiveLocalModelProfile
import com.projectnuke.fusion.model.toConversationOptions
import com.projectnuke.fusion.model.toRequestedEngineProfile
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.util.FusionMemoryManager
import com.projectnuke.fusion.util.MtpPolicyProduction
import com.projectnuke.fusion.util.buildEffectiveRuntimeSettings
import com.projectnuke.fusion.util.toKoreanMtpStatus
import com.projectnuke.fusion.util.FallbackCauseFormatter
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
internal const val FusionBenchmarkPrompt = "반도체 공정과 메모리 계층 구조를 1000자 정도로 설명해 주세요. 핵심 개념, 성능 병목, 전력 효율 관점까지 포함해 주세요."

internal const val FusionBenchmarkPromptLabel = "반도체 공정/메모리 계층 1000자 설명"

internal fun buildBenchmarkPromptMessages(
    modelFamily: ModelFamily,
    prompt: String,
): List<ChatMessage> = listOf(
    ChatMessage("system", "FUSION_MODEL_FAMILY=${modelFamily.name}"),
    ChatMessage("user", prompt),
)

internal enum class BenchmarkBackAction { LEAVE, BLOCK }

internal fun resolveBenchmarkBackAction(isRunning: Boolean): BenchmarkBackAction =
    if (isRunning) BenchmarkBackAction.BLOCK else BenchmarkBackAction.LEAVE

internal fun shouldConsumeBenchmarkRequest(requestToken: Int, lastConsumedToken: Int): Boolean =
    requestToken > 0 && requestToken != lastConsumedToken

internal fun shouldConsumeAiProviderRequest(requestToken: Int, lastConsumedToken: Int): Boolean =
    requestToken > 0 && requestToken != lastConsumedToken

/**
 * True monotonic request gate shared by the API-Settings CTA and the
 * Benchmark deep link. The parent owns a never-reset [nextToken] plus a
 * separately-owned nullable [pendingToken]. Issuing bumps [nextToken] and
 * publishes it as pending; acknowledgement clears ONLY the pending slot and
 * never resets the monotonic counter, so a second request always yields a
 * distinct token and a recreated child observing `pending ?: 0` can never
 * reopen an already-acknowledged request.
 */
internal data class MonotonicRequestGate(
    val nextToken: Long = 0L,
    val pendingToken: Long? = null,
)

internal fun issueMonotonicRequest(gate: MonotonicRequestGate): MonotonicRequestGate {
    val next = gate.nextToken + 1L
    return gate.copy(nextToken = next, pendingToken = next)
}

internal fun acknowledgeMonotonicRequest(gate: MonotonicRequestGate): MonotonicRequestGate =
    gate.copy(pendingToken = null)

internal fun childRequestToken(pendingToken: Long?): Int = pendingToken?.toInt() ?: 0

/**
 * A launch request is copied into child-owned immutable state BEFORE the
 * parent acknowledges (clears) its pending request fields. The parent's
 * acknowledgement must never mutate the already-captured child state, so a
 * deep link that asks to open Benchmark History stays on History until the
 * user explicitly leaves it.
 */
internal data class BenchmarkLaunchState(
    val showHistory: Boolean,
    val modelFilter: String?,
)

internal fun captureBenchmarkLaunchState(
    initialShowHistory: Boolean,
    initialHistoryModelFilter: String?,
): BenchmarkLaunchState = BenchmarkLaunchState(
    showHistory = initialShowHistory,
    modelFilter = initialHistoryModelFilter,
)

@Composable
fun FusionBenchmarkScreen(
    onBack: () -> Unit,
    initialShowHistory: Boolean = false,
    initialHistoryModelFilter: String? = null,
    onInitialRequestConsumed: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val semantic = LocalFusionSemanticColors.current
    val materialColors = androidx.compose.material3.MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("fusion_chat_settings", Context.MODE_PRIVATE) }
    val engine = remember { FusionRuntimeManager.sharedEngine(context) }
    val db = remember { AppDatabase.getInstance(context) }
    val benchmarkDao = remember { db.benchmarkDao() }
    var showHistory by remember { mutableStateOf(initialShowHistory) }
    var activeHistoryModelFilter: String? by remember { mutableStateOf(initialHistoryModelFilter) }

    LaunchedEffect(Unit) {
        val launch = captureBenchmarkLaunchState(initialShowHistory, initialHistoryModelFilter)
        if (launch.showHistory) {
            activeHistoryModelFilter = launch.modelFilter
        }
        onInitialRequestConsumed?.invoke()
    }

        DisposableEffect(engine) {
            val unregisterMemoryUnloader = FusionMemoryManager.registerIdleEngineUnloader {
                Log.i("FusionMemory", "Requesting idle shared engine unload under memory pressure")
                scope.launch {
                    FusionRuntimeManager.unloadSharedEngineWhenRuntimeIdle("benchmark_memory_pressure")
                }
            }
            onDispose {
                unregisterMemoryUnloader()
            }
        }

    if (showHistory) {
        BackHandler { showHistory = false }
        BenchmarkHistoryScreen(
            dao = benchmarkDao,
            onBack = { showHistory = false },
            initialModelFilter = activeHistoryModelFilter
        )
        return
    }

    val selectedModel = prefs.getString("selected_model", "Gemma 4 E2B-it") ?: "Gemma 4 E2B-it"
    val selectedModelPath = prefs.getString("selected_model_path", null)
    val settings = resolveEffectiveLocalModelProfile(context, prefs, selectedModel, selectedModelPath).generationSettings
    val safeMaxTokensCap = benchmarkSafeMaxTokensCap(context)
    val resolvedModelPath = resolveBenchmarkModelPath(context, selectedModel, selectedModelPath)
    var isRunning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            status = null
            result = null
        }
    }

    fun leaveBenchmark() {
        if (isRunning) {
            Toast.makeText(context, "벤치마크 실행 중에는 화면을 나갈 수 없습니다. 완료 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isRunning) {
            status = null
            result = null
        }
        onBack()
    }

    BackHandler {
        when (resolveBenchmarkBackAction(isRunning)) {
            BenchmarkBackAction.BLOCK -> Toast.makeText(context, "벤치마크 실행 중에는 뒤로 갈 수 없습니다. 완료 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
            BenchmarkBackAction.LEAVE -> leaveBenchmark()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { leaveBenchmark() }) { Text("뒤로", color = semantic.onSurface) }
            Column(modifier = Modifier.weight(1f)) {
                Text("벤치마크", color = semantic.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("선택한 모델의 응답 속도와 생성 성능을 측정합니다.", color = semantic.onSurfaceVariant, fontSize = 12.sp)
            }
            TextButton(
                enabled = !isRunning,
                onClick = { showHistory = true }
            ) {
                Text("기록", color = semantic.onSurface)
            }
        }

        BenchmarkCardBlock {
            Text("모델: $selectedModel", color = semantic.onSurface)
            Text(if (resolvedModelPath == null) "모델 파일을 선택해 주세요." else "모델 파일 선택됨", color = semantic.onSurfaceVariant, fontSize = 12.sp)
            Text("가속기: ${settings.accelerator.name}", color = semantic.onSurface)
            Text("MTP 가속: ${initialBenchmarkMtpStatusLabel(context, settings, selectedModel)}", color = semantic.onSurface)
            Text("최대 출력=${settings.maxTokens} / 온도=${settings.temperature} / Top-K=${settings.topK} / Top-P=${settings.topP}", color = semantic.onSurfaceVariant, fontSize = 12.sp)
            safeMaxTokensCap?.let {
                Text("벤치마크 안전 제한: 최대 출력 토큰=$it", color = semantic.onSurfaceVariant, fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = semantic.panel) {
                TextButton(
                    enabled = !isRunning,
                    onClick = {
                        val snapshot = loadBenchmarkSnapshot(context, prefs)
                        if (FusionRuntimeLock.isChatGenerationRunning) {
                            Toast.makeText(context, "현재 응답을 생성하는 중입니다. 완료 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        if (snapshot.modelPath.isNullOrBlank() || !File(snapshot.modelPath).exists()) {
                            Log.e("FusionBenchmark", "Selected model file missing: ${snapshot.modelPath}")
                            DeveloperLogStore.record(context, "benchmark", "벤치마크 시작 실패", "model file missing")
                            Toast.makeText(context, "선택한 모델 파일을 찾을 수 없습니다. 모델을 다시 선택해 주세요.", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        FusionMemoryManager.logMemorySnapshot("FusionMemory", context, "benchmark-start")
                        val memorySnapshot = FusionMemoryManager.getMemorySnapshot(context)
                        if (memorySnapshot.lowMemory || FusionMemoryManager.shouldBlockBenchmark(context)) {
                            status = "사용 가능한 메모리가 부족하여 벤치마크를 시작할 수 없습니다."
                            DeveloperLogStore.record(context, "memory", "벤치마크 시작 차단", "low memory")
                            Toast.makeText(context, "사용 가능한 메모리가 부족하여 벤치마크를 시작할 수 없습니다.", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        if (memorySnapshot.availMem < memorySnapshot.threshold * 2) {
                            Toast.makeText(context, "메모리 사용량이 높아 일부 기능이 느려질 수 있습니다.", Toast.LENGTH_SHORT).show()
                        }

                        isRunning = true
                        status = "벤치마크를 준비하는 중입니다."
                        result = null
                        DeveloperLogStore.record(context, "benchmark", "벤치마크 시작", snapshot.modelName)

                        scope.launch {
                            runBenchmark(
                                context = context,
                                engine = engine,
                                benchmarkDao = benchmarkDao,
                                snapshot = snapshot,
                                onStatus = { status = it },
                                onResult = {
                                    result = it
                                },
                                onFinished = { isRunning = false }
                            )
                        }
                    }
                ) { Text("벤치마크 시작", color = semantic.onSurface) }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = semantic.panel) {
                TextButton(
                    enabled = !result.isNullOrBlank(),
                    onClick = {
                        clipboard.setText(AnnotatedString(result.orEmpty()))
                        Toast.makeText(context, "벤치마크 결과를 복사했습니다.", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("결과 복사", color = semantic.onSurface) }
            }
        }

        if (isRunning) {
            BenchmarkCardBlock {
                Text(status ?: "벤치마크를 준비하는 중입니다.", color = semantic.onSurface, fontSize = 13.sp)
                FusionBusyLine(Modifier.fillMaxWidth())
            }
        }

        status?.takeIf { it.isNotBlank() }?.let {
            if (!isRunning) {
                BenchmarkCardBlock { Text(it, color = semantic.onSurface) }
            }
        }
        result?.takeIf { it.isNotBlank() }?.let {
            BenchmarkCardBlock { Text(it, color = semantic.onSurface, fontSize = 13.sp) }
        }
    }
}

internal data class BenchmarkSnapshot(
    val modelName: String,
    val modelPath: String?,
    val settings: GenerationSettings,
    val requestedMaxTokens: Int,
    val requestedKvCapacity: Int,
    val safeMaxTokensCap: Int?,
    val reasoningEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val modelFamily: ModelFamily = ModelFamily.GEMMA,
    val modelIdentity: String? = null,
)

private suspend fun runBenchmark(
        context: Context,
        engine: LiteRtLlmEngine,
        benchmarkDao: BenchmarkDao,
        snapshot: BenchmarkSnapshot,
        onStatus: (String?) -> Unit,
        onResult: (String) -> Unit,
        onFinished: () -> Unit
    ) {
try {
             FusionRuntimeLock.withExclusiveBenchmark(
                 onPrepareExclusiveMode = {
                     onStatus("모델 리소스를 정리하는 중입니다.")
                     Log.i("FusionEngine", "Requesting chat engine unload before benchmark")
                     FusionRuntimeLock.requestChatEngineUnloadForBenchmark()
                     FusionRuntimeManager.unloadSharedEngineAfterExclusive("benchmark_prepare")
                 }
             ) {
                 engine.setNativeMinLogSeverity(LogSeverity.WARNING)
                 try {
                 Log.i(
                     "FusionBenchmark",
                     "Benchmark start model=${snapshot.modelName} path=${snapshot.modelPath} accelerator=${snapshot.settings.accelerator.name} mtp=${snapshot.settings.speculativeDecodingEnabled == true} maxTokens=${snapshot.settings.maxTokens} temp=${snapshot.settings.temperature} topK=${snapshot.settings.topK} topP=${snapshot.settings.topP}"
                 )
                 Log.i("FusionEngine", "Benchmark marks engine stale and reloads runtime settings")
                 onStatus("모델 설정을 다시 적용하는 중입니다.")
                 val reloadStartMs = SystemClock.elapsedRealtime()
                 FusionRuntimeManager.unloadSharedEngineAfterExclusive("benchmark_reload")
                 val engineReloadMs = SystemClock.elapsedRealtime() - reloadStartMs
                 onStatus("모델을 불러오는 중입니다.")

            val start = SystemClock.elapsedRealtime()
            var firstTokenMs: Long? = null
            val output = StringBuilder()
            onStatus("응답을 생성하는 중입니다.")
            // Phase 1: benchmark consumes the same shared local request
            // preparation contract; the displayed "applied output tokens" and the
            // actual ConversationOptions value both come from this ONE prepared
            // request (no second clamp below).
            val preparedBenchmark = com.projectnuke.fusion.chat.LocalPromptPreparation.prepare(
                rawMessages = buildBenchmarkPromptMessages(snapshot.modelFamily, FusionBenchmarkPrompt),
                budget = com.projectnuke.fusion.chat.FinalPromptBudget(
                    modelId = snapshot.modelName,
                    external = false,
                    maxOutputTokens = snapshot.settings.maxTokens,
                    engineKvCapacityTokens = snapshot.settings.kvCacheCapacityTokens,
                ),
                settings = snapshot.settings,
            )
            if (preparedBenchmark == null) {
                throw BenchmarkFailedException(
                    reason = "context too large for engine KV",
                    attemptSnapshot = null,
                    failureSnapshot = null
                )
            }
            val outcome = engine.generateStreaming(
                messages = preparedBenchmark.messages,
                profile = snapshot.settings.toRequestedEngineProfile(
                    modelPath = snapshot.modelPath.orEmpty(),
                    enableVisionBackend = false,
                    modelIdentity = snapshot.modelIdentity,
                ),
                options = preparedBenchmark.conversationOptions,
                onToken = { token ->
                    if (firstTokenMs == null && token.isNotEmpty()) {
                        firstTokenMs = SystemClock.elapsedRealtime() - start
                    }
                    output.append(token)
                }
            )

            val totalMs = SystemClock.elapsedRealtime() - start
            when (outcome) {
                is GenerationOutcome.Cancelled, GenerationOutcome.Empty -> {
                    val reason = when (outcome) {
                        is GenerationOutcome.Cancelled -> "generation cancelled"
                        GenerationOutcome.Empty -> "generation empty"
                        is GenerationOutcome.Success -> ""
                        is GenerationOutcome.Failure -> "generation failed: ${outcome.kind}"
                    }
                    val asFailure = outcome as? GenerationOutcome.Failure
                    val attemptSnapshot = asFailure?.attemptSnapshot
                    val failureSnapshot = asFailure?.failureSnapshot
                    throw BenchmarkFailedException(reason, attemptSnapshot, failureSnapshot)
                }
                is GenerationOutcome.Failure -> {
                    val attemptSnapshot = outcome.attemptSnapshot
                    val failureSnapshot = outcome.failureSnapshot
                    throw BenchmarkFailedException("generation failed: ${outcome.kind}", attemptSnapshot, failureSnapshot)
                }
                is GenerationOutcome.Success -> {
                    /* continue with success path below */
                }
            }
            @Suppress("UNUSED_VARIABLE") val success = outcome as GenerationOutcome.Success
            val text = success.text
                .replace(Regex("""</?fusion_(thinking|answer|metrics)>"""), "")
                .trim()
            output.clear()
            val estimatedTokens = estimateBenchmarkTokens(text)
            val totalTps = if (totalMs > 0) estimatedTokens * 1000.0 / totalMs else 0.0
            val decodeMs = firstTokenMs?.let { totalMs - it }?.takeIf { it > 0 }
            val decodeTps = decodeMs?.let { estimatedTokens * 1000.0 / it }
            val outcomeSnapshot = success.snapshot
            // Read from the immutable snapshot — never from
            // engine.lastMtpStatus / engine.lastRuntimeSelection, which are
            // only meaningful for the currently loaded engine and would
            // contradict the snapshot when present.
            val mtpStatus = outcomeSnapshot?.mtpStatus
                ?: MtpRuntimeStatus.OFF
            val runtimeSelection: EngineSelectionRuntime? = outcomeSnapshot?.let {
                EngineSelectionRuntime(
                    requestedAccelerator = it.requestedAccelerator.name,
                    actualTextBackend = it.selectedTextBackend.name,
                    actualVisionBackend = it.selectedVisionBackend?.name,
                    requestedMtp = it.mtpRequested,
                    initializedWithMtp = it.mtpStatus == MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST,
                    fallbackReason = null
                )
            }
            val nativeStats = success.stats
            val effective = buildEffectiveRuntimeSettings(
                modelName = snapshot.modelName,
                modelPath = snapshot.modelPath,
                settings = snapshot.settings,
                reasoningEnabled = snapshot.reasoningEnabled,
                webSearchEnabled = snapshot.webSearchEnabled,
                mtpStatus = mtpStatus,
                actualBackend = runtimeSelection?.actualTextBackend,
                actualVisionBackend = runtimeSelection?.actualVisionBackend
            )

            val resultText = buildString {
                appendLine(buildAppliedBenchmarkSettingsLine(snapshot, effective.actualBackend, mtpStatus))
                if (snapshot.safeMaxTokensCap != null && snapshot.requestedMaxTokens != snapshot.settings.maxTokens) {
                    appendLine("설정값: 최대 출력 토큰=${snapshot.requestedMaxTokens}")
                    appendLine("적용값: 최대 출력 토큰=${snapshot.settings.maxTokens}")
                }
                if (snapshot.requestedKvCapacity != snapshot.settings.kvCacheCapacityTokens) {
                    appendLine("설정값: KV캐시=${snapshot.requestedKvCapacity} tokens")
                    appendLine("적용값: KV캐시=${snapshot.settings.kvCacheCapacityTokens} tokens (저메모리 조정)")
                }
                appendLine("모델 설정을 다시 적용했습니다.")
                appendLine("모델 설정 재적용 시간: ${engineReloadMs}ms")
                appendLine("모델 로딩 시간: 측정 불가")
                appendLine("첫 토큰 시간: ${firstTokenMs?.let { "${it}ms" } ?: "측정 불가"}")
                appendLine("총 생성 시간: ${"%.2f".format(Locale.US, totalMs / 1000.0)}s")
                appendLine("추정 출력 토큰 수: $estimatedTokens")
                appendLine("전체 기준 토큰 속도: ${"%.1f".format(Locale.US, totalTps)} tok/s")
                appendLine("디코딩 기준 토큰 속도: ${decodeTps?.let { "${"%.1f".format(Locale.US, it)} tok/s" } ?: "측정 불가"}")
                if (nativeStats != null) {
                    appendLine("네이티브 첫 토큰 시간: ${"%.2f".format(Locale.US, nativeStats.timeToFirstTokenSeconds)}s")
                    appendLine("네이티브 프리필: ${"%.1f".format(Locale.US, nativeStats.prefillTokensPerSecond)} tok/s · ${nativeStats.prefillTokenCount} tokens")
                    appendLine("네이티브 디코딩: ${"%.1f".format(Locale.US, nativeStats.decodeTokensPerSecond)} tok/s · ${nativeStats.decodeTokenCount} tokens")
                    appendLine("네이티브 초기화 시간: ${"%.2f".format(Locale.US, nativeStats.initTimeSeconds)}s")
                }
                appendLine("가속기: ${effective.actualBackend}")
                appendLine("MTP 가속: ${mtpStatus.toKoreanMtpStatus()}")
                // Phase 4: a successful benchmark that reached its result via a
                // fallback must display the localized fallback cause. Showing
                // only "가속기: CPU / MTP 가속: 비활성" hides the fact that the
                // requested GPU backend actually failed.
                outcomeSnapshot?.let { snap ->
                    val fallbackSummary = FallbackCauseFormatter.formatFallbackSummary(snap)
                    if (fallbackSummary.isNotBlank()) {
                        appendLine("요청 가속기: ${snapshot.settings.accelerator.name}")
                        appendLine("폴백: $fallbackSummary")
                    }
                }
                appendLine()
                appendLine("정확한 비교를 위해 MTP 꺼짐/켜짐을 번갈아 3회 이상 측정해 주세요.")
            }.trim()

onResult(resultText)
            onStatus("측정 결과를 저장하는 중입니다.")
            saveBenchmarkResult(
                context = context,
                benchmarkDao = benchmarkDao,
                snapshot = snapshot,
                mtpStatus = mtpStatus,
                modelLoadingMs = engineReloadMs,
                firstTokenLatencyMs = firstTokenMs,
                totalGenerationMs = totalMs,
                estimatedOutputTokens = estimatedTokens,
                totalTokensPerSecond = totalTps.toFloat(),
                decodeTokensPerSecond = decodeTps?.toFloat(),
                success = true,
                errorMessage = null,
                actualBackend = runtimeSelection?.actualTextBackend,
                runtimeSnapshot = outcomeSnapshot,
                nativeStats = nativeStats
            )
            Toast.makeText(context, "벤치마크 기록을 저장했습니다.", Toast.LENGTH_SHORT).show()
            Log.i("FusionBenchmark", "Benchmark success totalMs=$totalMs tokens=$estimatedTokens totalTps=$totalTps decodeTps=$decodeTps mtp=${mtpStatus.name} nativeTtft=${nativeStats?.timeToFirstTokenSeconds} nativeDecodeTps=${nativeStats?.decodeTokensPerSecond} nativePrefillTps=${nativeStats?.prefillTokensPerSecond}")
            DeveloperLogStore.record(context, "benchmark", "벤치마크 성공", "model=${snapshot.modelName}, decodeTps=${decodeTps?.toFloat()}")
                onStatus("벤치마크가 완료되었습니다.")
} finally {
                 FusionRuntimeManager.unloadSharedEngineAfterExclusive("benchmark_after_run")
                 engine.setNativeMinLogSeverity(LogSeverity.ERROR)
             }
         }
} catch (e: BenchmarkFailedException) {
        Log.e("FusionBenchmark", "Benchmark generation failed: ${e.reason}", e)
        Log.e("FusionEngine", "모델 설정을 적용할 수 없습니다.", e)
        DeveloperLogStore.record(context, "benchmark", "벤치마크 실패", e::class.java.simpleName)
        onStatus(null)
        val userMessage = benchmarkUserErrorMessage(e, snapshot)
        Toast.makeText(context, userMessage, Toast.LENGTH_SHORT).show()
        // Phase 2: when a generation-after-acquisition failureSnapshot exists,
        // use its real fingerprint and MTP status instead of fabricated
        // defaults. The failureSnapshot captures the loaded engine state
        // that existed when generation crashed, including the selected
        // backend, MTP runtime status, and model fingerprint.
        val attemptSnapshot = e.attemptSnapshot ?: RuntimeAttemptSnapshot(
            requestedAccelerator = snapshot.settings.accelerator,
            fallbackEvents = e.failureSnapshot?.fallbackEventsFromAcquisition ?: emptyList(),
            modelFingerprint = if (e.failureSnapshot != null) {
                e.failureSnapshot!!.modelFingerprint
            } else {
                com.projectnuke.fusion.llm.ModelFingerprintSummary(
                    canonicalPath = snapshot.modelPath.orEmpty(),
                    fileSize = 0L,
                    modifiedAt = 0L,
                    validationVersion = 0,
                    mtpSupported = false
                )
            },
            mtpRequested = snapshot.settings.speculativeDecodingEnabled == true
        )
        val mtpStatus = e.failureSnapshot?.mtpRuntimeStatus
            ?: attemptSnapshot.inferredMtpStatus()
        val actualBackend = e.failureSnapshot?.selectedTextBackend?.name
        // Phase 4: a failed benchmark must expose the typed attempted / applied /
        // failure state in the CURRENT result surface too, not only in history.
        onResult(
            buildBenchmarkFailureResultText(
                snapshot = snapshot,
                userMessage = userMessage,
                mtpStatus = mtpStatus,
                actualBackend = actualBackend,
                attemptSnapshot = attemptSnapshot,
                failureSnapshot = e.failureSnapshot,
            )
        )
        runCatching {
            saveBenchmarkResult(
                context = context,
                benchmarkDao = benchmarkDao,
                snapshot = snapshot,
                mtpStatus = mtpStatus,
                modelLoadingMs = null,
                firstTokenLatencyMs = null,
                totalGenerationMs = 0L,
                estimatedOutputTokens = 0,
                totalTokensPerSecond = 0f,
                decodeTokensPerSecond = null,
                success = false,
                errorMessage = sanitizeBenchmarkErrorMessage(e, snapshot),
                actualBackend = actualBackend,
                attemptSnapshot = attemptSnapshot,
                failureSnapshot = e.failureSnapshot
            )
        }.onFailure { saveError ->
            Log.e("FusionBenchmark", "Failed to save benchmark result", saveError)
            Toast.makeText(context, "벤치마크 기록을 저장할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: Exception) {
        Log.e("FusionBenchmark", "Benchmark generation failed", e)
        Log.e("FusionEngine", "모델 설정을 적용할 수 없습니다.", e)
        DeveloperLogStore.record(context, "benchmark", "벤치마크 실패", e::class.java.simpleName)
        onStatus(null)
        val userMessage = benchmarkUserErrorMessage(e, snapshot)
        Toast.makeText(context, userMessage, Toast.LENGTH_SHORT).show()
        // Generic-exception path: construct an attempt snapshot from the
        // requested profile. Never reread engine.lastMtpStatus or
        // engine.lastRuntimeSelection — they are stale for a failed attempt.
        val attemptSnapshot = RuntimeAttemptSnapshot(
            requestedAccelerator = snapshot.settings.accelerator,
            fallbackEvents = emptyList(),
            modelFingerprint = com.projectnuke.fusion.llm.ModelFingerprintSummary(
                canonicalPath = snapshot.modelPath.orEmpty(),
                fileSize = 0L,
                modifiedAt = 0L,
                validationVersion = 0,
                mtpSupported = false
            ),
            mtpRequested = snapshot.settings.speculativeDecodingEnabled == true
        )
        // Phase 4: expose the typed attempted state in the current result too.
        onResult(
            buildBenchmarkFailureResultText(
                snapshot = snapshot,
                userMessage = userMessage,
                mtpStatus = attemptSnapshot.inferredMtpStatus(),
                actualBackend = null,
                attemptSnapshot = attemptSnapshot,
                failureSnapshot = null,
            )
        )
        runCatching {
            saveBenchmarkResult(
                context = context,
                benchmarkDao = benchmarkDao,
                snapshot = snapshot,
                mtpStatus = attemptSnapshot.inferredMtpStatus(),
                modelLoadingMs = null,
                firstTokenLatencyMs = null,
                totalGenerationMs = 0L,
                estimatedOutputTokens = 0,
                totalTokensPerSecond = 0f,
                decodeTokensPerSecond = null,
                success = false,
                errorMessage = sanitizeBenchmarkErrorMessage(e, snapshot),
                actualBackend = null,
                attemptSnapshot = attemptSnapshot,
                failureSnapshot = null
            )
        }.onFailure { saveError ->
            Log.e("FusionBenchmark", "Failed to save benchmark result", saveError)
            Toast.makeText(context, "벤치마크 기록을 저장할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    } finally {
        FusionRuntimeManager.unloadSharedEngineWhenRuntimeIdle("benchmark_final_cleanup")
        onFinished()
    }
}

/**
 * Phase 4: builds the CURRENT-result text for a failed benchmark so the typed
 * attempted / applied / failure state is user-visible instead of only being
 * persisted to history. Uses formal Korean wording and the localized fallback
 * prose from [FallbackCauseFormatter] — never raw FallbackReason enum names or
 * JSON fallback-code arrays.
 */
private fun buildBenchmarkFailureResultText(
    snapshot: BenchmarkSnapshot,
    userMessage: String,
    mtpStatus: com.projectnuke.fusion.llm.MtpRuntimeStatus,
    actualBackend: String?,
    attemptSnapshot: RuntimeAttemptSnapshot?,
    failureSnapshot: RuntimeFailureSnapshot?,
): String = buildString {
    appendLine("벤치마크 실패")
    appendLine("모델: ${snapshot.modelName}")
    appendLine("사유: $userMessage")
    appendLine("요청 가속기: ${snapshot.settings.accelerator.name}")
    appendLine("요청 출력 토큰: ${snapshot.requestedMaxTokens}")
    appendLine("적용 출력 토큰: ${snapshot.settings.maxTokens}")
    appendLine("요청 KV캐시: ${snapshot.requestedKvCapacity} tokens")
    appendLine("적용 KV캐시: ${snapshot.settings.kvCacheCapacityTokens} tokens")
    appendLine("실제 백엔드: ${actualBackend ?: "적용된 백엔드 없음"}")
    appendLine("MTP 가속: ${mtpStatus.toKoreanMtpStatus()}")
    val fallbackSummary = when {
        failureSnapshot != null -> FallbackCauseFormatter.formatFailureSnapshotSummary(failureSnapshot)
        attemptSnapshot != null -> FallbackCauseFormatter.formatAttemptFallbackSummary(attemptSnapshot)
        else -> ""
    }
    if (fallbackSummary.isNotBlank()) {
        appendLine("폴백: $fallbackSummary")
    }
}.trim()

private suspend fun saveBenchmarkResult(
    context: Context,
    benchmarkDao: BenchmarkDao,
    snapshot: BenchmarkSnapshot,
    mtpStatus: MtpRuntimeStatus,
    modelLoadingMs: Long?,
    firstTokenLatencyMs: Long?,
    totalGenerationMs: Long,
    estimatedOutputTokens: Int,
    totalTokensPerSecond: Float,
    decodeTokensPerSecond: Float?,
    success: Boolean,
    errorMessage: String?,
    actualBackend: String?,
    runtimeSnapshot: com.projectnuke.fusion.llm.RuntimeExecutionSnapshot? = null,
    attemptSnapshot: com.projectnuke.fusion.llm.RuntimeAttemptSnapshot? = null,
    failureSnapshot: com.projectnuke.fusion.llm.RuntimeFailureSnapshot? = null,
    nativeStats: GenerationBenchmarkStats? = null
) {
    benchmarkDao.insert(
        buildBenchmarkResultEntityPayload(
            snapshot = snapshot,
            mtpStatus = mtpStatus,
            modelLoadingMs = modelLoadingMs,
            firstTokenLatencyMs = firstTokenLatencyMs,
            totalGenerationMs = totalGenerationMs,
            estimatedOutputTokens = estimatedOutputTokens,
            totalTokensPerSecond = totalTokensPerSecond,
            decodeTokensPerSecond = decodeTokensPerSecond,
            success = success,
            errorMessage = errorMessage,
            actualBackend = actualBackend,
            runtimeSnapshot = runtimeSnapshot,
            attemptSnapshot = attemptSnapshot,
            failureSnapshot = failureSnapshot,
            nativeStats = nativeStats,
            appVersion = context.appVersionName(),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        )
    )
}

/**
 * Builds a [BenchmarkResultEntity] from a runtime + native-stats snapshot.
 * Pure: does not touch the DAO or Context, so unit tests can exercise the
 * persistence round-trip without a Room runtime. The snapshot is the single
 * source of truth; the legacy `actualBackendString` is used only when the
 * snapshot is absent (preview / failure-before-init paths).
 */
internal fun buildBenchmarkResultEntityPayload(
    snapshot: BenchmarkSnapshot,
    mtpStatus: MtpRuntimeStatus,
    modelLoadingMs: Long?,
    firstTokenLatencyMs: Long?,
    totalGenerationMs: Long,
    estimatedOutputTokens: Int,
    totalTokensPerSecond: Float,
    decodeTokensPerSecond: Float?,
    success: Boolean,
    errorMessage: String?,
    actualBackend: String?,
    runtimeSnapshot: com.projectnuke.fusion.llm.RuntimeExecutionSnapshot? = null,
    attemptSnapshot: com.projectnuke.fusion.llm.RuntimeAttemptSnapshot? = null,
    failureSnapshot: com.projectnuke.fusion.llm.RuntimeFailureSnapshot? = null,
    nativeStats: GenerationBenchmarkStats? = null,
    appVersion: String?,
    deviceModel: String,
    androidVersion: String,
    createdAtMs: Long = System.currentTimeMillis()
): BenchmarkResultEntity {
    val selectedTextBackend = if (success) {
        runtimeSnapshot?.selectedTextBackend?.name ?: actualBackend
    } else if (failureSnapshot != null) {
        // Generation-after-acquisition failure: we have a real selected backend
        failureSnapshot.selectedTextBackend?.name
    } else {
        // For failed runs with attemptSnapshot only (acquisition failure), there's no selected backend
        // For legacy failed runs without attemptSnapshot, fall back to actualBackend
        if (attemptSnapshot != null) null else actualBackend
    }
    val selectedVisionBackend = if (success) {
        runtimeSnapshot?.selectedVisionBackend?.name
    } else if (failureSnapshot != null) {
        // Generation-after-acquisition failure: preserve vision backend
        failureSnapshot.selectedVisionBackend?.name
    } else {
        null
    }
    val samplerBackend = if (success) {
        runtimeSnapshot?.samplerBackend?.name ?: com.projectnuke.fusion.llm.RuntimeComponentBackend.UNKNOWN.name
    } else {
        com.projectnuke.fusion.llm.RuntimeComponentBackend.UNKNOWN.name
    }
    val fallbackEventCodes = if (success) {
        runtimeSnapshot?.let { FallbackCauseFormatter.formatCodes(it) }
    } else if (failureSnapshot != null) {
        // Generation-after-acquisition failure: use acquisition fallback events from failure snapshot
        FallbackCauseFormatter.formatCodes(failureSnapshot)
    } else {
        attemptSnapshot?.let { FallbackCauseFormatter.formatCodes(it) }
    }
    val mtpRequestedFromSnapshot = if (success) {
        runtimeSnapshot?.mtpRequested ?: (snapshot.settings.speculativeDecodingEnabled == true)
    } else if (failureSnapshot != null) {
        failureSnapshot.mtpRequested
    } else {
        attemptSnapshot?.mtpRequested ?: (snapshot.settings.speculativeDecodingEnabled == true)
    }
    val initializedWithMtp = if (success) {
        runtimeSnapshot?.mtpStatus == MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST
    } else if (failureSnapshot != null) {
        failureSnapshot.mtpRuntimeStatus == MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST
    } else {
        false
    }
    val mtpRuntimeStatus = if (success) {
        runtimeSnapshot?.mtpStatus
    } else if (failureSnapshot != null) {
        failureSnapshot.mtpRuntimeStatus
    } else {
        mtpStatus
    }
    return BenchmarkResultEntity(
        createdAt = createdAtMs,
        modelName = snapshot.modelName,
        modelPath = snapshot.modelPath,
        accelerator = snapshot.settings.accelerator.name,
        selectedTextBackend = selectedTextBackend,
        selectedVisionBackend = selectedVisionBackend,
        samplerBackend = samplerBackend,
        mtpRequested = mtpRequestedFromSnapshot,
        mtpStatus = mtpRuntimeStatus?.toKoreanMtpStatus() ?: MtpRuntimeStatus.OFF.toKoreanMtpStatus(),
        fallbackEventCodes = fallbackEventCodes,
        initializedWithMtp = initializedWithMtp,
        nativeTtftSeconds = nativeStats?.timeToFirstTokenSeconds,
        nativePrefillTokensPerSecond = nativeStats?.prefillTokensPerSecond,
        nativeDecodeTokensPerSecond = nativeStats?.decodeTokensPerSecond,
        nativePrefillTokenCount = nativeStats?.prefillTokenCount,
        nativeDecodeTokenCount = nativeStats?.decodeTokenCount,
        nativeInitTimeSeconds = nativeStats?.initTimeSeconds,
        maxTokens = snapshot.settings.maxTokens,
        temperature = snapshot.settings.temperature,
        topK = snapshot.settings.topK,
        topP = snapshot.settings.topP,
        reasoningEnabled = snapshot.reasoningEnabled,
        webSearchEnabled = snapshot.webSearchEnabled,
        promptLabel = FusionBenchmarkPromptLabel,
        promptText = FusionBenchmarkPrompt,
        modelLoadingMs = modelLoadingMs,
        firstTokenLatencyMs = firstTokenLatencyMs,
        totalGenerationMs = totalGenerationMs,
        estimatedOutputTokens = estimatedOutputTokens,
        totalTokensPerSecond = totalTokensPerSecond,
        decodeTokensPerSecond = decodeTokensPerSecond,
        success = success,
        errorMessage = errorMessage,
        appVersion = appVersion,
        deviceModel = deviceModel,
        androidVersion = androidVersion
    )
}

private fun looksLikeBenchmarkLiteRtFailure(@Suppress("UNUSED_PARAMETER") text: String): Boolean = false

private fun buildAppliedBenchmarkSettingsLine(
    snapshot: BenchmarkSnapshot,
    actualBackend: String?,
    mtpStatus: MtpRuntimeStatus
): String {
    val mtpText = if (snapshot.settings.speculativeDecodingEnabled == true) {
        "MTP ${mtpStatus.toKoreanMtpStatus()}"
    } else {
        "MTP 꺼짐"
    }
    return "적용된 설정: ${actualBackend ?: snapshot.settings.accelerator.name} · $mtpText · 최대 출력=${snapshot.settings.maxTokens} · 온도=${snapshot.settings.temperature} · Top-K=${snapshot.settings.topK} · Top-P=${snapshot.settings.topP}"
}

@Composable
private fun BenchmarkCardBlock(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = LocalFusionSemanticColors.current.menu, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

private fun loadBenchmarkSnapshot(context: Context, prefs: android.content.SharedPreferences): BenchmarkSnapshot {
    val modelName = prefs.getString("selected_model", "Gemma 4 E2B-it") ?: "Gemma 4 E2B-it"
    val selectedPath = prefs.getString("selected_model_path", null)
    val effectiveProfile = resolveEffectiveLocalModelProfile(context, prefs, modelName, selectedPath)
    val rawSettings = effectiveProfile.generationSettings
    val modelFamily = FusionModelCatalog.findByNameOrId(context, effectiveProfile.modelId)?.family
        ?: FusionModelCatalog.inferFamily(context, modelName)
    val safeCap = benchmarkSafeMaxTokensCap(context)
    val recommendedMaxTokens = FusionMemoryManager.recommendedBenchmarkMaxTokens(context, rawSettings.maxTokens)
    val isLowMemoryAdjustment = recommendedMaxTokens != rawSettings.maxTokens
    val effectiveKvCapacity = if (isLowMemoryAdjustment) {
        LocalGenerationSettingsPolicy.lowMemoryKvCapacity()
    } else {
        rawSettings.kvCacheCapacityTokens
    }
    val resolvedModelPath = resolveBenchmarkModelPath(context, modelName, selectedPath)
    val effectiveSettings = if (isLowMemoryAdjustment) {
        rawSettings.copy(
            maxTokens = recommendedMaxTokens,
            kvCacheCapacityTokens = effectiveKvCapacity,
            speculativeDecodingEnabled = resolvedModelPath?.let { path ->
                MtpPolicyProduction.resolveEffectiveMtpSetting(
                    modelPath = path,
                    settings = rawSettings
                )
            } ?: false
        )
    } else {
        rawSettings.copy(
            kvCacheCapacityTokens = effectiveKvCapacity,
            speculativeDecodingEnabled = resolvedModelPath?.let { path ->
                MtpPolicyProduction.resolveEffectiveMtpSetting(
                    modelPath = path,
                    settings = rawSettings
                )
            } ?: false
        )
    }
    return BenchmarkSnapshot(
        modelName = modelName,
        modelPath = resolveBenchmarkModelPath(context, modelName, selectedPath),
        settings = effectiveSettings,
        requestedMaxTokens = rawSettings.maxTokens,
        requestedKvCapacity = rawSettings.kvCacheCapacityTokens,
        safeMaxTokensCap = safeCap,
        reasoningEnabled = false,
        webSearchEnabled = false,
        modelFamily = modelFamily,
        modelIdentity = FusionModelCatalog.findByNameOrId(context, modelName)?.id
            ?: effectiveProfile.modelId,
    )
}

private fun benchmarkSafeMaxTokensCap(context: Context): Int? {
    val recommended = FusionMemoryManager.recommendedBenchmarkMaxTokens(context, Int.MAX_VALUE)
    return recommended.takeIf { it < Int.MAX_VALUE }
}

private fun benchmarkUserErrorMessage(
    throwable: Throwable,
    snapshot: BenchmarkSnapshot
): String {
    val message = throwable.message.orEmpty()
    return when {
        message.contains("memory", ignoreCase = true) ||
            message.contains("oom", ignoreCase = true) ||
            message.contains("OutOfMemory", ignoreCase = true) -> {
            "메모리가 부족하여 벤치마크를 중단했습니다."
        }
        snapshot.settings.accelerator != AcceleratorMode.CPU -> {
            "GPU 벤치마크 중 오류가 발생했습니다. CPU 또는 낮은 최대 출력 토큰으로 다시 시도해 주세요."
        }
        else -> {
            "모델을 불러올 수 없습니다. 모델 설정을 확인한 뒤 다시 시도해 주세요."
        }
    }
}

private fun sanitizeBenchmarkErrorMessage(
    throwable: Throwable,
    snapshot: BenchmarkSnapshot
): String {
    return when {
        throwable.message.orEmpty().contains("memory", ignoreCase = true) ||
            throwable.message.orEmpty().contains("oom", ignoreCase = true) ||
            throwable is OutOfMemoryError -> {
            "메모리가 부족하여 벤치마크를 중단했습니다."
        }
        snapshot.settings.accelerator != AcceleratorMode.CPU -> {
            "GPU 벤치마크 오류"
        }
        else -> {
            "모델 로딩 오류"
        }
    }.take(100)
}

private fun loadBenchmarkSettingsFromPrefs(prefs: android.content.SharedPreferences): GenerationSettings {
    // Phase 4: centralized settings policy replaces per-screen duplication.
    return LocalGenerationSettingsPolicy.fromPrefs(prefs)
}

private fun resolveBenchmarkModelPath(context: Context, modelName: String, selectedPath: String?): String? {
    if (!selectedPath.isNullOrBlank() && File(selectedPath).exists()) return selectedPath
    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
    val modelsDir = File(baseDir, "models")
    val fileName = when (modelName) {
        "Gemma 4 E4B-it" -> "gemma-4-E4B-it.litertlm"
        else -> "gemma-4-E2B-it.litertlm"
    }
    val file = File(modelsDir, fileName)
    return if (file.exists()) file.absolutePath else null
}

private fun initialBenchmarkMtpStatusLabel(context: Context, settings: GenerationSettings, modelName: String): String {
    val selectedPath = context.getSharedPreferences("fusion_chat_settings", Context.MODE_PRIVATE)
        .getString("selected_model_path", null)
    val resolvedModelPath = resolveBenchmarkModelPath(context, modelName, selectedPath)
    val requested = resolvedModelPath?.let { path ->
        MtpPolicyProduction.resolveEffectiveMtpSetting(
            modelPath = path,
            settings = settings
        )
    } ?: false
    if (!requested) return "꺼짐"
    val supported = resolvedModelPath?.let { MtpPolicyProduction.isMtpSupported(it) } ?: false
    return if (supported) "요청됨" else "미지원"
}

private fun estimateBenchmarkTokens(text: String): Int {
    val cleaned = text.trim()
    if (cleaned.isBlank()) return 0
    return (cleaned.length / 4.0).toInt().coerceAtLeast(1)
}

private fun Context.appVersionName(): String? {
    return runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        packageInfo.versionName
    }.getOrNull()
}

/**
 * Exception that wraps a benchmark failure with its attempt snapshot
 * so the snapshot can be preserved through the catch block to persistence.
 */
internal class BenchmarkFailedException(
    val reason: String,
    val attemptSnapshot: RuntimeAttemptSnapshot? = null,
    val failureSnapshot: RuntimeFailureSnapshot? = null
) : RuntimeException("Benchmark failed: $reason")
