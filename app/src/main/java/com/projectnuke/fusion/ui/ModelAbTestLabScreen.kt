package com.projectnuke.fusion.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.llm.ChatGenerationRunningException
import com.projectnuke.fusion.llm.FusionRuntimeLock
import com.projectnuke.fusion.llm.FusionRuntimeManager
import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.LiteRtLlmEngine
import com.projectnuke.fusion.llm.RuntimeAttemptSnapshot
import com.projectnuke.fusion.llm.inferredMtpStatus
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.LocalGenerationSettingsPolicy
import com.projectnuke.fusion.model.resolveEffectiveLocalModelProfile
import com.projectnuke.fusion.model.KvCacheCapacityPolicy
import com.projectnuke.fusion.model.toConversationOptions
import com.projectnuke.fusion.model.toRequestedEngineProfile
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.FusionModelProfiles
import com.projectnuke.fusion.modelzoo.FusionModelSpec
import com.projectnuke.fusion.modelzoo.ModelAvailability
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.util.FusionMemoryManager
import com.projectnuke.fusion.util.MtpPolicyProduction
import com.projectnuke.fusion.util.FallbackCauseFormatter
import com.projectnuke.fusion.util.toKoreanMtpStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

private val AbBg: Color
    @Composable get() = LocalFusionSemanticColors.current.background
private val AbCard: Color
    @Composable get() = LocalFusionSemanticColors.current.menu
private val AbPanel: Color
    @Composable get() = LocalFusionSemanticColors.current.panel
private val AbText: Color
    @Composable get() = LocalFusionSemanticColors.current.onSurface
private val AbSubtle: Color
    @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val AbAccent: Color
    @Composable get() = LocalFusionSemanticColors.current.primary
private val AbFail: Color
    @Composable get() = LocalFusionSemanticColors.current.error

internal fun abEditorMutationAllowed(isRunning: Boolean): Boolean = !isRunning

private data class AbModelOption(
    val spec: FusionModelSpec,
    val path: String
)

private data class AbTarget(
    val id: Long,
    val model: AbModelOption,
    val settings: GenerationSettings,
    private val configuredReasoningEnabled: Boolean,
) {
    val reasoningEnabled: Boolean
        get() = FusionModelCatalog.effectiveReasoningEnabled(
            model.spec,
            configuredReasoningEnabled,
        )
}

internal fun buildAbTestPromptMessages(
    modelFamily: ModelFamily,
    savedMemoryText: String?,
    prompt: String,
): List<ChatMessage> = buildList {
    add(ChatMessage("system", "FUSION_MODEL_FAMILY=${modelFamily.name}"))
    savedMemoryText?.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
    add(ChatMessage("user", prompt))
}

private data class AbResult(
    val targetLabel: String,
    val modelName: String,
    val modelId: String?,
    val settings: GenerationSettings,
    val reasoningEnabled: Boolean,
    val memoryEnabled: Boolean,
    val answer: String?,
    val firstTokenLatencyMs: Long?,
    val totalGenerationMs: Long,
    val estimatedTokens: Int,
    val totalTokensPerSecond: Double,
    val decodeTokensPerSecond: Double?,
    val createdAt: Long,
    val errorMessage: String? = null,
    /** Phase 8: immutable runtime snapshot captured from the Success outcome. */
    val runtimeSnapshot: com.projectnuke.fusion.llm.RuntimeExecutionSnapshot? = null,
    /** Phase 4: immutable attempt snapshot captured from the Failure outcome
     * when acquisition failed (engine was not selected). */
    val attemptSnapshot: com.projectnuke.fusion.llm.RuntimeAttemptSnapshot? = null,
    /** Phase B: immutable generation-after-acquisition failure snapshot
     * when an Engine was successfully selected but generation then crashed.
     * Carries requested accelerator, selected text/vision backend, MTP
     * runtime status, fallback events from acquisition, model fingerprint,
     * and the generation failure kind. Distinct from [attemptSnapshot]. */
    val failureSnapshot: com.projectnuke.fusion.llm.RuntimeFailureSnapshot? = null,
    /** Native benchmark stats captured from the Success outcome, when reported. */
    val nativeStats: com.projectnuke.fusion.llm.GenerationBenchmarkStats? = null
) {
    val succeeded: Boolean get() = errorMessage == null
}

@Composable
fun ModelAbTestLabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("fusion_chat_settings", Context.MODE_PRIVATE) }
    val engine = remember { FusionRuntimeManager.sharedEngine(context) }
    val selectedModel = prefs.getString("selected_model", "Gemma 4 E2B-it") ?: "Gemma 4 E2B-it"
    val selectedPath = prefs.getString("selected_model_path", null)
    val currentSettings = remember(selectedModel, selectedPath) {
        resolveEffectiveLocalModelProfile(context, prefs, selectedModel, selectedPath).generationSettings
    }
    val localModels = remember(selectedModel, selectedPath) {
        loadLocalAbModels(context, selectedModel, selectedPath)
    }
    val initialModel = localModels.firstOrNull { it.spec.displayName == selectedModel } ?: localModels.firstOrNull()
    var prompt by remember { mutableStateOf("") }
    var targets by remember(initialModel) {
        mutableStateOf(
            initialModel?.let {
                val effective = resolveEffectiveLocalModelProfile(context, prefs, it.spec.id, it.path)
                listOf(
                    AbTarget(1L, it, effective.generationSettings, effective.reasoningEnabled),
                    AbTarget(2L, it, recommendedAbSettings(context, it.spec), recommendedAbReasoning(context, it.spec))
                )
            } ?: emptyList()
        )
    }
    var results by remember { mutableStateOf<List<AbResult>>(emptyList()) }
    var unsavedSession by remember { mutableStateOf<StoredAbTestSession?>(null) }
    var savingRecord by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var modelPickerTargetId by remember { mutableStateOf<Long?>(null) }
    var showHistory by remember { mutableStateOf(false) }

        DisposableEffect(engine) {
            val unregister = FusionMemoryManager.registerIdleEngineUnloader {
                scope.launch {
                    FusionRuntimeManager.unloadSharedEngineWhenRuntimeIdle("ab_test_memory_pressure")
                }
            }
            onDispose {
                unregister()
                scope.launch {
                    FusionRuntimeManager.unloadSharedEngineWhenRuntimeIdle("ab_test_dispose")
                }
            }
        }

    BackHandler(enabled = !isRunning) { onBack() }
    BackHandler(enabled = isRunning) {
        Toast.makeText(context, "테스트 실행 중에는 뒤로 갈 수 없습니다. 완료 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
    }

    if (showHistory) {
        ModelAbTestHistoryScreen(onBack = { showHistory = false })
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AbBg).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("모델 A/B 테스트", color = AbText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("같은 입력을 여러 모델 또는 설정으로 실행해 결과를 비교합니다.", color = AbSubtle, fontSize = 12.sp)
                }
                TextButton(enabled = !isRunning, onClick = onBack) { Text("뒤로", color = AbText) }
            }
        }

        item {
            AbCardBlock {
                Text("최근 A/B 테스트", color = AbText, fontWeight = FontWeight.SemiBold)
                Text("A/B 테스트 기록에는 테스트 프롬프트와 생성 결과가 저장될 수 있습니다.", color = AbSubtle, fontSize = 12.sp)
                TextButton(enabled = !isRunning, onClick = { showHistory = true }) {
                    Text("기록 열기", color = AbAccent)
                }
            }
        }

        item {
            AbCardBlock {
                Text("테스트 프롬프트", color = AbText, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { if (abEditorMutationAllowed(isRunning)) prompt = it },
                    enabled = abEditorMutationAllowed(isRunning),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    placeholder = { Text("비교할 질문이나 지시문을 입력해 주세요.", color = AbSubtle) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AbAccent,
                        unfocusedBorderColor = AbPanel,
                        cursorColor = AbAccent,
                        focusedTextColor = AbText,
                        unfocusedTextColor = AbText
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(enabled = abEditorMutationAllowed(isRunning), onClick = { prompt = "" }) { Text("입력 지우기", color = AbSubtle) }
                    TextButton(enabled = abEditorMutationAllowed(isRunning), onClick = { prompt = "Fusion 앱의 다음 개선 방향을 제안해 주세요." }) {
                        Text("예시 입력", color = AbAccent)
                    }
                }
            }
        }

        item {
            AbCardBlock {
                Text("테스트 대상", color = AbText, fontWeight = FontWeight.SemiBold)
                Text("로컬에서 실행 가능한 모델만 표시합니다. 테스트는 순차적으로 실행됩니다.", color = AbSubtle, fontSize = 12.sp)
                Text(
                    "저장된 메모리: ${if (isSavedMemoryContextEnabled(prefs)) "현재 전역 설정을 따릅니다." else "사용 안 함"}",
                    color = AbSubtle,
                    fontSize = 12.sp
                )
            }
        }

        if (localModels.isEmpty()) {
            item {
                AbCardBlock {
                    Text("로컬 테스트에 사용할 수 있는 모델 파일이 없습니다.", color = AbFail)
                    Text("모델 라이브러리에서 실행 가능한 모델 파일을 먼저 가져와 주세요.", color = AbSubtle, fontSize = 12.sp)
                }
            }
        }

        items(targets, key = { it.id }) { target ->
            val label = targetLabel(targets.indexOf(target))
            item@ AbTargetCard(
                label = label,
                target = target,
                canRemove = targets.size > 2,
                onSelectModel = { modelPickerTargetId = target.id },
                onUseCurrentSettings = {
                    targets = targets.replaceTarget(target.id) {
                        it.copy(settings = currentSettings, configuredReasoningEnabled = prefs.getBoolean("reasoning_enabled", false))
                    }
                },
                onUseRecommendedSettings = {
                    targets = targets.replaceTarget(target.id) {
                        it.copy(
                            settings = recommendedAbSettings(context, it.model.spec),
                            configuredReasoningEnabled = recommendedAbReasoning(context, it.model.spec)
                        )
                    }
                },
                onCopyTarget = {
                    if (targets.size < 4) {
                        targets = targets + target.copy(id = (targets.maxOfOrNull { it.id } ?: 0L) + 1L)
                    } else {
                        Toast.makeText(context, "테스트 대상은 최대 4개까지 추가할 수 있습니다.", Toast.LENGTH_SHORT).show()
                    }
                },
                onRemove = { targets = targets.filterNot { it.id == target.id } },
                memoryEnabled = isSavedMemoryContextEnabled(prefs),
                enabled = abEditorMutationAllowed(isRunning),
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !isRunning,
                    onClick = {
                        val cleanPrompt = prompt.trim()
                        when {
                            cleanPrompt.isBlank() -> Toast.makeText(context, "테스트 프롬프트를 입력해 주세요.", Toast.LENGTH_SHORT).show()
                            targets.size < 2 -> Toast.makeText(context, "테스트 대상을 2개 이상 선택해 주세요.", Toast.LENGTH_SHORT).show()
                            FusionRuntimeLock.isChatGenerationRunning || FusionRuntimeLock.isBenchmarkRunning -> {
                                Toast.makeText(context, "현재 다른 응답 또는 벤치마크를 실행하는 중입니다.", Toast.LENGTH_SHORT).show()
                            }
                            FusionMemoryManager.shouldBlockBenchmark(context) -> {
                                Toast.makeText(context, "사용 가능한 메모리가 부족하여 테스트를 시작할 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                val snapshot = FusionMemoryManager.getMemorySnapshot(context)
                                if (snapshot.availMem < snapshot.threshold * 2) {
                                    Toast.makeText(context, "메모리 사용량이 높아 테스트가 느려질 수 있습니다.", Toast.LENGTH_SHORT).show()
                                }
                                isRunning = true
                                results = emptyList()
                                unsavedSession = null
                                status = "테스트를 준비하는 중입니다."
                                DeveloperLogStore.record(context, "ab_test", "A/B 테스트 시작", "targets=${targets.size}, models=${targets.joinToString { it.model.spec.displayName }}")
                                scope.launch {
                                    try {
                                        val completedResults = runAbTests(
                                            context = context,
                                            engine = engine,
                                            prefs = prefs,
                                            prompt = cleanPrompt,
                                            targets = targets,
                                            onStatus = { status = it },
                                            onResult = { results = results + it }
                                        )
                                        val historySaved = if (completedResults.isNotEmpty()) {
                                            val session = StoredAbTestSession(
                                                    id = "ab-${System.currentTimeMillis()}",
                                                    fullPrompt = cleanPrompt,
                                                    createdAt = System.currentTimeMillis(),
                                                    results = completedResults.map { it.toStoredResult() }
                                                )
                                            unsavedSession = session
                                            ModelAbTestHistoryStore.save(context, session).also { if (it) unsavedSession = null }
                                        } else true
                                        status = if (historySaved) "A/B 테스트가 완료되었습니다." else
                                            "A/B 실행은 완료했지만 기록을 저장하지 못했습니다. 결과를 복사하거나 기록 저장을 다시 시도해 주세요."
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: ChatGenerationRunningException) {
                                        status = "현재 응답을 생성하는 중입니다."
                                    } catch (e: Exception) {
                                        Log.e("FusionAbTest", "A/B test failed", e)
                                        DeveloperLogStore.record(context, "ab_test", "A/B 테스트 실패", e::class.java.simpleName)
                                        status = "테스트를 완료할 수 없습니다."
                                    } finally {
                                        isRunning = false
                                    }
                                }
                            }
                        }
                    }
                ) { Text("테스트 시작", color = if (isRunning) AbSubtle else AbAccent) }
            }
        }

        if (isRunning) {
            item {
                AbCardBlock {
                    Text(status ?: "테스트를 준비하는 중입니다.", color = AbText)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AbAccent, trackColor = AbPanel)
                }
            }
        } else {
            status?.let { item { AbCardBlock { Text(it, color = AbText) } } }
        }

        if (results.isNotEmpty()) {
            unsavedSession?.let { session ->
                item {
                    TextButton(enabled = !savingRecord && !isRunning, onClick = {
                        savingRecord = true
                        scope.launch {
                            try {
                                if (ModelAbTestHistoryStore.save(context, session)) {
                                    unsavedSession = null
                                    status = "A/B 기록을 저장했습니다."
                                } else {
                                    status = "A/B 기록을 저장하지 못했습니다. 결과는 이 화면에서 복사할 수 있습니다."
                                }
                            } finally { savingRecord = false }
                        }
                    }) { Text("기록 저장 다시 시도") }
                }
            }
            item { AbComparisonSummary(results) }
            items(results, key = { "${it.targetLabel}-${it.createdAt}" }) { result ->
                AbResultCard(
                    result = result,
                    onCopyAnswer = {
                        clipboard.setText(AnnotatedString(result.answer.orEmpty()))
                        Toast.makeText(context, "답변을 복사했습니다.", Toast.LENGTH_SHORT).show()
                    },
                    onCopySettings = {
                        clipboard.setText(AnnotatedString(result.settingsSummary()))
                        Toast.makeText(context, "설정을 복사했습니다.", Toast.LENGTH_SHORT).show()
                    },
                    onCopyResult = {
                        clipboard.setText(AnnotatedString(result.copyText()))
                        Toast.makeText(context, "결과를 복사했습니다.", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    modelPickerTargetId?.let { targetId ->
        AlertDialog(
            onDismissRequest = { modelPickerTargetId = null },
            title = { Text("모델 선택") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(localModels, key = { it.spec.id }) { option ->
                        TextButton(
                            enabled = abEditorMutationAllowed(isRunning),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                targets = targets.replaceTarget(targetId) { it.copy(model = option) }
                                modelPickerTargetId = null
                            }
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(option.spec.displayName, color = AbText)
                                Text(option.path, color = AbSubtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { modelPickerTargetId = null }) { Text("닫기", color = AbSubtle) }
            },
            containerColor = AbPanel,
            titleContentColor = AbText,
            textContentColor = AbText
        )
    }
}

@Composable
private fun AbTargetCard(
    label: String,
    target: AbTarget,
    canRemove: Boolean,
    onSelectModel: () -> Unit,
    onUseCurrentSettings: () -> Unit,
    onUseRecommendedSettings: () -> Unit,
    onCopyTarget: () -> Unit,
    onRemove: () -> Unit,
    memoryEnabled: Boolean,
    enabled: Boolean,
) {
    AbCardBlock {
        Text("대상 $label · ${target.model.spec.displayName}", color = AbText, fontWeight = FontWeight.SemiBold)
        Text(target.settingsSummary(), color = AbSubtle, fontSize = 12.sp)
        Text("저장된 메모리=${if (memoryEnabled) "사용" else "사용 안 함"}", color = AbSubtle, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = enabled, onClick = onSelectModel) { Text("모델 선택", color = AbAccent) }
            TextButton(enabled = enabled, onClick = onUseCurrentSettings) { Text("현재 설정 사용", color = AbText) }
            TextButton(enabled = enabled, onClick = onUseRecommendedSettings) { Text("권장 설정 사용", color = AbText) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = enabled, onClick = onCopyTarget) { Text("복사해서 비교 대상 만들기", color = AbText) }
            if (canRemove) TextButton(enabled = enabled, onClick = onRemove) { Text("삭제", color = AbFail) }
        }
    }
}

@Composable
private fun AbResultCard(
    result: AbResult,
    onCopyAnswer: () -> Unit,
    onCopySettings: () -> Unit,
    onCopyResult: () -> Unit
) {
    val currentLocale = LocalLocale.current.platformLocale
    AbCardBlock {
        Text("대상 ${result.targetLabel} · ${result.modelName}", color = AbText, fontWeight = FontWeight.SemiBold)
        Text("상태: ${if (result.succeeded) "성공" else "실패"} · ${SimpleDateFormat("yyyy-MM-dd HH:mm", currentLocale).format(Date(result.createdAt))}", color = AbSubtle, fontSize = 12.sp)
        Text(result.settingsSummary(), color = AbSubtle, fontSize = 12.sp)
        if (result.succeeded) {
            Text(result.answer.orEmpty(), color = AbText, fontSize = 13.sp)
            Text(
                "첫 토큰 ${result.firstTokenLatencyMs?.let { "${it}ms" } ?: "측정 불가"} · 총 ${result.totalGenerationMs}ms · 추정 ${result.estimatedTokens} tokens",
                color = AbSubtle,
                fontSize = 12.sp
            )
            Text(
                "전체 ${formatSpeed(result.totalTokensPerSecond)} · 디코딩 ${result.decodeTokensPerSecond?.let(::formatSpeed) ?: "측정 불가"}",
                color = AbSubtle,
                fontSize = 12.sp
            )
            // Phase 4: successful live cards must also show the applied runtime
            // (backend / MTP / localized fallback), not only the requested
            // settings — otherwise a GPU-requested run that fell back to CPU
            // silently claims success with no visible fallback cause.
            result.appliedRuntimeLine()?.let {
                Text(it, color = AbSubtle, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCopyAnswer) { Text("답변 복사", color = AbAccent) }
                TextButton(onClick = onCopySettings) { Text("설정 복사", color = AbText) }
                TextButton(onClick = onCopyResult) { Text("결과 복사", color = AbText) }
            }
        } else {
            Text("이 대상의 실행에 실패했습니다.", color = AbFail)
            Text(result.errorMessage.orEmpty(), color = AbSubtle, fontSize = 12.sp)
            result.appliedRuntimeLine()?.let {
                Text(it, color = AbSubtle, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AbComparisonSummary(results: List<AbResult>) {
    val successful = results.filter { it.succeeded }
    val fastestFirstToken = successful.filter { it.firstTokenLatencyMs != null }.minByOrNull { it.firstTokenLatencyMs ?: Long.MAX_VALUE }
    val fastestDecode = successful.filter { it.decodeTokensPerSecond != null }.maxByOrNull { it.decodeTokensPerSecond ?: 0.0 }
    val shortest = successful.minByOrNull { it.answer.orEmpty().length }
    val longest = successful.maxByOrNull { it.answer.orEmpty().length }
    val failures = results.count { !it.succeeded }
    AbCardBlock {
        Text("비교 요약", color = AbText, fontWeight = FontWeight.SemiBold)
        Text("가장 빠른 응답 시작: ${fastestFirstToken?.targetLabel ?: "측정 불가"}", color = AbSubtle, fontSize = 12.sp)
        Text("가장 높은 디코딩 속도: ${fastestDecode?.targetLabel ?: "측정 불가"}", color = AbSubtle, fontSize = 12.sp)
        Text("가장 짧은 답변: ${shortest?.targetLabel ?: "측정 불가"} · 가장 긴 답변: ${longest?.targetLabel ?: "측정 불가"}", color = AbSubtle, fontSize = 12.sp)
        Text("실패한 대상: ${if (failures == 0) "없음" else "${failures}개"}", color = AbSubtle, fontSize = 12.sp)
    }
}

private suspend fun runAbTests(
    context: Context,
    engine: LiteRtLlmEngine,
    prefs: android.content.SharedPreferences,
    prompt: String,
    targets: List<AbTarget>,
    onStatus: (String) -> Unit,
    onResult: (AbResult) -> Unit
): List<AbResult> {
    val completedResults = mutableListOf<AbResult>()
    FusionRuntimeLock.withExclusiveBenchmark(
        onPrepareExclusiveMode = {
            onStatus("모델 리소스를 정리하는 중입니다.")
            FusionRuntimeLock.requestChatEngineUnloadForBenchmark()
            FusionRuntimeManager.unloadSharedEngineAfterExclusive("ab_test_prepare")
        }
    ) {
        targets.forEachIndexed { index, target ->
            val label = targetLabel(index)
            onStatus("$label 실행 중...")
            runCatching {
                FusionRuntimeManager.unloadSharedEngineAfterExclusive("ab_test_target_prepare")
                delay(250L)
                runSingleAbTarget(context, engine, prefs, prompt, label, target)
            }.onSuccess { result ->
                completedResults += result
                onResult(result)
                DeveloperLogStore.record(
                    context,
                    "ab_test",
                    "A/B 대상 완료",
                    "target=$label, model=${target.model.spec.displayName}, success=${result.succeeded}, totalMs=${result.totalGenerationMs}"
                )
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) {
                    throw error
                }
                Log.e("FusionAbTest", "Target $label failed unexpectedly", error)
                val failedResult = AbResult(
                        targetLabel = label,
                        modelName = target.model.spec.displayName,
                        modelId = target.model.spec.id,
                        settings = target.settings.copy(
                            speculativeDecodingEnabled = MtpPolicyProduction.resolveEffectiveMtpSetting(
                                modelPath = target.model.path,
                                settings = target.settings
                            )
                        ),
                        reasoningEnabled = target.reasoningEnabled,
                        memoryEnabled = isSavedMemoryContextEnabled(prefs),
                        answer = null,
                        firstTokenLatencyMs = null,
                        totalGenerationMs = 0L,
                        estimatedTokens = 0,
                        totalTokensPerSecond = 0.0,
                        decodeTokensPerSecond = null,
                        createdAt = System.currentTimeMillis(),
                        errorMessage = safeAbErrorMessage(error)
                    )
                completedResults += failedResult
                onResult(failedResult)
                DeveloperLogStore.record(context, "ab_test", "A/B 대상 예외", "target=$label, model=${target.model.spec.displayName}, error=${error::class.java.simpleName}")
            }
        }
    }
    FusionRuntimeManager.unloadSharedEngineWhenRuntimeIdle("ab_test_complete")
    DeveloperLogStore.record(context, "ab_test", "A/B 테스트 완료", "targets=${targets.size}")
    return completedResults
}

private suspend fun runSingleAbTarget(
    context: Context,
    engine: LiteRtLlmEngine,
    prefs: android.content.SharedPreferences,
    prompt: String,
    label: String,
    target: AbTarget
): AbResult {
    if (!File(target.model.path).exists()) {
        return AbResult(
            targetLabel = label,
            modelName = target.model.spec.displayName,
            modelId = target.model.spec.id,
            settings = target.settings,
            reasoningEnabled = target.reasoningEnabled,
            memoryEnabled = isSavedMemoryContextEnabled(prefs),
            answer = null,
            firstTokenLatencyMs = null,
            totalGenerationMs = 0L,
            estimatedTokens = 0,
            totalTokensPerSecond = 0.0,
            decodeTokensPerSecond = null,
            createdAt = System.currentTimeMillis(),
            errorMessage = "모델 파일을 찾을 수 없습니다."
        )
    }
    val resolvedSettings = target.settings.copy(
        speculativeDecodingEnabled = MtpPolicyProduction.resolveEffectiveMtpSetting(
            modelPath = target.model.path,
            settings = target.settings
        )
    )
    val memoryText = buildSavedMemoryContext(
        context = context,
        prefs = prefs,
        currentConversationId = null,
        currentModelId = target.model.spec.id,
        globalPreviewOnly = false
    ).text
    val prepared = com.projectnuke.fusion.chat.LocalPromptPreparation.prepare(
        rawMessages = buildAbTestPromptMessages(
            modelFamily = target.model.spec.family,
            savedMemoryText = memoryText,
            prompt = prompt,
        ),
        budget = com.projectnuke.fusion.chat.FinalPromptBudget(
            modelId = target.model.spec.id,
            external = false,
            maxOutputTokens = resolvedSettings.maxTokens,
            engineKvCapacityTokens = resolvedSettings.kvCacheCapacityTokens,
        ),
        settings = resolvedSettings,
    )
    if (prepared == null) {
        return AbResult(
            targetLabel = label,
            modelName = target.model.spec.displayName,
            modelId = target.model.spec.id,
            settings = resolvedSettings,
            reasoningEnabled = target.reasoningEnabled,
            memoryEnabled = isSavedMemoryContextEnabled(prefs),
            answer = null,
            firstTokenLatencyMs = null,
            totalGenerationMs = 0L,
            estimatedTokens = 0,
            totalTokensPerSecond = 0.0,
            decodeTokensPerSecond = null,
            createdAt = System.currentTimeMillis(),
            errorMessage = "대화 내용이 너무 길어 실행할 수 없습니다. 프롬프트를 줄여 주세요."
        )
    }
    val output = StringBuilder()
    val startedAt = SystemClock.elapsedRealtime()
    var firstTokenLatencyMs: Long? = null
    val outcome = engine.generateStreaming(
        messages = prepared.messages,
        profile = resolvedSettings.toRequestedEngineProfile(
            modelPath = target.model.path,
            enableVisionBackend = false,
            modelIdentity = target.model.spec.id,
        ),
        options = prepared.conversationOptions,
        onToken = { token ->
            if (firstTokenLatencyMs == null && token.isNotEmpty()) {
                firstTokenLatencyMs = SystemClock.elapsedRealtime() - startedAt
            }
            output.append(token)
        }
    )
    val totalMs = SystemClock.elapsedRealtime() - startedAt
    return when (outcome) {
        is GenerationOutcome.Success -> {
            val runtimeSnapshot = outcome.snapshot
            val nativeStats = outcome.stats
            val answer = sanitizeAbAnswer(outcome.text)
            if (answer.isBlank()) {
                AbResult(
                    targetLabel = label,
                    modelName = target.model.spec.displayName,
                    modelId = target.model.spec.id,
                    settings = resolvedSettings,
                    reasoningEnabled = target.reasoningEnabled,
                    memoryEnabled = isSavedMemoryContextEnabled(prefs),
                    answer = null,
                    firstTokenLatencyMs = firstTokenLatencyMs,
                    totalGenerationMs = totalMs,
                    estimatedTokens = 0,
                    totalTokensPerSecond = 0.0,
                    decodeTokensPerSecond = null,
                    createdAt = System.currentTimeMillis(),
                    errorMessage = "빈 응답을 받았습니다.",
                    runtimeSnapshot = runtimeSnapshot,
                    nativeStats = nativeStats
                )
            } else {
                val estimatedTokens = estimateAbTokens(answer)
                val totalTps = if (totalMs > 0) estimatedTokens * 1000.0 / totalMs else 0.0
                val decodeMs = firstTokenLatencyMs?.let { totalMs - it }?.takeIf { it > 0 }
                AbResult(
                    targetLabel = label,
                    modelName = target.model.spec.displayName,
                    modelId = target.model.spec.id,
                    settings = resolvedSettings,
                    reasoningEnabled = target.reasoningEnabled,
                    memoryEnabled = isSavedMemoryContextEnabled(prefs),
                    answer = answer,
                    firstTokenLatencyMs = firstTokenLatencyMs,
                    totalGenerationMs = totalMs,
                    estimatedTokens = estimatedTokens,
                    totalTokensPerSecond = totalTps,
                    decodeTokensPerSecond = decodeMs?.let { estimatedTokens * 1000.0 / it },
                    createdAt = System.currentTimeMillis(),
                    runtimeSnapshot = runtimeSnapshot,
                    nativeStats = nativeStats
                )
            }
        }
        is GenerationOutcome.Cancelled -> {
            AbResult(
                targetLabel = label,
                modelName = target.model.spec.displayName,
                modelId = target.model.spec.id,
                settings = resolvedSettings,
                reasoningEnabled = target.reasoningEnabled,
                memoryEnabled = isSavedMemoryContextEnabled(prefs),
                answer = null,
                firstTokenLatencyMs = firstTokenLatencyMs,
                totalGenerationMs = totalMs,
                estimatedTokens = 0,
                totalTokensPerSecond = 0.0,
                decodeTokensPerSecond = null,
                createdAt = System.currentTimeMillis(),
                errorMessage = "생성이 취소되었습니다.",
                attemptSnapshot = null
            )
        }
        GenerationOutcome.Empty -> {
            AbResult(
                targetLabel = label,
                modelName = target.model.spec.displayName,
                modelId = target.model.spec.id,
                settings = resolvedSettings,
                reasoningEnabled = target.reasoningEnabled,
                memoryEnabled = isSavedMemoryContextEnabled(prefs),
                answer = null,
                firstTokenLatencyMs = firstTokenLatencyMs,
                totalGenerationMs = totalMs,
                estimatedTokens = 0,
                totalTokensPerSecond = 0.0,
                decodeTokensPerSecond = null,
                createdAt = System.currentTimeMillis(),
                errorMessage = "빈 모델 응답을 받았습니다.",
                attemptSnapshot = null
            )
        }
        is GenerationOutcome.Failure -> {
            val attemptSnapshot = outcome.attemptSnapshot
            val failureSnapshot = outcome.failureSnapshot
            AbResult(
                targetLabel = label,
                modelName = target.model.spec.displayName,
                modelId = target.model.spec.id,
                settings = resolvedSettings,
                reasoningEnabled = target.reasoningEnabled,
                memoryEnabled = isSavedMemoryContextEnabled(prefs),
                answer = null,
                firstTokenLatencyMs = firstTokenLatencyMs,
                totalGenerationMs = totalMs,
                estimatedTokens = 0,
                totalTokensPerSecond = 0.0,
                decodeTokensPerSecond = null,
                createdAt = System.currentTimeMillis(),
                errorMessage = outcome.message,
                attemptSnapshot = attemptSnapshot,
                failureSnapshot = failureSnapshot
            )
        }
    }
}

private fun loadLocalAbModels(context: Context, selectedModel: String, selectedPath: String?): List<AbModelOption> {
    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
    val modelsDir = File(baseDir, "models")
    return FusionModelCatalog.all(context)
        .asSequence()
        .filter { spec ->
            spec.canRunLocalRuntime &&
                spec.availability != ModelAvailability.REMOTE_ONLY &&
                spec.availability != ModelAvailability.UNSUPPORTED_ON_DEVICE &&
                spec.availability != ModelAvailability.NEEDS_CONVERSION
        }
        .mapNotNull { spec ->
            val path = when {
                spec.displayName == selectedModel && !selectedPath.isNullOrBlank() && File(selectedPath).exists() -> selectedPath
                !spec.localPath.isNullOrBlank() && File(spec.localPath).exists() -> spec.localPath
                !spec.fileName.isNullOrBlank() && File(modelsDir, spec.fileName).exists() -> File(modelsDir, spec.fileName).absolutePath
                else -> null
            }
            path?.let { AbModelOption(spec, it) }
        }
        .distinctBy { it.spec.id }
        .toList()
}

private fun loadAbSettings(prefs: android.content.SharedPreferences): GenerationSettings {
    // Phase 4: centralized settings policy replaces per-screen duplication.
    return LocalGenerationSettingsPolicy.fromPrefs(prefs)
}

private fun recommendedAbSettings(context: Context, spec: FusionModelSpec): GenerationSettings {
    return FusionModelProfiles.recommended(context, spec)?.settings ?: GenerationSettings(maxTokens = spec.recommendedMaxTokens8Gb.coerceAtLeast(1024))
}

private fun recommendedAbReasoning(context: Context, spec: FusionModelSpec): Boolean {
    return FusionModelProfiles.recommended(context, spec)?.reasoningEnabled ?: false
}

private fun List<AbTarget>.replaceTarget(id: Long, transform: (AbTarget) -> AbTarget): List<AbTarget> {
    return map { if (it.id == id) transform(it) else it }
}

private fun targetLabel(index: Int): String = ('A'.code + index).toChar().toString()

private fun AbTarget.settingsSummary(): String {
    return "가속기=${settings.accelerator.name} · 최대 출력=${settings.maxTokens} · 온도=${settings.temperature} · Top-K=${settings.topK} · Top-P=${settings.topP} · MTP=${if (settings.speculativeDecodingEnabled == true) "켜짐" else "꺼짐"} · 추론=${if (reasoningEnabled) "켜짐" else "꺼짐"}"
}

private fun AbResult.settingsSummary(): String {
    return "가속기=${settings.accelerator.name} · 최대 출력=${settings.maxTokens} · 온도=${settings.temperature} · Top-K=${settings.topK} · Top-P=${settings.topP} · MTP=${if (settings.speculativeDecodingEnabled == true) "켜짐" else "꺼짐"} · 추론=${if (reasoningEnabled) "켜짐" else "꺼짐"}"
}

/**
 * Phase 8: distinguish requested from applied runtime in the copied result
 * text. Requested reads from [GenerationSettings] (what the user asked for);
 * applied reads from [AbResult.runtimeSnapshot] (what the runtime did),
 * so the persisted/copied result never claims GPU+MTP merely because it was
 * requested.
 */
private fun AbResult.appliedRuntimeLine(): String? {
    // Priority: success snapshot > failure snapshot (acquisition succeeded,
    // generation crashed; the running engine's state is in the failure
    // snapshot) > attempt snapshot (acquisition failed; no selected backend).
    // Phase C3: MTP status is rendered as Korean via toKoreanMtpStatus;
    // never expose raw enum names (INITIALIZED_WITH_MTP_REQUEST,
    // FALLBACK_DISABLED) in user-facing displays.
    runtimeSnapshot?.let { snap ->
        val backend = snap.selectedTextBackend.name
        val mtpState = snap.mtpStatus.toKoreanMtpStatus()
        val fallbackSummary = FallbackCauseFormatter.formatFallbackSummary(snap)
        var result = "적용: $backend · MTP $mtpState"
        if (fallbackSummary.isNotBlank()) {
            result += " · 폴백: $fallbackSummary"
        }
        return result + (snap.selectedVisionBackend?.let { " · 비전 $it" } ?: "")
    }
    failureSnapshot?.let { snap ->
        val backend = snap.selectedTextBackend.name
        val mtpState = snap.mtpRuntimeStatus.toKoreanMtpStatus()
        val fallbackSummary = FallbackCauseFormatter.formatEvents(snap.fallbackEventsFromAcquisition)
        var result = "적용: $backend · MTP $mtpState"
        if (fallbackSummary.isNotBlank()) {
            result += " · 폴백: $fallbackSummary"
        }
        return result + (snap.selectedVisionBackend?.let { " · 비전 $it" } ?: "")
    }
    // Phase 4: acquisition failures carry only an attemptSnapshot (no engine
    // was selected, so there is no applied backend). Still render a formal
    // Korean attempted/failure line so the user is not left with a bare error
    // message. MTP status comes from the snapshot's inferred status, and the
    // fallback prose is localized — never raw enum names or JSON arrays.
    attemptSnapshot?.let { snap ->
        val mtpState = snap.inferredMtpStatus().toKoreanMtpStatus()
        val fallbackSummary = FallbackCauseFormatter.formatAttemptFallbackSummary(snap)
        var result = "시도: 적용된 백엔드 없음 · MTP $mtpState"
        if (fallbackSummary.isNotBlank()) {
            result += " · 폴백: $fallbackSummary"
        }
        return result
    }
    return null
}

private fun AbResult.copyText(): String = buildString {
    appendLine("Fusion Model A/B Test")
    appendLine("대상: $targetLabel")
    appendLine("모델: $modelName")
    appendLine("요청: ${settingsSummary()}")
    appliedRuntimeLine()?.let { appendLine(it) }
    attemptSnapshot?.let { snap ->
        val backend = snap.modelFingerprint.canonicalPath
        val events = FallbackCauseFormatter.formatAttemptFallbackSummary(snap)
        if (events.isNotBlank()) appendLine("폴백: $events")
    }
    appendLine("시간: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))}")
    if (succeeded) {
        appendLine("첫 토큰 시간: ${firstTokenLatencyMs?.let { "${it}ms" } ?: "측정 불가"}")
        appendLine("총 생성 시간: ${totalGenerationMs}ms")
        appendLine("추정 출력 토큰 수: $estimatedTokens")
        appendLine("전체 기준 토큰 속도: ${formatSpeed(totalTokensPerSecond)}")
        appendLine("디코딩 기준 토큰 속도: ${decodeTokensPerSecond?.let(::formatSpeed) ?: "측정 불가"}")
        appendLine()
        appendLine(answer.orEmpty())
    } else {
        appendLine("실패: ${errorMessage.orEmpty()}")
    }
}.trim()

private fun AbResult.toStoredResult(): StoredAbTestResult {
    val snapshot = runtimeSnapshot
    val attemptSnap = attemptSnapshot
    val failureSnap = failureSnapshot
    // Machine-readable fallback event codes: a JSON array of stable
    // FallbackReason.name values. Never store localized Korean prose (e.g.
    // format/formatFallbackSummary output) in a code field — those are only
    // for on-screen rendering. See Repair Phase C2.
    val fallbackCodes = when {
        succeeded -> snapshot?.let { FallbackCauseFormatter.formatCodes(it) }
        failureSnap != null -> FallbackCauseFormatter.formatCodes(failureSnap)
        attemptSnap != null -> FallbackCauseFormatter.formatCodes(attemptSnap)
        else -> null
    }
    // Resolve model fingerprint fields from whichever snapshot captured them.
    // Priority: success snapshot > failure snapshot > attempt snapshot.
    val fpPath = snapshot?.modelFingerprint?.canonicalPath
        ?: failureSnap?.modelFingerprint?.canonicalPath
        ?: attemptSnap?.modelFingerprint?.canonicalPath
    val fpSize = snapshot?.modelFingerprint?.fileSize
        ?: failureSnap?.modelFingerprint?.fileSize
        ?: attemptSnap?.modelFingerprint?.fileSize
    val fpModifiedAt = snapshot?.modelFingerprint?.modifiedAt
        ?: failureSnap?.modelFingerprint?.modifiedAt
        ?: attemptSnap?.modelFingerprint?.modifiedAt
    val fpValidationVersion = snapshot?.modelFingerprint?.validationVersion
        ?: failureSnap?.modelFingerprint?.validationVersion
        ?: attemptSnap?.modelFingerprint?.validationVersion
    val fpMtpSupported = snapshot?.modelFingerprint?.mtpSupported
        ?: failureSnap?.modelFingerprint?.mtpSupported
        ?: attemptSnap?.modelFingerprint?.mtpSupported
    return StoredAbTestResult(
        targetLabel = targetLabel,
        modelName = modelName,
        modelId = modelId,
        accelerator = settings.accelerator.name,
        maxTokens = settings.maxTokens,
        temperature = settings.temperature,
        topK = settings.topK,
        topP = settings.topP,
        mtpEnabled = settings.speculativeDecodingEnabled == true,
        reasoningEnabled = reasoningEnabled,
        memoryEnabled = memoryEnabled,
        answer = answer,
        success = succeeded,
        errorSummary = errorMessage,
        firstTokenLatencyMs = firstTokenLatencyMs,
        totalGenerationTimeMs = totalGenerationMs,
        estimatedTokens = estimatedTokens,
        totalTokensPerSecond = totalTokensPerSecond,
        decodeTokensPerSecond = decodeTokensPerSecond,
        selectedTextBackend = snapshot?.selectedTextBackend?.name
            ?: failureSnap?.selectedTextBackend?.name,
        selectedVisionBackend = snapshot?.selectedVisionBackend?.name
            ?: failureSnap?.selectedVisionBackend?.name,
        mtpRuntimeStatus = snapshot?.mtpStatus?.name
            ?: failureSnap?.mtpRuntimeStatus?.name,
        fallbackEventCodes = fallbackCodes,
        nativeTtftSeconds = nativeStats?.timeToFirstTokenSeconds,
        nativePrefillTokensPerSecond = nativeStats?.prefillTokensPerSecond,
        nativeDecodeTokensPerSecond = nativeStats?.decodeTokensPerSecond,
        modelFingerprintPath = fpPath,
        modelFingerprintSize = fpSize,
        modelFingerprintModifiedAt = fpModifiedAt,
        modelFingerprintValidationVersion = fpValidationVersion,
        modelFingerprintMtpSupported = fpMtpSupported,
        generationFailureKind = failureSnap?.failureKind?.name
    )
}

private fun sanitizeAbAnswer(raw: String): String {
    return raw
        .replace(Regex("""(?is)<fusion_metrics>.*?</fusion_metrics>"""), "")
        .replace(Regex("""(?is)<fusion_thinking>.*?</fusion_thinking>"""), "")
        .replace(Regex("""(?is)<fusion_answer>(.*?)</fusion_answer>"""), "$1")
        .replace(Regex("""(?is)<fusion_attachment_v2>.*?</fusion_attachment_v2>"""), "")
        .replace(Regex("""(?is)<fusion_attachment>.*?</fusion_attachment>"""), "")
        .trim()
}

private fun estimateAbTokens(text: String): Int = (text.length / 4.0).toInt().coerceAtLeast(1)

private fun safeAbErrorMessage(error: Throwable): String {
    val message = error.message.orEmpty()
    return if (message.contains("memory", ignoreCase = true) || message.contains("oom", ignoreCase = true)) {
        "메모리가 부족하여 이 대상의 실행을 중단했습니다."
    } else {
        "모델 파일과 실행 설정을 확인해 주세요."
    }
}

private fun formatSpeed(value: Double): String = String.format(Locale.US, "%.1f tok/s", value)

@Composable
private fun AbCardBlock(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(8.dp), color = AbCard, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
