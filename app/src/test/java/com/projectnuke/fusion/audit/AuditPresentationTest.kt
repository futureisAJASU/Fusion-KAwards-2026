package com.projectnuke.fusion.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditPresentationTest {
    @Test fun summarySeparatesSupportConflictInsufficientAndUnchecked() {
        val projection = AnswerAuditProjection(
            claims = listOf(
                claim("1", AuditAggregateStatus.SUPPORTED),
                claim("2", AuditAggregateStatus.CONTRADICTED),
                claim("3", AuditAggregateStatus.CONFLICTING_EVIDENCE),
                claim("4", AuditAggregateStatus.INSUFFICIENT),
                claim("5", AuditAggregateStatus.NOT_VERIFIED),
            ),
            coverage = AuditCoverage.PARTIALLY_AUDITED,
        )
        val rows = auditSummaryRows(projection)
        assertEquals(5, rows.size)
        assertEquals(1, rows.single { it.status == AuditAggregateStatus.SUPPORTED }.count)
        assertEquals(1, rows.single { it.status == AuditAggregateStatus.CONTRADICTED }.count)
        val conflict = rows.single { it.status == AuditAggregateStatus.CONFLICTING_EVIDENCE }
        assertTrue(conflict.label.contains("\uC790\uB8CC \uAC04"))
        assertTrue(conflict.label.contains("\uC8FC\uC7A5"))
        assertTrue(rows.single { it.status == AuditAggregateStatus.INSUFFICIENT }.label.contains("\uADFC\uAC70 \uBD80\uC871"))
        assertTrue(rows.single { it.status == AuditAggregateStatus.NOT_VERIFIED }.label.contains("\uD655\uC778\uD558\uC9C0 \uC54A\uC74C"))
        assertEquals("\uC77C\uBD80 \uC8FC\uC7A5 \uD655\uC778", auditCoverageLabel(AuditCoverage.PARTIALLY_AUDITED))
    }

    @Test fun provenanceLabelsAreAvailableWithoutTruthOrModelClaims() {
        assertEquals("\uC790\uB8CC", auditProvenanceLabel(AuditProvenanceType.SOURCE))
        assertEquals("\uC678\uBD80 \uC790\uB8CC", auditProvenanceLabel(AuditProvenanceType.EXTERNAL))
        assertEquals("\uACC4\uC0B0\uC73C\uB85C \uB3C4\uCD9C", auditProvenanceLabel(AuditProvenanceType.TOOL_DERIVED))
        assertEquals("\uD574\uC11D", auditProvenanceLabel(AuditProvenanceType.INTERPRETATION))
        assertEquals("\uADFC\uAC70 \uC5F0\uACB0 \uC5C6\uC74C", auditProvenanceLabel(AuditProvenanceType.UNSOURCED))
        assertNull(auditCoverageLabel(AuditCoverage.AUDITED))
        val allCopy = (auditSummaryRows(AnswerAuditProjection(emptyList(), AuditCoverage.NOT_AUDITED)).map { it.label } +
            AuditProvenanceType.entries.map(::auditProvenanceLabel)).joinToString()
        assertFalse(allCopy.contains("AI \uAC80\uC99D \uC644\uB8CC"))
        assertFalse(allCopy.contains("\uC815\uD655\uD568"))
        assertFalse(allCopy.contains("\uC0AC\uC2E4\uC784"))
    }

    @Test fun resolvedToolTraceIsNotCountedAsUncheckedAndMixedSourcesStayVisible() {
        val toolClaim = AnswerAuditClaim(
            id = "tool",
            text = "2 + 2 = 4",
            provenanceTypes = setOf(AuditProvenanceType.TOOL_DERIVED),
            verification = AuditVerification(AuditAggregateStatus.NOT_VERIFIED, AuditVerificationSource.DETERMINISTIC_TRACE),
            verificationRequirement = AuditVerificationRequirement.NOT_REQUIRED,
            toolTrace = AuditToolTrace("trace-1", "calculator", null, "2 + 2 = 4"),
        )
        val rows = auditSummaryRows(AnswerAuditProjection(listOf(toolClaim), AuditCoverage.PARTIALLY_AUDITED))
        assertEquals(0, rows.single { it.status == AuditAggregateStatus.NOT_VERIFIED }.count)
        assertEquals("\uACC4\uC0B0\uC73C\uB85C \uB3C4\uCD9C", auditProvenanceLabel(toolClaim.provenanceTypes))

        val mixedProvenance = auditProvenanceLabel(setOf(AuditProvenanceType.SOURCE, AuditProvenanceType.EXTERNAL))
        assertEquals("\uC790\uB8CC \u00B7 \uC678\uBD80 \uC790\uB8CC", mixedProvenance)
        assertNotEquals("\uADFC\uAC70 \uC5F0\uACB0 \uC5C6\uC74C", mixedProvenance)
    }

    private fun claim(id: String, status: AuditAggregateStatus) = AnswerAuditClaim(
        id = id,
        text = "Claim $id",
        provenanceTypes = setOf(AuditProvenanceType.UNSOURCED),
        verification = AuditVerification(status, AuditVerificationSource.RULE_EVIDENCE.takeUnless { status == AuditAggregateStatus.NOT_VERIFIED }),
    )
}
