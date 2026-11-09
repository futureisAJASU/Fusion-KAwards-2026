package com.projectnuke.fusion.data

import com.projectnuke.fusion.audit.*
import com.projectnuke.fusion.retrieval.RankedSemanticMatch
import com.projectnuke.fusion.retrieval.SemanticCandidateMatcher
import com.projectnuke.fusion.retrieval.SemanticMatchResult
import com.projectnuke.fusion.retrieval.SemanticSegment
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceAuditExecutionIntegrationTest {
    @Test fun `post semantic candidate set reaches backend while rule report remains unchanged`() = runTest {
        val message = MessageEntity(1, 1, "assistant", "ProductX2 has 12GB RAM.", 10)
        val plan = plan()
        val matcher = semanticMatcher()
        val unavailable = runEvidence(message, plan, matcher, UnavailableVerifierBackend)
        val recordingBackend = RecordingBackend { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) }
        val experimental = runEvidence(message, plan, matcher, recordingBackend)

        assertEquals(unavailable.report, experimental.report)
        assertEquals(EvidenceVerdict.SUPPORTED_BY_EVIDENCE, experimental.report.claims.single().verdict)
        assertEquals(listOf("E1"), experimental.report.claims.single().sourceIds)
        assertEquals(EVIDENCE_VERIFIER_VERSION, experimental.report.verifierVersion)
        assertEquals(
            listOf("E1", "E2"),
            recordingBackend.requests.map(VerificationPairRequest::evidenceIdentity),
        )
        assertTrue(recordingBackend.requests.all { "PRIVATE_QUERY_MARKER" !in it.evidenceText })
        assertEquals(AuditAggregateStatus.CONTRADICTED, experimental.transientAudit!!.claims.single().aggregateStatus)
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, unavailable.transientAudit!!.claims.single().aggregateStatus)
        val projection = projectEvidenceReport(
            experimental.report,
            semanticVerificationsByClaimId = experimental.transientAudit!!.claims.associateBy { it.claimId },
        )!!
        assertEquals(AuditAggregateStatus.SUPPORTED, projection.claims.single().verification.status)
        assertEquals(AuditAggregateStatus.CONTRADICTED, projection.claims.single().semanticVerification?.aggregateStatus)
    }

    @Test fun `backend exception and timeout preserve a complete durable report`() = runTest {
        val message = MessageEntity(2, 1, "assistant", "ProductX2 has 12GB RAM.", 10)
        val plan = plan()
        val matcher = semanticMatcher()
        val failure = runEvidence(message, plan, matcher, RecordingBackend { throw IOException("backend unavailable") })

        assertEquals(EvidenceVerdict.SUPPORTED_BY_EVIDENCE, failure.report.claims.single().verdict)
        assertEquals(
            VerifierBackendResult.Failure(AuditBackendFailureReason.EXECUTION_FAILED),
            failure.transientAudit!!.claims.single().pairExecutions.first().result,
        )

        val timedOut = runEvidence(
            message,
            plan,
            matcher,
            RecordingBackend { delay(6_000); VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) },
            deadlineMs = 5_000,
        )
        assertEquals(EvidenceVerdict.SUPPORTED_BY_EVIDENCE, timedOut.report.claims.single().verdict)
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, timedOut.transientAudit!!.claims.single().aggregateStatus)
        assertTrue(timedOut.transientAudit!!.claims.single().pairExecutions.all {
            it.result == VerifierBackendResult.Failure(AuditBackendFailureReason.TIMEOUT)
        })
    }

    @Test fun `parent cancellation during backend execution propagates`() = runTest {
        val message = MessageEntity(3, 1, "assistant", "ProductX2 has 12GB RAM.", 10)
        val entered = CompletableDeferred<Unit>()
        val service = service(semanticMatcher(), RecordingBackend {
            entered.complete(Unit)
            awaitCancellation()
        })
        val job = launch { service.verify(message, plan()) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    private suspend fun runEvidence(
        message: MessageEntity,
        plan: VerificationPlan,
        matcher: SemanticCandidateMatcher,
        backend: VerifierBackend,
        deadlineMs: Long = AUDIT_BACKEND_TOTAL_DEADLINE_MS,
    ): EvidenceVerificationResult.Complete {
        val result = service(matcher, backend, deadlineMs).verify(message, plan)
        assertTrue("expected durable completion but was $result", result is EvidenceVerificationResult.Complete)
        return result as EvidenceVerificationResult.Complete
    }

    private fun service(
        matcher: SemanticCandidateMatcher,
        backend: VerifierBackend,
        deadlineMs: Long = AUDIT_BACKEND_TOTAL_DEADLINE_MS,
    ) = EvidenceVerificationService(
        searchExecutor = object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest) = EvidenceSearchResult.Success(emptyList())
        },
        clock = EvidenceClock { 1234L },
        semanticMatcher = matcher,
        auditExecutionOrchestrator = AnswerAuditExecutionOrchestrator(backend, deadlineMs),
    )

    private fun plan(): VerificationPlan {
        val claimText = "ProductX2 has 12GB RAM."
        val plan = EvidenceVerificationPlanner.plan(
            claimText,
            existingSources = listOf(
                webSource("ProductX2 has 12GB RAM.", "lexical"),
                webSource("Unrelated product documentation.", "semantic"),
            ),
        )
        assertTrue(plan.claims.single().existingSourceCandidateIds.contains("E1"))
        assertTrue("E2" !in plan.claims.single().existingSourceCandidateIds)
        return plan
    }

    private fun semanticMatcher() = object : SemanticCandidateMatcher {
        override suspend fun rank(claim: String, segments: List<SemanticSegment>, limit: Int): SemanticMatchResult {
            assertTrue(segments.any { it.id == "E1" })
            assertTrue(segments.any { it.id == "E2" })
            return SemanticMatchResult(listOf(RankedSemanticMatch("E2", 0.999f, 1)), null)
        }
    }

    private fun webSource(snippet: String, query: String) = WebSearchSource(
        title = snippet,
        source = "Publisher",
        url = "https://example.com/$query",
        snippet = snippet,
        publishedAt = null,
        providerType = WebSearchProviderType.FREE_DEFAULT,
        providerDisplayName = "Free Search",
        searchCategory = null,
        queryUsed = query + "_PRIVATE_QUERY_MARKER",
        rank = 1,
        qualityHints = emptyList(),
    )

    private class RecordingBackend(
        private val result: suspend (VerificationPairRequest) -> VerifierBackendResult,
    ) : VerifierBackend {
        val requests = mutableListOf<VerificationPairRequest>()
        override val descriptor = VerifierBackendDescriptor(
            "test-experimental", "test-v1", setOf("en"), experimental = true,
        )

        override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult {
            requests += request
            return result(request)
        }
    }
}
