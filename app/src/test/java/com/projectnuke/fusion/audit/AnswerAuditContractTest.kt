package com.projectnuke.fusion.audit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnswerAuditContractTest {
    @Test fun aggregateUsesCategoricalEvidenceWithoutThresholds() {
        fun verified(label: AuditVerificationLabel) = VerifierBackendResult.Verified(label)
        assertEquals(AuditAggregateStatus.SUPPORTED, aggregateVerificationResults(listOf(verified(AuditVerificationLabel.SUPPORTED))))
        assertEquals(AuditAggregateStatus.CONTRADICTED, aggregateVerificationResults(listOf(verified(AuditVerificationLabel.CONTRADICTED))))
        assertEquals(AuditAggregateStatus.INSUFFICIENT, aggregateVerificationResults(listOf(verified(AuditVerificationLabel.INSUFFICIENT))))
        assertEquals(
            AuditAggregateStatus.CONFLICTING_EVIDENCE,
            aggregateVerificationResults(listOf(verified(AuditVerificationLabel.SUPPORTED), verified(AuditVerificationLabel.CONTRADICTED))),
        )
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, aggregateVerificationResults(emptyList()))
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, aggregateVerificationResults(listOf(VerifierBackendResult.Unavailable)))
    }

    @Test fun defaultBackendIsUnavailableAndNeverInsufficient() = runBlocking {
        val request = request()
        assertEquals(VerifierBackendResult.Unavailable, UnavailableVerifierBackend.verify(request))
        assertEquals(VerifierBackendResult.Unavailable, UnavailableVerifierBackend.verifySafely(request))
    }

    @Test fun backendFailureAndCancellationStayDistinct(): Unit = runBlocking {
        val request = request()
        val failure = object : VerifierBackend {
            override val descriptor = descriptor()
            override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult =
                VerifierBackendResult.Failure(AuditBackendFailureReason.EXECUTION_FAILED)
        }
        assertEquals(VerifierBackendResult.Failure(AuditBackendFailureReason.EXECUTION_FAILED), failure.verifySafely(request))

        val cancelled = object : VerifierBackend {
            override val descriptor = descriptor()
            override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult = throw CancellationException("stop")
        }
        assertThrows(CancellationException::class.java) { runBlocking { cancelled.verifySafely(request) } }
    }

    @Test fun diagnosticScoresRejectNonFiniteAndOutOfRangeValues() {
        assertThrows(IllegalArgumentException::class.java) { VerifierConfidenceScore("support", Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { VerifierConfidenceScore("support", Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { VerifierConfidenceScore("support", 1.01) }
    }

    @Test fun malformedAndAggregatorOnlyLabelsFailClosedAtBackendBoundary() {
        assertEquals(
            VerifierBackendResult.Failure(AuditBackendFailureReason.INVALID_OUTPUT),
            verifierBackendResultFromRawLabel("CONFLICTING"),
        )
        assertEquals(
            VerifierBackendResult.Failure(AuditBackendFailureReason.INVALID_OUTPUT),
            verifierBackendResultFromRawLabel("SUPPORTED_BY_EVIDENCE"),
        )
        assertEquals(
            VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED),
            verifierBackendResultFromRawLabel("SUPPORTED"),
        )
    }

    @Test fun coverageCannotBeInferredFromCheckedClaimsAlone() {
        assertEquals(AuditCoverage.NOT_AUDITED, auditCoverage(0, 0, false))
        assertEquals(AuditCoverage.PARTIALLY_AUDITED, auditCoverage(2, 2, false))
        assertEquals(AuditCoverage.AUDITED, auditCoverage(2, 2, true))
        assertEquals(AuditCoverage.PARTIALLY_AUDITED, auditCoverage(2, 1, true))
    }

    private fun request() = VerificationPairRequest("claim", "evidence", evidenceIdentity = "E1")
    private fun descriptor() = VerifierBackendDescriptor("test", "1", emptySet(), experimental = true)
}
