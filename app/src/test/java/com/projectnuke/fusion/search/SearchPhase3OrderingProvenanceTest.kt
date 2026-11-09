package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.SearchStrategy
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPhase3OrderingProvenanceTest {
    @Test
    fun permutedEquivalentResultsKeepTheSameAdmittedValue() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val decisive = result(
            query = query,
            title = "Bank of Korea Base Rate",
            url = "https://www.bok.or.kr/eng/base-rate",
            snippet = "The Bank of Korea Base Rate is 3.00%.",
            rank = 1,
        )
        val background = result(
            query = query,
            title = "Monetary policy explainer",
            url = "https://news.example/explainer",
            snippet = "Background information about how the base rate is set.",
            rank = 2,
        )
        val forward = route(response(query, listOf(decisive, background))).answerEvidence()
        val reversed = route(response(query, listOf(background, decisive))).answerEvidence()

        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, forward.answerSufficiency)
        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, reversed.answerSufficiency)
        assertEquals(
            forward.sources.map { it.url }.toSet(),
            reversed.sources.map { it.url }.toSet(),
        )
        assertTrue(
            forward.structuredContext.orEmpty().contains("3.00%"),
        )
        assertTrue(
            reversed.structuredContext.orEmpty().contains("3.00%"),
        )
    }

    @Test
    fun sameTitleFromDifferentDomainsKeepsDistinctProvenance() {
        val query = "Bank of Korea monetary policy briefing"
        val first = result(
            query = query,
            title = "Bank of Korea monetary policy briefing",
            url = "https://www.bok.or.kr/eng/mpc/briefing",
            snippet = "The Bank of Korea held its monetary policy briefing on October 22, 2026.",
            source = "bok.or.kr",
            publishedAt = LocalDate.now().minusDays(1).toString(),
            rank = 1,
        )
        val second = result(
            query = query,
            title = "Bank of Korea monetary policy briefing",
            url = "https://news.example/bok-briefing",
            snippet = "The Bank of Korea held its monetary policy briefing on October 22, 2026.",
            source = "news.example",
            publishedAt = LocalDate.now().minusDays(1).toString(),
            rank = 2,
        )

        val admitted = route(
            response(query, listOf(first, second), intent = SearchIntent.NEWS),
        ).answerEvidence()

        assertTrue(
            "admitted=${admitted.sources.map { it.title + "|" + it.source + "|" + it.url }}",
            admitted.answerSufficiency != SearchAnswerSufficiencyStatus.INSUFFICIENT,
        )
        assertEquals("admitted=${admitted.sources.map { it.url }}", 2, admitted.sources.size)
        assertEquals(
            "sources=${admitted.sources.map { it.title + "|" + it.source + "|" + it.url }}",
            2,
            admitted.sources.map { it.url }.distinct().size,
        )
        assertNotEquals(admitted.sources[0].source, admitted.sources[1].source)
    }

    @Test
    fun conflictingFreshOfficialValuesCannotSilentlyPickOne() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val older = result(
            query = query,
            title = "Bank of Korea Base Rate",
            url = "https://www.bok.or.kr/eng/base-rate",
            snippet = "The Bank of Korea Base Rate is 3.00%.",
            source = "bok.or.kr",
            publishedAt = LocalDate.now().minusDays(10).toString(),
            rank = 1,
        )
        val newer = result(
            query = query,
            title = "Bank of Korea Base Rate decision",
            url = "https://www.bok.or.kr/eng/mpc/decision",
            snippet = "The Bank of Korea Base Rate is 2.50%.",
            source = "bok.or.kr",
            publishedAt = LocalDate.now().minusDays(2).toString(),
            rank = 2,
        )

        val admitted = route(response(query, listOf(older, newer))).answerEvidence()

        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, admitted.answerSufficiency)
        assertTrue(admitted.sources.isEmpty())
    }

    @Test
    fun decomposedEntityNameStillMatchesTheSameEntity() {
        val query = "What is the current price of 삼성전자?"
        val composed = "삼성전자"
        val decomposed = java.text.Normalizer.normalize(composed, java.text.Normalizer.Form.NFD)
        val admitted = route(
            response(
                query,
                listOf(
                    result(
                        query = query,
                        title = "$composed price",
                        url = "https://news.example/samsung-price",
                        snippet = "The $composed price is $999.",
                        source = "news.example",
                        publishedAt = LocalDate.now().minusDays(1).toString(),
                        rank = 1,
                    ),
                ),
                snippetEntity = decomposed,
                titleEntity = decomposed,
            ),
        ).answerEvidence()

        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, admitted.answerSufficiency)
    }

    private fun result(
        query: String,
        title: String,
        url: String,
        snippet: String,
        source: String = "news.example",
        publishedAt: String = LocalDate.now().minusDays(1).toString(),
        rank: Int = 1,
    ) = FusionSearchResult(
        title = title,
        source = source,
        url = url,
        snippet = snippet,
        publishedAt = publishedAt,
        queryUsed = query,
        rank = rank,
    )

    private fun response(
        query: String,
        results: List<FusionSearchResult>,
        intent: SearchIntent = SearchIntent.CURRENT_INFO,
        snippetEntity: String? = null,
        titleEntity: String? = null,
    ) = FusionSearchResponse(
        query = query,
        normalizedQuery = query,
        intent = intent,
        results = results.map { result ->
            result.copy(
                title = titleEntity?.let { result.title.replace("삼성전자", it) } ?: result.title,
                snippet = snippetEntity?.let { result.snippet.orEmpty().replace("삼성전자", it) } ?: result.snippet,
            )
        },
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
