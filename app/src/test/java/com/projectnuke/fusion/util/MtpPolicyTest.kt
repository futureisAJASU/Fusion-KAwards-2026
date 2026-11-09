package com.projectnuke.fusion.util

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.GenerationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtpPolicyTest {
    @Test
    fun `non gemma model never gets MTP even when explicitly enabled`() {
        val settings = GenerationSettings(maxTokens = 512, accelerator = AcceleratorMode.GPU, speculativeDecodingEnabled = true)
        assertFalse(resolveEffectiveMtpSetting("Llama 3", settings))
    }

    @Test
    fun `gemma 4 on GPU defaults to MTP enabled`() {
        val settings = GenerationSettings(maxTokens = 512, accelerator = AcceleratorMode.GPU, speculativeDecodingEnabled = null)
        assertTrue(resolveEffectiveMtpSetting("Gemma 4 E4B-it", settings))
        assertTrue(resolveEffectiveMtpSetting("Gemma 4 E2B-it", settings))
    }

    @Test
    fun `gemma 4 on AUTO defaults to MTP enabled`() {
        val settings = GenerationSettings(maxTokens = 512, accelerator = AcceleratorMode.AUTO, speculativeDecodingEnabled = null)
        assertTrue(resolveEffectiveMtpSetting("Gemma 4 E4B-it", settings))
    }

    @Test
    fun `gemma 4 E4B on CPU defaults to MTP enabled but E2B on CPU does not`() {
        val cpu = GenerationSettings(maxTokens = 512, accelerator = AcceleratorMode.CPU, speculativeDecodingEnabled = null)
        assertTrue(resolveEffectiveMtpSetting("Gemma 4 E4B-it", cpu))
        assertFalse(resolveEffectiveMtpSetting("Gemma 4 E2B-it", cpu))
        assertFalse(resolveEffectiveMtpSetting("Gemma 4", cpu))
    }

    @Test
    fun `explicit user preference wins over default`() {
        val off = GenerationSettings(maxTokens = 512, accelerator = AcceleratorMode.GPU, speculativeDecodingEnabled = false)
        assertFalse(resolveEffectiveMtpSetting("Gemma 4 E4B-it", off))
        val on = GenerationSettings(maxTokens = 512, accelerator = AcceleratorMode.CPU, speculativeDecodingEnabled = true)
        assertTrue(resolveEffectiveMtpSetting("Gemma 4 E2B-it", on))
    }

    @Test
    fun `chat and benchmark policy agree for identical inputs`() {
        val settings = GenerationSettings(maxTokens = 2048, accelerator = AcceleratorMode.AUTO, speculativeDecodingEnabled = null)
        assertEquals(
            resolveEffectiveMtpSetting("Gemma 4 E4B-it", settings),
            resolveEffectiveMtpSetting("Gemma 4 E4B-it", settings)
        )
    }

    @Test
    fun `default policy matches chat defaults for gemma variants`() {
        assertEquals(true, defaultSpeculativeDecodingEnabled("Gemma 4 E4B-it", AcceleratorMode.GPU))
        assertEquals(true, defaultSpeculativeDecodingEnabled("Gemma 4 E2B-it", AcceleratorMode.AUTO))
        assertEquals(true, defaultSpeculativeDecodingEnabled("Gemma 4 E4B-it", AcceleratorMode.CPU))
        assertEquals(false, defaultSpeculativeDecodingEnabled("Gemma 4 E2B-it", AcceleratorMode.CPU))
        assertEquals(false, defaultSpeculativeDecodingEnabled("Phi-3", AcceleratorMode.GPU))
    }
}
