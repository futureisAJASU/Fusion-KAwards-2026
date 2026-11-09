package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.SearchStrategy
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPhase3StructuredEvidenceTest {
    @Test
    fun blankTitleRecordCannotBypassQualityOrReachTheModel() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val admitted = route(
            response(
                query,
                listOf(
                    result(
                        query = query,
                        title = "   ",
                        url = "https://news.example/blank-title",
                        snippet = "The Bank of Korea policy rate is 3.00%.",
                    ),
                ),
            ),
        ).answerEvidence()

        assertTrue(
            "a blank title must never become answer support",
            admitted.answerSufficiency != SearchAnswerSufficiencyStatus.SUFFICIENT,
        )
        assertTrue(admitted.sources.isEmpty())
        assertTrue(admitted.structuredContext == null)
    }

    @Test
    fun newlineAndOversizedTitleCannotBreakThePromptRecordBoundary() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val admitted = route(
            response(
                query,
                listOf(
                    result(
                        query = query,
                        title = "Bank of Korea rate\nrecord ${"x".repeat(400)}",
                        url = "https://news.example/newline-title",
                        snippet = "The Bank of Korea policy rate is 3.00%.",
                    ),
                ),
            ),
        ).answerEvidence()

        val context = admitted.structuredContext.orEmpty()
        assertEquals(1, Regex("UNTRUSTED_RESULT_\\d+_BEGIN").findAll(context).count())
        assertEquals(1, Regex("UNTRUSTED_RESULT_\\d+_END").findAll(context).count())
        assertTrue(context.contains("3.00%"))
    }

    @Test
    fun invalidPublishedDateIsTreatedAsUndatedWithoutCrashing() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val admitted = route(
            response(
                query,
                listOf(
                    result(
                        query = query,
                        title = "Bank of Korea policy rate",
                        url = "https://www.bok.or.kr/eng/base-rate",
                        snippet = "The Bank of Korea policy rate is 3.00%.",
                        publishedAt = "not-a-date",
                    ),
                ),
            ),
        ).answerEvidence()

        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, admitted.answerSufficiency)
        assertEquals(1, admitted.sources.size)
    }

    @Test
    fun sameUrlWithDifferentSnippetsCannotOccupyTwoModelSlots() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val admitted = route(
            response(
                query,
                listOf(
                    result(
                        query = query,
                        title = "Bank of Korea policy rate",
                        url = "https://www.bok.or.kr/eng/base-rate",
                        snippet = "The Bank of Korea policy rate is 3.00%.",
                    ),
                    result(
                        query = query,
                        title = "Bank of Korea policy rate",
                        url = "https://www.bok.or.kr/eng/base-rate",
                        snippet = "A different excerpt of the same article.",
                    ),
                ),
            ),
        ).answerEvidence()

        assertEquals(1, admitted.sources.size)
        assertEquals(
            1,
            admitted.structuredContext.orEmpty()
                .let { Regex("UNTRUSTED_RESULT_\\d+_BEGIN").findAll(it).count() },
        )
    }

    private fun result(
        query: String,
        title: String,
        url: String,
        snippet: String,
        publishedAt: String? = LocalDate.now().minusDays(1).toString(),
    ) = FusionSearchResult(
        title = title,
        source = if (url.contains("bok.or.kr")) "bok.or.kr" else "news.example",
        url = url,
        snippet = snippet,
        publishedAt = publishedAt,
        queryUsed = query,
        rank = 1,
    )

    private fun response(
        query: String,
        results: List<FusionSearchResult>,
    ) = FusionSearchResponse(
        query = query,
        normalizedQuery = query,
        intent = SearchIntent.CURRENT_INFO,
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
