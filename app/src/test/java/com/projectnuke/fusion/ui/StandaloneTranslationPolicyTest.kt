package com.projectnuke.fusion.ui

import com.projectnuke.fusion.translation.DedicatedTranslationBackendManifest
import com.projectnuke.fusion.translation.LocalTranslationCoordinator
import com.projectnuke.fusion.translation.LocalTranslator
import com.projectnuke.fusion.translation.TranslationArtifact
import com.projectnuke.fusion.translation.TranslationBackendCapabilities
import com.projectnuke.fusion.translation.TranslationBackends
import com.projectnuke.fusion.translation.TranslationBounds
import com.projectnuke.fusion.translation.TranslationPolicy
import com.projectnuke.fusion.translation.TranslationRequest
import com.projectnuke.fusion.translation.TranslationResult
import com.projectnuke.fusion.translation.TranslationRoute
import com.projectnuke.fusion.translation.TranslationSourceLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StandaloneTranslationPolicyTest {

    private val currentCapabilities = TranslationBackends.CURRENT

    /** Future dedicated backend capability truth used by tests only. */
    private val futureCapabilities: TranslationBackendCapabilities =
        DedicatedTranslationBackendManifest(
            backendId = "dedicated-translation-v1",
            implementationVersion = "dedicated-0.1",
            modelArtifactId = "research-approved-dedicated-model-id",
            supportedTargetLanguages = setOf("ko", "en", "ja"),
            supportedSourceLanguages = setOf("ko", "en", "ja"),
            supportsAutomaticSourceDetection = true,
            supportsContext = true,
            supportsStyle = true,
        ).toCapabilities()

    // ---- capability-derived target selection ----

    @Test
    fun `current backend exposes only ko and en as selectable targets`() {
        val codes = StandaloneTranslationPolicy.targetLanguages(currentCapabilities).map { it.code }
        assertEquals(listOf("ko", "en"), codes)
        assertEquals(
            listOf("한국어", "English"),
            StandaloneTranslationPolicy.targetLanguages(currentCapabilities).map { it.label },
        )
        assertTrue(StandaloneTranslationPolicy.isSupportedTarget("ko", currentCapabilities))
        assertTrue(StandaloneTranslationPolicy.isSupportedTarget("en", currentCapabilities))
        assertFalse(StandaloneTranslationPolicy.isSupportedTarget("ja", currentCapabilities))
        assertFalse(StandaloneTranslationPolicy.isSupportedTarget("", currentCapabilities))
    }

    @Test
    fun `future backend adds Japanese without language-specific UI logic`() {
        val codes = StandaloneTranslationPolicy.targetLanguages(futureCapabilities).map { it.code }
        assertEquals(listOf("ko", "en", "ja"), codes)
        assertEquals(
            listOf("한국어", "English", "日本語"),
            StandaloneTranslationPolicy.targetLanguages(futureCapabilities).map { it.label },
        )
        assertTrue(StandaloneTranslationPolicy.canStartWith("こんにちは", "ja", futureCapabilities))
        assertFalse(StandaloneTranslationPolicy.canStartWith("こんにちは", "ja", currentCapabilities))
    }

    @Test
    fun `default target hint is a hint only and never a resolved source`() {
        // Hint: Korean-looking content defaults to EN target.
        assertEquals("en", StandaloneTranslationPolicy.defaultTargetLanguageFor("안녕하세요"))
        assertEquals("ko", StandaloneTranslationPolicy.defaultTargetLanguageFor("Hello world"))
        assertEquals("ko", StandaloneTranslationPolicy.defaultTargetLanguageFor(""))
        // AUTO source stays AUTO in any request built on it.
        val request = TranslationRequest(
            originalText = "안녕하세요",
            targetLanguage = "en",
            sourceIdentity = "id",
        )
        assertEquals(TranslationSourceLanguage.Unspecified, request.sourceLanguage)
        assertEquals(
            "unspecified",
            TranslationPolicy.makeArtifact(request, "Hello", currentCapabilities)!!.requestedSourceLanguage,
        )
    }

    // ---- provenance identity: stable, epoch-free ----

    @Test
    fun `same semantic request yields the same provenance identity`() {
        val a = StandaloneTranslationPolicy.provenanceIdentityFor(
            "동일한 문장", "en", TranslationSourceLanguage.Unspecified,
        )
        val b = StandaloneTranslationPolicy.provenanceIdentityFor(
            "동일한 문장", "en", TranslationSourceLanguage.Unspecified,
        )
        assertEquals(a, b)
        // Two controller passes with different UI epochs must not change it.
        val c = StandaloneTranslationPolicy.provenanceIdentityFor(
            "동일한 문장", "en", TranslationSourceLanguage.Unspecified, TranslationRoute.STANDALONE,
        )
        assertEquals(a, c)
    }

    @Test
    fun `changed source or target changes identity`() {
        val base = StandaloneTranslationPolicy.provenanceIdentityFor("문장", "en", TranslationSourceLanguage.Unspecified)
        assertNotEquals(base, StandaloneTranslationPolicy.provenanceIdentityFor("다른 문장", "en", TranslationSourceLanguage.Unspecified))
        assertNotEquals(base, StandaloneTranslationPolicy.provenanceIdentityFor("문장", "ko", TranslationSourceLanguage.Unspecified))
        assertNotEquals(base, StandaloneTranslationPolicy.provenanceIdentityFor("문장", "en", TranslationSourceLanguage.Explicit("ko")))
        assertNotEquals(
            base,
            StandaloneTranslationPolicy.provenanceIdentityFor(
                "문장", "en", TranslationSourceLanguage.Unspecified, TranslationRoute.EVIDENCE_PRESENTATION,
            ),
        )
    }

    // ---- state transitions with capability truth ----

    @Test
    fun `source change invalidates stale result and any in-flight request`() {
        val success = completedSuccess("Hello world", "안녕 세상", target = "ko")
        assertEquals(StandaloneTranslationPhase.SUCCESS, success.phase)
        assertEquals("안녕 세상", StandaloneTranslationPolicy.displayedTranslatedText(success, currentCapabilities))

        val stale = StandaloneTranslationPolicy.onSourceChanged(success, "Hello again")
        assertEquals(StandaloneTranslationPhase.IDLE, stale.phase)
        assertNull(stale.artifact)
        assertNull(StandaloneTranslationPolicy.displayedTranslatedText(stale, currentCapabilities))
        assertTrue(stale.requestId > success.requestId)
        assertFalse(StandaloneTranslationPolicy.canCopy(stale, currentCapabilities))
        assertFalse(StandaloneTranslationPolicy.canSwap(stale, currentCapabilities))
    }

    @Test
    fun `duplicate translate while translating is blocked`() {
        val translating = StandaloneTranslationPolicy.beginTranslation(
            StandaloneTranslationState(sourceText = "Hello world", targetLanguage = "ko"),
            currentCapabilities,
        )
        assertEquals(StandaloneTranslationPhase.TRANSLATING, translating.phase)
        assertFalse(StandaloneTranslationPolicy.canStart(translating, currentCapabilities))
        assertEquals(
            translating,
            StandaloneTranslationPolicy.beginTranslation(translating, currentCapabilities),
        )
    }

    @Test
    fun `unsupported target cannot start under current backend`() {
        val state = StandaloneTranslationState(sourceText = "こんにちは", targetLanguage = "ja")
        assertFalse(StandaloneTranslationPolicy.canStart(state, currentCapabilities))
        assertTrue(StandaloneTranslationPolicy.canStart(state, futureCapabilities))
    }

    @Test
    fun `unavailable leaves original source intact`() {
        val started = StandaloneTranslationPolicy.beginTranslation(
            StandaloneTranslationState(sourceText = "Hello world", targetLanguage = "ko"),
            currentCapabilities,
        )
        val unavailable = StandaloneTranslationPolicy.onTranslationResult(
            started,
            TranslationResult.Unavailable,
            expectedRequestId = started.requestId,
            capabilities = currentCapabilities,
        )
        assertEquals(StandaloneTranslationPhase.UNAVAILABLE, unavailable.phase)
        assertEquals("Hello world", unavailable.sourceText)
        assertNull(unavailable.artifact)
        assertEquals(
            "현재 로컬 모델을 사용할 수 없어 번역하지 못했어요.",
            StandaloneTranslationPolicy.statusMessage(unavailable),
        )
    }

    @Test
    fun `stale in-flight completion is rejected by UI epoch ownership`() {
        val started = StandaloneTranslationPolicy.beginTranslation(
            StandaloneTranslationState(sourceText = "Hello world", targetLanguage = "ko"),
            currentCapabilities,
        )
        val edited = StandaloneTranslationPolicy.onSourceChanged(started, "New text")
        val late = StandaloneTranslationPolicy.onTranslationResult(
            edited,
            TranslationResult.Success(artifact("Hello world", "안녕 세상", target = "ko")),
            expectedRequestId = started.requestId,
            capabilities = currentCapabilities,
        )
        assertEquals(StandaloneTranslationPhase.IDLE, late.phase)
        assertNull(late.artifact)
        assertEquals("New text", late.sourceText)
    }

    @Test
    fun `result whose original does not match current source is rejected`() {
        val started = StandaloneTranslationPolicy.beginTranslation(
            StandaloneTranslationState(sourceText = "Hello world", targetLanguage = "ko"),
            currentCapabilities,
        )
        val mismatched = StandaloneTranslationPolicy.onTranslationResult(
            started,
            TranslationResult.Success(artifact("Some other text", "다른 내용", target = "ko")),
            expectedRequestId = started.requestId,
            capabilities = currentCapabilities,
        )
        assertEquals(StandaloneTranslationPhase.FAILURE, mismatched.phase)
        assertNull(StandaloneTranslationPolicy.displayedTranslatedText(mismatched, currentCapabilities))
    }

    @Test
    fun `artifact from a different backend is rejected by capability truth`() {
        val started = StandaloneTranslationPolicy.beginTranslation(
            StandaloneTranslationState(sourceText = "Hello world", targetLanguage = "ko"),
            currentCapabilities,
        )
        val foreign = artifact("Hello world", "안녕 세상", target = "ko")
            .copy(backendId = "some-other-backend")
        val result = StandaloneTranslationPolicy.onTranslationResult(
            started,
            TranslationResult.Success(foreign),
            expectedRequestId = started.requestId,
            capabilities = currentCapabilities,
        )
        assertEquals(StandaloneTranslationPhase.FAILURE, result.phase)
    }

    @Test
    fun `translations carrying host metadata are rejected`() {
        val started = StandaloneTranslationPolicy.beginTranslation(
            StandaloneTranslationState(sourceText = "Hello world", targetLanguage = "ko"),
            currentCapabilities,
        )
        // Built directly (bypassing makeArtifact sanitization) to prove the
        // surface's own metadata guard rejects poisoned outputs.
        val poisoned = TranslationArtifact(
            originalText = "Hello world",
            originalHashHex = TranslationPolicy.sha256Hex("Hello world"),
            targetLanguage = "ko",
            translatedText = "안녕 [FUSION_SELECTED_MODEL_PATH] 세상",
            implementationVersion = "local-v1",
            backendId = "litert-shared-local",
            requestedSourceLanguage = "unspecified",
            sourceIdentity = "test",
        )
        val result = StandaloneTranslationPolicy.onTranslationResult(
            started,
            TranslationResult.Success(poisoned),
            expectedRequestId = started.requestId,
            capabilities = currentCapabilities,
        )
        assertEquals(StandaloneTranslationPhase.FAILURE, result.phase)
        assertNull(StandaloneTranslationPolicy.displayedTranslatedText(result, currentCapabilities))
    }

    @Test
    fun `oversized source cannot start and shows actionable reason`() {
        val huge = "x".repeat(TranslationBounds.MAX_INPUT_CHARS + 1)
        val state = StandaloneTranslationState(sourceText = huge, targetLanguage = "ko")
        assertFalse(StandaloneTranslationPolicy.canStart(state, currentCapabilities))
        assertTrue(
            StandaloneTranslationPolicy.translateDisabledReason(state, currentCapabilities)!!.contains(
                TranslationBounds.MAX_INPUT_CHARS.toString(),
            ),
        )
    }

    // ---- presentation-only mutation guarantees ----

    @Test
    fun `composer replacement is the only mutation path and requires valid result`() {
        val success = completedSuccess("Hello world", "안녕 세상", target = "ko")
        assertEquals(
            "안녕 세상",
            StandaloneTranslationPolicy.composerReplacementText(success, currentCapabilities),
        )

        val changed = StandaloneTranslationPolicy.onSourceChanged(success, "Changed")
        assertNull(StandaloneTranslationPolicy.composerReplacementText(changed, currentCapabilities))
    }

    @Test
    fun `swap uses the known explicit source language when available`() {
        // Explicit KO source, translated to EN: swapping goes back to KO.
        val request = TranslationRequest(
            originalText = "안녕 세상",
            targetLanguage = "en",
            sourceLanguage = TranslationSourceLanguage.Explicit("ko"),
            sourceIdentity = "test-identity",
        )
        val artifact = TranslationPolicy.makeArtifact(request, "Hello world", currentCapabilities)!!
        val success = StandaloneTranslationPolicy.onTranslationResult(
            StandaloneTranslationState(
                sourceText = "안녕 세상",
                targetLanguage = "en",
                sourceLanguage = request.sourceLanguage,
            ),
            TranslationResult.Success(artifact),
            expectedRequestId = 0L,
            capabilities = currentCapabilities,
        )
        val swapped = StandaloneTranslationPolicy.swap(success, currentCapabilities)
        assertEquals("Hello world", swapped.sourceText)
        assertEquals("ko", swapped.targetLanguage)
        assertEquals(StandaloneTranslationPhase.IDLE, swapped.phase)
        assertNull(swapped.artifact)
    }

    @Test
    fun `swap with UNSPECIFIED source uses a safe supported default never fabricated detection`() {
        val success = completedSuccess("Hello world", "안녕 세상", target = "ko")
        val swapped = StandaloneTranslationPolicy.swap(success, currentCapabilities)
        assertEquals("안녕 세상", swapped.sourceText)
        // Previous source was UNSPECIFIED and unresolved; the hint picks EN
        // and the current backend supports it. No resolved source is recorded.
        assertEquals("en", swapped.targetLanguage)
        assertEquals(TranslationSourceLanguage.Unspecified, swapped.sourceLanguage)
        assertEquals(StandaloneTranslationPhase.IDLE, swapped.phase)
        assertNull(swapped.artifact)
    }

    @Test
    fun `swap with UNSPECIFIED falls back to first supported target when hint unsupported`() {
        // Japanese-only-looking text hints "en" which IS supported here; use
        // a capability set without the hinted target to force the fallback.
        val noEnglish = TranslationBackendCapabilities(
            backendId = "ko-only",
            implementationVersion = "v",
            supportedTargetLanguages = setOf("ko"),
            supportedSourceLanguages = setOf("ko"),
        )
        val target = StandaloneTranslationPolicy.swapTargetLanguage(
            resolvedSourceLanguage = null,
            requestedSourceLanguage = "unspecified",
            newSourceText = "Hello world",
            capabilities = noEnglish,
        )
        assertEquals("ko", target)
    }

    @Test
    fun `swap prefers a genuinely resolved source language over the hint`() {
        // A dedicated backend that truthfully resolved JA produces an
        // artifact whose resolved language drives swap back into JA.
        val dedicated = com.projectnuke.fusion.translation.DedicatedTranslationBackendManifest(
            backendId = "dedicated",
            implementationVersion = "0.1",
            modelArtifactId = "m",
            supportedTargetLanguages = setOf("ko", "en", "ja"),
            supportedSourceLanguages = setOf("ko", "en", "ja"),
            supportsAutomaticSourceDetection = true,
        ).toCapabilities()
        val request = TranslationRequest(
            originalText = "こんにちは",
            targetLanguage = "ko",
            sourceIdentity = "test-identity",
        )
        val started = StandaloneTranslationPolicy.beginTranslation(
            StandaloneTranslationState(sourceText = "こんにちは", targetLanguage = "ko"),
            dedicated,
        )
        val success = StandaloneTranslationPolicy.onTranslationResult(
            started,
            TranslationResult.Success(
                TranslationPolicy.makeArtifact(request, "안녕하세요", dedicated, resolvedSourceLanguage = "ja")!!,
            ),
            expectedRequestId = started.requestId,
            capabilities = dedicated,
        )
        val swapped = StandaloneTranslationPolicy.swap(success, dedicated)
        assertEquals("안녕하세요", swapped.sourceText)
        assertEquals("ja", swapped.targetLanguage)
    }

    @Test
    fun `resolved language matches explicit source for swap`() {
        val dedicated = com.projectnuke.fusion.translation.DedicatedTranslationBackendManifest(
            backendId = "dedicated",
            implementationVersion = "0.1",
            modelArtifactId = "m",
            supportedTargetLanguages = setOf("ko", "en", "ja"),
            supportedSourceLanguages = setOf("ko", "en", "ja"),
            supportsAutomaticSourceDetection = true,
        ).toCapabilities()
        // Caller asserted EN and the backend resolved EN; under the strict
        // contract the resolved language must match the explicit request.
        val request = TranslationRequest(
            originalText = "Hello world",
            targetLanguage = "ja",
            sourceLanguage = TranslationSourceLanguage.Explicit("en"),
            sourceIdentity = "test-identity",
        )
        val started = StandaloneTranslationPolicy.beginTranslation(
            StandaloneTranslationState(
                sourceText = "Hello world",
                targetLanguage = "ja",
                sourceLanguage = TranslationSourceLanguage.Explicit("en"),
            ),
            dedicated,
        )
        val artifact = TranslationPolicy.makeArtifact(
            request,
            "こんにちは世界",
            dedicated,
            resolvedSourceLanguage = "en",
        )!!
        val success = StandaloneTranslationPolicy.onTranslationResult(
            started,
            TranslationResult.Success(artifact),
            expectedRequestId = started.requestId,
            capabilities = dedicated,
        )
        val swapped = StandaloneTranslationPolicy.swap(success, dedicated)
        assertEquals("en", swapped.targetLanguage)
    }

    @Test
    fun `clear resets source and result but keeps target`() {
        val success = completedSuccess("Hello world", "안녕 세상", target = "ko")
        val cleared = StandaloneTranslationPolicy.clear(success)
        assertEquals("", cleared.sourceText)
        assertEquals("ko", cleared.targetLanguage)
        assertNull(cleared.artifact)
        assertEquals(StandaloneTranslationPhase.IDLE, cleared.phase)
    }

    @Test
    fun `target change invalidates prior result but keeps source`() {
        val success = completedSuccess("Hello world", "안녕 세상", target = "ko")
        val retargeted = StandaloneTranslationPolicy.onTargetChanged(success, "en")
        assertEquals("en", retargeted.targetLanguage)
        assertEquals("Hello world", retargeted.sourceText)
        assertNull(retargeted.artifact)
        assertEquals(StandaloneTranslationPhase.IDLE, retargeted.phase)
    }

    // ---- controller-level: single-flight + only local route ----

    @Test
    fun `controller blocks duplicate translate while one is running`() = runTest {
        val gate = CompletableDeferred<TranslationResult>()
        val translator = RecordingTranslator { gate.await() }
        val controller = StandaloneTranslationController(
            scope = this,
            coordinator = LocalTranslationCoordinator(translator),
        )
        controller.open("Hello world")
        controller.translate()
        runCurrent()
        assertEquals(1, translator.calls)

        controller.translate()
        runCurrent()
        assertEquals(1, translator.calls)

        gate.complete(TranslationResult.Success(artifact("Hello world", "안녕 세상", target = "ko")))
        runCurrent()
        assertEquals(StandaloneTranslationPhase.SUCCESS, controller.state.value.phase)
        assertEquals(
            "안녕 세상",
            StandaloneTranslationPolicy.displayedTranslatedText(controller.state.value, currentCapabilities),
        )
        assertEquals(1, translator.calls)
    }

    @Test
    fun `controller unavailable keeps source and shows calm message`() = runTest {
        val controller = StandaloneTranslationController(
            scope = this,
            coordinator = LocalTranslationCoordinator(RecordingTranslator { TranslationResult.Unavailable }),
        )
        controller.open("Hello world")
        controller.translate()
        runCurrent()
        assertEquals(StandaloneTranslationPhase.UNAVAILABLE, controller.state.value.phase)
        assertEquals("Hello world", controller.state.value.sourceText)
        assertNull(
            StandaloneTranslationPolicy.displayedTranslatedText(controller.state.value, currentCapabilities),
        )
    }

    @Test
    fun `controller never mutates anything besides its own state`() = runTest {
        val translator = RecordingTranslator { TranslationResult.Success(artifact("Hello world", "안녕 세상", target = "ko")) }
        val controller = StandaloneTranslationController(
            scope = this,
            coordinator = LocalTranslationCoordinator(translator),
        )
        controller.open("Hello world")
        controller.translate()
        runCurrent()

        var draftSeen: String? = "unchanged"
        val replacement = controller.composerReplacementText()
        if (replacement != null) draftSeen = replacement
        assertEquals("안녕 세상", draftSeen)
        assertEquals(TranslationRoute.STANDALONE, translator.lastRequest!!.route)
        assertEquals(TranslationSourceLanguage.Unspecified, translator.lastRequest!!.sourceLanguage)
        // Stable provenance identity: content-derived, no UI epoch inside.
        assertTrue(translator.lastRequest!!.sourceIdentity.startsWith("translation:standalone:"))
        assertFalse(translator.lastRequest!!.sourceIdentity.contains("requestId"))
    }

    @Test
    fun `no external translation route exists`() {
        assertFalse(TranslationPolicy.allowsExternalTranslation())
        // The surface controller only ever speaks to the existing local
        // coordinator; its only collaborators are LocalTranslationCoordinator
        // (LocalTranslator) and the policy. There is no external client type
        // in this surface's dependency graph.
        val coordinatorOnly = StandaloneTranslationController::class.java.constructors
            .flatMap { it.parameterTypes.toList() }
            .map { it.name }
        assertTrue(
            coordinatorOnly.any { it.contains("LocalTranslationCoordinator") },
        )
    }

    @Test
    fun `open seeds a copy and never the live draft`() = runTest {
        val controller = StandaloneTranslationController(
            scope = this,
            coordinator = LocalTranslationCoordinator(RecordingTranslator { TranslationResult.Unavailable }),
        )
        controller.open("draft copy")
        assertEquals("draft copy", controller.state.value.sourceText)
        assertEquals("ko", controller.state.value.targetLanguage)
        assertEquals(TranslationSourceLanguage.Unspecified, controller.state.value.sourceLanguage)
    }

    private fun completedSuccess(original: String, translated: String, target: String): StandaloneTranslationState {
        val started = StandaloneTranslationPolicy.beginTranslation(
            StandaloneTranslationState(sourceText = original, targetLanguage = target),
            currentCapabilities,
        )
        return StandaloneTranslationPolicy.onTranslationResult(
            started,
            TranslationResult.Success(artifact(original, translated, target)),
            expectedRequestId = started.requestId,
            capabilities = currentCapabilities,
        )
    }

    private fun artifact(original: String, translated: String, target: String): TranslationArtifact =
        TranslationPolicy.makeArtifact(
            TranslationRequest(
                originalText = original,
                targetLanguage = target,
                sourceIdentity = "test-identity",
            ),
            translated,
            currentCapabilities,
        )!!

    @Test
    fun `asymmetric capabilities prevent swap to unsupported target`() {
        val asymmetric = TranslationBackendCapabilities(
            backendId = "asymmetric",
            implementationVersion = "v",
            supportedTargetLanguages = setOf("en"),
            supportedSourceLanguages = setOf("ko", "ja"),
        )
        val resolvedPreviousSource = "ja"
        val target = StandaloneTranslationPolicy.swapTargetLanguage(
            resolvedSourceLanguage = resolvedPreviousSource,
            requestedSourceLanguage = "unspecified",
            newSourceText = "translated text",
            capabilities = asymmetric,
        )
        assertEquals("en", target)
        assertNotEquals("ja", target)
    }

    private class RecordingTranslator(
        private val block: suspend () -> TranslationResult = {
            TranslationResult.Failure("unexpected")
        },
    ) : LocalTranslator {
        override val capabilities: TranslationBackendCapabilities = TranslationBackends.CURRENT
        var calls = 0
        var lastRequest: TranslationRequest? = null
        override suspend fun translate(request: TranslationRequest): TranslationResult {
            calls++
            lastRequest = request
            return block()
        }
    }
}