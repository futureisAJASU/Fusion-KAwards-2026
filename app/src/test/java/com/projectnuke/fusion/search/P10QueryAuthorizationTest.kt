package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import com.projectnuke.fusion.privacy.DataEgressPrivateTextLedger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// P10-A: query authorization is separated from query-quality evaluation, and
// authorized public entity/terminology expansion is backed by deterministic,
// inspectable sources instead of model confidence.
class P10QueryAuthorizationTest {

    // ---- required public authorization cases -------------------------------

    @Test
    fun samsungElectronicsResolvesToTheValidatedPublicTickerAndKrx() {
        val query = "삼성전자 주가 알려줘"
        val decision = authorizer(query).authorize(query, "삼성전자 005930 현재 주가 KRX")
        assertTrue(decision.toString(), decision is AgenticQueryAuthorization.Authorized)
        assertEquals("삼성전자 005930 현재 주가 KRX", (decision as AgenticQueryAuthorization.Authorized).exactQuery)
        assertEquals(
            AgenticQueryMaterialKind.TRUSTED_PUBLIC_ENRICHMENT,
            decision.materialKind,
        )
    }

    @Test
    fun krxAndCurrentPriceTerminologySurviveTheAuthorizationBoundary() {
        val query = "삼성전자 주가 알려줘"
        val authorizer = authorizer(query)
        listOf(
            "삼성전자 KRX",
            "삼성전자 현재가",
            "삼성전자 종가 시가총액",
            "삼성전자 stock price",
            "삼성전자 market cap",
        ).forEach { proposed ->
            assertTrue(proposed, authorizer.authorize(query, proposed).isAuthorized())
        }
    }

    @Test
    fun userSuppliedExplicitTickerIsTypedMaterialNotEnrichment() {
        val query = "005930 주가 알려줘"
        val decision = authorizer(query).authorize(query, query)
        assertEquals(AgenticQueryMaterialKind.EXPLICIT_TYPED, (decision as AgenticQueryAuthorization.Authorized).materialKind)
        // Re-specified in latin case is still typed/duplicate material.
        assertTrue(authorizer(query).authorize(query, "005930 KRX 주가 알려줘").isAuthorized())
    }

    @Test
    fun trustedKoreanEnglishTechnicalAliasIsAuthorized() {
        val query = "반도체 산업 전망 알려줘"
        val decision = authorizer(query).authorize(query, "반도체 semiconductor 전망")
        assertTrue(decision.toString(), decision is AgenticQueryAuthorization.Authorized)
        assertEquals(
            AgenticQueryMaterialKind.TRUSTED_PUBLIC_ENRICHMENT,
            (decision as AgenticQueryAuthorization.Authorized).materialKind,
        )

        // The English surface equally authorizes the Korean surface.
        val englishQuery = "Nvidia semiconductor revenue"
        assertTrue(authorizer(englishQuery).authorize(englishQuery, "Nvidia 반도체 revenue").isAuthorized())
        // A term no typed surface names stays untrusted.
        assertTrue(authorizer(query).authorize(query, "반도체 quantum 전망").isDenied())
    }

    @Test
    fun legitimatePublicEntityAliasAndExchangeAreAuthorized() {
        val query = "SK하이닉스 주가 알려줘"
        val decision = authorizer(query).authorize(query, "SK하이닉스 Hynix 000660 KRX 주가")
        assertTrue(decision.toString(), decision is AgenticQueryAuthorization.Authorized)
        assertEquals(
            AgenticQueryMaterialKind.TRUSTED_PUBLIC_ENRICHMENT,
            (decision as AgenticQueryAuthorization.Authorized).materialKind,
        )
        assertEquals("SK하이닉스 Hynix 000660 KRX 주가", (decision as AgenticQueryAuthorization.Authorized).exactQuery)
        // The alias surface form itself is public material from the same record.
        assertTrue(authorizer(query).authorize(query, "SK하이닉스 SK hynix 000660").isAuthorized())
    }

    @Test
    fun nasdaqListedCompanyAliasResolvesToItsPublishedTicker() {
        val query = "nvidia stock price"
        val decision = authorizer(query).authorize(query, "nvidia NVDA NASDAQ stock price")
        assertTrue(decision is AgenticQueryAuthorization.Authorized)
        assertEquals(
            AgenticQueryMaterialKind.TRUSTED_PUBLIC_ENRICHMENT,
            (decision as AgenticQueryAuthorization.Authorized).materialKind,
        )
    }

    // ---- required denial cases ---------------------------------------------

    @Test
    fun modelHallucinatedIdentifierIsRejectedAsUntrustedMaterial() {
        val query = "삼성전자 주가 알려줘"
        val authorizer = authorizer(query)
        listOf(
            "삼성전자 005939 주가",
            "삼성전자 0059300 KRX 주가",
            "삼성전자 ZZZZZ 현재가",
        ).forEach { proposed ->
            val denial = authorizer.authorize(query, proposed) as AgenticQueryAuthorization.Denied
            assertEquals(proposed, "unverified_query_expansion", denial.reasonCode)
            assertEquals(
                proposed,
                AgenticQueryMaterialKind.UNTRUSTED_MODEL_INFERRED,
                denial.materialKind,
            )
        }
    }

    @Test
    fun ambiguousFamilyEntityNameResolvesToNothingAndCannotBeWidened() {
        listOf("삼성 주가 알려줘", "sk 주가 알려줘", "현대 주가 알려줘").forEach { query ->
            assertTrue(query, TrustedPublicEntityLexicon.isAmbiguousEntityRequest(query))
            assertTrue(query, TrustedPublicEntityLexicon.termsFor(query).isEmpty())
            assertTrue(query, authorizer(query).authorize(query, "$query 005930").isDenied())
        }
        // Disambiguating the family name restores deterministic resolution.
        assertFalse(TrustedPublicEntityLexicon.isAmbiguousEntityRequest("삼성전자 주가 알려줘"))
    }

    @Test
    fun personalIdentifierInjectedByTheModelIsRejectedAsPrivateMaterial() {
        val query = "삼성전자 주가 알려줘"
        val authorizer = authorizer(query)
        listOf(
            "삼성전자 주가 010-1234-5678",
            "삼성전자 주가 900101-1234567",
            "삼성전자 주가 person@example.com",
        ).forEach { proposed ->
            val denial = authorizer.authorize(query, proposed) as AgenticQueryAuthorization.Denied
            assertEquals(proposed, "private_or_sensitive_text", denial.reasonCode)
            assertEquals(
                proposed,
                AgenticQueryMaterialKind.PRIVATE_OR_TAINTED,
                denial.materialKind,
            )
        }
    }

    @Test
    fun privateRagExcerptAndPrivateFilenameCannotBeAppended() {
        val query = "요약해줘"
        val excerpt = "Project Mica confidential acquisition plan"
        val fileName = "Confidential-Q3-Acquisition.pdf"
        val authorizer = authorizer(query, privateExcerpts = listOf(excerpt), fileNames = listOf(fileName), localRag = true)
        assertEquals(
            "private_or_sensitive_text",
            (authorizer.authorize(query, "$query $excerpt") as AgenticQueryAuthorization.Denied).reasonCode,
        )
        val denial = authorizer.authorize(query, "$query $fileName") as AgenticQueryAuthorization.Denied
        assertEquals("private_or_sensitive_text", denial.reasonCode)
        assertEquals(AgenticQueryMaterialKind.PRIVATE_OR_TAINTED, denial.materialKind)
    }

    @Test
    fun previousPrivateConversationCannotAuthorizeNewSearchTerms() {
        val query = "What is the public weather forecast?"
        val secret = "my private diagnosis is long covid"
        val authorizer = authorizer(query, privateExcerpts = listOf(secret), privateHistory = true)
        assertEquals(
            "private_or_sensitive_text",
            (authorizer.authorize(query, "weather forecast $secret") as AgenticQueryAuthorization.Denied).reasonCode,
        )
        assertEquals(
            "unverified_query_expansion",
            (authorizer.authorize(query, "weather forecast diagnosis") as AgenticQueryAuthorization.Denied).reasonCode,
        )
    }

    // ---- multi-turn classification -----------------------------------------

    @Test
    fun publicMultiTurnConversationIsNotPrivatelyClassifiedMerelyBecauseHistoryExists() {
        val query = "삼성전자 주가 알려줘"
        val publicHistory = authorizer(query, privateHistory = false)
        // Public history keeps the deterministic stock vocabulary available.
        assertTrue(publicHistory.authorize(query, "삼성전자 주가 지수 수준").isAuthorized())
        assertTrue(TrustedPublicEntityLexicon.termsFor(query).contains("005930"))

        // Only privacy-tainted history drops the extra vocabulary.
        val tainted = authorizer(query, privateHistory = true)
        assertTrue(tainted.authorize(query, "삼성전자 005930 KRX 주가").isAuthorized())
        assertTrue(tainted.authorize(query, "삼성전자 주가 지수 수준").isDenied())
    }

    @Test
    fun benignPublicPriorTurnsCannotContributeAuthorityForNewTerms() {
        val query = "그럼 어제는?"
        val authorizer = authorizer(
            query = query,
            privateHistory = false,
            approvedPriorUserTurns = listOf("SK하이닉스 000660 주가 알려줘"),
        )
        // Prior turns are planning context: a term reachable only there is
        // never authorized for the outbound query.
        val denial = authorizer.authorize(query, "그럼 어제는 000660") as AgenticQueryAuthorization.Denied
        assertEquals("unverified_query_expansion", denial.reasonCode)
        assertEquals(
            AgenticQueryMaterialKind.UNTRUSTED_MODEL_INFERRED,
            denial.materialKind,
        )
    }

    @Test
    fun requestRevocationDeniesEveryIncludingPreviouslyAuthorizedMaterial() {
        var authorized = true
        val authorizer = RequestScopedAgenticSearchQueryAuthorizer(
            AgenticSearchPrivacySnapshot(
                requestId = "revoked-request",
                typedUserQuery = "삼성전자 주가 알려줘",
                requestStillAuthorized = { authorized },
            ),
        )
        assertTrue(authorizer.authorize("삼성전자 주가 알려줘", "삼성전자 주가 알려줘").isAuthorized())
        authorized = false
        assertEquals(
            "authorization_revoked",
            (authorizer.authorize("삼성전자 주가 알려줘", "삼성전자 주가 알려줘") as AgenticQueryAuthorization.Denied).reasonCode,
        )
        assertEquals(
            "authorization_revoked",
            (authorizer.authorize("삼성전자 주가 알려줘", "삼성전자 005930 KRX") as AgenticQueryAuthorization.Denied).reasonCode,
        )
    }

    @Test
    fun globalPrivateTextLedgerStillBlocksExpansionAtAuthorizationTime() {
        val query = "public weather in Seoul"
        val excerpt = "ledger-only confidential family settlement"
        val original = DataEgressPrivateTextLedger.currentTexts()
        try {
            DataEgressPrivateTextLedger.register(listOf(excerpt))
            val denial = authorizer(query).authorize(query, "$query $excerpt") as AgenticQueryAuthorization.Denied
            assertEquals("private_or_sensitive_text", denial.reasonCode)
        } finally {
            DataEgressPrivateTextLedger.clear()
            DataEgressPrivateTextLedger.register(original)
        }
    }

    // ---- no unauthorized egress --------------------------------------------

    @Test
    fun trustedPublicEnrichmentReachesExactlyOneAuthorizedTransportCall() = runTest {
        val query = "삼성전자 주가 알려줘"
        val searched = mutableListOf<String>()
        val outbound = mutableListOf<String>()
        val exactSearch = AgenticExactSearchTool { candidate, _, _ ->
            outbound += candidate
            response(candidate)
        }
        var planningRounds = 0
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply {
                searched += messages.single { it.content.startsWith("User request:") }.content
                planningRounds++
                return if (planningRounds == 1) {
                    SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["삼성전자 005930 KRX 현재 주가"]}""")
                } else {
                    SearchAgentReply.StructuredIntent("""{"action":"NO_MORE_SEARCH"}""")
                }
            }
        }
        val result = SearchStrategyRouter(
            exactSearchTool = exactSearch,
            queryAuthorizer = authorizer(query),
            legacyFallback = LegacySearchFallbackExecutor { error("agent succeeded; fallback must not run") },
        ).execute(routeRequest(query), model)

        assertEquals(SearchRouteOutcome.COMPLETED, result.outcome)
        assertEquals(listOf("삼성전자 005930 KRX 현재 주가"), outbound)
        assertEquals(1, result.searchQueriesIssued)
        assertEquals(2, planningRounds)
    }

    @Test
    fun privateModelExpansionProducesZeroNetworkCalls() = runTest {
        val query = "삼성전자 주가 알려줘"
        var outboundCalls = 0
        val model = object : SearchAgentModel {
            override val protocol = SearchAgentProtocol.STRUCTURED_INTENT
            override suspend fun complete(messages: List<SearchAgentMessage>): SearchAgentReply =
                SearchAgentReply.StructuredIntent("""{"action":"SEARCH","queries":["삼성전자 주가 010-1234-5678"]}""")
        }
        val result = SearchStrategyRouter(
            exactSearchTool = AgenticExactSearchTool { _, _, _ -> outboundCalls++; error("must not run") },
            queryAuthorizer = authorizer(query),
            legacyFallback = LegacySearchFallbackExecutor { outboundCalls++; error("must not run") },
        ).execute(routeRequest(query), model)

        assertEquals(SearchRouteOutcome.PRIVACY_DENIED, result.outcome)
        assertEquals(0, outboundCalls)
    }

    // ---- helpers -----------------------------------------------------------

    private fun routeRequest(query: String) = SearchStrategyRouteRequest(
        agentRequest = AgenticSearchRequest(
            requestId = "p10a-request",
            originalUserQuery = query,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
            ownerStillActive = { true },
            searchPermissionStillValid = { true },
        ),
        strategy = SearchStrategySnapshot.DEFAULT,
    )

    private fun authorizer(
        query: String,
        privateExcerpts: List<String> = emptyList(),
        fileNames: List<String> = emptyList(),
        privateHistory: Boolean = false,
        localRag: Boolean = false,
        attachments: Boolean = false,
        approvedPriorUserTurns: List<String> = emptyList(),
    ) = RequestScopedAgenticSearchQueryAuthorizer(
        AgenticSearchPrivacySnapshot(
            requestId = "p10a-request",
            typedUserQuery = query,
            forbiddenPrivateExcerpts = privateExcerpts,
            privateFileNames = fileNames,
            hasPrivateHistory = privateHistory,
            hasLocalRagExcerpts = localRag,
            hasAttachments = attachments,
            approvedPriorUserTurns = approvedPriorUserTurns,
            requestStillAuthorized = { true },
        ),
    )

    private fun AgenticQueryAuthorization.isAuthorized(): Boolean = this is AgenticQueryAuthorization.Authorized

    private fun AgenticQueryAuthorization.isDenied(): Boolean = this is AgenticQueryAuthorization.Denied

    private fun response(query: String): FusionSearchResponse {
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
