package com.projectnuke.fusion.chat

import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.ExternalRequestPatch
import com.projectnuke.fusion.ai.model.ReasoningEffortMapping
import com.projectnuke.fusion.ai.model.UserExternalVariant
import com.projectnuke.fusion.ai.model.UserVariantPatch
import com.projectnuke.fusion.ai.network.ExternalOutputTokenField
import com.projectnuke.fusion.ai.network.ExternalRequestCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExternalProviderRequestSnapshotTest {

    @Test
    fun `snapshot freezes variant and patch`() {
        val provider = AiProviderConfig(
            id = "test",
            type = AiProviderType.OPENAI,
            displayName = "Test",
            baseUrl = "https://example.com/",
            modelId = "model-x",
            apiKeySecretId = "key",
        )
        val snapshot = ExternalProviderRequestSnapshot.from(provider, 512).copy(
            selectedVariantId = "default",
            resolvedRequestPatch = ExternalRequestPatch(),
        )
        assertEquals("default", snapshot.selectedVariantId)
        assertNotNull(snapshot.resolvedRequestPatch)
        assertEquals("model-x", snapshot.modelId)
    }

    @Test
    fun `default snapshot preserves null variant`() {
        val provider = AiProviderConfig(
            id = "test",
            type = AiProviderType.OPENAI,
            displayName = "Test",
            baseUrl = "https://example.com/",
            modelId = "m",
            apiKeySecretId = "k",
        )
        val snapshot = ExternalProviderRequestSnapshot.from(provider, 256)
        assertEquals("default", snapshot.selectedVariantId)
        assertNotNull(snapshot.resolvedRequestPatch)
    }

    @Test
    fun `user variant is validated resolved and frozen`() {
        val provider = AiProviderConfig(
            id = "provider", type = AiProviderType.OPENAI, displayName = "Test",
            baseUrl = "https://example.com/v1", modelId = "model", apiKeySecretId = null,
            selectedVariantId = "user:variant",
        )
        val user = UserExternalVariant(
            id = "user:variant", providerId = "provider", modelId = "model", label = "Turbo",
            patch = UserVariantPatch.ReasoningEffort("turbo-high", ReasoningEffortMapping.NESTED_REASONING_EFFORT),
        )
        val snapshot = ExternalProviderRequestSnapshot.from(provider, 512, userVariants = listOf(user))
        assertEquals("user:variant", snapshot.selectedVariantId)
        assertEquals("turbo-high", snapshot.resolvedRequestPatch.customReasoningEffort)
        assertEquals(ReasoningEffortMapping.NESTED_REASONING_EFFORT, snapshot.resolvedRequestPatch.reasoningEffortMapping)
    }

    @Test
    fun `request compatibility is frozen at snapshot creation`() {
        val provider = AiProviderConfig(
            id = "provider", type = AiProviderType.OPENAI, displayName = "Test",
            baseUrl = "https://example.com/v1", modelId = "gpt-5.6-sol", apiKeySecretId = null,
        )
        var current = ExternalRequestCompatibility(ExternalOutputTokenField.MAX_COMPLETION_TOKENS)

        val snapshot = ExternalProviderRequestSnapshot.from(
            provider,
            512,
            compatibilityResolver = { _, _ -> current },
        )
        current = ExternalRequestCompatibility.FALLBACK

        assertEquals(ExternalOutputTokenField.MAX_COMPLETION_TOKENS, snapshot.requestCompatibility.outputTokenField)
    }
}
