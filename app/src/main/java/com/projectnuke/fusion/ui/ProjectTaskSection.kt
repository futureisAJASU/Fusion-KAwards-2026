package com.projectnuke.fusion.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.MAX_PROJECT_TASKS
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.tasks.ProjectTaskOwner
import com.projectnuke.fusion.tasks.AndroidProjectTaskReminderScheduler
import com.projectnuke.fusion.tasks.ProjectTaskState
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TASK_EDITOR_NEW_KEY = "new"
private const val TASK_EDITOR_EDIT_PREFIX = "edit:"

@Composable
internal fun ProjectTaskSection(
    project: ProjectEntity,
    requestedTaskId: Long? = null,
    onRequestedTaskConsumed: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    // Creation drafts survive Activity recreation in-memory (U02); entry keys
    // are saveable primitives while editable text never enters the Bundle.
    val drafts: CreationDraftsViewModel = viewModel()
    var taskEditorKey by androidx.compose.runtime.saveable.rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var pendingReminderTaskId by androidx.compose.runtime.saveable.rememberSaveable(project.id) { mutableStateOf(-1L) }
    var pendingReminderAt by androidx.compose.runtime.saveable.rememberSaveable(project.id) { mutableStateOf(-1L) }
    val owner = remember(context, project.id, scope) {
        ProjectTaskOwner(
            AppDatabase.getInstance(context), project.id, scope,
            AndroidProjectTaskReminderScheduler(context.applicationContext),
        )
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val taskId = pendingReminderTaskId
        val at = pendingReminderAt
        pendingReminderTaskId = -1L
        pendingReminderAt = -1L
        if (taskId > 0 && at > 0) {
            if (granted && reminderNotificationsAllowed(context)) owner.scheduleReminder(taskId, at, notificationsAllowed = true)
            else owner.notificationPermissionUnavailable()
        }
    }
    val state by owner.state.collectAsStateWithLifecycle()
    val linkedWorkPlanTaskIds by androidx.compose.runtime.produceState(
        initialValue = emptySet<Long>(), project.id, state.tasks,
    ) {
        value = withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).projectWorkPlanDao()
            state.tasks.mapNotNull { task -> dao.getStepForTask(task.id)?.let { task.id } }.toSet()
        }
    }
    var notificationsAllowed by remember(project.id) { mutableStateOf(reminderNotificationsAllowed(context)) }
    DisposableEffect(lifecycleOwner, context) {
        var hasReconciledForActivation = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = reminderNotificationsAllowed(context)
                if (now && !notificationsAllowed) {
                    // Permission restoration transition only
                    AndroidProjectTaskReminderScheduler(context.applicationContext)
                        .reconcileOverdue(AppDatabase.getInstance(context), System.currentTimeMillis())
                }
                notificationsAllowed = now
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // Cold activation reconciliation: run immediately if already allowed
        if (reminderNotificationsAllowed(context)) {
            AndroidProjectTaskReminderScheduler(context.applicationContext)
                .reconcileOverdue(AppDatabase.getInstance(context), System.currentTimeMillis())
            hasReconciledForActivation = true
            notificationsAllowed = true
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var deleteCandidate by remember(project.id) { mutableStateOf<ProjectTaskEntity?>(null) }
    var showCompleted by remember(project.id) { mutableStateOf(false) }
    DisposableEffect(owner) { onDispose { owner.close() } }
    // Process-death fallback: the saveable entry may outlive the in-memory
    // draft (ViewModels do not survive process death). Re-seed editable text
    // from durable truth (edit) or empty (new) exactly once per entry.
    androidx.compose.runtime.LaunchedEffect(taskEditorKey, project.id) {
        val key = taskEditorKey ?: return@LaunchedEffect
        if (drafts.taskKey != key) {
            if (key == TASK_EDITOR_NEW_KEY) {
                drafts.openTaskEditor(key, null, "", "")
            } else {
                val id = key.removePrefix(TASK_EDITOR_EDIT_PREFIX).toLongOrNull()
                val current = state.tasks.firstOrNull { it.id == id }
                if (current == null) {
                    taskEditorKey = null
                } else {
                    drafts.openTaskEditor(key, current, current.title, current.note)
                }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(requestedTaskId, project.id, state.loading, state.failed) {
        val taskId = requestedTaskId ?: return@LaunchedEffect
        if (state.loading) return@LaunchedEffect
        try {
            val database = AppDatabase.getInstance(context)
            val current = withContext(Dispatchers.IO) {
                val projectExists = database.projectDao().getProjectById(project.id)?.id == project.id
                val task = database.projectTaskDao().get(taskId, project.id)
                if (com.projectnuke.fusion.workspace.WorkspaceActionTargetPolicy.canOpenTask(
                        com.projectnuke.fusion.workspace.WorkspaceTaskTarget(project.id, taskId),
                        projectExists,
                        task?.id,
                        task?.projectId,
                    )
                ) task else null
            }
            if (current == null) {
                android.widget.Toast.makeText(context, "할 일을 더 이상 열 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val key = "$TASK_EDITOR_EDIT_PREFIX${current.id}"
                drafts.openTaskEditor(key, current, current.title, current.note)
                taskEditorKey = key
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, "할 일을 더 이상 열 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
        } finally {
            onRequestedTaskConsumed(taskId)
        }
    }

    fun commitReminder(taskId: Long, at: Long) {
        val requiresPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (requiresPermission) {
            pendingReminderTaskId = taskId
            pendingReminderAt = at
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!reminderNotificationsAllowed(context)) {
            owner.notificationPermissionUnavailable()
        } else {
            owner.scheduleReminder(taskId, at, notificationsAllowed = true)
        }
    }

    fun chooseReminderTime(task: ProjectTaskEntity) {
        val existingFutureReminder = task.reminderAt?.takeIf { it > System.currentTimeMillis() }
        val initial = Calendar.getInstance().apply {
            timeInMillis = existingFutureReminder ?: System.currentTimeMillis()
            if (existingFutureReminder == null) add(Calendar.MINUTE, 1)
        }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val selectedDate = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        selectedDate.set(Calendar.HOUR_OF_DAY, hour)
                        selectedDate.set(Calendar.MINUTE, minute)
                        selectedDate.set(Calendar.SECOND, 0)
                        selectedDate.set(Calendar.MILLISECOND, 0)
                        commitReminder(task.id, selectedDate.timeInMillis)
                    },
                    initial.get(Calendar.HOUR_OF_DAY),
                    initial.get(Calendar.MINUTE),
                    android.text.format.DateFormat.is24HourFormat(context),
                ).show()
            },
            initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH),
        ).apply { datePicker.minDate = System.currentTimeMillis() }.show()
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "할 일",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(
                onClick = {
                    drafts.openTaskEditor(TASK_EDITOR_NEW_KEY, null, "", "")
                    taskEditorKey = TASK_EDITOR_NEW_KEY
                },
                enabled = !state.loading && !state.failed && state.tasks.size < MAX_PROJECT_TASKS,
            ) { Text("추가") }
        }
        when {
            state.loading -> Text("불러오는 중…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.failed -> Text("할 일을 불러오지 못했습니다.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            else -> {
                val open = state.tasks.filter { it.state == ProjectTaskState.OPEN.name }
                val done = state.tasks.filter { it.state == ProjectTaskState.DONE.name }
                if (open.isEmpty()) Text("열린 할 일이 없습니다.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                open.forEach { task ->
                    ProjectTaskRow(
                        task = task,
                        linkedToWorkPlan = task.id in linkedWorkPlanTaskIds,
                        onComplete = { completed -> owner.complete(task.id, completed) },
                        onEdit = {
                            val key = "$TASK_EDITOR_EDIT_PREFIX${task.id}"
                            drafts.openTaskEditor(key, task, task.title, task.note)
                            taskEditorKey = key
                        },
                        onDelete = { deleteCandidate = task },
                        onScheduleReminder = { chooseReminderTime(task) },
                        onEnableReminder = { task.reminderAt?.let { commitReminder(task.id, it) } },
                        onDisableReminder = { owner.disableReminder(task.id) },
                        onCancelReminder = { owner.cancelReminder(task.id) },
                        notificationsAllowed = notificationsAllowed,
                    )
                }
                if (done.isNotEmpty()) {
                    TextButton(onClick = { showCompleted = !showCompleted }) {
                        Text(if (showCompleted) "완료한 할 일 숨기기 (${done.size})" else "완료한 할 일 (${done.size})")
                    }
                    if (showCompleted) done.forEach { task ->
                        ProjectTaskRow(
                            task = task,
                            linkedToWorkPlan = task.id in linkedWorkPlanTaskIds,
                            onComplete = { completed -> owner.complete(task.id, completed) },
                            onEdit = {
                                val key = "$TASK_EDITOR_EDIT_PREFIX${task.id}"
                                drafts.openTaskEditor(key, task, task.title, task.note)
                                taskEditorKey = key
                            },
                            onDelete = { deleteCandidate = task },
                            onScheduleReminder = { chooseReminderTime(task) },
                            onEnableReminder = { task.reminderAt?.let { commitReminder(task.id, it) } },
                            onDisableReminder = { owner.disableReminder(task.id) },
                            onCancelReminder = { owner.cancelReminder(task.id) },
                            notificationsAllowed = notificationsAllowed,
                        )
                    }
                }
            }
        }
        state.writeError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    val editorKey = taskEditorKey
    if (editorKey != null && drafts.taskKey == editorKey) {
        val editingTask = drafts.taskEntity
        ProjectTaskEditorDialog(
            isNew = editingTask == null,
            title = drafts.taskTitle,
            note = drafts.taskNote,
            onTitleChange = { drafts.updateTaskEditor(it, drafts.taskNote) },
            onNoteChange = { drafts.updateTaskEditor(drafts.taskTitle, it) },
            onDismiss = {
                drafts.clearTaskEditor()
                taskEditorKey = null
            },
            onSave = { title, note, onResult ->
                // The editor closes only after durable save succeeds (U01):
                // on failure the dialog stays open with the input intact and
                // the write error visible for retry.
                val finished: (Boolean) -> Unit = { stored ->
                    if (stored) {
                        drafts.clearTaskEditor()
                        taskEditorKey = null
                    }
                    onResult(stored)
                }
                if (editingTask == null) owner.addManual(title, note, finished)
                else owner.edit(editingTask, title, note, finished)
            },
        )
    }
    deleteCandidate?.let { task ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("할 일을 삭제할까요?") },
            text = { Text("이 프로젝트에서 할 일이 삭제됩니다.") },
            confirmButton = {
                TextButton(onClick = { deleteCandidate = null; owner.delete(task.id) }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun ProjectTaskRow(
    task: ProjectTaskEntity,
    linkedToWorkPlan: Boolean,
    onComplete: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onScheduleReminder: () -> Unit,
    onEnableReminder: () -> Unit,
    onDisableReminder: () -> Unit,
    onCancelReminder: () -> Unit,
    notificationsAllowed: Boolean,
) {
    val done = task.state == ProjectTaskState.DONE.name
    var menu by remember(task.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked = done, onCheckedChange = onComplete)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (linkedToWorkPlan) Text(
                "작업 계획에서 생성됨",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (task.note.isNotBlank()) Text(
                task.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            task.reminderAt?.let { at ->
                val time = remember(task.id, at) {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
                }
                Text(
                    "$time · ${when { task.reminderEnabled && !notificationsAllowed -> "알림 권한 필요"; task.reminderEnabled -> "알림 켜짐"; else -> "알림 꺼짐" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.foundation.layout.Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "${task.title} 할 일 작업") }
            FusionDropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("편집") }, onClick = { menu = false; onEdit() })
                if (!done) DropdownMenuItem(
                    text = { Text(if (task.reminderAt == null) "알림 예약" else "알림 시간 변경") },
                    onClick = { menu = false; onScheduleReminder() },
                )
                if (task.reminderAt != null && task.reminderEnabled) DropdownMenuItem(
                    text = { Text("알림 끄기") }, onClick = { menu = false; onDisableReminder() },
                )
                if (task.reminderAt != null && !task.reminderEnabled && task.reminderAt > System.currentTimeMillis() && !done) {
                    DropdownMenuItem(text = { Text("알림 켜기") }, onClick = { menu = false; onEnableReminder() })
                }
                if (task.reminderAt != null) DropdownMenuItem(
                    text = { Text("알림 취소") }, onClick = { menu = false; onCancelReminder() },
                )
                DropdownMenuItem(text = { Text("삭제") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun ProjectTaskEditorDialog(
    isNew: Boolean,
    title: String,
    note: String,
    onTitleChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, (Boolean) -> Unit) -> Unit,
) {
    var saving by remember(isNew) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (isNew) "프로젝트 할 일 추가" else "할 일 편집") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { onTitleChange(it.take(200)) },
                    label = { Text("제목") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { onNoteChange(it.take(4_000)) },
                    label = { Text("메모 (선택)") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (saving) return@TextButton
                    saving = true
                    onSave(title, note) { stored ->
                        saving = false
                        if (stored) onDismiss()
                    }
                },
                enabled = title.isNotBlank() && !saving,
            ) { Text(if (saving) "저장 중…" else "저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("취소") } },
    )
}

private fun reminderNotificationsAllowed(context: android.content.Context): Boolean {
    val runtimePermissionGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val notificationsEnabled = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    return runtimePermissionGranted && notificationsEnabled
}
