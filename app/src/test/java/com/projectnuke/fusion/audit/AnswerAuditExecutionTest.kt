package com.projectnuke.fusion.audit

import com.projectnuke.fusion.data.EvidenceSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerAuditExecutionTest {
    @Test fun supportedPairsAggregateIndependentlyOfDurableEvidence() = runTest {
        val result = orchestrator { VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) }
            .verifyClaim(input(candidates(2)))

        assertEquals(AuditAggregateStatus.SUPPORTED, result.aggregateStatus)
        assertEquals(listOf("E1", "E2"), result.expectedEvidenceIdentities)
        assertEquals(listOf("E1", "E2"), result.pairExecutions.map { it.evidenceIdentity })
        assertTrue(result.hasCompleteVerification)
    }

    @Test fun contradictionWinsOverInsufficientButSupportAndContradictionConflict() = runTest {
        val results = ArrayDeque(
            listOf(
                VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED),
                VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT),
                VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED),
                VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED),
            ),
        )
        val verifier = orchestrator { results.removeFirst() }

        assertEquals(AuditAggregateStatus.CONTRADICTED, verifier.verifyClaim(input(candidates(2))).aggregateStatus)
        assertEquals(AuditAggregateStatus.CONFLICTING_EVIDENCE, verifier.verifyClaim(input(candidates(2))).aggregateStatus)
    }

    @Test fun backendFailureAndUnavailableNeverVote() = runTest {
        val results = ArrayDeque(
            listOf(
                VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED),
                VerifierBackendResult.Failure(AuditBackendFailureReason.EXECUTION_FAILED),
                VerifierBackendResult.Unavailable,
                VerifierBackendResult.Failure(AuditBackendFailureReason.EXECUTION_FAILED),
                VerifierBackendResult.Unavailable,
            ),
        )
        val verifier = orchestrator { results.removeFirst() }

        assertEquals(AuditAggregateStatus.SUPPORTED, verifier.verifyClaim(input(candidates(3))).aggregateStatus)
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, verifier.verifyClaim(input(candidates(2))).aggregateStatus)
    }

    @Test fun unavailableDefaultIsTypedAndProducesNoVisibleSemanticResult() = runTest {
        val snapshot = AnswerAuditExecutionOrchestrator().verifyClaims(listOf(input(candidates(1))))

        assertEquals(VerifierBackendAvailability.UNAVAILABLE, snapshot.claims.single().backend.availability)
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, snapshot.claims.single().aggregateStatus)
        assertTrue(snapshot.claims.single().expectedEvidenceIdentities.isNotEmpty())
        assertTrue(snapshot.claims.single().pairExecutions.none { it.submitted })
        assertFalse(snapshot.claims.single().hasCompleteVerification)
        assertTrue(snapshot.displayableClaims.isEmpty())
        assertNull(auditSemanticSectionLabel(snapshot))
    }

    @Test fun pairAndClaimBoundsAreEnforcedAndNotRequiredToolSkipsBackend() = runTest {
        var calls = 0
        val backend = fakeBackend { calls++; VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) }
        val verifier = AnswerAuditExecutionOrchestrator(backend)
        val bounded = verifier.verifyClaim(input(candidates(20)))
        assertEquals(VerifierCandidateAdapter.MAX_CANDIDATES_PER_CLAIM, bounded.pairExecutions.size)

        val toolResult = verifier.verifyClaim(
            input(
                candidates(1),
                requirement = AuditVerificationRequirement.NOT_REQUIRED,
                trace = AuditToolTrace("T1", "calculator", null, "2 + 2 = 4"),
            ),
        )
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, toolResult.aggregateStatus)
        assertTrue(toolResult.pairExecutions.isEmpty())
        assertEquals(8, calls)
    }

    @Test fun totalTimeoutIsTransientFailureAndParentCancellationPropagates() = runTest {
        val timeoutVerifier = AnswerAuditExecutionOrchestrator(
            fakeBackend { delay(6_000); VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) },
            totalDeadlineMs = 5_000,
        )
        val timedOut = timeoutVerifier.verifyClaim(input(candidates(2)))
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, timedOut.aggregateStatus)
        assertTrue(timedOut.pairExecutions.all {
            it.result == VerifierBackendResult.Failure(AuditBackendFailureReason.TIMEOUT)
        })
        assertEquals(2, timedOut.expectedEvidenceIdentities.size)
        assertTrue(timedOut.pairExecutions.first().submitted)
        assertFalse(timedOut.pairExecutions.last().submitted)
        assertEquals(1, timedOut.actualSubmittedPairCount)
        assertFalse(timedOut.hasCompleteVerification)

        val waiting = AnswerAuditExecutionOrchestrator(fakeBackend { awaitCancellation() })
        val job = launch { waiting.verifyClaim(input(candidates(1))) }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test fun experimentalResultsHaveBoundedPresentationAndUnavailableStaysHidden() = runTest {
        val result = AnswerAuditExecutionOrchestrator(
            fakeBackend(experimental = true) { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) },
        ).verifyClaims(listOf(input(candidates(1))))
        assertEquals("\uC2E4\uD5D8\uC801 \uC790\uB3D9 \uC758\uBBF8 \uAC80\uC99D", auditSemanticSectionLabel(result))
        assertEquals(
            "\uC790\uB8CC\uC640 \uC758\uBBF8\uC0C1 \uCDA9\uB3CC",
            auditSemanticStatusLabel(result.claims.single().aggregateStatus),
        )
        assertFalse(result.displayableClaims.single().pairExecutions.any { it.evidenceIdentity.isBlank() })
    }

    private fun orchestrator(
        verify: suspend (VerificationPairRequest) -> VerifierBackendResult,
    ) = AnswerAuditExecutionOrchestrator(fakeBackend(verify = verify))

    private fun fakeBackend(
        experimental: Boolean = false,
        verify: suspend (VerificationPairRequest) -> VerifierBackendResult,
    ) = object : VerifierBackend {
        override val descriptor = VerifierBackendDescriptor(
            id = "test-backend",
            version = "test-v1",
            supportedLanguages = setOf("en"),
            experimental = experimental,
        )

        override suspend fun verify(request: VerificationPairRequest) = verify(request)
    }

    private fun input(
        sources: List<EvidenceSource>,
        requirement: AuditVerificationRequirement = AuditVerificationRequirement.REQUIRED,
        trace: AuditToolTrace? = null,
    ) = VerifierClaimInput("C1", "Claim text", sources, requirement, trace)

    private fun candidates(count: Int) = (1..count).map { index ->
        EvidenceSource(
            id = "E$index", title = "Evidence $index", source = "Local", url = "",
            snippet = "Evidence body $index", publishedAt = null,
            providerType = "LOCAL", providerDisplayName = "Local", queryUsed = "must not enter pair",
        )
    }
}
