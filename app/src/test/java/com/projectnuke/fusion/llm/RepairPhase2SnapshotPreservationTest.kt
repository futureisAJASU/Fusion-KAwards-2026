package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.AcceleratorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 2: snapshot-preservation regression tests.
 *
 * Verifies that:
 *  - RuntimeAttemptSnapshot.inferredMtpStatus() uses correct terminal-precedence
 *  - BenchmarkFailedException preserves both attemptSnapshot and failureSnapshot
 */
class RepairPhase2SnapshotPreservationTest {

    private val testFingerprint = ModelFingerprintSummary(
        canonicalPath = "/models/test.litertlm",
        fileSize = 12345L,
        modifiedAt = 1000000L,
        validationVersion = 1,
        mtpSupported = true
    )

    private val realFingerprint = ModelFingerprintSummary(
        canonicalPath = "/models/real.litertlm",
        fileSize = 99999L,
        modifiedAt = 2000000L,
        validationVersion = 2,
        mtpSupported = true
    )

    // ── inferredMtpStatus precedence ─────────────────────────────────────

    @Test
    fun `ALL_CANDIDATES_EXHAUSTED dominates MTP_ENGINE_INIT_FAILED`() {
        val snap = RuntimeAttemptSnapshot(
            requestedAccelerator = AcceleratorMode.GPU,
            fallbackEvents = listOf(
                RuntimeFallbackEvent(reason = FallbackReason.MTP_ENGINE_INIT_FAILED,
                    attemptedTextBackend = RuntimeBackend.GPU, attemptedMtpEnabled = true),
                RuntimeFallbackEvent(reason = FallbackReason.BACKEND_ENGINE_INIT_FAILED,
                    attemptedTextBackend = RuntimeBackend.GPU, attemptedMtpEnabled = false),
                RuntimeFallbackEvent(reason = FallbackReason.ALL_CANDIDATES_EXHAUSTED)
            ),
            modelFingerprint = testFingerprint,
            mtpRequested = true
        )
        assertEquals(
            "terminal ALL_CANDIDATES_EXHAUSTED should dominate MTP_ENGINE_INIT_FAILED",
            MtpRuntimeStatus.FAILED,
            snap.inferredMtpStatus()
        )
    }

    @Test
    fun `ALL_CANDIDATES_SKIPPED_RECENT_FAILURE dominates MTP_SKIPPED_RECENT_FAILURE`() {
        val snap = RuntimeAttemptSnapshot(
            requestedAccelerator = AcceleratorMode.GPU,
            fallbackEvents = listOf(
                RuntimeFallbackEvent(reason = FallbackReason.MTP_SKIPPED_RECENT_FAILURE,
                    attemptedTextBackend = RuntimeBackend.GPU, attemptedMtpEnabled = true),
                RuntimeFallbackEvent(reason = FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE,
                    attemptedTextBackend = RuntimeBackend.GPU, attemptedMtpEnabled = false),
                RuntimeFallbackEvent(reason = FallbackReason.ALL_CANDIDATES_SKIPPED_RECENT_FAILURE)
            ),
            modelFingerprint = testFingerprint,
            mtpRequested = true
        )
        assertEquals(
            "ALL_CANDIDATES_SKIPPED should dominate per-candidate skips",
            MtpRuntimeStatus.FAILED,
            snap.inferredMtpStatus()
        )
    }

    // ── BenchmarkFailedException carries both snapshots ─────────────────

    @Test
    fun `BenchmarkFailedException preserves attempt and failure snapshots`() {
        val attempt = RuntimeAttemptSnapshot(
            requestedAccelerator = AcceleratorMode.CPU,
            fallbackEvents = listOf(RuntimeFallbackEvent(reason = FallbackReason.BACKEND_ENGINE_INIT_FAILED,
                attemptedTextBackend = RuntimeBackend.CPU, attemptedMtpEnabled = false)),
            modelFingerprint = testFingerprint,
            mtpRequested = false
        )
        val failure = RuntimeFailureSnapshot(
            requestedAccelerator = AcceleratorMode.GPU,
            selectedTextBackend = RuntimeBackend.GPU,
            selectedVisionBackend = null,
            mtpRequested = true,
            mtpRuntimeStatus = MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST,
            fallbackEventsFromAcquisition = emptyList(),
            modelFingerprint = realFingerprint,
            failureKind = FailureKind.GENERATION_INTERRUPTED
        )
        val exc = com.projectnuke.fusion.ui.BenchmarkFailedException(
            reason = "test",
            attemptSnapshot = attempt,
            failureSnapshot = failure
        )
        assertEquals(attempt, exc.attemptSnapshot)
        assertEquals(failure, exc.failureSnapshot)
    }

    @Test
    fun `BenchmarkFailedException supports null snapshots for legacy paths`() {
        val exc = com.projectnuke.fusion.ui.BenchmarkFailedException("legacy")
        assertNull(exc.attemptSnapshot)
        assertNull(exc.failureSnapshot)
    }
}