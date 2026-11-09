package com.projectnuke.fusion.ui

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore
import com.projectnuke.fusion.ai.secure.AndroidKeystoreSecretStore
import com.projectnuke.fusion.ai.ui.AiProviderBeginnerSetupSurface
import com.projectnuke.fusion.ai.ui.AiProviderSettingsScreen
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.ComposerSubmissionSnapshot
import com.projectnuke.fusion.chat.FreshNewChatResult
import com.projectnuke.fusion.chat.PROJECT_SYNTHESIS_READY_MESSAGE
import com.projectnuke.fusion.ConversationShortcutNavigation
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.handoff.DocumentHandoffCoordinator
import com.projectnuke.fusion.handoff.ExternalDocumentHandoff
import com.projectnuke.fusion.handoff.ExternalHandoff
import com.projectnuke.fusion.handoff.ExternalHandoffChoiceDialog
import com.projectnuke.fusion.handoff.ExternalHandoffDestination
import com.projectnuke.fusion.handoff.ExternalHandoffRouteController
import com.projectnuke.fusion.handoff.ExternalHandoffSelection
import com.projectnuke.fusion.handoff.ExternalHandoffSelectionRepository
import com.projectnuke.fusion.handoff.ExternalTextHandoff
import com.projectnuke.fusion.handoff.HandoffImportOutcome
import com.projectnuke.fusion.handoff.TextHandoffCoordinator
import com.projectnuke.fusion.tasks.AndroidProjectTaskReminderScheduler
import com.projectnuke.fusion.tasks.reconcilePendingReminderNotificationCleanup
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.isPhysicalCleanupSettled
import com.projectnuke.fusion.ui.CommittedDraftReconciliationDebtStore
import com.projectnuke.fusion.ui.DraftReconciliationOwner
import com.projectnuke.fusion.ui.DraftReconciliationResult
import com.projectnuke.fusion.ui.onboarding.FusionOnboardingAction
import com.projectnuke.fusion.ui.onboarding.FusionOnboardingCompletionStore
import com.projectnuke.fusion.ui.onboarding.FusionOnboardingScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs the durable Fresh New Chat commit and classifies its exception
 * boundary: CancellationException and every fatal Error (including
 * LinkageError) propagate, while an ordinary RuntimeException is a bounded
 * retryable UI failure reported to [onBoundedFailure]. Returns the committed
 * result, or null when a bounded failure was reported.
 */
internal suspend fun attemptFreshNewChatCommit(
    commit: suspend () -> FreshNewChatResult,
    onBoundedFailure: (RuntimeException) -> Unit,
): FreshNewChatResult? = try {
    commit()
} catch (cancellation: kotlinx.coroutines.CancellationException) {
    throw cancellation
} catch (failure: RuntimeException) {
    onBoundedFailure(failure)
    null
}

@Composable
internal fun rememberFusionOnboardingVisibility(
    store: FusionOnboardingCompletionStore,
): androidx.compose.runtime.MutableState<Boolean> =
    rememberSaveable { mutableStateOf(!store.isCompleted()) }

private data class PendingProjectWorkPlanSubmission(
    val attempt: com.projectnuke.fusion.projects.ProjectWorkPlanAttempt,
    val newChatEpoch: Long,
    val conversationId: Long? = null,
    val userMessageId: Long? = null,
    /** Exact submission token from the commit; first commit wins. */
    val submissionToken: String? = null,
    /** Exact generation request for that submission; first wins. */
    val requestId: String? = null,
    /**
     * Finding C: immutable Project authority frozen at the execution
     * boundary. Consumed (after validation) by the send path.
     */
    val frozenProjectContext: com.projectnuke.fusion.chat.ProjectContextSnapshot? = null,
)

private fun workPlanHandoffMessage(
    action: com.projectnuke.fusion.projects.ProjectWorkPlanPreparedAction,
): String = when {
    action.frozenSelection != null ->
        "선택한 자료와 함께 Project 대화를 준비했습니다. 내용을 확인하고 직접 전송해 주세요."
    action.destination == com.projectnuke.fusion.projects.ProjectWorkPlanDestination.RESEARCH_CHAT ->
        "근거 조사를 Research 모드로 준비했습니다. 내용을 확인하고 직접 전송해 주세요."
    action.destination == com.projectnuke.fusion.projects.ProjectWorkPlanDestination.PROJECT_CONTEXT_CHAT ->
        "Project 대화를 준비했습니다. 내용을 확인하고 직접 전송해 주세요."
    else ->
        "초안을 채팅에 준비했습니다. 확인한 뒤 직접 전송해 주세요."
}

@Composable
fun FusionApp(
    pendingHandoff: ExternalHandoff? = null,
    onHandoffConsumed: () -> Unit = {},
    pendingShortcutNavigation: ConversationShortcutNavigation? = null,
    onShortcutConsumed: () -> Unit = {},
    pendingReminderNavigation: com.projectnuke.fusion.ReminderNavigation? = null,
    onReminderConsumed: () -> Unit = {},
) {
  val context = LocalContext.current
  val factory = remember(context) { ChatViewModel.factory(context) }
  val chatViewModel: ChatViewModel = viewModel(factory = factory)
  LaunchedEffect(context) {
    // Reconcile persisted notification cleanup debt after a process restart.
    // This only cancels obsolete alerts and restores any still-current alarm;
    // it does not deliver overdue reminders as a side effect of app startup.
    try {
      withContext(Dispatchers.IO) {
        val database = AppDatabase.getInstance(context)
        val scheduler = AndroidProjectTaskReminderScheduler(context.applicationContext)
        reconcilePendingReminderNotificationCleanup(database, scheduler, scheduler)
      }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      Log.w("FusionReminder", "Could not reconcile pending reminder cleanup", failure)
    }
  }
  val aiSecretStore = remember { AndroidKeystoreSecretStore(context) }
  // Single first-run owner: the onboarding flow shows on cold start until
  // completed, and can be reopened manually from the drawer (Settings/Help).
  val onboardingStore = remember(context) {
    FusionOnboardingCompletionStore(
      context.getSharedPreferences("fusion_chat_settings", android.content.Context.MODE_PRIVATE)
    )
  }
  var showOnboarding by rememberFusionOnboardingVisibility(onboardingStore)
  var showBeginnerProvider by remember { mutableStateOf(false) }
  var showAdvancedProviderSettings by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
  var pendingRestoredConversationId by remember(chatViewModel) {
    mutableStateOf(
      chatViewModel.currentConversationId.value.takeIf { it > ChatViewModel.NEW_CONVERSATION_ID },
    )
  }
  var pendingProjectWorkPlanSubmission by remember(chatViewModel) {
    mutableStateOf<PendingProjectWorkPlanSubmission?>(null)
  }
  LaunchedEffect(chatViewModel) {
    // Restore-time guard: a rebuilt process may hold a SavedStateHandle
    // current-conversation id whose row no longer exists (kill between the
    // Room delete-commit and the settlement redirect). Revalidate against
    // durable truth before any UI renders the ghost; New Chat fallback is
    // exactly what the live-deletion redirect path already uses.
    val restoredId = chatViewModel.currentConversationId.value
    if (restoredId > ChatViewModel.NEW_CONVERSATION_ID) {
      val database0 = AppDatabase.getInstance(context)
      val chatDao0 = database0.chatDao()
      val archiveLockEnabled = context.getSharedPreferences(
        "fusion_chat_settings",
        android.content.Context.MODE_PRIVATE,
      ).getBoolean(ArchiveLockPreferenceKey, false)
      chatViewModel.revalidateRestoredConversation { id ->
        restoredConversationAdmissionOrUnreadable(
          conversationLookup = { chatDao0.getConversationById(id) },
          archiveLockEnabled = archiveLockEnabled,
        ) == RestoredConversationAdmission.ALLOW
      }
    }
    // The id is either admitted, rejected to New Chat, or no longer current
    // because the user selected a newer owner while the lookup was suspended.
    // In all three cases the content surface may now proceed.
    if (pendingRestoredConversationId == restoredId) {
      pendingRestoredConversationId = null
    }
    val reconciliationFile = File(context.filesDir, "committed_draft_reconciliation_debt.json")
    if (chatViewModel.draftMachine.awaitHydration()) {
      // One startup owner: hydration -> exact reconciliation of durable debts.
      // No automatic no-debt orphan reclamation: a disk-restored token with no
      // matching debt is ambiguous (died before vs after the Room user-message
      // commit) and must fail safe without clearing, to avoid duplicating an
      // already-committed message. Normal text edits remain independent.
      // Fail-safe: unreadable durable draft ownership must not be treated as
      // proof that drafts are absent. Debts are preserved untouched.
      val health = chatViewModel.draftHealthNow()
      val outcome = if (!com.projectnuke.fusion.chat.shouldProcessReconciliationDebts(health)) {
        Log.w("FusionRecovery", "Draft store unreadable ($health); preserving reconciliation debts")
        null
      } else {
        CommittedDraftReconciliationDebtStore.retry(
          owner = DraftReconciliationOwner { debt ->
            val before = chatViewModel.draft(debt.draftKey)
            val release = before.activeSubmissionToken == null ||
                before.activeSubmissionToken == debt.token
            DraftReconciliationResult(
              success = chatViewModel.reconcileCommittedSubmission(
                snapshot = ComposerSubmissionSnapshot(
                  token = debt.token,
                  conversationId = debt.draftKey,
                  rawInput = debt.capturedRawInput,
                  pendingAttachments = emptyList(),
                  version = 0L,
                ),
                committedPaths = debt.committedPaths.toList(),
              ),
              releasePaths = release,
            )
          },
          unregisterPath = AttachmentStorageManager::unregisterPendingAttachment,
          file = reconciliationFile,
        )
      }
      if (outcome != null && !outcome.persistenceOk) {
        Log.w("FusionRecovery", "CommittedDraft startup recovery not durably persisted (load=${outcome.load::class.simpleName})")
      }

      val database = AppDatabase.getInstance(context)
      val chatDao = database.chatDao()
      val submissionWitnessDao = database.submissionCommitWitnessDao()
      val responseWitnessDao = database.responseVersionCommitWitnessDao()
      if (com.projectnuke.fusion.chat.shouldProcessReconciliationDebts(chatViewModel.draftHealthNow())) {
        // Schema-8 startup order: legacy file debt first, then the Room-owned
        // submission witness and unambiguous no-witness token repair.
        recoverSchema8SubmissionWitnesses(
          chatViewModel = chatViewModel,
          witnessDao = submissionWitnessDao,
          unregisterPendingAttachment = { path ->
            AttachmentStorageManager.unregisterPendingAttachment(path)
          },
        )
      } else {
        Log.w("FusionRecovery", "Composer unreadable; preserving schema-8 submission witnesses")
      }
      // Response-version witnesses do not depend on composer readability and
      // use the normal structural-lease -> response-mutex ordering.
      recoverResponseVersionCommitWitnesses(context, chatDao, responseWitnessDao)
    }
    // Retry obsolete secret cleanup on startup (bounded; failures are logged, never crash).
    val secretOutcome = ObsoleteSecretCleanupDebtStore.retry(
      secretStore = aiSecretStore,
      file = File(context.filesDir, "obsolete_secret_cleanup_debt.json"),
    )
    if (!secretOutcome.persistenceOk) {
      Log.w("FusionRecovery", "ObsoleteSecret startup recovery not durably persisted (load=${secretOutcome.load::class.simpleName})")
    }
  }
val currentConversationId by chatViewModel.currentConversationId.collectAsStateWithLifecycle()
    var authorizedArchiveConversationId by remember { mutableStateOf<Long?>(null) }
    var pendingCheckpointTarget by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var pendingResearchAnswerTarget by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var pendingResearchRefreshTarget by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val pendingProjectNewChatId by chatViewModel.pendingProjectNewChatId.collectAsStateWithLifecycle()
    val newChatEpoch by chatViewModel.newChatEpoch.collectAsStateWithLifecycle()
    // Motion fallback from the OS: zero animator scale -> MINIMAL, any active
    // accessibility service -> REDUCED (conservative, still fully functional),
    // otherwise FULL. Behavior never depends on animation completion.
    val fluidMotionMode = remember {
        val animatorScale = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        val accessibilityActive = (context.getSystemService(
            android.view.accessibility.AccessibilityManager::class.java,
        ))?.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ).orEmpty().isNotEmpty()
        com.projectnuke.fusion.ui.FluidMotionPolicy.resolve(animatorScale, accessibilityActive)
    }
    // True monotonic gates: next* is never reset; pending* is the only
    // nullable event slot cleared on acknowledgement. The child observes
    // `pending ?: 0`, so a second request always yields a distinct token and
    // a recreated child can never reopen an acknowledged request.
    var nextAiProviderRequestToken by remember { mutableLongStateOf(0L) }
    var pendingAiProviderRequestToken by remember { mutableStateOf<Long?>(null) }
    var modelLibraryRequestGate by remember { mutableStateOf(MonotonicRequestGate()) }
    var advancedSettingsRequestGate by remember { mutableStateOf(MonotonicRequestGate()) }
    var cameraCaptureRequestGate by remember { mutableStateOf(MonotonicRequestGate()) }
    var localModelStorageRevision by remember { mutableStateOf(0) }
    var nextBenchmarkRequestToken by remember { mutableLongStateOf(0L) }
    var pendingBenchmarkRequestToken by remember { mutableStateOf<Long?>(null) }
    var benchmarkRequestModelFilter by remember { mutableStateOf<String?>(null) }
    var benchmarkRequestOpenHistory by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val translationStateOwner: StandaloneTranslationViewModel = viewModel(factory = StandaloneTranslationViewModel.factory(context))
    var mainSurface by translationStateOwner::mainSurface
    var composerHandoffInProgress by remember { mutableStateOf(false) }
    var composerHandoffMessage by remember { mutableStateOf<String?>(null) }
    val translationRuntimeOwner = translationStateOwner.runtimeOwner
    val standaloneTranslationController = translationStateOwner.controller

    fun openTranslation() {
        composerHandoffMessage = null
        standaloneTranslationController.open("")
        scope.launch {
            drawerState.close()
            mainSurface = mainSurface.openTranslation()
        }
    }

    // Chat "번역에서 열기": seeds Translation with the visible answer copy and
    // switches surface. No auto-translation; Back returns to the same chat.
    fun openTranslationWithSource(sourceText: String) {
        composerHandoffMessage = null
        standaloneTranslationController.open(sourceText)
        scope.launch {
            drawerState.close()
            mainSurface = mainSurface.openTranslation()
        }
    }

    fun returnToChat() {
        composerHandoffMessage = null
        standaloneTranslationController.close()
        mainSurface = mainSurface.returnToChat()
    }

    fun insertTranslationIntoComposer() {
        if (composerHandoffInProgress) return
        val targetConversationId = chatViewModel.currentConversationId.value
        val translatedText = standaloneTranslationController.composerReplacementText()
        composerHandoffInProgress = true
        composerHandoffMessage = null
        scope.launch {
            val result = applyTranslationToComposer(
                replacementText = translatedText,
                conversationId = targetConversationId,
                replaceDurably = { conversationId, rawInput ->
                    chatViewModel.replaceDraftTextDurably(conversationId, rawInput)
                },
                canWrite = {
                    chatViewModel.currentConversationId.value == targetConversationId
                },
            )
            composerHandoffInProgress = false
            when (resolveTranslationComposerHandoffPresentation(
                result = result,
                targetConversationId = targetConversationId,
                currentConversationId = chatViewModel.currentConversationId.value,
            )) {
                TranslationComposerHandoffPresentation.SuccessTargetStillVisible -> returnToChat()
                TranslationComposerHandoffPresentation.SuccessTargetChanged -> {
                    composerHandoffMessage = "번역 결과를 원래 대화의 입력창에 저장했어요."
                }
                TranslationComposerHandoffPresentation.NoUsableResult -> {
                    composerHandoffMessage = "사용할 수 있는 번역 결과가 없어요."
                }
                TranslationComposerHandoffPresentation.DraftRejected -> {
                    composerHandoffMessage = "입력창이 사용 중이어서 번역 결과를 넣지 못했어요."
                }
            }
        }
    }

    // The fresh reset already durably removed the old NEW_CHAT draft's
    // references; the returned paths are released through the same
    // authority-aware seam as composer chip removal so shared managed files
    // (historical reattach, project rows, other drafts) always survive.
    suspend fun releaseRemovedDraftAttachmentPaths(paths: List<String>, logTag: String) {
        if (paths.isEmpty()) return
        val database = AppDatabase.getInstance(context)
        AttachmentStorageManager.releaseRemovedDraftAttachments(
            context,
            database.chatDao(),
            database.projectDao(),
            paths,
        ).forEach { (path, outcome) ->
            if (!outcome.isPhysicalCleanupSettled()) {
                Log.w(logTag, "Removed-draft attachment release deferred for $path ($outcome)")
            }
        }
    }

    // Single product owner for every New Chat entry point. The ViewModel
    // performs the durable reset and monotonic same-id epoch transition.
    fun openFreshNewChat(
        projectId: Long? = null,
        selectionContext: com.projectnuke.fusion.chat.SelectionContextSnapshot? = null,
        initialPrompt: String? = null,
    ) {
        authorizedArchiveConversationId = null
        scope.launch {
            // Fatal Errors (LinkageError, VirtualMachineError, ...) must
            // propagate from this boundary; only an ordinary RuntimeException
            // is a bounded retryable UI failure.
            val result = attemptFreshNewChatCommit(
                commit = { chatViewModel.openFreshNewChat(projectIdAfterCommit = projectId) },
                onBoundedFailure = { failure ->
                    Log.e("FusionNewChat", "Fresh New Chat failed", failure)
                    Toast.makeText(context, "새 채팅을 열지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                },
            ) ?: return@launch
            if (!result.success) {
                Log.w("FusionNewChat", "Fresh New Chat was not committed: ${result.failure}")
                Toast.makeText(context, "새 채팅을 열지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            releaseRemovedDraftAttachmentPaths(result.pendingAttachmentPaths, "FusionNewChat")
            if (selectionContext != null &&
                !chatViewModel.installSelectionContext(selectionContext, result.epoch)
            ) {
                Toast.makeText(context, "선택한 자료를 새 채팅에 연결하지 못했습니다. 다시 선택해 주세요.", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!initialPrompt.isNullOrBlank() &&
                !chatViewModel.appendPromptDurably(0L, initialPrompt, ::appendQuickPrompt)
            ) {
                Toast.makeText(context, "비교 요청을 입력창에 넣지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(context, "새 채팅을 열었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(enabled = mainSurface == FusionMainSurface.CHAT && drawerState.currentValue == DrawerValue.Open) {
        scope.launch { drawerState.close() }
    }

    // Android -> Fusion conversation handoff: the event is owned by
    // MainActivity and consumed exactly once after import settles (or after a
    // terminal user-visible failure). Onboarding stays authoritative: the event
    // is deferred, never discarded, while first-run setup is still showing.
    // Keying on the event id means recomposition, surface switches and drawer
    // navigation can never re-import the same event.
    val handoffCoordinator = remember(context) { TextHandoffCoordinator(context.applicationContext) }
  val documentHandoffCoordinator = remember(context) { DocumentHandoffCoordinator(context.applicationContext) }
    val handoffRouteController = remember(context, chatViewModel, handoffCoordinator, documentHandoffCoordinator) {
        ExternalHandoffRouteController(
            database = AppDatabase.getInstance(context),
            chatViewModel = chatViewModel,
            textCoordinator = handoffCoordinator,
            documentCoordinator = documentHandoffCoordinator,
        )
    }
    var selectedHandoffEventId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var handoffChoiceReadyEventId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var selectedHandoffDestination by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var selectedHandoffProjectId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    // Pinned-shortcut navigation: one-shot, deferred (never lost) while
    // onboarding is authoritative. Selects the exact persisted chat with no
    // fresh chat and no generation; a deleted target only shows a bounded
    // message and stays in normal Chat/history.
    LaunchedEffect(pendingShortcutNavigation?.eventId, showOnboarding) {
        val navigation = pendingShortcutNavigation ?: return@LaunchedEffect
        if (showOnboarding) return@LaunchedEffect
        mainSurface = mainSurface.openChat()
        try {
            val conversation = try {
                AppDatabase.getInstance(context).chatDao().getConversationById(navigation.conversationId)
            } catch (_: Exception) {
                null
            }
            val archiveLockEnabled = context.getSharedPreferences(
                "fusion_chat_settings",
                android.content.Context.MODE_PRIVATE,
            ).getBoolean(ArchiveLockPreferenceKey, false)
            if (conversation == null) {
                Toast.makeText(context, "대화를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            } else if (!ArchiveAccessPolicy.canAccessConversation(
                    isArchived = conversation.isArchived,
                    archiveLockEnabled = archiveLockEnabled,
                    archiveUnlockedForSession = false,
                )
            ) {
                Toast.makeText(context, "잠긴 아카이브 대화는 먼저 아카이브에서 인증해 열어 주세요.", Toast.LENGTH_SHORT).show()
            } else {
                authorizedArchiveConversationId = null
                chatViewModel.selectConversation(navigation.conversationId)
            }
            onShortcutConsumed()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            if (!consumeExternalEventAfterFailure(failure)) throw failure
            Log.e("FusionShortcut", "Shortcut navigation failed", failure)
            Toast.makeText(context, "대화를 열지 못했습니다.", Toast.LENGTH_SHORT).show()
            onShortcutConsumed()
        }
    }
    LaunchedEffect(pendingHandoff?.id, showOnboarding) {
        val pending = pendingHandoff ?: return@LaunchedEffect
        if (showOnboarding) return@LaunchedEffect
        selectedHandoffEventId = pending.id
        handoffChoiceReadyEventId = null
        val receipt = ExternalHandoffSelectionRepository(AppDatabase.getInstance(context)).get(pending.id)
        if (receipt == null) {
            selectedHandoffDestination = null
            selectedHandoffProjectId = null
            handoffChoiceReadyEventId = pending.id
            return@LaunchedEffect
        }
        if (receipt.settledAt != null) {
            if (receipt.outcome == HandoffImportOutcome.PARTIALLY_IMPORTED.name) {
                Toast.makeText(context, "문서 일부만 저장되었어요. 저장된 문서를 확인해 주세요.", Toast.LENGTH_LONG).show()
            }
            onHandoffConsumed()
            selectedHandoffEventId = null
            handoffChoiceReadyEventId = null
            selectedHandoffDestination = null
            selectedHandoffProjectId = null
            return@LaunchedEffect
        }
        selectedHandoffDestination = receipt.destination
        selectedHandoffProjectId = receipt.targetProjectId
        handoffChoiceReadyEventId = pending.id
    }
    LaunchedEffect(
        pendingHandoff?.id,
        selectedHandoffEventId,
        selectedHandoffDestination,
        selectedHandoffProjectId,
        showOnboarding,
    ) {
        val pending = pendingHandoff ?: return@LaunchedEffect
        if (showOnboarding || selectedHandoffEventId != pending.id) return@LaunchedEffect
        val destination = selectedHandoffDestination?.let { runCatching { ExternalHandoffDestination.valueOf(it) }.getOrNull() }
            ?: return@LaunchedEffect
        val selection = ExternalHandoffSelection(pending.id, destination, selectedHandoffProjectId)
        if (!com.projectnuke.fusion.handoff.ExternalHandoffDestinationPolicy.accepts(selection, pending)) {
            Toast.makeText(context, "공유 목적지를 확인할 수 없습니다.", Toast.LENGTH_SHORT).show()
            onHandoffConsumed()
            selectedHandoffEventId = null
            handoffChoiceReadyEventId = null
            selectedHandoffDestination = null
            selectedHandoffProjectId = null
            return@LaunchedEffect
        }
        if (destination == ExternalHandoffDestination.CHAT) mainSurface = mainSurface.openChat()
        try {
            val outcome = handoffRouteController.route(pending, selection)
            if (outcome == HandoffImportOutcome.PARTIALLY_IMPORTED) {
                // The destination coordinator already emitted its exact batch
                // counts immediately after its durable commit. The receipt
                // preserves this partial state for recreation/replay.
                Log.i("FusionHandoff", "External document handoff partially imported")
            } else if (outcome != HandoffImportOutcome.IMPORTED) {
                Log.w("FusionHandoff", "External handoff not imported: $outcome")
                // Explicit bounded state (C14): over-bound shared text is
                // rejected, never silently truncated and reported as stored.
                val message = if (outcome == HandoffImportOutcome.REJECTED_TOO_LARGE) {
                    "공유한 텍스트가 너무 길어 저장하지 못했습니다. 줄인 뒤 다시 공유해 주세요."
                } else {
                    "공유한 자료를 저장하지 못했습니다."
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            onHandoffConsumed()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // The coordinator already settled its partial state; the event stays
            // pending so the recreated composition can retry it.
            throw cancellation
        } catch (failure: Throwable) {
            if (!consumeExternalEventAfterFailure(failure)) throw failure
            Log.e("FusionHandoff", "External handoff failed", failure)
            Toast.makeText(context, "공유한 자료를 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
            onHandoffConsumed()
        }
        selectedHandoffEventId = null
        handoffChoiceReadyEventId = null
        selectedHandoffDestination = null
        selectedHandoffProjectId = null
    }

    CompositionLocalProvider(
        LocalFluidMotionMode provides fluidMotionMode,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
      pendingHandoff?.takeIf {
          !showOnboarding && selectedHandoffEventId == it.id && handoffChoiceReadyEventId == it.id && selectedHandoffDestination == null
      }?.let { pending ->
          ExternalHandoffChoiceDialog(
              database = AppDatabase.getInstance(context),
              handoff = pending,
              onSelect = { destination, projectId ->
                  val selection = ExternalHandoffSelection(pending.id, destination, projectId)
                  scope.launch {
                      when (ExternalHandoffSelectionRepository(AppDatabase.getInstance(context)).select(selection)) {
                          com.projectnuke.fusion.handoff.HandoffSelectionResult.Stored,
                          com.projectnuke.fusion.handoff.HandoffSelectionResult.Existing -> {
                              selectedHandoffEventId = pending.id
                              selectedHandoffDestination = destination.name
                              selectedHandoffProjectId = projectId
                          }
                          else -> Toast.makeText(context, "공유 목적지를 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
                      }
                  }
              },
              onDismiss = {
                  onHandoffConsumed()
                  selectedHandoffEventId = null
                  handoffChoiceReadyEventId = null
                  selectedHandoffDestination = null
                  selectedHandoffProjectId = null
              },
          )
      }
      if (mainSurface == FusionMainSurface.CHAT) {
        ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface
            ) {
ConversationListScreenV2(
  chatViewModel = chatViewModel,
  currentConversationId = currentConversationId,
                    onBack = {
                        scope.launch {
                            drawerState.close()
                        }
                    },
                    onOpenConversation = { conversationId, authorizedSession ->
                        pendingCheckpointTarget = null
                        pendingResearchAnswerTarget = null
                        pendingResearchRefreshTarget = null
                        scope.launch {
                            val allowed = archiveConversationAdmission(
                                lookup = { AppDatabase.getInstance(context).chatDao().getConversationById(conversationId) },
                                archiveLockEnabled = {
                                    context.getSharedPreferences("fusion_chat_settings", android.content.Context.MODE_PRIVATE)
                                        .getBoolean(ArchiveLockPreferenceKey, false)
                                },
                                archiveUnlockedForSession = authorizedSession,
                            )
                            if (!allowed) {
                                Toast.makeText(context, "대화를 열 수 없습니다. 잠긴 아카이브는 먼저 인증해 주세요.", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            mainSurface = mainSurface.openChat()
                            authorizedArchiveConversationId = conversationId.takeIf { authorizedSession }
                            chatViewModel.selectConversation(conversationId)
                            drawerState.close()
                        }
                    },
                    onConversationRemovedFromList = { removedConversationId, nextConversationId ->
                        if (currentConversationId == removedConversationId) {
                            authorizedArchiveConversationId = null
                            chatViewModel.selectConversation(nextConversationId ?: 0L)
                        }
                    },
                    onNewChat = {
                        pendingCheckpointTarget = null
                        pendingResearchAnswerTarget = null
                        pendingResearchRefreshTarget = null
                        mainSurface = mainSurface.openChat()
                        openFreshNewChat()
                        scope.launch {
                            drawerState.close()
                        }
                    },
                    onNewProjectChat = { projectId ->
                        pendingCheckpointTarget = null
                        pendingResearchAnswerTarget = null
                        pendingResearchRefreshTarget = null
                        mainSurface = mainSurface.openChat()
                        openFreshNewChat(projectId)
                        scope.launch { drawerState.close() }
                    },
                    onOpenResearchAnswer = { conversationId, messageId, authorizedSession ->
                        scope.launch {
                            val db = AppDatabase.getInstance(context)
                            val allowed = archiveConversationAdmission(
                                lookup = { db.chatDao().getConversationById(conversationId) },
                                archiveLockEnabled = {
                                    context.getSharedPreferences("fusion_chat_settings", android.content.Context.MODE_PRIVATE)
                                        .getBoolean(ArchiveLockPreferenceKey, false)
                                },
                                archiveUnlockedForSession = authorizedSession,
                            )
                            val target = db.chatDao().getMessageById(messageId)
                                ?.takeIf { it.conversationId == conversationId && it.role == "assistant" }
                            if (!allowed || target == null) {
                                Toast.makeText(context, "Research 결과를 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            pendingCheckpointTarget = null
                            pendingResearchAnswerTarget = conversationId to messageId
                            pendingResearchRefreshTarget = null
                            mainSurface = mainSurface.openChat()
                            authorizedArchiveConversationId = conversationId.takeIf { authorizedSession }
                            chatViewModel.selectConversation(conversationId)
                            drawerState.close()
                        }
                    },
                    onOpenCheckpoint = { checkpointId, conversationId, messageId, authorizedSession ->
                        scope.launch {
                            val db = AppDatabase.getInstance(context)
                            val allowed = archiveConversationAdmission(
                                lookup = {
                                    val checkpoint = db.conversationCheckpointDao().getOwned(checkpointId, conversationId)
                                    if (checkpoint?.messageId == messageId && db.conversationCheckpointDao().hasAssistantAnchor(conversationId, messageId)) {
                                        db.chatDao().getConversationById(conversationId)
                                    } else null
                                },
                                archiveLockEnabled = {
                                    context.getSharedPreferences("fusion_chat_settings", android.content.Context.MODE_PRIVATE)
                                        .getBoolean(ArchiveLockPreferenceKey, false)
                                },
                                archiveUnlockedForSession = authorizedSession,
                            )
                            if (!allowed) {
                                Toast.makeText(context, "체크포인트를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            pendingCheckpointTarget = conversationId to messageId
                            pendingResearchAnswerTarget = null
                            pendingResearchRefreshTarget = null
                            mainSurface = mainSurface.openChat()
                            authorizedArchiveConversationId = conversationId.takeIf { authorizedSession }
                            chatViewModel.selectConversation(conversationId)
                            drawerState.close()
                        }
                    },
                    onStartResearchRefresh = { sourceConversationId, sourceMessageId, authorizedSession ->
                        scope.launch {
                            val database = AppDatabase.getInstance(context)
                            val exactQueueState = ResearchRefreshQueueRepository(
                                RoomResearchRefreshQueueDataSource(database, context.applicationContext),
                            ).load(
                                includeArchived = authorizedSession,
                                requestedTarget = sourceConversationId to sourceMessageId,
                            )
                            if (exactQueueState.requestedTargetStatus != ResearchRefreshQueueTargetStatus.ELIGIBLE) {
                                Toast.makeText(context, "선택한 Research 원본이 바뀌었거나 현재 다시 확인할 수 없습니다.", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val allowed = archiveConversationAdmission(
                                lookup = {
                                    val conversation = database.chatDao().getConversationById(sourceConversationId)
                                        ?: return@archiveConversationAdmission null
                                    val source = database.chatDao().getMessageById(sourceMessageId)
                                        ?.takeIf {
                                            it.conversationId == sourceConversationId && it.role == "assistant" &&
                                                com.projectnuke.fusion.chat.AnswerContextReceipt.parse(it.content)
                                                    ?.let { receipt ->
                                                        receipt.interactionMode == com.projectnuke.fusion.chat.ChatInteractionMode.RESEARCH.name &&
                                                            com.projectnuke.fusion.chat.ResearchRefreshPolicy.restoreSourceScope(receipt) != null
                                                    } == true
                                        } ?: return@archiveConversationAdmission null
                                    val versions = when (val loaded = loadResponseVersionStateResult(context, sourceConversationId)) {
                                        ResponseVersionLoad.Missing -> ResponseVersionState()
                                        is ResponseVersionLoad.Valid -> loaded.state
                                        ResponseVersionLoad.Corrupt,
                                        ResponseVersionLoad.Oversized -> return@archiveConversationAdmission null
                                    }
                                    val messages = database.chatDao().getMessagesLatestLimited(sourceConversationId, 128)
                                    if (activeTimelineMessages(messages, versions).none { it.id == source.id && it.role == "assistant" }) {
                                        null
                                    } else {
                                        conversation
                                    }
                                },
                                archiveLockEnabled = {
                                    context.getSharedPreferences("fusion_chat_settings", android.content.Context.MODE_PRIVATE)
                                        .getBoolean(ArchiveLockPreferenceKey, false)
                                },
                                archiveUnlockedForSession = authorizedSession,
                            )
                            if (!allowed) {
                                Toast.makeText(context, "다시 확인할 Research 결과를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            pendingCheckpointTarget = null
                            pendingResearchAnswerTarget = null
                            pendingResearchRefreshTarget = sourceConversationId to sourceMessageId
                            mainSurface = mainSurface.openChat()
                            authorizedArchiveConversationId = sourceConversationId.takeIf { authorizedSession }
                            chatViewModel.selectConversation(sourceConversationId)
                            drawerState.close()
                        }
                    },
                    onContinueFromCheckpoint = { checkpointId, conversationId, messageId, authorizedSession ->
                        val db = AppDatabase.getInstance(context)
                        val allowed = archiveConversationAdmission(
                            lookup = {
                                val checkpoint = db.conversationCheckpointDao().getOwned(checkpointId, conversationId)
                                if (checkpoint?.messageId == messageId && db.conversationCheckpointDao().hasAssistantAnchor(conversationId, messageId)) {
                                    db.chatDao().getConversationById(conversationId)
                                } else null
                            },
                            archiveLockEnabled = {
                                context.getSharedPreferences("fusion_chat_settings", android.content.Context.MODE_PRIVATE)
                                    .getBoolean(ArchiveLockPreferenceKey, false)
                            },
                            archiveUnlockedForSession = authorizedSession,
                        )
                        if (!allowed) {
                            Toast.makeText(context, "체크포인트를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                            false
                        } else {
                            val coordinator = ConversationBranchCoordinator(
                                chatDao = db.chatDao(),
                                evidenceDao = db.evidenceDao(),
                                projectDao = db.projectDao(),
                                runInTransaction = { operation -> db.withTransaction { operation() } },
                                loadVersions = { id -> loadResponseVersionState(context, id) },
                            )
                            when (val outcome = withContext(Dispatchers.IO) {
                                coordinator.branch(conversationId, messageId)
                            }) {
                                is BranchOutcome.Success -> {
                                    mainSurface = mainSurface.openChat()
                                    authorizedArchiveConversationId = null
                                    chatViewModel.selectConversation(outcome.newConversationId)
                                    drawerState.close()
                                    true
                                }
                                is BranchOutcome.Failure -> {
                                    Toast.makeText(context, "이 지점에서 새 대화를 만들 수 없습니다.", Toast.LENGTH_SHORT).show()
                                    false
                                }
                            }
                        }
                    },
                    onQuestionWithSelection = { selectionContext ->
                        mainSurface = mainSurface.openChat()
                        openFreshNewChat(selectionContext = selectionContext)
                        scope.launch { drawerState.close() }
                    },
                    onCompareWithSelection = { selectionContext, prompt ->
                        mainSurface = mainSurface.openChat()
                        openFreshNewChat(selectionContext = selectionContext, initialPrompt = prompt)
                        scope.launch { drawerState.close() }
                    },
                    onResearchWithProjects = { selectionContext ->
                        mainSurface = mainSurface.openChat()
                        openFreshNewChat(selectionContext = selectionContext)
                        scope.launch { drawerState.close() }
                    },
                    onSynthesizeBranches = { selectionContext, prompt ->
                        mainSurface = mainSurface.openChat()
                        openFreshNewChat(selectionContext = selectionContext, initialPrompt = prompt)
                        scope.launch { drawerState.close() }
                    },
                    // Project Synthesis (F5): atomically seeds a fresh project
                    // chat with the user-visible synthesis request. The drawer
                    // closes only after the seeded adoption commits; on any
                    // failure the existing surface stays authoritative and the
                    // project page surfaces the failure copy. Never generates.
                    onNewProjectSynthesisChat = { projectId, prompt ->
                        val seeded = projectSynthesisSeedBounded(
                            onFailure = { failure ->
                                Log.e("FusionSynthesis", "Project synthesis handoff failed", failure)
                            },
                            block = {
                                chatViewModel.openFreshProjectChatSeeded(projectId, prompt)
                            },
                        )
                        if (seeded == null || !seeded.success) {
                            if (seeded != null) {
                                Log.w("FusionSynthesis", "Project synthesis handoff not committed: ${seeded.failure}")
                            }
                            false
                        } else {
                            mainSurface = mainSurface.openChat()
                            releaseRemovedDraftAttachmentPaths(seeded.pendingAttachmentPaths, "FusionSynthesis")
                            scope.launch { drawerState.close() }
                            Toast.makeText(context, PROJECT_SYNTHESIS_READY_MESSAGE, Toast.LENGTH_SHORT).show()
                            true
                        }
                    },
                    onDispatchProjectWorkPlanStep = { action ->
                        // Chat destinations only: seed the real composer with
                        // the frozen step instruction (and frozen selection
                        // when present). Nothing is ever sent automatically —
                        // the user reviews and sends, and the existing Chat
                        // fences the MESSAGE receipt for this exact attempt.
                        val destination = action.destination
                        val toChat = action.frozenSelection != null ||
                            destination == com.projectnuke.fusion.projects.ProjectWorkPlanDestination.RESEARCH_CHAT ||
                            destination == com.projectnuke.fusion.projects.ProjectWorkPlanDestination.PROJECT_CONTEXT_CHAT ||
                            destination == com.projectnuke.fusion.projects.ProjectWorkPlanDestination.DRAFT_COMPOSER
                        if (!toChat) {
                            false
                        } else {
                            val seededEpoch = projectSynthesisSeedBounded(
                                onFailure = { failure ->
                                    Log.e("FusionWorkPlan", "Step handoff failed", failure)
                                },
                                block = {
                                    val frozenSelection = action.frozenSelection
                                    if (frozenSelection != null) {
                                        val opened = chatViewModel.openFreshNewChat(projectIdAfterCommit = action.projectId)
                                        if (!opened.success) -1L
                                        else {
                                            releaseRemovedDraftAttachmentPaths(opened.pendingAttachmentPaths, "FusionWorkPlan")
                                            if (!chatViewModel.installSelectionContext(frozenSelection, opened.epoch)) -1L
                                            else if (!chatViewModel.appendPromptDurably(0L, action.seedText, ::appendQuickPrompt)) -1L
                                            else opened.epoch
                                        }
                                    } else {
                                        val seeded = chatViewModel.openFreshProjectChatSeeded(action.projectId, action.seedText)
                                        if (!seeded.success) -1L
                                        else {
                                            releaseRemovedDraftAttachmentPaths(seeded.pendingAttachmentPaths, "FusionWorkPlan")
                                            seeded.epoch
                                        }
                                    }
                                },
                            )
                            if (seededEpoch == null || seededEpoch < 0L) {
                                false
                            } else {
                                pendingProjectWorkPlanSubmission = PendingProjectWorkPlanSubmission(
                                    attempt = action.attempt,
                                    newChatEpoch = seededEpoch,
                                    frozenProjectContext = action.frozenProjectContext,
                                )
                                mainSurface = mainSurface.openChat()
                                scope.launch { drawerState.close() }
                                Toast.makeText(context, workPlanHandoffMessage(action), Toast.LENGTH_LONG).show()
                                true
                            }
                        }
                    },
                    isDrawerOpen = drawerState.currentValue == DrawerValue.Open,
                    onOpenModelLibrary = {
                        modelLibraryRequestGate = issueMonotonicRequest(modelLibraryRequestGate)
                        scope.launch { drawerState.close() }
                    },
                    onOpenAdvancedSettings = {
                        advancedSettingsRequestGate = issueMonotonicRequest(advancedSettingsRequestGate)
                        scope.launch { drawerState.close() }
                    },
                    onOpenTranslation = ::openTranslation,
                    onRequestCameraCapture = {
                        cameraCaptureRequestGate = issueMonotonicRequest(cameraCaptureRequestGate)
                        scope.launch { drawerState.close() }
                    },
                    openAiProviderSettingsRequest = pendingAiProviderRequestToken?.toInt() ?: 0,
                    onOpenAiProviderSettingsRequestConsumed = { pendingAiProviderRequestToken = null },
                    openBenchmarkRequest = pendingBenchmarkRequestToken?.toInt() ?: 0,
                    onBenchmarkRequestConsumed = {
                        // Payload belongs to the pending request and is cleared
                        // only after ConversationListScreenV2 has copied it into
                        // child-owned state (copy happens before this callback).
                        pendingBenchmarkRequestToken = null
                        benchmarkRequestModelFilter = null
                        benchmarkRequestOpenHistory = false
                    },
                    benchmarkRequestModelFilter = benchmarkRequestModelFilter,
                    benchmarkRequestOpenHistory = benchmarkRequestOpenHistory,
                    localModelStorageRevision = localModelStorageRevision,
                    onReopenOnboarding = { showOnboarding = true },
                    pendingReminderNavigation = pendingReminderNavigation,
                    onReminderConsumed = onReminderConsumed,
                )
            }
        }
    ) {
if (isRestoredConversationSurfaceReady(currentConversationId, pendingRestoredConversationId)) {
ChatScreen(
  conversationId = currentConversationId,
  pendingCheckpointTarget = pendingCheckpointTarget,
  pendingResearchAnswerTarget = pendingResearchAnswerTarget,
  onResearchAnswerTargetConsumed = { expected ->
    if (pendingResearchAnswerTarget == expected) pendingResearchAnswerTarget = null
  },
  onCheckpointMessageConsumed = { expected ->
    if (pendingCheckpointTarget == expected) pendingCheckpointTarget = null
  },
  pendingResearchRefreshTarget = pendingResearchRefreshTarget,
  onResearchRefreshTargetConsumed = { expected ->
    if (pendingResearchRefreshTarget == expected) pendingResearchRefreshTarget = null
  },
  pendingProjectId = pendingProjectNewChatId,
  pendingProjectWorkPlanAttempt = pendingProjectWorkPlanSubmission?.attempt,
  pendingProjectWorkPlanEpoch = pendingProjectWorkPlanSubmission?.newChatEpoch,
  pendingProjectWorkPlanConversationId = pendingProjectWorkPlanSubmission?.conversationId,
  pendingProjectWorkPlanUserMessageId = pendingProjectWorkPlanSubmission?.userMessageId,
  pendingProjectWorkPlanSubmissionToken = pendingProjectWorkPlanSubmission?.submissionToken,
  pendingProjectWorkPlanRequestId = pendingProjectWorkPlanSubmission?.requestId,
  pendingProjectWorkPlanProjectContext = pendingProjectWorkPlanSubmission?.frozenProjectContext,
  onProjectWorkPlanRequestCommitted = { newChatEpoch, projectId, conversationId, messageId, submissionToken ->
    val pending = pendingProjectWorkPlanSubmission
    // Finding B: first commit wins. A later normal send in the same chat
    // must never rebind the attempt to a different user message.
    if (pending != null && pending.newChatEpoch == newChatEpoch && pending.attempt.projectId == projectId &&
      pending.conversationId == null && pending.userMessageId == null
    ) {
      pendingProjectWorkPlanSubmission = pending.copy(
        conversationId = conversationId,
        userMessageId = messageId,
        submissionToken = submissionToken.takeIf { it.isNotBlank() },
      )
    }
  },
  onProjectWorkPlanGenerationStarted = { newChatEpoch, projectId, conversationId, messageId, requestId ->
    val pending = pendingProjectWorkPlanSubmission
    // Finding B: first generation for the bound submission wins.
    if (pending != null && pending.newChatEpoch == newChatEpoch && pending.attempt.projectId == projectId &&
      pending.conversationId == conversationId && pending.userMessageId == messageId &&
      pending.requestId == null && requestId.isNotBlank()
    ) {
      pendingProjectWorkPlanSubmission = pending.copy(requestId = requestId)
    }
  },
  onProjectWorkPlanAssistantResultCommitted = { newChatEpoch, projectId, conversationId, userMessageId, assistantMessageId, requestId, submissionToken ->
    val pending = pendingProjectWorkPlanSubmission
    // Finding B: only the bound submission + request may report, and the
    // repository still enforces durable origin proof. Terminal results
    // clear the pending binding; STALE keeps it for a later owned answer.
    if (pending == null || pending.newChatEpoch != newChatEpoch || pending.attempt.projectId != projectId ||
      pending.conversationId != conversationId || pending.userMessageId != userMessageId ||
      pending.submissionToken != submissionToken || pending.requestId != requestId
    ) {
      com.projectnuke.fusion.projects.ProjectWorkPlanAttemptResult.STALE
    } else {
      val result = try {
        val coordinator = ProjectWorkPlanHandoffCoordinator(
          ProjectWorkPlanRepositoryGateway(
            com.projectnuke.fusion.projects.ProjectWorkPlanRepository(AppDatabase.getInstance(context)),
          ),
        )
        coordinator.settleMessage(
          com.projectnuke.fusion.projects.ProjectWorkPlanResultReceipt(
            planId = pending.attempt.planId,
            stepId = pending.attempt.stepId,
            attemptId = pending.attempt.attemptId,
            projectId = pending.attempt.projectId,
            stepType = pending.attempt.type,
            resultKind = com.projectnuke.fusion.projects.ProjectWorkPlanResultReceipt.KIND_MESSAGE,
            resultId = assistantMessageId.toString(),
            conversationId = conversationId,
            userMessageId = userMessageId,
            submissionToken = submissionToken,
            requestId = requestId,
          ),
        )
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        com.projectnuke.fusion.projects.ProjectWorkPlanAttemptResult.INVALID
      }
      if (result == com.projectnuke.fusion.projects.ProjectWorkPlanAttemptResult.APPLIED ||
        result == com.projectnuke.fusion.projects.ProjectWorkPlanAttemptResult.INVALID ||
        result == com.projectnuke.fusion.projects.ProjectWorkPlanAttemptResult.ATTEMPT_LIMIT
      ) {
        pendingProjectWorkPlanSubmission = null
      }
      if (result != com.projectnuke.fusion.projects.ProjectWorkPlanAttemptResult.APPLIED &&
        result != com.projectnuke.fusion.projects.ProjectWorkPlanAttemptResult.STALE
      ) {
        Toast.makeText(context, "답변은 저장했지만 계획 단계 참조를 갱신하지 못했습니다. 계획에서 상태를 다시 확인해 주세요.", Toast.LENGTH_LONG).show()
      }
      result
    }
  },
  onProjectBindingConsumed = { projectId ->
    chatViewModel.consumePendingProjectNewChat(projectId)
  },
  onConversationCreated = { newId ->
    chatViewModel.selectionContextBinding.value?.snapshot?.id?.let { snapshotId ->
      chatViewModel.promoteSelectionContext(snapshotId, newId, chatViewModel.newChatEpoch.value)
    }
    chatViewModel.selectConversation(newId)
  },
  onOpenList = {
    scope.launch {
      drawerState.open()
    }
  },
  onNewChat = {
    mainSurface = mainSurface.openChat()
    openFreshNewChat()
  },
  newChatEpoch = newChatEpoch,
  onOpenApiProviderSettings = {
    nextAiProviderRequestToken += 1L
    pendingAiProviderRequestToken = nextAiProviderRequestToken
    scope.launch { drawerState.open() }
  },
  openModelLibraryRequest = childRequestToken(modelLibraryRequestGate.pendingToken),
  onOpenModelLibraryRequestConsumed = {
    modelLibraryRequestGate = acknowledgeMonotonicRequest(modelLibraryRequestGate)
  },
  openAdvancedSettingsRequest = childRequestToken(advancedSettingsRequestGate.pendingToken),
  onOpenAdvancedSettingsRequestConsumed = {
    advancedSettingsRequestGate = acknowledgeMonotonicRequest(advancedSettingsRequestGate)
  },
  openCameraCaptureRequest = childRequestToken(cameraCaptureRequestGate.pendingToken),
  onOpenCameraCaptureRequestConsumed = {
    cameraCaptureRequestGate = acknowledgeMonotonicRequest(cameraCaptureRequestGate)
  },
  onOpenBenchmark = { modelName, openHistory ->
    benchmarkRequestModelFilter = modelName
    benchmarkRequestOpenHistory = openHistory
    nextBenchmarkRequestToken += 1L
    pendingBenchmarkRequestToken = nextBenchmarkRequestToken
    scope.launch { drawerState.open() }
  },
  onLocalModelStorageChanged = { localModelStorageRevision += 1 },
  chatViewModel = chatViewModel,
  translationCoordinator = translationRuntimeOwner.coordinator,
  onOpenInTranslation = ::openTranslationWithSource,
  archiveAccessIncluded = run {
    val locked = context.getSharedPreferences("fusion_chat_settings", android.content.Context.MODE_PRIVATE)
      .getBoolean(ArchiveLockPreferenceKey, false)
    !locked || authorizedArchiveConversationId == currentConversationId
  },
)
} else {
  // Neutral startup shell: no conversation title, messages, project context,
  // or generation surface is exposed before restored-owner admission settles.
  Box(modifier = Modifier.fillMaxSize())
}
    }
      } else {
        StandaloneTranslationScreen(
          controller = standaloneTranslationController,
          clipboard = LocalClipboardManager.current,
          onBack = ::returnToChat,
          onInsertIntoComposer = ::insertTranslationIntoComposer,
          composerHandoffInProgress = composerHandoffInProgress,
          composerHandoffMessage = composerHandoffMessage,
        )
      }

    // First-run and beginner provider surfaces stack above the app root.
    if (showOnboarding) {
      FusionOnboardingScreen(
        store = onboardingStore,
        onDismiss = { showOnboarding = false },
        onAction = { action ->
          when (action) {
            FusionOnboardingAction.COMPLETE_LOCAL_ROUTE -> {
              showOnboarding = false
              // Existing Model Library path uses the same one-shot gate.
              modelLibraryRequestGate = issueMonotonicRequest(modelLibraryRequestGate)
            }
            FusionOnboardingAction.COMPLETE_EXTERNAL_ROUTE -> {
              showOnboarding = false
              showBeginnerProvider = true
            }
            FusionOnboardingAction.COMPLETE_BROWSE -> showOnboarding = false
            // The screen normally handles dismissal via onDismiss without
            // emitting this action; kept only so the callback contract stays
            // total and never writes completion for a non-durable dismissal.
            FusionOnboardingAction.DISMISS_FOR_NOW -> showOnboarding = false
          }
        },
      )
    }
    if (showBeginnerProvider) {
      AiProviderBeginnerSetupSurface(
        onDismiss = { showBeginnerProvider = false },
        onOpenAdvanced = { showAdvancedProviderSettings = true },
      )
    }
    if (showAdvancedProviderSettings) {
      AiProviderSettingsScreen(
        onDismiss = { showAdvancedProviderSettings = false },
      )
    }
    }
  }
}

/**
 * One-shot external-event consumption policy for a failed handoff/shortcut
 * processing round (the pure seam behind the top-level LaunchedEffect
 * catches, testable without Compose instrumentation):
 *
 *  - structural cancellation ([kotlinx.coroutines.CancellationException])
 *    never consumes — the event stays pending and the recreated composition
 *    retries it;
 *  - ordinary recoverable operational failures ([RuntimeException]) consume
 *    the event after the bounded user-visible toast/result, matching the
 *    existing terminal-failure semantics;
 *  - fatal runtime failures (LinkageError, VirtualMachineError, and every
 *    other [Error]) PROPAGATE — a broken runtime must never be converted
 *    into a normal completion that discards the one-shot external event.
 */
internal fun consumeExternalEventAfterFailure(failure: Throwable): Boolean =
    failure is RuntimeException &&
        failure !is kotlinx.coroutines.CancellationException

/**
 * Fatal boundary for the Project Synthesis fresh-chat seed (the pure seam
 * behind the drawer callback, testable without Compose instrumentation):
 *
 *  - structural cancellation ([kotlinx.coroutines.CancellationException])
 *    propagates — the seed attempt is simply abandoned with the composition
 *    state untouched;
 *  - ordinary recoverable operational failures ([RuntimeException]) are
 *    reported through [onFailure] and become the bounded `null` result so the
 *    existing surface stays authoritative and the project page can surface
 *    the failure copy;
 *  - fatal runtime failures (LinkageError, VirtualMachineError, and every
 *    other [Error]) PROPAGATE — a broken runtime must never be converted into
 *    the ordinary `seeded == null` false result.
 */
internal suspend fun <T : Any> projectSynthesisSeedBounded(
    onFailure: (Throwable) -> Unit,
    block: suspend () -> T,
): T? = try {
    block()
} catch (cancellation: kotlinx.coroutines.CancellationException) {
    throw cancellation
} catch (failure: RuntimeException) {
    onFailure(failure)
    null
}
