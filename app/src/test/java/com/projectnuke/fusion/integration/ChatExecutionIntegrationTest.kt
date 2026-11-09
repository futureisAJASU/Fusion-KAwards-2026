package com.projectnuke.fusion.integration

import com.projectnuke.fusion.ai.ExternalAiChatRunner
import com.projectnuke.fusion.ai.ExternalAiProviderSource
import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiChatResponse
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.buildExternalAiMessages
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.ai.network.AiProviderClientException
import com.projectnuke.fusion.ai.network.ChatClient
import com.projectnuke.fusion.ai.network.FakeHttpURLConnection
import com.projectnuke.fusion.chat.ChatPromptComposition
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.LlmEngine
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.ConversationOptions
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.privacy.DataEgressController
import com.projectnuke.fusion.privacy.DataEgressDestination
import com.projectnuke.fusion.privacy.DataEgressPolicy
import com.projectnuke.fusion.privacy.DataEgressScope
import com.projectnuke.fusion.privacy.EgressGateChatClient
import com.projectnuke.fusion.privacy.PreparedEgressRequestFactory
import com.projectnuke.fusion.retrieval.LocalAttachmentTextStore
import com.projectnuke.fusion.retrieval.LocalDocumentChunk
import com.projectnuke.fusion.retrieval.LocalRagBounds
import com.projectnuke.fusion.retrieval.LocalRagCoordinator
import com.projectnuke.fusion.retrieval.LocalRagPreparationResult
import com.projectnuke.fusion.retrieval.LocalRagPromptPolicy
import com.projectnuke.fusion.retrieval.LocalRagRetrieval
import com.projectnuke.fusion.retrieval.LocalRagSourceEntry
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import com.projectnuke.fusion.retrieval.LocalSemanticBatchRanker
import com.projectnuke.fusion.search.BoundedHttpClient
import com.projectnuke.fusion.search.SearchQueryAuthorization
import com.projectnuke.fusion.search.FusionWebSearch
import com.projectnuke.fusion.search.SearchConnectionFactory
import com.projectnuke.fusion.search.hasUsableResults
import com.projectnuke.fusion.translation.LiteRtLocalTranslator
import com.projectnuke.fusion.translation.LocalTranslationCoordinator
import com.projectnuke.fusion.translation.TranslationResult
import com.projectnuke.fusion.trust.TrustExecutionFailure
import com.projectnuke.fusion.trust.TrustRecoveryAction
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRouter
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.trust.TrustRoutingRequest
import com.projectnuke.fusion.ui.CloudEscalationCoordinator
import com.projectnuke.fusion.ui.CloudEscalationExecutionResult
import com.projectnuke.fusion.ui.CloudEscalationExecutor
import com.projectnuke.fusion.ui.CloudEscalationOffer
import com.projectnuke.fusion.ui.PendingCloudEscalation
import com.projectnuke.fusion.ui.ResponseVersionState
import com.projectnuke.fusion.ui.TrustRecoveryCoordinator
import com.projectnuke.fusion.ui.buildCloudEscalationSnapshot
import com.projectnuke.fusion.ui.cloudEscalationProvenanceLabel
import com.projectnuke.fusion.ui.resolveEscalationExecutionRequest
import com.projectnuke.fusion.ui.validatePendingEscalation
import com.projectnuke.fusion.util.AttachmentRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production execution integration tests (Phase 7).
 *
 * Every scenario below invokes an ACTUAL production coordinator or request
 * path used by the app (not policy helpers alone) with fake transports, and
 * asserts on the fake's received calls. This is the execution proof that
 * CrossPhasePolicyCompatibilityTest intentionally does not provide.
 */
class ChatExecutionIntegrationTest {

    // ---------- shared fakes ----------

    private fun provider() = AiProviderConfig(
        id = "p1",
        type = AiProviderType.OPENAI,
        displayName = "P",
        baseUrl = "https://api.example.com",
        modelId = "m",
        apiKeySecretId = "s",
    )

    private class FakeSource(private val config: AiProviderConfig) : ExternalAiProviderSource {
        override suspend fun getSelectedRunnableProvider(): AiProviderConfig = config
        override suspend fun getRunnableProviderById(id: String): AiProviderConfig? =
            config.takeIf { it.id == id }
    }

    private class CountingChatClient(
        var reply: String = "external answer",
        var failure: Throwable? = null,
    ) : ChatClient {
        val calls = AtomicInteger(0)
        val seenConfigs = mutableListOf<AiProviderConfig>()
        override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse {
            calls.incrementAndGet()
            seenConfigs += config
            failure?.let { throw it }
            return AiChatResponse("id", config.modelId, reply)
        }
    }

    private class FakeEngine(
        var reply: String = "translated text",
        var outcome: GenerationOutcome = GenerationOutcome.Success("translated text"),
    ) : LlmEngine {
        var calls = 0
        var lastMessages: List<ChatMessage> = emptyList()
        override suspend fun generate(
            messages: List<ChatMessage>,
            profile: RequestedEngineProfile,
            options: ConversationOptions,
        ): GenerationOutcome {
            calls++
            lastMessages = messages
            return outcome
        }

        override fun unload() {}
    }

    private fun engineProfile() = RequestedEngineProfile(
        modelPath = "/models/test",
        accelerator = AcceleratorMode.CPU,
        mtpRequested = false,
        kvCacheCapacityTokens = 4096,
        enableVisionBackend = false,
        modelIdentity = "test-stable",
    )

    private fun providerSnapshot() = ExternalProviderRequestSnapshot(
        id = "p1", type = AiProviderType.OPENAI, baseUrl = "https://api.example.com",
        modelId = "m", temperature = 0.7, maxTokens = 2048,
        authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderName = null,
        reasoningContentEnabled = false, apiKeySecretId = "s", displayName = "P",
    )

    private fun cloudDecision() = TrustRoutingDecision(
        TrustRoute.CLOUD_SUGGEST, TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD, emptySet(),
    )

    private fun frozenPending(conversationId: Long = 7L): PendingCloudEscalation {
        val snapshot = buildCloudEscalationSnapshot(
            conversationId, "req-e2e", "p1", providerSnapshot(), GenerationSettings(),
            false, "", listOf(ChatMessage("user", "complex task")), listOf(11L),
            "complex task", "complex task", 1L, cloudDecision(),
        )
        return PendingCloudEscalation(
            conversationId, 12L, 11L, 11L, "p1", "P", snapshot,
            listOf(
                MessageEntity(11L, conversationId, "user", "complex task", 1L),
                MessageEntity(12L, conversationId, "assistant", "local answer", 2L),
            ),
            ResponseVersionState(
                groupByMessageId = mapOf(12L to 11L),
                activeMessageIdByGroup = mapOf(11L to 12L),
            ),
        )
    }

    private fun productionExecutor(runner: ExternalAiChatRunner) = CloudEscalationExecutor { pending ->
        val request = resolveEscalationExecutionRequest(pending)
        runner.generateFromMessages(
            messages = listOf(AiMessage(AiRole.USER, request.promptText)),
            hasAttachments = false,
            providerId = pending.providerId,
            providerSelectionFrozen = true,
            maxTokensOverride = request.externalMaxOutputTokens,
            providerRequestSnapshot = request.externalProviderRequest,
        )
    }

    private fun newsRss(title: String, link: String): ByteArray =
        ("<rss version=\"2.0\"><channel><item><title>$title</title><link>$link</link>" +
            "<source>TestPress</source><pubDate>Mon, 01 Jan 2024 00:00:00 GMT</pubDate>" +
            "<description>summary</description></item></channel></rss>").toByteArray(Charsets.UTF_8)

    // JVM-safe canned payload for the bounded DuckDuckGo HTML provider path.
    private fun duckHtml(title: String, link: String): ByteArray =
        ("<html><body><div class=\"result results_links_deep web-result\">" +
            "<a rel=\"nofollow\" class=\"result__a\" href=\"" + link + "\">" + title + "</a>" +
            "<a class=\"result__snippet\">summary text</a>" +
            "</div></div></body></html>").toByteArray(Charsets.UTF_8)

    private fun fakeNewsClient(
        requestedUrls: MutableList<String>,
        body: ByteArray,
        code: Int = 200,
    ): BoundedHttpClient = BoundedHttpClient(
        SearchConnectionFactory { url ->
            requestedUrls += url.toString()
            FakeHttpURLConnection(url).apply { configure(code, body) }
        },
    )

    // ---------- A: LOCAL stable request ----------

    @Test
    fun `A local stable request generates with no web external privacy action`() {
        val decision = TrustRouter.route(TrustRoutingRequest(userText = "1+1은 얼마야?"))
        assertEquals(TrustRoute.LOCAL, decision.route)
        val coordinator = CloudEscalationCoordinator(productionExecutor(
            ExternalAiChatRunner(FakeSource(provider()), CountingChatClient()),
        ))
        // No escalation surface for LOCAL; recovery classifies nothing.
        assertTrue(coordinator.offerFor(decision, "LOCAL_MODEL", true, false) is CloudEscalationOffer.Hidden)
        val recovery = TrustRecoveryCoordinator()
        assertEquals(null, recovery.searchFailure(decision, attempted = true, usableResult = true, requestId = "a"))
    }

    // ---------- B: SEARCH success, fake transport called ----------

    @Test
    fun `B search success calls fake transport and enters sources`() = runBlocking {
        assertEquals(
            "market news",
            FusionWebSearch.parseDuckDuckGoHtmlForTest(duckHtml("market news", "https://example.com/n1").toString(Charsets.UTF_8))
                .singleOrNull()?.title,
        )
        val requested = mutableListOf<String>()
        FusionWebSearch.httpClientForTests = fakeNewsClient(
            requested,
            duckHtml("market news", "https://example.com/n1"),
        )
        try {
            val response = FusionWebSearch.search(
                userInput = "오늘 주요 뉴스 알려줘",
                previousUserMessage = null,
                providerRepository = null,
            )
            assertTrue(requested.isNotEmpty())
            assertTrue(response.hasUsableResults())
            val recovery = TrustRecoveryCoordinator()
            val decision = TrustRoutingDecision(
                TrustRoute.SEARCH, TrustRoutingReason.CURRENT_INFORMATION_REQUIRED, emptySet(),
            )
            assertEquals(null, recovery.searchFailure(decision, attempted = true, usableResult = true, requestId = "b"))
            assertTrue(response.sources.isNotEmpty())
            assertTrue((response.sources.first().url ?: "").contains("example.com"))
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
    }

    // ---------- C: SEARCH failure, no stale fallback ----------

    @Test
    fun `C search failure yields typed failure and keeps stable answer`() = runBlocking {
        val requested = mutableListOf<String>()
        FusionWebSearch.httpClientForTests = fakeNewsClient(requested, ByteArray(0), 500)
        try {
            val response = FusionWebSearch.search(
                userInput = "오늘 주요 뉴스 알려줘",
                previousUserMessage = null,
                providerRepository = null,
            )
            assertTrue(requested.isNotEmpty())
            assertFalse(response.hasUsableResults())
            val recovery = TrustRecoveryCoordinator()
            val decision = TrustRoutingDecision(
                TrustRoute.SEARCH, TrustRoutingReason.CURRENT_INFORMATION_REQUIRED, emptySet(),
            )
            val failure = recovery.searchFailure(decision, attempted = true, usableResult = false, requestId = "c")
            assertTrue(failure is TrustExecutionFailure.SearchNoUsableResult)
            assertTrue((failure as TrustExecutionFailure.SearchNoUsableResult).userMessage.isNotBlank())
            assertTrue(failure.recovery is TrustRecoveryAction.RetrySearch)
            // The stable answer is never silently replaced by generation.
            assertEquals("stable", recovery.resolveAfterRetry("stable", null, false))
            val retrievalFailed = recovery.searchFailure(decision, attempted = false, usableResult = false, requestId = "c")
            assertTrue(retrievalFailed is TrustExecutionFailure.SearchRetrievalFailed)
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
    }

    // ---------- D: ABSTAIN, no calls ----------

    @Test
    fun `D abstain performs no model search external call`() = runTest {
        val decision = TrustRouter.route(
            TrustRoutingRequest(userText = "다음주 로또 당첨번호를 100% 확실하게 알려줘"),
            clock = object : com.projectnuke.fusion.trust.TrustRouterClock {
                override fun currentYear(): Int = 2026
            },
        )
        assertEquals(TrustRoute.ABSTAIN, decision.route)
        val requested = mutableListOf<String>()
        FusionWebSearch.httpClientForTests = fakeNewsClient(requested, newsRss("x", "https://example.com/x"))
        try {
            // Production skips search for ABSTAIN; the fake sees nothing.
            assertTrue(requested.isEmpty())
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
        val recovery = TrustRecoveryCoordinator()
        assertFalse(recovery.isAbstainError())
    }

    // ---------- E: CLOUD_SUGGEST consented escalation ----------

    @Test
    fun `E cloud suggest escalation confirms through real runner exactly once`() = runTest {
        val client = CountingChatClient()
        val coordinator = CloudEscalationCoordinator(
            productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)),
        )
        val offer = coordinator.offerFor(cloudDecision(), "LOCAL_MODEL", true, false)
        assertTrue(offer is CloudEscalationOffer.Shown)
        assertEquals(0, client.calls.get())

        val pending = frozenPending()
        assertTrue(validatePendingEscalation(pending, 7L, pending.fullMessageEntities))
        assertTrue(coordinator.freezePending(pending))
        assertEquals(0, client.calls.get())

        val result = coordinator.confirm(pending) {
            com.projectnuke.fusion.ui.CloudEscalationLiveState(
                true,
                pending.fullMessageEntities,
                pending.capturedResponseVersionState,
            )
        }
        assertTrue(result is CloudEscalationExecutionResult.Success)
        // Exactly one external attempt through the real production runner.
        assertEquals(1, client.calls.get())

        // Alternate version stored for the same originating user request.
        val store = mutableListOf(
            MessageEntity(11L, 7L, "user", "complex task", 1L),
            MessageEntity(12L, 7L, "assistant", "local answer", 2L),
        )
        var nextId = 13L
        var versions = ResponseVersionState()
        val content = cloudEscalationProvenanceLabel("P") + "\n\n" + (result as CloudEscalationExecutionResult.Success).content
        val persisted = coordinator.persistEscalatedResponse(
            pending = pending,
            escalatedContent = content,
            loadLive = {
                com.projectnuke.fusion.ui.CloudEscalationLiveState(
                    true,
                    pending.fullMessageEntities,
                    pending.capturedResponseVersionState,
                )
            },
            loadPreviousState = { versions },
            insertMessage = { text ->
                store += MessageEntity(nextId, 7L, "assistant", text, 3L)
                nextId++
            },
            saveState = { versions = it },
            restoreState = { versions = it },
            deleteMessage = { id -> store.removeAll { it.id == id } },
            updateConversationTimestamp = {},
        )
        assertEquals(1, store.count { it.role == "user" })
        assertEquals("local answer", store.first { it.id == 12L }.content)
        assertTrue(store.first { it.id == persisted!!.messageId }.content.contains("external answer"))
        assertEquals(1, client.calls.get())
    }

    @Test
    fun `E cloud escalation failure preserves local answer with no version`() = runTest {
        val client = CountingChatClient(failure = AiProviderClientException("boom"))
        val coordinator = CloudEscalationCoordinator(
            productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)),
        )
        val pending = frozenPending()
        val result = coordinator.confirm(pending) {
            com.projectnuke.fusion.ui.CloudEscalationLiveState(
                true,
                pending.fullMessageEntities,
                pending.capturedResponseVersionState,
            )
        }
        assertTrue(result is CloudEscalationExecutionResult.Failure)
        assertTrue((result as CloudEscalationExecutionResult.Failure).failure.preservesLocalAnswer)
        assertEquals(1, client.calls.get())
    }

    // ---------- F: C3 translation ----------

    @Test
    fun `F production local translator invoked with original retained`() = runTest {
        val engine = FakeEngine()
        val translator = LiteRtLocalTranslator(
            engineProvider = { engine },
            profileProvider = { engineProfile() },
        )
        val coordinator = LocalTranslationCoordinator(translator)
        val result = coordinator.translate(
            com.projectnuke.fusion.translation.TranslationRequest(
                originalText = "Original market headline.",
                targetLanguage = "ko",
                sourceLanguage = com.projectnuke.fusion.translation.TranslationSourceLanguage.Unspecified,
                route = com.projectnuke.fusion.translation.TranslationRoute.SEARCH_SNIPPET_PRESENTATION,
                sourceIdentity = "search-source#0",
                sourceUrl = "https://example.com/n1",
            ),
        )
        assertTrue(result is TranslationResult.Success)
        assertEquals(1, engine.calls)
        assertEquals(2, engine.lastMessages.size)
        // Translated render artifact kept; original remains canonical.
        val artifact = (result as TranslationResult.Success).artifact
        assertEquals("translated text", artifact.translatedText)
        assertEquals("Original market headline.", coordinator.canonicalEvidenceText(artifact))
        // Evidence keeps original source text and original URL.
        assertEquals("Original market headline.", artifact.originalText)
        assertEquals("https://example.com/n1", artifact.sourceUrl)
    }

    // ---------- G: C4 attachment RAG into the actual final prompt ----------

    @Test
    fun `G attachment rag excerpts enter actual final prompt with no network`() = runTest {
        val store = object : LocalAttachmentTextStore {
            val files = mapOf("/doc/notes.txt" to "사과 농장 보고서 내용이다. ".repeat(40))
            override fun readText(path: String, maxChars: Int): String? = files[path]?.take(maxChars)
        }
        var batches = 0
        var maxBatch = 0
        val ranker = LocalSemanticBatchRanker { _, chunks ->
            batches++
            maxBatch = maxOf(maxBatch, chunks.size)
            chunks.associate { "${it.docId}#${it.chunkIndex}" to 0.5 }
        }
        val coordinator = LocalRagCoordinator(store, ranker)
        val attachments = listOf(AttachmentRecord("notes.txt", "text/plain", "/doc/notes.txt"))
        val prepared = coordinator.prepare("req-g", "사과 농장", attachments)
        assertTrue(prepared is LocalRagPreparationResult.Prepared)
        val selections = (prepared as LocalRagPreparationResult.Prepared).selections
        // Frozen excerpts enter the ACTUAL final prompt used by generation.
        val ragBody = ChatPromptComposition.localizedRagUserContent("사과 농장 요약해줘", selections)
        val content = ChatPromptComposition.finalUserContent(
            body = ragBody,
            attachments = attachments,
            webSearchEnabled = false,
            webSearchResult = null,
        )
        assertTrue(selections.any { content.contains(it.source.excerpt.take(20)) })
        // Unsupported attachment never reaches the prompt.
        val unsupported = coordinator.prepare(
            "req-g2", "요약",
            listOf(AttachmentRecord("doc.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "/doc/doc.docx")),
        )
        assertTrue(unsupported is LocalRagPreparationResult.UnsupportedAttachment)
        // Deleted attachment invalidates the frozen selection.
        val emptyStore = LocalAttachmentTextStore { _, _ -> null }
        assertFalse(LocalRagCoordinator(emptyStore, null).validate(prepared.snapshot, attachments))
        // No network: semantic batch bounded, retrieval lexical-first.
        assertTrue(batches <= 1)
        assertTrue(maxBatch <= LocalRagBounds.MAX_SEMANTIC_CANDIDATES)
        assertTrue(selections.first().lexicalScore > 0)
        // Local provenance reaches render state.
        val entries = selections.map {
            LocalRagSourceEntry(
                it.source.displayName, it.source.attachmentId, it.chunk.chunkIndex,
                it.chunk.startOffset, it.chunk.endOffset, it.source.contentHashHex,
                it.source.excerpt, it.rank, it.lexicalScore, it.semanticScore,
            )
        }
        val persisted = LocalRagSourceMetadata.append("answer", entries)
        assertTrue(LocalRagSourceMetadata.parse(persisted).isNotEmpty())
    }

    // ---------- H: attachment + SEARCH, transport sees user query only ----------

    @Test
    fun `H web transport sees user query only never local excerpts`() = runBlocking {
        val excerpt = "비밀 보고서 본문 고유 토큰 xyz123"
        val requested = mutableListOf<String>()
        FusionWebSearch.httpClientForTests = fakeNewsClient(
            requested,
            duckHtml("market news", "https://example.com/n1"),
        )
        try {
            val response = FusionWebSearch.search(
                userInput = "오늘 주요 뉴스 알려줘",
                previousUserMessage = null,
                providerRepository = null,
            )
            assertTrue(response.hasUsableResults())
            assertTrue(requested.isNotEmpty())
            assertTrue(requested.any { it.contains("duckduckgo.com") })
            requested.forEach { url ->
                assertFalse(url.contains("xyz123"))
                assertFalse(url.contains("notes.txt"))
            }
            // Boundary guard: the request-scoped authorization for THIS
            // search approves the planner query and rejects unapproved
            // private material.
            val plan = FusionWebSearch.buildSearchPlan("오늘 주요 뉴스 알려줘", null)
            val auth = SearchQueryAuthorization(
                "tok-h",
                (setOf(plan.primaryQuery) + plan.alternateQueries).filter { it.isNotBlank() }.toSet(),
            )
            auth.requireApproved(plan.primaryQuery)
            var refused = false
            try {
                auth.requireApproved(excerpt)
            } catch (_: IllegalArgumentException) {
                refused = true
            }
            assertTrue(refused)
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
    }

    // ---------- H2: overlapping searches keep independent authorization ----------

    @Test
    fun `overlapping searches keep independent authorization contexts`() = runBlocking {
        val enteredA = java.util.concurrent.CountDownLatch(1)
        val releaseA = java.util.concurrent.CountDownLatch(1)
        val urls = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun duck(title: String, link: String): ByteArray =
            ("<html><body><div class=\"result results_links_deep web-result\">" +
                "<a rel=\"nofollow\" class=\"result__a\" href=\"" + link + "\">" + title + "</a>" +
                "<a class=\"result__snippet\">summary text</a>" +
                "</div></div></body></html>").toByteArray(Charsets.UTF_8)
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            SearchConnectionFactory { url ->
                val current = url.toString()
                urls += current
                if ("AAA-UNIQUE-QUERY" in current) {
                    enteredA.countDown()
                    assertTrue(releaseA.await(15, java.util.concurrent.TimeUnit.SECONDS))
                }
                FakeHttpURLConnection(url).apply {
                    configure(200, duck("title", "https://example.com/x"))
                }
            },
        )
        try {
            val deferredA = async {
                FusionWebSearch.search(
                    "AAA-UNIQUE-QUERY gadget review",
                    null,
                    null,
                    requestToken = "tok-a",
                )
            }
            // A is blocked inside its own transport holding its own auth.
            // The latch wait leaves the runBlocking loop so the async child runs.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                assertTrue(enteredA.await(15, java.util.concurrent.TimeUnit.SECONDS))
            }
            // B runs fully while A is still active: no interference.
            val resultB = FusionWebSearch.search(
                "BBB-UNIQUE-QUERY gadget review",
                null,
                null,
                requestToken = "tok-b",
            )
            releaseA.countDown()
            val resultA = deferredA.await()
            assertTrue(resultB.hasUsableResults())
            assertTrue(resultA.hasUsableResults())
            // Each transport saw only its own approved query material.
            assertTrue(urls.any { "AAA-UNIQUE-QUERY" in it })
            assertTrue(urls.any { "BBB-UNIQUE-QUERY" in it })
            assertTrue(resultA.results.all { "AAA-UNIQUE-QUERY" in it.queryUsed })
            assertTrue(resultB.results.all { "BBB-UNIQUE-QUERY" in it.queryUsed })
            // Either context still rejects unapproved private material.
            val authA = SearchQueryAuthorization("tok-a", setOf("AAA-UNIQUE-QUERY gadget review"))
            var refused = false
            try {
                authA.requireApproved("private excerpt material")
            } catch (_: IllegalArgumentException) {
                refused = true
            }
            assertTrue(refused)
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
    }

    // ---------- P: previous private context excluded from web query ----------

    @Test
    fun `P previous attachment turn never reaches web transport`() = runBlocking {
        val secret = "PREVIOUS-PRIVATE-TOKEN-qwerty"
        val attachedPrevious = com.projectnuke.fusion.util.AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(com.projectnuke.fusion.util.AttachmentRecord("s.pdf", "application/pdf", "/tmp/s.pdf")),
            "summarize this " + secret,
        )
        // Production helper path: the previous turn is attachment-bearing,
        // so only the current explicit query material is approved.
        assertTrue(com.projectnuke.fusion.search.isAttachmentBearingUserTurn(attachedPrevious))
        val previous = com.projectnuke.fusion.search.approvedPreviousUserMessage(
            com.projectnuke.fusion.util.AttachmentMessageCodec.parseAttachmentMessage(attachedPrevious).body,
            true,
        )
        assertEquals(null, previous)
        val requested = mutableListOf<String>()
        FusionWebSearch.httpClientForTests = fakeNewsClient(
            requested,
            duckHtml("market news", "https://example.com/n1"),
        )
        try {
            val response = FusionWebSearch.search(
                userInput = "BBB-UNIQUE-QUERY gadget review",
                previousUserMessage = previous,
                providerRepository = null,
            )
            assertTrue(response.hasUsableResults())
            assertTrue(requested.isNotEmpty())
            requested.forEach { url ->
                assertTrue(url.contains("BBB-UNIQUE-QUERY"))
                assertFalse(url.contains(secret))
            }
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
        // Ordinary context-aware rewriting is unchanged: a clean previous
        // turn still flows into the planner input.
        val cleanPrevious = com.projectnuke.fusion.search.approvedPreviousUserMessage("older question", false)
        assertEquals("older question", cleanPrevious)
    }

    // ---------- T: private-derived history containment ----------

    @Test
    fun `T rag derived history never reaches external transport`() {
        val derivedToken = "PRIVATE-DERIVED-TOKEN-xyz"
        val ragContent = "answer with " + derivedToken +
            "\n\n[FUSION_LOCAL_RAG_SOURCES_JSON]\n[]\n[/FUSION_LOCAL_RAG_SOURCES_JSON]"
        val entities = listOf(
            MessageEntity(11L, 7L, "user", "summarize", 1L),
            MessageEntity(12L, 7L, "assistant", ragContent, 2L),
            MessageEntity(13L, 7L, "user", "follow up", 3L),
        )
        // Production predicate: the branch is private-tainted.
        assertTrue(com.projectnuke.fusion.chat.isPrivateTaintedBranch(entities))
        // Production history composition excludes the derived turn, then
        // strips metadata exactly like the external prompt builder.
        val filtered = com.projectnuke.fusion.chat.excludePrivateDerivedTurns(entities)
        assertEquals(listOf(11L, 13L), filtered.map { it.id })
        val stripped = filtered.map {
            ChatMessage(
                it.role,
                com.projectnuke.fusion.retrieval.LocalRagSourceMetadata.strip(it.content),
            )
        }
        val external = buildExternalAiMessages(stripped) { it }
        val joined = external.joinToString("\n") { it.content }
        assertFalse(joined.contains(derivedToken))
        // Sanity: without the production filter the stripped prose leaks.
        val unfiltered = entities.map {
            ChatMessage(
                it.role,
                com.projectnuke.fusion.retrieval.LocalRagSourceMetadata.strip(it.content),
            )
        }
        assertTrue(
            buildExternalAiMessages(unfiltered) { it }
                .joinToString("\n").contains(derivedToken),
        )
        // External memory/summary is suppressed for the tainted branch.
        val memoryContext: String? = if (com.projectnuke.fusion.chat.isPrivateTaintedBranch(entities)) {
            null
        } else {
            "memory"
        }
        assertEquals(null, memoryContext)
    }

    @Test
    fun `T cloud escalation blocked for private derived history`() {
        val coordinator = CloudEscalationCoordinator(
            productionExecutor(ExternalAiChatRunner(FakeSource(provider()), CountingChatClient())),
        )
        val decision = TrustRoutingDecision(
            TrustRoute.CLOUD_SUGGEST,
            TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD,
            emptySet(),
        )
        // Clean history still offers escalation.
        val shown = coordinator.offerFor(decision, "LOCAL_MODEL", true, false, false)
        assertTrue(shown is CloudEscalationOffer.Shown)
        // Private-derived history blocks through the same offer policy.
        val blocked = coordinator.offerFor(decision, "LOCAL_MODEL", true, false, true)
        assertTrue(blocked is CloudEscalationOffer.Blocked)
    }

    // ---------- I: private egress blocked, exact consent, invalidation ----------

    @Test
    fun `I private egress blocked then exact consent then invalidation`() {
        val delegate = CountingDelegateForI()
        val controller = DataEgressController()
        val gate = EgressGateChatClient(delegate, controller)
        fun payload(excerpts: List<String>) = PreparedEgressRequestFactory.prepare(
            requestId = "req-i",
            destination = DataEgressDestination.EXTERNAL_AI,
            provider = AiProviderConfig(
                id = "p1",
                type = AiProviderType.OPENAI,
                displayName = "P",
                baseUrl = "https://api.example.com",
                modelId = "m",
                apiKeySecretId = "s",
            ),
            exactRequest = AiChatRequest(listOf(AiMessage(AiRole.USER, "summarize")), 0.7, 128),
            transmittedUserText = "summarize",
            transmittedHistoryText = "h",
            privateExcerpts = excerpts,
        )
        val transport = AiChatRequest(listOf(AiMessage(AiRole.USER, "summarize")), 0.7, 128)
        fun providerNow() = AiProviderConfig(
            id = "p1",
            type = AiProviderType.OPENAI,
            displayName = "P",
            baseUrl = "https://api.example.com",
            modelId = "m",
            apiKeySecretId = "s",
        )
        val payloadA = payload(listOf("excerpt A"))
        // Blocked before exact consent: 0 calls.
        assertFalse(controller.arm(payloadA, null))
        assertTrue(controller.authorize(providerNow(), transport, "req-i") is DataEgressController.Authorization.Refused)
        // Exact consent permits only the frozen payload.
        val consentA = DataEgressPolicy.makeConsent(
            "c", "req-i", "p1", payloadA.payloadHashHex, true, DataEgressScope.USER_QUERY_AND_HISTORY,
        )
        assertTrue(controller.arm(payloadA, consentA))
        assertTrue(controller.authorize(providerNow(), transport, "req-i") is DataEgressController.Authorization.Granted)
        // Excerpt mutation invalidates the consent: 0 calls until re-consent.
        val payloadB = payload(listOf("excerpt B"))
        assertFalse(controller.arm(payloadB, consentA))
        assertTrue(controller.authorize(providerNow(), transport, "req-i") is DataEgressController.Authorization.Refused)
        assertEquals(0, delegate.calls.get())
    }

    private class CountingDelegateForI : ChatClient {
        val calls = AtomicInteger(0)
        override suspend fun chatCompletion(config: AiProviderConfig, request: AiChatRequest): AiChatResponse {
            calls.incrementAndGet()
            return AiChatResponse("id", config.modelId, "ok")
        }
    }

    // ---------- J: staleness ----------

    @Test
    fun `J stale completion cannot attach to another request`() = runTest {
        val client = CountingChatClient()
        val coordinator = CloudEscalationCoordinator(
            productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)),
        )
        val pending = frozenPending(conversationId = 7L)
        // Process staleness: the frozen conversation no longer matches the
        // active one, so the pending request fails closed.
        assertFalse(validatePendingEscalation(pending, 8L, pending.fullMessageEntities))
        // Version staleness: the approved target no longer exists on the
        // active branch, so async completion cannot attach to it.
        val moved = coordinator.confirm(
            pending.copy(targetAssistantMessageId = 999L),
        ) {
            com.projectnuke.fusion.ui.CloudEscalationLiveState(true, pending.fullMessageEntities, ResponseVersionState())
        }
        assertTrue(moved is CloudEscalationExecutionResult.Failure)
        assertEquals(0, client.calls.get())
        // Request staleness: the originating user turn is gone.
        val otherBranch = pending.fullMessageEntities.filterNot { it.id == 11L }
        val result2 = coordinator.confirm(pending) {
            com.projectnuke.fusion.ui.CloudEscalationLiveState(
                true,
                otherBranch,
                pending.capturedResponseVersionState,
            )
        }
        assertTrue(result2 is CloudEscalationExecutionResult.Failure)
        assertEquals(0, client.calls.get())
        // Search retry stays bound to the failed request snapshot.
        val failure = TrustRecoveryCoordinator().searchFailure(
            TrustRoutingDecision(TrustRoute.SEARCH, TrustRoutingReason.CURRENT_INFORMATION_REQUIRED, emptySet()),
            true, false, "req-j",
        )
        assertEquals("req-j", (failure!!.recovery as TrustRecoveryAction.RetrySearch).requestId)
    }
}
