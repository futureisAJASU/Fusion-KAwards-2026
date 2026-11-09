package com.projectnuke.fusion.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.chat.SelectionContextSnapshot
import com.projectnuke.fusion.projects.ProjectWorkPlanPreparedAction

/**
 * REVIEW_KNOWLEDGE handoff: after the user explicitly froze Project knowledge
 * in the existing selection surface, this dialog records the choice as the
 * step result or routes a prepared question into the real chat owner. The
 * Work Plan never answers anything itself, and "선택 다시 하기" keeps the
 * attempt RUNNING so the user can re-pick without a retry.
 */
@Composable
internal fun ProjectWorkPlanKnowledgeReviewDialog(
    action: ProjectWorkPlanPreparedAction,
    selection: SelectionContextSnapshot,
    onConfirm: () -> Unit,
    onAsk: () -> Unit,
    onReselect: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("선택한 자료 확인") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(action.attempt.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    action.attempt.instruction,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "선택한 자료 ${selection.sources.size}개",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selection.sources.forEach { source ->
                    Text(
                        "· ${source.title}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                }
                Text(
                    "확인하면 이 자료가 단계 결과로 기록됩니다. 질문으로 진행하면 선택한 자료와 함께 준비된 지시가 채팅에 들어가고, 직접 전송해야 답변이 생성됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("이 자료로 단계 확인") }
        },
        dismissButton = {
            TextButton(onClick = onAsk) { Text("이 자료로 질문") }
            TextButton(onClick = onReselect) { Text("선택 다시 하기") }
            TextButton(onClick = onCancel) { Text("취소") }
        },
    )
}
