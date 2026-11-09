package com.projectnuke.fusion.handoff

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projectnuke.fusion.data.AppDatabase

@Composable
internal fun ExternalHandoffChoiceDialog(
    database: AppDatabase,
    handoff: ExternalHandoff,
    onSelect: (ExternalHandoffDestination, Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val projects by database.projectDao().observeProjects().collectAsStateWithLifecycle(initialValue = emptyList())
    val text = (handoff as? ExternalTextHandoff)?.text
    val isUrl = text?.let(ExternalHandoffDestinationPolicy::sharedUrl) != null
    val title = if (isUrl) "공유한 링크 저장" else "Fusion에 보관할 위치"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    if (handoff is ExternalDocumentHandoff) "공유한 문서를 어디에 둘까요?"
                    else "공유한 내용을 어디에 둘까요?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HandoffChoiceRow("현재 채팅") { onSelect(ExternalHandoffDestination.CHAT, null) }
                if (isUrl) {
                    HandoffChoiceRow("링크 저장 · 본문은 가져오지 않음") {
                        onSelect(ExternalHandoffDestination.INBOX, null)
                    }
                } else {
                    HandoffChoiceRow("Inbox") { onSelect(ExternalHandoffDestination.INBOX, null) }
                }
                if (handoff is ExternalTextHandoff) {
                    HandoffChoiceRow("저장 지식") { onSelect(ExternalHandoffDestination.SAVED_KNOWLEDGE, null) }
                }
                if (projects.isEmpty()) {
                    Text("프로젝트에 넣으려면 먼저 프로젝트를 만들어 주세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("프로젝트", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                        items(projects.take(100), key = { it.id }) { project ->
                            HandoffChoiceRow(project.name) { onSelect(ExternalHandoffDestination.PROJECT, project.id) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun HandoffChoiceRow(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}
