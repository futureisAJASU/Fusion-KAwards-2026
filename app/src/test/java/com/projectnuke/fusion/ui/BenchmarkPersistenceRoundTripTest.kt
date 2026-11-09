package com.projectnuke.fusion.ui

import com.projectnuke.fusion.llm.FallbackReason
import com.projectnuke.fusion.llm.GenerationBenchmarkStats
import com.projectnuke.fusion.llm.MtpRuntimeStatus
import com.projectnuke.fusion.llm.ModelFingerprintSummary
import com.projectnuke.fusion.llm.RuntimeBackend
import com.projectnuke.fusion.llm.RuntimeComponentBackend
import com.projectnuke.fusion.llm.RuntimeExecutionSnapshot
import com.projectnuke.fusion.llm.RuntimeFallbackEvent
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.GenerationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7: persist the immutable RuntimeExecutionSnapshot and native
 * GenerationBenchmarkStats in the benchmark results schema so the persisted
 * state always matches the snapshot used by generation.
 */
class BenchmarkPersistenceRoundTripTest {

    private fun snapshot(
        mtpRequested: Boolean = true,
        mtpStatus: MtpRuntimeStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
        selectedText: RuntimeBackend = RuntimeBackend.CPU,
        selectedVision: RuntimeBackend? = null,
        samplerBackend: RuntimeComponentBackend = RuntimeComponentBackend.UNKNOWN,
        fallbackEvents: List<RuntimeFallbackEvent> = emptyList()
    ) = RuntimeExecutionSnapshot(
        requestedAccelerator = AcceleratorMode.AUTO,
        selectedTextBackend = selectedText,
        selectedVisionBackend = selectedVision,
        samplerBackend = samplerBackend,
        mtpRequested = mtpRequested,
        mtpStatus = mtpStatus,
        fallbackEvents = fallbackEvents,
        modelFingerprint = ModelFingerprintSummary(
            canonicalPath = "/m.part",
            fileSize = 1L,
            modifiedAt = 2L,
            validationVersion = 3,
            mtpSupported = true
        )
    )

    private fun benchmarkSnapshot(
        accelerator: AcceleratorMode = AcceleratorMode.AUTO,
        maxTokens: Int = 4000
    ) = BenchmarkSnapshot(
        modelName = "Gemma",
        modelPath = "/m.part",
        settings = GenerationSettings(
            maxTokens = maxTokens,
            accelerator = accelerator,
            speculativeDecodingEnabled = true
        ),
        requestedMaxTokens = maxTokens,
        requestedKvCapacity = maxTokens,
        safeMaxTokensCap = null,
        reasoningEnabled = false,
        webSearchEnabled = false
    )

    private fun nativeStats() = GenerationBenchmarkStats(
        initTimeSeconds = 1.1,
        timeToFirstTokenSeconds = 0.42,
        prefillTokenCount = 100,
        decodeTokenCount = 200,
        prefillTokensPerSecond = 1234.5,
        decodeTokensPerSecond = 67.89
    )

    @Test
    fun `round trip persists selected text backend and vision backend`() {
        val snap = snapshot(
            mtpStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
            selectedText = RuntimeBackend.CPU,
            selectedVision = null
        )
        val entity = buildBenchmarkResultEntityPayload(
            snapshot = benchmarkSnapshot(),
            mtpStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
            modelLoadingMs = 500L,
            firstTokenLatencyMs = 420L,
            totalGenerationMs = 5000L,
            estimatedOutputTokens = 100,
            totalTokensPerSecond = 20.0f,
            decodeTokensPerSecond = 25.0f,
            success = true,
            errorMessage = null,
            actualBackend = null,
            runtimeSnapshot = snap,
            nativeStats = nativeStats(),
            appVersion = "1.0.0-beta-stable",
            deviceModel = "TestDevice",
            androidVersion = "14",
            createdAtMs = 1700000000L
        )
        assertEquals("CPU", entity.selectedTextBackend)
        assertNull(entity.selectedVisionBackend)
        assertEquals("UNKNOWN", entity.samplerBackend)
        assertEquals(true, entity.mtpRequested)
        assertEquals("대체 비활성", entity.mtpStatus)
        assertEquals(false, entity.initializedWithMtp)
        assertEquals(0.42, entity.nativeTtftSeconds!!, 1e-6)
        assertEquals(1234.5, entity.nativePrefillTokensPerSecond!!, 1e-6)
        assertEquals(67.89, entity.nativeDecodeTokensPerSecond!!, 1e-6)
        assertEquals(100, entity.nativePrefillTokenCount)
        assertEquals(200, entity.nativeDecodeTokenCount)
        assertEquals(1.1, entity.nativeInitTimeSeconds!!, 1e-6)
    }

    @Test
    fun `fallback event codes are serialized as JSON array of stable enum names`() {
        val snap = snapshot(
            fallbackEvents = listOf(
                RuntimeFallbackEvent(
                    attemptedTextBackend = RuntimeBackend.GPU,
                    attemptedMtpEnabled = true,
                    reason = FallbackReason.MTP_ENGINE_INIT_FAILED
                ),
                RuntimeFallbackEvent(
                    attemptedTextBackend = RuntimeBackend.GPU,
                    attemptedMtpEnabled = false,
                    selectedReplacementBackend = RuntimeBackend.CPU,
                    reason = FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED
                )
            )
        )
        val entity = buildBenchmarkResultEntityPayload(
            snapshot = benchmarkSnapshot(),
            mtpStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
            modelLoadingMs = null,
            firstTokenLatencyMs = null,
            totalGenerationMs = 0L,
            estimatedOutputTokens = 0,
            totalTokensPerSecond = 0f,
            decodeTokensPerSecond = null,
            success = true,
            errorMessage = null,
            actualBackend = null,
            runtimeSnapshot = snap,
            nativeStats = null,
            appVersion = null,
            deviceModel = "",
            androidVersion = ""
        )
        val codes = entity.fallbackEventCodes!!
        // Repair Phase C2: fallbackEventCodes is a JSON array of stable
        // FallbackReason.name strings (machine-readable), never localized
        // Korean prose.
        val arr = org.json.JSONArray(codes)
        assertEquals(2, arr.length())
        assertEquals(FallbackReason.MTP_ENGINE_INIT_FAILED.name, arr.optString(0))
        assertEquals(FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED.name, arr.optString(1))
        // Sanity: no localized Korean prose leaked into the code field.
        assertFalse(codes.contains("엔진"))
        assertFalse(codes.contains("텍스트"))
    }

    @Test
    fun `legacy backend string is used when snapshot is null`() {
        val entity = buildBenchmarkResultEntityPayload(
            snapshot = benchmarkSnapshot(),
            mtpStatus = MtpRuntimeStatus.OFF,
            modelLoadingMs = null,
            firstTokenLatencyMs = null,
            totalGenerationMs = 0L,
            estimatedOutputTokens = 0,
            totalTokensPerSecond = 0f,
            decodeTokensPerSecond = null,
            success = false,
            errorMessage = "boom",
            actualBackend = "GPU",
            runtimeSnapshot = null,
            nativeStats = null,
            appVersion = null,
            deviceModel = "",
            androidVersion = ""
        )
        assertEquals("GPU", entity.selectedTextBackend)
        assertNull(entity.selectedVisionBackend)
        assertEquals("UNKNOWN", entity.samplerBackend)
        assertNull(entity.fallbackEventCodes)
        assertEquals(false, entity.initializedWithMtp)
    }

    @Test
    fun `INITIALIZED_WITH_MTP_REQUEST persists initializedWithMtp=true and sampler UNKNOWN`() {
        val snap = snapshot(
            mtpStatus = MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST,
            selectedText = RuntimeBackend.GPU,
            samplerBackend = RuntimeComponentBackend.UNKNOWN
        )
        val entity = buildBenchmarkResultEntityPayload(
            snapshot = benchmarkSnapshot(accelerator = AcceleratorMode.GPU),
            mtpStatus = MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST,
            modelLoadingMs = null,
            firstTokenLatencyMs = null,
            totalGenerationMs = 0L,
            estimatedOutputTokens = 0,
            totalTokensPerSecond = 0f,
            decodeTokensPerSecond = null,
            success = true,
            errorMessage = null,
            actualBackend = null,
            runtimeSnapshot = snap,
            nativeStats = null,
            appVersion = null,
            deviceModel = "",
            androidVersion = ""
        )
        assertTrue(entity.initializedWithMtp)
        assertEquals("GPU", entity.selectedTextBackend)
        assertEquals("UNKNOWN", entity.samplerBackend)
        // Native stats absent on this path.
        assertNull(entity.nativeTtftSeconds)
    }
}
