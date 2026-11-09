package com.projectnuke.fusion.retrieval

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cancellation and concurrency safety of the ranking layer (fake engines). */
class SemanticCancellationTest {

    private class GatedEngine(private val gate: CompletableDeferred<Unit>) : FusionEmbeddingEngine {
        override val diagnostics: EmbeddingDiagnostics
            get() = EmbeddingDiagnostics(0, 0L, null, false, null)
        override val isClosed: Boolean get() = false

        override suspend fun embed(texts: List<String>): EmbeddingResult {
            gate.await()
            return EmbeddingResult.Success(texts.map { FloatArray(4) { 1f } }, 4)
        }

        override fun close() = Unit
    }

    @Test
    fun `cancelled rank propagates cancellation`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val matcher = EmbeddingSemanticCandidateMatcher(GatedEngine(gate))
        val job = launch {
            matcher.rank("claim", listOf(SemanticSegment("S1", "text")), 3)
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        gate.complete(Unit)
    }

    @Test
    fun `matcher holds no per-call state across concurrent ranks`() = runTest {
        val vectors = mapOf(
            E5InputContract.query("c") to floatArrayOf(1f, 0f, 0f, 0f),
            E5InputContract.passage("s1") to floatArrayOf(1f, 0f, 0f, 0f),
            E5InputContract.passage("s2") to floatArrayOf(0f, 1f, 0f, 0f),
        )
        val matcher = EmbeddingSemanticCandidateMatcher(FakeEmbeddingEngine(vectors))
        val segments = listOf(SemanticSegment("S1", "s1"), SemanticSegment("S2", "s2"))
        val results = (1..16).map {
            async { matcher.rank("c", segments, 2) }
        }.awaitAll()
        for (result in results) {
            assertEquals(listOf("S1", "S2"), result.matches.map { it.segmentId })
        }
    }

    @Test
    fun `failed rank leaves no residue for the next rank`() = runTest {
        var failNext = true
        val engine = object : FusionEmbeddingEngine {
            override val diagnostics: EmbeddingDiagnostics
                get() = EmbeddingDiagnostics(0, 0L, null, false, null)
            override val isClosed: Boolean get() = false

            override suspend fun embed(texts: List<String>): EmbeddingResult =
                if (failNext) {
                    EmbeddingResult.Failure(EmbeddingFailureReason.INFERENCE_FAILURE, "boom")
                } else {
                    EmbeddingResult.Success(texts.map { floatArrayOf(1f, 0f) }, 2)
                }

            override fun close() = Unit
        }
        val matcher = EmbeddingSemanticCandidateMatcher(engine)
        val segments = listOf(SemanticSegment("S1", "x"))
        assertEquals(
            EmbeddingFailureReason.INFERENCE_FAILURE,
            matcher.rank("c", segments, 1).unavailableReason,
        )
        failNext = false
        val retry = matcher.rank("c", segments, 1)
        assertEquals(null, retry.unavailableReason)
        assertEquals(listOf("S1"), retry.matches.map { it.segmentId })
    }
}
