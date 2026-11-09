package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.projectnuke.fusion.projects.ProjectWorkPlanAttempt
import com.projectnuke.fusion.projects.ProjectWorkPlanDecisionCreateResult
import com.projectnuke.fusion.projects.ProjectWorkPlanRepository
import com.projectnuke.fusion.projects.ProjectWorkPlanTaskCreateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProjectWorkPlanTaskConfirmationDialog(
    attempt: ProjectWorkPlanAttempt,
    initialTitle: String,
    initialNote: String,
    repository: ProjectWorkPlanRepository,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    var title by remember(attempt.attemptId) { mutableStateOf(initialTitle.take(200)) }
    var note by remember(attempt.attemptId) { mutableStateOf(initialNote.take(4_000)) }
    var saving by remember(attempt.attemptId) { mutableStateOf(false) }
    var error by remember(attempt.attemptId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FusionConfirmationDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("할 일 확인") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FusionSpacing.row)) {
                Text("확인하면 Project Task가 생성되고 이 단계와 연결됩니다. 완료 상태는 Task가 관리합니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(title, { title = it.take(200) }, modifier = Modifier.fillMaxWidth(), label = { Text("할 일") }, enabled = !saving)
                OutlinedTextField(note, { note = it.take(4_000) }, modifier = Modifier.fillMaxWidth(), label = { Text("메모") }, enabled = !saving, minLines = 3)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !saving && title.isNotBlank(), shape = FusionSpacing.objectShape, onClick = {
                saving = true
                scope.launch {
                    try {
                        when (repository.createConfirmedTask(attempt, title, note)) {
                            is ProjectWorkPlanTaskCreateResult.Created -> onCreated()
                            ProjectWorkPlanTaskCreateResult.Invalid -> error = "할 일 제목을 확인해 주세요."
                            ProjectWorkPlanTaskCreateResult.SensitiveContent -> error = "민감한 내용이 포함되어 저장하지 않았습니다. 내용을 확인해 주세요."
                            ProjectWorkPlanTaskCreateResult.CapacityReached -> error = "Project Task 한도에 도달했습니다."
                            ProjectWorkPlanTaskCreateResult.StaleAttempt -> error = "단계 상태가 바뀌어 할 일을 연결하지 못했습니다."
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "할 일을 저장하지 못했습니다. 완료로 표시하지 않았습니다."
                    } finally {
                        saving = false
                    }
                }
            }) { Text(if (saving) "저장 중…" else "할 일 추가") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
internal fun ProjectWorkPlanDecisionConfirmationDialog(
    attempt: ProjectWorkPlanAttempt,
    initialTitle: String,
    initialRationale: String,
    repository: ProjectWorkPlanRepository,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    var title by remember(attempt.attemptId) { mutableStateOf(initialTitle.take(200)) }
    var rationale by remember(attempt.attemptId) { mutableStateOf(initialRationale.take(1_000)) }
    var saving by remember(attempt.attemptId) { mutableStateOf(false) }
    var error by remember(attempt.attemptId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FusionConfirmationDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("결정 초안 확인") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FusionSpacing.row)) {
                Text("확인하면 Project Decisions에 새 결정으로 저장되고 이 단계에 연결됩니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(title, { title = it.take(200) }, modifier = Modifier.fillMaxWidth(), label = { Text("결정") }, enabled = !saving, minLines = 2)
                OutlinedTextField(rationale, { rationale = it.take(1_000) }, modifier = Modifier.fillMaxWidth(), label = { Text("이유") }, enabled = !saving, minLines = 2)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !saving && title.isNotBlank(), shape = FusionSpacing.objectShape, onClick = {
                saving = true
                scope.launch {
                    try {
                        when (repository.createConfirmedDecision(attempt, title, rationale)) {
                            is ProjectWorkPlanDecisionCreateResult.Created -> onCreated()
                            ProjectWorkPlanDecisionCreateResult.Invalid -> error = "결정 제목을 확인해 주세요."
                            ProjectWorkPlanDecisionCreateResult.CapacityReached -> error = "Project Decision 한도에 도달했습니다."
                            ProjectWorkPlanDecisionCreateResult.ProjectUnavailable -> error = "Project를 찾을 수 없습니다."
                            ProjectWorkPlanDecisionCreateResult.StaleAttempt -> error = "단계 상태가 바뀌어 결정을 연결하지 못했습니다."
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "결정을 저장하지 못했습니다. 완료로 표시하지 않았습니다."
                    } finally {
                        saving = false
                    }
                }
            }) { Text(if (saving) "저장 중…" else "결정 저장") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("취소") } },
    )
}
