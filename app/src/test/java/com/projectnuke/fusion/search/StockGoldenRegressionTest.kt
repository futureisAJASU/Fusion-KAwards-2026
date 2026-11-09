package com.projectnuke.fusion.search

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden regression for the user's actual STOCK failure.
 * Uses representative fixture data resembling the observed failure
 * (e.g., unrelated company news without quote evidence) without
 * hitting the external network.
 */
class StockGoldenRegressionTest {

    private class FixedHttpURLConnection(
        url: URL,
        private val status: Int,
        private val payload: ByteArray,
    ) : HttpURLConnection(url) {
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        override fun getResponseCode(): Int = status
        override fun getInputStream() = ByteArrayInputStream(payload)
        override fun getErrorStream() = if (status >= 400) ByteArrayInputStream(payload) else null
        override fun getContentType(): String = "application/xml; charset=utf-8"
    }

    private fun fakeClient(requested: MutableList<String>, status: Int, body: ByteArray) = BoundedHttpClient(
        SearchConnectionFactory { url ->
            requested += url.toString()
            FixedHttpURLConnection(url, status, body)
        },
    )

    private fun result(title: String, snippet: String, publishedAt: String? = "2026-09-09") = FusionSearchResult(
        title = title,
        source = "fixture",
        url = "https://example.com/fixture",
        snippet = snippet,
        publishedAt = publishedAt,
    )

    @Test
    fun `samsung stock quote query detects stock intent`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "오늘 삼성전자 주가 얼마였어?")
        )
        assertEquals(SearchIntent.STOCK, plan.intent)
    }

    @Test
    fun `normalized query does not contain conversational filler`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "오늘 삼성전자 주가 얼마였어?")
        )
        assertFalse(plan.primaryQuery.contains("얼마였어"))
        assertTrue(plan.primaryQuery.contains("삼성전자"))
        assertTrue(plan.primaryQuery.contains("주가"))
    }

    @Test
    fun `normalized query does not blindly become question plus latest`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "오늘 삼성전자 주가 얼마였어?")
        )
        assertFalse(plan.primaryQuery.contains("최신"))
    }

    @Test
    fun `stock free default fallback trace never attempts google news`() = runBlocking {
        val requested = mutableListOf<String>()
        FusionWebSearch.httpClientForTests = fakeClient(requested, 500, ByteArray(0))
        try {
            val response = FusionWebSearch.search("오늘 삼성전자 주가 얼마였어?", null)
            assertEquals(SearchIntent.STOCK, response.intent)
            assertTrue(response.traces.isNotEmpty())
            assertEquals("DuckDuckGo HTML", response.traces.first().debugLabel)
            assertTrue(response.traces.map { it.debugLabel }.indexOf("DuckDuckGo Instant") > 0)
            assertFalse(response.traces.any { it.debugLabel == "Google News RSS" })
            assertFalse(requested.any { it.contains("news.google.com", ignoreCase = true) })
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
    }

    @Test
    fun `news free default still attempts google news`() = runBlocking {
        val requested = mutableListOf<String>()
        val rss = "<rss version=\"2.0\"><channel><item><title>오늘 뉴스</title><link>https://example.com/news</link><source>Fixture</source><description>summary</description></item></channel></rss>".toByteArray()
        FusionWebSearch.httpClientForTests = fakeClient(requested, 200, rss)
        try {
            val response = FusionWebSearch.search("오늘 주요 뉴스", null)
            assertEquals(SearchIntent.NEWS, response.intent)
            assertEquals("Google News RSS", response.traces.first().debugLabel)
            assertTrue(response.traces.any { it.debugLabel == "Google News RSS" })
            assertTrue(requested.any { it.contains("news.google.com", ignoreCase = true) })
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
    }

    @Test
    fun `news-only results are semantically insufficient for stock value`() {
        val newsOnlyResults = listOf(
            FusionSearchResult(
                title = "외국인이 삼성전자 주식을 많이 사 모으더니...",
                source = "Some News",
                url = "https://example.com/news/123",
                snippet = "삼성전자 관련 투자 동향 분석 기사",
                publishedAt = "2026-09-09",
                providerType = WebSearchProviderType.FREE_DEFAULT,
                providerDisplayName = "Google News",
            ),
            FusionSearchResult(
                title = "삼성전자 신제품 발표...",
                source = "Tech Blog",
                url = "https://example.com/blog/456",
                snippet = "삼성전자의 새로운 스마트폰 라인업 소개",
                publishedAt = null,
                providerType = WebSearchProviderType.FREE_DEFAULT,
                providerDisplayName = "Google News",
            )
        )
        assertFalse(isStockEvidenceSufficient(newsOnlyResults, "오늘 삼성전자 주가 얼마였어?"))
    }

    @Test
    fun `wrong company quote never satisfies requested Samsung target`() {
        val samsungQuery = "오늘 삼성전자 주가 얼마였어?"
        listOf(
            result("LG전자 주가 95,000원", "LG전자 오늘 종가 95,000원"),
            result("SK하이닉스 주가 210,000원", "SK하이닉스 현재가 210,000원"),
        ).forEach { wrongCompany ->
            assertFalse(isStockEvidenceSufficient(listOf(wrongCompany), samsungQuery))
        }
        assertFalse(isStockEvidenceSufficient(listOf(result("삼성전자 주가 전망", "삼성전자 투자자 관심 증가")), samsungQuery))
        assertFalse(isStockEvidenceSufficient(listOf(result("현대차 주가 250,000원", "현대자동차 현재가 250,000원")), samsungQuery))
        assertFalse(isStockEvidenceSufficient(listOf(result("AMD share price \$142.50", "AMD current price \$142.50")), "NVIDIA stock price now"))
    }

    @Test
    fun `Samsung affiliates do not bind to Samsung Electronics`() {
        val semantics = stockQuerySemantics("삼성전자 주가 현재가")
        assertFalse(stockResultBindsToTarget(result("삼성SDI 주가 300,000원", "삼성SDI 현재가 300,000원"), semantics))
        assertFalse(stockResultBindsToTarget(result("삼성생명 주가 100,000원", "삼성생명 현재가 100,000원"), semantics))
        assertFalse(stockResultBindsToTarget(result("삼성화재 주가 400,000원", "삼성화재 현재가 400,000원"), semantics))
        assertTrue(stockResultBindsToTarget(result("005930 현재가 73,000원", "005930 종가 73,000원"), semantics))
        assertTrue(stockQuerySemantics("삼성 주가").targetIsAmbiguous)
        assertFalse(stockResultBindsToTarget(result("삼성전자 주가 73,000원", "삼성전자 현재가 73,000원"), stockQuerySemantics("삼성 주가")))
    }

    @Test
    fun `outlook without numeric value is insufficient even when stock words match`() {
        val queries = listOf("삼성전자 주가 전망", "삼성전자 주가 상승 가능성", "삼성전자 주가 관련 투자자 관심 증가")
        queries.forEach { query ->
            assertFalse(isStockEvidenceSufficient(listOf(result("삼성전자 주가", query)), query))
        }
    }

    @Test
    fun `quote-grade result is sufficient for stock value`() {
        // Deterministic evaluation date: fixtures are dated 2026-09-09 and the
        // TODAY gate compares provider dates against the evaluation date, so an
        // implicit system clock past midnight would turn this green fixture stale.
        // Production authority is unchanged; only the clock is pinned.
        val evaluationDate = LocalDate.of(2026, 9, 9)
        val quoteResults = listOf(
            FusionSearchResult(
                title = "삼성전자 주가 73,000원 (오늘 종가)",
                source = "Finance Portal",
                url = "https://example.com/quote/005930",
                snippet = "삼성전자(005930) 현재가 73,000원 · 오늘 종가 73,200원",
                publishedAt = "2026-09-09",
                providerType = WebSearchProviderType.FREE_DEFAULT,
                providerDisplayName = "DuckDuckGo",
            )
        )
        assertTrue(isStockEvidenceSufficient(quoteResults, "오늘 삼성전자 주가 얼마였어?", evaluationDate))
        assertTrue(isStockEvidenceSufficient(listOf(result("005930 현재가 73,000원", "005930 종가 73,000원")), "오늘 삼성전자 주가 얼마였어?", evaluationDate))
    }

    @Test
    fun `current stock evidence rejects stale dates and accepts a recent quote`() {
        val query = "오늘 삼성전자 주가 얼마였어?"
        val evaluationDate = LocalDate.of(2026, 9, 9)
        assertFalse(
            isStockEvidenceSufficient(
                listOf(result("삼성전자 주가 73,000원", "삼성전자 현재가 73,000원", "2026-06-01")),
                query,
                evaluationDate,
            )
        )
        assertFalse(
            isStockEvidenceSufficient(
                listOf(result("2026-06-01 삼성전자 주가 73,000원", "삼성전자 현재가 73,000원", "2026-09-09")),
                query,
                evaluationDate,
            )
        )
        assertTrue(
            isStockEvidenceSufficient(
                listOf(result("삼성전자 현재가 73,000원", "삼성전자 현재가 73,000원", "2026-09-09")),
                query,
                evaluationDate,
            )
        )
    }

    @Test
    fun `quote value validation rejects years percentages ranges and counts`() {
        val query = "삼성전자 주가"
        listOf(
            "삼성전자 주가 2026 전망",
            "삼성전자 주가 10% 상승 전망",
            "삼성전자 주가 52주 최고",
            "삼성전자 주가 목표가 보고서 3건",
        ).forEach { text ->
            assertFalse(text, hasStockValueEvidence(text, StockValueKind.CURRENT_PRICE))
        }
        assertTrue(hasStockValueEvidence("삼성전자 현재가 73,000원", StockValueKind.CURRENT_PRICE))
        assertTrue(hasStockValueEvidence("005930 종가 73,000", StockValueKind.CLOSE))
        assertTrue(hasStockValueEvidence("NVIDIA \$142.50", StockValueKind.CURRENT_PRICE))
        assertTrue(hasStockValueEvidence("NASDAQ 18,400.32", StockValueKind.INDEX_LEVEL))
    }

    @Test
    fun `generic target binding supports Hyundai and NVIDIA aliases without query side proof`() {
        // Pinned clock for the same midnight-rollover reason as above.
        val evaluationDate = LocalDate.of(2026, 9, 9)
        assertTrue(isStockEvidenceSufficient(listOf(result("Hyundai Motor 250,000 KRW", "Hyundai Motor current price 250,000 KRW")), "Hyundai Motor stock price now", evaluationDate))
        assertTrue(isStockEvidenceSufficient(listOf(result("NVIDIA \$142.50", "NVIDIA \$142.50")), "NVIDIA stock price now", evaluationDate))
        assertTrue(isStockEvidenceSufficient(listOf(result("NVDA \$142.50", "NVDA \$142.50")), "NVIDIA stock price now", evaluationDate))
    }

    @Test
    fun `unseen exact target binds without an alias table`() {
        val query = "Acme Robotics stock price"
        val semantics = stockQuerySemantics(query)
        assertTrue(stockResultBindsToTarget(result("Acme Robotics current price \$42.10", "Acme Robotics current price \$42.10"), semantics))
        assertFalse(stockResultBindsToTarget(result("Beta Machines current price \$42.10", "Beta Machines current price \$42.10"), semantics))
        assertTrue(isStockEvidenceSufficient(listOf(result("Acme Robotics current price \$42.10", "Acme Robotics current price \$42.10")), query))
    }

    @Test
    fun `value evidence respects market cap and index semantics`() {
        assertTrue(isStockEvidenceSufficient(listOf(result("NASDAQ Composite 18,400.32", "NASDAQ Composite 18,400.32")), "NASDAQ close"))
        assertTrue(isStockEvidenceSufficient(listOf(result("Apple market cap \$3.0T", "Apple market cap \$3.0T")), "Apple market cap"))
        assertFalse(isStockEvidenceSufficient(listOf(result("Apple share price \$200", "Apple share price \$200")), "Apple market cap"))
    }

    @Test
    fun `Korean index target binds and structured context preserves temporal semantics`() {
        val query = "오늘 코스피 현황"
        // Pinned clock: the index fixture is dated 2026-09-09.
        val evaluationDate = LocalDate.of(2026, 9, 9)
        val semantics = stockQuerySemantics(query)
        val indexResult = result("KOSPI 2,600.15", "KOSPI current index level 2,600.15")
        assertEquals(StockValueKind.INDEX_LEVEL, semantics.valueKind)
        assertTrue(semantics.instrumentTokens.contains("코스피"))
        assertTrue(stockResultBindsToTarget(indexResult, semantics))
        assertTrue(isStockEvidenceSufficient(listOf(indexResult), query, evaluationDate))
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = "코스피 오늘 지수",
            intent = SearchIntent.STOCK,
            results = listOf(indexResult),
            debugMessage = null,
        )
        assertTrue(response.toStructuredContext().contains("Stock temporal kind: TODAY"))
        assertTrue(response.toStructuredContext().contains("Stock value kind: INDEX_LEVEL"))
    }

    @Test
    fun `semantic success requires quote-grade evidence for stock`() {
        val response = FusionSearchResponse(
            query = "오늘 삼성전자 주가 얼마였어?",
            normalizedQuery = "삼성전자 주가 오늘 종가",
            intent = SearchIntent.STOCK,
            results = listOf(
                FusionSearchResult(
                    title = "외국인이 삼성전자 주식을 많이 사 모으더니...",
                    source = "News",
                    url = "https://example.com/news/123",
                    snippet = "투자 동향",
                    publishedAt = null,
                    providerType = WebSearchProviderType.FREE_DEFAULT,
                    providerDisplayName = "Google News",
                )
            ),
            debugMessage = null,
            quality = WebSearchQuality(isUsable = true, score = 0.5, reasons = listOf("count=1"), shouldTryFallback = false)
        )
        assertFalse(response.hasSemanticSuccess())
    }

    @Test
    fun `semantic success preserved for news query`() {
        val response = FusionSearchResponse(
            query = "오늘 주요 뉴스",
            normalizedQuery = "오늘 주요 뉴스",
            intent = SearchIntent.NEWS,
            results = listOf(
                FusionSearchResult(
                    title = "주요 뉴스 요약",
                    source = "News Source",
                    url = "https://example.com/news",
                    snippet = "오늘의 주요 이슈 요약",
                    publishedAt = "2026-09-08",
                    providerType = WebSearchProviderType.FREE_DEFAULT,
                    providerDisplayName = "Google News",
                )
            ),
            debugMessage = null,
            quality = WebSearchQuality(isUsable = true, score = 0.5, reasons = emptyList(), shouldTryFallback = false)
        )
        assertTrue(response.hasSemanticSuccess())
    }
}
