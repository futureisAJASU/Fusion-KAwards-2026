package com.projectnuke.fusion.ai

import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.ExternalModelVariant
import com.projectnuke.fusion.ai.model.ExternalReasoningEffort
import com.projectnuke.fusion.ai.model.ExternalRequestPatch
import com.projectnuke.fusion.ai.model.ExternalCapabilitySource
import com.projectnuke.fusion.ai.model.ReasoningEffortMapping
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.ai.network.FakeHttpURLConnection
import com.projectnuke.fusion.ai.network.OpenAiCompatibleClient
import com.projectnuke.fusion.ai.provider.ExternalModelCapabilityResolver
import com.projectnuke.fusion.ai.secure.SecretStore
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.privacy.DataEgressController
import com.projectnuke.fusion.privacy.DataEgressDestination
import com.projectnuke.fusion.privacy.EgressGateChatClient
import com.projectnuke.fusion.privacy.PreparedEgressRequestFactory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class ExternalVariantTransportIntegrationTest {
    @Test
    fun `snapshot runner client fake HTTP preserves authorized variant patch after mutation`() = runBlocking {
        val variant = ExternalModelVariant(
            id = "test-high",
            label = "Test high",
            requestPatch = ExternalRequestPatch(ExternalReasoningEffort.HIGH),
        )
        val resolver = ExternalModelCapabilityResolver { _, _ -> listOf(variant) }
        var currentProvider = provider(AiProviderType.OPENAI, "original-model", "https://original.example/v1")
            .copy(selectedVariantId = "test-high")
        val connection = FakeHttpURLConnection(URL("https://original.example/v1/chat/completions"))
        connection.configure(
            responseBody = """{"id":"r1","model":"original-model","choices":[{"message":{"content":"ok"}}]}""".toByteArray()
        )
        val client = OpenAiCompatibleClient(
            secretStore = FixedSecretStore(),
            connectionFactory = { connection },
        )
        val egress = DataEgressController()
        val gatedClient = EgressGateChatClient(client, egress)
        val runner = ExternalAiChatRunner(
            providerRepository = object : ExternalAiProviderSource {
                override suspend fun getSelectedRunnableProvider(): AiProviderConfig = currentProvider
                override suspend fun getRunnableProviderById(id: String): AiProviderConfig? =
                    currentProvider.takeIf { it.id == id }
            },
            client = gatedClient,
        )
        val snapshot = ExternalProviderRequestSnapshot.from(currentProvider, 512, resolver)
        val request = runner.buildTransportRequest(
            currentProvider,
            listOf(AiMessage(AiRole.USER, "hello")),
            null,
        )
        val prepared = PreparedEgressRequestFactory.prepare(
            requestId = "egress-high",
            destination = DataEgressDestination.EXTERNAL_AI,
            provider = currentProvider,
            exactRequest = request,
            resolvedRequestPatch = snapshot.resolvedRequestPatch,
            transmittedUserText = "hello",
            transmittedHistoryText = "",
        )
        assertTrue(egress.arm(prepared, null))
        currentProvider = provider(AiProviderType.OPENAI, "mutated-model", "https://mutated.example/v1")

        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hello")),
            providerId = "provider",
            providerSelectionFrozen = true,
            providerRequestSnapshot = snapshot,
            requestId = "egress-high",
        )

        assertTrue(result is ExternalAiChatResult.Success)
        val body = JSONObject(String(connection.sentBytes(), Charsets.UTF_8))
        assertEquals("original-model", body.getString("model"))
        assertEquals("high", body.getString("reasoning_effort"))
        assertFalse(body.has("mutated-model"))
    }

    @Test
    fun `generic provider snapshot never emits native reasoning field`() = runBlocking {
        val variant = ExternalModelVariant(
            id = "test-high",
            label = "Test high",
            requestPatch = ExternalRequestPatch(ExternalReasoningEffort.HIGH),
        )
        val config = provider(AiProviderType.CUSTOM_OPENAI_COMPATIBLE, "generic-model", "https://generic.example/v1")
            .copy(selectedVariantId = "test-high")
        val snapshot = ExternalProviderRequestSnapshot.from(
            config,
            512,
            ExternalModelCapabilityResolver { _, _ -> listOf(variant) },
        )
        val connection = FakeHttpURLConnection(URL("https://generic.example/v1/chat/completions"))
        connection.configure(
            responseBody = """{"id":"r1","model":"generic-model","choices":[{"message":{"content":"ok"}}]}""".toByteArray()
        )
        OpenAiCompatibleClient(FixedSecretStore(), connectionFactory = { connection }).chatCompletion(
            config,
            com.projectnuke.fusion.ai.model.AiChatRequest(listOf(AiMessage(AiRole.USER, "hello"))),
            null,
            snapshot,
        )
        assertFalse(JSONObject(String(connection.sentBytes(), Charsets.UTF_8)).has("reasoning_effort"))
    }

    @Test
    fun `nested user-defined effort reaches frozen HTTP JSON`() = runBlocking {
        val variant = ExternalModelVariant(
            id = "user:test-nested",
            label = "Nested",
            requestPatch = ExternalRequestPatch(
                customReasoningEffort = "high",
                reasoningEffortMapping = ReasoningEffortMapping.NESTED_REASONING_EFFORT,
            ),
            source = ExternalCapabilitySource.USER_DEFINED,
        )
        val config = provider(AiProviderType.CUSTOM_OPENAI_COMPATIBLE, "custom-model", "https://custom.example/v1")
            .copy(selectedVariantId = variant.id)
        val snapshot = ExternalProviderRequestSnapshot.from(
            config,
            512,
            ExternalModelCapabilityResolver { _, _ -> listOf(variant) },
        )
        val connection = FakeHttpURLConnection(URL("https://custom.example/v1/chat/completions"))
        connection.configure(responseBody = """{"choices":[{"message":{"content":"ok"}}]}""".toByteArray())
        OpenAiCompatibleClient(FixedSecretStore(), connectionFactory = { connection }).chatCompletion(
            config,
            com.projectnuke.fusion.ai.model.AiChatRequest(listOf(AiMessage(AiRole.USER, "hello"))),
            null,
            snapshot,
        )
        val body = JSONObject(String(connection.sentBytes(), Charsets.UTF_8))
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
        assertFalse(body.has("reasoning_effort"))
    }

    private fun provider(type: AiProviderType, model: String, baseUrl: String) = AiProviderConfig(
        id = "provider",
        type = type,
        displayName = "Provider",
        baseUrl = baseUrl,
        modelId = model,
        apiKeySecretId = "secret",
        temperature = 0.4,
        maxTokens = 512,
    )

    private class FixedSecretStore : SecretStore {
        override suspend fun getSecret(id: String): String = "secret-value"
        override suspend fun putSecret(id: String, value: String): Boolean = true
        override suspend fun deleteSecret(id: String): Boolean = true
    }
}
