package com.projectnuke.fusion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.chat.BranchSynthesisContextLoadResult
import com.projectnuke.fusion.chat.BranchSynthesisRequestPolicy
import com.projectnuke.fusion.chat.BranchSynthesisRequestPreparation
import com.projectnuke.fusion.chat.BranchSynthesisIntent
import com.projectnuke.fusion.chat.BranchMapNode
import com.projectnuke.fusion.chat.SelectionContextSnapshot
import kotlinx.coroutines.launch

@Composable
internal fun BranchMapSurface(
    currentId: Long,
    includeArchived: Boolean,
    onDismiss: () -> Unit,
    onNavigate: (Long) -> Unit,
    source: BranchMapSource? = null,
    onSynthesize: (SelectionContextSnapshot, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val latestArchive by rememberUpdatedState(includeArchived)
    val repository = remember(source) { source ?: BranchMapRepository(context, { latestArchive }) }
    val controller = remember(currentId, includeArchived, repository) { BranchMapController(coroutineScope, repository) }
    val state by controller.state.collectAsState()
    var unavailable by remember(currentId, includeArchived) { mutableStateOf(false) }
    var selectionMessage by remember(currentId, includeArchived) { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }
    var selectionMode by remember(currentId, includeArchived) { mutableStateOf(false) }
    val selected = remember(currentId, includeArchived) { mutableStateMapOf<Long, BranchMapNode>() }
    var intentMenu by remember { mutableStateOf(false) }

    LaunchedEffect(controller) { controller.load(currentId) }
    DisposableEffect(controller) { onDispose { controller.close() } }

    fun toggleSelection(node: BranchMapNode) {
        selectionMessage = null
        if (node.id in selected) {
            selected.remove(node.id)
        } else if (selected.size >= BranchSynthesisRequestPolicy.MAX_SOURCES) {
            selectionMessage = "한 번에 최대 ${BranchSynthesisRequestPolicy.MAX_SOURCES}개 갈래를 종합할 수 있습니다."
        } else {
            selected[node.id] = node
        }
    }

    fun synthesize(intent: BranchSynthesisIntent) {
        if (opening || selected.size !in 2..BranchSynthesisRequestPolicy.MAX_SOURCES) return
        val frozenIds = selected.keys.toList()
        opening = true
        unavailable = false
        selectionMessage = null
        coroutineScope.launch {
            try {
                val contextResult = try {
                    repository.synthesisSources(frozenIds, intent)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    BranchSynthesisContextLoadResult.LookupFailed
                }
                when (contextResult) {
                    is BranchSynthesisContextLoadResult.Ready -> {
                        when (val prepared = BranchSynthesisRequestPolicy.prepare(contextResult.selection)) {
                            is BranchSynthesisRequestPreparation.Ready ->
                                onSynthesize(prepared.request.selection, prepared.request.composerText)
                            BranchSynthesisRequestPreparation.InvalidSelection ->
                                selectionMessage = "선택한 갈래의 종합 요청을 만들 수 없습니다."
                        }
                    }
                    BranchSynthesisContextLoadResult.InvalidSelection ->
                        selectionMessage = "갈래를 2~${BranchSynthesisRequestPolicy.MAX_SOURCES}개 선택해 주세요."
                    BranchSynthesisContextLoadResult.SourceUnavailable -> {
                        unavailable = true
                        selectionMessage = "선택한 대화가 삭제되었거나 지금 사용할 수 없습니다."
                    }
                    BranchSynthesisContextLoadResult.AccessDenied -> {
                        unavailable = true
                        selectionMessage = "잠긴 아카이브 갈래를 사용할 수 없습니다. 다시 인증해 주세요."
                    }
                    BranchSynthesisContextLoadResult.SummaryUnavailable ->
                        selectionMessage = "선택한 대화의 저장된 요약을 확인할 수 없습니다."
                    BranchSynthesisContextLoadResult.LookupFailed ->
                        selectionMessage = "선택한 갈래를 확인하지 못했습니다. 다시 시도해 주세요."
                }
            } finally {
                opening = false
            }
        }
    }

    FusionReadingDialog(
        scrollBody = false,
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("대화 갈래", modifier = Modifier.weight(1f))
                TextButton(
                    enabled = !opening && !state.loading,
                    onClick = {
                        selectionMode = !selectionMode
                        selected.clear()
                        selectionMessage = null
                        unavailable = false
                    },
                ) { Text(if (selectionMode) "취소" else "선택") }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().fillMaxHeight()) {
                if (state.loading) {
                    Text("연결된 대화를 읽는 중…")
                    FusionBusyLine(Modifier.fillMaxWidth())
                }
                if (unavailable && !selectionMode) Text("이 대화를 지금 열 수 없습니다.", color = MaterialTheme.colorScheme.error)
                if (state.failed) Text("연결된 대화를 읽을 수 없습니다.")
                if (!state.loading && !state.failed && state.nodes.isEmpty()) Text("표시할 대화가 없습니다.")
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(state.nodes, key = { it.id }) { node ->
                        val isSelected = node.id in selected
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !opening, role = Role.Button) {
                                if (selectionMode) {
                                    toggleSelection(node)
                                } else {
                                    opening = true
                                    coroutineScope.launch {
                                        try {
                                            if (admitKnowledgeNavigation { repository.accessible(node.id) }) onNavigate(node.id)
                                            else unavailable = true
                                        } finally { opening = false }
                                    }
                                }
                            }.semantics { this.selected = if (selectionMode) isSelected else node.current }
                                .padding(start = (node.depth.coerceIn(0, 4) * 16).dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (node.depth > 0) {
                                Box(Modifier.width(2.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
                            }
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(node.title.ifBlank { "새 대화" }, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (node.current) Text("현재 대화", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            if (selectionMode) Checkbox(checked = isSelected, onCheckedChange = null, enabled = !opening)
                        }
                        HorizontalDivider()
                    }
                }
                if (state.nodes.size >= 40) Text("일부 갈래만 표시합니다.", style = MaterialTheme.typography.bodySmall)
                if (selectionMode) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${selected.size}개 선택", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box {
                            Button(
                                enabled = !opening && selected.size in 2..BranchSynthesisRequestPolicy.MAX_SOURCES,
                                onClick = { intentMenu = true },
                            ) { Text("종합") }
                            FusionDropdownMenu(intentMenu, onDismissRequest = { intentMenu = false }) {
                                BranchSynthesisIntent.entries.forEach { intent ->
                                    DropdownMenuItem(text = { Text(intent.label) }, onClick = {
                                        intentMenu = false
                                        synthesize(intent)
                                    })
                                }
                            }
                        }
                    }
                    selectionMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
