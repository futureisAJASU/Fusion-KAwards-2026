package com.projectnuke.fusion.search

import com.projectnuke.fusion.ai.model.AiToolCall
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ManualSearchQuery
import com.projectnuke.fusion.chat.ManualSearchQueryIssue
import com.projectnuke.fusion.chat.ManualSearchQueryValidation
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchStrategyRouterTest {
    @Test
    fun successfulAgentSearchDoesNotInvokeLegacyFallback() = runTest {
        var fallbackCalls = 0
        val router = router { fallbackCalls++; LegacySearchFallbackResult.Completed(response("legacy")) }
        val model = ScriptedModel(
            SearchAgentProtocol.STRUCTURED_INTENT,
            structured("SEARCH", "public query"),
            structured("NO_MORE_SEARCH"),
        )
        val exactCalls = mutableListOf<String>()

        val routed = router.execute(routeRequest(), model)

        assertEquals(SearchRouteOutcome.COMPLETED, routed.outcome)
        assertEquals(SearchStrategy.AGENT_HARNESS, routed.executedStrategy)
        assertEquals(0, fallbackCalls)
        assertNotNull(routed.response)
    }

    @Test
    fun legacySearchResponseKeepsTypedQuestionSeparateFromExecutedQuery() = runTest {
        val originalQuery = "Use an official Bank of Korea source to tell me the current base rate"
        val executedQueries = mutableListOf<String>()
        val authorizer = allowAll()
        val exact = AgenticExactSearchTool { query, _, _ ->
            executedQueries += query
            response("official BOK result", query, "https://www.bok.or.kr/eng/main/main.do")
        }
        val router = SearchStrategyRouter(
            exactSearchTool = exact,
            queryAuthorizer = authorizer,
            legacyFallback = RuleBasedLegacySearchFallback(exact, authorizer),
        )

        val routed = router.execute(
            routeRequest(
                query = originalQuery,
                strategy = SearchStrategySnapshot(
                    SearchStrategy.LEGACY_RULE_BASED,
                    false,
                    ManualSearchQueryValidation.Missing,
                ),
            ),
            agentModel = null,
        )

        assertEquals(SearchRouteOutcome.COMPLETED, routed.outcome)
        assertEquals(1, executedQueries.size)
        assertNotNull(routed.response)
        assertEquals(originalQuery, routed.response?.query)
        assertEquals(executedQueries.single(), routed.response?.normalizedQuery)
        assertEquals(executedQueries.single(), routed.response?.results?.single()?.queryUsed)
    }

    @Test
    fun explicitNoMoreSearchBeforeAnyToolCallDoesNotTriggerLegacyFallback() = runTest {
        var fallbackCalls = 0
        var exactCalls = 0
        val router = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> exactCalls++; response("unexpected") },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor {
                fallbackCalls++
                LegacySearchFallbackResult.Completed(response("legacy"))
            },
        )

        val routed = router.execute(
            routeRequest(),
            ScriptedModel(SearchAgentProtocol.STRUCTURED_INTENT, structured("NO_MORE_SEARCH")),
        )

        assertEquals(SearchRouteOutcome.COMPLETED, routed.outcome)
        assertEquals(SearchStrategy.AGENT_HARNESS, routed.executedStrategy)
        assertEquals(0, exactCalls)
        assertEquals(0, fallbackCalls)
        assertNull(routed.response)
        assertEquals(AgenticSearchOutcome.COMPLETED, routed.agentDiagnostics?.outcome)
    }

    @Test
    fun routeDiagnosticsCountAgentAndFallbackTransportAttempts() = runTest {
        val router = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> emptyResponse() },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor { fallbackRequest ->
                fallbackRequest.onQueryIssued()
                LegacySearchFallbackResult.Completed(response("fallback"))
            },
        )
        val model = ScriptedModel(
            SearchAgentProtocol.STRUCTURED_INTENT,
            structured("SEARCH", "first query"),
            SearchAgentReply.StructuredIntent("{malformed"),
        )

        val routed = router.execute(routeRequest(), model)

        assertEquals(SearchFallbackReason.INVALID_TOOL_CALL, routed.fallbackReason)
        assertEquals(2, routed.searchQueriesIssued)
    }

    @Test
    fun fallbackDiagnosticsKeepModelPlanningAndSearchTransportDurationsSeparate() = runTest {
        val router = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> response("unexpected") },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor { request ->
                request.onPlanningElapsed(17)
                request.onSearchTransportElapsed(31)
                request.onQueryIssued()
                LegacySearchFallbackResult.Completed(response("fallback"))
            },
        )
        val routed = router.execute(
            routeRequest(),
            ScriptedModel(SearchAgentProtocol.STRUCTURED_INTENT, SearchAgentReply.StructuredIntent("{invalid")),
        )

        assertEquals(SearchRouteOutcome.FALLBACK_COMPLETED, routed.outcome)
        assertTrue(routed.planningElapsedMs >= 17)
        assertEquals(31L, routed.searchTransportElapsedMs)
    }

    @Test
    fun missingAgentModelFallsBackOnlyWhenFrozenFallbackIsEnabled() = runTest {
        var fallbackCalls = 0
        val router = router { fallbackCalls++; LegacySearchFallbackResult.Completed(response("legacy result")) }
        val routed = router.execute(routeRequest(), agentModel = null)

        assertEquals(SearchRouteOutcome.FALLBACK_COMPLETED, routed.outcome)
        assertEquals(SearchFallbackReason.MODEL_UNAVAILABLE, routed.fallbackReason)
        assertEquals(SearchStrategy.LEGACY_RULE_BASED, routed.executedStrategy)
        assertEquals(1, fallbackCalls)
        assertEquals("legacy result", routed.response?.results?.single()?.title)
    }

    @Test
    fun nativeToolUnsupportedAndMalformedStructuredIntentUseBoundedFallback() = runTest {
        var fallbackCalls = 0
        val router = router { fallbackCalls++; LegacySearchFallbackResult.Completed(response("fallback")) }
        val unsupported = router.execute(
            routeRequest(),
            ScriptedModel(SearchAgentProtocol.NATIVE_TOOL_CALLS, SearchAgentReply.Unsupported("provider_no_tools")),
        )
        val malformed = router.execute(
            routeRequest(),
            ScriptedModel(SearchAgentProtocol.STRUCTURED_INTENT, SearchAgentReply.StructuredIntent("{not-json")),
        )

        assertEquals(SearchFallbackReason.NATIVE_TOOLS_UNSUPPORTED, unsupported.fallbackReason)
        assertEquals(SearchFallbackReason.INVALID_TOOL_CALL, malformed.fallbackReason)
        assertEquals(2, fallbackCalls)
    }

    @Test
    fun fatalPlannerFailureDoesNotUseAutomaticFallback() = runTest {
        var fallbackCalls = 0
        val router = router { fallbackCalls++; LegacySearchFallbackResult.Completed(response("fallback")) }
        val routed = router.execute(
            routeRequest(),
            ScriptedModel(SearchAgentProtocol.STRUCTURED_INTENT, SearchAgentReply.Failed("fatal", recoverable = false)),
        )

        assertEquals(SearchRouteOutcome.AGENT_FAILED, routed.outcome)
        assertEquals(0, fallbackCalls)
        assertFalse(routed.agentDiagnostics!!.legacyFallbackAllowed)
    }

    @Test
    fun disabledFallbackPreferenceKeepsPlannerFailureLocal() = runTest {
        var fallbackCalls = 0
        val router = router { fallbackCalls++; LegacySearchFallbackResult.Completed(response("fallback")) }
        val routed = router.execute(
            routeRequest(strategy = SearchStrategySnapshot(SearchStrategy.AGENT_HARNESS, false, ManualSearchQueryValidation.Missing)),
            agentModel = null,
        )
        assertEquals(SearchRouteOutcome.AGENT_FAILED, routed.outcome)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun recoverableSearchTransportFailureFallsBackWithinRemainingQueryBudget() = runTest {
        var exactCalls = 0
        var fallbackCalls = 0
        val exact = AgenticExactSearchTool { _, _, _ ->
            exactCalls++
            throw IllegalStateException("transport")
        }
        val fallback = LegacySearchFallbackExecutor { req ->
            fallbackCalls++
            assertEquals(3, req.maxAdditionalQueries)
            LegacySearchFallbackResult.Completed(response("fallback"))
        }
        val queryAuth = allowAll()
        val router = SearchStrategyRouter(exact, queryAuth, fallback)
        val model = ScriptedModel(SearchAgentProtocol.STRUCTURED_INTENT, structured("SEARCH", "query"))

        val routed = router.execute(routeRequest(), model)

        assertEquals(1, exactCalls)
        assertEquals(1, fallbackCalls)
        assertEquals(SearchFallbackReason.TOOL_FAILURE, routed.fallbackReason)
        assertEquals(SearchRouteOutcome.FALLBACK_COMPLETED, routed.outcome)
    }

    @Test
    fun privacyDenialAndRevokedPermissionNeverTriggerFallback() = runTest {
        var fallbackCalls = 0
        var exactCalls = 0
        val fallback = LegacySearchFallbackExecutor { fallbackCalls++; LegacySearchFallbackResult.Completed(response("fallback")) }
        val deniedRouter = SearchStrategyRouter(
            AgenticExactSearchTool { _, _, _ -> exactCalls++; response("unexpected") },
            AgenticSearchQueryAuthorizer { _, _ -> AgenticQueryAuthorization.Denied() },
            fallback,
        )
        val privacy = deniedRouter.execute(
            routeRequest(),
            ScriptedModel(SearchAgentProtocol.STRUCTURED_INTENT, structured("SEARCH", "private file.pdf")),
        )
        var permission = true
        val revoked = router { fallbackCalls++; LegacySearchFallbackResult.Completed(response("fallback")) }.execute(
            routeRequest(permission = { permission }, onPlannerResult = { permission = false }),
            ScriptedModel(SearchAgentProtocol.STRUCTURED_INTENT, structured("SEARCH", "query")),
        )

        assertEquals(SearchRouteOutcome.PRIVACY_DENIED, privacy.outcome)
        assertEquals(SearchRouteOutcome.PERMISSION_REVOKED, revoked.outcome)
        assertEquals(0, exactCalls)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun cancellationDoesNotStartLegacyFallback() = runTest {
        var fallbackCalls = 0
        val router = router { fallbackCalls++; LegacySearchFallbackResult.Completed(response("fallback")) }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val task = async(start = CoroutineStart.LAZY) { router.execute(routeRequest(), model) }
        task.start()
        entered.await()
        task.cancel()
        runCatching { task.await() }

        assertEquals(0, fallbackCalls)
    }

    @Test
    fun fallbackUsesOnlyUntouchedBudgetAndReceivesAgentQueryExclusions() = runTest {
        var fallbackCalls = 0
        val fallback = LegacySearchFallbackExecutor { req ->
            fallbackCalls++
            assertEquals(3, req.maxAdditionalQueries)
            assertTrue("repeat query" in req.excludedQueryIdentities)
            LegacySearchFallbackResult.Completed(response("new fallback result"))
        }
        val router = SearchStrategyRouter(
            AgenticExactSearchTool { _, _, _ -> emptyResponse() },
            allowAll(),
            fallback,
        )
        val model = ScriptedModel(
            SearchAgentProtocol.STRUCTURED_INTENT,
            structured("SEARCH", "repeat query"),
            SearchAgentReply.StructuredIntent("{malformed"),
        )
        val routed = router.execute(routeRequest(), model)

        assertEquals(1, fallbackCalls)
        assertEquals(SearchFallbackReason.INVALID_TOOL_CALL, routed.fallbackReason)
        assertEquals("new fallback result", routed.response?.results?.single()?.title)
    }

    @Test
    fun concreteRuleFallbackSkipsAgentQueryAndPreservesFallbackSourceMetadata() = runTest {
        val query = RuleBasedWebSearchPlanner.plan(RuleBasedWebSearchPlannerInput("삼성전자 주가" )).primaryQuery
        val calls = mutableListOf<String>()
        val exact = AgenticExactSearchTool { candidate, _, _ ->
            calls += candidate
            if (candidate == query) emptyResponse(candidate) else response("fallback source", candidate)
        }
        val router = SearchStrategyRouter(exact, allowAll(), RuleBasedLegacySearchFallback(exact, allowAll()))
        val model = ScriptedModel(
            SearchAgentProtocol.STRUCTURED_INTENT,
            structured("SEARCH", query),
            SearchAgentReply.StructuredIntent("{invalid"),
        )
        val routed = router.execute(routeRequest(query), model)

        assertEquals(1, calls.count { queryIdentity(it) == queryIdentity(query) })
        assertEquals(SearchRouteOutcome.FALLBACK_COMPLETED, routed.outcome)
        assertEquals("https://example.test/fallback", routed.response?.sources?.single()?.url)
    }

    @Test
    fun acceptedAgentEvidenceIsRetainedAndPreventsLegacyFallbackAfterLaterPlannerFailure() = runTest {
        var fallbackCalls = 0
        val exact = AgenticExactSearchTool { query, _, _ ->
            response("agent source", query, "https://example.test/agent")
        }
        val router = SearchStrategyRouter(
            exactSearchTool = exact,
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor { request ->
                fallbackCalls++
                request.onQueryIssued()
                LegacySearchFallbackResult.Completed(
                    response("fallback source", "fallback query", "https://example.test/fallback"),
                )
            },
        )
        val model = ScriptedModel(
            SearchAgentProtocol.STRUCTURED_INTENT,
            structured("SEARCH", "agent query"),
            SearchAgentReply.StructuredIntent("{malformed"),
        )

        val routed = router.execute(routeRequest(), model)

        assertEquals(SearchRouteOutcome.AGENT_FAILED, routed.outcome)
        assertEquals(0, fallbackCalls)
        assertEquals(
            setOf("https://example.test/agent"),
            routed.response?.sources?.mapNotNull { it.url }?.toSet(),
        )
        assertEquals(1, routed.response?.results?.size)
    }

    @Test
    fun manualModeWithMissingQueryNeverSubstitutesLegacySearch() = runTest {
        var fallbackCalls = 0
        var exactCalls = 0
        val router = SearchStrategyRouter(
            AgenticExactSearchTool { _, _, _ -> exactCalls++; response("unexpected") },
            allowAll(),
            LegacySearchFallbackExecutor { fallbackCalls++; LegacySearchFallbackResult.Completed(response("legacy")) },
        )
        val routed = router.execute(
            routeRequest(strategy = SearchStrategySnapshot(SearchStrategy.MANUAL_QUERY, true, ManualSearchQueryValidation.Missing)),
            agentModel = null,
        )

        assertEquals(SearchRouteOutcome.MANUAL_QUERY_MISSING, routed.outcome)
        assertEquals(0, fallbackCalls)
        assertEquals(0, exactCalls)
    }

    @Test
    fun manualModeExecutesOnlyTheExactCapturedQuery() = runTest {
        val query = "삼성전자 005930 KRX 현재가"
        val parsed = ManualSearchQuery.parse(query) as ManualSearchQueryValidation.Valid
        val searched = mutableListOf<String>()
        val router = SearchStrategyRouter(
            AgenticExactSearchTool { exactQuery, _, _ ->
                searched += exactQuery
                response("manual result", exactQuery)
            },
            allowAll(),
            LegacySearchFallbackExecutor { error("manual search must not fall back") },
        )

        val routed = router.execute(
            routeRequest(
                strategy = SearchStrategySnapshot(
                    SearchStrategy.MANUAL_QUERY,
                    allowLegacyFallback = true,
                    manualQuery = ManualSearchQueryValidation.Valid(parsed.query),
                ),
            ),
            agentModel = null,
        )

        assertEquals(SearchRouteOutcome.COMPLETED, routed.outcome)
        assertEquals(listOf(query), searched)
        assertEquals(query, routed.response?.query)
        assertEquals(query, routed.response?.results?.single()?.queryUsed)
        assertEquals(1, routed.searchQueriesIssued)
    }

    @Test
    fun invalidManualQueryMakesNoTransportOrFallbackCall() = runTest {
        var exactCalls = 0
        var fallbackCalls = 0
        val router = SearchStrategyRouter(
            AgenticExactSearchTool { _, _, _ -> exactCalls++; response("unexpected") },
            allowAll(),
            LegacySearchFallbackExecutor { fallbackCalls++; LegacySearchFallbackResult.Completed(response("unexpected")) },
        )

        val routed = router.execute(
            routeRequest(
                strategy = SearchStrategySnapshot(
                    SearchStrategy.MANUAL_QUERY,
                    allowLegacyFallback = true,
                    manualQuery = ManualSearchQueryValidation.Invalid(ManualSearchQueryIssue.NO_SEARCH_TERMS),
                ),
            ),
            agentModel = null,
        )

        assertEquals(SearchRouteOutcome.MANUAL_QUERY_INVALID, routed.outcome)
        assertEquals(0, exactCalls)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun manualModeCannotOverrideDisabledWebSearchPermission() = runTest {
        var exactCalls = 0
        var fallbackCalls = 0
        val parsed = ManualSearchQuery.parse("public exact query") as ManualSearchQueryValidation.Valid
        val router = SearchStrategyRouter(
            AgenticExactSearchTool { _, _, _ -> exactCalls++; response("unexpected") },
            allowAll(),
            LegacySearchFallbackExecutor { fallbackCalls++; LegacySearchFallbackResult.Completed(response("unexpected")) },
        )

        val routed = router.execute(
            routeRequest(
                policy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
                strategy = SearchStrategySnapshot(
                    SearchStrategy.MANUAL_QUERY,
                    allowLegacyFallback = true,
                    manualQuery = ManualSearchQueryValidation.Valid(parsed.query),
                ),
            ),
            agentModel = null,
        )

        assertEquals(SearchRouteOutcome.SEARCH_DISABLED, routed.outcome)
        assertEquals(0, exactCalls)
        assertEquals(0, fallbackCalls)
        assertEquals(0, routed.searchQueriesIssued)
    }

    @Test
    fun selectingStrategyDoesNotOverrideSearchDisabled() = runTest {
        var calls = 0
        val router = router { calls++; LegacySearchFallbackResult.Completed(response("unexpected")) }
        val routed = router.execute(
            routeRequest(policy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED),
            ScriptedModel(SearchAgentProtocol.STRUCTURED_INTENT, structured("SEARCH", "query")),
        )
        assertEquals(SearchRouteOutcome.SEARCH_DISABLED, routed.outcome)
        assertEquals(0, calls)
    }

    private fun router(fallback: suspend (LegacySearchFallbackRequest) -> LegacySearchFallbackResult): SearchStrategyRouter =
        SearchStrategyRouter(
            AgenticExactSearchTool { query, _, _ -> response("agent source", query) },
            allowAll(),
            LegacySearchFallbackExecutor(fallback),
        )

    private fun routeRequest(
        query: String = "user typed question",
        strategy: SearchStrategySnapshot = SearchStrategySnapshot.DEFAULT,
        policy: GenerationRequestSnapshot.WebSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
        permission: () -> Boolean = { true },
        onPlannerResult: () -> Unit = {},
    ): SearchStrategyRouteRequest {
        var checks = 0
        return SearchStrategyRouteRequest(
            agentRequest = AgenticSearchRequest(
                requestId = "request-1",
                originalUserQuery = query,
                webSearchPolicy = policy,
                limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
                ownerStillActive = { true },
                searchPermissionStillValid = {
                    checks++
                    if (checks == 4) onPlannerResult()
                    permission()
                },
            ),
            strategy = strategy,
        )
    }

    private fun allowAll() = AgenticSearchQueryAuthorizer { _, query -> AgenticQueryAuthorization.Authorized(query) }

    private class ScriptedModel(
        override val protocol: SearchAgentProtocol,
        vararg replies: SearchAgentReply,
    ) : SearchAgentModel {
        private val actions = replies.toMutableList()
        override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply = actions.removeAt(0)
    }

    private fun structured(action: String, vararg queries: String) = SearchAgentReply.StructuredIntent(
        when (action) {
            "SEARCH" -> """{"action":"SEARCH","queries":[${queries.joinToString(",") { org.json.JSONObject.quote(it) }}]}"""
            else -> """{"action":"$action"}"""
        },
    )

    private fun emptyResponse(query: String = "query") = FusionSearchResponse(
        query, query, SearchIntent.GENERAL, emptyList(), "no result",
    )

    private fun response(
        title: String,
        query: String = "query",
        url: String = "https://example.test/fallback",
    ): FusionSearchResponse {
        val result = FusionSearchResult(title, "Source", url, "Public evidence", "2026-10-26", queryUsed = query)
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
