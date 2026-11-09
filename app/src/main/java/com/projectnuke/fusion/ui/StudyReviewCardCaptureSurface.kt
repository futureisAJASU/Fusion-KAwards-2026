package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.study.QuickReviewCardCaptureState
import com.projectnuke.fusion.study.ReviewCardScope

@Composable
internal fun StudyReviewCardCaptureSurface(
    state: QuickReviewCardCaptureState,
    projectName: String?,
    onFrontChange: (String) -> Unit,
    onBackChange: (String) -> Unit,
    onScopeChange: (ReviewCardScope) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val draft = state.draft ?: return
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("복습 카드") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft.front,
                    onValueChange = onFrontChange,
                    label = { Text("앞면 · 질문") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
                OutlinedTextField(
                    value = draft.back,
                    onValueChange = onBackChange,
                    label = { Text("뒷면 · 답") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 10,
                )
                Text("저장 전에 내용을 확인하고 고칠 수 있습니다. 원본이 바뀌거나 삭제되어도 카드에는 지금 저장한 내용이 남습니다.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = !state.saving,
                        onClick = { onScopeChange(ReviewCardScope.PERSONAL) },
                    ) { Text(if (draft.scope == ReviewCardScope.PERSONAL) "✓ 개인" else "개인") }
                    if (draft.projectId != null && projectName != null) TextButton(
                        enabled = !state.saving,
                        onClick = { onScopeChange(ReviewCardScope.PROJECT) },
                    ) { Text(if (draft.scope == ReviewCardScope.PROJECT) "✓ $projectName" else projectName) }
                }
                Text("미리보기", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(draft.front.ifBlank { "질문" }, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(draft.back.ifBlank { "답" }, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !state.saving, onClick = onSave) { Text(if (state.saving) "저장 중…" else "저장") }
        },
        dismissButton = { TextButton(enabled = !state.saving, onClick = onDismiss) { Text("취소") } },
    )
}
