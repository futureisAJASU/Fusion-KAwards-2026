package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceCitationMapper
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.ConservativeRuleEvidenceAssessor
import com.projectnuke.fusion.data.EvidenceClock
import com.projectnuke.fusion.data.EVIDENCE_VERIFIER_VERSION
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.data.EvidenceVerificationPlanner
import com.projectnuke.fusion.translation.LocalTranslationCoordinator
import com.projectnuke.fusion.translation.LocalTranslator
import com.projectnuke.fusion.translation.TranslationArtifact
import com.projectnuke.fusion.translation.TranslationBackendCapabilities
import com.projectnuke.fusion.translation.TranslationBounds
import com.projectnuke.fusion.translation.TranslationPolicy
import com.projectnuke.fusion.translation.TranslationRequest
import com.projectnuke.fusion.translation.TranslationResult
import com.projectnuke.fusion.translation.TranslationRoute
import com.projectnuke.fusion.translation.TranslationSourceLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EvidenceTranslationUiControllerTest {
    private val answer = EvidenceAnswerIdentity(41L, TranslationPolicy.sha256Hex("answer"))

    @Test
    fun `eligible non Korean source exposes translation eligibility`() {
        assertTrue(isEvidenceTranslationEligibleSnippet("The original source snippet."))
    }

    @Test
    fun `Korean unsupported and blank snippets remain original only`() {
        assertFalse(isEvidenceTranslationEligibleSnippet("원문 출처 문장입니다."))
        assertFalse(isEvidenceTranslationEligibleSnippet(""))
        assertFalse(isEvidenceTranslationEligibleSnippet("x".repeat(TranslationBounds.MAX_INPUT_CHARS + 1)))
    }

    @Test
    fun `successful local translation is derived presentation and toggles deterministically`() = runTest {
        val translator = ControlledTranslator()
        val source = source()
        val controller = controller(this, translator)
        controller.openSession(answer)

        controller.toggle(answer, source)
        runCurrent()

        val translated = controller.presentation(answer, source)
        assertEquals(EvidenceTranslationPresentationState.TRANSLATED, translated.state)
        assertFalse(translated.showingOriginal)
        assertEquals("번역된 참고 문장", translated.artifact?.translatedText)
        assertEquals(source.snippet, translated.artifact?.originalText)
        assertTrue(TranslationPolicy.preservesOriginal(translated.artifact!!, source.snippet))

        controller.toggle(answer, source)
        assertTrue(controller.presentation(answer, source).showingOriginal)
        controller.toggle(answer, source)
        assertFalse(controller.presentation(answer, source).showingOriginal)
        assertEquals(1, translator.calls)
    }

    @Test
    fun `unavailable translator preserves original presentation`() = runTest {
        val translator = ControlledTranslator { _ -> TranslationResult.Unavailable }
        val source = source()
        val controller = controller(this, translator)
        controller.openSession(answer)
        controller.toggle(answer, source)
        runCurrent()

        val presentation = controller.presentation(answer, source)
        assertEquals(EvidenceTranslationPresentationState.UNAVAILABLE, presentation.state)
        assertNull(presentation.artifact)
        assertTrue(presentation.showingOriginal)
    }

    @Test
    fun `failed translator preserves original presentation`() = runTest {
        val translator = ControlledTranslator { _ -> TranslationResult.Failure("local failure") }
        val source = source()
        val controller = controller(this, translator)
        controller.openSession(answer)
        controller.toggle(answer, source)
        runCurrent()

        assertEquals(EvidenceTranslationPresentationState.UNAVAILABLE, controller.presentation(answer, source).state)
        assertNull(controller.presentation(answer, source).artifact)
    }

    @Test
    fun `cancellation preserves original and cannot publish late result`() = runTest {
        val translator = ControlledTranslator()
        val deferred = CompletableDeferred<TranslationResult>()
        translator.pending = deferred
        translator.ignoreCancellation = true
        val source = source()
        val controller = controller(this, translator)
        controller.openSession(answer)
        controller.toggle(answer, source)
        runCurrent()
        controller.cancel(answer, source)
        deferred.complete(translator.artifactFor(source, answer))
        runCurrent()

        val presentation = controller.presentation(answer, source)
        assertEquals(EvidenceTranslationPresentationState.ORIGINAL, presentation.state)
        assertNull(presentation.artifact)
    }

    @Test
    fun `late source A result cannot attach to source B`() = runTest {
        val translator = ControlledTranslator()
        val deferred = CompletableDeferred<TranslationResult>()
        translator.pending = deferred
        translator.ignoreCancellation = true
        val sourceA = source("source-a")
        val sourceB = source("source-b", snippet = "A different source snippet.")
        val controller = controller(this, translator)
        controller.openSession(answer)
        controller.toggle(answer, sourceA)
        runCurrent()
        controller.toggle(answer, sourceB)
        deferred.complete(translator.artifactFor(sourceA, answer))
        runCurrent()

        assertEquals(EvidenceTranslationPresentationState.UNAVAILABLE, controller.presentation(answer, sourceB).state)
        assertNull(controller.presentation(answer, sourceB).artifact)
        assertEquals("A different source snippet.", sourceB.snippet)
    }

    @Test
    fun `late message A result cannot attach after opening message B`() = runTest {
        val translator = ControlledTranslator()
        val deferred = CompletableDeferred<TranslationResult>()
        translator.pending = deferred
        translator.ignoreCancellation = true
        val source = source()
        val answerB = answer.copy(messageId = 42L)
        val controller = controller(this, translator)
        controller.openSession(answer)
        controller.toggle(answer, source)
        runCurrent()
        controller.openSession(answerB)
        deferred.complete(translator.artifactFor(source, answer))
        runCurrent()

        assertEquals(answerB, controller.state.value.session)
        assertTrue(controller.state.value.presentations.isEmpty())
        assertEquals(EvidenceTranslationPresentationState.ORIGINAL, controller.presentation(answerB, source).state)
    }

    @Test
    fun `dismissal invalidates a late translation result`() = runTest {
        val translator = ControlledTranslator()
        val deferred = CompletableDeferred<TranslationResult>()
        translator.pending = deferred
        translator.ignoreCancellation = true
        val source = source()
        val controller = controller(this, translator)
        controller.openSession(answer)
        controller.toggle(answer, source)
        runCurrent()
        controller.dismiss()
        deferred.complete(translator.artifactFor(source, answer))
        runCurrent()

        assertEquals(EvidenceTranslationUiState(), controller.state.value)
    }

    @Test
    fun `production evidence dismiss action clears translation and fences late result`() = runTest {
        val translator = ControlledTranslator()
        val sourceA = source("source-a")
        val sourceB = source("source-b", snippet = "Another source snippet.")
        val translationController = controller(this, translator)
        translationController.openSession(answer)
        translationController.toggle(answer, sourceA)
        runCurrent()
        assertEquals(EvidenceTranslationPresentationState.TRANSLATED, translationController.presentation(answer, sourceA).state)

        val deferred = CompletableDeferred<TranslationResult>()
        translator.pending = deferred
        translator.ignoreCancellation = true
        translationController.toggle(answer, sourceB)
        runCurrent()
        assertEquals(EvidenceTranslationPresentationState.TRANSLATING, translationController.presentation(answer, sourceB).state)

        var evidenceDismissed = false
        EvidenceSurfaceDismissAction(
            dismissEvidence = { evidenceDismissed = true },
            dismissTranslation = translationController::dismiss,
        ).dismiss()
        deferred.complete(translator.artifactFor(sourceB, answer))
        runCurrent()

        assertTrue(evidenceDismissed)
        assertEquals(EvidenceTranslationUiState(), translationController.state.value)
        assertEquals(0, translationController.cachedArtifactCount)
        translationController.openSession(answer)
        assertEquals(EvidenceTranslationPresentationState.ORIGINAL, translationController.presentation(answer, sourceA).state)
        assertTrue(translationController.presentation(answer, sourceA).showingOriginal)
    }

    @Test
    fun `production evidence open action rebinds same message after dismiss`() = runTest {
        val translator = ControlledTranslator()
        val translationController = controller(this, translator)
        var evidenceOpenCalls = 0
        val openAction = EvidenceSurfaceOpenAction(
            openTranslation = translationController::openSession,
            openEvidence = { evidenceOpenCalls++ },
        )
        val dismissAction = EvidenceSurfaceDismissAction(
            dismissEvidence = {},
            dismissTranslation = translationController::dismiss,
        )
        val cachedSource = source("cached-source")
        val sourceA = source("source-a")
        openAction.open(answer)
        translationController.toggle(answer, cachedSource)
        runCurrent()
        assertEquals(EvidenceTranslationPresentationState.TRANSLATED, translationController.presentation(answer, cachedSource).state)

        val staleDeferred = CompletableDeferred<TranslationResult>()
        translator.pending = staleDeferred
        translator.ignoreCancellation = true
        translationController.toggle(answer, sourceA)
        runCurrent()
        dismissAction.dismiss()
        assertEquals(0, translationController.cachedArtifactCount)

        openAction.open(answer)
        staleDeferred.complete(translator.artifactFor(sourceA, answer))
        runCurrent()
        assertEquals(2, evidenceOpenCalls)
        assertEquals(answer, translationController.state.value.session)
        assertEquals(EvidenceTranslationPresentationState.ORIGINAL, translationController.presentation(answer, sourceA).state)

        translator.pending = null
        translationController.toggle(answer, sourceA)
        runCurrent()
        assertEquals(3, translator.calls)
        assertEquals(EvidenceTranslationPresentationState.TRANSLATED, translationController.presentation(answer, sourceA).state)
    }

    @Test
    fun `same source id with changed snippet cannot accept old artifact`() = runTest {
        val translator = ControlledTranslator { request ->
            TranslationResult.Success(
                TranslationPolicy.makeArtifact(
                    request.copy(originalText = "Original snippet A."),
                    "번역",
                    com.projectnuke.fusion.translation.TranslationBackends.CURRENT,
                )!!,
            )
        }
        val sourceA = source("same-source", "Original snippet A.")
        val sourceB = source("same-source", "Changed snippet B.")
        val controller = controller(this, translator)
        controller.openSession(answer)
        controller.toggle(answer, sourceA)
        runCurrent()
        controller.toggle(answer, sourceB)
        runCurrent()

        assertEquals(EvidenceTranslationPresentationState.TRANSLATED, controller.presentation(answer, sourceA).state)
        assertEquals(EvidenceTranslationPresentationState.UNAVAILABLE, controller.presentation(answer, sourceB).state)
        assertNull(controller.presentation(answer, sourceB).artifact)
    }

    @Test
    fun `unsupported target artifact is rejected by presentation contract`() = runTest {
        val translator = ControlledTranslator { request ->
            TranslationResult.Success(
                TranslationArtifact(
                    originalText = request.originalText,
                    originalHashHex = TranslationPolicy.sha256Hex(request.originalText),
                    targetLanguage = "xx",
                    translatedText = "bad target",
                    implementationVersion = TranslationBounds.IMPLEMENTATION_VERSION,
                    backendId = "litert-shared-local",
                    requestedSourceLanguage = "auto",
                    sourceIdentity = request.sourceIdentity,
                ),
            )
        }
        val source = source()
        val controller = controller(this, translator)
        controller.openSession(answer)
        controller.toggle(answer, source)
        runCurrent()

        assertEquals(EvidenceTranslationPresentationState.UNAVAILABLE, controller.presentation(answer, source).state)
        assertNull(controller.presentation(answer, source).artifact)
    }

    @Test
    fun `source URL mismatch is rebound to canonical source URL`() = runTest {
        val translator = ControlledTranslator { request ->
            TranslationResult.Success(
                TranslationPolicy.makeArtifact(
                    request.copy(sourceUrl = "https://attacker.invalid/translated"),
                    "번역",
                    com.projectnuke.fusion.translation.TranslationBackends.CURRENT,
                )!!,
            )
        }
        val source = source(url = "https://example.test/canonical")
        val controller = controller(this, translator)
        controller.openSession(answer)
        controller.toggle(answer, source)
        runCurrent()

        val artifact = controller.presentation(answer, source).artifact
        assertNotNull(artifact)
        assertEquals(source.url, artifact!!.sourceUrl)
    }

    @Test
    fun `surviving host metadata is rejected rather than presented`() = runTest {
        val translator = ControlledTranslator { request ->
            TranslationResult.Success(
                TranslationArtifact(
                    originalText = request.originalText,
                    originalHashHex = TranslationPolicy.sha256Hex(request.originalText),
                    targetLanguage = "ko",
                    translatedText = "번역 [FUSION_TRUST_ROUTE_JSON]control",
                    implementationVersion = TranslationBounds.IMPLEMENTATION_VERSION,
                    backendId = "litert-shared-local",
                    requestedSourceLanguage = "auto",
                    sourceIdentity = request.sourceIdentity,
                ),
            )
        }
        val source = source()
        val controller = controller(this, translator)
        controller.openSession(answer)
        controller.toggle(answer, source)
        runCurrent()

        assertEquals(EvidenceTranslationPresentationState.UNAVAILABLE, controller.presentation(answer, source).state)
        assertNull(controller.presentation(answer, source).artifact)
    }

    @Test
    fun `busy second source remains original without corrupting active source`() = runTest {
        val translator = ControlledTranslator()
        val deferred = CompletableDeferred<TranslationResult>()
        translator.pending = deferred
        val sourceA = source("source-a")
        val sourceB = source("source-b", snippet = "Another source snippet.")
        val controller = controller(this, translator)
        controller.openSession(answer)
        controller.toggle(answer, sourceA)
        runCurrent()
        controller.toggle(answer, sourceB)
        assertEquals(EvidenceTranslationPresentationState.TRANSLATING, controller.presentation(answer, sourceA).state)
        assertEquals(EvidenceTranslationPresentationState.UNAVAILABLE, controller.presentation(answer, sourceB).state)
        deferred.complete(translator.artifactFor(sourceA, answer))
        runCurrent()
        assertEquals(EvidenceTranslationPresentationState.TRANSLATED, controller.presentation(answer, sourceA).state)
        assertNull(controller.presentation(answer, sourceB).artifact)
    }

    @Test
    fun `translation cache retains bounded deterministic artifact bytes`() = runTest {
        val translator = ControlledTranslator { request ->
            TranslationResult.Success(
                TranslationPolicy.makeArtifact(
                    request.copy(originalText = request.originalText),
                    "y".repeat(3_900),
                    com.projectnuke.fusion.translation.TranslationBackends.CURRENT,
                )!!,
            )
        }
        val controller = controller(this, translator)
        controller.openSession(answer)
        val accountingSource = source("accounting", "accounting source snippet")
        controller.toggle(answer, accountingSource)
        runCurrent()
        val accountingArtifact = controller.presentation(answer, accountingSource).artifact!!
        val expectedAccountingBytes = listOf(
            accountingArtifact.originalText,
            accountingArtifact.originalHashHex,
            accountingArtifact.targetLanguage,
            accountingArtifact.translatedText,
            accountingArtifact.implementationVersion,
            accountingArtifact.sourceIdentity,
            accountingArtifact.sourceUrl.orEmpty(),
        ).sumOf { it.length * 2 }
        assertEquals(expectedAccountingBytes, controller.cachedArtifactBytesForTest)
        controller.dismiss()
        controller.openSession(answer)
        repeat(20) { index ->
            controller.toggle(answer, source("source-$index", "x".repeat(3_900)))
            runCurrent()
        }

        assertTrue(controller.cachedArtifactBytesForTest <= TranslationBounds.MAX_CACHED_ARTIFACT_BYTES)
        assertTrue(controller.cachedArtifactCount < 20)
    }

    @Test
    fun `translation presentation does not alter citation or evidence authority`() = runTest {
        val source = source(snippet = answerText)
        val claim = EvidenceClaim("C1", answerText, EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf(source.id), null)
        val report = EvidenceReport(
            1,
            EvidenceFingerprint.canonicalAnswerHash(answerText),
            1L,
            EVIDENCE_VERIFIER_VERSION,
            listOf(claim),
            listOf(source),
        )
        val message = com.projectnuke.fusion.data.MessageEntity(answer.messageId, 10L, "assistant", answerText, 1L)
        val beforeSource = source.copy()
        val beforeCitations = EvidenceCitationMapper.map(message, com.projectnuke.fusion.data.MessageEvidenceReport(message.id, report))
        val candidate = EvidenceVerificationPlanner.candidates(answerText).first()
        val assessor = ConservativeRuleEvidenceAssessor(EvidenceClock { 1L }, java.time.ZoneId.of("UTC"))
        val beforeVerdict = assessor.assess(candidate, listOf(source)).verdict
        val controller = controller(this, ControlledTranslator())
        controller.openSession(answer)
        controller.toggle(answer, source)
        runCurrent()

        assertEquals(beforeSource, source)
        assertEquals(source.snippet, TranslationPolicy.canonicalEvidenceText(controller.presentation(answer, source).artifact!!))
        val afterCitations = EvidenceCitationMapper.map(message, com.projectnuke.fusion.data.MessageEvidenceReport(message.id, report))
        assertEquals(beforeCitations, afterCitations)
        assertEquals(beforeVerdict, assessor.assess(candidate, listOf(source)).verdict)
        assertEquals(source.url, report.sources.single().url)
    }

    private fun controller(scope: CoroutineScope, translator: ControlledTranslator): EvidenceTranslationUiController =
        EvidenceTranslationUiController(scope, LocalTranslationCoordinator(translator))

    private fun source(id: String = "source-1", snippet: String = "The canonical source snippet.", url: String = "https://example.test/source-1") =
        EvidenceSource(
            id = id,
            title = "Canonical title",
            source = "Example source",
            url = url,
            snippet = snippet,
            publishedAt = "2026-09-01",
            providerType = "FREE_DEFAULT",
            providerDisplayName = "Fixture provider",
            queryUsed = "canonical query",
        )

    private class ControlledTranslator(
        private val resultFactory: (TranslationRequest) -> TranslationResult = { request ->
            TranslationResult.Success(
                TranslationPolicy.makeArtifact(
                    request,
                    "번역된 참고 문장",
                    com.projectnuke.fusion.translation.TranslationBackends.CURRENT,
                )!!,
            )
        },
    ) : LocalTranslator {
        override val capabilities: TranslationBackendCapabilities =
            com.projectnuke.fusion.translation.TranslationBackends.CURRENT
        var calls = 0
        var pending: CompletableDeferred<TranslationResult>? = null
        var ignoreCancellation = false

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            calls++
            val deferred = pending
            if (deferred != null) {
                return if (ignoreCancellation) {
                    withContext(NonCancellable) { deferred.await() }
                } else {
                    deferred.await()
                }
            }
            return resultFactory(request)
        }

        fun artifactFor(source: EvidenceSource, answer: EvidenceAnswerIdentity): TranslationResult =
            TranslationResult.Success(
                TranslationPolicy.makeArtifact(
                    TranslationRequest(
                        originalText = source.snippet,
                        targetLanguage = "ko",
                        sourceLanguage = TranslationSourceLanguage.Unspecified,
                        route = TranslationRoute.EVIDENCE_PRESENTATION,
                        sourceIdentity = EvidenceTranslationIdentity.of(answer, source).requestSourceIdentity,
                    ),
                    "늦은 번역",
                    capabilities,
                )!!,
            )
    }

    private companion object {
        const val answerText = "Acme released ProductX2 in 2026."
    }
}
