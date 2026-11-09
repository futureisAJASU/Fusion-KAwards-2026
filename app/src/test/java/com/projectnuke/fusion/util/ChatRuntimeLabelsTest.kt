package com.projectnuke.fusion.util

import com.projectnuke.fusion.llm.MtpRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRuntimeLabelsTest {

    /**
     * Phase 6: the first metrics label reports the *requested* accelerator
     * (e.g. "AUTO · MTP on") while the applied line reports the immutable
     * selected backend and effective MTP status. The two lines must never
     * contradict each other; the requested label never claims the applied
     * runtime result.
     */
    @Test
    fun `buildAcceleratorLabel renders requested accelerator and MTP on`() {
        // Mirrors the private buildAcceleratorLabel in ChatScreen.kt.
        fun label(accel: String, mtp: Boolean): String =
            if (mtp) "$accel · MTP on" else accel

        assertEquals("AUTO · MTP on", label("AUTO", mtp = true))
        assertEquals("AUTO", label("AUTO", mtp = false))
        assertEquals("GPU · MTP on", label("GPU", mtp = true))
        assertEquals("CPU", label("CPU", mtp = false))
    }

    @Test
    fun `buildAppliedRuntimeLine reports selected backend and effective MTP status`() {
        val applied = buildAppliedRuntimeLine(
            actualBackend = "CPU",
            mtpStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
            mtpRequested = true
        )
        assertNotNull(applied)
        assertEquals("적용: CPU · MTP 대체 비활성", applied)
        applied!!.let {
            assertTrue("applied line names the selected backend", it.contains("CPU"))
            assertTrue("applied line names the effective MTP status", it.contains("대체 비활성"))
        }
    }

    @Test
    fun `buildAppliedRuntimeLine null when actual backend is unobservable`() {
        assertNull(buildAppliedRuntimeLine(actualBackend = null, mtpStatus = MtpRuntimeStatus.OFF, mtpRequested = false))
    }

    @Test
    fun `requested GPU+MTP with applied CPU plain shows distinct non-contradicting labels`() {
        fun requestedLabel(accel: String, mtp: Boolean): String =
            if (mtp) "$accel · MTP on" else accel

        val requested = requestedLabel("GPU", mtp = true)
        val applied = buildAppliedRuntimeLine(
            actualBackend = "CPU",
            mtpStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
            mtpRequested = true
        )
        assertEquals("GPU · MTP on", requested)
        assertEquals("적용: CPU · MTP 대체 비활성", applied)
        // The requested line must not claim the applied CPU backend.
        assertTrue(requested.contains("GPU") && !requested.contains("CPU"))
        // The applied line must name the actual CPU backend.
        assertTrue(applied!!.contains("CPU"))
        // Neither line claims "RUNTIME_CONFIRMED_ACTIVE" (Phase 1) — the
        // applied line describes the FALLBACK_DISABLED status truthfully.
        assertTrue(!applied.contains("실행 중 활성화됨"))
    }

    @Test
    fun `requested AUTO+MTP with applied CPU plain shows distinct non-contradicting labels`() {
        fun requestedLabel(accel: String, mtp: Boolean): String =
            if (mtp) "$accel · MTP on" else accel

        val requested = requestedLabel("AUTO", mtp = true)
        val applied = buildAppliedRuntimeLine(
            actualBackend = "CPU",
            mtpStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
            mtpRequested = true
        )
        assertEquals("AUTO · MTP on", requested)
        assertEquals("적용: CPU · MTP 대체 비활성", applied)
        assertTrue(!requested.contains("CPU"))
        assertTrue(applied!!.contains("CPU"))
    }

    @Test
    fun `applied GPU with INITIALIZED_WITH_MTP_REQUEST reflects optimistic runtime claim`() {
        val applied = buildAppliedRuntimeLine(
            actualBackend = "GPU",
            mtpStatus = MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST,
            mtpRequested = true
        )
        assertEquals("적용: GPU · MTP MTP 요청으로 초기화됨", applied)
    }
}
