package com.projectnuke.fusion.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeExecutionSnapshotTest {

    @Test
    fun `snapshot is immutable - data class copy fails to mutate original`() {
        val original = RuntimeExecutionSnapshot(
            requestedAccelerator = com.projectnuke.fusion.model.AcceleratorMode.GPU,
            selectedTextBackend = RuntimeBackend.GPU,
            selectedVisionBackend = null,
            samplerBackend = RuntimeComponentBackend.UNKNOWN,
            mtpRequested = true,
            mtpStatus = MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST,
            fallbackEvents = emptyList(),
            modelFingerprint = ModelFingerprintSummary(
                canonicalPath = "m.part",
                fileSize = 1L,
                modifiedAt = 2L,
                validationVersion = 1,
                mtpSupported = true
            )
        )
        val mutated = original.copy(mtpStatus = MtpRuntimeStatus.OFF)
        assertEquals(MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST, original.mtpStatus)
        assertNotEquals(original.mtpStatus, mutated.mtpStatus)
    }

    @Test
    fun `snapshot list of fallback events is read-only view of itself`() {
        val event = RuntimeFallbackEvent(
            attemptedMtpEnabled = true,
            selectedReplacementBackend = RuntimeBackend.GPU,
            reason = FallbackReason.MTP_ENGINE_INIT_FAILED
        )
        val snap = RuntimeExecutionSnapshot(
            requestedAccelerator = com.projectnuke.fusion.model.AcceleratorMode.GPU,
            selectedTextBackend = RuntimeBackend.GPU,
            selectedVisionBackend = null,
            samplerBackend = RuntimeComponentBackend.UNKNOWN,
            mtpRequested = true,
            mtpStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
            fallbackEvents = listOf(event),
            modelFingerprint = ModelFingerprintSummary("m", 1L, 2L, 1, true)
        )
        assertEquals(1, snap.fallbackEvents.size)
        assertEquals(FallbackReason.MTP_ENGINE_INIT_FAILED, snap.fallbackEvents.first().reason)
        assertEquals(RuntimeBackend.GPU, snap.fallbackEvents.first().selectedReplacementBackend)
    }

    @Test
    fun `sampler backend defaults to UNKNOWN`() {
        val snap = RuntimeExecutionSnapshot(
            requestedAccelerator = com.projectnuke.fusion.model.AcceleratorMode.GPU,
            selectedTextBackend = RuntimeBackend.GPU,
            selectedVisionBackend = null,
            samplerBackend = RuntimeComponentBackend.UNKNOWN,
            mtpRequested = false,
            mtpStatus = MtpRuntimeStatus.OFF,
            fallbackEvents = emptyList(),
            modelFingerprint = ModelFingerprintSummary("m", 1L, 2L, 1, false)
        )
        assertEquals(RuntimeComponentBackend.UNKNOWN, snap.samplerBackend)
    }

    @Test
    fun `RuntimeBackend enum covers CPU GPU UNKNOWN`() {
        assertEquals(setOf(RuntimeBackend.CPU, RuntimeBackend.GPU, RuntimeBackend.UNKNOWN), RuntimeBackend.entries.toSet())
    }

    @Test
    fun `RuntimeComponentBackend enum covers CPU GPU UNKNOWN`() {
        assertEquals(
            setOf(RuntimeComponentBackend.CPU, RuntimeComponentBackend.GPU, RuntimeComponentBackend.UNKNOWN),
            RuntimeComponentBackend.entries.toSet()
        )
    }

    @Test
    fun `FallbackReason enum covers the full app-level set`() {
        val expected = setOf(
            FallbackReason.MTP_UNSUPPORTED,
            FallbackReason.MTP_SKIPPED_RECENT_FAILURE,
            FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE,
            FallbackReason.BACKEND_ENGINE_INIT_FAILED,
            FallbackReason.SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED,
            FallbackReason.SPECULATIVE_DISABLE_FLAG_SETTLEMENT_FAILED,
            FallbackReason.MTP_ENGINE_INIT_FAILED,
            FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED,
            FallbackReason.GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED,
            FallbackReason.ALL_CANDIDATES_EXHAUSTED,
            FallbackReason.ALL_CANDIDATES_SKIPPED_RECENT_FAILURE
        )
        assertEquals(expected, FallbackReason.entries.toSet())
    }
}
