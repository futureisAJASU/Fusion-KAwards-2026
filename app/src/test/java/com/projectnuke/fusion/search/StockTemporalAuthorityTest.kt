package com.projectnuke.fusion.search

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockTemporalAuthorityTest {
    private val evaluationDate = LocalDate.of(2026, 9, 9)

    private fun result(title: String, snippet: String, publishedAt: String? = "2026-09-09") = FusionSearchResult(
        title = title,
        source = "fixture",
        url = "https://example.com/fixture",
        snippet = snippet,
        publishedAt = publishedAt,
    )

    @Test
    fun `local date authority uses supplied zone rather than UTC`() {
        val instant = Instant.parse("2026-09-09T16:00:00Z")
        val fixedClock = Clock.fixed(instant, ZoneOffset.UTC)

        assertEquals(LocalDate.of(2026, 9, 10), stockEvaluationDate(fixedClock, ZoneId.of("Asia/Seoul")))
        assertEquals(LocalDate.of(2026, 9, 9), stockEvaluationDate(fixedClock, ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun `today and current require the users exact local date`() {
        val query = "오늘 삼성전자 주가"
        assertEquals(StockTemporalKind.TODAY, stockQuerySemantics(query).temporalKind)
        assertTrue(isStockEvidenceSufficient(listOf(result("삼성전자 현재가 73,000원", "삼성전자 현재가 73,000원", "2026-09-09")), query, evaluationDate))
        assertFalse(isStockEvidenceSufficient(listOf(result("삼성전자 현재가 73,000원", "삼성전자 현재가 73,000원", "2026-09-08")), query, evaluationDate))
        assertFalse(isStockEvidenceSufficient(listOf(result("삼성전자 현재가 73,000원", "삼성전자 현재가 73,000원", "2026-06-01")), query, evaluationDate))
        assertFalse(isStockEvidenceSufficient(listOf(result("삼성전자 현재가 73,000원", "삼성전자 현재가 73,000원", "2026-09-10")), query, evaluationDate))
        assertTrue(isStockEvidenceSufficient(listOf(result("삼성전자 현재가 73,000원", "삼성전자 현재가 73,000원", null)), query, evaluationDate))
    }

    @Test
    fun `previous session requires the bounded previous calendar day`() {
        val query = "어제 삼성전자 주가"
        assertEquals(StockTemporalKind.PREVIOUS_SESSION, stockQuerySemantics(query).temporalKind)
        assertTrue(isStockEvidenceSufficient(listOf(result("삼성전자 현재가 73,000원", "삼성전자 현재가 73,000원", "2026-09-08")), query, evaluationDate))
        assertFalse(isStockEvidenceSufficient(listOf(result("삼성전자 현재가 73,000원", "삼성전자 현재가 73,000원", "2026-09-09")), query, evaluationDate))
        assertFalse(isStockEvidenceSufficient(listOf(result("삼성전자 현재가 73,000원", "삼성전자 현재가 73,000원", "2026-06-01")), query, evaluationDate))
        assertFalse(isStockEvidenceSufficient(listOf(result("삼성전자 현재가 73,000원", "삼성전자 현재가 73,000원", null)), query, evaluationDate))
    }

    @Test
    fun `previous day planning never substitutes current price`() {
        val plan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "어제 삼성전자 주가"),
        )
        assertTrue(plan.primaryQuery.contains("어제"))
        assertFalse(plan.primaryQuery.contains("현재가"))
        assertTrue(plan.alternateQueries.isNotEmpty())
        plan.alternateQueries.forEach { alternate ->
            assertTrue(alternate.contains("어제") || alternate.contains("previous", ignoreCase = true) || alternate.contains("yesterday", ignoreCase = true))
            assertFalse(alternate.contains("현재가"))
            assertFalse(alternate.contains("current price", ignoreCase = true))
        }
    }

    @Test
    fun `explicit date remains in primary and every stock alternate`() {
        val query = "2026-06-01 삼성전자 주가 얼마였어?"
        val semantics = stockQuerySemantics(query)
        val plan = RuleBasedWebSearchPlanner.plan(RuleBasedWebSearchPlannerInput(currentUserInput = query))

        assertEquals(StockTemporalKind.EXPLICIT_DATE, semantics.temporalKind)
        assertEquals(LocalDate.of(2026, 6, 1), semantics.requestedDate)
        assertTrue(plan.primaryQuery.contains("2026-06-01"))
        assertTrue(plan.primaryQuery.contains("주가"))
        assertTrue(plan.alternateQueries.toString(), plan.alternateQueries.isNotEmpty())
        plan.alternateQueries.forEach { alternate -> assertTrue(alternate, alternate.contains("2026-06-01")) }

        val closePlan = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(currentUserInput = "2026-06-01 삼성전자 종가"),
        )
        assertEquals(StockValueKind.CLOSE, stockQuerySemantics("2026-06-01 삼성전자 종가").valueKind)
        assertTrue(closePlan.primaryQuery.contains("2026-06-01"))
        assertTrue(closePlan.primaryQuery.contains("종가"))
    }

    @Test
    fun `explicit date evidence must match the requested date exactly`() {
        val query = "2026-06-01 삼성전자 주가"
        assertTrue(isStockEvidenceSufficient(listOf(result("삼성전자 주가 73,000원", "삼성전자 현재가 73,000원", "2026-06-01")), query, evaluationDate))
        assertFalse(isStockEvidenceSufficient(listOf(result("삼성전자 주가 73,000원", "삼성전자 현재가 73,000원", "2026-06-02")), query, evaluationDate))
        assertFalse(isStockEvidenceSufficient(listOf(result("삼성전자 주가 73,000원", "삼성전자 현재가 73,000원", null)), query, evaluationDate))
        val context = FusionSearchResponse(
            query = query,
            normalizedQuery = "삼성전자 2026-06-01 주가",
            intent = SearchIntent.STOCK,
            results = emptyList(),
            debugMessage = null,
        ).toStructuredContext()
        assertTrue(context.contains("Stock temporal kind: EXPLICIT_DATE"))
        assertTrue(context.contains("Stock requested date: 2026-06-01"))
    }

    @Test
    fun `Hyundai Motor identity does not collide with Hyundai Mobis`() {
        val query = "Hyundai Motor stock price"
        val semantics = stockQuerySemantics(query)
        assertFalse(stockResultBindsToTarget(result("Hyundai Mobis current price $250", "Hyundai Mobis current price $250"), semantics))
        assertTrue(semantics.toString(), stockResultBindsToTarget(result("Hyundai Motor current price $250", "Hyundai Motor current price $250"), semantics))

        val bare = stockQuerySemantics("Hyundai stock price")
        assertTrue(bare.targetIsAmbiguous)
        assertFalse(stockResultBindsToTarget(result("Hyundai Motor current price $250", "Hyundai Motor current price $250"), bare))
    }
}
