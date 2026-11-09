package com.projectnuke.fusion.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.projectnuke.fusion.chat.*
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.study.QuickReviewCardDraft
import com.projectnuke.fusion.study.QuickReviewRatingResult
import com.projectnuke.fusion.study.QuickReviewCardReminderResult
import com.projectnuke.fusion.study.ReviewCardOrigin
import com.projectnuke.fusion.study.ReviewCardRating
import com.projectnuke.fusion.study.ReviewCardScope
import com.projectnuke.fusion.tasks.AndroidProjectTaskReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudyProgressSurface(
    conversationId: Long,
    owner: StudyProgressOwner,
    onDismiss: () -> Unit,
    onRevisit: (String) -> Unit,
    projectId: Long?,
    archiveAccessIncluded: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember(conversationId) { mutableStateOf<StudyProgress?>(null) }
    var error by remember(conversationId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var topic by remember { mutableStateOf("") }
    var reviewScope by remember(conversationId) {
        mutableStateOf(if (projectId != null) ReviewCardScope.PROJECT else ReviewCardScope.PERSONAL)
    }
    var cardNotice by remember(conversationId) { mutableStateOf<String?>(null) }
    var notificationsAllowed by remember { mutableStateOf(studyReminderNotificationsAllowed(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = studyReminderNotificationsAllowed(context)
                if (now && !notificationsAllowed) {
                    AndroidProjectTaskReminderScheduler(context.applicationContext)
                        .reconcileOverdue(AppDatabase.getInstance(context), System.currentTimeMillis())
                }
                notificationsAllowed = now
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (studyReminderNotificationsAllowed(context)) {
            AndroidProjectTaskReminderScheduler(context.applicationContext)
                .reconcileOverdue(AppDatabase.getInstance(context), System.currentTimeMillis())
            notificationsAllowed = true
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var pendingReminderCardId by rememberSaveable { mutableStateOf("") }
    var pendingReminderAt by rememberSaveable { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    fun scheduleCardReminder(cardId: String, at: Long) {
        scope.launch {
            cardNotice = owner.scheduleReviewCardReminder(cardId, at, notificationsAllowed = true).messageOrNull()
        }
    }
    val reminderPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val cardId = pendingReminderCardId
        val at = pendingReminderAt
        pendingReminderCardId = ""
        pendingReminderAt = 0L
        notificationsAllowed = studyReminderNotificationsAllowed(context)
        if (cardId.isNotBlank() && at > 0L) {
            if (granted && notificationsAllowed) scheduleCardReminder(cardId, at)
            else cardNotice = "알림 권한이 없어 예약하지 않았습니다."
        }
    }
    val cardCaptureController = remember(owner, scope) { owner.newReviewCardCaptureController(scope) }
    val cardCaptureState by cardCaptureController.state.collectAsStateWithLifecycle()
    val reviewCards by remember(owner, reviewScope, projectId) {
        owner.observeReviewCards(if (reviewScope == ReviewCardScope.PROJECT) projectId else null)
    }.collectAsStateWithLifecycle(emptyList())
    fun requestCardReminder(cardId: String, at: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingReminderCardId = cardId
            pendingReminderAt = at
            reminderPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!studyReminderNotificationsAllowed(context)) {
            notificationsAllowed = false
            cardNotice = "알림이 꺼져 있어 예약하지 않았습니다. 시스템 설정에서 알림을 허용해 주세요."
        } else {
            notificationsAllowed = true
            scheduleCardReminder(cardId, at)
        }
    }
    fun chooseCardReminderTime(card: StudyReviewCardEntity) {
        val existingFutureReminder = card.reminderAt?.takeIf { it > System.currentTimeMillis() }
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
                        requestCardReminder(card.id, selectedDate.timeInMillis)
                    },
                    initial.get(Calendar.HOUR_OF_DAY),
                    initial.get(Calendar.MINUTE),
                    android.text.format.DateFormat.is24HourFormat(context),
                ).show()
            },
            initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH),
        ).apply { datePicker.minDate = System.currentTimeMillis() }.show()
    }
    LaunchedEffect(conversationId) { try { state = owner.load(conversationId) } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "학습 기록을 읽을 수 없습니다." } }
    fun update(transform: (StudyProgress) -> StudyProgress) { if (busy) return; busy = true
        scope.launch { try { state = owner.update(conversationId, transform); error = null }
            catch (e: CancellationException) { throw e } catch (_: Exception) { error = "학습 기록을 저장하지 못했습니다." } finally { busy = false } }
    }
    FusionReadingDialog(onDismissRequest = onDismiss, title = { Text("학습 기록") }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("답변 시도와 힌트 요청을 기록합니다. AI 검토는 확정적인 채점이 아닙니다.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            cardNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            StudyQuickReviewPanel(
                cards = reviewCards,
                reviewScope = reviewScope,
                canUseProject = projectId != null,
                notificationsAllowed = notificationsAllowed,
                onScopeChange = { reviewScope = it },
                onCreateTextCard = {
                    cardCaptureController.begin(
                        QuickReviewCardDraft(
                            front = "", back = "", scope = reviewScope, projectId = projectId,
                            sourceKind = ReviewCardOrigin.USER_TEXT,
                        ), archiveAccessIncluded,
                    )
                },
                onRate = { card, rating ->
                    scope.launch {
                        when (owner.rateReviewCard(card.id, rating)) {
                            QuickReviewRatingResult.Updated -> cardNotice = null
                            QuickReviewRatingResult.UpdatedReminderCleanupFailed ->
                                cardNotice = "복습 결과는 저장했지만 알림을 지우지 못했습니다. 알림을 직접 닫아 주세요."
                            QuickReviewRatingResult.UnknownCard, QuickReviewRatingResult.NotDue ->
                                cardNotice = "카드 상태가 바뀌어 복습 결과를 적용하지 않았습니다."
                            QuickReviewRatingResult.Failed -> cardNotice = "복습 결과를 저장하지 못했습니다."
                        }
                    }
                },
                onScheduleReminder = ::chooseCardReminderTime,
                onEnableReminder = { card -> card.reminderAt?.let { requestCardReminder(card.id, it) } },
                onDisableReminder = { card ->
                    scope.launch { cardNotice = owner.disableReviewCardReminder(card.id).messageOrNull() }
                },
                onCancelReminder = { card ->
                    scope.launch { cardNotice = owner.disableReviewCardReminder(card.id, clearTime = true).messageOrNull() }
                },
                onDelete = { card ->
                    scope.launch {
                        cardNotice = when (owner.deleteReviewCard(card.id)) {
                            com.projectnuke.fusion.study.QuickReviewCardDeleteResult.DELETED -> "복습 카드를 삭제했습니다."
                            com.projectnuke.fusion.study.QuickReviewCardDeleteResult.CLEANUP_PENDING ->
                                "삭제 요청을 저장했습니다. 알림 정리가 끝나면 삭제가 완료됩니다."
                            com.projectnuke.fusion.study.QuickReviewCardDeleteResult.NOT_FOUND,
                            com.projectnuke.fusion.study.QuickReviewCardDeleteResult.FAILED -> "복습 카드를 삭제하지 못했습니다."
                        }
                    }
                },
            )
            state?.let { value ->
                if (value.sessions.isEmpty()) Text("학습 모드에서 주제를 보내면 기록이 시작됩니다.")
                value.sessions.asReversed().forEach { session ->
                    HorizontalDivider()
                    Text(if (session.ended) "지난 학습" else "현재 학습", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Text("질문 요청 ${session.questionsRequested} · 답변 시도 ${session.attempts} · 힌트 ${session.hints}", style = MaterialTheme.typography.bodySmall)
                    session.topics.sortedByDescending { it.review }.forEach { item ->
                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(modifier = Modifier.semantics { selected = item.review }, enabled = !busy, onClick = { update { StudyProgressPolicy.topic(it, session.id, item.name, !item.review, item.understood) } }) { Text(if (item.review) "다시 볼 항목 ✓" else "다시 보기") }
                            TextButton(modifier = Modifier.semantics { selected = item.understood }, enabled = !busy, onClick = { update { StudyProgressPolicy.topic(it, session.id, item.name, item.review, !item.understood) } }) { Text(if (item.understood) "이해했어요 ✓" else "이해했어요") }
                        }
                        TextButton(enabled = !busy, onClick = { onRevisit(item.name) }) { Text("이 주제로 이어서 학습") }
                        TextButton(enabled = !busy, onClick = {
                            cardCaptureController.begin(
                                QuickReviewCardDraft(
                                    front = item.name,
                                    back = "",
                                    scope = if (projectId != null) ReviewCardScope.PROJECT else ReviewCardScope.PERSONAL,
                                    projectId = projectId,
                                    sourceKind = ReviewCardOrigin.STUDY_TOPIC,
                                    sourceConversationId = conversationId,
                                ),
                                archiveAccessIncluded,
                            )
                        }) { Text("복습 카드 만들기") }
                    }
                }
                value.sessions.lastOrNull()?.let { current ->
                    if (!current.ended && current.topics.size < StudyProgressPolicy.MAX_TOPICS) {
                        OutlinedTextField(topic, { topic = it.take(80) }, label = { Text("학습 항목") }, modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = !busy && topic.isNotBlank(), onClick = { update { StudyProgressPolicy.topic(it, current.id, topic, false, false) }; topic = "" }) { Text("항목 추가") }
                    }
                }
            } ?: if (error == null) Text("학습 기록을 읽는 중…") else Unit
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
    StudyReviewCardCaptureSurface(
        state = cardCaptureState,
        projectName = if (projectId != null) "프로젝트" else null,
        onFrontChange = cardCaptureController::updateFront,
        onBackChange = cardCaptureController::updateBack,
        onScopeChange = cardCaptureController::updateScope,
        onSave = cardCaptureController::save,
        onDismiss = cardCaptureController::dismiss,
        onSaved = { cardCaptureController.dismiss(); cardNotice = "복습 카드를 저장했습니다." },
    )
}

@Composable
private fun StudyQuickReviewPanel(
    cards: List<StudyReviewCardEntity>,
    reviewScope: ReviewCardScope,
    canUseProject: Boolean,
    notificationsAllowed: Boolean,
    onScopeChange: (ReviewCardScope) -> Unit,
    onCreateTextCard: () -> Unit,
    onRate: (StudyReviewCardEntity, ReviewCardRating) -> Unit,
    onScheduleReminder: (StudyReviewCardEntity) -> Unit,
    onEnableReminder: (StudyReviewCardEntity) -> Unit,
    onDisableReminder: (StudyReviewCardEntity) -> Unit,
    onCancelReminder: (StudyReviewCardEntity) -> Unit,
    onDelete: (StudyReviewCardEntity) -> Unit,
) {
    Text("복습 카드", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(modifier = Modifier.semantics { selected = reviewScope == ReviewCardScope.PERSONAL },
            onClick = { onScopeChange(ReviewCardScope.PERSONAL) }) { Text(if (reviewScope == ReviewCardScope.PERSONAL) "✓ 개인" else "개인") }
        if (canUseProject) TextButton(modifier = Modifier.semantics { selected = reviewScope == ReviewCardScope.PROJECT },
            onClick = { onScopeChange(ReviewCardScope.PROJECT) }) { Text(if (reviewScope == ReviewCardScope.PROJECT) "✓ 프로젝트" else "프로젝트") }
        TextButton(onClick = onCreateTextCard) { Text("카드 추가") }
    }
    if (cards.isEmpty()) Text("복습 카드가 없습니다.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    cards.forEach { card ->
        StudyQuickReviewCardRow(
            card, notificationsAllowed, onRate, onScheduleReminder, onEnableReminder,
            onDisableReminder, onCancelReminder, onDelete,
        )
    }
}

@Composable
internal fun StudyQuickReviewCardRow(
    card: StudyReviewCardEntity,
    notificationsAllowed: Boolean,
    onRate: (StudyReviewCardEntity, ReviewCardRating) -> Unit,
    onScheduleReminder: (StudyReviewCardEntity) -> Unit,
    onEnableReminder: (StudyReviewCardEntity) -> Unit,
    onDisableReminder: (StudyReviewCardEntity) -> Unit,
    onCancelReminder: (StudyReviewCardEntity) -> Unit,
    onDelete: (StudyReviewCardEntity) -> Unit,
    clockMillis: () -> Long = System::currentTimeMillis,
) {
    var answerVisible by remember(card.id) { mutableStateOf(false) }
    var menu by remember(card.id) { mutableStateOf(false) }
    var confirmDelete by remember(card.id) { mutableStateOf(false) }
    val currentTime by produceState(clockMillis(), card.id, card.dueAt) {
        while (value < card.dueAt) {
            delay((card.dueAt - clockMillis()).coerceIn(1L, 60_000L))
            value = clockMillis()
        }
    }
    val due = card.dueAt <= currentTime
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
      Column(Modifier.weight(1f)) {
        Text(card.front, style = MaterialTheme.typography.bodyMedium)
        card.reminderAt?.let { at ->
            val time = remember(card.id, at) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
            }
            Text(
                "$time · ${when { card.reminderEnabled && !notificationsAllowed -> "알림 권한 필요"; card.reminderEnabled -> "알림 켜짐"; else -> "알림 꺼짐" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (answerVisible) Text(card.back, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else TextButton(enabled = due, onClick = { answerVisible = true }) { Text(if (due) "답 보기" else "예정된 복습") }
        if (answerVisible && due) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onRate(card, ReviewCardRating.AGAIN) }) { Text("다시 보기") }
            TextButton(onClick = { onRate(card, ReviewCardRating.HARD) }) { Text("어려움") }
            TextButton(onClick = { onRate(card, ReviewCardRating.UNDERSTOOD) }) { Text("이해함") }
        }
      }
      Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "복습 카드 작업") }
        FusionDropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (card.reminderAt == null) "알림 예약" else "알림 시간 변경") },
                onClick = { menu = false; onScheduleReminder(card) },
            )
            if (card.reminderAt != null && card.reminderEnabled) DropdownMenuItem(
                text = { Text("알림 끄기") }, onClick = { menu = false; onDisableReminder(card) },
            )
            if (card.reminderAt != null && !card.reminderEnabled && card.reminderAt > System.currentTimeMillis()) DropdownMenuItem(
                text = { Text("알림 켜기") }, onClick = { menu = false; onEnableReminder(card) },
            )
            if (card.reminderAt != null) DropdownMenuItem(
                text = { Text("알림 취소") }, onClick = { menu = false; onCancelReminder(card) },
            )
            DropdownMenuItem(
                text = { Text("삭제") }, onClick = { menu = false; confirmDelete = true },
            )
        }
      }
    }
    HorizontalDivider()
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("복습 카드를 삭제할까요?") },
        text = { Text("저장한 카드와 예약된 알림이 함께 삭제됩니다.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(card) }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
    )
}

private fun studyReminderNotificationsAllowed(context: android.content.Context): Boolean {
    val runtimePermissionGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val notificationsEnabled = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    return runtimePermissionGranted && notificationsEnabled
}

private fun QuickReviewCardReminderResult.messageOrNull(): String? = when (this) {
    QuickReviewCardReminderResult.Updated -> null
    QuickReviewCardReminderResult.UnknownCard -> "복습 카드를 찾지 못해 알림을 변경하지 않았습니다."
    QuickReviewCardReminderResult.PermissionUnavailable -> "알림 권한이 없어 예약하지 않았습니다."
    QuickReviewCardReminderResult.InvalidTime -> "현재보다 늦은 시간을 선택해 주세요."
    QuickReviewCardReminderResult.TooFarAhead -> "알림은 1년 이내의 시간으로 설정해 주세요."
    QuickReviewCardReminderResult.CapacityReached -> "예약할 수 있는 복습 알림 수에 도달했습니다."
    QuickReviewCardReminderResult.Failed -> "복습 알림을 저장하지 못했습니다."
    QuickReviewCardReminderResult.NotificationCleanupFailed -> "알림 설정은 변경했지만 이전 알림을 지우지 못했습니다. 직접 닫아 주세요."
}
