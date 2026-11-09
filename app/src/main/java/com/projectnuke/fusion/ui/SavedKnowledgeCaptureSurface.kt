package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import com.projectnuke.fusion.knowledge.SavedKnowledgeCaptureState
import com.projectnuke.fusion.knowledge.SavedKnowledgeScope

@Composable
internal fun SavedKnowledgeCaptureSurface(
    state: SavedKnowledgeCaptureState,
    onTitleChange: (String) -> Unit,
    onScopeChange: (SavedKnowledgeScope) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = state.draft ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.saved) "노트 저장 완료" else "노트 저장 확인") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (state.saved) {
                    Text("확인한 답변 사본을 저장했습니다.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "저장할 제목과 보이는 답변 사본을 확인해 주세요. 원본 대화가 삭제되어도 이 사본은 별도로 남습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = onTitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("제목") },
                        singleLine = true,
                        enabled = !state.saving,
                    )
                    Spacer(Modifier.height(6.dp))
                    ScopeOption(
                        selected = state.scope == SavedKnowledgeScope.PERSONAL,
                        enabled = !state.saving,
                        title = "개인 저장 지식",
                        onClick = { onScopeChange(SavedKnowledgeScope.PERSONAL) },
                    )
                    if (draft.projectId != null && !draft.projectName.isNullOrBlank()) {
                        ScopeOption(
                            selected = state.scope == SavedKnowledgeScope.PROJECT,
                            enabled = !state.saving,
                            title = "${draft.projectName} 프로젝트",
                            onClick = { onScopeChange(SavedKnowledgeScope.PROJECT) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("저장될 답변 사본", fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            draft.visibleText,
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (state.saved) {
                TextButton(onClick = onDismiss) { Text("완료") }
            } else {
                TextButton(onClick = onSave, enabled = !state.saving && state.title.isNotBlank()) {
                    if (state.saving) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                    else Text("저장")
                }
            }
        },
        dismissButton = {
            if (!state.saved) TextButton(onClick = onDismiss, enabled = !state.saving) { Text("취소") }
        },
    )
}

@Composable
private fun ScopeOption(selected: Boolean, enabled: Boolean, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        ).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}
