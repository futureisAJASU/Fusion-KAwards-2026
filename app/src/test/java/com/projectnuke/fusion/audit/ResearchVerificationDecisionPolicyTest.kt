package com.projectnuke.fusion.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchVerificationDecisionPolicyTest {
    @Test fun `supported facts can finalize only with authoritative answer wide coverage`() {
        val audit = snapshot(claim("c1", AuditVerificationLabel.SUPPORTED))
        val partial = decide(ids = setOf("c1"), audit = audit, coverage = false)
        assertEquals(ResearchVerificationAction.ABSTAIN, partial.action)
        assertEquals(ResearchVerificationReason.INCOMPLETE_CLAIM_COVERAGE, partial.reason)
        assertFalse(partial.isFullyEvidenceSupported)

        val complete = decide(ids = setOf("c1"), audit = audit, coverage = true)
        assertEquals(ResearchVerificationAction.FINAL, complete.action)
        assertEquals(ResearchVerificationReason.COMPLETE_EVIDENCE_SUPPORT, complete.reason)
        assertTrue(complete.isFullyEvidenceSupported)
    }

    @Test fun `empty factual list is not itself a certification of complete extraction`() {
        val unknown = decide(ids = emptySet(), audit = null, coverage = false)
        assertEquals(ResearchVerificationAction.ABSTAIN, unknown.action)

        val nonFactual = decide(ids = emptySet(), audit = null, coverage = true)
        assertEquals(ResearchVerificationAction.FINAL, nonFactual.action)
        assertEquals(ResearchVerificationReason.NON_FACTUAL_ANSWER, nonFactual.reason)
        assertFalse(nonFactual.isFullyEvidenceSupported)

        val inconsistent = decide(
            ids = emptySet(),
            audit = snapshot(claim("unexpected", AuditVerificationLabel.SUPPORTED)),
            coverage = true,
        )
        assertEquals(ResearchVerificationAction.ABSTAIN, inconsistent.action)
        assertEquals(ResearchVerificationReason.INCOMPLETE_CLAIM_COVERAGE, inconsistent.reason)
    }

    @Test fun `truncated and overlength windows cannot finalize even supported claims`() {
        val audit = snapshot(claim("c1", AuditVerificationLabel.SUPPORTED))
        for ((truncated, overlength) in listOf(true to false, false to true)) {
            val result = ResearchVerificationDecisionPolicy.decide(
                ResearchVerificationDecisionInput(
                    factualClaimIds = setOf("c1"),
                    audit = audit,
                    authoritativeCoverage = true,
                    candidateWindowTruncated = truncated,
                    overlengthSegmentsExcluded = overlength,
                ),
            )
            assertEquals(ResearchVerificationAction.ABSTAIN, result.action)
            assertEquals(ResearchVerificationReason.UNCERTAIN_CLAIM_EXTRACTION, result.reason)
        }
    }

    @Test fun `an omitted or extra factual id prevents finalization`() {
        val audit = snapshot(claim("c1", AuditVerificationLabel.SUPPORTED))
        assertEquals(
            ResearchVerificationReason.INCOMPLETE_CLAIM_COVERAGE,
            decide(ids = setOf("c1", "c2"), audit = audit, coverage = true).reason,
        )
        assertEquals(
            ResearchVerificationReason.INCOMPLETE_CLAIM_COVERAGE,
            decide(ids = setOf("other"), audit = audit, coverage = true).reason,
        )
    }

    @Test fun `contradiction triggers bounded revision and then abstention`() {
        val audit = snapshot(claim("c1", AuditVerificationLabel.CONTRADICTED))
        val revise = decide(ids = setOf("c1"), audit = audit, coverage = true, revisions = 1)
        assertEquals(ResearchVerificationAction.REVISE, revise.action)
        assertEquals(ResearchVerificationReason.CONTRADICTED_CLAIM, revise.reason)

        val exhausted = decide(ids = setOf("c1"), audit = audit, coverage = true, revisions = 0)
        assertEquals(ResearchVerificationAction.ABSTAIN, exhausted.action)
        assertEquals(ResearchVerificationReason.REVISION_BUDGET_EXHAUSTED, exhausted.reason)
    }

    @Test fun `contradictory and supportive evidence never becomes supported`() {
        val mixed = claim("c1", AuditVerificationLabel.SUPPORTED, AuditVerificationLabel.CONTRADICTED)
        assertEquals(AuditAggregateStatus.CONFLICTING_EVIDENCE, mixed.aggregateStatus)
        val decision = decide(ids = setOf("c1"), audit = snapshot(mixed), coverage = true, revisions = 1)
        assertEquals(ResearchVerificationAction.REVISE, decision.action)
        assertEquals(ResearchVerificationReason.CONFLICTING_EVIDENCE, decision.reason)
    }

    @Test fun `insufficient evidence is not contradiction or silent search consent`() {
        val audit = snapshot(claim("c1", AuditVerificationLabel.INSUFFICIENT))
        val noConsent = decide(ids = setOf("c1"), audit = audit, coverage = true, search = false)
        assertEquals(ResearchVerificationAction.ABSTAIN, noConsent.action)
        assertEquals(ResearchVerificationReason.SEARCH_NOT_AUTHORIZED, noConsent.reason)

        val exactConsent = decide(ids = setOf("c1"), audit = audit, coverage = true, search = true)
        assertEquals(ResearchVerificationAction.SEARCH_AGAIN, exactConsent.action)
        assertEquals(ResearchVerificationReason.INSUFFICIENT_EVIDENCE, exactConsent.reason)
    }

    @Test fun `verified support does not conceal a timeout on another evidence pair`() {
        val supported = claim("c1", AuditVerificationLabel.SUPPORTED)
        val partiallyTimedOut = ClaimSemanticVerification(
            claimId = "c1",
            backend = supported.backend,
            expectedEvidenceIdentities = supported.expectedEvidenceIdentities + "e2",
            pairExecutions = supported.pairExecutions + VerifierPairExecution(
                "e2", VerifierBackendResult.Failure(AuditBackendFailureReason.TIMEOUT),
            ),
            aggregateStatus = AuditAggregateStatus.SUPPORTED,
        )
        val decision = decide(ids = setOf("c1"), audit = snapshot(partiallyTimedOut), coverage = true)
        assertEquals(ResearchVerificationAction.ABSTAIN, decision.action)
        assertEquals(ResearchVerificationReason.VERIFIER_RESULT_INCOMPLETE, decision.reason)
    }

    @Test fun `all returned pairs can be valid while an unreturned expected pair keeps the claim incomplete`() {
        val incompleteClaim = ClaimSemanticVerification(
            claimId = "c1",
            backend = VerifierBackendDescriptor("test-koen", "1", setOf("ko", "en"), experimental = false),
            aggregateStatus = AuditAggregateStatus.SUPPORTED,
            expectedEvidenceIdentities = listOf("e1", "e2"),
            pairExecutions = listOf(
                VerifierPairExecution("e1", VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)),
            ),
        )

        val decision = decide(ids = setOf("c1"), audit = snapshot(incompleteClaim), coverage = true)

        assertTrue(incompleteClaim.pairExecutions.all { it.result is VerifierBackendResult.Verified })
        assertFalse(incompleteClaim.hasCompleteVerification)
        assertEquals(ResearchVerificationAction.ABSTAIN, decision.action)
        assertEquals(ResearchVerificationReason.VERIFIER_RESULT_INCOMPLETE, decision.reason)
    }

    @Test fun `unavailable backend and missing audit cannot be counted as factual support`() {
        assertEquals(
            ResearchVerificationReason.VERIFIER_UNAVAILABLE,
            decide(ids = setOf("c1"), audit = null, coverage = true).reason,
        )
        val unavailable = claim("c1", AuditVerificationLabel.SUPPORTED, availability = VerifierBackendAvailability.UNAVAILABLE)
        assertEquals(
            ResearchVerificationReason.VERIFIER_UNAVAILABLE,
            decide(ids = setOf("c1"), audit = snapshot(unavailable), coverage = true).reason,
        )
    }

    @Test fun `live backend returning unavailable for every pair stays backend unavailability`() {
        val unavailablePair = ClaimSemanticVerification(
            claimId = "c1",
            backend = VerifierBackendDescriptor("live-koen", "1", setOf("ko", "en"), experimental = false),
            aggregateStatus = AuditAggregateStatus.NOT_VERIFIED,
            expectedEvidenceIdentities = listOf("e1"),
            pairExecutions = listOf(VerifierPairExecution("e1", VerifierBackendResult.Unavailable)),
        )

        val decision = decide(ids = setOf("c1"), audit = snapshot(unavailablePair), coverage = true)

        assertEquals(ResearchVerificationAction.ABSTAIN, decision.action)
        assertEquals(ResearchVerificationReason.VERIFIER_UNAVAILABLE, decision.reason)
    }

    @Test fun `experimental verifier cannot control production answers without qualification`() {
        val audit = snapshot(claim("c1", AuditVerificationLabel.SUPPORTED, experimental = true))
        val production = decide(ids = setOf("c1"), audit = audit, coverage = true)
        assertEquals(ResearchVerificationAction.ABSTAIN, production.action)
        assertEquals(ResearchVerificationReason.EXPERIMENTAL_BACKEND_NOT_QUALIFIED, production.reason)

        val evaluation = decide(ids = setOf("c1"), audit = audit, coverage = true, evaluationOptIn = true)
        assertEquals(ResearchVerificationAction.FINAL, evaluation.action)
        assertTrue(evaluation.isFullyEvidenceSupported)
    }

    @Test fun `mixed factual claims do not falsely complete when one is insufficient`() {
        val audit = snapshot(
            claim("c1", AuditVerificationLabel.SUPPORTED),
            claim("c2", AuditVerificationLabel.INSUFFICIENT),
        )
        val decision = decide(ids = setOf("c1", "c2"), audit = audit, coverage = true)
        assertEquals(ResearchVerificationAction.ABSTAIN, decision.action)
        assertEquals(ResearchVerificationReason.SEARCH_NOT_AUTHORIZED, decision.reason)
    }

    private fun decide(
        ids: Set<String>,
        audit: AnswerAuditExecutionSnapshot?,
        coverage: Boolean,
        revisions: Int = 0,
        search: Boolean = false,
        evaluationOptIn: Boolean = false,
    ) = ResearchVerificationDecisionPolicy.decide(
        ResearchVerificationDecisionInput(
            factualClaimIds = ids,
            audit = audit,
            authoritativeCoverage = coverage,
            remainingRevisionAttempts = revisions,
            hasExactSearchAuthorization = search,
            allowExperimentalBackendInEvaluation = evaluationOptIn,
        ),
    )

    private fun snapshot(vararg claims: ClaimSemanticVerification) =
        AnswerAuditExecutionSnapshot(claims.toList())

    private fun claim(
        id: String,
        vararg labels: AuditVerificationLabel,
        experimental: Boolean = false,
        availability: VerifierBackendAvailability = VerifierBackendAvailability.AVAILABLE,
    ): ClaimSemanticVerification {
        val pairs = labels.mapIndexed { index, label ->
            VerifierPairExecution("e${index + 1}", VerifierBackendResult.Verified(label))
        }
        return ClaimSemanticVerification(
            claimId = id,
            backend = VerifierBackendDescriptor(
                id = "test-koen", version = "1", supportedLanguages = setOf("ko", "en"),
                experimental = experimental, availability = availability,
            ),
            aggregateStatus = aggregateVerificationResults(pairs.map { it.result }),
            expectedEvidenceIdentities = pairs.map { it.evidenceIdentity },
            pairExecutions = pairs,
        )
    }
}
