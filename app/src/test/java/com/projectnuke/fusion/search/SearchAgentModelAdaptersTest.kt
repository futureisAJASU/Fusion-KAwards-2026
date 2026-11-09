package com.projectnuke.fusion.search

import com.projectnuke.fusion.ai.ExternalAiChatRunner
import com.projectnuke.fusion.ai.ExternalAiProviderSource
import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiChatResponse
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.network.AiProviderClientException
import com.projectnuke.fusion.ai.network.ChatClient
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.LlmEngine
import com.projectnuke.fusion.llm.WarmRuntimeExecution
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.privacy.DataEgressController
import com.projectnuke.fusion.privacy.EgressGateChatClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAgentModelAdaptersTest {
    private class ScriptedEngine(private val outputs: MutableList<GenerationOutcome>) : LlmEngine {
        val prompts = mutableListOf<List<ChatMessage>>()
        val options = mutableListOf<ConversationOptions>()

        override suspend fun generate(
            messages: List<ChatMessage>,
            profile: RequestedEngineProfile,
            options: ConversationOptions,
        ): GenerationOutcome {
            prompts += messages
            this.options += options
            return outputs.removeAt(0)
        }

        override fun unload() = Unit
    }

    @Test
    fun localAdapterUsesActiveEngineAndReturnsBoundedStructuredIntent() = runTest {
        val engine = ScriptedEngine(mutableListOf(GenerationOutcome.Success("""{"action":"NO_MORE_SEARCH"}""")))
        val model = LocalStructuredSearchAgentModel(
            engine = engine,
            profile = RequestedEngineProfile("active-model", com.projectnuke.fusion.model.AcceleratorMode.CPU, false, 4096, false),
            baseOptions = ConversationOptions(maxOutputToken = 2_000),
            engineStillReusable = { true },
            requestStillAuthorized = { true },
        )

        val result = model.complete(
            listOf(
                SearchAgentMessage(SearchAgentMessageRole.SYSTEM, "Return one JSON action."),
                SearchAgentMessage(SearchAgentMessageRole.USER, "Samsung Electronics stock price"),
                SearchAgentMessage(SearchAgentMessageRole.TOOL, "untrusted page text"),
            ),
        )

        assertEquals(SearchAgentProtocol.STRUCTURED_INTENT, model.protocol)
        assertEquals("""{"action":"NO_MORE_SEARCH"}""", (result as SearchAgentReply.StructuredIntent).output)
        assertEquals("user", engine.prompts.single().last().role)
        assertTrue(engine.prompts.single().last().content.contains("<untrusted_web_search_tool_result>"))
        assertEquals(LocalStructuredSearchAgentModel.LOCAL_PLANNER_MAX_OUTPUT_TOKENS, engine.options.single().maxOutputToken)
    }

    @Test
    fun localAdapterRechecksWarmProfileAfterWaitingForRuntimeLock() = runTest {
        val engine = ScriptedEngine(mutableListOf())
        var reusable = true
        var lockAcquisitions = 0
        val model = LocalStructuredSearchAgentModel(
            engine = engine,
            profile = RequestedEngineProfile("active-model", com.projectnuke.fusion.model.AcceleratorMode.CPU, false, 4096, false),
            baseOptions = ConversationOptions(),
            engineStillReusable = { reusable },
            requestStillAuthorized = { true },
            withRuntimeLock = { criticalSection ->
                lockAcquisitions++
                reusable = false
                criticalSection()
            },
        )

        val result = model.complete(listOf(SearchAgentMessage(SearchAgentMessageRole.USER, "public query")))

        assertEquals(1, lockAcquisitions)
        assertEquals("active_local_model_unavailable", (result as SearchAgentReply.Failed).reasonCode)
        assertTrue("stale warm profile must not reach engine.generate", engine.prompts.isEmpty())
    }

    @Test
    fun localAdapterDoesNotGenerateWhenEngineOrRequestIsNotAdmitted() = runTest {
        val engine = ScriptedEngine(mutableListOf())
        val messages = listOf(SearchAgentMessage(SearchAgentMessageRole.USER, "public query"))
        val unavailable = LocalStructuredSearchAgentModel(
            engine,
            RequestedEngineProfile("active", com.projectnuke.fusion.model.AcceleratorMode.CPU, false, 4096, false),
            ConversationOptions(),
            engineStillReusable = { false },
            requestStillAuthorized = { true },
        )
        assertTrue((unavailable.complete(messages) as SearchAgentReply.Failed).reasonCode.contains("active_local"))
        assertTrue(engine.prompts.isEmpty())

        val revoked = LocalStructuredSearchAgentModel(
            engine,
            RequestedEngineProfile("active", com.projectnuke.fusion.model.AcceleratorMode.CPU, false, 4096, false),
            ConversationOptions(),
            engineStillReusable = { true },
            requestStillAuthorized = { false },
        )
        assertFalse((revoked.complete(messages) as SearchAgentReply.Failed).recoverable)
        assertTrue(engine.prompts.isEmpty())
    }

    @Test
    fun officialNativeToolRejectionRetriesOnceWithStructuredProtocolAndExactEgress() = runTest {
        val provider = AiProviderConfig(
            id = "official-openai",
            type = AiProviderType.OPENAI,
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            modelId = "search-planner",
            apiKeySecretId = null,
            authMode = com.projectnuke.fusion.ai.model.AiProviderAuthMode.NONE,
        )
        val providerSource = object : ExternalAiProviderSource {
            override suspend fun getSelectedRunnableProvider() = provider
            override suspend fun getRunnableProviderById(id: String) = provider.takeIf { id == provider.id }
        }
        val sent = mutableListOf<AiChatRequest>()
        val delegate = object : ChatClient {
            override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse {
                sent += request
                if (request.tools.isNotEmpty()) {
                    throw AiProviderClientException("tools rejected", nativeToolProtocolUnsupported = true)
                }
                return AiChatResponse("response", config.modelId, """{"action":"SEARCH","queries":["Samsung 005930 current price KRX"]}""")
            }
        }
        val egress = DataEgressController()
        val runner = ExternalAiChatRunner(providerSource, EgressGateChatClient(delegate, egress))
        val model = ExternalSearchAgentModel.create(
            runner = runner,
            egressController = egress,
            requestId = "agentic-external-request",
            originalUserQuery = "삼성전자 주가 알려줘",
            providerId = provider.id,
            providerSelectionFrozen = true,
            providerRequestSnapshot = null,
            requestStillAuthorized = { true },
        )!!
        val messages = listOf(
            SearchAgentMessage(
                SearchAgentMessageRole.SYSTEM,
                SearchAgentProtocolPrompt.NATIVE_TOOL_CALLS,
            ),
            SearchAgentMessage(SearchAgentMessageRole.USER, "User request: 삼성전자 주가 알려줘"),
        )

        assertEquals(SearchAgentProtocol.NATIVE_TOOL_CALLS, model.protocol)
        val first = model.complete(messages)
        assertEquals(SearchAgentProtocol.STRUCTURED_INTENT, model.protocol)
        assertTrue(first is SearchAgentReply.StructuredIntent)
        val second = model.complete(messages)
        assertTrue(second is SearchAgentReply.StructuredIntent)
        assertEquals(listOf(1, 0, 0), sent.map { it.tools.size })
        assertTrue(sent[1].messages.any { it.role == com.projectnuke.fusion.ai.model.AiRole.SYSTEM && it.content.contains("Structured tool-intent mode") })
        assertFalse(sent[1].messages.any { it.content.contains("Use the web_search function for a public query") })
        assertEquals(0, egress.pendingTicketCountForTest())
    }

    @Test
    fun nativeRejectionThenAuthorizationRevocationDoesNotLeakOrReuseAnEgressTicket() = runTest {
        val provider = AiProviderConfig(
            id = "official-openai",
            type = AiProviderType.OPENAI,
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            modelId = "search-planner",
            apiKeySecretId = null,
            authMode = com.projectnuke.fusion.ai.model.AiProviderAuthMode.NONE,
        )
        val source = object : ExternalAiProviderSource {
            override suspend fun getSelectedRunnableProvider() = provider
            override suspend fun getRunnableProviderById(id: String) = provider.takeIf { id == provider.id }
        }
        val egress = DataEgressController()
        var authorized = true
        var calls = 0
        val runner = ExternalAiChatRunner(
            source,
            EgressGateChatClient(
                object : ChatClient {
                    override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse {
                        calls++
                        authorized = false
                        throw AiProviderClientException("native tools rejected", nativeToolProtocolUnsupported = true)
                    }
                },
                egress,
            ),
        )
        val model = ExternalSearchAgentModel.create(
            runner,
            egress,
            "revoked-native-retry",
            "public query",
            provider.id,
            true,
            null,
            requestStillAuthorized = { authorized },
        )!!

        val result = model.complete(listOf(SearchAgentMessage(SearchAgentMessageRole.USER, "User request: public query")))

        assertTrue(result is SearchAgentReply.Failed)
        assertEquals(1, calls)
        assertEquals(SearchAgentProtocol.STRUCTURED_INTENT, model.protocol)
        assertEquals(0, egress.pendingTicketCountForTest())
    }

    @Test
    fun cancellationDuringStructuredRetryPropagatesAndDisarmsEveryEgressTicket() = runTest {
        val provider = AiProviderConfig(
            id = "official-openai",
            type = AiProviderType.OPENAI,
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            modelId = "search-planner",
            apiKeySecretId = null,
            authMode = com.projectnuke.fusion.ai.model.AiProviderAuthMode.NONE,
        )
        val source = object : ExternalAiProviderSource {
            override suspend fun getSelectedRunnableProvider() = provider
            override suspend fun getRunnableProviderById(id: String) = provider.takeIf { id == provider.id }
        }
        val egress = DataEgressController()
        var calls = 0
        val runner = ExternalAiChatRunner(
            source,
            EgressGateChatClient(
                object : ChatClient {
                    override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse {
                        calls++
                        if (request.tools.isNotEmpty()) {
                            throw AiProviderClientException("native tools rejected", nativeToolProtocolUnsupported = true)
                        }
                        throw CancellationException("structured retry cancelled")
                    }
                },
                egress,
            ),
        )
        val model = ExternalSearchAgentModel.create(
            runner,
            egress,
            "cancelled-native-retry",
            "public query",
            provider.id,
            true,
            null,
            requestStillAuthorized = { true },
        )!!

        try {
            model.complete(listOf(SearchAgentMessage(SearchAgentMessageRole.USER, "User request: public query")))
            throw AssertionError("expected structured retry cancellation")
        } catch (cancelled: CancellationException) {
            assertEquals("structured retry cancelled", cancelled.message)
        }

        assertEquals(2, calls)
        assertEquals(SearchAgentProtocol.STRUCTURED_INTENT, model.protocol)
        assertEquals(0, egress.pendingTicketCountForTest())
    }

    @Test
    fun nativePreflightDowngradeRebuildsPromptForStructuredIntentBeforeTransport() = runTest {
        val nativeProvider = AiProviderConfig(
            id = "provider-switch",
            type = AiProviderType.OPENAI,
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            modelId = "planner",
            apiKeySecretId = null,
            authMode = com.projectnuke.fusion.ai.model.AiProviderAuthMode.NONE,
        )
        val structuredProvider = nativeProvider.copy(
            type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            baseUrl = "https://custom.example/v1",
        )
        var lookups = 0
        val providerSource = object : ExternalAiProviderSource {
            override suspend fun getSelectedRunnableProvider() = nativeProvider
            override suspend fun getRunnableProviderById(id: String): AiProviderConfig? {
                if (id != nativeProvider.id) return null
                lookups++
                return if (lookups == 1) nativeProvider else structuredProvider
            }
        }
        val sent = mutableListOf<AiChatRequest>()
        val egress = DataEgressController()
        val runner = ExternalAiChatRunner(
            providerSource,
            EgressGateChatClient(
                object : ChatClient {
                    override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse {
                        sent += request
                        return AiChatResponse("response", config.modelId, """{"action":"NO_MORE_SEARCH"}""")
                    }
                },
                egress,
            ),
        )
        val model = ExternalSearchAgentModel.create(
            runner = runner,
            egressController = egress,
            requestId = "preflight-downgrade",
            originalUserQuery = "public typed query",
            providerId = nativeProvider.id,
            providerSelectionFrozen = true,
            providerRequestSnapshot = null,
            requestStillAuthorized = { true },
        )!!

        assertEquals(SearchAgentProtocol.NATIVE_TOOL_CALLS, model.protocol)
        val reply = model.complete(
            listOf(
                SearchAgentMessage(SearchAgentMessageRole.SYSTEM, SearchAgentProtocolPrompt.NATIVE_TOOL_CALLS),
                SearchAgentMessage(SearchAgentMessageRole.USER, "User request: public typed query"),
            ),
        )

        assertTrue(reply is SearchAgentReply.StructuredIntent)
        assertEquals(SearchAgentProtocol.STRUCTURED_INTENT, model.protocol)
        assertEquals(1, sent.size)
        assertTrue(sent.single().tools.isEmpty())
        assertTrue(sent.single().messages.any {
            it.role == com.projectnuke.fusion.ai.model.AiRole.SYSTEM &&
                it.content.contains("Structured tool-intent mode")
        })
        assertFalse(sent.single().messages.any { it.content.contains("Use the web_search function for a public query") })
        assertEquals(0, egress.pendingTicketCountForTest())
    }

    @Test
    fun externalAdapterStopsBeforeTransportWhenRequestAuthorizationIsRevoked() = runTest {
        val provider = AiProviderConfig(
            id = "custom",
            type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Custom",
            baseUrl = "https://custom.example/v1",
            modelId = "m",
            apiKeySecretId = null,
            authMode = com.projectnuke.fusion.ai.model.AiProviderAuthMode.NONE,
        )
        val source = object : ExternalAiProviderSource {
            override suspend fun getSelectedRunnableProvider() = provider
            override suspend fun getRunnableProviderById(id: String) = provider.takeIf { id == provider.id }
        }
        var calls = 0
        val egress = DataEgressController()
        val runner = ExternalAiChatRunner(source, object : ChatClient {
            override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse {
                calls++
                return AiChatResponse("id", config.modelId, "{}")
            }
        })
        val model = ExternalSearchAgentModel.create(
            runner,
            egress,
            "revoked-request",
            "public query",
            provider.id,
            true,
            null,
            requestStillAuthorized = { false },
        )!!

        val result = model.complete(listOf(SearchAgentMessage(SearchAgentMessageRole.USER, "public query")))

        assertTrue(result is SearchAgentReply.Failed)
        assertFalse((result as SearchAgentReply.Failed).recoverable)
        assertEquals(0, calls)
    }
}
