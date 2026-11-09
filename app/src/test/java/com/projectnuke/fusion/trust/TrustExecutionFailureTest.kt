package com.projectnuke.fusion.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustExecutionFailureTest {
    private fun searchDecision() = TrustRoutingDecision(
        route = TrustRoute.SEARCH,
        reason = TrustRoutingReason.CURRENT_INFORMATION_REQUIRED,
        signals = emptySet(),
    )

    @Test
    fun `search retrieval failure does not become silent local answer`() {
        val failure = TrustFailurePolicy.searchFailure(
            decision = searchDecision(),
            attempted = false,
            usableResult = false,
            requestId = "req-search-1",
        )
        assertTrue(failure is TrustExecutionFailure.SearchRetrievalFailed)
        assertTrue(failure.isErrorState)
        assertEquals(SearchVerificationState.SEARCH_ATTEMPTED, (failure as TrustExecutionFailure.SearchRetrievalFailed).verificationState)
        assertTrue(failure.recovery is TrustRecoveryAction.RetrySearch)
        // Failure copy never claims verification from route selection alone.
        assertFalse(failure.userMessage.contains("검증 완료"))
        assertFalse(failure.userMessage.contains("확인됨"))
    }

    @Test
    fun `search empty result is explicit failure with retry`() {
        val failure = TrustFailurePolicy.searchFailure(
            decision = searchDecision(),
            attempted = true,
            usableResult = false,
            requestId = "req-search-2",
        )
        assertTrue(failure is TrustExecutionFailure.SearchNoUsableResult)
        assertTrue(failure.isErrorState)
        assertTrue(failure.recovery is TrustRecoveryAction.RetrySearch)
        assertEquals("req-search-2", (failure.recovery as TrustRecoveryAction.RetrySearch).requestId)
    }

    @Test
    fun `search retry is explicit and single flight`() {
        val gate = TrustRetryGate()
        assertTrue(gate.tryEnter())
        // Concurrent retry while one is in flight is rejected.
        assertFalse(gate.tryEnter())
        gate.release()
        assertTrue(gate.tryEnter())
        gate.release()
    }

    @Test
    fun `failed retry leaves previous stable state intact`() {
        val stable = "이전 안정 답변"
        assertEquals(stable, TrustFailurePolicy.resolveAfterRetry(stable, null, false))
        assertEquals(stable, TrustFailurePolicy.resolveAfterRetry(stable, "", false))
        assertEquals(stable, TrustFailurePolicy.resolveAfterRetry(stable, "후보", false))
        assertEquals("새 답변", TrustFailurePolicy.resolveAfterRetry(stable, "새 답변", true))
    }

    @Test
    fun `classifier fallback remains deterministic and quiet`() {
        val local = TrustRoutingDecision(TrustRoute.LOCAL, TrustRoutingReason.UNCERTAIN_STORED_KNOWLEDGE, emptySet())
        val search = searchDecision()
        assertEquals("", TrustFailurePolicy.classifierFallbackMessage(local))
        assertFalse(TrustFailurePolicy.containsClassifierInternals(TrustFailurePolicy.classifierFallbackMessage(local)))
        assertFalse(TrustFailurePolicy.containsClassifierInternals(TrustFailurePolicy.classifierFallbackMessage(search)))
    }

    @Test
    fun `no raw classifier internals leak to user`() {
        val raw = "TrustRoute.SEARCH failed\nat com.example.Parser.parse\nclassifier prompt: LOCAL|STABLE\njava.lang.Exception: boom"
        val sanitized = TrustFailurePolicy.sanitizeClassifierDiagnostics(raw)
        assertFalse(TrustFailurePolicy.containsClassifierInternals(sanitized))
        assertFalse(sanitized.contains("at com."))
    }

    @Test
    fun `abstain is not treated as provider failure`() {
        assertFalse(TrustFailurePolicy.isAbstainError())
        val notice = TrustFailurePolicy.abstainNotice(TrustRoutingReason.FUTURE_UNKNOWABLE)
        assertTrue(notice.contains("미래"))
        assertFalse(notice.contains("실패", ignoreCase = true))
        assertFalse(notice.contains("Exception"))
    }

    @Test
    fun `cloud escalation failure preserves local answer`() {
        val failure = TrustFailurePolicy.cloudFailure("pending-1")
        assertTrue(failure.preservesLocalAnswer)
        assertFalse(failure.autoRetried)
        assertFalse(failure.switchedProvider)
        assertFalse(failure.reportedSuccess)
        assertFalse(failure.mutatedDefaultMode)
        assertTrue(failure.retry is TrustRecoveryAction.RetryCloud)

        val exec = TrustFailurePolicy.cloudFailureAsExecutionFailure("pending-1")
        assertTrue(exec is TrustExecutionFailure.CloudEscalationFailed)
        assertTrue((exec as TrustExecutionFailure.CloudEscalationFailed).preservesLocalAnswer)
        assertFalse(exec.autoRetried)
        assertFalse(exec.switchedProvider)
    }

    @Test
    fun `cloud retry requires another explicit user action`() {
        val first = TrustFailurePolicy.cloudFailure("pending-1")
        val retry = first.retry as TrustRecoveryAction.RetryCloud
        assertEquals("pending-1", retry.pendingEscalationKey)
        // No automatic retry: the failure itself never executes.
        assertFalse(first.autoRetried)
    }

    @Test
    fun `no automatic provider failover and no mode mutation`() {
        val failure = TrustFailurePolicy.cloudFailure("pending-1")
        assertFalse(failure.switchedProvider)
        assertFalse(failure.mutatedDefaultMode)
    }

    @Test
    fun `retry bounds are explicit`() {
        assertEquals(1, TrustFailureBounds.MAX_SEARCH_RETRY_ATTEMPTS)
        assertEquals(1, TrustFailureBounds.MAX_CLOUD_RETRY_ATTEMPTS)
    }
}
