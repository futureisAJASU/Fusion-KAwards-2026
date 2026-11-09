package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.ChatPromptComposition
import com.projectnuke.fusion.chat.SearchEvidencePromptCompactor
import com.projectnuke.fusion.chat.SearchStrategy
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEvidenceClipSafetyBoundaryTest {
    @Test
    fun clippedCorrectionCannotAuthorizeTheEarlierNumericValue() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val snippet = "The Bank of Korea policy rate is 3.00%." +
            " Background context about monetary policy and market conditions.".repeat(5) +
            " However, the Bank later corrected the record: the current policy rate is 2.50%, not 3.00%."
        val response = response(
            query = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(
                FusionSearchResult(
                    title = "Bank of Korea policy rate update",
                    source = "bok.or.kr",
                    url = "https://www.bok.or.kr/eng/base-rate",
                    snippet = snippet,
                    publishedAt = LocalDate.now().minusDays(2).toString(),
                    queryUsed = query,
                    rank = 1,
                ),
            ),
        )

        val admitted = route(response).answerEvidence()

        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, admitted.answerSufficiency)
        assertNull(admitted.structuredContext)
        assertTrue(admitted.sources.isEmpty())
    }

    @Test
    fun broadNewsSnippetCompactionCannotDropTheCorrectingClause() {
        val query = "최신 뉴스 알려줘"
        val snippet = "Starship launch scrubbed: flight delayed indefinitely on May 1, 2026. " +
            "Launch details and mission updates continued for the program team. " +
            "However, the launch actually occurred on May 3, 2026."
        val response = response(
            query = query,
            intent = SearchIntent.NEWS,
            results = listOf(
                FusionSearchResult(
                    title = "Starship flight status",
                    source = "example.com",
                    url = "https://example.com/starship",
                    snippet = snippet,
                    publishedAt = LocalDate.now().minusDays(1).toString(),
                    queryUsed = query,
                ),
            ),
        )
        val content = ChatPromptComposition.finalUserContent(
            body = query,
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = response.toAnswerContext(maxResults = 1),
            evidenceStatus = "USABLE",
        )

        val variants = SearchEvidencePromptCompactor.compactedUserContents(
            content,
            evidenceStatusByCount = mapOf(1 to "USABLE"),
        )

        assertTrue(variants.isNotEmpty())
        variants.forEach { variant ->
            val variantSnippet = Regex("\"snippet\":\"(.*?)\"").find(variant)?.groupValues?.getOrNull(1).orEmpty()
            if (variantSnippet.contains("scrubbed")) {
                assertTrue(
                    "compacted snippet dropped the correcting clause: $variantSnippet",
                    variantSnippet.contains("actually occurred"),
                )
            }
        }
    }

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
                providerType = result.providerType,
                providerDisplayName = result.providerDisplayName,
                searchCategory = result.searchCategory,
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
