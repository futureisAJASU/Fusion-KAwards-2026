package com.projectnuke.fusion.search

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchReliabilityMetamorphicTest {
    private val today = LocalDate.of(2026, 11, 3)
    private val query = "latest news about Samsung Electronics"

    @Test
    fun `unrelated fresh record cannot turn stale topic news sufficient`() {
        val staleTopic = result(
            title = "Samsung Electronics quarterly results",
            snippet = "Samsung Electronics reported quarterly results.",
            publishedAt = "2026-10-01",
        )
        val freshUnrelated = result(
            title = "Tesla unveils a new vehicle",
            snippet = "Tesla announced a new vehicle platform.",
            publishedAt = "2026-11-03",
        )

        val before = evaluate(listOf(staleTopic))
        val after = evaluate(listOf(staleTopic, freshUnrelated))

        assertFalse(before.answerSufficient)
        assertEquals(before.answerSufficient, after.answerSufficient)
        assertFalse(after.answerSufficient)
    }

    @Test
    fun `duplicated unrelated fresh records cannot turn stale topic news sufficient`() {
        val staleTopic = result(
            title = "Samsung Electronics quarterly results",
            snippet = "Samsung Electronics reported quarterly results.",
            publishedAt = "2026-10-01",
        )
        val freshUnrelated = result(
            title = "Tesla unveils a new vehicle",
            snippet = "Tesla announced a new vehicle platform.",
            publishedAt = "2026-11-03",
        )

        val before = evaluate(listOf(staleTopic))
        val after = evaluate(
            listOf(
                staleTopic,
                freshUnrelated,
                freshUnrelated.copy(title = "Tesla quarterly delivery update"),
            ),
        )

        assertFalse(before.answerSufficient)
        assertEquals(before.answerSufficient, after.answerSufficient)
    }

    @Test
    fun `fresh topic evidence keeps stale-topic supplementation sufficient`() {
        val staleTopic = result(
            title = "Samsung Electronics quarterly results",
            snippet = "Samsung Electronics reported quarterly results.",
            publishedAt = "2026-10-01",
        )
        val freshTopic = result(
            title = "Samsung Electronics announces new investment",
            snippet = "Samsung Electronics announced a new investment.",
            publishedAt = "2026-11-03",
        )
        val freshUnrelated = result(
            title = "Tesla unveils a new vehicle",
            snippet = "Tesla announced a new vehicle platform.",
            publishedAt = "2026-11-03",
        )

        val base = evaluate(listOf(staleTopic, freshTopic))
        val withUnrelated = evaluate(listOf(staleTopic, freshTopic, freshUnrelated))

        assertTrue(base.answerSufficient)
        assertTrue(withUnrelated.answerSufficient)
    }

    @Test
    fun `evidence order does not change sufficiency`() {
        val staleTopic = result(
            title = "Samsung Electronics quarterly results",
            snippet = "Samsung Electronics reported quarterly results.",
            publishedAt = "2026-10-01",
        )
        val freshTopic = result(
            title = "Samsung Electronics announces new investment",
            snippet = "Samsung Electronics announced a new investment.",
            publishedAt = "2026-11-03",
        )
        val freshUnrelated = result(
            title = "Tesla unveils a new vehicle",
            snippet = "Tesla announced a new vehicle platform.",
            publishedAt = "2026-11-03",
        )
        val orders = listOf(
            listOf(staleTopic, freshTopic, freshUnrelated),
            listOf(staleTopic, freshUnrelated, freshTopic),
            listOf(freshTopic, staleTopic, freshUnrelated),
            listOf(freshTopic, freshUnrelated, staleTopic),
            listOf(freshUnrelated, staleTopic, freshTopic),
            listOf(freshUnrelated, freshTopic, staleTopic),
        )

        val expected = evaluate(orders.first()).status

        orders.forEach { order ->
            assertEquals(expected, evaluate(order).status)
        }
    }

    @Test
    fun `appending stale unrelated evidence does not weaken fresh topic support`() {
        val freshTopic = result(
            title = "Samsung Electronics announces new investment",
            snippet = "Samsung Electronics announced a new investment.",
            publishedAt = "2026-11-03",
        )
        val staleUnrelated = result(
            title = "Tesla quarterly results",
            snippet = "Tesla reported quarterly results.",
            publishedAt = "2026-10-01",
        )

        assertTrue(evaluate(listOf(freshTopic)).answerSufficient)
        assertTrue(evaluate(listOf(freshTopic, staleUnrelated)).answerSufficient)
    }

    private fun evaluate(results: List<FusionSearchResult>) =
        SearchAnswerSufficiencyPolicy.evaluate(
            query = query,
            intent = SearchIntent.NEWS,
            results = results,
            today = today,
        )

    private fun result(
        title: String,
        snippet: String,
        publishedAt: String,
    ) = FusionSearchResult(
        title = title,
        source = "example.com",
        url = "https://example.com/${title.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
        snippet = snippet,
        publishedAt = publishedAt,
        queryUsed = query,
    )
}
