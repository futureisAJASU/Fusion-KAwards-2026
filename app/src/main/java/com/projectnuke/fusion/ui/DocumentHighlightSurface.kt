package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.documents.DocumentHighlightDraft
import com.projectnuke.fusion.documents.DocumentHighlightRepository
import com.projectnuke.fusion.documents.DocumentHighlightWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DocumentHighlightCaptureDialog(
    database: AppDatabase,
    draft: DocumentHighlightDraft,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val repository = remember(database) { DocumentHighlightRepository(database) }
    var quote by remember(draft) { mutableStateOf(draft.quotedText.take(DocumentHighlightDraftBounds.MAX_QUOTE)) }
    var note by remember(draft) { mutableStateOf(draft.userNote.orEmpty().take(DocumentHighlightDraftBounds.MAX_NOTE)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("하이라이트 저장") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "${draft.documentTitle}${draft.pageNumber?.let { " · p.$it" }.orEmpty()} · 저장 지식에 추가",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = quote,
                    onValueChange = { quote = it.take(DocumentHighlightDraftBounds.MAX_QUOTE) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("인용할 내용") },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !saving,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(DocumentHighlightDraftBounds.MAX_NOTE) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 54.dp),
                    label = { Text("메모 (선택)") },
                    minLines = 2,
                    enabled = !saving,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (saving) return@TextButton
                saving = true
                scope.launch {
                    try {
                        when (val result = repository.save(draft.copy(quotedText = quote, userNote = note))) {
                            is DocumentHighlightWriteResult.Stored -> onSaved(result.savedKnowledgeId)
                            DocumentHighlightWriteResult.Invalid -> error = "인용문이나 출처 정보를 확인해 주세요."
                            DocumentHighlightWriteResult.SensitiveContent -> error = "민감한 정보가 감지되어 저장하지 않았습니다."
                            DocumentHighlightWriteResult.SourceUnavailable -> error = "원본 문서를 찾을 수 없어 저장하지 않았습니다."
                            DocumentHighlightWriteResult.CapacityReached -> error = "저장된 하이라이트 한도에 도달했습니다."
                            DocumentHighlightWriteResult.Failed -> error = "하이라이트를 저장하지 못했습니다."
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "하이라이트를 저장하지 못했습니다."
                    } finally {
                        saving = false
                    }
                }
            }, enabled = !saving && quote.isNotBlank()) { Text(if (saving) "저장 중…" else "저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("취소") } },
    )
}

private object DocumentHighlightDraftBounds {
    const val MAX_QUOTE = 1_200
    const val MAX_NOTE = 800
}
