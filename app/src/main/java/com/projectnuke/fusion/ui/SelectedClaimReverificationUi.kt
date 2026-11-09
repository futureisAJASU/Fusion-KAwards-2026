package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.audit.auditSemanticSectionLabel
import com.projectnuke.fusion.audit.auditSemanticStatusLabel

/** Primary action offered inside the focused stored-claim detail (UX-P2B). */
internal const val SELECTED_CLAIM_REVERIFY_LABEL = "이 주장 다시 확인"

/** In-flight label while one selected claim is being re-verified. */
internal const val SELECTED_CLAIM_REVERIFYING_LABEL = "다시 확인하는 중…"

/** Header of the ephemeral, clearly separated single-claim result section. */
internal const val SELECTED_CLAIM_TEMP_RESULT_LABEL = "이번 확인 결과 · 임시"

/** Header of the original stored record section (never overwritten). */
internal const val SELECTED_CLAIM_STORED_LABEL = "저장된 검증 기록"

/** Mandatory disclosure that the new result is not durable. */
internal const val SELECTED_CLAIM_NOT_SAVED_NOTE = "새 결과는 기존 검증 기록에 저장되지 않았습니다"

/** Cancel/return-to-list label reused by the focused detail and consent. */
internal const val SELECTED_CLAIM_CANCEL_LABEL = "취소"

/** Retry affordance after a failed single-claim run (never automatic). */
internal const val SELECTED_CLAIM_RETRY_LABEL = "다시 시도"

/** Explain the narrow scope before the user triggers the operation. */
internal const val SELECTED_CLAIM_REVERIFY_HINT =
    "선택한 주장 하나만 다시 확인합니다. 답변 전체를 다시 확인하지 않습니다."

/** Never imply the whole answer was audited by the transient result. */
internal const val SELECTED_CLAIM_SINGLE_SCOPE_NOTE =
    "이번 확인은 선택한 주장 1개만 대상으로 했습니다. 답변 전체를 확인한 것이 아닙니다."

/** Search disclosure when a web-authorized run was used. */
internal const val SELECTED_CLAIM_WEB_USED_LABEL = "웹 검색을 포함해 확인했습니다."

/** Fail-safe copy when an execution produced no valid claim result. */
internal const val SELECTED_CLAIM_NO_RESULT_LABEL =
    "이번 확인에서 표시할 결과가 없습니다. 저장된 기록은 그대로 유지됩니다."

/** Conservative copy when the transient result has no linked sources. */
internal const val SELECTED_CLAIM_NO_SOURCE_LABEL = "이번 확인에서 연결된 자료가 없습니다."

/**
 * The UX-P2B action + transient-result section for ONE focused stored claim.
 *
 * It renders exclusively inside the existing focused claim detail. State that
 * belongs to a different claim is ignored (fail-safe) so a stale transient
 * result can never appear under another claim.
 */
@Composable
internal fun SelectedClaimReverificationSection(
    claimId: String,
    state: SelectedClaimReverificationState,
    onReverify: (String) -> Unit,
    onCancel: () -> Unit,
    onOpenUrl: (String) -> Unit,
    identity: EvidenceAnswerIdentity? = null,
    storedReport: com.projectnuke.fusion.data.EvidenceReport? = null,
) {
    val scoped = if (state.claimIdOrNull == claimId) state else SelectedClaimReverificationState.Idle
    HorizontalDivider()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FusionSpacing.inlineGap)) {
        when (scoped) {
            SelectedClaimReverificationState.Idle -> {
                Button(
                    onClick = { onReverify(claimId) },
                    shape = FusionSpacing.objectShape,
                    modifier = Modifier.testTag("evidence-primary")
                        .fillMaxWidth()
                        .heightIn(min = FusionSpacing.control),
                ) {
                    Text(SELECTED_CLAIM_REVERIFY_LABEL)
                }
                Text(
                    SELECTED_CLAIM_REVERIFY_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is SelectedClaimReverificationState.Preparing,
            is SelectedClaimReverificationState.Running,
            is SelectedClaimReverificationState.AwaitingConsent,
            -> {
                Text(SELECTED_CLAIM_REVERIFYING_LABEL, style = MaterialTheme.typography.titleSmall)
                FusionBusyLine()
                (scoped as? SelectedClaimReverificationState.Running)?.notice?.let { notice ->
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.heightIn(min = FusionSpacing.control),
                ) {
                    Text(SELECTED_CLAIM_CANCEL_LABEL)
                }
            }
            is SelectedClaimReverificationState.Result ->
                SelectedClaimTempResult(scoped, onOpenUrl, identity, storedReport ?: scoped.storedReport)
            is SelectedClaimReverificationState.Failed -> {
                Text(SELECTED_CLAIM_TEMP_RESULT_LABEL, style = MaterialTheme.typography.titleSmall)
                Text(selectedClaimFailureExplanation(scoped.reason))
                TextButton(
                    onClick = { onReverify(claimId) },
                    modifier = Modifier.heightIn(min = FusionSpacing.control),
                ) {
                    Text(SELECTED_CLAIM_RETRY_LABEL)
                }
            }
            is SelectedClaimReverificationState.Ineligible -> {
                Text(SELECTED_CLAIM_TEMP_RESULT_LABEL, style = MaterialTheme.typography.titleSmall)
                Text(scoped.explanation)
            }
        }
    }
}

@Composable
internal fun SelectedClaimTempResult(
    state: SelectedClaimReverificationState.Result,
    onOpenUrl: (String) -> Unit,
    identity: EvidenceAnswerIdentity? = null,
    storedReport: com.projectnuke.fusion.data.EvidenceReport? = state.storedReport,
) {
    val report = state.report
    if (identity != null) {
        val validated = ClaimEvidenceComparisonProjection.projectStoredAgainstTransient(
            currentIdentity = identity,
            claimId = state.claimId,
            exactClaimText = report.claims.singleOrNull()?.text.orEmpty(),
            stored = null,
            transient = ClaimEvidenceReportSnapshot(identity, report, ClaimEvidenceReportOrigin.TRANSIENT, state),
            nowMillis = System.currentTimeMillis(),
            webSearchAuthorized = state.webSearchAuthorized,
        )
        if (validated is ClaimEvidenceResultComparisonProjection.Unavailable) {
            Text(resultComparisonUnavailableCopy(validated.reason), style = MaterialTheme.typography.bodySmall)
            return
        }
    }
    val claim = report.claims.firstOrNull() ?: run {
        Text(SELECTED_CLAIM_TEMP_RESULT_LABEL, style = MaterialTheme.typography.titleSmall)
        Text(SELECTED_CLAIM_NO_RESULT_LABEL)
        return
    }
    Text(SELECTED_CLAIM_TEMP_RESULT_LABEL, style = MaterialTheme.typography.titleSmall)
    Text(claim.text, style = MaterialTheme.typography.bodyLarge)
    if (identity == null) Text(claim.verdict.evidenceLabel(), style = MaterialTheme.typography.titleSmall)
    if (identity == null) evidenceClaimReasonLabel(claim.reasonCode)?.let { reason ->
        Text(
            reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.webSearchAuthorized) {
        Text(
            SELECTED_CLAIM_WEB_USED_LABEL,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.notice?.let { notice ->
        Text(
            notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (identity != null) {
        ClaimEvidenceResultComparisonSection(
            currentIdentity = identity,
            claimId = claim.id,
            exactClaimText = claim.text,
            storedReport = storedReport,
            transientResult = state,
            webSearchAuthorized = state.webSearchAuthorized,
            onOpenUrl = onOpenUrl,
        )
    } else {
        val sourcesById = remember(report) { report.sources.associateBy { it.id } }
        val claimSources = claim.sourceIds.mapNotNull(sourcesById::get)
        if (claimSources.isEmpty()) {
            Text(
                SELECTED_CLAIM_NO_SOURCE_LABEL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            claimSources.forEach { source -> EvidenceSourceCard(source = source, onOpenUrl = onOpenUrl) }
        }
        SavedClaimTransientLimitationLines(report)
    }

    state.transientAudit?.let { snapshot ->
        val semantic = snapshot.displayableClaims.firstOrNull { it.claimId == claim.id }
        if (semantic != null) {
            auditSemanticSectionLabel(snapshot)?.let { label ->
                Text(label, style = MaterialTheme.typography.titleSmall)
            }
            auditSemanticStatusLabel(semantic.aggregateStatus)?.let { status ->
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    Spacer(Modifier.height(EvidenceUiSpacing.related))
    Text(
        SELECTED_CLAIM_NOT_SAVED_NOTE,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        SELECTED_CLAIM_SINGLE_SCOPE_NOTE,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SavedClaimTransientLimitationLines(report: com.projectnuke.fusion.data.EvidenceReport) {
    if (!report.candidateWindowTruncated && !report.overlengthSegmentsExcluded) return
    val lines = remember(report) { SavedClaimEvidenceProjection.limitationLines(report) }
    lines.forEach { line ->
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
