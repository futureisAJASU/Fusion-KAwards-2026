package com.projectnuke.fusion.retrieval

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSimilarityTest {

    @Test
    fun `cosine of identical vectors is one`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, SemanticSimilarity.cosine(a, a.copyOf()), 1e-5f)
    }

    @Test
    fun `cosine of orthogonal vectors is zero`() {
        assertEquals(
            0f,
            SemanticSimilarity.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
            1e-5f,
        )
    }

    @Test
    fun `cosine of opposite vectors is minus one`() {
        assertEquals(
            -1f,
            SemanticSimilarity.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)),
            1e-5f,
        )
    }

    @Test
    fun `zero vector never yields NaN and scores zero`() {
        val score = SemanticSimilarity.cosine(FloatArray(4), floatArrayOf(1f, 0f, 0f, 0f))
        assertEquals(0f, score, 0f)
        assertTrue(score.isFinite())
    }

    @Test
    fun `normalize of zero vector stays zero without NaN`() {
        val out = SemanticSimilarity.normalize(FloatArray(3))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), out, 0f)
    }

    @Test
    fun `dot matches manual computation`() {
        assertEquals(
            32f,
            SemanticSimilarity.dot(floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f)),
            1e-4f,
        )
    }

    @Test
    fun `mean pool respects attention mask`() {
        val hidden = arrayOf(
            floatArrayOf(1f, 1f),
            floatArrayOf(3f, 3f),
            floatArrayOf(100f, 100f), // masked out
        )
        val pooled = SemanticSimilarity.meanPool(hidden, longArrayOf(1L, 1L, 0L))
        assertArrayEquals(floatArrayOf(2f, 2f), pooled, 1e-5f)
    }

    @Test
    fun `mean pool with empty mask yields zeros`() {
        val pooled = SemanticSimilarity.meanPool(
            arrayOf(floatArrayOf(5f, 5f)),
            longArrayOf(0L),
        )
        assertArrayEquals(floatArrayOf(0f, 0f), pooled, 0f)
    }
}
