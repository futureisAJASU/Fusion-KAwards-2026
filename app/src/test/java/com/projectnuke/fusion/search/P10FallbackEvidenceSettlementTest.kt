package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ManualSearchQuery
import com.projectnuke.fusion.chat.ManualSearchQueryValidation
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import com.projectnuke.fusion.model.GenerationSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// P10-D: terminal actions are semantically distinguished. An intentional
// zero-search completion is not fabricated into evidence, and a request that
// explicitly required retrieval is not silently suppressed by it.
class P10FallbackEvidenceSettlementTest {

    // ---- terminal actions ---------------------------------------------------

    @Test
    fun intentionalZeroSearchCompletionStaysIntentionalAndEmpty() = runTest {
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.StructuredIntent("""{"action":"NO_MORE_SEARCH"}""")
        }
        var fallbackCalls = 0
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> error("must not run") },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor {
                fallbackCalls++
                LegacySearchFallbackResult.Completed(evidence(title = "fallback"))
            },
        ).execute(routeRequest(), model)

        assertEquals(SearchRouteOutcome.COMPLETED, routed.outcome)
        assertEquals(0, routed.searchQueriesIssued)
        assertEquals(0, fallbackCalls)
        assertNull(routed.response)
        assertEquals(AgenticSearchOutcome.COMPLETED, routed.agentDiagnostics?.outcome)
    }

    @Test
    fun plannerFinalTextIsNeverExposedAsASearchResultOrVerifiedAnswer() = runTest {
        val plannerAnswer = "PLANNER-SELF-REPORTED-ANSWER-7731"
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.StructuredIntent("""{"action":"FINAL","answer":"$plannerAnswer"}""")
        }
        var fallbackCalls = 0
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { query, _, _ -> evidence(query = query) },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor {
                fallbackCalls++
                LegacySearchFallbackResult.Completed(evidence(title = "fallback"))
            },
        ).execute(routeRequest(), model)

        // No search ran, so nothing is claimed as evidence or provenance.
        assertEquals(0, routed.searchQueriesIssued)
        assertNull(routed.response)
        assertNull(routed.response?.sources?.firstOrNull { it.title.contains(plannerAnswer) })
        assertEquals(0, fallbackCalls)
    }

    // ---- explicit retrieval ------------------------------------------------

    @Test
    fun explicitRetrievalRequestThatEndsBeforeSearchingUsesTheBoundedFallback() = runTest {
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.StructuredIntent("""{"action":"NO_MORE_SEARCH"}""")
        }
        var agentSearches = 0
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> agentSearches++; evidence(title = "unexpected") },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor {
                LegacySearchFallbackResult.Completed(evidence(query = "legacy query", title = "legacy"))
            },
        ).execute(routeRequest(webSearchRequired = true), model)

        assertEquals(0, agentSearches)
        assertEquals(SearchFallbackReason.NO_USABLE_EVIDENCE, routed.fallbackReason)
        assertEquals(SearchRouteOutcome.FALLBACK_COMPLETED, routed.outcome)
        assertEquals("explicit_retrieval_not_attempted", routed.agentDiagnostics?.failureCode)
        assertEquals("legacy", routed.response?.results?.single()?.title)
    }

    @Test
    fun fallbackThatFindsNothingUsableNeverFabricatesAResult() = runTest {
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.StructuredIntent("""{"action":"NO_MORE_SEARCH"}""")
        }
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> error("must not run") },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor { LegacySearchFallbackResult.Completed(emptyResponse()) },
        ).execute(routeRequest(webSearchRequired = true), model)

        assertEquals(SearchRouteOutcome.FALLBACK_FAILED, routed.outcome)
        assertFalse(routed.response?.hasSemanticSuccess() == true)
        assertTrue(routed.response?.results.isNullOrEmpty())
        assertTrue(routed.response?.sources.isNullOrEmpty())
    }

    @Test
    fun explicitRetrievalWithASuccessfulSearchDoesNotFallBack() = runTest {
        var round = 0
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
                round++
                return if (round == 1) {
                    SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["public query"]}""")
                } else {
                    SearchAgentReply.StructuredIntent("""{"action":"NO_MORE_SEARCH"}""")
                }
            }
        }
        var fallbackCalls = 0
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { query, _, _ -> evidence(query = query) },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor {
                fallbackCalls++
                LegacySearchFallbackResult.Completed(evidence(title = "fallback"))
            },
        ).execute(routeRequest(webSearchRequired = true), model)

        assertEquals(SearchRouteOutcome.COMPLETED, routed.outcome)
        assertEquals(0, fallbackCalls)
        assertNotNull(routed.response)
        assertEquals(1, routed.searchQueriesIssued)
    }

    @Test
    fun searchSucceedsThenFallbackNeverRunsAfterExplicitCompletion() = runTest {
        var round = 0
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
                round++
                return if (round == 1) {
                    SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["public query"]}""")
                } else {
                    SearchAgentReply.StructuredIntent("""{"action":"FINAL","answer":"done"}""")
                }
            }
        }
        var fallbackCalls = 0
        val result = AgenticSearchHarness(
            model = model,
            searchTool = AgenticExactSearchTool { query, _, _ -> evidence(query = query) },
            queryAuthorizer = allowAll(),
        ).execute(
            AgenticSearchRequest(
                requestId = "p10d-completed",
                originalUserQuery = "public query",
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
                ownerStillActive = { true },
                searchPermissionStillValid = { true },
                webSearchRequired = true,
            ),
        )

        assertEquals(0, fallbackCalls)
        assertEquals(AgenticSearchOutcome.COMPLETED, result.outcome)
        assertFalse(result.diagnostics.legacyFallbackAllowed)
        assertNotNull(result.evidence)
        assertEquals("public query", result.evidence?.query)
    }

    @Test
    fun unsuccessfulSearchThenFinishCountsAsNoUsableEvidenceAndFallsBack() = runTest {
        var round = 0
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
                round++
                return if (round == 1) {
                    SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["public query"]}""")
                } else {
                    SearchAgentReply.StructuredIntent("""{"action":"FINAL","answer":"nothing usable"}""")
                }
            }
        }
        var agentSearches = 0
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ ->
                agentSearches++
                emptyResponse()
            },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor {
                LegacySearchFallbackResult.Completed(evidence(query = "legacy query", title = "legacy"))
            },
        ).execute(routeRequest(webSearchRequired = true), model)

        assertEquals(1, agentSearches)
        assertEquals(SearchFallbackReason.NO_USABLE_EVIDENCE, routed.fallbackReason)
        assertEquals(SearchRouteOutcome.FALLBACK_COMPLETED, routed.outcome)
        assertEquals("no_usable_search_evidence", routed.agentDiagnostics?.failureCode)
    }

    @Test
    fun malformedIntentAfterPartialEvidenceKeepsTheAcceptedSources() = runTest {
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                if (messages.any { it.role == SearchAgentMessageRole.USER && it.content.startsWith("Earlier") }) {
                    SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["truncated}""")
                } else {
                    SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["partial evidence query"]}""")
                }
        }
        var fallbackCalls = 0
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { query, _, _ -> evidence(query = query, title = "agent source", url = "https://example.test/agent") },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor {
                fallbackCalls++
                LegacySearchFallbackResult.Completed(evidence(query = "fallback query", title = "fallback", url = "https://example.test/fallback"))
            },
        ).execute(routeRequest(), model)

        // Existing evidence blocks the fallback: no repeated query, no lost source.
        assertEquals(0, fallbackCalls)
        assertEquals(SearchRouteOutcome.AGENT_FAILED, routed.outcome)
        assertEquals(setOf("https://example.test/agent"), routed.response?.sources?.mapNotNull { it.url }?.toSet())
        assertEquals("partial evidence query", routed.response?.results?.single()?.queryUsed)
        assertEquals(1, routed.searchQueriesIssued)
    }

    @Test
    fun successfulSearchThenRecoverableTransportFailureRetainsEarlierEvidence() = runTest {
        val actions = ArrayDeque(
            listOf(
                SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["first query"]}"""),
                SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["second query"]}"""),
            ),
        )
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>) = actions.removeFirst()
        }
        val searches = mutableListOf<String>()
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { query, _, _ ->
                searches += query
                if (query == "second query") throw IllegalStateException("recoverable transport failure")
                evidence(query = query, title = "first accepted result")
            },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor { error("accepted evidence must block fallback") },
        ).execute(routeRequest(), model)

        assertEquals(listOf("first query", "second query"), searches)
        assertEquals(SearchRouteOutcome.AGENT_FAILED, routed.outcome)
        assertEquals("first query", routed.response?.results?.single()?.queryUsed)
        assertEquals("https://example.test/source", routed.response?.sources?.single()?.url)
    }

    // ---- ineligible fallback transitions -----------------------------------

    @Test
    fun privacyDenialCancellationRevocationAndDisabledFallbackNeverFallBack() = runTest {
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["private-file.pdf"]}""")
        }
        var fallbackCalls = 0
        var exactCalls = 0
        val privacyRouted = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> exactCalls++; evidence(title = "unexpected") },
            queryAuthorizer = AgenticSearchQueryAuthorizer { _, _ -> AgenticQueryAuthorization.Denied("private_or_sensitive_text") },
            legacyFallback = LegacySearchFallbackExecutor {
                fallbackCalls++
                LegacySearchFallbackResult.Completed(evidence(title = "fallback"))
            },
        ).execute(routeRequest(webSearchRequired = true), model)
        assertEquals(SearchRouteOutcome.PRIVACY_DENIED, privacyRouted.outcome)
        assertEquals(0, fallbackCalls)
        assertEquals(0, exactCalls)

        val disabledRouted = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> evidence(title = "unexpected") },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor { error("must not run") },
        ).execute(
            routeRequest(
                webSearchRequired = true,
                strategy = SearchStrategySnapshot(SearchStrategy.AGENT_HARNESS, false, ManualSearchQueryValidation.Missing),
            ),
            agentModel = null,
        )
        assertEquals(SearchRouteOutcome.AGENT_FAILED, disabledRouted.outcome)
    }

    @Test
    fun exhaustedQueryBudgetNeverFallsBackIntoAnExtraQuery() = runTest {
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["public query"]}""")
        }
        var fallbackCalls = 0
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { query, _, _ -> evidence(query = query) },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor {
                fallbackCalls++
                LegacySearchFallbackResult.Completed(evidence(title = "fallback"))
            },
        ).execute(
            SearchStrategyRouteRequest(
                agentRequest = AgenticSearchRequest(
                    requestId = "p10d-budget",
                    originalUserQuery = "public query",
                    webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                    limits = AgenticSearchLimits(2, maxIssuedQueries = 1),
                    ownerStillActive = { true },
                    searchPermissionStillValid = { true },
                    webSearchRequired = true,
                ),
                strategy = SearchStrategySnapshot.DEFAULT,
            ),
            model,
        )

        // The bound stopped any extra query, the accepted evidence survived,
        // and the fallback never repeats work the agent already paid for.
        assertEquals(1, routed.searchQueriesIssued)
        assertEquals(0, fallbackCalls)
        assertEquals(SearchRouteOutcome.AGENT_FAILED, routed.outcome)
        assertNotNull(routed.response)
        assertTrue(routed.response?.hasSemanticSuccess() == true)
    }

    @Test
    fun fallbackNeverRepeatsAnAgentQueryWithinTheAggregateBudget() = runTest {
        val agentQuery = "public query"
        val searched = mutableListOf<String>()
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { query, _, _ ->
                searched += query
                evidence(query = query)
            },
            queryAuthorizer = allowAll(),
            legacyFallback = RuleBasedLegacySearchFallback(
                AgenticExactSearchTool { query, _, _ ->
                    searched += query
                    evidence(query = query)
                },
                allowAll(),
            ),
        ).execute(
            SearchStrategyRouteRequest(
                agentRequest = AgenticSearchRequest(
                    requestId = "p10d-no-repeat",
                    originalUserQuery = agentQuery,
                    webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                    limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
                    ownerStillActive = { true },
                    searchPermissionStillValid = { true },
                    webSearchRequired = true,
                ),
                strategy = SearchStrategySnapshot.DEFAULT,
            ),
            object : SearchAgentModel {
                override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
                override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                    SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["$agentQuery"]}""")
            },
        )

        assertEquals(1, searched.count { queryIdentity(it) == queryIdentity(agentQuery) })
        assertTrue(routed.searchQueriesIssued <= 4)
    }

    @Test
    fun fallbackBudgetIsExactlyTheRemainingAgentBudget() = runTest {
        var round = 0
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
                round++
                return if (round == 1) {
                    SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["public query"]}""")
                } else {
                    SearchAgentReply.StructuredIntent("""{"action":"FINAL","answer":"done"}""")
                }
            }
        }
        var fallbackBudget = -1
        var fallbackQueries = 0
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { query, _, _ -> emptyResponse(query) },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor { request ->
                fallbackBudget = request.maxAdditionalQueries
                request.maxAdditionalQueries
                LegacySearchFallbackResult.Completed(evidence(query = "fallback query", title = "fallback"))
            },
        ).execute(
            SearchStrategyRouteRequest(
                agentRequest = AgenticSearchRequest(
                    requestId = "p10d-remaining",
                    originalUserQuery = "public query",
                    webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                    limits = AgenticSearchLimits(maxDecisionRounds = 3, maxIssuedQueries = 4),
                    ownerStillActive = { true },
                    searchPermissionStillValid = { true },
                    webSearchRequired = true,
                ),
                strategy = SearchStrategySnapshot.DEFAULT,
            ),
            model,
        )

        assertEquals(3, fallbackBudget)
        assertEquals(SearchRouteOutcome.FALLBACK_COMPLETED, routed.outcome)
        assertEquals(1, routed.searchQueriesIssued)
    }

    @Test
    fun timeoutPreservesAcceptedEvidenceAndNeverFallsBack() = runTest {
        var planning = 0
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
                planning++
                if (planning == 1) return SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["public query"]}""")
                kotlinx.coroutines.awaitCancellation()
            }
        }
        var fallbackCalls = 0
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { query, _, _ -> evidence(query = query) },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor {
                fallbackCalls++
                LegacySearchFallbackResult.Completed(evidence(title = "fallback"))
            },
        ).execute(
            SearchStrategyRouteRequest(
                agentRequest = AgenticSearchRequest(
                    requestId = "p10d-timeout",
                    originalUserQuery = "public query",
                    webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                    limits = AgenticSearchLimits(maxDecisionRounds = 3, overallDeadlineMs = 1_000),
                    ownerStillActive = { true },
                    searchPermissionStillValid = { true },
                    webSearchRequired = true,
                ),
                strategy = SearchStrategySnapshot.DEFAULT,
            ),
            model,
        )

        assertEquals(SearchRouteOutcome.TIMEOUT, routed.outcome)
        assertEquals(0, fallbackCalls)
        assertNotNull(routed.response)
        assertTrue(routed.response?.hasSemanticSuccess() == true)
    }

    @Test
    fun cancellationDuringRetrievalPropagatesAndNeverFallsBack() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["public query"]}""")
        }
        var fallbackCalls = 0
        val router = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ ->
                entered.complete(Unit)
                kotlinx.coroutines.CompletableDeferred<FusionSearchResponse>().await()
            },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor {
                fallbackCalls++
                LegacySearchFallbackResult.Completed(evidence(title = "fallback"))
            },
        )
        val task = async {
            router.execute(routeRequest(webSearchRequired = true), model)
        }
        entered.await()
        task.cancel()
        try {
            task.await()
            throw AssertionError("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }
        assertEquals(0, fallbackCalls)
    }

    // ---- research request wiring -------------------------------------------

    @Test
    fun researchRequestMarksRetrievalRequiredAndOrdinaryRequestDoesNot() = runTest {
        val research = ResearchSearchOwner.searchWithStrategy(
            request = request(interactionMode = ChatInteractionMode.RESEARCH),
            previousUserMessage = null,
            providerRepository = null,
            privacySnapshot = privacy(),
            agentModel = neverSearchingModel(),
            ownerStillActive = { true },
            limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
        )
        assertEquals("explicit_retrieval_not_attempted", research.agentDiagnostics?.failureCode)
        assertEquals(AgenticSearchOutcome.NO_USABLE_EVIDENCE, research.agentDiagnostics?.outcome)

        val ordinary = ResearchSearchOwner.searchWithStrategy(
            request = request(interactionMode = ChatInteractionMode.NORMAL),
            previousUserMessage = null,
            providerRepository = null,
            privacySnapshot = privacy(),
            agentModel = neverSearchingModel(),
            ownerStillActive = { true },
            limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
        )
        assertEquals(AgenticSearchOutcome.COMPLETED, ordinary.agentDiagnostics?.outcome)
        assertEquals(SearchRouteOutcome.COMPLETED, ordinary.outcome)
        assertTrue(ordinary.response?.results.isNullOrEmpty())
    }

    @Test
    fun manualQueryStaysIndependentOfTheExplicitRetrievalRule() = runTest {
        val manualQuery = "삼성전자 005930 KRX 현재가"
        val searched = mutableListOf<String>()
        val routed = ResearchSearchOwner.searchWithStrategy(
            request = request(interactionMode = ChatInteractionMode.RESEARCH)
                .copy(
                    rawUserText = manualQuery,
                    searchStrategy = SearchStrategySnapshot(
                        SearchStrategy.MANUAL_QUERY,
                        true,
                        ManualSearchQuery.parse(manualQuery),
                    ),
                ),
            previousUserMessage = null,
            providerRepository = null,
            privacySnapshot = privacy(manualQuery, manualQuery),
            agentModel = null,
            ownerStillActive = { true },
            limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
            exactSearchOverride = AgenticExactSearchTool { exact, _, _ ->
                searched += exact
                evidence(query = exact)
            },
        )

        assertEquals(SearchRouteOutcome.COMPLETED, routed.outcome)
        assertEquals(SearchStrategy.MANUAL_QUERY, routed.executedStrategy)
        // Manual mode runs exactly the captured query, never a rewritten or
        // split one, and never the agentic planner.
        assertEquals(listOf(manualQuery), searched)
        assertEquals(manualQuery, routed.response?.results?.single()?.queryUsed)
    }

    // ---- helpers -----------------------------------------------------------

    private fun routeRequest(
        webSearchRequired: Boolean = false,
        strategy: SearchStrategySnapshot = SearchStrategySnapshot.DEFAULT,
    ) = SearchStrategyRouteRequest(
        agentRequest = AgenticSearchRequest(
            requestId = "p10d-request",
            originalUserQuery = "public query",
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
            ownerStillActive = { true },
            searchPermissionStillValid = { true },
            webSearchRequired = webSearchRequired,
        ),
        strategy = strategy,
    )

    private fun request(interactionMode: ChatInteractionMode) = GenerationRequestSnapshot(
        requestId = "p10d-research",
        conversationId = 12L,
        generationModeKey = "LOCAL_MODEL",
        selectedModelId = "local",
        selectedModelPath = "active-model",
        settings = GenerationSettings(),
        reasoningEnabled = false,
        webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
        attachmentIds = emptyList(),
        multimodalImagePaths = emptyList(),
        promptText = "public question",
        rawUserText = "public question",
        createdAt = 1L,
        interactionMode = interactionMode,
        searchStrategy = SearchStrategySnapshot.DEFAULT,
    )

    private fun privacy(query: String = "public question", manual: String? = null) =
        AgenticSearchPrivacySnapshot(
            requestId = "p10d-research",
            typedUserQuery = query,
            explicitManualQuery = manual,
            requestStillAuthorized = { true },
        )

    private fun neverSearchingModel() = object : SearchAgentModel {
        override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
        override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
            SearchAgentReply.StructuredIntent("""{"action":"NO_MORE_SEARCH"}""")
    }

    private fun allowAll() = AgenticSearchQueryAuthorizer { _, query -> AgenticQueryAuthorization.Authorized(query) }

    private fun emptyResponse(query: String = "query") =
        FusionSearchResponse(query, query, SearchIntent.GENERAL, emptyList(), "no result")

    private fun evidence(
        query: String = "query",
        title: String = "Public source",
        url: String = "https://example.test/source",
    ): FusionSearchResponse {
        val result = FusionSearchResult(
            title = title,
            source = "Example",
            url = url,
            snippet = "Public evidence.",
            publishedAt = "2026-10-26",
            queryUsed = query,
        )
        val source = WebSearchSource(
            title = result.title,
            source = result.source,
            url = result.url,
            snippet = result.snippet,
            publishedAt = result.publishedAt,
            providerType = WebSearchProviderType.FREE_DEFAULT,
            providerDisplayName = result.providerDisplayName,
            searchCategory = null,
            queryUsed = query,
            rank = 1,
        )
        return FusionSearchResponse(query, query, SearchIntent.GENERAL, listOf(result), null, listOf(source))
    }
}
