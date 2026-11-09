package com.projectnuke.fusion.ui

import com.projectnuke.fusion.translation.LocalTranslationCoordinator
import com.projectnuke.fusion.translation.LocalTranslator
import com.projectnuke.fusion.translation.TranslationArtifact
import com.projectnuke.fusion.translation.TranslationBackendCapabilities
import com.projectnuke.fusion.translation.TranslationBackends
import com.projectnuke.fusion.translation.TranslationPolicy
import com.projectnuke.fusion.translation.TranslationRequest
import com.projectnuke.fusion.translation.TranslationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exception-boundary contract for the standalone translation surface:
 *  - cancellation remains cancellation (never fabricated into failure),
 *  - unexpected operational backend failures settle the surface out of
 *    TRANSLATING into a bounded failure state,
 *  - a stale failed request can never overwrite a newer request,
 *  - translation remains local-only (no external route).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StandaloneTranslationFailureSettlementTest {
    @Test
    fun `editing source cancels entered backend without publishing failure`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<TranslationResult>()
        val translator = ThrowingTranslator {
            entered.complete(Unit)
            try { gate.await() } finally { cancelled.complete(Unit) }
        }
        val controller = StandaloneTranslationController(this, LocalTranslationCoordinator(translator))
        controller.open("Hello world")
        controller.translate()
        runCurrent()
        assertTrue(entered.isCompleted)
        controller.setSource("New source")
        runCurrent()
        assertTrue(cancelled.isCompleted)
        gate.complete(TranslationResult.Failure("late"))
        runCurrent()
        assertEquals("New source", controller.state.value.sourceText)
        assertEquals(StandaloneTranslationPhase.IDLE, controller.state.value.phase)
        assertEquals(null, controller.state.value.artifact)
    }

    @Test
    fun `editing target cancels entered backend without publishing failure`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<TranslationResult>()
        val translator = ThrowingTranslator {
            entered.complete(Unit)
            try { gate.await() } finally { cancelled.complete(Unit) }
        }
        val controller = StandaloneTranslationController(this, LocalTranslationCoordinator(translator))
        controller.open("Hello world")
        controller.translate()
        runCurrent()
        assertTrue(entered.isCompleted)
        controller.setTargetLanguage("ja")
        runCurrent()
        assertTrue(cancelled.isCompleted)
        gate.complete(TranslationResult.Failure("late"))
        runCurrent()
        assertEquals("ja", controller.state.value.targetLanguage)
        assertEquals(StandaloneTranslationPhase.IDLE, controller.state.value.phase)
        assertEquals(null, controller.state.value.artifact)
    }


    private val currentCapabilities = TranslationBackends.CURRENT

    private class ThrowingTranslator(
        private val block: suspend () -> TranslationResult,
    ) : LocalTranslator {
        override val capabilities: TranslationBackendCapabilities = TranslationBackends.CURRENT
        var calls = 0
        override suspend fun translate(request: TranslationRequest): TranslationResult {
            calls++
            return block()
        }
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

    // 1. A normal backend exception settles FAILURE (never stuck TRANSLATING).
    @Test
    fun `backend exception settles failure and never stays translating`() = runTest {
        val controller = StandaloneTranslationController(
            scope = this,
            coordinator = LocalTranslationCoordinator(
                ThrowingTranslator { throw IllegalStateException("backend exploded") },
            ),
        )
        controller.open("Hello world")
        controller.translate()
        runCurrent()
        val state = controller.state.value
        assertEquals(StandaloneTranslationPhase.FAILURE, state.phase)
        assertEquals("Hello world", state.sourceText)
        assertEquals(null, state.artifact)
    }

    // 1b. Coordinator-level: the backend contract converts operational
    //     exceptions into bounded Failure results.
    @Test
    fun `coordinator converts operational exception into failure result`() = runTest {
        val coordinator = LocalTranslationCoordinator(
            ThrowingTranslator { throw IllegalStateException("boom") },
        )
        val result = coordinator.translate(
            TranslationRequest(originalText = "Hello", targetLanguage = "ko", sourceIdentity = "t"),
        )
        assertTrue(result is TranslationResult.Failure)
        assertTrue((result as TranslationResult.Failure).reason.length <= 128)
    }

    // 1c. LinkageError from an optional/native backend integration is a
    //     bounded Unavailable, not a crash and not a stuck busy state.
    @Test
    fun `linkage error settles unavailable`() = runTest {
        val coordinator = LocalTranslationCoordinator(
            ThrowingTranslator { throw UnsatisfiedLinkError("native backend missing") },
        )
        val result = coordinator.translate(
            TranslationRequest(originalText = "Hello", targetLanguage = "ko", sourceIdentity = "t"),
        )
        assertEquals(TranslationResult.Unavailable, result)

        val controller = StandaloneTranslationController(
            scope = this,
            coordinator = LocalTranslationCoordinator(
                ThrowingTranslator { throw UnsatisfiedLinkError("native backend missing") },
            ),
        )
        controller.open("Hello world")
        controller.translate()
        runCurrent()
        assertEquals(StandaloneTranslationPhase.UNAVAILABLE, controller.state.value.phase)
    }

    // 2. Cancellation remains cancellation: nothing fabricates a failure and
    //    the coordinator rethrows CancellationException unchanged.
    @Test
    fun `cancellation stays cancellation and does not fabricate failure`() = runTest {
        val coordinator = LocalTranslationCoordinator(
            ThrowingTranslator { throw kotlinx.coroutines.CancellationException("cancelled") },
        )
        var rethrown: Throwable? = null
        try {
            coordinator.translate(
                TranslationRequest(originalText = "Hello", targetLanguage = "ko", sourceIdentity = "t"),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            rethrown = e
        }
        assertTrue(rethrown is kotlinx.coroutines.CancellationException)

        // Controller level: a cancelled in-flight request leaves no
        // fabricated FAILURE behind (stays in its pre-completion state).
        val gate = CompletableDeferred<TranslationResult>()
        val controller = StandaloneTranslationController(
            scope = this,
            coordinator = LocalTranslationCoordinator(ThrowingTranslator { gate.await() }),
        )
        controller.open("Hello world")
        controller.translate()
        runCurrent()
        controller.close()
        gate.complete(TranslationResult.Failure("late"))
        runCurrent()
        // The closed session resets to a fresh idle state, never FAILURE.
        assertEquals(StandaloneTranslationPhase.IDLE, controller.state.value.phase)
    }

    // 3. A stale failed request cannot overwrite a newer request.
    @Test
    fun `stale failure cannot overwrite newer request state`() = runTest {
        val gate = CompletableDeferred<TranslationResult>()
        val translator = ThrowingTranslator { gate.await() }
        val controller = StandaloneTranslationController(
            scope = this,
            coordinator = LocalTranslationCoordinator(translator),
        )
        controller.open("Hello world")
        controller.translate()
        runCurrent()
        val translatingRequestId = controller.state.value.requestId

        // User edits the source: the request epoch advances.
        controller.setSource("New text")
        val newerRequestId = controller.state.value.requestId
        assertTrue(newerRequestId > translatingRequestId)

        // The OLD request now fails after the epoch moved on.
        gate.completeExceptionally(IllegalStateException("old backend failure"))
        runCurrent()

        val state = controller.state.value
        assertEquals(StandaloneTranslationPhase.IDLE, state.phase)
        assertEquals("New text", state.sourceText)
        assertEquals(null, state.artifact)
    }

    // 4. A subsequent translation after a settled failure can run normally.
    @Test
    fun `translation works again after a settled failure`() = runTest {
        var shouldThrow = true
        val translator = ThrowingTranslator {
            if (shouldThrow) throw IllegalStateException("first attempt fails")
            TranslationResult.Success(artifact("Hello world", "안녕 세상", target = "ko"))
        }
        val controller = StandaloneTranslationController(
            scope = this,
            coordinator = LocalTranslationCoordinator(translator),
        )
        controller.open("Hello world")
        controller.translate()
        runCurrent()
        assertEquals(StandaloneTranslationPhase.FAILURE, controller.state.value.phase)

        shouldThrow = false
        controller.translate()
        runCurrent()
        assertEquals(StandaloneTranslationPhase.SUCCESS, controller.state.value.phase)
        assertEquals(
            "안녕 세상",
            StandaloneTranslationPolicy.displayedTranslatedText(controller.state.value, currentCapabilities),
        )
    }

    // 5. No external route is introduced by the failure path.
    @Test
    fun `failure settlement introduces no external translation route`() {
        assertFalse(TranslationPolicy.allowsExternalTranslation())
        val controllerOnly = StandaloneTranslationController::class.java.constructors
            .flatMap { it.parameterTypes.toList() }
            .map { it.name }
        assertTrue(controllerOnly.any { it.contains("LocalTranslationCoordinator") })
        assertTrue(
            "controller collaborators must remain local-only",
            controllerOnly.none { it.contains("External", ignoreCase = true) || it.contains("Cloud", ignoreCase = true) },
        )
    }
}
