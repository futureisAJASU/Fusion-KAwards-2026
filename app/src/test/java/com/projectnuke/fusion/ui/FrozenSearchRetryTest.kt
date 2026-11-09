package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ManualSearchQuery
import com.projectnuke.fusion.chat.ManualSearchQueryValidation
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 2: the explicit Retry Search repeats the FROZEN failed request.
// Current UI selections are never consulted by the frozen builders below.
class FrozenSearchRetryTest {

    @Test
    fun `retry remains available after pre-start failure and consumes only its validated snapshot`() {
        val coordinator = TrustRecoveryCoordinator()
        val frozen = coordinator.freezeSearchRetry(100L, searchSnapshot())
        assertTrue(coordinator.tryBeginSearchRetry("retry-100"))
        assertEquals(frozen, coordinator.pendingSearchRetry(100L))
        assertFalse(coordinator.tryBeginSearchRetry("retry-101"))
        // An aborted validation releases the gate without consuming the retry.
        coordinator.endSearchRetry("retry-100")
        assertTrue(coordinator.tryBeginSearchRetry("retry-100"))
        assertEquals(frozen, coordinator.pendingSearchRetry(100L))
        assertTrue(coordinator.consumeSearchRetry(100L, frozen))
        assertFalse(coordinator.consumeSearchRetry(100L, frozen))
        assertNull(coordinator.pendingSearchRetry(100L))
        coordinator.endSearchRetry("retry-100")
    }

    @Test
    fun `stale validation cannot consume a replacement retry`() {
        val coordinator = TrustRecoveryCoordinator()
        val old = coordinator.freezeSearchRetry(100L, searchSnapshot(requestId = "old"))
        val replacement = coordinator.freezeSearchRetry(100L, searchSnapshot(requestId = "new"))
        assertFalse(coordinator.consumeSearchRetry(100L, old))
        assertEquals(replacement, coordinator.pendingSearchRetry(100L))
    }

    @Test
    fun `invalid frozen manual query offers correction instead of replay`() {
        val validQuery = (ManualSearchQuery.parse("public search") as ManualSearchQueryValidation.Valid).query
        val validSnapshot = searchSnapshot(
            searchStrategy = SearchStrategySnapshot(
                strategy = SearchStrategy.MANUAL_QUERY,
                allowLegacyFallback = false,
                manualQuery = ManualSearchQueryValidation.Valid(validQuery),
            ),
        )
        val missingSnapshot = validSnapshot.copy(
            searchStrategy = validSnapshot.searchStrategy.copy(manualQuery = ManualSearchQueryValidation.Missing),
        )
        val invalidSnapshot = validSnapshot.copy(
            searchStrategy = validSnapshot.searchStrategy.copy(
                manualQuery = ManualSearchQueryValidation.Invalid(com.projectnuke.fusion.chat.ManualSearchQueryIssue.EMPTY),
            ),
        )

        assertEquals(
            SearchRetryActionPresentation("다시 검색", correctManualQueryFirst = false),
            SearchRetryActionPresentationPolicy.resolve(FrozenSearchRetry.from(validSnapshot, 100L)),
        )
        for (snapshot in listOf(missingSnapshot, invalidSnapshot)) {
            assertEquals(
                SearchRetryActionPresentation("검색어 수정", correctManualQueryFirst = true),
                SearchRetryActionPresentationPolicy.resolve(FrozenSearchRetry.from(snapshot, 100L)),
            )
        }
    }

    @Test
    fun `pre registration failure restores exact retry without replacing newer state`() {
        val coordinator = TrustRecoveryCoordinator()
        val frozen = coordinator.freezeSearchRetry(100L, searchSnapshot(requestId = "frozen"))
        assertTrue(coordinator.consumeSearchRetry(100L, frozen))
        assertTrue(coordinator.restoreSearchRetryIfAbsent(100L, frozen))
        assertTrue(coordinator.consumeSearchRetry(100L, frozen))

        val newer = coordinator.freezeSearchRetry(100L, searchSnapshot(requestId = "newer"))
        assertFalse(coordinator.restoreSearchRetryIfAbsent(100L, frozen))
        assertEquals(newer, coordinator.pendingSearchRetry(100L))
    }

    private fun providerSnapshot(id: String) = ExternalProviderRequestSnapshot(
        id = id, type = AiProviderType.OPENAI, baseUrl = "https://api.example.com",
        modelId = "m", temperature = 0.7, maxTokens = 2048,
        authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderName = null,
        reasoningContentEnabled = false, apiKeySecretId = "s", displayName = id,
    )

    private fun searchSnapshot(
        requestId: String = "req-1",
        modelPath: String = "/models/a",
        providerId: String? = null,
        settings: GenerationSettings = GenerationSettings(),
        rawUserText: String = "current query",
        historyIds: List<Long> = listOf(9L),
        attachments: List<String> = emptyList(),
        searchStrategy: SearchStrategySnapshot = SearchStrategySnapshot.DEFAULT,
        manualSearchQueryInputRevision: Long? = null,
    ): GenerationRequestSnapshot = GenerationRequestSnapshot(
        requestId = requestId,
        conversationId = 7L,
        generationModeKey = if (providerId != null) {
            ChatGenerationMode.EXTERNAL_AI_API.name
        } else {
            ChatGenerationMode.LOCAL_MODEL.name
        },
        selectedModelId = "m",
        selectedModelPath = modelPath,
        selectedModelStableId = "stable-a",
        modelFamily = ModelFamily.KANANA,
        settings = settings,
        reasoningEnabled = true,
        webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
        attachmentIds = attachments,
        multimodalImagePaths = emptyList(),
        promptText = rawUserText,
        rawUserText = rawUserText,
        createdAt = 1L,
        history = listOf(ChatMessage("user", "older")),
        historyMessageIds = historyIds,
        trustRoutingDecision = TrustRoutingDecision(
            TrustRoute.SEARCH, TrustRoutingReason.CURRENT_INFORMATION_REQUIRED, emptySet(),
        ),
        externalProviderId = providerId,
        externalProviderSelectionFrozen = providerId != null,
        externalMaxOutputTokens = 512,
        externalProviderRequest = providerId?.let { providerSnapshot(it) },
        searchStrategy = searchStrategy,
        manualSearchQueryInputRevision = manualSearchQueryInputRevision,
    )

    @Test
    fun `freeze take roundtrip copies the live request`() {
        val coordinator = TrustRecoveryCoordinator()
        val frozen = coordinator.freezeSearchRetry(100L, searchSnapshot())
        assertEquals(100L, frozen.failureMessageId)
        assertEquals("req-1", frozen.requestId)
        assertEquals("/models/a", frozen.selectedModelPath)
        assertEquals("current query", frozen.rawUserText)
        assertEquals(TrustRoute.SEARCH, frozen.trustRoutingDecision.route)
        // Single-use: consumed on take.
        assertEquals(frozen, coordinator.takeSearchRetry(100L))
        assertNull(coordinator.takeSearchRetry(100L))
    }

    @Test
    fun `retained snapshots stay bounded`() {
        val coordinator = TrustRecoveryCoordinator()
        repeat(TrustRecoveryBounds.MAX_RETAINED_SEARCH_RETRIES + 2) { i ->
            coordinator.freezeSearchRetry(100L + i, searchSnapshot(requestId = "req-$i"))
        }
        assertEquals(TrustRecoveryBounds.MAX_RETAINED_SEARCH_RETRIES, coordinator.retainedRetryCountForTest())
        assertNull(coordinator.takeSearchRetry(100L))
        assertNull(coordinator.takeSearchRetry(101L))
    }

    @Test
    fun `capture keeps model A semantics after ui switch to model B`() {
        val frozen = FrozenSearchRetry.from(searchSnapshot(modelPath = "/models/a"), 100L)
        // The builder takes no UI input at all: switching the UI to model B
        // cannot change the retried request.
        val capture = buildFrozenSearchRetryCapture(
            frozen,
            12L,
            ResponseVersionState(),
        )
        assertEquals("/models/a", capture.selectedModelPath)
        assertEquals("stable-a", capture.selectedModelStableId)
        assertEquals(ModelFamily.KANANA, capture.modelFamily)
        assertEquals(ChatGenerationMode.LOCAL_MODEL, capture.generationMode)
        assertEquals(TrustRoute.SEARCH, capture.frozenTrustRoutingDecision?.route)
        assertTrue(capture.webSearchEnabled)
    }

    @Test
    fun `capture keeps provider A or fails explicitly never provider B`() {
        val frozen = FrozenSearchRetry.from(searchSnapshot(providerId = "pA"), 100L)
        val capture = buildFrozenSearchRetryCapture(frozen, 12L, ResponseVersionState())
        assertEquals(ChatGenerationMode.EXTERNAL_AI_API, capture.generationMode)
        assertEquals("pA", capture.externalProviderId)
        assertEquals("pA", capture.externalProviderRequest?.id)
        assertEquals(null, capture.localProfile)
    }

    @Test
    fun `capture keeps frozen settings and search route`() {
        val settings = GenerationSettings().copy(temperature = 0.3f)
        val frozen = FrozenSearchRetry.from(searchSnapshot(settings = settings), 100L)
        val capture = buildFrozenSearchRetryCapture(frozen, 12L, ResponseVersionState())
        assertEquals(0.3f, capture.generationSettings.temperature)
        assertEquals(0.3f, capture.localProfile?.generationSettings?.temperature)
        assertEquals(TrustRoute.SEARCH, capture.frozenTrustRoutingDecision?.route)
        assertTrue(capture.webSearchEnabled)
        assertTrue(capture.reasoningEnabled)
    }

    @Test
    fun `frozen manual query survives the explicit search retry capture`() {
        val query = "삼성전자 005930 KRX 현재가"
        val manualQuery = (ManualSearchQuery.parse(query) as ManualSearchQueryValidation.Valid).query
        val strategy = SearchStrategySnapshot(
            strategy = SearchStrategy.MANUAL_QUERY,
            allowLegacyFallback = false,
            manualQuery = ManualSearchQueryValidation.Valid(manualQuery),
        )
        val frozen = FrozenSearchRetry.from(searchSnapshot(searchStrategy = strategy, manualSearchQueryInputRevision = 23L), 100L)
        val capture = buildFrozenSearchRetryCapture(frozen, 12L, ResponseVersionState())
        val request = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "retry-req",
            settings = capture.generationSettings,
            reasoningEnabled = capture.reasoningEnabled,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            attachmentIds = frozen.attachmentIds,
            multimodalImagePaths = frozen.multimodalImagePaths,
            promptText = query,
            rawUserText = query,
            createdAt = 2L,
            history = emptyList(),
            historyMessageIds = emptyList(),
        )

        assertEquals(strategy, frozen.searchStrategy)
        assertEquals(strategy, capture.searchStrategy)
        assertEquals(strategy, request.searchStrategy)
        assertEquals(23L, frozen.manualSearchQueryInputRevision)
        assertEquals(23L, capture.manualSearchQueryInputRevision)
        assertEquals(23L, request.manualSearchQueryInputRevision)
    }

    @Test
    fun `scope resolves one user turn from frozen identity`() {
        val frozen = FrozenSearchRetry.from(searchSnapshot(historyIds = listOf(9L)), 100L)
        val history = listOf(
            MessageEntity(9L, 7L, "user", "older", 1L),
            MessageEntity(11L, 7L, "user", "current query", 2L),
            MessageEntity(12L, 7L, "assistant", "search failed notice", 3L),
        )
        val scoped = resolveFrozenRetryScope(history, frozen, 12L)
        assertEquals(listOf(9L, 11L, 12L), scoped?.map { it.id })
    }

    @Test
    fun `scope rejects origin text drift`() {
        val frozen = FrozenSearchRetry.from(searchSnapshot(), 100L)
        val history = listOf(
            MessageEntity(9L, 7L, "user", "older", 1L),
            MessageEntity(11L, 7L, "user", "edited query", 2L),
            MessageEntity(12L, 7L, "assistant", "search failed notice", 3L),
        )
        assertNull(resolveFrozenRetryScope(history, frozen, 12L))
    }

    @Test
    fun `scope rejects attachment identity drift`() {
        val userContent = AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord("a.pdf", "application/pdf", "/tmp/a.pdf")),
            "current query",
        )
        val frozen = FrozenSearchRetry.from(
            searchSnapshot(attachments = listOf("/tmp/a.pdf")),
            100L,
        )
        assertEquals(listOf("/tmp/a.pdf"), frozen.attachmentIds)
        val drifted = AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord("b.pdf", "application/pdf", "/tmp/b.pdf")),
            "current query",
        )
        val history = listOf(
            MessageEntity(11L, 7L, "user", drifted, 2L),
            MessageEntity(12L, 7L, "assistant", "search failed notice", 3L),
        )
        assertNull(resolveFrozenRetryScope(history, frozen, 12L))
        val intact = listOf(
            MessageEntity(9L, 7L, "user", "older", 1L),
            MessageEntity(11L, 7L, "user", userContent, 2L),
            MessageEntity(12L, 7L, "assistant", "search failed notice", 3L),
        )
        // Identical attachment identities resolve the frozen scope.
        assertEquals(
            listOf(9L, 11L, 12L),
            resolveFrozenRetryScope(intact, frozen, 12L)?.map { it.id },
        )
    }

    @Test
    fun `scope rejects missing frozen history`() {
        val frozen = FrozenSearchRetry.from(searchSnapshot(historyIds = listOf(9L)), 100L)
        val history = listOf(
            MessageEntity(11L, 7L, "user", "current query", 2L),
            MessageEntity(12L, 7L, "assistant", "search failed notice", 3L),
        )
        assertNull(resolveFrozenRetryScope(history, frozen, 12L))
    }

    @Test
    fun `scope rejects missing target`() {
        val frozen = FrozenSearchRetry.from(searchSnapshot(), 100L)
        val history = listOf(
            MessageEntity(11L, 7L, "user", "current query", 2L),
        )
        assertNull(resolveFrozenRetryScope(history, frozen, 999L))
    }

    @Test
    fun `missing snapshot disables retry without rederiving`() {
        val coordinator = TrustRecoveryCoordinator()
        assertNull(coordinator.takeSearchRetry(4242L))
    }
}
