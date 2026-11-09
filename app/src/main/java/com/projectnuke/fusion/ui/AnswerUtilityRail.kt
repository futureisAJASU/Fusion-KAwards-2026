package com.projectnuke.fusion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.chat.AnswerUtilityAction
import com.projectnuke.fusion.chat.ContextualAnswerActionSuggestion
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

/**
 * Fusion primary interactive-component accessibility floor. Rail chips must
 * satisfy this; visual chrome stays compact, only the hit/layout target is
 * this large. Pinned in a constant so the policy is host-testable.
 */
internal const val RAIL_CHIP_MIN_HEIGHT_DP = 48

/**
 * Canonical label of the SINGLE message overflow (⋮). The compact rail must
 * not append a second "More" chip: less frequent actions are owned by the
 * existing message overflow, and this label names that one control.
 */
internal const val RAIL_MORE_LABEL = "더 보기"

/**
 * Next Action Flow v1A — the answer utility rail (Layer 1).
 *
 * A compact, horizontally scrolling set of PRIMARY direct actions belonging to
 * the currently visible answer. Kept strictly separate from the follow-up
 * question row (Layer 2): this rail uses the pure [AnswerUtilityRailPolicy]
 * eligibility computed by the caller and NEVER participates in the semantic
 * max-3 question composition, so utility actions can never consume question
 * capacity and questions can never consume action slots.
 *
 * Single ownership: the caller passes only PRIMARY-tier actions
 * ([AnswerUtilityRailPolicy.primaryActions]); OVERFLOW-tier actions (Study,
 * translation, comparison, project operations, ...) are owned by the one
 * message overflow, so no action is ever advertised by two surfaces.
 *
 * The `evidenceClick` slot is provided by exactly one composed
 * [EvidenceMessageActionHost] owned by the caller (per message), so this rail
 * never creates a second Evidence controller/verifier/dialog path.
 */
@Composable
internal fun AnswerUtilityRail(
    actions: List<AnswerUtilityAction>,
    enabled: Boolean,
    sourcesRevealed: Boolean,
    onToggleSources: () -> Unit,
    onStudy: () -> Unit,
    suggestedAction: ContextualAnswerActionSuggestion? = null,
    onSuggestedAction: (() -> Unit)? = null,
    evidenceClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            when (action) {
                AnswerUtilityAction.SOURCES -> RailChip(
                    label = if (sourcesRevealed) "사용한 자료 숨기기" else "사용한 자료",
                    enabled = enabled,
                    onClick = onToggleSources,
                )
                AnswerUtilityAction.EVIDENCE -> evidenceClick?.let { click ->
                    RailChip(label = "사실 확인", enabled = enabled, onClick = click)
                }
                // Defensive only: STUDY is an OVERFLOW-tier action and is never
                // passed here by the caller. Kept exhaustive for enum safety.
                AnswerUtilityAction.STUDY -> RailChip(
                    label = "이 주제로 공부",
                    enabled = enabled,
                    onClick = onStudy,
                )
            }
        }
        if (suggestedAction != null && onSuggestedAction != null) {
            RailChip(
                label = suggestedAction.label,
                enabled = enabled,
                onClick = onSuggestedAction,
            )
        }
    }
}

@Composable
private fun RailChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    val semantic = LocalFusionSemanticColors.current
    // Single interaction owner: one click node carries the Button role and the
    // visible Korean label (never icon-only, never color-only). Touch/layout
    // target at the Fusion 48dp floor; visual chrome stays compact.
    FluidPressableSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = RAIL_CHIP_MIN_HEIGHT_DP.dp),
        shape = FusionSpacing.chipShape,
        containerColor = Color.Transparent,
        contentColor = semantic.onSurface,
        border = null,
        contentPadding = PaddingValues(
            horizontal = FusionSpacing.chipPaddingHorizontal,
            vertical = FusionSpacing.chipPaddingVertical,
        ),
        role = Role.Button,
    ) {
        Text(
            text = label,
            color = if (enabled) semantic.onSurface else semantic.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
