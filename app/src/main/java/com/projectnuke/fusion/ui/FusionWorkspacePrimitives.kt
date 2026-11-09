package com.projectnuke.fusion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Operational work remains visible when the user's motion policy disables animation. */
@Composable
internal fun FusionBusyLine(modifier: Modifier = Modifier) {
    if (LocalFluidMotionMode.current == FluidMotionMode.FULL) {
        LinearProgressIndicator(modifier.fillMaxWidth())
    } else {
        Box(modifier.fillMaxWidth().height(4.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.24f))
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate })
    }
}
