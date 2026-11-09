package com.projectnuke.fusion.audit

import com.projectnuke.fusion.data.EVIDENCE_VERIFIER_VERSION
import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceCodec
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.retrieval.LocalRagSourceEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceAuditProjectionTest {
    @Test fun evidenceVerdictsKeepTheirOriginalRuleSourceAndDistinctMeaning() {
        val report = report(
            claims = listOf(
                claim("C1", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("W1")),
                claim("C2", EvidenceVerdict.CONFLICTING_EVIDENCE, listOf("W1")),
                claim("C3", EvidenceVerdict.INSUFFICIENT_EVIDENCE),
                claim("C4", EvidenceVerdict.NOT_CHECKED),
            ),
            sources = listOf(webSource()),
        )
        val projection = projectEvidenceReport(report)!!
        assertEquals(AuditAggregateStatus.SUPPORTED, projection.claims[0].verification.status)
        assertEquals(AuditVerificationSource.RULE_EVIDENCE, projection.claims[0].verification.source)
        assertEquals(AuditAggregateStatus.CONFLICTING_EVIDENCE, projection.claims[1].verification.status)
        assertEquals(AuditAggregateStatus.INSUFFICIENT, projection.claims[2].verification.status)
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, projection.claims[3].verification.status)
        assertEquals(setOf(AuditProvenanceType.EXTERNAL), projection.claims[0].provenanceTypes)
        assertEquals("https://example.com/source", projection.claims[0].evidenceRefs.single().externalUrl)
        assertEquals(AuditCoverage.PARTIALLY_AUDITED, projection.coverage)
        assertEquals(EVIDENCE_VERIFIER_VERSION, report.verifierVersion)
        assertEquals(report, EvidenceCodec.load(EvidenceCodec.serialize(report)).let { (it as com.projectnuke.fusion.data.EvidenceCodecResult.Valid).report })
    }

    @Test fun unlinkedWebSourceDoesNotBecomeClaimProvenance() {
        val projection = projectEvidenceReport(
            report(claims = listOf(claim("C1", EvidenceVerdict.NOT_CHECKED)), sources = listOf(webSource())),
        )!!
        assertEquals(setOf(AuditProvenanceType.UNSOURCED), projection.claims.single().provenanceTypes)
        assertTrue(projection.claims.single().evidenceRefs.isEmpty())
    }

    @Test fun localMetadataRequiresAnExplicitClaimLinkAndNeverInventsUrl() {
        val local = LocalRagSourceEntry(
            fileName = "manual.pdf", attachmentId = "att-1", chunkIndex = 3,
            startOffset = 0, endOffset = 24, contentHashHex = "a".repeat(64),
            excerpt = "Local excerpt", rank = 2, lexicalScore = 7, semanticScore = 0.99, pageNumber = 4,
        ).toAuditEvidenceReference()
        assertEquals(AuditProvenanceType.SOURCE, local.provenance)
        assertNull(local.externalUrl)
        assertEquals("att-1", local.attachmentId)
        assertEquals(3, local.chunkIndex)
        assertEquals(4, local.pageNumber)
        assertEquals("a".repeat(64), local.contentHashHex)
        assertEquals(2, local.rank)

        val unlinked = projectEvidenceReport(report(claims = listOf(claim("C1", EvidenceVerdict.NOT_CHECKED))))!!
        assertEquals(setOf(AuditProvenanceType.UNSOURCED), unlinked.claims.single().provenanceTypes)
        val linked = projectEvidenceReport(
            report(claims = listOf(claim("C1", EvidenceVerdict.NOT_CHECKED))),
            explicitLocalLinksByClaimId = mapOf("C1" to listOf(local)),
        )!!
        assertEquals(setOf(AuditProvenanceType.SOURCE), linked.claims.single().provenanceTypes)
        assertEquals("Local excerpt", linked.claims.single().evidenceRefs.single().text)
    }

    @Test fun explicitToolAndInterpretationOwnersDoNotUseNli() {
        val report = report(
            claims = listOf(
                claim("C1", EvidenceVerdict.SUPPORTED_BY_EVIDENCE),
                claim("C2", EvidenceVerdict.NOT_CHECKED),
            ),
        )
        val projected = projectEvidenceReport(
            report,
            explicitToolTracesByClaimId = mapOf("C1" to AuditToolTrace("T1", "calculator", null, "2 + 2 = 4")),
            explicitInterpretationByClaimId = mapOf("C2" to AuditInterpretationOwnership("I1", "policy-v1")),
        )!!
        assertEquals(setOf(AuditProvenanceType.TOOL_DERIVED), projected.claims[0].provenanceTypes)
        assertEquals(AuditVerificationRequirement.NOT_REQUIRED, projected.claims[0].verificationRequirement)
        assertEquals(AuditVerificationSource.DETERMINISTIC_TRACE, projected.claims[0].verification.source)
        assertEquals(setOf(AuditProvenanceType.INTERPRETATION), projected.claims[1].provenanceTypes)
        assertFalse(projected.claims[1].verification.source == AuditVerificationSource.NLI_BACKEND)
    }

    @Test fun localAndWebLinksPreserveBothProvenanceTypes() {
        val local = localReference()
        val projection = projectEvidenceReport(
            report(claims = listOf(claim("C1", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("W1"))), sources = listOf(webSource())),
            explicitLocalLinksByClaimId = mapOf("C1" to listOf(local)),
        )!!
        val auditClaim = projection.claims.single()
        assertEquals(setOf(AuditProvenanceType.SOURCE, AuditProvenanceType.EXTERNAL), auditClaim.provenanceTypes)
        assertFalse(AuditProvenanceType.UNSOURCED in auditClaim.provenanceTypes)
        assertEquals("자료 · 외부 자료", auditProvenanceLabel(auditClaim.provenanceTypes))
    }

    @Test fun sourceAndToolTraceCanCoexistAndToolAuthorityResolvesClaim() {
        val projection = projectEvidenceReport(
            report(claims = listOf(claim("C1", EvidenceVerdict.NOT_CHECKED))),
            explicitLocalLinksByClaimId = mapOf("C1" to listOf(localReference())),
            explicitToolTracesByClaimId = mapOf("C1" to AuditToolTrace("T1", "calculator", null, "2 + 2 = 4")),
        )!!
        val auditClaim = projection.claims.single()
        assertEquals(setOf(AuditProvenanceType.SOURCE, AuditProvenanceType.TOOL_DERIVED), auditClaim.provenanceTypes)
        assertTrue(auditClaim.isAuditResolved())
        assertEquals(AuditCoverage.PARTIALLY_AUDITED, projection.coverage)
    }

    @Test fun coverageRequiresAuthorityButCountsRuleAndExplicitTraceClaimsAsResolved() {
        val report = report(
            claims = listOf(
                claim("C1", EvidenceVerdict.SUPPORTED_BY_EVIDENCE),
                claim("C2", EvidenceVerdict.NOT_CHECKED),
            ),
        )
        val toolTrace = mapOf("C2" to AuditToolTrace("T2", "calculator", null, "2 + 2 = 4"))
        val partial = projectEvidenceReport(report, explicitToolTracesByClaimId = toolTrace)!!
        val complete = projectEvidenceReport(
            report,
            explicitToolTracesByClaimId = toolTrace,
            hasAuthoritativeCompleteCoverage = true,
        )!!
        assertTrue(partial.claims[0].isAuditResolved())
        assertTrue(partial.claims[1].isAuditResolved())
        assertEquals(AuditCoverage.PARTIALLY_AUDITED, partial.coverage)
        assertEquals(AuditCoverage.AUDITED, complete.coverage)

        val unchecked = projectEvidenceReport(report(claims = listOf(claim("C3", EvidenceVerdict.NOT_CHECKED))))!!
        assertFalse(unchecked.claims.single().isAuditResolved())
        assertEquals(AuditCoverage.NOT_AUDITED, unchecked.coverage)
    }

    @Test fun unavailableOrRequiredNotVerifiedClaimsRemainUnresolved() = runBlocking {
        val projection = projectEvidenceReport(report(claims = listOf(claim("C1", EvidenceVerdict.NOT_CHECKED))))!!
        val auditClaim = projection.claims.single()
        assertEquals(VerifierBackendResult.Unavailable, UnavailableVerifierBackend.verifySafely(
            VerificationPairRequest("claim", "evidence", evidenceIdentity = "E1"),
        ))
        assertEquals(AuditVerificationRequirement.REQUIRED, auditClaim.verificationRequirement)
        assertFalse(auditClaim.isAuditResolved())
    }

    @Test fun unsourcedIsExclusiveAndNakedNotRequiredCannotResolve() {
        val report = report(claims = listOf(claim("C1", EvidenceVerdict.NOT_CHECKED)))
        val local = localReference()
        val sourced = projectEvidenceReport(
            report,
            explicitLocalLinksByClaimId = mapOf("C1" to listOf(local)),
        )!!.claims.single()
        assertThrows(IllegalArgumentException::class.java) {
            sourced.copy(provenanceTypes = setOf(AuditProvenanceType.UNSOURCED, AuditProvenanceType.SOURCE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            sourced.copy(verificationRequirement = AuditVerificationRequirement.NOT_REQUIRED)
        }
    }

    @Test fun invalidDurableReportIsNotProjected() {
        val invalid = report(claims = listOf(claim("C1", EvidenceVerdict.NOT_CHECKED))).copy(answerSha256 = "bad")
        assertNull(projectEvidenceReport(invalid))
    }

    private fun claim(id: String, verdict: EvidenceVerdict, sourceIds: List<String> = emptyList()) =
        EvidenceClaim(id, "Claim text $id", verdict, sourceIds, null)

    private fun webSource() = EvidenceSource(
        id = "W1", title = "Source title", source = "Publisher", url = "https://example.com/source",
        snippet = "Source excerpt", publishedAt = null, providerType = "WEB", providerDisplayName = "Web",
        queryUsed = "query",
    )

    private fun localReference() = LocalRagSourceEntry(
        fileName = "manual.pdf", attachmentId = "att-1", chunkIndex = 3,
        startOffset = 0, endOffset = 24, contentHashHex = "a".repeat(64),
        excerpt = "Local excerpt", rank = 2, lexicalScore = 7, semanticScore = 0.99, pageNumber = 4,
    ).toAuditEvidenceReference()

    private fun report(claims: List<EvidenceClaim>, sources: List<EvidenceSource> = emptyList()) = EvidenceReport(
        schemaVersion = 1, answerSha256 = "a".repeat(64), checkedAt = 1_000L,
        verifierVersion = EVIDENCE_VERIFIER_VERSION, claims = claims, sources = sources,
    )
}
