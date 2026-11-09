package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.projects.ProjectDecisionCaptureController
import com.projectnuke.fusion.projects.ProjectDecisionIntelligencePolicy
import com.projectnuke.fusion.projects.ProjectDecisionCaptureResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ProjectDecisionCaptureSurface(
    controller: ProjectDecisionCaptureController,
    activeDecisions: List<ProjectDecisionEntity>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = remember(context) { AppDatabase.getInstance(context) }
    val repository = remember(database, context) {
        com.projectnuke.fusion.projects.ProjectDecisionIntelligenceRepository(database, context)
    }
    var title by remember(controller) { mutableStateOf(controller.draftTitle) }
    var rationale by remember(controller) { mutableStateOf(controller.draftRationale) }
    var saving by remember(controller) { mutableStateOf(false) }
    var message by remember(controller) { mutableStateOf<String?>(null) }
    var saveJob by remember(controller) { mutableStateOf<Job?>(null) }
    val supersedesDecisionId by controller.supersedesDecisionIdFlow.collectAsState()
    var suggestions by remember { mutableStateOf<List<com.projectnuke.fusion.projects.ProjectDecisionIntelligencePolicy.Candidate>>(emptyList()) }
    var suggestionsLoading by remember { mutableStateOf(false) }
    var suggestionsForDraft by remember(controller) { mutableStateOf<ProjectDecisionSuggestionDraft?>(null) }
    val currentSuggestionDraft = ProjectDecisionSuggestionDraft(title, rationale)

    // Suggestions track the current draft. Debounce edits to avoid repeated
    // local ranking while typing; old recommendations disappear immediately.
    androidx.compose.runtime.LaunchedEffect(controller, title, rationale) {
        val source = controller.frozenSource ?: return@LaunchedEffect
        suggestions = emptyList()
        suggestionsForDraft = null
        suggestionsLoading = true
        try {
            delay(250)
            val result = repository.suggestActiveCandidates(
                projectId = source.projectId,
                currentTitle = title,
                currentRationale = rationale,
                frozenSourceConversationId = source.conversationId,
                frozenSourceMessageId = source.assistantMessageId,
            )
            suggestions = result
            suggestionsForDraft = ProjectDecisionSuggestionDraft(title, rationale)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Suggestion failure is non-blocking; capture continues.
            suggestions = emptyList()
            suggestionsForDraft = ProjectDecisionSuggestionDraft(title, rationale)
        } finally {
            suggestionsLoading = false
        }
    }

    fun dismiss() {
        saveJob?.cancel()
        controller.dismiss()
        onDismiss()
    }

    DisposableEffect(controller) {
        onDispose {
            saveJob?.cancel()
            controller.dismiss()
        }
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("결정으로 저장") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("프로젝트 결정으로 저장할 내용을 검토해 주세요.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        suggestions = emptyList()
                        suggestionsForDraft = null
                        suggestionsLoading = true
                        controller.updateTitle(it)
                    },
                    label = { Text("결정 제목") },
                    singleLine = true,
                    enabled = !saving && !controller.saved,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rationale,
                    onValueChange = {
                        rationale = it
                        suggestions = emptyList()
                        suggestionsForDraft = null
                        suggestionsLoading = true
                        controller.updateRationale(it)
                    },
                    label = { Text("결정 이유 (선택)") },
                    enabled = !saving && !controller.saved,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                // Related current decision suggestions (local only; never supersede authority)
                if (ProjectDecisionSuggestionPresentationPolicy.showSuggestions(
                        currentSuggestionDraft, suggestionsForDraft, suggestionsLoading, suggestions.isNotEmpty(),
                    )
                ) {
                    Text("관련된 현재 결정", style = MaterialTheme.typography.bodyMedium)
                    suggestions.forEach { candidate ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.decision.title, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                Text(candidate.label, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(
                                onClick = { controller.selectSupersede(candidate.decision.id) },
                                enabled = !saving && !controller.saved,
                            ) { Text("선택") }
                        }
                        androidx.compose.material3.HorizontalDivider()
                    }
                }
                if (suggestionsLoading) {
                    Text("관련 결정을 확인하는 중…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (activeDecisions.isNotEmpty()) {
                    Text("대체할 기존 결정 (선택)", style = MaterialTheme.typography.bodyMedium)
                    activeDecisions.forEach { decision ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                        ) {
                            Switch(
                                checked = supersedesDecisionId == decision.id,
                                enabled = !saving && !controller.saved,
                                onCheckedChange = { checked ->
                                    controller.selectSupersede(if (checked) decision.id else null)
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(decision.title, maxLines = 1)
                        }
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss, enabled = !saving) { Text("취소") }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (saving || controller.saved || title.isBlank()) return@TextButton
                    saving = true
                    saveJob = scope.launch {
                        try {
                            when (controller.save()) {
                                is ProjectDecisionCaptureResult.Created -> message = "결정을 저장했어요."
                                ProjectDecisionCaptureResult.SourceUnavailable,
                                ProjectDecisionCaptureResult.SourceChanged -> message = "원본 답변이 바뀌어 저장하지 않았어요. 다시 확인해 주세요."
                                ProjectDecisionCaptureResult.ArchiveInaccessible -> message = "보관된 원본을 열 수 없어 저장하지 않았어요."
                                ProjectDecisionCaptureResult.ProjectMissing -> message = "프로젝트를 찾을 수 없어 결정을 저장하지 않았어요."
                                ProjectDecisionCaptureResult.SupersededDecisionMissing,
                                ProjectDecisionCaptureResult.SupersededDecisionInactive -> message = "대체할 결정이 더 이상 현재 결정이 아니에요. 다시 선택해 주세요."
                                ProjectDecisionCaptureResult.CapacityReached -> message = "프로젝트 결정 저장 한도에 도달했어요."
                                ProjectDecisionCaptureResult.Invalid -> message = "입력한 내용을 확인해 주세요."
                                ProjectDecisionCaptureResult.SaveInFlight -> message = "저장을 진행 중이에요."
                                ProjectDecisionCaptureResult.AlreadySaved -> message = "결정을 저장했어요."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            message = "결정을 저장하지 못했어요. 다시 시도해 주세요."
                        } finally {
                            saving = false
                            saveJob = null
                        }
                    }
                },
                enabled = !saving && !controller.saved && title.isNotBlank(),
                modifier = Modifier.testTag("project-decision-save"),
            ) { Text(if (controller.saved) "저장됨" else if (saving) "저장 중…" else "저장") }
        },
    )
}
