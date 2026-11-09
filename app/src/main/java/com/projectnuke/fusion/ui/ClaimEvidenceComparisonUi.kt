package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.data.formatCheckedAt

internal const val CLAIM_EVIDENCE_COMPARE_LABEL = "근거 비교"
private const val TEMPORARY_VERDICT_TITLE = "이번 확인 · 임시"

/**
 * Focused source-pair inspection for one exact claim and one report. All data
 * comes from that claim's sourceIds; source ids from another report are never
 * used to resolve these records.
 */
@Composable
internal fun ClaimEvidenceSourcePairComparison(
    identity: EvidenceAnswerIdentity,
    report: EvidenceReport,
    origin: ClaimEvidenceReportOrigin,
    claimId: String,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    transientOwnerResult: SelectedClaimReverificationState.Result? = null,
    title: String = CLAIM_EVIDENCE_COMPARE_LABEL,
) {
    val ownerResult = transientOwnerResult.takeIf { origin == ClaimEvidenceReportOrigin.TRANSIENT }
    val snapshot = remember(identity, report, origin, ownerResult) {
        ClaimEvidenceReportSnapshot(identity, report, origin, ownerResult)
    }
    var sourceAId by remember(identity, report, claimId) { mutableStateOf<String?>(null) }
    var sourceBId by remember(identity, report, claimId) { mutableStateOf<String?>(null) }
    val projection = ClaimEvidenceComparisonProjection.projectSources(
        currentIdentity = identity,
        snapshot = snapshot,
        claimId = claimId,
        nowMillis = System.currentTimeMillis(),
        selectedSourceAId = sourceAId,
        selectedSourceBId = sourceBId,
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(EvidenceUiSpacing.inline),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        when (projection) {
            is ClaimEvidenceSourceProjection.Unavailable -> Text(
                sourceUnavailableCopy(projection.reason),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is ClaimEvidenceSourceProjection.Ready -> {
                Text(
                    reportOriginCopy(projection.origin) + " · 확인 시각 " + formatCheckedAt(projection.checkedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val selectableSources = projection.linkedSources.filter { it.source != null }
                if (projection.selectionIssue != ClaimEvidenceSourceSelectionIssue.READY &&
                    selectableSources.size >= 2
                ) {
                    SourceSelector(
                        label = "자료 A",
                        records = selectableSources,
                        selected = projection.sourceA,
                        onSelect = { sourceAId = it },
                    )
                    SourceSelector(
                        label = "자료 B",
                        records = selectableSources,
                        selected = projection.sourceB,
                        onSelect = { sourceBId = it },
                    )
                }
                when (projection.selectionIssue) {
                    ClaimEvidenceSourceSelectionIssue.NO_LINKED_SOURCES ->
                        Text("이 주장에는 연결된 자료가 없습니다.")
                    ClaimEvidenceSourceSelectionIssue.NO_RESOLVED_SOURCES ->
                        Text("자료 참조를 현재 기록에서 확인할 수 없어 비교할 수 없습니다.")
                    ClaimEvidenceSourceSelectionIssue.ONE_SOURCE_ONLY -> {
                        Text("연결된 자료가 1개뿐이라 두 자료 비교는 할 수 없습니다.")
                        projection.sourceA?.let {
                            ClaimEvidenceSourceRecordView(it, "연결된 자료", onOpenUrl)
                        }
                    }
                    ClaimEvidenceSourceSelectionIssue.INVALID_SOURCE_SELECTION ->
                        Text("선택한 자료가 이 주장의 연결 자료에 없어 비교를 열지 않았습니다.")
                    ClaimEvidenceSourceSelectionIssue.SAME_SOURCE_SELECTED ->
                        Text("같은 자료가 양쪽에 선택되어 있습니다. 서로 다른 자료를 선택해 주세요.")
                    ClaimEvidenceSourceSelectionIssue.READY -> {
                        val resolved = projection.linkedSources.filter { it.source != null }
                        SourceSelector(
                            label = "자료 A",
                            records = resolved,
                            selected = projection.sourceA,
                            onSelect = { sourceAId = it },
                        )
                        SourceSelector(
                            label = "자료 B",
                            records = resolved,
                            selected = projection.sourceB,
                            onSelect = { sourceBId = it },
                        )
                        if (projection.linkedSources.size > resolved.size) {
                            Text(
                                "연결된 자료 참조 중 일부는 확인할 수 없어 비교 대상에서 제외했습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (maxWidth >= 600.dp && LocalDensity.current.fontScale < 1.5f) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(EvidenceUiSpacing.row),
                                ) {
                                    ClaimEvidenceSourceRecordView(
                                        requireNotNull(projection.sourceA),
                                        "자료 A",
                                        onOpenUrl,
                                        Modifier.weight(1f),
                                    )
                                    ClaimEvidenceSourceRecordView(
                                        requireNotNull(projection.sourceB),
                                        "자료 B",
                                        onOpenUrl,
                                        Modifier.weight(1f),
                                    )
                                }
                            } else {
                                Column(
                                    Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(EvidenceUiSpacing.inline),
                                ) {
                                    ClaimEvidenceSourceRecordView(
                                        requireNotNull(projection.sourceA),
                                        "자료 A",
                                        onOpenUrl,
                                    )
                                    ClaimEvidenceSourceRecordView(
                                        requireNotNull(projection.sourceB),
                                        "자료 B",
                                        onOpenUrl,
                                    )
                                }
                            }
                        }
                        projection.observations.forEach { observation ->
                            Text(
                                sourceObservationCopy(observation),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceSelector(
    label: String,
    records: List<ClaimEvidenceSourceRecord>,
    selected: ClaimEvidenceSourceRecord?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember(label, records) { mutableStateOf(false) }
    val selectedTitle = selected?.source?.title?.takeIf(String::isNotBlank) ?: "자료 선택"
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FusionSpacing.control)
                .semantics {
                    contentDescription = label + " 선택, 현재 " + selectedTitle
                    stateDescription = if (expanded) "펼쳐짐" else "접힘"
                },
        ) {
            Text(label + " · " + selectedTitle, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            records.forEach { record ->
                val title = record.source?.title?.takeIf(String::isNotBlank) ?: "제목 기록 없음"
                DropdownMenuItem(
                    text = { Text(title + " · " + record.sourceId) },
                    onClick = {
                        onSelect(record.sourceId)
                        expanded = false
                    },
                    modifier = Modifier.heightIn(min = FusionSpacing.control),
                )
            }
        }
    }
}

@Composable
internal fun ClaimEvidenceSourceRecordView(
    record: ClaimEvidenceSourceRecord,
    heading: String,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = record.source
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(EvidenceUiSpacing.inline),
    ) {
        HorizontalDivider()
        EvidenceHeading(heading)
        Text(reportOriginCopy(record.reportOrigin), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "검증 확인 시각 · " + formatCheckedAt(record.reportCheckedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (source == null) {
            Text("자료 참조 " + record.sourceId + "를 찾을 수 없습니다.")
            return@Column
        }
        Text(source.title.displayOrMissing(), style = MaterialTheme.typography.titleMedium)
        Text("원문 발행처 · " + source.source.displayOrMissing(), style = MaterialTheme.typography.bodyMedium)
        Text(
            "자료 발행일 · " + (source.publishedAt?.takeIf(String::isNotBlank) ?: "기록 없음"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("저장된 발췌", style = MaterialTheme.typography.labelMedium)
        Text(source.snippet.ifBlank { "저장된 발췌문이 없습니다." }, style = MaterialTheme.typography.bodyMedium)
        EvidenceDisclosure("검색 기록", record) {
            Text("검색 제공자 · " + source.providerDisplayName.displayOrMissing(), style = MaterialTheme.typography.bodySmall)
            Text("기록된 검색어 · " + source.queryUsed.displayOrMissing(), style = MaterialTheme.typography.bodySmall)
        }
        val safeUrl = record.safeOriginalUrl
        if (safeUrl != null) {
            TextButton(
                onClick = { onOpenUrl(safeUrl) },
                modifier = Modifier.heightIn(min = FusionSpacing.control),
            ) {
                Text("원문 열기")
            }
        } else {
            Text(
                "안전한 원문 링크를 사용할 수 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ClaimEvidenceResultComparisonSection(
    currentIdentity: EvidenceAnswerIdentity,
    claimId: String,
    exactClaimText: String,
    storedReport: EvidenceReport?,
    transientResult: SelectedClaimReverificationState.Result,
    webSearchAuthorized: Boolean,
    onOpenUrl: (String) -> Unit,
) {
    val transientReport = transientResult.report
    var inspected by remember(currentIdentity, storedReport, transientResult, claimId) { mutableStateOf<String?>(null) }
    val stored = storedReport?.let {
        ClaimEvidenceReportSnapshot(currentIdentity, it, ClaimEvidenceReportOrigin.STORED)
    }
    val transient = ClaimEvidenceReportSnapshot(
        currentIdentity,
        transientReport,
        ClaimEvidenceReportOrigin.TRANSIENT,
        transientOwnerResult = transientResult,
    )
    val projection = ClaimEvidenceComparisonProjection.projectStoredAgainstTransient(
        currentIdentity = currentIdentity,
        claimId = claimId,
        exactClaimText = exactClaimText,
        stored = stored,
        transient = transient,
        nowMillis = System.currentTimeMillis(),
        webSearchAuthorized = webSearchAuthorized,
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(EvidenceUiSpacing.inline)) {
        when (projection) {
            is ClaimEvidenceResultComparisonProjection.Unavailable -> {
                // Projection refusal must not leak unvalidated verdicts or source records.
                Text(
                    resultComparisonUnavailableCopy(projection.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is ClaimEvidenceResultComparisonProjection.TemporaryOnly -> {
                transientReport.claims.singleOrNull()?.let { VerdictSummary(TEMPORARY_VERDICT_TITLE, it.verdict) }
                Text(
                    resultComparisonUnavailableCopy(projection.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ClaimEvidenceCurrentOnlySources(
                    sources = projection.transientSources,
                    transientResult = transientResult,
                    onOpenUrl = onOpenUrl,
                )
            }
            is ClaimEvidenceResultComparisonProjection.Available -> {
                val comparison = projection.comparison
                // Natural-height cells stack on narrow/large-font layouts.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.5f
                    if (stacked) {
                        Column(verticalArrangement = Arrangement.spacedBy(EvidenceUiSpacing.row)) {
                            VerdictSummary("저장된 판정", comparison.stored.verdict)
                            VerdictSummary(TEMPORARY_VERDICT_TITLE, comparison.transient.verdict)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(EvidenceUiSpacing.row)) {
                            Column(Modifier.weight(1f)) { VerdictSummary("저장된 판정", comparison.stored.verdict) }
                            Column(Modifier.weight(1f)) { VerdictSummary(TEMPORARY_VERDICT_TITLE, comparison.transient.verdict) }
                        }
                    }
                }
                Text(verdictTransitionCopy(comparison.transition), style = MaterialTheme.typography.bodyMedium)
                EvidenceDisclosure(
                    "연결된 자료 · 저장 ${comparison.stored.sourceRecords.size}개 · 이번 ${comparison.transient.sourceRecords.size}개",
                    expanded = inspected == "sources",
                    onExpandedChange = { inspected = if (it) "sources" else null },
                ) {
                    EvidenceRecordList(comparison.stored.sourceRecords, "저장 자료", onOpenUrl)
                    EvidenceRecordList(comparison.transient.sourceRecords, "이번 자료 · 임시", onOpenUrl)
                }

                if (storedReport?.candidateWindowTruncated == true || storedReport?.overlengthSegmentsExcluded == true ||
                    transientReport.candidateWindowTruncated || transientReport.overlengthSegmentsExcluded) {
                    Text("일부 자료 또는 주장은 검증 범위에서 제외되었습니다. 검증 범위와 한계를 확인하세요.",
                        style = MaterialTheme.typography.bodySmall)
                }
                SourcePairDisclosure(
                    currentIdentity,
                    storedReport,
                    transientResult,
                    claimId,
                    onOpenUrl,
                    expanded = inspected == "comparison",
                    onExpandedChange = { inspected = if (it) "comparison" else null },
                )
                EvidenceDisclosure("검증 범위와 한계", listOf(currentIdentity, storedReport, transientResult)) {
                    Text("저장 확인 시각 · " + formatCheckedAt(comparison.stored.checkedAt), style = MaterialTheme.typography.bodySmall)
                    Text("이번 확인 시각 · " + formatCheckedAt(comparison.transient.checkedAt), style = MaterialTheme.typography.bodySmall)
                    evidenceClaimReasonLabel(comparison.stored.reasonCode)?.let { Text("저장 · " + it) }
                    evidenceClaimReasonLabel(comparison.transient.reasonCode)?.let { Text("이번 · " + it) }
                    Text("웹 검색 사용 승인 여부 · " + if (comparison.transient.webSearchAuthorized == true) "승인됨" else "승인되지 않음")
                    Text("확인 시각만으로 어느 판정이 더 정확한지는 알 수 없습니다.")
                    Text("동일한 검증 URL · ${comparison.sharedValidatedUrlCount}개")
                    Text("동일 URL로 대응하지 못한 자료 · 저장 ${comparison.storedUnmatchedByValidatedUrlCount}개 · 이번 ${comparison.transientUnmatchedByValidatedUrlCount}개")
                    Text(SAVED_CLAIMS_VERDICT_CAVEAT, style = MaterialTheme.typography.bodySmall)
                    Text("현재 기록만으로 어떤 자료가 정확한지 판정할 수 없습니다.", style = MaterialTheme.typography.bodySmall)
                    ReportLimitationLines(storedReport)
                    ReportLimitationLines(transientReport)
                }
            }
        }
    }
}

@Composable
private fun ClaimEvidenceCurrentOnlySources(
    sources: ClaimEvidenceSourceProjection.Ready,
    transientResult: SelectedClaimReverificationState.Result,
    onOpenUrl: (String) -> Unit,
) {
    var inspected by remember(sources.identity, transientResult) { mutableStateOf<String?>(null) }
    if (sources.linkedSources.isEmpty()) {
        Text("이번 확인에 연결된 자료가 없습니다.")
    }
    if (sources.linkedSources.isNotEmpty()) {
        EvidenceDisclosure("연결된 자료 · 이번 ${sources.linkedSources.size}개",
            expanded = inspected == "sources", onExpandedChange = { inspected = if (it) "sources" else null }) {
            EvidenceRecordList(sources.linkedSources, "이번 자료 · 임시", onOpenUrl)
        }
        SourcePairDisclosure(
            identity = sources.identity,
            storedReport = null,
            transientResult = transientResult,
            claimId = sources.claimId,
            onOpenUrl = onOpenUrl,
            expanded = inspected == "comparison",
            onExpandedChange = { inspected = if (it) "comparison" else null },
        )
    }
    EvidenceDisclosure("검증 범위와 한계", transientResult) {
        Text("확인 시각 · " + formatCheckedAt(transientResult.report.checkedAt), style = MaterialTheme.typography.bodySmall)
        evidenceClaimReasonLabel(transientResult.report.claims.singleOrNull()?.reasonCode)?.let { Text(it) }
        Text(SAVED_CLAIMS_VERDICT_CAVEAT, style = MaterialTheme.typography.bodySmall)
        ReportLimitationLines(transientResult.report)
    }
    if (transientResult.report.candidateWindowTruncated || transientResult.report.overlengthSegmentsExcluded) {
        Text("일부 자료 또는 주장은 검증 범위에서 제외되었습니다.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SourcePairDisclosure(
    identity: EvidenceAnswerIdentity,
    storedReport: EvidenceReport?,
    transientResult: SelectedClaimReverificationState.Result,
    claimId: String,
    onOpenUrl: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val transientReport = transientResult.report
    // Only one comparison origin is open at a time; neither source set is eagerly enumerated.
    EvidenceDisclosure(CLAIM_EVIDENCE_COMPARE_LABEL, expanded = expanded, onExpandedChange = onExpandedChange) {
        var useStored by remember(identity, storedReport, transientResult, claimId) { mutableStateOf(false) }
        if (storedReport != null) {
            TextButton(onClick = { useStored = !useStored }, modifier = Modifier.heightIn(min = FusionSpacing.control)) {
                Text(if (useStored) "이번 자료로 전환" else "저장 자료로 전환")
            }
        }
        val report = if (useStored) requireNotNull(storedReport) else transientReport
        ClaimEvidenceSourcePairComparison(
            identity, report,
            if (useStored) ClaimEvidenceReportOrigin.STORED else ClaimEvidenceReportOrigin.TRANSIENT,
            claimId, onOpenUrl,
            transientOwnerResult = if (useStored) null else transientResult,
            title = if (useStored) "저장 자료 비교" else "이번 확인 자료 비교 · 임시",
        )
    }
}

@Composable
private fun VerdictSummary(label: String, verdict: EvidenceVerdict) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(verdict.evidenceLabel(), style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun EvidenceRecordList(records: List<ClaimEvidenceSourceRecord>, heading: String, onOpenUrl: (String) -> Unit) {
    EvidenceHeading(heading)
    if (records.isEmpty()) Text("연결된 자료가 없습니다.")
    records.forEach { record ->
        EvidenceDisclosure(record.source?.title?.takeIf(String::isNotBlank) ?: "자료 참조 ${record.sourceId}", record) {
            ClaimEvidenceSourceRecordView(record, heading, onOpenUrl)
        }
    }
}

@Composable
private fun ReportLimitationLines(report: EvidenceReport?) {
    if (report == null || (!report.candidateWindowTruncated && !report.overlengthSegmentsExcluded)) return
    SavedClaimEvidenceProjection.limitationLines(report).forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun String.displayOrMissing(): String = if (isBlank()) "기록 없음" else this

private fun reportOriginCopy(origin: ClaimEvidenceReportOrigin): String = when (origin) {
    ClaimEvidenceReportOrigin.STORED -> "저장된 자료 기록"
    ClaimEvidenceReportOrigin.TRANSIENT -> "이번 확인의 임시 자료"
}

private fun sourceUnavailableCopy(reason: ClaimEvidenceSourceUnavailableReason): String = when (reason) {
    ClaimEvidenceSourceUnavailableReason.ANSWER_IDENTITY_MISMATCH ->
        "답변이 변경되어 이 자료 비교를 표시하지 않습니다."
    ClaimEvidenceSourceUnavailableReason.INVALID_REPORT ->
        "검증된 자료 기록을 확인할 수 없어 비교를 열지 않았습니다."
    ClaimEvidenceSourceUnavailableReason.STORED_REPORT_OUTDATED ->
        "저장된 자료 기록이 현재 답변과 호환되지 않아 비교하지 않습니다."
    ClaimEvidenceSourceUnavailableReason.TRANSIENT_RESULT_NOT_OWNED ->
        "이번 확인 결과의 소유권이 끝나 자료 비교를 닫았습니다."
    ClaimEvidenceSourceUnavailableReason.CLAIM_NOT_FOUND ->
        "선택한 주장을 이 자료 기록에서 찾을 수 없습니다."
}

internal fun resultComparisonUnavailableCopy(
    reason: ClaimEvidenceResultComparisonUnavailableReason,
): String = when (reason) {
    ClaimEvidenceResultComparisonUnavailableReason.NO_STORED_REPORT ->
        "이 주장과 정확히 일치하는 저장 기록이 없어 이번 임시 결과만 표시합니다."
    ClaimEvidenceResultComparisonUnavailableReason.NO_TRANSIENT_RESULT ->
        "비교할 이번 확인 결과가 없습니다."
    ClaimEvidenceResultComparisonUnavailableReason.ANSWER_IDENTITY_MISMATCH ->
        "답변 또는 대화가 달라 저장 기록과 직접 비교할 수 없습니다."
    ClaimEvidenceResultComparisonUnavailableReason.INVALID_REPORT ->
        "검증된 보고서가 아니어서 직접 비교하지 않습니다."
    ClaimEvidenceResultComparisonUnavailableReason.STORED_REPORT_OUTDATED ->
        "저장 기록이 현재 답변과 호환되지 않아 판정을 비교하지 않습니다."
    ClaimEvidenceResultComparisonUnavailableReason.TRANSIENT_RESULT_NOT_OWNED ->
        "이번 임시 결과의 소유권이 끝나 비교하지 않습니다."
    ClaimEvidenceResultComparisonUnavailableReason.TRANSIENT_REPORT_NOT_SINGLE_CLAIM ->
        "이번 결과가 선택한 주장 하나에 한정되지 않아 직접 비교하지 않습니다."
    ClaimEvidenceResultComparisonUnavailableReason.STORED_CLAIM_NOT_FOUND ->
        "저장 기록에서 같은 주장 ID를 찾을 수 없어 직접 비교하지 않습니다."
    ClaimEvidenceResultComparisonUnavailableReason.CLAIM_ID_MISMATCH ->
        "주장 ID가 달라 직접 판정을 비교하지 않습니다."
    ClaimEvidenceResultComparisonUnavailableReason.CLAIM_TEXT_MISMATCH ->
        "주장 문장이 달라 직접 판정을 비교하지 않습니다."
}

private fun sourceObservationCopy(observation: ClaimEvidenceSourceObservation): String = when (observation) {
    ClaimEvidenceSourceObservation.RECORDED_PUBLICATION_DATES_DIFFER ->
        "자료에 기록된 발행일이 서로 다릅니다."
    ClaimEvidenceSourceObservation.RECORDED_EXCERPTS_DIFFER ->
        "서로 다른 발췌문이 기록되어 있습니다."
    ClaimEvidenceSourceObservation.ACCURACY_CANNOT_BE_DETERMINED_FROM_THESE_RECORDS ->
        "현재 기록만으로 어떤 자료가 정확한지 판정할 수 없습니다."
}

private fun verdictTransitionCopy(transition: ClaimEvidenceVerdictTransition): String = when (transition) {
    ClaimEvidenceVerdictTransition.SAME_RECORDED_VERDICT ->
        "저장된 판정과 이번 판정이 같습니다."
    ClaimEvidenceVerdictTransition.SUPPORTED_TO_INSUFFICIENT,
    ClaimEvidenceVerdictTransition.INSUFFICIENT_TO_SUPPORTED,
    ClaimEvidenceVerdictTransition.SUPPORTED_TO_CONFLICTING,
    ClaimEvidenceVerdictTransition.CONFLICTING_TO_INSUFFICIENT,
    ClaimEvidenceVerdictTransition.NOT_CHECKED_TRANSITION,
    ClaimEvidenceVerdictTransition.OTHER_RECORDED_CHANGE,
    -> "저장된 판정과 이번 확인의 판정이 다릅니다. 확인 시각, 사용한 자료 및 검증 범위를 함께 살펴보세요."
}
