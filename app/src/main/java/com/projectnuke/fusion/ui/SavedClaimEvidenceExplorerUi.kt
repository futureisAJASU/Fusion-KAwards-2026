package com.projectnuke.fusion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceReportCompatibilityPolicy
import com.projectnuke.fusion.data.EvidenceStore
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.formatCheckedAt
import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

/** Single contextual entry label shown in the existing message overflow (⋮). */
internal const val SAVED_CLAIMS_OPEN_LABEL = "기록된 주장 보기"

/** Read-only explorer title. */
internal const val SAVED_CLAIMS_TITLE = "주장별 근거"

/** Empty state when no persisted report exists for the exact answer. */
internal const val SAVED_CLAIMS_MISSING_LABEL = "저장된 검증 기록이 없습니다"

/** Conservative state for a dangling/unavailable source reference. */
internal const val SAVED_CLAIMS_MISSING_SOURCE_LABEL = "연결된 자료를 찾을 수 없습니다"

/** Explicit reminder that stored browsing never auto-runs and is not auto-refreshed. */
internal const val SAVED_CLAIMS_READ_ONLY_NOTE = "저장된 검증 기록을 보여줍니다. 이 화면은 기록을 자동으로 갱신하지 않습니다."

/** Reused verdict-semantics caveat: a relationship to checked materials, not proof. */
internal const val SAVED_CLAIMS_VERDICT_CAVEAT = "근거 판정은 확인한 자료와의 관계를 나타내며, 답변 전체의 사실성이나 절대적 사실 판정이 아닙니다."

/**
 * The single explorer host for ONE assistant message.
 *
 * It owns exactly one [SavedClaimEvidenceExplorerOwner] (read-only loads only)
 * and — when a [verifier] is supplied — one narrowly scoped
 * [SelectedClaimReverificationOwner] for the explicit UX-P2B "이 주장 다시 확인"
 * action. The read-only explorer owner still performs no verification; the
 * selected-claim owner is a SEPARATE component that runs only on an explicit
 * user action, for exactly one stored claim, and never persists anything.
 *
 * Identity is keyed to the exact [AnswerSurfaceIdentity] so a
 * response-version/conversation switch disposes the explorer and a stale report
 * or transient result can never surface under a different answer.
 */
@Composable
internal fun SavedClaimEvidenceExplorerHost(
    message: MessageEntity,
    store: EvidenceUiStore,
    onOpenUrl: (String) -> Unit,
    conversationId: Long = message.conversationId,
    openTrigger: Long = 0L,
    expectedSurfaceIdentity: AnswerSurfaceIdentity = AnswerSurfaceIdentity.of(conversationId, message),
    isCurrentAnswer: () -> Boolean = { true },
    verifier: EvidenceUiVerifier? = null,
    privateTextsProvider: (() -> List<String>)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val identity = remember(message.id, message.content, conversationId) {
        EvidenceAnswerIdentity.of(message)
    }
    val dao = remember(context.applicationContext) {
        com.projectnuke.fusion.data.AppDatabase.getInstance(context.applicationContext).chatDao()
    }
    val owner = remember(identity, expectedSurfaceIdentity, store) {
        SavedClaimEvidenceExplorerOwner(
            scope = scope,
            store = store,
            currentMessageReader = { expected -> dao.getMessageById(expected.id) },
            isCurrentAnswer = {
                isCurrentAnswer() &&
                    expectedSurfaceIdentity == AnswerSurfaceIdentity.of(conversationId, message)
            },
        )
    }
    val reverifyOwner = remember(identity, expectedSurfaceIdentity, store, verifier, privateTextsProvider) {
        verifier?.let { activeVerifier ->
            SelectedClaimReverificationOwner(
                scope = scope,
                verifier = activeVerifier,
                privateTextsProvider = privateTextsProvider,
                currentMessageReader = { expected -> dao.getMessageById(expected.id) },
                isCurrentAnswer = {
                    isCurrentAnswer() &&
                        expectedSurfaceIdentity == AnswerSurfaceIdentity.of(conversationId, message)
                },
                // Reads the LIVE selected claim; a claim switch invalidates the
                // in-flight operation at its next ownership check.
                isSelectedClaimCurrent = { claimId ->
                    (owner.state.value as? SavedClaimExplorerState.Ready)?.selectedClaimId == claimId
                },
            )
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(openTrigger) {
        if (openTrigger > 0L) owner.open(message)
    }
    DisposableEffect(owner, reverifyOwner, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                owner.dismiss()
                reverifyOwner?.dismiss()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            owner.dismiss()
            reverifyOwner?.dismiss()
        }
    }
    val state by owner.state.collectAsState()
    val selectedClaimId = (state as? SavedClaimExplorerState.Ready)?.selectedClaimId
    // Switching claims (or returning to the list) clears any transient result.
    LaunchedEffect(selectedClaimId) { reverifyOwner?.cancel() }
    val idleReverification = remember {
        kotlinx.coroutines.flow.MutableStateFlow<SelectedClaimReverificationState>(
            SelectedClaimReverificationState.Idle,
        )
    }
    val reverificationState by (reverifyOwner?.state ?: idleReverification).collectAsState()
    val consent = reverificationState as? SelectedClaimReverificationState.AwaitingConsent
    if (consent != null && reverifyOwner != null) {
        EvidenceSearchAuthorizationDialog(
            prompt = PostAnswerVerificationSearchPrompt(
                operationId = consent.operationId,
                identity = consent.identity,
                queries = listOf(consent.query),
            ),
            onCancel = { reverifyOwner.choose(consent.operationId, SelectedClaimSourceChoice.CANCEL) },
            onExistingSourcesOnly = {
                reverifyOwner.choose(consent.operationId, SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY)
            },
            onWebSearch = { reverifyOwner.choose(consent.operationId, SelectedClaimSourceChoice.WEB) },
        )
    } else if (state !is SavedClaimExplorerState.Closed) {
        SavedClaimEvidenceDialog(
            state = state,
            reverificationState = reverificationState,
            reverifyAvailable = reverifyOwner != null,
            onReverify = { claimId ->
                val ready = owner.state.value as? SavedClaimExplorerState.Ready
                if (ready != null) reverifyOwner?.reverify(message, identity, ready.report, claimId)
            },
            onReverifyCancel = { reverifyOwner?.cancel() },
            onSelectClaim = owner::selectClaim,
            onBackToClaims = owner::backToClaims,
            onDismiss = owner::dismiss,
            onOpenUrl = onOpenUrl,
        )
    }
}

/** Explorer dialog: claims list, focused claim detail (with UX-P2B action), or fail-closed copy. */
@Composable
internal fun SavedClaimEvidenceDialog(
    state: SavedClaimExplorerState,
    onSelectClaim: (String) -> Unit,
    onBackToClaims: () -> Unit,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    reverificationState: SelectedClaimReverificationState = SelectedClaimReverificationState.Idle,
    reverifyAvailable: Boolean = false,
    onReverify: (String) -> Unit = {},
    onReverifyCancel: () -> Unit = {},
) {
    val renderState = (state as? SavedClaimExplorerState.Ready)?.let { ready ->
        val nowMillis = System.currentTimeMillis()
        if (EvidenceReportCompatibilityPolicy.isCurrentDisplayCompatible(
                ready.report,
                ready.identity.answerSha256,
                nowMillis,
            )
        ) {
            ready
        } else {
            SavedClaimExplorerState.Unavailable(
                identity = ready.identity,
                absence = SavedClaimAbsence.OUTDATED,
                outdatedReason = EvidenceReportCompatibilityPolicy.outdatedReason(
                    ready.report,
                    ready.identity.answerSha256,
                    nowMillis,
                ),
            )
        }
    } ?: state
    val ready = renderState as? SavedClaimExplorerState.Ready
    val selected = ready?.selectedClaimId?.let { id ->
        SavedClaimEvidenceProjection.detailOf(ready.report, id)
    }
    val listScroll = rememberScrollState()
    val detailScroll = remember(ready?.identity, selected?.claimId) { ScrollState(0) }
    val navigateBack = if (selected != null) onBackToClaims else onDismiss
    BackHandler(enabled = selected != null, onBack = onBackToClaims)
    FusionReadingDialog(
        onDismissRequest = navigateBack,
        title = { Text(SAVED_CLAIMS_TITLE) },
        scrollBody = false,
        text = {
            Column(
                Modifier.fillMaxSize().verticalScroll(if (selected == null) listScroll else detailScroll),
                verticalArrangement = Arrangement.spacedBy(EvidenceUiSpacing.row),
            ) {
                when (renderState) {
                    SavedClaimExplorerState.Closed, is SavedClaimExplorerState.Loading -> {
                        FusionBusyLine()
                        Text("저장된 검증 기록을 불러오는 중입니다.")
                    }
                    is SavedClaimExplorerState.Unavailable -> {
                        Text(SAVED_CLAIMS_READ_ONLY_NOTE)
                        SavedClaimAbsenceMessage(renderState)
                    }
                    is SavedClaimExplorerState.Ready -> if (selected == null) {
                        SavedClaimsList(renderState, onSelectClaim)
                    } else {
                        SavedClaimDetailView(
                            state = renderState,
                            detail = selected,
                            onOpenUrl = onOpenUrl,
                            reverificationState = reverificationState,
                            reverifyAvailable = reverifyAvailable,
                            onReverify = onReverify,
                            onReverifyCancel = onReverifyCancel,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun SavedClaimAbsenceMessage(state: SavedClaimExplorerState.Unavailable) {
    val message = when (state.absence) {
        SavedClaimAbsence.MISSING -> SAVED_CLAIMS_MISSING_LABEL
        SavedClaimAbsence.STALE -> "이 답변이 변경되어 이전 근거 확인 결과를 사용할 수 없습니다."
        SavedClaimAbsence.OUTDATED -> outdatedMessage(state.outdatedReason)
        SavedClaimAbsence.CORRUPT -> "저장된 근거 확인 결과를 읽을 수 없습니다."
        SavedClaimAbsence.READ_FAILED -> "저장된 검증 기록을 불러오지 못했습니다."
    }
    Text(message)
    Text(
        "이전 기록은 삭제하지 않고 그대로 보존합니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SavedClaimsList(
    state: SavedClaimExplorerState.Ready,
    onSelectClaim: (String) -> Unit,
) {
    val summaries = remember(state.report) {
        SavedClaimEvidenceProjection.summariesOf(state.report)
    }
    if (summaries.isNotEmpty()) Text("기록된 주장 ${summaries.size}개")
    SavedClaimEvidenceProjection.coverageGuidance(state.report)?.let {
        EvidenceHeading(it.label)
    }
    if (state.report.candidateWindowTruncated || state.report.overlengthSegmentsExcluded) {
        Text("일부 주장은 검증 범위에서 제외되었습니다.", style = MaterialTheme.typography.bodySmall)
    }
    summaries.forEach { summary ->
        key(summary.claimId) {
            SavedClaimRow(summary = summary, onClick = { onSelectClaim(summary.claimId) })
        }
    }
    EvidenceDisclosure("검증 범위와 한계", state.identity) {
        SavedClaimCoverageLine(state.report)
        Text(SAVED_CLAIMS_READ_ONLY_NOTE)
        Text(SAVED_CLAIMS_VERDICT_CAVEAT, style = MaterialTheme.typography.bodySmall)
        SavedClaimLimitationLines(state.report)
    }
}

@Composable
private fun SavedClaimRow(summary: SavedClaimSummary, onClick: () -> Unit) {
    val semantic = LocalFusionSemanticColors.current
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = semantic.divider, thickness = FusionSpacing.dividerThickness)
        FluidPressableSurface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FusionSpacing.menuItemMinHeight)
                .semantics(mergeDescendants = true) {},
            containerColor = Color.Transparent,
            contentColor = semantic.onSurface,
            contentPadding = PaddingValues(horizontal = EvidenceUiSpacing.related, vertical = EvidenceUiSpacing.row),
            role = Role.Button,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EvidenceUiSpacing.inline)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(EvidenceUiSpacing.related)) {
                    Text(summary.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        summary.verdict.evidenceLabel() + " · 자료 ${summary.sourceCount}개",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null,
                    modifier = Modifier.size(EvidenceUiSpacing.navigationIcon), tint = semantic.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SavedClaimDetailView(
    state: SavedClaimExplorerState.Ready,
    detail: SavedClaimDetail,
    onOpenUrl: (String) -> Unit,
    reverificationState: SelectedClaimReverificationState,
    reverifyAvailable: Boolean,
    onReverify: (String) -> Unit,
    onReverifyCancel: () -> Unit,
) {
    val activeTransientResult = (reverificationState as? SelectedClaimReverificationState.Result)
        ?.takeIf { it.claimId == detail.claimId }
    var inspected by remember(state.identity, state.report, detail.claimId, activeTransientResult) {
        mutableStateOf<String?>(null)
    }
    // The result owns the one exact-claim display while it is visible.
    if (activeTransientResult == null) {
        Text(detail.text, style = MaterialTheme.typography.bodyLarge)
        EvidenceHeading(SELECTED_CLAIM_STORED_LABEL)
        Text(detail.verdict.evidenceLabel(), style = MaterialTheme.typography.titleSmall)
        Text("확인 시각: ${formatCheckedAt(state.report.checkedAt)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (reverifyAvailable) {
        SelectedClaimReverificationSection(
            claimId = detail.claimId, state = reverificationState, identity = state.identity,
            storedReport = state.report, onReverify = onReverify,
            onCancel = onReverifyCancel, onOpenUrl = onOpenUrl,
        )
    }
    if (activeTransientResult == null) {
        if (detail.sources.isEmpty()) {
            Text("이 주장에 연결된 자료가 없습니다.")
        } else EvidenceDisclosure("연결된 자료 ${detail.sources.size}개",
            expanded = inspected == "sources", onExpandedChange = { inspected = if (it) "sources" else null }) {
            detail.sources.forEach { view ->
                view.source?.let { source ->
                    EvidenceDisclosure(source.title.ifBlank { "자료" }, source) {
                        EvidenceSourceCard(source = source, onOpenUrl = onOpenUrl)
                    }
                } ?: Text(SAVED_CLAIMS_MISSING_SOURCE_LABEL + " (참조: " + view.danglingReferenceId + ")")
            }
        }
        if (detail.sources.isNotEmpty()) EvidenceDisclosure(CLAIM_EVIDENCE_COMPARE_LABEL,
            expanded = inspected == "comparison", onExpandedChange = { inspected = if (it) "comparison" else null }) {
            ClaimEvidenceSourcePairComparison(
                identity = state.identity, report = state.report,
                origin = ClaimEvidenceReportOrigin.STORED, claimId = detail.claimId,
                onOpenUrl = onOpenUrl, title = "저장 자료 비교",
            )
        }
        EvidenceDisclosure("검증 범위와 한계", listOf(state.identity, detail.claimId)) {
            evidenceClaimReasonLabel(detail.reasonCode)?.let { Text(it) }
            Text(SAVED_CLAIMS_READ_ONLY_NOTE, style = MaterialTheme.typography.bodySmall)
            Text(SAVED_CLAIMS_VERDICT_CAVEAT, style = MaterialTheme.typography.bodySmall)
            SavedClaimCoverageLine(state.report)
            SavedClaimLimitationLines(state.report)
        }
    }
}

@Composable
private fun SavedClaimCoverageLine(report: EvidenceReport) {
    val guidance = remember(report) {
        SavedClaimEvidenceProjection.coverageGuidance(report)
    } ?: return
    Text(guidance.label, style = MaterialTheme.typography.titleSmall)
    Text(
        guidance.guidance,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SavedClaimLimitationLines(report: EvidenceReport) {
    val lines = remember(report) { SavedClaimEvidenceProjection.limitationLines(report) }
    lines.forEach { line ->
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
