package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.tasks.ProjectTaskCaptureState

@Composable
internal fun ProjectTaskCaptureSurface(
    state: ProjectTaskCaptureState,
    onTitleChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = state.draft ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.saved) "할 일 추가 완료" else "할 일로 만들기") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (state.saved) {
                    Text("${draft.projectName} 프로젝트에 할 일을 추가했습니다.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("${draft.projectName} 프로젝트 · 저장 전에 제목과 메모를 확인하세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = onTitleChange,
                        label = { Text("제목") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.saving,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.note,
                        onValueChange = onNoteChange,
                        label = { Text("메모 (선택)") },
                        minLines = 4,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.saving,
                    )
                    state.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (state.saved) TextButton(onClick = onDismiss) { Text("완료") }
            else TextButton(onClick = onSave, enabled = !state.saving && state.title.isNotBlank()) {
                if (state.saving) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                else Text("프로젝트에 추가")
            }
        },
        dismissButton = { if (!state.saved) TextButton(onClick = onDismiss, enabled = !state.saving) { Text("취소") } },
    )
}
