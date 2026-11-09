package com.projectnuke.fusion.ui.model

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.projectnuke.fusion.model.LocalSettingsEditValues
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.SimpleAnswerLength
import com.projectnuke.fusion.model.SimpleResponseStyle
import com.projectnuke.fusion.model.ReasoningEffortPreset
import com.projectnuke.fusion.model.applyReasoningEffort
import com.projectnuke.fusion.model.applyRecommendedSimpleReasoning
import com.projectnuke.fusion.model.applySimpleAccelerator
import com.projectnuke.fusion.model.applySimpleLength
import com.projectnuke.fusion.model.applySimpleStyle
import com.projectnuke.fusion.model.detectReasoningEffort
import com.projectnuke.fusion.model.detectSimpleLength
import com.projectnuke.fusion.model.detectSimpleStyle
import com.projectnuke.fusion.model.recommendedSimpleLengthForCap
import com.projectnuke.fusion.model.setReasoningEnabled
import com.projectnuke.fusion.model.shouldRecommendCpuForCompatibility
import com.projectnuke.fusion.model.simpleLengthAvailability
import com.projectnuke.fusion.model.simpleLengthLabel
import com.projectnuke.fusion.model.stableRecommendedOutputCap
import com.projectnuke.fusion.model.supportsSimpleReasoning
import com.projectnuke.fusion.model.toSelection
import com.projectnuke.fusion.modelzoo.FusionModelSpec
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.ui.reasoningBudgetExplanation
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

@Composable
fun SimpleResponseSettingsScreen(
    modelName: String,
    spec: FusionModelSpec,
    current: LocalSettingsEditValues,
    totalRamBytes: Long,
    isLowRamDevice: Boolean,
    onDismiss: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onWrite: ((LocalSettingsEditValues) -> LocalSettingsEditValues) -> Boolean,
    onApplyRecommended: ((LocalSettingsEditValues) -> LocalSettingsEditValues) -> Boolean,
) {
    val stableCap = stableRecommendedOutputCap(spec, totalRamBytes, isLowRamDevice)
    val selectedStyle = detectSimpleStyle(current, spec.family)
    val selectedLength = detectSimpleLength(current)
    val selectedReasoning = detectReasoningEffort(current)
    val colors = LocalFusionSemanticColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .heightIn(min = 320.dp, max = 720.dp)
                .navigationBarsPadding(),
            color = colors.panel,
            contentColor = colors.onBackground,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.divider),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("응답 설정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("${modelName}의 답변 방식을 쉽게 조절할 수 있어요.", style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "닫기") }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item { SimpleSettingsSection {
                    Text("답변 스타일", style = MaterialTheme.typography.titleMedium)
                    SimpleChoiceColumn(SimpleResponseStyle.entries.filter { it != SimpleResponseStyle.CUSTOM }, selectedStyle) { style ->
                        onWrite { values -> applySimpleStyle(values, style, spec.family) }
                    }
                    Text(selectedStyle.description, style = MaterialTheme.typography.bodySmall)
                    Text("답변 스타일은 표현의 다양성을 조절합니다. 사실 정확도나 모델의 지능을 보장하는 설정은 아니에요.", style = MaterialTheme.typography.bodySmall)
               }
                }
                item { SimpleSettingsSection {
                    Text("답변 길이", style = MaterialTheme.typography.titleMedium)
                    Text("현재: ${simpleLengthLabel(current)}", style = MaterialTheme.typography.bodySmall)
                    SimpleAnswerLength.entries.filter { it != SimpleAnswerLength.CUSTOM }.forEach { length ->
                        val availability = simpleLengthAvailability(length, stableCap, current)
                        FilterChip(
                            selected = selectedLength == length,
                            enabled = availability.enabled,
                            onClick = { onWrite { values -> applySimpleLength(values, length) } },
                            label = { Text(length.label) },
                            modifier = Modifier.padding(end = 6.dp, bottom = 6.dp),
                        )
                        if (!availability.enabled) Text(availability.helper.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
               }
                }
                item { SimpleSettingsSection {
                    Text("추론", style = MaterialTheme.typography.titleMedium)
                    when {
                        spec.family == ModelFamily.KANANA && !supportsSimpleReasoning(spec) -> Text("현재 Kanana 호환 경로에서는 추론 조절을 사용하지 않아요.", style = MaterialTheme.typography.bodyMedium)
                        !supportsSimpleReasoning(spec) -> Text("이 모델은 간편 추론 조절 대상이 아니에요.", style = MaterialTheme.typography.bodyMedium)
                        else -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = current.reasoningEnabled,
                                    onClick = { onWrite { values -> setReasoningEnabled(values, true) } },
                                    label = { Text("켜짐") },
                                )
                                FilterChip(
                                    selected = !current.reasoningEnabled,
                                    onClick = { onWrite { values -> setReasoningEnabled(values, false) } },
                                    label = { Text("꺼짐") },
                                )
                            }
                            if (current.reasoningEnabled) {
                                Text("추론 수준", style = MaterialTheme.typography.titleSmall)
                                ReasoningEffortPreset.entries.chunked(2).forEach { rowPresets ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        rowPresets.forEach { preset ->
                                            FilterChip(
                                                selected = selectedReasoning == preset.toSelection(),
                                                onClick = {
                                                    onWrite { values ->
                                                        requireNotNull(
                                                            applyReasoningEffort(
                                                                values,
                                                                preset,
                                                                supportsReasoning = FusionModelCatalog.reasoningCapabilityAvailable(spec),
                                                            )
                                                        )
                                                    }
                                                },
                                                label = { Text(preset.label) },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        if (rowPresets.size == 1) Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                                if (selectedReasoning == com.projectnuke.fusion.model.ReasoningEffortSelection.CUSTOM) {
                                    Text("현재: 사용자 설정 · ${current.settings.reasoningBudgetTokens}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text(reasoningBudgetExplanation(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
               }
                }
                item { SimpleSettingsSection {
                    Text("실행 방식", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = current.settings.accelerator == AcceleratorMode.GPU,
                            onClick = { onWrite { values -> applySimpleAccelerator(values, AcceleratorMode.GPU) } },
                            label = { Text("GPU") },
                        )
                        FilterChip(
                            selected = current.settings.accelerator == AcceleratorMode.CPU,
                            onClick = { onWrite { values -> applySimpleAccelerator(values, AcceleratorMode.CPU) } },
                            label = { Text("CPU") },
                        )
                    }
                    Text("GPU", style = MaterialTheme.typography.bodyMedium)
                    Text("지원되는 모델에서 더 빠를 수 있어요.", style = MaterialTheme.typography.bodySmall)
                    Text("CPU", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "GPU에서 출력이 이상하거나 실행 문제가 있을 때 사용해 보세요. 속도는 더 느릴 수 있어요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (shouldRecommendCpuForCompatibility(spec)) {
                        Text(
                            "이 모델은 CPU 실행을 권장해요. GPU에서 문제가 발생하면 CPU를 사용해 보세요.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                }
                item { SimpleSettingsSection {
                    Button(onClick = {
                        onApplyRecommended { values ->
                            val styled = applySimpleStyle(values, SimpleResponseStyle.BALANCED, spec.family)
                            val length = recommendedSimpleLengthForCap(stableCap)
                            val withLength = applySimpleLength(styled, length)
                            applyRecommendedSimpleReasoning(
                                values = withLength,
                                supportsReasoning = supportsSimpleReasoning(spec),
                                recommendedReasoningEnabled = spec.recommendedReasoningEnabled,
                            )
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("추천값으로 맞추기") }
                    Text("이 모델과 기기에 맞춘 간편 설정을 적용해요.", style = MaterialTheme.typography.bodySmall)
               }
                }
                item { SimpleSettingsSection {
                    OutlinedButton(onClick = onOpenAdvanced, modifier = Modifier.fillMaxWidth()) { Text("고급 설정") }
                    Text("Temperature, Top-K, Top-P, 토큰 수, 컨텍스트, GPU/CPU, MTP 등을 직접 조절합니다.", style = MaterialTheme.typography.bodySmall)
                }
                }
            }
        }
        }
    }
}

@Composable
private fun SimpleSettingsSection(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val colors = LocalFusionSemanticColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.menu,
        contentColor = colors.onSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.divider),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun <T> SimpleChoiceColumn(options: List<T>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val label = when (option) {
                is SimpleResponseStyle -> option.label
                else -> option.toString()
            }
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
