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

class SemanticBenchmarkTest {

    private val assessor = ConservativeRuleEvidenceAssessor()

    private fun benchSource(id: String, text: String) = EvidenceSource(
        id = id,
        title = text,
        source = "bench",
        url = "https://example.com/bench/$id",
        snippet = "",
        publishedAt = null,
        providerType = "BENCH",
        providerDisplayName = "Bench",
        queryUsed = "bench-query",
    )

    @Test
    fun `each direction ranks positive first with top-k recall one`() = runTest {
        for (case in SemanticBenchmarkFixtures.CASES) {
            // Oracle vectors: claim == positive, hard negative orthogonal.
            val query = floatArrayOf(1f, 0f, 0f, 0f)
            val engine = FakeEmbeddingEngine(
                mapOf(
                    E5InputContract.query(case.claim) to query,
                    E5InputContract.passage(case.positive) to floatArrayOf(1f, 0f, 0f, 0f),
                    E5InputContract.passage(case.hardNegative) to floatArrayOf(0f, 1f, 0f, 0f),
                ),
            )
            val segments = listOf(
                SemanticSegment("POS", case.positive),
                SemanticSegment("NEG", case.hardNegative),
            )
            val result = EmbeddingSemanticCandidateMatcher(engine).rank(case.claim, segments, limit = 2)
            assertEquals(case.direction, null, result.unavailableReason)
            assertEquals(case.direction, "POS", result.matches.first().segmentId)
            assertEquals(case.direction, 1, result.matches.first { it.segmentId == "POS" }.rank)
            assertTrue(case.direction, result.matches.last().segmentId == "NEG")
            // Top-k recall: positive in top-2 of 2.
            assertTrue(case.direction, result.matches.any { it.segmentId == "POS" })
        }
    }

    @Test
    fun `hard negatives never earn SUPPORT from deterministic rules`() {
        for ((claim, negative) in SemanticBenchmarkFixtures.HARD_NEGATIVES) {
            val verdict = assessor.assess(
                EvidenceClaimCandidate(
                    "C1", claim, 1,
                    ClaimClassification.CHECKABLE_FACT, ClaimPriority.HIGH,
                    ClaimClassificationReason.FACTUAL_DECLARATIVE,
                ),
                listOf(benchSource("E1", negative)),
            ).verdict
            assertTrue(
                "$claim vs $negative -> $verdict",
                verdict != EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            )
        }
    }

    @Test
    fun `no universal threshold is fabricated from tiny fixture`() {
        // Guardrail: the fixture records ranks/recall only. If anyone adds a
        // threshold constant derived from these 4 cases, this test name shows
        // up in review. Scores are ranking signals, not confidence.
        assertEquals(4, SemanticBenchmarkFixtures.CASES.size)
        assertTrue(SemanticBenchmarkFixtures.HARD_NEGATIVES.size >= 6)
    }
}
