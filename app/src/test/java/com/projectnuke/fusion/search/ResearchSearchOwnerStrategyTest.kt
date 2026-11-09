package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ManualSearchQuery
import com.projectnuke.fusion.chat.ManualSearchQueryValidation
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import com.projectnuke.fusion.model.GenerationSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchSearchOwnerStrategyTest {
    private fun request(
        strategy: SearchStrategySnapshot = SearchStrategySnapshot.DEFAULT,
        policy: GenerationRequestSnapshot.WebSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
    ) = GenerationRequestSnapshot(
        requestId = "application-search-request",
        conversationId = 4L,
        generationModeKey = "LOCAL_MODEL",
        selectedModelId = "local",
        selectedModelPath = "active-model",
        settings = GenerationSettings(),
        reasoningEnabled = false,
        webSearchPolicy = policy,
        attachmentIds = emptyList(),
        multimodalImagePaths = emptyList(),
        promptText = "public question",
        rawUserText = "public question",
        createdAt = 1L,
        interactionMode = ChatInteractionMode.RESEARCH,
        searchStrategy = strategy,
    )

    private fun privacy(query: String, manual: String? = null) = AgenticSearchPrivacySnapshot(
        requestId = "application-search-request",
        typedUserQuery = query,
        explicitManualQuery = manual,
        requestStillAuthorized = { true },
    )

    private fun evidence(query: String) = FusionSearchResponse(
        query = query,
        normalizedQuery = query,
        intent = SearchIntent.GENERAL,
        results = listOf(
            FusionSearchResult(
                title = "Public source",
                source = "Example",
                url = "https://example.test/source",
                snippet = "A public result.",
                publishedAt = null,
                queryUsed = query,
            ),
        ),
        debugMessage = null,
        sources = listOf(
            WebSearchSource(
                title = "Public source",
                source = "Example",
                url = "https://example.test/source",
                snippet = "A public result.",
                publishedAt = null,
                providerType = WebSearchProviderType.FREE_DEFAULT,
                providerDisplayName = WebSearchProviderType.FREE_DEFAULT.defaultDisplayName(),
                searchCategory = null,
                queryUsed = query,
                rank = 1,
            ),
        ),
    )

    @Test
    fun defaultRequestUsesAgentHarnessAndReturnsItsFinalSourceSnapshot() = runTest {
        val queries = mutableListOf<String>()
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            var round = 0
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                if (round++ == 0) SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["public question"]}""")
                else SearchAgentReply.StructuredIntent("""{"action":"NO_MORE_SEARCH"}""")
        }
        val result = ResearchSearchOwner.searchWithStrategy(
            request = request(),
            previousUserMessage = null,
            providerRepository = null,
            privacySnapshot = privacy("public question"),
            agentModel = model,
            ownerStillActive = { true },
            limits = AgenticSearchLimits.LOCAL_DEFAULT,
            exactSearchOverride = AgenticExactSearchTool { query, _, _ ->
                queries += query
                evidence(query)
            },
        )

        assertEquals(listOf("public question"), queries)
        assertEquals(SearchStrategy.AGENT_HARNESS, result.selectedStrategy)
        assertEquals(SearchStrategy.AGENT_HARNESS, result.executedStrategy)
        assertEquals(SearchRouteOutcome.COMPLETED, result.outcome)
        assertEquals("https://example.test/source", result.response?.sources?.single()?.url)
        assertEquals(1, result.searchQueriesIssued)
    }

    @Test
    fun explicitLegacySelectionUsesTheSameApprovedTransportWithoutAgentCalls() = runTest {
        var agentCalls = 0
        val query = "latest public climate report"
        val searchPlan = RuleBasedWebSearchPlanner.plan(RuleBasedWebSearchPlannerInput(query))
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
                agentCalls++
                return SearchAgentReply.Failed("should_not_run")
            }
        }
        val routeRequest = request(
            strategy = SearchStrategySnapshot(
                strategy = SearchStrategy.LEGACY_RULE_BASED,
                allowLegacyFallback = true,
                manualQuery = ManualSearchQueryValidation.Missing,
            ),
        ).copy(rawUserText = query, promptText = query)
        val result = ResearchSearchOwner.searchWithStrategy(
            routeRequest,
            previousUserMessage = null,
            providerRepository = null,
            privacySnapshot = privacy(query),
            agentModel = model,
            ownerStillActive = { true },
            limits = AgenticSearchLimits.LOCAL_DEFAULT,
            exactSearchOverride = AgenticExactSearchTool { exactQuery, _, _ -> evidence(exactQuery) },
        )

        assertEquals(0, agentCalls)
        assertTrue(searchPlan.primaryQuery.isNotBlank())
        assertEquals(SearchStrategy.LEGACY_RULE_BASED, result.executedStrategy)
        assertEquals(SearchRouteOutcome.COMPLETED, result.outcome)
        assertTrue(result.searchQueriesIssued > 0)
    }

    @Test
    fun manualModeUsesOnlyTheCapturedExplicitRequestQuery() = runTest {
        val manual = (ManualSearchQuery.parse("exact manual public query") as ManualSearchQueryValidation.Valid).query.value
        val routeRequest = request(
            strategy = SearchStrategySnapshot(
                strategy = SearchStrategy.MANUAL_QUERY,
                allowLegacyFallback = true,
                manualQuery = ManualSearchQuery.parse(manual),
            ),
        )
        val queries = mutableListOf<String>()
        val result = ResearchSearchOwner.searchWithStrategy(
            routeRequest,
            previousUserMessage = null,
            providerRepository = null,
            privacySnapshot = privacy("public question", manual),
            agentModel = null,
            ownerStillActive = { true },
            limits = AgenticSearchLimits.LOCAL_DEFAULT,
            exactSearchOverride = AgenticExactSearchTool { query, _, _ ->
                queries += query
                evidence(query)
            },
        )

        assertEquals(listOf(manual), queries)
        assertEquals(SearchStrategy.MANUAL_QUERY, result.executedStrategy)
        assertEquals(SearchRouteOutcome.COMPLETED, result.outcome)
        assertEquals(1, result.searchQueriesIssued)
    }

    @Test
    fun strategySelectionCannotOverrideDisabledWebSearch() = runTest {
        var searches = 0
        val result = ResearchSearchOwner.searchWithStrategy(
            request(policy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED),
            previousUserMessage = null,
            providerRepository = null,
            privacySnapshot = privacy("public question"),
            agentModel = null,
            ownerStillActive = { true },
            limits = AgenticSearchLimits.LOCAL_DEFAULT,
            exactSearchOverride = AgenticExactSearchTool { query, _, _ ->
                searches++
                evidence(query)
            },
        )

        assertEquals(0, searches)
        assertEquals(SearchRouteOutcome.SEARCH_DISABLED, result.outcome)
        assertNull(result.response)
    }

    @Test
    fun researchBoundingKeepsMatchingUrlLessProvenanceAndDropsUnrelatedSource() {
        val retainedResult = FusionSearchResult(
            title = "DuckDuckGo instant result",
            source = "Instant Answer",
            url = null,
            snippet = "A useful URL-less public snippet.",
            publishedAt = null,
            queryUsed = "public topic",
        )
        val matchingSource = WebSearchSource(
            title = retainedResult.title,
            source = retainedResult.source,
            url = null,
            snippet = retainedResult.snippet,
            publishedAt = null,
            providerType = WebSearchProviderType.FREE_DEFAULT,
            providerDisplayName = retainedResult.providerDisplayName,
            searchCategory = null,
            queryUsed = retainedResult.queryUsed,
            rank = 1,
        )
        val unrelatedSource = matchingSource.copy(
            title = "Unrelated URL-less source",
            snippet = "Not produced by the retained result.",
            queryUsed = "different query",
            rank = 2,
        )
        val response = FusionSearchResponse(
            query = "public topic",
            normalizedQuery = "public topic",
            intent = SearchIntent.GENERAL,
            results = listOf(retainedResult),
            debugMessage = null,
            sources = listOf(matchingSource, unrelatedSource),
        )

        val bounded = ResearchSearchOwner.bound(response, ChatInteractionMode.RESEARCH)

        assertEquals(listOf(retainedResult), bounded.results)
        assertEquals(listOf(matchingSource), bounded.sources)
        assertTrue(bounded.sources.size <= com.projectnuke.fusion.chat.ResearchRunPolicy.MAX_WEB_SOURCES)
    }
}
