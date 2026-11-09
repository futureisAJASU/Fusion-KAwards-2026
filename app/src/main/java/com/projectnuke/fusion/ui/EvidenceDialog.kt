package com.projectnuke.fusion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.projectnuke.fusion.data.*
import com.projectnuke.fusion.audit.auditCoverageLabel
import com.projectnuke.fusion.audit.auditProvenanceLabel
import com.projectnuke.fusion.audit.auditSemanticSectionLabel
import com.projectnuke.fusion.audit.auditSemanticStatusLabel
import com.projectnuke.fusion.audit.auditSummaryRows
import com.projectnuke.fusion.audit.projectEvidenceReport
import com.projectnuke.fusion.translation.LocalTranslationCoordinator
import com.projectnuke.fusion.util.SafeWebUrlPolicy

internal class EvidenceSurfaceDismissAction(
    private val dismissEvidence: () -> Unit,
    private val dismissTranslation: () -> Unit,
) {
    fun dismiss() {
        dismissEvidence()
        dismissTranslation()
    }
}

internal class EvidenceSurfaceOpenAction(
    private val openTranslation: (EvidenceAnswerIdentity) -> Unit,
    private val openEvidence: () -> Unit,
) {
    fun open(answer: EvidenceAnswerIdentity) {
        openTranslation(answer)
        openEvidence()
    }
}

@Composable
internal fun EvidenceMessageAction(
    message: MessageEntity,
    store: EvidenceUiStore,
    verifier: EvidenceUiVerifier,
    translationCoordinator: LocalTranslationCoordinator,
    onOpenUrl: (String) -> Unit,
    conversationId: Long = message.conversationId,
    openTrigger: Long = 0L,
    expectedSurfaceIdentity: AnswerSurfaceIdentity = AnswerSurfaceIdentity.of(conversationId, message),
    isCurrentAnswer: () -> Boolean = { true },
) {
    EvidenceMessageActionHost(
        message = message,
        store = store,
        verifier = verifier,
        translationCoordinator = translationCoordinator,
        onOpenUrl = onOpenUrl,
        conversationId = conversationId,
        openTrigger = openTrigger,
        expectedSurfaceIdentity = expectedSurfaceIdentity,
        isCurrentAnswer = isCurrentAnswer,
    ) { onOpen ->
        TextButton(onClick = onOpen) {
            Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("사실 확인")
        }
    }
}

/**
 * Single Evidence ownership host for ONE assistant message.
 *
 * Owns exactly one [EvidenceUiController] (+ its translation session) and the
 * durable [EvidenceDialog] for it, and accepts a pluggable `trigger` slot.
 * This lets the SAME durable/verifier/dialog path be surfaced by different
 * affordances (the legacy action row, or the NORMAL-mode answer utility rail)
 * without ever composing a second controller, verifier, store or dialog host
 * for the same message. Exactly one host is composed per message at a time.
 *
 * Identity is [EvidenceAnswerIdentity] = (messageId, sha256(content)) so a
 * response-version switch changes the message content, the recomposed
 * identity no longer matches any in-flight controller state, and a stale
 * Evidence report can never surface under a different visible answer.
 */
@Composable
internal fun EvidenceMessageActionHost(
    message: MessageEntity,
    store: EvidenceUiStore,
    verifier: EvidenceUiVerifier,
    translationCoordinator: LocalTranslationCoordinator,
    onOpenUrl: (String) -> Unit,
    conversationId: Long = message.conversationId,
    openTrigger: Long = 0L,
    expectedSurfaceIdentity: AnswerSurfaceIdentity = AnswerSurfaceIdentity.of(conversationId, message),
    isCurrentAnswer: () -> Boolean = { true },
    trigger: @Composable (onOpenEvidence: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val identity = remember(message.id, message.content, conversationId) { EvidenceAnswerIdentity.of(message) }
    val dao = remember(context.applicationContext) {
        com.projectnuke.fusion.data.AppDatabase.getInstance(context.applicationContext).chatDao()
    }
    val controller = remember(identity, expectedSurfaceIdentity, store, verifier) {
        PostAnswerFactVerificationOwner(
            scope,
            store,
            verifier,
            privateTextsProvider = {
                com.projectnuke.fusion.privacy.DataEgressPrivateTextLedger.currentTexts()
            },
            currentMessageReader = { expected -> dao.getMessageById(expected.id) },
            isCurrentAnswer = {
                isCurrentAnswer() &&
                    expectedSurfaceIdentity == AnswerSurfaceIdentity.of(conversationId, message)
            },
        )
    }
    val translationController = remember(identity, translationCoordinator) {
        EvidenceTranslationUiController(scope, translationCoordinator)
    }
    val dismissEvidenceSurface = remember(controller, translationController) {
        EvidenceSurfaceDismissAction(controller::dismiss, translationController::dismiss)
    }
    val openEvidenceSurface = remember(controller, translationController, message) {
        EvidenceSurfaceOpenAction(translationController::openSession) { controller.open(message) }
    }
    val searchPrompt by controller.searchPrompt.collectAsState()
    val state by controller.state.collectAsState()
    val translationState by translationController.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(identity) {
        translationController.openSession(identity)
    }
    LaunchedEffect(openTrigger) {
        if (openTrigger > 0) {
            controller.open(message)
        }
    }
    DisposableEffect(controller, translationController, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                dismissEvidenceSurface.dismiss()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            dismissEvidenceSurface.dismiss()
        }
    }
    val prompt = searchPrompt
    if (prompt != null) {
        EvidenceSearchAuthorizationDialog(
            prompt = prompt,
            onCancel = {
                controller.chooseSearch(prompt.operationId, PostAnswerVerificationSearchChoice.CANCEL)
            },
            onExistingSourcesOnly = {
                controller.chooseSearch(prompt.operationId, PostAnswerVerificationSearchChoice.EXISTING_SOURCES_ONLY)
            },
            onWebSearch = {
                controller.chooseSearch(prompt.operationId, PostAnswerVerificationSearchChoice.WEB)
            },
        )
    }
    trigger { openEvidenceSurface.open(identity) }
    if (state.identity == identity && state.execution != EvidenceExecutionState.AWAITING_SEARCH_AUTHORIZATION) {
        EvidenceDialog(
            state = state,
            translationContext = EvidenceTranslationPresentationContext(
                answer = identity,
                controller = translationController,
                state = translationState,
            ),
            onRetry = controller::retry,
            onCancel = controller::cancel,
            onDismiss = dismissEvidenceSurface::dismiss,
            onOpenUrl = onOpenUrl,
        )
    }
}

@Composable
internal fun EvidenceDialog(
    state: EvidenceUiState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    translationContext: EvidenceTranslationPresentationContext? = null,
) {
    FusionReadingDialog(
        onDismissRequest = onDismiss,
        title = { Text("사실 확인") },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("확인 가능한 답변 주장을 연결된 자료와 대조합니다.")
                Text("자료와의 일치 여부는 답변 전체의 사실성을 보장하지 않습니다.")
                state.notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                when (state.execution) {
                    EvidenceExecutionState.PLANNING -> {
                        FusionBusyLine()
                        Text("확인할 주장을 정리하고 있습니다.")
                    }
                    EvidenceExecutionState.AWAITING_SEARCH_AUTHORIZATION -> Unit
                    EvidenceExecutionState.CHECKING -> {
                        FusionBusyLine()
                        Text("주장과 근거를 대조하고 있습니다.")
                    }
                    EvidenceExecutionState.FAILED -> Text("사실 확인을 완료하지 못했습니다. 다시 시도할 수 있습니다.")
                    EvidenceExecutionState.CANCELLED -> Text("사실 확인을 취소했습니다.")
                    EvidenceExecutionState.IDLE -> when (state.stored) {
                        EvidenceStoredState.OUTDATED -> Text(outdatedMessage(state.outdatedReason))
                        EvidenceStoredState.STALE -> Text("이 답변이 변경되어 이전 근거 확인 결과를 사용할 수 없습니다.")
                        EvidenceStoredState.CORRUPT -> Text("저장된 근거 확인 결과를 읽을 수 없습니다. 기존 자료를 유지하며 다시 확인할 수 있습니다.")
                        else -> Unit
                    }
                    EvidenceExecutionState.COMPLETE -> state.report?.let { report ->
                        val auditProjection = projectEvidenceReport(
                            report,
                            semanticVerificationsByClaimId = state.transientAudit?.claims
                                ?.associateBy { it.claimId }
                                .orEmpty(),
                        )
                        if (report.verifierVersion != EVIDENCE_VERIFIER_VERSION) {
                            Text(
                                "\uADFC\uAC70 \uD655\uC778 \uADDC\uCE59\uC774 \uC5C5\uB370\uC774\uD2B8\uB418\uC5B4 \uC774\uC804 \uACB0\uACFC\uB97C \uB2E4\uC2DC \uD655\uC778\uD574\uC57C \uD569\uB2C8\uB2E4.",
                            )
                        } else {
                        Text("\uD655\uC778 \uC2DC\uAC01: " + formatCheckedAt(report.checkedAt))
                        state.transientAudit?.let { snapshot ->
                            val reportClaimsById = report.claims.associateBy { it.id }
                            val visibleSemanticClaims = snapshot.displayableClaims
                                .filter { it.claimId in reportClaimsById }
                            auditSemanticSectionLabel(snapshot)?.takeIf { visibleSemanticClaims.isNotEmpty() }?.let { label ->
                                Text(label, style = MaterialTheme.typography.titleSmall)
                                visibleSemanticClaims.forEach { semantic ->
                                    val claimText = reportClaimsById[semantic.claimId]?.text.orEmpty()
                                    auditSemanticStatusLabel(semantic.aggregateStatus)?.let { status ->
                                        Text("$claimText: $status", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        if (report.claims.isEmpty()) {
                            Text(
                                if (report.candidateWindowTruncated || report.overlengthSegmentsExcluded) {
                                    "기록된 확인 주장은 없지만, 답변 일부가 확인 범위에 포함되지 않았어요. 아래 제한 안내를 확인해 주세요."
                                } else {
                                    "확인할 사실 주장을 찾지 못했습니다."
                                },
                            )
                        } else {
                            Text("기록된 주장 ${report.claims.size}개")
                            auditProjection?.coverage?.let(::auditCoverageLabel)?.let { Text(it) }
                            auditProjection?.let(::auditSummaryRows)?.forEach { row ->
                                Text("${row.label}  ${row.count}")
                            }
                            val uncheckedCount = report.claims.count { it.verdict == EvidenceVerdict.NOT_CHECKED }
                            if (uncheckedCount > 0) {
                                Text("\uD655\uC778\uD558\uC9C0 \uC54A\uC740 \uC8FC\uC7A5: $uncheckedCount / ${report.claims.size}")
                            }
                        }
                        if (evidenceCandidateWindowWarningVisible(report)) {
                            Text(
                                "확인 후보는 최대 ${EvidencePlanningLimits.MAX_CLAIM_CANDIDATES}개입니다. 그보다 많은 후보는 확인 범위에서 빠질 수 있어요.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (report.overlengthSegmentsExcluded) {
                            Text(
                                "개별 확인 문장이 허용 길이(${EvidencePlanningLimits.MAX_CLAIM_TEXT_LENGTH}자)를 넘어 일부 문장을 확인하지 않았어요.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (report.claims.isNotEmpty()) {
                            report.claims.forEach { claim ->
                                key(claim.id) {
                                    EvidenceClaimRow(
                                        claim,
                                        report,
                                        auditProjection?.claims?.firstOrNull { it.id == claim.id },
                                        translationContext,
                                        onOpenUrl,
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = {
            if (state.execution == EvidenceExecutionState.CHECKING || state.execution == EvidenceExecutionState.PLANNING) {
                TextButton(onClick = onCancel) { Text("취소") }
            } else {
                TextButton(onClick = onRetry) { Text("다시 확인") }
            }
        },
    )
}

internal fun evidenceCandidateWindowWarningVisible(report: EvidenceReport): Boolean =
    report.candidateWindowTruncated

internal fun evidenceOverlengthWarningVisible(report: EvidenceReport): Boolean =
    report.overlengthSegmentsExcluded

@Composable
private fun EvidenceClaimRow(
    claim: EvidenceClaim,
    report: EvidenceReport,
    auditClaim: com.projectnuke.fusion.audit.AnswerAuditClaim?,
    translationContext: EvidenceTranslationPresentationContext?,
    onOpenUrl: (String) -> Unit,
) {
    var expanded by remember(report, claim.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap)) {
            Text(claim.text)
            Text(claim.verdict.evidenceLabel(), style = MaterialTheme.typography.labelLarge)
            evidenceClaimReasonLabel(claim.reasonCode)?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            auditClaim?.let { Text(auditProvenanceLabel(it.provenanceTypes), style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "자료 접기" else "자료 보기")
            }
            if (expanded) {
                report.sources.filter { it.id in claim.sourceIds }.forEach { source ->
                    EvidenceSourceCard(source, onOpenUrl, translationContext)
                }
                if (claim.sourceIds.isEmpty()) Text("참조할 검색 자료가 없습니다.")
            }
        }
    }
}

@Composable
internal fun EvidenceSourceCard(
    source: EvidenceSource,
    onOpenUrl: (String) -> Unit,
    translationContext: EvidenceTranslationPresentationContext? = null,
) {
    val identity = translationContext?.let {
        EvidenceTranslationIdentity.of(it.answer, source)
    }
    val presentation = translationContext?.state?.presentations?.get(identity)
        ?: EvidenceTranslationPresentation()
    val translated = presentation.artifact?.translatedText
    val showTranslated = presentation.state == EvidenceTranslationPresentationState.TRANSLATED &&
        !presentation.showingOriginal &&
        !translated.isNullOrBlank()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap)) {
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap)) {
            Text(source.title)
            Text(if (showTranslated) translated.orEmpty() else source.snippet)
            if (showTranslated) {
                Text("기기 내 번역 · 참고용", style = MaterialTheme.typography.bodySmall)
            }
            if (translationContext != null && isEvidenceTranslationEligibleSnippet(source.snippet)) {
                when (presentation.state) {
                    EvidenceTranslationPresentationState.TRANSLATING -> Text(
                        "번역 중…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    EvidenceTranslationPresentationState.UNAVAILABLE -> Text(
                        "기기 내 번역을 사용할 수 없습니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Unit
                }
                TextButton(
                    enabled = presentation.state != EvidenceTranslationPresentationState.TRANSLATING,
                    onClick = {
                        translationContext.controller.toggle(translationContext.answer, source)
                    },
                ) {
                    Text(
                        if (showTranslated) "원문 보기" else "번역 보기",
                    )
                }
            }
            Text(source.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            source.publishedAt?.let { Text("게시일: $it", style = MaterialTheme.typography.bodySmall) }
            var details by remember(source.id) { mutableStateOf(false) }
            TextButton(onClick = { details = !details }) { Text(if (details) "출처 정보 접기" else "출처 정보") }
            if (details) {
                Text("검색 제공자: ${source.providerDisplayName}", style = MaterialTheme.typography.bodySmall)
                Text("검색어 (검색 기록): ${source.queryUsed}", style = MaterialTheme.typography.bodySmall)
                Text(source.url, style = MaterialTheme.typography.bodySmall)
            }
            if (isEvidenceWebUrl(source.url)) {
                TextButton(onClick = { onOpenUrl(source.url) }) { Text("원문 열기") }
            }
        }
    }
}

internal fun isEvidenceWebUrl(url: String): Boolean {
    return SafeWebUrlPolicy.normalize(url) != null
}

/** Localized OUTDATED copy that distinguishes why the result is outdated. */
internal fun outdatedMessage(reason: EvidenceOutdatedReason?): String = when (reason) {
    EvidenceOutdatedReason.TIME_EXPIRED ->
        "이 답변은 최신 정보 확인이 필요한 내용이라 이전 근거 확인 결과가 만료되었습니다."
    EvidenceOutdatedReason.CLOCK_INVALID ->
        "확인 시각이 현재 시각보다 앞서 있어 이전 근거 확인 결과를 다시 확인해야 합니다."
    EvidenceOutdatedReason.RULES_CHANGED, null ->
        "\uADFC\uAC70 \uD655\uC778 \uADDC\uCE59\uC774 \uC5C5\uB370\uC774\uD2B8\uB418\uC5B4 \uC774\uC804 \uACB0\uACFC\uB97C \uB2E4\uC2DC \uD655\uC778\uD574\uC57C \uD569\uB2C8\uB2E4."
}

@Composable
internal fun EvidenceSearchAuthorizationDialog(
    prompt: PostAnswerVerificationSearchPrompt,
    onCancel: () -> Unit,
    onExistingSourcesOnly: () -> Unit,
    onWebSearch: () -> Unit,
    selectedClaimText: String? = null,
) {
    FusionConfirmationDialog(
        onDismissRequest = onCancel,
        title = { Text("웹에서도 확인할까요?") },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap),
            ) {
                if (prompt.queries.isNotEmpty()) {
                    Text("웹 검색에 사용할 검색어:", style = MaterialTheme.typography.labelMedium)
                    prompt.queries.forEach { approved ->
                        Text(approved.query, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "기존 출처로 부족한 근거를 웹에서 더 찾을 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "답변이나 문서 내용은 보내지 않고, 표시된 검색어만 전송합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selectedClaimText?.takeIf { it.isNotBlank() }?.let { claim ->
                    Text("선택한 문장:", style = MaterialTheme.typography.labelMedium)
                    Text(claim, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        alternativeButton = {
            Button(onClick = onExistingSourcesOnly, shape = FusionSpacing.objectShape,
                modifier = Modifier.testTag("evidence-primary").fillMaxWidth().heightIn(min = FusionSpacing.control)) {
                Text("기존 출처만 확인")
            }
        },
        confirmButton = {
            TextButton(onClick = onWebSearch, modifier = Modifier.heightIn(min = FusionSpacing.control)) {
                Text("웹에서도 확인")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = FusionSpacing.control)) {
                Text("취소")
            }
        },
    )
}
