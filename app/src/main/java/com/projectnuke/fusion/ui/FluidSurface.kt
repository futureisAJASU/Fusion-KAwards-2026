package com.projectnuke.fusion.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Product-level motion fallback for Fluid Surface UX.
 *
 * FULL keeps the short adhesion/settle response, REDUCED trims the travel and
 * duration, and MINIMAL keeps the interaction semantically identical without
 * animated scale. The value is intentionally independent from feature state so
 * visuals can degrade without changing behavior.
 */
@Immutable
internal enum class FluidMotionMode {
    FULL,
    REDUCED,
    MINIMAL,
}

internal val LocalFluidMotionMode = compositionLocalOf { FluidMotionMode.FULL }

/**
 * Pure motion-fallback resolution stem, host-testable without Android. The
 * system-provided inputs come from the app root: animator duration scale (0
 * means animations are disabled at the OS level) and whether an accessibility
 * service is active. REDUCED keeps every interaction semantically identical
 * with smaller/shorter motion; MINIMAL still runs the tap handler but never
 * animates scale.
 */
internal object FluidMotionPolicy {
    fun resolve(
        animatorDurationScale: Float,
        accessibilityServiceActive: Boolean,
    ): FluidMotionMode = when {
        animatorDurationScale <= 0f -> FluidMotionMode.MINIMAL
        accessibilityServiceActive -> FluidMotionMode.REDUCED
        else -> FluidMotionMode.FULL
    }
}

@Composable
internal fun FluidPressableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    role: Role? = null,
    /**
     * Single-owner selection semantics. When non-null the surface exposes the
     * selected state on the SAME pointer/click node that [role] describes, so
     * selectable controls never need a second nested clickable/selectable
     * child. Visual selection state is owned by the caller, exactly as today.
     */
    isSelected: Boolean? = null,
    motionMode: FluidMotionMode = LocalFluidMotionMode.current,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    val isSelectedState = isSelected
    val pressed by interactionSource.collectIsPressedAsState()
    val targetScale = when {
        !enabled || !pressed || motionMode == FluidMotionMode.MINIMAL -> 1f
        motionMode == FluidMotionMode.REDUCED -> 0.992f
        else -> 0.982f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = when (motionMode) {
            FluidMotionMode.FULL -> spring(
                dampingRatio = 0.72f,
                stiffness = 720f,
            )
            FluidMotionMode.REDUCED -> tween(durationMillis = 70)
            FluidMotionMode.MINIMAL -> tween(durationMillis = 0)
        },
        label = "FluidPressScale",
    )

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .semantics {
                if (isSelectedState != null) this.selected = isSelectedState
            }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = role,
                onClick = onClick,
            ),
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        border = border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}
