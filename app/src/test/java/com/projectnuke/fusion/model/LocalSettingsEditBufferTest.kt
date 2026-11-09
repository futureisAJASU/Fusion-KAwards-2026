package com.projectnuke.fusion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSettingsEditBufferTest {
    private fun values(output: Int) = LocalSettingsEditValues(
        settings = GenerationSettings(maxTokens = output),
        reasoningEnabled = false,
        contextMode = LocalContextMode.AUTO,
        manualKvCapacityTokens = 4096,
        compactPolicy = LocalCompactPolicy(),
    )

    @Test
    fun `model and global edit buffers remain independent while switching scope`() {
        val original = LocalSettingsEditBuffer(values(1000), values(2000))
        val modelChanged = original.update(LocalSettingsScope.MODEL, values(3000))
        val globalChanged = modelChanged.update(LocalSettingsScope.GLOBAL, values(4000))
        assertEquals(3000, globalChanged.active(LocalSettingsScope.MODEL).settings.maxTokens)
        assertEquals(4000, globalChanged.active(LocalSettingsScope.GLOBAL).settings.maxTokens)
        assertNotEquals(globalChanged.model, globalChanged.global)
    }

    @Test
    fun `reset then OK deletes but reset followed by a model edit saves`() {
        assertEquals(true, shouldDeleteModelOverride(resetRequested = true, modelEditedAfterReset = false))
        assertEquals(false, shouldDeleteModelOverride(resetRequested = true, modelEditedAfterReset = true))
        assertEquals(false, shouldDeleteModelOverride(resetRequested = false, modelEditedAfterReset = false))
    }

    @Test
    fun `complete model profile remains independent when global values change`() {
        val model = LocalModelSettingsOverride(
            maxOutputTokens = 4096,
            temperature = 0.4f,
            topK = 40,
            topP = 0.9f,
            reasoningBudgetTokens = 512,
            reasoningEnabled = true,
            accelerator = AcceleratorMode.CPU,
            speculativeDecodingEnabled = true,
            contextMode = LocalContextMode.MANUAL,
            manualKvCapacityTokens = 12288,
            compactPolicy = LocalCompactPolicy(enabled = true, triggerPercent = 90, recentHistoryPercent = 40),
        )
        val before = LocalModelSettingsResolver.resolve(
            "model-a",
            globalDefaults = LocalModelSettingsOverride(maxOutputTokens = 1024, manualKvCapacityTokens = 4096),
            perModelOverride = model,
        )
        val after = LocalModelSettingsResolver.resolve(
            "model-a",
            globalDefaults = LocalModelSettingsOverride(maxOutputTokens = 2048, manualKvCapacityTokens = 8192),
            perModelOverride = model,
        )
        assertEquals(before.generationSettings, after.generationSettings)
        assertEquals(before.reasoningEnabled, after.reasoningEnabled)
        assertEquals(before.compactPolicy, after.compactPolicy)
    }

    @Test
    fun `only GLOBAL scope may mirror legacy global settings`() {
        assertEquals(false, shouldPersistGlobalLocalSettings(LocalSettingsScope.MODEL))
        assertEquals(true, shouldPersistGlobalLocalSettings(LocalSettingsScope.GLOBAL))
    }

    @Test
    fun `quick reasoning change creates a complete model override without changing global`() {
        val global = LocalModelSettingsOverride(maxOutputTokens = 1024, reasoningEnabled = false)
        val effective = LocalModelSettingsResolver.resolve(
            modelId = "model-a",
            globalDefaults = global,
        )
        val edit = LocalSettingsEditValues(
            settings = effective.generationSettings,
            reasoningEnabled = true,
            contextMode = effective.contextMode,
            manualKvCapacityTokens = 4096,
            compactPolicy = effective.compactPolicy,
        )
        val override = edit.toCompleteLocalModelSettingsOverride()

        assertEquals(true, override.reasoningEnabled)
        assertEquals(false, global.reasoningEnabled)
        assertEquals(true, LocalModelSettingsResolver.resolve("model-a", globalDefaults = global, perModelOverride = override).reasoningEnabled)
        assertEquals(false, LocalModelSettingsResolver.resolve("model-b", globalDefaults = global).reasoningEnabled)
    }

    @Test
    fun `saved manual capacity survives AUTO edit round trip`() {
        val modelOverride = LocalModelSettingsOverride(
            contextMode = LocalContextMode.AUTO,
            manualKvCapacityTokens = 16384,
        )
        val globalOverride = LocalModelSettingsOverride(
            contextMode = LocalContextMode.AUTO,
            manualKvCapacityTokens = 8192,
        )

        assertEquals(16384, resolveSavedManualKvCapacityTokens(modelOverride, globalOverride))
        assertEquals(8192, resolveSavedManualKvCapacityTokens(null, globalOverride))
        assertEquals(
            KvCacheCapacityPolicy.DEFAULT_CAPACITY,
            resolveSavedManualKvCapacityTokens(null, null),
        )
    }

    @Test
    fun `AUTO preview follows temporary output while manual knob stays separate`() {
        val recommendation = LocalModelRecommendation(
            recommendedMaxOutputTokens = 1024,
            recommendedKvCapacityTokens = 4096,
        )
        val lowOutput = resolveAutoKvCapacityTokens(1024, recommendation, deviceIsLowRam = false)
        val highOutput = resolveAutoKvCapacityTokens(4096, recommendation, deviceIsLowRam = false)

        assertEquals(4096, lowOutput)
        assertEquals(8192, highOutput)
        assertEquals(16384, resolveSavedManualKvCapacityTokens(
            LocalModelSettingsOverride(contextMode = LocalContextMode.AUTO, manualKvCapacityTokens = 16384),
            null,
        ))
    }

    @Test
    fun `manual capacity warning includes prompt and safety overhead`() {
        assertTrue(com.projectnuke.fusion.chat.FinalPromptBudgeter.manualCapacityMayReduceOutput(4096, 4096))
        assertTrue(com.projectnuke.fusion.chat.FinalPromptBudgeter.manualCapacityMayReduceOutput(4096, 4064))
        assertEquals(false, com.projectnuke.fusion.chat.FinalPromptBudgeter.manualCapacityMayReduceOutput(4096, 4000))
    }
}
