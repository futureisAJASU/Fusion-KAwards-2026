package com.projectnuke.fusion.translation

import com.projectnuke.fusion.ui.StandaloneTranslationController
import com.projectnuke.fusion.ui.StandaloneTranslationPhase
import com.projectnuke.fusion.ui.StandaloneTranslationPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dedicated on-device translation backend slot tests.
 *
 * The manifest + LocalTranslator seam is the ONLY approved integration
 * surface for a future dedicated model. These tests use a FAKE dedicated
 * backend advertising KO/EN/JA + AUTO source detection and prove that the
 * SAME StandaloneTranslationSurface/policy architecture consumes those
 * capabilities with zero language-specific production logic.
 *
 * Production keeps the LiteRT backend until a validated research artifact
 * exists:
 * DEDICATED MODEL BINARY INTEGRATION: DEFERRED — RESEARCH ARTIFACT NOT YET FROZEN
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeDedicatedTranslationBackendTest {

    private val fakeCapabilities = DedicatedTranslationBackendManifest(
        backendId = "dedicated-translation-v1",
        implementationVersion = "dedicated-0.1",
        modelArtifactId = "research-approved-dedicated-model-id",
        supportedTargetLanguages = setOf("ko", "en", "ja"),
        supportedSourceLanguages = setOf("ko", "en", "ja"),
        supportsAutomaticSourceDetection = true,
        supportsContext = true,
        supportsStyle = true,
    ).toCapabilities()

    private class FakeDedicatedBackend : LocalTranslator {
        override val capabilities = DedicatedTranslationBackendManifest(
            backendId = "dedicated-translation-v1",
            implementationVersion = "dedicated-0.1",
            modelArtifactId = "research-approved-dedicated-model-id",
            supportedTargetLanguages = setOf("ko", "en", "ja"),
            supportedSourceLanguages = setOf("ko", "en", "ja"),
            supportsAutomaticSourceDetection = true,
            supportsContext = true,
            supportsStyle = true,
        ).toCapabilities()

        var lastRequest: TranslationRequest? = null

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            lastRequest = request
            val artifact = TranslationPolicy.makeArtifact(
                request,
                "翻訳結果 version of ${request.originalText.take(12)}",
                capabilities,
            ) ?: return TranslationResult.Failure("invalid")
            return TranslationResult.Success(artifact)
        }
    }

    @Test
    fun `fake dedicated backend advertises KO EN JA with AUTO detection`() {
        assertTrue(fakeCapabilities.supportsTarget("ko"))
        assertTrue(fakeCapabilities.supportsTarget("en"))
        assertTrue(fakeCapabilities.supportsTarget("ja"))
        assertTrue(fakeCapabilities.supportsAutomaticSourceDetection)
        assertEquals("dedicated-translation-v1", fakeCapabilities.backendId)
    }

    @Test
    fun `same standalone controller consumes JA target end to end`() = runTest {
        val backend = FakeDedicatedBackend()
        val controller = StandaloneTranslationController(
            scope = this,
            coordinator = LocalTranslationCoordinator(backend),
        )
        // No language-specific UI logic: AUTO source, JA target.
        controller.open("こんにちは世界")
        controller.setTargetLanguage("ja")
        assertEquals("ja", controller.state.value.targetLanguage)
        assertTrue(StandaloneTranslationPolicy.canStart(controller.state.value, controller.capabilities))

        controller.translate()
        runCurrent()

        assertEquals(StandaloneTranslationPhase.SUCCESS, controller.state.value.phase)
        val display = StandaloneTranslationPolicy.displayedTranslatedText(
            controller.state.value,
            controller.capabilities,
        )
        assertTrue(display!!.startsWith("翻訳結果"))
        assertEquals(TranslationRoute.STANDALONE, backend.lastRequest!!.route)
        assertEquals(TranslationSourceLanguage.Unspecified, backend.lastRequest!!.sourceLanguage)
        assertEquals("ja", backend.lastRequest!!.targetLanguage)
        // Artifact provenance points at the dedicated backend.
        assertEquals("dedicated-translation-v1", controller.state.value.artifact!!.backendId)
        assertEquals("unspecified", controller.state.value.artifact!!.requestedSourceLanguage)
    }

    @Test
    fun `dedicated artifact is distinguished from the current backend artifact`() {
        val current = TranslationBackends.CURRENT
        val dedicated = fakeCapabilities
        assertFalse(current.backendId == dedicated.backendId)
        // Same-shaped artifacts carry different backend provenance.
        val request = TranslationRequest(
            originalText = "こんにちは",
            targetLanguage = "ja",
            sourceIdentity = "test",
        )
        val currentArtifact = TranslationPolicy.makeArtifact(
            request.copy(targetLanguage = "ko"),
            "안녕하세요",
            current,
        )!!
        val dedicatedArtifact = TranslationPolicy.makeArtifact(request, "こんにちは翻訳", dedicated)!!
        assertEquals(current.backendId, currentArtifact.backendId)
        assertEquals(dedicated.backendId, dedicatedArtifact.backendId)
        assertFalse(currentArtifact.backendId == dedicatedArtifact.backendId)
        // Policy validation is truthful per backend.
        assertTrue(TranslationPolicy.validateArtifact(dedicatedArtifact, dedicated))
        assertFalse(TranslationPolicy.validateArtifact(dedicatedArtifact, current))
    }

    @Test
    fun `production coordinator still selects the current LiteRT backend`() {
        // The manifest seam exists but no approved artifact is wired: the
        // product backend remains the shared-LiteRT adapter.
        assertEquals(TranslationBackends.CURRENT_BACKEND_ID, TranslationBackends.CURRENT.backendId)
        assertFalse(TranslationBackends.CURRENT.supportsTarget("ja"))
    }
}