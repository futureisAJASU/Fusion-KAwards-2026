package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.search.FusionSearchResponse
import com.projectnuke.fusion.search.FusionSearchResult
import com.projectnuke.fusion.search.SearchAnswerSufficiencyStatus
import com.projectnuke.fusion.search.SearchIntent
import com.projectnuke.fusion.search.SearchRouteOutcome
import com.projectnuke.fusion.search.SearchStrategyRouteResult
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.search.parseRetrievedSearchResultsMetadata
import com.projectnuke.fusion.search.parseSearchSourcesMetadata
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// GATE B: the transcript must never disagree with the model-facing evidence.
// Attribution, retrieval provenance, and the final prompt are one state.
class SearchPhase3AttributionStateTest {
    @Test
    fun attributedSourcesAreExactlyTheModelFacingEvidence() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.COMPLETED, sufficientResponse(query)), query)

        val persisted = consumption.persistedAssistantContent(
            answer = "The Bank of Korea policy rate is 3.00%.",
            trustRoutingDecision = TRUST,
        )

        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, consumption.answerSufficiency)
        assertEquals(
            consumption.sources.map { it.url },
            parseSearchSourcesMetadata(persisted).map { it.url },
        )
        assertEquals(
            consumption.retrievedSources.map { it.url },
            parseRetrievedSearchResultsMetadata(persisted).map { it.url },
        )
    }

    @Test
    fun insufficientEvidenceKeepsProvenanceWithoutAttributingSources() {
        val query = "What is the current price of the Galaxy S26 Ultra?"
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(
                result(
                    query = query,
                    title = "Galaxy S26 Ultra price report",
                    url = "https://blog.example/galaxy-s26-ultra-price",
                    snippet = "The Galaxy S26 Ultra price is $1,049.",
                    publishedAt = LocalDate.now().minusDays(400).toString(),
                    rank = 1,
                ),
            ),
            debugMessage = null,
            sources = listOf(
                source(
                    query = query,
                    title = "Galaxy S26 Ultra price report",
                    url = "https://blog.example/galaxy-s26-ultra-price",
                    snippet = "The Galaxy S26 Ultra price is $1,049.",
                    publishedAt = LocalDate.now().minusDays(400).toString(),
                    rank = 1,
                ),
            ),
        )
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.COMPLETED, response), query)

        val persisted = consumption.persistedSearchFailureContent(
            answer = consumption.deterministicAbstentionAnswer.orEmpty(),
            trustRoutingDecision = TRUST,
        )

        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, consumption.answerSufficiency)
        assertTrue(consumption.sources.isEmpty())
        assertNull(consumption.structuredContext)
        assertTrue(parseSearchSourcesMetadata(persisted).isEmpty())
        assertEquals(
            1,
            parseRetrievedSearchResultsMetadata(persisted).size,
        )
    }

    @Test
    fun failedFallbackFailsClosedOnAttribution() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(
                result(
                    query = query,
                    title = "   ",
                    url = "https://news.example/failed-fallback",
                    snippet = "   ",
                    publishedAt = null,
                    rank = 1,
                ),
            ),
            debugMessage = null,
            sources = listOf(
                source(
                    query = query,
                    title = "   ",
                    url = "https://news.example/failed-fallback",
                    snippet = "   ",
                    publishedAt = null,
                    rank = 1,
                ),
            ),
        )
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.FALLBACK_FAILED, response), query)

        val persisted = consumption.persistedSearchFailureContent(
            answer = "no answer",
            trustRoutingDecision = TRUST,
        )

        assertTrue(consumption.sources.isEmpty())
        assertNull(consumption.structuredContext)
        assertTrue(parseSearchSourcesMetadata(persisted).isEmpty())
    }

    @Test
    fun modelTextCannotIntroduceSourceAttribution() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.COMPLETED, sufficientResponse(query)), query)

        val persisted = consumption.persistedAssistantContent(
            answer = "The rate is 3.00% according to https://unattested.example/claim",
            trustRoutingDecision = TRUST,
        )

        val attributed = parseSearchSourcesMetadata(persisted)
        assertEquals(consumption.sources.map { it.url }, attributed.map { it.url })
        assertTrue(attributed.none { it.url.orEmpty().contains("unattested.example") })
    }

    @Test
    fun timeoutWithRecoveredEvidenceStillAttributesExactlyThatEvidence() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.TIMEOUT, sufficientResponse(query)), query)

        val persisted = consumption.persistedAssistantContent(
            answer = "The Bank of Korea policy rate is 3.00%.",
            trustRoutingDecision = TRUST,
        )

        assertTrue(consumption.sources.isNotEmpty())
        assertEquals(
            consumption.sources.map { it.url },
            parseSearchSourcesMetadata(persisted).map { it.url },
        )
    }

    private fun sufficientResponse(query: String) = FusionSearchResponse(
        query = query,
        normalizedQuery = query,
        intent = SearchIntent.CURRENT_INFO,
        results = listOf(
            result(
                query = query,
                title = "Bank of Korea Base Rate",
                url = "https://www.bok.or.kr/eng/base-rate",
                snippet = "The Bank of Korea Base Rate is 3.00%.",
                publishedAt = LocalDate.now().minusDays(1).toString(),
                rank = 1,
            ),
        ),
        debugMessage = null,
        sources = listOf(
            source(
                query = query,
                title = "Bank of Korea Base Rate",
                url = "https://www.bok.or.kr/eng/base-rate",
                snippet = "The Bank of Korea Base Rate is 3.00%.",
                publishedAt = LocalDate.now().minusDays(1).toString(),
                rank = 1,
            ),
        ),
    )

    private fun result(
        query: String,
        title: String,
        url: String,
        snippet: String,
        publishedAt: String?,
        rank: Int,
    ) = FusionSearchResult(
        title = title,
        source = if (url.contains("bok.or.kr")) "bok.or.kr" else "news.example",
        url = url,
        snippet = snippet,
        publishedAt = publishedAt,
        queryUsed = query,
        rank = rank,
    )

    private fun source(
        query: String,
        title: String,
        url: String,
        snippet: String,
        publishedAt: String?,
        rank: Int,
    ) = WebSearchSource(
        title = title,
        source = if (url.contains("bok.or.kr")) "bok.or.kr" else "news.example",
        url = url,
        snippet = snippet,
        publishedAt = publishedAt,
        providerType = WebSearchProviderType.FREE_DEFAULT,
        providerDisplayName = "Free",
        searchCategory = null,
        queryUsed = query,
        rank = rank,
    )

    private fun route(
        outcome: SearchRouteOutcome,
        response: FusionSearchResponse?,
    ) = SearchStrategyRouteResult(
        outcome = outcome,
        response = response,
        selectedStrategy = SearchStrategy.AGENT_HARNESS,
        executedStrategy = SearchStrategy.AGENT_HARNESS,
    )

    private companion object {
        val TRUST = TrustRoutingDecision(
            TrustRoute.SEARCH,
            TrustRoutingReason.EXPLICIT_WEB_REQUEST,
            emptySet(),
        )
    }
}
