package com.projectnuke.fusion.retrieval

import com.projectnuke.fusion.data.ClaimClassification
import com.projectnuke.fusion.data.ClaimClassificationReason
import com.projectnuke.fusion.data.ClaimPriority
import com.projectnuke.fusion.data.ConservativeRuleEvidenceAssessor
import com.projectnuke.fusion.data.EvidenceClaimCandidate
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceVerdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun source(id: String, title: String, snippet: String = "") = EvidenceSource(
    id = id,
    title = title,
    source = "test",
    url = "https://example.com/$id",
    snippet = snippet,
    publishedAt = null,
    providerType = "TEST",
    providerDisplayName = "Test",
    queryUsed = "ignored-queryUsed-must-never-be-evidence",
)

private fun checkable(id: String, text: String) = EvidenceClaimCandidate(
    id = id,
    text = text,
    ordinal = 1,
    classification = ClaimClassification.CHECKABLE_FACT,
    priority = ClaimPriority.HIGH,
    reason = ClaimClassificationReason.FACTUAL_DECLARATIVE,
)

class SemanticEvidenceCandidatesTest {

    private val assessor = ConservativeRuleEvidenceAssessor()

    @Test
    fun `null matcher keeps lexical path`() = runTest {
        val result = SemanticEvidenceCandidates.rankSources("claim", listOf(source("E1", "t")), null)
        assertEquals(null, result)
    }

    @Test
    fun `unavailable matcher falls back to lexical ids`() = runTest {
        val failing = FakeEmbeddingEngine(
            emptyMap(),
            failure = EmbeddingResult.Failure(EmbeddingFailureReason.MODEL_LOAD_FAILURE, "boom"),
        )
        val ranked = SemanticEvidenceCandidates.rankSources(
            "claim",
            listOf(source("E1", "ProductX2 supports 12GB.")),
            EmbeddingSemanticCandidateMatcher(failing),
        )
        assertEquals(EmbeddingFailureReason.MODEL_LOAD_FAILURE, ranked?.unavailableReason)
        assertEquals(
            listOf("E1"),
            SemanticEvidenceCandidates.expand(listOf("E1"), ranked),
        )
    }

    @Test
    fun `segments exclude queryUsed`() {
        val segments = SemanticEvidenceCandidates.segmentsForSources(
            listOf(source("E1", "Real title", "Real snippet")),
        )
        assertEquals(1, segments.size)
        assertTrue(!segments.single().text.contains("ignored-queryUsed"))
    }

    @Test
    fun `expand only adds bounded ids deterministically`() {
        val ranked = SemanticMatchResult(
            listOf(
                RankedSemanticMatch("E2", 0.9f, 1),
                RankedSemanticMatch("E3", 0.8f, 2),
                RankedSemanticMatch("E2", 0.9f, 1),
            ),
            null,
        )
        assertEquals(listOf("E1", "E2", "E3"), SemanticEvidenceCandidates.expand(listOf("E1"), ranked))
    }

    @Test
    fun `adversarial top-ranked negation never forces SUPPORT`() = runTest {
        // Matcher is fooled on purpose: the negation embeds identically to the claim.
        val claim = "ProductX2 supports 12GB."
        val negation = "ProductX2 does not support 12GB."
        val engine = FakeEmbeddingEngine(
            mapOf(
                E5InputContract.query(claim) to floatArrayOf(1f, 0f, 0f, 0f),
                E5InputContract.passage(negation) to floatArrayOf(1f, 0f, 0f, 0f),
            ),
        )
        val ranked = SemanticEvidenceCandidates.rankSources(
            claim,
            listOf(source("E1", negation)),
            EmbeddingSemanticCandidateMatcher(engine),
        )
        assertEquals(listOf("E1"), ranked?.matches?.map { it.segmentId })
        // Deterministic rules remain authoritative: negation cannot support.
        val verdict = assessor.assess(checkable("C1", claim), listOf(source("E1", negation))).verdict
        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, verdict)
    }

    @Test
    fun `value mismatch stays unsafe regardless of similarity`() {
        val claim = "ProductX2 has 12GB RAM."
        val verdict = assessor.assess(
            checkable("C1", claim),
            listOf(source("E1", "ProductX2 has 8GB RAM.")),
        ).verdict
        // Either conflict or insufficient is safe; SUPPORT is forbidden.
        assertTrue(verdict != EvidenceVerdict.SUPPORTED_BY_EVIDENCE)
    }
}
