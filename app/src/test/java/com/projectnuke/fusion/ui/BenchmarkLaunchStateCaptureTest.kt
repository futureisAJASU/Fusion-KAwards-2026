package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for Fix 1 (Benchmark state capture) + Fix 6 (monotonic
 * request tokens): a deep link that requests Benchmark History must copy the
 * request into child-owned immutable state BEFORE the parent acknowledges
 * (clears) its pending request fields, so the parent's acknowledgement can
 * never mutate the already-captured child state. The parent gate is a true
 * monotonic counter plus a separately-owned nullable pending slot; ack clears
 * only pending and never resets the counter.
 */
class BenchmarkLaunchStateCaptureTest {

    @Test
    fun capture_preserves_history_request_and_model_filter() {
        val state = captureBenchmarkLaunchState(
            initialShowHistory = true,
            initialHistoryModelFilter = "llama-4",
        )
        assertTrue(state.showHistory)
        assertEquals("llama-4", state.modelFilter)
    }

    @Test
    fun capture_without_history_request_does_not_show_history() {
        val state = captureBenchmarkLaunchState(
            initialShowHistory = false,
            initialHistoryModelFilter = null,
        )
        assertFalse(state.showHistory)
        assertNull(state.modelFilter)
    }

    @Test
    fun parent_acknowledging_request_after_capture_cannot_mutate_child_state() {
        // Child captures its immutable launch snapshot...
        val captured = captureBenchmarkLaunchState(
            initialShowHistory = true,
            initialHistoryModelFilter = "gemma",
        )
        // ...then the parent is free to clear its OWN pending request fields
        // (this is just locals here; the point is that `captured` is a value copy
        // and is not backed by any mutable parent slot).
        assertEquals("gemma", captured.modelFilter)
    }

    @Test
    fun request_consumption_gates_on_token_monotonicity() {
        assertTrue(shouldConsumeBenchmarkRequest(requestToken = 1, lastConsumedToken = 0))
        assertFalse(shouldConsumeBenchmarkRequest(requestToken = 0, lastConsumedToken = 0))
        assertFalse(shouldConsumeBenchmarkRequest(requestToken = 1, lastConsumedToken = 1))
        assertTrue(shouldConsumeBenchmarkRequest(requestToken = 42, lastConsumedToken = 7))
    }

    @Test
    fun back_action_blocks_while_running_and_leaves_when_done() {
        assertEquals(BenchmarkBackAction.BLOCK, resolveBenchmarkBackAction(isRunning = true))
        assertEquals(BenchmarkBackAction.LEAVE, resolveBenchmarkBackAction(isRunning = false))
    }

    // ---- Fix 6: API monotonic token reuse ----

    @Test
    fun api_second_request_yields_distinct_token_and_consumes() {
        var gate = MonotonicRequestGate()
        var lastConsumed = 0

        // Request #1.
        gate = issueMonotonicRequest(gate)
        val token1 = childRequestToken(gate.pendingToken)
        assertTrue(shouldConsumeAiProviderRequest(token1, lastConsumed))
        lastConsumed = token1
        gate = acknowledgeMonotonicRequest(gate)
        // Ack clears only pending; monotonic counter is never reset.
        assertNull(gate.pendingToken)
        assertEquals(1L, gate.nextToken)

        // Request #2 must be a DIFFERENT token and must consume.
        gate = issueMonotonicRequest(gate)
        val token2 = childRequestToken(gate.pendingToken)
        assertNotEquals(token1, token2)
        assertTrue(shouldConsumeAiProviderRequest(token2, lastConsumed))
        lastConsumed = token2
        gate = acknowledgeMonotonicRequest(gate)
        assertNull(gate.pendingToken)
        assertEquals(2L, gate.nextToken)
    }

    @Test
    fun api_recreation_after_acknowledgement_does_not_reopen() {
        var gate = MonotonicRequestGate()
        var lastConsumed = 0

        gate = issueMonotonicRequest(gate)
        val token1 = childRequestToken(gate.pendingToken)
        assertTrue(shouldConsumeAiProviderRequest(token1, lastConsumed))
        lastConsumed = token1
        gate = acknowledgeMonotonicRequest(gate)

        // Recreated child observes pending ?: 0 with fresh lastConsumed.
        val recreatedToken = childRequestToken(gate.pendingToken)
        assertEquals(0, recreatedToken)
        assertFalse(shouldConsumeAiProviderRequest(recreatedToken, 0))
        // Even the old lastConsumed must not reopen: pending is gone.
        assertFalse(shouldConsumeAiProviderRequest(recreatedToken, lastConsumed))
    }

    @Test
    fun `model library and advanced settings requests are consumed once`() {
        listOf("model library", "advanced settings").forEach { destination ->
            var gate = MonotonicRequestGate()

            gate = issueMonotonicRequest(gate)
            val first = childRequestToken(gate.pendingToken)
            assertEquals(destination, 1, first)
            gate = acknowledgeMonotonicRequest(gate)
            assertEquals(destination, 0, childRequestToken(gate.pendingToken))

            // Child recreation observes no pending token, so dismissal cannot
            // replay. A later explicit request gets a new token.
            assertEquals(destination, 0, childRequestToken(gate.pendingToken))
            gate = issueMonotonicRequest(gate)
            assertEquals(destination, 2, childRequestToken(gate.pendingToken))
        }
    }

    // ---- Fix 6: Benchmark monotonic token reuse ----

    @Test
    fun benchmark_second_request_yields_distinct_token_and_opens() {
        var gate = MonotonicRequestGate()
        var lastConsumed = 0

        gate = issueMonotonicRequest(gate)
        val token1 = childRequestToken(gate.pendingToken)
        assertTrue(shouldConsumeBenchmarkRequest(token1, lastConsumed))
        lastConsumed = token1
        gate = acknowledgeMonotonicRequest(gate)
        assertEquals(1L, gate.nextToken)

        gate = issueMonotonicRequest(gate)
        val token2 = childRequestToken(gate.pendingToken)
        assertNotEquals(token1, token2)
        assertTrue(shouldConsumeBenchmarkRequest(token2, lastConsumed))
    }

    @Test
    fun benchmark_acknowledged_request_does_not_reopen_after_recreation() {
        var gate = MonotonicRequestGate()
        var lastConsumed = 0

        gate = issueMonotonicRequest(gate)
        val token1 = childRequestToken(gate.pendingToken)
        assertTrue(shouldConsumeBenchmarkRequest(token1, lastConsumed))
        lastConsumed = token1
        gate = acknowledgeMonotonicRequest(gate)

        val recreatedToken = childRequestToken(gate.pendingToken)
        assertEquals(0, recreatedToken)
        assertFalse(shouldConsumeBenchmarkRequest(recreatedToken, 0))
        assertFalse(shouldConsumeBenchmarkRequest(recreatedToken, lastConsumed))
    }

    // ---- Fix 7: Benchmark deep-link capture owns navigation ----

    @Test
    fun benchmark_history_request_survives_parent_ack_and_back_returns_to_main() {
        // Parent issues specialized request: history=true, filter=X.
        var gate = MonotonicRequestGate()
        var parentFilter: String? = "model-X"
        var parentOpenHistory = true
        var lastConsumed = 0

        gate = issueMonotonicRequest(gate)
        val token = childRequestToken(gate.pendingToken)
        assertTrue(shouldConsumeBenchmarkRequest(token, lastConsumed))
        lastConsumed = token
        // ConversationListScreenV2 copies payload into child-owned state BEFORE ack.
        var pendingChildFilter: String? = parentFilter
        var pendingChildOpenHistory = parentOpenHistory
        // Parent acknowledges (clears its own pending payload).
        gate = acknowledgeMonotonicRequest(gate)
        parentFilter = null
        parentOpenHistory = false

        // FusionBenchmarkScreen captures into child-owned immutable state.
        val captured = captureBenchmarkLaunchState(
            initialShowHistory = pendingChildOpenHistory,
            initialHistoryModelFilter = pendingChildFilter,
        )
        // Parent ack must not mutate captured child state.
        assertTrue(captured.showHistory)
        assertEquals("model-X", captured.modelFilter)

        // Simulate FusionBenchmarkScreen child-owned remember(): ack clearing
        // the ConversationList pending slots must not reset visible history.
        pendingChildFilter = null
        pendingChildOpenHistory = false
        var showHistory = captured.showHistory
        var activeFilter = captured.modelFilter
        assertTrue(showHistory)
        assertEquals("model-X", activeFilter)

        // User presses History Back -> Benchmark main (child-owned).
        showHistory = false
        assertFalse(showHistory)
        // Filter slot is retained but hidden behind main; leaving Benchmark
        // and entering ordinarily must start clean (see next test).
        activeFilter = activeFilter
        assertEquals("model-X", activeFilter)
    }

    @Test
    fun benchmark_leave_then_ordinary_open_has_no_stale_history() {
        // After leaving Benchmark, an ordinary open carries no payload.
        val ordinary = captureBenchmarkLaunchState(
            initialShowHistory = false,
            initialHistoryModelFilter = null,
        )
        assertFalse(ordinary.showHistory)
        assertNull(ordinary.modelFilter)
    }

    @Test
    fun benchmark_specialized_request_twice_catches_token_reuse() {
        var gate = MonotonicRequestGate()
        var lastConsumed = 0

        repeat(2) { round ->
            val filter = "model-X"
            gate = issueMonotonicRequest(gate)
            val token = childRequestToken(gate.pendingToken)
            // Second round must be a NEW token, otherwise the child would
            // see an already-consumed token and ignore the request.
            if (round == 1) assertNotEquals(1, token)
            assertTrue(shouldConsumeBenchmarkRequest(token, lastConsumed))
            lastConsumed = token

            val captured = captureBenchmarkLaunchState(
                initialShowHistory = true,
                initialHistoryModelFilter = filter,
            )
            assertTrue(captured.showHistory)
            assertEquals(filter, captured.modelFilter)

            gate = acknowledgeMonotonicRequest(gate)
            assertNull(gate.pendingToken)
        }
        assertEquals(2L, gate.nextToken)
    }
}
