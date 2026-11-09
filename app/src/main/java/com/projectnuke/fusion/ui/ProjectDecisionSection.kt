package com.projectnuke.fusion.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.projects.ProjectDecisionCreateResult
import com.projectnuke.fusion.projects.ProjectDecisionDraft
import com.projectnuke.fusion.projects.ProjectDecisionOwner
import com.projectnuke.fusion.projects.ProjectDecisionRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProjectDecisionSection(
    projectId: Long,
    includeArchived: Boolean,
    onOpenConversation: (Long) -> Unit,
    onOpenKnowledge: (KnowledgeTarget) -> Unit,
    requestedDecisionId: String? = null,
    onDecisionRequestConsumed: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = remember(context) { AppDatabase.getInstance(context) }
    // Decision drafts survive Activity recreation in-memory (U02); the entry
    // key is a saveable primitive while editable text never enters the Bundle.
    // Key "" opens a brand-new decision; any other key supersedes that id.
    val drafts: CreationDraftsViewModel = viewModel()
    val repository = remember(database) { ProjectDecisionRepository(database) }
    val owner = remember(repository, projectId, scope) { ProjectDecisionOwner(repository, projectId, scope) }
    val state by owner.state.collectAsStateWithLifecycle()
    DisposableEffect(owner) { onDispose { owner.close() } }
    var draftTarget by androidx.compose.runtime.saveable.rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    var selected by remember(projectId) { mutableStateOf<ProjectDecisionEntity?>(null) }
    var showOlder by remember(projectId) { mutableStateOf(false) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(requestedDecisionId, state.activeItems, state.loading) {
        val id = requestedDecisionId ?: return@LaunchedEffect
        if (!state.loading) {
            val target = state.activeItems.firstOrNull { it.id == id }
            if (target != null) selected = target else {
                Toast.makeText(context, "선택한 결정을 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
            onDecisionRequestConsumed(id)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("결정", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = {
                // "" is the new-decision sentinel: the previous code set null
                // here, which left the dialog closed and made adding
                // unreachable. Keyed draft init keeps the new draft across
                // recreation until explicitly dismissed.
                drafts.openDecisionEditor("")
                draftTarget = ""
                error = null
            }) { Text("결정 추가") }
        }
        when {
            state.loading -> Text("불러오는 중…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.failed -> Text("결정을 불러오지 못했습니다.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            state.activeItems.isEmpty() -> Text("이 프로젝트에 저장된 결정이 없습니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val active = state.activeItems
                val historical = state.items.filterNot { it.status == "ACTIVE" }
                val visible = if (showOlder) state.items else active.take(5)
                visible.forEach { decision ->
                    DecisionRow(
                        decision = decision,
                        onClick = { selected = decision },
                        onReplace = {
                            drafts.openDecisionEditor(decision.id)
                            draftTarget = decision.id
                            error = null
                        },
                    )
                }
                val hiddenCount = if (showOlder) 0 else (active.size - 5).coerceAtLeast(0) + historical.size
                if (hiddenCount > 0 || showOlder) {
                    TextButton(onClick = { showOlder = !showOlder }) {
                        Text(if (showOlder) "간단히 보기" else "이전 결정 보기 · $hiddenCount")
                    }
                }
            }
        }
    }

        val decisionKey = draftTarget
    if (decisionKey != null && drafts.decisionKey == decisionKey) {
        val supersedesId = decisionKey.takeIf { it.isNotEmpty() }
        var saving by remember(decisionKey) { mutableStateOf(false) }
        val oldDecision = supersedesId?.let { id -> state.items.firstOrNull { it.id == id } }
        val oldDecisionTitle = oldDecision?.title?.takeIf { it.isNotBlank() }
        AlertDialog(
            onDismissRequest = {
                if (!saving) {
                    drafts.clearDecisionEditor()
                    draftTarget = null
                }
            },
            title = { Text(if (supersedesId == null) "결정 추가" else (oldDecisionTitle?.let { "기존 결정 대체 · $it" } ?: "기존 결정 대체")) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (oldDecision != null) Text("이전 결정은 기록에 남으며 ‘대체됨’으로 표시됩니다.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = drafts.decisionTitle,
                        onValueChange = { drafts.updateDecisionEditor(it.take(200), drafts.decisionRationale) },
                        modifier = Modifier.fillMaxWidth(), label = { Text("무엇을 결정했나요?") },
                        enabled = !saving, minLines = 2)
                    OutlinedTextField(value = drafts.decisionRationale,
                        onValueChange = { drafts.updateDecisionEditor(drafts.decisionTitle, it.take(1_000)) },
                        modifier = Modifier.fillMaxWidth(), label = { Text("이유 (선택)") },
                        enabled = !saving, minLines = 2)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = drafts.decisionTitle.isNotBlank() && !saving, onClick = {
                    saving = true
                    val snapshotTitle = drafts.decisionTitle
                    val snapshotRationale = drafts.decisionRationale
                    scope.launch {
                        val result = try {
                            repository.create(projectId, ProjectDecisionDraft(
                                title = snapshotTitle,
                                rationale = snapshotRationale,
                                supersedesDecisionId = supersedesId,
                            ), System.currentTimeMillis())
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            ProjectDecisionCreateResult.Invalid("결정을 저장하지 못했습니다.")
                        }
                        when (result) {
                            is ProjectDecisionCreateResult.Created -> {
                                error = null
                                drafts.clearDecisionEditor()
                                draftTarget = null
                            }
                            is ProjectDecisionCreateResult.Invalid -> { error = result.message; saving = false }
                            ProjectDecisionCreateResult.ProjectMissing -> { error = "프로젝트를 찾을 수 없습니다."; saving = false }
                            ProjectDecisionCreateResult.SupersededDecisionMissing -> { error = "대체할 이전 결정을 찾을 수 없습니다."; saving = false }
                            ProjectDecisionCreateResult.SupersededDecisionInactive -> { error = "대체할 결정이 더 이상 현재 결정이 아니에요. 다시 선택해 주세요."; saving = false }
                            ProjectDecisionCreateResult.CapacityReached -> { error = "프로젝트 결정 기록이 가득 찼습니다."; saving = false }
                        }
                    }
                }) { Text(if (saving) "저장 중…" else "결정 저장") }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = {
                    drafts.clearDecisionEditor()
                    draftTarget = null
                }) { Text("취소") }
            },
        )
    }

    selected?.let { decision ->
        val replacedBy = state.items.firstOrNull { it.supersedesDecisionId == decision.id }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(decision.title) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${decisionDate(decision.createdAt)} · ${if (decision.status == "ACTIVE") "현재 결정" else "이후 결정으로 대체됨"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (decision.rationale.isNotBlank()) Text(decision.rationale, style = MaterialTheme.typography.bodyMedium)
                    replacedBy?.let { Text("이 결정을 대체한 결정 · ${it.title}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    // H4 — Decision lineage (explicit supersede only, bounded)
                    val lineage = buildDecisionLineage(decision, state.items)
                    if (lineage.isNotEmpty()) {
                        Text("결정 이력", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp))
                        lineage.forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (row.id == decision.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    Text(decisionDate(row.createdAt), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (row.id == decision.id) {
                                    Text("현재", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    TextButton(onClick = {
                                        scope.launch {
                                            val exact = try {
                                                repository.get(row.id, projectId)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                null
                                            }
                                            if (exact != null) {
                                                selected = exact
                                            } else {
                                                Toast.makeText(context, "이 결정을 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text("열기")
                                    }
                                }
                            }
                        }
                    }
                    RelatedKnowledgeSection(
                        target = KnowledgeTarget.Decision(projectId, decision.id),
                        includeArchived = includeArchived,
                        onOpen = { target ->
                            selected = null
                            onOpenKnowledge(target)
                        },
                    )
                }
            },
            confirmButton = {
                if (decision.sourceConversationId != null && decision.sourceMessageId != null) {
                    TextButton(onClick = { selected = null; onOpenConversation(decision.sourceConversationId) }) {
                        Text("출처 대화 열기")
                    }
                } else TextButton(onClick = { selected = null }) { Text("닫기") }
            },
            dismissButton = if (decision.sourceConversationId != null) ({
                TextButton(onClick = { selected = null }) { Text("닫기") }
            }) else null,
        )
    }
}

@Composable
internal fun DecisionRow(
    decision: ProjectDecisionEntity,
    onClick: () -> Unit,
    onReplace: () -> Unit,
) {
    var menu by remember(decision.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 9.dp)) {
            Text(decision.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(decisionDate(decision.createdAt))
                    if (decision.status != "ACTIVE") append(" · 대체됨")
                    if (decision.supersedesDecisionId != null) append(" · 이전 결정을 대체")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (decision.status == "ACTIVE") {
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "${decision.title} 작업") }
                FusionDropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("이 결정 대체하기") }, onClick = { menu = false; onReplace() })
                }
            }
        }
    }
    androidx.compose.material3.HorizontalDivider()
}

/** Build a bounded explicit-supersede lineage for a decision. */
private fun buildDecisionLineage(
    current: ProjectDecisionEntity,
    allProjectDecisions: List<ProjectDecisionEntity>,
): List<ProjectDecisionEntity> {
    val maxLineage = 8
    val chain = mutableListOf<ProjectDecisionEntity>()
    // Start from current, go backward through explicit supersedes
    var pointer: ProjectDecisionEntity? = current
    val visited = mutableSetOf<String>()
    while (pointer != null && chain.size < maxLineage && pointer.id !in visited) {
        visited += pointer.id
        chain += pointer
        // Find previous (the one this points to via supersedesDecisionId)
        val prev = pointer.supersedesDecisionId?.let { prevId ->
            allProjectDecisions.firstOrNull { it.id == prevId && it.projectId == pointer!!.projectId }
        }
        pointer = prev
    }
    // Reverse so chronological order (oldest first) within the backward chain
    val backwardChain = chain.reversed().toMutableList()
    // Now go forward from current through decisions that supersede it
    pointer = current
    val forwardVisited = mutableSetOf<String>()
    while (pointer != null && backwardChain.size + forwardVisited.size < maxLineage) {
        val next = allProjectDecisions.firstOrNull { it.supersedesDecisionId == pointer!!.id && it.projectId == pointer!!.projectId }
        if (next == null || next.id in forwardVisited || next.id in visited) break
        forwardVisited += next.id
        backwardChain += next
        pointer = next
    }
    // Deduplicate and bound
    val result = backwardChain.distinctBy { it.id }.take(maxLineage)
    return result
}

private fun decisionDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy년 M월 d일", Locale.KOREAN).format(Date(epochMillis))
