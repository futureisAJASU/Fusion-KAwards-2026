package com.projectnuke.fusion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.ComposerQuickAcceleratorChoice
import com.projectnuke.fusion.model.ComposerQuickGenerationStatus
import com.projectnuke.fusion.model.ComposerQuickSettingsPolicy
import com.projectnuke.fusion.model.ComposerQuickSettingsTarget
import com.projectnuke.fusion.model.LocalSettingsEditValues
import com.projectnuke.fusion.model.ReasoningEffortPreset
import com.projectnuke.fusion.model.applyReasoningEffort
import com.projectnuke.fusion.model.detectReasoningEffort
import com.projectnuke.fusion.model.setReasoningEnabled
import com.projectnuke.fusion.model.toSelection
import com.projectnuke.fusion.chat.FusionWorkflowPreset
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

/**
 * Stateless anchored panel for Composer Quick Settings.
 *
 * The parent owns model/mode invalidation, chat workflow controls, and profile
 * writes. This surface presents the current settings and dispatches bounded
 * workflow choices through the same transient Tune interaction.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ComposerQuickSettingsPanel(
    currentValues: LocalSettingsEditValues,
    modelFamilyKey: String?,
    supportsReasoning: Boolean,
    maxSupportedOutputTokens: Int?,
    target: ComposerQuickSettingsTarget,
    generationStatus: ComposerQuickGenerationStatus?,
    reasoningActive: Boolean,
    selectedWorkflow: FusionWorkflowPreset?,
    hasProjectSources: Boolean,
    canApplyWorkflow: Boolean,
    onSelectWorkflow: (FusionWorkflowPreset) -> Unit,
    onApply: (ComposerQuickSettingsTarget, LocalSettingsEditValues) -> Unit,
    onOpenFullSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalFusionSemanticColors.current
    var workflowOptionsExpanded by remember { mutableStateOf(false) }
    val generationStatusText = ComposerQuickSettingsPolicy.composerGenerationStatusText(
        status = generationStatus,
        reasoningActive = reasoningActive,
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = FusionSpacing.objectShape,
        color = semantic.menu,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "빠른 응답 설정",
                    color = semantic.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (generationStatusText != null) {
                    Text(
                        text = generationStatusText,
                        color = semantic.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            ComposerQuickSettingsSectionTitle("워크플로")
            FluidPressableSurface(onClick = { workflowOptionsExpanded = !workflowOptionsExpanded },
                modifier = Modifier.fillMaxWidth().heightIn(min = FusionSpacing.control)
                    .semantics { stateDescription = if (workflowOptionsExpanded) "펼쳐짐" else "접힘" },
                shape = FusionSpacing.objectShape, containerColor = semantic.background,
                contentColor = semantic.onSurface, border = BorderStroke(1.dp, semantic.divider),
                contentPadding = PaddingValues(12.dp), role = Role.Button) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(selectedWorkflow?.title ?: "선택 안 함", style = MaterialTheme.typography.bodyMedium)
                        Text("워크플로 선택", style = MaterialTheme.typography.bodySmall,
                            color = semantic.onSurfaceVariant)
                    }
                    Icon(if (workflowOptionsExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null)
                }
            }
            if (workflowOptionsExpanded) {
                FusionWorkflowPreset.entries.forEach { preset ->
                    val available = canApplyWorkflow && (!preset.requiresProjectSources || hasProjectSources)
                    FusionWorkflowOptionRow(
                        preset = preset,
                        selected = selectedWorkflow == preset,
                        enabled = available,
                        onClick = {
                            onSelectWorkflow(preset)
                            workflowOptionsExpanded = false
                        },
                    )
                }
            }

            ComposerQuickSettingsSectionTitle("답변 스타일")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3,
            ) {
                ComposerQuickSettingsPolicy.answerStyles.forEach { style ->
                    val preset = ComposerQuickSettingsPolicy.samplingPreset(style, modelFamilyKey)
                    ComposerQuickChoiceChip(
                        label = style.label,
                        selected = currentValues.settings.temperature == preset.temperature &&
                            currentValues.settings.topP == preset.topP &&
                            currentValues.settings.topK == preset.topK,
                        enabled = true,
                        modifier = Modifier.widthIn(min = 80.dp).weight(1f).fillMaxRowHeight(),
                        onClick = {
                            onApply(
                                target,
                                ComposerQuickSettingsPolicy.applyAnswerStyle(
                                    current = currentValues,
                                    style = style,
                                    modelFamilyKey = modelFamilyKey,
                                ),
                            )
                        },
                    )
                }
            }
            Text(
                text = ComposerQuickSettingsPolicy.STYLE_HELPER_TEXT,
                color = semantic.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )

            ComposerQuickSettingsSectionTitle("답변 길이")
            ComposerQuickOutputLengthGrid(
                currentValues = currentValues,
                maxSupportedOutputTokens = maxSupportedOutputTokens,
                target = target,
                onApply = onApply,
            )

            if (supportsReasoning) {
                val reasoningSelection = detectReasoningEffort(currentValues)
                ComposerQuickSettingsSectionTitle("추론")
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(true, false).forEach { enabled ->
                        ComposerQuickChoiceChip(
                            label = if (enabled) "켜짐" else "꺼짐",
                            selected = currentValues.reasoningEnabled == enabled,
                            enabled = true,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = {
                                onApply(target, setReasoningEnabled(currentValues, enabled))
                            },
                        )
                    }
                }
                if (currentValues.reasoningEnabled) {
                    ComposerQuickSettingsSectionTitle("추론 수준")
                    if (reasoningSelection == com.projectnuke.fusion.model.ReasoningEffortSelection.CUSTOM) {
                        Text(
                            text = "추론 수준 · 사용자 설정 ${currentValues.settings.reasoningBudgetTokens}",
                            color = semantic.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReasoningEffortPreset.entries.chunked(2).forEach { rowPresets ->
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowPresets.forEach { preset ->
                                    ComposerQuickChoiceChip(
                                        label = preset.label,
                                        selected = reasoningSelection == preset.toSelection(),
                                        enabled = true,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                        onClick = {
                                            applyReasoningEffort(currentValues, preset, supportsReasoning)
                                                ?.let { onApply(target, it) }
                                        },
                                    )
                                }
                                if (rowPresets.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Text(
                        text = reasoningBudgetExplanation(),
                        color = semantic.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            ComposerQuickSettingsSectionTitle("Accelerator")
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ComposerQuickSettingsPolicy.acceleratorChoices.forEach { choice ->
                    ComposerQuickChoiceChip(
                        label = choice.label,
                        selected = currentValues.settings.accelerator == choice.mode,
                        enabled = true,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = {
                            onApply(
                                target,
                                ComposerQuickSettingsPolicy.applyAccelerator(
                                    current = currentValues,
                                    choice = choice,
                                ),
                            )
                        },
                    )
                }
            }
            Text(
                text = when (currentValues.settings.accelerator) {
                    AcceleratorMode.CPU -> ComposerQuickAcceleratorChoice.CPU.helperText
                    else -> ComposerQuickAcceleratorChoice.GPU.helperText
                },
                color = semantic.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            ComposerQuickSettingsPolicy.kananaCpuRecommendationText(modelFamilyKey)?.let { helper ->
                Text(
                    text = helper,
                    color = semantic.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                )
            }

            ComposerQuickActionChip(
                label = "전체 설정",
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenFullSettings,
            )
        }
    }
}

@Composable
private fun FusionWorkflowOptionRow(
    preset: FusionWorkflowPreset,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    FluidPressableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        containerColor = if (selected) semantic.bubble else Color.Transparent,
        contentColor = if (enabled) semantic.onSurface else semantic.onSurfaceVariant,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        role = Role.Button,
        isSelected = selected,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(preset.title, style = MaterialTheme.typography.bodyMedium)
            Text(preset.summary, style = MaterialTheme.typography.bodySmall,
                color = semantic.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComposerQuickOutputLengthGrid(
    currentValues: LocalSettingsEditValues,
    maxSupportedOutputTokens: Int?,
    target: ComposerQuickSettingsTarget,
    onApply: (ComposerQuickSettingsTarget, LocalSettingsEditValues) -> Unit,
) {
    val options = ComposerQuickSettingsPolicy.outputLengthOptions(maxSupportedOutputTokens)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowOptions.forEach { option ->
                    ComposerQuickChoiceChip(
                        label = "${option.length.label} · ${option.length.tokens}",
                        selected = currentValues.settings.maxTokens == option.length.tokens,
                        enabled = option.enabled,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = {
                            ComposerQuickSettingsPolicy.applyOutputLength(
                                current = currentValues,
                                length = option.length,
                                maxSupportedOutputTokens = maxSupportedOutputTokens,
                            )?.let { onApply(target, it) }
                        },
                    )
                }
                if (rowOptions.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ComposerQuickSettingsSectionTitle(text: String) {
    val semantic = LocalFusionSemanticColors.current
    Text(
        text = text,
        color = semantic.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ComposerQuickChoiceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    val shape = RoundedCornerShape(16.dp)
    FluidPressableSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = shape,
        containerColor = if (selected) semantic.bubble else semantic.background,
        contentColor = if (enabled) semantic.onSurface else semantic.onSurfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, if (selected) semantic.primary else semantic.divider),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        role = Role.Button,
        isSelected = selected,
    ) {
        Text(
            text = label,
            color = if (enabled) semantic.onSurface else semantic.onSurfaceVariant.copy(alpha = 0.45f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ComposerQuickActionChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    FluidPressableSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = semantic.background,
        contentColor = semantic.onSurface,
        border = BorderStroke(1.dp, semantic.divider),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        role = Role.Button,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = semantic.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "→",
                color = semantic.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}
