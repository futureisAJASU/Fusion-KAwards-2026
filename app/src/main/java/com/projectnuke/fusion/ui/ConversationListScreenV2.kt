package com.projectnuke.fusion.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.projectnuke.fusion.chat.isDeletedOrAbsent
import com.projectnuke.fusion.tasks.AndroidProjectTaskReminderScheduler
import com.projectnuke.fusion.tasks.ReminderDeletionCoordinator
import com.projectnuke.fusion.tasks.ReminderDeletionResult
import com.projectnuke.fusion.study.QuickReviewCardDraft
import com.projectnuke.fusion.study.ReviewCardOrigin
import com.projectnuke.fusion.study.ReviewCardScope
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.withTransaction
import com.projectnuke.fusion.ai.ui.AiProviderSettingsScreen
import com.projectnuke.fusion.chat.ProjectBriefInput
import com.projectnuke.fusion.chat.ProjectBriefLoadState
import com.projectnuke.fusion.chat.ProjectBriefConversationInput
import com.projectnuke.fusion.chat.ProjectBriefFileInput
import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteRepository
import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteEditorDraft
import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteReferenceDraft
import com.projectnuke.fusion.knowledge.SavedKnowledgeNoteSaveResult
import com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_REFERENCES
import com.projectnuke.fusion.chat.buildProjectBriefSnapshot
import com.projectnuke.fusion.chat.selectProjectBriefSummaryCandidates
import com.projectnuke.fusion.chat.PromptActionFlight
import com.projectnuke.fusion.chat.ProjectSynthesisMode
import com.projectnuke.fusion.chat.PROJECT_SYNTHESIS_MODE_OPTIONS
import com.projectnuke.fusion.chat.ProjectSynthesisTapOutcome
import com.projectnuke.fusion.chat.isProjectSynthesisAvailable
import com.projectnuke.fusion.chat.runProjectSynthesisTap
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.knowledge.OrganizationObjectKind
import com.projectnuke.fusion.inbox.FusionInboxScreen
import com.projectnuke.fusion.workspace.ContinueWorkCandidate
import com.projectnuke.fusion.workspace.ContinueWorkKind
import com.projectnuke.fusion.workspace.ContinueWorkingRepository
import com.projectnuke.fusion.data.BenchmarkDao
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.projects.ProjectSnapshotExportResult
import com.projectnuke.fusion.projects.ProjectSnapshotImportResult
import com.projectnuke.fusion.projects.ProjectSnapshotRepository
import com.projectnuke.fusion.projects.ProjectSnapshotBounds
import com.projectnuke.fusion.projects.withOwnedProjectSnapshotInput
import com.projectnuke.fusion.chat.ProjectInstructionPromptPolicy
import com.projectnuke.fusion.model.resolveEffectiveLocalModelProfile
import com.projectnuke.fusion.model.LocalModelSettingsProfileStore
import com.projectnuke.fusion.util.AttachmentImportCoordinator
import com.projectnuke.fusion.util.AttachmentImportFailure
import com.projectnuke.fusion.util.AttachmentStorageManager
import com.projectnuke.fusion.util.AttachmentStorageStats
import com.projectnuke.fusion.util.collectFusionSocInfo
import com.projectnuke.fusion.util.normalizeUserVisibleName
import com.projectnuke.fusion.ui.resolveLocalModelChipLabel
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors
import com.projectnuke.fusion.ui.theme.readFusionThemeMode
import com.projectnuke.fusion.ui.theme.saveFusionThemeMode
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import androidx.compose.foundation.layout.widthIn

private sealed interface SavedKnowledgeDetailAccessState {
    data class Checking(val identity: String) : SavedKnowledgeDetailAccessState
    data class Allowed(val identity: String) : SavedKnowledgeDetailAccessState
    data class Denied(val identity: String) : SavedKnowledgeDetailAccessState
}

private fun identityForDetail(itemId: String, archiveAllowed: Boolean): String = "${itemId}:${archiveAllowed}"

private const val FUSION_GITHUB_REPO_URL =
    "https://github.com/futureisAJASU/ProjectNuke-Fusion"

private fun openFusionGithubUrl(context: Context) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(FUSION_GITHUB_REPO_URL)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "GitHub 페이지를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
    }
}
private const val FUSION_GITHUB_ISSUES_URL = "https://github.com/futureisAJASU/ProjectNuke-Fusion/issues"

private data class AppInfoSummary(
    val versionName: String,
    val versionCode: String
)

internal fun markdownExportCanStart(isBusy: Boolean): Boolean = !isBusy

private val DrawerBlackBg: Color
    @Composable get() = LocalFusionSemanticColors.current.background
private val DrawerPanelBg: Color
    @Composable get() = LocalFusionSemanticColors.current.panel
private val DrawerCardBg: Color
    @Composable get() = LocalFusionSemanticColors.current.panel
private val DrawerCardSelectedBg: Color
    @Composable get() = LocalFusionSemanticColors.current.menu
private val DrawerTextPrimary: Color
    @Composable get() = LocalFusionSemanticColors.current.onSurface
private val DrawerTextSecondary: Color
    @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val DrawerAccentBlue: Color
    @Composable get() = LocalFusionSemanticColors.current.primary

private enum class SidebarPage {
    HOME,
    LAB,
    SETTINGS,
    ARCHIVE,
    PROMPT_LAB,
    BENCHMARK,
    PROJECTS,
    INBOX,
    ACTION_CENTER,
    RESEARCH_REFRESH,
}

internal enum class PromptLabOrigin { HOME, SETTINGS, LAB }

/**
 * Origin of the current Benchmark screen instance. LAB distinguishes "entered
 * from the 실험실(Lab) page" (Back must return to LAB) from every other
 * existing entry (Settings row, external/deep benchmark request) whose Back
 * keeps its previous HOME-return behavior.
 */
internal enum class BenchmarkOrigin { LAB, OTHER }

internal fun attachmentCleanupCanStart(isLoading: Boolean): Boolean = !isLoading

internal enum class DrawerBackPage {
    HOME,
    LAB,
    SETTINGS,
    ARCHIVE,
    INBOX,
    PROMPT_LAB,
    OTHER,
    PROJECTS,
}

internal enum class DrawerBackAction {
    CLEAR_SEARCH,
    SETTINGS_HOME,
    DRAWER_HOME,
    DRAWER_LAB,
    SETTINGS_PARENT,
    NONE,
}

/**
 * Pure policy: where Back from the Benchmark screen must land, keyed by how
 * the screen was entered. Lab-origin Benchmark returns to LAB; every other
 * existing entry (Settings row, direct/external benchmark request) keeps the
 * previous HOME-return behavior.
 */
internal fun resolveBenchmarkBack(origin: BenchmarkOrigin): DrawerBackAction =
    if (origin == BenchmarkOrigin.LAB) DrawerBackAction.DRAWER_LAB else DrawerBackAction.DRAWER_HOME

internal fun resolveDrawerBackAction(
    page: DrawerBackPage,
    searchActive: Boolean,
    settingsSubsectionActive: Boolean,
    promptLabOrigin: PromptLabOrigin = PromptLabOrigin.SETTINGS,
): DrawerBackAction = when {
    searchActive -> DrawerBackAction.CLEAR_SEARCH
    page == DrawerBackPage.SETTINGS && settingsSubsectionActive -> DrawerBackAction.SETTINGS_HOME
    page == DrawerBackPage.SETTINGS -> DrawerBackAction.DRAWER_HOME
    page == DrawerBackPage.LAB -> DrawerBackAction.DRAWER_HOME
    page == DrawerBackPage.ARCHIVE -> DrawerBackAction.DRAWER_HOME
    page == DrawerBackPage.INBOX -> DrawerBackAction.DRAWER_HOME
    page == DrawerBackPage.HOME -> DrawerBackAction.DRAWER_HOME
    page == DrawerBackPage.PROJECTS -> DrawerBackAction.DRAWER_HOME
    page == DrawerBackPage.PROMPT_LAB && promptLabOrigin == PromptLabOrigin.HOME -> DrawerBackAction.DRAWER_HOME
    page == DrawerBackPage.PROMPT_LAB && promptLabOrigin == PromptLabOrigin.LAB -> DrawerBackAction.DRAWER_LAB
    page == DrawerBackPage.PROMPT_LAB -> DrawerBackAction.SETTINGS_PARENT
    else -> DrawerBackAction.NONE
}

private enum class SettingsSection(val title: String, val subtitle: String) {
    GENERAL("일반 및 응답", "생성 동작과 응답 기본값"),
    APPEARANCE("화면 모양", "Black, Dark, Light 테마"),
    MODELS_AI("모델 및 AI", "로컬 모델과 외부 AI 제공자"),
    PRIVACY_DATA("개인정보 및 데이터", "보관, 파일, 백업과 데이터 관리"),
    ADVANCED_ABOUT("고급 및 정보", "실험, 진단, 도움말과 앱 정보"),
}

@Composable
fun ConversationListScreenV2(
  chatViewModel: com.projectnuke.fusion.chat.ChatViewModel,
  currentConversationId: Long,
  onBack: () -> Unit,
  onOpenConversation: (Long, Boolean) -> Unit,
  onOpenResearchAnswer: (conversationId: Long, messageId: Long, authorizedSession: Boolean) -> Unit = { _, _, _ -> },
  onOpenCheckpoint: (checkpointId: Long, conversationId: Long, messageId: Long, authorizedSession: Boolean) -> Unit = { _, _, _, _ -> },
  onContinueFromCheckpoint: suspend (checkpointId: Long, conversationId: Long, messageId: Long, authorizedSession: Boolean) -> Boolean = { _, _, _, _ -> false },
  onStartResearchRefresh: (sourceConversationId: Long, sourceMessageId: Long, authorizedSession: Boolean) -> Unit,
  onConversationRemovedFromList: (removedConversationId: Long, nextConversationId: Long?) -> Unit,
  onNewChat: () -> Unit,
  onNewProjectChat: (Long) -> Unit = {},
  onQuestionWithSelection: (com.projectnuke.fusion.chat.SelectionContextSnapshot) -> Unit = {},
  onCompareWithSelection: (com.projectnuke.fusion.chat.SelectionContextSnapshot, String) -> Unit = { _, _ -> },
  onResearchWithProjects: (com.projectnuke.fusion.chat.SelectionContextSnapshot) -> Unit = {},
  onSynthesizeBranches: (com.projectnuke.fusion.chat.SelectionContextSnapshot, String) -> Unit = { _, _ -> },
  onNewProjectSynthesisChat: suspend (projectId: Long, prompt: String) -> Boolean = { _, _ -> false },
  onDispatchProjectWorkPlanStep: suspend (com.projectnuke.fusion.projects.ProjectWorkPlanPreparedAction) -> Boolean = { false },
  onOpenTranslation: () -> Unit,
  onRequestCameraCapture: () -> Unit = {},
  isDrawerOpen: Boolean = true,
  onOpenModelLibrary: (() -> Unit)? = null,
  onOpenAdvancedSettings: (() -> Unit)? = null,
  openAiProviderSettingsRequest: Int = 0,
  onOpenAiProviderSettingsRequestConsumed: (() -> Unit)? = null,
  openBenchmarkRequest: Int = 0,
  onBenchmarkRequestConsumed: (() -> Unit)? = null,
  benchmarkRequestModelFilter: String? = null,
  benchmarkRequestOpenHistory: Boolean = false,
  localModelStorageRevision: Int = 0,
  onReopenOnboarding: (() -> Unit)? = null,
  pendingReminderNavigation: com.projectnuke.fusion.ReminderNavigation? = null,
  onReminderConsumed: (() -> Unit)? = null,
) {
  val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val db = remember { AppDatabase.getInstance(context) }
    val savedKnowledgeNoteRepository = remember(db) { SavedKnowledgeNoteRepository(db) }
    val dao = remember { db.chatDao() }
    val scope = rememberCoroutineScope()
    val studyProgressOwner = remember(context, dao) { StudyProgressOwner(context, dao) }
    val reviewCardCaptureController = remember(studyProgressOwner, scope) {
        studyProgressOwner.newReviewCardCaptureController(scope)
    }
    val reviewCardCaptureState by reviewCardCaptureController.state.collectAsStateWithLifecycle()
    DisposableEffect(reviewCardCaptureController) { onDispose { reviewCardCaptureController.dismiss() } }
    val prefs = remember { context.getSharedPreferences("fusion_chat_settings", android.content.Context.MODE_PRIVATE) }
    val inboxCount by db.inboxItemDao().observeCount().collectAsStateWithLifecycle(initialValue = 0)

    var page by rememberSaveable { mutableStateOf(SidebarPage.HOME) }
    var requestedResearchRefreshTarget by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var settingsSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var promptLabOrigin by rememberSaveable { mutableStateOf(PromptLabOrigin.HOME) }
    var benchmarkOrigin by remember { mutableStateOf(BenchmarkOrigin.OTHER) }
    var searchQuery by remember { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    var menuConversation by remember { mutableStateOf<ConversationEntity?>(null) }
    var checkpointConversation by remember { mutableStateOf<ConversationEntity?>(null) }
    var checkpointItems by remember { mutableStateOf<List<com.projectnuke.fusion.data.ConversationCheckpointEntity>>(emptyList()) }
    var renameConversation by remember { mutableStateOf<ConversationEntity?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var titleRegenerationGeneration by remember { mutableStateOf(0L) }
    var deleteConversation by remember { mutableStateOf<ConversationEntity?>(null) }
  var deletingConversationId by remember { mutableStateOf<Long?>(null) }
    var showAttachmentStorageDialog by remember { mutableStateOf(false) }
    var showAppInfoDialog by remember { mutableStateOf(false) }
    var showReleaseNotesDialog by remember { mutableStateOf(false) }
    var showDeveloperLogDialog by remember { mutableStateOf(false) }
    var showStatusDashboardDialog by remember { mutableStateOf(false) }
    var showFusionHealthDialog by remember { mutableStateOf(false) }
    var showDeviceInfoDialog by remember { mutableStateOf(false) }
    var showReleaseChecklistDialog by remember { mutableStateOf(false) }
    var showPromptPresetsDialog by remember { mutableStateOf(false) }
    var showExperimentNotesDialog by remember { mutableStateOf(false) }
    var showDeveloperModeDialog by remember { mutableStateOf(false) }
    var showTroubleshootingGuideDialog by remember { mutableStateOf(false) }
    var showPrivacyDataGuideDialog by remember { mutableStateOf(false) }
    var showMemoryManagerDialog by rememberSaveable { mutableStateOf(false) }
    var showFusionSearch by rememberSaveable { mutableStateOf(false) }
    var savedKnowledgeDetail by remember { mutableStateOf<SavedKnowledgeEntity?>(null) }
    var showSavedKnowledgeHygiene by remember { mutableStateOf(false) }
    var structuredExtractionSource by remember { mutableStateOf<StructuredExtractionSource?>(null) }
    var savedKnowledgeNoteDraft by remember { mutableStateOf<SavedKnowledgeNoteEditorDraft?>(null) }
    var savedKnowledgeNoteSaving by remember { mutableStateOf(false) }
    var savedKnowledgeNoteError by remember { mutableStateOf<String?>(null) }
    var organizeSavedKnowledgeId by remember { mutableStateOf<String?>(null) }
    var confirmSavedKnowledgeDelete by remember { mutableStateOf(false) }
    var showBranchMap by remember { mutableStateOf(false) }
    var showModelAbTestLab by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var attachmentStorageStats by remember { mutableStateOf<AttachmentStorageStats?>(null) }
    var attachmentStorageLoading by remember { mutableStateOf(false) }
    var archiveLockEnabled by remember { mutableStateOf(prefs.getBoolean(ArchiveLockPreferenceKey, false)) }
    var archiveUnlockedForSession by remember { mutableStateOf(false) }
    val archiveAccessState = rememberUpdatedState(archiveLockEnabled to archiveUnlockedForSession)
    var archiveAuthInFlight by remember { mutableStateOf(false) }
    var pendingBenchmarkModelFilter by remember { mutableStateOf<String?>(null) }
    var pendingBenchmarkOpenHistory by remember { mutableStateOf(false) }
    var lastConsumedBenchmarkRequest by remember { mutableStateOf(0) }
    var lastConsumedOpenAiProviderSettingsRequest by remember { mutableStateOf(0) }
    var showChatMarkdownExportPicker by remember { mutableStateOf(false) }
    var showMarkdownCopyFailureDialog by rememberSaveable { mutableStateOf(false) }
    var markdownCopyInProgress by remember { mutableStateOf(false) }
    var includeArchivedInMarkdownExport by remember { mutableStateOf(false) }
    var markdownExportSearchQuery by remember { mutableStateOf("") }
    var pendingChatMarkdownExport by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingMarkdownStore = remember(context.applicationContext) {
        PendingMarkdownExportStore(File(context.applicationContext.cacheDir, "pending-markdown-exports"))
    }
    var markdownExportGeneration by remember { mutableStateOf(0L) }
    var markdownExportBusy by remember { mutableStateOf(pendingChatMarkdownExport != null) }
    var showSettingsBackupDialog by remember { mutableStateOf(false) }
    var pendingSettingsRestoreJson by remember { mutableStateOf<String?>(null) }
    var localModelSettingsRefreshKey by remember { mutableStateOf(0) }
    var showModelCompatibilityGuide by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAiProviderSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showAppearanceDialog by remember { mutableStateOf(false) }
    var appLanguage by remember { mutableStateOf(getEffectiveFusionAppLanguage(context)) }
    var developerModeEnabled by remember { mutableStateOf(isFusionDeveloperModeEnabled(context)) }
    var openReviewCardId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isDrawerOpen) {
        if (!isDrawerOpen) {
            archiveUnlockedForSession = false
            if (page == SidebarPage.ARCHIVE) {
                page = SidebarPage.HOME
                searchQuery = ""
            }
        }
    }
    // State transitions are fail-closed; the render branch below is also
    // gated so an archive frame can never expose content before this runs.
    LaunchedEffect(archiveLockEnabled, archiveUnlockedForSession, page) {
        if (page == SidebarPage.ARCHIVE && !ArchiveAccessPolicy.canRenderArchive(
                archiveLockEnabled,
                archiveUnlockedForSession,
            )
        ) {
            page = SidebarPage.HOME
            searchQuery = ""
        }
        if (!ArchiveAccessPolicy.canIncludeArchivedInExport(archiveLockEnabled, archiveUnlockedForSession)) {
            includeArchivedInMarkdownExport = false
        }
    }
    LaunchedEffect(openAiProviderSettingsRequest) {
        if (shouldConsumeAiProviderRequest(openAiProviderSettingsRequest, lastConsumedOpenAiProviderSettingsRequest)) {
            lastConsumedOpenAiProviderSettingsRequest = openAiProviderSettingsRequest
            page = SidebarPage.SETTINGS
            settingsSection = SettingsSection.MODELS_AI
            searchQuery = ""
            showAiProviderSettingsDialog = true
            onOpenAiProviderSettingsRequestConsumed?.invoke()
        }
    }
    LaunchedEffect(searchQuery) {
        val bounded = boundedConversationSearchQuery(searchQuery).orEmpty()
        if (bounded.isBlank()) {
            debouncedSearchQuery = ""
        } else {
            delay(ConversationSearchDebounceMs)
            debouncedSearchQuery = bounded
        }
    }
    LaunchedEffect(openBenchmarkRequest) {
        if (shouldConsumeBenchmarkRequest(openBenchmarkRequest, lastConsumedBenchmarkRequest)) {
            lastConsumedBenchmarkRequest = openBenchmarkRequest
            page = SidebarPage.BENCHMARK
            settingsSection = null
            searchQuery = ""
            archiveUnlockedForSession = false
            // Direct/external benchmark request: not Lab-origin, preserve the
            // existing HOME-return behavior.
            benchmarkOrigin = BenchmarkOrigin.OTHER
            pendingBenchmarkModelFilter = benchmarkRequestModelFilter
            pendingBenchmarkOpenHistory = benchmarkRequestOpenHistory
            onBenchmarkRequestConsumed?.invoke()
        }
    }

    var webSearchEnabled by remember { mutableStateOf(prefs.getBoolean("web_search_enabled", false)) }
    // Single key authority lives in DynamicFollowUpPreference: no scattered
    // string literals for this product setting.
    var followUpAutoGenerate by remember {
        mutableStateOf(
            com.projectnuke.fusion.chat.DynamicFollowUpPreference.isAutoGenerateEnabled(prefs),
        )
    }

    val conversations by remember(isDrawerOpen) {
        if (isDrawerOpen) dao.observeConversations() else flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val archivedConversations by remember(isDrawerOpen) {
        if (isDrawerOpen) dao.observeArchivedConversations() else flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val projectDao = remember { db.projectDao() }
    val projects by remember(isDrawerOpen) {
        if (isDrawerOpen) projectDao.observeProjects() else flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val continueWork by produceState<List<ContinueWorkCandidate>>(emptyList(), db, inboxCount, projects) {
        value = try { ContinueWorkingRepository(db).load() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }
    var selectedProjectId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedProject = projects.firstOrNull { it.id == selectedProjectId }
    var showProjectSnapshotOptions by remember { mutableStateOf(false) }
    var showCreateWorkPlanDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var openProjectWorkPlanId by remember { mutableStateOf<String?>(null) }
    var openProjectWorkPlanStepId by remember { mutableStateOf<String?>(null) }
    var requestedProjectDecisionId by remember { mutableStateOf<String?>(null) }
    var requestedProjectTaskTarget by remember { mutableStateOf<com.projectnuke.fusion.workspace.WorkspaceTaskTarget?>(null) }
    var projectReportMarkdown by remember { mutableStateOf<String?>(null) }
    var projectReportBusy by remember { mutableStateOf(false) }
    var showProjectWorkPlanHistory by remember { mutableStateOf(false) }
    var projectSnapshotBusy by remember { mutableStateOf(false) }
    var showProjectConsistencyReview by remember { mutableStateOf(false) }
    var showProjectKnowledgeDigest by remember { mutableStateOf(false) }
    var showProjectPulse by remember { mutableStateOf(false) }
    var projectDigestRangeDays by remember { mutableStateOf(7) }
    var requestedProjectFileInspectionId by remember { mutableStateOf<Long?>(null) }
    var pendingProjectSnapshotExportPath by rememberSaveable { mutableStateOf<String?>(null) }
    val projectSnapshotRepository = remember(context.applicationContext, db) {
        ProjectSnapshotRepository(context.applicationContext, db)
    }
    val projectConsistencyReviewRepository = remember(context.applicationContext, db) {
        ProjectConsistencyReviewRepository(context.applicationContext, db)
    }
    val projectKnowledgeDigestRepository = remember(context.applicationContext, db) {
        ProjectKnowledgeDigestRepository(context.applicationContext, db)
    }
    var projectConversations by remember { mutableStateOf<List<ConversationEntity>>(emptyList()) }
    // Project membership survives archiving; only authorized rows may feed
    // enumeration, titles, Brief summaries and synthesis. Also mask newly
    // archived rows while the project query is being refreshed.
    val archivedIds = archivedConversations.map { it.id }.toSet()
    val accessibleProjectConversations = ArchiveAccessPolicy.accessibleConversations(
        projectConversations.map { if (it.id in archivedIds) it.copy(isArchived = true) else it },
        archiveLockEnabled,
        archiveUnlockedForSession,
    )
    var projectCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var projectFiles by remember { mutableStateOf<List<ProjectFileEntity>>(emptyList()) }
    var projectResourceRevision by remember { mutableStateOf(0) }
    var loadedProjectId by remember { mutableStateOf<Long?>(null) }
    var loadedProjectResourceRevision by remember { mutableStateOf<Int?>(null) }
    var projectInstructionsDraft by remember { mutableStateOf("") }
    var showProjectInstructionsDialog by remember { mutableStateOf(false) }
    var projectFileImportProjectId by remember { mutableStateOf<Long?>(null) }
    var showDrawerNewProject by remember { mutableStateOf(false) }
    var drawerNewProjectName by remember { mutableStateOf("") }
    var confirmDeleteProject by remember { mutableStateOf<ProjectEntity?>(null) }
    // Project Synthesis (F5) single flight: the first tap owns the operation
    // and the action renders disabled while active. No queue, no GlobalScope.
    val projectSynthesisFlight = remember { PromptActionFlight() }
    val projectSynthesisInFlight by projectSynthesisFlight.inFlight.collectAsStateWithLifecycle()
    val projectWorkPlanFlight = remember { PromptActionFlight() }
    val projectWorkPlanInFlight by projectWorkPlanFlight.inFlight.collectAsStateWithLifecycle()
    // Work Plan -> specialized feature handoff router. Compose retains only
    // transient UI/navigation state (pending action, review selection,
    // surfaces). All orchestration — receipt settlement, cancellation,
    // typed transitions, source/result authority, provenance validation —
    // lives in ProjectWorkPlanHandoffCoordinator:
    // UI event -> coordinator -> specialized owner -> validated receipt.
    var pendingWorkPlanAction by remember { mutableStateOf<com.projectnuke.fusion.projects.ProjectWorkPlanPreparedAction?>(null) }
    var pendingWorkPlanReviewSelection by remember { mutableStateOf<com.projectnuke.fusion.chat.SelectionContextSnapshot?>(null) }
    val workPlanRepository = remember(db) {
        com.projectnuke.fusion.projects.ProjectWorkPlanRepository(
            db,
            attachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context.applicationContext),
        )
    }
    val workPlanCoordinator = remember(db) {
        ProjectWorkPlanHandoffCoordinator(ProjectWorkPlanRepositoryGateway(workPlanRepository))
    }
    val workPlanContentReader = remember(db) { WorkPlanCanonicalContentReader(context.applicationContext, db) }
    suspend fun workPlanSourceAccessible(identity: com.projectnuke.fusion.chat.SelectionSourceIdentity): Boolean {
        // Issue 5: single shared authority with the session archive flag.
        val authority = workPlanAccessAuthority(
            context = context.applicationContext,
            database = db,
            archiveAccessIncluded = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession),
        )
        return try {
            authority.isAccessible(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
    fun clearPendingWorkPlan() {
        pendingWorkPlanReviewSelection = null
        pendingWorkPlanAction = null
    }
    fun cancelPendingWorkPlanAttempt(userMessage: String? = null) {
        val pending = pendingWorkPlanAction ?: return
        clearPendingWorkPlan()
        scope.launch {
            try {
                workPlanCoordinator.cancel(pending.attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The attempt is recovered on the next plan load if this fails.
            }
        }
        userMessage?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    fun reopenWorkPlanAfterSettle(
        action: com.projectnuke.fusion.projects.ProjectWorkPlanPreparedAction,
        applied: Boolean,
        successMessage: String,
    ) {
        clearPendingWorkPlan()
        // Reopen only after the settlement commits so the dialog always
        // loads the durable state the receipt produced.
        openProjectWorkPlanId = action.planId
        Toast.makeText(
            context,
            if (applied) successMessage
            else "단계 결과를 기록하지 못했습니다. 계획에서 상태를 다시 확인해 주세요.",
            Toast.LENGTH_LONG,
        ).show()
    }
    suspend fun dispatchWorkPlanStep(
        action: com.projectnuke.fusion.projects.ProjectWorkPlanPreparedAction,
    ): Boolean {
        val destination = action.destination
        val toChat = action.frozenSelection != null || destination ==
            com.projectnuke.fusion.projects.ProjectWorkPlanDestination.RESEARCH_CHAT ||
            destination == com.projectnuke.fusion.projects.ProjectWorkPlanDestination.PROJECT_CONTEXT_CHAT ||
            destination == com.projectnuke.fusion.projects.ProjectWorkPlanDestination.DRAFT_COMPOSER
        if (toChat) return onDispatchProjectWorkPlanStep(action)
        // Selection destinations: the plan dialog must not stack on top of
        // the selection surface, so it is closed synchronously here while
        // this coroutine keeps running without another suspension point.
        pendingWorkPlanReviewSelection = null
        pendingWorkPlanAction = action
        openProjectWorkPlanId = null
        showFusionSearch = true
        return true
    }
    fun handleWorkPlanSelection(snapshot: com.projectnuke.fusion.chat.SelectionContextSnapshot) {
        val action = pendingWorkPlanAction ?: return
        scope.launch {
            val route = try {
                workPlanCoordinator.routeSelection(
                    action = action,
                    snapshot = snapshot,
                    isAccessible = ::workPlanSourceAccessible,
                    reader = workPlanContentReader,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProjectWorkPlanHandoffCoordinator.SelectionRoute.Rejected("선택한 자료를 확인하지 못했습니다. 다시 시도해 주세요.")
            }
            when (route) {
                is ProjectWorkPlanHandoffCoordinator.SelectionRoute.DispatchPrepared -> {
                    showFusionSearch = false
                    pendingWorkPlanAction = null
                    if (!onDispatchProjectWorkPlanStep(route.action)) {
                        workPlanCoordinator.cancel(route.action.attempt)
                        Toast.makeText(context, "비교 요청을 준비하지 못했습니다. 계획에서 다시 시작할 수 있습니다.", Toast.LENGTH_LONG).show()
                    }
                }
                is ProjectWorkPlanHandoffCoordinator.SelectionRoute.ExtractionReady -> {
                    showFusionSearch = false
                    structuredExtractionSource = route.source
                }
                ProjectWorkPlanHandoffCoordinator.SelectionRoute.ReviewPending -> {
                    showFusionSearch = false
                    pendingWorkPlanReviewSelection = snapshot
                }
                is ProjectWorkPlanHandoffCoordinator.SelectionRoute.Rejected -> {
                    Toast.makeText(context, route.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    LaunchedEffect(projects, isDrawerOpen, projectResourceRevision, conversations, archivedConversations) {
        if (!isDrawerOpen) return@LaunchedEffect
        projectCounts = runCatching {
            projects.associate { it.id to projectDao.getAssignmentCount(it.id) }
        }.getOrDefault(emptyMap())
    }
    LaunchedEffect(selectedProjectId, isDrawerOpen, page, projectResourceRevision, conversations, archivedConversations) {
        val target = selectedProjectId
        // Clear the previous project's resource lists before the new keyed
        // read completes; a brief for Project A must never render under B.
        loadedProjectId = null
        loadedProjectResourceRevision = null
        projectConversations = emptyList()
        projectFiles = emptyList()
        if (isDrawerOpen && page == SidebarPage.PROJECTS && target != null) {
            val conversations = runCatching {
                projectDao.getConversationsForProject(target)
            }.getOrDefault(emptyList())
            val files = runCatching {
                projectDao.getProjectFiles(target)
            }.getOrDefault(emptyList())
            projectConversations = conversations
            projectFiles = files
            loadedProjectId = target
            loadedProjectResourceRevision = projectResourceRevision
        }
    }
    val projectBriefLoadState by produceState<ProjectBriefLoadState>(
        initialValue = ProjectBriefLoadState.Loading,
        selectedProjectId,
        selectedProject,
        accessibleProjectConversations,
        projectFiles,
        projectResourceRevision,
        loadedProjectId,
        loadedProjectResourceRevision,
        page,
        isDrawerOpen,
    ) {
        value = ProjectBriefLoadState.Loading
        val currentProject = selectedProject
        if (!isDrawerOpen || page != SidebarPage.PROJECTS || currentProject == null ||
            currentProject.id != selectedProjectId
        ) {
            value = ProjectBriefLoadState.Unavailable
            return@produceState
        }
        if (loadedProjectId != currentProject.id ||
            loadedProjectResourceRevision != projectResourceRevision
        ) {
            value = ProjectBriefLoadState.Loading
            return@produceState
        }

        value = try {
            withContext(Dispatchers.IO) {
                val conversationInputs = accessibleProjectConversations.map { conversation ->
                    ProjectBriefConversationInput(
                        conversationId = conversation.id,
                        title = conversation.title,
                        conversationUpdatedAt = conversation.updatedAt,
                        summary = null,
                        summaryUpdatedAt = null,
                    )
                }
                val candidates = selectProjectBriefSummaryCandidates(conversationInputs)
                val candidateIds = candidates.map { it.conversationId }.toSet()
                val summaryLoad = loadConversationSummariesResult(context, candidateIds)
                if (summaryLoad.durabilityUnknownConversationIds.isNotEmpty()) {
                    ProjectBriefLoadState.Unavailable
                } else {
                    val summariesById = summaryLoad.summaries.associateBy { it.conversationId }
                    val input = ProjectBriefInput(
                        projectId = currentProject.id,
                        projectName = currentProject.name,
                        instructions = currentProject.instructions,
                        conversations = conversationInputs.map { conversation ->
                            val summary = summariesById[conversation.conversationId]
                            conversation.copy(
                                summary = if (conversation.conversationId in candidateIds) summary?.summary else null,
                                summaryUpdatedAt = if (conversation.conversationId in candidateIds) summary?.updatedAt else null,
                            )
                        },
                        files = projectFiles.map { file ->
                            ProjectBriefFileInput(
                                displayName = file.displayName,
                                mimeType = file.mimeType,
                            )
                        },
                        summaryCandidateConversationIds = candidateIds,
                        savedKnowledge = db.savedKnowledgeDao().getProjectItemsBounded(currentProject.id, 3).map {
                            com.projectnuke.fusion.chat.ProjectBriefKnowledgeInput(it.id, it.title, it.updatedAt, it.retrievedAt)
                        },
                        openTasks = db.projectTaskDao().getOpenForProjectBounded(currentProject.id, 3).map {
                            com.projectnuke.fusion.chat.ProjectBriefTaskInput(it.id, it.title, it.updatedAt)
                        },
                        decisions = db.projectDecisionDao().getRecent(currentProject.id, 12).map {
                            com.projectnuke.fusion.chat.ProjectBriefDecisionInput(it.id, it.title, it.status, it.createdAt, it.rationale)
                        },
                        changeCandidates = buildList {
                            accessibleProjectConversations.sortedByDescending { it.updatedAt }.take(64).forEach { conversation ->
                                add(com.projectnuke.fusion.chat.ProjectBriefChangeInput(
                                    key = "conversation:${conversation.id}",
                                    kind = if (conversation.refreshedFromConversationId != null) "Research 다시 확인" else "대화",
                                    title = conversation.title,
                                    changedAt = conversation.updatedAt,
                                    conversationId = conversation.id,
                                ))
                            }
                            projectFiles.sortedByDescending { it.addedAt }.take(32).forEach { file ->
                                add(com.projectnuke.fusion.chat.ProjectBriefChangeInput("file:${file.id}", "자료", file.displayName, file.addedAt))
                            }
                            db.savedKnowledgeDao().getProjectItemsBounded(currentProject.id, 64).forEach { item ->
                                add(com.projectnuke.fusion.chat.ProjectBriefChangeInput("knowledge:${item.id}", "저장한 지식", item.title, item.updatedAt))
                            }
                            db.projectTaskDao().getRecentForProjectBounded(currentProject.id, 64).forEach { task ->
                                add(com.projectnuke.fusion.chat.ProjectBriefChangeInput("task:${task.id}", if (task.state == "DONE") "완료한 작업" else "열린 작업", task.title, task.updatedAt))
                            }
                            db.projectDecisionDao().getRecent(currentProject.id, 32).forEach { decision ->
                                add(com.projectnuke.fusion.chat.ProjectBriefChangeInput("decision:${decision.id}", if (decision.status == "SUPERSEDED") "대체된 결정" else "결정", decision.title, decision.createdAt))
                            }
                        },
                    )
                    ProjectBriefLoadState.Ready(buildProjectBriefSnapshot(input))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProjectBriefLoadState.Unavailable
        }
    }

    val trimmedSearchQuery = debouncedSearchQuery
    val isSearchMode = trimmedSearchQuery.isNotEmpty()
    val searchDomain = if (page == SidebarPage.ARCHIVE) {
        ConversationSearchDomain.ARCHIVE
    } else {
        ConversationSearchDomain.ACTIVE
    }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            when (key) {
                "web_search_enabled" -> webSearchEnabled = changed.getBoolean(key, false)
                com.projectnuke.fusion.chat.DynamicFollowUpPreference.KEY_AUTO_GENERATE ->
                    followUpAutoGenerate = com.projectnuke.fusion.chat.DynamicFollowUpPreference.isAutoGenerateEnabled(changed)
                ArchiveLockPreferenceKey -> {
                    archiveLockEnabled = changed.getBoolean(key, false)
                    archiveUnlockedForSession = false
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val searchDomainConversationIds = remember(searchDomain, conversations, archivedConversations) {
        when (searchDomain) {
            ConversationSearchDomain.ACTIVE -> conversations
            ConversationSearchDomain.ARCHIVE -> archivedConversations
        }.mapTo(mutableSetOf()) { it.id }
    }
    // Visible-content search: coarse SQL candidates verified in Kotlin against
    // the canonical visible-content contract (hidden envelopes/metadata never
    // match). Recollected whenever the debounced query changes.
    val matchingMessageConversationIds by produceState(
        initialValue = emptyList<Long>(),
        trimmedSearchQuery,
        searchDomain,
        searchDomainConversationIds,
    ) {
        value = if (trimmedSearchQuery.length < 2) {
            emptyList()
        } else {
            searchVisibleConversationIds(
                dao = dao,
                rawQuery = trimmedSearchQuery,
                limit = ConversationSearchResultLimit,
                allowedConversationIds = searchDomainConversationIds,
            )
        }
    }

    val filteredConversations = remember(
        searchDomain,
        conversations,
        archivedConversations,
        trimmedSearchQuery,
        matchingMessageConversationIds,
    ) {
        filterConversationsForSearchDomain(
            domain = searchDomain,
            activeConversations = conversations,
            archivedConversations = archivedConversations,
            query = trimmedSearchQuery,
            matchingMessageConversationIds = matchingMessageConversationIds,
        )
    }
    val chatMarkdownExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val token = pendingChatMarkdownExport ?: return@rememberLauncherForActivityResult
        scope.launch {
            val markdown = withContext(Dispatchers.IO) { pendingMarkdownStore.read(token) }
            if (uri == null || markdown.isNullOrBlank()) {
                withContext(Dispatchers.IO) { pendingMarkdownStore.remove(token) }
                pendingChatMarkdownExport = null
                markdownExportBusy = false
                if (uri != null) Toast.makeText(context, "내보낼 내용을 복구하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val written = try {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("Unable to open export target")
                    stream.bufferedWriter().use { it.write(markdown) }
                    true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (written) {
                Toast.makeText(context, "채팅 Markdown을 내보냈습니다.", Toast.LENGTH_SHORT).show()
                pendingChatMarkdownExport = null
                withContext(Dispatchers.IO) { pendingMarkdownStore.remove(token) }
                markdownExportBusy = false
            } else {
                showMarkdownCopyFailureDialog = true
                markdownExportBusy = false
            }
        }
    }
    val settingsBackupExportedText = androidx.compose.ui.res.stringResource(com.projectnuke.fusion.R.string.settings_backup_exported)
    val settingsBackupWriteFailedText = androidx.compose.ui.res.stringResource(com.projectnuke.fusion.R.string.settings_backup_write_failed)
    val settingsBackupEmptyText = androidx.compose.ui.res.stringResource(com.projectnuke.fusion.R.string.settings_backup_empty)
    val settingsBackupReadFailedText = androidx.compose.ui.res.stringResource(com.projectnuke.fusion.R.string.settings_backup_read_failed)
    val settingsBackupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val success = try {
                withContext(Dispatchers.IO) {
                    val payload = buildSettingsBackupJson(context, prefs)
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        writeBoundedSettingsStream(output, payload)
                    } ?: false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            Toast.makeText(context, if (success) settingsBackupExportedText else settingsBackupWriteFailedText, Toast.LENGTH_SHORT).show()
        }
    }
    val settingsBackupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val result = readSettingsBackup(context, uri)) {
                is SettingsBackupReadResult.Success -> {
                    if (result.text.isBlank()) Toast.makeText(context, settingsBackupEmptyText, Toast.LENGTH_SHORT).show()
                    else pendingSettingsRestoreJson = result.text
                }
                else -> Toast.makeText(context, settingsBackupReadFailedText, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val projectSnapshotExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val path = pendingProjectSnapshotExportPath
        pendingProjectSnapshotExportPath = null
        scope.launch {
            val source = path?.let(::File)
            if (uri == null || source == null) {
                withContext(Dispatchers.IO) { source?.delete() }
                projectSnapshotBusy = false
                return@launch
            }
            val written = try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: error("Unable to open snapshot destination")
                    output.use { target ->
                        source.inputStream().use { input ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= ProjectSnapshotBounds.MAX_ARCHIVE_INPUT_BYTES)
                                target.write(buffer, 0, count)
                            }
                        }
                    }
                    true
                }
            } catch (cancelled: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { source.delete() }
                projectSnapshotBusy = false
                throw cancelled
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.IO) { source.delete() }
            projectSnapshotBusy = false
            Toast.makeText(
                context,
                if (written) "프로젝트 스냅샷을 내보냈습니다." else "프로젝트 스냅샷을 저장하지 못했습니다.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val projectConsistencyReviewState by produceState<ProjectConsistencyReviewLoadState>(
        initialValue = ProjectConsistencyReviewLoadState.Unavailable,
        showProjectConsistencyReview,
        selectedProjectId,
        page,
        isDrawerOpen,
        archiveLockEnabled,
        archiveUnlockedForSession,
        projectResourceRevision,
        selectedProject,
        accessibleProjectConversations,
        projectFiles,
    ) {
        if (!showProjectConsistencyReview || !isDrawerOpen || page != SidebarPage.PROJECTS) {
            value = ProjectConsistencyReviewLoadState.Unavailable
            return@produceState
        }
        val projectId = selectedProjectId ?: run {
            value = ProjectConsistencyReviewLoadState.Unavailable
            return@produceState
        }
        value = ProjectConsistencyReviewLoadState.Loading
        try {
            val includeArchived = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
            val report = projectConsistencyReviewRepository.review(projectId, includeArchived)
            value = ProjectConsistencyReviewLoadState.Ready(report)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = ProjectConsistencyReviewLoadState.Unavailable
        }
    }
    val projectKnowledgeDigestState by produceState<ProjectKnowledgeDigestState>(
        initialValue = ProjectKnowledgeDigestState.Unavailable,
        showProjectKnowledgeDigest,
        selectedProjectId,
        projectDigestRangeDays,
        archiveLockEnabled,
        archiveUnlockedForSession,
        page,
        isDrawerOpen,
    ) {
        if (!showProjectKnowledgeDigest || !isDrawerOpen || page != SidebarPage.PROJECTS) {
            value = ProjectKnowledgeDigestState.Unavailable
            return@produceState
        }
        val projectId = selectedProjectId ?: run {
            value = ProjectKnowledgeDigestState.Unavailable
            return@produceState
        }
        value = ProjectKnowledgeDigestState.Loading
        value = try {
            val includeArchived = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
            ProjectKnowledgeDigestState.Ready(
                projectKnowledgeDigestRepository.load(projectId, projectDigestRangeDays, includeArchived),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProjectKnowledgeDigestState.Unavailable
        }
    }
    val projectSnapshotImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            projectSnapshotBusy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val result = withOwnedProjectSnapshotInput(
                    open = { context.contentResolver.openInputStream(uri) },
                    consume = { input -> projectSnapshotRepository.importSnapshot(input) },
                )
                if (result == null) {
                    Toast.makeText(context, "스냅샷 파일을 열지 못했습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    when (result) {
                        is ProjectSnapshotImportResult.Imported -> {
                            selectedProjectId = result.projectId
                            projectResourceRevision++
                            Toast.makeText(
                                context,
                                "${result.name} 프로젝트를 가져왔습니다. 저장 지식 ${result.savedItems}개, 할 일 ${result.taskCount}개, 복습 카드 ${result.cardCount}개, 원본 자료 파일 ${result.fileCount}개",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        ProjectSnapshotImportResult.InvalidArchive ->
                            Toast.makeText(context, "스냅샷 형식이나 내용이 올바르지 않습니다.", Toast.LENGTH_LONG).show()
                        ProjectSnapshotImportResult.SnapshotTooLarge ->
                            Toast.makeText(context, "스냅샷이 Fusion의 가져오기 한도를 초과했습니다.", Toast.LENGTH_LONG).show()
                        ProjectSnapshotImportResult.ContentRejected ->
                            Toast.makeText(context, "민감한 정보가 포함된 스냅샷은 가져올 수 없습니다.", Toast.LENGTH_LONG).show()
                        ProjectSnapshotImportResult.Failed ->
                            Toast.makeText(context, "프로젝트를 가져오지 못했습니다. 기존 프로젝트는 변경되지 않았습니다.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, "프로젝트를 가져오지 못했습니다. 기존 프로젝트는 변경되지 않았습니다.", Toast.LENGTH_LONG).show()
            } finally {
                projectSnapshotBusy = false
            }
        }
    }
    val projectFileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val targetProjectId = projectFileImportProjectId
        projectFileImportProjectId = null
        if (uri == null || targetProjectId == null) return@rememberLauncherForActivityResult
scope.launch {
        var lastPdfAdmissionRejection: String? = null
        val metadata = AttachmentStorageManager.queryUriMetadata(context, uri)
            val displayName = normalizeUserVisibleName(
                metadata.displayName ?: uri.lastPathSegment,
                fallback = "project-file",
                maxCodePoints = 120,
            )
            val result = ProjectFileImportCoordinator(
                deletePendingFile = { path ->
                    AttachmentStorageManager.deletePendingAttachmentFile(context, path)
                },
                unregisterPending = AttachmentStorageManager::unregisterPendingAttachment,
            ).copyAndPersist(
                copy = {
                    AttachmentImportCoordinator(context.applicationContext).copy(
                        source = uri.toString(),
                        displayName = displayName,
                        declaredLength = metadata.sizeBytes,
                    )
                },
persist = { generatedFile ->
                    // ONE authoritative project admission seam (shared with
                    // conversation->project promotion): PDFs resolve their
                    // typed extraction state through the derived index (one
                    // build at import), plain text keeps the existing
                    // whole-file contract.
                    val canonical = generatedFile.canonicalPath
                    val admission = com.projectnuke.fusion.retrieval.ProjectKnowledgeAdmissionPolicy.admitManagedPdfViaIndex(
                        context = context,
                        attachmentRoot = AttachmentStorageManager.getAttachmentDirectory(context),
                        mimeType = metadata.mimeType,
                        displayName = displayName,
                        localPath = canonical,
                    )
                    if (admission.status != com.projectnuke.fusion.retrieval.ProjectFileAdmission.ACCEPTED) {
                        admission.documentStatus?.let { status ->
                            lastPdfAdmissionRejection =
                                com.projectnuke.fusion.retrieval.DocumentStatusPolicy.statusMessage(status)
                        }
                        ProjectOutcome.Failure(
                            when (admission.status) {
                                com.projectnuke.fusion.retrieval.ProjectFileAdmission.EMPTY -> ProjectFailure.EMPTY_PROJECT_FILE
                                com.projectnuke.fusion.retrieval.ProjectFileAdmission.TOO_LARGE -> ProjectFailure.PROJECT_FILE_TOO_LARGE
                                com.projectnuke.fusion.retrieval.ProjectFileAdmission.UNREADABLE -> ProjectFailure.UNREADABLE_PROJECT_FILE
                                com.projectnuke.fusion.retrieval.ProjectFileAdmission.UNSUPPORTED -> ProjectFailure.UNSUPPORTED_PROJECT_FILE
                                com.projectnuke.fusion.retrieval.ProjectFileAdmission.ACCEPTED -> error("unreachable")
                            },
                        )
                    } else {
                        ProjectCoordinator(
                            projectDao,
                            dao,
                            runInTransaction = { block -> block() },
                        ).insertProjectFile(
                            ProjectFileEntity(
                                projectId = targetProjectId,
                                displayName = displayName,
                                mimeType = metadata.mimeType ?: "text/plain",
                                localPath = checkNotNull(admission.trustedFile).canonicalPath,
                                addedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                isAdopted = { it is ProjectOutcome.Ok },
                isDurablyReferenced = { path ->
                    projectDao.getProjectFileByPath(targetProjectId, path) != null
                },
            )
            when (result) {
                is ProjectFileImportResult.ImportFailed -> {
                    val message = when (result.kind) {
                        AttachmentImportFailure.FILE_TOO_LARGE,
                        AttachmentImportFailure.BATCH_TOO_LARGE -> ProjectFilePolicy.tooLargeMessage()
                        AttachmentImportFailure.SOURCE_UNAVAILABLE -> ProjectFilePolicy.unreadableMessage()
                        else -> "프로젝트 자료를 가져오지 못했습니다."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
                ProjectFileImportResult.PersistenceFailed ->
                    Toast.makeText(context, "프로젝트 자료를 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
is ProjectFileImportResult.Persisted -> when (val outcome = result.value) {
                    is ProjectOutcome.Ok -> {
                        projectResourceRevision++
                        Toast.makeText(context, "프로젝트 자료로 추가했습니다.", Toast.LENGTH_SHORT).show()
                    }
                    is ProjectOutcome.Failure -> {
                        // Typed Document Intelligence rejection (e.g. scanned
                        // PDF) carries its bounded truthful message.
                        if (outcome.reason == ProjectFailure.UNREADABLE_PROJECT_FILE &&
                            lastPdfAdmissionRejection != null
                        ) {
                            Toast.makeText(context, lastPdfAdmissionRejection, Toast.LENGTH_LONG).show()
                        } else {
                            val message = when (outcome.reason) {
                                ProjectFailure.PROJECT_FILE_LIMIT -> ProjectFilePolicy.capacityMessage()
                                ProjectFailure.EMPTY_PROJECT_FILE -> ProjectFilePolicy.emptyMessage()
                                ProjectFailure.PROJECT_FILE_TOO_LARGE -> ProjectFilePolicy.tooLargeMessage()
                                ProjectFailure.UNREADABLE_PROJECT_FILE -> ProjectFilePolicy.unreadableMessage()
                                ProjectFailure.UNSUPPORTED_PROJECT_FILE -> ProjectFilePolicy.unsupportedMessage()
                                else -> "프로젝트 자료를 저장하지 못했습니다."
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
    fun saveSetting(key: String, value: Boolean) {
        saveDrawerChatBoolean(prefs, key, value)
        Toast.makeText(context, "설정을 저장했습니다.", Toast.LENGTH_SHORT).show()
    }

    fun leaveArchive() {
        archiveUnlockedForSession = false
        page = SidebarPage.HOME
        settingsSection = null
        searchQuery = ""
    }

    fun closeDrawer() {
        archiveUnlockedForSession = false
        onBack()
    }

    fun openConversation(conversationId: Long) {
        val authorizedSession = archiveUnlockedForSession
        archiveUnlockedForSession = false
        onOpenConversation(conversationId, authorizedSession)
    }

    // Reminder-tap deep link (U05): the notification carries stable identity
    // so the tap resolves the referenced task/review item instead of merely
    // opening the app root. Deleted/stale targets resolve truthfully with a
    // bounded message; endpoint screens revalidate access on open.
    LaunchedEffect(pendingReminderNavigation?.eventId) {
        val navigation = pendingReminderNavigation ?: return@LaunchedEffect
        try {
            if (navigation.taskId != null) {
                val task = withContext(Dispatchers.IO) {
                    db.projectTaskDao().getById(navigation.taskId)
                }
                if (task == null) {
                    Toast.makeText(context, "할 일을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    val project = withContext(Dispatchers.IO) {
                        db.projectDao().getProjectById(task.projectId)
                    }
                    if (project == null) {
                        Toast.makeText(context, "할 일의 프로젝트를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        selectedProjectId = project.id
                        requestedProjectTaskTarget = com.projectnuke.fusion.workspace.WorkspaceTaskTarget(project.id, task.id)
                        page = SidebarPage.PROJECTS
                    }
                }
            } else if (navigation.cardId != null) {
                val card = withContext(Dispatchers.IO) {
                    db.studyReviewCardDao().get(navigation.cardId)
                }
                if (card == null) {
                    Toast.makeText(context, "복습 카드를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    val archiveAllowed = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
                    val project = card.projectId?.let { projectId ->
                        withContext(Dispatchers.IO) { db.projectDao().getProjectById(projectId) }
                    }
                    val conversationId = card.sourceConversationId
                    val savedId = card.sourceSavedKnowledgeId
                    val conversation = conversationId?.let {
                        withContext(Dispatchers.IO) { db.chatDao().getConversationById(it) }
                    }
                    val saved = savedId?.let {
                        withContext(Dispatchers.IO) { db.savedKnowledgeDao().getById(it) }
                    }
                    val savedAccessible = saved != null && try {
                        withContext(Dispatchers.IO) {
                            com.projectnuke.fusion.knowledge.SavedKnowledgeAccessGate.isRowAccessible(
                                db, saved, archiveAllowed,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    val destination = com.projectnuke.fusion.resolveReminderReviewCardDestination(
                        scope = card.scope,
                        projectId = card.projectId,
                        projectExists = project?.id == card.projectId,
                        projectDeleting = project?.deletionPending ?: false,
                        deletionPending = card.deletionPending,
                        sourceConversationId = conversationId,
                        sourceConversationArchived = conversation?.isArchived,
                        sourceSavedKnowledgeId = savedId,
                        savedKnowledgeAccessible = savedAccessible,
                        archiveAllowed = archiveAllowed,
                    )
                    when (destination) {
                        com.projectnuke.fusion.ReminderReviewCardDestination.PROJECT_CARD -> {
                            val validProject = project ?: return@LaunchedEffect
                            selectedProjectId = validProject.id
                            page = SidebarPage.PROJECTS
                            openReviewCardId = card.id
                        }
                        com.projectnuke.fusion.ReminderReviewCardDestination.PERSONAL_CARD -> {
                            openReviewCardId = card.id
                        }
                        null -> {
                            val message = if (card.scope == "PROJECT") {
                                "카드의 프로젝트를 찾을 수 없습니다."
                            } else {
                                "개인 복습 카드의 원본에 접근할 수 없습니다."
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            onReminderConsumed?.invoke()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Toast.makeText(context, "알림 대상으로 이동하지 못했습니다.", Toast.LENGTH_SHORT).show()
            onReminderConsumed?.invoke()
        }
    }

    fun regenerateConversationTitle(conversation: ConversationEntity) {
        menuConversation = null
        val generation = titleRegenerationGeneration + 1L
        titleRegenerationGeneration = generation
        scope.launch {
            runCatching {
                val messages = dao.getMessagesForConversation(conversation.id)
                val newTitle = buildSafeConversationTitleFromMessages(messages)
                val currentTitle = conversation.title.ifBlank { "새 대화" }
                val latest = dao.getConversationById(conversation.id)
                if (generation != titleRegenerationGeneration ||
                    latest?.title.orEmpty().ifBlank { "새 대화" } != currentTitle
                ) return@runCatching
                if (newTitle == currentTitle) {
                    Toast.makeText(context, "이미 적절한 제목입니다.", Toast.LENGTH_SHORT).show()
                } else {
                    dao.updateConversationTitle(conversation.id, newTitle)
                    Toast.makeText(context, "채팅 제목을 다시 생성했습니다.", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(context, "채팅 제목을 생성할 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openArchiveWithAuth() {
        if (!archiveLockEnabled) {
            settingsSection = null
            searchQuery = ""
            page = SidebarPage.ARCHIVE
            return
        }
        if (archiveAuthInFlight) return

        if (!isArchiveAuthenticationAvailable(context)) {
            Toast.makeText(context, "이 기기에서는 아카이브 잠금을 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        archiveAuthInFlight = true
        authenticateArchiveAccess(
            context = context,
            onSuccess = {
                archiveAuthInFlight = false
                archiveUnlockedForSession = true
                settingsSection = null
                searchQuery = ""
                page = SidebarPage.ARCHIVE
                Toast.makeText(context, "인증이 완료되었습니다.", Toast.LENGTH_SHORT).show()
            },
            onCanceled = {
                archiveAuthInFlight = false
                Toast.makeText(context, "인증이 취소되었습니다.", Toast.LENGTH_SHORT).show()
            },
            onError = {
                archiveAuthInFlight = false
                Toast.makeText(context, "아카이브를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    BackHandler(enabled = isDrawerOpen && page != SidebarPage.BENCHMARK && (searchQuery.isNotBlank() || page != SidebarPage.HOME || settingsSection != null)) {
        when (resolveDrawerBackAction(
            page = when (page) {
                SidebarPage.HOME -> DrawerBackPage.HOME
                SidebarPage.LAB -> DrawerBackPage.LAB
                SidebarPage.SETTINGS -> DrawerBackPage.SETTINGS
                SidebarPage.ARCHIVE -> DrawerBackPage.ARCHIVE
                SidebarPage.INBOX -> DrawerBackPage.INBOX
                SidebarPage.PROMPT_LAB -> DrawerBackPage.PROMPT_LAB
                SidebarPage.PROJECTS -> DrawerBackPage.PROJECTS
                SidebarPage.ACTION_CENTER -> DrawerBackPage.HOME
                SidebarPage.RESEARCH_REFRESH -> DrawerBackPage.HOME
                SidebarPage.BENCHMARK -> DrawerBackPage.OTHER
            },
            searchActive = searchQuery.isNotBlank(),
            settingsSubsectionActive = settingsSection != null,
            promptLabOrigin = promptLabOrigin,
        )) {
            DrawerBackAction.CLEAR_SEARCH -> searchQuery = ""
            DrawerBackAction.SETTINGS_HOME -> settingsSection = null
            DrawerBackAction.DRAWER_HOME -> leaveArchive()
            DrawerBackAction.DRAWER_LAB -> {
                settingsSection = null
                searchQuery = ""
                page = SidebarPage.LAB
            }
            DrawerBackAction.SETTINGS_PARENT -> {
                settingsSection = SettingsSection.ADVANCED_ABOUT
                page = SidebarPage.SETTINGS
            }
            DrawerBackAction.NONE -> Unit
        }
    }

    if (!isSearchMode && page == SidebarPage.BENCHMARK) {
        val semanticColors = LocalFusionSemanticColors.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(semanticColors.background)
        ) {
            FusionBenchmarkScreen(
                onBack = {
                    pendingBenchmarkModelFilter = null
                    pendingBenchmarkOpenHistory = false
                    // Lab-origin Benchmark returns to its immediate parent LAB;
                    // every other entry (Settings row, direct/external request)
                    // keeps the previous HOME-return behavior. The single onBack
                    // drives both the screen's visible Back and its own
                    // BackHandler, so both mechanisms agree by construction.
                    when (resolveBenchmarkBack(benchmarkOrigin)) {
                        DrawerBackAction.DRAWER_LAB -> {
                            settingsSection = null
                            searchQuery = ""
                            page = SidebarPage.LAB
                        }
                        else -> leaveArchive()
                    }
                },
                initialShowHistory = pendingBenchmarkOpenHistory,
                initialHistoryModelFilter = pendingBenchmarkModelFilter,
                onInitialRequestConsumed = {
                    pendingBenchmarkModelFilter = null
                    pendingBenchmarkOpenHistory = false
                }
            )
        }
        return
    }

    if (showModelAbTestLab) {
        ModelAbTestLabScreen(onBack = { showModelAbTestLab = false })
        return
    }

    fun startProjectSnapshotExport(includeSourceFiles: Boolean) {
        val target = selectedProject ?: return
        val projectId = target.id
        val authorizedRows = accessibleProjectConversations
            .take(ProjectSnapshotBounds.MAX_CONVERSATIONS)
            .toList()
        val authorizedIds = authorizedRows.map { it.id }.toSet()
        projectSnapshotBusy = true
        scope.launch {
            var waitingForPicker = false
            try {
                val summaryLoad = withContext(Dispatchers.IO) {
                    loadConversationSummariesResult(context, authorizedIds)
                }
                if (summaryLoad.durabilityUnknownConversationIds.isNotEmpty()) {
                    Toast.makeText(context, "대화 요약의 저장 상태를 확인할 수 없어 내보내기를 중단했습니다.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val summariesById = summaryLoad.summaries.associateBy { it.conversationId }
                val boundedSummaries = authorizedRows.mapNotNull { conversation ->
                    summariesById[conversation.id]?.let { memory ->
                        val visible = com.projectnuke.fusion.chat.sanitizeFusionMemoryText(memory.summary)
                            .trim().take(ProjectSnapshotBounds.MAX_SUMMARY_CHARS)
                        visible.takeIf(String::isNotBlank)?.let { memory.copy(summary = it) }
                    }
                }
                val result = projectSnapshotRepository.exportProject(
                    projectId = projectId,
                    authorizedConversationIds = authorizedIds,
                    authorizedConversations = boundedSummaries,
                    includeSourceFiles = includeSourceFiles,
                    archivedContentAuthorized = ArchiveAccessPolicy.canIncludeArchivedInExport(
                        archiveLockEnabled,
                        archiveUnlockedForSession,
                    ),
                )
                when (result) {
                    is ProjectSnapshotExportResult.Ready -> {
                        pendingProjectSnapshotExportPath = result.file.absolutePath
                        val safeName = target.name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "-")
                            .replace(Regex("\\s+"), "-").take(48).ifBlank { "Fusion-Project" }
                        try {
                            projectSnapshotExportLauncher.launch("$safeName.fusion-project.zip")
                            waitingForPicker = true
                        } catch (_: Exception) {
                            pendingProjectSnapshotExportPath = null
                            withContext(Dispatchers.IO) { result.file.delete() }
                            Toast.makeText(context, "내보내기 위치를 열지 못했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    ProjectSnapshotExportResult.ProjectUnavailable ->
                        Toast.makeText(context, "프로젝트를 찾을 수 없어 내보내지 못했습니다.", Toast.LENGTH_LONG).show()
                    ProjectSnapshotExportResult.UnauthorizedConversation,
                    ProjectSnapshotExportResult.SummaryUnavailable ->
                        Toast.makeText(context, "접근 가능한 대화 요약을 확인하지 못해 내보내기를 중단했습니다.", Toast.LENGTH_LONG).show()
                    ProjectSnapshotExportResult.ContentUnavailable ->
                        Toast.makeText(context, "프로젝트 자료를 안전하게 확인하지 못해 내보내기를 중단했습니다.", Toast.LENGTH_LONG).show()
                    ProjectSnapshotExportResult.SnapshotTooLarge ->
                        Toast.makeText(context, "프로젝트 자료가 스냅샷 내보내기 한도를 초과했습니다.", Toast.LENGTH_LONG).show()
                    ProjectSnapshotExportResult.Failed ->
                        Toast.makeText(context, "프로젝트 스냅샷을 준비하지 못했습니다.", Toast.LENGTH_LONG).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, "프로젝트 스냅샷을 준비하지 못했습니다.", Toast.LENGTH_LONG).show()
            } finally {
                if (!waitingForPicker) projectSnapshotBusy = false
            }
        }
    }

    fun showConversationCheckpoints(conversation: ConversationEntity) {
        menuConversation = null
        scope.launch {
            val current = withContext(Dispatchers.IO) { dao.getConversationById(conversation.id) }
            val allowed = current != null && (!current.isArchived ||
                ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession))
            if (!allowed) {
                Toast.makeText(context, "대화에 접근할 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            checkpointConversation = current
            checkpointItems = withContext(Dispatchers.IO) {
                db.conversationCheckpointDao().listForConversation(conversation.id)
            }
        }
    }

    checkpointConversation?.let { selectedConversation ->
        ConversationCheckpointListDialog(
            conversationTitle = selectedConversation.title,
            checkpoints = checkpointItems,
            onDismiss = { checkpointConversation = null },
            onOpen = { checkpoint ->
                val authorized = archiveUnlockedForSession
                archiveUnlockedForSession = false
                checkpointConversation = null
                onOpenCheckpoint(checkpoint.id, checkpoint.conversationId, checkpoint.messageId, authorized)
            },
            onContinue = { checkpoint ->
                val authorized = archiveUnlockedForSession
                archiveUnlockedForSession = false
                scope.launch {
                    val succeeded = onContinueFromCheckpoint(checkpoint.id, checkpoint.conversationId, checkpoint.messageId, authorized)
                    if (succeeded) checkpointConversation = null
                }
            },
            onRename = { checkpoint, title, note, onResult ->
                scope.launch {
                    val repository = com.projectnuke.fusion.data.ConversationCheckpointRepository(db)
                    val succeeded = withContext(Dispatchers.IO) {
                        repository.rename(checkpoint.id, checkpoint.conversationId, title, note)
                    }
                    if (succeeded) {
                        checkpointItems = withContext(Dispatchers.IO) {
                            db.conversationCheckpointDao().listForConversation(checkpoint.conversationId)
                        }
                    } else {
                        Toast.makeText(context, "체크포인트를 수정하지 못했습니다.", Toast.LENGTH_SHORT).show()
                    }
                    onResult(succeeded)
                }
            },
            onDelete = { checkpoint ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        db.conversationCheckpointDao().delete(checkpoint.id, checkpoint.conversationId)
                    }
                    checkpointItems = withContext(Dispatchers.IO) {
                        db.conversationCheckpointDao().listForConversation(checkpoint.conversationId)
                    }
                }
            },
        )
    }

    fun startProjectSnapshotImport() {
        if (projectSnapshotBusy) return
        projectSnapshotBusy = true
        try {
            projectSnapshotImportLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        } catch (_: Exception) {
            projectSnapshotBusy = false
            Toast.makeText(context, "스냅샷 파일 선택을 열지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    fun startProjectReport(targetProject: ProjectEntity) {
        if (projectReportBusy) return
        projectReportBusy = true
        scope.launch {
            try {
                val sourceRows = withContext(Dispatchers.IO) {
                    projectDao.getActiveConversationsForProjectBounded(
                        targetProject.id,
                        com.projectnuke.fusion.projects.ProjectReportRepository.MAX_REPORT_CONVERSATIONS,
                    )
                }
                val authorizedRows = sourceRows
                val authorizedIds = authorizedRows.map { it.id }.toSet()
                val summaryLoad = withContext(Dispatchers.IO) {
                    loadConversationSummariesResult(context, authorizedIds)
                }
                if (summaryLoad.durabilityUnknownConversationIds.isNotEmpty()) {
                    Toast.makeText(context, "프로젝트 요약 상태를 확인할 수 없어 보고서를 만들지 않았습니다.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val currentIds = withContext(Dispatchers.IO) {
                    projectDao.getActiveConversationsForProjectBounded(
                        targetProject.id,
                        com.projectnuke.fusion.projects.ProjectReportRepository.MAX_REPORT_CONVERSATIONS,
                    ).map { it.id }.toSet()
                }
                if (!currentIds.containsAll(authorizedIds)) {
                    Toast.makeText(context, "프로젝트 자료 접근 상태가 바뀌어 보고서 생성을 중단했습니다.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val reportFiles = withContext(Dispatchers.IO) {
                    projectDao.getProjectFilesBounded(targetProject.id, 32)
                        .take(com.projectnuke.fusion.projects.ProjectReportPolicy.MAX_FILES)
                }
                val knowledgeController = ProjectKnowledgeController(context.applicationContext, db)
                val sourceSummaries = reportFiles.mapNotNull { file ->
                    val summary = try {
                        knowledgeController.inspectPassages(file)
                            .take(2)
                            .joinToString(" ") { passage -> passage.text.replace(Regex("\\s+"), " ").trim().take(260) }
                            .take(520)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ""
                    }
                    summary.takeIf(String::isNotBlank)?.let {
                        com.projectnuke.fusion.projects.ProjectReportSourceSummary(file.id, it)
                    }
                }
                val markdown = withContext(Dispatchers.IO) {
                    val research = summaryLoad.summaries.map {
                        com.projectnuke.fusion.projects.ProjectReportResearchSummary(
                            conversationId = it.conversationId,
                            summary = com.projectnuke.fusion.chat.sanitizeFusionMemoryText(it.summary),
                        )
                    }
                    com.projectnuke.fusion.projects.ProjectReportRepository(db).buildMarkdown(
                        projectId = targetProject.id,
                        authorizedConversationIds = authorizedIds,
                        researchSummaries = research,
                        sourceSummaries = sourceSummaries,
                        archivedContentAuthorized = false,
                    )
                }
                if (markdown == null) {
                    Toast.makeText(context, "프로젝트 정보를 불러올 수 없어 보고서를 만들지 않았습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    projectReportMarkdown = markdown
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, "프로젝트 보고서를 만들지 못했습니다.", Toast.LENGTH_LONG).show()
            } finally {
                projectReportBusy = false
            }
        }
    }

    // Projects v2: explicit instructions and shared local files sit beside
    // the existing conversation membership. Sibling histories remain
    // independent, and deleting a project keeps its conversations.
    if (!isSearchMode && page == SidebarPage.PROJECTS) {
        val semanticColors = LocalFusionSemanticColors.current
        val project = selectedProject
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(semanticColors.background)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            DrawerPageTopBar(
                project?.name?.ifBlank { "새 프로젝트" } ?: "프로젝트",
                onBack = { leaveArchive() },
                trailing = if (project == null) null else {
                    {
                        ProjectActionsOverflow(
                            enabled = !projectSnapshotBusy && !projectWorkPlanInFlight && !projectReportBusy,
                            onCreateWorkPlan = { showCreateWorkPlanDialog = true },
                            onCreateReport = { startProjectReport(project) },
                            onOpenWorkPlanHistory = { showProjectWorkPlanHistory = true },
                            onOpenPulse = { showProjectPulse = true },
                            onExport = { showProjectSnapshotOptions = true },
                            onImport = ::startProjectSnapshotImport,
                            onConsistencyReview = { showProjectConsistencyReview = true },
                            onRecentDigest = {
                                projectDigestRangeDays = 7
                                showProjectKnowledgeDigest = true
                            },
                        )
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (project == null) {
                Text(
                    text = "프로젝트를 찾을 수 없습니다.",
                    color = DrawerTextSecondary,
                    fontSize = 13.sp
                )
            } else {
                val currentProjectBriefState = when (val state = projectBriefLoadState) {
                    is ProjectBriefLoadState.Ready ->
                        if (state.snapshot.projectId == project.id &&
                            state.snapshot.recentSummaries.all { item ->
                                accessibleProjectConversations.any { it.id == item.conversationId }
                            }
                        ) state else ProjectBriefLoadState.Loading
                    else -> state
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        ProjectBriefSection(
                            state = currentProjectBriefState,
                            synthesisAvailable = isProjectSynthesisAvailable(
                                currentProjectBriefState,
                                project.id,
                            ),
                            synthesisInFlight = projectSynthesisInFlight,
                            onProjectSynthesis = { selectedMode ->
                                val frozenSnapshot =
                                    (currentProjectBriefState as? ProjectBriefLoadState.Ready)?.snapshot
                                val targetProjectId = project.id
                                scope.launch {
                                    projectSynthesisFlight.runAction {
                                        when (val outcome = runProjectSynthesisTap(
                                            frozenSnapshot,
                                            targetProjectId,
                                            selectedMode,
                                            onNewProjectSynthesisChat,
                                        )) {
                                            ProjectSynthesisTapOutcome.Opened -> Unit
                                            is ProjectSynthesisTapOutcome.Rejected ->
                                                Toast.makeText(context, outcome.message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onOpenConversation = ::openConversation,
                        )
                    }
                    item {
                        ProjectWorkPlanBriefRow(
                            projectId = project.id,
                            onOpenPlan = {
                                openProjectWorkPlanStepId = null
                                openProjectWorkPlanId = it
                            },
                        )
                    }
                    item {
                        ProjectKnowledgeSection(
                            project = project,
                            files = projectFiles,
                            onEditInstructions = {
                                projectInstructionsDraft = project.instructions
                                showProjectInstructionsDialog = true
                            },
                            onAddFile = {
                                projectFileImportProjectId = project.id
                                projectFileImportLauncher.launch(arrayOf("text/*", "application/json", "application/pdf", "image/png", "image/jpeg", "image/webp"))
                            },
                            onRemoveFile = { file ->
                                scope.launch {
                                    when (ProjectCoordinator(
                                        projectDao,
                                        dao,
                                        runInTransaction = { block -> block() },
                                    ).removeProjectFile(file.id)) {
                                        is ProjectOutcome.Ok -> {
                                            projectResourceRevision++
                                            runCatching {
                                                AttachmentStorageManager.cleanupUnreferencedAttachments(context, dao, projectDao)
                                            }
                                            Toast.makeText(context, "프로젝트 자료에서 제거했습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                        is ProjectOutcome.Failure ->
                                            Toast.makeText(context, "프로젝트 자료를 제거하지 못했습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            includeArchivedBacklinks = ArchiveAccessPolicy.canRenderArchive(
                                archiveLockEnabled,
                                archiveUnlockedForSession,
                            ),
                            onOpenConversation = ::openConversation,
                            onOpenKnowledge = { target ->
                                scope.launch {
                                    val archiveAllowed = ArchiveAccessPolicy.canRenderArchive(
                                        archiveLockEnabled,
                                        archiveUnlockedForSession,
                                    )
                                    val repository = FusionKnowledgeSearchRepository(context.applicationContext) { archiveAllowed }
                                    if (!repository.accessible(target)) {
                                        Toast.makeText(context, "자료를 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                    } else when (target) {
                                        is KnowledgeTarget.Conversation -> openConversation(target.id)
                                        is KnowledgeTarget.Project -> {
                                            selectedProjectId = target.id
                                            page = SidebarPage.PROJECTS
                                        }
                                        is KnowledgeTarget.Decision -> {
                                            selectedProjectId = target.projectId
                                            page = SidebarPage.PROJECTS
                                        }
                                        is KnowledgeTarget.Memory -> showMemoryManagerDialog = true
                                        is KnowledgeTarget.InboxItem -> page = SidebarPage.INBOX
                                        is KnowledgeTarget.Checkpoint -> onOpenCheckpoint(target.id, target.conversationId, target.messageId, archiveUnlockedForSession)
                                        is KnowledgeTarget.SavedKnowledge -> {
                                            savedKnowledgeDetail = withContext(Dispatchers.IO) { db.savedKnowledgeDao().getById(target.id) }
                                        }
                                    }
                                }
                            },
                            onSaveExtractedNote = { outcome ->
                                if (outcome is StructuredExtractionNoteHandoff.Ready) {
                                    savedKnowledgeNoteDraft = outcome.draft
                                    savedKnowledgeNoteError = null
                                }
                            },
                            onQuestionWithSelection = onQuestionWithSelection,
                            onSaveDocumentSectionDraft = { draft -> savedKnowledgeNoteDraft = draft },
                            requestedFileInspectionId = requestedProjectFileInspectionId,
                            onFileInspectionRequestConsumed = { requestedProjectFileInspectionId = null },
                            requestedTaskId = requestedProjectTaskTarget?.takeIf { it.projectId == project.id }?.taskId,
                            onTaskRequestConsumed = { taskId ->
                                if (requestedProjectTaskTarget?.taskId == taskId) requestedProjectTaskTarget = null
                            },
                            requestedDecisionId = requestedProjectDecisionId,
                            onDecisionRequestConsumed = { decisionId ->
                                if (requestedProjectDecisionId == decisionId) requestedProjectDecisionId = null
                            },
                        )
                    }
                    if (accessibleProjectConversations.isEmpty()) {
                        item {
                            Text(
                                text = "아직 추가된 대화가 없습니다. 채팅 메뉴의 프로젝트에 추가를 이용해 주세요.",
                                color = DrawerTextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    } else {
                        items(accessibleProjectConversations, key = { DrawerItemKey.conversation(it.id) }) { conversation ->
                            ConversationRow(
                                conversation = conversation,
                                onClick = { openConversation(conversation.id) },
                                menuExpanded = menuConversation?.id == conversation.id,
                                onDismissMenu = { menuConversation = null },
                                onMenuClick = { menuConversation = conversation },
                                onCheckpoints = { showConversationCheckpoints(conversation) },
                                onTogglePinned = {
                                    menuConversation = null
                                    scope.launch { dao.updateConversationPinned(conversation.id, !conversation.isPinned) }
                                },
                                onRename = {
                                    menuConversation = null
                                    renameConversation = conversation
                                    renameTitle = conversation.title
                                },
                                onRegenerateTitle = {
                                    regenerateConversationTitle(conversation)
                                },
                                onArchive = {
                                    menuConversation = null
                                    scope.launch {
                                        dao.archiveConversation(conversation.id)
                                        if (conversation.id == currentConversationId) {
                                            val nextId = dao.getLatestConversation()?.id
                                            onConversationRemovedFromList(conversation.id, nextId)
                                        }
                                        projectConversations = runCatching {
                                            projectDao.getConversationsForProject(project.id)
                                        }.getOrDefault(emptyList())
                                        projectResourceRevision++
                                    }
                                },
                                onDelete = {
                                    menuConversation = null
                                    deleteConversation = conversation
                                },
                                onRemoveFromProject = {
                                    menuConversation = null
                                    scope.launch {
                                        try {
                                            projectDao.removeAssignment(conversation.id)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "프로젝트에서 제거하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                                            return@launch
                                        }
                                        projectConversations = projectConversations.filterNot { it.id == conversation.id }
                                        projectResourceRevision++
                                        Toast.makeText(context, "프로젝트에서 제거했습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }
                if (showCreateWorkPlanDialog) {
                    ProjectWorkPlanCreateDialog(
                        onDismiss = { showCreateWorkPlanDialog = false },
                        onPrepareProposal = { prompt ->
                            val targetProjectId = project.id
                            scope.launch {
                                projectWorkPlanFlight.runAction {
                                    val opened = onNewProjectSynthesisChat(targetProjectId, prompt)
                                    if (opened) {
                                        showCreateWorkPlanDialog = false
                                        Toast.makeText(context, "작업 계획 요청을 채팅 초안에 준비했습니다. 확인한 뒤 직접 전송해 주세요.", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "계획 제안 요청을 준비하지 못했습니다.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                    )
                }
                openProjectWorkPlanId?.let { planId ->
                    ProjectWorkPlanDetailDialog(
                        projectId = project.id,
                        planId = planId,
                        requestedStepId = openProjectWorkPlanStepId,
                        onDismiss = {
                            openProjectWorkPlanId = null
                            openProjectWorkPlanStepId = null
                        },
                        onDispatchStep = { action -> dispatchWorkPlanStep(action) },
                        onCreateReport = { startProjectReport(project) },
                        archiveAccessIncluded = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession),
                        onOpenConversation = { conversationId ->
                            openProjectWorkPlanId = null
                            openConversation(conversationId)
                        },
                    )
                }
                if (showProjectPulse) {
                    ProjectPulseDialog(
                        database = db,
                        projectId = project.id,
                        projectName = project.name,
                        includeArchived = {
                            ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
                        },
                        onDismiss = { showProjectPulse = false },
                        onOpenTarget = { target ->
                            showProjectPulse = false
                            page = SidebarPage.PROJECTS
                            when (target) {
                                is com.projectnuke.fusion.projects.ProjectPulseItem.WorkPlan -> {
                                    selectedProjectId = target.projectId
                                    openProjectWorkPlanId = target.planId
                                    openProjectWorkPlanStepId = target.stepId
                                }
                                is com.projectnuke.fusion.projects.ProjectPulseItem.Task -> {
                                    selectedProjectId = target.projectId
                                    requestedProjectTaskTarget = com.projectnuke.fusion.workspace.WorkspaceTaskTarget(target.projectId, target.taskId)
                                }
                                is com.projectnuke.fusion.projects.ProjectPulseItem.Decision -> {
                                    selectedProjectId = target.projectId
                                    requestedProjectDecisionId = target.decisionId
                                }
                                is com.projectnuke.fusion.projects.ProjectPulseItem.Research -> {
                                    selectedProjectId = target.projectId
                                    onOpenResearchAnswer(target.conversationId, target.messageId, archiveUnlockedForSession)
                                }
                                is com.projectnuke.fusion.projects.ProjectPulseItem.ReviewCard -> {
                                    selectedProjectId = target.projectId
                                    openReviewCardId = target.cardId
                                }
                            }
                        },
                    )
                }
                pendingWorkPlanAction?.let { action ->
                    if (action.destination == com.projectnuke.fusion.projects.ProjectWorkPlanDestination.KNOWLEDGE_SELECTION) {
                        pendingWorkPlanReviewSelection?.let { selection ->
                            ProjectWorkPlanKnowledgeReviewDialog(
                                action = action,
                                selection = selection,
                                onConfirm = {
                                    scope.launch {
                                        val outcome = try {
                                            workPlanCoordinator.settleReviewSelection(
                                                action = action,
                                                snapshot = selection,
                                                isAccessible = ::workPlanSourceAccessible,
                                            )
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            ProjectWorkPlanHandoffCoordinator.SettleOutcome.Rejected(
                                                "선택한 자료를 기록하지 못했습니다. 계획에서 다시 시작할 수 있습니다.",
                                            )
                                        }
                                        when (outcome) {
                                            ProjectWorkPlanHandoffCoordinator.SettleOutcome.Applied ->
                                                reopenWorkPlanAfterSettle(
                                                    action = action,
                                                    applied = true,
                                                    successMessage = "선택한 자료를 단계 결과로 기록했습니다.",
                                                )
                                            ProjectWorkPlanHandoffCoordinator.SettleOutcome.Stale ->
                                                reopenWorkPlanAfterSettle(
                                                    action = action,
                                                    applied = false,
                                                    successMessage = "선택한 자료를 단계 결과로 기록했습니다.",
                                                )
                                            is ProjectWorkPlanHandoffCoordinator.SettleOutcome.Rejected ->
                                                Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                onAsk = {
                                    val prepared = workPlanCoordinator.prepareReviewQuestion(action, selection)
                                    pendingWorkPlanReviewSelection = null
                                    if (prepared == null) {
                                        cancelPendingWorkPlanAttempt("선택한 자료로 질문을 준비하지 못했습니다.")
                                        return@ProjectWorkPlanKnowledgeReviewDialog
                                    }
                                    scope.launch {
                                        if (onDispatchProjectWorkPlanStep(prepared)) {
                                            pendingWorkPlanAction = null
                                        } else {
                                            cancelPendingWorkPlanAttempt("선택한 자료로 질문을 준비하지 못했습니다. 계획에서 다시 시작할 수 있습니다.")
                                        }
                                    }
                                },
                                onReselect = {
                                    pendingWorkPlanReviewSelection = null
                                    showFusionSearch = true
                                },
                                onCancel = { cancelPendingWorkPlanAttempt("단계 진행을 취소했습니다. 계획에서 다시 시작할 수 있습니다.") },
                            )
                        }
                    }
                }
                if (showProjectWorkPlanHistory) {
                    ProjectWorkPlanHistoryDialog(
                        projectId = project.id,
                        onDismiss = { showProjectWorkPlanHistory = false },
                        onOpenPlan = { planId ->
                            showProjectWorkPlanHistory = false
                            openProjectWorkPlanId = planId
                        },
                    )
                }
                projectReportMarkdown?.let { markdown ->
                    ProjectReportPreviewDialog(
                        markdown = markdown,
                        onDismiss = { projectReportMarkdown = null },
                        onCopy = {
                            clipboard.setText(AnnotatedString(markdown))
                            Toast.makeText(context, "프로젝트 보고서를 복사했습니다.", Toast.LENGTH_SHORT).show()
                        },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/markdown"
                                putExtra(Intent.EXTRA_TEXT, markdown)
                                putExtra(Intent.EXTRA_SUBJECT, "프로젝트 보고서")
                            }
                            try {
                                context.startActivity(Intent.createChooser(send, "프로젝트 보고서 공유"))
                            } catch (_: Exception) {
                                Toast.makeText(context, "공유 화면을 열지 못했습니다.", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                DrawerActionRow(
                    title = "새 대화",
                    subtitle = "이 프로젝트에서 새로 시작합니다.",
                    leading = "+",
                    onClick = { onNewProjectChat(project.id) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                DrawerActionRow(
                    title = "프로젝트 삭제",
                    subtitle = "대화는 보관함에 그대로 남습니다.",
                    leading = "×",
                    onClick = { confirmDeleteProject = project },
                )
            }
        }
        if (showDrawerNewProject) {
            ProjectNameDialog(
                title = "새 프로젝트",
                initial = drawerNewProjectName,
                onDismiss = { showDrawerNewProject = false },
                onConfirm = { name ->
                    showDrawerNewProject = false
                    scope.launch {
                        when (ProjectCoordinator(
                            projectDao,
                            dao,
                            runInTransaction = { block -> db.withTransaction { block() } },
                        ).createProject(name, null)) {
                            is ProjectOutcome.Ok ->
                                Toast.makeText(context, "프로젝트를 만들었습니다.", Toast.LENGTH_SHORT).show()
                            is ProjectOutcome.Failure ->
                                Toast.makeText(context, "프로젝트를 만들지 못했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
        confirmDeleteProject?.let { target ->
            AlertDialog(
                onDismissRequest = { confirmDeleteProject = null },
                title = { Text("프로젝트 삭제") },
                text = { Text("프로젝트를 삭제할까요? 포함된 대화는 그대로 남습니다.") },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteProject = null }) { Text("취소") }
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDeleteProject = null
                        scope.launch {
                            val reminderScheduler = AndroidProjectTaskReminderScheduler(context.applicationContext)
                            when (ReminderDeletionCoordinator(db, reminderScheduler, reminderScheduler).deleteProject(target.id)) {
                                ReminderDeletionResult.DELETED -> {
                                    runCatching {
                                        AttachmentStorageManager.cleanupUnreferencedAttachments(context, dao, projectDao)
                                    }
                                    Toast.makeText(context, "프로젝트를 삭제했습니다.", Toast.LENGTH_SHORT).show()
                                    leaveArchive()
                                }
                                ReminderDeletionResult.NOT_FOUND ->
                                    Toast.makeText(context, "프로젝트를 삭제하지 못했습니다.", Toast.LENGTH_SHORT).show()
                                ReminderDeletionResult.CLEANUP_PENDING ->
                                    Toast.makeText(context, "프로젝트 삭제를 저장했습니다. 알림 정리가 끝나면 삭제가 완료됩니다.", Toast.LENGTH_SHORT).show()
                                ReminderDeletionResult.FAILED ->
                                    Toast.makeText(context, "프로젝트를 삭제하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("삭제") }
                },
                containerColor = DrawerPanelBg,
                titleContentColor = DrawerTextPrimary,
                textContentColor = DrawerTextPrimary
            )
        }
        if (showProjectInstructionsDialog && project != null) {
            val tooLong = !ProjectInstructionPromptPolicy.isWithinLimit(projectInstructionsDraft)
            AlertDialog(
                onDismissRequest = { showProjectInstructionsDialog = false },
                title = { Text("프로젝트 지침") },
                text = {
                    Column {
                        BasicTextField(
                            value = projectInstructionsDraft,
                            onValueChange = { projectInstructionsDraft = it },
                            textStyle = TextStyle(color = DrawerTextPrimary, fontSize = 15.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(DrawerCardSelectedBg, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (tooLong) ProjectInstructionPromptPolicy.boundedMessage() else "${projectInstructionsDraft.length}/4000",
                            color = if (tooLong) MaterialTheme.colorScheme.error else DrawerTextSecondary,
                            fontSize = 12.sp,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "프로젝트 지침은 로컬 모델에서 적용돼요.",
                            color = DrawerTextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showProjectInstructionsDialog = false }) { Text("취소") }
                },
                confirmButton = {
                    TextButton(
                        enabled = !tooLong,
                        onClick = {
                            scope.launch {
                                when (ProjectCoordinator(
                                    projectDao,
                                    dao,
                                    runInTransaction = { block -> block() },
                                ).updateInstructions(project.id, projectInstructionsDraft)) {
                                    is ProjectOutcome.Ok -> {
                                        showProjectInstructionsDialog = false
                                        projectResourceRevision++
                                        Toast.makeText(context, "프로젝트 지침을 저장했습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                    is ProjectOutcome.Failure ->
                                        Toast.makeText(context, "프로젝트 지침을 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    ) { Text("저장") }
                },
                containerColor = DrawerPanelBg,
                titleContentColor = DrawerTextPrimary,
                textContentColor = DrawerTextPrimary,
            )
        }
        if (showProjectSnapshotOptions && project != null) {
            ProjectSnapshotExportOptionsDialog(
                onDismiss = { showProjectSnapshotOptions = false },
                onExport = { includeFiles ->
                    showProjectSnapshotOptions = false
                    startProjectSnapshotExport(includeFiles)
                },
            )
        }
        if (showProjectConsistencyReview && project != null) {
            ProjectConsistencyReviewSurface(
                state = projectConsistencyReviewState,
                onDismiss = { showProjectConsistencyReview = false },
                onOpenSource = { source ->
                    showProjectConsistencyReview = false
                    scope.launch {
                        val includeArchived = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
                        when {
                            source.projectFileId != null && source.projectId == project.id -> {
                                requestedProjectFileInspectionId = source.projectFileId
                            }
                            source.savedKnowledgeId != null -> {
                                val target = KnowledgeTarget.SavedKnowledge(source.savedKnowledgeId)
                                val repository = FusionKnowledgeSearchRepository(context.applicationContext) { includeArchived }
                                if (repository.accessible(target)) {
                                    savedKnowledgeDetail = withContext(Dispatchers.IO) {
                                        db.savedKnowledgeDao().getById(source.savedKnowledgeId)
                                    }
                                } else Toast.makeText(context, "자료를 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                            source.conversationId != null -> {
                                val accessible = withContext(Dispatchers.IO) {
                                    dao.getConversationById(source.conversationId)?.let { includeArchived || !it.isArchived } == true
                                }
                                if (accessible) openConversation(source.conversationId)
                                else Toast.makeText(context, "대화를 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                            source.kind == com.projectnuke.fusion.projects.ProjectConsistencySourceKind.DECISION &&
                                source.projectId != null -> {
                                val target = KnowledgeTarget.Decision(source.projectId, source.id.removePrefix("decision:"))
                                val repository = FusionKnowledgeSearchRepository(context.applicationContext) { includeArchived }
                                if (repository.accessible(target)) {
                                    selectedProjectId = source.projectId
                                    page = SidebarPage.PROJECTS
                                } else Toast.makeText(context, "결정을 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
            )
        }
        if (showProjectKnowledgeDigest && project != null) {
            ProjectKnowledgeDigestSurface(
                projectName = project.name,
                state = projectKnowledgeDigestState,
                selectedRangeDays = projectDigestRangeDays,
                onSelectRange = { days -> projectDigestRangeDays = ProjectKnowledgeDigestPolicy.boundedRangeDays(days) },
                onDismiss = { showProjectKnowledgeDigest = false },
                onOpenEntry = { entry ->
                    showProjectKnowledgeDigest = false
                    when (val target = entry.target) {
                        is ProjectDigestTarget.Conversation -> openConversation(target.id)
                        is ProjectDigestTarget.SavedKnowledge -> scope.launch {
                            val includeArchived = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
                            val repository = FusionKnowledgeSearchRepository(context.applicationContext) { includeArchived }
                            if (repository.accessible(KnowledgeTarget.SavedKnowledge(target.id))) {
                                savedKnowledgeDetail = withContext(Dispatchers.IO) { db.savedKnowledgeDao().getById(target.id) }
                            } else Toast.makeText(context, "이 자료에 접근할 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                        is ProjectDigestTarget.ProjectFile -> scope.launch {
                            val live = withContext(Dispatchers.IO) { db.projectDao().getProjectFileById(target.id) }
                            if (live?.projectId == project.id) requestedProjectFileInspectionId = target.id
                            else Toast.makeText(context, "프로젝트 자료를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                        null -> Unit
                    }
                },
            )
        }
        return
    }

    if (page == SidebarPage.HOME || (isSearchMode && page != SidebarPage.ARCHIVE)) {
        val semanticColors = LocalFusionSemanticColors.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(semanticColors.background)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (!isSearchMode) {
                DrawerTopBar(onClose = { closeDrawer() }, onNewChat = onNewChat)
                Spacer(modifier = Modifier.height(12.dp))
            }

            DrawerSearchBox(value = searchQuery, onValueChange = { searchQuery = it })
            Spacer(modifier = Modifier.height(10.dp))

            // ONE primary vertical scrolling surface: header + search stay
            // fixed above; Tools / Projects / Conversations / Utilities all
            // participate in this single LazyColumn so the entire drawer
            // scrolls naturally with no small nested conversation viewport
            // and no competing vertical scroll owners.
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isSearchMode) {
                    if (continueWork.isNotEmpty()) {
                        item { DrawerSectionTitle("이어서 하기") }
                        items(continueWork, key = { "continue:${it.stableId}" }) { shortcut ->
                            DrawerActionRow(
                                title = shortcut.title,
                                subtitle = when (shortcut.kind) {
                                    ContinueWorkKind.PROJECT -> "최근 프로젝트"
                                    ContinueWorkKind.TASK -> "미완료 할 일 · ${shortcut.subtitle}"
                                    ContinueWorkKind.INBOX -> "최근 캡처"
                                },
                                leading = when (shortcut.kind) {
                                    ContinueWorkKind.PROJECT -> "P"
                                    ContinueWorkKind.TASK -> "T"
                                    ContinueWorkKind.INBOX -> "I"
                                },
                                onClick = {
                                    searchQuery = ""
                                    settingsSection = null
                                    when (shortcut.kind) {
                                        ContinueWorkKind.PROJECT, ContinueWorkKind.TASK -> {
                                            selectedProjectId = shortcut.projectId
                                            page = SidebarPage.PROJECTS
                                        }
                                        ContinueWorkKind.INBOX -> page = SidebarPage.INBOX
                                    }
                                },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(4.dp)) }
                    }
                    item {
                        DrawerActionRow(
                            title = "지금 할 일",
                            subtitle = "확인하거나 이어서 할 항목",
                            leading = "A",
                            onClick = {
                                searchQuery = ""
                                settingsSection = null
                                page = SidebarPage.ACTION_CENTER
                            },
                        )
                    }
                    item { DrawerSectionTitle("도구") }
                    item {
                        DrawerActionRow(title = "Fusion 검색", subtitle = "대화와 자료를 함께 찾습니다.",
                            leading = "S", leadingIcon = Icons.Rounded.Search, onClick = { showFusionSearch = true })
                    }
                    if (currentConversationId > 0) item {
                        DrawerActionRow(title = "대화 갈래", subtitle = "연결된 대화로 이동합니다.",
                            leading = "B", leadingIcon = Icons.Rounded.AccountTree, onClick = { showBranchMap = true })
                    }
                    item {
                        DrawerActionRow(
                            title = "번역",
                            subtitle = "기기에서 텍스트를 번역합니다.",
                            leading = "T",
                            onClick = onOpenTranslation,
                        )
                    }

                    item { Spacer(modifier = Modifier.height(6.dp)) }
                    item { DrawerSectionTitle("프로젝트") }
                    items(projects, key = { DrawerItemKey.project(it.id) }) { project ->
                        val count = projectCounts[project.id] ?: 0
                        DrawerActionRow(
                            title = project.name.ifBlank { "새 프로젝트" },
                            subtitle = "대화 ${count}개",
                            leading = "P",
                            onClick = {
                                selectedProjectId = project.id
                                searchQuery = ""
                                page = SidebarPage.PROJECTS
                            },
                        )
                    }
                    item {
                        DrawerActionRow(
                            title = "새 프로젝트",
                            subtitle = "대화를 묶어 정리합니다.",
                            leading = "+",
                            onClick = {
                                drawerNewProjectName = ""
                                showDrawerNewProject = true
                            },
                        )
                    }

                    item { Spacer(modifier = Modifier.height(6.dp)) }
                    item { DrawerSectionTitle("대화") }
                } else {
                    item { DrawerSectionTitle("검색 결과") }
                }
                if (filteredConversations.isEmpty()) {
                    item { EmptyConversationList(hasSearchQuery = isSearchMode) }
                } else {
                    items(filteredConversations, key = { DrawerItemKey.conversation(it.id) }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            onClick = { openConversation(conversation.id) },
                            menuExpanded = menuConversation?.id == conversation.id,
                            onDismissMenu = { menuConversation = null },
                            onMenuClick = { menuConversation = conversation },
                            onCheckpoints = { showConversationCheckpoints(conversation) },
                            onTogglePinned = {
                                menuConversation = null
                                scope.launch { dao.updateConversationPinned(conversation.id, !conversation.isPinned) }
                            },
                            onRename = {
                                menuConversation = null
                                renameConversation = conversation
                                renameTitle = conversation.title
                            },
                            onRegenerateTitle = {
                                regenerateConversationTitle(conversation)
                            },
                            onArchive = {
                                menuConversation = null
                                scope.launch {
                                    dao.archiveConversation(conversation.id)
                                    if (conversation.id == currentConversationId) {
                                        val nextId = dao.getLatestConversation()?.id
                                        onConversationRemovedFromList(conversation.id, nextId)
                                    }
                                }
                            },
                            onDelete = {
                                menuConversation = null
                                deleteConversation = conversation
                            }
                        )
                    }
                }
                if (!isSearchMode) {
                    item { Spacer(modifier = Modifier.height(6.dp)) }
                    item { DrawerSectionTitle(DrawerNavigationModel.UTILITIES_SECTION) }
                    item {
                        DrawerUtilityRow(
                            title = if (inboxCount > 0) "Inbox · $inboxCount" else "Inbox",
                            subtitle = "나중에 정리할 자료를 보관합니다.",
                            leading = "I",
                            onClick = {
                                settingsSection = null
                                searchQuery = ""
                                page = SidebarPage.INBOX
                            },
                        )
                    }
                    item {
                        DrawerUtilityRow(
                            title = DrawerNavigationModel.LAB_TITLE,
                            subtitle = DrawerNavigationModel.LAB_SUBTITLE,
                            leading = "L",
                            onClick = {
                                settingsSection = null
                                searchQuery = ""
                                page = SidebarPage.LAB
                            },
                        )
                    }
                    item {
                        DrawerUtilityRow(
                            title = "Research 다시 확인",
                            subtitle = "오래된 Research 결과를 다시 확인합니다.",
                            leading = "R",
                            onClick = {
                                settingsSection = null
                                searchQuery = ""
                                page = SidebarPage.RESEARCH_REFRESH
                            },
                        )
                    }
                    item {
                        DrawerUtilityRow(
                            title = DrawerNavigationModel.ARCHIVE_TITLE,
                            subtitle = DrawerNavigationModel.ARCHIVE_SUBTITLE,
                            leading = "A",
                            onClick = { openArchiveWithAuth() },
                        )
                    }
                    item {
                        DrawerUtilityRow(
                            title = DrawerNavigationModel.SETTINGS_TITLE,
                            subtitle = DrawerNavigationModel.SETTINGS_SUBTITLE,
                            leading = "S",
                            onClick = {
                                archiveUnlockedForSession = false
                                settingsSection = null
                                searchQuery = ""
                                page = SidebarPage.SETTINGS
                            },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(6.dp)) }
                }
            }

            if (showDrawerNewProject) {
                ProjectNameDialog(
                    title = "새 프로젝트",
                    initial = drawerNewProjectName,
                    onDismiss = { showDrawerNewProject = false },
                    onConfirm = { name ->
                        showDrawerNewProject = false
                        scope.launch {
                            when (ProjectCoordinator(
                                projectDao,
                                dao,
                                runInTransaction = { block -> db.withTransaction { block() } },
                            ).createProject(name, null)) {
                                is ProjectOutcome.Ok ->
                                    Toast.makeText(context, "프로젝트를 만들었습니다.", Toast.LENGTH_SHORT).show()
                                is ProjectOutcome.Failure ->
                                    Toast.makeText(context, "프로젝트를 만들지 못했습니다.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
        }
        return
    }

    if (!isSearchMode && page == SidebarPage.INBOX) {
        FusionInboxScreen(
            database = db,
            chatViewModel = chatViewModel,
            conversationId = currentConversationId,
            includeArchived = { ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession) },
            onBack = { page = SidebarPage.HOME },
            onCloseDrawer = { page = SidebarPage.HOME; closeDrawer() },
        )
        return
    }

    if (!isSearchMode && page == SidebarPage.RESEARCH_REFRESH) {
        ResearchRefreshQueueSurface(
            database = db,
            includeArchived = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession),
            requestedTarget = requestedResearchRefreshTarget,
            onBack = { page = SidebarPage.HOME },
            onPrepareRefresh = { candidate ->
                val authorizedSession = archiveUnlockedForSession
                archiveUnlockedForSession = false
                onStartResearchRefresh(
                    candidate.sourceConversationId,
                    candidate.sourceMessageId,
                    authorizedSession,
                )
            },
        )
        return
    }

    if (!isSearchMode && page == SidebarPage.ACTION_CENTER) {
        WorkspaceActionCenterSurface(
            database = db,
            onBack = { page = SidebarPage.HOME },
            onOpenProjectPlan = { target ->
                scope.launch {
                    val valid = withContext(Dispatchers.IO) {
                        val project = db.projectDao().getProjectById(target.projectId)
                        val plan = db.projectWorkPlanDao().getPlanById(target.planId)
                        val step = db.projectWorkPlanDao().getStep(target.stepId, target.planId)
                        com.projectnuke.fusion.workspace.WorkspaceActionTargetPolicy.canOpenWorkPlan(
                            target,
                            projectExists = project?.id == target.projectId,
                            planProjectId = plan?.projectId,
                            stepPlanId = step?.planId,
                        )
                    }
                    if (!valid) {
                        Toast.makeText(context, "작업 계획을 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    selectedProjectId = target.projectId
                    openProjectWorkPlanId = target.planId
                    openProjectWorkPlanStepId = target.stepId
                    page = SidebarPage.PROJECTS
                }
            },
            onOpenTask = { target ->
                scope.launch {
                    val task = withContext(Dispatchers.IO) { db.projectTaskDao().getById(target.taskId) }
                    val projectExists = withContext(Dispatchers.IO) {
                        db.projectDao().getProjectById(target.projectId)?.id == target.projectId
                    }
                    if (!com.projectnuke.fusion.workspace.WorkspaceActionTargetPolicy.canOpenTask(
                            target, projectExists, task?.id, task?.projectId,
                        )
                    ) {
                        Toast.makeText(context, "할 일을 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    selectedProjectId = target.projectId
                    requestedProjectTaskTarget = target
                    page = SidebarPage.PROJECTS
                }
            },
            onOpenReviewCard = { cardId ->
                openReviewCardId = cardId
            },
            onOpenInbox = { page = SidebarPage.INBOX },
            onOpenResearchRefresh = { conversationId, messageId ->
                requestedResearchRefreshTarget = conversationId to messageId
                page = SidebarPage.RESEARCH_REFRESH
            },
            includeArchived = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession),
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalFusionSemanticColors.current.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!isSearchMode) {
            item {
                when (page) {
                    SidebarPage.SETTINGS -> if (settingsSection == null) {
                        DrawerPageTopBar("설정", onBack = { leaveArchive() })
                    }
                    SidebarPage.LAB -> DrawerPageTopBar(DrawerNavigationModel.LAB_TITLE, onBack = { leaveArchive() })
                    SidebarPage.ARCHIVE -> DrawerPageTopBar("아카이브", onBack = { leaveArchive() })
                    SidebarPage.PROMPT_LAB -> Unit
                    SidebarPage.BENCHMARK -> DrawerPageTopBar("벤치마크", onBack = { leaveArchive() })
                    SidebarPage.RESEARCH_REFRESH -> DrawerPageTopBar("Research 다시 확인", onBack = { page = SidebarPage.HOME })
                    else -> {}
                }
            }
        }

        if (page == SidebarPage.HOME || page == SidebarPage.ARCHIVE) {
            item {
                if (!isSearchMode) Spacer(modifier = Modifier.height(6.dp))
                DrawerSearchBox(value = searchQuery, onValueChange = { searchQuery = it })
            }
        }

        if (isSearchMode) {
            if (page == SidebarPage.ARCHIVE && !ArchiveAccessPolicy.canRenderArchive(
                    archiveLockEnabled,
                    archiveUnlockedForSession,
                )
            ) {
                item { EmptyConversationList(hasSearchQuery = true) }
            } else {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    DrawerSectionTitle("검색 결과")
                }
                if (filteredConversations.isEmpty()) {
                    item { EmptyConversationList(hasSearchQuery = true) }
                } else {
                    val archiveAction = conversationRowArchiveAction(searchDomain)
                    items(filteredConversations, key = { DrawerItemKey.conversation(it.id) }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            onClick = { openConversation(conversation.id) },
                            menuExpanded = menuConversation?.id == conversation.id,
                            onDismissMenu = { menuConversation = null },
                            onMenuClick = { menuConversation = conversation },
                            onCheckpoints = { showConversationCheckpoints(conversation) },
                            onTogglePinned = {
                                menuConversation = null
                                scope.launch { dao.updateConversationPinned(conversation.id, !conversation.isPinned) }
                            },
                            onRename = {
                                menuConversation = null
                                renameConversation = conversation
                                renameTitle = conversation.title
                            },
                            onRegenerateTitle = {
                                regenerateConversationTitle(conversation)
                            },
                            onArchive = {
                                menuConversation = null
                                scope.launch {
                                    if (archiveAction.restores) {
                                        dao.setConversationArchived(conversation.id, false)
                                    } else {
                                        dao.archiveConversation(conversation.id)
                                        if (conversation.id == currentConversationId) {
                                            val nextId = dao.getLatestConversation()?.id
                                            onConversationRemovedFromList(conversation.id, nextId)
                                        }
                                    }
                                }
                            },
                            onDelete = {
                                menuConversation = null
                                deleteConversation = conversation
                            },
                            archiveMenuLabel = archiveAction.menuLabel,
                        )
                    }
                }
            }
        } else {
            when (page) {
                SidebarPage.HOME -> {}
                SidebarPage.ACTION_CENTER -> {}
                SidebarPage.PROJECTS -> {}
                SidebarPage.INBOX -> {}
                SidebarPage.RESEARCH_REFRESH -> {}
                SidebarPage.LAB -> {
                    // Lab is information architecture only: it groups the two
                    // advanced experimental tools behind one drawer entry. Each
                    // destination opens the existing screen with its existing
                    // behavior unchanged.
                    item { DrawerSectionTitle(DrawerNavigationModel.LAB_SUBTITLE) }
                    items(DrawerNavigationModel.labDestinations, key = { it.name }) { destination ->
                        DrawerActionRow(
                            title = destination.title,
                            subtitle = destination.subtitle,
                            leading = destination.leading,
                            onClick = {
                                when (destination) {
                                    LabDestination.BENCHMARK -> {
                                        settingsSection = null
                                        searchQuery = ""
                                        pendingBenchmarkModelFilter = null
                                        pendingBenchmarkOpenHistory = false
                                        benchmarkOrigin = BenchmarkOrigin.LAB
                                        page = SidebarPage.BENCHMARK
                                    }
                                    LabDestination.PROMPT_LAB -> {
                                        promptLabOrigin = PromptLabOrigin.LAB
                                        settingsSection = null
                                        searchQuery = ""
                                        page = SidebarPage.PROMPT_LAB
                                    }
                                }
                            },
                        )
                    }
                }
                SidebarPage.ARCHIVE -> {
                    if (!ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)) {
                        item { EmptyConversationList(hasSearchQuery = false) }
                    } else {
                        item { DrawerSectionTitle("보관한 대화를 다시 열거나 복원할 수 있습니다.") }
                        if (archivedConversations.isEmpty()) {
                            item { EmptyArchiveList() }
                        } else {
                        items(archivedConversations, key = { DrawerItemKey.conversation(it.id) }) { conversation ->
                            ConversationRow(
                                conversation = conversation,
                                onClick = { openConversation(conversation.id) },
                                menuExpanded = menuConversation?.id == conversation.id,
                                onDismissMenu = { menuConversation = null },
                                onMenuClick = { menuConversation = conversation },
                                onCheckpoints = { showConversationCheckpoints(conversation) },
                                onTogglePinned = {
                                    menuConversation = null
                                    scope.launch { dao.updateConversationPinned(conversation.id, !conversation.isPinned) }
                                },
                                onRename = {
                                    menuConversation = null
                                    renameConversation = conversation
                                    renameTitle = conversation.title
                                },
                                onRegenerateTitle = {
                                    regenerateConversationTitle(conversation)
                                },
                                onArchive = {
                                    menuConversation = null
                                    scope.launch { dao.setConversationArchived(conversation.id, false) }
                                },
                                onDelete = {
                                    menuConversation = null
                                    deleteConversation = conversation
                                },
                                archiveMenuLabel = "복원",
                                pinMenuLabel = if (conversation.isPinned) "고정 해제" else "채팅 고정"
                            )
                        }
                        }
                    }
                }

                SidebarPage.SETTINGS -> {
                    val modelName = prefs.getString(LocalModelSelectedPreference, DefaultLocalModelDisplayName) ?: DefaultLocalModelDisplayName
                    val modelPath = prefs.getString(LocalModelSelectedPathPreference, null)

                    if (settingsSection == null) {
                        item { DrawerSectionTitle("설정") }
                        items(SettingsSection.entries, key = { it.name }) { section ->
                            DrawerSettingCategoryRow(
                                title = section.title,
                                subtitle = section.subtitle,
                                onClick = { settingsSection = section }
                            )
                        }
                    } else {
                        item {
                            DrawerPageTopBar(settingsSection?.title.orEmpty()) {
                                settingsSection = null
                            }
                        }
                    }

                    if (settingsSection == SettingsSection.APPEARANCE) {
                        item {
                            val mode = readFusionThemeMode(context)
                            DrawerSettingActionRow("앱 테마", "현재: ${mode.label}") {
                                showAppearanceDialog = true
                            }
                        }
                    }

                    if (settingsSection == SettingsSection.MODELS_AI) {
                    item {
                        DrawerSettingActionRow(
                            "외부 AI API 설정",
                            "OpenAI 호환 API 키와 Base URL을 기기 내에 저장합니다."
                        ) {
                            showAiProviderSettingsDialog = true
                        }
                    }

                    item { DrawerSectionTitle("모델") }
                    item {
                        DrawerSettingActionRow("모델 라이브러리", "모델을 선택합니다.") {
                            if (onOpenModelLibrary != null) onOpenModelLibrary()
                            else Toast.makeText(context, "아직 준비 중입니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    item {
                        DrawerSettingActionRow(
                            "모델 호환성 가이드",
                            "모델군별 특징과 실행 기준을 확인합니다."
                        ) {
                            showModelCompatibilityGuide = true
                        }
                    }
                    item {
                        val settingsReadiness = remember(localModelStorageRevision, localModelSettingsRefreshKey, modelName, modelPath) {
                            resolveLocalModelReadinessTruth(context, prefs, modelName, modelPath)
                        }
                        val settingsChipLabel = resolveLocalModelChipLabel(modelName, settingsReadiness.isRunnable)
                        val settingsDisplayPath = settingsReadiness.finalRunnablePath
                        DrawerSettingActionRow(
                            "기본 모델",
                            settingsDisplayPath?.let { "$modelName\n$it" } ?: settingsChipLabel
                        ) {
                            if (onOpenModelLibrary != null) onOpenModelLibrary()
                            else Toast.makeText(context, "모델 선택 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    item {
                        // Effective settings truth: a per-model override or
                        // model recommendation must win over the stored global
                        // values in every "current" settings display.
                        val effectiveProfile = remember(localModelStorageRevision, localModelSettingsRefreshKey, modelName, modelPath) {
                            resolveEffectiveLocalModelProfile(context, prefs, modelName, modelPath)
                        }
                        DrawerSettingInfoRow("가속기", effectiveProfile.generationSettings.accelerator.name)
                    }
                    }

                    if (settingsSection == SettingsSection.GENERAL) {
                    item { DrawerSectionTitle("응답 설정") }
                    item {
                        // Same effective-profile truth as the accelerator row.
                        val effectiveProfile = remember(localModelStorageRevision, localModelSettingsRefreshKey, modelName, modelPath) {
                            resolveEffectiveLocalModelProfile(context, prefs, modelName, modelPath)
                        }
                        val settings = effectiveProfile.generationSettings
                        DrawerSettingActionRow(
                            "고급 설정",
                            "최대 출력 ${settings.maxTokens} · 온도 ${settings.temperature} · Top-K ${settings.topK} · Top-P ${settings.topP}"
                        ) {
                            if (onOpenAdvancedSettings != null) onOpenAdvancedSettings()
                            else Toast.makeText(context, "아직 준비 중입니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    item {
                        val effectiveProfile = remember(localModelStorageRevision, localModelSettingsRefreshKey, modelName, modelPath) {
                            resolveEffectiveLocalModelProfile(context, prefs, modelName, modelPath)
                        }
                        DrawerSettingToggleRow(
                            title = "추론",
                            subtitle = "답변 전 추론 과정을 사용합니다.",
                            checked = effectiveProfile.reasoningEnabled,
                            onToggle = { enabled ->
                                if (LocalModelSettingsProfileStore(prefs).writeModelReasoning(effectiveProfile.modelId, enabled)) {
                                    localModelSettingsRefreshKey += 1
                                } else {
                                    Toast.makeText(context, "설정을 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    item {
                        DrawerSettingToggleRow(
                            title = "웹 검색",
                            subtitle = "최신 정보가 필요한 질문에서 인터넷 검색을 사용합니다.",
                            checked = webSearchEnabled,
                            onToggle = {
                                webSearchEnabled = it
                                saveSetting("web_search_enabled", it)
                            }
                        )
                    }
                    item {
                        val effectiveProfile = remember(localModelStorageRevision, localModelSettingsRefreshKey, modelName, modelPath) {
                            resolveEffectiveLocalModelProfile(context, prefs, modelName, modelPath)
                        }
                        val settings = effectiveProfile.generationSettings
                        DrawerSettingToggleRow(
                            title = "MTP 가속",
                            subtitle = "지원 모델에서 speculative decoding으로 출력 속도를 높입니다.",
                            checked = settings.speculativeDecodingEnabled == true,
                            onToggle = { enabled ->
                                if (LocalModelSettingsProfileStore(prefs).writeModelMtp(effectiveProfile.modelId, enabled)) {
                                    localModelSettingsRefreshKey += 1
                                } else {
                                    Toast.makeText(context, "설정을 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    item {
                        DrawerSettingToggleRow(
                            title = "추천 질문 자동 생성",
                            subtitle = "로컬 모델이 준비되어 있으면 답변 뒤에 관련 질문을 만들어요.",
                            checked = followUpAutoGenerate,
                            onToggle = {
                                followUpAutoGenerate = it
                                saveSetting(com.projectnuke.fusion.chat.DynamicFollowUpPreference.KEY_AUTO_GENERATE, it)
                            }
                        )
                    }
                    }

                    if (settingsSection == SettingsSection.PRIVACY_DATA) {
                    item { DrawerSectionTitle("데이터") }
                    item { DrawerSettingActionRow("아카이브", "보관한 채팅을 확인하고 복원합니다.") { openArchiveWithAuth() } }
                    item {
                        DrawerSettingToggleRow(
                            title = "아카이브 잠금",
                            subtitle = "보관된 채팅을 열 때 생체 인증 또는 기기 잠금 인증을 요구합니다.",
                            checked = archiveLockEnabled,
                            onToggle = { enabled ->
                                if (!enabled) {
                                    archiveLockEnabled = false
                                    archiveUnlockedForSession = false
                                    prefs.edit().putBoolean(ArchiveLockPreferenceKey, false).apply()
                                    Toast.makeText(context, "아카이브 잠금을 해제했습니다.", Toast.LENGTH_SHORT).show()
                                } else if (!isArchiveAuthenticationAvailable(context)) {
                                    archiveLockEnabled = false
                                    archiveUnlockedForSession = false
                                    prefs.edit().putBoolean(ArchiveLockPreferenceKey, false).apply()
                                    Toast.makeText(context, "이 기기에서는 아카이브 잠금을 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
                                } else {
                                    authenticateArchiveAccess(
                                        context = context,
                                        onSuccess = {
                                            archiveLockEnabled = true
                                            archiveUnlockedForSession = false
                                            prefs.edit().putBoolean(ArchiveLockPreferenceKey, true).apply()
                                            Toast.makeText(context, "아카이브 잠금을 사용합니다.", Toast.LENGTH_SHORT).show()
                                        },
                                        onCanceled = {
                                            archiveLockEnabled = false
                                            archiveUnlockedForSession = false
                                            prefs.edit().putBoolean(ArchiveLockPreferenceKey, false).apply()
                                            Toast.makeText(context, "인증이 취소되었습니다.", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = {
                                            archiveLockEnabled = false
                                            archiveUnlockedForSession = false
                                            prefs.edit().putBoolean(ArchiveLockPreferenceKey, false).apply()
                                            Toast.makeText(context, "아카이브 잠금을 설정할 수 없습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        )
                    }
                    item {
                        DrawerSettingActionRow("첨부 파일 저장 공간", "이미지와 파일 캐시를 관리합니다.") {
                            showAttachmentStorageDialog = true
                            attachmentStorageLoading = true
                            scope.launch {
                                attachmentStorageStats = runCatching {
                                    AttachmentStorageManager.calculateAttachmentStorageStats(context, dao, projectDao)
                                }.getOrNull()
                                attachmentStorageLoading = false
                            }
                        }
                    }
                    item {
                        DrawerSettingActionRow("설정 백업 및 복원", "모델, 생성 옵션, 모델 라이브러리 설정을 백업하거나 복원합니다.") {
                            showSettingsBackupDialog = true
                        }
                    }
                    item {
                        DrawerSettingActionRow("채팅 Markdown 내보내기", "선택한 채팅을 Markdown 파일로 저장합니다.") {
                            showChatMarkdownExportPicker = true
                        }
                    }
                    item {
                        DrawerSettingActionRow("메모리 관리", "저장된 메모리와 대화 요약을 확인하고 관리합니다.") {
                            showMemoryManagerDialog = true
                        }
                    }
                    }

                    if (settingsSection == SettingsSection.ADVANCED_ABOUT) {
                    item { DrawerSectionTitle("실험 및 진단") }
                    item { DrawerSettingActionRow("벤치마크", "TTFT, 토큰 속도, 메모리 사용량을 측정합니다.") {
                        settingsSection = null
                        pendingBenchmarkModelFilter = null
                        pendingBenchmarkOpenHistory = false
                        // Settings entry is not Lab-origin: keep the existing
                        // HOME-return back behavior.
                        benchmarkOrigin = BenchmarkOrigin.OTHER
                        page = SidebarPage.BENCHMARK
                    } }
                    item {
                        DrawerSettingActionRow("모델 A/B 테스트", "같은 프롬프트로 모델과 설정을 비교합니다.") {
                            showModelAbTestLab = true
                        }
                    }
                    item {
                        DrawerSettingActionRow("프롬프트 프리셋", "자주 쓰는 요청 문구를 확인하고 복사합니다.") {
                            showPromptPresetsDialog = true
                        }
                    }
                    item {
                        DrawerSettingActionRow("실험 노트", "모델 테스트와 벤치마크 메모를 기록합니다.") {
                            showExperimentNotesDialog = true
                        }
                    }
                    item { DrawerSettingActionRow("Prompt Lab", "시스템 프롬프트와 응답 스타일을 테스트합니다.") {
                        promptLabOrigin = PromptLabOrigin.SETTINGS
                        settingsSection = null
                        page = SidebarPage.PROMPT_LAB
                    } }
                    if (developerModeEnabled) {
                        item { DrawerPlannedRow("에이전트 모드", "기기 제어 실험 기능입니다.", "A", container = DrawerCardBg) }
                    }

                    item { Spacer(modifier = Modifier.height(6.dp)); DrawerSectionTitle("앱 정보") }
                    item {
                        DrawerSettingActionRow("사용 가이드", "Fusion의 주요 기능과 사용 방법을 확인합니다.") {
                            showHelpDialog = true
                        }
                    }
                    item {
                        DrawerSettingActionRow("시작하기 다시 보기", "처음 시작 안내를 다시 확인합니다.") {
                            onReopenOnboarding?.invoke()
                        }
                    }
                    item {
                        DrawerSettingActionRow("개인정보 및 데이터 안내", "Fusion이 저장하거나 내보내는 데이터를 확인합니다.") {
                            showPrivacyDataGuideDialog = true
                        }
                    }
                    item {
                        DrawerSettingActionRow("GitHub 이슈 제보", "버그, 개선 요청, 기능 제안을 GitHub Issues에 남깁니다.") {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(FUSION_GITHUB_ISSUES_URL)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }.onFailure {
                                Toast.makeText(context, "GitHub 이슈 페이지를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    if (developerModeEnabled) {
                        item {
                            DrawerSettingActionRow("언어(Language)", getFusionAppLanguageLabel(appLanguage)) {
                                showLanguageDialog = true
                            }
                        }
                    }
                    item {
                        DrawerSettingActionRow(
                            title = "앱 정보",
                            subtitle = "버전, 모델 상태, 데이터 정보를 확인합니다.",
                            modifier = Modifier.pointerInput(developerModeEnabled) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    val releasedEarly = withTimeoutOrNull(7_000L) {
                                        waitForUpOrCancellation()
                                    }
                                    if (releasedEarly == null && !developerModeEnabled) {
                                        setFusionDeveloperModeEnabled(context, true)
                                        developerModeEnabled = true
                                        Toast.makeText(context, "개발자 모드를 사용하도록 설정했습니다.", Toast.LENGTH_SHORT).show()
                                        waitForUpOrCancellation()
                                    }
                                }
                            }
                        ) {
                            showAppInfoDialog = true
                        }
                    }
                    item {
                        DrawerSettingActionRow("Fusion 상태 점검", "모델, 설정, 저장소, 메모리, 권한 상태를 확인합니다.") {
                            showFusionHealthDialog = true
                        }
                    }
                    item {
                        DrawerSettingActionRow("상태 대시보드", "현재 모델, 메모리, 벤치마크, 앱 상태를 한눈에 확인합니다.") {
                            showStatusDashboardDialog = true
                        }
                    }
                    item {
                        DrawerSettingActionRow("기기 정보", "기기 메모리와 AP 정보를 확인합니다.") {
                            showDeviceInfoDialog = true
                        }
                    }
                    item {
                        DrawerSettingActionRow("문제 해결 가이드", "모델 실행, 메모리, 설치, 로그 문제 해결 방법을 확인합니다.") {
                            showTroubleshootingGuideDialog = true
                        }
                    }
                    item {
                        DrawerSettingActionRow("업데이트 기록", "최근 추가된 기능과 변경사항을 확인합니다.") {
                            showReleaseNotesDialog = true
                        }
                    }
                    if (developerModeEnabled) {
                        item {
                            DrawerSettingActionRow("개발자 로그", "최근 모델, 메모리, 오류, 벤치마크 상태를 확인합니다.") {
                                showDeveloperLogDialog = true
                            }
                        }
                        item {
                            DrawerSettingActionRow("디버그 정보 복사", "모델, 설정, 기기, 최근 상태 정보를 클립보드에 복사합니다.") {
                                scope.launch {
                                    try {
                                        // Effective truth for the copied
                                        // diagnostics text.
                                        val debugProfile = resolveEffectiveLocalModelProfile(context, prefs, modelName, modelPath)
                                        val debugSettings = debugProfile.generationSettings
                                        val debugText = buildFusionDebugInfo(
                                            context = context,
                                            dao = dao,
                                            projectDao = projectDao,
                                            benchmarkDao = db.benchmarkDao(),
                                            currentConversationId = currentConversationId,
                                            modelName = modelName,
                                            modelPath = modelPath,
                                            accelerator = debugSettings.accelerator.name,
                                            maxTokens = debugSettings.maxTokens,
                                            topK = debugSettings.topK,
                                            topP = debugSettings.topP,
                                            temperature = debugSettings.temperature,
                                            reasoningBudgetTokens = debugSettings.reasoningBudgetTokens,
                                            reasoningEnabled = debugProfile.reasoningEnabled,
                                            webSearchEnabled = webSearchEnabled,
                                            speculativeEnabled = debugSettings.speculativeDecodingEnabled == true
                                        )
                                        clipboard.setText(AnnotatedString(debugText))
                                        Toast.makeText(context, "디버그 정보를 복사했습니다.", Toast.LENGTH_SHORT).show()
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "디버그 정보 복사 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                    if (developerModeEnabled) {
                        item { DrawerSectionTitle("개발자 항목") }
                        item {
                            DrawerSettingActionRow("릴리즈 체크리스트", "빌드와 실기기 테스트 항목을 확인합니다.") {
                                showReleaseChecklistDialog = true
                            }
                        }
                        item {
                            DrawerSettingActionRow("개발자 모드", "릴리즈 체크리스트를 표시합니다.") {
                                showDeveloperModeDialog = true
                            }
                        }
                    }
                    }
                }
                SidebarPage.PROMPT_LAB -> {
                    item {
                        PromptLabScreen(onBack = {
                            when (promptLabOrigin) {
                                PromptLabOrigin.HOME -> leaveArchive()
                                PromptLabOrigin.SETTINGS -> {
                                    page = SidebarPage.SETTINGS
                                    settingsSection = SettingsSection.ADVANCED_ABOUT
                                    searchQuery = ""
                                }
                                PromptLabOrigin.LAB -> {
                                    settingsSection = null
                                    searchQuery = ""
                                    page = SidebarPage.LAB
                                }
                            }
                        })
                    }
                }
                SidebarPage.BENCHMARK -> {
                    // Rendered above outside this LazyColumn to avoid nested vertical scrolling.
                }
            }
        }
    }

    pendingSettingsRestoreJson?.let { raw ->
        AlertDialog(
            onDismissRequest = { pendingSettingsRestoreJson = null },
            title = { Text("설정을 복원하시겠습니까?") },
            text = { Text("현재 설정이 백업 파일의 값으로 변경됩니다. 채팅 기록과 모델 파일은 삭제되지 않습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val restoreResult = restoreSettingsBackupJson(context, prefs, raw)
                        when (restoreResult) {
                            SettingsRestoreResult.Success -> {
                                Toast.makeText(context, "설정을 복원했습니다.", Toast.LENGTH_SHORT).show()
                                Toast.makeText(context, "일부 설정은 화면을 다시 열면 반영됩니다.", Toast.LENGTH_SHORT).show()
                            }
                            SettingsRestoreResult.ModelPathMissing -> {
                                Toast.makeText(context, "설정을 복원했습니다.", Toast.LENGTH_SHORT).show()
                                Toast.makeText(context, "백업의 모델 파일을 찾을 수 없어 현재 모델을 유지했습니다.", Toast.LENGTH_SHORT).show()
                            }
                            SettingsRestoreResult.InvalidJson -> {
                                Toast.makeText(context, "설정 파일을 읽을 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                            SettingsRestoreResult.CommitFailed -> {
                                Toast.makeText(context, "설정을 저장하지 못했습니다. 저장공간 또는 시스템 상태를 확인한 뒤 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                            }
                            SettingsRestoreResult.TooLarge,
                            SettingsRestoreResult.InvalidFields,
                            SettingsRestoreResult.UnsupportedSchema -> {
                                Toast.makeText(context, "지원하지 않는 설정 백업 형식입니다.", Toast.LENGTH_SHORT).show()
                            }
                        }
                        if (restoreResult == SettingsRestoreResult.Success || restoreResult == SettingsRestoreResult.ModelPathMissing) {
                            localModelSettingsRefreshKey += 1
                            webSearchEnabled = prefs.getBoolean("web_search_enabled", webSearchEnabled)
                            followUpAutoGenerate =
                                com.projectnuke.fusion.chat.DynamicFollowUpPreference.isAutoGenerateEnabled(prefs)
                        }
                        pendingSettingsRestoreJson = null
                    }
                }) { Text("복원", color = DrawerAccentBlue) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSettingsRestoreJson = null }) {
                    Text("취소", color = DrawerTextSecondary)
                }
            },
            containerColor = DrawerPanelBg,
            titleContentColor = DrawerTextPrimary,
            textContentColor = DrawerTextPrimary
        )
    }

    if (showSettingsBackupDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsBackupDialog = false },
            title = { Text("설정 백업 및 복원") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("채팅 기록과 모델 파일은 포함되지 않습니다.", color = DrawerTextSecondary, fontSize = 12.sp)
                    Text("모델 메모가 백업에 포함될 수 있습니다.", color = DrawerTextSecondary, fontSize = 12.sp)
                    Text("저장된 메모리와 대화 요약 내용은 설정 백업에 포함되지 않습니다.", color = DrawerTextSecondary, fontSize = 12.sp)
                    Text("A/B 테스트 기록은 설정 백업에 포함되지 않습니다.", color = DrawerTextSecondary, fontSize = 12.sp)
                    Text("실험 노트 내용은 설정 백업에 포함되지 않습니다.", color = DrawerTextSecondary, fontSize = 12.sp)
                    DrawerSettingActionRow("설정 내보내기", "JSON 파일로 저장합니다.") {
                        settingsBackupExportLauncher.launch("fusion-settings-backup.json")
                    }
                    DrawerSettingActionRow("설정 가져오기", "백업 JSON 파일에서 복원합니다.") {
                        settingsBackupImportLauncher.launch(arrayOf("application/json", "text/*"))
                    }
                    DrawerSettingActionRow("설정 JSON 복사", "백업 JSON을 클립보드에 복사합니다.") {
                        clipboard.setText(AnnotatedString(buildSettingsBackupJson(context, prefs)))
                        Toast.makeText(context, "설정 JSON을 복사했습니다.", Toast.LENGTH_SHORT).show()
                        Log.d("FusionModelSelect", "settings_export schema=$FusionSettingsBackupSchemaVersion keys=settings,modelLibrary success=true")
                    }
                    DrawerSettingActionRow("클립보드에서 복원", "클립보드의 백업 JSON을 복원합니다.") {
                        val raw = clipboard.getText()?.text?.toString()
                        if (raw.isNullOrBlank()) {
                            Toast.makeText(context, "설정 파일을 읽을 수 없습니다.", Toast.LENGTH_SHORT).show()
                        } else {
                            pendingSettingsRestoreJson = raw
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsBackupDialog = false }) {
                    Text("확인", color = DrawerAccentBlue)
                }
            },
            containerColor = DrawerPanelBg,
            titleContentColor = DrawerTextPrimary,
            textContentColor = DrawerTextPrimary
        )
    }

    if (showChatMarkdownExportPicker) {
        val archiveExportAuthorized = ArchiveAccessPolicy.canIncludeArchivedInExport(
            archiveLockEnabled,
            archiveUnlockedForSession,
        )
        val exportConversations = remember(
            conversations,
            archivedConversations,
            includeArchivedInMarkdownExport,
            archiveExportAuthorized,
            markdownExportSearchQuery
        ) {
            val base = if (includeArchivedInMarkdownExport && archiveExportAuthorized) {
                conversations + archivedConversations
            } else {
                conversations
            }
            val query = markdownExportSearchQuery.trim()
            if (query.isBlank()) base else base.filter { it.title.contains(query, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = {
                showChatMarkdownExportPicker = false
                markdownExportSearchQuery = ""
            },
            title = { Text("내보낼 채팅 선택") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Markdown 파일로 저장할 채팅을 선택해 주세요.", color = DrawerTextSecondary, fontSize = 12.sp)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DrawerCardBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            if (markdownExportSearchQuery.isBlank()) {
                                Text("채팅을 검색합니다.", color = DrawerTextSecondary, fontSize = 13.sp)
                            }
                            BasicTextField(
                                value = markdownExportSearchQuery,
                                onValueChange = { markdownExportSearchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(color = DrawerTextPrimary, fontSize = 13.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("아카이브 포함", color = DrawerTextPrimary, fontSize = 12.sp)
                        Switch(
                            checked = includeArchivedInMarkdownExport && archiveExportAuthorized,
                            enabled = archiveExportAuthorized,
                            onCheckedChange = { includeArchivedInMarkdownExport = it }
                        )
                    }
                    if (exportConversations.isEmpty()) {
                        Text("내보낼 채팅이 없습니다.", color = DrawerTextSecondary, fontSize = 12.sp)
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(exportConversations, key = { DrawerItemKey.conversation(it.id) }) { conversation ->
                                Surface(
                                    color = DrawerCardBg,
                                    shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !markdownExportBusy) {
                                                scope.launch {
                                                if (!ArchiveAccessPolicy.canAccessConversation(
                                                        isArchived = conversation.isArchived,
                                                        archiveLockEnabled = archiveLockEnabled,
                                                        archiveUnlockedForSession = archiveUnlockedForSession,
                                                    )
                                                ) {
                                                    Toast.makeText(context, "잠긴 아카이브는 내보낼 수 없습니다.", Toast.LENGTH_SHORT).show()
                                                    return@launch
                                                }
                                                val messages = try {
                                                    dao.getMessagesForConversation(conversation.id)
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (_: Exception) {
                                                    Toast.makeText(context, "채팅을 내보낼 수 없습니다.", Toast.LENGTH_SHORT).show()
                                                    return@launch
                                                }
                                                if (messages.isEmpty()) {
                                                    Toast.makeText(context, "이 채팅에는 내보낼 메시지가 없습니다.", Toast.LENGTH_SHORT).show()
                                                    return@launch
                                                }
                                                val title = conversation.title.ifBlank { "새 대화" }
                                                if (!markdownExportCanStart(markdownExportBusy)) return@launch
                                                markdownExportBusy = true
                                                val generation = markdownExportGeneration + 1L
                                                markdownExportGeneration = generation
                                                pendingChatMarkdownExport = try {
                                                    withContext(Dispatchers.IO) {
                                                        pendingMarkdownStore.stage(buildChatExportMarkdown(context, title, messages))
                                                    }
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (_: Exception) {
                                                    pendingChatMarkdownExport = null
                                                    markdownExportBusy = false
                                                    Toast.makeText(context, "채팅 Markdown을 생성할 수 없습니다.", Toast.LENGTH_SHORT).show()
                                                    null
                                                }
                                                if (pendingChatMarkdownExport == null) {
                                                    showChatMarkdownExportPicker = false
                                                    markdownExportSearchQuery = ""
                                                    return@launch
                                                }
                                                try {
                                                    chatMarkdownExportLauncher.launch("fusion-chat-${safeMarkdownExportTitle(title)}.md")
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (_: Exception) {
                                                    pendingChatMarkdownExport?.let { token ->
                                                        withContext(Dispatchers.IO) { pendingMarkdownStore.remove(token) }
                                                    }
                                                    pendingChatMarkdownExport = null
                                                    markdownExportBusy = false
                                                    Toast.makeText(context, "채팅 Markdown을 내보낼 수 없습니다.", Toast.LENGTH_SHORT).show()
                                                }
                                                showChatMarkdownExportPicker = false
                                                markdownExportSearchQuery = ""
                                            }
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                        Text(
                                            text = exportConversationTitle(conversation),
                                            color = DrawerTextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = SimpleDateFormat(
                                                "yyyy-MM-dd HH:mm",
                                                LocalLocale.current.platformLocale,
                                            ).format(Date(conversation.updatedAt)),
                                            color = DrawerTextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showChatMarkdownExportPicker = false
                    markdownExportSearchQuery = ""
                }) {
                    Text("취소")
                }
            },
            containerColor = DrawerPanelBg,
            titleContentColor = DrawerTextPrimary,
            textContentColor = DrawerTextPrimary
        )
    }

    if (showMarkdownCopyFailureDialog) {
        val token = pendingChatMarkdownExport
        val dismissCopyFailure: () -> Unit = {
            showMarkdownCopyFailureDialog = false
            pendingChatMarkdownExport = null
            markdownExportBusy = false
            scope.launch {
                token?.let { withContext(Dispatchers.IO) { pendingMarkdownStore.remove(it) } }
            }
            Unit
        }
        AlertDialog(
            onDismissRequest = dismissCopyFailure,
            title = { Text("파일 저장에 실패했습니다.") },
            text = { Text("채팅 Markdown을 클립보드에 복사할까요?") },
            confirmButton = {
                TextButton(
                    enabled = token != null && !markdownCopyInProgress,
                    onClick = {
                        val exportToken = token ?: return@TextButton
                        if (markdownCopyInProgress) return@TextButton
                        markdownCopyInProgress = true
                        scope.launch {
                            try {
                                val markdown = withContext(Dispatchers.IO) {
                                    pendingMarkdownStore.read(exportToken)
                                }
                                if (markdown.isNullOrBlank()) {
                                    Toast.makeText(context, "내보낼 내용을 복구하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
                                } else {
                                    clipboard.setText(AnnotatedString(markdown))
                                    Toast.makeText(context, "채팅 Markdown을 복사했습니다.", Toast.LENGTH_SHORT).show()
                                    showMarkdownCopyFailureDialog = false
                                    pendingChatMarkdownExport = null
                                    markdownExportBusy = false
                                    withContext(Dispatchers.IO) { pendingMarkdownStore.remove(exportToken) }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                Toast.makeText(context, "채팅 Markdown을 복사하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
                            } finally {
                                markdownCopyInProgress = false
                            }
                        }
                    },
                ) { Text("복사") }
            },
            dismissButton = {
                TextButton(onClick = dismissCopyFailure) { Text("취소") }
            },
            containerColor = DrawerPanelBg,
            titleContentColor = DrawerTextPrimary,
            textContentColor = DrawerTextPrimary,
        )
    }

    if (showAppInfoDialog) {
        val appInfoSnapshot = resolveFusionRuntimeDiagnosticsSnapshot(context, prefs)
        val readiness = appInfoSnapshot.readiness
        val modelName = readiness.selectedDisplayName
        val modelPath = readiness.finalRunnablePath
        val modelExists = readiness.isRunnable
        val accelerator = appInfoSnapshot.effectiveProfile.generationSettings.accelerator.name
        val mtpEnabled = appInfoSnapshot.effectiveProfile.generationSettings.speculativeDecodingEnabled == true
        val appInfo = rememberAppInfo(context)
        AlertDialog(
            onDismissRequest = { showAppInfoDialog = false },
            title = { Text("앱 정보") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(resolveAppInfoTitle(appInfo.versionName), color = DrawerTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("버전 이름: ${appInfo.versionName}", color = DrawerTextPrimary, fontSize = 13.sp)
                    Text("버전 코드: ${appInfo.versionCode}", color = DrawerTextPrimary, fontSize = 13.sp)
                    Text("기기: ${Build.MANUFACTURER} ${Build.MODEL}", color = DrawerTextPrimary, fontSize = 13.sp)
                    Text("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})", color = DrawerTextPrimary, fontSize = 13.sp)
                    Text("선택 모델: $modelName", color = DrawerTextPrimary, fontSize = 13.sp)
                    Text("모델 경로: ${modelPath ?: "없음"}", color = DrawerTextPrimary, fontSize = 13.sp)
                    Text("모델 파일 존재: ${if (modelExists) "예" else "아니요"}", color = DrawerTextPrimary, fontSize = 13.sp)
                    Text("가속기: $accelerator", color = DrawerTextPrimary, fontSize = 13.sp)
                    Text("MTP 설정: ${if (mtpEnabled) "켜짐" else "꺼짐"}", color = DrawerTextPrimary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("채팅과 설정 데이터는 이 기기에 저장됩니다.", color = DrawerTextSecondary, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppInfoDialog = false }) { Text("확인") }
            },
            containerColor = DrawerPanelBg,
            titleContentColor = DrawerTextPrimary,
            textContentColor = DrawerTextPrimary
        )
    }

    if (showReleaseNotesDialog) {
        ReleaseNotesDialog(
            context = context,
            clipboard = clipboard,
            onDismiss = { showReleaseNotesDialog = false }
        )
    }

    if (showDeveloperLogDialog) {
        val benchmarkResults by db.benchmarkDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
        DeveloperLogDialog(
            context = context,
            prefs = prefs,
            clipboard = clipboard,
            benchmarkResults = benchmarkResults,
            onDismiss = { showDeveloperLogDialog = false }
        )
    }

    if (showStatusDashboardDialog) {
        val benchmarkResults by db.benchmarkDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
        FusionStatusDashboardDialog(
            context = context,
            prefs = prefs,
            clipboard = clipboard,
            benchmarkResults = benchmarkResults,
            onDismiss = { showStatusDashboardDialog = false }
        )
    }

    if (showFusionHealthDialog) {
        FusionHealthCheckDialog(
            context = context,
            prefs = prefs,
            db = db,
            clipboard = clipboard,
            onDismiss = { showFusionHealthDialog = false },
            onOpenModelLibrary = onOpenModelLibrary,
            onOpenAdvancedSettings = onOpenAdvancedSettings
        )
    }

    if (showDeviceInfoDialog) {
        FusionDeviceInfoDialog(
            context = context,
            clipboard = clipboard,
            onDismiss = { showDeviceInfoDialog = false }
        )
    }

    if (showReleaseChecklistDialog) {
        FusionReleaseChecklistDialog(
            context = context,
            clipboard = clipboard,
            onDismiss = { showReleaseChecklistDialog = false }
        )
    }

    if (showPromptPresetsDialog) {
        FusionPromptPresetsDialog(
            context = context,
            clipboard = clipboard,
            onDismiss = { showPromptPresetsDialog = false }
        )
    }

    if (showExperimentNotesDialog) {
        FusionExperimentNotesDialog(
            context = context,
            clipboard = clipboard,
            onDismiss = { showExperimentNotesDialog = false }
        )
    }

    if (showTroubleshootingGuideDialog) {
        FusionTroubleshootingGuideDialog(
            context = context,
            clipboard = clipboard,
            onDismiss = { showTroubleshootingGuideDialog = false }
        )
    }

    if (showPrivacyDataGuideDialog) {
        FusionPrivacyDataGuideDialog(
            context = context,
            clipboard = clipboard,
            onDismiss = { showPrivacyDataGuideDialog = false }
        )
    }

    if (showFusionSearch && isDrawerOpen) {
        FusionSearchSurface(
            includeArchived = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession),
            onDismiss = {
                showFusionSearch = false
                cancelPendingWorkPlanAttempt("단계 진행을 취소했습니다. 계획에서 다시 시작할 수 있습니다.")
            },
            workPlanIntent = when (pendingWorkPlanAction?.destination) {
                com.projectnuke.fusion.projects.ProjectWorkPlanDestination.SOURCE_SELECTION_COMPARE ->
                    FusionSearchWorkPlanIntent.COMPARE
                com.projectnuke.fusion.projects.ProjectWorkPlanDestination.SOURCE_SELECTION_EXTRACTION ->
                    FusionSearchWorkPlanIntent.EXTRACT
                com.projectnuke.fusion.projects.ProjectWorkPlanDestination.KNOWLEDGE_SELECTION ->
                    FusionSearchWorkPlanIntent.REVIEW
                else -> null
            },
            onWorkPlanSelection = { snapshot -> handleWorkPlanSelection(snapshot) },
            onQuestionWithSelection = { snapshot ->
                showFusionSearch = false
                onQuestionWithSelection(snapshot)
            },
            onCompareWithSelection = { snapshot ->
                when (val prepared = com.projectnuke.fusion.chat.SourceComparisonRequestPolicy.prepare(snapshot)) {
                    is com.projectnuke.fusion.chat.SourceComparisonRequestPreparation.Ready -> {
                        showFusionSearch = false
                        onCompareWithSelection(prepared.request.selection, prepared.request.composerText)
                    }
                    com.projectnuke.fusion.chat.SourceComparisonRequestPreparation.InvalidSourceCount -> {
                        Toast.makeText(context, "비교에는 자료를 2~4개 선택해 주세요.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onResearchWithProjects = { snapshot ->
                showFusionSearch = false
                onResearchWithProjects(snapshot)
            },
            onCreateReviewCard = { snapshot ->
                showFusionSearch = false
                val source = snapshot.sources.singleOrNull()
                val identity = source?.identity
                val draft = when (identity?.kind) {
                    com.projectnuke.fusion.chat.SelectionSourceKind.PROJECT_DOCUMENT -> QuickReviewCardDraft(
                        front = source.title,
                        back = source.excerpt,
                        scope = ReviewCardScope.PROJECT,
                        projectId = identity.projectId,
                        sourceKind = ReviewCardOrigin.PROJECT_DOCUMENT,
                        sourceProjectId = identity.projectId,
                        sourceProjectFileId = identity.fileId,
                    )
                    com.projectnuke.fusion.chat.SelectionSourceKind.SAVED_KNOWLEDGE -> QuickReviewCardDraft(
                        front = source.title,
                        back = source.excerpt,
                        sourceKind = ReviewCardOrigin.SAVED_KNOWLEDGE,
                        sourceSavedKnowledgeId = identity.savedKnowledgeId,
                    )
                    else -> null
                }
                if (draft == null) {
                    Toast.makeText(context, "이 자료에서는 복습 카드를 만들 수 없습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    reviewCardCaptureController.begin(draft, snapshot.archiveAccessIncluded)
                }
            },
            onNoteSourcesSelected = { snapshot ->
                showFusionSearch = false
                val current = savedKnowledgeNoteDraft
                if (current == null) {
                    Toast.makeText(context, "편집 중인 연구 노트가 없습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    val additions = snapshot.sources.map { source ->
                        SavedKnowledgeNoteReferenceDraft(source.identity, source.title)
                    }
                    val merged = (current.references + additions).distinctBy { it.identity.stableKey() }
                    if (merged.size > MAX_SAVED_NOTE_REFERENCES) {
                        savedKnowledgeNoteError = "출처는 노트마다 ${MAX_SAVED_NOTE_REFERENCES}개까지 연결할 수 있습니다."
                    } else {
                        savedKnowledgeNoteDraft = current.copy(references = merged)
                        savedKnowledgeNoteError = null
                    }
                }
            },
            onQuickAction = { action ->
                showFusionSearch = false
                cancelPendingWorkPlanAttempt("단계 진행을 취소했습니다. 계획에서 다시 시작할 수 있습니다.")
                when (action) {
                    com.projectnuke.fusion.search.FusionQuickAction.NEW_PROJECT -> {
                        drawerNewProjectName = ""
                        showDrawerNewProject = true
                    }
                    com.projectnuke.fusion.search.FusionQuickAction.OPEN_INBOX -> {
                        archiveUnlockedForSession = false
                        settingsSection = null
                        searchQuery = ""
                        page = SidebarPage.INBOX
                    }
                    com.projectnuke.fusion.search.FusionQuickAction.MANAGE_MODELS -> {
                        archiveUnlockedForSession = false
                        onOpenModelLibrary?.invoke() ?: run {
                            settingsSection = SettingsSection.MODELS_AI
                            page = SidebarPage.SETTINGS
                        }
                    }
                    com.projectnuke.fusion.search.FusionQuickAction.OPEN_TRANSLATION -> {
                        archiveUnlockedForSession = false
                        onOpenTranslation()
                    }
                    com.projectnuke.fusion.search.FusionQuickAction.CAMERA_CAPTURE -> onRequestCameraCapture()
                    com.projectnuke.fusion.search.FusionQuickAction.UPDATE_HISTORY -> {
                        archiveUnlockedForSession = false
                        showReleaseNotesDialog = true
                    }
                    com.projectnuke.fusion.search.FusionQuickAction.OPEN_SETTINGS -> {
                        archiveUnlockedForSession = false
                        settingsSection = null
                        searchQuery = ""
                        page = SidebarPage.SETTINGS
                    }
                }
            },
            onNavigate = { target ->
                showFusionSearch = false
                when (target) {
                    is KnowledgeTarget.Conversation -> openConversation(target.id)
                    is KnowledgeTarget.Project -> {
                        selectedProjectId = target.id
                        searchQuery = ""
                        page = SidebarPage.PROJECTS
                    }
                    is KnowledgeTarget.Decision -> {
                        selectedProjectId = target.projectId
                        searchQuery = ""
                        page = SidebarPage.PROJECTS
                    }
                    is KnowledgeTarget.Memory -> showMemoryManagerDialog = true
                    is KnowledgeTarget.InboxItem -> page = SidebarPage.INBOX
                    is KnowledgeTarget.Checkpoint -> onOpenCheckpoint(target.id, target.conversationId, target.messageId, archiveUnlockedForSession)
                    is KnowledgeTarget.SavedKnowledge -> scope.launch {
                        val repository = FusionKnowledgeSearchRepository(context.applicationContext) {
                            ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
                        }
                        val accessible = try {
                            repository.accessible(target)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        if (!accessible) {
                            Toast.makeText(context, "저장 지식을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                        } else {
                            val exact = try {
                                withContext(Dispatchers.IO) {
                                    db.savedKnowledgeDao().getById(target.id)?.takeIf { row ->
                                        row.id == target.id &&
                                            (target.expectedProjectId == null || row.projectId == target.expectedProjectId)
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                            if (exact == null) {
                                Toast.makeText(context, "저장 지식을 현재 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                            } else {
                                confirmSavedKnowledgeDelete = false
                                savedKnowledgeDetail = exact
                            }
                        }
                    }
                }
            },
        )
    }
    StudyReviewCardCaptureSurface(
        state = reviewCardCaptureState,
        projectName = if (reviewCardCaptureState.draft?.projectId != null) "프로젝트" else null,
        onFrontChange = reviewCardCaptureController::updateFront,
        onBackChange = reviewCardCaptureController::updateBack,
        onScopeChange = reviewCardCaptureController::updateScope,
        onSave = reviewCardCaptureController::save,
        onDismiss = reviewCardCaptureController::dismiss,
        onSaved = {
            reviewCardCaptureController.dismiss()
            Toast.makeText(context, "복습 카드를 저장했습니다.", Toast.LENGTH_SHORT).show()
        },
    )
    savedKnowledgeNoteDraft?.let { draft ->
        SavedKnowledgeNoteEditorSurface(
            draft = draft,
            saving = savedKnowledgeNoteSaving,
            error = savedKnowledgeNoteError,
            onTitleChange = { value -> savedKnowledgeNoteDraft = draft.copy(title = value.take(160)) },
            onBodyChange = { value -> savedKnowledgeNoteDraft = draft.copy(body = value.take(com.projectnuke.fusion.knowledge.MAX_SAVED_NOTE_BODY_CHARS)) },
            onAddSource = { showFusionSearch = true },
            onRemoveSource = { index ->
                savedKnowledgeNoteDraft = draft.copy(references = draft.references.filterIndexed { current, _ -> current != index })
            },
            onSave = {
                if (savedKnowledgeNoteSaving) return@SavedKnowledgeNoteEditorSurface
                savedKnowledgeNoteSaving = true
                savedKnowledgeNoteError = null
                scope.launch {
                    val access = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
                    val result = try {
                        savedKnowledgeNoteRepository.save(
                            noteId = draft.id,
                            title = draft.title,
                            body = draft.body,
                            references = draft.references,
                            now = System.currentTimeMillis(),
                            includeArchived = access,
                            sourceSavedKnowledgeId = draft.sourceSavedKnowledgeId,
                            projectId = draft.projectId,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    savedKnowledgeNoteSaving = false
                    when (result) {
                        is SavedKnowledgeNoteSaveResult.Saved -> {
                            savedKnowledgeNoteDraft = null
                            savedKnowledgeNoteError = null
                            savedKnowledgeDetail = withContext(Dispatchers.IO) { db.savedKnowledgeDao().getById(result.noteId) }
                            Toast.makeText(context, "연구 노트를 저장했습니다.", Toast.LENGTH_SHORT).show()
                        }
                        SavedKnowledgeNoteSaveResult.SourceUnavailable -> savedKnowledgeNoteError = "출처를 더 이상 열 수 없습니다. 검색에서 다시 선택해 주세요."
                        SavedKnowledgeNoteSaveResult.ProjectUnavailable -> savedKnowledgeNoteError = "프로젝트가 바뀌어 노트를 저장하지 않았습니다."
                        SavedKnowledgeNoteSaveResult.NoteUnavailable -> savedKnowledgeNoteError = "연구 노트가 삭제되어 저장할 수 없습니다."
                        SavedKnowledgeNoteSaveResult.InvalidDraft -> savedKnowledgeNoteError = "제목, 본문, 출처 개수를 확인해 주세요."
                        null -> savedKnowledgeNoteError = "연구 노트를 저장하지 못했습니다. 다시 시도해 주세요."
                    }
                }
            },
            onDismiss = {
                savedKnowledgeNoteDraft = null
                savedKnowledgeNoteError = null
            },
        )
    }
    savedKnowledgeDetail?.let { item ->
        val archiveAllowedForDetail = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
        val detailAccessIdentity = identityForDetail(item.id, archiveAllowedForDetail)
        val detailAccessState by produceState<SavedKnowledgeDetailAccessState>(
            SavedKnowledgeDetailAccessState.Checking(detailAccessIdentity),
            item.id, archiveAllowedForDetail,
        ) {
            value = try {
                withContext(Dispatchers.IO) {
                    val allowed = com.projectnuke.fusion.knowledge.SavedKnowledgeAccessGate.isRowAccessible(
                        db, item, archiveAllowedForDetail,
                    )
                    if (allowed) SavedKnowledgeDetailAccessState.Allowed(detailAccessIdentity)
                    else SavedKnowledgeDetailAccessState.Denied(detailAccessIdentity)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SavedKnowledgeDetailAccessState.Denied(detailAccessIdentity)
            }
        }
        val noteReferences by produceState<List<SavedKnowledgeNoteReferenceDraft>>(emptyList(), item.id, item.provenanceType) {
            value = if (item.provenanceType == "USER_NOTE") {
                savedKnowledgeNoteRepository.load(
                    item.id,
                    ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession),
                )?.references.orEmpty()
            } else emptyList()
        }
        val highlightSource by produceState<com.projectnuke.fusion.data.DocumentHighlightSourceEntity?>(null, item.id) {
            value = if (item.provenanceType == "DOCUMENT_HIGHLIGHT") {
                db.documentHighlightSourceDao().getByKnowledgeId(item.id)
            } else null
        }
        AlertDialog(
            onDismissRequest = { savedKnowledgeDetail = null },
            title = {
                when (detailAccessState) {
                    is SavedKnowledgeDetailAccessState.Allowed -> Text(item.title)
                    else -> Text("저장 지식", style = MaterialTheme.typography.titleLarge)
                }
            },
            text = {
                Column {
                    when (detailAccessState) {
                        is SavedKnowledgeDetailAccessState.Checking -> {
                            Text("접근 권한을 확인하는 중…", style = MaterialTheme.typography.bodyMedium)
                            return@Column
                        }
                        is SavedKnowledgeDetailAccessState.Denied -> {
                            Text(
                                "원본 접근 권한이 변경되어 내용을 표시할 수 없습니다.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            return@Column
                        }
                        is SavedKnowledgeDetailAccessState.Allowed -> Unit
                    }
                    if (detailAccessState !is SavedKnowledgeDetailAccessState.Allowed) {
                        return@Column
                    }
                    Text(
                        if (item.provenanceType == "DOCUMENT_HIGHLIGHT") {
                            "원본 문서에서 저장한 인용문입니다. 프로젝트에서 원본 자료를 제거하면 하이라이트도 삭제됩니다. 관리 파일이 사라진 경우 인용문은 사본으로 남습니다."
                        } else if (item.provenanceType == "WEB_CLIP") {
                            "웹에서 가져온 로컬 스냅샷입니다. 온라인 페이지가 바뀌어도 이 사본은 저장 시점 내용을 유지합니다."
                        } else if (item.provenanceType == "USER_NOTE") {
                            "사용자가 편집한 연구 노트입니다. 본문과 출처 연결은 별도로 보관됩니다."
                        } else "저장 시점의 답변 사본입니다. 원본과 별도로 보관됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.provenanceType == "WEB_CLIP") {
                        webClipFreshnessLabel(item.retrievedAt, System.currentTimeMillis())?.let { freshness ->
                            Text(freshness, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (noteReferences.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("연결된 출처", style = MaterialTheme.typography.titleSmall)
                        noteReferences.take(MAX_SAVED_NOTE_REFERENCES).forEach { reference ->
                            TextButton(onClick = {
                                val target = reference.identity.toKnowledgeTarget()
                                if (target == null) {
                                    Toast.makeText(context, "이 출처 형식은 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                } else scope.launch {
                                    val searchRepository = FusionKnowledgeSearchRepository(context.applicationContext) {
                                        ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
                                    }
                                    if (!searchRepository.accessible(target)) {
                                        Toast.makeText(context, "원본을 더 이상 열 수 없습니다. 출처 정보는 노트에 남아 있습니다.", Toast.LENGTH_LONG).show()
                                    } else {
                                        savedKnowledgeDetail = null
                                        when (target) {
                                            is KnowledgeTarget.Conversation -> openConversation(target.id)
                                            is KnowledgeTarget.Project -> {
                                                selectedProjectId = target.id
                                                page = SidebarPage.PROJECTS
                                            }
                                            is KnowledgeTarget.SavedKnowledge -> savedKnowledgeDetail = withContext(Dispatchers.IO) {
                                                db.savedKnowledgeDao().getById(target.id)
                                            }
                                            else -> Unit
                                        }
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("${reference.title} · ${noteSourceKindLabel(reference.identity.kind)}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    highlightSource?.userNote?.takeIf { it.isNotBlank() }?.let { note ->
                        Text("메모 · $note", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.provenanceType == "DOCUMENT_HIGHLIGHT" && item.projectId != null) {
                        TextButton(onClick = {
                            selectedProjectId = item.projectId
                            page = SidebarPage.PROJECTS
                            savedKnowledgeDetail = null
                        }) { Text("프로젝트 열기") }
                    }
                    TextButton(
                        onClick = { showSavedKnowledgeHygiene = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = FusionSpacing.control),
                    ) { Text("정리 후보") }
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            item.savedText,
                            modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    RelatedKnowledgeSection(
                        target = KnowledgeTarget.SavedKnowledge(item.id),
                        includeArchived = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession),
                        onOpen = { target ->
                            scope.launch {
                                val archiveAllowed = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
                                val repository = FusionKnowledgeSearchRepository(context.applicationContext) { archiveAllowed }
                                if (!repository.accessible(target)) {
                                    Toast.makeText(context, "자료를 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                } else {
                                    savedKnowledgeDetail = null
                                    when (target) {
                                        is KnowledgeTarget.Conversation -> openConversation(target.id)
                                        is KnowledgeTarget.Project -> {
                                            selectedProjectId = target.id
                                            page = SidebarPage.PROJECTS
                                        }
                                        is KnowledgeTarget.Decision -> {
                                            selectedProjectId = target.projectId
                                            page = SidebarPage.PROJECTS
                                        }
                                        is KnowledgeTarget.Memory -> showMemoryManagerDialog = true
                                        is KnowledgeTarget.InboxItem -> page = SidebarPage.INBOX
                                        is KnowledgeTarget.Checkpoint -> onOpenCheckpoint(target.id, target.conversationId, target.messageId, archiveUnlockedForSession)
                                        is KnowledgeTarget.SavedKnowledge -> {
                                            savedKnowledgeDetail = withContext(Dispatchers.IO) { db.savedKnowledgeDao().getById(target.id) }
                                        }
                                    }
                                }
                            }
                        },
                    )
                    TextButton(onClick = {
                        structuredExtractionSource = StructuredExtractionSource(
                            title = item.title,
                            kind = "저장 지식",
                            reference = "saved:${item.id}",
                            text = item.savedText,
                            durableIdentity = com.projectnuke.fusion.chat.SelectionSourceIdentity(
                                com.projectnuke.fusion.chat.SelectionSourceKind.SAVED_KNOWLEDGE,
                                savedKnowledgeId = item.id,
                            ),
                        )
                    }) { Text("구조화해서 추출") }
                    TextButton(onClick = {
                        val projectId = item.projectId
                        reviewCardCaptureController.begin(
                            QuickReviewCardDraft(
                                front = item.title,
                                back = item.savedText,
                                scope = if (item.scope == "PROJECT" && projectId != null) ReviewCardScope.PROJECT else ReviewCardScope.PERSONAL,
                                projectId = projectId,
                                sourceKind = ReviewCardOrigin.SAVED_KNOWLEDGE,
                                sourceSavedKnowledgeId = item.id,
                            ),
                            archiveAccessIncluded = false,
                        )
                    }) { Text("복습 카드 만들기") }
                    TextButton(onClick = {
                        organizeSavedKnowledgeId = item.id
                        savedKnowledgeDetail = null
                    }) { Text("태그 및 모음") }
                    TextButton(onClick = {
                        scope.launch {
                            val access = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
                            val draft = if (item.provenanceType == "USER_NOTE") {
                                withContext(Dispatchers.IO) { savedKnowledgeNoteRepository.load(item.id, access) }
                            } else {
                                withContext(Dispatchers.IO) {
                                    savedKnowledgeNoteRepository.prepareFromSavedKnowledge(item.id, access)
                                }
                            }
                            if (draft == null) {
                                Toast.makeText(context, "연구 노트를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                            } else {
                                savedKnowledgeNoteDraft = draft
                                savedKnowledgeNoteError = null
                                savedKnowledgeDetail = null
                            }
                        }
                    }) { Text(if (item.provenanceType == "USER_NOTE") "노트 편집" else "연구 노트로 복사") }
                }
            },
            confirmButton = { TextButton(onClick = { savedKnowledgeDetail = null }) { Text("닫기") } },
            dismissButton = { TextButton(onClick = { confirmSavedKnowledgeDelete = true }) { Text("삭제") } },
        )
    }
    if (showSavedKnowledgeHygiene) {
        val sourceId = savedKnowledgeDetail?.id
        if (sourceId == null) {
            showSavedKnowledgeHygiene = false
        } else {
            KnowledgeHygieneDialog(
                database = db,
                sourceId = sourceId,
                includeArchived = { ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession) },
                onDismiss = { showSavedKnowledgeHygiene = false },
                onOpenCandidate = { candidateId ->
                    scope.launch {
                        val archiveAllowed = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession)
                        val source = withContext(Dispatchers.IO) { db.savedKnowledgeDao().getById(sourceId) }
                        val candidate = withContext(Dispatchers.IO) { db.savedKnowledgeDao().getById(candidateId) }
                        val sourceAllowed = source != null && com.projectnuke.fusion.knowledge.SavedKnowledgeAccessGate
                            .isRowAccessible(db, source, archiveAllowed)
                        val candidateAllowed = candidate != null && com.projectnuke.fusion.knowledge.SavedKnowledgeAccessGate
                            .isRowAccessible(db, candidate, archiveAllowed)
                        showSavedKnowledgeHygiene = false
                        if (sourceAllowed && candidateAllowed && candidate != null) {
                            savedKnowledgeDetail = candidate
                        } else {
                            Toast.makeText(context, "선택한 저장 지식을 더 이상 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
    }
    openReviewCardId?.let { cardId ->
        ReviewCardReviewSurface(
            database = db,
            cardId = cardId,
            onBack = { openReviewCardId = null },
        )
    }
    val workPlanExtraction = pendingWorkPlanAction?.takeIf {
        it.destination == com.projectnuke.fusion.projects.ProjectWorkPlanDestination.SOURCE_SELECTION_EXTRACTION
    }
    StructuredExtractionSurface(
        source = structuredExtractionSource,
        onDismiss = {
            structuredExtractionSource = null
            if (workPlanExtraction != null) {
                cancelPendingWorkPlanAttempt("추출 단계를 취소했습니다. 계획에서 다시 시작할 수 있습니다.")
            }
        },
        onAccept = workPlanExtraction?.let { action -> { result: StructuredExtractionResult ->
            scope.launch {
                val outcome = try {
                    workPlanCoordinator.settleExtraction(
                        action = action,
                        result = result,
                        isAccessible = ::workPlanSourceAccessible,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ProjectWorkPlanHandoffCoordinator.SettleOutcome.Rejected(
                        "추출 결과를 기록하지 못했습니다. 계획에서 다시 시작할 수 있습니다.",
                    )
                }
                when (outcome) {
                    ProjectWorkPlanHandoffCoordinator.SettleOutcome.Applied -> {
                        structuredExtractionSource = null
                        reopenWorkPlanAfterSettle(
                            action = action,
                            applied = true,
                            successMessage = "추출 결과를 단계 결과로 기록했습니다.",
                        )
                    }
                    ProjectWorkPlanHandoffCoordinator.SettleOutcome.Stale -> {
                        structuredExtractionSource = null
                        reopenWorkPlanAfterSettle(
                            action = action,
                            applied = false,
                            successMessage = "추출 결과를 단계 결과로 기록했습니다.",
                        )
                    }
                    is ProjectWorkPlanHandoffCoordinator.SettleOutcome.Rejected ->
                        Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                }
            }
        } },
        onSaveAsNote = if (workPlanExtraction != null) null else if (savedKnowledgeDetail?.provenanceType == "USER_NOTE") null else { result ->
            val draft = result.toNoteDraft()
            if (draft == null) {
                Toast.makeText(context, "출처를 보존할 수 없어 노트를 만들지 않았습니다.", Toast.LENGTH_SHORT).show()
            } else {
                structuredExtractionSource = null
                savedKnowledgeDetail = null
                savedKnowledgeNoteDraft = draft
                savedKnowledgeNoteError = null
            }
        },
        onAddToProject = if (workPlanExtraction != null) null else if (savedKnowledgeDetail?.projectId != null && savedKnowledgeDetail?.provenanceType != "USER_NOTE") ({ result ->
            val projectId = savedKnowledgeDetail?.projectId ?: return@StructuredExtractionSurface
            scope.launch {
                val project = withContext(Dispatchers.IO) { db.projectDao().getProjectById(projectId) }
                val draft = project?.let { result.toNoteDraft(it.id, it.name) }
                if (draft == null) {
                    Toast.makeText(context, "프로젝트가 바뀌어 노트를 만들지 않았습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    structuredExtractionSource = null
                    savedKnowledgeDetail = null
                    savedKnowledgeNoteDraft = draft
                    savedKnowledgeNoteError = null
                }
            }
        }) else null,
    )
    organizeSavedKnowledgeId?.let { itemId ->
        KnowledgeOrganizationDialog(
            database = db,
            kind = OrganizationObjectKind.SAVED_KNOWLEDGE,
            itemId = itemId,
            onDismiss = { organizeSavedKnowledgeId = null },
            onStatus = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
        )
    }
    if (confirmSavedKnowledgeDelete) {
        AlertDialog(
            onDismissRequest = { confirmSavedKnowledgeDelete = false },
            title = { Text("저장 지식을 삭제할까요?") },
            text = { Text("저장한 사본만 삭제됩니다. 원본 답변은 바뀌지 않습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = savedKnowledgeDetail?.id ?: return@TextButton
                    scope.launch {
                        try {
                            val deleted = withContext(Dispatchers.IO) { db.savedKnowledgeDao().delete(id) == 1 }
                            if (deleted) {
                                savedKnowledgeDetail = null
                                confirmSavedKnowledgeDelete = false
                            } else {
                                Toast.makeText(context, "저장 지식을 삭제하지 못했습니다.", Toast.LENGTH_SHORT).show()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Toast.makeText(context, "저장 지식을 삭제하지 못했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmSavedKnowledgeDelete = false }) { Text("취소") } },
        )
    }
    if (showBranchMap && isDrawerOpen) BranchMapSurface(
        currentId = currentConversationId,
        includeArchived = ArchiveAccessPolicy.canRenderArchive(archiveLockEnabled, archiveUnlockedForSession),
        onDismiss = { showBranchMap = false },
        onNavigate = { id -> showBranchMap = false; openConversation(id) },
        onSynthesize = { selection, prompt ->
            showBranchMap = false
            onSynthesizeBranches(selection, prompt)
        },
    )
    if (showMemoryManagerDialog) {
        MemoryManagerDialog(
            context = context,
            clipboard = clipboard,
            conversations = conversations,
            archivedConversations = archivedConversations,
            archiveLockEnabled = archiveLockEnabled,
            archiveUnlockedForSession = archiveUnlockedForSession,
            currentConversationId = currentConversationId,
            onDismiss = { showMemoryManagerDialog = false }
        )
    }

    if (showHelpDialog) {
        FusionHelpScreen(
            context = context,
            clipboard = clipboard,
            onDismiss = { showHelpDialog = false }
        )
    }

    if (showLanguageDialog) {
        FusionLanguageSettingsDialog(
            context = context,
            currentLanguage = appLanguage,
            storedLanguage = getStoredFusionAppLanguage(context),
            onSelect = { language ->
                appLanguage = getEffectiveFusionAppLanguage(context)
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showAiProviderSettingsDialog) {
        AiProviderSettingsScreen(
            onDismiss = { showAiProviderSettingsDialog = false }
        )
    }

    if (showAppearanceDialog) {
        val selectedMode = readFusionThemeMode(context)
        AlertDialog(
            onDismissRequest = { showAppearanceDialog = false },
            title = { Text("앱 테마") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fusion의 전체 색상 모드를 선택하세요.", color = DrawerTextSecondary, fontSize = 13.sp)
                    FusionThemeMode.entries.forEach { mode ->
                        DrawerThemeChoiceRow(
                            mode = mode,
                            selected = mode == selectedMode,
                            onClick = {
                                if (saveFusionThemeMode(context, mode)) {
                                    showAppearanceDialog = false
                                } else {
                                    Toast.makeText(context, "테마를 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppearanceDialog = false }) {
                    Text("닫기", color = DrawerAccentBlue)
                }
            },
            containerColor = DrawerPanelBg,
            titleContentColor = DrawerTextPrimary,
            textContentColor = DrawerTextPrimary,
        )
    }

    if (showDeveloperModeDialog) {
        var researchInterventionEnabled by remember {
            mutableStateOf(ResearchInterventionSettings.isEnabled(prefs))
        }
        AlertDialog(
            onDismissRequest = { showDeveloperModeDialog = false },
            title = { Text("개발자 모드") },
            text = {
                Column {
                    Text("개발자 모드는 내부 테스트 항목을 표시하기 위한 UI 기능입니다. 보안 기능이 아닙니다.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "조사 답변 자동 검증 실험",
                            modifier = Modifier.weight(1f),
                            color = DrawerTextPrimary,
                        )
                        Switch(
                            checked = researchInterventionEnabled,
                            onCheckedChange = { enabled ->
                                ResearchInterventionSettings.setEnabled(context, enabled)
                                researchInterventionEnabled = enabled
                            },
                        )
                    }
                    Text(
                        text = "리서치 모드 답변만 대상이며, 기본값은 꺼짐입니다. 답변 저장 전에 근거를 확인하고 필요한 경우 수정·추가 검색·확정 보류를 수행합니다.",
                        color = DrawerTextPrimary,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    setFusionDeveloperModeEnabled(context, false)
                    developerModeEnabled = false
                    showDeveloperModeDialog = false
                    showReleaseChecklistDialog = false
                    Toast.makeText(context, "개발자 모드를 사용하지 않도록 설정했습니다.", Toast.LENGTH_SHORT).show()
                }) {
                    Text("끄기")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeveloperModeDialog = false }) {
                    Text("닫기")
                }
            },
            containerColor = DrawerPanelBg,
            titleContentColor = DrawerTextPrimary,
            textContentColor = DrawerTextPrimary
        )
    }

    if (showModelCompatibilityGuide) {
        ModelCompatibilityGuideDialog(
            onDismiss = { showModelCompatibilityGuide = false }
        )
    }

    if (showAttachmentStorageDialog) {
        AlertDialog(
            onDismissRequest = { showAttachmentStorageDialog = false },
            title = { Text("첨부 파일 저장 공간") },
            text = {
                val stats = attachmentStorageStats
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (attachmentStorageLoading) {
                        Text("저장공간 정보를 계산하고 있습니다.", color = DrawerTextSecondary, fontSize = 13.sp)
                    } else if (stats != null) {
                        Text("총 용량: ${formatBytes(stats.totalBytes)}", color = DrawerTextPrimary, fontSize = 14.sp)
                        Text("파일 수: ${stats.totalFiles}", color = DrawerTextPrimary, fontSize = 14.sp)
                        Text("참조 중인 파일: ${stats.referencedFiles}", color = DrawerTextPrimary, fontSize = 14.sp)
                        Text("미참조 파일: ${stats.unreferencedFiles}", color = DrawerTextPrimary, fontSize = 14.sp)
                    } else {
                        Text("저장공간 정보를 불러오지 못했습니다.", color = DrawerTextSecondary, fontSize = 13.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAttachmentStorageDialog = false }) {
                    Text("닫기")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!attachmentCleanupCanStart(attachmentStorageLoading)) return@TextButton
                        attachmentStorageLoading = true
                        scope.launch {
                            try {
                                val result = AttachmentStorageManager.cleanupUnreferencedAttachments(context, dao, projectDao)
                                if (result.deletedFiles > 0) {
                                    Toast.makeText(context, "사용하지 않는 첨부파일을 정리했습니다.", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "정리할 첨부파일이 없습니다.", Toast.LENGTH_SHORT).show()
                                }
                                attachmentStorageStats = runCatching {
                                    AttachmentStorageManager.calculateAttachmentStorageStats(context, dao, projectDao)
                                }.getOrNull()
                            } catch (_: Exception) {
                                Toast.makeText(context, "첨부파일 정리 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                            } finally {
                                attachmentStorageLoading = false
                            }
                        }
                    }
                ) {
                    Text("사용하지 않는 첨부파일 정리", color = MaterialTheme.colorScheme.error)
                }
            },
            containerColor = DrawerPanelBg,
            titleContentColor = DrawerTextPrimary,
            textContentColor = DrawerTextPrimary
        )
    }

    renameConversation?.let { conversation ->
        AlertDialog(
            onDismissRequest = { renameConversation = null },
            title = { Text("채팅 이름 변경") },
            text = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = DrawerCardSelectedBg
                ) {
                    BasicTextField(
                        value = renameTitle,
                        onValueChange = { renameTitle = it },
                        singleLine = true,
                        textStyle = TextStyle(color = DrawerTextPrimary, fontSize = 16.sp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            },
            dismissButton = { TextButton(onClick = { renameConversation = null }) { Text("취소") } },
            confirmButton = {
                TextButton(onClick = {
                    val title = renameTitle.takeIf { it.isNotBlank() }
                        ?.let { normalizeUserVisibleName(it, "New conversation", 80) }
                        .orEmpty()
                    if (title.isNotBlank()) {
                        titleRegenerationGeneration += 1L
                        scope.launch { dao.updateConversationTitle(conversation.id, title) }
                    }
                    renameConversation = null
                }) { Text("저장") }
            },
            containerColor = DrawerPanelBg,
            titleContentColor = DrawerTextPrimary,
            textContentColor = DrawerTextPrimary
        )
    }

deleteConversation?.let { conversation ->
  val isDeleting = deletingConversationId == conversation.id
  AlertDialog(
    onDismissRequest = {
      if (!isDeleting) {
        deleteConversation = null
        deletingConversationId = null
      }
    },
    title = { Text("채팅 삭제") },
    text = { Text("이 채팅을 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.") },
    dismissButton = {
      TextButton(
        onClick = {
          deleteConversation = null
          deletingConversationId = null
        },
        enabled = !isDeleting
      ) { Text("취소") }
    },
    confirmButton = {
      TextButton(
        onClick = {
          if (deletingConversationId != null) return@TextButton
          val deletingId = conversation.id
          deletingConversationId = deletingId
          scope.launch {
            try {
              val result = deleteConversationProduction(context, dao, projectDao, chatViewModel, deletingId)
              if (result.isDeletedOrAbsent()) {
                projectResourceRevision++
                val nextConversationId = dao.getLatestConversation()?.id
                if (deletingId == currentConversationId) {
                  onConversationRemovedFromList(deletingId, nextConversationId)
                }
              }
            } catch (e: CancellationException) {
              throw e
            } catch (_: Exception) {
              Toast.makeText(
                context,
                "채팅을 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                Toast.LENGTH_SHORT
              ).show()
            } finally {
              if (deletingConversationId == deletingId) {
                deletingConversationId = null
              }
              if (deleteConversation?.id == deletingId) {
                deleteConversation = null
              }
            }
          }
        },
        enabled = !isDeleting
      ) { Text("삭제", color = MaterialTheme.colorScheme.error) }
    },
    containerColor = DrawerPanelBg,
    titleContentColor = DrawerTextPrimary,
    textContentColor = DrawerTextPrimary
  )
}
}

@Composable
private fun DrawerPageTopBar(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DrawerCircleButton("←", "이전 화면으로", onBack)
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = title, color = colors.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun DrawerTopBar(
    onClose: () -> Unit,
    onNewChat: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DrawerCircleButton("←", "대화로 돌아가기", onClose)

        Spacer(modifier = Modifier.width(10.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = "Fusion",
                color = colors.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = "Local AI Workspace",
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.wrapContentWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DrawerGitHubPill(
                onClick = { openFusionGithubUrl(context) }
            )
            DrawerCircleButton("+", "새 대화 시작", onNewChat)
        }
    }
}

@Composable
private fun DrawerSearchBox(
    value: String,
    onValueChange: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = colors.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "⌕", color = colors.onSurfaceVariant, fontSize = 19.sp)
            Spacer(modifier = Modifier.width(10.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(color = colors.onSurface, fontSize = 15.sp),
                decorationBox = { inner ->
                    if (value.isBlank()) {
                        Text(text = "대화를 검색합니다.", color = colors.onSurfaceVariant, fontSize = 15.sp)
                    }
                    inner()
                }
            )
        }
    }
    if (value.isNotBlank()) {
        val prefix = when {
            value.trim().length < 2 -> androidx.compose.ui.res.stringResource(com.projectnuke.fusion.R.string.drawer_search_short) + "\n"
            value.trim().length > 100 -> androidx.compose.ui.res.stringResource(com.projectnuke.fusion.R.string.drawer_search_long) + "\n"
            else -> ""
        }
        Text(
            text = prefix + androidx.compose.ui.res.stringResource(com.projectnuke.fusion.R.string.drawer_search_limits, ConversationSearchCoarseMaxRawCandidates, ConversationSearchResultLimit),
            color = colors.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
    }
}

@Composable
private fun DrawerPrimaryButton(title: String, subtitle: String, leading: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceVariant
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            DrawerAvatar(text = leading, accent = true)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(text = subtitle, color = colors.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun DrawerActionRow(title: String, subtitle: String, leading: String, leadingIcon: ImageVector? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = colors.surface
    ) {
        Row(modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                Icon(leadingIcon, contentDescription = null, tint = colors.onSurfaceVariant)
            } else DrawerAvatar(text = leading, accent = false)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(text = subtitle, color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DrawerPlannedRow(title: String, subtitle: String, leading: String, container: Color = DrawerBlackBg) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = container
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            DrawerAvatar(text = leading, accent = false)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = colors.onSurfaceVariant, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = subtitle, color = colors.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "준비 중", color = colors.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DrawerUtilityRow(title: String, subtitle: String, leading: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = colors.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DrawerAvatar(text = leading, accent = false)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DrawerSettingInfoRow(title: String, subtitle: String) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = FusionSpacing.row)) {
        Text(text = title, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge)
        Text(text = subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DrawerSettingActionRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = FusionSpacing.control).padding(vertical = FusionSpacing.row),
        horizontalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap),
        verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun DrawerSettingCategoryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) = DrawerSettingActionRow(title = title, subtitle = subtitle, onClick = onClick)

@Composable
private fun DrawerThemeChoiceRow(
    mode: FusionThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(mode.label, color = if (selected) colors.onPrimaryContainer else colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    when (mode) {
                        FusionThemeMode.BLACK -> "순수한 검정 배경과 구분되는 표면"
                        FusionThemeMode.DARK -> "중성 회색의 어두운 표면"
                        FusionThemeMode.LIGHT -> "밝은 중성 표면과 어두운 글자"
                        FusionThemeMode.AUTO -> "시스템 모드에 따라 자동으로"
                    },
                    color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Text(if (selected) "선택됨" else "", color = colors.primary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DrawerSettingToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth()
        .toggleable(value = checked, role = Role.Switch, onValueChange = onToggle)
        .semantics { contentDescription = title }
        .heightIn(min = FusionSpacing.control).padding(vertical = FusionSpacing.row),
        horizontalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap),
        verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationEntity,
    onClick: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onMenuClick: () -> Unit,
    onTogglePinned: () -> Unit,
    onRename: () -> Unit,
    onRegenerateTitle: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    archiveMenuLabel: String = "아카이브에 보관",
    pinMenuLabel: String = if (conversation.isPinned) "고정 해제" else "채팅 고정",
    onRemoveFromProject: (() -> Unit)? = null,
    onCheckpoints: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val currentLocale = LocalLocale.current.platformLocale
    val dateText = remember(conversation.updatedAt, currentLocale) {
        SimpleDateFormat("MM/dd HH:mm", currentLocale).format(Date(conversation.updatedAt))
    }
    val branchMarker = conversationBranchMarker(conversation)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceVariant
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            DrawerAvatar(
                text = conversation.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "F",
                accent = false
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.isPinned) {
                        Text(text = "PIN", color = colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                    }
                    Text(
                        text = conversation.title.ifBlank { "새 대화" },
                        color = colors.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    branchMarker?.let { marker ->
                        Text(
                            text = marker,
                            color = colors.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "·", color = colors.onSurfaceVariant, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(text = dateText, color = colors.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            Box {
                Box(modifier = Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { onMenuClick() }
                    .semantics { contentDescription = "${conversation.title.ifBlank { "새 대화" }} 대화 작업" }, contentAlignment = Alignment.Center) {
                    Text(text = "⋯", color = colors.onSurfaceVariant, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                FusionDropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu, containerColor = colors.surfaceVariant) {
                    DropdownMenuItem(text = { Text(pinMenuLabel, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = onTogglePinned)
                    DropdownMenuItem(text = { Text("이름 변경", color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = onRename)
                    DropdownMenuItem(text = { Text("제목 다시 생성", color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = onRegenerateTitle)
                    DropdownMenuItem(text = { Text(archiveMenuLabel, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = onArchive)
                    if (onCheckpoints != null) {
                        DropdownMenuItem(text = { Text("체크포인트", color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = onCheckpoints)
                    }
                    if (onRemoveFromProject != null) {
                        DropdownMenuItem(text = { Text("프로젝트에서 제거", color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = onRemoveFromProject)
                    }
                    DropdownMenuItem(text = { Text("삭제", color = colors.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun ProjectNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = DrawerCardSelectedBg
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = DrawerTextPrimary, fontSize = 16.sp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("만들기") } },
        containerColor = DrawerPanelBg,
        titleContentColor = DrawerTextPrimary,
        textContentColor = DrawerTextPrimary
    )
}

@Composable
private fun EmptyConversationList(hasSearchQuery: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (hasSearchQuery) "검색 결과가 없습니다." else "아직 저장된 대화가 없습니다.",
            color = DrawerTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (hasSearchQuery) "다른 키워드로 검색해 보세요." else "새 채팅을 시작하면 여기에 저장됩니다.",
            color = DrawerTextSecondary,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun EmptyArchiveList() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "보관된 채팅이 없습니다.",
            color = DrawerTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DrawerSectionTitle(text: String) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = text,
        color = colors.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun DrawerGitHubPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)

    Row(
        modifier = modifier
            .wrapContentWidth()
            .height(34.dp)
            .clip(shape)
            .background(colors.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = "GitHub",
            color = colors.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = "↗",
            color = colors.onPrimaryContainer.copy(alpha = 0.9f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

private fun buildSafeConversationTitleFromMessages(messages: List<MessageEntity>): String {
    val userCandidates = messages
        .asSequence()
        .filter { it.role == "user" }
        .map { stripHiddenForTitle(it.content) }
        .filter { it.isNotBlank() && it.length >= 2 }
        .toList()

    val assistantCandidates = messages
        .asSequence()
        .filter { it.role != "user" }
        .map { stripHiddenForTitle(it.content) }
        .filter { it.isNotBlank() && it.length >= 2 }
        .toList()

    val base = (userCandidates + assistantCandidates).firstOrNull().orEmpty()
    if (base.isBlank()) return "새 대화"
    return truncateSafeTitle(base)
}

private fun truncateSafeTitle(text: String): String {
    val normalized = text.replace('\n', ' ').trim()
    if (normalized.isBlank()) return "새 대화"
    val hasNonAscii = normalized.any { it.code > 127 }
    val limit = if (hasNonAscii) 18 else 28
    return if (normalized.length <= limit) normalized else normalized.take(limit).trimEnd() + "…"
}

private fun buildConversationTitleFromMessages(messages: List<MessageEntity>): String {
    val userCandidates = messages
        .asSequence()
        .filter { it.role == "user" }
        .map { stripHiddenForTitle(it.content) }
        .filter { it.isNotBlank() && it.length >= 2 }
        .toList()

    val assistantCandidates = messages
        .asSequence()
        .filter { it.role != "user" }
        .map { stripHiddenForTitle(it.content) }
        .filter { it.isNotBlank() && it.length >= 2 }
        .toList()

    val base = (userCandidates + assistantCandidates).firstOrNull().orEmpty()
    if (base.isBlank()) return "새 대화"
    return truncateTitle(base)
}

private fun stripHiddenForTitle(raw: String): String =
    com.projectnuke.fusion.util.titleMessageBody(raw)

private fun truncateTitle(text: String): String {
    val normalized = text.replace('\n', ' ').trim()
    if (normalized.isBlank()) return "새 대화"
    val hasNonAscii = normalized.any { it.code > 127 }
    val limit = if (hasNonAscii) 18 else 28
    return if (normalized.length <= limit) normalized else normalized.take(limit).trimEnd() + "…"
}

private fun buildChatExportMarkdown(
    context: android.content.Context,
    title: String,
    messages: List<MessageEntity>
): String {
    val dateText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date())

    val body = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("- 내보낸 날짜: $dateText")
        appendLine("- 메시지 수: ${messages.size}")
        appendLine()
        val conversationId = messages.firstOrNull()?.conversationId ?: 0L
        activeTimelineMessages(
            messages,
            loadResponseVersionState(context, conversationId)
        ).forEach { message ->
            val heading = if (message.role == "user") "사용자" else "Fusion"
            val visibleContent = stripHiddenFusionBlocks(message.content).trim()
            if (visibleContent.isNotBlank()) {
                appendLine("## $heading")
                appendLine(visibleContent)
                appendLine()
            }
        }
    }

    return body.trimEnd()
}

private fun exportConversationTitle(conversation: ConversationEntity): String {
    return buildString {
        append(conversation.title.ifBlank { "새 대화" })
        if (conversation.isPinned) append(" · 고정")
        if (conversation.isArchived) append(" · 아카이브")
    }
}

private fun safeMarkdownExportTitle(title: String): String {
    val normalized = title.trim().ifBlank { "chat" }
        .replace(Regex("""[\\/:*?"<>|]"""), "-")
        .replace(Regex("""\s+"""), "-")
        .trim('-')
    return normalized.take(48).ifBlank { "chat" }
}

private fun stripHiddenFusionBlocks(content: String): String =
    com.projectnuke.fusion.util.exportMessageBody(content)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.2f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.2f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun archiveAuthenticators(): Int {
    return BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
}

private fun Context.findActivity(): Activity? {
    var currentContext: Context? = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}

private fun isArchiveAuthenticationAvailable(context: Context): Boolean {
    if (context.findActivity() == null) return false
    return runCatching {
        val biometricManager = context.getSystemService(BiometricManager::class.java) ?: return false
        biometricManager.canAuthenticate(archiveAuthenticators()) == BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)
}

private fun authenticateArchiveAccess(
    context: Context,
    onSuccess: () -> Unit,
    onCanceled: () -> Unit,
    onError: () -> Unit
) {
    val activity = context.findActivity() ?: run {
        onError()
        return
    }

    runCatching {
        val cancellationSignal = CancellationSignal()
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("본인 인증")
            .setSubtitle("아카이브를 열려면 인증이 필요합니다.")
            .setAllowedAuthenticators(archiveAuthenticators())
            .build()

        prompt.authenticate(
            cancellationSignal,
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                    ) {
                        onCanceled()
                    } else {
                        onError()
                    }
                }
            }
        )
    }.onFailure {
        onError()
    }
}

private suspend fun buildFusionDebugInfo(
    context: android.content.Context,
    dao: com.projectnuke.fusion.data.ChatDao,
    projectDao: com.projectnuke.fusion.data.ProjectDao,
    benchmarkDao: BenchmarkDao,
    currentConversationId: Long,
    modelName: String,
    modelPath: String?,
    accelerator: String,
    maxTokens: Int,
    topK: Int,
    topP: Float,
    temperature: Float,
    reasoningBudgetTokens: Int,
    reasoningEnabled: Boolean,
    webSearchEnabled: Boolean,
    speculativeEnabled: Boolean
): String {
    val appInfo = getAppInfoSummary(context)
    val latestBenchmark = runCatching { benchmarkDao.getLatest() }.getOrNull()
    val socInfo = collectFusionSocInfo()

    val modelFile = modelPath?.let { File(it) }
    val modelExists = modelFile?.exists() == true
    val modelSize = if (modelExists) formatBytes(modelFile?.length() ?: 0L) else "unknown"

    val attachmentDir = AttachmentStorageManager.getAttachmentDirectory(context)
    val attachmentStats = runCatching {
        AttachmentStorageManager.calculateAttachmentStorageStats(context, dao, projectDao)
    }.getOrNull()

    val messageCount = if (currentConversationId > 0L) {
        runCatching { dao.getMessageCountForConversation(currentConversationId) }.getOrNull()
    } else null
    val totalConversations = runCatching { dao.getConversationCount() }.getOrNull()
    val archivedConversations = runCatching { dao.getArchivedConversationCount() }.getOrNull()

    return buildString {
        appendLine("Fusion Debug Info")
        appendLine("-----------------")
        appendLine("Package: ${context.packageName}")
        appendLine("App version: ${appInfo.versionName} (${appInfo.versionCode})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Hardware: ${socInfo.hardware.ifBlank { "unknown" }}")
        appendLine("Board: ${socInfo.board.ifBlank { "unknown" }}")
        appendLine("SoC manufacturer: ${socInfo.socManufacturer.ifBlank { "unknown" }}")
        appendLine("SoC model: ${socInfo.socModel.ifBlank { "unknown" }}")
        appendLine("Detected SoC vendor: ${socInfo.detectedSocVendor.name}")
        appendLine()
        appendLine("Model")
        appendLine("- Name: $modelName")
        appendLine("- Path: ${modelPath ?: "none"}")
        appendLine("- Exists: $modelExists")
        appendLine("- Size: $modelSize")
        appendLine()
        appendLine("Generation Settings")
        appendLine("- Accelerator: $accelerator")
        appendLine("- Max tokens: $maxTokens")
        appendLine("- TopK: $topK")
        appendLine("- TopP: $topP")
        appendLine("- Temperature: $temperature")
        appendLine("- Reasoning budget tokens: $reasoningBudgetTokens")
        appendLine("- Reasoning enabled: $reasoningEnabled")
        appendLine("- Web search enabled: $webSearchEnabled")
        appendLine("- MTP enabled/requested: $speculativeEnabled")
        appendLine("- MTP status: ${if (speculativeEnabled) "requested" else "off"}")
        appendLine()
        appendLine("Runtime")
        appendLine("- Latest status: unavailable")
        appendLine("- Latest metrics: unavailable")
        appendLine("- Latest runtime error: unavailable")
        appendLine("- Streaming state: unavailable")
        appendLine("- Web search pipeline: enabled")
        appendLine()
        appendLine("Latest Benchmark")
        if (latestBenchmark != null) {
            appendLine("- Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(latestBenchmark.createdAt))}")
            appendLine("- Model: ${latestBenchmark.modelName}")
            appendLine("- Accelerator: ${latestBenchmark.selectedTextBackend ?: latestBenchmark.accelerator}")
            appendLine("- MTP: ${latestBenchmark.mtpStatus}")
            appendLine("- Total tok/s: ${latestBenchmark.totalTokensPerSecond}")
            appendLine("- Decode tok/s: ${latestBenchmark.decodeTokensPerSecond ?: "unknown"}")
            appendLine("- Success: ${latestBenchmark.success}")
        } else {
            appendLine("- None")
        }
        appendLine()
        appendLine("Storage")
        appendLine("- Attachment dir: ${attachmentDir.absolutePath}")
        appendLine("- Attachment files: ${attachmentStats?.totalFiles ?: "unknown"}")
        appendLine("- Attachment total size: ${attachmentStats?.let { formatBytes(it.totalBytes) } ?: "unknown"}")
        appendLine()
        appendLine("Database")
        appendLine("- Current conversation id: $currentConversationId")
        appendLine("- Messages in current conversation: ${messageCount ?: "unknown"}")
        appendLine("- Total conversations: ${totalConversations ?: "unknown"}")
        appendLine("- Archived conversations: ${archivedConversations ?: "unknown"}")
    }.trimEnd()
}

@Composable
private fun rememberAppInfo(context: Context): AppInfoSummary {
    return remember(context) { getAppInfoSummary(context) }
}

private fun getAppInfoSummary(context: Context): AppInfoSummary {
    val info = resolveFusionDiagnosticsAppInfo(context)
    return AppInfoSummary(versionName = info.versionName, versionCode = info.versionCode)
}
