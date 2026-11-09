package com.projectnuke.fusion.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.chat.NextActionSuggestion
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

/**
 * Quiet contextual next-action surface shown only below the latest active
 * assistant answer. At most three compact chips; a tap only seeds composer
 * text through the existing durable Quick Prompt path (never sends).
 */
@Composable
internal fun NextActionSuggestionsRow(
    suggestions: List<NextActionSuggestion>,
    enabled: Boolean = true,
    onSelect: (NextActionSuggestion) -> Unit,
) {
    if (suggestions.isEmpty()) return
    val semanticColors = LocalFusionSemanticColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "다음에 해볼까요?",
            color = semanticColors.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap),
        ) {
            suggestions.forEach { suggestion ->
                key(suggestion.id) {
                    FluidPressableSurface(
                        onClick = { onSelect(suggestion) },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = FusionSpacing.chipMinHeight),
                        shape = FusionSpacing.chipShape,
                        containerColor = if (enabled) {
                            semanticColors.panel
                        } else {
                            semanticColors.panel.copy(alpha = 0.5f)
                        },
                        contentColor = if (enabled) {
                            semanticColors.onSurface
                        } else {
                            semanticColors.onSurfaceVariant
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        role = Role.Button,
                    ) {
                        Text(
                            text = suggestion.label,
                            color = if (enabled) {
                                semanticColors.onSurface
                            } else {
                                semanticColors.onSurfaceVariant
                            },
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
