package com.projectnuke.fusion.ui

import com.projectnuke.fusion.trust.TrustExecutionFailure
import com.projectnuke.fusion.trust.TrustRecoveryAction
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// C2-E production wiring tests: the coordinator classifies typed failures
// bound to the original request and gates explicit retries. ChatScreen calls
// these exact methods in the send/retry flows.
class TrustRecoveryCoordinatorTest {

    private fun searchDecision() = TrustRoutingDecision(
        TrustRoute.SEARCH, TrustRoutingReason.CURRENT_INFORMATION_REQUIRED, emptySet(),
    )

    private fun localDecision() = TrustRoutingDecision(
        TrustRoute.LOCAL, TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE, emptySet(),
    )

    @Test
    fun `attempt failure yields typed retrieval failure with bound retry`() {
        val coordinator = TrustRecoveryCoordinator()
        val failure = coordinator.searchFailure(searchDecision(), attempted = false, usableResult = false, requestId = "r1")
        assertTrue(failure is TrustExecutionFailure.SearchRetrievalFailed)
        val recovery = (failure as TrustExecutionFailure.SearchRetrievalFailed).recovery
        assertTrue(recovery is TrustRecoveryAction.RetrySearch)
        assertEquals("r1", (recovery as TrustRecoveryAction.RetrySearch).requestId)
    }

    @Test
    fun `completed attempt without usable results yields no usable result type`() {
        val coordinator = TrustRecoveryCoordinator()
        val failure = coordinator.searchFailure(searchDecision(), attempted = true, usableResult = false, requestId = "r2")
        assertTrue(failure is TrustExecutionFailure.SearchNoUsableResult)
    }

    @Test
    fun `usable retrieval yields no failure state`() {
        val coordinator = TrustRecoveryCoordinator()
        assertNull(coordinator.searchFailure(searchDecision(), attempted = true, usableResult = true, requestId = "r3"))
    }

    @Test
    fun `non search routes never produce search failures`() {
        val coordinator = TrustRecoveryCoordinator()
        assertNull(coordinator.searchFailure(localDecision(), attempted = false, usableResult = false, requestId = "r4"))
        assertNull(coordinator.searchFailure(null, attempted = false, usableResult = false, requestId = "r5"))
    }

    @Test
    fun `failed retry keeps previous stable answer`() {
        val coordinator = TrustRecoveryCoordinator()
        assertEquals("stable", coordinator.resolveAfterRetry("stable", null, false))
        assertEquals("stable", coordinator.resolveAfterRetry("stable", "candidate", false))
        assertEquals("candidate", coordinator.resolveAfterRetry("stable", "candidate", true))
    }

    @Test
    fun `cloud failure is typed with explicit retry and no auto behavior`() {
        val coordinator = TrustRecoveryCoordinator()
        val failure = coordinator.cloudFailure("pending-1")
        assertTrue(failure.preservesLocalAnswer)
        assertFalse(failure.autoRetried)
        assertFalse(failure.switchedProvider)
        assertTrue(failure.recovery is TrustRecoveryAction.RetryCloud)
        assertEquals("pending-1", (failure.recovery as TrustRecoveryAction.RetryCloud).pendingEscalationKey)
    }

    @Test
    fun `retry gate is single flight`() {
        val coordinator = TrustRecoveryCoordinator()
        assertTrue(coordinator.tryBeginSearchRetry("r1"))
        assertFalse(coordinator.tryBeginSearchRetry("r1"))
        coordinator.endSearchRetry("r1")
        assertTrue(coordinator.tryBeginSearchRetry("r1"))
        coordinator.endSearchRetry("r1")
    }

    @Test
    fun `classifier internals never leak into user copy`() {
        val coordinator = TrustRecoveryCoordinator()
        assertFalse(coordinator.containsClassifierInternals(coordinator.classifierFallbackMessage(localDecision())))
        assertFalse(coordinator.containsClassifierInternals(coordinator.abstainNotice(TrustRoutingReason.FUTURE_UNKNOWABLE)))
        assertFalse(coordinator.isAbstainError())
        val sanitized = coordinator.sanitizeClassifierDiagnostics("TrustRoute.SEARCH failed\nat com.example.Foo.bar")
        assertFalse(coordinator.containsClassifierInternals(sanitized))
    }
}
