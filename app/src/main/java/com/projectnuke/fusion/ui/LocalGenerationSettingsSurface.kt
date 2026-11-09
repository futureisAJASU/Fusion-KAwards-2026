package com.projectnuke.fusion.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.chat.FinalPromptBudgeter
import com.projectnuke.fusion.chat.ManualSearchQuery
import com.projectnuke.fusion.chat.ManualSearchQueryIssue
import com.projectnuke.fusion.chat.ManualSearchQueryValidation
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.chat.SearchStrategySettings
import com.projectnuke.fusion.chat.SearchStrategyPreference
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.KvCacheCapacityPolicy
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.model.LocalContextMode
import com.projectnuke.fusion.model.LocalGenerationSettingBounds
import com.projectnuke.fusion.model.LocalGenerationFloatSettings
import com.projectnuke.fusion.model.LocalSettingsEditState
import com.projectnuke.fusion.model.LocalSettingsEditValues
import com.projectnuke.fusion.model.LocalSettingsScope
import com.projectnuke.fusion.model.ReasoningEffortPreset
import com.projectnuke.fusion.model.RuntimeCpuDeviceSignals
import com.projectnuke.fusion.model.RuntimeCpuThreadPolicy
import com.projectnuke.fusion.model.RuntimePerformanceMode
import com.projectnuke.fusion.model.RuntimePerformancePreference
import com.projectnuke.fusion.model.applyReasoningEffort
import com.projectnuke.fusion.model.detectReasoningEffort
import com.projectnuke.fusion.model.resolveLocalAutoKvCapacityPreview
import com.projectnuke.fusion.model.shouldDeleteModelOverride
import com.projectnuke.fusion.model.toSelection
import com.projectnuke.fusion.search.SearchStrategyPresentationPolicy
import com.projectnuke.fusion.search.WebSearchProviderRepository
import com.projectnuke.fusion.search.WebSearchProviderSettingsSection
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import com.projectnuke.fusion.util.MtpPolicyProduction
import kotlin.math.roundToInt
import java.util.Locale

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    val displayTitle = if (title.contains("MTP")) "MTP" else title
    val displaySubtitle = settingExplanation(displayTitle) ?: subtitle
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = displayTitle,
                color = if (enabled) TextPrimary else TextSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = displaySubtitle,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onToggle() },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = displayTitle },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedTrackColor = LocalFusionSemanticColors.current.divider,
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }

}

@Composable
private fun SearchStrategySettingsSection(
    preference: SearchStrategyPreference,
    onPreferenceChange: (SearchStrategyPreference) -> Unit,
    manualQuery: String,
    onManualQueryChange: (String) -> Unit,
) {
    val choices = listOf(
        Triple(
            SearchStrategy.AGENT_HARNESS,
            SearchStrategyPresentationPolicy.choiceTitle(SearchStrategy.AGENT_HARNESS),
            SearchStrategyPresentationPolicy.choiceDescription(SearchStrategy.AGENT_HARNESS),
        ),
        Triple(
            SearchStrategy.LEGACY_RULE_BASED,
            SearchStrategyPresentationPolicy.choiceTitle(SearchStrategy.LEGACY_RULE_BASED),
            SearchStrategyPresentationPolicy.choiceDescription(SearchStrategy.LEGACY_RULE_BASED),
        ),
        Triple(
            SearchStrategy.MANUAL_QUERY,
            SearchStrategyPresentationPolicy.choiceTitle(SearchStrategy.MANUAL_QUERY),
            SearchStrategyPresentationPolicy.choiceDescription(SearchStrategy.MANUAL_QUERY),
        ),
    )

    fun select(strategy: SearchStrategy, fallback: Boolean = preference.allowLegacyFallback) {
            onPreferenceChange(preference.copy(
                strategy = strategy,
                allowLegacyFallback = fallback,
                recoveredFromCorruption = false,
            ))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("웹 검색 방식", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(
            "현재 선택: ${choices.first { it.first == preference.strategy }.second}",
            color = TextSecondary,
            fontSize = 12.sp,
        )
        Text(
            "방식 선택은 웹 검색 권한을 켜지 않습니다. 위 웹 검색 설정과 요청별 권한이 허용될 때만 검색합니다.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        choices.forEach { (strategy, title, description) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { select(strategy) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = preference.strategy == strategy,
                    onClick = { select(strategy) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(description, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
        SettingSwitchRow(
            title = "AI Search Agent 실패 시 Classic Search로 자동 전환",
            subtitle = "개인정보 거부, 권한 취소, 취소 또는 요청 소유권 상실에는 적용되지 않습니다.",
            checked = preference.allowLegacyFallback,
            onToggle = { select(preference.strategy, !preference.allowLegacyFallback) },
        )
        if (preference.strategy == SearchStrategy.MANUAL_QUERY) {
            val validation = ManualSearchQuery.parse(manualQuery)
            val validationMessage = when (validation) {
                ManualSearchQueryValidation.Missing -> "직접 검색어를 입력하세요. 예: 삼성전자 005930 KRX 현재가"
                is ManualSearchQueryValidation.Valid -> "현재 요청에서 웹 검색이 허용될 때만 이 검색어를 한 번 사용합니다."
                is ManualSearchQueryValidation.Invalid -> when (validation.issue) {
                    ManualSearchQueryIssue.EMPTY -> "검색어를 입력하세요."
                    ManualSearchQueryIssue.TOO_LONG -> "검색어는 ${ManualSearchQuery.MAX_CHARS}자 이하여야 합니다."
                    ManualSearchQueryIssue.CONTROL_CHARACTERS -> "줄바꿈과 제어 문자는 사용할 수 없습니다."
                    ManualSearchQueryIssue.NO_SEARCH_TERMS -> "문자 또는 숫자를 포함한 검색어를 입력하세요."
                }
            }
            OutlinedTextField(
                value = manualQuery,
                onValueChange = { onManualQueryChange(it.take(ManualSearchQuery.MAX_CHARS + 1)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Manual Search Query") },
                placeholder = { Text("삼성전자 005930 KRX 현재가") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                isError = validation is ManualSearchQueryValidation.Invalid,
                supportingText = {
                    Text(
                        validationMessage,
                        color = if (validation is ManualSearchQueryValidation.Invalid) MaterialTheme.colorScheme.error else TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                },
            )
            Text(
                "채팅 원문과 검색어는 별도로 유지되며, 이 입력은 전역 설정에 저장되지 않습니다.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        if (preference.recoveredFromCorruption) {
            Text(
                "저장된 검색 설정을 읽을 수 없어 Classic Search를 사용합니다.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

private fun settingExplanation(title: String): String? {
    return when (title) {
        REASONING_BUDGET_TITLE -> reasoningBudgetExplanation()
        "최대 출력 토큰" -> "\ub2f5\ubcc0\uc758 \ucd5c\ub300 \uae38\uc774\ub97c \uc81c\ud55c\ud569\ub2c8\ub2e4. \uac12\uc774 \ud074\uc218\ub85d \uae34 \ub2f5\ubcc0\uc744 \uc0dd\uc131\ud560 \uc218 \uc788\uc9c0\ub9cc \uba54\ubaa8\ub9ac \uc0ac\uc6a9\ub7c9\uacfc \uc0dd\uc131 \uc2dc\uac04\uc774 \uc99d\uac00\ud569\ub2c8\ub2e4."
        "온도" -> "\ub2f5\ubcc0\uc758 \ubb34\uc791\uc704\uc131\uacfc \ucc3d\uc758\uc131\uc744 \uc870\uc808\ud569\ub2c8\ub2e4. \ub0ae\uc744\uc218\ub85d \uc548\uc815\uc801\uc774\uace0, \ub192\uc744\uc218\ub85d \ub2e4\uc591\ud558\uc9c0\ub9cc \ubd80\uc815\ud655\ud574\uc9c8 \uc218 \uc788\uc2b5\ub2c8\ub2e4."
        "Top-K" -> "\ub2e4\uc74c \ud1a0\ud070 \ud6c4\ubcf4\ub97c \uc0c1\uc704 \uba87 \uac1c\uae4c\uc9c0 \uace0\ub824\ud560\uc9c0 \uc815\ud569\ub2c8\ub2e4. \ub0ae\uc744\uc218\ub85d \uc548\uc815\uc801\uc774\uace0, \ub192\uc744\uc218\ub85d \ud45c\ud604\uc774 \ub2e4\uc591\ud574\uc9c8 \uc218 \uc788\uc2b5\ub2c8\ub2e4."
        "Top-P" -> "\ub204\uc801 \ud655\ub960 \uae30\uc900\uc73c\ub85c \ud6c4\ubcf4 \ud1a0\ud070 \ubc94\uc704\ub97c \uc81c\ud55c\ud569\ub2c8\ub2e4. \ub0ae\uc744\uc218\ub85d \ubcf4\uc218\uc801\uc774\uace0, \ub192\uc744\uc218\ub85d \ub2e4\uc591\ud55c \ud45c\ud604\uc744 \uc0ac\uc6a9\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4."
        "MTP" -> "\uc5ec\ub7ec \ud1a0\ud070\uc744 \ubbf8\ub9ac \uc608\uce21\ud574 \uc18d\ub3c4\ub97c \ub192\uc774\ub294 \uc2e4\ud5d8\uc801 \uac00\uc18d \uae30\ub2a5\uc785\ub2c8\ub2e4. \uae30\uae30\uc640 \ubaa8\ub378\uc5d0 \ub530\ub77c \ub354 \ub290\ub824\uc9c8 \uc218 \uc788\uc2b5\ub2c8\ub2e4."
        "추론" -> "\ub2f5\ubcc0\uc744 \uc0dd\uc131\ud558\uae30 \uc804\uc5d0 \ub354 \uad6c\uc870\uc801\uc73c\ub85c \uc0dd\uac01\ud558\ub3c4\ub85d \uc720\ub3c4\ud569\ub2c8\ub2e4. \ud488\uc9c8\uc774 \uc88b\uc544\uc9c8 \uc218 \uc788\uc9c0\ub9cc \uc0dd\uc131 \uc2dc\uac04\uc774 \ub298\uc5b4\ub0a9\ub2c8\ub2e4."
        "웹 검색" -> "\ucd5c\uc2e0 \uc815\ubcf4\uac00 \ud544\uc694\ud55c \uc9c8\ubb38\uc5d0\uc11c \uc778\ud130\ub137 \uac80\uc0c9 \uacb0\uacfc\ub97c \ucc38\uace0\ud569\ub2c8\ub2e4. \uac80\uc0c9 \uacb0\uacfc\uac00 \uae38\uba74 \uc751\ub2f5 \uc2dc\uac04\uc774 \ub298\uc5b4\ub0a0 \uc218 \uc788\uc2b5\ub2c8\ub2e4."
else -> null
    }
}
@Composable
private fun AdvancedSettingsEntry(
    settings: GenerationSettings,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        color = LocalFusionSemanticColors.current.bubble
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = "고급 설정",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "최대 출력 ${settings.maxTokens} · Top-K ${settings.topK} · Top-P ${
                    LocalGenerationFloatSettings.formatTopP(settings.topP)
                } · 온도 ${LocalGenerationFloatSettings.formatTemperature(settings.temperature)}",
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "${settings.accelerator.name} · 추론 ${settings.reasoningBudgetTokens} · MTP ${
                    when (settings.speculativeDecodingEnabled) {
                        true -> "On"
                        false -> "Off"
                        null -> "Auto"
                    }
                }",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }

}

private val MenuBg: Color
    @Composable get() = LocalFusionSemanticColors.current.menu

internal enum class AdvancedSettingsApplyResult {
    APPLIED,
    FAILED_BEFORE_PERSISTENCE,
    PARTIALLY_APPLIED,
}

@Composable
internal fun AdvancedSettingsDialog(
    settings: GenerationSettings,
    selectedModel: String,
    selectedModelPath: String?,
    profileId: String,
    initialEditState: LocalSettingsEditState,
    reasoningEnabled: Boolean,
    reasoningEnabledAvailable: Boolean,
    webSearchEnabled: Boolean,
    runtimePerformancePreference: RuntimePerformancePreference,
    runtimeCpuDeviceSignals: RuntimeCpuDeviceSignals,
    webSearchProviderRepository: WebSearchProviderRepository,
    searchStrategySettings: SearchStrategySettings,
    manualSearchQuery: String,
    onManualSearchQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onApply: (GenerationSettings, Boolean, Boolean, LocalSettingsScope, LocalSettingsEditValues, Boolean, RuntimePerformancePreference, SearchStrategyPreference) -> AdvancedSettingsApplyResult
) {
    val dialogContext = LocalContext.current
    val semantic = LocalFusionSemanticColors.current
    var partialApplyWarning by remember(profileId) { mutableStateOf(false) }
    var maxTokens by remember(settings) {
        mutableStateOf(settings.maxTokens.coerceIn(1, 32000))
    }
    var topK by remember(settings) {
        mutableStateOf(LocalGenerationSettingBounds.clampTopK(settings.topK))
    }
    var topP by remember(settings) {
        mutableStateOf(LocalGenerationFloatSettings.topPOrDefault(settings.topP))
    }
    var temperature by remember(settings) {
        mutableStateOf(LocalGenerationFloatSettings.temperatureOrDefault(settings.temperature))
    }
    var reasoningBudget by remember(settings) {
        mutableStateOf(LocalGenerationSettingBounds.clampReasoningBudget(settings.reasoningBudgetTokens))
    }
    var accelerator by remember(settings) {
        mutableStateOf(
            if (settings.accelerator == AcceleratorMode.CPU) {
                AcceleratorMode.CPU
            } else {
                AcceleratorMode.GPU
            }
        )
    }
    var speculativeDecodingEnabled by remember(settings, selectedModelPath) {
        mutableStateOf(
            settings.speculativeDecodingEnabled
                ?: selectedModelPath?.let { path ->
                    MtpPolicyProduction.getDefaultMtpSetting(
                        modelPath = path,
                        accelerator = settings.accelerator
                    )
                } ?: false
        )
    }
    var speculativeDecodingTouched by remember(settings, selectedModelPath) {
        mutableStateOf(settings.speculativeDecodingEnabled != null)
    }
    var maxTokensText by remember(settings) { mutableStateOf(maxTokens.toString()) }
    var topKText by remember(settings) { mutableStateOf(topK.toString()) }
    var topPText by remember(settings) { mutableStateOf(LocalGenerationFloatSettings.formatTopP(topP)) }
    var temperatureText by remember(settings) { mutableStateOf(LocalGenerationFloatSettings.formatTemperature(temperature)) }
    var floatInputError by remember(settings) { mutableStateOf(false) }
    var reasoningBudgetText by remember(settings) { mutableStateOf(reasoningBudget.toString()) }
    var reasoningEnabledLocal by remember(reasoningEnabled) { mutableStateOf(reasoningEnabled) }
    var webSearchEnabledLocal by remember(webSearchEnabled) { mutableStateOf(webSearchEnabled) }
    var searchPreferenceDraft by remember(searchStrategySettings) { mutableStateOf(searchStrategySettings.read()) }
    var manualQueryDraft by remember { mutableStateOf(manualSearchQuery) }
    var runtimePerformance by remember(profileId, runtimePerformancePreference) {
        mutableStateOf(runtimePerformancePreference.normalized())
    }
    var scope by remember(profileId) { mutableStateOf(LocalSettingsScope.MODEL) }
    var contextMode by remember(initialEditState) { mutableStateOf(initialEditState.buffer.model.contextMode) }
    var manualKvCapacity by remember(settings, profileId) {
        mutableStateOf(initialEditState.buffer.model.manualKvCapacityTokens.coerceIn(KvCacheCapacityPolicy.MIN_CAPACITY, KvCacheCapacityPolicy.MAX_CAPACITY))
    }
    var manualKvCapacityText by remember(initialEditState) { mutableStateOf(manualKvCapacity.toString()) }
    var compactEnabled by remember(initialEditState) { mutableStateOf(initialEditState.buffer.model.compactPolicy.enabled) }
    var compactTrigger by remember(initialEditState) { mutableStateOf(initialEditState.buffer.model.compactPolicy.triggerPercent) }
    var compactTriggerText by remember(initialEditState) { mutableStateOf(compactTrigger.toString()) }
    var recentHistoryPercent by remember(initialEditState) { mutableStateOf(initialEditState.buffer.model.compactPolicy.recentHistoryPercent) }
    var recentHistoryPercentText by remember(initialEditState) { mutableStateOf(recentHistoryPercent.toString()) }
    var resetModelOverride by remember(profileId, initialEditState) { mutableStateOf(false) }
    var modelEditedAfterReset by remember(profileId, initialEditState) { mutableStateOf(false) }
    var editBuffer by remember(profileId, initialEditState) {
        mutableStateOf(initialEditState.buffer)
    }

    fun markModelEdit() {
        if (scope == LocalSettingsScope.MODEL && resetModelOverride) {
            resetModelOverride = false
            modelEditedAfterReset = true
        }
    }

    fun currentAutoKvCapacity(): Int = resolveLocalAutoKvCapacityPreview(
        context = dialogContext,
        selectedModel = selectedModel,
        selectedModelPath = selectedModelPath,
        maxOutputTokens = maxTokens,
        scope = scope,
    )

    fun currentEditValues(): LocalSettingsEditValues = LocalSettingsEditValues(
        settings = settings.copy(
            maxTokens = maxTokens,
            topK = topK,
            topP = topP,
            temperature = temperature,
            reasoningBudgetTokens = reasoningBudget,
            accelerator = accelerator,
            speculativeDecodingEnabled = if (speculativeDecodingTouched) speculativeDecodingEnabled else null,
            kvCacheCapacityTokens = manualKvCapacity,
        ),
        // Preserve the stored preference for unsupported families; the
        // effective request policy disables it without rewriting the value.
        reasoningEnabled = if (reasoningEnabledAvailable) {
            reasoningEnabledLocal
        } else {
            initialEditState.buffer.model.reasoningEnabled
        },
        contextMode = contextMode,
        manualKvCapacityTokens = manualKvCapacity,
        compactPolicy = LocalCompactPolicy(compactEnabled, compactTrigger, recentHistoryPercent),
        effectiveAutoKvCapacityTokens = currentAutoKvCapacity(),
    )

    fun loadEditValues(value: LocalSettingsEditValues) {
        maxTokens = value.settings.maxTokens
        maxTokensText = maxTokens.toString()
        topK = LocalGenerationSettingBounds.clampTopK(value.settings.topK)
        topKText = topK.toString()
        topP = LocalGenerationFloatSettings.topPOrDefault(value.settings.topP)
        topPText = LocalGenerationFloatSettings.formatTopP(topP)
        temperature = LocalGenerationFloatSettings.temperatureOrDefault(value.settings.temperature)
        temperatureText = LocalGenerationFloatSettings.formatTemperature(temperature)
        floatInputError = false
        reasoningBudget = LocalGenerationSettingBounds.clampReasoningBudget(value.settings.reasoningBudgetTokens)
        reasoningBudgetText = reasoningBudget.toString()
        accelerator = value.settings.accelerator
        speculativeDecodingEnabled = value.settings.speculativeDecodingEnabled ?: false
        speculativeDecodingTouched = value.settings.speculativeDecodingEnabled != null
        reasoningEnabledLocal = value.reasoningEnabled
        contextMode = value.contextMode
        manualKvCapacity = value.manualKvCapacityTokens.coerceIn(KvCacheCapacityPolicy.MIN_CAPACITY, KvCacheCapacityPolicy.MAX_CAPACITY)
        manualKvCapacityText = manualKvCapacity.toString()
        compactEnabled = value.compactPolicy.enabled
        compactTrigger = value.compactPolicy.triggerPercent
        compactTriggerText = compactTrigger.toString()
        recentHistoryPercent = value.compactPolicy.recentHistoryPercent
        recentHistoryPercentText = recentHistoryPercent.toString()
    }

    fun switchScope(next: LocalSettingsScope) {
        editBuffer = editBuffer.update(scope, currentEditValues())
        scope = next
        loadEditValues(editBuffer.active(next))
    }

    val performanceResolution = RuntimeCpuThreadPolicy.resolve(runtimePerformance, runtimeCpuDeviceSignals)
    val appliedSummaryLines = buildAdvancedSettingsAppliedSummary(
        selectedModel = selectedModel,
        accelerator = accelerator,
        mtpEnabled = speculativeDecodingEnabled,
        maxTokensText = maxTokensText,
        contextMode = contextMode,
        contextCapacityText = if (contextMode == LocalContextMode.AUTO) {
            currentAutoKvCapacity().toString()
        } else {
            manualKvCapacityText
        },
        temperatureText = temperatureText,
        topKText = topKText,
        topPText = topPText,
        compactEnabled = compactEnabled,
        compactTriggerText = compactTriggerText,
        recentHistoryText = recentHistoryPercentText,
        reasoningEnabled = reasoningEnabledLocal,
        webSearchEnabled = webSearchEnabledLocal,
    ) + buildRuntimePerformanceSummaryLine(runtimePerformance, performanceResolution.threadCount, accelerator)

    FusionReadingDialog(
        onDismissRequest = onDismiss,
        containerColor = MenuBg,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        title = {
            Text(
                text = "고급 설정",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Normal
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                if (partialApplyWarning) {
                    Text(
                        "일부 변경 사항이 저장되었거나 저장 상태를 확인할 수 없습니다. 다시 적용하거나 닫기를 선택해 주세요.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("\uc801\uc6a9 \ub300\uc0c1", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(selectedModel, color = TextSecondary, fontSize = 12.sp)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .border(1.dp, semantic.divider, RoundedCornerShape(999.dp))
                    ) {
                        AcceleratorSegment(
                            text = "\uc774 \ubaa8\ub378",
                            selected = scope == LocalSettingsScope.MODEL,
                            onClick = { switchScope(LocalSettingsScope.MODEL) },
                        )
                        AcceleratorSegment(
                            text = "\ubaa8\ub4e0 \ub85c\uceec \ubaa8\ub378",
                            selected = scope == LocalSettingsScope.GLOBAL,
                            onClick = { switchScope(LocalSettingsScope.GLOBAL) },
                        )
                    }
                    if (scope == LocalSettingsScope.GLOBAL) {
                        Text(
                            "\ubaa8\ub378\ubcc4 \uc124\uc815\uc774 \uc788\ub294 \ubaa8\ub378\uc5d0\ub294 \ud574\ub2f9 \uc124\uc815\uc774 \uc6b0\uc120 \uc801\uc6a9\ub429\ub2c8\ub2e4.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    } else {
                        if (initialEditState.hasExplicitModelOverride || resetModelOverride) {
                            TextButton(onClick = {
                                resetModelOverride = true
                                modelEditedAfterReset = false
                                loadEditValues(initialEditState.inheritedModel)
                            }) {
                                Text("\uc774 \ubaa8\ub378 \uc124\uc815 \ucd08\uae30\ud654", color = AccentBlue)
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "\ucd94\ucc9c \uc124\uc815", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            markModelEdit(); maxTokens = 1024; maxTokensText = "1024"; temperature = 0.7f; temperatureText = "0.70"; topK = 40; topKText = "40"; topP = 0.9f; topPText = "0.90"; reasoningEnabledLocal = false; speculativeDecodingEnabled = false; speculativeDecodingTouched = true
                        }) { Text("\uc800\uba54\ubaa8\ub9ac", color = TextPrimary) }
                        TextButton(onClick = {
                            markModelEdit(); maxTokens = 1024; maxTokensText = "1024"; temperature = 0.7f; temperatureText = "0.70"; topK = 40; topKText = "40"; topP = 0.9f; topPText = "0.90"; reasoningEnabledLocal = false; speculativeDecodingEnabled = false; speculativeDecodingTouched = true
                        }) { Text("\ube60\ub978 \uc751\ub2f5", color = TextPrimary) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            markModelEdit(); maxTokens = 2048; maxTokensText = "2048"; temperature = 0.4f; temperatureText = "0.40"; topK = 40; topKText = "40"; topP = 0.9f; topPText = "0.90"; reasoningEnabledLocal = true; speculativeDecodingEnabled = false; speculativeDecodingTouched = true
                        }) { Text("\uc815\ud655\ud55c \ub2f5\ubcc0", color = TextPrimary) }
                        TextButton(onClick = {
                            markModelEdit(); maxTokens = 4096; maxTokensText = "4096"; temperature = 0.7f; temperatureText = "0.70"; topK = 64; topKText = "64"; topP = 0.95f; topPText = "0.95"; reasoningEnabledLocal = false
                        }) { Text("\uae34 \uc124\uba85", color = TextPrimary) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            markModelEdit(); maxTokens = 2048; maxTokensText = "2048"; temperature = 1.1f; temperatureText = "1.10"; topK = 80; topKText = "80"; topP = 0.98f; topPText = "0.98"; reasoningEnabledLocal = false; speculativeDecodingEnabled = false; speculativeDecodingTouched = true
                        }) { Text("\ucc3d\uc758\uc801 \ub2f5\ubcc0", color = TextPrimary) }
                        TextButton(onClick = {
                            markModelEdit(); maxTokens = 1024; maxTokensText = "1024"; temperature = 0.7f; temperatureText = "0.70"; topK = 40; topKText = "40"; topP = 0.9f; topPText = "0.90"; reasoningEnabledLocal = false; webSearchEnabledLocal = false; speculativeDecodingEnabled = false; speculativeDecodingTouched = true
                        }) { Text("\ubca4\uce58\ub9c8\ud06c \uc548\uc804", color = TextPrimary) }
                    }
                    Text(
                        text = "\uc815\ud655\ud55c \ube44\uad50\ub97c \uc704\ud574 \uac19\uc740 \uc870\uac74\uc5d0\uc11c 3\ud68c \uc774\uc0c1 \uce21\uc815\ud574 \uc8fc\uc138\uc694.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }

                Surface(shape = RoundedCornerShape(12.dp), color = PanelBg, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("\ud604\uc7ac \uc801\uc6a9\uac12", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        appliedSummaryLines.forEach { line ->
                            Text(line, color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "\uc0dd\uc131 \uc124\uc815",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(64 to "64", 256 to "256", 1024 to "1024", 2048 to "2048").forEach { (value, label) ->
                            TextButton(
                                onClick = {
                                    markModelEdit()
                                    maxTokens = value
                                    maxTokensText = label
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    label,
                                    color = if (maxTokens == value) AccentBlue else TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = if (maxTokens == value) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    IntSliderField(
                        title = "최대 출력 토큰",
                        value = maxTokens,
                        valueText = maxTokensText,
                        min = 1,
                        max = 32000,
                        onSliderChange = { newValue ->
                            markModelEdit()
                            maxTokens = newValue
                            maxTokensText = newValue.toString()
                        },
                        onTextChange = { text ->
                            markModelEdit()
                            maxTokensText = text
                            text.toIntOrNull()?.let { parsed ->
                                maxTokens = parsed.coerceIn(1, 32000)
                            }
                        }
                    )

                    IntSliderField(
                        title = "Top-K",
                    value = topK,
                    valueText = topKText,
                    min = LocalGenerationSettingBounds.TOP_K_MIN,
                    max = LocalGenerationSettingBounds.TOP_K_MAX,
                    onSliderChange = { newValue ->
                        markModelEdit()
                        topK = newValue
                        topKText = newValue.toString()
                    },
                    onTextChange = { text ->
                        markModelEdit()
                        topKText = text
                        topK = LocalGenerationSettingBounds.parseTopK(text, topK)
                    }
                )

                FloatSliderField(
                    title = "Top-P",
                    value = topP,
                    valueText = topPText,
                    min = 0f,
                    max = 1f,
                    onSliderChange = { newValue ->
                        markModelEdit()
                        topP = newValue
                        topPText = LocalGenerationFloatSettings.formatTopP(newValue)
                        floatInputError = false
                    },
                    onTextChange = { text ->
                        markModelEdit()
                        topPText = text
                        LocalGenerationFloatSettings.parseTopP(text)?.let { topP = it }
                        floatInputError = false
                    }
                )

                FloatSliderField(
                    title = "온도",
                    value = temperature,
                    valueText = temperatureText,
                    min = 0f,
                    max = 2f,
                    onSliderChange = { newValue ->
                        markModelEdit()
                        temperature = newValue
                        temperatureText = LocalGenerationFloatSettings.formatTemperature(newValue)
                        floatInputError = false
                    },
                    onTextChange = { text ->
                        markModelEdit()
                        temperatureText = text
                        LocalGenerationFloatSettings.parseTemperature(text)?.let { temperature = it }
                        floatInputError = false
                    }
                )

                if (floatInputError) {
                    Text(
                        "Top-P(0–1)와 온도(0–2)는 유한한 숫자로 입력해 주세요.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }

                IntSliderField(
                    title = "추론 예산(권장)",
                    value = reasoningBudget,
                    valueText = reasoningBudgetText,
                    min = LocalGenerationSettingBounds.REASONING_BUDGET_MIN,
                    max = LocalGenerationSettingBounds.REASONING_BUDGET_MAX,
                    onSliderChange = { newValue ->
                        markModelEdit()
                        reasoningBudget = newValue
                        reasoningBudgetText = newValue.toString()
                    },
                    onTextChange = { text ->
                        markModelEdit()
                        reasoningBudgetText = text
                        reasoningBudget = LocalGenerationSettingBounds.parseReasoningBudget(text, reasoningBudget)
                    }
                )
                if (reasoningEnabledAvailable) {
                    val reasoningSelection = detectReasoningEffort(currentEditValues())
                    Text("추론 수준", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    ReasoningEffortPreset.entries.chunked(2).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowPresets.forEach { preset ->
                                Box(modifier = Modifier.weight(1f)) {
                                    StableSegmentedOption(
                                        text = preset.label,
                                        selected = reasoningSelection == preset.toSelection(),
                                        onClick = {
                                            markModelEdit()
                                            applyReasoningEffort(
                                                currentEditValues(),
                                                preset,
                                                supportsReasoning = reasoningEnabledAvailable,
                                            )
                                                ?.let(::loadEditValues)
                                        },
                                    )
                                }
                            }
                            if (rowPresets.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    if (reasoningSelection == com.projectnuke.fusion.model.ReasoningEffortSelection.CUSTOM) {
                        Text(
                            "현재: 사용자 설정 · $reasoningBudget",
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\ucee8\ud14d\uc2a4\ud2b8", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "\uc785\ub825\uacfc \ucd9c\ub825\uc5d0 \ud568\uaed8 \uc0ac\uc6a9\ud558\ub294 \ub85c\uceec \ubaa8\ub378\uc758 \ud1a0\ud070 \uc6a9\ub7c9\uc785\ub2c8\ub2e4.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .border(1.dp, semantic.divider, RoundedCornerShape(999.dp))
                    ) {
                        AcceleratorSegment("\uc790\ub3d9", contextMode == LocalContextMode.AUTO) { markModelEdit(); contextMode = LocalContextMode.AUTO }
                        AcceleratorSegment("\uc9c1\uc811 \uc124\uc815", contextMode == LocalContextMode.MANUAL) { markModelEdit(); contextMode = LocalContextMode.MANUAL }
                    }
                    if (contextMode == LocalContextMode.AUTO) {
                        Text(
                            "\ud604\uc7ac \uc790\ub3d9 \uc801\uc6a9\uac12: ${currentAutoKvCapacity()} \ud1a0\ub070",
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    } else {
                        IntSliderField(
                            title = "\ucee8\ud14d\uc2a4\ud2b8 \uc6a9\ub7c9",
                            value = manualKvCapacity,
                            valueText = manualKvCapacityText,
                            min = KvCacheCapacityPolicy.MIN_CAPACITY,
                            max = KvCacheCapacityPolicy.MAX_CAPACITY,
                            onSliderChange = { value -> markModelEdit(); manualKvCapacity = value; manualKvCapacityText = value.toString() },
                            onTextChange = { text ->
                                markModelEdit()
                                manualKvCapacityText = text
                                text.toIntOrNull()?.let { manualKvCapacity = it.coerceIn(KvCacheCapacityPolicy.MIN_CAPACITY, KvCacheCapacityPolicy.MAX_CAPACITY) }
                            },
                        )
                        if (FinalPromptBudgeter.manualCapacityMayReduceOutput(manualKvCapacity, maxTokens)) {
                            Text("\ud604\uc7ac \ucd9c\ub825 \ud55c\ub3c4\uc5d0 \ub530\ub77c \uc2e4\uc81c \ucd9c\ub825 \ud1a0\ud070 \uc218\uac00 \uc904\uc5b4\ub4e4 \uc218 \uc788\uc2b5\ub2c8\ub2e4.", color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
                SettingSwitchRow(
                    title = "\uc790\ub3d9 \uc555\ucd95",
                    subtitle = "\ucee8\ud14d\uc2a4\ud2b8\uac00 \ubd80\uc871\ud574\uc9c0\uae30 \uc804\uc5d0 \uc624\ub798\ub41c \ub300\ud654\ub97c \uc694\uc57d\ud558\uc5ec \ucd5c\uadfc \ub300\ud654\ub97c \ub354 \uc624\ub798 \uc720\uc9c0\ud569\ub2c8\ub2e4.",
                    checked = compactEnabled,
                    onToggle = { markModelEdit(); compactEnabled = !compactEnabled },
                )
                if (compactEnabled) {
                    IntSliderField("\uc555\ucd95 \uc2dc\uc791", compactTrigger, compactTriggerText, 60, 95, { value -> markModelEdit(); compactTrigger = value; compactTriggerText = value.toString() }) { text ->
                        markModelEdit()
                        compactTriggerText = text
                        text.toIntOrNull()?.let { compactTrigger = it.coerceIn(60, 95) }
                    }
                    IntSliderField("\ucd5c\uadfc \ub300\ud654 \ubcf4\uc874", recentHistoryPercent, recentHistoryPercentText, 15, 50, { value -> markModelEdit(); recentHistoryPercent = value; recentHistoryPercentText = value.toString() }) { text ->
                        markModelEdit()
                        recentHistoryPercentText = text
                        text.toIntOrNull()?.let { recentHistoryPercent = it.coerceIn(15, 50) }
                    }
                }

                Column {
                    Text(
                        text = "Accelerator",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .border(
                                width = 1.dp,
                                color = semantic.divider,
                                shape = RoundedCornerShape(999.dp)
                            )
                    ) {
                        AcceleratorSegment(
                            text = "GPU",
                            selected = accelerator == AcceleratorMode.GPU,
                            onClick = { markModelEdit(); accelerator = AcceleratorMode.GPU }
                        )

                        AcceleratorSegment(
                            text = "CPU",
                            selected = accelerator == AcceleratorMode.CPU,
                            onClick = { markModelEdit(); accelerator = AcceleratorMode.CPU }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "가속기를 변경하면 모델을 다시 불러옵니다.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("\uc131\ub2a5", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    SettingSwitchRow(
                        title = "\uc790\ub3d9 \uc131\ub2a5 \uc870\uc808",
                        subtitle = "\ubaa8\ub4e0 \ub85c\uceec \ubaa8\ub378\uacfc \uac80\uc99d \uae30\ub2a5\uc5d0 \uacf5\ud1b5 \uc801\uc6a9\ub418\uba70, \uae30\uae30 \uc0c1\ud0dc\ub97c \ubcf4\uc218\uc801\uc73c\ub85c \uace0\ub824\ud569\ub2c8\ub2e4.",
                        checked = runtimePerformance.mode == RuntimePerformanceMode.AUTO,
                        onToggle = {
                            runtimePerformance = runtimePerformance.copy(
                                mode = if (runtimePerformance.mode == RuntimePerformanceMode.AUTO) RuntimePerformanceMode.MANUAL else RuntimePerformanceMode.AUTO,
                            )
                        },
                    )
                    Text(
                        text = if (runtimePerformance.mode == RuntimePerformanceMode.AUTO) {
                            "\ud604\uc7ac: \uc790\ub3d9 \u00b7 CPU ${RuntimeCpuThreadPolicy.threadCountLabel(performanceResolution.threadCount)}"
                        } else {
                            "\ud604\uc7ac \uc801\uc6a9 \uc608\uc815: CPU ${RuntimeCpuThreadPolicy.threadCountLabel(performanceResolution.threadCount)}"
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    Text(
                        "\uae30\uae30 \uc0c1\ud0dc\uc5d0 \ub530\ub77c \uc751\ub2f5\uc131, \ubc1c\uc5f4, \uc804\ub825\uc744 \uace0\ub824\ud558\uc5ec CPU \uc0ac\uc6a9\ub7c9\uc744 \ubcf4\uc218\uc801\uc73c\ub85c \uc870\uc808\ud569\ub2c8\ub2e4.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                    Text("CPU \uc131\ub2a5", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("\ucd5c\uc18c", color = TextSecondary, fontSize = 12.sp)
                        Slider(
                            value = runtimePerformance.manualLevel.toFloat(),
                            onValueChange = { value ->
                                val snapped = RuntimeCpuThreadPolicy.snapManualLevel(value.roundToInt(), performanceResolution.deviceCeiling)
                                runtimePerformance = runtimePerformance.copy(manualLevel = snapped)
                            },
                            valueRange = 0f..100f,
                            steps = (performanceResolution.deviceCeiling - 2).coerceAtLeast(0),
                            enabled = runtimePerformance.mode == RuntimePerformanceMode.MANUAL,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = "CPU performance, ${RuntimeCpuThreadPolicy.threadCountLabel(performanceResolution.threadCount)}"
                                },
                        )
                        Text("\ucd5c\ub300", color = TextSecondary, fontSize = 12.sp)
                    }
                    Text(
                        "CPU \uc2a4\ub808\ub4dc \ubcc0\uacbd\uc740 \ubaa8\ub378 \ub610\ub294 \uac80\uc99d \ub7f0\ud0c0\uc784\uc744 \ub2e4\uc2dc \ubd88\ub7ec\uc57c \uc801\uc6a9\ub429\ub2c8\ub2e4. GPU \uc2e4\ud589 \uc911\uc5d0\ub294 \uc8fc \ubaa8\ub378\uc5d0 \uc9c1\uc811 \uc801\uc6a9\ub418\uc9c0 \uc54a\uc9c0\ub9cc, CPU \ud3f4\ubc31\uacfc \uc628\ub514\ubc14\uc774\uc2a4 \uac80\uc99d\uc5d0 \uc0ac\uc6a9\ub429\ub2c8\ub2e4.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                    Text(
                        "\uc131\ub2a5\uc744 \ub192\uc774\uba74 \ub85c\uceec AI \uc751\ub2f5 \uc18d\ub3c4\uac00 \ube68\ub77c\uc9c8 \uc218 \uc788\uc9c0\ub9cc \ubc1c\uc5f4, \ubc30\ud130\ub9ac \uc0ac\uc6a9\ub7c9, \uc21c\uac04 \uc804\ub825 \uc18c\ubaa8\uac00 \uc99d\uac00\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }

                SettingSwitchRow(
                    title = "MTP 가속",
                    subtitle = "지원되는 모델에서 speculative decoding으로 출력 속도를 높입니다. 가속기 또는 MTP를 변경하면 모델을 다시 불러옵니다.",
                    checked = if (speculativeDecodingTouched) {
                        speculativeDecodingEnabled
                    } else {
                        selectedModelPath?.let { path ->
                            MtpPolicyProduction.getDefaultMtpSetting(
                                modelPath = path,
                                accelerator = accelerator
                            )
                        } ?: false
                    },
                    onToggle = {
                        markModelEdit()
                        val current = if (speculativeDecodingTouched) {
                            speculativeDecodingEnabled
                        } else {
                            selectedModelPath?.let { path ->
                                MtpPolicyProduction.getDefaultMtpSetting(
                                    modelPath = path,
                                    accelerator = accelerator
                                )
                            } ?: false
                        }
                        speculativeDecodingEnabled = !current
                        speculativeDecodingTouched = true
                    }
                )

                SettingSwitchRow(
                    title = "추론",
                    subtitle = if (reasoningEnabledAvailable) "" else "Kanana에서는 아직 검증되지 않았습니다.",
                    checked = reasoningEnabledAvailable && reasoningEnabledLocal,
                    enabled = reasoningEnabledAvailable,
                    onToggle = { markModelEdit(); reasoningEnabledLocal = !reasoningEnabledLocal }
                )

                SettingSwitchRow(
                    title = "웹 검색",
                    subtitle = "",
                    checked = webSearchEnabledLocal,
                    onToggle = { webSearchEnabledLocal = !webSearchEnabledLocal }
                )

                Text("제공자 설정은 각 항목을 변경하거나 저장할 때 바로 적용됩니다.", color = TextSecondary, fontSize = 12.sp)
                WebSearchProviderSettingsSection(
                    repository = webSearchProviderRepository,
                    modifier = Modifier.fillMaxWidth()
                )

                SearchStrategySettingsSection(
                    preference = searchPreferenceDraft,
                    onPreferenceChange = { searchPreferenceDraft = it },
                    manualQuery = manualQueryDraft,
                    onManualQueryChange = { manualQueryDraft = it },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = if (partialApplyWarning) "닫기" else "취소",
                    color = Color(0xFFD0D0D0)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedTopP = LocalGenerationFloatSettings.parseTopP(topPText)
                    val parsedTemperature = LocalGenerationFloatSettings.parseTemperature(temperatureText)
                    if (parsedTopP == null || parsedTemperature == null) {
                        floatInputError = true
                        return@TextButton
                    }
                    val appliedSettings = settings.copy(
                        maxTokens = maxTokensText.toIntOrNull()
                            ?.coerceIn(1, 32000)
                            ?: maxTokens.coerceIn(1, 32000),
                        topK = LocalGenerationSettingBounds.parseTopK(topKText, topK),
                        topP = parsedTopP,
                        temperature = parsedTemperature,
                        accelerator = accelerator,
                        reasoningBudgetTokens = LocalGenerationSettingBounds.parseReasoningBudget(
                            reasoningBudgetText,
                            reasoningBudget,
                        ),
                        kvCacheCapacityTokens = manualKvCapacity,
                        speculativeDecodingEnabled = if (speculativeDecodingTouched) speculativeDecodingEnabled else null,
                    )
                    val appliedEdit = currentEditValues().copy(settings = appliedSettings)
                    editBuffer = editBuffer.update(scope, appliedEdit)
                    val result = onApply(
                        appliedSettings,
                        reasoningEnabledLocal,
                        webSearchEnabledLocal,
                        scope,
                        appliedEdit,
                        shouldDeleteModelOverride(resetModelOverride, modelEditedAfterReset),
                        runtimePerformance.normalized(),
                        searchPreferenceDraft,
                    )
                    when (result) {
                        AdvancedSettingsApplyResult.FAILED_BEFORE_PERSISTENCE -> return@TextButton
                        AdvancedSettingsApplyResult.PARTIALLY_APPLIED -> {
                            partialApplyWarning = true
                            return@TextButton
                        }
                        AdvancedSettingsApplyResult.APPLIED -> partialApplyWarning = false
                    }
                    onManualSearchQueryChange(if (searchPreferenceDraft.strategy == SearchStrategy.MANUAL_QUERY) manualQueryDraft else "")
                }
            ) {
                Text(
                    text = "적용",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    )

}
@Composable
private fun IntSliderField(
    title: String,
    value: Int,
    valueText: String,
    min: Int,
    max: Int,
    onSliderChange: (Int) -> Unit,
    onTextChange: (String) -> Unit
) {
    val semantic = LocalFusionSemanticColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        settingExplanation(title)?.let { explanation ->
            Text(
                text = explanation,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = min.toString(),
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.width(50.dp)
            )

            Slider(
                value = value.coerceIn(min, max).toFloat(),
                onValueChange = { sliderValue ->
                    onSliderChange(sliderValue.roundToInt().coerceIn(min, max))
                },
                valueRange = min.toFloat()..max.toFloat(),
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = semantic.primary,
                    inactiveTrackColor = semantic.divider,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )

            Spacer(modifier = Modifier.width(12.dp))

            OutlinedTextField(
                value = valueText,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 14.sp
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                modifier = Modifier.width(110.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = semantic.primary,
                    unfocusedBorderColor = semantic.divider,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = semantic.primary,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )
        }
    }

}
@Composable
private fun FloatSliderField(
    title: String,
    value: Float,
    valueText: String,
    min: Float,
    max: Float,
    onSliderChange: (Float) -> Unit,
    onTextChange: (String) -> Unit
) {
    val semantic = LocalFusionSemanticColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        settingExplanation(title)?.let { explanation ->
            Text(
                text = explanation,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = String.format(Locale.ROOT, "%.2f", min),
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.width(50.dp)
            )

            Slider(
                value = value.coerceIn(min, max),
                onValueChange = { sliderValue ->
                    onSliderChange(sliderValue.coerceIn(min, max))
                },
                valueRange = min..max,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = semantic.primary,
                    inactiveTrackColor = semantic.divider,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )

            Spacer(modifier = Modifier.width(12.dp))

            OutlinedTextField(
                value = valueText,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 14.sp
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                ),
                modifier = Modifier.width(110.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = semantic.primary,
                    unfocusedBorderColor = semantic.divider,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = semantic.primary,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )
        }
    }

}
@Composable
private fun AcceleratorSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    StableSegmentedOption(
        text = text,
        selected = selected,
        onClick = onClick,
    )
}

internal fun buildRuntimePerformanceSummaryLine(
    preference: RuntimePerformancePreference,
    resolvedThreads: Int,
    accelerator: AcceleratorMode,
): String {
    val mode = if (preference.mode == RuntimePerformanceMode.AUTO) "\uc790\ub3d9" else "\uc0ac\uc6a9\uc790 \uc124\uc815"
    val runtime = if (accelerator == AcceleratorMode.CPU) "CPU" else "CPU \ud3f4\ubc31"
    return "\uc131\ub2a5: $mode \u00b7 $runtime ${RuntimeCpuThreadPolicy.threadCountLabel(resolvedThreads)}"
}

internal fun buildAdvancedSettingsAppliedSummary(
    selectedModel: String,
    accelerator: AcceleratorMode,
    mtpEnabled: Boolean,
    maxTokensText: String,
    contextMode: LocalContextMode,
    contextCapacityText: String,
    temperatureText: String,
    topKText: String,
    topPText: String,
    compactEnabled: Boolean,
    compactTriggerText: String,
    recentHistoryText: String,
    reasoningEnabled: Boolean,
    webSearchEnabled: Boolean,
): List<String> {
    val acceleratorLabel = when (accelerator) {
        AcceleratorMode.AUTO -> "자동"
        AcceleratorMode.GPU -> "GPU"
        AcceleratorMode.CPU -> "CPU"
    }
    val contextLabel = if (contextMode == LocalContextMode.AUTO) {
        "자동 ($contextCapacityText 토큰)"
    } else {
        "직접 설정 ($contextCapacityText 토큰)"
    }
    val compactLabel = if (compactEnabled) {
        "켜짐 · ${compactTriggerText}%에서 시작 · 최근 ${recentHistoryText}% 보존"
    } else {
        "꺼짐"
    }
    return listOf(
        "모델: $selectedModel",
        "가속기: $acceleratorLabel · MTP: ${if (mtpEnabled) "켜짐" else "꺼짐"}",
        "출력: $maxTokensText · 컨텍스트: $contextLabel",
        "온도 $temperatureText · Top-K $topKText · Top-P $topPText",
        "자동 압축: $compactLabel",
        "추론: ${if (reasoningEnabled) "켜짐(권장 예산)" else "꺼짐"} · 웹 검색: ${if (webSearchEnabled) "켜짐" else "꺼짐"}",
    )
}
