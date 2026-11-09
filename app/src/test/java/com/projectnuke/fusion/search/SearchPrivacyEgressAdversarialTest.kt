package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.privacy.DataEgressPolicy
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPrivacyEgressAdversarialTest {
    @Test
    fun manualSensitiveQueriesAreDeniedAtExactSearchBoundary() = runTest {
        val sensitive = listOf("ｐｒｉｖａｔｅ＠ｅｘａｍｐｌｅ．ｃｏｍ", "private\u200B@example.com", "０１０\u00A0１２３４\u2003５６７８", "900101-1234567")
        val calls = mutableListOf<String>()
        for (query in sensitive + "public planet facts") {
            val parsed = com.projectnuke.fusion.chat.ManualSearchQuery.parse(query) as com.projectnuke.fusion.chat.ManualSearchQueryValidation.Valid
            val request = GenerationRequestSnapshot(
                requestId = "manual-boundary", conversationId = 41L, generationModeKey = "LOCAL_MODEL",
                selectedModelId = "local", selectedModelPath = "model", settings = GenerationSettings(),
                reasoningEnabled = false, webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                attachmentIds = emptyList(), multimodalImagePaths = emptyList(), promptText = "public search",
                rawUserText = "public search", createdAt = 1L,
                searchStrategy = com.projectnuke.fusion.chat.SearchStrategySnapshot(
                    com.projectnuke.fusion.chat.SearchStrategy.MANUAL_QUERY, false, parsed,
                ),
            )
            val privacy = AgenticSearchPrivacySnapshot(
                requestId = request.requestId, typedUserQuery = request.rawUserText,
                explicitManualQuery = query, requestStillAuthorized = { true },
            )
            val result = ResearchSearchOwner.searchWithStrategy(
                request, null, null, privacy, null, { true }, AgenticSearchLimits.LOCAL_DEFAULT,
                exactSearchOverride = AgenticExactSearchTool { issued, _, _ -> calls += issued; response(issued) },
            )
            if (query in sensitive) {
                assertEquals(SearchRouteOutcome.PRIVACY_DENIED, result.outcome)
                assertEquals(0, calls.size)
            } else {
                assertEquals(listOf(query), calls)
            }
        }
    }
    @Test
    fun manualQueriesRejectUnicodeObfuscatedSensitiveIdentifiers() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val queries = listOf(
                "private@example.com",
                "ｐｒｉｖａｔｅ＠ｅｘａｍｐｌｅ．ｃｏｍ",
                "private\u200B@exam\u200Dple.com",
                "private\u200C@exam\u202Eple.com",
                "０１０\u00A0１２３４\u2003５６７８",
                "900101-1234567",
            )
            for (query in queries) {
                val parsed = com.projectnuke.fusion.chat.ManualSearchQuery.parse(query)
                assertTrue("manual validation: $query", parsed is com.projectnuke.fusion.chat.ManualSearchQueryValidation.Valid)
                val snapshot = AgenticSearchPrivacySnapshot(
                    requestId = "manual-sensitive", typedUserQuery = "public search",
                    explicitManualQuery = query, requestStillAuthorized = { true },
                )
                assertTrue("manual authorization: $query", RequestScopedAgenticSearchQueryAuthorizer(snapshot)
                    .authorize(query, query) is AgenticQueryAuthorization.Denied)
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun productionResearchRouteDoesNotAuthorizeTermsOnlyPresentInSafePriorTurns() = runTest {
        val currentQuery = "다른 출처도 봐줘"
        val priorTurn = "SK하이닉스 000660 주가 알려줘"
        val contextualPlan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(
                currentUserInput = currentQuery,
                previousUserMessage = priorTurn,
                safePriorUserTurns = listOf(priorTurn),
            ),
        )
        assertTrue(contextualPlan.primaryQuery.contains("하이닉스"))

        val request = GenerationRequestSnapshot(
            requestId = "prior-authority-request",
            conversationId = 41L,
            generationModeKey = "LOCAL_MODEL",
            selectedModelId = "local",
            selectedModelPath = "active-model",
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = currentQuery,
            rawUserText = currentQuery,
            createdAt = 1L,
            interactionMode = ChatInteractionMode.RESEARCH,
        )
        val snapshot = AgenticSearchPrivacySnapshot(
            requestId = request.requestId,
            typedUserQuery = currentQuery,
            approvedLegacyQueries = approvedLegacyQueriesFromTypedInput(currentQuery),
            approvedPriorUserTurns = listOf(priorTurn),
            requestStillAuthorized = { true },
        )
        var outboundQueries = 0
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>) =
                SearchAgentReply.StructuredIntent(
                    """{"action":"SEARCH","queries":[${org.json.JSONObject.quote(contextualPlan.primaryQuery)}]}""",
                )
        }
        val route = ResearchSearchOwner.searchWithStrategy(
            request = request,
            previousUserMessage = priorTurn,
            providerRepository = null,
            privacySnapshot = snapshot,
            agentModel = model,
            ownerStillActive = { true },
            limits = AgenticSearchLimits(maxDecisionRounds = 1, maxQueriesPerRound = 1, maxIssuedQueries = 1),
            exactSearchOverride = AgenticExactSearchTool { query, _, _ ->
                outboundQueries++
                response(query)
            },
        )

        assertEquals("A prior-turn-only term must not authorize transport", 0, outboundQueries)
        assertEquals(SearchRouteOutcome.PRIVACY_DENIED, route.outcome)
    }

    @Test
    fun queryAndPrivacyNormalizationAreLocaleInvariantAndCompatibilityAware() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(queryIdentity("INDEX\t Query"), queryIdentity("index query"))
            assertEquals(queryIdentity("ＡＢＣ\u200B topic"), queryIdentity("ABC topic"))
            assertTrue(
                DataEgressPolicy.webQueryContainsPrivate("id７ public topic", listOf("ID7"), emptyList()),
            )
            assertTrue(queryIdentity("한국어 검색") != queryIdentity("English search"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private fun response(query: String): FusionSearchResponse {
        val result = FusionSearchResult(
            title = "Public evidence",
            source = "Example",
            url = "https://example.test/source",
            snippet = "A public result for $query",
            publishedAt = null,
            queryUsed = query,
        )
        return FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.GENERAL,
            results = listOf(result),
            debugMessage = null,
            sources = listOf(
                WebSearchSource(
                    title = result.title,
                    source = result.source,
                    url = result.url,
                    snippet = result.snippet,
                    publishedAt = result.publishedAt,
                    providerType = result.providerType,
                    providerDisplayName = result.providerDisplayName,
                    searchCategory = result.searchCategory,
                    queryUsed = result.queryUsed,
                    rank = 1,
                ),
            ),
        )
    }
}
