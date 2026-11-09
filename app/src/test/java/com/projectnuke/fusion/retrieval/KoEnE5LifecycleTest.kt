package com.projectnuke.fusion.retrieval

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoEnE5LifecycleTest {

    @Test
    fun `missing assets fail closed without native load`() = runTest {
        val missingDir = File(System.getProperty("java.io.tmpdir"), "koen-e5-missing-${System.nanoTime()}")
        val engine = KoEnE5OnnxEmbeddingEngine(FileSystemKoEnE5AssetProvider(missingDir))
        val result = engine.embed(listOf(E5InputContract.query("hello")))
        assertTrue(result is EmbeddingResult.Failure)
        val failure = result as EmbeddingResult.Failure
        assertTrue(
            failure.reason == EmbeddingFailureReason.ASSET_MISSING ||
                failure.reason == EmbeddingFailureReason.TOKENIZER_FAILURE,
        )
        assertFalse(engine.diagnostics.isLoaded)
        engine.close()
    }

    @Test
    fun `close is idempotent and embed after close fails closed`() = runTest {
        val engine = KoEnE5OnnxEmbeddingEngine(
            FileSystemKoEnE5AssetProvider(File(System.getProperty("java.io.tmpdir"))),
        )
        engine.close()
        engine.close()
        assertTrue(engine.isClosed)
        val result = engine.embed(listOf("hello"))
        assertEquals(
            EmbeddingFailureReason.CLOSED,
            (result as EmbeddingResult.Failure).reason,
        )
    }

    @Test
    fun `oversize batch is rejected before inference`() = runTest {
        val engine = KoEnE5OnnxEmbeddingEngine(
            FileSystemKoEnE5AssetProvider(File(System.getProperty("java.io.tmpdir"))),
        )
        val tooMany = (1..(FusionEmbeddingLimits.MAX_TEXTS_PER_CALL + 1)).map { "t$it" }
        val result = engine.embed(tooMany)
        assertEquals(
            EmbeddingFailureReason.INVALID_INPUT,
            (result as EmbeddingResult.Failure).reason,
        )
        engine.close()
    }

    @Test
    fun `asset provider never downloads and reports missing names`() {
        val provider = FileSystemKoEnE5AssetProvider(
            File(System.getProperty("java.io.tmpdir"), "koen-e5-never-created-xyz"),
        )
        val model = provider.modelAsset() as KoEnE5AssetState.Missing
        val tokenizer = provider.tokenizerAsset() as KoEnE5AssetState.Missing
        assertTrue(model.reason.contains("MODEL_ASSET_MISSING"))
        assertTrue(tokenizer.reason.contains("TOKENIZER_ASSET_MISSING"))
    }

    @Test
    fun `matcher with closed engine reports unavailable for fallback`() = runTest {
        val engine = FakeEmbeddingEngine(mapOf("a" to floatArrayOf(1f)))
        engine.close()
        val failing = FakeEmbeddingEngine(
            emptyMap(),
            failure = EmbeddingResult.Failure(EmbeddingFailureReason.CLOSED, "closed"),
        )
        val result = EmbeddingSemanticCandidateMatcher(failing).rank(
            "claim",
            listOf(SemanticSegment("S1", "text")),
            3,
        )
        assertEquals(EmbeddingFailureReason.CLOSED, result.unavailableReason)
    }
}
