package com.projectnuke.fusion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.retrieval.DocumentSectionOutline
import com.projectnuke.fusion.retrieval.StructuredDocumentSection
import kotlinx.coroutines.CancellationException

internal sealed interface DocumentOutlineLoadState {
    data object Loading : DocumentOutlineLoadState
    data object Empty : DocumentOutlineLoadState
    data class Ready(val outline: DocumentSectionOutline) : DocumentOutlineLoadState
    data object Failed : DocumentOutlineLoadState
}

@Composable
internal fun rememberDocumentOutlineLoadState(
    fileId: Long,
    filePath: String,
    load: suspend () -> DocumentSectionOutline?,
): State<DocumentOutlineLoadState> = produceState<DocumentOutlineLoadState>(DocumentOutlineLoadState.Loading, fileId, filePath) {
    value = DocumentOutlineLoadState.Loading
    try {
        val outline = load()
        value = when {
            outline == null -> DocumentOutlineLoadState.Failed
            outline.sections.isEmpty() -> DocumentOutlineLoadState.Empty
            else -> DocumentOutlineLoadState.Ready(outline)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        value = DocumentOutlineLoadState.Failed
    }
}

@Composable
internal fun DocumentSectionOutlineSurface(
    file: ProjectFileEntity,
    controller: ProjectKnowledgeController,
    onDismiss: () -> Unit,
    onAskSection: (StructuredDocumentSection) -> Unit,
    onSaveSection: (StructuredDocumentSection) -> Unit,
) {
    val outlineState by rememberDocumentOutlineLoadState(file.id, file.localPath) {
        controller.inspectSectionOutline(file)
    }
    
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        title = { Text("문서 목차", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = { DocumentSectionOutlineContent(file.displayName, outlineState, onAskSection, onSaveSection) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
internal fun DocumentSectionOutlineContent(
    fileName: String,
    state: DocumentOutlineLoadState,
    onAskSection: (StructuredDocumentSection) -> Unit,
    onSaveSection: (StructuredDocumentSection) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(fileName, style = MaterialTheme.typography.titleSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "제목을 기반으로 섹션을 구분해 찾았습니다. 본문 페이지 배치와 현저히 다를 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("섹션 경계는 질문 맥락으로만 쓰입니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (state) {
            DocumentOutlineLoadState.Loading -> {
                Spacer(Modifier.height(8.dp))
                Text("\uBB38\uC11C \uBAA9\uCC28\uB97C \uBD88\uB7EC\uC624\uB294 \uC911\u2026", style = MaterialTheme.typography.bodyMedium)
            }
            DocumentOutlineLoadState.Failed -> {
                Spacer(Modifier.height(8.dp))
                Text("문서 섹션을 읽을 수 없습니다.", style = MaterialTheme.typography.bodyMedium)
            }
            DocumentOutlineLoadState.Empty -> {
                Spacer(Modifier.height(8.dp))
                Text("명시적 구조를 찾지 못했습니다.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is DocumentOutlineLoadState.Ready -> {
                val outline = state.outline
                if (outline.partial) {
                    Text("처리 범위까지만 사용됩니다.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                outline.sections.forEachIndexed { index, section ->
                    var expanded by remember(fileName, index) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(
                            Modifier.weight(1f).heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { onAskSection(section) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(section.title, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                buildString {
                                    append(section.kind.label)
                                    section.pageNumber?.let { append(" · p.$it") }
                                    if (section.truncated) append(" · 일부 생략")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                            Text(section.text, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            IconButton(onClick = { expanded = true }, modifier = Modifier.size(FusionSpacing.control)) {
                                Icon(Icons.Default.MoreVert, "${section.title} 작업")
                            }
                            FusionDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("섹션 저장") },
                                    enabled = !section.truncated,
                                    onClick = { expanded = false; onSaveSection(section) },
                                )
                            }
                        }
                    }
                    if (index < outline.sections.lastIndex) androidx.compose.material3.HorizontalDivider()
                }
            }
        }
    }
}
