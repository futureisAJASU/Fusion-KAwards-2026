package com.projectnuke.fusion.retrieval

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext

/**
 * Documented E5 retrieval input contract (see model card FAQ):
 *
 * - asymmetric retrieval: `query: <claim>` vs `passage: <source segment>`
 * - symmetric / feature tasks: `query: ` prefix
 *
 * Formatting is applied at encode time only. [SemanticSegment.text] always
 * holds the raw bounded source text, so prefixes can never leak into
 * persisted user-visible evidence.
 */
object E5InputContract {
    const val QUERY_PREFIX = "query: "
    const val PASSAGE_PREFIX = "passage: "

    fun query(text: String): String = QUERY_PREFIX + text

    fun passage(text: String): String = PASSAGE_PREFIX + text

    /**
     * Prefix-inclusive bounded construction (C08). The public boundary
     * accepts raw payloads up to [FusionEmbeddingLimits.MAX_TEXT_CHARS]; the
     * encoder validates the prefixed input against the same bound, so prefix
     * capacity is reserved here. A maximum-allowed input therefore never
     * fails downstream as INVALID_INPUT solely because the prefix was added
     * afterwards. Truncation stays within the already-explicit bounded-input
     * contract; no new silent content policy is introduced.
     */
    fun queryBounded(text: String): String =
        QUERY_PREFIX + text.take(FusionEmbeddingLimits.MAX_TEXT_CHARS - QUERY_PREFIX.length)

    fun passageBounded(text: String): String =
        PASSAGE_PREFIX + text.take(FusionEmbeddingLimits.MAX_TEXT_CHARS - PASSAGE_PREFIX.length)
}

/** Bounded raw source segment. Never carries E5 prefixes. */
data class SemanticSegment(
    val id: String,
    val text: String,
)

data class RankedSemanticMatch(
    val segmentId: String,
    /** Cosine ranking signal in [-1, 1]. NOT factual confidence. */
    val score: Float,
    val rank: Int,
)

data class SemanticMatchResult(
    val matches: List<RankedSemanticMatch>,
    /** Non-null when the matcher was unavailable; caller must use lexical fallback. */
    val unavailableReason: EmbeddingFailureReason?,
)

/**
 * General Fusion semantic candidate matcher. Ranks candidate source segments
 * for a claim; it never emits an Evidence verdict.
 */
interface SemanticCandidateMatcher {
    suspend fun rank(
        claim: String,
        segments: List<SemanticSegment>,
        limit: Int,
    ): SemanticMatchResult
}

/** Fail-safe matcher: always reports unavailable so callers keep lexical behavior. */
object DisabledSemanticCandidateMatcher : SemanticCandidateMatcher {
    override suspend fun rank(
        claim: String,
        segments: List<SemanticSegment>,
        limit: Int,
    ): SemanticMatchResult = SemanticMatchResult(emptyList(), EmbeddingFailureReason.CLOSED)
}

/**
 * Embedding-backed matcher with deterministic ranking: score desc, then
 * segment id asc, then original index asc. Bounded, cancellation-safe, no
 * persistent cache; input refs are dropped when the call returns.
 */
class EmbeddingSemanticCandidateMatcher(
    private val engine: FusionEmbeddingEngine,
    private val maxInferenceBatch: Int = FusionEmbeddingLimits.MAX_INFERENCE_BATCH,
) : SemanticCandidateMatcher {

    override suspend fun rank(
        claim: String,
        segments: List<SemanticSegment>,
        limit: Int,
    ): SemanticMatchResult {
        val boundedLimit = limit.coerceIn(1, FusionEmbeddingLimits.MAX_SEGMENTS_PER_RANK)
        if (claim.length > FusionEmbeddingLimits.MAX_TEXT_CHARS) {
            return SemanticMatchResult(emptyList(), EmbeddingFailureReason.INVALID_INPUT)
        }
        val bounded = segments
            .asSequence()
            .filter { it.id.isNotBlank() && it.text.isNotBlank() }
            .filter { it.id.length <= 64 && it.text.length <= FusionEmbeddingLimits.MAX_TEXT_CHARS }
            .take(FusionEmbeddingLimits.MAX_SEGMENTS_PER_RANK)
            .toList()
        if (bounded.isEmpty()) return SemanticMatchResult(emptyList(), null)

        // Prefixes applied at encode time only; never written back to segments.
        // Prefix-inclusive bound: reservation happens centrally in
        // E5InputContract so the accepted raw bound and the encoder input agree.
        val queryText = E5InputContract.queryBounded(claim.take(FusionEmbeddingLimits.MAX_TEXT_CHARS))
        val passageTexts = bounded.map { E5InputContract.passageBounded(it.text) }

        val queryVector = try {
            embedOne(queryText)
        } catch (failed: EmbedFailed) {
            return SemanticMatchResult(emptyList(), failed.reason)
        }
        val passageVectors = try {
            embedBatched(passageTexts)
        } catch (failed: EmbedFailed) {
            return SemanticMatchResult(emptyList(), failed.reason)
        }

        // Ranking is pure compute; cancellation-checked per element.
        val scored = withContext(Dispatchers.Default) {
            bounded.mapIndexed { index, segment ->
                coroutineContext.ensureActive()
                val score = SemanticSimilarity.cosine(queryVector, passageVectors[index])
                Triple(segment, score, index)
            }.sortedWith(
                compareByDescending<Triple<SemanticSegment, Float, Int>> { it.second }
                    .thenBy { it.first.id }
                    .thenBy { it.third },
            )
        }
        val matches = scored.take(boundedLimit).mapIndexed { rank, (segment, score, _) ->
            RankedSemanticMatch(segment.id, score, rank + 1)
        }
        return SemanticMatchResult(matches, null)
    }

    private class EmbedFailed(val reason: EmbeddingFailureReason) :
        RuntimeException("embedding failed: $reason")

    private suspend fun embedOne(text: String): FloatArray {
        coroutineContext.ensureActive()
        return when (val result = engine.embed(listOf(text))) {
            is EmbeddingResult.Success ->
                result.vectors.firstOrNull() ?: throw EmbedFailed(EmbeddingFailureReason.INFERENCE_FAILURE)
            is EmbeddingResult.Failure -> {
                if (result.reason == EmbeddingFailureReason.CANCELLED) {
                    throw kotlinx.coroutines.CancellationException("embedding cancelled")
                }
                throw EmbedFailed(result.reason)
            }
        }
    }

    private suspend fun embedBatched(texts: List<String>): List<FloatArray> {
        val out = ArrayList<FloatArray>(texts.size)
        var cursor = 0
        val batch = maxInferenceBatch.coerceIn(1, FusionEmbeddingLimits.MAX_INFERENCE_BATCH)
        while (cursor < texts.size) {
            coroutineContext.ensureActive()
            val end = minOf(cursor + batch, texts.size)
            when (val result = engine.embed(texts.subList(cursor, end))) {
                is EmbeddingResult.Success -> out += result.vectors
                is EmbeddingResult.Failure -> {
                    if (result.reason == EmbeddingFailureReason.CANCELLED) {
                        throw kotlinx.coroutines.CancellationException("embedding cancelled")
                    }
                    throw EmbedFailed(result.reason)
                }
            }
            cursor = end
        }
        return out
    }
}
