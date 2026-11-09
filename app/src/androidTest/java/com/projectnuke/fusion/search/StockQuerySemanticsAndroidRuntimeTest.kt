package com.projectnuke.fusion.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the production planner on Android's ICU-backed regex runtime.
 * Host java.util.regex tests cannot prove that top-level Kotlin Regex values
 * are accepted by the installed Android runtime.
 */
@RunWith(AndroidJUnit4::class)
class StockQuerySemanticsAndroidRuntimeTest {
    @Test
    fun ordinaryAndStockQueries_initializePlannerWithoutRegexCrash() {
        val ordinaryQueries = listOf(
            "안녕",
            "트랜지스터가 뭐야?",
            "캐시 메모리를 설명해줘",
            "오늘 뭐 공부할까?",
        )
        ordinaryQueries.forEach { query ->
            val semantics = stockQuerySemantics(query)
            assertFalse(query, hasStockIntentSignal(query))
            val plannerIntent = RuleBasedWebSearchPlanner.detectIntentForText(query)
            val facadeIntent = FusionWebSearch.detectIntent(query)
            assertTrue(query, plannerIntent != SearchIntent.STOCK)
            assertTrue(query, facadeIntent != SearchIntent.STOCK)
            RuleBasedWebSearchPlanner.shouldUseWebSearch(query)
            FusionWebSearch.shouldAutoUseWebSearch(query)
            assertEquals(query, StockValueKind.GENERIC_STOCK_INFO, semantics.valueKind)
        }

        val stockQueries = listOf(
            "삼성전자 주가 85000원",
            "오늘 삼성전자 주가 얼마야?",
            "NVIDIA current price",
            "AMD market cap",
            "코스피 현재 지수",
        )
        stockQueries.forEach { query ->
            // These calls intentionally force StockQuerySemanticsKt class
            // initialization through every request-critical public seam.
            stockQuerySemantics(query)
            hasStockIntentSignal(query)
            val plannerIntent = RuleBasedWebSearchPlanner.detectIntentForText(query)
            RuleBasedWebSearchPlanner.shouldUseWebSearch(query)
            val facadeIntent = FusionWebSearch.detectIntent(query)
            FusionWebSearch.shouldAutoUseWebSearch(query)
            assertTrue(query, plannerIntent in SearchIntent.values().toSet())
            assertTrue(query, facadeIntent in SearchIntent.values().toSet())
        }

        assertEquals(SearchIntent.STOCK, RuleBasedWebSearchPlanner.detectIntentForText("삼성전자 주가 85000원"))
        assertEquals(SearchIntent.STOCK, RuleBasedWebSearchPlanner.detectIntentForText("AMD market cap"))
        assertEquals(SearchIntent.STOCK, RuleBasedWebSearchPlanner.detectIntentForText("코스피 현재 지수"))
    }
}
