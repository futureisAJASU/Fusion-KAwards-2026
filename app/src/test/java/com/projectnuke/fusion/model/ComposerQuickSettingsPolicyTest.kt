package com.projectnuke.fusion.model

import com.projectnuke.fusion.modelzoo.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerQuickSettingsPolicyTest {
    private fun editValues(): LocalSettingsEditValues = LocalSettingsEditValues(
        settings = GenerationSettings(),
        reasoningEnabled = false,
        contextMode = LocalContextMode.AUTO,
        manualKvCapacityTokens = 4096,
        compactPolicy = LocalCompactPolicy(),
    )

    @Test
    fun answerStyleLabelsMatchComposerContract() {
        assertEquals(
            listOf("공부", "균형", "아이디어"),
            ComposerQuickSettingsPolicy.answerStyles.map { it.label },
        )
        assertEquals(
            "답변 스타일은 표현의 다양성에 영향을 줍니다. 사실성이나 지능을 보장하지 않습니다.",
            ComposerQuickSettingsPolicy.STYLE_HELPER_TEXT,
        )
    }

    @Test
    fun answerStyleSamplingPresetsPreserveGeneralAndDeepSeekTruth() {
        assertEquals(
            ComposerQuickSamplingPreset(0.35f, 0.85f, 32),
            ComposerQuickSettingsPolicy.samplingPreset(ComposerQuickAnswerStyle.CAREFUL, "qwen"),
        )
        assertEquals(
            ComposerQuickSamplingPreset(1.00f, 0.95f, 64),
            ComposerQuickSettingsPolicy.samplingPreset(ComposerQuickAnswerStyle.BALANCED, "qwen"),
        )
        assertEquals(
            ComposerQuickSamplingPreset(0.80f, 0.95f, 64),
            ComposerQuickSettingsPolicy.samplingPreset(ComposerQuickAnswerStyle.BALANCED, "deepseek"),
        )
        assertEquals(
            ComposerQuickSamplingPreset(1.20f, 0.98f, 96),
            ComposerQuickSettingsPolicy.samplingPreset(ComposerQuickAnswerStyle.CREATIVE, "qwen"),
        )
        assertEquals(
            ComposerQuickSamplingPreset(1.00f, 0.98f, 96),
            ComposerQuickSettingsPolicy.samplingPreset(ComposerQuickAnswerStyle.CREATIVE, "DeepSeek-R1"),
        )
    }

    @Test
    fun outputLengthOptionsAreExactAndDisableUnsupportedWithoutClamp() {
        assertEquals(
            listOf(512, 1024, 2048, 4096),
            ComposerQuickSettingsPolicy.outputLengths.map { it.tokens },
        )
        assertEquals(
            listOf("짧게", "보통", "자세히", "매우 자세히"),
            ComposerQuickSettingsPolicy.outputLengths.map { it.label },
        )

        val options = ComposerQuickSettingsPolicy.outputLengthOptions(maxSupportedOutputTokens = 2048)
        assertEquals(listOf(true, true, true, false), options.map { it.enabled })
        assertNull(
            ComposerQuickSettingsPolicy.applyOutputLength(
                current = editValues(),
                length = ComposerQuickOutputLength.VERY_DETAILED,
                maxSupportedOutputTokens = 2048,
            ),
        )
    }

    @Test
    fun reasoningEffortPresetsUseSharedLabelsAndBudgets() {
        assertEquals(
            listOf("낮음", "중간", "높음", "매우 높음"),
            ReasoningEffortPreset.entries.map { it.label },
        )
        assertEquals(
            listOf(512, 1024, 2048, 4096),
            ReasoningEffortPreset.entries.map { it.budgetTokens },
        )
        assertEquals(ReasoningEffortPreset.entries.sortedBy { it.budgetTokens }, ReasoningEffortPreset.entries)
    }

    @Test
    fun reasoningCapabilityIsIndependentOfCurrentEnabledState() {
        val qwen = com.projectnuke.fusion.modelzoo.FusionModelCatalog.builtIn.first {
            it.family == ModelFamily.QWEN
        }
        val deepSeek = com.projectnuke.fusion.modelzoo.FusionModelCatalog.builtIn.first {
            it.family == ModelFamily.DEEPSEEK
        }
        val kanana = com.projectnuke.fusion.modelzoo.FusionModelCatalog.importedSpec(
            displayName = "Imported Kanana",
            path = "/tmp/kanana.litertlm",
            family = ModelFamily.KANANA,
        )
        assertTrue(ComposerQuickSettingsPolicy.reasoningCapabilityAvailable(qwen))
        assertTrue(ComposerQuickSettingsPolicy.reasoningCapabilityAvailable(deepSeek))
        assertFalse(ComposerQuickSettingsPolicy.reasoningCapabilityAvailable(null))
        assertFalse(ComposerQuickSettingsPolicy.reasoningCapabilityAvailable(kanana))
        listOf(qwen, deepSeek, kanana).forEach { spec ->
            assertEquals(
                spec.supportsReasoning,
                ComposerQuickSettingsPolicy.reasoningCapabilityAvailable(spec),
            )
        }

        // A reasoning-capable model with reasoning currently disabled still exposes effort controls.
        assertEquals(
            listOf("낮음", "중간", "높음", "매우 높음"),
            ReasoningEffortPreset.entries.map { it.label },
        )
    }

    @Test
    fun reasoningEffortApplicationIsolatedFromAnswerAndRuntimeSettings() {
        val current = editValues().copy(
            reasoningEnabled = false,
            settings = GenerationSettings(
                maxTokens = 777,
                temperature = .73f,
                topP = .91f,
                topK = 53,
                accelerator = AcceleratorMode.CPU,
                reasoningBudgetTokens = 3072,
                speculativeDecodingEnabled = true,
                kvCacheCapacityTokens = 8192,
            ),
            contextMode = LocalContextMode.MANUAL,
            manualKvCapacityTokens = 8192,
            compactPolicy = LocalCompactPolicy(false, 70, 30),
        )
        val applied = requireNotNull(applyReasoningEffort(current, ReasoningEffortPreset.XHIGH))
        assertTrue(applied.reasoningEnabled)
        assertEquals(4096, applied.settings.reasoningBudgetTokens)
        assertEquals(777, applied.settings.maxTokens)
        assertEquals(.73f, applied.settings.temperature, 0f)
        assertEquals(.91f, applied.settings.topP, 0f)
        assertEquals(53, applied.settings.topK)
        assertEquals(AcceleratorMode.CPU, applied.settings.accelerator)
        assertEquals(true, applied.settings.speculativeDecodingEnabled)
        assertEquals(LocalContextMode.MANUAL, applied.contextMode)
        assertEquals(8192, applied.manualKvCapacityTokens)
        assertEquals(LocalCompactPolicy(false, 70, 30), applied.compactPolicy)
        assertNull(applyReasoningEffort(current, ReasoningEffortPreset.LOW, supportsReasoning = false))
    }

    @Test
    fun reasoningTogglePreservesBudget() {
        val high = editValues().copy(
            reasoningEnabled = true,
            settings = editValues().settings.copy(reasoningBudgetTokens = 2048),
        )
        assertEquals(2048, setReasoningEnabled(high, false).settings.reasoningBudgetTokens)
        assertEquals(2048, setReasoningEnabled(setReasoningEnabled(high, false), true).settings.reasoningBudgetTokens)
    }

    @Test
    fun outputLengthDoesNotTreatCurrentPreferenceAsCapabilityCeiling() {
        val options = ComposerQuickSettingsPolicy.outputLengthOptions(maxSupportedOutputTokens = null)
        assertEquals(listOf(true, true, true, true), options.map { it.enabled })
        assertEquals(
            4096,
            ComposerQuickSettingsPolicy
                .applyOutputLength(
                    current = editValues(),
                    length = ComposerQuickOutputLength.VERY_DETAILED,
                    maxSupportedOutputTokens = null,
                )!!.settings.maxTokens,
        )
    }

    @Test
    fun acceleratorCopyAvoidsAbsoluteClaimsAndKananaUsesQuietHelper() {
        assertEquals("GPU", ComposerQuickAcceleratorChoice.GPU.label)
        assertEquals("지원되는 모델에서 더 빠를 수 있어요.", ComposerQuickAcceleratorChoice.GPU.helperText)
        assertEquals(
            "GPU에서 출력이 이상하거나 실행 문제가 있을 때 사용해 보세요. 속도는 더 느릴 수 있어요.",
            ComposerQuickAcceleratorChoice.CPU.helperText,
        )

        val cpu = ComposerQuickSettingsPolicy.applyAccelerator(
            current = editValues(),
            choice = ComposerQuickAcceleratorChoice.CPU,
        )
        assertEquals(AcceleratorMode.CPU, cpu.settings.accelerator)
        assertEquals(
            "이 모델은 CPU 실행을 권장해요. GPU에서 문제가 발생하면 CPU를 사용해 보세요.",
            ComposerQuickSettingsPolicy.kananaCpuRecommendationText("Kanana"),
        )
        assertNull(ComposerQuickSettingsPolicy.kananaCpuRecommendationText("Qwen"))
    }

    @Test
    fun quickPanelVisibilityAndWriteIsolationFollowCurrentLocalTarget() {
        val modelA = ComposerQuickSettingsTarget(
            stableModelId = "model-a",
            selectedPath = "/models/a.litertlm",
            displayIdentity = "Model A",
        )
        val modelB = modelA.copy(stableModelId = "model-b", displayIdentity = "Model B")

        assertTrue(ComposerQuickSettingsPolicy.shouldShowQuickSettings(ComposerQuickGenerationMode.LOCAL_MODEL))
        assertFalse(ComposerQuickSettingsPolicy.shouldShowQuickSettings(ComposerQuickGenerationMode.EXTERNAL_AI_API))
        assertFalse(
            ComposerQuickSettingsPolicy.shouldClosePanel(
                previous = modelA,
                current = modelA,
                mode = ComposerQuickGenerationMode.LOCAL_MODEL,
            ),
        )
        assertTrue(
            ComposerQuickSettingsPolicy.shouldClosePanel(
                previous = modelA,
                current = modelB,
                mode = ComposerQuickGenerationMode.LOCAL_MODEL,
            ),
        )
        assertTrue(
            ComposerQuickSettingsPolicy.shouldClosePanel(
                previous = modelA,
                current = modelA,
                mode = ComposerQuickGenerationMode.EXTERNAL_AI_API,
            ),
        )
        assertTrue(
            ComposerQuickSettingsPolicy.shouldAllowWrite(
                captured = modelA,
                current = modelA,
                mode = ComposerQuickGenerationMode.LOCAL_MODEL,
            ),
        )
        assertFalse(
            ComposerQuickSettingsPolicy.shouldAllowWrite(
                captured = modelA,
                current = modelB,
                mode = ComposerQuickGenerationMode.LOCAL_MODEL,
            ),
        )
    }

    @Test
    fun composerGenerationStatusUsesPresentationOnlyRuntimeText() {
        assertNull(ComposerQuickSettingsPolicy.composerGenerationStatusText(null, reasoningActive = false))
        assertEquals(
            "◌ GPU로 모델 불러오는 중...",
            ComposerQuickSettingsPolicy.composerGenerationStatusText(
                ComposerQuickGenerationStatus.LOADING_GPU_MODEL,
                reasoningActive = false,
            ),
        )
        assertEquals(
            "◌ 질문 처리 중...",
            ComposerQuickSettingsPolicy.composerGenerationStatusText(
                ComposerQuickGenerationStatus.PROCESSING_PROMPT,
                reasoningActive = false,
            ),
        )
        assertEquals(
            "◌ 답변 생성 중...",
            ComposerQuickSettingsPolicy.composerGenerationStatusText(
                ComposerQuickGenerationStatus.GENERATING_RESPONSE,
                reasoningActive = false,
            ),
        )
        assertEquals(
            "◌ 더 깊게 생각하는 중...",
            ComposerQuickSettingsPolicy.composerGenerationStatusText(
                ComposerQuickGenerationStatus.GENERATING_RESPONSE,
                reasoningActive = true,
            ),
        )
    }
}
