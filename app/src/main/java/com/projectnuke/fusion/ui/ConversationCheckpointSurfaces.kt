package com.projectnuke.fusion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.data.ConversationCheckpointEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ConversationCheckpointCaptureDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onSave: (String, String, (Boolean) -> Unit) -> Unit,
) {
    val drafts: CreationDraftsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    CheckpointTextDialog(
        title = "체크포인트 저장",
        titleValue = drafts.checkpointTitle.takeIf { drafts.checkpointKey != null } ?: initialTitle,
        noteValue = drafts.checkpointNote.takeIf { drafts.checkpointKey != null }.orEmpty(),
        onTitleChange = { drafts.updateCheckpoint(it.take(160), drafts.checkpointNote) },
        onNoteChange = { drafts.updateCheckpoint(drafts.checkpointTitle, it.take(560)) },
        onDismiss = onDismiss,
        onConfirm = { savedTitle, savedNote, onResult -> onSave(savedTitle, savedNote, onResult) },
    )
}

@Composable
internal fun ConversationCheckpointListDialog(
    conversationTitle: String,
    checkpoints: List<ConversationCheckpointEntity>,
    onDismiss: () -> Unit,
    onOpen: (ConversationCheckpointEntity) -> Unit,
    onContinue: (ConversationCheckpointEntity) -> Unit,
    onRename: (ConversationCheckpointEntity, String, String, (Boolean) -> Unit) -> Unit,
    onDelete: (ConversationCheckpointEntity) -> Unit,
) {
    var selected by remember { mutableStateOf<ConversationCheckpointEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationCheckpointEntity?>(null) }
    var continueTarget by remember { mutableStateOf<ConversationCheckpointEntity?>(null) }
    // Rename entry + text survive Activity recreation in-memory (U02).
    val drafts: CreationDraftsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val renameTarget = drafts.renameCheckpoint
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("체크포인트", style = MaterialTheme.typography.titleLarge)
                Text(conversationTitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            if (checkpoints.isEmpty()) {
                Text("저장된 체크포인트가 없습니다.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    items(checkpoints, key = { it.id }) { checkpoint ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .clickable { onOpen(checkpoint) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(checkpoint.title, style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val date = remember(checkpoint.createdAt) {
                                        SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date(checkpoint.createdAt))
                                    }
                                    Text(date, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (checkpoint.note.isNotBlank()) {
                                        Text(checkpoint.note, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                                            overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                androidx.compose.foundation.layout.Box {
                                    androidx.compose.material3.IconButton(onClick = { selected = checkpoint }) {
                                        Text(
                                            "⋮", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.semantics { contentDescription = "${checkpoint.title} 작업" },
                                        )
                                    }
                                    androidx.compose.material3.DropdownMenu(
                                        expanded = selected?.id == checkpoint.id,
                                        onDismissRequest = { selected = null },
                                    ) {
                                        DropdownMenuItem(text = { Text("여기서 계속하기") }, onClick = { selected = null; continueTarget = checkpoint })
                                        DropdownMenuItem(text = { Text("이름 바꾸기") }, onClick = { selected = null; drafts.openCheckpointRename(checkpoint) })
                                        DropdownMenuItem(text = { Text("삭제") }, onClick = { selected = null; deleteTarget = checkpoint })
                                    }
                                }
                            }
                            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
    renameTarget?.let { checkpoint ->
        CheckpointTextDialog(
            title = "체크포인트 수정",
            titleValue = drafts.renameTitle,
            noteValue = drafts.renameNote,
            onTitleChange = { drafts.updateCheckpointRename(it, drafts.renameNote) },
            onNoteChange = { drafts.updateCheckpointRename(drafts.renameTitle, it) },
            onDismiss = { drafts.clearCheckpointRename() },
            onConfirm = { title, note, onResult -> onRename(checkpoint, title, note, onResult) },
            editableTitle = true,
            editableNote = true,
        )
    }
    deleteTarget?.let { checkpoint ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("체크포인트 삭제") },
            text = { Text("‘${checkpoint.title}’ 체크포인트를 삭제할까요?") },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } },
            confirmButton = { TextButton(onClick = { onDelete(checkpoint); deleteTarget = null }) { Text("삭제") } },
        )
    }
    continueTarget?.let { checkpoint ->
        AlertDialog(
            onDismissRequest = { continueTarget = null },
            title = { Text("새 대화로 이어가기") },
            text = { Text("이 체크포인트까지의 대화로 새 대화를 만듭니다. 기존 대화는 그대로 유지됩니다.") },
            dismissButton = { TextButton(onClick = { continueTarget = null }) { Text("취소") } },
            confirmButton = {
                TextButton(onClick = { onContinue(checkpoint); continueTarget = null }) { Text("새 대화 만들기") }
            },
        )
    }
}

@Composable
internal fun CheckpointTextDialog(
    title: String,
    titleValue: String,
    noteValue: String,
    onTitleChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, String, (Boolean) -> Unit) -> Unit,
    editableTitle: Boolean = true,
    editableNote: Boolean = true,
) {
    var saving by remember { mutableStateOf(false) }
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var localTitle by remember(titleValue) { mutableStateOf(titleValue) }
    var localNote by remember(noteValue) { mutableStateOf(noteValue) }
    AlertDialog(
        onDismissRequest = { if (!saving) currentOnDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CheckpointField("제목", localTitle, maxCodePoints = 80, onTitleChange = { localTitle = it; onTitleChange(it) }, enabled = editableTitle && !saving)
                CheckpointField("메모 (선택)", localNote, maxCodePoints = 280, onTitleChange = { localNote = it; onNoteChange(it) }, enabled = editableNote && !saving)
            }
        },
        dismissButton = { TextButton(onClick = { currentOnDismiss() }, enabled = !saving) { Text("취소") } },
        confirmButton = {
            TextButton(
                onClick = {
                    if (saving) return@TextButton
                    saving = true
                    currentOnConfirm(localTitle, localNote) { success ->
                        saving = false
                        if (success) currentOnDismiss()
                    }
                },
                enabled = localTitle.isNotBlank() && !saving,
            ) { Text(if (saving) "저장 중…" else if (editableNote && title.contains("수정")) "저장" else "체크포인트 저장") }
        },
    )
}

@Composable
private fun CheckpointField(label: String, value: String, maxCodePoints: Int, onTitleChange: (String) -> Unit, enabled: Boolean) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { raw ->
                    val count = raw.codePointCount(0, raw.length)
                    val end = raw.offsetByCodePoints(0, minOf(count, maxCodePoints))
                    onTitleChange(raw.substring(0, end))
                },
                enabled = enabled,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            )
        }
    }
}
