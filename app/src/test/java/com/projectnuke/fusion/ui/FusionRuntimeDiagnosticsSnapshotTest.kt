package com.projectnuke.fusion.ui

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.LocalModelSettingsOverride
import com.projectnuke.fusion.model.LocalModelSettingsResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the shared read-only diagnostics snapshot: local
 * diagnostic presentation must show the EFFECTIVE settings actual generation
 * would use (per-model override > global defaults > recommendation/fallback),
 * never a flat legacy/global preference value.
 */
class FusionRuntimeDiagnosticsSnapshotTest {

    private fun format(profile: com.projectnuke.fusion.model.EffectiveLocalModelProfile): List<String> =
        FusionRuntimeDiagnosticsFormat.effectiveLocalModelSettingLines(profile)

    // 1. Global defaults with no per-model override.
    @Test
    fun `global defaults are shown when no per-model override exists`() {
        val profile = LocalModelSettingsResolver.resolve(
            modelId = "model-a",
            globalDefaults = LocalModelSettingsOverride(
                maxOutputTokens = 2048,
                temperature = 0.7f,
                topK = 32,
                topP = 0.9f,
            ),
        )
        val lines = format(profile)
        assertTrue(lines.contains("maxTokens: 2048"))
        assertTrue(lines.contains("temperature: 0.70"))
        assertTrue(lines.contains("topK: 32"))
        assertTrue(lines.contains("topP: 0.90"))
    }

    // 2. Per-model override wins over global defaults.
    @Test
    fun `per-model override wins over global defaults in diagnostics lines`() {
        val profile = LocalModelSettingsResolver.resolve(
            modelId = "model-a",
            globalDefaults = LocalModelSettingsOverride(maxOutputTokens = 4096),
            perModelOverride = LocalModelSettingsOverride(maxOutputTokens = 1024),
        )
        assertTrue(format(profile).contains("maxTokens: 1024"))
        assertFalse(format(profile).contains("maxTokens: 4096"))
    }

    // 3. Effective reasoning value follows the selected model profile.
    @Test
    fun `effective reasoning follows selected model profile`() {
        val profile = LocalModelSettingsResolver.resolve(
            modelId = "model-a",
            globalDefaults = LocalModelSettingsOverride(reasoningEnabled = false),
            perModelOverride = LocalModelSettingsOverride(reasoningEnabled = true),
        )
        assertTrue(profile.reasoningEnabled)
        assertTrue(format(profile).contains("추론: 켜짐"))

        val globalOnly = LocalModelSettingsResolver.resolve(
            modelId = "model-a",
            globalDefaults = LocalModelSettingsOverride(reasoningEnabled = true),
        )
        assertTrue(globalOnly.reasoningEnabled)
    }

    // 4. Effective accelerator follows the selected model profile.
    @Test
    fun `effective accelerator follows selected model profile`() {
        val profile = LocalModelSettingsResolver.resolve(
            modelId = "model-a",
            globalDefaults = LocalModelSettingsOverride(accelerator = AcceleratorMode.GPU),
            perModelOverride = LocalModelSettingsOverride(accelerator = AcceleratorMode.CPU),
        )
        assertEquals(AcceleratorMode.CPU, profile.generationSettings.accelerator)
        assertTrue(format(profile).contains("가속기: CPU"))
        assertFalse(format(profile).contains("가속기: GPU"))
    }

    // 5. Effective MTP/speculative setting follows the selected model profile.
    @Test
    fun `effective mtp follows selected model profile`() {
        val profile = LocalModelSettingsResolver.resolve(
            modelId = "model-a",
            globalDefaults = LocalModelSettingsOverride(speculativeDecodingEnabled = false),
            perModelOverride = LocalModelSettingsOverride(speculativeDecodingEnabled = true),
        )
        assertEquals(true, profile.generationSettings.speculativeDecodingEnabled)
        assertTrue(format(profile).contains("MTP: 켜짐"))

        val globalOnly = LocalModelSettingsResolver.resolve(
            modelId = "model-a",
            globalDefaults = LocalModelSettingsOverride(speculativeDecodingEnabled = true),
        )
        assertEquals(true, globalOnly.generationSettings.speculativeDecodingEnabled)
        assertTrue(format(globalOnly).contains("MTP: 켜짐"))
    }

    // 6. Readiness is false when a built-in identity exists but the backing
    //    model file is absent.
    @Test
    fun `built-in identity without backing model is not runnable`() {
        val truth = resolveLocalModelReadinessTruth(
            selectedDisplayName = "Gemma 4 E2B-it",
            selectedManagedPath = null,
            actualManagedRunnablePath = null,
            selectedBuiltInAvailable = false,
        )
        assertFalse(truth.isRunnable)
    }

    // 7. A runnable managed model reports ready.
    @Test
    fun `runnable managed model reports ready`() {
        val truth = resolveLocalModelReadinessTruth(
            selectedDisplayName = "Gemma 4 E2B-it",
            selectedManagedPath = "/managed/model.litertlm",
            actualManagedRunnablePath = "/managed/model.litertlm",
            selectedBuiltInAvailable = false,
        )
        assertTrue(truth.isRunnable)
    }

    @Test
    fun `summary line reflects effective values not stored globals`() {
        val profile = LocalModelSettingsResolver.resolve(
            modelId = "model-a",
            globalDefaults = LocalModelSettingsOverride(maxOutputTokens = 4096, reasoningEnabled = false),
            perModelOverride = LocalModelSettingsOverride(maxOutputTokens = 1024, reasoningEnabled = true),
        )
        val line = FusionRuntimeDiagnosticsFormat.effectiveGenerationSummaryLine(profile, webSearchEnabled = false)
        assertTrue(line.contains("최대 출력 1024"))
        assertTrue(line.contains("추론=켜짐"))
        assertFalse(line.contains("최대 출력 4096"))
        assertTrue(line.contains("웹 검색=꺼짐"))
    }
}
