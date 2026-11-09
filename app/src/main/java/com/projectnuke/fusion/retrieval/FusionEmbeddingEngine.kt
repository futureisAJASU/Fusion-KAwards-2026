package com.projectnuke.fusion.retrieval

/**
 * S1 runtime-agnostic embedding primitive.
 *
 * General Fusion retrieval building block (C4 local RAG may reuse it later).
 * It is NOT an Evidence verifier and MUST never decide
 * SUPPORTED_BY_EVIDENCE / CONFLICTING_EVIDENCE / INSUFFICIENT_EVIDENCE / NOT_CHECKED.
 *
 * A similarity score produced from these embeddings is candidate-ranking
 * assistance only. It is NOT accuracy, truth probability, or verification
 * confidence and must never be displayed as such.
 */
object FusionEmbeddingLimits {
    /** Hard per-text bound before tokenization. */
    const val MAX_TEXT_CHARS = 2_000

    /** Hard bound on texts per [FusionEmbeddingEngine.embed] call. */
    const val MAX_TEXTS_PER_CALL = 32

    /** Bounded inference batch; engines chunk larger calls. */
    const val MAX_INFERENCE_BATCH = 8

    /** Hard bound on segments per rank call. */
    const val MAX_SEGMENTS_PER_RANK = 24
}

enum class EmbeddingFailureReason {
    ASSET_MISSING,
    TOKENIZER_FAILURE,
    MODEL_LOAD_FAILURE,
    INFERENCE_FAILURE,
    UNSUPPORTED_ABI,
    OUT_OF_MEMORY,
    CANCELLED,
    CLOSED,
    INVALID_INPUT,
}

sealed interface EmbeddingResult {
    data class Success(
        /** L2-normalized vectors, one per input text, in input order. */
        val vectors: List<FloatArray>,
        val dimension: Int,
    ) : EmbeddingResult

    data class Failure(
        val reason: EmbeddingFailureReason,
        val detail: String,
    ) : EmbeddingResult
}

/** Bounded, text-free diagnostic snapshot. Never retains source text. */
data class EmbeddingDiagnostics(
    val lastBatchSize: Int,
    val lastLatencyMs: Long,
    val loadLatencyMs: Long?,
    val isLoaded: Boolean,
    val lastFailureReason: EmbeddingFailureReason?,
)

/**
 * Reusable embedding engine abstraction. Implementations own exactly one
 * native session, support idempotent [close], and are cancellation-safe.
 * No unbounded caching: implementations must not retain embeddings or
 * source text beyond the call unless documented elsewhere.
 */
interface FusionEmbeddingEngine : AutoCloseable {
    suspend fun embed(texts: List<String>): EmbeddingResult

    val diagnostics: EmbeddingDiagnostics

    val isClosed: Boolean

    override fun close()
}
