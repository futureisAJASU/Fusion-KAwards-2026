package com.projectnuke.fusion.search

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultRelevancePolicyTest {
    private val artemisQuery = "What is the current target date for NASA Artemis II?"
    private val today = LocalDate.of(2026, 11, 3)

    @Test
    fun correctEventEvidenceWinsRegardlessOfInsertionOrder() {
        val direct = result(
            "Artemis II launched",
            "https://nasa.gov/mission/artemis-ii",
            "NASA's Artemis II lunar mission launched on April 1, 2026.",
        )
        val adjacent = result(
            "NASA Mars exploration schedule",
            "https://nasa.gov/mars",
            "NASA studies future Mars exploration and launch schedules for 2028.",
        )

        val forward = SearchResultRelevancePolicy.rank(listOf(adjacent, direct), artemisQuery, SearchIntent.CURRENT_INFO, today)
        val reversed = SearchResultRelevancePolicy.rank(listOf(direct, adjacent), artemisQuery, SearchIntent.CURRENT_INFO, today)

        assertEquals(direct.url, forward.first().url)
        assertEquals(forward.map { it.url }, reversed.map { it.url })
    }

    @Test
    fun exactEventAndDateBeatSeveralAdjacentNASAResults() {
        val adjacent = listOf(
            result("NASA Mars program update", "https://nasa.gov/mars-1", "NASA's Mars mission status and 2028 schedule."),
            result("NASA Artemis architecture", "https://nasa.gov/artemis", "NASA's Artemis IV plan targets a lunar landing in 2028."),
            result("NASA moon exploration news", "https://nasa.gov/moon", "NASA prepares future lunar missions and Mars exploration."),
        )
        val direct = result("Artemis II mission status", "https://space.example/artemis-ii", "Artemis II launched on April 1, 2026 and flew around the Moon.")

        val ranked = SearchResultRelevancePolicy.rank(adjacent + direct, artemisQuery, SearchIntent.CURRENT_INFO, today)

        assertEquals(direct.url, ranked.first().url)
    }

    @Test
    fun officialValueEvidenceStaysAheadOfHighOverlapUnofficialResults() {
        val official = result("Bank of Korea Base Rate", "https://www.bok.or.kr/eng/main", "The Bank of Korea left the Base Rate at 3.00%.")
        val unofficial = listOf(
            result("South Korea official policy rate", "https://markets.example/rate", "The Bank of Korea official policy rate is discussed here."),
            result("Bank of Korea policy rate today", "https://finance.example/bok", "Current Bank of Korea policy rate background and analysis."),
            result("Bank of Korea interest-rate article", "https://news.example/bok", "A detailed Bank of Korea policy rate news story."),
        )
        val query = "What is the current official policy rate of the Bank of Korea?"

        val ranked = SearchResultRelevancePolicy.rank(unofficial + official, query, SearchIntent.CURRENT_INFO, today)

        assertEquals(official.url, ranked.first().url)
        assertTrue(SearchAnswerSufficiencyPolicy.evaluate(query, SearchIntent.CURRENT_INFO, ranked).answerSufficient)
    }

    @Test
    fun freshNumericEvidenceRanksAheadOfStaleMatchingEvidence() {
        val stale = result(
            "Bank of Korea policy rate",
            "https://markets.example/stale-rate",
            "The Bank of Korea policy rate is 3.00%.",
            publishedAt = "2025-01-01",
        )
        val fresh = result(
            "Bank of Korea current policy rate",
            "https://markets.example/current-rate",
            "The Bank of Korea policy rate is 2.75%.",
            publishedAt = "2026-11-02",
        )

        val ranked = SearchResultRelevancePolicy.rank(
            listOf(stale, fresh),
            "What is the current policy rate of the Bank of Korea?",
            SearchIntent.CURRENT_INFO,
            today,
        )

        assertEquals(fresh.url, ranked.first().url)
    }

    private fun result(title: String, url: String, snippet: String, publishedAt: String? = null) = FusionSearchResult(
        title = title,
        source = url.substringAfter("//").substringBefore('/'),
        url = url,
        snippet = snippet,
        publishedAt = publishedAt,
        queryUsed = "subquery",
    )
}
