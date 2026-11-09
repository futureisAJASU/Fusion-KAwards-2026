package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.chat.ResearchRunPolicy

@Composable
internal fun ResearchModeStatus(generating: Boolean, status: String?, notice: String?, webEnabled: Boolean) {
    Text(if (generating) ResearchRunPolicy.stage(status) else notice ?: if (webEnabled) "조사 · 자료와 허용된 웹 검색 활용" else "조사 · 로컬 자료 활용 · 웹 검색 꺼짐",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
}
