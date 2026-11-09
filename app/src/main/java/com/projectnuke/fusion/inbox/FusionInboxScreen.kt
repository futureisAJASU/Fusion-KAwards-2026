package com.projectnuke.fusion.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.projectnuke.fusion.chat.ChatViewModel
import com.projectnuke.fusion.chat.PendingAttachmentIdentity
import com.projectnuke.fusion.chat.PreparedExternalHandoff
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectFileEntity
import com.projectnuke.fusion.data.ProjectTaskInsertResult
import com.projectnuke.fusion.data.ReviewCardInsertResult
import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.knowledge.SavedKnowledgePolicy
import com.projectnuke.fusion.knowledge.SavedKnowledgeProvenance
import com.projectnuke.fusion.knowledge.WebClipCapture
import com.projectnuke.fusion.knowledge.WebClipFetchResult
import com.projectnuke.fusion.knowledge.WebClipFetcher
import com.projectnuke.fusion.knowledge.WebClipRepository
import com.projectnuke.fusion.knowledge.WebClipSaveResult
import com.projectnuke.fusion.knowledge.KnowledgeOrganizationRepository
import com.projectnuke.fusion.knowledge.OrganizationObjectKind
import com.projectnuke.fusion.knowledge.OrganizationWriteResult
import com.projectnuke.fusion.retrieval.KoEnE5RetrievalOwner
import com.projectnuke.fusion.ui.WebClipPreviewDialog
import com.projectnuke.fusion.ui.WebClipPreviewState
import com.projectnuke.fusion.ui.KnowledgeOrganizationDialog
import com.projectnuke.fusion.study.QuickReviewCardDraft
import com.projectnuke.fusion.study.QuickReviewCardPolicy
import com.projectnuke.fusion.study.QuickReviewCardCaptureController
import com.projectnuke.fusion.study.ReviewCardOrigin
import com.projectnuke.fusion.tasks.ProjectTaskCaptureController
import com.projectnuke.fusion.tasks.ProjectTaskDraft
import com.projectnuke.fusion.tasks.ProjectTaskPolicy
import com.projectnuke.fusion.ui.ProjectTaskCaptureSurface
import com.projectnuke.fusion.ui.StudyReviewCardCaptureSurface
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class InboxAction { PROJECT, TASK }

@Composable
internal fun FusionInboxScreen(
    database: AppDatabase,
    chatViewModel: ChatViewModel,
    conversationId: Long,
    includeArchived: () -> Boolean,
    onBack: () -> Unit,
    onCloseDrawer: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(database) { InboxRepository(database) }
    val inboxItems by repository.observeLatest().collectAsStateWithLifecycle(initialValue = emptyList())
    val count by database.inboxItemDao().observeCount().collectAsState(initial = 0)
    val projects by database.projectDao().observeProjects().collectAsStateWithLifecycle(initialValue = emptyList())
    var showNoteDialog by remember { mutableStateOf(false) }
    var noteTitle by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var confirmationItem by remember { mutableStateOf<InboxItemEntity?>(null) }
    var organizationItem by remember { mutableStateOf<InboxItemEntity?>(null) }
    var projectAction by remember { mutableStateOf<Pair<InboxItemEntity, InboxAction>?>(null) }
    var webClipPreview by remember { mutableStateOf<WebClipPreviewState?>(null) }
    var webClipSaving by remember { mutableStateOf(false) }
    var webClipJob by remember { mutableStateOf<Job?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateMapOf<String, InboxItemEntity>() }
    var bulkMenuExpanded by remember { mutableStateOf(false) }
    var bulkProjectDialog by remember { mutableStateOf(false) }
    var bulkTagDialog by remember { mutableStateOf(false) }
    var bulkTagText by remember { mutableStateOf("") }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var triageInFlight by remember { mutableStateOf(false) }
    var triageSettlements by remember { mutableStateOf<List<InboxTriageSettlement>?>(null) }
    var smartTriageTarget by remember { mutableStateOf<InboxItemEntity?>(null) }
    var smartTriageProposal by remember { mutableStateOf<InboxSmartTriageProposal?>(null) }
    var smartTriageLoading by remember { mutableStateOf(false) }
    var smartTriageJob by remember { mutableStateOf<Job?>(null) }
    var smartTriageKey by remember { mutableStateOf<String?>(null) }
    var smartRecommendedProjectId by remember { mutableStateOf<Long?>(null) }
    var projectPickerFromSmart by remember { mutableStateOf(false) }
    var smartTaskSnapshot by remember { mutableStateOf<InboxItemEntity?>(null) }
    var smartReviewSnapshot by remember { mutableStateOf<InboxItemEntity?>(null) }
    var batchReviewDialog by remember { mutableStateOf(false) }
    var batchReviewReturnToQueue by remember { mutableStateOf(false) }
    val webClipFetcher = remember { WebClipFetcher() }
    val webClipRepository = remember(database) { WebClipRepository(database) }
    val triageRepository = remember(database, context) {
        InboxTriageRepository(database) { managedId ->
            AttachmentStorageManager.resolveManagedAttachment(context, managedId) != null
        }
    }
    // Smart Triage v1: candidate generation only. The coordinator owns no
    // mutation path; confirmed actions below reuse the existing flows.
    // Semantic ranking stays on-device through the shared E5 owner.
    val smartDataSource = remember(database) { RoomInboxSmartTriageDataSource(database, includeArchived) }
    val smartCoordinator = remember(smartDataSource) { InboxSmartTriageCoordinator(smartDataSource) }
    val smartEmbeddings = remember {
        KoEnE5RetrievalOwner.symmetricProxy(context.assets, File(context.filesDir, "koen-e5"))
    }
    val batchProposalSource = remember(smartCoordinator, smartEmbeddings) {
        InboxSmartBatchReviewCoordinator(smartCoordinator, smartEmbeddings)
    }
    val batchReviewController = remember(batchProposalSource, scope) {
        InboxSmartBatchReviewController(batchProposalSource, scope)
    }
    val batchReviewState by batchReviewController.state.collectAsState()
    DisposableEffect(batchReviewController) {
        onDispose { batchReviewController.dispose() }
    }
    // Reviewed-action settlement: the existing editable capture controllers
    // own the draft UI; these narrow gateways own the exact-snapshot Inbox
    // move (destination insert + exact Inbox delete, one transaction) and
    // only run on explicit Save. Dismiss never writes.
    val smartTaskStore = remember(database) { RoomInboxTaskSettlementStore(database) }
    val smartTaskGateway = remember(smartTaskStore) { InboxTaskSettlementGateway(smartTaskStore) }
    val smartTaskController = remember(smartTaskGateway) {
        ProjectTaskCaptureController(smartTaskGateway, scope)
    }
    val smartReviewStore = remember(database) { RoomInboxReviewCardSettlementStore(database) }
    val smartReviewGateway = remember(smartReviewStore) { InboxReviewCardSettlementGateway(smartReviewStore) }
    val smartReviewController = remember(smartReviewGateway) {
        QuickReviewCardCaptureController(smartReviewGateway, scope)
    }
    val smartTaskState by smartTaskController.state.collectAsState()
    val smartReviewState by smartReviewController.state.collectAsState()

    fun toast(message: String) = android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()

    fun dismissBatchReview() {
        batchReviewController.dismiss()
        batchReviewDialog = false
        batchReviewReturnToQueue = false
    }

    fun toggleSelection(item: InboxItemEntity, selected: Boolean) {
        if (selected) selectedItems[item.id] = item else selectedItems.remove(item.id)
    }

    fun applyTriage(action: InboxTriageAction) {
        if (triageInFlight || selectedItems.isEmpty()) return
        val frozenItems = selectedItems.values.toList()
        triageInFlight = true
        scope.launch {
            try {
                triageSettlements = triageRepository.apply(frozenItems, action)
                selectedItems.clear()
                selectionMode = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toast("Inbox 작업을 마치지 못했습니다. 항목을 다시 확인해 주세요.")
            } finally {
                triageInFlight = false
            }
        }
    }

    fun startWebClip(url: String) {
        val requestId = UUID.randomUUID().toString()
        webClipJob?.cancel()
        webClipPreview = WebClipPreviewState.Loading(requestId, url)
        webClipJob = scope.launch {
            when (val result = webClipFetcher.fetch(url)) {
                is WebClipFetchResult.Captured -> {
                    if (webClipPreview?.requestId == requestId) {
                        webClipPreview = WebClipPreviewState.Ready(requestId, url, result.capture)
                    }
                }
                is WebClipFetchResult.Failed -> {
                    if (webClipPreview?.requestId == requestId) {
                        webClipPreview = WebClipPreviewState.Failed(requestId, url, result.reason)
                    }
                }
            }
        }
    }

    fun dismissWebClip() {
        webClipJob?.cancel()
        webClipJob = null
        webClipPreview = null
        webClipSaving = false
    }

    fun saveWebClip(capture: WebClipCapture, projectId: Long? = null, toInbox: Boolean = false) {
        if (webClipSaving) return
        val requestId = webClipPreview?.requestId ?: return
        webClipSaving = true
        scope.launch {
            try {
                val result = if (toInbox) webClipRepository.saveToInbox(capture)
                else webClipRepository.saveToKnowledge(capture, projectId)
                if (webClipPreview?.requestId != requestId) return@launch
                when (result) {
                    is WebClipSaveResult.Stored -> {
                        dismissWebClip()
                        toast(when {
                            toInbox -> "웹 페이지 스냅샷을 Inbox에 저장했습니다."
                            projectId != null -> "웹 페이지 스냅샷을 Project Knowledge에 저장했습니다."
                            else -> "웹 페이지 스냅샷을 저장 지식에 저장했습니다."
                        })
                    }
                    WebClipSaveResult.Invalid -> toast("웹 페이지 스냅샷을 저장할 수 없습니다.")
                    WebClipSaveResult.SensitiveContent -> toast("민감한 정보가 감지되어 저장하지 않았습니다.")
                    WebClipSaveResult.CapacityReached -> toast("Inbox가 가득 찼습니다. 먼저 항목을 정리해 주세요.")
                    WebClipSaveResult.Failed -> toast("웹 페이지 스냅샷을 저장하지 못했습니다.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toast("웹 페이지 스냅샷을 저장하지 못했습니다.")
            } finally {
                if (webClipPreview?.requestId == requestId) webClipSaving = false
            }
        }
    }

    fun deleteAfterSuccessfulAction(item: InboxItemEntity, action: String, save: suspend () -> Boolean) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    database.withTransaction {
                        if (database.inboxItemDao().get(item.id) != item) return@withTransaction false
                        if (!save()) return@withTransaction false
                        database.inboxItemDao().delete(item.id) == 1
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
            toast(if (result) action else "작업을 완료하지 못했습니다. Inbox 항목을 유지했습니다.")
        }
    }

    fun moveToKnowledge(item: InboxItemEntity, project: ProjectEntity?) {
        val now = System.currentTimeMillis()
        val saved = SavedKnowledgeEntity(
            id = UUID.randomUUID().toString(),
            scope = if (project == null) "PERSONAL" else "PROJECT",
            projectId = project?.id,
            title = item.title.take(SavedKnowledgePolicy.MAX_TITLE_CHARS),
            savedText = item.content.take(SavedKnowledgePolicy.MAX_TEXT_CHARS),
            provenanceType = SavedKnowledgeProvenance.INBOX_CAPTURE.name,
            sourceConversationId = null,
            sourceMessageId = null,
            createdAt = now,
            updatedAt = now,
            sourceUrl = item.originalUri,
            effectiveSourceUrl = item.effectiveSourceUri,
        )
        deleteAfterSuccessfulAction(item, if (project == null) "Saved Knowledge로 옮겼습니다." else "Project에 추가했습니다.") {
            database.savedKnowledgeDao().insert(saved)
            true
        }
    }

    fun createReviewCard(item: InboxItemEntity) {
        val now = System.currentTimeMillis()
        val prepared = QuickReviewCardPolicy.prepare(
            QuickReviewCardDraft(front = item.title.take(QuickReviewCardPolicy.MAX_FRONT_CHARS),
                back = item.content, sourceKind = ReviewCardOrigin.USER_TEXT),
            item.title.take(QuickReviewCardPolicy.MAX_FRONT_CHARS), item.content, now,
        )
        if (prepared is com.projectnuke.fusion.study.QuickReviewCardPreparation.Ready) {
            val card = QuickReviewCardPolicy.toEntity(prepared.proposal)
            deleteAfterSuccessfulAction(item, "복습 카드로 옮겼습니다.") {
                database.studyReviewCardDao().insertBounded(card) == ReviewCardInsertResult.Inserted
            }
        } else toast("복습 카드로 저장할 수 없는 내용입니다.")
    }

    fun dismissSmartTriage() {
        val reopenBatchQueue = batchReviewReturnToQueue
        batchReviewReturnToQueue = false
        smartTriageJob?.cancel()
        smartTriageJob = null
        smartTriageTarget = null
        smartTriageProposal = null
        smartTriageKey = null
        smartTriageLoading = false
        if (reopenBatchQueue && batchReviewState.sessionKey != null) batchReviewDialog = true
    }

    fun startSmartTriage(item: InboxItemEntity) {
        // Single-flight per exact item identity + revision: rapid repeated
        // taps on the same revision never launch duplicate embedding work.
        val key = InboxSmartTriagePolicy.keyFor(item)
        if (smartTriageJob?.isActive == true && smartTriageKey == key) return
        smartTriageJob?.cancel()
        smartTriageTarget = item
        smartTriageProposal = null
        smartTriageKey = key
        smartTriageLoading = true
        smartTriageJob = scope.launch {
            val result = try {
                smartCoordinator.proposeSnapshot(item.copy(), smartEmbeddings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            // Late results from an older item/revision never publish.
            if (smartTriageKey != key || smartTriageTarget?.id != item.id) return@launch
            smartTriageLoading = false
            when (result) {
                is InboxSmartTriageResult.Ready -> {
                    if (result.proposal.itemRevision != item.updatedAt) {
                        toast("항목이 바뀌어 정리 제안을 다시 만들었어요.")
                        dismissSmartTriage()
                    } else {
                        smartTriageProposal = result.proposal
                    }
                }
                InboxSmartTriageResult.Stale -> {
                    toast("항목이 바뀌어 정리 제안을 다시 만들었어요.")
                    dismissSmartTriage()
                }
                InboxSmartTriageResult.DuplicateSuppressed -> Unit
                null -> {
                    toast("정리 제안을 만들지 못했습니다.")
                    dismissSmartTriage()
                }
            }
        }
    }

    /** Stale proposals fail closed: they can never invoke a mutation. */
    suspend fun freshSmartItem(
        proposal: InboxSmartTriageProposal,
        snapshot: InboxItemEntity,
    ): InboxItemEntity? = InboxSmartTriageCoordinator.freshItemFor(smartDataSource, proposal, snapshot)

    fun dismissSmartTaskEditor(saved: Boolean) {
        if (saved) toast("Project 할 일로 옮겼습니다.")
        smartTaskController.dismiss()
        smartTaskGateway.snapshot = null
        smartTaskSnapshot = null
    }

    fun dismissSmartReviewEditor(saved: Boolean) {
        if (saved) toast("복습 카드로 옮겼습니다.")
        smartReviewController.dismiss()
        smartReviewGateway.snapshot = null
        smartReviewSnapshot = null
    }

    /** Freezes the exact snapshot and opens the editable Task draft. No insert yet. */
    fun openSmartTaskEditor(item: InboxItemEntity, project: ProjectEntity) {
        smartTaskGateway.snapshot = item
        smartTaskSnapshot = item
        smartTaskController.begin(
            ProjectTaskDraft(project.id, project.name, item.title, item.content),
            archiveAccessIncluded = false,
        )
    }

    /** Freezes the exact snapshot and opens the editable Review Card draft. No insert yet. */
    fun openSmartReviewEditor(item: InboxItemEntity) {
        smartReviewGateway.snapshot = item
        smartReviewSnapshot = item
        smartReviewController.begin(
            QuickReviewCardDraft(
                front = item.title.take(QuickReviewCardPolicy.MAX_FRONT_CHARS),
                back = item.content,
                sourceKind = ReviewCardOrigin.USER_TEXT,
            ),
            archiveAccessIncluded = false,
        )
    }

    fun applySmartPrimary(kind: InboxSmartActionKind) {
        val proposal = smartTriageProposal ?: return
        val snapshot = smartTriageTarget ?: return
        if (proposal.itemId != snapshot.id) return
        scope.launch {
            val live = freshSmartItem(proposal, snapshot)
            if (live == null) {
                toast("항목이 바뀌어 정리 제안을 다시 만들었어요.")
                dismissSmartTriage()
                return@launch
            }
            when (kind) {
                InboxSmartActionKind.SAVED_KNOWLEDGE -> {
                    dismissSmartTriage()
                    moveToKnowledge(live, null)
                }
                InboxSmartActionKind.PROJECT, InboxSmartActionKind.PROJECT_TASK -> {
                    smartRecommendedProjectId = proposal.projectCandidates.firstOrNull()?.projectId
                    projectPickerFromSmart = kind == InboxSmartActionKind.PROJECT_TASK
                    dismissSmartTriage()
                    projectAction = live to if (kind == InboxSmartActionKind.PROJECT) InboxAction.PROJECT else InboxAction.TASK
                }
                InboxSmartActionKind.REVIEW_CARD -> {
                    dismissSmartTriage()
                    openSmartReviewEditor(live)
                }
                InboxSmartActionKind.KEEP_IN_INBOX -> dismissSmartTriage()
            }
        }
    }

    fun openSmartProjectPicker(projectId: Long) {
        val proposal = smartTriageProposal ?: return
        val snapshot = smartTriageTarget ?: return
        if (proposal.projectCandidates.none { it.projectId == projectId }) return
        scope.launch {
            val live = freshSmartItem(proposal, snapshot)
            if (live == null) {
                toast("항목이 바뀌어 정리 제안을 다시 만들었어요.")
                dismissSmartTriage()
                return@launch
            }
            smartRecommendedProjectId = projectId
            projectPickerFromSmart = false
            dismissSmartTriage()
            projectAction = live to InboxAction.PROJECT
        }
    }

    fun openSmartTaskPicker(projectId: Long) {
        val proposal = smartTriageProposal ?: return
        val snapshot = smartTriageTarget ?: return
        if (proposal.projectCandidates.none { it.projectId == projectId }) return
        scope.launch {
            val live = freshSmartItem(proposal, snapshot)
            if (live == null) {
                toast("항목이 바뀌어 정리 제안을 다시 만들었어요.")
                dismissSmartTriage()
                return@launch
            }
            smartRecommendedProjectId = projectId
            // Reviewed path: pick (or change) the Project first, then edit
            // the draft. Nothing is inserted at selection time.
            projectPickerFromSmart = true
            dismissSmartTriage()
            projectAction = live to InboxAction.TASK
        }
    }

    fun applySmartTag(tag: String) {
        val proposal = smartTriageProposal ?: return
        val snapshot = smartTriageTarget ?: return
        if (proposal.tagSuggestions.none { it.value == tag }) return
        scope.launch {
            val live = freshSmartItem(proposal, snapshot)
            if (live == null) {
                toast("항목이 바뀌어 정리 제안을 다시 만들었어요.")
                dismissSmartTriage()
                return@launch
            }
            // Explicit tap only: existing organization repository stays the
            // mutation authority (normalization owned by its policy).
            val outcome = withContext(Dispatchers.IO) {
                try {
                    KnowledgeOrganizationRepository(database).addInboxTag(live.id, tag, expectedItem = live)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            toast(if (outcome == OrganizationWriteResult.Updated) "태그를 추가했습니다." else "태그를 추가하지 못했습니다.")
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("뒤로") }
            Spacer(Modifier.width(12.dp))
            Text("Inbox · $count", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            if (selectionMode) {
                TextButton(onClick = {
                    selectionMode = false
                    selectedItems.clear()
                }, enabled = !triageInFlight) { Text("취소") }
                InboxBulkActions(
                    selectedCount = selectedItems.size,
                    triageInFlight = triageInFlight,
                    expanded = bulkMenuExpanded,
                    onExpandedChange = { bulkMenuExpanded = it },
                    onMoveToProject = { bulkMenuExpanded = false; bulkProjectDialog = true },
                    onMoveToKnowledge = {
                        bulkMenuExpanded = false
                        applyTriage(InboxTriageAction.TO_SAVED_KNOWLEDGE)
                    },
                    onAddTag = {
                        bulkMenuExpanded = false
                        bulkTagText = ""
                        bulkTagDialog = true
                    },
                    onSmartReview = {
                        bulkMenuExpanded = false
                        if (batchReviewController.start(selectedItems.values.toList())) batchReviewDialog = true
                    },
                    onDelete = { bulkMenuExpanded = false; confirmBulkDelete = true },
                )
            } else {
                TextButton(onClick = { selectionMode = true }) { Text("선택") }
                TextButton(onClick = { showNoteDialog = true }) { Text("메모 추가") }
            }
        }
        if (inboxItems.isEmpty()) {
            Text("지금 보관해 둘 자료를 Inbox에 담아 두세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(inboxItems, key = { it.id }) { item ->
                    var menuExpanded by remember(item.id) { mutableStateOf(false) }
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !triageInFlight) {
                                if (selectionMode) toggleSelection(item, item.id !in selectedItems)
                                else menuExpanded = true
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selectionMode) {
                                Checkbox(
                                    checked = item.id in selectedItems,
                                    onCheckedChange = { toggleSelection(item, it) },
                                    enabled = !triageInFlight,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.content.ifBlank { item.managedMimeType?.let { "공유 문서 · $it" } ?: "" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (!selectionMode) IconButton(onClick = { menuExpanded = true }) { Text("⋮") }
                            DropdownMenu(expanded = !selectionMode && menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(text = { Text("정리 제안") }, onClick = {
                                    menuExpanded = false
                                    startSmartTriage(item)
                                })
                                DropdownMenuItem(text = { Text("채팅에서 열기") }, onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        val stored = if (!item.managedFileId.isNullOrBlank()) {
                                            val available = withContext(Dispatchers.IO) {
                                                AttachmentStorageManager.resolveManagedAttachment(context, item.managedFileId) != null
                                            }
                                            available && chatViewModel.adoptPreparedExternalHandoff(
                                                PreparedExternalHandoff(
                                                    eventId = item.id,
                                                    rawInput = "",
                                                    pendingAttachments = listOf(
                                                        PendingAttachmentIdentity(
                                                            item.title,
                                                            item.managedMimeType ?: "application/octet-stream",
                                                            item.managedFileId,
                                                        ),
                                                    ),
                                                    generatedPaths = emptyList(),
                                                ),
                                            ).success
                                        } else {
                                            chatViewModel.appendPromptDurably(conversationId, item.content) { current, added ->
                                                if (current.isBlank()) added else "$current\n\n$added"
                                            }
                                        }
                                        if (stored) onCloseDrawer() else toast("현재 채팅 초안에 자료를 넣지 못했습니다.")
                                    }
                                })
                                if (item.content.isNotBlank()) {
                                    DropdownMenuItem(text = { Text("저장 지식으로 저장") }, onClick = {
                                        menuExpanded = false
                                        moveToKnowledge(item, null)
                                    })
                                }
                                if (item.captureType == InboxCaptureType.URL.name && !item.originalUri.isNullOrBlank()) {
                                    DropdownMenuItem(text = { Text("본문 가져오기") }, onClick = {
                                        menuExpanded = false
                                        startWebClip(item.originalUri)
                                    })
                                }
                                DropdownMenuItem(text = { Text("태그 및 모음") }, onClick = {
                                    menuExpanded = false
                                    organizationItem = item
                                })
                                DropdownMenuItem(text = { Text("Project에 추가") }, onClick = {
                                    menuExpanded = false
                                    projectAction = item to InboxAction.PROJECT
                                })
                                DropdownMenuItem(text = { Text("할 일로 만들기") }, onClick = {
                                    menuExpanded = false
                                    projectAction = item to InboxAction.TASK
                                })
                                if (item.content.isNotBlank()) DropdownMenuItem(text = { Text("복습 카드 만들기") }, onClick = {
                                    menuExpanded = false
                                    createReviewCard(item)
                                })
                        DropdownMenuItem(text = { Text("삭제") }, onClick = {
                                    menuExpanded = false
                                    confirmationItem = item
                                })
                            }
                        }
                        androidx.compose.material3.HorizontalDivider()
                    }
                }
                if (inboxItems.size >= 100) item {
                    Text("최근 100개만 표시합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        }
    }

    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("Inbox 메모") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(noteTitle, { noteTitle = it }, label = { Text("제목") }, singleLine = true)
                    OutlinedTextField(noteText, { noteText = it }, label = { Text("내용") }, minLines = 3)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = noteTitle.trim().ifBlank { noteText.trim().lineSequence().firstOrNull().orEmpty() }
                    scope.launch {
                        when (repository.capture(InboxCapture(InboxCaptureType.NOTE, title, noteText))) {
                            is InboxWriteResult.Stored -> {
                                noteTitle = ""; noteText = ""; showNoteDialog = false
                                toast("Inbox에 보관했습니다.")
                            }
                            InboxWriteResult.SensitiveContent -> toast("민감한 정보가 포함되어 저장하지 않았습니다.")
                            InboxWriteResult.CapacityReached -> toast("Inbox가 가득 찼습니다. 먼저 항목을 정리해 주세요.")
                            else -> toast("메모를 저장하지 못했습니다.")
                        }
                    }
                }) { Text("보관") }
            },
            dismissButton = { TextButton(onClick = { showNoteDialog = false }) { Text("취소") } },
        )
    }

    if (bulkProjectDialog) {
        AlertDialog(
            onDismissRequest = { bulkProjectDialog = false },
            title = { Text("Project에 이동 · ${selectedItems.size}개") },
            text = {
                if (projects.isEmpty()) Text("먼저 Project를 만들어 주세요.")
                else Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    projects.take(100).forEach { project ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !triageInFlight,
                            onClick = {
                                bulkProjectDialog = false
                                applyTriage(InboxTriageAction.TO_PROJECT(project.id))
                            },
                        ) { Text(project.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { bulkProjectDialog = false }) { Text("닫기") } },
        )
    }

    if (bulkTagDialog) {
        AlertDialog(
            onDismissRequest = { bulkTagDialog = false },
            title = { Text("선택한 Inbox에 태그 추가") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${selectedItems.size}개 항목에 같은 태그를 추가합니다.")
                    OutlinedTextField(
                        value = bulkTagText,
                        onValueChange = { bulkTagText = it },
                        label = { Text("태그") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    bulkTagDialog = false
                    applyTriage(InboxTriageAction.ADD_TAG(bulkTagText))
                }, enabled = bulkTagText.isNotBlank() && !triageInFlight) { Text("추가") }
            },
            dismissButton = { TextButton(onClick = { bulkTagDialog = false }) { Text("취소") } },
        )
    }

    if (confirmBulkDelete) {
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("선택한 Inbox 항목을 삭제할까요?") },
            text = { Text("${selectedItems.size}개 항목을 삭제합니다. 삭제한 자료는 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBulkDelete = false
                    applyTriage(InboxTriageAction.DELETE)
                }, enabled = !triageInFlight) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("취소") } },
        )
    }

    triageSettlements?.let { settlements ->
        val summary = InboxTriagePolicy.summarize(settlements)
        AlertDialog(
            onDismissRequest = { triageSettlements = null },
            title = { Text("Inbox 작업 결과") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("완료 ${summary.completed} · 실패 ${summary.failed} · 이미 사라짐 ${summary.missing} / ${summary.total}")
                    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    settlements.forEach { settlement ->
                        Text(
                            "${settlement.title} · ${when (settlement.outcome) {
                                InboxTriageOutcome.COMPLETED -> "완료"
                                InboxTriageOutcome.MISSING -> "대상 없음"
                                InboxTriageOutcome.FAILED -> "실패 · Inbox에 유지됨"
                            }}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { triageSettlements = null }) { Text("닫기") } },
        )
    }

    projectAction?.let { (item, action) ->
        AlertDialog(
            onDismissRequest = { projectAction = null; smartRecommendedProjectId = null; projectPickerFromSmart = false },
            title = { Text(if (action == InboxAction.PROJECT) "Project에 추가" else "할 일로 만들기") },
            text = {
                if (projects.isEmpty()) Text("먼저 Project를 만들어 주세요.")
                else Column {
                    val recommended = smartRecommendedProjectId?.let { id -> projects.firstOrNull { it.id == id } }
                    val ordered = if (recommended != null) {
                        listOf(recommended) + projects.filter { it.id != recommended.id }.take(99)
                    } else projects.take(100)
                    if (recommended != null) {
                        Text(
                            "정리 제안에서 고른 후보를 먼저 보여줘요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ordered.forEach { project ->
                        TextButton(onClick = {
                            projectAction = null
                            smartRecommendedProjectId = null
                            val fromSmart = projectPickerFromSmart
                            projectPickerFromSmart = false
                            if (action == InboxAction.PROJECT) {
                                if (!item.managedFileId.isNullOrBlank()) {
                                    scope.launch {
                                        val managedFileId = item.managedFileId
                                        val fileAvailable = withContext(Dispatchers.IO) {
                                            AttachmentStorageManager.resolveManagedAttachment(context, managedFileId) != null
                                        }
                                        if (!fileAvailable) {
                                            toast("원본 문서를 찾을 수 없어 Project에 추가하지 않았습니다.")
                                        } else {
                                            val projectFile = ProjectFileEntity(
                                                projectId = project.id,
                                                displayName = item.title,
                                                mimeType = item.managedMimeType ?: "application/octet-stream",
                                                localPath = managedFileId,
                                                addedAt = System.currentTimeMillis(),
                                                sourceHandoffEventId = "inbox-${item.id}",
                                            )
                                            deleteAfterSuccessfulAction(item, "Project에 추가했습니다.") {
                                                when (database.projectDao().insertHandoffProjectFileBounded(projectFile)) {
                                                    is com.projectnuke.fusion.data.BoundedProjectFileInsertResult.Inserted,
                                                    is com.projectnuke.fusion.data.BoundedProjectFileInsertResult.Existing -> true
                                                    else -> false
                                                }
                                            }
                                        }
                                    }
                                } else moveToKnowledge(item, project)
                            } else if (fromSmart) {
                                // Reviewed Smart Triage path: choosing a
                                // Project only opens the editable draft.
                                // Nothing is inserted here; explicit Save
                                // settles through the snapshot gateway.
                                // Revalidate at editor-open; the gateway
                                // revalidates again at save time.
                                scope.launch {
                                    val current = withContext(Dispatchers.IO) {
                                        try {
                                            database.inboxItemDao().get(item.id)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }
                                    if (current == null || current != item) {
                                        toast("항목이 바뀌어 정리 제안을 다시 만들었어요.")
                                        return@launch
                                    }
                                    openSmartTaskEditor(current, project)
                                }
                            } else {
                                val prepared = ProjectTaskPolicy.prepare(
                                    ProjectTaskDraft(project.id, project.name, item.title, item.content),
                                    item.title, item.content, System.currentTimeMillis(),
                                )
                                if (prepared is com.projectnuke.fusion.tasks.ProjectTaskPreparation.Ready) {
                                    deleteAfterSuccessfulAction(item, "Project 할 일로 옮겼습니다.") {
                                        when (database.projectTaskDao().insertBounded(ProjectTaskPolicy.entity(prepared.proposal))) {
                                            is ProjectTaskInsertResult.Inserted -> true
                                            ProjectTaskInsertResult.CapacityReached -> false
                                            ProjectTaskInsertResult.ProjectUnavailable -> false
                                        }
                                    }
                                } else toast("할 일을 저장할 수 없는 내용입니다.")
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (project.id == smartRecommendedProjectId) "${project.name} · 추천" else project.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { projectAction = null; smartRecommendedProjectId = null; projectPickerFromSmart = false }) { Text("닫기") } },
        )
    }

    smartTriageTarget?.let {
        if (smartTriageLoading && smartTriageProposal == null) {
            AlertDialog(
                onDismissRequest = ::dismissSmartTriage,
                title = { Text("정리 제안") },
                text = { Text("이 자료를 어디에 정리하면 좋을지 살펴보고 있어요.") },
                confirmButton = { TextButton(onClick = ::dismissSmartTriage) { Text("취소") } },
            )
        }
        smartTriageProposal?.let { proposal ->
            InboxSmartTriageDialog(
                proposal = proposal,
                onPrimary = ::applySmartPrimary,
                onMoveToProject = ::openSmartProjectPicker,
                onMakeTask = ::openSmartTaskPicker,
                onAddTag = ::applySmartTag,
                onDismiss = ::dismissSmartTriage,
            )
        }
    }

    // Reviewed Smart Triage editors: explicit Save settles through the
    // exact-snapshot gateway; dismiss leaves the Inbox untouched.
    if (smartTaskSnapshot != null) {
        ProjectTaskCaptureSurface(
            state = smartTaskState,
            onTitleChange = smartTaskController::updateTitle,
            onNoteChange = smartTaskController::updateNote,
            onSave = smartTaskController::save,
            onDismiss = { dismissSmartTaskEditor(saved = smartTaskState.saved) },
        )
    }
    if (smartReviewSnapshot != null) {
        StudyReviewCardCaptureSurface(
            state = smartReviewState,
            projectName = null,
            onFrontChange = smartReviewController::updateFront,
            onBackChange = smartReviewController::updateBack,
            onScopeChange = smartReviewController::updateScope,
            onSave = { smartReviewController.save() },
            onDismiss = { dismissSmartReviewEditor(saved = false) },
            onSaved = { dismissSmartReviewEditor(saved = true) },
        )
    }

    confirmationItem?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmationItem = null },
            title = { Text("Inbox에서 삭제할까요?") },
            text = { Text("${item.title}을(를) 삭제합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmationItem = null
                    scope.launch { toast(if (repository.delete(item.id)) "Inbox에서 삭제했습니다." else "삭제하지 못했습니다.") }
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmationItem = null }) { Text("취소") } },
        )
    }

    webClipPreview?.let { state ->
        WebClipPreviewDialog(
            state = state,
            projects = projects,
            saving = webClipSaving,
            onDismiss = ::dismissWebClip,
            onRetry = { startWebClip(state.url) },
            onSaveToInbox = {
                (state as? WebClipPreviewState.Ready)?.capture?.let { saveWebClip(it, toInbox = true) }
            },
            onSaveToKnowledge = {
                (state as? WebClipPreviewState.Ready)?.capture?.let { saveWebClip(it) }
            },
            onSaveToProject = { projectId ->
                (state as? WebClipPreviewState.Ready)?.capture?.let { saveWebClip(it, projectId = projectId) }
            },
        )
    }

    organizationItem?.let { item ->
        KnowledgeOrganizationDialog(
            database = database,
            kind = OrganizationObjectKind.INBOX,
            itemId = item.id,
            onDismiss = { organizationItem = null },
            onStatus = ::toast,
        )
    }

    if (batchReviewDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = ::dismissBatchReview,
            title = { Text("선택 항목 정리 제안") },
            text = {
                if (batchReviewState.loading) {
                    Text("정리 제안을 만들고 있어요...")
                } else {
                    val proposals = batchReviewState.proposals
                    if (proposals.isEmpty()) {
                        Text(batchReviewState.failureMessage ?: "정리 제안을 만들지 못했습니다.")
                    } else {
                        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                            proposals.forEach { proposal ->
                                val session = batchReviewState.sessionKey
                                val action = proposal.proposal?.primaryAction
                                val actionLabel = when (action) {
                                    InboxSmartActionKind.KEEP_IN_INBOX -> "Inbox에 유지"
                                    InboxSmartActionKind.SAVED_KNOWLEDGE -> "저장 지식"
                                    InboxSmartActionKind.PROJECT -> "Project"
                                    InboxSmartActionKind.PROJECT_TASK -> "할 일"
                                    InboxSmartActionKind.REVIEW_CARD -> "복습 카드"
                                    null -> null
                                }
                                val statusLabel = when (proposal.status) {
                                    InboxSmartBatchItemStatus.READY -> actionLabel?.let { "제안: $it" } ?: "제안을 사용할 수 없습니다."
                                    InboxSmartBatchItemStatus.STALE -> "항목이 바뀌었습니다. 다시 선택해 주세요."
                                    InboxSmartBatchItemStatus.UNAVAILABLE -> "제안을 만들 수 없습니다."
                                    InboxSmartBatchItemStatus.SKIPPED -> "건너뛰었습니다."
                                    InboxSmartBatchItemStatus.OPENED -> "단일 항목 검토에서 열었습니다."
                                }
                                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                    Text("· ${proposal.snapshot.title}", style = MaterialTheme.typography.bodyMedium)
                                    Text(statusLabel, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row {
                                        if (proposal.status != InboxSmartBatchItemStatus.SKIPPED &&
                                            proposal.status != InboxSmartBatchItemStatus.OPENED
                                        ) {
                                            TextButton(onClick = {
                                                batchReviewController.markSkipped(proposal.itemId, session)
                                            }) { Text("건너뛰기") }
                                        }
                                        if (proposal.status == InboxSmartBatchItemStatus.READY && proposal.proposal != null) {
                                            TextButton(onClick = {
                                                val expectedSession = session ?: return@TextButton
                                                scope.launch {
                                                    if (batchReviewController.state.value.sessionKey != expectedSession) return@launch
                                                    val live = freshSmartItem(proposal.proposal, proposal.snapshot)
                                                    if (live == null) {
                                                        batchReviewController.markStale(proposal.itemId, expectedSession)
                                                        toast("항목이 바뀌어 이 제안을 열지 않았습니다.")
                                                        return@launch
                                                    }
                                                    if (batchReviewController.state.value.sessionKey != expectedSession) return@launch
                                                    batchReviewController.markOpened(proposal.itemId, expectedSession)
                                                    batchReviewReturnToQueue = true
                                                    batchReviewDialog = false
                                                    startSmartTriage(live)
                                                }
                                            }) { Text("이 제안 열기") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = ::dismissBatchReview) { Text("닫기") } },
        )
    }
}
