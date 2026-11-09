package com.projectnuke.fusion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

/** Evidence presentation only: no persistence, IO, or operation ownership. */
internal object EvidenceUiSpacing {
    val navigationIcon = 20.dp
    val related = 4.dp
    val inline = FusionSpacing.inlineGap
    val row = FusionSpacing.row
}

@Composable
internal fun EvidenceDisclosure(
    label: String,
    stateKey: Any,
    content: @Composable () -> Unit,
) {
    var expanded by remember(stateKey) { mutableStateOf(false) }
    EvidenceDisclosure(label = label, expanded = expanded, onExpandedChange = { expanded = it }, content = content)
}

/** Controlled disclosure lets alternative inspectors share one visible detail area. */
@Composable
internal fun EvidenceDisclosure(
    label: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val duration = when (LocalFluidMotionMode.current) {
        FluidMotionMode.FULL -> 120
        FluidMotionMode.REDUCED -> 60
        FluidMotionMode.MINIMAL -> 0
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(EvidenceUiSpacing.inline)) {
        TextButton(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier.fillMaxWidth().heightIn(min = FusionSpacing.control)
                .semantics { stateDescription = if (expanded) "펼쳐짐" else "접힘" },
        ) {
            Text(label + if (expanded) " · 접기" else " · 펼치기",
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start, style = MaterialTheme.typography.labelLarge)
        }
        // No size animation: selectable text and selection handles never move during a transition.
        AnimatedVisibility(expanded, enter = fadeIn(tween(duration)), exit = fadeOut(tween(0))) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(EvidenceUiSpacing.inline)) {
                content()
            }
        }
    }
}

@Composable
internal fun EvidenceHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

/** Short selection task: bounded body with actions outside the scroll region. */
@Composable
internal fun EvidenceFocusedDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    val colors = LocalFusionSemanticColors.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val bodyMaxHeight = with(LocalDensity.current) { windowHeight.toDp() * .55f }
        .coerceAtLeast(FusionSpacing.control)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = FusionSpacing.objectShape,
        containerColor = colors.panel,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurface,
        title = title,
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = bodyMaxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(EvidenceUiSpacing.inline)) { text() }
        },
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}
