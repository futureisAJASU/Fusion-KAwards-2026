package com.projectnuke.fusion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortPolicyTest {
    private fun values(
        enabled: Boolean = true,
        budget: Int = 2048,
    ) = LocalSettingsEditValues(
        settings = GenerationSettings(
            maxTokens = 1536,
            temperature = .73f,
            topP = .91f,
            topK = 53,
            accelerator = AcceleratorMode.CPU,
            reasoningBudgetTokens = budget,
            speculativeDecodingEnabled = true,
        ),
        reasoningEnabled = enabled,
        contextMode = LocalContextMode.MANUAL,
        manualKvCapacityTokens = 8192,
        compactPolicy = LocalCompactPolicy(false, 70, 30),
    )

    @Test
    fun `preset contract is ordered and exact`() {
        assertEquals(listOf("낮음", "중간", "높음", "매우 높음"), ReasoningEffortPreset.entries.map { it.label })
        assertEquals(listOf(512, 1024, 2048, 4096), ReasoningEffortPreset.entries.map { it.budgetTokens })
        assertTrue(ReasoningEffortPreset.LOW.budgetTokens < ReasoningEffortPreset.MEDIUM.budgetTokens)
        assertTrue(ReasoningEffortPreset.MEDIUM.budgetTokens < ReasoningEffortPreset.HIGH.budgetTokens)
        assertTrue(ReasoningEffortPreset.HIGH.budgetTokens < ReasoningEffortPreset.XHIGH.budgetTokens)
    }

    @Test
    fun `detection distinguishes off presets and custom values without coercion`() {
        assertEquals(ReasoningEffortSelection.OFF, detectReasoningEffort(false, 2048))
        assertEquals(ReasoningEffortSelection.LOW, detectReasoningEffort(true, 512))
        assertEquals(ReasoningEffortSelection.MEDIUM, detectReasoningEffort(true, 1024))
        assertEquals(ReasoningEffortSelection.HIGH, detectReasoningEffort(true, 2048))
        assertEquals(ReasoningEffortSelection.XHIGH, detectReasoningEffort(true, 4096))
        assertEquals(ReasoningEffortSelection.CUSTOM, detectReasoningEffort(true, 128))
        assertEquals(ReasoningEffortSelection.CUSTOM, detectReasoningEffort(true, 768))
        assertEquals(ReasoningEffortSelection.CUSTOM, detectReasoningEffort(true, 1536))
        assertEquals(ReasoningEffortSelection.CUSTOM, detectReasoningEffort(true, 3072))
        assertEquals(ReasoningEffortSelection.CUSTOM, detectReasoningEffort(true, 8192))
    }

    @Test
    fun `preset changes only reasoning truth and enables reasoning`() {
        val current = values(enabled = false, budget = 3072)
        val applied = requireNotNull(applyReasoningEffort(current, ReasoningEffortPreset.LOW))

        assertTrue(applied.reasoningEnabled)
        assertEquals(512, applied.settings.reasoningBudgetTokens)
        assertEquals(current.settings.maxTokens, applied.settings.maxTokens)
        assertEquals(current.settings.temperature, applied.settings.temperature, 0f)
        assertEquals(current.settings.topP, applied.settings.topP, 0f)
        assertEquals(current.settings.topK, applied.settings.topK)
        assertEquals(current.settings.accelerator, applied.settings.accelerator)
        assertEquals(current.settings.speculativeDecodingEnabled, applied.settings.speculativeDecodingEnabled)
        assertEquals(current.contextMode, applied.contextMode)
        assertEquals(current.manualKvCapacityTokens, applied.manualKvCapacityTokens)
        assertEquals(current.compactPolicy, applied.compactPolicy)
        assertEquals(4096, requireNotNull(applyReasoningEffort(current, ReasoningEffortPreset.XHIGH)).settings.reasoningBudgetTokens)
        assertEquals(null, applyReasoningEffort(current, ReasoningEffortPreset.LOW, supportsReasoning = false))
    }

    @Test
    fun `toggle round trip preserves high xhigh and custom budgets`() {
        listOf(2048, 4096, 3072).forEach { budget ->
            val current = values(enabled = true, budget = budget)
            val off = setReasoningEnabled(current, false)
            val on = setReasoningEnabled(off, true)
            assertEquals(budget, off.settings.reasoningBudgetTokens)
            assertEquals(budget, on.settings.reasoningBudgetTokens)
        }
    }

    @Test
    fun `profile codec persists only existing reasoning truth`() {
        val encoded = LocalModelSettingsProfileCodec.encode(
            LocalModelSettingsDocument(
                globalDefaults = values().toCompleteLocalModelSettingsOverride(),
            ),
        )
        assertTrue(encoded.contains("reasoningEnabled"))
        assertTrue(encoded.contains("reasoningBudgetTokens"))
        assertFalse(encoded.contains("reasoning_effort"))
        assertFalse(encoded.contains("reasoning_level"))
        assertFalse(encoded.contains("reasoning_preset"))
    }
}
