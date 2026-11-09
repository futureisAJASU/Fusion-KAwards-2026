package com.projectnuke.fusion.search

import com.projectnuke.fusion.ai.model.AiToolCall
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ManualSearchQuery
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// P10-C: the exact transport carries an already authorized query without
// depending on legacy query rewriting, and native tool results stay paired to
// exactly one call identity. An invalid provider tool response can never
// become a successful search result.
class P10ExactTransportToolProtocolTest {

    // ---- manual query exactness --------------------------------------------

    @Test
    fun exactManualQueryStaysByteForByteIdenticalThroughAnAuthorizedSearch() = runTest {
        val approved = "삼성전자 005930 KRX 현재가 (2026.10.26)"
        val outbound = mutableListOf<String>()
        val router = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { query, _, _ ->
                outbound += query
                evidence(query)
            },
            queryAuthorizer = RequestScopedAgenticSearchQueryAuthorizer(
                AgenticSearchPrivacySnapshot(
                    requestId = "manual-exact",
                    typedUserQuery = approved,
                    explicitManualQuery = approved,
                    requestStillAuthorized = { true },
                ),
            ),
            legacyFallback = LegacySearchFallbackExecutor { error("manual search must not fall back") },
        )

        val routed = router.execute(
            SearchStrategyRouteRequest(
                agentRequest = AgenticSearchRequest(
                    requestId = "manual-exact",
                    originalUserQuery = approved,
                    webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                    limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
                    ownerStillActive = { true },
                    searchPermissionStillValid = { true },
                ),
                strategy = SearchStrategySnapshot(
                    SearchStrategy.MANUAL_QUERY,
                    true,
                    ManualSearchQuery.parse(approved),
                ),
            ),
            agentModel = null,
        )

        assertEquals(SearchRouteOutcome.COMPLETED, routed.outcome)
        assertEquals(listOf(approved), outbound)
        assertEquals(approved, routed.response?.results?.single()?.queryUsed)
        assertEquals(approved, routed.response?.query)
    }

    // ---- native tool-call protocol -----------------------------------------

    @Test
    fun duplicateNativeToolCallIdsAreRejectedInsteadOfPairingAmbiguously() = runTest {
        val duplicate = AiToolCall("call-dup", "web_search", """{"queries":["first"]}""")
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.NATIVE_TOOL_CALLS
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.NativeToolCalls(listOf(duplicate, duplicate), finishReason = "tool_calls")
        }
        var searches = 0
        val result = AgenticSearchHarness(
            model = model,
            searchTool = AgenticExactSearchTool { _, _, _ -> searches++; evidence("unexpected") },
            queryAuthorizer = allowAll(),
        ).execute(request())

        assertEquals(0, searches)
        assertEquals(AgenticSearchOutcome.INVALID_TOOL_CALL, result.outcome)
        assertEquals("duplicate_tool_call_id", result.diagnostics.failureCode)
        // An ambiguous provider response is still a bounded protocol failure.
        assertTrue(result.diagnostics.legacyFallbackAllowed)
    }

    @Test
    fun multipleCallsEachCarryTheirQueriesAndKeepTheirOwnResults() = runTest {
        val first = AiToolCall("call-1", "web_search", """{"queries":["first query"]}""")
        val second = AiToolCall("call-2", "web_search", """{"queries":["second query"]}""")
        val model = CapturingModel(
            SearchAgentProtocol.NATIVE_TOOL_CALLS,
            SearchAgentReply.NativeToolCalls(listOf(first, second), finishReason = "tool_calls"),
            SearchAgentReply.Finished("finished"),
        )
        val searched = mutableListOf<String>()
        val result = AgenticSearchHarness(
            model = model,
            searchTool = AgenticExactSearchTool { query, _, _ ->
                searched += query
                evidence(query, "result for $query")
            },
            queryAuthorizer = allowAll(),
        ).execute(request())
        val toolResults = model.toolResultsAt(1)

        // Call order is the transport order, and each id receives only its own result.
        assertEquals(listOf("first query", "second query"), searched)
        assertEquals(setOf("call-1", "call-2"), toolResults.keys)
        assertTrue(toolResults.getValue("call-1").contains("result for first query"))
        assertFalse(toolResults.getValue("call-1").contains("second query"))
        assertTrue(toolResults.getValue("call-2").contains("result for second query"))
        assertFalse(toolResults.getValue("call-2").contains("first query"))
        assertEquals(AgenticSearchOutcome.COMPLETED, result.outcome)
        assertEquals(2, result.diagnostics.issuedQueries)
    }

    @Test
    fun perRoundLimitKeepsNativeCallFeedbackPairedWhenAQueryIsOmitted() = runTest {
        val call = AiToolCall("call-limited", "web_search", """{"queries":["first query","second query"]}""")
        val model = CapturingModel(
            SearchAgentProtocol.NATIVE_TOOL_CALLS,
            SearchAgentReply.NativeToolCalls(listOf(call), finishReason = "tool_calls"),
            SearchAgentReply.Finished("finished"),
        )
        val searched = mutableListOf<String>()

        AgenticSearchHarness(
            model = model,
            searchTool = AgenticExactSearchTool { query, _, _ ->
                searched += query
                evidence(query, "result for $query")
            },
            queryAuthorizer = allowAll(),
        ).execute(request(limits = AgenticSearchLimits(maxDecisionRounds = 2, maxQueriesPerRound = 1, maxIssuedQueries = 2)))

        val toolResults = model.toolResultsAt(1)
        assertEquals(listOf("first query"), searched)
        assertEquals(setOf("call-limited"), toolResults.keys)
        assertTrue(toolResults.getValue("call-limited").contains("result for first query"))
        assertFalse(toolResults.getValue("call-limited").contains("result for second query"))
    }

    @Test
    fun issuedQueryBudgetFeedbackDoesNotPairFirstCallEvidenceWithSecondCallId() = runTest {
        val first = AiToolCall("call-first", "web_search", """{"queries":["first query"]}""")
        val second = AiToolCall("call-second", "web_search", """{"queries":["second query"]}""")
        val model = CapturingModel(
            SearchAgentProtocol.NATIVE_TOOL_CALLS,
            SearchAgentReply.NativeToolCalls(listOf(first, second), finishReason = "tool_calls"),
            SearchAgentReply.Finished("finished"),
        )
        val searched = mutableListOf<String>()

        AgenticSearchHarness(
            model = model,
            searchTool = AgenticExactSearchTool { query, _, _ ->
                searched += query
                evidence(query, "result for $query")
            },
            queryAuthorizer = allowAll(),
        ).execute(request(limits = AgenticSearchLimits(maxDecisionRounds = 2, maxQueriesPerRound = 2, maxIssuedQueries = 1)))

        val toolResults = model.toolResultsAt(1)
        assertEquals(listOf("first query"), searched)
        assertTrue(toolResults.getValue("call-first").contains("result for first query"))
        assertFalse(toolResults.getValue("call-first").contains("budget is exhausted"))
        assertTrue(toolResults.getValue("call-second").contains("budget is exhausted"))
        assertFalse(toolResults.getValue("call-second").contains("result for first query"))
    }

    @Test
    fun moreThanTheBoundedQueryCountAcrossCallsIsRejectedBeforeAnyTransport() = runTest {
        val first = AiToolCall("call-1", "web_search", """{"queries":["first query","second query"]}""")
        val second = AiToolCall("call-2", "web_search", """{"queries":["third query"]}""")
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.NATIVE_TOOL_CALLS
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.NativeToolCalls(listOf(first, second), finishReason = "tool_calls")
        }
        var searches = 0
        val result = AgenticSearchHarness(
            model,
            AgenticExactSearchTool { _, _, _ -> searches++; evidence("unexpected") },
            allowAll(),
        ).execute(request())

        assertEquals(0, searches)
        assertEquals(AgenticSearchOutcome.INVALID_TOOL_CALL, result.outcome)
        assertEquals("native_query_count", result.diagnostics.failureCode)
    }

    @Test
    fun oneDeniedQueryInAMultiCallResponseIsFeedbackNotASilentSearch() = runTest {
        val authorizedCall = AiToolCall("call-authorized", "web_search", """{"queries":["public query"]}""")
        val deniedCall = AiToolCall("call-denied", "web_search", """{"queries":["private-file.pdf"]}""")
        val model = CapturingModel(
            SearchAgentProtocol.NATIVE_TOOL_CALLS,
            SearchAgentReply.NativeToolCalls(listOf(authorizedCall, deniedCall), finishReason = "tool_calls"),
            SearchAgentReply.Finished("finished"),
        )
        val searched = mutableListOf<String>()
        AgenticSearchHarness(
            model = model,
            searchTool = AgenticExactSearchTool { query, _, _ ->
                searched += query
                evidence(query, "result for $query")
            },
            queryAuthorizer = AgenticSearchQueryAuthorizer { _, query ->
                if (query == "public query") AgenticQueryAuthorization.Authorized(query)
                else AgenticQueryAuthorization.Denied("unverified_query_expansion")
            },
        ).execute(request())
        val toolResults = model.toolResultsAt(1)

        assertEquals(listOf("public query"), searched)
        assertEquals(setOf("call-authorized", "call-denied"), toolResults.keys)
        assertTrue(toolResults.getValue("call-authorized").contains("result for public query"))
        assertFalse(toolResults.getValue("call-authorized").contains("private-file.pdf"))
        assertTrue(toolResults.getValue("call-denied").contains("denied by request-scoped privacy authorization"))
        assertFalse(toolResults.getValue("call-denied").contains("RESULT_1_BEGIN"))
    }

    @Test
    fun truncatedMalformedUnknownAndUnsupportedToolCallsNeverReachATransport() = runTest {
        val cases = listOf(
            "malformed_arguments" to AiToolCall("call-1", "web_search", """{"queries":["public"""),
            "malformed_arguments" to AiToolCall("call-1", "web_search", """{"queries": ["public"],}"""),
            "unknown_arguments" to AiToolCall("call-1", "web_search", """{"queries":["public"],"path":"/etc"}"""),
            "unsupported_tool" to AiToolCall("call-1", "shell", """{"queries":["public"]}"""),
            "missing_tool_call_id" to AiToolCall("", "web_search", """{"queries":["public"]}"""),
            "unknown_arguments" to AiToolCall("call-1", "web_search", """{"query":"public"}"""),
            "query_count" to AiToolCall("call-1", "web_search", """{"queries":["one","two","three"]}"""),
            "invalid_query" to AiToolCall("call-1", "web_search", """{"queries":["   "]}"""),
        )
        assertEquals("Every declared malformed/unsupported fixture must execute", 8, cases.size)
        cases.forEach { (expectedCode, call) ->
            val parsed = SearchHarnessProtocol.parseNativeToolCall(call)
            assertTrue(call.arguments, parsed.action is SearchToolAction.InvalidToolCall)
            assertEquals(call.arguments, expectedCode, parsed.errorCode)

            val model = object : SearchAgentModel {
                override val protocol = SearchAgentProtocol.NATIVE_TOOL_CALLS
                override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                    SearchAgentReply.NativeToolCalls(listOf(call), finishReason = "tool_calls")
            }
            var searches = 0
            val result = AgenticSearchHarness(
                model = model,
                searchTool = AgenticExactSearchTool { _, _, _ -> searches++; evidence("unexpected") },
                queryAuthorizer = allowAll(),
            ).execute(request())
            assertEquals(call.arguments, 0, searches)
            assertEquals(call.arguments, AgenticSearchOutcome.INVALID_TOOL_CALL, result.outcome)
        }
    }

    @Test
    fun truncatedNativeToolArgumentsAreRejectedBeforeAnyQueryIsIssued() = runTest {
        val truncated = AiToolCall("call-1", "web_search", """{"queries":["삼성전자 005930 KRX""")
        val parsed = SearchHarnessProtocol.parseNativeToolCall(truncated)
        assertEquals("malformed_arguments", parsed.errorCode)
        assertEquals(SearchToolAction.InvalidToolCall, parsed.action)
    }

    @Test
    fun malformedStructuredIntentIsInvalidAndNeverBecomesASearchResult() = runTest {
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["gone"""")
        }
        var searches = 0
        val result = AgenticSearchHarness(
            model = model,
            searchTool = AgenticExactSearchTool { _, _, _ -> searches++; evidence("unexpected") },
            queryAuthorizer = allowAll(),
        ).execute(request())

        assertEquals(0, searches)
        assertEquals(AgenticSearchOutcome.INVALID_TOOL_CALL, result.outcome)
        assertNull(result.evidence)
    }

    @Test
    fun invalidProviderToolResponseCannotBecomeASuccessfulSearchResult() = runTest {
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.NATIVE_TOOL_CALLS
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.NativeToolCalls(
                    listOf(AiToolCall("call-1", "web_search", "not json at all")),
                    finishReason = "tool_calls",
                )
        }
        var exactCalls = 0
        var fallbackCalls = 0
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> exactCalls++; error("must not run") },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor {
                fallbackCalls++
                LegacySearchFallbackResult.Completed(null)
            },
        ).execute(routeRequest(), model)

        assertEquals(0, exactCalls)
        assertEquals(1, fallbackCalls)
        assertEquals(SearchRouteOutcome.FALLBACK_FAILED, routed.outcome)
        assertNull(routed.response)
        assertEquals(0, routed.searchQueriesIssued)
    }

    @Test
    fun unsupportedNativeProtocolFallsBackWithoutExecutingASearch() = runTest {
        var exactCalls = 0
        var fallbackCalls = 0
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> exactCalls++; evidence("unexpected") },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor {
                fallbackCalls++
                LegacySearchFallbackResult.Completed(evidence("legacy"))
            },
        ).execute(
            routeRequest(),
            object : SearchAgentModel {
                override val protocol = SearchAgentProtocol.NATIVE_TOOL_CALLS
                override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                    SearchAgentReply.Unsupported("provider_rejected_web_search_tool")
            },
        )

        assertEquals(0, exactCalls)
        assertEquals(1, fallbackCalls)
        assertEquals(SearchFallbackReason.NATIVE_TOOLS_UNSUPPORTED, routed.fallbackReason)
        assertEquals(SearchRouteOutcome.FALLBACK_COMPLETED, routed.outcome)
    }

    @Test
    fun transportCancellationPropagatesWithoutEmittingAResultOrAFallback() = runTest {
        val entered = CompletableDeferred<Unit>()
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.NATIVE_TOOL_CALLS
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.NativeToolCalls(
                    listOf(AiToolCall("call-1", "web_search", """{"queries":["public query"]}""")),
                    finishReason = "tool_calls",
                )
        }
        var attempts = 0
        var fallbackCalls = 0
        val task = async {
            SearchStrategyRouter(
                exactSearchTool = AgenticExactSearchTool { _, _, _ ->
                    attempts++
                    entered.complete(Unit)
                    // Parks inside the transport so the test can cancel mid-flight.
                    CompletableDeferred<FusionSearchResponse>().await()
                },
                queryAuthorizer = allowAll(),
                legacyFallback = LegacySearchFallbackExecutor {
                    fallbackCalls++
                    LegacySearchFallbackResult.Completed(evidence("unexpected"))
                },
            ).execute(routeRequest(), model)
        }
        task.start()
        entered.await()
        task.cancel()
        runCatching { task.await() }

        assertEquals(1, attempts)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun aCancelledTransportLeavesNoEgressTicketForALaterRequest() = runTest {
        // The exact-transport authorization lambda is rechecked per request,
        // so a revoked owner cancels the in-flight transport and nothing else.
        var attempts = 0
        var ownerActive = true
        val tool = AgenticExactSearchTool { _, _, stillAuthorized ->
            attempts++
            ownerActive = false
            assertFalse(stillAuthorized())
            evidence("stale")
        }
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.NATIVE_TOOL_CALLS
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.NativeToolCalls(
                    listOf(AiToolCall("call-1", "web_search", """{"queries":["public query"]}""")),
                    finishReason = "tool_calls",
                )
        }
        val result = AgenticSearchHarness(model, tool, allowAll()).execute(
            request(owner = { ownerActive }),
        )

        assertEquals(1, attempts)
        assertEquals(AgenticSearchOutcome.OWNERSHIP_LOST, result.outcome)
        // Ownership loss discards the evidence that the transport already returned.
        assertNull(result.evidence)
    }

    // ---- helpers -----------------------------------------------------------

    private class CapturingModel(
        override val protocol: SearchAgentProtocol,
        vararg replies: SearchAgentReply,
    ) : SearchAgentModel {
        private val replies = replies.toMutableList()
        private val seen = mutableListOf<List<SearchAgentMessage>>()
        override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
            seen += messages
            return replies.removeAt(0)
        }

        fun toolResultsAt(roundIndex: Int): Map<String, String> = seen
            .getOrNull(roundIndex)
            ?.filter { it.role == SearchAgentMessageRole.TOOL }
            ?.associate { (it.toolCallId ?: "") to it.content }
            .orEmpty()
    }

    private fun request(
        owner: () -> Boolean = { true },
        limits: AgenticSearchLimits = AgenticSearchLimits.EXTERNAL_DEFAULT,
    ) = AgenticSearchRequest(
        requestId = "p10c-request",
        originalUserQuery = "user typed question",
        webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
        limits = limits,
        ownerStillActive = owner,
        searchPermissionStillValid = { true },
    )

    private fun routeRequest() = SearchStrategyRouteRequest(
        agentRequest = request(),
        strategy = SearchStrategySnapshot.DEFAULT,
    )

    private fun allowAll() = AgenticSearchQueryAuthorizer { _, query -> AgenticQueryAuthorization.Authorized(query) }

    private fun evidence(query: String, snippet: String = "A public source snippet."): FusionSearchResponse {
        val result = FusionSearchResult(
            title = "Evidence for $query",
            source = "Example Source",
            url = "https://example.test/source",
            snippet = snippet,
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
