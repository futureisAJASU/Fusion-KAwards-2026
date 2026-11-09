package com.projectnuke.fusion.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun StructuredExtractionSurface(
    source: StructuredExtractionSource?,
    onDismiss: () -> Unit,
    handoffEnabled: Boolean = true,
    onSaveAsNote: ((StructuredExtractionResult) -> Unit)? = null,
    onAsk: ((String) -> Unit)? = null,
    onAddToProject: ((StructuredExtractionResult) -> Unit)? = null,
    onAccept: ((StructuredExtractionResult) -> Unit)? = null,
) {
    if (source == null) return
    var selectedType by remember(source) { mutableStateOf<StructuredExtractionType?>(null) }
    val result by produceState<StructuredExtractionResult?>(null, source, selectedType) {
        value = selectedType?.let { type ->
            withContext(Dispatchers.Default) { StructuredExtractionPolicy.extract(source, type) }
        }
    }
    val clipboard = LocalClipboardManager.current

    if (selectedType == null) {
        FusionReadingDialog(
            onDismissRequest = onDismiss,
            title = { Text("구조화해서 추출") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${source.kind} · ${source.title}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StructuredExtractionType.entries.forEach { type ->
                        TextButton(
                            onClick = { selectedType = type },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(type.label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } },
        )
        return
    }

    val extracted = result
    var actionsExpanded by remember(source, selectedType) { mutableStateOf(false) }
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        title = { Text(extracted?.title ?: "추출 중…", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            if (extracted == null) {
                FusionBusyLine(Modifier.fillMaxWidth())
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("출처 · ${extracted.sourceKind} · ${extracted.sourceTitle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (extracted.type == StructuredExtractionType.TABLE && extracted.rows.isNotEmpty()) {
                        // One shared horizontal coordinate system for the whole
                        // logical table (U04): header and every row scroll
                        // together so columns never misalign.
                        val tableScroll = rememberScrollState()
                        ExtractionTableRow(extracted.columns, header = true, scrollState = tableScroll)
                        HorizontalDivider()
                        extracted.rows.forEachIndexed { index, row ->
                            ExtractionTableRow(row.cells, header = false, scrollState = tableScroll)
                            if (index < extracted.rows.lastIndex) HorizontalDivider()
                        }
                    } else {
                        extracted.rows.forEachIndexed { index, row ->
                            if (row.cells.size >= 2) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(row.cells[0], modifier = Modifier.weight(.38f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 4, overflow = TextOverflow.Ellipsis)
                                    Text(row.cells.drop(1).joinToString(" · "), modifier = Modifier.weight(.62f),
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            } else {
                                Text(row.cells.joinToString(" · "), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                            if (index < extracted.rows.lastIndex) HorizontalDivider()
                        }
                    }
                    extracted.warnings.forEach { warning ->
                        Text(warning, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { extracted?.let { clipboard.setText(AnnotatedString(StructuredExtractionPolicy.asPlainText(it))) } },
                enabled = extracted != null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("복사") }
        },
        dismissButton = {
            if (onAccept != null && extracted != null) {
                TextButton(
                    onClick = { extracted?.let(onAccept) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("이 단계 결과로 사용") }
            }
            if (extracted != null && (onSaveAsNote != null || onAsk != null || onAddToProject != null)) {
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { actionsExpanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("추가 작업")
                    }
                    FusionDropdownMenu(actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                        onSaveAsNote?.let { save ->
                            DropdownMenuItem(
                                enabled = handoffEnabled,text = { Text("노트로 저장") }, onClick = {
                                actionsExpanded = false
                                save(extracted)
                            })
                        }
                        onAsk?.let { ask ->
                            DropdownMenuItem(text = { Text("추출한 내용으로 질문") }, onClick = {
                                actionsExpanded = false
                                ask(StructuredExtractionPolicy.askPrompt(extracted))
                            })
                        }
                        onAddToProject?.let { add ->
                            DropdownMenuItem(
                                enabled = handoffEnabled,text = { Text("Project에 추가") }, onClick = {
                                actionsExpanded = false
                                add(extracted)
                            })
                        }
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") }
        },
    )
}

@Composable
private fun ExtractionTableRow(
    cells: List<String>,
    header: Boolean,
    scrollState: ScrollState = rememberScrollState(),
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scrollState).heightIn(min = 48.dp).padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        cells.take(STRUCTURED_EXTRACTION_MAX_COLUMNS).forEach { cell ->
            var expanded by remember(cell) { mutableStateOf(false) }
            Text(
                text = cell,
                modifier = Modifier
                    .width(140.dp)
                    .heightIn(min = 32.dp)
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
