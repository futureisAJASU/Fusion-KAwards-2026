package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.chat.SearchStrategyPreference
import com.projectnuke.fusion.chat.SearchStrategyPreferenceAccess
import com.projectnuke.fusion.chat.SearchStrategySettingsStore
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// P10-E: bounded diagnostics, a safe fresh-install default, and a visible
// label that describes the strategy that actually ran.
class P10DiagnosticsSettingsTest {

    // ---- bounded, privacy-safe diagnostics ---------------------------------

    @Test
    fun agentReasonCodeIsBoundedAndNeverCarriesQueryOrSourceText() {
        val privateQuery = "private customer account 123456"
        val privateSnippet = "internal note: do not share"
        val route = route(
            outcome = SearchRouteOutcome.FALLBACK_COMPLETED,
            executed = SearchStrategy.LEGACY_RULE_BASED,
            fallbackReason = SearchFallbackReason.NO_USABLE_EVIDENCE,
            failureCode = "explicit_retrieval_not_attempted",
            sources = listOf(
                WebSearchSource(
                    title = "Private title",
                    source = "Private publisher",
                    url = "https://example.test/source",
                    snippet = privateSnippet,
                    publishedAt = null,
                    providerType = WebSearchProviderType.FREE_DEFAULT,
                    providerDisplayName = "Free",
                    searchCategory = null,
                    queryUsed = privateQuery,
                    rank = 1,
                ),
            ),
        )

        val event = SearchStrategyDiagnosticEvent.from(route, deadlineMs = 75_000, elapsedMs = 1_200)
        val line = event.toSafeLogLine()

        assertEquals("explicit_retrieval_not_attempted", event.agentReasonCode)
        assertTrue(line.contains("agentReason=explicit_retrieval_not_attempted"))
        assertFalse(line.contains(privateQuery))
        assertFalse(line.contains(privateSnippet))
        assertFalse(line.contains("Private title"))
    }

    @Test
    fun reasonCodeThatIsNotIdentifierShapedIsDroppedInsteadOfLogged() {
        assertNull(SearchStrategyDiagnosticEvent.sanitizeReasonCode("query with spaces"))
        assertNull(SearchStrategyDiagnosticEvent.sanitizeReasonCode("x".repeat(200)))
        assertNull(SearchStrategyDiagnosticEvent.sanitizeReasonCode(""))
        assertNull(SearchStrategyDiagnosticEvent.sanitizeReasonCode(null))
        assertEquals("local_model_cold_start", SearchStrategyDiagnosticEvent.sanitizeReasonCode("local_model_cold_start"))
    }

    @Test
    fun budgetExhaustionIsVisibleInDiagnostics() {
        val exhausted = SearchStrategyDiagnosticEvent.from(
            route(
                outcome = SearchRouteOutcome.AGENT_FAILED,
                executed = SearchStrategy.AGENT_HARNESS,
                failureCode = "round_budget_exhausted",
                agentOutcome = AgenticSearchOutcome.BUDGET_EXHAUSTED,
            ),
            deadlineMs = 75_000,
            elapsedMs = 1_200,
        )

        assertTrue(exhausted.budgetLimitHit)
        assertTrue(exhausted.toSafeLogLine().contains("budgetLimitHit=true"))

        val completed = SearchStrategyDiagnosticEvent.from(
            route(
                outcome = SearchRouteOutcome.COMPLETED,
                executed = SearchStrategy.AGENT_HARNESS,
                agentOutcome = AgenticSearchOutcome.COMPLETED,
            ),
            deadlineMs = 75_000,
            elapsedMs = 1_200,
        )

        assertFalse(completed.budgetLimitHit)
        assertTrue(completed.toSafeLogLine().contains("budgetLimitHit=false"))
    }

    @Test
    fun unknownCountsStayUnknownInsteadOfBeingInvented() {
        val event = SearchStrategyDiagnosticEvent(
            selectedStrategy = SearchStrategy.AGENT_HARNESS,
            executedStrategy = null,
            adapter = null,
            planningCalls = null,
            searchQueriesIssued = null,
            searchRounds = null,
            fallbackUsed = false,
            fallbackReason = null,
            sourceCount = null,
            deadlineMs = 90_000,
            elapsedMs = 500,
            outcome = SearchRouteOutcome.CANCELLED,
        )

        val line = event.toSafeLogLine()

        assertTrue(line.contains("agentReason=NONE"))
        assertTrue(line.contains("budgetLimitHit=false"))
        assertTrue(line.contains("outcome=CANCELLED"))
    }

    @Test
    fun failedTransportAttemptsAreCountedAsIssuedQueries() = runTest {
        var attempts = 0
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
                attempts++
                return SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["public query"]}""")
            }
        }
        val routed = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> throw IllegalStateException("transport down") },
            queryAuthorizer = { _, query -> AgenticQueryAuthorization.Authorized(query) },
            legacyFallback = LegacySearchFallbackExecutor { error("must not run") },
        ).execute(
            SearchStrategyRouteRequest(
                agentRequest = AgenticSearchRequest(
                    requestId = "p10e-transport",
                    originalUserQuery = "public query",
                    webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                    limits = AgenticSearchLimits(maxDecisionRounds = 1),
                    ownerStillActive = { true },
                    searchPermissionStillValid = { true },
                ),
                strategy = SearchStrategySnapshot.DEFAULT,
            ),
            model,
        )

        assertEquals(1, attempts)
        assertEquals(1, routed.searchQueriesIssued)
        assertEquals(1, routed.agentDiagnostics?.issuedQueries)
        assertEquals(AgenticSearchOutcome.TOOL_FAILURE, routed.agentDiagnostics?.outcome)
    }

    // ---- fresh-install default ---------------------------------------------

    @Test
    fun freshInstallSelectsTheAgentHarnessWithoutGrantingSearchPermission() {
        val store = SearchStrategySettingsStore(EmptyPreferences())
        val preference = store.read()
        val snapshot = store.captureRequestSnapshot()

        assertEquals(SearchStrategyPreference(SearchStrategy.AGENT_HARNESS, true), preference)
        assertEquals(SearchStrategy.AGENT_HARNESS, snapshot.strategy)
        assertTrue(snapshot.allowLegacyFallback)
        // Selecting a strategy never grants web-search permission.
        assertEquals(
            GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            GenerationRequestSnapshot.resolveWebSearchPolicy(false, false, false),
        )
    }

    // ---- visible labels follow the executed strategy -----------------------

    @Test
    fun fallbackWithoutTransportDoesNotClaimSearchWasPerformed() {
        val noTransport = route(
            outcome = SearchRouteOutcome.FALLBACK_FAILED,
            executed = SearchStrategy.LEGACY_RULE_BASED,
            fallbackReason = SearchFallbackReason.NO_USABLE_EVIDENCE,
        ).copy(fallbackAttempted = true, fallbackQueriesIssued = 0)
        val notice = SearchStrategyPresentationPolicy.executedNotice(noTransport)
        assertNotNull(notice)
        assertFalse(notice!!.contains("로 검색했습니다"))
    }

    @Test
    fun failedFallbackTransportIsNotPresentedAsSuccessfulRetrieval() {
        val failed = route(
            outcome = SearchRouteOutcome.FALLBACK_FAILED,
            executed = SearchStrategy.LEGACY_RULE_BASED,
            fallbackReason = SearchFallbackReason.NO_USABLE_EVIDENCE,
        ).copy(fallbackAttempted = true, fallbackQueriesIssued = 1)
        val notice = SearchStrategyPresentationPolicy.executedNotice(failed)
        assertNotNull(notice)
        assertTrue(notice!!.contains("시도"))
        assertFalse(notice.contains("로 검색했습니다"))
    }

    @Test
    fun timeoutBeforeFallbackTransportIsReportedAsTimeoutWithoutSearch() {
        val timedOut = route(
            outcome = SearchRouteOutcome.TIMEOUT,
            executed = SearchStrategy.AGENT_HARNESS,
            fallbackReason = SearchFallbackReason.NO_USABLE_EVIDENCE,
        ).copy(fallbackAttempted = true, fallbackQueriesIssued = 0)
        val notice = SearchStrategyPresentationPolicy.executedNotice(timedOut)
        assertNotNull(notice)
        assertTrue(notice!!.contains("시간 초과"))
        assertFalse(notice.contains("로 검색했습니다"))
    }

    @Test
    fun labelDescribesTheExecutedStrategyNotTheSelectedOne() {
        val fallback = route(
            outcome = SearchRouteOutcome.FALLBACK_COMPLETED,
            executed = SearchStrategy.LEGACY_RULE_BASED,
            fallbackReason = SearchFallbackReason.NO_USABLE_EVIDENCE,
        )
        val notice = SearchStrategyPresentationPolicy.executedNotice(fallback)
        assertNotNull(notice)
        assertTrue(notice!!.contains("Classic Search"))
        assertFalse(notice.contains("AI Search Agent로 검색했습니다"))

        val agentRun = route(
            outcome = SearchRouteOutcome.COMPLETED,
            executed = SearchStrategy.AGENT_HARNESS,
        )
        assertNull(SearchStrategyPresentationPolicy.executedNotice(agentRun))
    }

    @Test
    fun agentFailureWithRetainedUsableEvidenceDoesNotClaimNoResultsWereFound() {
        val failedAfterEvidence = route(
            outcome = SearchRouteOutcome.AGENT_FAILED,
            executed = SearchStrategy.AGENT_HARNESS,
            agentOutcome = AgenticSearchOutcome.INVALID_TOOL_CALL,
        ).copy(response = usableEvidence())

        val notice = SearchStrategyPresentationPolicy.executedNotice(failedAfterEvidence)

        assertNotNull(notice)
        assertTrue(notice!!.contains("사용 가능한 검색 결과"))
        assertFalse(notice.contains("찾지 못했습니다"))
    }

    @Test
    fun timeoutWithRetainedEvidenceHasATruthfulPartialResultNotice() {
        val timedOutAfterEvidence = route(
            outcome = SearchRouteOutcome.TIMEOUT,
            executed = SearchStrategy.AGENT_HARNESS,
        ).copy(response = usableEvidence())

        val notice = SearchStrategyPresentationPolicy.executedNotice(timedOutAfterEvidence)

        assertNotNull(notice)
        assertTrue(notice!!.contains("시간 초과"))
        assertTrue(notice.contains("검색 결과"))
    }

    @Test
    fun fallbackReasonWithoutFallbackExecutionIsNotReportedAsUsed() {
        val notAttempted = route(
            outcome = SearchRouteOutcome.TIMEOUT,
            executed = SearchStrategy.AGENT_HARNESS,
            fallbackReason = SearchFallbackReason.NO_USABLE_EVIDENCE,
        ).copy(
            searchQueriesIssued = 0,
            fallbackAttempted = false,
            agentDiagnostics = route(
                outcome = SearchRouteOutcome.TIMEOUT,
                executed = SearchStrategy.AGENT_HARNESS,
                fallbackReason = SearchFallbackReason.NO_USABLE_EVIDENCE,
            ).agentDiagnostics?.copy(issuedQueries = 0),
        )

        val event = SearchStrategyDiagnosticEvent.from(notAttempted, deadlineMs = 75_000, elapsedMs = 1_200)

        assertEquals(SearchFallbackReason.NO_USABLE_EVIDENCE, event.fallbackReason)
        assertTrue(event.fallbackEligible)
        assertFalse(event.fallbackAttempted)
        assertFalse(event.fallbackTransportExecuted)
        assertFalse(event.fallbackCompleted)
        assertFalse(event.fallbackUsed)
    }

    @Test
    fun agentFailureWithoutFallbackIsNeverPresentedAsASuccessfulAgentSearch() {
        val failed = route(
            outcome = SearchRouteOutcome.AGENT_FAILED,
            executed = SearchStrategy.AGENT_HARNESS,
            agentOutcome = AgenticSearchOutcome.NO_USABLE_EVIDENCE,
        )

        val notice = SearchStrategyPresentationPolicy.executedNotice(failed)

        assertNotNull(notice)
        assertTrue(notice!!.contains("AI Search Agent"))
    }

    @Test
    fun noNoticeIsShownWhenNoStrategyRan() {
        val noSearch = route(
            outcome = SearchRouteOutcome.SEARCH_DISABLED,
            executed = null,
        )

        assertNull(SearchStrategyPresentationPolicy.executedNotice(noSearch))
    }

    @Test
    fun settingsChoicesUseTheSameLabelsAsTheExecutedStrategyNotice() {
        assertEquals(
            "AI Search Agent",
            SearchStrategyPresentationPolicy.choiceTitle(SearchStrategy.AGENT_HARNESS),
        )
        assertEquals(
            "Classic Search",
            SearchStrategyPresentationPolicy.choiceTitle(SearchStrategy.LEGACY_RULE_BASED),
        )
        assertEquals(
            "Manual Search Query",
            SearchStrategyPresentationPolicy.choiceTitle(SearchStrategy.MANUAL_QUERY),
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun route(
        outcome: SearchRouteOutcome,
        executed: SearchStrategy?,
        fallbackReason: SearchFallbackReason? = null,
        failureCode: String? = null,
        agentOutcome: AgenticSearchOutcome = AgenticSearchOutcome.COMPLETED,
        sources: List<WebSearchSource> = emptyList(),
    ) = SearchStrategyRouteResult(
        outcome = outcome,
        response = FusionSearchResponse(
            query = "query",
            normalizedQuery = "query",
            intent = SearchIntent.GENERAL,
            results = emptyList(),
            debugMessage = null,
            sources = sources,
        ),
        selectedStrategy = SearchStrategy.AGENT_HARNESS,
        executedStrategy = executed,
        fallbackReason = fallbackReason,
        agentDiagnostics = AgenticSearchDiagnostics(
            protocol = SearchAgentProtocol.STRUCTURED_INTENT,
            planningCalls = 1,
            issuedQueries = 1,
            rounds = 1,
            outcome = agentOutcome,
            failureCode = failureCode,
            legacyFallbackAllowed = fallbackReason != null,
        ),
        searchQueriesIssued = 1,
        fallbackAttempted = fallbackReason != null,
        fallbackQueriesIssued = if (fallbackReason != null && outcome == SearchRouteOutcome.FALLBACK_COMPLETED) 1 else 0,
    )

    private fun usableEvidence() = FusionSearchResponse(
        query = "public query",
        normalizedQuery = "public query",
        intent = SearchIntent.GENERAL,
        results = listOf(
            FusionSearchResult(
                title = "Usable public result",
                source = "Example",
                url = "https://example.test/evidence",
                snippet = "A usable public snippet.",
                publishedAt = null,
                queryUsed = "public query",
            ),
        ),
        debugMessage = null,
        sources = emptyList(),
    )

    private class EmptyPreferences : SearchStrategyPreferenceAccess {
        override fun getString(key: String): String? = null
        override fun write(values: Map<String, String>): Boolean = true
    }
}
