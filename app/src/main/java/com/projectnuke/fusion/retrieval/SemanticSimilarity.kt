package com.projectnuke.fusion.retrieval

import kotlin.math.sqrt

/**
 * Pure similarity math for semantic candidate ranking.
 *
 * Cosine similarity over L2-normalized E5 vectors is the model's documented
 * similarity function. Scores are ranking signals only, never factual
 * confidence.
 */
object SemanticSimilarity {

    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dimension mismatch" }
        var sum = 0.0
        for (i in a.indices) sum += (a[i] * b[i]).toDouble()
        return sum.toFloat()
    }

    fun l2Norm(values: FloatArray): Double {
        var sum = 0.0
        for (v in values) sum += (v * v).toDouble()
        return sqrt(sum)
    }

    /** Returns a normalized copy; zero vectors map to zero vectors (no NaN). */
    fun normalize(values: FloatArray): FloatArray {
        val norm = l2Norm(values)
        if (norm <= 0.0 || !norm.isFinite()) return FloatArray(values.size)
        val out = FloatArray(values.size)
        for (i in values.indices) out[i] = (values[i] / norm).toFloat()
        return out
    }

    /**
     * Cosine similarity in [-1, 1]. Zero vectors score 0 (never NaN) so a
     * degenerate embedding can never outrank real content.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dimension mismatch" }
        val normA = l2Norm(a)
        val normB = l2Norm(b)
        if (normA <= 0.0 || normB <= 0.0 || !normA.isFinite() || !normB.isFinite()) return 0f
        return (dot(a, b) / (normA * normB)).toFloat().coerceIn(-1f, 1f)
    }

    /**
     * Mean pooling over [hidden] weighted by [attentionMask], matching the
     * sentence-transformers pooling contract (mean tokens, include prompt).
     */
    fun meanPool(hidden: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        require(hidden.size == attentionMask.size) { "sequence mismatch" }
        if (hidden.isEmpty()) return FloatArray(0)
        val dim = hidden[0].size
        val out = DoubleArray(dim)
        var count = 0L
        for (i in hidden.indices) {
            if (attentionMask[i] != 0L) {
                count++
                val row = hidden[i]
                for (d in 0 until dim) out[d] += row[d].toDouble()
            }
        }
        if (count <= 0L) return FloatArray(dim)
        return FloatArray(dim) { d -> (out[d] / count).toFloat() }
    }
}
