package com.projectnuke.fusion.ui

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.AnswerContextRoute
import com.projectnuke.fusion.llm.GenerationOutcome
import com.projectnuke.fusion.llm.GenerationRequestKey
import com.projectnuke.fusion.llm.GenerationTerminationIntent
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.search.appendSearchSourcesMetadata
import com.projectnuke.fusion.retrieval.LocalDocumentChunk
import com.projectnuke.fusion.retrieval.LocalDocumentSource
import com.projectnuke.fusion.retrieval.LocalRagSelection
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResponsePersistenceCoordinatorTest {

    @Test
    fun `cancellation while waiting for persistence admission prevents insertion`() = runBlocking {
        val registry = com.projectnuke.fusion.chat.GenerationSessionRegistry()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val admissionEntered = CompletableDeferred<Unit>()
        val releaseAdmission = CompletableDeferred<Unit>()
        val key = GenerationRequestKey(103L, "cancel-during-admission")
        var inserted = false
        lateinit var session: com.projectnuke.fusion.chat.GenerationSession
        try {
            session = registry.start(scope, snapshot(103L, "cancel-during-admission")) {
                try {
                    persistAssistantMessage(
                        beforePersist = {
                            admissionEntered.complete(Unit)
                            releaseAdmission.await()
                            if (!registry.admitAssistantResponsePersistence(key)) {
                                throw kotlinx.coroutines.CancellationException("persistence admission lost")
                            }
                        },
                        insertMessage = { inserted = true; 1L },
                        updateConversationTimestamp = {},
                    )
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // The canceled owner may not enter the irreversible insert.
                }
            }
            admissionEntered.await()
            session.job.cancel(kotlinx.coroutines.CancellationException("request superseded"))
            releaseAdmission.complete(Unit)
            session.job.join()

            assertFalse(inserted)
        } finally {
            releaseAdmission.complete(Unit)
            scope.cancel()
        }
    }

    private fun receipt(
        route: AnswerContextRoute,
        docs: Int = 0,
        chunks: Int = 0,
        web: Int = 0,
    ) = AnswerContextReceipt.build(
        route = route,
        interactionMode = "NORMAL",
        priorConversationContextProvided = true,
        localRagDocumentCount = docs,
        localRagChunkCount = chunks,
        webSourceCount = web,
    )

    private fun ragSelection(docId: String, attachmentId: String, rank: Int) = LocalRagSelection(
        chunk = LocalDocumentChunk(
            docId = docId,
            displayName = "$docId.txt",
            chunkIndex = rank,
            startOffset = 0,
            endOffset = 10,
            contentHashHex = "hash-$docId-$rank",
            text = "chunk-$rank",
            pageNumber = null,
        ),
        source = LocalDocumentSource(
            docId = docId,
            displayName = "$docId.txt",
            attachmentId = attachmentId,
            chunkIndex = rank,
            startOffset = 0,
            endOffset = 10,
            contentHashHex = "hash-$docId-$rank",
            excerpt = "excerpt-$rank",
            pageNumber = null,
        ),
        lexicalScore = rank,
        semanticScore = null,
        rank = rank,
    )

    private fun receiptMarkerCount(content: String): Int =
        content.windowed(AnswerContextReceipt.START_MARKER.length, 1)
            .count { it == AnswerContextReceipt.START_MARKER }

    @Test
    fun `local successful stored answer contains exactly one local receipt`() {
        val content = storedAssistantContent(
            "local answer",
            TrustRoutingDecision.local(TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE),
            contextReceipt = receipt(AnswerContextRoute.LOCAL_MODEL),
        )

        assertEquals(1, receiptMarkerCount(content))
        assertEquals(AnswerContextRoute.LOCAL_MODEL, AnswerContextReceipt.parse(content)?.route)
    }

    @Test
    fun `external successful stored answer contains exactly one external receipt`() {
        val content = storedAssistantContent(
            "external answer",
            TrustRoutingDecision.local(TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE),
            contextReceipt = receipt(AnswerContextRoute.EXTERNAL_AI_API),
        )

        assertEquals(1, receiptMarkerCount(content))
        assertEquals(AnswerContextRoute.EXTERNAL_AI_API, AnswerContextReceipt.parse(content)?.route)
    }

    @Test
    fun `local rag writer records actual selected document and chunk counts`() {
        val selections = listOf(
            ragSelection("doc-a", "attachment-a", 1),
            ragSelection("doc-a", "attachment-a", 2),
            ragSelection("doc-b", "attachment-b", 1),
        )
        val content = storedAssistantContentWithLocalRag(
            "rag answer",
            TrustRoutingDecision.local(TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE),
            ragSelections = selections,
            contextReceipt = receipt(AnswerContextRoute.LOCAL_MODEL, docs = 2, chunks = 3),
        )
        val decoded = AnswerContextReceipt.parse(content)!!

        assertEquals(2, decoded.localRagDocumentCount)
        assertEquals(3, decoded.localRagChunkCount)
        assertEquals(3, LocalRagSourceMetadata.parse(content).size)
    }

    @Test
    fun `search writer records adopted source count`() {
        val sources = listOf(
            WebSearchSource("one", "example", "https://example.test/one", "one", null, WebSearchProviderType.FREE_DEFAULT, "test", null, "query", 1),
            WebSearchSource("two", "example", "https://example.test/two", "two", null, WebSearchProviderType.FREE_DEFAULT, "test", null, "query", 2),
        )
        val content = storedAssistantContent(
            "search answer",
            TrustRoutingDecision.local(TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE),
            sources,
            receipt(AnswerContextRoute.EXTERNAL_AI_API, web = sources.size),
        )

        assertEquals(sources.size, AnswerContextReceipt.parse(content)?.webSourceCount)
        assertEquals(sources.size, com.projectnuke.fusion.search.parseSearchSourcesMetadata(content).size)
    }

    @Test
    fun `failed no-source search does not claim sources used`() {
        val content = storedAssistantContent(
            "search unavailable",
            TrustRoutingDecision.local(TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE),
        )

        assertEquals(0, receiptMarkerCount(content))
        assertEquals(null, AnswerContextReceipt.parse(content))
        assertTrue(LocalRagSourceMetadata.parse(content).isEmpty())
    }

    @Test
    fun `version persistence composes a receipt for each regenerated answer`() = runBlocking {
        var first: String? = null
        var second: String? = null
        suspend fun persist(content: String, id: Long) {
            persistAssistantVersion(
                loadPreviousState = { "old" },
                buildCommitPlan = { ResponseVersionCommitPlan(11L, 7L) },
                insertMessage = { content.let { if (id == 1L) first = it else second = it }; id },
                buildUpdatedState = { previous, _, messageId -> "$previous+$messageId" },
                saveState = {},
                restoreState = {},
                deleteMessage = {},
                updateConversationTimestamp = {},
            )
        }
        persist(
            storedAssistantContent(
                "version one",
                TrustRoutingDecision.local(TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE),
                contextReceipt = receipt(AnswerContextRoute.LOCAL_MODEL),
            ),
            1L,
        )
        persist(
            storedAssistantContent(
                "version two",
                TrustRoutingDecision.local(TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE),
                contextReceipt = receipt(AnswerContextRoute.EXTERNAL_AI_API),
            ),
            2L,
        )

        assertEquals(AnswerContextRoute.LOCAL_MODEL, AnswerContextReceipt.parse(first!!)?.route)
        assertEquals(AnswerContextRoute.EXTERNAL_AI_API, AnswerContextReceipt.parse(second!!)?.route)
        assertEquals(1, receiptMarkerCount(first!!))
        assertEquals(1, receiptMarkerCount(second!!))
    }

    @Test
    fun `legacy answer without receipt remains supported`() {
        assertEquals(null, AnswerContextReceipt.parse("legacy answer"))
        assertEquals("legacy answer", com.projectnuke.fusion.util.visibleMessageContent("legacy answer"))
    }

    @Test
    fun `deterministic abstain persistence stores only its explicit zero-context receipt`() = runBlocking {
        val decision = TrustRoutingDecision(
            route = TrustRoute.ABSTAIN,
            reason = TrustRoutingReason.FUTURE_UNKNOWABLE,
            signals = emptySet(),
        )
        var stored: String? = null
        persistDeterministicTrustRoutingResponse(
            decision = decision,
            generationModeKey = "LOCAL_MODEL",
            contextReceipt = AnswerContextReceipt.build(
                route = AnswerContextRoute.DETERMINISTIC,
                interactionMode = "NORMAL",
                priorConversationContextProvided = false,
            ),
            insertMessage = { stored = it; 1L },
            updateConversationTimestamp = {},
        )

        val decoded = AnswerContextReceipt.parse(stored!!)
        assertEquals(AnswerContextRoute.DETERMINISTIC, decoded?.route)
        assertFalse(decoded!!.projectContextUsed)
        assertEquals(0, decoded.webSourceCount)
    }

    @Test
    fun `typed policy claims exact user stop ownership and rejects structural or stale ownership`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val registry = com.projectnuke.fusion.chat.GenerationSessionRegistry()
        val key = GenerationRequestKey(71L, "request-a")
        val release = CompletableDeferred<Unit>()
        try {
            val session = registry.start(scope, snapshot(71L, "request-a")) { release.await() }
            registry.cancellationController.recordIntent(key, GenerationTerminationIntent.USER_STOP)

            val eligible = decideUserStopPartial(
                registry,
                key,
                GenerationOutcome.Cancelled("  exact partial  ", GenerationTerminationIntent.USER_STOP),
            )
            assertEquals(UserStopPartialDecision.Eligible("exact partial"), eligible)
            assertEquals(
                UserStopPartialDecision.Ineligible,
                decideUserStopPartial(
                    registry,
                    key,
                    GenerationOutcome.Cancelled("second", GenerationTerminationIntent.USER_STOP),
                ),
            )

            release.complete(Unit)
            session.job.join()
            assertEquals(0, registry.recordedIntentCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `fallback snapshot is exact-requested and blank or deleted partials are rejected`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val registry = com.projectnuke.fusion.chat.GenerationSessionRegistry()
        val release = CompletableDeferred<Unit>()
        val key = GenerationRequestKey(72L, "request-a")
        try {
            val session = registry.start(scope, snapshot(72L, "request-a")) { release.await() }
            registry.cancellationController.recordIntent(key, GenerationTerminationIntent.USER_STOP)
            registry.cancellationController.publishPartial(key, "  A partial  ")
            registry.cancellationController.publishPartial(GenerationRequestKey(72L, "request-b"), "B partial")
            assertEquals("  A partial  ", registry.cancellationController.partialSnapshot(key))
            assertEquals(
                UserStopPartialDecision.Eligible("A partial"),
                decideUserStopPartial(
                    registry,
                    key,
                    GenerationOutcome.Cancelled(
                        partialText = "",
                        terminationIntent = GenerationTerminationIntent.USER_STOP,
                    ),
                    fallbackText = registry.cancellationController.partialSnapshot(key),
                ),
            )

            release.complete(Unit)
            session.job.join()

            val deletedRegistry = com.projectnuke.fusion.chat.GenerationSessionRegistry()
            val deletedRelease = CompletableDeferred<Unit>()
            val deletedKey = GenerationRequestKey(73L, "request-a")
            val deletedSession = deletedRegistry.start(scope, snapshot(73L, "request-a")) { deletedRelease.await() }
            deletedRegistry.cancellationController.recordIntent(deletedKey, GenerationTerminationIntent.USER_STOP)
            assertTrue(deletedRegistry.claimDeletionOwnership(73L, "delete"))
            assertEquals(
                UserStopPartialDecision.Ineligible,
                decideUserStopPartial(
                    deletedRegistry,
                    deletedKey,
                    GenerationOutcome.Cancelled("deleted", GenerationTerminationIntent.USER_STOP),
                ),
            )
            deletedRelease.complete(Unit)
            deletedSession.job.join()
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `native and fallback user stop use identical family sanitation`() = runBlocking {
        val raw = "<think>hidden</think>  한국 답변  "
        val adapter = FusionPromptAdapters.forFamily(ModelFamily.QWEN)
        val source = WebSearchSource(
            title = "source",
            source = "example",
            url = "https://example.test/source",
            snippet = "snippet",
            publishedAt = null,
            providerType = WebSearchProviderType.FREE_DEFAULT,
            providerDisplayName = "test",
            searchCategory = null,
            queryUsed = "query",
            rank = 1,
        )

        val nativeCancellationObserved = CompletableDeferred<Unit>()
        val nativeCancellationRelease = CompletableDeferred<Unit>()
        val fallbackCancellationObserved = CompletableDeferred<Unit>()
        val fallbackCancellationRelease = CompletableDeferred<Unit>()
        suspend fun createRegistry(
            requestId: String,
            cancellationObserved: CompletableDeferred<Unit>,
            cancellationRelease: CompletableDeferred<Unit>,
        ): Pair<com.projectnuke.fusion.chat.GenerationSessionRegistry, CoroutineScope> {
            val registry = com.projectnuke.fusion.chat.GenerationSessionRegistry()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val started = CompletableDeferred<Unit>()
            registry.start(scope, snapshot(75L, requestId)) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    cancellationObserved.complete(Unit)
                    withContext(NonCancellable) { cancellationRelease.await() }
                    throw cancelled
                }
            }
            withTimeout(2000) { started.await() }
            registry.cancellationController.recordIntent(
                GenerationRequestKey(75L, requestId),
                GenerationTerminationIntent.USER_STOP,
            )
            registry.cancellationController.publishPartial(
                GenerationRequestKey(75L, requestId),
                raw,
            )
            return registry to scope
        }

        val (nativeRegistry, nativeScope) = createRegistry(
            "native",
            nativeCancellationObserved,
            nativeCancellationRelease,
        )
        val (fallbackRegistry, fallbackScope) = createRegistry(
            "fallback",
            fallbackCancellationObserved,
            fallbackCancellationRelease,
        )
        try {
            assertTrue(nativeRegistry.cancel(75L, "native-user-stop"))
            withTimeout(2000) { nativeCancellationObserved.await() }
            assertTrue(fallbackRegistry.cancel(75L, "fallback-user-stop"))
            withTimeout(2000) { fallbackCancellationObserved.await() }
            val native = decideUserStopPartial(
                nativeRegistry,
                GenerationRequestKey(75L, "native"),
                GenerationOutcome.Cancelled(
                    adapter.sanitizeOutput(raw),
                    GenerationTerminationIntent.USER_STOP,
                ),
                candidateAlreadySanitized = true,
            )
            var persisted: String? = null
            adoptUserStopFallback(
                registry = fallbackRegistry,
                requestKey = GenerationRequestKey(75L, "fallback"),
                sanitizeOutput = { text -> adapter.sanitizeOutput(text) },
            ) { persisted = appendSearchSourcesMetadata(it, listOf(source)) }
            assertEquals(UserStopPartialDecision.Eligible(adapter.sanitizeOutput(raw)), native)
            assertEquals(
                appendSearchSourcesMetadata(
                    (native as UserStopPartialDecision.Eligible).text,
                    listOf(source),
                ),
                persisted,
            )
        } finally {
            nativeCancellationRelease.complete(Unit)
            fallbackCancellationRelease.complete(Unit)
            nativeRegistry.cancellationController.clearRequest(GenerationRequestKey(75L, "native"))
            fallbackRegistry.cancellationController.clearRequest(GenerationRequestKey(75L, "fallback"))
            nativeScope.cancel()
            fallbackScope.cancel()
        }
    }

    @Test
    fun `unsupported family reasoning only fallback is not adopted`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val registry = com.projectnuke.fusion.chat.GenerationSessionRegistry()
        val key = GenerationRequestKey(76L, "blank")
        try {
            registry.start(scope, snapshot(76L, "blank")) { CompletableDeferred<Unit>().await() }
            registry.cancellationController.recordIntent(key, GenerationTerminationIntent.USER_STOP)
            registry.cancellationController.publishPartial(key, "<fusion_thinking>only hidden</fusion_thinking>")
            var persisted = false
            assertFalse(
                adoptUserStopFallback(
                    registry = registry,
                    requestKey = key,
                    sanitizeOutput = { text -> FusionPromptAdapters.forFamily(ModelFamily.KANANA).sanitizeOutput(text) },
                ) { persisted = true }
            )
            assertFalse(persisted)
        } finally {
            registry.cancellationController.clearRequest(key)
            scope.cancel()
        }
    }

    @Test
    fun `newer request blocks older user stop adoption`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val releaseB = CompletableDeferred<Unit>()
        val registry = com.projectnuke.fusion.chat.GenerationSessionRegistry()
        try {
            registry.start(scope, snapshot(74L, "request-a")) { CompletableDeferred<Unit>().await() }
            registry.start(scope, snapshot(74L, "request-b")) { releaseB.await() }
            val keyA = GenerationRequestKey(74L, "request-a")
            registry.cancellationController.recordIntent(keyA, GenerationTerminationIntent.USER_STOP)
            assertEquals(
                UserStopPartialDecision.Ineligible,
                decideUserStopPartial(
                    registry,
                    keyA,
                    GenerationOutcome.Cancelled("stale", GenerationTerminationIntent.USER_STOP),
                ),
            )
        } finally {
            releaseB.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `user stop partial persistence success settles the exact session`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val registry = com.projectnuke.fusion.chat.GenerationSessionRegistry()
        val key = GenerationRequestKey(77L, "request-a")
        val nativeOwner = Any()
        var persisted = false
        val entered = CompletableDeferred<Unit>()
        // Outcomes are recorded here and asserted in the test body after the
        // session job is joined: assertions must never run inside the detached
        // session coroutine, where a failure would escape as an uncaught
        // exception and poison unrelated tests instead of failing this one.
        var observedDecisionText: String? = null
        lateinit var session: com.projectnuke.fusion.chat.GenerationSession
        try {
            session = registry.start(scope, snapshot(77L, "request-a")) {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        when (val decision = decideUserStopPartial(
                            registry,
                            key,
                            GenerationOutcome.Cancelled("partial", GenerationTerminationIntent.USER_STOP),
                        )) {
                            is UserStopPartialDecision.Eligible -> {
                                persistAssistantMessage(
                                    insertMessage = { persisted = true; 1L },
                                    updateConversationTimestamp = {},
                                    lifecycleRequestKey = key,
                                )
                                observedDecisionText = decision.text
                            }
                            UserStopPartialDecision.Ineligible -> Unit
                        }
                    }
                }
            }
            entered.await()
            registry.cancellationController.registerCancellationHook(key, nativeOwner) {
                session.job.cancel(kotlinx.coroutines.CancellationException("native stop"))
            }

            assertTrue(registry.stopAndJoin(77L, "request-a"))
            session.job.join()
            assertTrue(persisted)
            assertEquals("partial", observedDecisionText)
            assertTrue(session.job.isCompleted)
            assertFalse(registry.hasLogicalRequest(77L))
            assertEquals(0, registry.recordedIntentCount())
        } finally {
            registry.cancellationController.unregisterCancellationHook(key, nativeOwner)
            scope.cancel()
        }
    }

    @Test
    fun `user stop partial persistence failure still settles the exact session`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val registry = com.projectnuke.fusion.chat.GenerationSessionRegistry()
        val key = GenerationRequestKey(78L, "request-a")
        val nativeOwner = Any()
        val expected = IllegalStateException("insert")
        val sessionBodyEntered = CompletableDeferred<Unit>()
        // Outcomes are recorded here and asserted in the test body after the
        // session job is joined: assertions must never run inside the detached
        // session coroutine, where a failure would escape as an uncaught
        // exception and poison unrelated tests instead of failing this one.
        var persistAttempted = false
        var observed: Throwable? = null
        var observedDecision: UserStopPartialDecision? = null
        lateinit var session: com.projectnuke.fusion.chat.GenerationSession
        try {
            session = registry.start(scope, snapshot(78L, "request-a")) {
                try {
                    sessionBodyEntered.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        val decision = decideUserStopPartial(
                            registry,
                            key,
                            GenerationOutcome.Cancelled("partial", GenerationTerminationIntent.USER_STOP),
                        )
                        observedDecision = decision
                        if (decision is UserStopPartialDecision.Eligible) {
                            persistAttempted = true
                            try {
                                persistAssistantMessage(
                                    insertMessage = { throw expected },
                                    updateConversationTimestamp = {},
                                    lifecycleRequestKey = key,
                                )
                            } catch (actual: IllegalStateException) {
                                observed = actual
                            }
                        }
                    }
                }
            }
            sessionBodyEntered.await()
            registry.cancellationController.registerCancellationHook(key, nativeOwner) {
                session.job.cancel(kotlinx.coroutines.CancellationException("native stop"))
            }

            assertTrue(registry.stopAndJoin(78L, "request-a"))
            session.job.join()
            assertTrue(observedDecision is UserStopPartialDecision.Eligible)
            assertTrue(persistAttempted)
            // Exception identity does not survive coroutine dispatch: the
            // stack-trace recovery machinery may deliver a copy (same message,
            // cause chain intact) instead of the original instance. Assert on
            // the failure's content, never on its identity.
            assertTrue(observed is IllegalStateException)
            assertEquals(expected.message, (observed as IllegalStateException).message)
            assertTrue(session.job.isCompleted)
            assertFalse(registry.hasLogicalRequest(78L))
            assertEquals(0, registry.recordedIntentCount())
        } finally {
            registry.cancellationController.unregisterCancellationHook(key, nativeOwner)
            scope.cancel()
        }
    }

    @Test
    fun `cancelled partial persistence caller completes its noncancellable write`() = runBlocking {
        val key = GenerationRequestKey(79L, "request-a")
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val writerScope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val writer = writerScope.async {
                persistAssistantMessage(
                    insertMessage = {
                        writeStarted.complete(Unit)
                        releaseWrite.await()
                        1L
                    },
                    updateConversationTimestamp = {},
                    lifecycleRequestKey = key,
                )
            }
            writeStarted.await()
            writer.cancel()
            releaseWrite.complete(Unit)
            try {
                writer.await()
                fail("Expected caller cancellation")
            } catch (_: kotlinx.coroutines.CancellationException) {
                assertTrue(true)
            }
        } finally {
            writerScope.cancel()
        }
    }

    private fun snapshot(conversationId: Long, requestId: String) =
        com.projectnuke.fusion.chat.GenerationRequestSnapshot(
            requestId = requestId,
            conversationId = conversationId,
            generationModeKey = "TEST",
            selectedModelId = null,
            selectedModelPath = null,
            settings = com.projectnuke.fusion.model.GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = com.projectnuke.fusion.chat.GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "test",
            rawUserText = "test",
            createdAt = 0L,
        )

    @Test
    fun `timestamp failure keeps a successfully inserted answer`() = runBlocking {
        var deleted = false
        var reported: Throwable? = null
        val expected = IllegalStateException("timestamp")

        val id = persistAssistantMessage(
            insertMessage = { 41L },
            updateConversationTimestamp = { throw expected },
            onTimestampFailure = { reported = it },
        )

        assertEquals(41L, id)
        assertFalse(deleted)
        assertSame(expected, reported)
    }

    @Test
    fun `version save failure removes the new answer`() = runBlocking {
        val events = mutableListOf<String>()
        val expected = IllegalStateException("version save")

        try {
            persistAssistantVersion(
                loadPreviousState = { "old" },
                buildCommitPlan = { ResponseVersionCommitPlan(11L, 7L) },
                insertMessage = { events += "insert"; 9L },
                buildUpdatedState = { _, _, _ -> "new" },
                saveState = { events += "save"; throw expected },
                restoreState = { events += "restore" },
                deleteMessage = { events += "delete:$it" },
                updateConversationTimestamp = { events += "timestamp" },
            )
            fail("Expected version save failure")
        } catch (actual: IllegalStateException) {
            assertEquals(expected.message, actual.message)
        }

        assertEquals(listOf("insert", "save", "delete:9", "restore"), events)
    }

    @Test
    fun `timestamp failure keeps answer and version state`() = runBlocking {
        val events = mutableListOf<String>()
        var reported: Throwable? = null
        val expected = IllegalStateException("timestamp")

        val persisted = persistAssistantVersion(
            loadPreviousState = { "old" },
            buildCommitPlan = { ResponseVersionCommitPlan(11L, 7L) },
            insertMessage = { events += "insert"; 12L },
            buildUpdatedState = { previous, _, id -> "$previous+$id" },
            saveState = { events += "save:$it" },
            restoreState = { events += "restore:$it" },
            deleteMessage = { events += "delete:$it" },
            updateConversationTimestamp = { events += "timestamp"; throw expected },
            onTimestampFailure = { reported = it },
        )

        assertEquals(12L, persisted.messageId)
        assertEquals("old+12", persisted.state)
        assertEquals(listOf("insert", "save:old+12", "timestamp"), events)
        assertSame(expected, reported)
        assertTrue(events.none { it.startsWith("delete") || it.startsWith("restore") })
    }

    @Test
    fun `witness deletion failure keeps durable answer and version state`() = runBlocking {
        val events = mutableListOf<String>()
        var reported: Throwable? = null

        val persisted = persistAssistantVersion(
            loadPreviousState = { "old" },
            buildCommitPlan = { ResponseVersionCommitPlan(11L, 7L) },
            insertMessage = { events += "insert"; 13L },
            buildUpdatedState = { previous, _, id -> "$previous+$id" },
            saveState = { events += "save:$it" },
            restoreState = { events += "restore:$it" },
            deleteMessage = { events += "delete:$it" },
            deleteWitness = { events += "witness:$it"; false },
            updateConversationTimestamp = { events += "timestamp" },
            onWitnessDeletionFailure = { reported = it },
        )

        assertEquals(13L, persisted.messageId)
        assertEquals("old+13", persisted.state)
        assertEquals(listOf("insert", "save:old+13", "timestamp", "witness:13"), events)
        assertTrue(reported is IllegalStateException)
        assertTrue(events.none { it.startsWith("delete") || it.startsWith("restore") })
    }
}
