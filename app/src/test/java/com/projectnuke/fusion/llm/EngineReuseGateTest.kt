package com.projectnuke.fusion.llm

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.RequestedEngineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reuse-gate contract for [isLoadedKeyReusableFor]: every engine-build input
 * in the requested profile participates, so a `true` answer means no cold
 * build. In particular an MTP toggle must report "not reusable", otherwise
 * warm-only background work (dynamic follow-ups) or reuse-assuming RAM
 * admission would trigger a fresh Engine build under the runtime lock.
 */
class EngineReuseGateTest {
    private fun fingerprint(mtpSupported: Boolean = true) = ModelFingerprint(
        canonicalPath = "/models/test.litertlm",
        fileSize = 1_234L,
        modifiedAt = 5_678L,
        validationVersion = 1,
        mtpSupported = mtpSupported,
    )

    private fun profile(
        mtpRequested: Boolean = false,
        accelerator: AcceleratorMode = AcceleratorMode.GPU,
        kvCacheCapacityTokens: Int = 4096,
        enableVisionBackend: Boolean = false,
        modelIdentity: String? = null,
        cpuThreadCount: Int = 0,
    ) = RequestedEngineProfile(
        modelPath = "/models/test.litertlm",
        accelerator = accelerator,
        mtpRequested = mtpRequested,
        kvCacheCapacityTokens = kvCacheCapacityTokens,
        enableVisionBackend = enableVisionBackend,
        modelIdentity = modelIdentity,
        cpuThreadCount = cpuThreadCount,
    )

    private fun key(
        fingerprint: ModelFingerprint,
        mtpEnabled: Boolean = false,
        accelerator: AcceleratorMode = AcceleratorMode.GPU,
        kvCacheCapacityTokens: Int = 4096,
        enableVisionBackend: Boolean = false,
        selectedBackend: String = "GPU",
        cpuThreadCount: Int = 0,
        selectedVisionBackend: String? = null,
    ) = EngineRuntimeKey(
        fingerprint = fingerprint,
        accelerator = accelerator,
        kvCacheCapacityTokens = kvCacheCapacityTokens,
        enableVisionBackend = enableVisionBackend,
        mtpEnabled = mtpEnabled,
        selectedBackend = selectedBackend,
        cpuThreadCount = cpuThreadCount,
        selectedVisionBackend = selectedVisionBackend,
    )

    @Test
    fun `exact match without mtp is reusable`() {
        val fp = fingerprint()
        assertTrue(isLoadedKeyReusableFor(key(fp), profile(), fp))
    }

    @Test
    fun `mtp match is reusable`() {
        val fp = fingerprint(mtpSupported = true)
        assertTrue(isLoadedKeyReusableFor(key(fp, mtpEnabled = true), profile(mtpRequested = true), fp))
    }

    @Test
    fun `enabling mtp over a plain engine is not reusable`() {
        val fp = fingerprint(mtpSupported = true)
        assertFalse(isLoadedKeyReusableFor(key(fp, mtpEnabled = false), profile(mtpRequested = true), fp))
    }

    @Test
    fun `disabling mtp over an mtp engine is not reusable`() {
        val fp = fingerprint(mtpSupported = true)
        assertFalse(isLoadedKeyReusableFor(key(fp, mtpEnabled = true), profile(mtpRequested = false), fp))
    }

    @Test
    fun `mtp requested but unsupported model still reuses plain engine`() {
        val fp = fingerprint(mtpSupported = false)
        assertTrue(isLoadedKeyReusableFor(key(fp, mtpEnabled = false), profile(mtpRequested = true), fp))
    }

    @Test
    fun `accelerator kv vision fingerprint mismatches are not reusable`() {
        val fp = fingerprint()
        assertFalse(isLoadedKeyReusableFor(key(fp, accelerator = AcceleratorMode.CPU), profile(), fp))
        assertFalse(isLoadedKeyReusableFor(key(fp, kvCacheCapacityTokens = 2048), profile(), fp))
        assertFalse(isLoadedKeyReusableFor(key(fp, enableVisionBackend = true), profile(), fp))
        assertFalse(isLoadedKeyReusableFor(key(fp.copy(fileSize = 9_999L)), profile(), fp))
    }

    @Test
    fun `model identity alone never forces a rebuild`() {
        val fp = fingerprint()
        assertTrue(
            isLoadedKeyReusableFor(
                key(fp),
                profile(modelIdentity = "kanana-2.1-q4"),
                fp,
            ),
        )
    }

    @Test
    fun `cpu engine built with one thread is not reused for two threads`() {
        val fp = fingerprint()
        val oneThreadCpu = key(
            fp,
            accelerator = AcceleratorMode.CPU,
            selectedBackend = "CPU",
            cpuThreadCount = 1,
        )
        assertFalse(isLoadedKeyReusableFor(
            oneThreadCpu,
            profile(accelerator = AcceleratorMode.CPU, cpuThreadCount = 2),
            fp,
        ))
        assertTrue(isLoadedKeyReusableFor(
            oneThreadCpu,
            profile(accelerator = AcceleratorMode.CPU, cpuThreadCount = 1),
            fp,
        ))
    }

    @Test
    fun `gpu engine reuse ignores a changed inactive cpu fallback budget`() {
        val fp = fingerprint()
        assertTrue(isLoadedKeyReusableFor(
            key(fp, selectedBackend = "GPU", cpuThreadCount = 0),
            profile(accelerator = AcceleratorMode.GPU, cpuThreadCount = 4),
            fp,
        ))
    }

    @Test
    fun `gpu text engine with active cpu vision fallback tracks its cpu thread budget`() {
        val fp = fingerprint()
        val gpuTextCpuVision = key(
            fp,
            selectedBackend = "GPU",
            selectedVisionBackend = "CPU",
            cpuThreadCount = 2,
        )
        assertFalse(isLoadedKeyReusableFor(
            gpuTextCpuVision,
            profile(accelerator = AcceleratorMode.GPU, cpuThreadCount = 4),
            fp,
        ))
        assertTrue(isLoadedKeyReusableFor(
            gpuTextCpuVision,
            profile(accelerator = AcceleratorMode.GPU, cpuThreadCount = 2),
            fp,
        ))
    }

    @Test
    fun `candidate reuse invalidates active cpu vision but preserves gpu only runtime`() {
        val fp = fingerprint()
        fun loaded(visionBackend: String, cpuThreads: Int) = LoadedRuntimeState(
            engine = "loaded",
            key = key(
                fp,
                enableVisionBackend = true,
                selectedBackend = "GPU",
                selectedVisionBackend = visionBackend,
                cpuThreadCount = cpuThreads,
            ),
            mtpStatus = MtpRuntimeStatus.OFF,
            runtimeSelection = EngineSelectionRuntime(
                requestedAccelerator = AcceleratorMode.GPU.name,
                actualTextBackend = "GPU",
                actualVisionBackend = visionBackend,
                requestedMtp = false,
                initializedWithMtp = false,
                fallbackReason = null,
            ),
            actualTextBackend = "GPU",
            actualVisionBackend = visionBackend,
        )

        var activeCpuVisionAttempts = 0
        val activeCpuVision = selectFirstWorkingEngine(
            ladder = listOf(EngineCandidate("GPU", mtpEnabled = false)),
            enableVisionBackend = true,
            configureFlag = { true },
            tryCreate = { _, _, _ ->
                activeCpuVisionAttempts++
                EngineCandidateAttempt.Success("fresh")
            },
            loadedState = loaded("CPU", 2),
            fingerprint = fp,
            accelerator = AcceleratorMode.GPU,
            kvCacheCapacityTokens = 4096,
            enableVisionBackendProfile = true,
            profile = profile(accelerator = AcceleratorMode.GPU, enableVisionBackend = true, cpuThreadCount = 4),
        )
        assertFalse(activeCpuVision.reusedLoadedEngine)
        assertEquals(1, activeCpuVisionAttempts)

        var gpuOnlyAttempts = 0
        val gpuOnly = selectFirstWorkingEngine(
            ladder = listOf(EngineCandidate("GPU", mtpEnabled = false)),
            enableVisionBackend = false,
            configureFlag = { true },
            tryCreate = { _, _, _ ->
                gpuOnlyAttempts++
                EngineCandidateAttempt.Success("fresh")
            },
            loadedState = loaded("GPU", 0),
            fingerprint = fp,
            accelerator = AcceleratorMode.GPU,
            kvCacheCapacityTokens = 4096,
            enableVisionBackendProfile = true,
            profile = profile(accelerator = AcceleratorMode.GPU, enableVisionBackend = true, cpuThreadCount = 8),
        )
        assertTrue(gpuOnly.reusedLoadedEngine)
        assertEquals(0, gpuOnlyAttempts)
    }
}
