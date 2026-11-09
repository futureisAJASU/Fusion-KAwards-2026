package com.projectnuke.fusion.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class RuleBasedWebSearchPlannerTest {

    @Test
    fun `official source request preserves the requested federal funds range`() {
        val query = "Use an official Federal Reserve source to tell me the current federal funds target range"
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = query),
        )

        assertEquals(SearchIntent.CURRENT_INFO, plan.intent)
        assertTrue(plan.primaryQuery.contains("target", ignoreCase = true))
        assertTrue(plan.primaryQuery.contains("range", ignoreCase = true))
        assertFalse(plan.primaryQuery.contains("추가 자료"))
        assertFalse(plan.modifiers.contains("more_sources"))
    }

    @Test
    fun `explicit request for more sources still adds the modifier`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "Find more sources about the current federal funds target range"),
        )

        assertTrue(plan.modifiers.contains("more_sources"))
    }

    @Test
    fun `stock query does not append generic latest`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "오늘 삼성전자 주가 얼마였어?")
        )
        assertEquals(SearchIntent.STOCK, plan.intent)
        assertFalse(plan.primaryQuery.contains("최신"))
        assertTrue(plan.primaryQuery.contains("삼성전자"))
        assertTrue(plan.primaryQuery.contains("주가"))
    }

    @Test
    fun `today stock query preserves current today semantics`() {
        val semantics = stockQuerySemantics("오늘 삼성전자 주가 얼마였어?")
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "오늘 삼성전자 주가 얼마였어?")
        )
        assertEquals(StockValueKind.CURRENT_PRICE, semantics.valueKind)
        assertEquals(StockTemporalKind.TODAY, semantics.temporalKind)
        assertFalse(plan.primaryQuery.contains("종가"))
        assertTrue(plan.primaryQuery.contains("오늘") || plan.primaryQuery.contains("현재가"))
    }

    @Test
    fun `stock query preserves market cap semantics`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "삼성전자 시가총액 얼마야?")
        )
        assertEquals(SearchIntent.STOCK, plan.intent)
        assertTrue(plan.primaryQuery.contains("시가총액") || plan.primaryQuery.contains("시총"))
    }

    @Test
    fun `stock query preserves ticker and close`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "005930 오늘 종가")
        )
        assertTrue(plan.primaryQuery.contains("005930") || plan.primaryQuery.contains("종가"))
    }

    @Test
    fun `stock query preserves English instrument and price`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "NVIDIA stock price now")
        )
        assertTrue(plan.primaryQuery.lowercase().contains("nvidia"))
        assertTrue(plan.primaryQuery.contains("price") || plan.primaryQuery.contains("현재가"))
    }

    @Test
    fun `bounded English finance patterns classify as stock`() {
        listOf(
            "NVIDIA stock price now",
            "NVDA stock price",
            "AMD share price",
            "Apple market cap",
            "NASDAQ close",
        ).forEach { query ->
            assertEquals(query, SearchIntent.STOCK, RuleBasedWebSearchPlanner.plan(RuleBasedWebSearchPlannerInput(query)).intent)
            assertEquals(query, SearchIntent.STOCK, FusionWebSearch.detectIntent(query))
        }
    }

    @Test
    fun `generic product price is not stock intent`() {
        assertEquals(
            SearchIntent.GENERAL,
            RuleBasedWebSearchPlanner.plan(RuleBasedWebSearchPlannerInput("삼성전자 신제품 가격")).intent,
        )
        assertEquals(SearchIntent.GENERAL, FusionWebSearch.detectIntent("삼성전자 신제품 가격"))
        listOf("RTX 5090 price", "OLED TV price", "HBM price").forEach { query ->
            assertEquals(query, SearchIntent.GENERAL, FusionWebSearch.detectIntent(query))
        }
    }

    @Test
    fun `stock alternates preserve finance semantics and never use news freshness`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "오늘 삼성전자 주가 얼마였어?"),
        )
        assertTrue(plan.alternateQueries.isNotEmpty())
        plan.alternateQueries.forEach { alternate ->
            assertTrue(alternate.contains("삼성전자"))
            assertTrue(alternate.contains("종가") || alternate.contains("현재가") || alternate.contains("주가"))
            assertFalse(alternate.contains("최신 뉴스"))
            assertFalse(alternate.contains("latest news", ignoreCase = true))
        }
    }

    @Test
    fun `unknown ticker is never fabricated in stock alternates`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "오늘 이름없는회사 주가 얼마야?"),
        )
        assertEquals(SearchIntent.STOCK, plan.intent)
        assertTrue(plan.alternateQueries.none { it.matches(Regex(".*\\b\\d{4,6}\\b.*")) })
    }

    @Test
    fun `news query remains news even with stock words in alternate`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "삼성전자 주가 최신 뉴스")
        )
        assertEquals(SearchIntent.NEWS, plan.intent)
    }

    @Test
    fun `English broad and topic news queries retain news intent`() {
        val broad = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "What are the top world news stories today"),
        )
        val topic = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "Samsung Electronics latest news"),
        )

        assertEquals(SearchIntent.NEWS, broad.intent)
        assertTrue(FusionWebSearch.isBroadNewsQueryForTest(broad.primaryQuery))
        assertEquals(SearchIntent.NEWS, topic.intent)
        assertFalse(FusionWebSearch.isBroadNewsQueryForTest(topic.primaryQuery))
    }

    @Test
    fun `weather query preserved`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "서울 오늘 날씨")
        )
        assertEquals(SearchIntent.WEATHER, plan.intent)
    }

    @Test
    fun `current info query preserved`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "오늘 코스피 현황")
        )
        assertEquals(SearchIntent.STOCK, plan.intent)
    }

    @Test
    fun `Korean index targets are extracted independently of descriptors`() {
        listOf(
            "오늘 코스피 현황" to "코스피",
            "오늘 코스닥 지수" to "코스닥",
            "나스닥 현재 지수" to "나스닥",
            "KOSPI close" to "kospi",
            "NASDAQ close" to "nasdaq",
        ).forEach { (query, target) ->
            val semantics = stockQuerySemantics(query)
            assertEquals(query, StockValueKind.INDEX_LEVEL, semantics.valueKind)
            assertTrue("$query -> $target", semantics.instrumentTokens.contains(target))
            assertFalse(semantics.instrumentTokens.contains("현황"))
            assertFalse(semantics.instrumentTokens.contains("지수"))
        }
    }

    @Test
    fun `ticker extraction rejects years and arbitrary numeric prices`() {
        assertFalse(stockQuerySemantics("2026 삼성전자 주가").tickerTokens.contains("2026"))
        assertTrue(stockQuerySemantics("005930 현재가").tickerTokens.contains("005930"))
    }

    @Test
    fun `stock quote query does not become latest`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "삼성전자 현재가")
        )
        assertFalse(plan.primaryQuery.contains("최신"))
        assertTrue(plan.primaryQuery.contains("삼성전자"))
    }

    @Test
    fun `currency quote forms preserve stock value evidence`() {
        listOf(
            "삼성전자 주가 85,000원",
            "NVIDIA price $123.45",
            "market cap USD 100 billion",
            "시총 500조",
            "AMD current price 210 USD",
        ).forEach { query ->
            val semantics = stockQuerySemantics(query)
            assertTrue(query, hasStockValueEvidence(query, semantics.valueKind))
        }
        assertFalse(hasStockValueEvidence("\\$123abc", StockValueKind.CURRENT_PRICE))
    }

    @Test
    fun `planner failure fails closed without external search`() = runTest {
        FusionWebSearch.plannerFailureForTests = ExceptionInInitializerError("test planner failure")
        try {
            assertFalse(FusionWebSearch.shouldAutoUseWebSearch("삼성전자 주가"))
            assertEquals(SearchIntent.GENERAL, FusionWebSearch.detectIntent("삼성전자 주가"))
            val response = FusionWebSearch.search("삼성전자 주가", previousUserMessage = null)
            assertTrue(response.results.isEmpty())
            assertEquals(SearchIntent.GENERAL, response.intent)
        } finally {
            FusionWebSearch.plannerFailureForTests = null
        }
    }

    @Test
    fun `all public planner facade helpers fail closed when planner is unavailable`() {
        FusionWebSearch.plannerFailureForTests = ExceptionInInitializerError("test planner failure")
        try {
            assertEquals("ordinary prompt", FusionWebSearch.rewriteQuery("ordinary prompt", "private context"))
            val plan = FusionWebSearch.buildSearchPlan("ordinary prompt", "private context")
            assertEquals(SearchIntent.GENERAL, plan.intent)
            assertEquals("ordinary prompt", plan.primaryQuery)
            assertTrue(plan.alternateQueries.isEmpty())
        } finally {
            FusionWebSearch.plannerFailureForTests = null
        }
    }
}
