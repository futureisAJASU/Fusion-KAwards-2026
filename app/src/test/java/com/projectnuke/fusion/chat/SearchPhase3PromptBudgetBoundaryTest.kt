package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.search.FusionSearchResponse
import com.projectnuke.fusion.search.FusionSearchResult
import com.projectnuke.fusion.search.SearchIntent
import com.projectnuke.fusion.search.toAnswerContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPhase3PromptBudgetBoundaryTest {
    @Test
    fun fittedEvidenceNeverSplitsOrOrphansAStructuredRecord() {
        val query = "What is the current policy rate of the Bank of Korea?"
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = (1..4).map { index ->
                FusionSearchResult(
                    title = "Bank of Korea policy record $index",
                    source = "bok.or.kr",
                    url = "https://www.bok.or.kr/eng/record/$index",
                    snippet = "The Bank of Korea policy record $index states the current policy rate in detail. " +
                        "Background context that keeps this snippet long enough to be reduced under budget pressure. ".repeat(4),
                    publishedAt = "2026-11-05",
                    queryUsed = query,
                    rank = index,
                )
            },
            debugMessage = null,
        )
        val userContent = ChatPromptComposition.finalUserContent(
            body = query,
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = response.toAnswerContext(maxResults = 4),
            evidenceStatus = "USABLE",
        )
        val messages = listOf(
            ChatMessage("system", "Core Fusion policy. ".repeat(20)),
            ChatMessage("user", userContent),
        )

        (900..2_600 step 25).forEach { capacity ->
            val result = FinalPromptBudgeter.fit(
                messages,
                FinalPromptBudget(
                    modelId = "Gemma 4 E2B",
                    external = false,
                    maxOutputTokens = 1_024,
                    engineKvCapacityTokens = capacity,
                    searchEvidenceStatusByCount = (1..4).associateWith { "USABLE" },
                ),
            )
            if (result.isTooLarge) {
                assertTrue("too large result must carry no messages", result.messages.isEmpty())
                return@forEach
            }
            val finalUser = result.messages.last().content
            val begins = Regex("UNTRUSTED_RESULT_(\\d+)_BEGIN").findAll(finalUser).map { it.groupValues[1] }.toList()
            val ends = Regex("UNTRUSTED_RESULT_(\\d+)_END").findAll(finalUser).map { it.groupValues[1] }.toList()
            assertEquals("capacity=$capacity begins=$begins ends=$ends", begins, ends)
            assertEquals("capacity=$capacity", result.admittedSearchEvidenceCount ?: begins.size, begins.size)
            assertTrue("capacity=$capacity", finalUser.contains("Current user request:\n$query"))
            val snippetMatches = Regex("\"snippet\":\"(.*?)\"").findAll(finalUser).toList()
            snippetMatches.forEach { match ->
                val snippet = match.groupValues[1]
                assertTrue(
                    "capacity=$capacity produced an unpaired surrogate: $snippet",
                    snippet.none { it.code in 0xD800..0xDBFF },
                )
                assertTrue(
                    "capacity=$capacity produced a broken surrogate: $snippet",
                    snippet.none { it.code in 0xDC00..0xDFFF },
                )
            }
        }
    }
}
