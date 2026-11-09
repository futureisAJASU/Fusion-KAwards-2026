package com.projectnuke.fusion.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAnswerGroundingCorruptionTest {
    @Test
    fun `organization role phrase is not corrupted by generic news office-title guard`() {
        val answer = "회사 대표가 새 계획을 발표했습니다."
        val evidence = source(
            title = "시장 동향",
            snippet = "오늘의 경제 소식입니다.",
        )

        assertEquals(
            answer,
            SearchAnswerTemporalGroundingPolicy.sanitize(
                answer = answer,
                admittedSources = listOf(evidence),
                requestedQuery = "뉴스 알려줘",
            ),
        )
    }

    @Test
    fun `temporal sanitizer is idempotent and preserves the metrics tail`() {
        val evidence = source(
            title = "Artemis II Splashdown",
            snippet = "Artemis II splashed down on April 10, 2026.",
        )
        val answer = "Artemis II splashed down on April 10, 2026.\n<fusion_metrics>tokens=12</fusion_metrics>"

        val once = SearchAnswerTemporalGroundingPolicy.sanitize(
            answer = answer,
            admittedSources = listOf(evidence),
            requestedQuery = "What date did Artemis II splash down?",
        )
        val twice = SearchAnswerTemporalGroundingPolicy.sanitize(
            answer = once,
            admittedSources = listOf(evidence),
            requestedQuery = "What date did Artemis II splash down?",
        )

        assertEquals(once, twice)
        assertTrue(once.endsWith("\n<fusion_metrics>tokens=12</fusion_metrics>"))
    }

    @Test
    fun `unsupported date removal is idempotent and preserves the metrics tail`() {
        val evidence = source(
            title = "Artemis II Splashdown",
            snippet = "Artemis II splashed down on April 10, 2026.",
        )
        val answer = "The mission ended on April 11, 2026 in Florida.\n<fusion_metrics>tokens=8</fusion_metrics>"

        val once = SearchAnswerTemporalGroundingPolicy.sanitize(
            answer = answer,
            admittedSources = listOf(evidence),
            requestedQuery = "When did the mission end?",
        )
        val twice = SearchAnswerTemporalGroundingPolicy.sanitize(
            answer = once,
            admittedSources = listOf(evidence),
            requestedQuery = "When did the mission end?",
        )

        assertEquals(once, twice)
        assertTrue(once.endsWith("\n<fusion_metrics>tokens=8</fusion_metrics>"))
    }

    @Test
    fun `date-only answer keeps only the evidence-supported date`() {
        val evidence = source(
            title = "Artemis II Splashdown",
            snippet = "Artemis II splashed down at 8:07 p.m. EDT on Friday, April 10, 2026.",
        )

        val sanitized = SearchAnswerTemporalGroundingPolicy.sanitize(
            answer = "Artemis II splashed down on Friday, April 10, 2026.",
            admittedSources = listOf(evidence),
            requestedQuery = "On what date did Artemis II splash down?",
        )

        assertEquals("April 10, 2026.", sanitized)
    }

    private fun source(
        title: String,
        snippet: String,
        sourceDomain: String = "example.com",
    ) = WebSearchSource(
        title = title,
        source = sourceDomain,
        url = "https://$sourceDomain/article",
        snippet = snippet,
        publishedAt = null,
        providerType = WebSearchProviderType.FREE_DEFAULT,
        providerDisplayName = "Free",
        searchCategory = null,
        queryUsed = "test query",
        rank = 1,
    )
}
