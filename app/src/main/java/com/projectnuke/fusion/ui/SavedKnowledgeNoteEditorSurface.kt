package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteEditorDraft
import com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_BODY_CHARS
import com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_REFERENCES

@Composable
internal fun SavedKnowledgeNoteEditorSurface(
    draft: SavedKnowledgeNoteEditorDraft,
    saving: Boolean,
    error: String?,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onAddSource: () -> Unit,
    onRemoveSource: (Int) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    allowSourceEditing: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("연구 노트") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("본문은 편집할 수 있고 출처 연결은 별도로 보관됩니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                draft.projectId?.let {
                    Text("Project · ${draft.projectName ?: "프로젝트"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("제목") },
                    singleLine = true,
                    enabled = !saving,
                )
                OutlinedTextField(
                    value = draft.body,
                    onValueChange = onBodyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("노트") },
                    minLines = 5,
                    maxLines = 16,
                    supportingText = { Text("${draft.body.length} / $MAX_SAVED_NOTE_BODY_CHARS") },
                    enabled = !saving,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("출처 ${draft.references.size}개", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (allowSourceEditing) TextButton(onClick = onAddSource,
                        enabled = !saving && draft.references.size < MAX_SAVED_NOTE_REFERENCES) { Text("출처 삽입") }
                }
                draft.references.forEachIndexed { index, reference ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(reference.title, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(noteSourceKindLabel(reference.identity.kind), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (allowSourceEditing) IconButton(onClick = { onRemoveSource(index) }, enabled = !saving) {
                            Icon(Icons.Default.Close, contentDescription = "${reference.title} 출처 제거")
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !saving) { Text(if (saving) "저장 중…" else "저장") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("취소") } },
    )
}

internal fun noteSourceKindLabel(kind: com.projectnuke.fusion.chat.SelectionSourceKind): String = when (kind) {
    com.projectnuke.fusion.chat.SelectionSourceKind.CONVERSATION -> "대화"
    com.projectnuke.fusion.chat.SelectionSourceKind.PROJECT -> "프로젝트"
    com.projectnuke.fusion.chat.SelectionSourceKind.PROJECT_DOCUMENT -> "프로젝트 문서"
    com.projectnuke.fusion.chat.SelectionSourceKind.SAVED_KNOWLEDGE -> "저장 지식"
    com.projectnuke.fusion.chat.SelectionSourceKind.ATTACHED_DOCUMENT -> "첨부 문서"
    com.projectnuke.fusion.chat.SelectionSourceKind.MEMORY -> "메모리"
}
