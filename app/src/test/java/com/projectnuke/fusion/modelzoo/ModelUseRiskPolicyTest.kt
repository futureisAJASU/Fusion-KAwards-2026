package com.projectnuke.fusion.modelzoo

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.GenerationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUseRiskPolicyTest {
    private fun spec(
        availability: ModelAvailability = ModelAvailability.READY,
        recommendedRam: Int = 8,
        minRam: Int = 8,
        externallyReferenced: Boolean = false,
    ) = FusionModelSpec(
        id = "model",
        displayName = "Test model",
        family = ModelFamily.QWEN,
        parameterLabel = "1.3B",
        runtimeFormat = ModelRuntimeFormat.LITERT_LM,
        availability = availability,
        memoryClass = ModelMemoryClass.LOW,
        recommendedDeviceClass = ModelRecommendedDeviceClass.RAM_8GB_SAFE,
        recommendedMaxTokens8Gb = 1024,
        recommendedMaxTokens12Gb = 2048,
        recommendedMtpEnabled = false,
        recommendedReasoningEnabled = false,
        supportsVision = false,
        supportsReasoning = false,
        supportsNpuCandidate = false,
        notes = "test",
        minRecommendedRamGb = minRam,
        recommendedRamGb = recommendedRam,
        externallyReferenced = externallyReferenced,
    )

    @Test fun `small safe local model is direct`() {
        val decision = evaluateModelUseDecision(spec(), "other", true, 12L * GIB, 12L * GIB, false, false, 1024)
        assertEquals(ModelUseRisk.DIRECT, decision.risk)
    }

    @Test fun `memory headroom model requires confirmation`() {
        val decision = evaluateModelUseDecision(spec(recommendedRam = 16, minRam = 12), "other", true, 12L * GIB, 12L * GIB, false, false, 1024)
        assertEquals(ModelUseRisk.CAUTION, decision.risk)
    }

    @Test fun `not recommended model is blocked`() {
        val decision = evaluateModelUseDecision(spec(recommendedRam = 16, minRam = 12), "other", true, 8L * GIB, 8L * GIB, false, false, 1024)
        assertEquals(ModelUseRisk.BLOCKED, decision.risk)
    }

    @Test fun `broken imported link is blocked`() {
        val decision = evaluateModelUseDecision(
            spec(ModelAvailability.CUSTOM_IMPORTED, externallyReferenced = true),
            "other",
            false,
            8L * GIB,
            4L * GIB,
            false,
            false,
            1024,
        )
        assertEquals(ModelUseRisk.BLOCKED, decision.risk)
    }

    @Test fun `target preflight resolves target model identity, not current`() {
        val target = spec().copy(id = "target", localPath = "/models/target.bin")
        val queried = mutableListOf<Pair<String, String?>>()
        val resolved = resolveTargetPreflightMaxTokens(target) { modelId, localPath ->
            queried += modelId to localPath
            when (modelId) {
                "target" -> 4096
                else -> 1024
            }
        }
        assertEquals(4096, resolved)
        assertEquals(listOf("target" to "/models/target.bin"), queried)
    }

    @Test fun `preflight risk follows target effective maxTokens`() {
        val target = spec().copy(
            id = "target",
            recommendedRamGb = null,
            minRecommendedRamGb = null,
        )
        val light = FusionModelMemoryPreflight.evaluate(target, 12L * GIB, 12L * GIB, 1024, false)
        val heavy = FusionModelMemoryPreflight.evaluate(target, 12L * GIB, 12L * GIB, 8192, false)
        assertEquals(FusionModelMemoryRiskLevel.RECOMMENDED, light.level)
        assertTrue(heavy.level.rank > light.level.rank)
    }

    @Test
    fun `runtime admission exposes explicit choices and Kanana safe values`() {
        val kanana = spec().copy(
            family = ModelFamily.KANANA,
            fileSizeBytes = 1_315_000_000L,
            recommendedRamGb = 8,
        )
        val admission = evaluateFusionModelRuntimeAdmission(
            spec = kanana,
            totalRamBytes = 8L * GIB,
            availableRamBytes = 1L * GIB,
            lowMemory = false,
            maxOutputTokens = 4096,
            kvCapacityTokens = 4096,
            accelerator = AcceleratorMode.GPU,
            runtimeReusable = false,
        )

        assertTrue(admission.requiresDecision)
        assertEquals(1024, admission.safeMaxOutputTokens)
        assertEquals(2048, admission.safeKvCapacityTokens)
        assertTrue(FusionModelRuntimeAdmissionAction.USE_CPU in admission.actions)
        assertTrue(FusionModelRuntimeAdmissionAction.KEEP_CURRENT_SETTINGS in admission.actions)
        assertTrue(FusionModelRuntimeAdmissionAction.CANCEL in admission.actions)
    }

    @Test
    fun `reusable runtime bypasses fresh-engine admission dialog`() {
        val admission = evaluateFusionModelRuntimeAdmission(
            spec = spec(recommendedRam = 8).copy(fileSizeBytes = 1_315_000_000L),
            totalRamBytes = 8L * GIB,
            availableRamBytes = 1L * GIB,
            lowMemory = true,
            maxOutputTokens = 4096,
            kvCapacityTokens = 4096,
            accelerator = AcceleratorMode.GPU,
            runtimeReusable = true,
        )

        assertFalse(admission.requiresDecision)
        assertTrue(admission.actions.isEmpty())
    }

    @Test
    fun `accepted safe runtime profile is reused for same session profile until material conditions change`() {
        SessionSafeRuntimeOverrideStore.clear()
        val requested = GenerationSettings(maxTokens = 4096, kvCacheCapacityTokens = 4096)
        val key = SessionSafeRuntimeOverrideKey(
            modelIdentity = "gemma-e2b",
            modelPath = "model.bin",
            visionEnabled = false,
            requestedSettings = requested,
        )
        val safe = requested.copy(maxTokens = 768, kvCacheCapacityTokens = 2048, speculativeDecodingEnabled = false)
        SessionSafeRuntimeOverrideStore.accept(
            key, safe, FusionModelMemoryRiskLevel.CAUTION, lowMemory = false,
        )

        repeat(5) {
            assertEquals(
                safe,
                SessionSafeRuntimeOverrideStore.resolve(
                    key, FusionModelMemoryRiskLevel.CAUTION, lowMemory = false,
                ),
            )
        }
        assertEquals(
            null,
            SessionSafeRuntimeOverrideStore.resolve(
                key.copy(requestedSettings = requested.copy(maxTokens = 2048)),
                FusionModelMemoryRiskLevel.CAUTION,
                lowMemory = false,
            ),
        )
        SessionSafeRuntimeOverrideStore.accept(
            key, safe, FusionModelMemoryRiskLevel.CAUTION, lowMemory = false,
        )
        assertEquals(
            safe,
            SessionSafeRuntimeOverrideStore.resolve(
                key, FusionModelMemoryRiskLevel.CAUTION, lowMemory = false,
            ),
        )
        assertEquals(
            null,
            SessionSafeRuntimeOverrideStore.resolve(
                key, FusionModelMemoryRiskLevel.CAUTION, lowMemory = true,
            ),
        )
        SessionSafeRuntimeOverrideStore.accept(
            key, safe, FusionModelMemoryRiskLevel.CAUTION, lowMemory = false,
        )
        assertEquals(
            null,
            SessionSafeRuntimeOverrideStore.resolve(
                key, FusionModelMemoryRiskLevel.HEAVY, lowMemory = false,
            ),
        )
        SessionSafeRuntimeOverrideStore.clear()
    }

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
