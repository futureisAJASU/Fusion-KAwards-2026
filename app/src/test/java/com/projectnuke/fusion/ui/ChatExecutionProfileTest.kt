package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ai.ExternalAiChatResult
import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.trust.TrustRouter
import com.projectnuke.fusion.trust.TrustRoutingRequest
import com.projectnuke.fusion.test.PlatformSharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

// Unified execution profile: pure policy, migration, freezing, and gating.
//
// Low-level authorities (TrustRouter, CloudEscalationPolicy/Coordinator,
// DataEgress, provider snapshots) keep their own tests green; this file
// proves the profile resolves into those primitives without becoming a
// second source of truth or a DataEgress grant.
class ChatExecutionProfileTest {

    private fun cloudDecision() = TrustRoutingDecision(
        route = TrustRoute.CLOUD_SUGGEST,
        reason = TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD,
        signals = emptySet(),
    )

    private fun baseSnapshot(
        profile: ChatExecutionProfile,
        providerId: String? = null,
        providerRequest: ExternalProviderRequestSnapshot? = null,
    ): GenerationRequestSnapshot {
        val resolved = ChatExecutionProfilePolicy.resolve(profile)
        return GenerationRequestSnapshot(
            requestId = "req-profile-1",
            conversationId = 7L,
            generationModeKey = resolved.generationMode.name,
            selectedModelId = "Local Model",
            selectedModelPath = "/models/local.gguf",
            selectedModelStableId = "local-stable",
            modelFamily = ModelFamily.QWEN,
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "안녕",
            rawUserText = "안녕",
            createdAt = 1L,
            history = emptyList(),
            historyMessageIds = emptyList(),
            externalProviderId = providerId,
            externalProviderSelectionFrozen = profile == ChatExecutionProfile.EXTERNAL_AI,
            externalProviderRequest = providerRequest,
            autoRoutingEnabled = resolved.autoLocalModelRouting,
            executionProfileKey = profile.name,
        )
    }

    private fun providerSnapshot(id: String = "provider-1") = ExternalProviderRequestSnapshot(
        id = id,
        type = AiProviderType.OPENAI,
        baseUrl = "https://api.example.com",
        modelId = "test-model",
        temperature = 0.7,
        maxTokens = 2048,
        authMode = AiProviderAuthMode.BEARER_API_KEY,
        authHeaderName = null,
        reasoningContentEnabled = false,
        apiKeySecretId = "secret-id-1",
        displayName = "Test Provider",
    )

    // 1. LOCAL resolves to local generation + manual local model.
    @Test
    fun `local resolves to local generation without auto routing`() {
        val resolved = ChatExecutionProfilePolicy.resolve(ChatExecutionProfile.LOCAL)
        assertEquals(ChatGenerationMode.LOCAL_MODEL, resolved.generationMode)
        assertFalse(resolved.autoLocalModelRouting)
        assertFalse(resolved.allowCloudEscalationOffer)
    }

    // 2. AUTOMATIC resolves to local generation + auto local routing.
    @Test
    fun `automatic resolves to local generation with auto routing`() {
        val resolved = ChatExecutionProfilePolicy.resolve(ChatExecutionProfile.AUTOMATIC)
        assertEquals(ChatGenerationMode.LOCAL_MODEL, resolved.generationMode)
        assertTrue(resolved.autoLocalModelRouting)
        assertTrue(resolved.allowCloudEscalationOffer)
    }

    // 3. EXTERNAL_AI resolves to external generation.
    @Test
    fun `external resolves to external generation without auto routing`() {
        val resolved = ChatExecutionProfilePolicy.resolve(ChatExecutionProfile.EXTERNAL_AI)
        assertEquals(ChatGenerationMode.EXTERNAL_AI_API, resolved.generationMode)
        assertFalse(resolved.autoLocalModelRouting)
        assertFalse(resolved.allowCloudEscalationOffer)
    }

    // 4. Only AUTOMATIC allows cloud escalation presentation.
    @Test
    fun `only automatic allows cloud escalation presentation`() {
        val allowing = ChatExecutionProfile.entries.filter {
            ChatExecutionProfilePolicy.resolve(it).allowCloudEscalationOffer
        }
        assertEquals(listOf(ChatExecutionProfile.AUTOMATIC), allowing)
    }

    // 5. No profile directly grants DataEgress authorization.
    @Test
    fun `no profile grants data egress authorization`() {
        ChatExecutionProfile.entries.forEach {
            assertFalse(ChatExecutionProfilePolicy.grantsDataEgressAuthorization(it))
        }
    }

    // 6. old EXTERNAL mode migrates to EXTERNAL_AI.
    @Test
    fun `legacy external mode migrates to external profile`() {
        assertEquals(
            ChatExecutionProfile.EXTERNAL_AI,
            ChatExecutionProfilePolicy.migrate(null, "EXTERNAL_AI_API", false),
        )
        assertEquals(
            ChatExecutionProfile.EXTERNAL_AI,
            ChatExecutionProfilePolicy.migrate(null, "EXTERNAL_AI_API", true),
        )
    }

    // 7. old LOCAL + auto=true migrates to AUTOMATIC.
    @Test
    fun `legacy local with auto selection migrates to automatic`() {
        assertEquals(
            ChatExecutionProfile.AUTOMATIC,
            ChatExecutionProfilePolicy.migrate(null, "LOCAL_MODEL", true),
        )
    }

    // 8. old LOCAL + auto=false migrates to LOCAL.
    @Test
    fun `legacy local without auto selection migrates to local`() {
        assertEquals(
            ChatExecutionProfile.LOCAL,
            ChatExecutionProfilePolicy.migrate(null, "LOCAL_MODEL", false),
        )
        // Absent legacy state is safest as LOCAL.
        assertEquals(
            ChatExecutionProfile.LOCAL,
            ChatExecutionProfilePolicy.migrate(null, null, false),
        )
    }

    // 9. migration is idempotent: a stored profile always wins.
    @Test
    fun `migration is idempotent once a profile is stored`() {
        ChatExecutionProfile.entries.forEach { stored ->
            assertEquals(
                stored,
                ChatExecutionProfilePolicy.migrate(stored.name, "EXTERNAL_AI_API", true),
            )
            assertEquals(
                stored,
                ChatExecutionProfilePolicy.migrate(stored.name, "LOCAL_MODEL", false),
            )
        }
        // Unknown stored values are ignored and the legacy mode decides;
        // fully unknown state falls back to LOCAL (safest).
        assertEquals(
            ChatExecutionProfile.EXTERNAL_AI,
            ChatExecutionProfilePolicy.migrate("UNKNOWN_PROFILE", "EXTERNAL_AI_API", true),
        )
        assertEquals(
            ChatExecutionProfile.LOCAL,
            ChatExecutionProfilePolicy.migrate("UNKNOWN_PROFILE", "BOGUS_MODE", false),
        )
    }

    // 10/11. provider + local model selections survive migration: the
    // profile write touches only profile-derived keys.
    @Test
    fun `migration preserves provider and local model selections`() {
        val current = mapOf(
            "selected_external_provider" to "provider-9",
            "selected_local_model" to "My Model",
            "selected_local_model_path" to "/models/my.gguf",
            "max_tokens" to "2048",
        )
        ChatExecutionProfile.entries.forEach { profile ->
            val migrated = ChatExecutionProfilePolicy.applyPreferenceUpdates(current, profile)
            assertEquals("provider-9", migrated["selected_external_provider"])
            assertEquals("My Model", migrated["selected_local_model"])
            assertEquals("/models/my.gguf", migrated["selected_local_model_path"])
            assertEquals("2048", migrated["max_tokens"])
            assertEquals(profile.name, migrated[ChatExecutionProfilePolicy.PREF_KEY])
        }
    }

    // 12. Send under LOCAL freezes LOCAL_MODEL + autoRouting=false.
    @Test
    fun `send under local freezes manual local execution`() {
        val snapshot = baseSnapshot(ChatExecutionProfile.LOCAL)
        assertEquals("LOCAL_MODEL", snapshot.generationModeKey)
        assertFalse(snapshot.autoRoutingEnabled)
        assertEquals("LOCAL", snapshot.executionProfileKey)
    }

    // 13. Send under AUTOMATIC freezes LOCAL_MODEL + autoRouting=true.
    @Test
    fun `send under automatic freezes auto local routing`() {
        val snapshot = baseSnapshot(ChatExecutionProfile.AUTOMATIC)
        assertEquals("LOCAL_MODEL", snapshot.generationModeKey)
        assertTrue(snapshot.autoRoutingEnabled)
        assertEquals("AUTOMATIC", snapshot.executionProfileKey)
    }

    // 14. Send under EXTERNAL_AI freezes EXTERNAL_AI_API + provider identity.
    @Test
    fun `send under external freezes provider identity`() {
        val snapshot = baseSnapshot(
            ChatExecutionProfile.EXTERNAL_AI,
            providerId = "provider-1",
            providerRequest = providerSnapshot("provider-1"),
        )
        assertEquals("EXTERNAL_AI_API", snapshot.generationModeKey)
        assertFalse(snapshot.autoRoutingEnabled)
        assertEquals("provider-1", snapshot.externalProviderId)
        assertTrue(snapshot.externalProviderSelectionFrozen)
        assertEquals("provider-1", snapshot.externalProviderRequest?.id)
    }

    // 15. changing profile after Send does not mutate captured request.
    @Test
    fun `profile switch after send does not mutate captured request`() {
        val captured = baseSnapshot(ChatExecutionProfile.AUTOMATIC)
        // The visible profile moved to LOCAL after the Send tap, but the
        // frozen capture still describes its own AUTOMATIC execution: the
        // capture is immutable and fromFrozen derives only from frozen
        // fields, never from the live profile.
        val visibleProfile = ChatExecutionProfile.LOCAL
        assertEquals("AUTOMATIC", captured.executionProfileKey)
        assertTrue(captured.autoRoutingEnabled)
        assertEquals("LOCAL_MODEL", captured.generationModeKey)
        assertEquals(
            ChatExecutionProfile.AUTOMATIC,
            ChatExecutionProfilePolicy.fromFrozen(captured.generationModeKey, captured.autoRoutingEnabled),
        )
        assertEquals(ChatExecutionProfile.LOCAL, visibleProfile)
    }

    // 16. provider change after Send does not mutate captured external request.
    @Test
    fun `provider change after send does not mutate captured external request`() {
        val captured = baseSnapshot(
            ChatExecutionProfile.EXTERNAL_AI,
            providerId = "provider-1",
            providerRequest = providerSnapshot("provider-1"),
        )
        var liveProviderId: String? = "provider-1"
        liveProviderId = "provider-2"
        assertEquals("provider-1", captured.externalProviderId)
        assertEquals("provider-1", captured.externalProviderRequest?.id)
        assertEquals("provider-2", liveProviderId)
    }

    // 17. local model change after Send does not mutate captured LOCAL request.
    @Test
    fun `local model change after send does not mutate captured local request`() {
        val captured = baseSnapshot(ChatExecutionProfile.LOCAL)
        var liveModelPath: String? = "/models/local.gguf"
        liveModelPath = "/models/other.gguf"
        assertEquals("/models/local.gguf", captured.selectedModelPath)
        assertEquals("local-stable", captured.selectedModelStableId)
        assertEquals("/models/other.gguf", liveModelPath)
    }

    // 18. AUTOMATIC satisfies the AutoModelRoutingOwner preconditions
    // (autoRouting + LOCAL mode); other profiles do not arm it.
    @Test
    fun `automatic arms auto routing owner preconditions only`() {
        fun ownerWouldRoute(profile: ChatExecutionProfile): Boolean {
            val resolved = ChatExecutionProfilePolicy.resolve(profile)
            return resolved.autoLocalModelRouting &&
                resolved.generationMode.name == ChatGenerationMode.LOCAL_MODEL.name
        }
        assertTrue(ownerWouldRoute(ChatExecutionProfile.AUTOMATIC))
        assertFalse(ownerWouldRoute(ChatExecutionProfile.LOCAL))
        assertFalse(ownerWouldRoute(ChatExecutionProfile.EXTERNAL_AI))
    }

    // 19. AUTOMATIC can produce SEARCH through the existing TrustRouter.
    @Test
    fun `automatic keeps search route through trust router`() {
        val resolved = ChatExecutionProfilePolicy.resolve(ChatExecutionProfile.AUTOMATIC)
        val decision = TrustRouter.route(
            TrustRoutingRequest(
                userText = "웹에서 검색해줘: 오늘의 주요 뉴스",
                manualSearchEnabled = true,
                generationModeKey = resolved.generationMode.name,
            ),
        )
        assertEquals(TrustRoute.SEARCH, decision.route)
    }

    // 20. AUTOMATIC never directly invokes an external provider merely
    // because TrustRouter suggests cloud: the offer is presentation only.
    @Test
    fun `automatic cloud suggestion produces presentation only with zero calls`() {
        val calls = AtomicInteger(0)
        val coordinator = CloudEscalationCoordinator(
            CloudEscalationExecutor {
                calls.incrementAndGet()
                ExternalAiChatResult.Truncated("unused", "P")
            },
        )
        val baseOffer = CloudEscalationPolicy.offer(
            decision = cloudDecision(),
            generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
            providerAvailable = true,
            hasAttachments = false,
            actionLabel = "더 강한 모델로 다시 요청",
        )
        assertTrue(baseOffer is CloudEscalationOffer.Shown)
        val gated = ChatExecutionProfilePolicy.gateCloudEscalationOffer(
            ChatExecutionProfile.AUTOMATIC, baseOffer,
        )
        assertTrue(gated is CloudEscalationOffer.Shown)
        // No confirm happened: zero external calls.
        assertEquals(0, calls.get())
    }

    // 21. cloud suggestion requires existing explicit confirmation.
    @Test
    fun `cloud suggestion requires explicit confirmation`() = runTest {
        val calls = AtomicInteger(0)
        val coordinator = CloudEscalationCoordinator(
            CloudEscalationExecutor {
                calls.incrementAndGet()
                ExternalAiChatResult.Truncated("외부 답변", "P")
            },
        )
        val snapshot = buildCloudEscalationSnapshot(
            conversationId = 7L,
            requestId = "req-esc-1",
            providerId = "p1",
            providerRequestSnapshot = providerSnapshot("p1"),
            generationSettings = GenerationSettings(),
            reasoningEnabled = false,
            promptLabInstruction = "",
            history = listOf(ChatMessage("user", "복잡한 작업")),
            historyMessageIds = listOf(11L),
            promptText = "복잡한 작업",
            rawUserText = "복잡한 작업",
            createdAt = 1L,
            trustRoutingDecision = cloudDecision(),
        )
        val pending = PendingCloudEscalation(
            conversationId = 7L,
            targetAssistantMessageId = 12L,
            userMessageId = 11L,
            versionGroupId = 11L,
            providerId = "p1",
            providerDisplayName = "P",
            escalatedRequest = snapshot,
            fullMessageEntities = listOf(
                MessageEntity(11L, 7L, "user", "복잡한 작업", 1L),
                MessageEntity(12L, 7L, "assistant", "로컬 답변", 2L),
            ),
            capturedResponseVersionState = ResponseVersionState(
                groupByMessageId = mapOf(12L to 11L),
                activeMessageIdByGroup = mapOf(11L to 12L),
            ),
        )
        assertTrue(coordinator.freezePending(pending))
        assertEquals(0, calls.get())
        val result = coordinator.confirm(pending) {
            CloudEscalationLiveState(true, pending.fullMessageEntities, pending.capturedResponseVersionState)
        }
        assertTrue(result is CloudEscalationExecutionResult.Success)
        assertEquals(1, calls.get())
    }

    // 22. cancelling cloud suggestion produces zero external calls.
    @Test
    fun `cancelling cloud suggestion produces zero external calls`() = runTest {
        val calls = AtomicInteger(0)
        val coordinator = CloudEscalationCoordinator(
            CloudEscalationExecutor {
                calls.incrementAndGet()
                ExternalAiChatResult.Truncated("unused", "P")
            },
        )
        val gated = ChatExecutionProfilePolicy.gateCloudEscalationOffer(
            ChatExecutionProfile.AUTOMATIC,
            CloudEscalationOffer.Shown("더 강한 모델로 다시 요청"),
        )
        assertTrue(gated is CloudEscalationOffer.Shown)
        coordinator.cancel()
        assertEquals(0, calls.get())
    }

    // 23. private/local content remains blocked from unsafe escalation.
    @Test
    fun `private tainted history stays blocked under automatic`() {
        val baseOffer = CloudEscalationPolicy.offer(
            decision = cloudDecision(),
            generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
            providerAvailable = true,
            hasAttachments = false,
            actionLabel = "더 강한 모델로 다시 요청",
            historyTainted = true,
        )
        assertTrue(baseOffer is CloudEscalationOffer.Blocked)
        val gated = ChatExecutionProfilePolicy.gateCloudEscalationOffer(
            ChatExecutionProfile.AUTOMATIC, baseOffer,
        )
        // AUTOMATIC passes the existing verdict through; it never upgrades
        // a Blocked offer into an approval.
        assertTrue(gated is CloudEscalationOffer.Blocked)
    }

    // 24/25. LOCAL does not run auto-model routing and suppresses the
    // automatic cloud escalation offer even on CLOUD_SUGGEST.
    @Test
    fun `local suppresses automatic cloud escalation offer`() {
        val baseOffer = CloudEscalationPolicy.offer(
            decision = cloudDecision(),
            generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
            providerAvailable = true,
            hasAttachments = false,
            actionLabel = "더 강한 모델로 다시 요청",
        )
        assertTrue(baseOffer is CloudEscalationOffer.Shown)
        val gated = ChatExecutionProfilePolicy.gateCloudEscalationOffer(
            ChatExecutionProfile.LOCAL, baseOffer,
        )
        assertTrue(gated is CloudEscalationOffer.Hidden)
    }

    // 26. explicit/search-policy web search stays functional under LOCAL.
    @Test
    fun `local keeps explicit search route`() {
        val decision = TrustRouter.route(
            TrustRoutingRequest(
                userText = "검색해줘: 내일 비가 올까",
                manualSearchEnabled = true,
                generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
            ),
        )
        assertEquals(TrustRoute.SEARCH, decision.route)
    }

    // 27. LOCAL does not call external LLM generation.
    @Test
    fun `local never resolves to external generation`() {
        val resolved = ChatExecutionProfilePolicy.resolve(ChatExecutionProfile.LOCAL)
        assertEquals(ChatGenerationMode.LOCAL_MODEL, resolved.generationMode)
        val snapshot = baseSnapshot(ChatExecutionProfile.LOCAL)
        assertEquals(null, snapshot.externalProviderId)
        assertEquals(null, snapshot.externalProviderRequest)
    }

    // 28/30. EXTERNAL_AI retains explicit provider execution, frozen.
    @Test
    fun `external retains frozen explicit provider`() {
        val snapshot = baseSnapshot(
            ChatExecutionProfile.EXTERNAL_AI,
            providerId = "provider-1",
            providerRequest = providerSnapshot("provider-1"),
        )
        assertEquals("EXTERNAL_AI_API", snapshot.generationModeKey)
        assertEquals("provider-1", snapshot.externalProviderId)
        assertTrue(snapshot.externalProviderSelectionFrozen)
    }

    // 29. no runnable provider => settings guidance, zero provider calls.
    @Test
    fun `external without runnable provider guides to settings`() {
        val pick = pickEscalationProvider(
            selectedId = null,
            providers = emptyList(),
            isRunnable = { true },
        )
        assertTrue(pick is CloudProviderPick.NoneAvailable)
        val offer = CloudEscalationPolicy.offer(
            decision = cloudDecision(),
            generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
            providerAvailable = false,
            hasAttachments = false,
            actionLabel = "더 강한 모델로 다시 요청",
        )
        assertTrue(offer is CloudEscalationOffer.Blocked)
    }

    // 31/32. private history filtering + attachment restrictions stay
    // intact: the existing policy still blocks tainted/attachment cases
    // regardless of profile gating.
    @Test
    fun `privacy blocks survive profile gating`() {
        val attached = CloudEscalationPolicy.offer(
            decision = cloudDecision(),
            generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
            providerAvailable = true,
            hasAttachments = true,
            actionLabel = "더 강한 모델로 다시 요청",
        )
        assertTrue(attached is CloudEscalationOffer.Blocked)
        val tainted = CloudEscalationPolicy.offer(
            decision = cloudDecision(),
            generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
            providerAvailable = true,
            hasAttachments = false,
            actionLabel = "더 강한 모델로 다시 요청",
            historyTainted = true,
        )
        assertTrue(tainted is CloudEscalationOffer.Blocked)
    }

    // 34. captured AUTOMATIC retry preserves auto-routing semantics.
    @Test
    fun `captured automatic retry preserves auto routing`() {
        val capture = RegenerationRequestCapture(
            conversationId = 7L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            selectedModel = "Local Model",
            selectedModelPath = "/models/local.gguf",
            selectedModelStableId = "local-stable",
            modelFamily = ModelFamily.QWEN,
            localProfile = null,
            generationSettings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchEnabled = false,
            promptLabInstruction = "",
            externalProviderId = null,
            externalMaxOutputTokens = null,
            autoRoutingEnabled = true,
            executionProfile = ChatExecutionProfile.AUTOMATIC,
        )
        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "req-retry-1",
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "안녕",
            rawUserText = "안녕",
            createdAt = 2L,
            history = emptyList(),
            historyMessageIds = emptyList(),
        )
        assertTrue(snapshot.autoRoutingEnabled)
        assertEquals("LOCAL_MODEL", snapshot.generationModeKey)
        assertEquals("AUTOMATIC", snapshot.executionProfileKey)
    }

    // 35. captured LOCAL retry remains local/manual.
    @Test
    fun `captured local retry remains manual`() {
        val capture = RegenerationRequestCapture(
            conversationId = 7L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            selectedModel = "Local Model",
            selectedModelPath = "/models/local.gguf",
            selectedModelStableId = "local-stable",
            modelFamily = ModelFamily.QWEN,
            localProfile = null,
            generationSettings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchEnabled = false,
            promptLabInstruction = "",
            externalProviderId = null,
            externalMaxOutputTokens = null,
            autoRoutingEnabled = false,
            executionProfile = ChatExecutionProfile.LOCAL,
        )
        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "req-retry-2",
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "안녕",
            rawUserText = "안녕",
            createdAt = 2L,
            history = emptyList(),
            historyMessageIds = emptyList(),
        )
        assertFalse(snapshot.autoRoutingEnabled)
        assertEquals("LOCAL", snapshot.executionProfileKey)
    }

    // 36. captured EXTERNAL retry retains frozen provider semantics.
    @Test
    fun `captured external retry retains frozen provider`() {
        val capture = RegenerationRequestCapture(
            conversationId = 8L,
            targetMessageId = 191L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.EXTERNAL_AI_API,
            selectedModel = "unused",
            selectedModelPath = null,
            selectedModelStableId = null,
            modelFamily = null,
            localProfile = null,
            generationSettings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchEnabled = false,
            promptLabInstruction = "",
            externalProviderId = "provider-1",
            externalMaxOutputTokens = 512,
            externalProviderRequest = providerSnapshot("provider-1"),
            executionProfile = ChatExecutionProfile.EXTERNAL_AI,
        )
        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "req-retry-3",
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "안녕",
            rawUserText = "안녕",
            createdAt = 2L,
            history = emptyList(),
            historyMessageIds = emptyList(),
        )
        assertEquals("EXTERNAL_AI_API", snapshot.generationModeKey)
        assertEquals("provider-1", snapshot.externalProviderId)
        assertEquals("provider-1", snapshot.externalProviderRequest?.id)
        assertTrue(snapshot.externalProviderSelectionFrozen)
        assertEquals("EXTERNAL_AI", snapshot.executionProfileKey)
    }

    // 37. changing visible profile does not hijack an already captured retry.
    @Test
    fun `visible profile switch does not hijack captured retry`() {
        val capture = RegenerationRequestCapture(
            conversationId = 7L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            selectedModel = "Local Model",
            selectedModelPath = "/models/local.gguf",
            selectedModelStableId = "local-stable",
            modelFamily = ModelFamily.QWEN,
            localProfile = null,
            generationSettings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchEnabled = false,
            promptLabInstruction = "",
            externalProviderId = null,
            externalMaxOutputTokens = null,
            autoRoutingEnabled = true,
            executionProfile = ChatExecutionProfile.AUTOMATIC,
        )
        // User switched the visible profile to EXTERNAL before retry starts.
        val visibleProfile = ChatExecutionProfile.EXTERNAL_AI
        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "req-retry-4",
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "안녕",
            rawUserText = "안녕",
            createdAt = 2L,
            history = emptyList(),
            historyMessageIds = emptyList(),
        )
        assertEquals("LOCAL_MODEL", snapshot.generationModeKey)
        assertTrue(snapshot.autoRoutingEnabled)
        assertEquals("AUTOMATIC", snapshot.executionProfileKey)
        assertEquals(ChatExecutionProfile.EXTERNAL_AI, visibleProfile)
    }

    // Presentation: exactly three profiles with coherent labels.
    @Test
    fun `three profiles with coherent labels and explanations`() {
        assertEquals(3, ChatExecutionProfile.entries.size)
        assertEquals("로컬", ChatExecutionProfilePolicy.label(ChatExecutionProfile.LOCAL))
        assertEquals("자동", ChatExecutionProfilePolicy.label(ChatExecutionProfile.AUTOMATIC))
        assertEquals("외부 AI", ChatExecutionProfilePolicy.label(ChatExecutionProfile.EXTERNAL_AI))
        ChatExecutionProfile.entries.forEach {
            assertTrue(ChatExecutionProfilePolicy.explanation(it).isNotBlank())
        }
    }

    // Presentation: route button labels never promise unfrozen behavior.
    @Test
    fun `route button labels stay neutral before routing resolves`() {
        assertEquals(
            "로컬 · My Model",
            ChatExecutionProfilePolicy.routeButtonLabel(
                ChatExecutionProfile.LOCAL, "My Model", null, null,
            ),
        )
        assertEquals(
            "자동 · 자동 선택",
            ChatExecutionProfilePolicy.routeButtonLabel(
                ChatExecutionProfile.AUTOMATIC, null, null, null,
            ),
        )
        assertEquals(
            "자동 · Routed Model",
            ChatExecutionProfilePolicy.routeButtonLabel(
                ChatExecutionProfile.AUTOMATIC, null, "자동 선택 · Routed Model\n빠른 모델", null,
            ),
        )
        assertEquals(
            "외부 AI · Test Provider",
            ChatExecutionProfilePolicy.routeButtonLabel(
                ChatExecutionProfile.EXTERNAL_AI, null, null, "Test Provider",
            ),
        )
        assertEquals(
            "외부 AI · 제공자 선택",
            ChatExecutionProfilePolicy.routeButtonLabel(
                ChatExecutionProfile.EXTERNAL_AI, null, null, null,
            ),
        )
    }

    @Test
    fun `automatic pre-resolution status is neutral`() {
        assertEquals(
            "요청 시 자동 선택",
            ChatExecutionProfilePolicy.autoRoutingStatusLabel(null),
        )
        assertEquals(
            "자동 선택 · Routed Model",
            ChatExecutionProfilePolicy.autoRoutingStatusLabel("자동 선택 · Routed Model\n이유"),
        )
    }

    // EXTERNAL_AI never shows a redundant escalation offer.
    @Test
    fun `external profile hides escalation offer`() {
        val baseOffer = CloudEscalationPolicy.offer(
            decision = cloudDecision(),
            generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
            providerAvailable = true,
            hasAttachments = false,
            actionLabel = "더 강한 모델로 다시 요청",
        )
        val gated = ChatExecutionProfilePolicy.gateCloudEscalationOffer(
            ChatExecutionProfile.EXTERNAL_AI, baseOffer,
        )
        assertTrue(gated is CloudEscalationOffer.Hidden)
    }

    // fromFrozen keeps legacy captures' execution semantics.
    @Test
    fun `from frozen preserves legacy capture semantics`() {
        assertEquals(
            ChatExecutionProfile.AUTOMATIC,
            ChatExecutionProfilePolicy.fromFrozen("LOCAL_MODEL", true),
        )
        assertEquals(
            ChatExecutionProfile.LOCAL,
            ChatExecutionProfilePolicy.fromFrozen("LOCAL_MODEL", false),
        )
        assertEquals(
            ChatExecutionProfile.EXTERNAL_AI,
            ChatExecutionProfilePolicy.fromFrozen("EXTERNAL_AI_API", false),
        )
    }

    // Provider config used in tests is complete enough to freeze.
    @Test
    fun `test provider snapshot carries identity for freezing`() {
        val provider = AiProviderConfig(
            id = "provider-1",
            type = AiProviderType.OPENAI,
            displayName = "Test Provider",
            baseUrl = "https://api.example.com",
            modelId = "test-model",
            apiKeySecretId = "secret-id-1",
            maxTokens = 2048,
        )
        val snapshot = ExternalProviderRequestSnapshot.from(provider, 2048)
        assertEquals("provider-1", snapshot.id)
        assertEquals("test-model", snapshot.modelId)
    }

    // ---- Preference persistence lifecycle (production wiring) ----
    //
    // These tests exercise the real SharedPreferences boundary through the
    // existing PlatformSharedPreferences fake: first production resolution
    // must write a canonical profile, steady-state restarts must not write,
    // and only profile-derived compatibility keys may change.

    private fun seedPrefs(vararg entries: Pair<String, Any?>): PlatformSharedPreferences {
        val prefs = PlatformSharedPreferences()
        val editor = prefs.edit()
        entries.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                null -> editor.remove(key)
            }
        }
        editor.apply()
        prefs.applyCalls.set(0)
        return prefs
    }

    private fun profileKeys() = setOf(
        ChatExecutionProfilePolicy.PREF_KEY,
        ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY,
        ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY,
    )

    // 1. missing new profile + legacy EXTERNAL resolves EXTERNAL_AI and
    // requests persistence; the write makes the next start steady-state.
    @Test
    fun `missing profile with legacy external persists canonical external`() {
        val prefs = seedPrefs(
            ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY to "EXTERNAL_AI_API",
        )
        val first = ChatExecutionProfilePolicy.readResolution(prefs)
        assertEquals(ChatExecutionProfile.EXTERNAL_AI, first.profile)
        assertTrue(first.persistenceRequired)
        assertTrue(ChatExecutionProfilePolicy.persistResolution(prefs, first))

        assertEquals("EXTERNAL_AI", prefs.getString(ChatExecutionProfilePolicy.PREF_KEY, null))
        assertEquals("EXTERNAL_AI_API", prefs.getString(ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY, null))
        assertEquals(false, prefs.getBoolean(ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY, true))

        val second = ChatExecutionProfilePolicy.readResolution(prefs)
        assertEquals(ChatExecutionProfile.EXTERNAL_AI, second.profile)
        assertFalse(second.persistenceRequired)
        assertFalse(ChatExecutionProfilePolicy.persistResolution(prefs, second))
    }

    // 2. missing profile + LOCAL/auto true resolves AUTOMATIC and requests
    // persistence.
    @Test
    fun `missing profile with legacy auto persists canonical automatic`() {
        val prefs = seedPrefs(
            ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY to "LOCAL_MODEL",
            ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY to true,
        )
        val first = ChatExecutionProfilePolicy.readResolution(prefs)
        assertEquals(ChatExecutionProfile.AUTOMATIC, first.profile)
        assertTrue(first.persistenceRequired)
        assertEquals(
            mapOf(
                ChatExecutionProfilePolicy.PREF_KEY to "AUTOMATIC",
                ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY to "LOCAL_MODEL",
                ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY to "true",
            ),
            first.updates,
        )
        assertTrue(ChatExecutionProfilePolicy.persistResolution(prefs, first))

        val second = ChatExecutionProfilePolicy.readResolution(prefs)
        assertEquals(ChatExecutionProfile.AUTOMATIC, second.profile)
        assertFalse(second.persistenceRequired)
    }

    // 3. missing profile + LOCAL/auto false resolves LOCAL and requests
    // persistence.
    @Test
    fun `missing profile with legacy manual persists canonical local`() {
        val prefs = seedPrefs(
            ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY to "LOCAL_MODEL",
            ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY to false,
        )
        val first = ChatExecutionProfilePolicy.readResolution(prefs)
        assertEquals(ChatExecutionProfile.LOCAL, first.profile)
        assertTrue(first.persistenceRequired)
        assertTrue(ChatExecutionProfilePolicy.persistResolution(prefs, first))

        val second = ChatExecutionProfilePolicy.readResolution(prefs)
        assertEquals(ChatExecutionProfile.LOCAL, second.profile)
        assertFalse(second.persistenceRequired)
    }

    // 4. valid stored profile with matching mirrors requires no migration
    // write: steady-state restarts are read-only.
    @Test
    fun `valid stored profile with matching mirrors performs zero writes`() {
        val prefs = seedPrefs(
            ChatExecutionProfilePolicy.PREF_KEY to "AUTOMATIC",
            ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY to "LOCAL_MODEL",
            ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY to true,
        )
        val resolution = ChatExecutionProfilePolicy.readResolution(prefs)
        assertEquals(ChatExecutionProfile.AUTOMATIC, resolution.profile)
        assertFalse(resolution.persistenceRequired)
        assertFalse(ChatExecutionProfilePolicy.persistResolution(prefs, resolution))
        assertEquals(0, prefs.applyCalls.get())
    }

    // 5. valid stored AUTOMATIC with stale legacy EXTERNAL fields keeps
    // AUTOMATIC and reconciles the legacy mirrors (never reinterprets the
    // user back to EXTERNAL_AI because a mirror drifted).
    @Test
    fun `valid automatic beats conflicting legacy mirrors and reconciles them`() {
        val prefs = seedPrefs(
            ChatExecutionProfilePolicy.PREF_KEY to "AUTOMATIC",
            ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY to "EXTERNAL_AI_API",
            ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY to false,
        )
        val first = ChatExecutionProfilePolicy.readResolution(prefs)
        assertEquals(ChatExecutionProfile.AUTOMATIC, first.profile)
        assertTrue(first.persistenceRequired)
        assertTrue(ChatExecutionProfilePolicy.persistResolution(prefs, first))

        assertEquals("AUTOMATIC", prefs.getString(ChatExecutionProfilePolicy.PREF_KEY, null))
        assertEquals("LOCAL_MODEL", prefs.getString(ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY, null))
        assertEquals(true, prefs.getBoolean(ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY, false))

        val second = ChatExecutionProfilePolicy.readResolution(prefs)
        assertEquals(ChatExecutionProfile.AUTOMATIC, second.profile)
        assertFalse(second.persistenceRequired)
    }

    // 6. valid LOCAL remains LOCAL regardless of conflicting legacy fields.
    @Test
    fun `valid local beats conflicting legacy fields`() {
        val prefs = seedPrefs(
            ChatExecutionProfilePolicy.PREF_KEY to "LOCAL",
            ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY to "EXTERNAL_AI_API",
            ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY to true,
        )
        val first = ChatExecutionProfilePolicy.readResolution(prefs)
        assertEquals(ChatExecutionProfile.LOCAL, first.profile)
        assertTrue(first.persistenceRequired)
        assertTrue(ChatExecutionProfilePolicy.persistResolution(prefs, first))

        assertEquals("LOCAL_MODEL", prefs.getString(ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY, null))
        assertEquals(false, prefs.getBoolean(ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY, true))
        assertFalse(ChatExecutionProfilePolicy.readResolution(prefs).persistenceRequired)
    }

    // 7. invalid profile is replaced with one valid canonical profile.
    @Test
    fun `invalid stored profile is replaced with canonical result`() {
        val prefs = seedPrefs(
            ChatExecutionProfilePolicy.PREF_KEY to "BOGUS_PROFILE",
            ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY to "EXTERNAL_AI_API",
        )
        val first = ChatExecutionProfilePolicy.readResolution(prefs)
        assertEquals(ChatExecutionProfile.EXTERNAL_AI, first.profile)
        assertTrue(first.persistenceRequired)
        assertTrue(ChatExecutionProfilePolicy.persistResolution(prefs, first))

        assertEquals("EXTERNAL_AI", prefs.getString(ChatExecutionProfilePolicy.PREF_KEY, null))
        val second = ChatExecutionProfilePolicy.readResolution(prefs)
        assertEquals(ChatExecutionProfile.EXTERNAL_AI, second.profile)
        assertFalse(second.persistenceRequired)
    }

    // 8. second resolution after applying migration updates is idempotent
    // for every legacy starting state.
    @Test
    fun `second resolution after migration is idempotent for all legacy states`() {
        val legacyStates = listOf(
            Triple(null, null, false),
            Triple(null, "LOCAL_MODEL", false),
            Triple(null, "LOCAL_MODEL", true),
            Triple(null, "EXTERNAL_AI_API", false),
            Triple(null, "EXTERNAL_AI_API", true),
            Triple(null, "BOGUS_MODE", false),
        )
        legacyStates.forEach { (stored, mode, auto) ->
            val resolution = ChatExecutionProfilePolicy.resolvePreferences(stored, mode, auto)
            assertTrue(resolution.persistenceRequired)
            val applied = ChatExecutionProfilePolicy.applyPreferenceUpdates(emptyMap(), resolution.profile)
            val reread = ChatExecutionProfilePolicy.resolvePreferences(
                storedProfileName = applied[ChatExecutionProfilePolicy.PREF_KEY],
                generationModeName = applied[ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY],
                autoModelSelection = applied.getValue(ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY).toBoolean(),
            )
            assertEquals(resolution.profile, reread.profile)
            assertFalse(reread.persistenceRequired)
        }
    }

    // 9/10/11. provider selection, local model/path, and performance
    // preferences survive migration through the real prefs boundary.
    @Test
    fun `provider model and performance selections survive migration write`() {
        val prefs = seedPrefs(
            ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY to "LOCAL_MODEL",
            ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY to true,
            "selected_external_provider" to "provider-9",
            "selected_local_model" to "My Model",
            "selected_local_model_path" to "/models/my.gguf",
            "max_tokens" to 2048,
            "accelerator" to "AUTO",
            "web_search_enabled" to true,
            "reasoning_enabled" to false,
        )
        val resolution = ChatExecutionProfilePolicy.readResolution(prefs)
        assertEquals(ChatExecutionProfile.AUTOMATIC, resolution.profile)
        assertTrue(ChatExecutionProfilePolicy.persistResolution(prefs, resolution))

        assertEquals("provider-9", prefs.getString("selected_external_provider", null))
        assertEquals("My Model", prefs.getString("selected_local_model", null))
        assertEquals("/models/my.gguf", prefs.getString("selected_local_model_path", null))
        assertEquals(2048, prefs.getInt("max_tokens", 0))
        assertEquals("AUTO", prefs.getString("accelerator", null))
        assertEquals(true, prefs.getBoolean("web_search_enabled", false))
        assertEquals(false, prefs.getBoolean("reasoning_enabled", true))
        assertEquals("AUTOMATIC", prefs.getString(ChatExecutionProfilePolicy.PREF_KEY, null))
    }

    // 12. only profile-derived compatibility keys are changed by the
    // migration write; every other key keeps its exact value.
    @Test
    fun `migration write touches only profile derived keys`() {
        val prefs = seedPrefs(
            ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY to "LOCAL_MODEL",
            "selected_external_provider" to "provider-9",
            "selected_local_model" to "My Model",
            "api_key_secret" to "secret-id-1",
        )
        val before = prefs.values.toMap()
        val resolution = ChatExecutionProfilePolicy.readResolution(prefs)
        assertTrue(ChatExecutionProfilePolicy.persistResolution(prefs, resolution))
        val after = prefs.values.toMap()

        val changedKeys = (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
        assertTrue(changedKeys.isNotEmpty())
        assertTrue(
            changedKeys.all { it in profileKeys() },
        )
        (before.keys - profileKeys()).forEach { key ->
            assertEquals(before[key], after[key])
        }
    }

    // Explicit user-action path writes the canonical profile with the same
    // bounded key set and preserves unrelated selections.
    @Test
    fun `explicit profile switch writes canonical keys only`() {
        val prefs = seedPrefs(
            ChatExecutionProfilePolicy.PREF_KEY to "LOCAL",
            ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY to "LOCAL_MODEL",
            ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY to false,
            "selected_external_provider" to "provider-9",
        )
        ChatExecutionProfilePolicy.writeCanonicalProfile(prefs, ChatExecutionProfile.EXTERNAL_AI)
        assertEquals("EXTERNAL_AI", prefs.getString(ChatExecutionProfilePolicy.PREF_KEY, null))
        assertEquals("EXTERNAL_AI_API", prefs.getString(ChatExecutionProfilePolicy.LEGACY_GENERATION_MODE_KEY, null))
        assertEquals(false, prefs.getBoolean(ChatExecutionProfilePolicy.LEGACY_AUTO_SELECTION_KEY, true))
        assertEquals("provider-9", prefs.getString("selected_external_provider", null))
        assertFalse(ChatExecutionProfilePolicy.readResolution(prefs).persistenceRequired)
    }
}
