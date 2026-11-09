package com.projectnuke.fusion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.projectnuke.fusion.chat.SelectionContextSnapshot
import com.projectnuke.fusion.chat.SelectionSnapshotResult
import com.projectnuke.fusion.chat.SelectionSourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Work Plan step intent while this selection surface is bound to a running attempt. */
internal enum class FusionSearchWorkPlanIntent { COMPARE, EXTRACT, REVIEW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FusionSearchSurface(
    includeArchived: Boolean,
    onDismiss: () -> Unit,
    onNavigate: (KnowledgeTarget) -> Unit,
    searchSource: FusionKnowledgeSource? = null,
    onQuestionWithSelection: (SelectionContextSnapshot) -> Unit = {},
    onCompareWithSelection: (SelectionContextSnapshot) -> Unit = {},
    onCreateReviewCard: (SelectionContextSnapshot) -> Unit = {},
    onResearchWithProjects: (SelectionContextSnapshot) -> Unit = {},
    onNoteSourcesSelected: (SelectionContextSnapshot) -> Unit = {},
    onQuickAction: (com.projectnuke.fusion.search.FusionQuickAction) -> Unit = {},
    workPlanIntent: FusionSearchWorkPlanIntent? = null,
    onWorkPlanSelection: (SelectionContextSnapshot) -> Unit = {},
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val latestArchive = rememberUpdatedState(includeArchived)
    val repository = remember(searchSource) { searchSource ?: FusionKnowledgeSearchRepository(context.applicationContext) { latestArchive.value } }
    // Access revocation switches to an empty controller before any result row can render.
    var rawQuery by remember { mutableStateOf("") }
    var unavailable by remember { mutableStateOf(false) }
    var noExcerpt by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var accessRevision by remember { mutableIntStateOf(0) }
    val latestAccessRevision = rememberUpdatedState(accessRevision)
    // Finding A: the Work Plan owns selection mode while bound. Filter or
    // archive changes clear the transient selection but must preserve the
    // mandatory Work Plan mode; ordinary search keeps the legacy reset.
    val workPlanSelectionBound = workPlanIntent != null
    var selectionMode by remember(workPlanIntent) {
        mutableStateOf(
            com.projectnuke.fusion.projects.ProjectWorkPlanSearchSelectionPolicy
                .selectionModeOwnedByWorkPlan(workPlanSelectionBound),
        )
    }
    var filters by remember { mutableStateOf(KnowledgeSearchFilters()) }
    var draftFilters by remember { mutableStateOf(KnowledgeSearchFilters()) }
    var showFilters by remember { mutableStateOf(false) }
    var filterOptions by remember { mutableStateOf(KnowledgeSearchFilterOptions()) }
    var filterOptionsFailed by remember { mutableStateOf(false) }
    val latestFilters = rememberUpdatedState(filters)
    var showSelectionActions by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, KnowledgeCandidate>() }
    val controller = remember(includeArchived, repository) {
        FusionSearchController(
            scope,
            search = { query -> repository.search(query, latestFilters.value) },
            searchWithStatus = { query -> repository.searchWithStatus(query, latestFilters.value) },
        )
    }
    val state by controller.state.collectAsState()
    LaunchedEffect(includeArchived, filters, workPlanSelectionBound) {
        accessRevision++
        opening = false
        selected.clear()
        selectionMode =
            com.projectnuke.fusion.projects.ProjectWorkPlanSearchSelectionPolicy
                .modeAfterFilterReset(workPlanSelectionBound)
        controller.query(rawQuery)
    }
    DisposableEffect(controller) { onDispose { controller.close() } }

    fun accessRequestIsCurrent(revision: Int, archiveSnapshot: Boolean, filterSnapshot: KnowledgeSearchFilters): Boolean =
        latestAccessRevision.value == revision && latestArchive.value == archiveSnapshot && latestFilters.value == filterSnapshot

    fun openFilters() {
        draftFilters = filters
        showFilters = true
        filterOptionsFailed = false
        scope.launch {
            try {
                filterOptions = repository.filterOptions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                filterOptionsFailed = true
            }
        }
    }

    fun select(result: KnowledgeCandidate) {
        if (result.key in selected) {
            selected.remove(result.key)
            unavailable = false
            noExcerpt = false
            return
        }
        if (selected.size >= SelectionContextSnapshot.MAX_SOURCES) {
            unavailable = true
            return
        }
        val candidateIdentity = result.toSelectionSourceSnapshot()?.identity?.stableKey() ?: run {
            noExcerpt = true
            return
        }
        val selectedIdentity = selected.entries.firstOrNull {
            it.value.toSelectionSourceSnapshot()?.identity?.stableKey() == candidateIdentity
        }
        if (selectedIdentity != null) {
            selected.remove(selectedIdentity.key)
            unavailable = false
            noExcerpt = false
            return
        }
        unavailable = false
        noExcerpt = false
        opening = true
        val requestRevision = accessRevision
        val archiveSnapshot = includeArchived
        val filterSnapshot = filters
        scope.launch {
            try {
                val accessible = admitKnowledgeNavigation { repository.accessible(result.target) }
                if (!accessRequestIsCurrent(requestRevision, archiveSnapshot, filterSnapshot)) return@launch
                if (accessible) {
                    if (result.toSelectionSourceSnapshot()?.excerpt?.isNotBlank() == true) {
                        selected[result.key] = result.copy()
                        unavailable = false
                        noExcerpt = false
                    } else noExcerpt = true
                } else unavailable = true
            } finally {
                if (accessRequestIsCurrent(requestRevision, archiveSnapshot, filterSnapshot)) opening = false
            }
        }
    }

    fun freezeSelection(
        onFrozen: (SelectionContextSnapshot) -> Unit,
        purpose: com.projectnuke.fusion.chat.SelectionContextPurpose = com.projectnuke.fusion.chat.SelectionContextPurpose.QUESTION,
    ) {
        if (opening || selected.isEmpty()) return
        unavailable = false
        noExcerpt = false
        val frozen = selected.values.map { it.copy() }
        opening = true
        val requestRevision = accessRevision
        val archiveSnapshot = includeArchived
        val filterSnapshot = filters
        scope.launch {
            try {
                val accessible = frozen.all { result ->
                    admitKnowledgeNavigation { repository.accessible(result.target) }
                }
                if (!accessRequestIsCurrent(requestRevision, archiveSnapshot, filterSnapshot)) return@launch
                if (!accessible) {
                    unavailable = true
                    return@launch
                }
                val sources = frozen.mapNotNull(KnowledgeCandidate::toSelectionSourceSnapshot)
                when (val result = SelectionContextSnapshot.create(
                    sources = sources,
                    createdAt = System.currentTimeMillis(),
                    archiveAccessIncluded = archiveSnapshot,
                    purpose = purpose,
                )) {
                    is SelectionSnapshotResult.Valid -> onFrozen(result.snapshot)
                    SelectionSnapshotResult.Invalid -> noExcerpt = true
                }
            } finally {
                if (accessRequestIsCurrent(requestRevision, archiveSnapshot, filterSnapshot)) opening = false
            }
        }
    }

    fun handoffSelection() = freezeSelection(onQuestionWithSelection)

    fun handoffComparison() {
        if (selected.size !in 2..4) return
        freezeSelection(onCompareWithSelection)
    }

    fun handoffWorkPlanSelection() = freezeSelection(onWorkPlanSelection)

    val selectedProjectResearch = selected.size in 2..4 && selected.values.all {
        val target = it.target as? KnowledgeTarget.Project
        target != null && target.fileId == null
    }

    val selectedNoteSources = selected.size in 1..4 && selected.values.all { candidate ->
        candidate.toSelectionSourceSnapshot()?.identity?.kind?.let {
            it in setOf(SelectionSourceKind.CONVERSATION, SelectionSourceKind.PROJECT,
                SelectionSourceKind.PROJECT_DOCUMENT, SelectionSourceKind.SAVED_KNOWLEDGE)
        } == true
    }

    fun handoffProjectResearch() = freezeSelection(
        onFrozen = onResearchWithProjects,
        purpose = com.projectnuke.fusion.chat.SelectionContextPurpose.CROSS_PROJECT_RESEARCH,
    )

    fun handoffNoteSources() = freezeSelection(
        onFrozen = onNoteSourcesSelected,
        purpose = com.projectnuke.fusion.chat.SelectionContextPurpose.NOTE_REFERENCES,
    )

    fun handoffReviewCard() {
        if (opening || selected.size != 1) return
        val candidate = selected.values.single()
        val source = candidate.toSelectionSourceSnapshot() ?: run { noExcerpt = true; return }
        if (source.identity.kind != SelectionSourceKind.PROJECT_DOCUMENT &&
            source.identity.kind != SelectionSourceKind.SAVED_KNOWLEDGE
        ) return
        opening = true
        val requestRevision = accessRevision
        val archiveSnapshot = includeArchived
        val filterSnapshot = filters
        scope.launch {
            try {
                val accessible = admitKnowledgeNavigation { repository.accessible(candidate.target) }
                if (!accessRequestIsCurrent(requestRevision, archiveSnapshot, filterSnapshot)) return@launch
                if (!accessible) {
                    unavailable = true
                    return@launch
                }
                when (val snapshot = SelectionContextSnapshot.create(
                    sources = listOf(source),
                    createdAt = System.currentTimeMillis(),
                    archiveAccessIncluded = archiveSnapshot,
                )) {
                    is SelectionSnapshotResult.Valid -> onCreateReviewCard(snapshot.snapshot)
                    SelectionSnapshotResult.Invalid -> noExcerpt = true
                }
            } finally {
                if (accessRequestIsCurrent(requestRevision, archiveSnapshot, filterSnapshot)) opening = false
            }
        }
    }

    val selectedReviewCardSource = selected.values.singleOrNull()?.toSelectionSourceSnapshot()
        ?.identity?.kind in setOf(SelectionSourceKind.PROJECT_DOCUMENT, SelectionSourceKind.SAVED_KNOWLEDGE)
    val quickAction = com.projectnuke.fusion.search.FusionQuickActionRegistry.resolve(rawQuery)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Fusion 검색") }, navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "검색 닫기") }
                }, actions = {
                    if (com.projectnuke.fusion.projects.ProjectWorkPlanSearchSelectionPolicy
                            .manualToggleVisible(workPlanSelectionBound)
                    ) TextButton(onClick = {
                        if (!selectionMode) keyboardController?.hide()
                        selectionMode = !selectionMode
                        selected.clear()
                        unavailable = false
                        noExcerpt = false
                    }, enabled = !opening) { Text(if (selectionMode) "취소" else "선택") }
                })
            },
            bottomBar = {
                if (selectionMode) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("${selected.size}개 선택", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            if (workPlanIntent == null && (selected.size in 2..4 || selectedReviewCardSource || selectedProjectResearch || selectedNoteSources)) {
                                Box {
                                    TextButton(onClick = { showSelectionActions = true }, enabled = !opening) {
                                        Text("추가 작업")
                                    }
                                    DropdownMenu(
                                        expanded = showSelectionActions,
                                        onDismissRequest = { showSelectionActions = false },
                                    ) {
                                        if (selected.size in 2..4) DropdownMenuItem(
                                            text = { Text("자료 비교") },
                                            onClick = { showSelectionActions = false; handoffComparison() },
                                        )
                                        if (selectedProjectResearch) DropdownMenuItem(
                                            text = { Text("함께 조사") },
                                            onClick = { showSelectionActions = false; handoffProjectResearch() },
                                        )
                                        if (selectedNoteSources) DropdownMenuItem(
                                            text = { Text("출처 삽입") },
                                            onClick = { showSelectionActions = false; handoffNoteSources() },
                                        )
                                        if (selectedReviewCardSource) DropdownMenuItem(
                                            text = { Text("복습 카드") },
                                            onClick = { showSelectionActions = false; handoffReviewCard() },
                                        )
                                    }
                                }
                            }
                        }
                        when (workPlanIntent) {
                            FusionSearchWorkPlanIntent.COMPARE -> Button(
                                onClick = ::handoffWorkPlanSelection,
                                enabled = selected.size in 2..4 && !opening,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("선택한 자료 비교") }
                            FusionSearchWorkPlanIntent.EXTRACT -> Button(
                                onClick = ::handoffWorkPlanSelection,
                                enabled = selected.size == 1 && !opening,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("이 자료로 추출") }
                            FusionSearchWorkPlanIntent.REVIEW -> Button(
                                onClick = ::handoffWorkPlanSelection,
                                enabled = selected.isNotEmpty() && !opening,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("이 자료로 확인") }
                            null -> Button(
                                onClick = ::handoffSelection,
                                enabled = selected.isNotEmpty() && !opening,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("선택한 자료로 질문") }
                        }
                    }
                }
            },
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(value = rawQuery, onValueChange = {
                        rawQuery = it.take(100)
                        unavailable = false
                        noExcerpt = false
                        controller.query(rawQuery)
                    }, label = { Text("검색어") }, placeholder = { Text("대화와 자료 검색") },
                        leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                        modifier = Modifier.weight(1f).padding(start = 16.dp))
                    TextButton(onClick = ::openFilters, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text(if (filters.activeCount > 0) "필터 · ${filters.activeCount}" else "필터")
                    }
                }
                if (state.loading) FusionBusyLine(Modifier.fillMaxWidth().padding(top = 8.dp))
                val status = when {
                    unavailable && selectionMode && selected.size >= SelectionContextSnapshot.MAX_SOURCES -> "최대 ${SelectionContextSnapshot.MAX_SOURCES}개 자료를 선택할 수 있습니다."
                    noExcerpt -> "이 결과에서 질문에 사용할 발췌를 만들 수 없습니다."
                    unavailable -> "항목이 삭제되었거나 지금 열 수 없습니다."
                    state.error -> "검색할 수 없습니다. 검색어를 다시 입력해 주세요."
                    state.query.length < 2 -> "두 글자 이상 입력해 주세요."
                    !state.loading && state.results.isEmpty() && state.savedKnowledgeScanIncomplete -> "현재 확인한 범위에서 검색 결과를 찾지 못했어요."
                    !state.loading && state.results.isEmpty() -> "검색 결과가 없습니다."
                    else -> null
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.savedKnowledgeScanIncomplete) {
                    Text(
                        "저장 지식의 일부만 확인했어요. 확인하지 못한 항목에 일치하는 결과가 있을 수 있어요.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.savedKnowledgeAdditionalResults) {
                    Text(
                        "접근 가능한 저장 지식 일치 항목이 더 있어요. 검색어 또는 필터를 좁혀 주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (state.savedKnowledgeResultLimitReached && !state.savedKnowledgeScanIncomplete) {
                    Text(
                        "저장 지식은 유형별 관련도 순으로 일부 결과를 보여 드려요.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    quickAction?.let { action ->
                        item(key = "quick-actions-heading") {
                            Text("빠른 실행", style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
                        }
                        item(key = "quick-action:${action.name}") {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !opening) {
                                    keyboardController?.hide()
                                    onQuickAction(action)
                                }.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(action.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(action.subtitle, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("실행", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                    KnowledgeKind.entries.forEach { kind ->
                        val rows = state.results.filter { it.kind == kind }
                        if (rows.isNotEmpty()) {
                            item(key = "heading:${kind.name}") {
                                Text(kind.label, style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() })
                            }
                            items(rows, key = { it.key }) { result ->
                                val selectedIdentityKey = result.toSelectionSourceSnapshot()?.identity?.stableKey()
                                val isSelected = result.key in selected || selected.values.any {
                                    it.toSelectionSourceSnapshot()?.identity?.stableKey() == selectedIdentityKey
                                }
                                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = !opening) {
                                    if (selectionMode) select(result) else {
                                        opening = true
                                        val requestRevision = accessRevision
                                        val archiveSnapshot = includeArchived
                                        val filterSnapshot = filters
                                        scope.launch {
                                            try {
                                                val accessible = admitKnowledgeNavigation { repository.accessible(result.target) }
                                                if (!accessRequestIsCurrent(requestRevision, archiveSnapshot, filterSnapshot)) return@launch
                                                if (accessible) onNavigate(result.target)
                                                else unavailable = true
                                            } finally {
                                                if (accessRequestIsCurrent(requestRevision, archiveSnapshot, filterSnapshot)) opening = false
                                            }
                                        }
                                    }
                                }.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(result.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(result.metadata, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (result.preview.isNotBlank()) Text(result.preview, style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (selectionMode) Checkbox(checked = isSelected, onCheckedChange = null, enabled = false)
                                }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                    if (state.results.isNotEmpty()) item {
                        Text("본문과 의미 검색은 최근 항목을 대상으로 합니다.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("검색 필터", style = MaterialTheme.typography.titleLarge)
                if (filterOptionsFailed) Text("일부 필터를 불러오지 못했습니다.", color = MaterialTheme.colorScheme.error)
                Text("유형", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = draftFilters.kind == null, onClick = { draftFilters = draftFilters.copy(kind = null) }, label = { Text("모든 유형") })
                    KnowledgeKind.entries.forEach { kind ->
                        FilterChip(selected = draftFilters.kind == kind, onClick = {
                            draftFilters = draftFilters.copy(kind = if (draftFilters.kind == kind) null else kind)
                        }, label = { Text(kind.label) })
                    }
                }
                if (filterOptions.projects.isNotEmpty()) {
                    Text("Project", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draftFilters.projectId == null, onClick = { draftFilters = draftFilters.copy(projectId = null) }, label = { Text("모든 Project") })
                        filterOptions.projects.forEach { project ->
                            FilterChip(selected = draftFilters.projectId == project.id, onClick = {
                                draftFilters = draftFilters.copy(projectId = if (draftFilters.projectId == project.id) null else project.id)
                            }, label = { Text(project.name.take(32)) })
                        }
                    }
                }
                if (filterOptions.tags.isNotEmpty()) {
                    Text("태그", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draftFilters.tagKey == null, onClick = { draftFilters = draftFilters.copy(tagKey = null) }, label = { Text("모든 태그") })
                        filterOptions.tags.forEach { tag ->
                            FilterChip(selected = draftFilters.tagKey == tag.tagKey, onClick = {
                                draftFilters = draftFilters.copy(tagKey = if (draftFilters.tagKey == tag.tagKey) null else tag.tagKey)
                            }, label = { Text(tag.displayName) })
                        }
                    }
                }
                if (filterOptions.collections.isNotEmpty()) {
                    Text("모음", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draftFilters.collectionId == null, onClick = { draftFilters = draftFilters.copy(collectionId = null) }, label = { Text("모든 모음") })
                        filterOptions.collections.forEach { collection ->
                            FilterChip(selected = draftFilters.collectionId == collection.collectionId, onClick = {
                                draftFilters = draftFilters.copy(collectionId = if (draftFilters.collectionId == collection.collectionId) null else collection.collectionId)
                            }, label = { Text(collection.displayName.take(40)) })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { draftFilters = KnowledgeSearchFilters() }) { Text("초기화") }
                    Button(onClick = { filters = draftFilters; showFilters = false }, enabled = !filterOptionsFailed) { Text("적용") }
                }
            }
        }
    }
}
