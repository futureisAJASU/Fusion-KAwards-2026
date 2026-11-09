package com.projectnuke.fusion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

internal const val StableSegmentedLeadingSlotWidthDp = 20
internal const val StableSegmentedTextSizeSp = 16

internal data class StableSegmentedOptionModel(
    val label: String,
    val selected: Boolean,
    val leadingSlotWidthDp: Int = StableSegmentedLeadingSlotWidthDp,
    val textSizeSp: Int = StableSegmentedTextSizeSp,
    val fontWeight: FontWeight = FontWeight.Normal,
)

internal fun stableSegmentedOptionModel(
    label: String,
    selected: Boolean,
): StableSegmentedOptionModel = StableSegmentedOptionModel(
    label = label,
    selected = selected,
)

/**
 * A capsule option whose measured content is identical across selection
 * states. The checkmark is painted inside a slot that is always present, so
 * selection cannot move the label or change the segment's measured width.
 */
@Composable
internal fun StableSegmentedOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val semantic = LocalFusionSemanticColors.current
    val model = stableSegmentedOptionModel(text, selected)
    Box(
        modifier = Modifier
            .selectable(
                selected = model.selected,
                onClick = onClick,
                role = androidx.compose.ui.semantics.Role.RadioButton,
            )
            .background(if (model.selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(model.leadingSlotWidthDp.dp)
                    .height(StableSegmentedLeadingSlotWidthDp.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (model.selected) {
                    Text(
                        text = "✓",
                        color = semantic.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = model.label,
                color = semantic.onSurface,
                fontSize = model.textSizeSp.sp,
                fontWeight = model.fontWeight,
            )
        }
    }
}
