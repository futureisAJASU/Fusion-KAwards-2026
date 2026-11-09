package com.projectnuke.fusion.projects

import com.projectnuke.fusion.audit.AuditBackendFailureReason
import com.projectnuke.fusion.audit.AuditVerificationLabel
import com.projectnuke.fusion.audit.VerificationPairRequest
import com.projectnuke.fusion.audit.VerifierBackend
import com.projectnuke.fusion.audit.VerifierBackendAvailability
import com.projectnuke.fusion.audit.VerifierBackendDescriptor
import com.projectnuke.fusion.audit.VerifierBackendResult
import com.projectnuke.fusion.retrieval.EmbeddingFailureReason
import com.projectnuke.fusion.retrieval.EmbeddingResult
import com.projectnuke.fusion.retrieval.SymmetricEmbeddingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectConsistencySemanticReviewTest {
    @Test fun relatedDifferentTitlePairIsOneReviewOnlyConflict() = runTest {
        val sources = listOf(source("a"), source("b"))
        val embeddings = FakeEmbeddings { FloatArray(2) { 1f } }
        val verifier = FakeVerifier { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) }

        val issues = ProjectConsistencySemanticReview.review(sources, embeddings, verifier)

        assertEquals(1, issues.size)
        assertEquals(ProjectConsistencyIssueKind.POTENTIAL_CONFLICT, issues.single().kind)
        assertEquals(ProjectConsistencyIssueOrigin.SEMANTIC_VERIFIER, issues.single().origin)
        assertTrue(issues.single().statement.contains("오류나 거짓으로 판정하지 않습니다"))
        assertFalse(issues.single().statement.contains("거짓입니다"))
        assertEquals(2, verifier.requests.size)
        assertEquals(setOf("a", "b"), verifier.requests.map { it.evidenceIdentity }.toSet())
    }

    @Test fun verifierRequiresContradictionWithoutAnySupportedDirection() = runTest {
        suspend fun review(labels: List<VerifierBackendResult>) = ProjectConsistencySemanticReview.review(
            listOf(source("a"), source("b")), FakeEmbeddings { floatArrayOf(1f, 0f) },
            FakeVerifier { labels.getOrElse(it) { VerifierBackendResult.Unavailable } },
        )
        fun verified(label: AuditVerificationLabel) = VerifierBackendResult.Verified(label)

        assertEquals(1, review(listOf(verified(AuditVerificationLabel.CONTRADICTED), verified(AuditVerificationLabel.INSUFFICIENT))).size)
        assertEquals(1, review(listOf(verified(AuditVerificationLabel.CONTRADICTED), verified(AuditVerificationLabel.CONTRADICTED))).size)
        assertTrue(review(listOf(verified(AuditVerificationLabel.SUPPORTED), verified(AuditVerificationLabel.SUPPORTED))).isEmpty())
        assertTrue(review(listOf(verified(AuditVerificationLabel.SUPPORTED), verified(AuditVerificationLabel.CONTRADICTED))).isEmpty())
        assertTrue(review(listOf(verified(AuditVerificationLabel.INSUFFICIENT), verified(AuditVerificationLabel.INSUFFICIENT))).isEmpty())
        assertTrue(review(listOf(VerifierBackendResult.Unavailable, VerifierBackendResult.Unavailable)).isEmpty())
        assertTrue(review(listOf(
            VerifierBackendResult.Failure(AuditBackendFailureReason.TIMEOUT), VerifierBackendResult.Unavailable,
        )).isEmpty())
    }

    @Test fun deterministicPairIsExcludedFromSupplementalVerifier() = runTest {
        val a = source("a", title = "Same title", kind = ProjectConsistencySourceKind.DECISION)
        val b = source("b", title = "Same title", kind = ProjectConsistencySourceKind.DECISION)
        val verifier = FakeVerifier { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) }

        val issues = ProjectConsistencySemanticReview.review(listOf(a, b), FakeEmbeddings { floatArrayOf(1f) }, verifier)

        assertEquals(listOf(ProjectConsistencyIssueKind.UNRESOLVED_DIFFERENCE), issues.map { it.kind })
        assertTrue(verifier.requests.isEmpty())
    }

    @Test fun topRankedPairIsComparedOnceWithoutReverseDuplicate() = runTest {
        val sources = listOf(source("a"), source("b"), source("c"))
        val embeddings = FakeEmbeddings { text ->
            when {
                text.startsWith("Title c") -> floatArrayOf(0f, 1f)
                else -> floatArrayOf(1f, 0f)
            }
        }
        val verifier = FakeVerifier { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) }

        val issues = ProjectConsistencySemanticReview.review(sources, embeddings, verifier)

        assertEquals(1, issues.size)
        assertEquals(setOf("a", "b"), setOf(issues.single().sourceA.id, issues.single().sourceB.id))
        assertEquals(2, verifier.requests.size)
        assertEquals(setOf("a", "b"), verifier.requests.map { it.evidenceIdentity }.toSet())
    }

    @Test fun unavailableEmbeddingFallsBackToDeterministicReview() = runTest {
        val expected = ProjectConsistencyPolicy.review(listOf(source("a", "Same", "enabled"), source("b", "Same", "disabled")))
        val actual = ProjectConsistencySemanticReview.review(
            listOf(source("a", "Same", "enabled"), source("b", "Same", "disabled")),
            FakeEmbeddings { floatArrayOf(1f) }.also { it.failure = EmbeddingResult.Failure(EmbeddingFailureReason.ASSET_MISSING, "") },
            FakeVerifier { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) },
        )
        assertEquals(expected, actual)
    }

    @Test fun embeddingsAreComputedOncePerSourceAndChunkedAtEngineLimit() = runTest {
        val sources = (0 until 35).map { source("s$it") }
        val embeddings = FakeEmbeddings { floatArrayOf(1f, 0f) }
        val verifier = FakeVerifier { VerifierBackendResult.Unavailable }

        ProjectConsistencySemanticReview.review(sources, embeddings, verifier)

        assertEquals(listOf(32, 3), embeddings.batches.map(List<String>::size))
        assertEquals(sources.size, embeddings.batches.sumOf(List<String>::size))
        assertTrue(verifier.requests.size <= MAX_PROJECT_CONSISTENCY_VERIFIER_PAIRS * 2)
    }

    @Test fun cancelledEmbeddingPropagatesWithoutIssues() = runTest {
        val embeddings = object : SymmetricEmbeddingSource {
            override suspend fun embedSources(texts: List<String>): EmbeddingResult = throw CancellationException("cancel")
        }
        try {
            ProjectConsistencySemanticReview.review(
                listOf(source("a"), source("b")), embeddings,
                FakeVerifier { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) },
            )
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation is not converted to model unavailability.
        }
    }

    @Test fun semanticOnlyBudgetNeverExceedsGlobalIssueLimit() = runTest {
        val sources = (0 until 12).map { source("s$it", title = "Topic $it") }
        val embeddings = FakeEmbeddings { floatArrayOf(1f, 0f) }
        val verifier = FakeVerifier { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) }
        val issues = ProjectConsistencySemanticReview.review(sources, embeddings, verifier)
        assertEquals(MAX_PROJECT_CONSISTENCY_ISSUES, issues.size)
        assertTrue(issues.all { it.origin == ProjectConsistencyIssueOrigin.SEMANTIC_VERIFIER })
        assertTrue(verifier.requests.size <= MAX_PROJECT_CONSISTENCY_VERIFIER_PAIRS * 2)
        assertFalse(issues.toString().contains("score", ignoreCase = true))
    }

    @Test fun deterministicIssuesConsumeBudgetBeforeSemanticRuntime() = runTest {
        val sources = (0 until 6).map { source("s$it", title = "Different $it", text = "Shared body with enough content to count as an exact duplicate conclusion.") }
        val embeddings = FakeEmbeddings { floatArrayOf(1f, 0f) }
        val verifier = FakeVerifier { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) }

        val issues = ProjectConsistencySemanticReview.review(sources, embeddings, verifier)

        assertEquals(MAX_PROJECT_CONSISTENCY_ISSUES, issues.size)
        assertTrue(issues.all { it.origin == ProjectConsistencyIssueOrigin.DETERMINISTIC })
        assertTrue(embeddings.batches.isEmpty())
        assertTrue(verifier.requests.isEmpty())
    }

    private fun source(
        id: String,
        title: String = "Title $id",
        text: String = "Body $id has bounded content.",
        kind: ProjectConsistencySourceKind = ProjectConsistencySourceKind.SAVED_KNOWLEDGE,
    ) = ProjectConsistencySource(id, kind, title, text)

    private class FakeEmbeddings(
        private val vector: (String) -> FloatArray,
    ) : SymmetricEmbeddingSource {
        val batches = mutableListOf<List<String>>()
        var failure: EmbeddingResult.Failure? = null

        override suspend fun embedSources(texts: List<String>): EmbeddingResult {
            batches += texts.toList()
            failure?.let { return it }
            return EmbeddingResult.Success(texts.map(vector), 2)
        }
    }

    private class FakeVerifier(
        private val result: (Int) -> VerifierBackendResult,
    ) : VerifierBackend {
        override val descriptor = VerifierBackendDescriptor(
            id = "fake", version = "test", supportedLanguages = setOf("ko", "en"), experimental = true,
            availability = VerifierBackendAvailability.AVAILABLE,
        )
        val requests = mutableListOf<VerificationPairRequest>()
        override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult {
            requests += request
            return result(requests.lastIndex)
        }
    }
}
