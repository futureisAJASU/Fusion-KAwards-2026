package com.projectnuke.fusion.ai

import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalOutputBudgetPolicyTest {
    private fun provider(maxTokens: Int?) = AiProviderConfig(
        id = "provider", type = AiProviderType.OPENAI, displayName = "Provider",
        baseUrl = "https://example.invalid", modelId = "model", apiKeySecretId = null,
        maxTokens = maxTokens,
    )

    @Test
    fun `null provider limit freezes compatibility output`() {
        assertEquals(ExternalAiChatRunner.COMPATIBILITY_MAX_OUTPUT_TOKENS, effectiveExternalMaxOutputTokens(provider(null)))
        assertEquals(32000, effectiveExternalMaxOutputTokens(provider(32000)))
    }

    @Test
    fun `configured provider limit is the effective output`() {
        assertEquals(512, effectiveExternalMaxOutputTokens(provider(512)))
    }
}
