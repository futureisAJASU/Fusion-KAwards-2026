package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import com.projectnuke.fusion.privacy.DataEgressPrivateTextLedger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestScopedAgenticSearchPrivacyTest {
    @Test
    fun exactTypedQueryAndDeterministicNormalizationAreAllowed() {
        val authorizer = authorizer("삼성전자 주가 알려줘")
        assertEquals(
            AgenticQueryMaterialKind.EXPLICIT_TYPED,
            (authorizer.authorize("삼성전자 주가 알려줘", "삼성전자 주가 알려줘") as AgenticQueryAuthorization.Authorized).materialKind,
        )
        assertEquals(
            AgenticQueryMaterialKind.DETERMINISTIC_NORMALIZATION,
            (authorizer.authorize("삼성전자 주가 알려줘", "삼성전자 주가") as AgenticQueryAuthorization.Authorized).materialKind,
        )
    }

    @Test
    fun trustedSamsungMappingPermits005930AndKrxButRejectsUnverifiedTicker() {
        val query = "삼성전자 주가 알려줘"
        val authorizer = authorizer(query)
        val approved = authorizer.authorize(query, "삼성전자 005930 현재 주가 KRX")
        assertEquals(AgenticQueryMaterialKind.TRUSTED_PUBLIC_ENRICHMENT, (approved as AgenticQueryAuthorization.Authorized).materialKind)
        assertEquals("삼성전자 005930 현재 주가 KRX", approved.exactQuery)
        assertEquals("unverified_query_expansion", (authorizer.authorize(query, "삼성전자 005939 현재 주가 KRX") as AgenticQueryAuthorization.Denied).reasonCode)
    }

    @Test
    fun exactCapturedLegacyPlanMayUsePreviouslyApprovedSafeUserContext() {
        val query = "그 영화의 개봉일 알려줘"
        val legacyQuery = RuleBasedWebSearchPlanner.plan(
            RuleBasedWebSearchPlannerInput(
                currentUserInput = query,
                previousUserMessage = "Dune Part Two",
                safePriorUserTurns = listOf("Dune Part Two release date"),
            ),
        ).primaryQuery
        val authorizer = authorizer(query, approvedLegacyQueries = listOf(legacyQuery))

        assertEquals(
            AgenticQueryMaterialKind.DETERMINISTIC_NORMALIZATION,
            (authorizer.authorize(query, legacyQuery) as AgenticQueryAuthorization.Authorized).materialKind,
        )
        assertEquals(
            "unverified_query_expansion",
            (authorizer.authorize(query, "$legacyQuery private contract") as AgenticQueryAuthorization.Denied).reasonCode,
        )
    }

    @Test
    fun explicitManualQueryIsAuthorizedOnlyForItsExactCapturedValue() {
        val query = "What should I search for?"
        val manual = "Samsung Electronics 005930 current price KRX"
        val authorizer = authorizer(query, manualQuery = manual)

        assertEquals(
            AgenticQueryMaterialKind.EXPLICIT_TYPED,
            (authorizer.authorize(manual, manual) as AgenticQueryAuthorization.Authorized).materialKind,
        )
        assertEquals(
            "request_query_mismatch",
            (authorizer.authorize("changed manual query", "Samsung current price") as AgenticQueryAuthorization.Denied).reasonCode,
        )
    }

    @Test
    fun unverifiedPublicEnrichmentIsRejected() {
        val query = "삼성전자 주가 알려줘"
        val decision = authorizer(query).authorize(query, "삼성전자 주가 실적 전망 2027")
        assertEquals("unverified_query_expansion", (decision as AgenticQueryAuthorization.Denied).reasonCode)
    }

    @Test
    fun privatePassageInjectedIntoTheQueryIsRejected() {
        val query = "Summarize public battery news"
        val secret = "Project Mica confidential acquisition plan"
        val decision = authorizer(query, privateExcerpts = listOf(secret)).authorize(query, "$query $secret")
        assertEquals("private_or_sensitive_text", (decision as AgenticQueryAuthorization.Denied).reasonCode)
    }

    @Test
    fun privatePriorConversationCannotAuthorizeNewSearchTerms() {
        val query = "What is the public weather forecast?"
        val secret = "my private diagnosis is long covid"
        val authorizer = authorizer(query, privateExcerpts = listOf(secret), privateHistory = true)
        assertEquals("private_or_sensitive_text", (authorizer.authorize(query, "weather forecast $secret") as AgenticQueryAuthorization.Denied).reasonCode)
        assertEquals("unverified_query_expansion", (authorizer.authorize(query, "weather forecast diagnosis") as AgenticQueryAuthorization.Denied).reasonCode)
    }

    @Test
    fun attachmentFilenameAndLocalDocumentTaintAreRejected() {
        val query = "Samsung public stock price"
        val filename = "Confidential-Q3-Acquisition.pdf"
        val authorizer = authorizer(query, fileNames = listOf(filename), attachments = true, localRag = true)
        assertEquals("private_or_sensitive_text", (authorizer.authorize(query, "$query $filename") as AgenticQueryAuthorization.Denied).reasonCode)
        assertEquals("unverified_query_expansion", (authorizer.authorize(query, "$query hidden-project-code") as AgenticQueryAuthorization.Denied).reasonCode)
    }

    @Test
    fun projectAndSelectionTaintCannotContributeTerms() {
        val query = "Public material prices"
        val authorizer = authorizer(query, project = true, selection = true)
        assertTrue(authorizer.authorize(query, "public material prices").isAuthorized())
        assertEquals("unverified_query_expansion", (authorizer.authorize(query, "public material prices Redwood design brief") as AgenticQueryAuthorization.Denied).reasonCode)
    }

    @Test
    fun globalPrivateTextLedgerIsCheckedAtAuthorizationTime() {
        val query = "public weather in Seoul"
        val excerpt = "ledger-only confidential family settlement"
        val originalEntries = DataEgressPrivateTextLedger.currentTexts()
        try {
            DataEgressPrivateTextLedger.register(listOf(excerpt))
            val decision = authorizer(query).authorize(query, "$query $excerpt")
            assertEquals("private_or_sensitive_text", (decision as AgenticQueryAuthorization.Denied).reasonCode)
        } finally {
            DataEgressPrivateTextLedger.clear()
            DataEgressPrivateTextLedger.register(originalEntries)
        }
    }

    @Test
    fun authorizationIsBoundToTheCapturedRequestAndRevocation() {
        val authorizer = RequestScopedAgenticSearchQueryAuthorizer(
            AgenticSearchPrivacySnapshot(
                requestId = "request-a",
                typedUserQuery = "public request A",
                requestStillAuthorized = { false },
            ),
        )
        assertEquals("authorization_revoked", (authorizer.authorize("public request A", "public request A") as AgenticQueryAuthorization.Denied).reasonCode)

        val otherRequest = authorizer("public request A")
        assertEquals("request_query_mismatch", (otherRequest.authorize("public request B", "public request A") as AgenticQueryAuthorization.Denied).reasonCode)
    }

    @Test
    fun missingRequestAuthorizationFailsClosed() {
        val authorizer = RequestScopedAgenticSearchQueryAuthorizer(
            AgenticSearchPrivacySnapshot(requestId = "request-without-admission", typedUserQuery = "public query"),
        )
        assertEquals(
            "authorization_revoked",
            (authorizer.authorize("public query", "public query") as AgenticQueryAuthorization.Denied).reasonCode,
        )
    }

    @Test
    fun concurrentSnapshotsDoNotShareQueryAuthority() {
        val first = authorizer("public query alpha")
        val second = authorizer("public query beta")
        assertTrue(first.authorize("public query alpha", "public query alpha").isAuthorized())
        assertEquals("request_query_mismatch", (first.authorize("public query beta", "public query beta") as AgenticQueryAuthorization.Denied).reasonCode)
        assertTrue(second.authorize("public query beta", "public query beta").isAuthorized())
    }

    @Test
    fun noUnauthorizedProposalReachesSearchOrFallbackTransport() = runTest {
        var outboundCalls = 0
        val query = "public project update"
        val authorizer = authorizer(query, privateExcerpts = listOf("Redwood unreleased acquisition"))
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>) =
                SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["public project update Redwood unreleased acquisition"]}""")
        }
        val exactSearch = AgenticExactSearchTool { _, _, _ -> outboundCalls++; error("unauthorized egress") }
        val fallback = LegacySearchFallbackExecutor { outboundCalls++; error("fallback must not bypass privacy") }
        val router = SearchStrategyRouter(exactSearch, authorizer, fallback)
        val request = SearchStrategyRouteRequest(
            agentRequest = AgenticSearchRequest(
                requestId = "privacy-request",
                originalUserQuery = query,
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
                ownerStillActive = { true },
                searchPermissionStillValid = { true },
            ),
            strategy = SearchStrategySnapshot.DEFAULT,
        )

        val result = router.execute(request, model)

        assertEquals(SearchRouteOutcome.PRIVACY_DENIED, result.outcome)
        assertEquals(0, outboundCalls)
    }

    @Test
    fun benignUnverifiedModelExpansionUsesOnlyIndependentlyAuthorizedLegacyFallback() = runTest {
        val query = "오늘 주요 뉴스를 검색해서 알려줘"
        val proposed = "$query 삼성 주가"
        val authorizer = authorizer(query)
        assertEquals(
            "unverified_query_expansion",
            (authorizer.authorize(query, proposed) as AgenticQueryAuthorization.Denied).reasonCode,
        )
        val outbound = mutableListOf<String>()
        val exact = AgenticExactSearchTool { candidate, _, _ ->
            outbound += candidate
            response(candidate)
        }
        val router = SearchStrategyRouter(exact, authorizer, RuleBasedLegacySearchFallback(exact, authorizer))
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>) =
                SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["$proposed"]}""")
        }
        val result = router.execute(
            SearchStrategyRouteRequest(
                agentRequest = AgenticSearchRequest(
                    requestId = "benign-expansion",
                    originalUserQuery = query,
                    webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                    limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
                    ownerStillActive = { true },
                    searchPermissionStillValid = { true },
                ),
                strategy = SearchStrategySnapshot.DEFAULT,
            ),
            model,
        )

        assertEquals(SearchRouteOutcome.FALLBACK_COMPLETED, result.outcome)
        assertEquals(SearchFallbackReason.UNVERIFIED_QUERY_EXPANSION, result.fallbackReason)
        assertEquals(AgenticSearchOutcome.UNVERIFIED_QUERY_EXPANSION, result.agentDiagnostics?.outcome)
        assertEquals(SearchStrategy.LEGACY_RULE_BASED, result.executedStrategy)
        assertTrue(outbound.isNotEmpty())
        assertFalse(proposed in outbound)
        assertTrue(outbound.all { authorizer.authorize(query, it) is AgenticQueryAuthorization.Authorized })
    }

    @Test
    fun hallucinatedSensitiveIdentifiersAndPrivateExcerptNeverReachFallbackOrTransport() = runTest {
        for (sensitive in listOf("010-1234-5678", "900101-1234567", "person@example.com", "private attachment excerpt")) {
            val query = "오늘 주요 뉴스를 검색해서 알려줘"
            val authorizer = authorizer(query, privateExcerpts = listOf("private attachment excerpt"))
            val outbound = mutableListOf<String>()
            val exact = AgenticExactSearchTool { candidate, _, _ -> outbound += candidate; response(candidate) }
            var fallbackCalls = 0
            val router = SearchStrategyRouter(exact, authorizer, LegacySearchFallbackExecutor {
                fallbackCalls++
                LegacySearchFallbackResult.Completed(null)
            })
            val model = object : SearchAgentModel {
                override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
                override suspend fun complete(messages: List<SearchAgentMessage>) = SearchAgentReply.StructuredIntent(
                    """{"action":"SEARCH","queries":["$query $sensitive"]}""",
                )
            }
            val result = router.execute(
                SearchStrategyRouteRequest(
                    agentRequest = AgenticSearchRequest("privacy-$sensitive", query, GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                        AgenticSearchLimits.EXTERNAL_DEFAULT, ownerStillActive = { true }, searchPermissionStillValid = { true }),
                    strategy = SearchStrategySnapshot.DEFAULT,
                ),
                model,
            )
            assertEquals(SearchRouteOutcome.PRIVACY_DENIED, result.outcome)
            assertTrue(outbound.none { it.contains(sensitive) })
            assertEquals(0, fallbackCalls)
        }
    }

    @Test
    fun hallucinatedSamsungTickerIsRejectedAndNeverTransmitted() = runTest {
        val query = "삼성전자 최신 뉴스"
        val fakeTicker = "005939"
        val authorizer = authorizer(query)
        val rejected = authorizer.authorize(query, "$query $fakeTicker") as AgenticQueryAuthorization.Denied
        assertEquals("unverified_query_expansion", rejected.reasonCode)
        val outbound = mutableListOf<String>()
        val exact = AgenticExactSearchTool { candidate, _, _ -> outbound += candidate; response(candidate) }
        val router = SearchStrategyRouter(exact, authorizer, RuleBasedLegacySearchFallback(exact, authorizer))
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>) =
                SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["$query $fakeTicker"]}""")
        }
        val result = router.execute(
            SearchStrategyRouteRequest(
                AgenticSearchRequest("fake-ticker", query, GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                    AgenticSearchLimits.EXTERNAL_DEFAULT, ownerStillActive = { true }, searchPermissionStillValid = { true }),
                SearchStrategySnapshot.DEFAULT,
            ),
            model,
        )

        assertTrue(result.outcome == SearchRouteOutcome.FALLBACK_COMPLETED || result.outcome == SearchRouteOutcome.FALLBACK_FAILED)
        assertTrue(outbound.none { fakeTicker in it })
        assertTrue(outbound.all { authorizer.authorize(query, it) is AgenticQueryAuthorization.Authorized })
    }

    private fun authorizer(
        query: String,
        privateExcerpts: List<String> = emptyList(),
        fileNames: List<String> = emptyList(),
        approvedLegacyQueries: List<String> = emptyList(),
        manualQuery: String? = null,
        privateHistory: Boolean = false,
        attachments: Boolean = false,
        localRag: Boolean = false,
        project: Boolean = false,
        selection: Boolean = false,
    ) = RequestScopedAgenticSearchQueryAuthorizer(
        AgenticSearchPrivacySnapshot(
            requestId = "privacy-request",
            typedUserQuery = query,
            forbiddenPrivateExcerpts = privateExcerpts,
            privateFileNames = fileNames,
            approvedLegacyQueries = approvedLegacyQueries,
            explicitManualQuery = manualQuery,
            hasPrivateHistory = privateHistory,
            hasAttachments = attachments,
            hasLocalRagExcerpts = localRag,
            hasProjectContext = project,
            hasSelectionContext = selection,
            requestStillAuthorized = { true },
        ),
    )

    private fun AgenticQueryAuthorization.isAuthorized(): Boolean = this is AgenticQueryAuthorization.Authorized

    private fun response(query: String): FusionSearchResponse {
        val result = FusionSearchResult("Public result", "Source", "https://example.test/news", "Public evidence", "2026-10-31", queryUsed = query)
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
