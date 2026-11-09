package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ai.model.AiToolCall
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.ChatPromptComposition
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.search.AgenticExactSearchTool
import com.projectnuke.fusion.search.AgenticQueryAuthorization
import com.projectnuke.fusion.search.AgenticSearchHarness
import com.projectnuke.fusion.search.AgenticSearchLimits
import com.projectnuke.fusion.search.AgenticSearchOutcome
import com.projectnuke.fusion.search.AgenticSearchQueryAuthorizer
import com.projectnuke.fusion.search.AgenticSearchRequest
import com.projectnuke.fusion.search.FusionSearchResponse
import com.projectnuke.fusion.search.FusionSearchResult
import com.projectnuke.fusion.search.SearchAgentMessage
import com.projectnuke.fusion.search.SearchAgentModel
import com.projectnuke.fusion.search.SearchAgentProtocol
import com.projectnuke.fusion.search.SearchAgentReply
import com.projectnuke.fusion.search.SearchAnswerSufficiencyStatus
import com.projectnuke.fusion.search.SearchIntent
import com.projectnuke.fusion.search.SearchRouteOutcome
import com.projectnuke.fusion.search.SearchEvidenceStatus
import com.projectnuke.fusion.search.SearchStrategyRouteResult
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchQuality
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.search.parseSearchSourcesMetadata
import com.projectnuke.fusion.search.parseRetrievedSearchResultsMetadata
import com.projectnuke.fusion.search.toAnswerContext
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustExecutionFailure
import com.projectnuke.fusion.trust.TrustFailurePolicy
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// GATE B: one admitted evidence value drives the final prompt, the persisted
// row, and the research intervention. Route-visible evidence must never
// disagree with what the model was told or what the transcript records.
class SearchEvidenceConsumptionIntegrationTest {

    @Test
    fun `persisted current status drops a date inferred from an undated weekday snippet`() {
        val query = "What is the current status of NASA Europa Clipper"
        val snippet = "Europa Clipper has embarked on its long voyage to Jupiter. " +
            "The spacecraft launched at 12:06 p.m. EDT Monday aboard a SpaceX Falcon Heavy rocket."
        val result = FusionSearchResult(
            title = "Europa Clipper Homepage - NASA Science",
            source = "science.nasa.gov",
            url = "https://science.nasa.gov/europa-clipper-homepage/",
            snippet = snippet,
            publishedAt = null,
            providerDisplayName = "Free",
            rank = 1,
            queryUsed = "status of NASA Europa Clipper latest",
        )
        val source = WebSearchSource(
            title = result.title,
            source = result.source,
            url = result.url,
            snippet = result.snippet,
            publishedAt = result.publishedAt,
            providerType = WebSearchProviderType.FREE_DEFAULT,
            providerDisplayName = "Free",
            searchCategory = null,
            queryUsed = result.queryUsed,
            rank = result.rank,
        )
        val response = usableResponse().copy(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(result),
            sources = listOf(source),
        )
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.COMPLETED, response))

        val persisted = consumption.persistedAssistantContent(
            answer = "NASA의 유로파 클리퍼는 목성으로의 장거리 항해를 시작하여 유로파를 탐사할 예정이며, " +
                "우주선은 SpaceX의 Falcon Heavy 로켓을 타고 1월 12일 정오에 발사되었습니다.",
            trustRoutingDecision = TRUST,
        )

        assertTrue(persisted.startsWith("NASA의 유로파 클리퍼는 목성으로의 장거리 항해를 시작하여 유로파를 탐사할 예정입니다."))
        assertTrue(!persisted.contains("1월 12일"))
        assertEquals(listOf(source), parseSearchSourcesMetadata(persisted))
    }

    @Test
    fun `persisted date only answer omits unrequested weekday and time`() {
        val query = "According to NASA what date did Artemis II splash down"
        val result = FusionSearchResult(
            title = "Artemis II Splashdown and Recovery - NASA",
            source = "nasa.gov",
            url = "https://www.nasa.gov/mission/artemis-ii/",
            snippet = "Artemis II splashed down at 8:07 p.m. EDT on April 10, 2026.",
            publishedAt = null,
            providerDisplayName = "Free",
            rank = 1,
            queryUsed = query,
        )
        val source = WebSearchSource(
            title = result.title,
            source = result.source,
            url = result.url,
            snippet = result.snippet,
            publishedAt = result.publishedAt,
            providerType = WebSearchProviderType.FREE_DEFAULT,
            providerDisplayName = result.providerDisplayName.orEmpty(),
            searchCategory = null,
            queryUsed = result.queryUsed,
            rank = result.rank,
        )
        val response = usableResponse().copy(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(result),
            sources = listOf(source),
        )
        val consumption = SearchEvidenceConsumption.from(
            route(SearchRouteOutcome.COMPLETED, response),
            requestedQuery = query,
        )

        val persisted = consumption.persistedAssistantContent(
            answer = "NASA에 따르면 Artemis II는 2026년 4월 10일에 낙하산 재현 및 복귀했습니다.",
            trustRoutingDecision = TRUST,
        )
        val visibleAnswer = persisted.substringBefore("[FUSION_SEARCH_SOURCES_JSON]").trimEnd()

        assertEquals("2026년 4월 10일.", visibleAnswer)
        assertEquals(listOf(source), parseSearchSourcesMetadata(persisted))
    }

    @Test
    fun `no route admits nothing and produces a search-free prompt`() {
        val consumption = SearchEvidenceConsumption.from(null)

        assertFalse(consumption.actualWebSearchUsed)
        assertNull(consumption.structuredContext)
        assertTrue(consumption.sources.isEmpty())

        val prompt = consumption.systemPrompt(reasoningEnabled = false)
        assertFalse(prompt.contains(WEB_SECTION_MARKER))
        assertFalse(consumption.finalUserContent("question", emptyList()).contains(USER_WEB_MARKER))
        val persisted = consumption.persistedAssistantContent("answer", TRUST)
        assertTrue(persisted.startsWith("answer"))
        assertTrue(parseSearchSourcesMetadata(persisted).isEmpty())
        assertTrue(consumption.interventionWebSources().isEmpty())
    }

    @Test
    fun `denied routes cannot admit even a usable response`() {
        val denied = listOf(
            SearchRouteOutcome.PRIVACY_DENIED,
            SearchRouteOutcome.PERMISSION_REVOKED,
            SearchRouteOutcome.OWNERSHIP_LOST,
            SearchRouteOutcome.SEARCH_DISABLED,
            SearchRouteOutcome.CANCELLED,
            SearchRouteOutcome.MANUAL_QUERY_MISSING,
            SearchRouteOutcome.MANUAL_QUERY_INVALID,
        )

        denied.forEach { outcome ->
            val consumption = SearchEvidenceConsumption.from(route(outcome, usableResponse()))
            assertFalse("$outcome", consumption.actualWebSearchUsed)
            assertNull("$outcome", consumption.structuredContext)
            assertTrue("$outcome", consumption.sources.isEmpty())
            assertFalse("$outcome", consumption.systemPrompt(false).contains(WEB_SECTION_MARKER))
            val persisted = consumption.persistedAssistantContent("answer", TRUST)
            assertTrue("$outcome", persisted.startsWith("answer"))
            assertTrue("$outcome", parseSearchSourcesMetadata(persisted).isEmpty())
        }
    }

    @Test
    fun `weak but attributable evidence stays admitted`() {
        val weak = usableResponse().copy(
            quality = WebSearchQuality(
                isUsable = false,
                score = 0.1,
                reasons = listOf("low aggregate quality"),
                shouldTryFallback = false,
            ),
        )

        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.COMPLETED, weak))

        assertTrue(consumption.actualWebSearchUsed)
        assertTrue(consumption.structuredContext.orEmpty().contains("usable public snippet"))
        assertEquals(weak.sources, consumption.sources)
    }

    @Test
    fun `consumption exposes the exact admitted facts`() {
        val response = usableResponse()
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.AGENT_FAILED, response))

        assertTrue(consumption.actualWebSearchUsed)
        assertEquals(response.sources, consumption.sources)
        assertFalse(consumption.structuredContext.orEmpty().contains("https://example.test/usable"))
        assertEquals("https://example.test/usable", consumption.sources.single().url)
    }

    @Test
    fun `prompt user content and persistence all consume the admitted evidence`() {
        val response = usableResponse()
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.COMPLETED, response))

        val prompt = consumption.systemPrompt(reasoningEnabled = false)
        assertTrue(prompt.contains(WEB_SECTION_MARKER))
        assertFalse(prompt.contains("usable public snippet"))

        val userContent = consumption.finalUserContent("question", emptyList())
        assertTrue(userContent.contains(USER_WEB_MARKER))
        assertTrue(userContent.contains("usable public snippet"))
        assertEquals(1, Regex("UNTRUSTED_RESULT_1_BEGIN").findAll(userContent).count())

        val persisted = consumption.persistedAssistantContent("answer", TRUST)
        assertTrue(persisted.startsWith("answer"))
        val sources = parseSearchSourcesMetadata(persisted)
        assertEquals(response.sources, sources)
    }

    @Test
    fun `prompt and user content delegate byte-identically for admitted evidence`() {
        val response = usableResponse()
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.TIMEOUT, response))

        assertEquals(
            ChatPromptComposition.finalUserContent(
                body = "question",
                attachments = emptyList(),
                webSearchEnabled = true,
                webSearchResult = response.toAnswerContext(),
                evidenceStatus = "LIMITED",
            ),
            consumption.finalUserContent("question", emptyList()),
        )
        assertEquals(
            buildFusionSystemPrompt(
                reasoningEnabled = true,
                webSearchEnabled = true,
                webContext = response.toAnswerContext(),
                promptLabInstruction = null,
                reasoningBudgetTokens = 512,
                interactionMode = ChatInteractionMode.NORMAL,
            ),
            consumption.systemPrompt(reasoningEnabled = true),
        )
    }

    @Test
    fun `admission labels the model-facing retrieval prefix rather than trusting aggregate quality`() {
        val response = usableResponse().copy(quality = WebSearchQuality(true, 0.9, emptyList(), false))
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.COMPLETED, response))
        val userContent = consumption.finalUserContent("question", emptyList())

        assertTrue(userContent.contains("EVIDENCE_STATUS=LIMITED"))
        assertTrue(userContent.contains("RETRIEVAL_USABLE=false"))
        assertTrue(userContent.contains("If RETRIEVAL_USABLE is false, briefly distinguish supported facts from the remaining limitation."))
    }

    @Test
    fun `limited model-facing retrieval prefix is labeled while complete source metadata is preserved`() {
        val response = usableResponse().copy(quality = WebSearchQuality(false, 0.2, listOf("weak"), false))
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.COMPLETED, response))
        val userContent = consumption.finalUserContent("question", emptyList())

        assertTrue(userContent.contains("EVIDENCE_STATUS=LIMITED"))
        assertTrue(userContent.contains("RETRIEVAL_USABLE=false"))
        assertEquals("https://example.test/usable", consumption.sources.single().url)
    }

    @Test
    fun `retrieval success without the requested current rate fails the trust search gate`() {
        val query = "What is the current official policy rate of the Bank of Korea"
        val response = usableResponse().copy(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(
                FusionSearchResult(
                    title = "Bank of Korea policy meeting",
                    source = "bok.or.kr",
                    url = "https://www.bok.or.kr/eng/meeting",
                    snippet = "The Monetary Policy Board reviewed current economic conditions.",
                    publishedAt = "2026-10-22",
                    queryUsed = query,
                ),
                FusionSearchResult(
                    title = "Bank of Korea monetary policy overview",
                    source = "bok.or.kr",
                    url = "https://www.bok.or.kr/eng/policy",
                    snippet = "The central bank explains its monetary policy framework.",
                    publishedAt = "2026-10-22",
                    queryUsed = query,
                ),
            ),
            sources = emptyList(),
        )
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.COMPLETED, response))

        assertTrue(consumption.actualWebSearchUsed)
        assertTrue(consumption.retrievalUsable)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, consumption.answerSufficiency)
        assertNull(consumption.structuredContext)
        assertTrue(consumption.sources.isEmpty())
        assertTrue(consumption.deterministicAbstentionAnswer.orEmpty().contains("확인하지 못했어요"))
        assertFalse(consumption.usableForTrustSearch)
        val failure = TrustFailurePolicy.searchFailure(
            decision = TRUST,
            attempted = consumption.actualWebSearchUsed,
            usableResult = consumption.usableForTrustSearch,
            requestId = "request-current-rate",
        )
        assertTrue(failure is TrustExecutionFailure.SearchNoUsableResult)
    }

    @Test
    fun `final fitting retains requested rate evidence ahead of background context`() {
        val query = "What is the current official policy rate of the Bank of Korea"
        val response = usableResponse().copy(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = listOf(
                FusionSearchResult(
                    title = "Bank of Korea policy meeting",
                    source = "bok.or.kr",
                    url = "https://www.bok.or.kr/eng/meeting",
                    snippet = "The Monetary Policy Board reviewed current economic conditions.",
                    publishedAt = "2026-10-22",
                    queryUsed = query,
                ),
                FusionSearchResult(
                    title = "Bank of Korea Base Rate",
                    source = "bok.or.kr",
                    url = "https://www.bok.or.kr/eng/base-rate",
                    snippet = "The Bank of Korea Base Rate is 3.00% as of October 22, 2026.",
                    publishedAt = "2026-10-22",
                    queryUsed = query,
                ),
            ),
            sources = emptyList(),
        )
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.COMPLETED, response))

        assertEquals(SearchAnswerSufficiencyStatus.SUFFICIENT, consumption.answerSufficiency)
        assertTrue(consumption.usableForTrustSearch)
        consumption.retainFinalModelEvidence(1)

        assertEquals(SearchEvidenceStatus.USABLE, consumption.evidenceStatus)
        assertTrue(consumption.usableForTrustSearch)
        assertEquals(1, consumption.sources.size)
        assertEquals("https://www.bok.or.kr/eng/base-rate", consumption.sources.single().url)
    }

    @Test
    fun `eight results are bounded for model while all source metadata remains available`() {
        val results = (1..8).map { index ->
            FusionSearchResult(
                title = "RESULT_SENTINEL_$index",
                source = "Example $index",
                url = "https://example.test/$index",
                snippet = "Evidence $index " + "public Korean search evidence ".repeat(80),
                publishedAt = null,
                queryUsed = "public query",
            )
        }
        val sources = results.mapIndexed { index, result ->
            WebSearchSource(
                title = result.title,
                source = result.source,
                url = result.url,
                snippet = result.snippet,
                publishedAt = null,
                providerType = WebSearchProviderType.FREE_DEFAULT,
                providerDisplayName = "Free",
                searchCategory = null,
                queryUsed = "public query",
                rank = index + 1,
            )
        }
        val response = usableResponse().copy(
            results = results,
            sources = sources,
            quality = WebSearchQuality(true, 0.9, emptyList(), false),
        )
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.COMPLETED, response))
        val content = consumption.finalUserContent("current request", emptyList())

        assertEquals(4, consumption.sources.size)
        assertEquals(8, consumption.retrievedSources.size)
        (1..4).forEach { assertEquals(1, Regex("RESULT_SENTINEL_$it").findAll(content).count()) }
        (5..8).forEach { assertEquals(0, Regex("RESULT_SENTINEL_$it").findAll(content).count()) }
        assertTrue(content.contains("current request"))
        assertEquals("https://example.test/4", consumption.sources.last().url)
        val persisted = consumption.persistedAssistantContent("answer", TRUST)
        assertEquals(4, parseSearchSourcesMetadata(persisted).size)
        assertEquals(8, parseRetrievedSearchResultsMetadata(persisted).size)
    }

    @Test
    fun `final fitting narrows attribution and recalculates quality for the retained prefix`() {
        val results = (1..4).map { index ->
            FusionSearchResult(
                title = "Samsung public query result $index",
                source = "Example $index",
                url = "https://example.test/$index",
                snippet = "Samsung public query evidence $index",
                publishedAt = null,
                queryUsed = "public query",
            )
        }
        val sources = results.mapIndexed { index, result ->
            WebSearchSource(
                title = result.title,
                source = result.source,
                url = result.url,
                snippet = result.snippet,
                publishedAt = null,
                providerType = WebSearchProviderType.FREE_DEFAULT,
                providerDisplayName = "Free",
                searchCategory = null,
                queryUsed = result.queryUsed,
                rank = index + 1,
            )
        }
        val response = usableResponse().copy(
            query = "public query",
            normalizedQuery = "public query",
            results = results,
            sources = sources,
            quality = WebSearchQuality(true, 0.9, emptyList(), false),
        )
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.COMPLETED, response))

        consumption.retainFinalModelEvidence(2)

        assertEquals(2, consumption.sources.size)
        assertEquals(4, consumption.retrievedSources.size)
        assertEquals(SearchEvidenceStatus.USABLE, consumption.evidenceStatus)
        val persisted = consumption.persistedAssistantContent("answer", TRUST)
        assertEquals(2, parseSearchSourcesMetadata(persisted).size)
        assertEquals(4, parseRetrievedSearchResultsMetadata(persisted).size)
    }

    @Test
    fun `persistence carries only admitted provenance`() {
        val weakUnusable = usableResponse().copy(
            results = listOf(
                FusionSearchResult(
                    title = "title only",
                    source = "Example",
                    url = null,
                    snippet = null,
                    publishedAt = null,
                    queryUsed = "q",
                ),
            ),
        )
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.COMPLETED, weakUnusable))

        assertFalse(consumption.actualWebSearchUsed)
        val persisted = consumption.persistedAssistantContent("answer", TRUST)
        assertTrue(persisted.startsWith("answer"))
        assertTrue(parseSearchSourcesMetadata(persisted).isEmpty())
    }

    @Test
    fun `search failure persistence retains retrieval provenance without answer attribution`() {
        val query = "What is the current Bank of Korea base rate?"
        val results = listOf(
            FusionSearchResult(
                title = "Bank of Korea policy outlook",
                source = "bok.or.kr",
                url = "https://www.bok.or.kr/eng/main/main.do",
                snippet = "The Monetary Policy Board discussed inflation and future decisions.",
                publishedAt = null,
                providerDisplayName = "DuckDuckGo HTML",
                rank = 1,
                queryUsed = "Bank of Korea base rate",
            ),
            FusionSearchResult(
                title = "South Korea interest policy",
                source = "example.test",
                url = "https://example.test/rates",
                snippet = "An overview of central-bank policy and market conditions.",
                publishedAt = null,
                providerDisplayName = "DuckDuckGo HTML",
                rank = 2,
                queryUsed = "Bank of Korea base rate",
            ),
        )
        val response = FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.CURRENT_INFO,
            results = results,
            debugMessage = null,
            sources = results.map { result ->
                WebSearchSource(
                    title = result.title,
                    source = result.source,
                    url = result.url,
                    snippet = result.snippet,
                    publishedAt = result.publishedAt,
                    providerType = WebSearchProviderType.FREE_DEFAULT,
                    providerDisplayName = result.providerDisplayName.orEmpty(),
                    searchCategory = null,
                    queryUsed = result.queryUsed,
                    rank = result.rank,
                )
            },
            quality = WebSearchQuality(true, 0.9, emptyList(), false),
        )
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.FALLBACK_COMPLETED, response))

        assertTrue(consumption.actualWebSearchUsed)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, consumption.answerSufficiency)
        assertTrue(consumption.sources.isEmpty())
        assertEquals(2, consumption.retrievedSources.size)

        val persisted = consumption.persistedSearchFailureContent("Could not confirm the current rate.", TRUST)

        assertTrue(parseSearchSourcesMetadata(persisted).isEmpty())
        assertEquals(2, parseRetrievedSearchResultsMetadata(persisted).size)
    }

    @Test
    fun `latest news with only 13 day old evidence abstains and keeps retrieval provenance`() {
        val query = "What is the latest news about Samsung Electronics"
        val result = FusionSearchResult(
            title = "MOIN Secures Multiple Payment Patents from Samsung Electronics",
            source = "벤처스퀘어",
            url = "https://www.venturesquare.net/2026/10/23/samsung-payment-patents",
            snippet = "MOIN secured multiple payment patents from Samsung Electronics.",
            publishedAt = "Fri, 23 Oct 2026 13:44:39 GMT",
            providerDisplayName = "Google News",
            rank = 1,
            queryUsed = "Samsung latest news Electronics 최신",
        )
        val source = WebSearchSource(
            title = result.title,
            source = result.source,
            url = result.url,
            snippet = result.snippet,
            publishedAt = result.publishedAt,
            providerType = WebSearchProviderType.FREE_DEFAULT,
            providerDisplayName = result.providerDisplayName.orEmpty(),
            searchCategory = null,
            queryUsed = result.queryUsed,
            rank = result.rank,
        )
        val response = usableResponse().copy(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.NEWS,
            results = listOf(result),
            sources = listOf(source),
            quality = WebSearchQuality(true, 0.9, emptyList(), false),
        )
        val classicRoute = route(SearchRouteOutcome.COMPLETED, response).copy(
            selectedStrategy = SearchStrategy.LEGACY_RULE_BASED,
            executedStrategy = SearchStrategy.LEGACY_RULE_BASED,
        )
        val consumption = SearchEvidenceConsumption.from(classicRoute, requestedQuery = query)

        assertTrue(consumption.actualWebSearchUsed)
        assertEquals(SearchAnswerSufficiencyStatus.INSUFFICIENT, consumption.answerSufficiency)
        assertNull(consumption.structuredContext)
        assertTrue(consumption.sources.isEmpty())
        assertEquals(listOf(source), consumption.retrievedSources)
        assertTrue(consumption.deterministicAbstentionAnswer.orEmpty().contains("확인하지 못했어요"))

        val persisted = consumption.persistedSearchFailureContent(
            answer = requireNotNull(consumption.deterministicAbstentionAnswer),
            trustRoutingDecision = TRUST,
        )
        assertTrue(persisted.startsWith("검색 결과만으로는 요청한 사실을 확인하지 못했어요."))
        assertTrue(parseSearchSourcesMetadata(persisted).isEmpty())
        assertEquals(1, parseRetrievedSearchResultsMetadata(persisted).size)
    }

    @Test
    fun `intervention receives the same admitted sources as persistence`() {
        val response = usableResponse()
        val consumption = SearchEvidenceConsumption.from(route(SearchRouteOutcome.FALLBACK_COMPLETED, response))

        assertEquals(consumption.sources, consumption.interventionWebSources())
        assertEquals(response.sources, consumption.interventionWebSources())
    }

    @Test
    fun `cancelled route admits nothing even after a successful response`() {
        val consumption = SearchEvidenceConsumption.from(
            route(SearchRouteOutcome.CANCELLED, usableResponse()),
        )

        assertFalse(consumption.actualWebSearchUsed)
        assertTrue(consumption.interventionWebSources().isEmpty())
    }

    @Test
    fun `F13 merged agentic evidence keeps the original user intent and per-result queries`() = runTest {
        val model = ScriptedModel(
            SearchAgentProtocol.NATIVE_TOOL_CALLS,
            SearchAgentReply.NativeToolCalls(
                listOf(
                    AiToolCall(
                        "call-multi",
                        "web_search",
                        """{"queries":["first public query","second public query"]}""",
                    ),
                ),
                finishReason = "tool_calls",
            ),
            SearchAgentReply.Finished("finished"),
        )
        val harness = AgenticSearchHarness(
            model,
            AgenticExactSearchTool { query, _, _ -> evidence(query) },
            object : AgenticSearchQueryAuthorizer {
                override fun authorize(requestId: String, query: String) =
                    AgenticQueryAuthorization.Authorized(query)
            },
        )

        val result = harness.execute(
            AgenticSearchRequest(
                requestId = "request-f13",
                originalUserQuery = "user typed question",
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
                ownerStillActive = { true },
                searchPermissionStillValid = { true },
            ),
        )

        assertEquals(AgenticSearchOutcome.COMPLETED, result.outcome)
        val merged = requireNotNull(result.evidence)
        assertEquals("user typed question", merged.query)
        assertEquals("user typed question", merged.normalizedQuery)
        assertEquals(SearchIntent.GENERAL, merged.intent)
        assertNotNull(merged.quality)
        assertNull(merged.plan)
        assertEquals(
            setOf("first public query", "second public query"),
            merged.results.map { it.queryUsed }.toSet(),
        )
    }

    private fun route(
        outcome: SearchRouteOutcome,
        response: FusionSearchResponse?,
    ) = SearchStrategyRouteResult(
        outcome = outcome,
        response = response,
        selectedStrategy = SearchStrategy.AGENT_HARNESS,
        executedStrategy = SearchStrategy.AGENT_HARNESS,
    )

    private fun usableResponse() = FusionSearchResponse(
        query = "public query",
        normalizedQuery = "public query",
        intent = SearchIntent.GENERAL,
        results = listOf(
            FusionSearchResult(
                title = "Usable result",
                source = "Example",
                url = "https://example.test/usable",
                snippet = "A usable public snippet.",
                publishedAt = null,
                providerDisplayName = "Free",
                rank = 1,
                queryUsed = "public query",
            ),
        ),
        debugMessage = null,
        sources = listOf(
            WebSearchSource(
                title = "Usable result",
                source = "Example",
                url = "https://example.test/usable",
                snippet = "A usable public snippet.",
                publishedAt = null,
                providerType = WebSearchProviderType.FREE_DEFAULT,
                providerDisplayName = "Free",
                searchCategory = null,
                queryUsed = "public query",
                rank = 1,
            ),
        ),
    )

    private fun evidence(query: String): FusionSearchResponse {
        val result = FusionSearchResult(
            title = "Evidence for $query",
            source = "Example Source",
            url = "https://example.test/$query",
            snippet = "A public source snippet.",
            publishedAt = null,
            queryUsed = query,
        )
        return FusionSearchResponse(
            query = query,
            normalizedQuery = query,
            intent = SearchIntent.GENERAL,
            results = listOf(result),
            debugMessage = null,
            sources = listOf(
                WebSearchSource(
                    title = result.title,
                    source = result.source,
                    url = result.url,
                    snippet = result.snippet,
                    publishedAt = null,
                    providerType = WebSearchProviderType.FREE_DEFAULT,
                    providerDisplayName = "Free",
                    searchCategory = null,
                    queryUsed = query,
                    rank = 1,
                ),
            ),
        )
    }

    private class ScriptedModel(
        override val protocol: SearchAgentProtocol,
        vararg replies: SearchAgentReply,
    ) : SearchAgentModel {
        private val replies = replies.toMutableList()

        override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
            replies.removeAt(0)
    }

    private companion object {
        const val WEB_SECTION_MARKER = "Web search policy"
        const val USER_WEB_MARKER = "[FUSION_WEB_SEARCH_EVIDENCE]"
        val TRUST = TrustRoutingDecision(
            TrustRoute.SEARCH,
            TrustRoutingReason.CURRENT_INFORMATION_REQUIRED,
            emptySet(),
        )
    }
}
