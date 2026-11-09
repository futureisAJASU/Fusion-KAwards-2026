package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// GATE A: the production Retry Search owner enforces one in-flight attempt
// per failed message, consumes the frozen snapshot exactly once, keeps the
// retry eligible only when the attempt never started, and releases ownership
// exactly once on every terminal path.
class SearchRetryAttemptCoordinatorIntegrationTest {

    @Test
    fun `second tap while the first attempt is in flight is refused`() = runTest {
        val recovery = TrustRecoveryCoordinator()
        recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot())
        val coordinator = SearchRetryAttemptCoordinator(recovery)
        val release = CompletableDeferred<Unit>()

        val first = coordinator.claim(FAILED_MESSAGE_ID)
        assertTrue(first is SearchRetryAttemptCoordinator.ClaimOutcome.Granted)
        val attempt = (first as SearchRetryAttemptCoordinator.ClaimOutcome.Granted).attempt
        val job = launch {
            coordinator.run(
                attempt = attempt,
                prepare = { Unit },
                register = { },
                execute = { release.await() },
            )
        }
        testScheduler.runCurrent()

        val second = coordinator.claim(FAILED_MESSAGE_ID)
        assertTrue(second is SearchRetryAttemptCoordinator.ClaimOutcome.Rejected)
        assertEquals(
            SearchRetryAttemptCoordinator.RejectionReason.ALREADY_IN_FLIGHT,
            (second as SearchRetryAttemptCoordinator.ClaimOutcome.Rejected).reason,
        )
        assertEquals(1, coordinator.inFlightAttemptCount())

        release.complete(Unit)
        job.join()
        assertEquals(0, coordinator.inFlightAttemptCount())
    }

    @Test
    fun `different retry owners settle safely when completion order reverses`() = runTest {
        val recovery = TrustRecoveryCoordinator()
        val secondFailureMessageId = FAILED_MESSAGE_ID + 1
        recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot(requestId = "first"))
        recovery.freezeSearchRetry(secondFailureMessageId, searchSnapshot(requestId = "second"))
        val coordinator = SearchRetryAttemptCoordinator(recovery)
        val first = granted(coordinator.claim(FAILED_MESSAGE_ID))
        val second = granted(coordinator.claim(secondFailureMessageId))
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val completed = mutableListOf<String>()

        val firstJob = launch {
            coordinator.run(
                attempt = first,
                prepare = { first.frozen.requestId },
                register = { },
                execute = {
                    releaseFirst.await()
                    completed += "first"
                },
            )
        }
        val secondJob = launch {
            coordinator.run(
                attempt = second,
                prepare = { second.frozen.requestId },
                register = { },
                execute = {
                    releaseSecond.await()
                    completed += "second"
                },
            )
        }
        testScheduler.runCurrent()
        assertEquals(2, coordinator.inFlightAttemptCount())

        releaseSecond.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf("second"), completed)
        assertEquals(1, coordinator.inFlightAttemptCount())

        releaseFirst.complete(Unit)
        firstJob.join()
        secondJob.join()
        assertEquals(listOf("second", "first"), completed)
        assertEquals(0, coordinator.inFlightAttemptCount())
        assertNull(recovery.pendingSearchRetry(FAILED_MESSAGE_ID))
        assertNull(recovery.pendingSearchRetry(secondFailureMessageId))
    }

    @Test
    fun `claim without a frozen snapshot is refused`() {
        val coordinator = SearchRetryAttemptCoordinator(TrustRecoveryCoordinator())
        val outcome = coordinator.claim(FAILED_MESSAGE_ID)
        assertTrue(outcome is SearchRetryAttemptCoordinator.ClaimOutcome.Rejected)
        assertEquals(
            SearchRetryAttemptCoordinator.RejectionReason.NO_FROZEN_SNAPSHOT,
            (outcome as SearchRetryAttemptCoordinator.ClaimOutcome.Rejected).reason,
        )
        assertEquals(0, coordinator.inFlightAttemptCount())
    }

    @Test
    fun `a completed attempt consumes its frozen snapshot exactly once and releases ownership`() = runTest {
        val recovery = TrustRecoveryCoordinator()
        val frozen = recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot())
        val coordinator = SearchRetryAttemptCoordinator(recovery)
        val attempt = granted(coordinator.claim(FAILED_MESSAGE_ID))
        var prepareCalls = 0
        var registerCalls = 0
        var executeCalls = 0

        val outcome = coordinator.run(
            attempt = attempt,
            prepare = { prepareCalls++ },
            register = { registerCalls++ },
            execute = { executeCalls++ },
        )

        assertSame(SearchRetryAttemptCoordinator.AttemptOutcome.Completed, outcome)
        assertEquals(1, prepareCalls)
        assertEquals(1, registerCalls)
        assertEquals(1, executeCalls)
        assertNull(recovery.pendingSearchRetry(FAILED_MESSAGE_ID))
        assertFalse(coordinator.consume(attempt))
        assertEquals(0, coordinator.inFlightAttemptCount())
        assertEquals(frozen, attempt.frozen)
    }

    @Test
    fun `a preparation failure keeps the retry eligible and never reaches registration`() = runTest {
        val recovery = TrustRecoveryCoordinator()
        val frozen = recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot())
        val coordinator = SearchRetryAttemptCoordinator(recovery)
        val attempt = granted(coordinator.claim(FAILED_MESSAGE_ID))
        var registerCalls = 0

        val outcome = coordinator.run<Unit>(
            attempt = attempt,
            prepare = { throw SearchRetryPreparationRefused("refused") },
            register = { registerCalls++ },
            execute = { },
        )

        assertTrue(outcome is SearchRetryAttemptCoordinator.AttemptOutcome.Failed)
        assertEquals(
            SearchRetryAttemptCoordinator.Stage.PREPARE,
            (outcome as SearchRetryAttemptCoordinator.AttemptOutcome.Failed).stage,
        )
        assertEquals(0, registerCalls)
        assertEquals(frozen, recovery.pendingSearchRetry(FAILED_MESSAGE_ID))
        assertEquals(0, coordinator.inFlightAttemptCount())
        // A fresh attempt re-claims the very same frozen snapshot.
        val retry = granted(coordinator.claim(FAILED_MESSAGE_ID))
        assertEquals(frozen, retry.frozen)
    }

    @Test
    fun `a registration failure restores the frozen retry exactly once`() = runTest {
        val recovery = TrustRecoveryCoordinator()
        val frozen = recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot())
        val coordinator = SearchRetryAttemptCoordinator(recovery)
        val attempt = granted(coordinator.claim(FAILED_MESSAGE_ID))
        var restoreCalls = 0

        val outcome = coordinator.run<Unit>(
            attempt = attempt,
            prepare = { Unit },
            register = {
                restoreCalls++
                throw IllegalStateException("registration refused")
            },
            execute = { },
        )

        assertTrue(outcome is SearchRetryAttemptCoordinator.AttemptOutcome.Failed)
        assertEquals(
            SearchRetryAttemptCoordinator.Stage.REGISTER,
            (outcome as SearchRetryAttemptCoordinator.AttemptOutcome.Failed).stage,
        )
        assertEquals(1, restoreCalls)
        assertEquals(frozen, recovery.pendingSearchRetry(FAILED_MESSAGE_ID))
        assertEquals(0, coordinator.inFlightAttemptCount())
    }

    @Test
    fun `a post-registration failure keeps the attempt consumed and does not restore it`() = runTest {
        val recovery = TrustRecoveryCoordinator()
        recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot())
        val coordinator = SearchRetryAttemptCoordinator(recovery)
        val attempt = granted(coordinator.claim(FAILED_MESSAGE_ID))

        val outcome = coordinator.run<Unit>(
            attempt = attempt,
            prepare = { Unit },
            register = { },
            execute = { throw IllegalStateException("generation failed") },
        )

        assertTrue(outcome is SearchRetryAttemptCoordinator.AttemptOutcome.Failed)
        assertEquals(
            SearchRetryAttemptCoordinator.Stage.EXECUTE,
            (outcome as SearchRetryAttemptCoordinator.AttemptOutcome.Failed).stage,
        )
        assertNull(recovery.pendingSearchRetry(FAILED_MESSAGE_ID))
        assertEquals(0, coordinator.inFlightAttemptCount())
    }

    @Test
    fun `cancellation before the attempt starts keeps the retry eligible`() = runTest {
        val recovery = TrustRecoveryCoordinator()
        val frozen = recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot())
        val coordinator = SearchRetryAttemptCoordinator(recovery)
        val attempt = granted(coordinator.claim(FAILED_MESSAGE_ID))

        var cancelled = false
        try {
            coordinator.run<Unit>(
                attempt = attempt,
                prepare = { throw CancellationException("cancelled") },
                register = { },
                execute = { },
            )
        } catch (expected: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(frozen, recovery.pendingSearchRetry(FAILED_MESSAGE_ID))
        assertEquals(0, coordinator.inFlightAttemptCount())
    }

    @Test
    fun `cancellation after the attempt started settles ownership without restoring`() = runTest {
        val recovery = TrustRecoveryCoordinator()
        recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot())
        val coordinator = SearchRetryAttemptCoordinator(recovery)
        val attempt = granted(coordinator.claim(FAILED_MESSAGE_ID))

        var cancelled = false
        try {
            coordinator.run<Unit>(
                attempt = attempt,
                prepare = { Unit },
                register = { },
                execute = { throw CancellationException("cancelled") },
            )
        } catch (expected: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertNull(recovery.pendingSearchRetry(FAILED_MESSAGE_ID))
        assertEquals(0, coordinator.inFlightAttemptCount())
        // Ownership is released: a later failure on the same message can still
        // claim a newly frozen snapshot.
        val newer = recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot(requestId = "newer"))
        assertEquals(newer, granted(coordinator.claim(FAILED_MESSAGE_ID)).frozen)
    }

    @Test
    fun `fatal preparation errors propagate and retain retry eligibility`() = runTest {
        val recovery = TrustRecoveryCoordinator()
        val frozen = recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot())
        val coordinator = SearchRetryAttemptCoordinator(recovery)
        val attempt = granted(coordinator.claim(FAILED_MESSAGE_ID))
        val fatal = OutOfMemoryError("prepare")

        val observed = try {
            coordinator.run<Unit>(attempt, prepare = { throw fatal }, register = { }, execute = { })
            null
        } catch (failure: OutOfMemoryError) {
            failure
        }

        assertSame(fatal, observed)
        assertEquals(frozen, recovery.pendingSearchRetry(FAILED_MESSAGE_ID))
        assertEquals(0, coordinator.inFlightAttemptCount())
    }

    @Test
    fun `fatal registration errors propagate and restore retry eligibility`() = runTest {
        val recovery = TrustRecoveryCoordinator()
        val frozen = recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot())
        val coordinator = SearchRetryAttemptCoordinator(recovery)
        val attempt = granted(coordinator.claim(FAILED_MESSAGE_ID))
        val fatal = LinkageError("register")

        val observed = try {
            coordinator.run<Unit>(attempt, prepare = { Unit }, register = { throw fatal }, execute = { })
            null
        } catch (failure: LinkageError) {
            failure
        }

        assertSame(fatal, observed)
        assertEquals(frozen, recovery.pendingSearchRetry(FAILED_MESSAGE_ID))
        assertEquals(0, coordinator.inFlightAttemptCount())
    }

    @Test
    fun `fatal execution errors propagate without restoring consumed retry`() = runTest {
        val recovery = TrustRecoveryCoordinator()
        recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot())
        val coordinator = SearchRetryAttemptCoordinator(recovery)
        val attempt = granted(coordinator.claim(FAILED_MESSAGE_ID))
        val fatal = OutOfMemoryError("execute")

        val observed = try {
            coordinator.run<Unit>(attempt, prepare = { Unit }, register = { }, execute = { throw fatal })
            null
        } catch (failure: OutOfMemoryError) {
            failure
        }

        assertSame(fatal, observed)
        assertNull(recovery.pendingSearchRetry(FAILED_MESSAGE_ID))
        assertEquals(0, coordinator.inFlightAttemptCount())
    }

    @Test
    fun `settle is idempotent and never releases a newer attempt`() = runTest {
        val recovery = TrustRecoveryCoordinator()
        recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot())
        val coordinator = SearchRetryAttemptCoordinator(recovery)
        val first = granted(coordinator.claim(FAILED_MESSAGE_ID))
        coordinator.settle(first)
        coordinator.settle(first)
        assertEquals(0, coordinator.inFlightAttemptCount())

        val second = granted(coordinator.claim(FAILED_MESSAGE_ID))
        coordinator.settle(first)
        assertEquals(1, coordinator.inFlightAttemptCount())
        coordinator.settle(second)
        assertEquals(0, coordinator.inFlightAttemptCount())
    }

    @Test
    fun `a retry started after a completed attempt consumes the newer frozen snapshot`() = runTest {
        val recovery = TrustRecoveryCoordinator()
        recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot(requestId = "old"))
        val coordinator = SearchRetryAttemptCoordinator(recovery)
        coordinator.run<Unit>(
            attempt = granted(coordinator.claim(FAILED_MESSAGE_ID)),
            prepare = { Unit },
            register = { },
            execute = { },
        )

        val newer = recovery.freezeSearchRetry(FAILED_MESSAGE_ID, searchSnapshot(requestId = "new"))
        val attempt = granted(coordinator.claim(FAILED_MESSAGE_ID))
        assertEquals(newer, attempt.frozen)
        var registeredRequest: String? = null
        coordinator.run(
            attempt = attempt,
            prepare = { attempt.frozen.requestId },
            register = { registeredRequest = it },
            execute = { },
        )
        assertEquals("new", registeredRequest)
        assertNull(recovery.pendingSearchRetry(FAILED_MESSAGE_ID))
    }

    private fun granted(
        outcome: SearchRetryAttemptCoordinator.ClaimOutcome,
    ): SearchRetryAttemptCoordinator.Attempt {
        assertTrue(
            "expected a granted claim, was $outcome",
            outcome is SearchRetryAttemptCoordinator.ClaimOutcome.Granted,
        )
        return (outcome as SearchRetryAttemptCoordinator.ClaimOutcome.Granted).attempt
    }

    private fun searchSnapshot(requestId: String = "req-1"): GenerationRequestSnapshot =
        GenerationRequestSnapshot(
            requestId = requestId,
            conversationId = 7L,
            generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
            selectedModelId = "m",
            selectedModelPath = "/models/a",
            selectedModelStableId = "stable-a",
            modelFamily = ModelFamily.KANANA,
            settings = GenerationSettings(),
            reasoningEnabled = true,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "current query",
            rawUserText = "current query",
            createdAt = 1L,
            history = listOf(ChatMessage("user", "older")),
            historyMessageIds = listOf(9L),
            trustRoutingDecision = TrustRoutingDecision(
                TrustRoute.SEARCH,
                TrustRoutingReason.CURRENT_INFORMATION_REQUIRED,
                emptySet(),
            ),
            externalProviderId = null,
            externalProviderSelectionFrozen = false,
            externalMaxOutputTokens = 512,
            externalProviderRequest = null,
            searchStrategy = SearchStrategySnapshot.DEFAULT,
            manualSearchQueryInputRevision = null,
        )

    private companion object {
        const val FAILED_MESSAGE_ID = 100L
    }
}

