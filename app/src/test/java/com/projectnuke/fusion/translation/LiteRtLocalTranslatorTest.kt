package com.projectnuke.fusion.translation

import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.FusionRuntimeLock
import com.projectnuke.fusion.llm.LlmEngine
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.RequestedEngineProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// C3 production translator tests: the real LiteRtLocalTranslator runs
// against a fake/injected LlmEngine seam. External provider call count stays
// zero by construction (the fake engine is the only generation path).
class LiteRtLocalTranslatorTest {

    private class FakeEngine(
        var reply: String = "translated text",
        var outcome: GenerationOutcome = GenerationOutcome.Success("translated text"),
    ) : LlmEngine {
        var calls = 0
        var lastMessages: List<ChatMessage> = emptyList()
        var lastOptions: ConversationOptions? = null
        var lastProfile: RequestedEngineProfile? = null

        override suspend fun generate(
            messages: List<ChatMessage>,
            profile: RequestedEngineProfile,
            options: ConversationOptions,
        ): GenerationOutcome {
            calls++
            lastMessages = messages
            lastOptions = options
            lastProfile = profile
            return outcome
        }

        override fun unload() {}
    }

    private fun profile() = RequestedEngineProfile(
        modelPath = "/models/test",
        accelerator = AcceleratorMode.CPU,
        mtpRequested = false,
        kvCacheCapacityTokens = 4096,
        enableVisionBackend = false,
        modelIdentity = "test-stable",
    )

    private fun request(
        original: String = "Original source sentence.",
        target: String = "ko",
        sourceIdentity: String = "src-1",
        route: TranslationRoute = TranslationRoute.STANDALONE,
    ) = TranslationRequest(
        originalText = original,
        targetLanguage = target,
        sourceIdentity = sourceIdentity,
        route = route,
    )

    @Test
    fun `real translator uses fresh bounded local generation`() = runTest {
        val engine = FakeEngine()
        val translator = LiteRtLocalTranslator(
            engineProvider = { engine },
            profileProvider = { profile() },
        )
        val result = translator.translate(request())
        assertTrue(result is TranslationResult.Success)
        val artifact = (result as TranslationResult.Success).artifact
        assertEquals("translated text", artifact.translatedText)
        // Fresh translation request: bounded system + user messages only.
        assertEquals(1, engine.calls)
        assertEquals(2, engine.lastMessages.size)
        assertEquals("system", engine.lastMessages[0].role)
        assertTrue(engine.lastMessages[0].content.contains("translation engine", ignoreCase = true))
        assertTrue(engine.lastMessages[1].content.contains("Original source sentence."))
        // Bounded deterministic settings, local path only.
        val options = engine.lastOptions!!
        assertEquals(0.1f, options.temperature)
        assertEquals(1, options.topK)
        assertEquals(9_137, options.seed)
        assertEquals(TranslationGenerationSettings.MAX_OUTPUT_TOKENS, options.maxOutputToken)
        // Reuses the already loaded engine: same instance, same profile.
        assertEquals(profile(), engine.lastProfile)
        // Truthful capability: KO/EN shipping support only.
        assertFalse(translator.capabilities.supportsTarget("ja"))
        assertEquals(TranslationBackends.CURRENT_BACKEND_ID, artifact.backendId)
        // The shared-LiteRT adapter never manufactures a resolved source.
        assertNull(artifact.resolvedSourceLanguage)
        assertEquals("unspecified", artifact.requestedSourceLanguage)
    }

    @Test
    fun `no history reuse across translations`() = runTest {
        val engine = FakeEngine()
        val translator = LiteRtLocalTranslator(
            engineProvider = { engine },
            profileProvider = { profile() },
        )
        translator.translate(request(original = "First source.", sourceIdentity = "src-1"))
        translator.translate(request(original = "Second source.", sourceIdentity = "src-2"))
        assertEquals(2, engine.calls)
        assertTrue(engine.lastMessages.none { it.content.contains("First source.") })
        assertTrue(engine.lastMessages.any { it.content.contains("Second source.") })
    }

    @Test
    fun `unavailable engine yields original unchanged`() = runTest {
        val translator = LiteRtLocalTranslator(
            engineProvider = { null },
            profileProvider = { profile() },
        )
        val result = translator.translate(request(original = "Original."))
        assertTrue(result is TranslationResult.Unavailable)
        assertEquals("Original.", TranslationPolicy.fallbackToOriginalWhenUnavailable(result, "Original."))
    }

    @Test
    fun `failed generation yields original unchanged without external fallback`() = runTest {
        val engine = FakeEngine(outcome = GenerationOutcome.Empty)
        val translator = LiteRtLocalTranslator(
            engineProvider = { engine },
            profileProvider = { profile() },
        )
        val result = translator.translate(request(original = "Original."))
        assertTrue(result is TranslationResult.Unavailable)
        assertEquals(1, engine.calls)
    }

    @Test
    fun `coordinator preserves canonical original for evidence`() = runTest {
        val engine = FakeEngine(reply = "번역된 문장.")
        val coordinator = LocalTranslationCoordinator(
            LiteRtLocalTranslator(engineProvider = { engine }, profileProvider = { profile() }),
        )
        val result = coordinator.translate(
            request(
                original = "Original evidence sentence.",
                sourceIdentity = "src-web",
                route = TranslationRoute.EVIDENCE_PRESENTATION,
            ).copy(sourceUrl = "https://example.com/s"),
        )
        assertTrue(result is TranslationResult.Success)
        val artifact = (result as TranslationResult.Success).artifact
        // Original remains canonical; evidence verification uses it.
        assertEquals("Original evidence sentence.", coordinator.canonicalEvidenceText(artifact))
        assertTrue(TranslationPolicy.preservesOriginal(artifact, "Original evidence sentence."))
        assertEquals("https://example.com/s", artifact.sourceUrl)
        assertEquals("번역 보기", coordinator.actionLabel(false))
        assertEquals("원문 보기", coordinator.actionLabel(true))
        // The translated artifact passes policy validation against the
        // coordinator's active capability truth.
        assertTrue(TranslationPolicy.validateArtifact(artifact, coordinator.capabilities))
        assertEquals(TranslationBackends.CURRENT_BACKEND_ID, coordinator.capabilities.backendId)
    }

    @Test
    fun `unsupported language fails safely to original`() = runTest {
        val engine = FakeEngine()
        val coordinator = LocalTranslationCoordinator(
            LiteRtLocalTranslator(engineProvider = { engine }, profileProvider = { profile() }),
        )
        val result = coordinator.translate(request(target = "xx", original = "Original."))
        assertTrue(result is TranslationResult.Failure)
        assertEquals(0, engine.calls)
    }

    @Test
    fun `context request is accepted but never reaches the current engine`() = runTest {
        val engine = FakeEngine()
        val translator = LiteRtLocalTranslator(
            engineProvider = { engine },
            profileProvider = { profile() },
        )
        val result = translator.translate(
            request(original = "Hello.").copy(context = "The user only cares about cooking."),
        )
        assertTrue(result is TranslationResult.Success)
        // The current adapter advertises supportsContext=false and therefore
        // does not fabricate context support; the prompt stays bounded.
        assertFalse(translator.capabilities.supportsContext)
        assertEquals(2, engine.lastMessages.size)
        assertEquals("[TRANSLATION_SOURCE_BEGIN]\nHello.\n[TRANSLATION_SOURCE_END]", engine.lastMessages[1].content)
    }

    @Test
    fun `benchmark conflict yields Unavailable without fallback or engine work`() = runTest {
        val engine = FakeEngine()
        val translator = LiteRtLocalTranslator(
            engineProvider = { engine },
            profileProvider = { profile() },
        )
        val releaseBenchmark = CompletableDeferred<Unit>()
        val benchmarkLatched = CompletableDeferred<Unit>()
        val holder = launch {
            FusionRuntimeLock.withExclusiveBenchmark(
                onPrepareExclusiveMode = {},
                block = {
                    benchmarkLatched.complete(Unit)
                    releaseBenchmark.await()
                },
            )
        }
        benchmarkLatched.await()
        try {
            val result = translator.translate(request(original = "Original."))
            assertTrue(result is TranslationResult.Unavailable)
            assertEquals(0, engine.calls)
            // Local-only: the result is the original, never a cloud route.
            assertEquals(
                "Original.",
                TranslationPolicy.fallbackToOriginalWhenUnavailable(result, "Original."),
            )
        } finally {
            releaseBenchmark.complete(Unit)
            holder.join()
        }
        // The lock must be fully released: a normal translation works again.
        engine.calls = 0
        val after = translator.translate(request(original = "After benchmark."))
        assertTrue(after is TranslationResult.Success)
        assertEquals(1, engine.calls)
    }
}