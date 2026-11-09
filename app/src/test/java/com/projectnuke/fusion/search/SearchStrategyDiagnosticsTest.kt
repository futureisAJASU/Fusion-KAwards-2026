package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.SearchStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchStrategyDiagnosticsTest {
    @Test
    fun eventReportsStrategyAdapterCountsFallbackSourcesAndDeadlineWithoutQueryText() {
        val privateQuery = "private customer account 123456"
        val privateSnippet = "internal note: do not share"
        val source = WebSearchSource(
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
        )
        val route = SearchStrategyRouteResult(
            outcome = SearchRouteOutcome.FALLBACK_COMPLETED,
            response = FusionSearchResponse(
                query = privateQuery,
                normalizedQuery = privateQuery,
                intent = SearchIntent.GENERAL,
                results = emptyList(),
                debugMessage = null,
                sources = listOf(source),
            ),
            selectedStrategy = SearchStrategy.AGENT_HARNESS,
            executedStrategy = SearchStrategy.LEGACY_RULE_BASED,
            fallbackReason = SearchFallbackReason.INVALID_TOOL_CALL,
            fallbackAttempted = true,
            fallbackQueriesIssued = 1,
            agentDiagnostics = AgenticSearchDiagnostics(
                protocol = SearchAgentProtocol.STRUCTURED_INTENT,
                planningCalls = 2,
                issuedQueries = 1,
                rounds = 2,
                outcome = AgenticSearchOutcome.INVALID_TOOL_CALL,
                failureCode = "invalid_structured_action",
                legacyFallbackAllowed = true,
                planningElapsedMs = 123,
                searchTransportElapsedMs = 456,
            ),
            searchQueriesIssued = 3,
            planningElapsedMs = 123,
            searchTransportElapsedMs = 456,
        )

        val event = SearchStrategyDiagnosticEvent.from(route, deadlineMs = 75_000, elapsedMs = 2_400)
        val line = event.toSafeLogLine()

        assertEquals(SearchStrategy.AGENT_HARNESS, event.selectedStrategy)
        assertEquals(SearchStrategy.LEGACY_RULE_BASED, event.executedStrategy)
        assertEquals(SearchAgentProtocol.STRUCTURED_INTENT, event.adapter)
        assertEquals(2, event.planningCalls)
        assertEquals(3, event.searchQueriesIssued)
        assertEquals(2, event.searchRounds)
        assertTrue(event.fallbackUsed)
        assertTrue(event.fallbackEligible)
        assertTrue(event.fallbackAttempted)
        assertTrue(event.fallbackTransportExecuted)
        assertTrue(event.fallbackCompleted)
        assertEquals(SearchFallbackReason.INVALID_TOOL_CALL, event.fallbackReason)
        assertEquals(1, event.sourceCount)
        assertEquals(75_000L, event.deadlineMs)
        assertEquals(2_400L, event.elapsedMs)
        assertEquals(123L, event.planningElapsedMs)
        assertEquals(456L, event.searchTransportElapsedMs)
        assertTrue(line.contains("outcome=FALLBACK_COMPLETED"))
        assertTrue(line.contains("planningMs=123"))
        assertTrue(line.contains("searchTransportMs=456"))
        assertFalse(line.contains(privateQuery))
        assertFalse(line.contains(privateSnippet))
        assertFalse(line.contains("Private title"))
        assertFalse(line.contains("sk-secret"))
    }

    @Test
    fun cancellationDiagnosticsMarkUnavailableCountsInsteadOfInventingThem() {
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

        assertTrue(line.contains("planning=UNKNOWN"))
        assertTrue(line.contains("queries=UNKNOWN"))
        assertTrue(line.contains("rounds=UNKNOWN"))
        assertTrue(line.contains("sources=UNKNOWN"))
        assertTrue(line.contains("planningMs=UNKNOWN"))
        assertTrue(line.contains("searchTransportMs=UNKNOWN"))
        assertTrue(line.contains("outcome=CANCELLED"))
    }
}
