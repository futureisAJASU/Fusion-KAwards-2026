package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.knowledge.WebClipDocument
import com.projectnuke.fusion.knowledge.WebClipCapture
import com.projectnuke.fusion.knowledge.WebClipFailure

internal sealed interface WebClipPreviewState {
    val requestId: String
    val url: String

    data class Loading(override val requestId: String, override val url: String) : WebClipPreviewState
    data class Ready(override val requestId: String, override val url: String, val capture: WebClipCapture) : WebClipPreviewState {
        val document: WebClipDocument get() = capture.document
    }
    data class Failed(override val requestId: String, override val url: String, val reason: WebClipFailure) : WebClipPreviewState
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun WebClipPreviewDialog(
    state: WebClipPreviewState,
    projects: List<ProjectEntity>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSaveToInbox: () -> Unit,
    onSaveToKnowledge: () -> Unit,
    onSaveToProject: (Long) -> Unit,
) {
    FusionReadingDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("웹 본문 가져오기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FusionSpacing.row)) {
                Text(
                    "입력한 링크(다시 열 때 사용): ${state.url}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state) {
                    is WebClipPreviewState.Loading -> Text("선택한 페이지의 본문을 가져오는 중입니다.")
                    is WebClipPreviewState.Failed -> Text(
                        failureMessage(state.reason),
                        color = MaterialTheme.colorScheme.error,
                    )
                    is WebClipPreviewState.Ready -> {
                        Text(
                            "본문 출처(리디렉션 후): ${state.document.effectiveUrl ?: "출처 주소를 확인할 수 없습니다."}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(state.document.displayTitle, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "저장하면 지금 가져온 내용의 로컬 스냅샷이 보관됩니다. 원본 페이지가 바뀌어도 저장한 내용은 바뀌지 않습니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (projects.isNotEmpty()) {
                            Text("Project에 저장", style = MaterialTheme.typography.labelLarge)
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 136.dp)) {
                                items(projects.take(100), key = { it.id }) { project ->
                                    TextButton(
                                        onClick = { onSaveToProject(project.id) },
                                        enabled = !saving,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(project.name) }
                                }
                            }
                        }
                        Text(
                            if (state.document.textTruncated) "미리보기 · 저장 가능한 앞부분" else "미리보기",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        SelectionContainer {
                            Text(
                                state.document.text,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is WebClipPreviewState.Loading -> TextButton(onClick = onDismiss) { Text("취소") }
                is WebClipPreviewState.Failed -> TextButton(onClick = onRetry, enabled = !saving) { Text("다시 가져오기") }
                is WebClipPreviewState.Ready -> FlowRow(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("닫기") }
                    OutlinedButton(onClick = onSaveToKnowledge, enabled = !saving,
                        shape = FusionSpacing.objectShape) { Text("저장 지식으로 저장") }
                    Button(onClick = onSaveToInbox, enabled = !saving,
                        shape = FusionSpacing.objectShape) { Text(if (saving) "저장 중…" else "Inbox에 저장") }
                }
            }
        },
    )
}

private fun failureMessage(reason: WebClipFailure): String = when (reason) {
    WebClipFailure.INVALID_URL -> "HTTPS 링크만 가져올 수 있습니다."
    WebClipFailure.PRIVATE_NETWORK -> "공개 웹 페이지 주소만 가져올 수 있습니다."
    WebClipFailure.NETWORK -> "페이지에 연결하지 못했습니다. 연결과 링크를 확인해 주세요."
    WebClipFailure.HTTP_STATUS -> "페이지가 본문을 제공하지 않았습니다."
    WebClipFailure.UNSUPPORTED_CONTENT -> "HTML 페이지 본문만 가져올 수 있습니다."
    WebClipFailure.RESPONSE_TOO_LARGE -> "페이지가 가져오기 한도를 넘었습니다."
    WebClipFailure.INVALID_CHARSET,
    WebClipFailure.MALFORMED_CONTENT -> "페이지 문자 인코딩을 안전하게 읽지 못했습니다."
    WebClipFailure.NO_READABLE_TEXT -> "읽을 수 있는 본문 텍스트가 없습니다."
}
