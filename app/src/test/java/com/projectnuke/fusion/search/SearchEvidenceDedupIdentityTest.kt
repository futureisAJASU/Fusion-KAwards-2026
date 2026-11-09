package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.SearchStrategy
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEvidenceDedupIdentityTest {
    @Test
    fun trackingParameterDuplicateCannotDisplaceDistinctEvidence() {
        val query = "What is the current price of the Galaxy S26 Ultra?"
        val results = listOf(
            FusionSearchResult(
                title = "Galaxy S26 Ultra price",
                source = "example.com",
                url = "https://example.com/galaxy-s26-ultra",
                snippet = "The Galaxy S26 Ultra price is $999.",
                publishedAt = LocalDate.now().minusDays(1).toString(),
                queryUsed = query,
                rank = 1,
            ),
            FusionSearchResult(
                title = "Galaxy S26 Ultra price",
                source = "example.com",
                url = "https://example.com/galaxy-s26-ultra?utm_source=newsletter&utm_medium=email",
                snippet = "The Galaxy S26 Ultra price is $999.",
                publishedAt = LocalDate.now().minusDays(1).toString(),
                queryUsed = query,
                rank = 2,
            ),
            FusionSearchResult(
                title = "Galaxy S26 price update",
                source = "other.example",
                url = "https://other.example/galaxy-s26",
                snippet = "The Galaxy S26 price was reviewed by another publisher.",
                publishedAt = LocalDate.now().minusDays(1).toString(),
                queryUsed = query,
                rank = 3,
            ),
            FusionSearchResult(
                title = "Galaxy S26 Ultra launch coverage",
                source = "third.example",
                url = "https://third.example/galaxy-s26-ultra-launch",
                snippet = "Coverage of the Galaxy S26 Ultra launch event.",
                publishedAt = LocalDate.now().minusDays(1).toString(),
                queryUsed = query,
                rank = 4,
            ),
            FusionSearchResult(
                title = "Galaxy S26 Ultra review",
                source = "fourth.example",
                url = "https://fourth.example/galaxy-s26-ultra-review",
                snippet = "Independent review of the Galaxy S26 Ultra device.",
                publishedAt = LocalDate.now().minusDays(1).toString(),
                queryUsed = query,
                rank = 5,
            ),
        )
        val response = FusionSearchResponse(
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
                    providerType = result.providerType,
                    providerDisplayName = result.providerDisplayName,
                    searchCategory = result.searchCategory,
                    queryUsed = result.queryUsed,
                    rank = result.rank,
                )
            },
        )

        val admitted = route(response).answerEvidence()

        assertEquals(4, admitted.sources.size)
        assertEquals(
            "the same article must not occupy two model-visible slots",
            4,
            admitted.sources.map { it.url }.distinct().size,
        )
        assertTrue(
            "a tracking-parameter copy must not be admitted as separate provenance",
            admitted.sources.none { it.url?.contains("utm_source") == true },
        )
        assertTrue(
            "distinct evidence must not be displaced by a duplicate copy",
            admitted.sources.any { it.url == "https://fourth.example/galaxy-s26-ultra-review" },
        )
    }

    private fun route(response: FusionSearchResponse) = SearchStrategyRouteResult(
        outcome = SearchRouteOutcome.COMPLETED,
        response = response,
        selectedStrategy = SearchStrategy.AGENT_HARNESS,
        executedStrategy = SearchStrategy.AGENT_HARNESS,
    )
}
