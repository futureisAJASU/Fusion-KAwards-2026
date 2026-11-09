package com.projectnuke.fusion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnuke.fusion.chat.PROJECT_SYNTHESIS_MODE_OPTIONS
import com.projectnuke.fusion.chat.ProjectBriefLoadState
import com.projectnuke.fusion.chat.ProjectSynthesisMode
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectBriefSection(
    state: ProjectBriefLoadState,
    synthesisAvailable: Boolean,
    synthesisInFlight: Boolean,
    onProjectSynthesis: (ProjectSynthesisMode) -> Unit,
    onOpenConversation: (Long) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var synthesisModeMenuExpanded by remember { mutableStateOf(false) }
    var recentChangesExpanded by remember { mutableStateOf(false) }
    var recentChangesDays by remember { mutableIntStateOf(7) }
    LaunchedEffect(synthesisInFlight) {
        if (synthesisInFlight) synthesisModeMenuExpanded = false
    }
    LaunchedEffect(synthesisAvailable) {
        if (!synthesisAvailable) synthesisModeMenuExpanded = false
    }
    LaunchedEffect(state) {
        synthesisModeMenuExpanded = false
        recentChangesExpanded = false
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("프로젝트 브리프", color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(4.dp))
            when (state) {
                ProjectBriefLoadState.Loading -> {
                    Text("불러오는 중…", color = colors.onSurfaceVariant, fontSize = 13.sp)
                }
                ProjectBriefLoadState.Unavailable -> {
                    Text("프로젝트 브리프를 불러오지 못했습니다.", color = colors.onSurfaceVariant, fontSize = 13.sp)
                }
                is ProjectBriefLoadState.Ready -> {
                    val snapshot = state.snapshot
                    Text("대화 ${snapshot.conversationCount}개 · 자료 ${snapshot.fileCount}개", color = colors.onSurfaceVariant, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { recentChangesExpanded = !recentChangesExpanded }) {
                            Text(if (recentChangesExpanded) "최근 변경 닫기" else "최근 변경")
                        }
                        if (recentChangesExpanded) {
                            Box {
                                var rangeMenuExpanded by remember { mutableStateOf(false) }
                                TextButton(onClick = { rangeMenuExpanded = true }) { Text("최근 ${recentChangesDays}일") }
                                FusionDropdownMenu(
                                    expanded = rangeMenuExpanded,
                                    onDismissRequest = { rangeMenuExpanded = false },
                                    containerColor = colors.surfaceVariant,
                                ) {
                                    listOf(7, 30).forEach { days ->
                                        DropdownMenuItem(
                                            text = { Text("최근 ${days}일", color = colors.onSurface) },
                                            onClick = { recentChangesDays = days; rangeMenuExpanded = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (recentChangesExpanded) {
                        val changes = com.projectnuke.fusion.chat.projectBriefRecentChanges(
                            snapshot, System.currentTimeMillis(), recentChangesDays,
                        )
                        if (changes.isEmpty()) {
                            Text("최근 ${recentChangesDays}일 동안 변경된 항목이 없습니다.", color = colors.onSurfaceVariant, fontSize = 13.sp)
                        } else {
                            changes.forEach { change ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .clickable(enabled = change.conversationId != null) {
                                            change.conversationId?.let(onOpenConversation)
                                        }.padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(change.title, color = colors.onSurface, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(change.kind, color = colors.onSurfaceVariant, fontSize = 12.sp)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(SimpleDateFormat("M월 d일 HH:mm", Locale.KOREAN).format(Date(change.changedAt)), color = colors.onSurfaceVariant, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    if (snapshot.conversationCount == 0 && snapshot.fileCount == 0 && snapshot.recentKnowledge.isEmpty() && snapshot.openTasks.isEmpty() && snapshot.recentDecisions.isEmpty()) {
                        Text("이 프로젝트에 대화나 자료를 추가하면 여기에 이어서 표시합니다.", color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    when {
                        snapshot.conversationCount == 0 -> Unit
                        snapshot.recentSummaries.isEmpty() -> {
                            Text("아직 저장된 대화 요약이 없습니다.", color = colors.onSurfaceVariant, fontSize = 13.sp)
                        }
                        else -> {
                            Text("최근 대화", color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(4.dp))
                            snapshot.recentSummaries.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { onOpenConversation(item.conversationId) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            color = colors.onSurface,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = item.summaryPreview,
                                            color = colors.onSurfaceVariant,
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = SimpleDateFormat("M월 d일", Locale.KOREAN).format(Date(item.updatedAt)),
                                        color = colors.onSurfaceVariant,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                    if (snapshot.recentKnowledge.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        BriefGroup("저장한 지식", snapshot.recentKnowledge.map { it.title }, colors)
                    }
                    if (snapshot.openTasks.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        BriefGroup("열린 작업", snapshot.openTasks.map { it.title }, colors)
                    }
                    if (snapshot.recentDecisions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        BriefGroup("결정", snapshot.recentDecisions.map { item ->
                            if (item.status == "SUPERSEDED") "${item.title} · 대체됨" else item.title
                        }, colors)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (synthesisAvailable) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "최근 대화 요약을 새 프로젝트 채팅에서 함께 정리합니다.",
                            color = colors.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                        Box {
                            TextButton(
                                onClick = { synthesisModeMenuExpanded = true },
                                enabled = !synthesisInFlight,
                            ) { Text("프로젝트 종합") }
                            FusionDropdownMenu(
                                expanded = synthesisModeMenuExpanded,
                                onDismissRequest = { synthesisModeMenuExpanded = false },
                                containerColor = colors.surfaceVariant,
                            ) {
                                PROJECT_SYNTHESIS_MODE_OPTIONS.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label, color = colors.onSurface) },
                                        enabled = !synthesisInFlight,
                                        onClick = {
                                            synthesisModeMenuExpanded = false
                                            onProjectSynthesis(option.mode)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }
}

@Composable
private fun BriefGroup(title: String, rows: List<String>, colors: androidx.compose.material3.ColorScheme) {
    Column {
        Text(title, color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        rows.take(3).forEach { row ->
            Text(row, modifier = Modifier.fillMaxWidth().padding(top = 7.dp), color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ProjectKnowledgeSection(
    project: ProjectEntity,
    files: List<ProjectFileEntity>,
    onEditInstructions: () -> Unit,
    onAddFile: () -> Unit,
    onRemoveFile: (ProjectFileEntity) -> Unit,
    includeArchivedBacklinks: Boolean,
    onOpenConversation: (Long) -> Unit,
    onOpenKnowledge: (KnowledgeTarget) -> Unit,
    onSaveExtractedNote: (StructuredExtractionNoteHandoff) -> Unit,
    onQuestionWithSelection: (com.projectnuke.fusion.chat.SelectionContextSnapshot) -> Unit,
    onSaveDocumentSectionDraft: (com.projectnuke.fusion.knowledge.SavedKnowledgeNoteEditorDraft) -> Unit,
    requestedFileInspectionId: Long? = null,
    onFileInspectionRequestConsumed: () -> Unit = {},
    requestedTaskId: Long? = null,
    onTaskRequestConsumed: (Long) -> Unit = {},
    requestedDecisionId: String? = null,
    onDecisionRequestConsumed: (String) -> Unit = {},
) = ProjectKnowledgeExplorer(
    project, files, onEditInstructions, onAddFile, onRemoveFile,
    includeArchivedBacklinks, onOpenConversation, onOpenKnowledge, onSaveExtractedNote,
    onQuestionWithSelection, onSaveDocumentSectionDraft,
    requestedFileInspectionId, onFileInspectionRequestConsumed, requestedTaskId, onTaskRequestConsumed,
    requestedDecisionId, onDecisionRequestConsumed,
)
