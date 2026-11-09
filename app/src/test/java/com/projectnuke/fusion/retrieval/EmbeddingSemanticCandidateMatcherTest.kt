package com.projectnuke.fusion.retrieval

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake engine with deterministic caller-supplied vectors. No caching. */
class FakeEmbeddingEngine(
    private val vectors: Map<String, FloatArray>,
    private val dim: Int = 4,
    private val failure: EmbeddingResult.Failure? = null,
) : FusionEmbeddingEngine {
    var calls = 0
        private set
    var closedCalls = 0
        private set
    private var closedFlag = false

    override val diagnostics: EmbeddingDiagnostics
        get() = EmbeddingDiagnostics(calls, 0L, null, false, null)
    override val isClosed: Boolean get() = closedFlag

    override suspend fun embed(texts: List<String>): EmbeddingResult {
        calls++
        failure?.let { return it }
        if (texts.size > FusionEmbeddingLimits.MAX_TEXTS_PER_CALL) {
            return EmbeddingResult.Failure(EmbeddingFailureReason.INVALID_INPUT, "too many")
        }
        return EmbeddingResult.Success(texts.map { vectors[it] ?: FloatArray(dim) }, dim)
    }

    override fun close() {
        closedCalls++
        closedFlag = true
    }
}

class E5InputContractTest {

    @Test
    fun `query and passage prefixes match documented contract`() {
        assertEquals("query: ProductX supports 12GB", E5InputContract.query("ProductX supports 12GB"))
        assertEquals("passage: ProductX supports 12GB", E5InputContract.passage("ProductX supports 12GB"))
    }

    @Test
    fun `prefixes never leak into stored segments`() = runTest {
        val engine = FakeEmbeddingEngine(
            mapOf(
                E5InputContract.query("claim") to floatArrayOf(1f, 0f, 0f, 0f),
                E5InputContract.passage("raw segment") to floatArrayOf(1f, 0f, 0f, 0f),
            ),
        )
        val matcher = EmbeddingSemanticCandidateMatcher(engine)
        val segments = listOf(SemanticSegment("S1", "raw segment"))
        val result = matcher.rank("claim", segments, limit = 5)
        // Stored segment text is untouched; prefixes existed only at encode time.
        assertEquals("raw segment", segments.single().text)
        assertEquals(listOf("S1"), result.matches.map { it.segmentId })
        // Fake saw prefixed forms on the wire.
        assertTrue(engine.calls >= 2)
    }

    @Test
    fun `bounded construction reserves prefix capacity`() {
        val max = FusionEmbeddingLimits.MAX_TEXT_CHARS
        assertEquals(max, E5InputContract.queryBounded("x".repeat(max)).length)
        assertEquals(max, E5InputContract.passageBounded("y".repeat(max)).length)
        // One unit above maximum still encodes within the bound.
        assertEquals(max, E5InputContract.queryBounded("x".repeat(max + 1)).length)
        assertEquals(max, E5InputContract.passageBounded("y".repeat(max + 1)).length)
        // Multibyte/Korean content counts in the same characters.
        val korean = "가".repeat(max)
        assertEquals(max, E5InputContract.queryBounded(korean).length)
        assertEquals(max, E5InputContract.passageBounded(korean).length)
        // Short payloads are untouched apart from the prefix.
        assertEquals("query: abc", E5InputContract.queryBounded("abc"))
        assertEquals("passage: abc", E5InputContract.passageBounded("abc"))
    }
}

class EmbeddingSemanticCandidateMatcherTest {

    private fun engineFor(vararg pairs: Pair<String, FloatArray>) =
        FakeEmbeddingEngine(mapOf(*pairs))

    @Test
    fun `ranking is score-descending with stable id tie break`() = runTest {
        val engine = engineFor(
            E5InputContract.query("c") to floatArrayOf(1f, 0f, 0f, 0f),
            E5InputContract.passage("b2") to floatArrayOf(1f, 0f, 0f, 0f),
            E5InputContract.passage("b1") to floatArrayOf(1f, 0f, 0f, 0f),
            E5InputContract.passage("low") to floatArrayOf(0f, 1f, 0f, 0f),
        )
        val matcher = EmbeddingSemanticCandidateMatcher(engine)
        val segments = listOf(
            SemanticSegment("B2", "b2"),
            SemanticSegment("B1", "b1"),
            SemanticSegment("C", "low"),
        )
        val result = matcher.rank("c", segments, limit = 3)
        assertEquals(null, result.unavailableReason)
        // B1 and B2 tie at 1.0; id asc wins deterministically.
        assertEquals(listOf("B1", "B2", "C"), result.matches.map { it.segmentId })
        assertEquals(listOf(1, 2, 3), result.matches.map { it.rank })
    }

    @Test
    fun `deterministic given identical embeddings`() = runTest {
        fun fresh() = engineFor(
            E5InputContract.query("c") to floatArrayOf(0f, 1f, 0f, 0f),
            E5InputContract.passage("s1") to floatArrayOf(0f, 1f, 0f, 0f),
            E5InputContract.passage("s2") to floatArrayOf(1f, 0f, 0f, 0f),
        )
        val segments = listOf(SemanticSegment("S1", "s1"), SemanticSegment("S2", "s2"))
        val first = EmbeddingSemanticCandidateMatcher(fresh()).rank("c", segments, 2)
        val second = EmbeddingSemanticCandidateMatcher(fresh()).rank("c", segments, 2)
        assertEquals(first.matches.map { it.segmentId }, second.matches.map { it.segmentId })
    }

    @Test
    fun `engine failure surfaces bounded unavailable reason`() = runTest {
        val engine = FakeEmbeddingEngine(
            emptyMap(),
            failure = EmbeddingResult.Failure(EmbeddingFailureReason.ASSET_MISSING, "no asset"),
        )
        val result = EmbeddingSemanticCandidateMatcher(engine)
            .rank("claim", listOf(SemanticSegment("S1", "text")), 5)
        assertEquals(EmbeddingFailureReason.ASSET_MISSING, result.unavailableReason)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `oversize claim fails closed without inference`() = runTest {
        val engine = FakeEmbeddingEngine(emptyMap())
        val result = EmbeddingSemanticCandidateMatcher(engine).rank(
            "x".repeat(FusionEmbeddingLimits.MAX_TEXT_CHARS + 1),
            listOf(SemanticSegment("S1", "text")),
            5,
        )
        assertEquals(EmbeddingFailureReason.INVALID_INPUT, result.unavailableReason)
        assertEquals(0, engine.calls)
    }

    @Test
    fun `limit and segment counts are bounded`() = runTest {
        val pairs = (1..30).associate { i ->
            E5InputContract.passage("t$i") to floatArrayOf(1f, 0f, 0f, 0f)
        } + (E5InputContract.query("c") to floatArrayOf(1f, 0f, 0f, 0f))
        val engine = FakeEmbeddingEngine(pairs)
        val segments = (1..30).map { SemanticSegment("S$it", "t$it") }
        val result = EmbeddingSemanticCandidateMatcher(engine).rank("c", segments, limit = 100)
        assertTrue(result.matches.size <= FusionEmbeddingLimits.MAX_SEGMENTS_PER_RANK)
    }

    @Test
    fun `maximum accepted payload survives prefixing without invalid input`() = runTest {
        // C08: the engine validates the prefixed input; the matcher must
        // reserve prefix capacity so a maximum-allowed raw payload never
        // fails as INVALID_INPUT after prefixing.
        val max = FusionEmbeddingLimits.MAX_TEXT_CHARS
        val claim = "가".repeat(max)
        val passageRaw = "나".repeat(max)
        val engine = object : FusionEmbeddingEngine {
            override val diagnostics: EmbeddingDiagnostics
                get() = EmbeddingDiagnostics(0, 0L, null, false, null)
            override val isClosed: Boolean get() = false
            override suspend fun embed(texts: List<String>): EmbeddingResult {
                for (text in texts) {
                    if (text.length > FusionEmbeddingLimits.MAX_TEXT_CHARS) {
                        return EmbeddingResult.Failure(EmbeddingFailureReason.INVALID_INPUT, "text too long")
                    }
                }
                return EmbeddingResult.Success(texts.map { floatArrayOf(1f, 0f, 0f, 0f) }, 4)
            }
            override fun close() {}
        }
        val result = EmbeddingSemanticCandidateMatcher(engine).rank(
            claim,
            listOf(SemanticSegment("S1", passageRaw)),
            5,
        )
        assertEquals(null, result.unavailableReason)
        assertEquals(listOf("S1"), result.matches.map { it.segmentId })
    }
}
