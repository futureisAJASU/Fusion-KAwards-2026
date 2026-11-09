package com.projectnuke.fusion.model

import com.projectnuke.fusion.modelzoo.FusionModelSpec
import com.projectnuke.fusion.modelzoo.ModelAvailability
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.modelzoo.ModelMemoryClass
import com.projectnuke.fusion.modelzoo.ModelRecommendedDeviceClass
import com.projectnuke.fusion.modelzoo.ModelRuntimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleGenerationPresetPolicyTest {
    private val values = LocalSettingsEditValues(
        settings = GenerationSettings(maxTokens = 1024, temperature = 1f, topP = .95f, topK = 64, accelerator = AcceleratorMode.CPU, speculativeDecodingEnabled = true, reasoningBudgetTokens = 512),
        reasoningEnabled = false,
        contextMode = LocalContextMode.MANUAL,
        manualKvCapacityTokens = 8192,
        compactPolicy = LocalCompactPolicy(false, 75, 30),
    )

    @Test fun `style mappings are exact including DeepSeek`() {
        assertEquals(SimpleStyleValues(.35f, .85f, 32), simpleStyleValues(SimpleResponseStyle.CAREFUL, ModelFamily.QWEN))
        assertEquals(SimpleStyleValues(1f, .95f, 64), simpleStyleValues(SimpleResponseStyle.BALANCED, ModelFamily.QWEN))
        assertEquals(SimpleStyleValues(.8f, .95f, 64), simpleStyleValues(SimpleResponseStyle.BALANCED, ModelFamily.DEEPSEEK))
        assertEquals(SimpleStyleValues(1.2f, .98f, 96), simpleStyleValues(SimpleResponseStyle.CREATIVE, ModelFamily.QWEN))
        assertEquals(SimpleStyleValues(1f, .98f, 96), simpleStyleValues(SimpleResponseStyle.CREATIVE, ModelFamily.DEEPSEEK))
    }

    @Test fun `simple fields preserve unrelated expert settings`() {
        val careful = applySimpleStyle(values, SimpleResponseStyle.CAREFUL, ModelFamily.QWEN)
        assertEquals(.35f, careful.settings.temperature, 0f)
        assertEquals(AcceleratorMode.CPU, careful.settings.accelerator)
        assertEquals(true, careful.settings.speculativeDecodingEnabled)
        assertEquals(LocalContextMode.MANUAL, careful.contextMode)
        assertEquals(8192, careful.manualKvCapacityTokens)
        assertEquals(values.compactPolicy, careful.compactPolicy)
        assertEquals(2048, applySimpleLength(values, SimpleAnswerLength.DETAILED).settings.maxTokens)
        val reasoning = requireNotNull(applyReasoningEffort(values, ReasoningEffortPreset.MEDIUM))
        assertEquals(1024, reasoning.settings.reasoningBudgetTokens)
        assertTrue(reasoning.reasoningEnabled)
        assertEquals(values.settings.maxTokens, reasoning.settings.maxTokens)
    }

    @Test fun `arbitrary advanced values remain custom`() {
        val arbitrary = values.copy(settings = values.settings.copy(temperature = .73f, topP = .91f, topK = 53, maxTokens = 1536, reasoningBudgetTokens = 768), reasoningEnabled = true)
        assertEquals(SimpleResponseStyle.CUSTOM, detectSimpleStyle(arbitrary, ModelFamily.QWEN))
        assertEquals("사용자 설정 · 1536 토큰", simpleLengthLabel(arbitrary))
        assertEquals(ReasoningEffortSelection.CUSTOM, detectReasoningEffort(arbitrary))
    }

    @Test fun `unsupported simple length is disabled rather than silently clamped`() {
        assertFalse(simpleLengthAvailability(SimpleAnswerLength.DETAILED, 1024, values).enabled)
        assertTrue(simpleLengthAvailability(SimpleAnswerLength.NORMAL, 1024, values).enabled)
        assertFalse(simpleLengthAvailability(SimpleAnswerLength.VERY_DETAILED, 4096, values.copy(manualKvCapacityTokens = 2048)).enabled)
    }

    @Test fun `manual context reserves prompt headroom for output`() {
        val manual4096 = values.copy(manualKvCapacityTokens = 4096)
        assertTrue(simpleLengthAvailability(SimpleAnswerLength.DETAILED, 4096, manual4096).enabled)
        assertFalse(simpleLengthAvailability(SimpleAnswerLength.VERY_DETAILED, 4096, manual4096).enabled)
        assertTrue(simpleLengthAvailability(SimpleAnswerLength.VERY_DETAILED, 4096, manual4096.copy(manualKvCapacityTokens = 8192)).enabled)
    }

    @Test fun `Kanana summary omits unsupported thinking control`() {
        val kanana = FusionModelSpec(
            id = "kanana",
            displayName = "Kanana",
            family = ModelFamily.KANANA,
            parameterLabel = "1.3B",
            runtimeFormat = ModelRuntimeFormat.LITERT_LM,
            availability = ModelAvailability.READY,
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
        )
        assertEquals("균형 있게 · 보통", simpleResponseSummary(values, kanana))
    }

    @Test fun `simple accelerator writes the same profile truth as advanced`() {
        val gpu = applySimpleAccelerator(values, AcceleratorMode.GPU)
        val cpu = applySimpleAccelerator(values, AcceleratorMode.CPU)
        assertEquals(AcceleratorMode.GPU, gpu.settings.accelerator)
        assertEquals(AcceleratorMode.CPU, cpu.settings.accelerator)
        // The completed override is what the shared profile store persists,
        // identical to the Advanced Settings write path.
        assertEquals(AcceleratorMode.GPU, gpu.toCompleteLocalModelSettingsOverride().accelerator)
        assertEquals(AcceleratorMode.CPU, cpu.toCompleteLocalModelSettingsOverride().accelerator)
        // Unrelated simple fields are preserved.
        assertEquals(values.settings.maxTokens, gpu.settings.maxTokens)
        assertEquals(values.reasoningEnabled, gpu.reasoningEnabled)
    }

    @Test fun `recommended reasoning uses low and otherwise only turns reasoning off`() {
        val enabled = applyRecommendedSimpleReasoning(values, supportsReasoning = true, recommendedReasoningEnabled = true)
        assertTrue(enabled.reasoningEnabled)
        assertEquals(512, enabled.settings.reasoningBudgetTokens)
        assertEquals(values.settings.maxTokens, enabled.settings.maxTokens)

        val disabled = applyRecommendedSimpleReasoning(
            values.copy(reasoningEnabled = true, settings = values.settings.copy(reasoningBudgetTokens = 3072)),
            supportsReasoning = true,
            recommendedReasoningEnabled = false,
        )
        assertFalse(disabled.reasoningEnabled)
        assertEquals(3072, disabled.settings.reasoningBudgetTokens)
        assertEquals(values.settings.maxTokens, disabled.settings.maxTokens)
    }

    @Test fun `simple and advanced accelerator values share one store entry`() {
        val prefs = LocalModelSettingsProfileStoreTest.InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        val modelId = "accel-sync-model"

        // Simple write path (mirrors SimpleResponseSettingsScreen onWrite).
        assertTrue(
            writeSimpleModelOverrideFromLatest(
                store = store,
                modelId = modelId,
                readCurrent = { values },
                transform = { applySimpleAccelerator(it, AcceleratorMode.CPU) },
            ),
        )
        assertEquals(AcceleratorMode.CPU, store.read().modelOverrides[modelId]?.accelerator)

        // Advanced write path overwrites the same entry; Simple reads it back.
        assertTrue(
            store.writeModelOverride(
                modelId,
                values.toCompleteLocalModelSettingsOverride().copy(accelerator = AcceleratorMode.GPU),
            ),
        )
        val reread = store.read().modelOverrides[modelId]
        assertEquals(AcceleratorMode.GPU, reread?.accelerator)
        assertEquals(
            AcceleratorMode.GPU,
            LocalModelSettingsResolver.resolve(
                modelId = modelId,
                perModelOverride = reread,
            ).generationSettings.accelerator,
        )
    }

    @Test fun `cpu helper is family typed without filename checks`() {
        val kanana = FusionModelSpec(
            id = "kanana",
            displayName = "kanana-k0-control-dynamic.litertlm",
            family = ModelFamily.KANANA,
            parameterLabel = "1.3B",
            runtimeFormat = ModelRuntimeFormat.LITERT_LM,
            availability = ModelAvailability.READY,
            memoryClass = ModelMemoryClass.LOW,
            recommendedDeviceClass = ModelRecommendedDeviceClass.RAM_8GB_SAFE,
            recommendedMaxTokens8Gb = 1024,
            recommendedMaxTokens12Gb = 2048,
            recommendedMtpEnabled = false,
            recommendedReasoningEnabled = false,
            supportsVision = false,
            supportsReasoning = false,
            supportsNpuCandidate = true,
            notes = "test",
        )
        val gemma = kanana.copy(id = "gemma", displayName = "Gemma", family = ModelFamily.GEMMA)
        assertTrue(shouldRecommendCpuForCompatibility(kanana))
        assertFalse(shouldRecommendCpuForCompatibility(gemma))
        assertFalse(shouldRecommendCpuForCompatibility(null))
    }
}
