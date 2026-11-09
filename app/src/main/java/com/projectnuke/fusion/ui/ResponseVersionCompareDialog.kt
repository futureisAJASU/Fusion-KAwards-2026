package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Existing frozen compare owner; only selection, original-copy and dismissal callbacks. */
@Composable
internal fun ResponseVersionCompareDialog(
    snapshot: ResponseVersionCompareSnapshot,
    onSelectComparison: (Long) -> Unit,
    onCopySection: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var fullAnswers by remember(snapshot.activeMessageId) { mutableStateOf(false) }
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text("답변 비교") },
        scrollBody = false,
        text = {
            // A new key discards old result/scroll/disclosure immediately, before async work runs.
            key(snapshot) {
                if (ResponseVersionChangeProjection.valid(snapshot)) {
                    ComparisonBody(snapshot, fullAnswers, { fullAnswers = it }, onSelectComparison, onCopySection)
                } else {
                    Text("비교할 답변을 확인할 수 없습니다.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = FusionSpacing.control)) { Text("닫기") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComparisonBody(
    snapshot: ResponseVersionCompareSnapshot,
    fullAnswers: Boolean,
    onMode: (Boolean) -> Unit,
    onSelect: (Long) -> Unit,
    onCopy: (String) -> Unit,
) {
    val active = ResponseVersionComparePolicy.activeEntry(snapshot)!!
    val comparison = ResponseVersionComparePolicy.comparisonEntry(snapshot)!!
    val changes by produceState<ResponseVersionChanges?>(null, snapshot) {
        value = withContext(Dispatchers.Default) {
            ensureActive()
            val result = ResponseVersionChangeProjection.project(snapshot)
            ensureActive()
            result
        }
    }
    var showUnchanged by remember { mutableStateOf(false) }
    val colors = LocalFusionSemanticColors.current
    val ready = changes as? ResponseVersionChanges.Ready
    val visible = remember(ready, showUnchanged) {
        ready?.passages?.filter { showUnchanged || it.kind != ResponseVersionChangeKind.UNCHANGED }.orEmpty()
    }
    val candidates = remember(snapshot) { snapshot.entries.filter { it.messageId != active.messageId } }
    val versionState = rememberLazyListState(
        initialFirstVisibleItemIndex = candidates.indexOfFirst { it.messageId == comparison.messageId }.coerceAtLeast(0),
    )
    val listState = key(fullAnswers) { rememberLazyListState() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("response-version-reading-list"), state = listState,
        verticalArrangement = Arrangement.spacedBy(FusionSpacing.section),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap)) {
                Text("현재 답변 · ${active.ordinal}/${snapshot.totalCount}",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text("비교 답변 · ${comparison.ordinal}/${snapshot.totalCount}",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                LazyRow(state = versionState, horizontalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap)) {
                    items(candidates, key = { it.messageId }) { candidate ->
                        FilterChip(
                            selected = candidate.messageId == comparison.messageId,
                            onClick = { onSelect(candidate.messageId) },
                            label = { Text("${candidate.ordinal}/${snapshot.totalCount}") },
                            modifier = Modifier.heightIn(min = FusionSpacing.control).semantics {
                                contentDescription = "비교 답변 ${candidate.ordinal}/${snapshot.totalCount}"
                            },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap)) {
                    FilterChip(selected = !fullAnswers, onClick = { onMode(false) }, label = { Text("변경 내용") },
                        modifier = Modifier.heightIn(min = FusionSpacing.control))
                    FilterChip(selected = fullAnswers, onClick = { onMode(true) }, label = { Text("전체 답변") },
                        modifier = Modifier.heightIn(min = FusionSpacing.control))
                }
            }
        }
        if (fullAnswers) {
            item { CompareSection("현재 답변 · ${active.ordinal}/${snapshot.totalCount}", "현재 답변", active.visibleAnswer, onCopy) }
            item { CompareSection("비교 답변 · ${comparison.ordinal}/${snapshot.totalCount}", "비교 답변", comparison.visibleAnswer, onCopy) }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap)) {
                    when (val result = changes) {
                        null -> Text("변경 내용을 분석하고 있습니다.", style = MaterialTheme.typography.bodyMedium)
                        is ResponseVersionChanges.Unavailable -> Text(
                            "답변이 길거나 줄 수가 많아 상세 비교를 표시할 수 없습니다. 전체 답변에서 원문을 확인하세요.")
                        is ResponseVersionChanges.Ready -> {
                            if (result.identical) Text("두 답변의 표시된 텍스트가 동일합니다.")
                            else {
                                val counts = result.passages.groupingBy { it.kind }.eachCount()
                                Text("추가 ${counts[ResponseVersionChangeKind.ADDED] ?: 0}구간 · 삭제 ${counts[ResponseVersionChangeKind.REMOVED] ?: 0}구간 · 동일 ${counts[ResponseVersionChangeKind.UNCHANGED] ?: 0}구간",
                                    style = MaterialTheme.typography.titleSmall)
                            }
                            Text("비교 답변 기준의 줄 단위 차이입니다. 사실 확인 결과가 아닙니다.",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Text("반복되거나 이동한 줄은 추가·삭제로 표시될 수 있습니다.",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
            if (ready != null && ready.passages.any { it.kind == ResponseVersionChangeKind.UNCHANGED }) {
                item {
                    TextButton(onClick = { showUnchanged = !showUnchanged },
                        modifier = Modifier.heightIn(min = FusionSpacing.control).semantics {
                            stateDescription = if (showUnchanged) "펼쳐짐" else "접힘"
                        }) { Text(if (showUnchanged) "동일한 내용 숨기기" else "동일한 내용 보기") }
                }
            }
            itemsIndexed(visible) { _, passage ->
                Column(verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap)) {
                    EvidenceHeading(when (passage.kind) {
                        ResponseVersionChangeKind.ADDED -> "비교 답변에만 있음"
                        ResponseVersionChangeKind.REMOVED -> "현재 답변에만 있음"
                        ResponseVersionChangeKind.UNCHANGED -> "동일 · 두 답변"
                    })
                    Text(passage.text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CompareSection(header: String, role: String, body: String, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap)) {
        FlowRow(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap),
            itemVerticalAlignment = Alignment.CenterVertically) {
            EvidenceHeading(header)
            TextButton(onClick = { onCopy(body) }, modifier = Modifier.heightIn(min = FusionSpacing.control)
                .semantics { contentDescription = "$role 전체 복사" }) { Text("전체 복사") }
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalFusionSemanticColors.current.onSurface)
    }
}
