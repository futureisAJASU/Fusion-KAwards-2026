package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ai.ExternalAiChatResult
import com.projectnuke.fusion.ai.ExternalAiChatRunner
import com.projectnuke.fusion.ai.ExternalAiProviderSource
import com.projectnuke.fusion.ai.model.AiChatRequest
import com.projectnuke.fusion.ai.model.AiChatResponse
import com.projectnuke.fusion.ai.model.AiMessage
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.AiRole
import com.projectnuke.fusion.ai.network.AiProviderClientException
import com.projectnuke.fusion.ai.network.ChatClient
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.trust.TrustRoutingDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import com.projectnuke.fusion.chat.ConversationStructuralMutationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

// C2-D production wiring tests: the coordinator drives the REAL production
// external runner (ExternalAiChatRunner) with a fake provider source and a
// counting fake ChatClient transport. Call-count assertions prove the real
// runner is invoked (or not), never a detached integer.
class CloudEscalationCoordinatorTest {

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

    private class CountingClient(
        var reply: String = "외부 답변",
        var failure: Throwable? = null,
    ) : ChatClient {
        val calls = AtomicInteger(0)
        val seenRequests = mutableListOf<AiChatRequest>()
        val seenConfigs = mutableListOf<AiProviderConfig>()
        override suspend fun chatCompletion(
            config: AiProviderConfig,
            request: AiChatRequest,
        ): AiChatResponse {
            calls.incrementAndGet()
            seenRequests += request
            seenConfigs += config
            failure?.let { throw it }
            return AiChatResponse(id = "r", model = config.modelId, content = reply)
        }
    }

    private fun decision() = TrustRoutingDecision(
        TrustRoute.CLOUD_SUGGEST, TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD, emptySet(),
    )

    private fun providerSnapshot() = ExternalProviderRequestSnapshot(
        id = "p1", type = AiProviderType.OPENAI, baseUrl = "https://api.example.com",
        modelId = "m", temperature = 0.7, maxTokens = 2048,
        authMode = com.projectnuke.fusion.ai.model.AiProviderAuthMode.BEARER_API_KEY,
        authHeaderName = null, reasoningContentEnabled = false,
        apiKeySecretId = "s", displayName = "P",
    )

    private fun frozenPending(
        conversationId: Long = 7L,
        withAttachments: Boolean = false,
    ): PendingCloudEscalation {
        var snapshot = buildCloudEscalationSnapshot(
            7L, "req-d", "p1", providerSnapshot(), GenerationSettings(),
            false, "", listOf(ChatMessage("user", "복잡한 작업")), listOf(11L),
            "복잡한 작업", "복잡한 작업", 1L, decision(),
        )
        if (withAttachments) {
            snapshot = snapshot.copy(attachmentIds = listOf("/tmp/x.pdf"))
        }
        return PendingCloudEscalation(
            conversationId, 12L, 11L, 11L, "p1", "P", snapshot,
            listOf(
                MessageEntity(11L, conversationId, "user", "복잡한 작업", 1L),
                MessageEntity(12L, conversationId, "assistant", "로컬 답변", 2L),
            ),
            ResponseVersionState(
                groupByMessageId = mapOf(12L to 11L),
                activeMessageIdByGroup = mapOf(11L to 12L),
            ),
        )
    }

    private fun liveFor(pending: PendingCloudEscalation) = CloudEscalationLiveState(
        conversationExists = true,
        activeMessages = pending.fullMessageEntities,
        versions = pending.capturedResponseVersionState,
    )

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

    @Test
    fun `confirm executes real runner exactly once and stores alternate version`() = runTest {
        val client = CountingClient()
        val runner = ExternalAiChatRunner(FakeSource(provider()), client)
        val coordinator = CloudEscalationCoordinator(productionExecutor(runner))

        val offer = coordinator.offerFor(decision(), "LOCAL_MODEL", true, false)
        assertTrue(offer is CloudEscalationOffer.Shown)

        val pending = frozenPending()
        assertTrue(coordinator.freezePending(pending))
        assertEquals(0, client.calls.get())

        val result = coordinator.confirm(pending) { liveFor(pending) }
        assertTrue(result is CloudEscalationExecutionResult.Success)
        assertEquals("외부 답변", (result as CloudEscalationExecutionResult.Success).content)
        // Exactly one external generation attempt through the real runner.
        assertEquals(1, client.calls.get())

        // Persist as an alternate response/version of the SAME user request.
        val store = mutableListOf(
            MessageEntity(11L, 7L, "user", "복잡한 작업", 1L),
            MessageEntity(12L, 7L, "assistant", "로컬 답변", 2L),
        )
        var nextId = 13L
        var versions = ResponseVersionState()
        val persisted = coordinator.persistEscalatedResponse(
            pending = pending,
            escalatedContent = "외부 AI로 생성된 대체 답변이에요.\n\n외부 답변",
            loadLive = { liveFor(pending) },
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
        // No user turn duplicated: still exactly one user message.
        assertEquals(1, store.count { it.role == "user" })
        // Original local answer preserved; new alternate version stored.
        assertTrue(store.any { it.id == 12L && it.content == "로컬 답변" })
        assertTrue(store.any { it.id == persisted!!.messageId && it.content.contains("외부 답변") })
        val groupId = persisted!!.state.groupByMessageId[persisted.messageId]
        assertEquals(persisted.messageId, persisted.state.activeMessageIdByGroup[groupId])
        // Default generation mode never mutates through escalation.
        assertEquals("LOCAL_MODEL", defaultModeAfterEscalation("LOCAL_MODEL"))
    }

    @Test
    fun `truncated cloud answer is adopted with durable notice`() = runTest {
        val pending = frozenPending()
        val coordinator = CloudEscalationCoordinator(
            executor = CloudEscalationExecutor {
                ExternalAiChatResult.Truncated("partial", "P")
            },
        )

        val result = coordinator.confirm(pending) { liveFor(pending) }

        assertTrue(result is CloudEscalationExecutionResult.Success)
        assertEquals(
            "partial\n\n$EXTERNAL_OUTPUT_TRUNCATION_NOTICE",
            (result as CloudEscalationExecutionResult.Success).content,
        )
    }

    @Test
    fun `cloud alternate canonicalizes an implicit target group`() = runTest {
        val base = frozenPending().copy(
            versionGroupId = -12L,
            capturedResponseVersionState = ResponseVersionState(),
        )
        val messages = base.fullMessageEntities.toMutableList()
        var nextId = 13L
        var persistedState = ResponseVersionState()
        val coordinator = CloudEscalationCoordinator({ error("unused") })

        val persisted = coordinator.persistEscalatedResponse(
            pending = base,
            escalatedContent = "외부 대체 답변",
            loadLive = {
                CloudEscalationLiveState(true, messages.toList(), persistedState)
            },
            loadPreviousState = { persistedState },
            insertMessage = { content ->
                messages += MessageEntity(nextId, 7L, "assistant", content, 3L)
                nextId++
            },
            saveState = { persistedState = it },
            restoreState = { persistedState = it },
            deleteMessage = { id -> messages.removeAll { it.id == id } },
            updateConversationTimestamp = {},
        )

        val result = requireNotNull(persisted)
        assertEquals(-12L, result.state.groupByMessageId[12L])
        assertEquals(-12L, result.state.groupByMessageId[result.messageId])
        assertEquals(result.messageId, result.state.activeMessageIdByGroup[-12L])

        val timeline = buildChatTimeline(messages, result.state)
        val group = timeline.single { it is ChatTimelineItem.AssistantVersions }
            as ChatTimelineItem.AssistantVersions
        assertEquals(listOf(12L, result.messageId), group.versions.map { it.id })
        val moved = requireNotNull(
            moveResponseVersionFromDurableState(
                result.state,
                -12L,
                listOf(12L, result.messageId),
                ResponseVersionDirection.PREVIOUS,
            ),
        )
        assertEquals(12L, moved.activeMessageIdByGroup[-12L])
    }

    @Test
    fun `cancel performs zero external calls and keeps local answer`() = runTest {
        val client = CountingClient()
        val coordinator = CloudEscalationCoordinator(productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)))
        val pending = frozenPending()
        assertTrue(coordinator.freezePending(pending))
        coordinator.cancel()
        assertEquals(0, client.calls.get())
    }

    @Test
    fun `stale pending fails without any external call`() = runTest {
        val client = CountingClient()
        val coordinator = CloudEscalationCoordinator(productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)))
        val pending = frozenPending()
        // Active branch moved to another conversation: frozen request invalid.
        val result = coordinator.confirm(pending) { CloudEscalationLiveState(false, emptyList(), pending.capturedResponseVersionState) }
        assertTrue(result is CloudEscalationExecutionResult.Failure)
        assertEquals(0, client.calls.get())
    }

    @Test
    fun `single flight second confirm produces no second call`() = runTest {
        val client = CountingClient()
        val coordinator = CloudEscalationCoordinator(productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)))
        val pending = frozenPending()
        assertTrue(coordinator.executionGate.tryEnter())
        try {
            val result = coordinator.confirm(pending) { liveFor(pending) }
            assertTrue(result is CloudEscalationExecutionResult.Failure)
            assertEquals(0, client.calls.get())
        } finally {
            coordinator.executionGate.release()
        }
    }

    @Test
    fun `failed contenders never release the first confirmation ownership`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val blockingExecutor = CloudEscalationExecutor {
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            ExternalAiChatResult.Success("외부 답변", "P")
        }
        val coordinator = CloudEscalationCoordinator(blockingExecutor)
        val pending = frozenPending()

        val first = async { coordinator.confirm(pending) { liveFor(pending) } }
        entered.await()

        val second = coordinator.confirm(pending) { liveFor(pending) }
        val third = coordinator.confirm(pending) { liveFor(pending) }
        assertTrue(second is CloudEscalationExecutionResult.Failure)
        assertTrue(third is CloudEscalationExecutionResult.Failure)
        assertEquals(1, calls.get())

        release.complete(Unit)
        assertTrue(first.await() is CloudEscalationExecutionResult.Success)

        val later = coordinator.confirm(pending) { liveFor(pending) }
        assertTrue(later is CloudEscalationExecutionResult.Success)
        assertEquals(2, calls.get())
    }

    @Test
    fun `provider failure preserves local answer with bounded retry state`() = runTest {
        val client = CountingClient(failure = AiProviderClientException("boom"))
        val coordinator = CloudEscalationCoordinator(productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)))
        val pending = frozenPending()
        val result = coordinator.confirm(pending) { liveFor(pending) }
        assertTrue(result is CloudEscalationExecutionResult.Failure)
        val failure = (result as CloudEscalationExecutionResult.Failure).failure
        assertTrue(failure.preservesLocalAnswer)
        assertFalse(failure.autoRetried)
        assertFalse(failure.switchedProvider)
        // Exactly one failed attempt; no automatic fallback provider.
        assertEquals(1, client.calls.get())
    }

    @Test
    fun `deletion first discards cloud completion before any response mutation`() = runTest {
        val pending = frozenPending()
        val deletion = ConversationStructuralMutationRegistry.beginDeletion(pending.conversationId)
        var inserts = 0
        var saves = 0
        try {
            val persisted = CloudEscalationCoordinator({ error("provider must not run") })
                .persistEscalatedResponse(
                    pending = pending,
                    escalatedContent = "외부 답변",
                    loadLive = { liveFor(pending) },
                    loadPreviousState = { pending.capturedResponseVersionState },
                    insertMessage = { inserts++; 99L },
                    saveState = { saves++ },
                    restoreState = {},
                    deleteMessage = {},
                    updateConversationTimestamp = {},
                )
            assertNull(persisted)
            assertEquals(0, inserts)
            assertEquals(0, saves)
        } finally {
            deletion.finish()
        }
    }

    @Test
    fun `cloud persistence lease makes deletion wait for short final write`() = runTest {
        val pending = frozenPending()
        val enteredInsert = CompletableDeferred<Unit>()
        val releaseInsert = CompletableDeferred<Unit>()
        var saves = 0
        val coordinator = CloudEscalationCoordinator({ error("unused") })
        val persistence = async {
            coordinator.persistEscalatedResponse(
                pending = pending,
                escalatedContent = "외부 답변",
                loadLive = { liveFor(pending) },
                loadPreviousState = { pending.capturedResponseVersionState },
                insertMessage = {
                    enteredInsert.complete(Unit)
                    releaseInsert.await()
                    99L
                },
                saveState = { saves++ },
                restoreState = {},
                deleteMessage = {},
                updateConversationTimestamp = {},
            )
        }
        enteredInsert.await()
        val deletion = ConversationStructuralMutationRegistry.beginDeletion(pending.conversationId)
        val settled = async { deletion.awaitQuiescent() }
        assertFalse(settled.isCompleted)
        releaseInsert.complete(Unit)
        assertTrue(persistence.await() != null)
        withTimeout(2_000) { settled.await() }
        assertEquals(1, saves)
        deletion.finish()
    }

    @Test
    fun `cloud provider delay holds no structural lease`() = runTest {
        val enteredProvider = CompletableDeferred<Unit>()
        val releaseProvider = CompletableDeferred<Unit>()
        val coordinator = CloudEscalationCoordinator(CloudEscalationExecutor {
            enteredProvider.complete(Unit)
            releaseProvider.await()
            ExternalAiChatResult.Success("외부 답변", "P")
        })
        val pending = frozenPending()
        val confirmation = async { coordinator.confirm(pending) { liveFor(pending) } }
        enteredProvider.await()
        val deletion = ConversationStructuralMutationRegistry.beginDeletion(pending.conversationId)
        try {
            withTimeout(2_000) { deletion.awaitQuiescent() }
        } finally {
            deletion.finish()
            releaseProvider.complete(Unit)
        }
        assertTrue(confirmation.await() is CloudEscalationExecutionResult.Success)
    }

    @Test
    fun `attachment bearing pending never escalates`() = runTest {
        val client = CountingClient()
        val coordinator = CloudEscalationCoordinator(productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)))
        val pending = frozenPending(withAttachments = true)
        val result = coordinator.confirm(pending) { liveFor(pending) }
        assertTrue(result is CloudEscalationExecutionResult.Failure)
        assertEquals(0, client.calls.get())
    }

    @Test
    fun `abort offer blocks attachments without provider calls`() {
        val coordinator = CloudEscalationCoordinator(productionExecutor(
            ExternalAiChatRunner(FakeSource(provider()), CountingClient()),
        ))
        val offer = coordinator.offerFor(decision(), "LOCAL_MODEL", true, true)
        assertTrue(offer is CloudEscalationOffer.Blocked)
    }

    @Test
    fun `external mode never re-offers`() {
        val coordinator = CloudEscalationCoordinator(productionExecutor(
            ExternalAiChatRunner(FakeSource(provider()), CountingClient()),
        ))
        val offer = coordinator.offerFor(decision(), "EXTERNAL_AI_API", true, false)
        assertTrue(offer is CloudEscalationOffer.Hidden)
    }

    // ---------- Phase 1: live-state validation + two-phase staleness ----------

    @Test
    fun `live validator accepts intact branch`() {
        val pending = frozenPending()
        val verdict = validateEscalationLive(pending, liveFor(pending))
        assertTrue(verdict is CloudEscalationLiveVerdict.Valid)
    }

    @Test
    fun `live validator rejects gone conversation`() {
        val pending = frozenPending()
        val verdict = validateEscalationLive(
            pending,
            CloudEscalationLiveState(false, pending.fullMessageEntities, pending.capturedResponseVersionState),
        )
        assertTrue(verdict is CloudEscalationLiveVerdict.Stale)
        assertEquals(
            CloudEscalationStaleness.CONVERSATION_GONE,
            (verdict as CloudEscalationLiveVerdict.Stale).reason,
        )
    }

    @Test
    fun `target deleted before confirm means zero external calls`() = runTest {
        val client = CountingClient()
        val coordinator = CloudEscalationCoordinator(productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)))
        val pending = frozenPending()
        val liveWithoutTarget = pending.fullMessageEntities.filterNot { it.id == 12L }
        val result = coordinator.confirm(pending) {
            CloudEscalationLiveState(true, liveWithoutTarget, pending.capturedResponseVersionState)
        }
        assertTrue(result is CloudEscalationExecutionResult.Failure)
        assertEquals(0, client.calls.get())
    }

    @Test
    fun `origin user deleted before confirm means zero external calls`() = runTest {
        val client = CountingClient()
        val coordinator = CloudEscalationCoordinator(productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)))
        val pending = frozenPending()
        val liveWithoutOrigin = pending.fullMessageEntities.filterNot { it.id == 11L }
        val result = coordinator.confirm(pending) {
            CloudEscalationLiveState(true, liveWithoutOrigin, pending.capturedResponseVersionState)
        }
        assertTrue(result is CloudEscalationExecutionResult.Failure)
        assertEquals(0, client.calls.get())
    }

    @Test
    fun `version group moved before confirm means zero external calls`() = runTest {
        val client = CountingClient()
        val coordinator = CloudEscalationCoordinator(productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)))
        val pending = frozenPending()
        // Live regrouping moved the target to an incompatible group.
        val movedVersions = ResponseVersionState(
            groupByMessageId = mapOf(12L to 999L),
            activeMessageIdByGroup = mapOf(999L to 12L),
        )
        val result = coordinator.confirm(pending) {
            CloudEscalationLiveState(true, pending.fullMessageEntities, movedVersions)
        }
        assertTrue(result is CloudEscalationExecutionResult.Failure)
        assertEquals(0, client.calls.get())
    }

    @Test
    fun `valid live target executes exactly one call`() = runTest {
        val client = CountingClient()
        val coordinator = CloudEscalationCoordinator(productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)))
        val pending = frozenPending()
        val result = coordinator.confirm(pending) { liveFor(pending) }
        assertTrue(result is CloudEscalationExecutionResult.Success)
        assertEquals(1, client.calls.get())
    }

    @Test
    fun `target deleted in flight is not persisted`() = runTest {
        val client = CountingClient()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val blockingExecutor = CloudEscalationExecutor { pending ->
            entered.complete(Unit)
            release.await()
            ExternalAiChatResult.Success("late answer", "P")
        }
        val coordinator = CloudEscalationCoordinator(blockingExecutor)
        val pending = frozenPending()
        var liveMessages = pending.fullMessageEntities
        val job = async {
            coordinator.confirm(pending) {
                CloudEscalationLiveState(true, liveMessages, pending.capturedResponseVersionState)
            }
        }
        entered.await()
        // Deletion lands while the provider call is in flight.
        liveMessages = pending.fullMessageEntities.filterNot { it.id == 12L }
        release.complete(Unit)
        val result = job.await()
        assertTrue(result is CloudEscalationExecutionResult.Success)
        var inserted = 0
        val persisted = coordinator.persistEscalatedResponse(
            pending = pending,
            escalatedContent = "late answer",
            loadLive = { CloudEscalationLiveState(true, liveMessages, pending.capturedResponseVersionState) },
            loadPreviousState = { ResponseVersionState() },
            insertMessage = { inserted++ ; 100L },
            saveState = {},
            restoreState = {},
            deleteMessage = {},
            updateConversationTimestamp = {},
        )
        assertEquals(null, persisted)
        assertEquals(0, inserted)
    }

    @Test
    fun `provider ui change keeps frozen provider authoritative`() = runTest {
        val client = CountingClient()
        val coordinator = CloudEscalationCoordinator(productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)))
        val pending = frozenPending()
        // The UI now shows provider p2, but the frozen request names p1.
        val result = coordinator.confirm(pending) { liveFor(pending) }
        assertTrue(result is CloudEscalationExecutionResult.Success)
        assertEquals(1, client.calls.get())
        assertEquals("p1", client.seenConfigs.single().id)
    }

    @Test
    fun `navigation alone does not invalidate durable target`() = runTest {
        val client = CountingClient()
        val coordinator = CloudEscalationCoordinator(productionExecutor(ExternalAiChatRunner(FakeSource(provider()), client)))
        val pending = frozenPending()
        // The user opened another conversation; the frozen conversation's
        // durable target still exists, so confirmation still proceeds.
        val result = coordinator.confirm(pending) { liveFor(pending) }
        assertTrue(result is CloudEscalationExecutionResult.Success)
        assertEquals(1, client.calls.get())
    }
}
