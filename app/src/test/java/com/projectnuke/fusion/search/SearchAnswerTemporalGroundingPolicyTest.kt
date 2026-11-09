package com.projectnuke.fusion.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAnswerTemporalGroundingPolicyTest {
    @Test
    fun `generic broad news drops an office title not stated for a person in admitted evidence`() {
        val evidence = source(
            title = "‘윤석열 손바닥 王자’ 소환한 재판부",
            snippet = "재판부가 선거법 사건 항소심에서 판단을 내렸다.",
            sourceDomain = "hani.co.kr",
        )
        val answer = "현재 검색된 최신 뉴스 중에서는 윤석열 대통령과 관련된 재판부 상황이 있습니다."

        assertEquals(
            "현재 검색된 최신 뉴스 중에서는 윤석열과 관련된 재판부 상황이 있습니다.",
            SearchAnswerTemporalGroundingPolicy.sanitize(
                answer = answer,
                admittedSources = listOf(evidence),
                requestedQuery = "뉴스 알려줘",
            ),
        )
    }

    @Test
    fun `generic broad news retains an office title stated in admitted evidence`() {
        val evidence = source(
            title = "한덕수 국무총리 기자회견",
            snippet = "한덕수 국무총리가 오늘 기자회견을 했다.",
            sourceDomain = "example.com",
        )
        val answer = "한덕수 국무총리가 오늘 기자회견을 했습니다."

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
    fun `named topic news does not apply the generic office title guard`() {
        val evidence = source(
            title = "‘윤석열 손바닥 王자’ 소환한 재판부",
            snippet = "재판부가 선거법 사건 항소심에서 판단을 내렸다.",
            sourceDomain = "hani.co.kr",
        )
        val answer = "현재 검색된 최신 뉴스 중에서는 윤석열 대통령과 관련된 재판부 상황이 있습니다."

        assertEquals(
            answer,
            SearchAnswerTemporalGroundingPolicy.sanitize(
                answer = answer,
                admittedSources = listOf(evidence),
                requestedQuery = "윤석열 뉴스 알려줘",
            ),
        )
    }

    @Test
    fun `date only answer omits unrequested time when evidence includes it`() {
        val evidence = source(
            "Artemis II Splashdown and Recovery - NASA. " +
                "Artemis II splashed down at 8:07 p.m. EDT on April 10, 2026.",
        )
        val answer = "NASA에 따르면, Artemis II는 2026년 4월 10일 금요일 늦은 오후 8시 7분에 지구상에서 착수했습니다."

        val sanitized = SearchAnswerTemporalGroundingPolicy.sanitize(
            answer = answer,
            admittedSources = listOf(evidence),
            requestedQuery = "According to NASA what date did Artemis II splash down",
        )

        assertEquals(
            "2026년 4월 10일.",
            sanitized,
        )
        assertTrue(!sanitized.contains("오후"))
        assertTrue(!sanitized.contains("8시 7분"))
        assertTrue(!sanitized.contains("금요일"))
    }

    @Test
    fun `date and time request preserves the evidence time-zone label`() {
        val evidence = source("Artemis II splashed down at 8:07 p.m. EDT on April 10, 2026.")
        val answer = "Artemis II splashed down at 8:07 p.m. EDT on April 10, 2026."

        assertEquals(
            answer,
            SearchAnswerTemporalGroundingPolicy.sanitize(
                answer = answer,
                admittedSources = listOf(evidence),
                requestedQuery = "According to NASA what date and time did Artemis II splash down",
            ),
        )
    }

    @Test
    fun `weekday and time do not support an inferred Korean calendar date`() {
        val source = source(
            "Europa Clipper has embarked on its long voyage to Jupiter. " +
                "The spacecraft launched at 12:06 p.m. EDT Monday aboard a SpaceX Falcon Heavy rocket.",
        )
        val answer = "NASA의 유로파 클리퍼는 목성으로의 장거리 항해를 시작하여 유로파를 탐사할 예정이며, " +
            "우주선은 SpaceX의 Falcon Heavy 로켓을 타고 1월 12일 정오에 발사되었습니다."

        val sanitized = SearchAnswerTemporalGroundingPolicy.sanitize(answer, listOf(source))

        assertEquals(
            "NASA의 유로파 클리퍼는 목성으로의 장거리 항해를 시작하여 유로파를 탐사할 예정입니다.",
            sanitized,
        )
        assertTrue(!sanitized.contains("1월 12일"))
    }

    @Test
    fun `explicitly supported Korean and English dates remain unchanged`() {
        val korean = source("NASA confirms splashdown on April 10, 2026 at 8:07 p.m. EDT.")
        val english = source("The policy was announced on 22 October 2026.")

        assertEquals(
            "Artemis II splashed down on 2026년 4월 10일.",
            SearchAnswerTemporalGroundingPolicy.sanitize(
                "Artemis II splashed down on 2026년 4월 10일.",
                listOf(korean),
            ),
        )
        assertEquals(
            "The decision was announced on October 22, 2026.",
            SearchAnswerTemporalGroundingPolicy.sanitize(
                "The decision was announced on October 22, 2026.",
                listOf(english),
            ),
        )
    }

    @Test
    fun `unsupported date with no other supported clause becomes bounded uncertainty`() {
        val sanitized = SearchAnswerTemporalGroundingPolicy.sanitize(
            answer = "The mission launched on January 12.",
            admittedSources = listOf(source("The spacecraft launched at noon on Monday.")),
        )

        assertEquals(
            "The admitted search evidence does not state the specific date in that answer, so I can't confirm it.",
            sanitized,
        )
    }

    @Test
    fun `unsupported date does not erase a supported requested BOK rate`() {
        val sanitized = SearchAnswerTemporalGroundingPolicy.sanitize(
            answer = "한국은행 기준금리는 2026년 11월 5일 기준 3.00%입니다.",
            admittedSources = listOf(
                source(
                    snippet = "The Monetary Policy Board of the Bank of Korea left the Base Rate unchanged at 3.00%.",
                    sourceDomain = "bok.or.kr",
                    title = "Bank of Korea - 한국은행",
                ),
            ),
            requestedQuery = "What is the current Bank of Korea base rate?",
        )

        assertEquals("한국은행 기준금리는 3.00%입니다.", sanitized)
    }

    @Test
    fun `simple BOK rate answer drops unsupported committee naming`() {
        val evidence = source(
            snippet = "The Monetary Policy Board of the Bank of Korea left the Base Rate unchanged at 3.00%.",
            sourceDomain = "bok.or.kr",
            title = "Bank of Korea - 한국은행",
        )
        val sanitized = SearchAnswerTemporalGroundingPolicy.sanitize(
            answer = "현재 한국은행 기준금리는 3.00%로 유지됩니다. 이는 한국은행의 정책기금위원회에서 결정했습니다.",
            admittedSources = listOf(evidence),
            requestedQuery = "What is the current Bank of Korea base rate?",
        )

        assertEquals("한국은행 기준금리는 3.00%입니다.", sanitized)
    }

    @Test
    fun `compound BOK rate request keeps the model response for its second fact`() {
        val evidence = source(
            snippet = "The Monetary Policy Board of the Bank of Korea left the Base Rate unchanged at 3.00%.",
            sourceDomain = "bok.or.kr",
            title = "Bank of Korea - 한국은행",
        )
        val answer = "The Bank of Korea base rate remains unchanged at 3.00%."

        assertEquals(
            answer,
            SearchAnswerTemporalGroundingPolicy.sanitize(
                answer = answer,
                admittedSources = listOf(evidence),
                requestedQuery = "What is the current Bank of Korea base rate and did it change?",
            ),
        )
    }

    @Test
    fun `compound BOK current rate request retains official rate when unsupported date is removed`() {
        val evidence = source(
            snippet = "The Monetary Policy Board of the Bank of Korea decided to leave the Base Rate unchanged at 3.00%.",
            sourceDomain = "bok.or.kr",
            title = "Bank of Korea Monetary Policy Decision",
        )

        assertEquals(
            "한국은행 기준금리는 3.00%이며, 동결됐습니다.",
            SearchAnswerTemporalGroundingPolicy.sanitize(
                answer = "한국은행 기준금리는 2026년 11월 5일 기준 3.00%입니다.",
                admittedSources = listOf(evidence),
                requestedQuery = "What is the current Bank of Korea base rate and did it change?",
            ),
        )
    }

    @Test
    fun `compound BOK rate fallback does not infer an unchanged status`() {
        val evidence = source(
            snippet = "The Bank of Korea base rate is 3.00%.",
            sourceDomain = "bok.or.kr",
            title = "Bank of Korea base rate",
        )

        assertEquals(
            "검색 근거에서 답변의 구체적인 날짜를 확인할 수 없어 해당 날짜 주장을 제외했습니다.",
            SearchAnswerTemporalGroundingPolicy.sanitize(
                answer = "한국은행 기준금리는 2026년 11월 5일 기준 3.00%입니다.",
                admittedSources = listOf(evidence),
                requestedQuery = "What is the current Bank of Korea base rate and did it change?",
            ),
        )
    }

    private fun source(
        snippet: String,
        sourceDomain: String = "nasa.gov",
        title: String = "NASA mission update",
    ) = WebSearchSource(
        title = title,
        source = sourceDomain,
        url = "https://$sourceDomain/mission/update",
        snippet = snippet,
        publishedAt = null,
        providerType = WebSearchProviderType.FREE_DEFAULT,
        providerDisplayName = "Free",
        searchCategory = null,
        queryUsed = "current mission status",
        rank = 1,
    )
}
