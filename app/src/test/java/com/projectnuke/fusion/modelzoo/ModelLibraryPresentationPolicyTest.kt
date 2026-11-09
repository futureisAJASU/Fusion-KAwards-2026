package com.projectnuke.fusion.modelzoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLibraryPresentationPolicyTest {
    private fun spec(
        availability: ModelAvailability = ModelAvailability.READY,
        recommendedRam: Int = 8,
        minRam: Int = 8,
        directUrl: String? = null,
        externallyReferenced: Boolean = false,
        copiedInternally: Boolean = false,
        parameterLabel: String = "1.3B",
        modelSizeEstimateGb: Float? = 1.3f,
    ) = FusionModelSpec(
        id = "model",
        displayName = "Test model",
        family = ModelFamily.QWEN,
        parameterLabel = parameterLabel,
        runtimeFormat = ModelRuntimeFormat.LITERT_LM,
        availability = availability,
        memoryClass = ModelMemoryClass.LOW,
        recommendedDeviceClass = ModelRecommendedDeviceClass.RAM_8GB_SAFE,
        recommendedMaxTokens8Gb = 1024,
        recommendedMaxTokens12Gb = 2048,
        recommendedMtpEnabled = false,
        recommendedReasoningEnabled = false,
        supportsVision = false,
        supportsReasoning = true,
        supportsNpuCandidate = false,
        notes = "short purpose",
        minRecommendedRamGb = minRam,
        recommendedRamGb = recommendedRam,
        directDownloadUrl = directUrl,
        modelSizeEstimateGb = modelSizeEstimateGb,
        externallyReferenced = externallyReferenced,
        copiedInternally = copiedInternally,
    )

    @Test fun `8GB small runnable model is recommended and usable`() {
        val result = presentModelForLibrary(spec(), 8L * GIB, false, true, false)
        assertEquals("내 기기에 추천", result.fitLabel)
        assertEquals(ModelLibraryPrimaryAction.USE, result.primaryAction)
    }

    @Test fun `heavy 12GB model on 8GB is not a false direct-use claim`() {
        val result = presentModelForLibrary(spec(recommendedRam = 12, minRam = 12), 8L * GIB, false, true, false)
        assertEquals(StableDeviceFit.NOT_RECOMMENDED, result.fit)
        assertNotEquals("내 기기에 추천", result.fitLabel)
        assertNotEquals(ModelLibraryPrimaryAction.USE, result.primaryAction)
    }

    @Test fun `availability labels and actions are student facing`() {
        val conversion = presentModelForLibrary(spec(ModelAvailability.NEEDS_CONVERSION), 8L * GIB, false, false, false)
        assertEquals("변환 필요", conversion.statusLabel)
        assertEquals(ModelLibraryPrimaryAction.PREPARE, conversion.primaryAction)
        val remote = presentModelForLibrary(spec(ModelAvailability.REMOTE_ONLY), 8L * GIB, false, false, false)
        assertEquals("원격 모델", remote.statusLabel)
        assertNotEquals(ModelLibraryPrimaryAction.USE, remote.primaryAction)
        assertEquals("모델 파일 필요", presentModelForLibrary(spec(ModelAvailability.NEEDS_DOWNLOAD), 8L * GIB, false, false, false).statusLabel)
        assertEquals("바로 사용 가능", presentModelForLibrary(spec(ModelAvailability.CUSTOM_IMPORTED), 8L * GIB, false, true, false).statusLabel)
        assertEquals("사용 중", presentModelForLibrary(spec(), 8L * GIB, false, true, true).statusLabel)
    }

    @Test fun `transient pressure warns without changing stable fit`() {
        val base = presentModelForLibrary(spec(), 8L * GIB, false, true, false)
        val again = presentModelForLibrary(spec(), 8L * GIB, false, true, false)
        assertEquals(base.fit, again.fit)
        assertTrue(liveMemoryPressureWarning(1L * GIB, false).orEmpty().contains("메모리 여유"))
        assertNull(liveMemoryPressureWarning(4L * GIB, false))
    }

    @Test fun `broken imported models keep truthful status and stay in My Models`() {
        val linked = spec(availability = ModelAvailability.CUSTOM_IMPORTED, externallyReferenced = true)
        val presentation = presentModelForLibrary(linked, 8L * GIB, false, false, false)
        assertEquals("다시 연결 필요", presentation.statusLabel)
        assertNotEquals(ModelLibraryPrimaryAction.USE, presentation.primaryAction)
        assertTrue(belongsInMyModels(linked, null, false))
    }

    @Test fun `custom imported runnable model is available`() {
        val imported = spec(availability = ModelAvailability.CUSTOM_IMPORTED, copiedInternally = true)
        val presentation = presentModelForLibrary(imported, 8L * GIB, false, true, false)
        assertEquals("바로 사용 가능", presentation.statusLabel)
        assertEquals(ModelLibraryPrimaryAction.USE, presentation.primaryAction)
    }

    @Test fun `stable fit supports 8 12 and 16GB classes`() {
        val sixteenGb = spec(recommendedRam = 16, minRam = 12)
        assertEquals(8, stableDeviceRamClassGb(8L * GIB, false))
        assertEquals(12, stableDeviceRamClassGb(12L * GIB, false))
        assertEquals(16, stableDeviceRamClassGb(16L * GIB, false))
        assertEquals(StableDeviceFit.NOT_RECOMMENDED, stableFit(sixteenGb, 8L * GIB, false))
        assertEquals(StableDeviceFit.MEMORY_HEADROOM_NEEDED, stableFit(sixteenGb, 12L * GIB, false))
        assertEquals(StableDeviceFit.RECOMMENDED, stableFit(sixteenGb, 16L * GIB, false))
    }

    @Test fun `metadata does not expose memory enum names`() {
        assertEquals("로컬 · 1.3B · 파일 약 1.3GB", modelLibraryMetadata(spec()))
        assertEquals("로컬", modelLibraryMetadata(spec(parameterLabel = "Custom", modelSizeEstimateGb = null)))
        assertEquals("Qwen", modelFamilyLabel(ModelFamily.QWEN))
        assertEquals("기타 / 사용자 모델", modelFamilyLabel(ModelFamily.CUSTOM))
    }

    @Test fun `current model is excluded from recommendation sections`() {
        val current = spec().copy(id = "current")
        val other = spec().copy(id = "other")
        assertEquals(listOf(other), excludeCurrentFromRecommendations(listOf(current, other), "current"))
    }

    @Test fun `broken current model is truthful and recoverable`() {
        val linked = spec(availability = ModelAvailability.CUSTOM_IMPORTED, externallyReferenced = true)
        val presentation = presentModelForLibrary(linked, 8L * GIB, false, false, true)
        assertEquals("현재 선택됨 · 모델 확인 필요", presentation.statusLabel)
        assertEquals(ModelLibraryPrimaryAction.DETAILS, presentation.primaryAction)
        assertTrue(presentation.primaryEnabled)
    }

    @Test fun `runnable current model still claims active use`() {
        val presentation = presentModelForLibrary(spec(), 8L * GIB, false, true, true)
        assertEquals("사용 중", presentation.statusLabel)
        assertEquals(ModelLibraryPrimaryAction.CURRENT, presentation.primaryAction)
        assertFalse(presentation.primaryEnabled)
    }

    @Test fun `broken imported model stays in My Models but is excluded from recommendations`() {
        val linked = spec(availability = ModelAvailability.CUSTOM_IMPORTED, externallyReferenced = true)
        assertTrue(belongsInMyModels(linked, null, false))
        assertFalse(eligibleForStudentRecommendation(linked, false, false))
    }

    @Test fun `runnable imported model may be recommended`() {
        val imported = spec(availability = ModelAvailability.CUSTOM_IMPORTED, copiedInternally = true)
        assertTrue(eligibleForStudentRecommendation(imported, true, false))
    }

    @Test fun `built-in downloadable model is not excluded solely for not being runnable`() {
        val downloadable = spec(availability = ModelAvailability.NEEDS_DOWNLOAD, directUrl = "https://example.test/model.bin")
        assertTrue(eligibleForStudentRecommendation(downloadable, false, false))
    }

    @Test fun `repository information URL cannot enable binary download action`() {
        val pageOnly = spec(availability = ModelAvailability.NEEDS_DOWNLOAD).copy(
            downloadUrl = "https://huggingface.co/org/model",
            modelPageUrl = "https://huggingface.co/org/model",
            directDownloadUrl = null,
        )
        val presentation = presentModelForLibrary(pageOnly, 8L * GIB, false, false, false)
        assertEquals(ModelLibraryPrimaryAction.PREPARE, presentation.primaryAction)
    }

    @Test fun `current model is never recommended`() {
        assertFalse(eligibleForStudentRecommendation(spec(), true, true))
    }

    private companion object { const val GIB = 1024L * 1024L * 1024L }
}
