package com.projectnuke.fusion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ProjectWorkPlanEntity
import com.projectnuke.fusion.data.ProjectWorkPlanStepEntity
import com.projectnuke.fusion.projects.ProjectWorkPlanCreateResult
import com.projectnuke.fusion.projects.ProjectWorkPlanProposal
import com.projectnuke.fusion.projects.ProjectWorkPlanProposalPolicy
import com.projectnuke.fusion.projects.ProjectWorkPlanProposalStep
import com.projectnuke.fusion.projects.ProjectWorkPlanPromptPolicy
import com.projectnuke.fusion.projects.ProjectWorkPlanAttempt
import com.projectnuke.fusion.projects.ProjectWorkPlanRepository
import com.projectnuke.fusion.projects.ProjectWorkPlanStatus
import com.projectnuke.fusion.projects.ProjectWorkPlanStepState
import com.projectnuke.fusion.projects.ProjectWorkPlanStepType
import com.projectnuke.fusion.projects.ProjectWorkPlanNextAction
import com.projectnuke.fusion.projects.ProjectWorkPlanNextActionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectWorkPlanCreateDialog(
    onDismiss: () -> Unit,
    onPrepareProposal: (String) -> Unit,
) {
    // Draft text survives Activity recreation in-memory (U02).
    val drafts: CreationDraftsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = {
            drafts.clearPlanCreate()
            onDismiss()
        },
        title = { Text("작업 계획 만들기") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("목표를 바탕으로 검토할 계획 초안을 준비합니다. 저장하거나 실행하기 전에 단계를 직접 고칠 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = drafts.planGoal,
                    onValueChange = {
                        drafts.updatePlanCreate(
                            it.take(ProjectWorkPlanProposalPolicy.MAX_GOAL_CHARS),
                            drafts.planOutput,
                            drafts.planConstraints,
                        )
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(), label = { Text("목표") }, minLines = 2,
                )
                OutlinedTextField(
                    value = drafts.planOutput,
                    onValueChange = {
                        drafts.updatePlanCreate(
                            drafts.planGoal,
                            it.take(ProjectWorkPlanProposalPolicy.MAX_DESIRED_OUTPUT_CHARS),
                            drafts.planConstraints,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(), label = { Text("원하는 결과 (선택)") }, minLines = 1,
                )
                OutlinedTextField(
                    value = drafts.planConstraints,
                    onValueChange = {
                        drafts.updatePlanCreate(
                            drafts.planGoal,
                            drafts.planOutput,
                            it.take(ProjectWorkPlanProposalPolicy.MAX_CONSTRAINT_CHARS),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(), label = { Text("제약 (선택)") }, minLines = 1,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = drafts.planGoal.isNotBlank(), onClick = {
                val prompt = ProjectWorkPlanPromptPolicy.buildPrompt(
                    com.projectnuke.fusion.projects.ProjectWorkPlanRequest(
                        drafts.planGoal,
                        drafts.planOutput,
                        drafts.planConstraints,
                    ),
                )
                if (prompt == null) error = "목표를 확인해 주세요."
                else {
                    drafts.clearPlanCreate()
                    onPrepareProposal(prompt)
                }
            }) { Text("계획 초안 준비") }
        },
        dismissButton = {
            TextButton(onClick = {
                drafts.clearPlanCreate()
                onDismiss()
            }) { Text("취소") }
        },
    )
}

internal data class PendingProjectWorkPlanReview(
    val projectId: Long,
    val proposal: ProjectWorkPlanProposal,
)

@Composable
internal fun ProjectWorkPlanProposalReviewDialog(
    pending: PendingProjectWorkPlanReview,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val context = LocalContext.current
    val database = remember(context.applicationContext) { AppDatabase.getInstance(context.applicationContext) }
    val repository = remember(database) { ProjectWorkPlanRepository(database, attachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context.applicationContext)) }
    val scope = rememberCoroutineScope()
    // Review text + step edits survive Activity recreation in-memory (U02).
    // Initialized at the open event; the guarded ensure below only backstops
    // direct invocation without an open event.
    val drafts: CreationDraftsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val reviewKey = drafts.reviewKeyFor(pending)
    if (drafts.reviewKey != reviewKey) drafts.openProposalReview(reviewKey, pending.proposal)
    val steps = drafts.reviewSteps
    var editIndex by androidx.compose.runtime.saveable.rememberSaveable(reviewKey) { mutableStateOf<Int?>(null) }
    var showManualEditor by androidx.compose.runtime.saveable.rememberSaveable(reviewKey) { mutableStateOf(false) }
    var saving by remember(reviewKey) { mutableStateOf(false) }
    var error by remember(reviewKey) { mutableStateOf<String?>(null) }
    val proposal = ProjectWorkPlanProposal(
        drafts.reviewTitle,
        drafts.reviewGoal,
        drafts.reviewOutput,
        drafts.reviewConstraints,
        steps.toList(),
    )

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("계획 제안 검토") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("저장하면 계획만 만들어집니다. 어떤 단계도 자동으로 실행되지 않습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(drafts.reviewTitle,
                    {
                        drafts.updateProposalReview(
                            it.take(ProjectWorkPlanProposalPolicy.MAX_PLAN_TITLE_CHARS),
                            drafts.reviewGoal,
                            drafts.reviewOutput,
                            drafts.reviewConstraints,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(), label = { Text("계획 제목") }, enabled = !saving, singleLine = true)
                OutlinedTextField(drafts.reviewGoal,
                    {
                        drafts.updateProposalReview(
                            drafts.reviewTitle,
                            it.take(ProjectWorkPlanProposalPolicy.MAX_GOAL_CHARS),
                            drafts.reviewOutput,
                            drafts.reviewConstraints,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(), label = { Text("목표") }, enabled = !saving, minLines = 2)
                OutlinedTextField(drafts.reviewOutput,
                    {
                        drafts.updateProposalReview(
                            drafts.reviewTitle,
                            drafts.reviewGoal,
                            it.take(ProjectWorkPlanProposalPolicy.MAX_DESIRED_OUTPUT_CHARS),
                            drafts.reviewConstraints,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(), label = { Text("원하는 결과 (선택)") }, enabled = !saving, minLines = 1)
                OutlinedTextField(drafts.reviewConstraints,
                    {
                        drafts.updateProposalReview(
                            drafts.reviewTitle,
                            drafts.reviewGoal,
                            drafts.reviewOutput,
                            it.take(ProjectWorkPlanProposalPolicy.MAX_CONSTRAINT_CHARS),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(), label = { Text("제약 (선택)") }, enabled = !saving, minLines = 1)
                HorizontalDivider()
                steps.forEachIndexed { index, step ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("${index + 1}  ${step.title}", style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("${step.type.label} · ${step.instruction}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        androidx.compose.foundation.layout.Box {
                            var expanded by remember(step, saving) { mutableStateOf(false) }
                            TextButton(onClick = { expanded = true }, enabled = !saving) { Text("수정") }
                            FusionDropdownMenu(expanded, { expanded = false }) {
                                DropdownMenuItem(text = { Text("내용 수정") }, onClick = {
                                    expanded = false
                                    val target = steps[index]
                                    drafts.openStepEdit(
                                        "review:$reviewKey:${target.title.hashCode()}:${target.instruction.hashCode()}",
                                        target.title,
                                        target.instruction,
                                    )
                                    editIndex = index
                                })
                                DropdownMenuItem(text = { Text("위로 이동") }, enabled = index > 0, onClick = {
                                    expanded = false
                                    if (index > 0) { val value = steps.removeAt(index); steps.add(index - 1, value) }
                                })
                                DropdownMenuItem(text = { Text("아래로 이동") }, enabled = index < steps.lastIndex, onClick = {
                                    expanded = false
                                    if (index < steps.lastIndex) { val value = steps.removeAt(index); steps.add(index + 1, value) }
                                })
                                DropdownMenuItem(text = { Text("단계 제거") }, enabled = steps.size > 1, onClick = {
                                    expanded = false
                                    if (steps.size > 1) steps.removeAt(index)
                                })
                            }
                        }
                    }
                    if (index < steps.lastIndex) HorizontalDivider()
                }
                TextButton(
                    enabled = !saving && steps.size < ProjectWorkPlanProposalPolicy.MAX_STEPS,
                    onClick = {
                        drafts.openStepEdit("review:$reviewKey:new", "", "")
                        showManualEditor = true
                    },
                ) { Text("직접 수행 단계 추가") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && ProjectWorkPlanProposalPolicy.validateEditedProposal(proposal), onClick = {
                saving = true
                error = null
                scope.launch {
                    val result = try { repository.create(pending.projectId, proposal) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { ProjectWorkPlanCreateResult.InvalidProposal }
                    when (result) {
                        is ProjectWorkPlanCreateResult.Created -> onSaved(result.planId)
                        ProjectWorkPlanCreateResult.InvalidProposal -> { error = "계획 내용을 확인해 주세요."; saving = false }
                        ProjectWorkPlanCreateResult.ProjectUnavailable -> { error = "Project를 찾을 수 없어 계획을 저장하지 못했습니다."; saving = false }
                        ProjectWorkPlanCreateResult.ActivePlanAlreadyExists -> { error = "이 Project에 진행 중인 계획이 있습니다. 먼저 완료하거나 일시 중지해 주세요."; saving = false }
                    }
                }
            }) { Text(if (saving) "저장 중…" else "계획 저장") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("취소") } },
    )

    val selectedIndex = editIndex
    if (selectedIndex != null && selectedIndex in steps.indices) {
        val target = steps[selectedIndex]
        WorkPlanStepEditDialog(
            step = target,
            stepKey = "review:$reviewKey:${target.title.hashCode()}:${target.instruction.hashCode()}",
            onDismiss = { drafts.clearStepEdit(); editIndex = null },
            onSave = { updated ->
                steps[selectedIndex] = updated
                drafts.clearStepEdit()
                editIndex = null
            },
        )
    }
    if (showManualEditor) {
        WorkPlanStepEditDialog(
            step = ProjectWorkPlanProposalStep(ProjectWorkPlanStepType.MANUAL, "", ""),
            stepKey = "review:$reviewKey:new",
            onDismiss = { drafts.clearStepEdit(); showManualEditor = false },
            onSave = { step ->
                steps += step
                drafts.clearStepEdit()
                showManualEditor = false
            },
        )
    }
}

@Composable
private fun WorkPlanStepEditDialog(
    step: ProjectWorkPlanProposalStep,
    stepKey: String,
    onDismiss: () -> Unit,
    onSave: (ProjectWorkPlanProposalStep) -> Unit,
) {
    // Step text survives Activity recreation in-memory (U02); initialized at
    // the open event, with a guarded backstop for direct invocation.
    val drafts: CreationDraftsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    if (drafts.stepKey != stepKey) drafts.openStepEdit(stepKey, step.title, step.instruction)
    var error by remember(stepKey) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step.title.isBlank()) "직접 수행 단계 추가" else "단계 수정") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(drafts.stepTitle,
                    { drafts.updateStepEdit(it.take(ProjectWorkPlanProposalPolicy.MAX_STEP_TITLE_CHARS), drafts.stepInstruction) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("단계 제목") }, minLines = 1)
                OutlinedTextField(drafts.stepInstruction,
                    { drafts.updateStepEdit(drafts.stepTitle, it.take(ProjectWorkPlanProposalPolicy.MAX_STEP_INSTRUCTION_CHARS)) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("사용자 지시") }, minLines = 3)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val candidate = step.copy(title = drafts.stepTitle.trim(), instruction = drafts.stepInstruction.trim())
                if (candidate.title.isBlank() || candidate.instruction.isBlank()) error = "제목과 지시를 입력해 주세요."
                else onSave(candidate)
            }) { Text("적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
internal fun ProjectWorkPlanBriefRow(
    projectId: Long,
    onOpenPlan: (String) -> Unit,
) {
    val context = LocalContext.current
    val database = remember(context.applicationContext) { AppDatabase.getInstance(context.applicationContext) }
    val repository = remember(database) { ProjectWorkPlanRepository(database, attachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context.applicationContext)) }
    val plans by remember(database, projectId) { database.projectWorkPlanDao().observePlans(projectId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val active = plans.firstOrNull { it.status == ProjectWorkPlanStatus.ACTIVE.name && it.archivedAt == null }
    val details by produceState(initialValue = null as com.projectnuke.fusion.projects.ProjectWorkPlanWithSteps?, active?.id, active?.updatedAt) {
        value = active?.let { withContext(Dispatchers.IO) { repository.load(it.id, projectId, recover = false) } }
    }
    val current = details ?: return
    val completed = current.steps.count { it.state == ProjectWorkPlanStepState.COMPLETED.name }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("진행 중인 작업 계획", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpenPlan(current.plan.id) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(current.plan.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$completed / ${current.steps.size} 완료", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("열기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ProjectWorkPlanHistoryDialog(
    projectId: Long,
    onDismiss: () -> Unit,
    onOpenPlan: (String) -> Unit,
) {
    val context = LocalContext.current
    val database = remember(context.applicationContext) { AppDatabase.getInstance(context.applicationContext) }
    val repository = remember(database) { ProjectWorkPlanRepository(database) }
    val plans by produceState(initialValue = emptyList<ProjectWorkPlanEntity>(), projectId) {
        value = withContext(Dispatchers.IO) { repository.plans(projectId, includeArchived = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("작업 계획 기록") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (plans.isEmpty()) {
                    Text("저장된 계획이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    plans.forEachIndexed { index, plan ->
                        Column(Modifier.fillMaxWidth().clickable { onOpenPlan(plan.id) }.padding(vertical = 10.dp)) {
                            Text(plan.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${workPlanPlanStatusLabel(plan.status)}${if (plan.archivedAt != null) " · 보관됨" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (index < plans.lastIndex) HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
internal fun ProjectWorkPlanDetailDialog(
    projectId: Long,
    planId: String,
    requestedStepId: String? = null,
    onDismiss: () -> Unit,
    onDispatchStep: suspend (com.projectnuke.fusion.projects.ProjectWorkPlanPreparedAction) -> Boolean = { false },
    onCreateReport: () -> Unit = {},
    onOpenConversation: (Long) -> Unit = {},
    archiveAccessIncluded: Boolean = false,
) {
    val context = LocalContext.current
    val database = remember(context.applicationContext) { AppDatabase.getInstance(context.applicationContext) }
    val repository = remember(database) { ProjectWorkPlanRepository(database, attachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context.applicationContext)) }
    val nextStepCoordinator = remember(planId, projectId, repository, database, onDispatchStep) {
        ProjectWorkPlanNextStepCoordinator(
            gateway = ProjectWorkPlanRepositoryNextStepGateway(repository, database),
            dispatch = onDispatchStep,
        )
    }
    val scope = rememberCoroutineScope()
    var refresh by remember(planId) { mutableStateOf(0) }
    val details by produceState(initialValue = null as com.projectnuke.fusion.projects.ProjectWorkPlanWithSteps?, planId, projectId, refresh) {
        // Observational read: reloading must never release a live RUNNING attempt (C05).
        value = withContext(Dispatchers.IO) { repository.load(planId, projectId, recover = false) }
    }
    var menuExpanded by remember(planId) { mutableStateOf(false) }
    var error by remember(planId) { mutableStateOf<String?>(null) }
    var actionInFlight by remember(planId) { mutableStateOf(false) }
    var pendingTaskAttempt by remember(planId, projectId) { mutableStateOf<ProjectWorkPlanAttempt?>(null) }
    var pendingDecisionAttempt by remember(planId, projectId) { mutableStateOf<ProjectWorkPlanAttempt?>(null) }
    var manualConfirmationStepId by remember(planId) { mutableStateOf<String?>(null) }
    var manualConfirmationInFlight by remember(planId) { mutableStateOf(false) }
    val current = details?.takeIf { loaded ->
        requestedStepId == null || loaded.steps.any { it.id == requestedStepId }
    }
    fun renderNextStepOutcome(outcome: ProjectWorkPlanNextStepCoordinator.Outcome) {
        when (outcome) {
            is ProjectWorkPlanNextStepCoordinator.Outcome.Dispatched -> refresh++
            is ProjectWorkPlanNextStepCoordinator.Outcome.NeedsTaskConfirmation -> pendingTaskAttempt = outcome.attempt
            is ProjectWorkPlanNextStepCoordinator.Outcome.NeedsDecisionConfirmation -> pendingDecisionAttempt = outcome.attempt
            is ProjectWorkPlanNextStepCoordinator.Outcome.ManualConfirmationRequired -> manualConfirmationStepId = outcome.stepId
            is ProjectWorkPlanNextStepCoordinator.Outcome.ReviewRequired -> Unit
            is ProjectWorkPlanNextStepCoordinator.Outcome.AlreadyRunning -> error = "이 단계는 이미 실행 중입니다."
            ProjectWorkPlanNextStepCoordinator.Outcome.Paused -> error = "계획이 일시 중지되어 단계를 시작할 수 없습니다."
            ProjectWorkPlanNextStepCoordinator.Outcome.Finished -> error = "완료되었거나 취소된 계획입니다."
            ProjectWorkPlanNextStepCoordinator.Outcome.Stale -> {
                error = "계획 상태가 바뀌었습니다. 최신 상태를 다시 확인해 주세요."
                refresh++
            }
            is ProjectWorkPlanNextStepCoordinator.Outcome.Blocked -> {
                error = when (outcome.reason) {
                    ProjectWorkPlanNextAction.BlockReason.ATTEMPT_LIMIT -> "이 단계의 재시도 한도에 도달했습니다."
                    ProjectWorkPlanNextAction.BlockReason.LINKED_TASK -> "연결된 할 일이 완료될 때까지 기다려 주세요."
                    ProjectWorkPlanNextAction.BlockReason.ARCHIVED -> "보관된 계획은 실행할 수 없습니다."
                    ProjectWorkPlanNextAction.BlockReason.NOT_READY,
                    ProjectWorkPlanNextAction.BlockReason.INVALID_STATE,
                    -> "현재 단계 상태를 확인해 주세요."
                }
            }
            is ProjectWorkPlanNextStepCoordinator.Outcome.Rejected -> error = outcome.message
        }
    }

    fun runNextStep(stepId: String, action: suspend () -> ProjectWorkPlanNextStepCoordinator.Outcome) {
        if (actionInFlight || current == null) return
        actionInFlight = true
        error = null
        scope.launch {
            try {
                renderNextStepOutcome(action())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "단계 요청을 준비하지 못했습니다. 계획 상태를 다시 확인해 주세요."
            } finally {
                actionInFlight = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(current?.plan?.title ?: "작업 계획")
                current?.let {
                    val completed = it.steps.count { row -> row.state == ProjectWorkPlanStepState.COMPLETED.name }
                    Text("$completed / ${it.steps.size} 완료", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            if (current == null) {
                Text("계획을 불러올 수 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                    val currentStep = current.steps.singleOrNull { it.position == current.plan.currentStepPosition }
                    val nextAction = currentStep?.let {
                        ProjectWorkPlanNextActionPolicy.resolve(current, it.id)
                    }
                    if (currentStep != null) {
                        ProjectWorkPlanNextStepActionButton(
                            action = requireNotNull(nextAction),
                            isBusy = actionInFlight,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            onClick = {
                                runNextStep(currentStep.id) {
                                    nextStepCoordinator.runNextStep(projectId, planId, currentStep.id)
                                }
                            },
                        )
                    }
                    if (current.plan.status == ProjectWorkPlanStatus.COMPLETED.name) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("모든 계획 단계가 정리되었습니다. 결과 참조는 아래 단계별 목록에서 확인할 수 있습니다.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = onCreateReport) { Text("보고서 만들기") }
                        }
                    }
                    current.steps.forEachIndexed { index, step ->
                        val stateLabel = workPlanStepStateLabel(step)
                        val isCurrent = step.position == current.plan.currentStepPosition && current.plan.status == ProjectWorkPlanStatus.ACTIVE.name
                        val isRequested = step.id == requestedStepId
                        Column(
                            Modifier.fillMaxWidth()
                                .then(if (isRequested) Modifier.background(
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                                    RoundedCornerShape(8.dp),
                                ) else Modifier)
                                .padding(horizontal = if (isRequested) 8.dp else 0.dp, vertical = 8.dp)
                                .then(if (isCurrent) Modifier.semantics { heading() } else Modifier),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text("${index + 1}  ${step.title}", style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal)
                            if (isRequested) Text("Action Center에서 선택한 단계", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(step.instruction, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${ProjectWorkPlanStepType.fromModelValue(step.type)?.label.orEmpty()} · $stateLabel",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (step.interrupted) Text("이전 실행은 완료 근거가 없어 중단된 상태로 복구했습니다.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (step.failureKind != null) Text(workPlanFailureLabel(step.failureKind),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            WorkPlanResultReferenceText(database, projectId, step, onOpenConversation, archiveAccessIncluded)
                            if (isCurrent && step.state == ProjectWorkPlanStepState.FAILED.name &&
                                step.attemptCount >= ProjectWorkPlanRepository.MAX_STEP_ATTEMPTS
                            ) {
                                Text("이 단계의 재시도 한도에 도달했습니다.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            if (isCurrent && step.state == ProjectWorkPlanStepState.REVIEW.name) {
                                TextButton(onClick = {
                                    scope.launch {
                                        if (step.linkedTaskId != null) {
                                            error = "연결된 할 일을 완료하면 이 단계도 완료됩니다."
                                        } else if (nextStepCoordinator.completeReviewedStep(projectId, planId, step.id)) refresh++
                                        else error = "검토 결과를 저장하지 못했습니다. 다시 불러와 주세요."
                                    }
                                }) { Text("완료하고 다음으로") }
                                if (step.type != ProjectWorkPlanStepType.MANUAL.name && step.linkedTaskId == null &&
                                    step.attemptCount < ProjectWorkPlanRepository.MAX_STEP_ATTEMPTS
                                ) {
                                    TextButton(enabled = !actionInFlight, onClick = {
                                        runNextStep(step.id) {
                                            nextStepCoordinator.retryReviewedStep(projectId, planId, step.id)
                                        }
                                    }) { Text("다시 시도") }
                                }
                                if (step.attemptCount >= ProjectWorkPlanRepository.MAX_STEP_ATTEMPTS) {
                                    Text("이 단계의 재시도 한도에 도달했습니다.", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (index < current.steps.lastIndex) HorizontalDivider()
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { menuExpanded = true }) { Text("더보기") }
                FusionDropdownMenu(menuExpanded, { menuExpanded = false }) {
                    val activeStep = current?.steps?.singleOrNull { it.position == current.plan.currentStepPosition }
                    if (current?.plan?.status == ProjectWorkPlanStatus.ACTIVE.name) {
                        if (activeStep != null && activeStep.linkedTaskId == null && activeStep.activeAttemptId == null &&
                            activeStep.state in setOf(
                                ProjectWorkPlanStepState.READY.name,
                                ProjectWorkPlanStepState.REVIEW.name,
                                ProjectWorkPlanStepState.FAILED.name,
                            )
                        ) {
                            DropdownMenuItem(text = { Text("현재 단계를 할 일로 추가") }, onClick = {
                                menuExpanded = false
                                runNextStep(activeStep.id) {
                                    nextStepCoordinator.prepareTaskConversion(projectId, planId, activeStep.id)
                                }
                            })
                        }
                        if (activeStep != null && activeStep.state in setOf(
                                ProjectWorkPlanStepState.READY.name,
                                ProjectWorkPlanStepState.REVIEW.name,
                                ProjectWorkPlanStepState.FAILED.name,
                            ) && activeStep.activeAttemptId == null
                        ) {
                            DropdownMenuItem(text = { Text("현재 단계 건너뛰기") }, onClick = {
                                menuExpanded = false
                                scope.launch {
                                    if (repository.skipStep(planId, activeStep.id, projectId)) refresh++
                                    else error = "단계를 건너뛰지 못했습니다. 최신 계획 상태를 확인해 주세요."
                                }
                            })
                        }
                        DropdownMenuItem(text = { Text("계획 일시 중지") }, onClick = {
                            menuExpanded = false
                            scope.launch { if (repository.pause(planId, projectId)) refresh++ else error = "실행 중인 단계를 확인해 주세요." }
                        })
                        DropdownMenuItem(text = { Text("계획 취소") }, onClick = {
                            menuExpanded = false
                            scope.launch { if (repository.cancelPlan(planId, projectId)) refresh++ else error = "계획을 취소하지 못했습니다." }
                        })
                    }
                    if (current?.plan?.status == ProjectWorkPlanStatus.PAUSED.name) {
                        DropdownMenuItem(text = { Text("계획 다시 시작") }, onClick = {
                            menuExpanded = false
                            scope.launch { if (repository.resume(planId, projectId)) refresh++ else error = "계획을 다시 시작하지 못했습니다." }
                        })
                        DropdownMenuItem(text = { Text("계획 취소") }, onClick = {
                            menuExpanded = false
                            scope.launch { if (repository.cancelPlan(planId, projectId)) refresh++ else error = "계획을 취소하지 못했습니다." }
                        })
                    }
                    if (current?.plan?.status in setOf(ProjectWorkPlanStatus.COMPLETED.name, ProjectWorkPlanStatus.PAUSED.name, ProjectWorkPlanStatus.CANCELLED.name)) {
                        DropdownMenuItem(text = { Text("계획 보관") }, onClick = {
                            menuExpanded = false
                            scope.launch { if (repository.archive(planId, projectId)) onDismiss() else error = "계획을 보관하지 못했습니다." }
                        })
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
    manualConfirmationStepId?.let { stepId ->
        val step = current?.steps?.singleOrNull { it.id == stepId }
        ProjectWorkPlanManualConfirmationDialog(
            stepTitle = step?.title.orEmpty(),
            isBusy = manualConfirmationInFlight,
            enabled = step != null,
            onDismiss = { if (!manualConfirmationInFlight) manualConfirmationStepId = null },
            onConfirm = {
                manualConfirmationInFlight = true
                scope.launch {
                    try {
                        if (nextStepCoordinator.completeManualStep(projectId, planId, stepId)) {
                            manualConfirmationStepId = null
                            refresh++
                        } else {
                            error = "단계를 완료로 표시하지 못했습니다. 최신 계획 상태를 확인해 주세요."
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "단계를 완료로 표시하지 못했습니다. 최신 계획 상태를 확인해 주세요."
                    } finally {
                        manualConfirmationInFlight = false
                    }
                }
            },
        )
    }
    ProjectWorkPlanPendingConfirmationOwner(
        attempt = pendingTaskAttempt ?: pendingDecisionAttempt,
        coordinator = nextStepCoordinator,
    )
    pendingTaskAttempt?.let { attempt ->
        val step = current?.steps?.firstOrNull { it.id == attempt.stepId }
        ProjectWorkPlanTaskConfirmationDialog(
            attempt = attempt,
            initialTitle = step?.title.orEmpty(),
            initialNote = step?.instruction.orEmpty(),
            repository = repository,
            onDismiss = {
                scope.launch {
                    nextStepCoordinator.cancelAttempt(attempt)
                    pendingTaskAttempt = null
                    refresh++
                }
            },
            onCreated = { pendingTaskAttempt = null; refresh++ },
        )
    }
    pendingDecisionAttempt?.let { attempt ->
        val step = current?.steps?.firstOrNull { it.id == attempt.stepId }
        ProjectWorkPlanDecisionConfirmationDialog(
            attempt = attempt,
            initialTitle = step?.title.orEmpty(),
            initialRationale = step?.instruction.orEmpty(),
            repository = repository,
            onDismiss = {
                scope.launch {
                    nextStepCoordinator.cancelAttempt(attempt)
                    pendingDecisionAttempt = null
                    refresh++
                }
            },
            onCreated = { pendingDecisionAttempt = null; refresh++ },
        )
    }
}

/**
 * Task and Decision confirmations are local owners, not accepted specialized handoffs.
 * Losing this composition releases only the attempt represented by the pending dialog.
 */
@Composable
internal fun ProjectWorkPlanPendingConfirmationOwner(
    attempt: ProjectWorkPlanAttempt?,
    coordinator: ProjectWorkPlanNextStepCoordinator,
) {
    if (attempt != null) {
        LaunchedEffect(
            attempt.projectId,
            attempt.planId,
            attempt.stepId,
            attempt.attemptId,
        ) {
            try {
                awaitCancellation()
            } finally {
                // cancelAttempt performs its exact-attempt transaction in
                // NonCancellable + Dispatchers.IO; settled Task/Decision results
                // have already cleared this attempt and make this cleanup a no-op.
                coordinator.cancelAttempt(attempt)
            }
        }
    }
}

@Composable
internal fun ProjectWorkPlanNextStepActionButton(
    action: ProjectWorkPlanNextAction,
    isBusy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val canRun = action is ProjectWorkPlanNextAction.Start ||
        action is ProjectWorkPlanNextAction.Retry ||
        action is ProjectWorkPlanNextAction.ManualConfirmationRequired
    Button(modifier = modifier, enabled = !isBusy && canRun, onClick = onClick) {
        Text(
            when (action) {
                is ProjectWorkPlanNextAction.Start -> if (isBusy) "단계 준비 중…" else "다음 단계 실행"
                is ProjectWorkPlanNextAction.Retry -> if (isBusy) "단계 준비 중…" else "단계 다시 실행"
                is ProjectWorkPlanNextAction.ManualConfirmationRequired -> "직접 수행 단계"
                is ProjectWorkPlanNextAction.ReviewRequired -> "결과 검토 필요"
                is ProjectWorkPlanNextAction.AlreadyRunning -> "단계 실행 중"
                ProjectWorkPlanNextAction.Paused -> "계획이 일시 중지됨"
                ProjectWorkPlanNextAction.Finished -> "계획이 종료됨"
                ProjectWorkPlanNextAction.Stale -> "최신 상태 확인 필요"
                is ProjectWorkPlanNextAction.Blocked -> when (action.reason) {
                    ProjectWorkPlanNextAction.BlockReason.ATTEMPT_LIMIT -> "재시도 한도 도달"
                    ProjectWorkPlanNextAction.BlockReason.LINKED_TASK -> "연결된 할 일 확인 필요"
                    ProjectWorkPlanNextAction.BlockReason.ARCHIVED -> "보관된 계획"
                    ProjectWorkPlanNextAction.BlockReason.NOT_READY,
                    ProjectWorkPlanNextAction.BlockReason.INVALID_STATE,
                    -> "현재 단계 실행 불가"
                }
            },
        )
    }
}

@Composable
internal fun ProjectWorkPlanManualConfirmationDialog(
    stepTitle: String,
    isBusy: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text("직접 수행 확인") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$stepTitle 단계를 직접 수행했나요?")
                Text(
                    "확인하면 이 단계만 완료됩니다. 다음 단계는 별도로 실행할 때까지 시작되지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = enabled && !isBusy, onClick = onConfirm) {
                Text(if (isBusy) "확인 중…" else "직접 수행 완료 표시")
            }
        },
        dismissButton = {
            TextButton(enabled = !isBusy, onClick = onDismiss) { Text("취소") }
        },
    )
}

private fun workPlanStepStateLabel(step: ProjectWorkPlanStepEntity): String = when (step.state) {
    ProjectWorkPlanStepState.PENDING.name -> "대기"
    ProjectWorkPlanStepState.READY.name -> "시작 가능"
    ProjectWorkPlanStepState.RUNNING.name -> "진행 중"
    ProjectWorkPlanStepState.REVIEW.name -> "결과 확인"
    ProjectWorkPlanStepState.COMPLETED.name -> "완료"
    ProjectWorkPlanStepState.SKIPPED.name -> "건너뜀"
    ProjectWorkPlanStepState.FAILED.name -> "다시 시도 가능"
    else -> "상태 확인 필요"
}

private fun workPlanPlanStatusLabel(status: String): String = when (status) {
    ProjectWorkPlanStatus.ACTIVE.name -> "진행 중"
    ProjectWorkPlanStatus.PAUSED.name -> "일시 중지"
    ProjectWorkPlanStatus.COMPLETED.name -> "완료"
    ProjectWorkPlanStatus.CANCELLED.name -> "취소됨"
    else -> "상태 확인 필요"
}

private fun workPlanFailureLabel(kind: String): String = when (kind) {
    "GENERATION" -> "생성에 실패했습니다. 다시 시도할 수 있습니다."
    "STALE_SOURCE" -> "자료가 변경되거나 없어 다시 확인해야 합니다."
    "ACCESS" -> "현재 Project 또는 자료에 접근할 수 없습니다."
    "PERSISTENCE" -> "결과 저장에 실패했습니다. 완료로 표시하지 않았습니다."
    else -> "실행을 완료하지 못했습니다. 다시 확인해 주세요."
}

@Composable
private fun WorkPlanResultReferenceText(
    database: AppDatabase,
    projectId: Long,
    step: ProjectWorkPlanStepEntity,
    onOpenConversation: (Long) -> Unit,
    archiveAccessIncluded: Boolean,
) {
    val appContext = LocalContext.current.applicationContext
    if (step.resultKind.isNullOrBlank() || step.resultId.isNullOrBlank()) return
    if (step.resultKind == "MESSAGE") {
        val messageId = step.resultId.toLongOrNull()
        val conversationId by produceState<Long?>(null, projectId, messageId) {
            value = withContext(Dispatchers.IO) {
                val identity = messageId?.let { database.chatDao().getMessageIdentityById(it) }
                val projectIdForConversation = identity?.conversationId?.let {
                    database.projectDao().getProjectIdForConversation(it)
                }
                identity?.conversationId?.takeIf {
                    identity.role == "assistant" && projectIdForConversation == projectId
                }
            }
        }
        Text(
            if (conversationId == null) "연결된 Project 답변이 삭제되었거나 사용할 수 없습니다."
            else "검토할 Project 답변이 저장되어 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = if (conversationId == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        conversationId?.let { id ->
            TextButton(onClick = { onOpenConversation(id) }) { Text("결과 보기") }
        }
        return
    }
    val reference by produceState<String?>(null, projectId, step.resultKind, step.resultId, step.linkedTaskId, archiveAccessIncluded) {
        value = withContext(Dispatchers.IO) {
            when (step.resultKind) {
                "TASK" -> {
                    val taskId = step.linkedTaskId ?: step.resultId.toLongOrNull()
                    val task = taskId?.let { database.projectTaskDao().get(it, projectId) }
                    task?.let { "연결된 할 일 · ${it.title}" } ?: "연결된 할 일이 삭제되었거나 사용할 수 없습니다."
                }
                "DECISION" -> database.projectDecisionDao().get(step.resultId, projectId)
                    ?.let { "연결된 결정 · ${it.title}" }
                    ?: "연결된 결정이 삭제되었거나 사용할 수 없습니다."
                // Issue 5: durable provenance resolves the actual selected
                // sources through the shared access authority with the
                // current archive authorization. Accessible sources show
                // their titles; inaccessible sources contribute only to a
                // neutral count so a locked reopen never leaks an archived
                // title. A raw snapshot UUID is never displayed.
                "SELECTION", "EXTRACTION" -> {
                    val repository = com.projectnuke.fusion.projects.ProjectWorkPlanRepository(database)
                    val entries = repository.loadStepResultSources(step.planId, step.id)
                    if (entries.isEmpty()) {
                        "선택한 자료를 불러오지 못했습니다."
                    } else {
                        val authority = workPlanAccessAuthority(
                            context = appContext,
                            database = database,
                            archiveAccessIncluded = archiveAccessIncluded,
                        )
                        val availableTitles = entries.mapNotNull { entry ->
                            val open = try {
                                authority.isAccessible(entry.identity)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            if (open) entry.title else null
                        }
                        if (step.resultKind == "EXTRACTION") {
                            com.projectnuke.fusion.projects.WorkPlanResultDisplayPolicy.formatExtractionLabel(
                                entries.size,
                                availableTitles,
                            )
                        } else {
                            com.projectnuke.fusion.projects.WorkPlanResultDisplayPolicy.formatSelectionLabel(
                                entries.size,
                                availableTitles,
                            )
                        }
                    }
                }
                else -> "저장된 결과 참조 · ${step.resultKind}"
            }
        }
    }
    reference?.let { text ->
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (text.contains("삭제") || text.contains("사용할 수 없습니다")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
