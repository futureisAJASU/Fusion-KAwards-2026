package com.projectnuke.fusion.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import kotlin.math.roundToInt

/** Transient presentation geometry only. Feature state and writes stay with the caller. */
@Stable
internal class FusionPopupAnchor {
    var bounds by mutableStateOf<IntRect?>(null)
    var press by mutableStateOf<Offset?>(null)
}

@Composable
internal fun rememberFusionPopupAnchor() = remember { FusionPopupAnchor() }

internal fun Modifier.fusionPopupAnchor(anchor: FusionPopupAnchor): Modifier =
    onGloballyPositioned { coordinates ->
        val rect = coordinates.boundsInWindow()
        anchor.bounds = IntRect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
    }.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) anchor.press = null
        false
    }.pointerInput(anchor) {
        // Observe without consuming: the existing clickable owns admission and the action.
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.let { anchor.press = it.position }
            }
        }
    }

internal object FusionPopupPlacement {
    fun position(anchor: IntRect, window: IntSize, popup: IntSize, margin: Int,
        topInset: Int, bottomInset: Int, rtl: Boolean, offset: IntOffset = IntOffset.Zero): IntOffset {
        val minX = margin
        val maxX = (window.width - popup.width - margin).coerceAtLeast(minX)
        val minY = topInset + margin
        val maxY = (window.height - bottomInset - popup.height - margin).coerceAtLeast(minY)
        val below = anchor.bottom + margin + offset.y
        val above = anchor.top - popup.height - margin + offset.y
        val y = when {
            below <= maxY -> below
            above >= minY -> above
            window.height - bottomInset - anchor.bottom >= anchor.top - topInset -> below.coerceIn(minY, maxY)
            else -> above.coerceIn(minY, maxY)
        }
        val x = (if (rtl) anchor.right - popup.width - offset.x else anchor.left + offset.x).coerceIn(minX, maxX)
        return IntOffset(x, y)
    }

    fun origin(anchor: IntRect, press: Offset?, position: IntOffset, popup: IntSize): TransformOrigin {
        val x = anchor.left + (press?.x ?: anchor.width / 2f)
        val y = anchor.top + (press?.y ?: anchor.height / 2f)
        // Origins outside the surface connect expansion to a trigger just beyond its edge.
        return TransformOrigin((x - position.x) / popup.width.coerceAtLeast(1), (y - position.y) / popup.height.coerceAtLeast(1))
    }
}

/** Compact, focusable anchored menu. Long reading/editing uses FusionReadingDialog. */
@Composable
internal fun FusionDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    containerColor: Color = LocalFusionSemanticColors.current.menu,
    anchor: FusionPopupAnchor? = null,
    scrollContent: Boolean = true,
    preferredHeight: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val topInset = WindowInsets.systemBars.getTop(density)
    val bottomInset = maxOf(WindowInsets.systemBars.getBottom(density), WindowInsets.ime.getBottom(density))
    val margin = with(density) { 12.dp.roundToPx() }
    val safeHeight = (configuration.screenHeightDp.dp - with(density) { (topInset + bottomInset + 2 * margin).toDp() }).coerceAtLeast(48.dp)
    val safeWidth = (configuration.screenWidthDp.dp - 24.dp).coerceAtLeast(48.dp)
    val maximumHeight = (if (preferredHeight != null) safeHeight else safeHeight * .7f).coerceIn(48.dp, 480.dp)
    val scrollState = if (scrollContent) rememberScrollState() else null
    // Every open must present the top of the scroll viewport: never retain a stale scroll position
    // across dismiss/reopen, IME toggle, or safe-area change.
    LaunchedEffect(expanded) {
        if (expanded) scrollState?.scrollTo(0)
    }
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    val motion = LocalFluidMotionMode.current
    val progress = remember { Animatable(if (motion == FluidMotionMode.FULL) 0f else 1f) }
    LaunchedEffect(motion) {
        if (motion == FluidMotionMode.FULL) progress.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
        else progress.snapTo(1f)
    }
    val provider = remember(anchor?.bounds, anchor?.press, density, offset, topInset, bottomInset) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val trigger = anchor?.bounds ?: anchorBounds
                val delta = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
                val position = FusionPopupPlacement.position(trigger, windowSize, popupContentSize, margin,
                    topInset, bottomInset, layoutDirection == LayoutDirection.Rtl, delta)
                origin = FusionPopupPlacement.origin(trigger, anchor?.press, position, popupContentSize)
                return position
            }
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)) {
        Surface(
            modifier = Modifier.widthIn(max = minOf(320.dp, safeWidth)).heightIn(max = maximumHeight)
                .then(if (preferredHeight != null) Modifier.height(preferredHeight.coerceIn(48.dp, maximumHeight)) else Modifier)
                .then(modifier)
                .graphicsLayer {
                    transformOrigin = origin
                    scaleX = .92f + .08f * progress.value
                    scaleY = scaleX
                    alpha = progress.value
                },
            shape = RoundedCornerShape(16.dp), color = containerColor,
            contentColor = LocalFusionSemanticColors.current.onSurface, shadowElevation = 8.dp,
        ) {
            Column(Modifier.then(if (preferredHeight != null) Modifier.fillMaxHeight() else Modifier)
                .then(scrollState?.let { Modifier.verticalScroll(it) } ?: Modifier)
                .padding(PaddingValues(vertical = 8.dp)), content = content)
        }
    }
}
