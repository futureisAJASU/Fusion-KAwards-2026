package com.projectnuke.fusion.search

import com.projectnuke.fusion.ai.ExternalAiChatRunner
import com.projectnuke.fusion.ai.ExternalAiProviderSource
import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiChatResponse
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.network.ChatClient
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ManualSearchQueryValidation
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import com.projectnuke.fusion.llm.LiteRtLlmEngine
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.privacy.DataEgressController
import com.projectnuke.fusion.privacy.DataEgressPrivateTextLedger
import com.projectnuke.fusion.privacy.EgressGateChatClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class PlannerPrivateFragmentBoundaryTest {
    @Test
    fun registeredPrivateFragmentDoesNotReachExternalPlanner() = runTest {
        val saved = DataEgressPrivateTextLedger.currentTexts()
        try {
            DataEgressPrivateTextLedger.clear()
            val excerpt = "Synthetic confidential passage: cobalt heron migration schedule belongs to document alpha."
            val fragment = "cobalt heron migration schedule"
            DataEgressPrivateTextLedger.register(listOf(excerpt))
            val prior = "Please consider $fragment"
            val provider = AiProviderConfig(
                id = "fake-planner", type = AiProviderType.OPENAI, displayName = "Fake",
                baseUrl = "https://api.openai.com/v1", modelId = "fake", apiKeySecretId = null,
                authMode = com.projectnuke.fusion.ai.model.AiProviderAuthMode.NONE,
            )
            var registerDuringProviderResolution = false
            val source = object : ExternalAiProviderSource {
                override suspend fun getSelectedRunnableProvider() = provider
                override suspend fun getRunnableProviderById(id: String): AiProviderConfig? {
                    if (registerDuringProviderResolution) DataEgressPrivateTextLedger.register(listOf(excerpt))
                    return provider.takeIf { it.id == id }
                }
            }
            val sent = mutableListOf<AiChatRequest>()
            val controller = DataEgressController()
            val runner = ExternalAiChatRunner(source, EgressGateChatClient(object : ChatClient {
                override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse {
                    sent += request
                    return AiChatResponse("fake", config.modelId, "")
                }
            }, controller))
            // These Android-owned collaborators are not touched by this external,
            // no-fallback route; allocating them avoids constructing Android services.
            val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null) as sun.misc.Unsafe
            val executor = ApplicationSearchStrategyExecutor(
                localEngine = unsafe.allocateInstance(LiteRtLlmEngine::class.java) as LiteRtLlmEngine,
                externalRunner = runner,
                egressController = controller,
                providerRepository = unsafe.allocateInstance(WebSearchProviderRepository::class.java) as WebSearchProviderRepository,
                diagnosticsSink = SearchStrategyDiagnosticsSink { },
            )
            val request = GenerationRequestSnapshot(
                requestId = "fragment-test", conversationId = 1L, generationModeKey = "EXTERNAL_AI_API",
                selectedModelId = null, selectedModelPath = null, settings = GenerationSettings(),
                reasoningEnabled = false, webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                attachmentIds = emptyList(), multimodalImagePaths = emptyList(), promptText = "public query",
                rawUserText = "public query", createdAt = 1L, externalProviderId = provider.id,
                externalProviderSelectionFrozen = true,
                safeWebSearchPriorUserTurns = buildSafeWebSearchPriorTurns(listOf(prior)),
                searchStrategy = SearchStrategySnapshot(SearchStrategy.AGENT_HARNESS, false, ManualSearchQueryValidation.Missing),
            )
            executor.execute(request, prior) { true }
            assertEquals(1, sent.size)
            assertFalse("private fragment reached fake external transport", sent.single().messages.any { it.content.contains(fragment) })

            val saturationCases = listOf(
                (0 until 64).map { "synthetic-entry-$it" },
                listOf("x".repeat(65_536)),
                listOf("x".repeat(65_537)),
            )
            saturationCases.forEachIndexed { index, entries ->
                DataEgressPrivateTextLedger.clear()
                DataEgressPrivateTextLedger.register(entries)
                DataEgressPrivateTextLedger.register(listOf(excerpt))
                sent.clear()
                executor.execute(request.copy(requestId = "saturated-$index"), prior) { true }
                assertEquals("one synthetic planner request for saturation case $index", 1, sent.size)
                assertFalse("private fragment escaped saturation case $index", sent.single().messages.any { it.content.contains(fragment) })
            }

            DataEgressPrivateTextLedger.clear()
            sent.clear()
            val publicPrior = "A public question about Jupiter"
            executor.execute(
                request.copy(requestId = "public-history", safeWebSearchPriorUserTurns = listOf(publicPrior)),
                publicPrior,
            ) { true }
            assertEquals(1, sent.size)
            assertFalse("unknown-origin history must not enter external planning", sent.single().messages.any { it.content.contains(publicPrior) })

            // Clearing the process ledger models restored legacy history: the
            // raw stored turn has no durable marker identifying its origin.
            sent.clear()
            executor.execute(request.copy(requestId = "restored-unknown"), prior) { true }
            assertEquals(1, sent.size)
            assertFalse("restored fragment reached external planning", sent.single().messages.any { it.content.contains(fragment) })

            // Registration after request capture must invalidate its planner
            // authority before the fake transport is called.
            sent.clear()
            registerDuringProviderResolution = true
            executor.execute(request.copy(requestId = "registration-race"), prior) { true }
            assertEquals("changed privacy ledger must deny the frozen planner", 0, sent.size)
        } finally {
            DataEgressPrivateTextLedger.clear()
            DataEgressPrivateTextLedger.register(saved)
        }
    }
}
