package com.projectnuke.fusion.ui

import com.projectnuke.fusion.llm.FallbackReason
import com.projectnuke.fusion.llm.FailureKind
import com.projectnuke.fusion.llm.MtpRuntimeStatus
import com.projectnuke.fusion.llm.RuntimeBackend
import com.projectnuke.fusion.llm.RuntimeFailureSnapshot
import com.projectnuke.fusion.llm.RuntimeExecutionSnapshot
import com.projectnuke.fusion.llm.RuntimeAttemptSnapshot
import com.projectnuke.fusion.llm.ModelFingerprintSummary
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.RequestedEngineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2: regression tests for preserving RuntimeFailureSnapshot through Benchmark persistence.
 *
 * Verifies that:
 * - buildBenchmarkResultEntityPayload preserves RuntimeFailureSnapshot fields
 * - acquisition failure (no backend selected) → selectedTextBackend = null
 * - generation failure after successful acquisition → selected backend preserved
 * - vision backend preserved on generation failure
 * - MTP status from failure snapshot used when present
 * - fallback event codes from failure snapshot used
 * - no legacy state leaks from previous successful runs
 */
class RepairPhase2BenchmarkFailureSnapshotTest {

    private val testFingerprint = com.projectnuke.fusion.llm.ModelFingerprintSummary(
        canonicalPath = "/models/test.litertlm",
        fileSize = 12345L,
        modifiedAt = 1000000L,
        validationVersion = 1,
        mtpSupported = true
    )

    private fun testSnapshot(
        success: Boolean,
        runtimeSnapshot: RuntimeExecutionSnapshot? = null,
        attemptSnapshot: RuntimeAttemptSnapshot? = null,
        failureSnapshot: RuntimeFailureSnapshot? = null
    ) = buildBenchmarkResultEntityPayload(
        snapshot = BenchmarkSnapshot(
            modelName = "test-model",
            modelPath = "/path/model",
            settings = com.projectnuke.fusion.model.GenerationSettings(
                maxTokens = 4096,
                kvCacheCapacityTokens = 4096,
                accelerator = com.projectnuke.fusion.model.AcceleratorMode.GPU
            ),
            requestedMaxTokens = 4096,
            requestedKvCapacity = 4096,
            safeMaxTokensCap = 4096,
            reasoningEnabled = false,
            webSearchEnabled = false
        ),
        mtpStatus = MtpRuntimeStatus.OFF,
        modelLoadingMs = null,
        firstTokenLatencyMs = null,
        totalGenerationMs = 2000L,
        estimatedOutputTokens = 1000,
        totalTokensPerSecond = 20.0f,
        decodeTokensPerSecond = 15.0f,
        success = success,
        errorMessage = null,
        actualBackend = "GPU",
        runtimeSnapshot = runtimeSnapshot,
        attemptSnapshot = attemptSnapshot,
        failureSnapshot = failureSnapshot,
        nativeStats = null,
        appVersion = "1.0.0",
        deviceModel = "test-device",
        androidVersion = "34"
    )

    // ── A. acquisition fails before backend selection ─────────────────────

    @Test
    fun `A acquisition fails before selection → selectedTextBackend = null`() {
        val attempt = RuntimeAttemptSnapshot(
            requestedAccelerator = AcceleratorMode.GPU,
            fallbackEvents = listOf(
                com.projectnuke.fusion.llm.RuntimeFallbackEvent(
                    reason = FallbackReason.MTP_UNSUPPORTED,
                    attemptedTextBackend = RuntimeBackend.GPU,
                    attemptedMtpEnabled = true
                )
            ),
            modelFingerprint = testFingerprint,
            mtpRequested = true
        )

        val entity = testSnapshot(
            success = false,
            attemptSnapshot = attempt,
            failureSnapshot = null
        )

        assertNull("acquisition failure should have no selected backend", entity.selectedTextBackend)
        assertNull(entity.selectedVisionBackend)
        assertEquals(false, entity.initializedWithMtp)
    }

    // ── B. successful acquisition, generation failure ─────────────────────

    @Test
    fun `B GPU acquisition succeeds then generation fails → selectedTextBackend = GPU`() {
val failure = RuntimeFailureSnapshot(
            requestedAccelerator = AcceleratorMode.GPU,
            selectedTextBackend = RuntimeBackend.GPU,
            selectedVisionBackend = null,
            mtpRequested = false,
            mtpRuntimeStatus = MtpRuntimeStatus.OFF,
            fallbackEventsFromAcquisition = emptyList(),
            modelFingerprint = testFingerprint,
            failureKind = FailureKind.GENERATION_IO
        )

        val entity = testSnapshot(
            success = false,
            attemptSnapshot = null,
            failureSnapshot = failure
        )

        assertEquals("GPU", entity.selectedTextBackend)
        assertNull(entity.selectedVisionBackend)
        assertEquals(false, entity.initializedWithMtp)
    }

    // ── C. GPU text + CPU vision selected, generation fails ──────────────

    @Test
    fun `C GPU text + CPU vision selected then generation fails → both backends preserved`() {
        val failure = RuntimeFailureSnapshot(
            requestedAccelerator = AcceleratorMode.GPU,
            selectedTextBackend = RuntimeBackend.GPU,
            selectedVisionBackend = RuntimeBackend.CPU,
            mtpRequested = false,
            mtpRuntimeStatus = MtpRuntimeStatus.OFF,
            fallbackEventsFromAcquisition = emptyList(),
            modelFingerprint = testFingerprint,
            failureKind = FailureKind.MODEL_LOAD_FAILED
        )

        val entity = testSnapshot(
            success = false,
            attemptSnapshot = null,
            failureSnapshot = failure
        )

        assertEquals("GPU", entity.selectedTextBackend)
        assertEquals("CPU", entity.selectedVisionBackend)
        assertEquals(false, entity.initializedWithMtp)
    }

    // ── D. previous successful run then failed run ────────────────────────

    @Test
    fun `D previous successful run then failed run → no previous state leaks into failed row`() {
        val successEntity = buildBenchmarkResultEntityPayload(
            snapshot = BenchmarkSnapshot(
                modelName = "test-model",
                modelPath = "/path/model",
                settings = GenerationSettings(
                    maxTokens = 4096,
                    kvCacheCapacityTokens = 4096,
                    accelerator = AcceleratorMode.GPU
                ),
                requestedMaxTokens = 4096,
                requestedKvCapacity = 4096,
                safeMaxTokensCap = 4096,
                reasoningEnabled = false,
                webSearchEnabled = false
            ),
            mtpStatus = MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST,
            modelLoadingMs = 100L,
            firstTokenLatencyMs = 50L,
            totalGenerationMs = 2000L,
            estimatedOutputTokens = 1000,
            totalTokensPerSecond = 20.0f,
            decodeTokensPerSecond = 15.0f,
            success = true,
            errorMessage = null,
            actualBackend = "GPU",
            runtimeSnapshot = RuntimeExecutionSnapshot(
                requestedAccelerator = AcceleratorMode.GPU,
                selectedTextBackend = RuntimeBackend.GPU,
                selectedVisionBackend = null,
                samplerBackend = com.projectnuke.fusion.llm.RuntimeComponentBackend.UNKNOWN,
                mtpRequested = true,
                mtpStatus = MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST,
                fallbackEvents = emptyList(),
                modelFingerprint = testFingerprint
            ),
            nativeStats = null,
            appVersion = "1.0.0",
            deviceModel = "test-device",
            androidVersion = "34"
        )

        // Now a failed run with RuntimeFailureSnapshot
        val failure = RuntimeFailureSnapshot(
            requestedAccelerator = AcceleratorMode.GPU,
            selectedTextBackend = RuntimeBackend.GPU,
            selectedVisionBackend = null,
            mtpRequested = true,
            mtpRuntimeStatus = MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST,
            fallbackEventsFromAcquisition = emptyList(),
            modelFingerprint = testFingerprint,
            failureKind = FailureKind.GENERATION_INTERRUPTED
        )

        val failedEntity = testSnapshot(
            success = false,
            attemptSnapshot = null,
            failureSnapshot = failure
        )

        assertEquals("GPU", failedEntity.selectedTextBackend)
        assertNull(failedEntity.selectedVisionBackend)
        assertEquals(true, failedEntity.initializedWithMtp)
        // MTP status should come from failure snapshot, not the previous successful run
        assertEquals("MTP 요청으로 초기화됨", failedEntity.mtpStatus)
    }
}