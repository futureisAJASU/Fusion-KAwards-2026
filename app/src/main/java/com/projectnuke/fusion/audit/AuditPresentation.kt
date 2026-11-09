package com.projectnuke.fusion.audit

data class AuditSummaryRow(
    val status: AuditAggregateStatus,
    val label: String,
    val count: Int,
)

/** Bounded Korean copy for the existing Evidence surface. */
fun auditSummaryRows(projection: AnswerAuditProjection): List<AuditSummaryRow> = listOf(
    AuditAggregateStatus.SUPPORTED to "\uC790\uB8CC\uC5D0\uC11C \uD655\uC778",
    AuditAggregateStatus.CONTRADICTED to "\uC790\uB8CC\uC640 \uCDA9\uB3CC",
    AuditAggregateStatus.CONFLICTING_EVIDENCE to
        "\uC790\uB8CC \uAC04 \uB610\uB294 \uC790\uB8CC\uC640 \uC8FC\uC7A5 \uC0AC\uC774\uC5D0 \uCDA9\uB3CC\uD558\uB294 \uADFC\uAC70\uAC00 \uC788\uC74C",
    AuditAggregateStatus.INSUFFICIENT to "\uADFC\uAC70 \uBD80\uC871",
    AuditAggregateStatus.NOT_VERIFIED to "\uD655\uC778\uD558\uC9C0 \uC54A\uC74C",
).map { (status, label) ->
    AuditSummaryRow(
        status,
        label,
        projection.claims.count {
            it.verification.status == status &&
                (status != AuditAggregateStatus.NOT_VERIFIED || !it.isAuditResolved())
        },
    )
}

/** Coverage copy deliberately has no full-answer completion claim. */
fun auditCoverageLabel(coverage: AuditCoverage): String? = when (coverage) {
    AuditCoverage.AUDITED -> null
    AuditCoverage.PARTIALLY_AUDITED -> "\uC77C\uBD80 \uC8FC\uC7A5 \uD655\uC778"
    AuditCoverage.NOT_AUDITED -> "\uD655\uC778\uD55C \uC8FC\uC7A5\uC774 \uC5C6\uC74C"
}

fun auditProvenanceLabel(provenance: AuditProvenanceType): String = when (provenance) {
    AuditProvenanceType.SOURCE -> "\uC790\uB8CC"
    AuditProvenanceType.TOOL_DERIVED -> "\uACC4\uC0B0\uC73C\uB85C \uB3C4\uCD9C"
    AuditProvenanceType.INTERPRETATION -> "\uD574\uC11D"
    AuditProvenanceType.EXTERNAL -> "\uC678\uBD80 \uC790\uB8CC"
    AuditProvenanceType.UNSOURCED -> "\uADFC\uAC70 \uC5F0\uACB0 \uC5C6\uC74C"
}

/** Renders every explicit provenance type in a fixed, stable order. */
fun auditProvenanceLabel(provenanceTypes: Set<AuditProvenanceType>): String {
    require(provenanceTypes.isNotEmpty())
    require(AuditProvenanceType.UNSOURCED !in provenanceTypes || provenanceTypes.size == 1)
    val presentationOrder = listOf(
        AuditProvenanceType.SOURCE,
        AuditProvenanceType.EXTERNAL,
        AuditProvenanceType.TOOL_DERIVED,
        AuditProvenanceType.INTERPRETATION,
        AuditProvenanceType.UNSOURCED,
    )
    return presentationOrder.asSequence()
        .filter(provenanceTypes::contains)
        .joinToString(" · ", transform = ::auditProvenanceLabel)
}

fun auditSemanticSectionLabel(snapshot: AnswerAuditExecutionSnapshot): String? {
    val shown = snapshot.displayableClaims
    if (shown.isEmpty()) return null
    return if (shown.any { it.backend.experimental }) {
        "\uC2E4\uD5D8\uC801 \uC790\uB3D9 \uC758\uBBF8 \uAC80\uC99D"
    } else {
        "\uC790\uB3D9 \uC758\uBBF8 \uAC80\uC99D"
    }
}

fun auditSemanticStatusLabel(status: AuditAggregateStatus): String? = when (status) {
    AuditAggregateStatus.SUPPORTED -> "\uC790\uB8CC\uC640 \uC758\uBBF8\uC0C1 \uC77C\uCE58"
    AuditAggregateStatus.CONTRADICTED -> "\uC790\uB8CC\uC640 \uC758\uBBF8\uC0C1 \uCDA9\uB3CC"
    AuditAggregateStatus.INSUFFICIENT -> "\uD310\uB2E8\uD558\uAE30\uC5D0 \uADFC\uAC70 \uBD80\uC871"
    AuditAggregateStatus.CONFLICTING_EVIDENCE -> "\uC11C\uB85C \uCDA9\uB3CC\uD558\uB294 \uC790\uB8CC \uAC10\uC9C0"
    AuditAggregateStatus.NOT_VERIFIED -> null
}
