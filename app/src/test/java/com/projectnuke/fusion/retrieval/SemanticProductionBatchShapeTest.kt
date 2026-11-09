package com.projectnuke.fusion.retrieval

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production batch-shape validation ONLY.
 *
 * Confirms EmbeddingSemanticCandidateMatcher produces:
 * - one query embed(batch=1)
 * - first passage batch embed(batch=8)
 * - second passage batch embed(batch=2)
 *
 * Uses a fake engine for deterministic batching-unit-test purposes.
 * Does NOT report semantic Recall/MRR, claim Korean/cross-lingual quality,
 * or assert universal hard-negative verdicts (see EvidenceHardNegativeVerdictTest
 * and EvidenceAttributeCompatibilityTest for curated verdict safety).
 */
class SemanticProductionBatchShapeTest {

    private class TrackingEngine(
        private val delegate: FakeEmbeddingEngine,
        private val batch1Counter: AtomicInteger,
        private val batch8Counter: AtomicInteger,
        private val batch2Counter: AtomicInteger,
    ) : FusionEmbeddingEngine {
        override suspend fun embed(texts: List<String>): EmbeddingResult {
            when (texts.size) {
                1 -> {
                    if (texts.first().startsWith("query: ")) batch1Counter.incrementAndGet()
                }
                8 -> batch8Counter.incrementAndGet()
                2 -> batch2Counter.incrementAndGet()
            }
            return delegate.embed(texts)
        }

        override val diagnostics: EmbeddingDiagnostics
            get() = delegate.diagnostics
        override val isClosed: Boolean
            get() = delegate.isClosed
        override fun close() {
            delegate.close()
        }
    }

    @Test
    fun `production batch shape is 1 + 8 + 2`() = runTest {
        val batch1 = AtomicInteger(0)
        val batch8 = AtomicInteger(0)
        val batch2 = AtomicInteger(0)

        val engine = TrackingEngine(
            FakeEmbeddingEngine(
                mapOf(
                    E5InputContract.query("claim") to floatArrayOf(1f, 0f, 0f, 0f),
                    E5InputContract.passage("a") to floatArrayOf(1f, 0f, 0f, 0f),
                    E5InputContract.passage("b") to floatArrayOf(1f, 0f, 0f, 0f),
                    E5InputContract.passage("c") to floatArrayOf(1f, 0f, 0f, 0f),
                    E5InputContract.passage("d") to floatArrayOf(1f, 0f, 0f, 0f),
                    E5InputContract.passage("e") to floatArrayOf(1f, 0f, 0f, 0f),
                    E5InputContract.passage("f") to floatArrayOf(1f, 0f, 0f, 0f),
                    E5InputContract.passage("g") to floatArrayOf(1f, 0f, 0f, 0f),
                    E5InputContract.passage("h") to floatArrayOf(1f, 0f, 0f, 0f),
                    E5InputContract.passage("i") to floatArrayOf(0f, 1f, 0f, 0f),
                    E5InputContract.passage("j") to floatArrayOf(0f, 1f, 0f, 0f),
                )
            ),
            batch1,
            batch8,
            batch2,
        )

        val matcher = EmbeddingSemanticCandidateMatcher(engine)
        val segments = (1..10).map { SemanticSegment("S$it", "text$it") }
        matcher.rank("claim", segments, limit = 10)

        assertEquals("one query batch of size 1", 1, batch1.get())
        assertEquals("one passage batch of size 8", 1, batch8.get())
        assertEquals("one passage batch of size 2", 1, batch2.get())
    }
}
