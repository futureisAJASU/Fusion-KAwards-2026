package com.projectnuke.fusion.ai

import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiChatResponse
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.ai.model.AgenticWebSearchTool
import com.projectnuke.fusion.ai.model.AiToolCall
import com.projectnuke.fusion.ai.network.AiProviderClientException
import com.projectnuke.fusion.ai.network.ChatClient
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAiChatRunnerTest {

    @Test
    fun `official OpenAI planner request carries native tool protocol through gated client seam`() = runBlocking {
        val provider = AiProviderConfig(
            id = "openai", type = AiProviderType.OPENAI, displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1", modelId = "gpt-5.5", apiKeySecretId = "key",
        )
        var captured: AiChatRequest? = null
        val source = object : ExternalAiProviderSource {
            override suspend fun getSelectedRunnableProvider(): AiProviderConfig? = provider
            override suspend fun getRunnableProviderById(id: String): AiProviderConfig? = provider.takeIf { id == it.id }
        }
        val runner = ExternalAiChatRunner(source, object : ChatClient {
            override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse {
                captured = request
                return AiChatResponse(
                    id = "response",
                    model = config.modelId,
                    content = "",
                    toolCalls = listOf(AiToolCall("call-1", "web_search", """{"queries":["public query"]}""")),
                    finishReason = "tool_calls",
                )
            }
        })
        val prepared = runner.prepareSearchPlannerTurn(
            messages = listOf(AiMessage(AiRole.USER, "public query")),
            toolDefinitions = listOf(AgenticWebSearchTool.definition()),
            providerId = "openai",
            providerSelectionFrozen = true,
            maxTokensOverride = 128,
            providerRequestSnapshot = null,
            allowNativeToolCalling = true,
        ) as ExternalSearchPlannerPreparation.Ready
        assertTrue(prepared.nativeToolCalling)
        assertEquals(listOf(AgenticWebSearchTool.definition()), prepared.transportRequest.tools)

        val response = runner.completeSearchPlannerTurn(prepared, "request-1")
        assertEquals("call-1", response.toolCalls.single().id)
        assertEquals(prepared.transportRequest, captured)
    }

    @Test
    fun `custom compatible planner request uses structured intent without native schema`() = runBlocking {
        val provider = AiProviderConfig(
            id = "custom", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE, displayName = "Custom",
            baseUrl = "https://custom.example/v1", modelId = "model", apiKeySecretId = "key",
        )
        val source = object : ExternalAiProviderSource {
            override suspend fun getSelectedRunnableProvider(): AiProviderConfig? = provider
            override suspend fun getRunnableProviderById(id: String): AiProviderConfig? = provider.takeIf { id == it.id }
        }
        val runner = ExternalAiChatRunner(source, object : ChatClient {
            override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse =
                AiChatResponse("id", config.modelId, """{"action":"NO_MORE_SEARCH"}""")
        })
        val prepared = runner.prepareSearchPlannerTurn(
            messages = listOf(AiMessage(AiRole.SYSTEM, "Return the structured intent schema.")),
            toolDefinitions = listOf(AgenticWebSearchTool.definition()),
            providerId = "custom",
            providerSelectionFrozen = true,
            maxTokensOverride = 128,
            providerRequestSnapshot = null,
            allowNativeToolCalling = true,
        ) as ExternalSearchPlannerPreparation.Ready

        assertTrue(!prepared.nativeToolCalling)
        assertTrue(prepared.transportRequest.tools.isEmpty())
        assertEquals("""{"action":"NO_MORE_SEARCH"}""", runner.completeSearchPlannerTurn(prepared, "request-2").content)
    }

    @Test
    fun `frozen request uses explicit compatibility output after provider mutation`() = runBlocking {
        var provider = AiProviderConfig(
            id = "provider_a", type = AiProviderType.OPENAI, displayName = "Provider A",
            baseUrl = "https://a.example.com/", modelId = "a-model", apiKeySecretId = "a-key",
            maxTokens = null,
        )
        var captured: AiChatRequest? = null
        val source = object : ExternalAiProviderSource {
            override suspend fun getSelectedRunnableProvider(): AiProviderConfig? = provider
            override suspend fun getRunnableProviderById(id: String): AiProviderConfig? = provider
        }
        val runner = ExternalAiChatRunner(source, object : ChatClient {
            override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse {
                captured = request
                return AiChatResponse("id", "model", "ok")
            }
        })
        val frozen = ExternalAiChatRunner.COMPATIBILITY_MAX_OUTPUT_TOKENS
        provider = provider.copy(maxTokens = 32000)
        runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            providerId = "provider_a", providerSelectionFrozen = true,
            maxTokensOverride = frozen,
        )
        assertEquals(frozen, captured?.maxTokens)
    }

    @Test
    fun `frozen semantic provider config survives repository mutation`() = runBlocking {
        var provider = AiProviderConfig(
            id = "provider_a", type = AiProviderType.OPENAI, displayName = "Provider A",
            baseUrl = "https://a.example.com/", modelId = "a-model", apiKeySecretId = "a-key",
            temperature = 0.3, maxTokens = 512,
        )
        var capturedConfig: AiProviderConfig? = null
        var capturedRequest: AiChatRequest? = null
        val source = object : ExternalAiProviderSource {
            override suspend fun getSelectedRunnableProvider(): AiProviderConfig? = provider
            override suspend fun getRunnableProviderById(id: String): AiProviderConfig? = provider
        }
        val runner = ExternalAiChatRunner(source, object : ChatClient {
            override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse {
                capturedConfig = config
                capturedRequest = request
                return AiChatResponse("id", "model", "ok")
            }
        })
        val frozen = ExternalProviderRequestSnapshot.from(provider, 512)
        provider = provider.copy(baseUrl = "https://b.example.com/", modelId = "b-model", temperature = 1.2, maxTokens = 32000)
        runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            providerId = "provider_a",
            providerSelectionFrozen = true,
            providerRequestSnapshot = frozen,
        )
        assertEquals("https://a.example.com/", capturedConfig?.baseUrl)
        assertEquals("a-model", capturedConfig?.modelId)
        assertEquals(0.3, capturedConfig?.temperature)
        assertEquals(512, capturedRequest?.maxTokens)
    }

    @Test
    fun `deleted captured provider is rejected without fallback`() = runBlocking {
        val captured = AiProviderConfig(
            id = "provider_a", type = AiProviderType.OPENAI, displayName = "Provider A",
            baseUrl = "https://a.example.com/", modelId = "a-model", apiKeySecretId = "a-key",
            maxTokens = 512,
        )
        val source = object : ExternalAiProviderSource {
            override suspend fun getSelectedRunnableProvider(): AiProviderConfig? = AiProviderConfig(
                id = "provider_b", type = AiProviderType.OPENAI, displayName = "Provider B",
                baseUrl = "https://b.example.com/", modelId = "b-model", apiKeySecretId = "b-key",
            )
            override suspend fun getRunnableProviderById(id: String): AiProviderConfig? = null
        }
        val runner = ExternalAiChatRunner(source, object : ChatClient {
            override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse {
                throw AssertionError("Deleted provider must not execute")
            }
        })
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            providerRequestSnapshot = ExternalProviderRequestSnapshot.from(captured, 512),
            providerSelectionFrozen = true,
        )
        assertTrue(result is ExternalAiChatResult.NoProvider)
    }

    @Test
    fun blankContent_mapsToEmpty() = runBlocking {
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                return AiChatResponse(id = "1", model = "test", content = "")
            }
        }
        val runner = ExternalAiChatRunner(FakeProviderSource(), fakeClient)
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false
        )
        assertTrue(result is ExternalAiChatResult.Empty)
    }

    @Test
    fun blankContentWithWhitespace_mapsToEmpty() = runBlocking {
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                return AiChatResponse(id = "1", model = "test", content = "  \n\t  ")
            }
        }
        val runner = ExternalAiChatRunner(FakeProviderSource(), fakeClient)
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false
        )
        assertTrue(result is ExternalAiChatResult.Empty)
    }

    @Test
    fun nonBlankContent_mapsToSuccess() = runBlocking {
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                return AiChatResponse(id = "1", model = "test", content = "Hello!")
            }
        }
        val runner = ExternalAiChatRunner(FakeProviderSource(), fakeClient)
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false
        )
        assertTrue(result is ExternalAiChatResult.Success)
        assertEquals("Hello!", (result as ExternalAiChatResult.Success).content)
    }

    @Test
    fun explicitProviderReasoning_staysSeparateFromCanonicalAnswer() = runBlocking {
        val provider = AiProviderConfig(
            id = "test",
            type = AiProviderType.OPENAI,
            displayName = "Test",
            baseUrl = "https://example.com/",
            modelId = "test-model",
            apiKeySecretId = "test-key",
            reasoningContentEnabled = true,
        )
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest,
            ): AiChatResponse = AiChatResponse(
                id = "1",
                model = "test",
                content = "final answer",
                reasoningContent = "provider plan",
            )
        }
        val source = object : ExternalAiProviderSource {
            override suspend fun getSelectedRunnableProvider(): AiProviderConfig = provider
            override suspend fun getRunnableProviderById(id: String): AiProviderConfig? = provider.takeIf { it.id == id }
        }
        val result = ExternalAiChatRunner(source, fakeClient).generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
        ) as ExternalAiChatResult.Success
        assertEquals("final answer", result.content)
        assertEquals("provider plan", result.reasoningContent)
    }

    @Test
    fun reasoningOnlyProviderResponse_mapsToTypedEmptyResult() = runBlocking {
        val result = ExternalAiChatRunner(FakeProviderSource(), object : ChatClient {
            override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest) =
                AiChatResponse("1", "test", "", "provider plan")
        }).generateFromMessages(listOf(AiMessage(AiRole.USER, "hi"))) as ExternalAiChatResult.Empty
        assertEquals("provider plan", result.reasoningContent)
    }

    @Test
    fun clientException_mapsToError() = runBlocking {
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                throw AiProviderClientException("Test error message")
            }
        }
        val runner = ExternalAiChatRunner(FakeProviderSource(), fakeClient)
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false
        )
        assertTrue(result is ExternalAiChatResult.Error)
        assertEquals("Test error message", (result as ExternalAiChatResult.Error).message)
    }

    @Test
    fun cancellationException_isRethrown() = runBlocking {
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                throw CancellationException("test cancel")
            }
        }
        val runner = ExternalAiChatRunner(FakeProviderSource(), fakeClient)
        try {
            runner.generateFromMessages(
                messages = listOf(AiMessage(AiRole.USER, "hi")),
                hasAttachments = false
            )
            assertTrue("Expected CancellationException", false)
        } catch (e: CancellationException) {
            assertEquals("test cancel", e.message)
        }
    }

    @Test
    fun blockedAttachment_returnsBlockedAttachment() = runBlocking {
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                throw AssertionError("Should not be called")
            }
        }
        val runner = ExternalAiChatRunner(FakeProviderSource(), fakeClient)
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = true
        )
        assertTrue(result is ExternalAiChatResult.BlockedAttachment)
    }

    @Test
    fun noProvider_returnsNoProvider() = runBlocking {
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                throw AssertionError("Should not be called")
            }
        }
        val runner = ExternalAiChatRunner(NoProviderSource(), fakeClient)
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false
        )
        assertTrue(result is ExternalAiChatResult.NoProvider)
    }

    @Test
    fun nonFrozenExplicitId_usesExactLookup() = runBlocking {
        val multiSource = MultiProviderSource()
        multiSource.selectedId = "provider_b"
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                assertEquals("provider_a", config.id)
                assertEquals("https://a.example.com/", config.baseUrl)
                return AiChatResponse(id = "1", model = "a-model", content = "From A")
            }
        }
        val runner = ExternalAiChatRunner(multiSource, fakeClient)
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false,
            providerId = "provider_a"
        )
        assertTrue(result is ExternalAiChatResult.Success)
        assertEquals("From A", (result as ExternalAiChatResult.Success).content)
    }

    @Test
    fun nonFrozenExplicitDeleted_returnsNoProvider() = runBlocking {
        val multiSource = MultiProviderSource()
        multiSource.selectedId = "provider_b"
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                throw AssertionError("Should not be called")
            }
        }
        val runner = ExternalAiChatRunner(multiSource, fakeClient)
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false,
            providerId = "deleted_provider"
        )
        assertTrue(result is ExternalAiChatResult.NoProvider)
    }

    @Test
    fun nonFrozenNull_usesSelectedProvider() = runBlocking {
        val multiSource = MultiProviderSource()
        multiSource.selectedId = "provider_b"
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                assertEquals("provider_b", config.id)
                return AiChatResponse(id = "1", model = "b-model", content = "From B")
            }
        }
        val runner = ExternalAiChatRunner(multiSource, fakeClient)
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false,
            providerId = null
        )
        assertTrue(result is ExternalAiChatResult.Success)
        assertEquals("From B", (result as ExternalAiChatResult.Success).content)
    }

    @Test
    fun frozenProviderA_usedEvenIfSelectionIsB() = runBlocking {
        val multiSource = MultiProviderSource()
        multiSource.selectedId = "provider_b"
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                assertEquals("provider_a", config.id)
                return AiChatResponse(id = "1", model = "a-model", content = "From A")
            }
        }
        val runner = ExternalAiChatRunner(multiSource, fakeClient)
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false,
            providerId = "provider_a",
            providerSelectionFrozen = true
        )
        assertTrue(result is ExternalAiChatResult.Success)
        assertEquals("From A", (result as ExternalAiChatResult.Success).content)
        assertEquals(0, multiSource.selectedProviderCallCount)
        assertEquals(1, multiSource.exactLookupCallCount)
    }

    @Test
    fun frozenDeletedProvider_returnsNoProvider() = runBlocking {
        val multiSource = MultiProviderSource()
        multiSource.selectedId = "provider_b"
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                throw AssertionError("Should not be called")
            }
        }
        val runner = ExternalAiChatRunner(multiSource, fakeClient)
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false,
            providerId = "deleted_provider",
            providerSelectionFrozen = true
        )
        assertTrue(result is ExternalAiChatResult.NoProvider)
        assertEquals(0, multiSource.selectedProviderCallCount)
    }

    @Test
    fun frozenDisabledProvider_returnsNoProvider() = runBlocking {
        val source = object : ExternalAiProviderSource {
            var selectedProviderCallCount = 0
            var exactLookupCallCount = 0

            override suspend fun getSelectedRunnableProvider(): AiProviderConfig? {
                selectedProviderCallCount++
                return AiProviderConfig(
                    id = "enabled", type = AiProviderType.OPENAI, displayName = "Enabled",
                    baseUrl = "https://enabled.example.com/", modelId = "e-model", apiKeySecretId = "e-key"
                )
            }

            override suspend fun getRunnableProviderById(id: String): AiProviderConfig? {
                exactLookupCallCount++
                if (id == "disabled") return null
                return AiProviderConfig(
                    id = "enabled", type = AiProviderType.OPENAI, displayName = "Enabled",
                    baseUrl = "https://enabled.example.com/", modelId = "e-model", apiKeySecretId = "e-key"
                )
            }
        }
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                throw AssertionError("Should not be called")
            }
        }
        val runner = ExternalAiChatRunner(source, fakeClient)
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false,
            providerId = "disabled",
            providerSelectionFrozen = true
        )
        assertTrue(result is ExternalAiChatResult.NoProvider)
        assertEquals(0, source.selectedProviderCallCount)
        assertEquals(1, source.exactLookupCallCount)
    }

    @Test
    fun frozenNullId_returnsNoProvider() = runBlocking {
        val multiSource = MultiProviderSource()
        multiSource.selectedId = "provider_b"
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                throw AssertionError("Should not be called")
            }
        }
        val runner = ExternalAiChatRunner(multiSource, fakeClient)
        val result = runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false,
            providerId = null,
            providerSelectionFrozen = true
        )
        assertTrue(result is ExternalAiChatResult.NoProvider)
        assertEquals(0, multiSource.selectedProviderCallCount)
    }

    @Test
    fun frozenNullId_neverCallsSelectedProvider() = runBlocking {
        val multiSource = MultiProviderSource()
        multiSource.selectedId = "provider_b"
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                throw AssertionError("Should not be called")
            }
        }
        val runner = ExternalAiChatRunner(multiSource, fakeClient)
        runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false,
            providerId = null,
            providerSelectionFrozen = true
        )
        assertEquals(0, multiSource.selectedProviderCallCount)
    }

    @Test
    fun frozenExactLookup_doesNotMutateSelection() = runBlocking {
        val multiSource = MultiProviderSource()
        multiSource.selectedId = "provider_b"
        val fakeClient = object : ChatClient {
            override suspend fun chatCompletion(
                config: AiProviderConfig,
                request: AiChatRequest
            ): AiChatResponse {
                return AiChatResponse(id = "1", model = "a-model", content = "From A")
            }
        }
        val runner = ExternalAiChatRunner(multiSource, fakeClient)
        runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, "hi")),
            hasAttachments = false,
            providerId = "provider_a",
            providerSelectionFrozen = true
        )
        assertEquals("provider_b", multiSource.selectedId)
    }

    @Test
    fun resolveWebSearchPolicy_attachmentBlockedAndManualEnabled_returnsDisabled() {
        assertEquals(
            GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            GenerationRequestSnapshot.resolveWebSearchPolicy(
                externalApiAttachmentBlocked = true,
                webSearchEnabled = true,
                autoWebSearchSuggested = false
            )
        )
    }

    @Test
    fun resolveWebSearchPolicy_attachmentBlockedAndAutoSuggested_returnsDisabled() {
        assertEquals(
            GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            GenerationRequestSnapshot.resolveWebSearchPolicy(
                externalApiAttachmentBlocked = true,
                webSearchEnabled = false,
                autoWebSearchSuggested = true
            )
        )
    }

    @Test
    fun resolveWebSearchPolicy_manualEnabled_returnsEnabled() {
        assertEquals(
            GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            GenerationRequestSnapshot.resolveWebSearchPolicy(
                externalApiAttachmentBlocked = false,
                webSearchEnabled = true,
                autoWebSearchSuggested = false
            )
        )
    }

    @Test
    fun resolveWebSearchPolicy_autoSuggested_returnsAuto() {
        assertEquals(
            GenerationRequestSnapshot.WebSearchPolicy.AUTO,
            GenerationRequestSnapshot.resolveWebSearchPolicy(
                externalApiAttachmentBlocked = false,
                webSearchEnabled = false,
                autoWebSearchSuggested = true
            )
        )
    }

    @Test
    fun resolveWebSearchPolicy_neither_returnsDisabled() {
        assertEquals(
            GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            GenerationRequestSnapshot.resolveWebSearchPolicy(
                externalApiAttachmentBlocked = false,
                webSearchEnabled = false,
                autoWebSearchSuggested = false
            )
        )
    }

    private class FakeProviderSource : ExternalAiProviderSource {
        override suspend fun getSelectedRunnableProvider(): AiProviderConfig? {
            return AiProviderConfig(
                id = "test",
                type = AiProviderType.OPENAI,
                displayName = "Test",
                baseUrl = "https://example.com/",
                modelId = "test-model",
                apiKeySecretId = "test-key"
            )
        }

        override suspend fun getRunnableProviderById(id: String): AiProviderConfig? {
            if (id != "test") return null
            return AiProviderConfig(
                id = "test",
                type = AiProviderType.OPENAI,
                displayName = "Test",
                baseUrl = "https://example.com/",
                modelId = "test-model",
                apiKeySecretId = "test-key"
            )
        }
    }

    private class NoProviderSource : ExternalAiProviderSource {
        override suspend fun getSelectedRunnableProvider(): AiProviderConfig? = null
        override suspend fun getRunnableProviderById(id: String): AiProviderConfig? = null
    }

    private class MultiProviderSource : ExternalAiProviderSource {
        var selectedId: String? = "provider_b"
        var selectedProviderCallCount = 0
        var exactLookupCallCount = 0

        private val providers = listOf(
            AiProviderConfig(
                id = "provider_a",
                type = AiProviderType.OPENAI,
                displayName = "Provider A",
                baseUrl = "https://a.example.com/",
                modelId = "a-model",
                apiKeySecretId = "a-key"
            ),
            AiProviderConfig(
                id = "provider_b",
                type = AiProviderType.OPENAI,
                displayName = "Provider B",
                baseUrl = "https://b.example.com/",
                modelId = "b-model",
                apiKeySecretId = "b-key"
            ),
        )

        override suspend fun getSelectedRunnableProvider(): AiProviderConfig? {
            selectedProviderCallCount++
            return providers.firstOrNull { it.id == selectedId }
        }

        override suspend fun getRunnableProviderById(id: String): AiProviderConfig? {
            exactLookupCallCount++
            val p = providers.firstOrNull { it.id == id }
            if (p != null && p.isEnabled && !p.apiKeySecretId.isNullOrBlank() && p.baseUrl.isNotBlank() && p.modelId.isNotBlank()) return p
            return null
        }
    }
}
