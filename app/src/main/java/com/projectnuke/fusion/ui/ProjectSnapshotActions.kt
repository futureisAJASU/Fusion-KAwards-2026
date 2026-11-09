package com.projectnuke.fusion.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
internal fun ProjectActionsOverflow(
    enabled: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onConsistencyReview: () -> Unit = {},
    onRecentDigest: (() -> Unit)? = null,
    onCreateWorkPlan: (() -> Unit)? = null,
    onCreateReport: (() -> Unit)? = null,
    onOpenWorkPlanHistory: (() -> Unit)? = null,
    onOpenPulse: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberFusionPopupAnchor()
    androidx.compose.foundation.layout.Box(modifier) {
        IconButton(
            enabled = enabled,
            onClick = { expanded = true },
            modifier = Modifier.fusionPopupAnchor(anchor)
                .semantics { contentDescription = "프로젝트 작업 메뉴" },
        ) {
            Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FusionDropdownMenu(expanded, { expanded = false }, anchor = anchor, scrollContent = true) {
            onCreateWorkPlan?.let { onClick ->
                DropdownMenuItem(text = { Text("작업 계획 만들기") }, enabled = enabled, onClick = {
                    expanded = false
                    onClick()
                })
            }
            onCreateReport?.let { onClick ->
                DropdownMenuItem(text = { Text("보고서 만들기") }, enabled = enabled, onClick = {
                    expanded = false
                    onClick()
                })
            }
            onOpenWorkPlanHistory?.let { onClick ->
                DropdownMenuItem(text = { Text("작업 계획 기록") }, enabled = enabled, onClick = {
                    expanded = false
                    onClick()
                })
            }
            onOpenPulse?.let { onClick ->
                DropdownMenuItem(text = { Text("Project Pulse") }, enabled = enabled, onClick = {
                    expanded = false
                    onClick()
                })
            }
            DropdownMenuItem(text = { Text("일관성 점검") }, enabled = enabled, onClick = {
                expanded = false
                onConsistencyReview()
            })
            onRecentDigest?.let { onClick ->
                DropdownMenuItem(text = { Text("최근 내용 요약") }, enabled = enabled, onClick = {
                    expanded = false
                    onClick()
                })
            }
            DropdownMenuItem(text = { Text("프로젝트 내보내기") }, enabled = enabled, onClick = { expanded = false; onExport() })
            DropdownMenuItem(text = { Text("스냅샷 가져오기") }, enabled = enabled, onClick = { expanded = false; onImport() })
        }
    }
}

@Composable
internal fun ProjectSnapshotExportOptionsDialog(
    onDismiss: () -> Unit,
    onExport: (includeSourceFiles: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("프로젝트 내보내기") },
        text = {
            Text(
                "프로젝트 지침, 저장 지식, 할 일, 복습 카드와 접근 가능한 대화 요약(최대 32개)을 담습니다. " +
                    "원본 대화 내용과 기기별 알림은 포함하지 않습니다. 대화 요약은 가져올 때 저장 지식으로 보존합니다. " +
                    "자료 포함은 관리 중인 Project 파일을 추가합니다.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onExport(false) }) { Text("가벼운 내보내기") }
        },
        dismissButton = {
            TextButton(onClick = { onExport(true) }) { Text("자료 포함") }
        },
    )
}
