package com.projectnuke.fusion.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class RepairPhase1EventAccumulationTest {

    private fun event(reason: FallbackReason, backend: RuntimeBackend = RuntimeBackend.GPU, mtp: Boolean = false) =
        RuntimeFallbackEvent(attemptedTextBackend = backend, reason = reason, attemptedMtpEnabled = mtp)

    @Test
    fun `empty request-local events produce acquisition events only`() {
        val acquired = listOf(
            event(FallbackReason.MTP_ENGINE_INIT_FAILED, mtp = true),
            event(FallbackReason.BACKEND_ENGINE_INIT_FAILED, mtp = false)
        )
        val combined = combineFallbackEvents(acquired, emptyList())
        assertEquals(2, combined.size)
    }

    @Test
    fun `request with cooldown skip combined with stable acquisition events`() {
        val acquired = listOf(event(FallbackReason.MTP_ENGINE_INIT_FAILED, mtp = true))
        val local = listOf(event(FallbackReason.MTP_SKIPPED_RECENT_FAILURE, mtp = true))
        val combined = combineFallbackEvents(acquired, local)
        assertEquals(2, combined.size)
        assertEquals(FallbackReason.MTP_ENGINE_INIT_FAILED, combined[0].reason)
        assertEquals(FallbackReason.MTP_SKIPPED_RECENT_FAILURE, combined[1].reason)
    }

    @Test
    fun `later request without cooldown does not inherit earlier cooldown`() {
        val acquired = listOf(event(FallbackReason.MTP_ENGINE_INIT_FAILED, mtp = true))
        val localA = listOf(event(FallbackReason.MTP_SKIPPED_RECENT_FAILURE, mtp = true))
        val localB = emptyList<RuntimeFallbackEvent>()
        val snapshotA = combineFallbackEvents(acquired, localA)
        assertEquals(2, snapshotA.size)
        val snapshotB = combineFallbackEvents(acquired, localB)
        assertEquals("request B must not inherit A cooldown", 1, snapshotB.size)
    }

    @Test
    fun `repeated reuse preserves stable events and does not grow`() {
        val acquired = listOf(
            event(FallbackReason.MTP_ENGINE_INIT_FAILED, mtp = true),
            event(FallbackReason.BACKEND_ENGINE_INIT_FAILED, mtp = false),
            event(FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED, mtp = false)
        )
        val req1 = listOf(event(FallbackReason.MTP_SKIPPED_RECENT_FAILURE, mtp = true))
        val req2 = emptyList<RuntimeFallbackEvent>()
        val req3 = listOf(
            event(FallbackReason.MTP_SKIPPED_RECENT_FAILURE, mtp = true),
            event(FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE, mtp = false)
        )
        val req4 = emptyList<RuntimeFallbackEvent>()
        val s1 = combineFallbackEvents(acquired, req1)
        val s2 = combineFallbackEvents(acquired, req2)
        val s3 = combineFallbackEvents(acquired, req3)
        val s4 = combineFallbackEvents(acquired, req4)
        assertEquals(4, s1.size)
        assertEquals(3, s2.size)
        assertEquals(5, s3.size)
        assertEquals(3, s4.size)
        assertEquals(3, acquired.size)
    }

    @Test
    fun `consecutive duplicate events limited to at most 2 copies`() {
        val ev = event(FallbackReason.MTP_SKIPPED_RECENT_FAILURE, mtp = true)
        val result = combineFallbackEvents(emptyList(), listOf(ev, ev, ev, ev, ev))
        assertEquals(2, result.size)
    }

    @Test
    fun `different events are not deduplicated`() {
        val e1 = event(FallbackReason.MTP_UNSUPPORTED, mtp = true)
        val e2 = event(FallbackReason.MTP_ENGINE_INIT_FAILED, mtp = true)
        val e3 = event(FallbackReason.BACKEND_ENGINE_INIT_FAILED, mtp = false)
        val result = combineFallbackEvents(emptyList(), listOf(e1, e2, e3))
        assertEquals(3, result.size)
    }

    @Test
    fun `deduplication resets across different events`() {
        val a = event(FallbackReason.MTP_SKIPPED_RECENT_FAILURE, mtp = true)
        val b = event(FallbackReason.MTP_SKIPPED_RECENT_FAILURE, mtp = false)
        val c = event(FallbackReason.MTP_SKIPPED_RECENT_FAILURE, mtp = true)
        val result = combineFallbackEvents(emptyList(), listOf(a, a, a, b, c, c, c))
        assertEquals(5, result.size)
    }
}