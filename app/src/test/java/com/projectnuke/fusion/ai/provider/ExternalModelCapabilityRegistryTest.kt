package com.projectnuke.fusion.ai.provider

import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.ExternalCapabilitySource
import com.projectnuke.fusion.ai.model.ExternalDiscoveredModel
import com.projectnuke.fusion.ai.model.ExternalModelVariant
import com.projectnuke.fusion.ai.model.ExternalRequestPatch
import com.projectnuke.fusion.ai.model.ReasoningEffortMapping
import com.projectnuke.fusion.ai.model.UserExternalVariant
import com.projectnuke.fusion.ai.model.UserVariantPatch
import com.projectnuke.fusion.ai.network.ExternalOutputTokenField
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalModelCapabilityRegistryTest {
    private fun shippedCatalog(): String {
        val candidates = listOf(
            File("app/src/main/assets/external_model_capabilities_v1.json"),
            File("src/main/assets/external_model_capabilities_v1.json"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("shipped external capability catalog asset not found")
    }

    private fun shipped(providerType: AiProviderType, modelId: String) =
        ExternalModelCapabilityRegistry.resolveCatalogForTest(shippedCatalog(), providerType, modelId)

    @Test
    fun `unknown model is default only without singleton mutation`() {
        val result = shipped(AiProviderType.CUSTOM_OPENAI_COMPATIBLE, "unknown-model-123")
        assertEquals(listOf("default"), result.map { it.id })
        assertEquals("기본", result.single().label)
    }

    @Test
    fun `shipped asset pins exact OpenAI contracts and alias`() {
        val expected = listOf("default", "builtin:none", "builtin:low", "builtin:medium", "builtin:high", "builtin:xhigh", "builtin:max")
        listOf("gpt-5.6-sol", "gpt-5.6", "gpt-5.6-terra", "gpt-5.6-luna").forEach { modelId ->
            val result = shipped(AiProviderType.OPENAI, modelId)
            assertEquals(modelId, expected, result.map { it.id })
            assertTrue(result.drop(1).all { it.source == ExternalCapabilitySource.FUSION_VERIFIED })
            assertEquals(listOf("none", "low", "medium", "high", "xhigh", "max"), result.drop(1).mapNotNull { it.requestPatch.reasoningEffort?.name?.lowercase() })
        }
        assertEquals(listOf("default"), shipped(AiProviderType.OPENAI, "gpt-5.6-sol-pro").map { it.id })
    }

    @Test
    fun `shipped request compatibility is exact and provider scoped`() {
        listOf("gpt-5.6-sol", "gpt-5.6", "gpt-5.6-terra", "gpt-5.6-luna").forEach { modelId ->
            assertEquals(
                ExternalOutputTokenField.MAX_COMPLETION_TOKENS,
                ExternalModelCapabilityRegistry.resolveCompatibilityForTest(shippedCatalog(), AiProviderType.OPENAI, modelId).outputTokenField,
            )
        }
        assertEquals(
            ExternalOutputTokenField.MAX_TOKENS,
            ExternalModelCapabilityRegistry.resolveCompatibilityForTest(shippedCatalog(), AiProviderType.OPENAI, "my-gpt-5.6-sol-copy").outputTokenField,
        )
        assertEquals(
            ExternalOutputTokenField.MAX_TOKENS,
            ExternalModelCapabilityRegistry.resolveCompatibilityForTest(shippedCatalog(), AiProviderType.NVIDIA_NIM, "openai/gpt-oss-20b").outputTokenField,
        )
        assertEquals(
            ExternalOutputTokenField.MAX_TOKENS,
            ExternalModelCapabilityRegistry.resolveCompatibilityForTest(shippedCatalog(), AiProviderType.CUSTOM_OPENAI_COMPATIBLE, "gpt-5.6-sol").outputTokenField,
        )
    }

    @Test
    fun `shipped asset pins conditional NIM contracts`() {
        listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b").forEach { modelId ->
            val result = shipped(AiProviderType.NVIDIA_NIM, modelId)
            assertEquals(listOf("default", "builtin:low", "builtin:medium", "builtin:high"), result.map { it.id })
            assertTrue(result.drop(1).all { it.providerNote?.isNotBlank() == true })
        }
        assertEquals(listOf("default"), shipped(AiProviderType.NVIDIA_NIM, "openai/other-model").map { it.id })
    }

    @Test
    fun `malformed isolated catalog fails closed`() {
        val malformed = "{\"version\":99,\"entries\":[]}"
        assertEquals(listOf("default"), ExternalModelCapabilityRegistry.resolveCatalogForTest(malformed, AiProviderType.OPENAI, "gpt-5.6-sol").map { it.id })
        val malformedRequest = """
            {"version":1,"entries":[{"providerType":"OPENAI","modelIds":["m"],"request":{"outputTokenField":"NOPE"},"variants":[{"id":"builtin:none","label":"None","effort":"none"}]}]}
        """.trimIndent()
        assertEquals(
            ExternalOutputTokenField.MAX_TOKENS,
            ExternalModelCapabilityRegistry.resolveCompatibilityForTest(malformedRequest, AiProviderType.OPENAI, "m").outputTokenField,
        )
    }

    @Test
    fun `isolated catalogs do not leak into one another or singleton state`() {
        val shippedResult = shipped(AiProviderType.OPENAI, "gpt-5.6-sol")
        val malformedResult = ExternalModelCapabilityRegistry.resolveCatalogForTest(
            "{\"version\":99,\"entries\":[]}",
            AiProviderType.OPENAI,
            "gpt-5.6-sol",
        )
        val shippedAgain = shipped(AiProviderType.OPENAI, "gpt-5.6-sol")
        assertTrue(shippedResult.size > 1)
        assertEquals(listOf("default"), malformedResult.map { it.id })
        assertEquals(shippedResult.map { it.id }, shippedAgain.map { it.id })
        assertEquals(listOf("default"), ExternalModelCapabilityRegistry.resolveVariants(AiProviderType.OPENAI, "gpt-5.6-sol").map { it.id })
    }

    @Test
    fun `composition orders provider and user variants without source collision`() {
        val provider = AiProviderConfig(
            id = "provider-a", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE, displayName = "P",
            baseUrl = "https://example.com/v1", modelId = "model-a", apiKeySecretId = null,
        )
        val advertised = ExternalModelVariant(
            id = "provider:high", label = "Provider", requestPatch = ExternalRequestPatch(customReasoningEffort = "high"),
            source = ExternalCapabilitySource.PROVIDER_ADVERTISED,
        )
        val user = UserExternalVariant(
            id = "user:123", providerId = "provider-a", modelId = "model-a", label = "Turbo",
            patch = UserVariantPatch.ReasoningEffort("high", ReasoningEffortMapping.FLAT_REASONING_EFFORT),
        )
        val result = ExternalModelCapabilityRegistry.resolveComposed(
            provider,
            listOf(user),
            discoveredModel = ExternalDiscoveredModel("model-a"),
            adapters = listOf(object : ExternalProviderCapabilityAdapter {
                override fun resolve(provider: AiProviderConfig, discoveredModel: ExternalDiscoveredModel) = listOf(advertised)
            }),
        )
        assertEquals(listOf("default", "provider:high", "user:123"), result.map { it.id })
        assertEquals(ExternalCapabilitySource.USER_DEFINED, result.last().source)
    }

    @Test
    fun `default resolver returns default for unknown model`() {
        val result = ExternalModelCapabilityRegistry.resolveVariant(AiProviderType.OPENAI, "unknown-model", "default")
        assertNotNull(result)
        assertEquals("default", result!!.id)
    }
}
