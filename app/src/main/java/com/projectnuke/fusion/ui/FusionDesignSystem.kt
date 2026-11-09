package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

/**
 * Shared Fusion spacing, sizing and shape tokens.
 *
 * These extend the existing screen/row/section/control scale with the semantic
 * values the compact chat action surfaces share, so chips, menu rows and
 * dividers stop drifting between scattered hard-coded dimensions. Values are
 * additive only; existing screens keep their current look.
 */
internal object FusionSpacing {
    val screen = 16.dp
    val row = 12.dp
    val section = 24.dp
    val control = 48.dp
    val objectShape = RoundedCornerShape(16.dp)

    /** Accessible hit/layout floor for a compact interactive chip. */
    val chipMinHeight = 48.dp
    val chipShape = RoundedCornerShape(16.dp)
    val chipPaddingHorizontal = 14.dp
    val chipPaddingVertical = 8.dp

    /** Minimum layout height for a single overflow/secondary menu row. */
    val menuItemMinHeight = 48.dp

    val dividerThickness = 1.dp
    val inlineGap = 8.dp
    val blockGap = 12.dp
}

@Composable
internal fun FusionPageHeader(title: String, onBack: () -> Unit, subtitle: String? = null, actionDescription: String = "이전 화면으로") {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(FusionSpacing.control)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, actionDescription)
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalFusionSemanticColors.current.onSurfaceVariant) }
        }
    }
}

/** Short confirmations share Fusion's palette and spacing without changing action ownership. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FusionConfirmationDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    alternativeButton: (@Composable () -> Unit)? = null,
) {
    val semantic = LocalFusionSemanticColors.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = FusionSpacing.objectShape,
        containerColor = semantic.panel,
        titleContentColor = semantic.onSurface,
        textContentColor = semantic.onSurface,
        title = { ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() } },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FusionSpacing.row)) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                alternativeButton?.invoke()
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        },
    )
}

/** Adaptive reading shell; callbacks retain their original feature/lifecycle owner. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FusionReadingDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = FusionSpacing.objectShape,
    containerColor: Color = LocalFusionSemanticColors.current.background,
    iconContentColor: Color = LocalFusionSemanticColors.current.onSurface,
    titleContentColor: Color = LocalFusionSemanticColors.current.onSurface,
    textContentColor: Color = LocalFusionSemanticColors.current.onSurface,
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    scrollBody: Boolean = true,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(
        dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside,
        securePolicy = properties.securePolicy,
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
    )) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val compact = maxWidth < 600.dp
            Surface(modifier = modifier.then(if (compact) Modifier.fillMaxSize() else Modifier.widthIn(max = 640.dp).fillMaxHeight(.9f)),
                shape = if (compact) RoundedCornerShape(0.dp) else shape,
                color = containerColor, contentColor = textContentColor, tonalElevation = tonalElevation) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = FusionSpacing.screen)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(onClick = onDismissRequest, modifier = Modifier.size(FusionSpacing.control)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "이전 화면으로")
                        }
                        if (icon != null) CompositionLocalProvider(LocalContentColor provides iconContentColor) { icon() }
                        Column(Modifier.weight(1f)) {
                            CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                                ProvideTextStyle(MaterialTheme.typography.titleLarge) { title?.invoke() }
                            }
                        }
                    }
                    val bodyModifier = Modifier.weight(1f).fillMaxWidth().then(if (scrollBody) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    Column(bodyModifier.padding(vertical = 12.dp)) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text?.invoke() }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}
