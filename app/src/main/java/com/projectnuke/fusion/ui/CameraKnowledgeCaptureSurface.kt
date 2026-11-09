package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.documents.CameraKnowledgeCaptureState
import com.projectnuke.fusion.documents.CameraOcrFailure
import com.projectnuke.fusion.documents.CameraOcrStatus

@Composable
internal fun CameraKnowledgeCaptureSurface(
    state: CameraKnowledgeCaptureState,
    projectAvailable: Boolean,
    actionBusy: Boolean = false,
    onRetry: () -> Unit,
    onAddToChat: () -> Unit,
    onAddToProject: () -> Unit,
    onSaveOcrTextToInbox: () -> Unit,
    onDismiss: () -> Unit,
) {
    val failureText = when (state.failure) {
        CameraOcrFailure.INVALID_CAPTURE -> "캡처된 이미지가 비어 있거나 올바르지 않습니다. 다시 촬영해 주세요."
        CameraOcrFailure.TOO_LARGE -> "이미지가 20MB 한도를 넘었습니다. 다시 촬영해 주세요."
        CameraOcrFailure.NO_TEXT -> "이미지에서 읽을 텍스트가 없습니다. 다시 촬영할 수 있습니다."
        CameraOcrFailure.PARTIAL -> "텍스트 일부를 읽지 못했습니다. 저장하지 않았습니다. 다시 시도해 주세요."
        CameraOcrFailure.TOO_MANY_PAGES -> "이미지를 처리할 수 없습니다. 다시 촬영해 주세요."
        CameraOcrFailure.TOO_MUCH_TEXT -> "인식한 텍스트가 처리 한도를 넘었습니다."
        CameraOcrFailure.TIMEOUT -> "기기 내 텍스트 인식 시간이 초과되었습니다. 다시 시도해 주세요."
        CameraOcrFailure.UNAVAILABLE -> "카메라 자료를 읽거나 저장하지 못했습니다. 다시 시도해 주세요."
        null -> null
    }
    AlertDialog(
        onDismissRequest = { if (!actionBusy) onDismiss() },
        title = { Text(if (state.displayName.isBlank()) "카메라 자료" else state.displayName, maxLines = 2) },
        text = {
            Column {
                Text("카메라 · 기기 내 OCR", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (state.status) {
                    CameraOcrStatus.IDLE -> Text("촬영 자료를 확인하는 중입니다.")
                CameraOcrStatus.PROCESSING -> {
                        Text("기기 안에서 텍스트를 인식하고 있습니다. 이미지는 외부로 전송되지 않습니다.",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        FusionBusyLine(Modifier.fillMaxWidth())
                    }
                CameraOcrStatus.PREVIEW -> {
                        if (actionBusy) Text("자료를 추가하는 중입니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Text(
                                state.previewText + if (state.previewTruncated) "\n\n[미리보기 일부]" else "",
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState()).padding(top = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onSaveOcrTextToInbox, enabled = !actionBusy) {
                            Text("OCR 텍스트를 Inbox에 저장")
                        }
                    }
                    CameraOcrStatus.FAILED -> Text(
                        failureText.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            when (state.status) {
                CameraOcrStatus.PREVIEW -> TextButton(onClick = onAddToChat, enabled = !actionBusy) { Text("채팅에 첨부") }
                CameraOcrStatus.FAILED -> TextButton(onClick = onRetry, enabled = !actionBusy) { Text("다시 인식") }
                CameraOcrStatus.PROCESSING -> TextButton(onClick = onDismiss) { Text("취소") }
                CameraOcrStatus.IDLE -> TextButton(onClick = onDismiss) { Text("닫기") }
            }
        },
        dismissButton = {
            if (state.status == CameraOcrStatus.PREVIEW && projectAvailable) {
                TextButton(onClick = onAddToProject, enabled = !actionBusy) { Text("프로젝트 지식에 추가") }
            } else if (state.status != CameraOcrStatus.PROCESSING) {
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        },
    )
}
