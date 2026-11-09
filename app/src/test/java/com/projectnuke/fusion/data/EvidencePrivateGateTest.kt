package com.projectnuke.fusion.data

import com.projectnuke.fusion.search.WebSearchProviderType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

// Phase 8: a local-RAG-derived answer must never turn its claims into web
// egress. The production EvidenceVerificationService gates targeted search
// while existing public sources and local assessment continue.
class EvidencePrivateGateTest {

    private class CountingExecutor : EvidenceSearchExecutor {
        val calls = AtomicInteger(0)
        val seenQueries = mutableListOf<String>()
        override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
            calls.incrementAndGet()
            seenQueries += request.query
            return EvidenceSearchResult.Success(emptyList())
        }
    }

    private fun candidate(id: String, text: String) = EvidenceClaimCandidate(
        id = id,
        text = text,
        ordinal = 0,
        classification = ClaimClassification.CHECKABLE_FACT,
        priority = ClaimPriority.HIGH,
        reason = ClaimClassificationReason.FACTUAL_DECLARATIVE,
    )

    private fun publicSource() = EvidencePlanningSource(
        id = "src-public",
        title = "Public article",
        source = "Example",
        url = "https://example.com/article",
        snippet = "public snippet",
        publishedAt = null,
        providerType = WebSearchProviderType.FREE_DEFAULT,
        providerDisplayName = "free",
        searchCategory = null,
        queryUsed = "existing query",
        rank = 1,
        qualityHints = emptyList(),
    )

    private fun planWithTarget(token: String) = VerificationPlan(
        claims = listOf(
            PlannedClaim(candidate("c1", "Claim about $token"), listOf("src-public"), true),
        ),
        existingSources = listOf(publicSource()),
        targetedSearchRequests = listOf(TargetedSearchRequest("c1", "verify $token")),
    )

    private fun ragMessage(token: String) = MessageEntity(
        12L, 7L, "assistant",
        "answer with $token\n\n[FUSION_LOCAL_RAG_SOURCES_JSON]\n[]\n[/FUSION_LOCAL_RAG_SOURCES_JSON]",
        2L,
    )

    @Test
    fun `private derived answer produces zero targeted web egress`() = runTest {
        val token = "EVIDENCE-PRIVATE-TOKEN-xyz"
        val executor = CountingExecutor()
        val service = EvidenceVerificationService(executor)
        val result = service.verify(ragMessage(token), planWithTarget(token))
        assertTrue(result is EvidenceVerificationResult.Complete)
        assertEquals(0, executor.calls.get())
        assertTrue(executor.seenQueries.none { it.contains(token) })
        // Local assessment still ran over the existing public source.
        val report = (result as EvidenceVerificationResult.Complete).report
        assertTrue(report.claims.any { it.id == "c1" })
    }

    @Test
    fun `existing public source evaluation still works for private answers`() = runTest {
        val token = "EVIDENCE-PRIVATE-TOKEN-xyz"
        val executor = CountingExecutor()
        val service = EvidenceVerificationService(executor)
        val result = service.verify(ragMessage(token), planWithTarget(token))
        assertTrue(result is EvidenceVerificationResult.Complete)
        val report = (result as EvidenceVerificationResult.Complete).report
        // The already-attached public source is still evaluated into the
        // report (re-identified by the accumulator); no new web search ran.
        assertTrue(report.sources.isNotEmpty())
        assertTrue(report.claims.any { it.id == "c1" })
        assertEquals(0, executor.calls.get())
    }

    @Test
    fun `ordinary answer evidence targeted search unchanged`() = runTest {
        val executor = CountingExecutor()
        val service = EvidenceVerificationService(executor)
        val message = MessageEntity(12L, 7L, "assistant", "ordinary answer about markets", 2L)
        val result = service.verify(message, planWithTarget("markets"))
        assertTrue(result is EvidenceVerificationResult.Complete)
        assertEquals(1, executor.calls.get())
        assertTrue(executor.seenQueries.any { it.contains("markets") })
    }
}
