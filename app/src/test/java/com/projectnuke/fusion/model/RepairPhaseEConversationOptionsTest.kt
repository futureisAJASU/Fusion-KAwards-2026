package com.projectnuke.fusion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase E tests: pin the post-repair behaviour of
 * [GenerationSettings.toConversationOptions] so the "prompt ≈ output"
 * heuristic that previously clamped `maxOutputToken` to ~96 at defaults
 * can never silently regress.
 *
 * The Phase E contract:
 *  - `toConversationOptions()` (no prompt info) MUST NOT pre-clamp the
 *    output budget — pass `maxTokens` through as `maxOutputToken`.
 *  - `toConversationOptions(estimatedPromptTokens)` clamps only when
 *    `prompt + requestedOutput > kvCacheCapacityTokens`.
 *  - At production defaults (maxTokens=4000, kvCacheCapacityTokens=4096),
 *    the no-arg form MUST yield `maxOutputToken = 4000` (not 96, not 0).
 */
class RepairPhaseEConversationOptionsTest {

    @Test
    fun `E defaults no-arg form does not pre-clamp the output budget`() {
        val settings = GenerationSettings()
        val options = settings.toConversationOptions()
        assertEquals(
            "Phase E: at default settings (maxTokens=4000, kvCache=4096), " +
                "the no-arg toConversationOptions must pass maxTokens through unchanged",
            4000,
            options.maxOutputToken
        )
    }

    @Test
    fun `E defaults no-arg form does not introduce a 96-token cap`() {
        // Regression guard: the broken heuristic produced maxOutputToken=96
        // when maxTokens=4000 and kvCacheCapacityTokens=4096, because
        // estimatedPromptTokens was set to maxTokens.coerceAtLeast(1)=4000
        // and validateOutputBudget did (4096-4000).coerceAtLeast(1)=96.
        val settings = GenerationSettings()
        val options = settings.toConversationOptions()
        assertTrue(
            "Phase E regression: maxOutputToken must not be 96 at defaults, was ${options.maxOutputToken}",
            options.maxOutputToken != 96
        )
        assertTrue(
            "Phase E regression: maxOutputToken must be > 100 at defaults, was ${options.maxOutputToken}",
            (options.maxOutputToken ?: 0) > 100
        )
    }

    @Test
    fun `E overload with zero prompt estimate yields the requested output`() {
        val settings = GenerationSettings(maxTokens = 4000, kvCacheCapacityTokens = 4096)
        val options = settings.toConversationOptions(estimatedPromptTokens = 0)
        assertEquals(4000, options.maxOutputToken)
    }

    @Test
    fun `E overload clamps output only when prompt plus output exceeds capacity`() {
        val settings = GenerationSettings(maxTokens = 4000, kvCacheCapacityTokens = 4096)
        // Prompt=2000, requested output=4000, capacity=4096
        // 2000 + 4000 = 6000 > 4096 → clamp output to 4096-2000=2096
        val options = settings.toConversationOptions(estimatedPromptTokens = 2000)
        assertEquals(2096, options.maxOutputToken)
    }

    @Test
    fun `E overload passes output through when prompt plus output fits`() {
        val settings = GenerationSettings(maxTokens = 2000, kvCacheCapacityTokens = 4096)
        val options = settings.toConversationOptions(estimatedPromptTokens = 1000)
        // 1000 + 2000 = 3000 ≤ 4096 → no clamp
        assertEquals(2000, options.maxOutputToken)
    }

    @Test
    fun `E overload clamps to a minimum of one token even when prompt exhausts capacity`() {
        val settings = GenerationSettings(maxTokens = 4000, kvCacheCapacityTokens = 1024)
        val options = settings.toConversationOptions(estimatedPromptTokens = 4000)
        // 4000 + 4000 = 8000 > 1024 → (1024-4000).coerceAtLeast(1) = 1
        assertEquals(1, options.maxOutputToken)
    }

    @Test
    fun `E overload normalises negative prompt estimate to zero`() {
        val settings = GenerationSettings(maxTokens = 4000, kvCacheCapacityTokens = 4096)
        val optionsNeg = settings.toConversationOptions(estimatedPromptTokens = -50)
        val optionsZero = settings.toConversationOptions(estimatedPromptTokens = 0)
        assertEquals(optionsZero.maxOutputToken, optionsNeg.maxOutputToken)
        assertEquals(4000, optionsNeg.maxOutputToken)
    }

    @Test
    fun `E overload preserves sampling options unchanged`() {
        val settings = GenerationSettings(
            maxTokens = 4000,
            kvCacheCapacityTokens = 4096,
            temperature = 0.42f,
            topK = 17,
            topP = 0.77f
        )
        val options = settings.toConversationOptions(estimatedPromptTokens = 100)
        assertEquals(0.42f, options.temperature, 0.0001f)
        assertEquals(17, options.topK)
        assertEquals(0.77f, options.topP, 0.0001f)
    }

    @Test
    fun `E overload guarantees non-null maxOutputToken`() {
        val settings = GenerationSettings(maxTokens = 4000, kvCacheCapacityTokens = 4096)
        val options = settings.toConversationOptions(estimatedPromptTokens = 999_999)
        assertNotNull(
            "maxOutputToken must be non-null even when prompt overflows capacity; " +
                "the policy clamps to a minimum of 1 token",
            options.maxOutputToken
        )
    }

    @Test
    fun `E overload keeps maxOutputToken greater than zero at all default values`() {
        // Sweep a few realistic defaults to make sure the output cap never
        // accidentally collapses to 1 token in normal configurations.
        val scenarios = listOf(
            GenerationSettings(maxTokens = 1024, kvCacheCapacityTokens = 4096) to 500,
            GenerationSettings(maxTokens = 2048, kvCacheCapacityTokens = 4096) to 500,
            GenerationSettings(maxTokens = 4096, kvCacheCapacityTokens = 8192) to 1000,
            GenerationSettings(maxTokens = 1024, kvCacheCapacityTokens = 2048) to 100,
        )
        for ((settings, promptEstimate) in scenarios) {
            val options = settings.toConversationOptions(estimatedPromptTokens = promptEstimate)
            assertTrue(
                "maxOutputToken must be > 0 for settings=$settings prompt=$promptEstimate, " +
                    "got ${options.maxOutputToken}",
                (options.maxOutputToken ?: 0) > 0
            )
        }
    }
}
