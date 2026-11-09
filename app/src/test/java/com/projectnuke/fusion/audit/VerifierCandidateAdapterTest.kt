package com.projectnuke.fusion.audit

import com.projectnuke.fusion.data.EvidenceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifierCandidateAdapterTest {
    @Test fun selectedCandidatesBecomeBoundedPairsWithoutSimilarityOrSupportLabels() {
        val candidates = (1..20).map { source("E$it", "candidate $it") }
        val requests = VerifierCandidateAdapter.requestsForSelectedCandidates(
            claimText = "claim",
            selectedCandidates = candidates,
        )
        assertEquals(VerifierCandidateAdapter.MAX_CANDIDATES_PER_CLAIM, requests.size)
        assertEquals((1..8).map { "E$it" }, requests.map { it.evidenceIdentity })
        assertTrue(requests.all { it.evidenceText.length <= MAX_PAIR_TEXT_LENGTH })
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, aggregateVerificationResults(emptyList()))
    }

    @Test fun duplicateCandidateIdsAreCollapsedAndNoCandidateMeansNoVerification() {
        val candidate = source("E1", "Highly similar evidence")
        val requests = VerifierCandidateAdapter.requestsForSelectedCandidates(
            "claim", listOf(candidate, candidate),
        )
        assertEquals(1, requests.size)
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, aggregateVerificationResults(emptyList()))
    }

    @Test fun sourceTitleStaysInIdentityButOnlyEvidencePassageReachesVerifier() {
        val candidate = source("file-17", "Qualification evidence: The cat is on the mat.")
            .copy(title = "FusionVQ2_controlled_evidence.txt")

        val request = VerifierCandidateAdapter.requestsForSelectedCandidates(
            "고양이가 매트 위에 있습니다.", listOf(candidate),
        ).single()

        assertEquals("Qualification evidence: The cat is on the mat.", request.evidenceText)
        assertEquals("file-17", request.evidenceIdentity)
    }

    @Test fun titleOnlyOrBlankPassagesDoNotBecomeVerifierEvidence() {
        val titleOnly = source("title-only", "").copy(title = "The cat is on the mat")
        val blankPassage = source("blank-passage", " \n\t").copy(title = "Relevant title")

        val requests = VerifierCandidateAdapter.requestsForSelectedCandidates(
            "The cat is on the mat.", listOf(titleOnly, blankPassage),
        )

        assertTrue(requests.isEmpty())
    }

    private fun source(id: String, snippet: String) = EvidenceSource(
        id = id, title = "Title", source = "Publisher", url = "https://example.com/$id",
        snippet = snippet, publishedAt = null, providerType = "WEB", providerDisplayName = "Web", queryUsed = "query",
    )
}
