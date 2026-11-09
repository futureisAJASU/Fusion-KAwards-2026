package com.projectnuke.fusion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

// Unified execution-profile selector: exactly one top-level control for
// 로컬 | 자동 | 외부 AI. The profile is presentation/orchestration only; it
// resolves into the existing low-level primitives at the Send tap and the
// frozen request stays authoritative afterwards.
@Composable
internal fun GenerationModeSelector(
    profile: ChatExecutionProfile,
    selectedLocalModelLabel: String?,
    autoSelectionExplanation: String?,
    selectedProviderName: String?,
    selectedProviderId: String?,
    runnableProviders: List<AiProviderConfig>,
    enabled: Boolean,
    onProfileSelected: (ChatExecutionProfile) -> Unit,
    onOpenModelLibrary: () -> Unit,
    onExternalProviderSelected: (String) -> Unit,
    onOpenApiSettings: () -> Unit
) {
    var providerExpanded by remember { mutableStateOf(false) }
    val semantic = LocalFusionSemanticColors.current
    val providerLabel = when {
        runnableProviders.isEmpty() -> "외부 AI API 설정이 필요합니다."
        !selectedProviderName.isNullOrBlank() -> "API: $selectedProviderName"
        else -> "외부 AI API 제공자를 선택해 주세요."
    }

    LaunchedEffect(profile, enabled, selectedProviderId, runnableProviders) {
        if (profile != ChatExecutionProfile.EXTERNAL_AI || !enabled || runnableProviders.isEmpty()) {
            providerExpanded = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModeChip(
                label = ChatExecutionProfilePolicy.label(ChatExecutionProfile.LOCAL),
                selected = profile == ChatExecutionProfile.LOCAL,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onProfileSelected(ChatExecutionProfile.LOCAL) }
            )
            ModeChip(
                label = ChatExecutionProfilePolicy.label(ChatExecutionProfile.AUTOMATIC),
                selected = profile == ChatExecutionProfile.AUTOMATIC,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onProfileSelected(ChatExecutionProfile.AUTOMATIC) }
            )
            ModeChip(
                label = ChatExecutionProfilePolicy.label(ChatExecutionProfile.EXTERNAL_AI),
                selected = profile == ChatExecutionProfile.EXTERNAL_AI,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onProfileSelected(ChatExecutionProfile.EXTERNAL_AI) }
            )
        }

        Text(
            text = ChatExecutionProfilePolicy.explanation(profile),
            color = semantic.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 2.dp),
        )

        if (profile == ChatExecutionProfile.LOCAL) {
            FluidPressableSurface(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                onClick = onOpenModelLibrary,
                shape = RoundedCornerShape(8.dp),
                containerColor = semantic.panel,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "모델: ${selectedLocalModelLabel?.takeIf { it.isNotBlank() } ?: "모델 선택"}",
                        color = semantic.onSurface,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "변경",
                        color = semantic.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (profile == ChatExecutionProfile.AUTOMATIC) {
            // No manual local model picker as the primary execution
            // authority: routing resolves at request time through
            // AutoModelRoutingOwner. The model library stays reachable from
            // the route menu for management. Never pretend a model is fixed
            // before routing: neutral status until an explanation exists.
            Text(
                text = ChatExecutionProfilePolicy.autoRoutingStatusLabel(autoSelectionExplanation) +
                    (autoSelectionExplanation?.lineSequence()?.drop(1)?.firstOrNull()?.let { "\n$it" }.orEmpty()),
                color = semantic.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }

        if (profile == ChatExecutionProfile.EXTERNAL_AI) {
            Box(modifier = Modifier.fillMaxWidth()) {
                FluidPressableSurface(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled && runnableProviders.isNotEmpty(),
                    onClick = { providerExpanded = true },
                    shape = RoundedCornerShape(8.dp),
                    containerColor = semantic.panel,
                ) {
                    if (runnableProviders.isEmpty()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = providerLabel,
                                    color = semantic.onSurface,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "사용할 외부 AI 제공자를 설정해 주세요.",
                                    color = semantic.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            TextButton(
                                enabled = enabled,
                                onClick = onOpenApiSettings,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 10.dp,
                                    vertical = 4.dp
                                )
                            ) {
                                Text(
                                    text = "API 설정",
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = providerLabel,
                                color = semantic.onSurface,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "변경",
                                color = semantic.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                FusionDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false },
                    containerColor = semantic.menu
                ) {
                    runnableProviders.forEach { provider ->
                        val selected = provider.id == selectedProviderId
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = buildString {
                                        if (selected) append("선택됨 · ")
                                        append("API: ")
                                        append(provider.displayName)
                                    },
                                    color = if (selected) semantic.onSurface else semantic.onSurfaceVariant
                                )
                            },
                            onClick = {
                                providerExpanded = false
                                onExternalProviderSelected(provider.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val semantic = LocalFusionSemanticColors.current
    val shape = RoundedCornerShape(8.dp)
    // Single interaction owner: FluidPressableSurface carries the one
    // pointer/click node, the RadioButton role and the selected semantics on
    // that same node. No nested selectable/clickable child.
    FluidPressableSurface(
        modifier = modifier
            .heightIn(min = 48.dp),
        enabled = enabled,
        onClick = onClick,
        shape = shape,
        containerColor = if (selected) semantic.bubble else semantic.panel,
        border = BorderStroke(1.dp, if (selected) semantic.primary else semantic.divider),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        role = androidx.compose.ui.semantics.Role.RadioButton,
        isSelected = selected,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (selected) semantic.onSurface else semantic.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
