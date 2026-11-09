package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.search.FusionSearchResponse
import com.projectnuke.fusion.search.FusionSearchResult
import com.projectnuke.fusion.search.SearchAnswerSufficiencyStatus
import com.projectnuke.fusion.search.SearchEvidenceStatus
import com.projectnuke.fusion.search.SearchIntent
import com.projectnuke.fusion.search.SearchRouteOutcome
import com.projectnuke.fusion.search.SearchStrategyRouteResult
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPhase3CrossLayerInvariantTest {
    @Test
    fun clippedCorrectionRecordCannotBecomeAttributedSupport() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val snippet = "The Bank of Korea policy rate is 3.00%." +
            " Background context about monetary policy and market conditions.".repeat(5) +
            " However, the Bank later corrected the record: the current policy rate is 2.50%, not 3.00%."
        val response = response(
            query = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(
                result(
                    query = query,
                    title = "Bank of Korea policy rate update",
                    url = "https://www.bok.or.kr/eng/base-rate",
                    snippet = snippet,
                    rank = 1,
                ),
            ),
        )

        val consumption = SearchEvidenceConsumption.from(route(response), query)

        assertTrue(consumption.actualWebSearchUsed)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, consumption.answerSufficiency)
        assertEquals(SearchEvidenceStatus.ANSWER_INSUFFICIENT, consumption.evidenceStatus)
        assertFalse(consumption.usableForTrustSearch)
        assertNotNull(consumption.deterministicAbstentionAnswer)
        assertNull(consumption.structuredContext)
        assertTrue("no false source attribution", consumption.sources.isEmpty())
        assertEquals("retrieval provenance is retained for audit", 1, consumption.retrievedSources.size)

        val persisted = consumption.persistedSearchFailureContent(
            answer = consumption.deterministicAbstentionAnswer.orEmpty(),
            trustRoutingDecision = com.projectnuke.fusion.trust.TrustRoutingDecision(
                route = com.projectnuke.fusion.trust.TrustRoute.SEARCH,
                reason = com.projectnuke.fusion.trust.TrustRoutingReason.EXPLICIT_WEB_REQUEST,
                signals = emptySet(),
            ),
        )
        assertFalse(persisted.contains("[FUSION_SEARCH_SOURCES]"))
    }

    @Test
    fun wrongEntityOfficialAndStaleCorrectEntityResultCannotAnswer() {
        val query = "What is the current price of the Galaxy S26 Ultra?"
        val response = response(
            query = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(
                result(
                    query = query,
                    title = "Galaxy S26 price update",
                    url = "https://www.samsung.com/galaxy-s26-price",
                    snippet = "The Galaxy S26 price is $999.",
                    publishedAt = LocalDate.now().minusDays(1).toString(),
                    rank = 1,
                ),
                result(
                    query = query,
                    title = "Galaxy S26 Ultra price report",
                    url = "https://blog.example/galaxy-s26-ultra-price",
                    snippet = "The Galaxy S26 Ultra price is $1,049.",
                    publishedAt = LocalDate.now().minusDays(400).toString(),
                    rank = 2,
                ),
            ),
        )

        val consumption = SearchEvidenceConsumption.from(route(response), query)

        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, consumption.answerSufficiency)
        assertFalse(consumption.usableForTrustSearch)
        assertTrue(consumption.sources.isEmpty())
        assertNull(consumption.structuredContext)
    }

    @Test
    fun lastRankedDecisiveEvidenceStillReachesPromptAndAttribution() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val response = response(
            query = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(
                result(
                    query = query,
                    title = "Bank of Korea policy overview",
                    url = "https://www.bok.or.kr/eng/overview",
                    snippet = "Background information about the monetary policy framework.",
                    publishedAt = LocalDate.now().minusDays(1).toString(),
                    rank = 1,
                ),
                result(
                    query = query,
                    title = "Monetary policy explainer",
                    url = "https://news.example/bok-explainer",
                    snippet = "An explainer about how the central bank sets the policy rate.",
                    publishedAt = LocalDate.now().minusDays(1).toString(),
                    rank = 2,
                ),
                result(
                    query = query,
                    title = "Rate commentary",
                    url = "https://news.example/rate-commentary",
                    snippet = "Commentary about the latest rate decision and inflation outlook.",
                    publishedAt = LocalDate.now().minusDays(1).toString(),
                    rank = 3,
                ),
                result(
                    query = query,
                    title = "Bank of Korea Base Rate",
                    url = "https://www.bok.or.kr/eng/base-rate",
                    snippet = "The Bank of Korea Base Rate is 3.00%.",
                    publishedAt = LocalDate.now().minusDays(1).toString(),
                    rank = 4,
                ),
            ),
        )

        val consumption = SearchEvidenceConsumption.from(route(response), query)

        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, consumption.answerSufficiency)
        assertTrue(consumption.usableForTrustSearch)
        assertTrue(
            "the decisive record must reach the model-visible context",
            consumption.structuredContext.orEmpty().contains("Base Rate is 3.00%"),
        )
        assertTrue(
            "the decisive record must be attributed",
            consumption.sources.any { it.url == "https://www.bok.or.kr/eng/base-rate" },
        )
    }

    private fun result(
        query: String,
        title: String,
        url: String,
        snippet: String,
        publishedAt: String = LocalDate.now().minusDays(1).toString(),
        rank: Int = 1,
    ) = FusionSearchResult(
        title = title,
        source = if (url.contains("bok.or.kr")) "bok.or.kr" else "news.example",
        url = url,
        snippet = snippet,
        publishedAt = publishedAt,
        queryUsed = query,
        rank = rank,
    )

    private fun response(
        query: String,
        intent: SearchIntent,
        results: List<FusionSearchResult>,
    ) = FusionSearchResponse(
        query = query,
        normalizedQuery = query,
        intent = intent,
        results = results,
        debugMessage = null,
        sources = results.map { result ->
            WebSearchSource(
                title = result.title,
                source = result.source,
                url = result.url,
                snippet = result.snippet,
                publishedAt = result.publishedAt,
                providerType = WebSearchProviderType.FREE_DEFAULT,
                providerDisplayName = "Free",
                searchCategory = null,
                queryUsed = result.queryUsed,
                rank = result.rank,
            )
        },
    )

    private fun route(response: FusionSearchResponse) = SearchStrategyRouteResult(
        outcome = SearchRouteOutcome.COMPLETED,
        response = response,
        selectedStrategy = SearchStrategy.AGENT_HARNESS,
        executedStrategy = SearchStrategy.AGENT_HARNESS,
    )
}
