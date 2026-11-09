package com.projectnuke.fusion.projects

import com.projectnuke.fusion.data.ProjectDecisionEntity
import com.projectnuke.fusion.retrieval.EmbeddingFailureReason
import com.projectnuke.fusion.retrieval.EmbeddingResult
import com.projectnuke.fusion.retrieval.FusionEmbeddingLimits
import com.projectnuke.fusion.retrieval.SymmetricEmbeddingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDecisionIntelligenceTest {
    @Test
    fun `related decision labels use revised Korean copy`() {
        assertEquals("내용이 비슷한 현재 결정", ProjectDecisionIntelligencePolicy.labelForReason("content_related"))
        assertEquals("관련된 현재 결정", ProjectDecisionIntelligencePolicy.labelForReason("unknown"))
    }

    @Test
    fun `deterministic admission keeps only active same project decisions`() = runBlocking {
        var requestedLimit = 0
        val candidates = listOf(
            decision("accepted", 1, title = matchingTitle("a")),
            decision("superseded", 2, status = "SUPERSEDED", title = matchingTitle("b")),
            decision("other-project", 3, projectId = 2, title = matchingTitle("c")),
            decision("unrelated", 4, title = "completely unrelated phrase"),
        )
        val embedding = FakeEmbedding(vectorFor = { _, _ -> vector(1f, 0f) })
        val repository = repository(candidates, embedding) { _, limit -> requestedLimit = limit }

        val result = repository.suggestActiveCandidates(1, "shared alpha beta gamma", "")

        assertEquals(ProjectDecisionIntelligencePolicy.MAX_DECISION_CANDIDATES, requestedLimit)
        assertEquals(listOf("accepted"), result.map { it.decision.id })
    }

    @Test
    fun `semantic similarity cannot admit a deterministic noncandidate`() = runBlocking {
        val embedding = FakeEmbedding(vectorFor = { _, _ -> vector(1f, 0f) })
        val repository = repository(listOf(decision("semantic-only", 1, title = "unrelated wording")), embedding)

        val result = repository.suggestActiveCandidates(1, "shared alpha beta gamma", "")

        assertTrue(result.isEmpty())
        assertTrue("Non-admitted candidates must not be embedded", embedding.batches.isEmpty())
    }

    @Test
    fun `short generic exact title is admitted by explicit title rule despite unrelated rationale`() = runBlocking {
        val candidate = decision("generic", 1, title = "AI").copy(rationale = "Repair a garden fence and plan a bicycle trip")
        val repository = repository(listOf(candidate), FakeEmbedding({ _, _ -> vector(1f, 0f) }))

        val result = repository.suggestActiveCandidates(
            projectId = 1,
            currentTitle = "AI",
            currentRationale = "Prepare an unrelated household budget",
        )

        assertEquals(listOf("generic"), result.map { it.decision.id })
        assertEquals("title_near", result.single().reason)
    }

    @Test
    fun `Korean morphology mismatch and unrelated rationale do not admit by semantic score`() = runBlocking {
        val candidate = decision("morphology", 1, title = "\uD504\uB85C\uC81D\uD2B8 \uAC80\uD1A0")
            .copy(rationale = "\uC0BC\uAC01\uD615 \uC815\uC6D0\uC5D0 \uB098\uBB34\uB97C \uC2EC\uC2B5\uB2C8\uB2E4")
        val embedding = FakeEmbedding({ _, _ -> vector(1f, 0f) })
        val repository = repository(listOf(candidate), embedding)

        val result = repository.suggestActiveCandidates(
            projectId = 1,
            currentTitle = "\uD504\uB85C\uC81D\uD2B8\uB97C \uAC80\uD1A0\uD569\uB2C8\uB2E4",
            currentRationale = "\uAC00\uC815\uC6A9 \uC608\uC0B0\uC744 \uC791\uC131\uD569\uB2C8\uB2E4",
        )

        assertTrue(result.isEmpty())
        assertTrue("Semantic score cannot independently admit a Decision", embedding.batches.isEmpty())
    }

    @Test
    fun `semantic ranking reorders all admitted decisions before final top three`() = runBlocking {
        val candidates = (0..3).map { index -> decision("d$index", createdAt = 100L - index, title = matchingTitle("c$index")) }
        val embedding = FakeEmbedding(vectorFor = { text, _ ->
            when (text.substringBefore('\n').split(' ').lastOrNull()) {
                "gamma" -> vector(1f, 0f)
                "c0" -> vector(0f, 1f)
                "c1" -> vector(-1f, 0f)
                "c2" -> vector(0.6f, 0.8f)
                "c3" -> vector(1f, 0f)
                else -> vector(1f, 0f)
            }
        })
        val repository = repository(candidates, embedding)

        val result = repository.suggestActiveCandidates(1, "shared alpha beta gamma", "")

        assertEquals(listOf("d3", "d2", "d0"), result.map { it.decision.id })
        assertEquals(5, embedding.batches.sumOf { it.size }) // draft plus every admitted Decision
    }

    @Test
    fun `embedding failure preserves deterministic candidates before top three`() = runBlocking {
        val candidates = (0..4).map { index -> decision("d$index", createdAt = 100L - index, title = matchingTitle("c$index")) }
        val embedding = FakeEmbedding(vectorFor = { _, _ -> vector(1f, 0f) }, failOnCall = 0)
        val repository = repository(candidates, embedding)

        val result = repository.suggestActiveCandidates(1, "shared alpha beta gamma", "")

        assertEquals(listOf("d0", "d1", "d2"), result.map { it.decision.id })
        assertTrue(result.all { it.rankingScore.isNaN() })
    }

    @Test
    fun `candidate embedding chunks are bounded and draft is embedded once`() = runBlocking {
        val candidates = (0 until 30).map { index -> decision("d$index", createdAt = 100L - index, title = matchingTitle("c$index")) }
        val embedding = FakeEmbedding({ text, _ ->
            if (text.startsWith("shared alpha beta gamma\n")) vector(1f, 0f) else vector(1f, 0f)
        })
        var requestedLimit = 0
        val repository = ProjectDecisionIntelligenceRepository(
            ProjectDecisionCandidateSource { _, limit -> requestedLimit = limit; candidates },
            embedding,
            embeddingBatchLimit = 5,
        )

        repository.suggestActiveCandidates(1, "shared alpha beta gamma", "")

        assertEquals(ProjectDecisionIntelligencePolicy.MAX_DECISION_CANDIDATES, requestedLimit)
        assertEquals(listOf(5, 5, 5, 5, 5), embedding.batches.map { it.size })
        assertTrue(embedding.batches.all { it.size <= minOf(5, FusionEmbeddingLimits.MAX_TEXTS_PER_CALL) })
        assertEquals(1, embedding.batches.flatten().count { it == "shared alpha beta gamma\n" })
        assertEquals(ProjectDecisionIntelligencePolicy.MAX_DECISION_CANDIDATES + 1, embedding.batches.sumOf { it.size })
    }

    @Test
    fun `older exact duplicate outside 24 candidate bound is not auto suggested`() = runBlocking {
        val visibleWindow = (1..ProjectDecisionIntelligencePolicy.MAX_DECISION_CANDIDATES)
            .map { index -> decision("recent-$index", 10_000L - index, title = "Recent unrelated $index") }
        val oldDuplicate = decision("old-duplicate", 1, title = "Exact old duplicate")
        var requestedLimit = 0
        val repository = ProjectDecisionIntelligenceRepository(
            ProjectDecisionCandidateSource { _, limit ->
                requestedLimit = limit
                (visibleWindow + oldDuplicate).take(limit)
            },
            FakeEmbedding({ _, _ -> vector(1f, 0f) }),
        )

        val result = repository.suggestActiveCandidates(1, "Exact old duplicate", "")

        assertEquals(ProjectDecisionIntelligencePolicy.MAX_DECISION_CANDIDATES, requestedLimit)
        assertTrue(result.isEmpty())
        assertFalse(visibleWindow.any { it.id == oldDuplicate.id })
    }

    @Test
    fun `lineage exact lookup opens row outside recent four and binds project`() = runBlocking {
        val recentFour = (1..4).map { decision("recent-$it", 100L - it, title = "recent $it") }
        val oldLineageDecision = decision("lineage-8", 1, title = "older lineage")
        assertFalse(recentFour.any { it.id == oldLineageDecision.id })
        var requested: Pair<String, Long>? = null
        val resolver = ProjectDecisionLineageResolver { id, projectId ->
            requested = id to projectId
            oldLineageDecision.takeIf { it.id == id && it.projectId == projectId }
        }

        val resolved = resolver.resolve("lineage-8", 1)

        assertEquals("lineage-8" to 1L, requested)
        assertEquals(oldLineageDecision, resolved)
        assertNull(resolver.resolve("lineage-8", 2))
    }

    @Test
    fun `lineage read failure fails closed and never supplies stale item`() = runBlocking {
        val resolver = ProjectDecisionLineageResolver { _, _ -> throw IllegalStateException("database read failed") }

        assertNull(resolver.resolve("stale-lineage", 1))
    }

    @Test
    fun `lineage cancellation propagates`() = runBlocking {
        val resolver = ProjectDecisionLineageResolver { _, _ -> throw CancellationException("cancelled") }

        try {
            resolver.resolve("lineage", 1)
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(true)
        }
    }

    private fun repository(
        candidates: List<ProjectDecisionEntity>,
        embedding: FakeEmbedding,
        onRead: (Long, Int) -> Unit = { _, _ -> },
    ) = ProjectDecisionIntelligenceRepository(
        ProjectDecisionCandidateSource { projectId, limit ->
            onRead(projectId, limit)
            candidates
        },
        embedding,
    )

    private class FakeEmbedding(
        private val vectorFor: (String, Int) -> FloatArray,
        private val failOnCall: Int? = null,
    ) : SymmetricEmbeddingSource {
        val batches = mutableListOf<List<String>>()

        override suspend fun embedSources(texts: List<String>): EmbeddingResult {
            val call = batches.size
            batches += texts.toList()
            if (call == failOnCall) {
                return EmbeddingResult.Failure(EmbeddingFailureReason.INFERENCE_FAILURE, "fake failure")
            }
            return EmbeddingResult.Success(texts.map { vectorFor(it, call) }, dimension = 2)
        }
    }

    private fun decision(
        id: String,
        createdAt: Long,
        projectId: Long = 1,
        status: String = "ACTIVE",
        title: String,
    ) = ProjectDecisionEntity(id, projectId, title, "", status, createdAt = createdAt, updatedAt = createdAt)

    private fun matchingTitle(suffix: String) = "shared alpha beta gamma $suffix"

    private fun vector(x: Float, y: Float) = floatArrayOf(x, y)
}
