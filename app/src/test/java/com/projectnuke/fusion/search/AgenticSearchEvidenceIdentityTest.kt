package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgenticSearchEvidenceIdentityTest {
    @Test
    fun strongSecondQueryResultsCannotBeStarvedByEightEarlierWeakResults() = runTest {
        val weak = (1..8).map { index ->
            result("Unrelated weather result $index", "https://weak.test/$index", "unrelated local weather", "weak query")
        }
        val strong = (1..4).map { index ->
            result("Samsung chip earnings news $index", "https://strong.test/$index", "Samsung chip earnings acquisition news", "strong query")
        }

        val merged = mergeTwoQueries("Samsung chip earnings acquisition news", weak, strong)

        assertEquals(8, merged.results.size)
        assertEquals(4, merged.results.take(4).count { it.queryUsed == "strong query" })
        assertTrue(merged.toAnswerContext().contains("Samsung chip earnings news 4"))
        assertFalse(merged.toAnswerContext().contains("Unrelated weather result 1"))
    }

    @Test
    fun strongFirstQueryEvidenceRemainsAheadOfWeakSecondQueryEvidence() = runTest {
        val strong = (1..4).map { index ->
            result("Samsung chip earnings news $index", "https://strong.test/$index", "Samsung chip earnings news", "strong query")
        }
        val weak = (1..4).map { index ->
            result("Unrelated weather result $index", "https://weak.test/$index", "unrelated weather", "weak query")
        }

        val merged = mergeTwoQueries("Samsung chip earnings news", strong, weak)

        assertEquals(4, merged.results.take(4).count { it.queryUsed == "strong query" })
    }

    @Test
    fun complementaryQueryResultsBothReachTheModelFacingSet() = runTest {
        val first = listOf(
            result("Samsung chip industry report", "https://a.test/1", "Samsung chip production update", "first query"),
            result("Samsung chip supply report", "https://a.test/2", "Samsung chip supply chain", "first query"),
        )
        val second = listOf(
            result("Earnings acquisition announcement", "https://b.test/1", "earnings acquisition deal announced", "second query"),
            result("Earnings acquisition outlook", "https://b.test/2", "earnings and acquisition outlook", "second query"),
        )

        val merged = mergeTwoQueries("Samsung chip earnings acquisition", first, second)

        assertEquals(setOf("first query", "second query"), merged.results.take(4).map { it.queryUsed }.toSet())
    }

    @Test
    fun qualityIsRecomputedFromModelAdmittedResultsNotEverySuccessfulResponse() = runTest {
        val strong = (1..6).map { index ->
            result("Samsung earnings result $index", "https://strong.test/$index", "Samsung earnings report", "strong query")
        }
        val weak = (1..4).map { index ->
            result("Unrelated weather result $index", "https://weak.test/$index", "unrelated weather", "weak query")
        }

        val merged = mergeTwoQueries(
            originalQuery = "Samsung earnings report",
            firstResults = strong,
            secondResults = weak,
        )

        assertTrue(merged.results.take(4).all { it.queryUsed == "strong query" })
        assertEquals(true, merged.quality?.isUsable)
    }

    @Test
    fun mixedSubqueriesMustNotAdmitGeneralEvidenceAsAStockAnswerWhenStockQueryFails() = runTest {
        val generalQuery = "삼성전자 사업 현황"
        val stockQuery = "삼성전자 현재 주가"
        val replies = ArrayDeque(
            listOf(
                search(generalQuery),
                search(stockQuery),
                SearchAgentReply.StructuredIntent("""{"action":"NO_MORE_SEARCH"}"""),
            ),
        )
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply = replies.removeFirst()
        }
        val merged = AgenticSearchHarness(
            model = model,
            searchTool = AgenticExactSearchTool { query, _, _ ->
                if (query == generalQuery) {
                    response(
                        query,
                        listOf(result("삼성전자 기업 소개", "https://example.test/company", "기업과 사업에 대한 소개", query)),
                        intent = SearchIntent.GENERAL,
                    )
                } else {
                    response(
                        query,
                        listOf(result("반도체 산업 동향", "https://example.test/industry", "업계 성장률 전망", query)),
                        intent = SearchIntent.STOCK,
                    )
                }
            },
            queryAuthorizer = AgenticSearchQueryAuthorizer { _, query -> AgenticQueryAuthorization.Authorized(query) },
        ).execute(
            AgenticSearchRequest(
                requestId = "mixed-stock-admission",
                originalUserQuery = "오늘 삼성전자 주가 알려줘",
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                limits = AgenticSearchLimits(maxDecisionRounds = 3, maxQueriesPerRound = 1, maxIssuedQueries = 2),
                ownerStillActive = { true },
                searchPermissionStillValid = { true },
            ),
        ).evidence

        assertNotNull(merged)
        assertEquals(listOf(generalQuery), merged!!.results.map { it.queryUsed }.distinct())
        val route = SearchStrategyRouteResult(
            outcome = SearchRouteOutcome.COMPLETED,
            response = merged,
            selectedStrategy = com.projectnuke.fusion.chat.SearchStrategy.AGENT_HARNESS,
            executedStrategy = com.projectnuke.fusion.chat.SearchStrategy.AGENT_HARNESS,
        )
        assertFalse("GENERAL subquery evidence cannot satisfy the original STOCK request", route.answerEvidence().actualWebSearchUsed)
        assertNull(route.answerEvidence().structuredContext)
        assertTrue(route.answerEvidence().sources.isEmpty())
    }

    @Test
    fun mergedCrossQueryContextKeepsPerResultQueryProvenance() = runTest {
        val firstQuery = "오늘 삼성전자 주가"
        val secondQuery = "삼성전자 실적 revenue"
        val replies = ArrayDeque(
            listOf(
                search(firstQuery),
                search(secondQuery),
                SearchAgentReply.StructuredIntent("""{"action":"NO_MORE_SEARCH"}"""),
            ),
        )
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply = replies.removeFirst()
        }
        val merged = AgenticSearchHarness(
            model = model,
            searchTool = AgenticExactSearchTool { query, _, _ ->
                val stockIntent = query == firstQuery
                response(
                    query = query,
                    results = listOf(
                        result(
                            if (stockIntent) "삼성전자 현재가 73,000원" else "삼성전자 earnings revenue",
                            "https://example.test/${if (stockIntent) "price" else "earnings"}",
                            if (stockIntent) "삼성전자 현재가 73,000원" else "evidence for $query",
                            query,
                        ),
                    ),
                    intent = if (stockIntent) SearchIntent.STOCK else SearchIntent.GENERAL,
                )
            },
            queryAuthorizer = AgenticSearchQueryAuthorizer { _, query -> AgenticQueryAuthorization.Authorized(query) },
        ).execute(
            AgenticSearchRequest(
                requestId = "cross-query-metadata",
                originalUserQuery = "삼성전자 주가와 실적",
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                limits = AgenticSearchLimits(maxDecisionRounds = 3, maxQueriesPerRound = 1, maxIssuedQueries = 2),
                ownerStillActive = { true },
                searchPermissionStillValid = { true },
            ),
        ).evidence!!

        val context = merged.toStructuredContext()
        assertEquals("삼성전자 주가와 실적", merged.query)
        assertEquals(SearchIntent.STOCK, merged.intent)
        assertTrue(context.contains("\"query\":\"$firstQuery\""))
        assertTrue(context.contains("\"query\":\"$secondQuery\""))
        assertTrue(context.contains("Original query: \"삼성전자 주가와 실적\""))
        assertNotNull(merged.quality)
    }

    @Test
    fun mergedSourcesBelongOnlyToRetainedResultsIncludingUrlLessRecords() = runTest {
        val firstResults = listOf(
            result("kept-shared-url", "https://same.test/item", "snippet-shared", "query-one"),
            result("kept-one", "https://one.test/item", "snippet-one", "query-one"),
            result("kept-url-less-one", null, "snippet-url-less-one", "query-one"),
            result("kept-url-less-two", null, "snippet-url-less-two", "query-one"),
            result("kept-five", "https://five.test/item", "snippet-five", "query-one"),
        )
        val secondResults = listOf(
            result("kept-six", "https://six.test/item", "snippet-six", "query-two"),
            result("kept-seven", "https://seven.test/item", "snippet-seven", "query-two"),
            result("kept-eight", "https://eight.test/item", "snippet-eight", "query-two"),
            result("omitted-shares-url", "https://same.test/item", "snippet-omitted-shared", "query-two"),
            result("omitted-url-less", null, "snippet-omitted-url-less", "query-two"),
        )
        val replies = ArrayDeque(
            listOf(
                search("query-one"),
                search("query-two"),
                SearchAgentReply.StructuredIntent("""{"action":"NO_MORE_SEARCH"}"""),
            ),
        )
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply = replies.removeFirst()
        }
        val callCount = mutableListOf<String>()
        val result = AgenticSearchHarness(
            model = model,
            searchTool = AgenticExactSearchTool { query, _, _ ->
                callCount += query
                when (query) {
                    "query-one" -> response(query, firstResults)
                    "query-two" -> response(query, secondResults, extraOrphan = true)
                    else -> error("unexpected query: $query")
                }
            },
            queryAuthorizer = AgenticSearchQueryAuthorizer { _, query -> AgenticQueryAuthorization.Authorized(query) },
        ).execute(
            AgenticSearchRequest(
                requestId = "identity-request",
                originalUserQuery = "typed query",
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                limits = AgenticSearchLimits(maxDecisionRounds = 3, maxQueriesPerRound = 1, maxIssuedQueries = 4),
                ownerStillActive = { true },
                searchPermissionStillValid = { true },
            ),
        )

        val merged = result.evidence
        assertEquals(listOf("query-one", "query-two"), callCount)
        assertNotNull(merged)
        assertEquals(8, merged!!.results.size)
        assertEquals(8, merged.sources.size)
        val resultKeys = merged.results.map(::key).toSet()
        val sourceKeys = merged.sources.map(::key).toSet()
        assertEquals(resultKeys, sourceKeys)
        assertEquals(8, resultKeys.size)
    }

    private fun search(query: String) = SearchAgentReply.StructuredIntent(
        """{"action":"SEARCH","queries":["$query"]}""",
    )

    private suspend fun mergeTwoQueries(
        originalQuery: String,
        firstResults: List<FusionSearchResult>,
        secondResults: List<FusionSearchResult>,
    ): FusionSearchResponse {
        val replies = ArrayDeque(
            listOf(
                SearchAgentReply.StructuredIntent(
                    """{"action":"SEARCH","queries":["weak query","strong query"]}""",
                ),
                SearchAgentReply.StructuredIntent("""{"action":"NO_MORE_SEARCH"}"""),
            ),
        )
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply = replies.removeFirst()
        }
        val merged = AgenticSearchHarness(
            model = model,
            searchTool = AgenticExactSearchTool { query, _, _ ->
                response(
                    query = query,
                    results = if (query == "weak query") firstResults else secondResults,
                    intent = SearchIntent.GENERAL,
                ).copy(
                    quality = if (query == "weak query") {
                        WebSearchQuality(false, 0.1, listOf("weak response"), true)
                    } else {
                        WebSearchQuality(true, 0.9, listOf("strong response"), false)
                    },
                )
            },
            queryAuthorizer = AgenticSearchQueryAuthorizer { _, query -> AgenticQueryAuthorization.Authorized(query) },
        ).execute(
            AgenticSearchRequest(
                requestId = "rank-two-query-results",
                originalUserQuery = originalQuery,
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                limits = AgenticSearchLimits(maxDecisionRounds = 2, maxQueriesPerRound = 2, maxIssuedQueries = 2),
                ownerStillActive = { true },
                searchPermissionStillValid = { true },
            ),
        ).evidence
        return requireNotNull(merged)
    }

    private fun result(title: String, url: String?, snippet: String, query: String) = FusionSearchResult(
        title = title,
        source = "Publisher $title",
        url = url,
        snippet = snippet,
        publishedAt = null,
        queryUsed = query,
    )

    private fun response(
        query: String,
        results: List<FusionSearchResult>,
        extraOrphan: Boolean = false,
        intent: SearchIntent = SearchIntent.GENERAL,
    ): FusionSearchResponse {
        val sources = results.mapIndexed { index, item ->
            WebSearchSource(
                title = item.title,
                source = item.source,
                url = item.url,
                snippet = item.snippet,
                publishedAt = item.publishedAt,
                providerType = item.providerType,
                providerDisplayName = item.providerDisplayName,
                searchCategory = item.searchCategory,
                queryUsed = item.queryUsed,
                rank = index + 1,
            )
        }.toMutableList()
        if (extraOrphan) {
            sources += WebSearchSource(
                title = "orphan-url-less-source",
                source = "Publisher orphan",
                url = null,
                snippet = "orphan snippet",
                publishedAt = null,
                providerType = WebSearchProviderType.FREE_DEFAULT,
                providerDisplayName = "Free",
                searchCategory = null,
                queryUsed = query,
                rank = 99,
            )
            val adversarialSourceOrder = listOf(
                "omitted-shares-url",
                "omitted-url-less",
                "orphan-url-less-source",
                "kept-six",
                "kept-seven",
                "kept-eight",
            )
            sources.sortBy { source -> adversarialSourceOrder.indexOf(source.title).let { if (it < 0) Int.MAX_VALUE else it } }
        }
        return FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = intent,
            results = results,
            debugMessage = null,
            sources = sources,
        )
    }

    private fun key(result: FusionSearchResult): List<String?> =
        listOf(result.title, result.url, result.snippet, result.queryUsed)

    private fun key(source: WebSearchSource): List<String?> =
        listOf(source.title, source.url, source.snippet, source.queryUsed)
}
