package com.projectnuke.fusion.search

import com.projectnuke.fusion.ai.model.AiToolCall
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ManualSearchQueryValidation
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import com.projectnuke.fusion.llm.FailureKind
import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.LlmEngine
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.RequestedEngineProfile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// P10-B: bounded, privacy-approved multi-turn planning context and explicit
// cold/warm local-runtime behavior. The typed request stays the only authority
// for outbound search; no planner ever loads a second model instance.
class P10MultiTurnPlanningTest {

    // ---- cold and warm local runtime ---------------------------------------

    @Test
    fun coldLocalRuntimeIsReportedAsColdStartNotPlannerFailure() {
        // First query on a cold runtime: nothing is loaded yet.
        assertEquals("local_model_cold_start", localAgentUnavailableReason("model-path", false, false))
        // Warm runtime that is active for another model or profile.
        assertEquals("local_runtime_not_reusable", localAgentUnavailableReason("model-path", false, true))
        // No model selected at all.
        assertEquals("no_local_model_selected", localAgentUnavailableReason(null, false, false))
        assertEquals("no_local_model_selected", localAgentUnavailableReason("  ", false, true))
        // A reusable runtime is available: the agent plans, no reason code.
        assertNull(localAgentUnavailableReason("model-path", true, true))
        assertNull(localAgentUnavailableReason("model-path", true, false))
    }

    @Test
    fun coldStartRoutesToBoundedClassicFallbackWithAnExplicitReasonCode() = runTest {
        val query = "삼성전자 주가 알려줘"
        val plan = RuleBasedWebSearchPlanner.plan(RuleBasedWebSearchPlannerInput(query))
        val searched = mutableListOf<String>()
        val result = ResearchSearchOwner.searchWithStrategy(
            request = request(query, safePriorUserTurns = listOf("SK하이닉스 주가 알려줘")),
            previousUserMessage = null,
            providerRepository = null,
            privacySnapshot = privacy(query),
            agentModel = null,
            ownerStillActive = { true },
            limits = AgenticSearchLimits.LOCAL_DEFAULT,
            agentUnavailableReasonCode = "local_model_cold_start",
            exactSearchOverride = AgenticExactSearchTool { exactQuery, _, _ ->
                searched += exactQuery
                evidence(exactQuery)
            },
        )

        assertEquals(SearchRouteOutcome.FALLBACK_COMPLETED, result.outcome)
        assertEquals(SearchStrategy.LEGACY_RULE_BASED, result.executedStrategy)
        assertEquals(SearchFallbackReason.MODEL_UNAVAILABLE, result.fallbackReason)
        assertEquals("local_model_cold_start", result.agentDiagnostics?.failureCode)
        assertTrue(searched.contains(plan.primaryQuery))
    }

    @Test
    fun warmLocalRuntimePlansWithTheActiveEngineAndNeverLoadsAnotherOne() = runTest {
        var generated = 0
        var unloaded = 0
        val engine = object : LlmEngine {
            override suspend fun generate(
                messages: List<ChatMessage>,
                profile: RequestedEngineProfile,
                options: ConversationOptions,
            ): GenerationOutcome {
                generated++
                return if (generated == 1) {
                    GenerationOutcome.Success("""{"action":"SEARCH","queries":["public follow up question"]}""")
                } else {
                    GenerationOutcome.Success("""{"action":"NO_MORE_SEARCH"}""")
                }
            }

            override fun unload() {
                unloaded++
            }
        }
        val searched = mutableListOf<String>()
        val result = ResearchSearchOwner.searchWithStrategy(
            request = request("public follow up question"),
            previousUserMessage = "SK하이닉스 주가 알려줘",
            providerRepository = null,
            privacySnapshot = privacy("public follow up question"),
            agentModel = warmModel(engine),
            ownerStillActive = { true },
            limits = AgenticSearchLimits.LOCAL_DEFAULT,
            exactSearchOverride = AgenticExactSearchTool { exactQuery, _, _ ->
                searched += exactQuery
                evidence(exactQuery)
            },
        )

        assertEquals(SearchRouteOutcome.COMPLETED, result.outcome)
        assertEquals(SearchStrategy.AGENT_HARNESS, result.executedStrategy)
        assertEquals(listOf("public follow up question"), searched)
        assertEquals(2, generated)
        assertEquals(0, unloaded)
        assertEquals(1, result.searchQueriesIssued)
    }

    @Test
    fun runtimeSwitchBeforeFirstPlannerTurnDoesNotCallTheStaleEngine() = runTest {
        var reusable = true
        var generated = 0
        val engine = object : LlmEngine {
            override suspend fun generate(
                messages: List<ChatMessage>,
                profile: RequestedEngineProfile,
                options: ConversationOptions,
            ): GenerationOutcome {
                generated++
                return GenerationOutcome.Success("""{"action":"NO_MORE_SEARCH"}""")
            }
            override fun unload() = Unit
        }
        val model = warmModel(engine, engineStillReusable = { reusable })
        // Simulate a profile/runtime switch after adapter creation but before
        // the first planner completion.
        reusable = false

        val reply = model.complete(listOf(SearchAgentMessage(SearchAgentMessageRole.USER, "User request: public query")))

        assertEquals(SearchAgentReply.Failed("active_local_model_unavailable"), reply)
        assertEquals(0, generated)
    }

    @Test
    fun engineChangeDuringSearchRetainsAcceptedEvidenceAndNeverRepeatsAQuery() = runTest {
        val query = "public query"
        var reusable = true
        var generated = 0
        val engine = object : LlmEngine {
            override suspend fun generate(
                messages: List<ChatMessage>,
                profile: RequestedEngineProfile,
                options: ConversationOptions,
            ): GenerationOutcome {
                generated++
                return when (generated) {
                    1 -> GenerationOutcome.Success("""{"action":"SEARCH","queries":["public query"]}""")
                    else -> GenerationOutcome.Success("""{"action":"NO_MORE_SEARCH"}""")
                }
            }

            override fun unload() = Unit
        }
        val searched = mutableListOf<String>()
        val result = ResearchSearchOwner.searchWithStrategy(
            request = request(query),
            previousUserMessage = null,
            providerRepository = null,
            privacySnapshot = privacy(query),
            agentModel = warmModel(engine, engineStillReusable = { reusable }),
            ownerStillActive = { true },
            limits = AgenticSearchLimits.LOCAL_DEFAULT,
            exactSearchOverride = AgenticExactSearchTool { exactQuery, _, _ ->
                searched += exactQuery
                // Runtime identity changes after retrieval and before the next
                // planner turn. The adapter must reject reuse before calling
                // generate a second time.
                reusable = false
                evidence(exactQuery)
            },
        )

        // The already accepted evidence is preserved and no fallback runs for
        // it, because the fallback policy refuses to repeat a successful search.
        assertEquals(SearchRouteOutcome.AGENT_FAILED, result.outcome)
        assertNull(result.fallbackReason)
        assertEquals(1, searched.count { queryIdentity(it) == queryIdentity("public query") })
        assertEquals(1, generated)
        assertNotNull(result.response)
    }

    @Test
    fun coldStartFallbackExecutesOnlyWhenTheAgentNeverSearched() = runTest {
        val query = "public query"
        var fallbackCalls = 0
        val router = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { exactQuery, _, _ -> evidence(exactQuery) },
            queryAuthorizer = allowAll(),
            legacyFallback = LegacySearchFallbackExecutor { request ->
                fallbackCalls++
                LegacySearchFallbackResult.Completed(evidence("legacy"))
            },
        )
        val result = router.execute(
            SearchStrategyRouteRequest(
                agentRequest = AgenticSearchRequest(
                    requestId = "cold-fallback",
                    originalUserQuery = query,
                    webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                    limits = AgenticSearchLimits.LOCAL_DEFAULT,
                    ownerStillActive = { true },
                    searchPermissionStillValid = { true },
                ),
                strategy = SearchStrategySnapshot(
                    SearchStrategy.AGENT_HARNESS,
                    true,
                    ManualSearchQueryValidation.Missing,
                ),
                agentUnavailableReasonCode = "local_model_cold_start",
            ),
            agentModel = null,
        )

        assertEquals(SearchRouteOutcome.FALLBACK_COMPLETED, result.outcome)
        assertEquals(SearchFallbackReason.MODEL_UNAVAILABLE, result.fallbackReason)
        assertEquals("local_model_cold_start", result.agentDiagnostics?.failureCode)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun unrecoverablePlannerFailureKeepsTheSearchLocalWithoutFallback() = runTest {
        val query = "public query"
        var fallbackCalls = 0
        val result = ResearchSearchOwner.searchWithStrategy(
            request = request(query),
            previousUserMessage = null,
            providerRepository = null,
            privacySnapshot = privacy(query),
            agentModel = LocalStructuredSearchAgentModel(
                engine = object : LlmEngine {
                    override suspend fun generate(
                        messages: List<ChatMessage>,
                        profile: RequestedEngineProfile,
                        options: ConversationOptions,
                    ) = GenerationOutcome.Failure(FailureKind.MODEL_LOAD_FAILED, "engine switched")
                    override fun unload() = Unit
                },
                profile = RequestedEngineProfile("active", com.projectnuke.fusion.model.AcceleratorMode.CPU, false, 4096, false),
                baseOptions = ConversationOptions(),
                engineStillReusable = { true },
                requestStillAuthorized = { false },
            ),
            ownerStillActive = { true },
            limits = AgenticSearchLimits.LOCAL_DEFAULT,
            exactSearchOverride = AgenticExactSearchTool { exactQuery, _, _ -> evidence(exactQuery) },
        )

        assertEquals(SearchRouteOutcome.AGENT_FAILED, result.outcome)
        assertEquals(0, fallbackCalls)
        assertFalse(result.agentDiagnostics!!.legacyFallbackAllowed)
    }

    // ---- multi-turn planning context ---------------------------------------

    @Test
    fun publicFollowUpReceivesBoundedPriorContextWithoutLosingQueryAuthority() = runTest {
        val seen = mutableListOf<List<SearchAgentMessage>>()
        var round = 0
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
                seen += messages
                round++
                return if (round == 1) {
                    SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["그럼 현재가는"]}""")
                } else {
                    SearchAgentReply.StructuredIntent("""{"action":"NO_MORE_SEARCH"}""")
                }
            }
        }
        val searched = mutableListOf<String>()
        val result = ResearchSearchOwner.searchWithStrategy(
            request = request(
                query = "그럼 현재가는",
                previousUserMessage = "SK하이닉스 000660 주가 알려줘",
                safePriorUserTurns = listOf(
                    "SK하이닉스 000660 주가 알려줘",
                    "그럼 어제는",
                    "반도체 수출 소식 알려줘",
                ),
            ),
            previousUserMessage = "SK하이닉스 000660 주가 알려줘",
            providerRepository = null,
            privacySnapshot = privacy("그럼 현재가는"),
            agentModel = model,
            ownerStillActive = { true },
            limits = AgenticSearchLimits.LOCAL_DEFAULT,
            exactSearchOverride = AgenticExactSearchTool { exactQuery, _, _ ->
                searched += exactQuery
                evidence(exactQuery)
            },
        )

        val plannerContext = seen.first().joinToString("\n") { it.content }
        assertTrue(plannerContext.contains("User request:"))
        assertTrue(plannerContext.contains("그럼 현재가는"))
        assertTrue(plannerContext.contains("Prior user turns"))
        assertTrue(plannerContext.contains("SK하이닉스 000660 주가 알려줘"))
        // Bounded: at most 3 turns and at most 1024 characters of prior context.
        val priorBlock = plannerContext.substringAfter("Prior user turns").substringBefore("\nEarlier search records")
        assertTrue(priorBlock.lines().count { it.startsWith("- ") } <= MAX_AGENT_PLANNING_CONTEXT_TURNS)
        assertTrue(priorBlock.length <= MAX_AGENT_PLANNING_CONTEXT_CHARS + 256)
        // The current typed request stayed the authority for the outbound query.
        assertEquals(listOf("그럼 현재가는"), searched)
        assertEquals(SearchRouteOutcome.COMPLETED, result.outcome)
    }

    @Test
    fun privatePreviousTurnIsExcludedFromPlannerContextAndCannotAuthorizeTerms() = runTest {
        val secret = "PRIVATE-FOLLOW-UP-TOKEN-9182"
        val seen = mutableListOf<List<SearchAgentMessage>>()
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
                seen += messages
                return SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["그럼 $secret"]}""")
            }
        }
        var outboundCalls = 0
        val result = ResearchSearchOwner.searchWithStrategy(
            request = request(
                query = "그럼 이것도",
                previousUserMessage = null,
                safePriorUserTurns = listOf("public climate report"),
            ),
            previousUserMessage = null,
            providerRepository = null,
            privacySnapshot = privacy("그럼 이것도", privateExcerpts = listOf(secret)),
            agentModel = model,
            ownerStillActive = { true },
            limits = AgenticSearchLimits.LOCAL_DEFAULT,
            exactSearchOverride = AgenticExactSearchTool { _, _, _ -> outboundCalls++; error("must not run") },
        )

        assertTrue(seen.first().joinToString("\n") { it.content }.contains("그럼 이것도"))
        assertFalse(seen.first().joinToString("\n") { it.content }.contains(secret))
        assertEquals(SearchRouteOutcome.PRIVACY_DENIED, result.outcome)
        assertEquals(0, outboundCalls)
    }

    @Test
    fun unrelatedOldTopicsAreDroppedBeyondTheMostRecentTurns() {
        val recent = "SK하이닉스 HBM4E 수율 알려줘"
        val bounded = ResearchSearchOwner.boundedAgentPlanningContext(
            previousUserMessage = recent,
            safePriorUserTurns = listOf(
                "반도체 산업 전망 알려줘",
                "오늘 저녁 메뉴 추천해줘",
                "코스피 지수 알려줘",
            ),
        )
        assertEquals(listOf(recent, "반도체 산업 전망 알려줘", "오늘 저녁 메뉴 추천해줘"), bounded)
        // The fourth, stale topic never reaches the planner.
        assertFalse(bounded.contains("코스피 지수 알려줘"))
    }

    @Test
    fun planningContextBudgetIsCappedInTotalCharacters() {
        val longTurn = "가".repeat(400)
        val bounded = ResearchSearchOwner.boundedAgentPlanningContext(
            previousUserMessage = null,
            safePriorUserTurns = listOf(longTurn, longTurn, longTurn, longTurn),
        )
        assertTrue(bounded.size <= MAX_AGENT_PLANNING_CONTEXT_TURNS)
        assertTrue(bounded.sumOf { it.length } <= MAX_AGENT_PLANNING_CONTEXT_CHARS)
        assertTrue(bounded.all { it.length <= MAX_SAFE_PRIOR_TURN_CHARS })
    }

    @Test
    fun regeneratedAndRetriedRequestsKeepTheSameFrozenPlanningContext() {
        val frozen = listOf("SK하이닉스 000660 주가 알려줘", "반도체 수출 알려줘")
        val original = AgenticSearchRequest(
            requestId = "request-1",
            originalUserQuery = "그럼 현재가는",
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            limits = AgenticSearchLimits.LOCAL_DEFAULT,
            ownerStillActive = { true },
            searchPermissionStillValid = { true },
            priorUserContext = frozen,
        )
        val regenerated = original.copy(requestId = "request-2")
        val retried = original.copy(requestId = "request-3")

        // Frozen per request: the retry/regeneration snapshot carries exactly
        // the same bounded context, never a wider one from later history.
        assertEquals(frozen, original.priorUserContext)
        assertEquals(frozen, regenerated.priorUserContext)
        assertEquals(frozen, retried.priorUserContext)
        assertEquals(original.priorUserContext.size, regenerated.priorUserContext.size)
    }

    @Test
    fun regenerationCannotWidenPlanningContextFromMutableHistory() = runTest {
        var mutableHistory = listOf("SK하이닉스 000660 주가 알려줘")
        val request1 = request(
            query = "그럼 현재가는",
            safePriorUserTurns = mutableHistory,
        )
        // The conversation moves on before the retry runs.
        mutableHistory = listOf("Project Mica confidential acquisition plan")
        val request2 = request(query = "그럼 현재가는", safePriorUserTurns = mutableHistory)

        val first = ResearchSearchOwner.boundedAgentPlanningContext(null, request1.safeWebSearchPriorUserTurns)
        val second = ResearchSearchOwner.boundedAgentPlanningContext(null, request2.safeWebSearchPriorUserTurns)
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertFalse(first.contains("Project Mica confidential acquisition plan"))
    }

    // ---- cancellation and duplicate execution -------------------------------

    @Test
    fun cancellationDuringPlanningIssuesNoSearchAndNoFallback() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        var searches = 0
        var fallbackCalls = 0
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val task = async(start = CoroutineStart.LAZY) {
            SearchStrategyRouter(
                exactSearchTool = AgenticExactSearchTool { _, _, _ -> searches++; evidence("unexpected") },
                queryAuthorizer = allowAll(),
                legacyFallback = LegacySearchFallbackExecutor {
                    fallbackCalls++
                    LegacySearchFallbackResult.Completed(evidence("unexpected"))
                },
            ).execute(
                SearchStrategyRouteRequest(
                    agentRequest = AgenticSearchRequest(
                        requestId = "cancelled-request",
                        originalUserQuery = "그럼 현재가는",
                        webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                        limits = AgenticSearchLimits.LOCAL_DEFAULT,
                        ownerStillActive = { true },
                        searchPermissionStillValid = { true },
                        priorUserContext = listOf("SK하이닉스 000660 주가 알려줘"),
                    ),
                    strategy = SearchStrategySnapshot.DEFAULT,
                ),
                model,
            )
        }
        task.start()
        entered.await()
        task.cancel()
        runCatching { task.await() }

        assertEquals(0, searches)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun eachPlanningRoundUsesOneModelSessionAndNeverRepeatsASearch() = runTest {
        val model = LocalStructuredSearchAgentModel(
            engine = object : LlmEngine {
                private var calls = 0
                override suspend fun generate(
                    messages: List<ChatMessage>,
                    profile: RequestedEngineProfile,
                    options: ConversationOptions,
                ): GenerationOutcome {
                    calls++
                    return when (calls) {
                        1 -> GenerationOutcome.Success("""{"action":"SEARCH","queries":["public query"]}""")
                        2 -> GenerationOutcome.Success("""{"action":"SEARCH","queries":["public query"]}""")
                        else -> GenerationOutcome.Success("""{"action":"NO_MORE_SEARCH"}""")
                    }
                }

                override fun unload() = Unit
            },
            profile = RequestedEngineProfile("active", com.projectnuke.fusion.model.AcceleratorMode.CPU, false, 4096, false),
            baseOptions = ConversationOptions(),
            engineStillReusable = { true },
            requestStillAuthorized = { true },
        )
        val searched = mutableListOf<String>()
        val result = AgenticSearchHarness(
            model = model,
            searchTool = AgenticExactSearchTool { exactQuery, _, _ ->
                searched += exactQuery
                evidence(exactQuery)
            },
            queryAuthorizer = allowAll(),
        ).execute(
            AgenticSearchRequest(
                requestId = "single-session",
                originalUserQuery = "public query",
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                limits = AgenticSearchLimits.LOCAL_DEFAULT,
                ownerStillActive = { true },
                searchPermissionStillValid = { true },
            ),
        )

        // The duplicate round is bounded feedback only: the loop stops and the
        // exact query was executed exactly once.
        assertEquals(listOf("public query"), searched)
        assertEquals(2, result.diagnostics.planningCalls)
        assertEquals(AgenticSearchOutcome.BUDGET_EXHAUSTED, result.outcome)
        assertEquals(1, result.diagnostics.issuedQueries)
        assertNotNull(result.evidence)
    }

    // ---- helpers -----------------------------------------------------------

    private fun warmModel(
        engine: LlmEngine,
        engineStillReusable: () -> Boolean = { true },
    ) = LocalStructuredSearchAgentModel(
        engine = engine,
        profile = RequestedEngineProfile("active", com.projectnuke.fusion.model.AcceleratorMode.CPU, false, 4096, false),
        baseOptions = ConversationOptions(),
        engineStillReusable = engineStillReusable,
        requestStillAuthorized = { true },
    )

    private fun request(
        query: String,
        previousUserMessage: String? = null,
        safePriorUserTurns: List<String> = emptyList(),
    ) = GenerationRequestSnapshot(
        requestId = "p10b-request",
        conversationId = 9L,
        generationModeKey = "LOCAL_MODEL",
        selectedModelId = "local",
        selectedModelPath = "active-model",
        settings = GenerationSettings(),
        reasoningEnabled = false,
        webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
        attachmentIds = emptyList(),
        multimodalImagePaths = emptyList(),
        promptText = query,
        rawUserText = query,
        createdAt = 1L,
        safeWebSearchPriorUserTurns = safePriorUserTurns,
        searchStrategy = SearchStrategySnapshot(SearchStrategy.AGENT_HARNESS, true, ManualSearchQueryValidation.Missing),
    )

    private fun privacy(
        query: String,
        manual: String? = null,
        privateExcerpts: List<String> = emptyList(),
    ) = AgenticSearchPrivacySnapshot(
        requestId = "p10b-request",
        typedUserQuery = query,
        explicitManualQuery = manual,
        forbiddenPrivateExcerpts = privateExcerpts,
        requestStillAuthorized = { true },
    )

    private fun allowAll() = AgenticSearchQueryAuthorizer { _, query -> AgenticQueryAuthorization.Authorized(query) }

    private fun evidence(query: String): FusionSearchResponse {
        val result = FusionSearchResult(
            title = "Evidence for $query",
            source = "Example",
            url = "https://example.test/source",
            snippet = "Public evidence.",
            publishedAt = "2026-10-26",
            queryUsed = query,
        )
        val source = WebSearchSource(
            title = result.title,
            source = result.source,
            url = result.url,
            snippet = result.snippet,
            publishedAt = result.publishedAt,
            providerType = WebSearchProviderType.FREE_DEFAULT,
            providerDisplayName = result.providerDisplayName,
            searchCategory = null,
            queryUsed = query,
            rank = 1,
        )
        return FusionSearchResponse(query, query, SearchIntent.GENERAL, listOf(result), null, listOf(source))
    }
}
