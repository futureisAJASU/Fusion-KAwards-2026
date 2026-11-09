package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.EvidenceSearchExecutor
import com.projectnuke.fusion.data.EvidenceSearchResult
import com.projectnuke.fusion.data.EvidenceVerificationResult
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.TargetedSearchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchEvidenceOwnerTest {
    @Test
    fun `automatic research plan remains targeted-search-free`() = runTest {
        val answer = "Acme released ProductX2 in 2026.\nBeta announced a 2025 update."
        val plan = ResearchEvidenceOwner.boundedPlan(answer)
        var searchCalls = 0
        val verifier = SearchBackedEvidenceUiVerifier(object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                searchCalls++
                return EvidenceSearchResult.Success(emptyList())
            }
        })

        val result = verifier.verify(MessageEntity(3L, 9L, "assistant", answer, 1L), plan)

        assertTrue(plan.claims.isNotEmpty())
        assertTrue(plan.targetedSearchRequests.isEmpty())
        assertEquals(0, searchCalls)
        assertTrue(result is EvidenceVerificationResult.Complete)
    }
}
