package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.translation.LocalTranslationCoordinator
import com.projectnuke.fusion.translation.LocalTranslator
import com.projectnuke.fusion.translation.TranslationBackendCapabilities
import com.projectnuke.fusion.translation.TranslationBackends
import com.projectnuke.fusion.translation.TranslationPolicy
import com.projectnuke.fusion.translation.TranslationRequest
import com.projectnuke.fusion.translation.TranslationResult
import com.projectnuke.fusion.translation.TranslationRoute
import com.projectnuke.fusion.translation.TranslationSourceLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exception-boundary contract for the evidence translation surface:
 *  - an ordinary operational exception escaping the coordinator barrier must
 *    settle the OWNED source out of TRANSLATING, because a source left
 *    TRANSLATING with no active job can never be retried,
 *  - a superseded run must never overwrite the newer request owner,
 *  - cancellation keeps its existing supersede semantics,
 *  - fatal errors are never converted into UNAVAILABLE.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceTranslationFailureSettlementTest {
    private val answer = EvidenceAnswerIdentity(41L, TranslationPolicy.sha256Hex("answer"))

    // P2.1: the coordinator's own request validation reads the backend
    // capability surface outside its guarded backend call, so a capability
    // violation escapes `translate` as an ordinary RuntimeException while the
    // run still owns the source. Without the bounded Exception path the source
    // stays TRANSLATING forever: the next toggle returns early and no job
    // remains that could ever finish it.
    @Test
    fun `ordinary runtime failure escaping the coordinator settles unavailable`() = runTest {
        val translator = ContractViolatingTranslator()
        val source = source()
        val controller = controller(this, translator)
        controller.openSession(answer)

        controller.toggle(answer, source)
        translator.failCapabilities = true
        runCurrent()

        val presentation = controller.presentation(answer, source)
        assertEquals(EvidenceTranslationPresentationState.UNAVAILABLE, presentation.state)
        assertNull(presentation.artifact)
        assertTrue(presentation.showingOriginal)
    }

    // P2.2: because the failed run settled (and released its job), the same
    // source can be translated again instead of being permanently blocked by
    // the early TRANSLATING return.
    @Test
    fun `retry after an ordinary runtime failure performs a second translation`() = runTest {
        val translator = ContractViolatingTranslator()
        val source = source()
        val controller = controller(this, translator)
        controller.openSession(answer)

        controller.toggle(answer, source)
        translator.failCapabilities = true
        runCurrent()
        assertEquals(EvidenceTranslationPresentationState.UNAVAILABLE, controller.presentation(answer, source).state)

        translator.failCapabilities = false
        controller.toggle(answer, source)
        runCurrent()

        // The failed run escaped at the coordinator's own capability
        // validation, before any backend call, so exactly one backend call
        // (the retry) must have occurred.
        assertEquals(1, translator.calls)
        assertEquals(EvidenceTranslationPresentationState.TRANSLATED, controller.presentation(answer, source).state)
        assertEquals("번역된 참고 문장", controller.presentation(answer, source).artifact?.translatedText)
    }

    // P2.3: a superseded run's late failure must never settle, clear or
    // overwrite the newer request owner. The gate is completed while the older
    // run is still owned by nobody, and the newer request is started only
    // afterwards, so the barrier is fully deterministic.
    @Test
    fun `superseded late failure cannot overwrite the newer request owner`() = runTest {
        val translator = ContractViolatingTranslator()
        translator.ignoreCancellation = true
        val gateA = CompletableDeferred<TranslationResult>()
        translator.gate = gateA
        val source = source()
        val controller = controller(this, translator)
        controller.openSession(answer)

        controller.toggle(answer, source)
        runCurrent()
        assertEquals(
            EvidenceTranslationPresentationState.TRANSLATING,
            controller.presentation(answer, source).state,
        )

        controller.cancel(answer, source)
        assertEquals(
            EvidenceTranslationPresentationState.ORIGINAL,
            controller.presentation(answer, source).state,
        )

        gateA.complete(TranslationResult.Failure("superseded backend failure"))
        runCurrent()

        val afterStaleFailure = controller.presentation(answer, source)
        assertEquals(EvidenceTranslationPresentationState.ORIGINAL, afterStaleFailure.state)
        assertNull(afterStaleFailure.artifact)

        translator.gate = null
        controller.toggle(answer, source)
        runCurrent()

        assertEquals(
            EvidenceTranslationPresentationState.TRANSLATED,
            controller.presentation(answer, source).state,
        )
        assertEquals("번역된 참고 문장", controller.presentation(answer, source).artifact?.translatedText)
    }

    // P2.4: explicit cancellation keeps the existing supersede semantics.
    @Test
    fun `explicit cancellation keeps the original presentation semantics`() = runTest {
        val translator = ContractViolatingTranslator()
        translator.ignoreCancellation = true
        val gate = CompletableDeferred<TranslationResult>()
        translator.gate = gate
        val source = source()
        val controller = controller(this, translator)
        controller.openSession(answer)

        controller.toggle(answer, source)
        runCurrent()
        controller.cancel(answer, source)
        gate.complete(artifactFor(source))
        runCurrent()

        val presentation = controller.presentation(answer, source)
        assertEquals(EvidenceTranslationPresentationState.ORIGINAL, presentation.state)
        assertNull(presentation.artifact)
    }

    // P2.5: a fatal linkage failure must escape the coroutine instead of being
    // downgraded into an ordinary UNAVAILABLE presentation.
    @Test
    fun `fatal linkage error is not converted into unavailable`() = runTest {
        val fatal = CompletableDeferred<Throwable>()
        val scope = ownedScope(testScheduler, fatal)
        val translator = ContractViolatingTranslator()
        translator.capabilitiesFailure = { LinkageError("translation backend linkage failure") }
        val source = source()
        val controller = EvidenceTranslationUiController(scope, LocalTranslationCoordinator(translator))
        controller.openSession(answer)

        controller.toggle(answer, source)
        translator.failCapabilities = true
        runCurrent()

        assertTrue(withTimeout(1_000) { fatal.await() } is LinkageError)
        assertTrue(
            controller.presentation(answer, source).state != EvidenceTranslationPresentationState.UNAVAILABLE,
        )
        scope.cancel()
    }

    private fun controller(
        scope: CoroutineScope,
        translator: ContractViolatingTranslator,
    ): EvidenceTranslationUiController =
        EvidenceTranslationUiController(scope, LocalTranslationCoordinator(translator))

    private fun ownedScope(
        scheduler: TestCoroutineScheduler,
        fatal: CompletableDeferred<Throwable>,
    ): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(scheduler) +
                CoroutineExceptionHandler { _, e -> fatal.complete(e) },
        )

    private fun source(
        id: String = "source-1",
        snippet: String = "The canonical source snippet.",
        url: String = "https://example.test/source-1",
    ) = EvidenceSource(
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

    private fun artifactFor(source: EvidenceSource): TranslationResult.Success {
        val identity = EvidenceTranslationIdentity.of(answer, source)
        val artifact = TranslationPolicy.makeArtifact(
            TranslationRequest(
                originalText = source.snippet,
                targetLanguage = "ko",
                sourceLanguage = TranslationSourceLanguage.Unspecified,
                route = TranslationRoute.EVIDENCE_PRESENTATION,
                sourceIdentity = identity.requestSourceIdentity,
                sourceUrl = source.url,
            ),
            "번역된 참고 문장",
            TranslationBackends.CURRENT,
        )!!
        return TranslationResult.Success(artifact)
    }

    /**
     * Backend seam whose capability surface can become unavailable between the
     * presentation eligibility check and the coordinator's own request
     * validation, which is exactly where an ordinary operational exception
     * escapes the coordinator's guarded backend call.
     */
    private class ContractViolatingTranslator : LocalTranslator {
        override val capabilities: TranslationBackendCapabilities
            get() {
                if (failCapabilities) throw capabilitiesFailure()
                return TranslationBackends.CURRENT
            }

        var failCapabilities = false
        var capabilitiesFailure: () -> Throwable = {
            IllegalStateException("translation capabilities unavailable")
        }
        var calls = 0
        var gate: CompletableDeferred<TranslationResult>? = null
        var ignoreCancellation = false

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            calls++
            val pending = gate
            if (pending != null) {
                return if (ignoreCancellation) {
                    withContext(NonCancellable) { pending.await() }
                } else {
                    pending.await()
                }
            }
            return TranslationResult.Success(
                TranslationPolicy.makeArtifact(request, "번역된 참고 문장", TranslationBackends.CURRENT)!!,
            )
        }
    }
}
