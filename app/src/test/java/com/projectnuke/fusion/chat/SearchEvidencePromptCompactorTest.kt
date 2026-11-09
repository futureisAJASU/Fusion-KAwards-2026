package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.search.FusionSearchResponse
import com.projectnuke.fusion.search.FusionSearchResult
import com.projectnuke.fusion.search.SearchIntent
import com.projectnuke.fusion.search.toAnswerContext
import com.projectnuke.fusion.ui.buildFusionSystemPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEvidencePromptCompactorTest {
    @Test
    fun `search answer prompt preserves source numeric value and unit without conversion`() {
        val content = ChatPromptComposition.finalUserContent(
            body = "What is the latest news about Samsung Electronics",
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = """
                [FUSION_WEB_SEARCH_RESULTS]
                UNTRUSTED_RESULT_1_BEGIN
                {"title":"Broadcom's KRW 18.6 Billion Fine Upheld Over Samsung Electronics Supply Deal"}
                UNTRUSTED_RESULT_1_END
            """.trimIndent(),
            evidenceStatus = "USABLE",
        )

        assertTrue(content.contains("Copy numeric values and units exactly as written"))
        assertTrue(content.contains("do not convert or rescale unless requested"))
    }

    @Test
    fun `search answer contract follows evidence and the exact request`() {
        val request = "Use an official Bank of Korea source to tell me the current base rate"
        val content = ChatPromptComposition.finalUserContent(
            body = request,
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = "The Bank of Korea left its Base Rate unchanged at 3.00%.",
            evidenceStatus = "USABLE",
        )

        val evidenceEnd = content.indexOf("[UNTRUSTED_EXTERNAL_EVIDENCE_END]")
        val requestIndex = content.indexOf("Current user request:\n$request")
        val contractIndex = content.indexOf("[FUSION_SEARCH_ANSWER_CONTRACT]")
        assertTrue("admitted evidence is present", evidenceEnd >= 0)
        assertTrue("exact request is present", requestIndex > evidenceEnd)
        assertTrue("direct answer contract follows the request", contractIndex > requestIndex)

        val contract = content.substring(contractIndex)
        assertTrue(contract.contains("Answer the exact request in the first sentence"))
        assertTrue(contract.contains("Preserve the evidence's meaning and polarity"))
        assertTrue(contract.contains("unchanged at X"))
        assertTrue(contract.contains("do not answer only with general background"))
        assertTrue(contract.contains("Copy proper names, organization names, event names, and program names exactly"))
        assertTrue(contract.contains("retain the original Latin spelling"))
        assertTrue(contract.contains("state the explicit present status"))
        assertTrue(contract.contains("name one specific supported story or headline"))
    }

    @Test
    fun `compaction keeps complete result units and applies host quality for selected count`() {
        val request = "CURRENT_REQUEST_SENTINEL: summarize the current Samsung news"
        val content = ChatPromptComposition.finalUserContent(
            body = request,
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = evidence(4).toAnswerContext(maxResults = 4),
            evidenceStatus = "USABLE",
        )

        val compacted = SearchEvidencePromptCompactor.compactedUserContents(
            content,
            evidenceStatusByCount = mapOf(1 to "LIMITED", 2 to "LIMITED", 3 to "USABLE", 4 to "USABLE"),
        ).first { SearchEvidencePromptCompactor.admittedResultCount(it) == 2 }

        assertEquals(2, Regex("UNTRUSTED_RESULT_\\d+_BEGIN").findAll(compacted).count())
        assertEquals(2, Regex("UNTRUSTED_RESULT_\\d+_END").findAll(compacted).count())
        assertTrue(compacted.contains("EVIDENCE_STATUS=LIMITED"))
        assertTrue(compacted.contains("Evidence status: LIMITED"))
        assertTrue(compacted.contains("CURRENT_REQUEST_SENTINEL"))
        assertTrue(compacted.contains("[UNTRUSTED_EXTERNAL_EVIDENCE_BEGIN]"))
        assertTrue(compacted.contains("[UNTRUSTED_EXTERNAL_EVIDENCE_END]"))
    }

    @Test
    fun `compaction does not drop the only answer-bearing result or truncate its snippet`() {
        val response = FusionSearchResponse(
            query = "What is the current policy rate of the Bank of Korea?",
            normalizedQuery = "Bank of Korea current policy rate",
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(
                FusionSearchResult(
                    title = "Bank of Korea policy overview",
                    source = "bok.or.kr",
                    url = "https://www.bok.or.kr/eng/overview",
                    snippet = "Background information. ".repeat(20),
                    publishedAt = "2026-10-22",
                    queryUsed = "Bank of Korea current policy rate",
                    rank = 1,
                ),
                FusionSearchResult(
                    title = "Bank of Korea Base Rate",
                    source = "bok.or.kr",
                    url = "https://www.bok.or.kr/eng/base-rate",
                    snippet = "Current policy details. " + "Background. ".repeat(20) + "The Bank of Korea Base Rate is 3.00%.",
                    publishedAt = "2026-10-22",
                    queryUsed = "Bank of Korea current policy rate",
                    rank = 2,
                ),
            ),
            debugMessage = null,
        )
        val content = ChatPromptComposition.finalUserContent(
            body = response.query,
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = response.toAnswerContext(maxResults = 2),
            evidenceStatus = "USABLE",
        )

        val variants = SearchEvidencePromptCompactor.compactedUserContents(
            content,
            evidenceStatusByCount = mapOf(1 to "ANSWER_INSUFFICIENT", 2 to "USABLE"),
        )

        assertTrue(variants.none { SearchEvidencePromptCompactor.admittedResultCount(it) == 1 })
        assertTrue(variants.all { it.contains("3.00%") })
    }

    @Test
    fun `generic broad news keeps four sufficient stories inside the local context budget`() {
        val query = "뉴스 알려줘"
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.NEWS,
            results = (1..4).map { index ->
                FusionSearchResult(
                    title = "2026년 11월 5일 주요 뉴스 제목 $index " + "관련 소식 ".repeat(18),
                    source = "news-source-$index.example",
                    url = "https://news-source-$index.example/current/$index",
                    snippet = "이 보도는 사건의 주요 인물과 장소, 발표 내용 및 후속 조치를 설명하는 최신 뉴스입니다. ".repeat(12),
                    publishedAt = "2026-11-05",
                    queryUsed = query,
                    rank = index,
                )
            },
            debugMessage = null,
        )
        val settings = com.projectnuke.fusion.model.GenerationSettings(
            maxTokens = 1024,
            kvCacheCapacityTokens = 4096,
        )
        val finalUserContent = ChatPromptComposition.finalUserContent(
            body = query,
            attachments = emptyList(),
            webSearchEnabled = true,
            webSearchResult = response.toAnswerContext(maxResults = 4),
            evidenceStatus = "USABLE",
        )
        val systemContent = buildFusionSystemPrompt(
            reasoningEnabled = false,
            webSearchEnabled = true,
            webContext = null,
            promptLabInstruction = null,
        )
        val candidateTokenCosts = SearchEvidencePromptCompactor.compactedUserContents(
            finalUserContent,
            mapOf(1 to "ANSWER_INSUFFICIENT", 2 to "ANSWER_INSUFFICIENT", 3 to "ANSWER_INSUFFICIENT", 4 to "USABLE"),
        ).map { candidate ->
            SearchEvidencePromptCompactor.admittedResultCount(candidate) to PromptTokenCost.estimateMessages(
                listOf(ChatMessage("system", systemContent), ChatMessage("user", candidate)),
            )
        }
        val prepared = LocalPromptPreparation.prepare(
            rawMessages = listOf(
                ChatMessage("system", systemContent),
                ChatMessage("user", finalUserContent),
            ),
            budget = FinalPromptBudget(
                modelId = "Gemma 4 E2B-it",
                external = false,
                maxOutputTokens = 1024,
                engineKvCapacityTokens = 4096,
                searchEvidenceStatusByCount = mapOf(
                    1 to "ANSWER_INSUFFICIENT",
                    2 to "ANSWER_INSUFFICIENT",
                    3 to "ANSWER_INSUFFICIENT",
                    4 to "USABLE",
                ),
            ),
            settings = settings,
        )

        assertTrue("four sufficient broad-news records should fit after snippet compaction; candidates=$candidateTokenCosts", prepared != null)
        val fittedUserContent = requireNotNull(prepared).messages.last().content
        assertTrue(fittedUserContent.contains("Current user request:\n$query"))
        assertTrue(fittedUserContent.contains("[FUSION_SEARCH_ANSWER_CONTRACT]"))
        assertTrue(fittedUserContent.contains("Do not ask the user to choose a topic"))
        assertTrue(fittedUserContent.contains("never append a follow-up question"))
        assertFalse(fittedUserContent.contains("For current-status requests"))
        assertEquals(4, Regex("UNTRUSTED_RESULT_\\d+_BEGIN").findAll(fittedUserContent).count())
        (1..4).forEach { index ->
            assertTrue(fittedUserContent.contains("2026년 11월 5일 주요 뉴스 제목 $index"))
            assertTrue(fittedUserContent.contains("news-source-$index.example"))
            assertTrue(fittedUserContent.contains("2026-11-05"))
        }
        assertTrue(fittedUserContent.length < finalUserContent.length)
        assertTrue(prepared.appliedOutputTokens >= FinalPromptBudgeter.MIN_USEFUL_OUTPUT_TOKENS)
    }

    @Test
    fun `long accumulated history and eight mixed-language results preserve answer floor or fail typed`() {
        val response = evidence(8)
        val request = "CURRENT_REQUEST_SENTINEL: 오늘 삼성전자 주요 뉴스를 요약해줘"
        val messages = buildList {
            add(ChatMessage("system", "Core Fusion answer policy. ".repeat(30)))
            repeat(16) { turn ->
                add(ChatMessage("user", "이전 질문 $turn about Samsung news ".repeat(40)))
                add(ChatMessage("assistant", "이전 답변 $turn with background details. ".repeat(40)))
            }
            add(
                ChatMessage(
                    "user",
                    ChatPromptComposition.finalUserContent(
                        body = request,
                        attachments = emptyList(),
                        webSearchEnabled = true,
                        webSearchResult = response.toAnswerContext(maxResults = 8),
                        evidenceStatus = "USABLE",
                    ),
                ),
            )
        }

        val result = FinalPromptBudgeter.fit(
            messages,
            FinalPromptBudget(
                modelId = "Gemma 4 E2B",
                external = false,
                maxOutputTokens = 2_048,
                engineKvCapacityTokens = 4_096,
                searchEvidenceStatusByCount = (1..8).associateWith { if (it >= 3) "USABLE" else "LIMITED" },
            ),
        )

        if (result.isTooLarge) {
            assertTrue(result.messages.isEmpty())
        } else {
            assertTrue("short answer allowance ${result.appliedOutputTokens}", result.appliedOutputTokens >= 256)
            val finalUser = result.messages.last().content
            val requestAndAnswerContract = finalUser.substringAfter("Current user request:\n")
            assertTrue(requestAndAnswerContract.startsWith(request))
            assertTrue(requestAndAnswerContract.contains("[FUSION_SEARCH_ANSWER_CONTRACT]"))
            assertTrue(
                PromptTokenEstimator.estimateTokens(result.messages) + result.appliedOutputTokens + 128 <= 4_096,
            )
            val admitted = result.admittedSearchEvidenceCount ?: 0
            assertTrue(admitted in 1..8)
            assertEquals(admitted, Regex("UNTRUSTED_RESULT_\\d+_BEGIN").findAll(finalUser).count())
            assertFalse(finalUser.contains("UNTRUSTED_RESULT_${admitted + 1}_BEGIN"))
        }
    }

    private fun evidence(count: Int) = FusionSearchResponse(
        query = "Samsung electronics current news",
        normalizedQuery = "Samsung electronics current news",
        intent = SearchIntent.NEWS,
        results = (1..count).map { index ->
            FusionSearchResult(
                title = "Samsung electronics RESULT_SENTINEL_$index current news",
                source = "Publisher $index",
                url = "https://example.test/news/$index?lang=en&item=$index",
                snippet = "삼성전자 최근 뉴스 검색 근거 $index with a bounded English detail. ".repeat(12),
                publishedAt = "2026-11-01",
                queryUsed = "Samsung electronics current news",
                rank = index,
            )
        },
        debugMessage = null,
    )
}
