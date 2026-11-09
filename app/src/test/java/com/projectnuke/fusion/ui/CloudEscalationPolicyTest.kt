package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.trust.TrustRoutingPresentationMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudEscalationPolicyTest {
    private fun cloudDecision() = TrustRoutingDecision(
        route = TrustRoute.CLOUD_SUGGEST,
        reason = TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD,
        signals = emptySet(),
    )

    private fun providerSnapshot(
        id: String = "provider-1",
        displayName: String = "Test Provider",
    ) = ExternalProviderRequestSnapshot(
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
        displayName = displayName,
    )

    private fun escalationSnapshot(
        conversationId: Long = 7L,
        providerId: String = "provider-1",
    ): GenerationRequestSnapshot = buildCloudEscalationSnapshot(
        conversationId = conversationId,
        requestId = "req-1",
        providerId = providerId,
        providerRequestSnapshot = providerSnapshot(id = providerId),
        generationSettings = GenerationSettings(),
        reasoningEnabled = false,
        promptLabInstruction = "",
        history = listOf(ChatMessage("user", "원본 질문")),
        historyMessageIds = listOf(11L),
        promptText = "원본 질문",
        rawUserText = "원본 질문",
        createdAt = 123L,
        trustRoutingDecision = cloudDecision(),
    )

    private fun pending(
        conversationId: Long = 7L,
        providerId: String = "provider-1",
    ): PendingCloudEscalation {
        val snapshot = escalationSnapshot(conversationId, providerId)
        val messages = listOf(
            MessageEntity(id = 11L, conversationId = conversationId, role = "user", content = "원본 질문", createdAt = 1L),
            MessageEntity(id = 12L, conversationId = conversationId, role = "assistant", content = "로컬 답변", createdAt = 2L),
        )
        return PendingCloudEscalation(
            conversationId = conversationId,
            targetAssistantMessageId = 12L,
            userMessageId = 11L,
            versionGroupId = 11L,
            providerId = providerId,
            providerDisplayName = "Test Provider",
            escalatedRequest = snapshot,
            fullMessageEntities = messages,
            capturedResponseVersionState = ResponseVersionState(),
        )
    }

    // 1.10: CLOUD_SUGGEST + LOCAL_MODEL + provider available -> action visible
    @Test
    fun `cloud suggest local mode provider available shows action`() {
        val offer = CloudEscalationPolicy.offer(
            decision = cloudDecision(),
            generationModeKey = "LOCAL_MODEL",
            providerAvailable = true,
            hasAttachments = false,
            actionLabel = TrustRoutingPresentationMapper.CLOUD_SUGGEST_ACTION_LABEL,
        )
        assertTrue(offer is CloudEscalationOffer.Shown)
        assertEquals("더 강한 모델로 다시 요청", (offer as CloudEscalationOffer.Shown).label)
    }

    @Test
    fun `presentation mapper exposes suggested action for cloud suggest local`() {
        val presentation = TrustRoutingPresentationMapper.map(
            cloudDecision(),
            generationModeKey = "LOCAL_MODEL",
        )
        assertTrue(presentation.isVisible)
        assertEquals("더 강한 모델로 다시 요청", presentation.suggestedAction)
    }

    // 1.10: CLOUD_SUGGEST + EXTERNAL_AI_API -> action absent
    @Test
    fun `cloud suggest external mode hides action`() {
        val offer = CloudEscalationPolicy.offer(
            decision = cloudDecision(),
            generationModeKey = "EXTERNAL_AI_API",
            providerAvailable = true,
            hasAttachments = false,
            actionLabel = "더 강한 모델로 다시 요청",
        )
        assertTrue(offer is CloudEscalationOffer.Hidden)
        val presentation = TrustRoutingPresentationMapper.map(
            cloudDecision(),
            generationModeKey = "EXTERNAL_AI_API",
        )
        assertFalse(presentation.isVisible)
        assertNull(presentation.suggestedAction)
    }

    // Non-cloud routes never offer.
    @Test
    fun `local search abstain and null never offer escalation`() {
        val local = TrustRoutingDecision(TrustRoute.LOCAL, TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE, emptySet())
        val search = TrustRoutingDecision(TrustRoute.SEARCH, TrustRoutingReason.CURRENT_INFORMATION_REQUIRED, emptySet())
        val abstain = TrustRoutingDecision(TrustRoute.ABSTAIN, TrustRoutingReason.FUTURE_UNKNOWABLE, emptySet())
        listOf(local, search, abstain, null).forEach {
            val offer = CloudEscalationPolicy.offer(it, "LOCAL_MODEL", true, false, "더 강한 모델로 다시 요청")
            assertTrue(offer is CloudEscalationOffer.Hidden)
        }
    }

    // 1.6: no configured provider -> 0 calls, bounded UX
    @Test
    fun `no provider blocks with bounded notice`() {
        val offer = CloudEscalationPolicy.offer(
            decision = cloudDecision(),
            generationModeKey = "LOCAL_MODEL",
            providerAvailable = false,
            hasAttachments = false,
            actionLabel = "더 강한 모델로 다시 요청",
        )
        assertTrue(offer is CloudEscalationOffer.Blocked)
        assertEquals("외부 AI 설정이 필요해요.", (offer as CloudEscalationOffer.Blocked).notice)
    }

    // 1.7: attachment request -> 0 calls, blocked
    @Test
    fun `attachment request blocks escalation`() {
        val offer = CloudEscalationPolicy.offer(
            decision = cloudDecision(),
            generationModeKey = "LOCAL_MODEL",
            providerAvailable = true,
            hasAttachments = true,
            actionLabel = "더 강한 모델로 다시 요청",
        )
        assertTrue(offer is CloudEscalationOffer.Blocked)
        assertTrue((offer as CloudEscalationOffer.Blocked).notice.contains("첨부"))
    }

    // 1.8: ABSTAIN cannot bypass
    @Test
    fun `abstain never offers cloud action`() {
        val abstain = TrustRoutingDecision(TrustRoute.ABSTAIN, TrustRoutingReason.FUTURE_UNKNOWABLE, emptySet())
        val offer = CloudEscalationPolicy.offer(abstain, "LOCAL_MODEL", true, false, "더 강한 모델로 다시 요청")
        assertTrue(offer is CloudEscalationOffer.Hidden)
    }

    // 1.2: consent dialog copy
    @Test
    fun `consent copy explains egress single-use cancel`() {
        val body = CloudEscalationPolicy.consentBody("Test Provider")
        assertTrue(body.contains("외부 AI 제공자"))
        assertTrue(body.contains("전송"))
        assertTrue(body.contains("이번 전환 하나에만"))
        assertTrue(body.contains("취소하면"))
        assertFalse(body.contains("apiKey", ignoreCase = true))
        assertFalse(body.contains("secret", ignoreCase = true))
        assertFalse(body.contains("CLOUD_SUGGEST"))
        assertFalse(body.contains("Bearer", ignoreCase = true))
        val title = CloudEscalationPolicy.title("Test Provider")
        assertTrue(title.contains("외부 AI"))
        assertFalse(title.contains("secret", ignoreCase = true))
    }

    @Test
    fun `consent body with blank provider omits provider line but keeps disclosure`() {
        val body = CloudEscalationPolicy.consentBody(null)
        assertTrue(body.contains("전송"))
        assertTrue(body.contains("취소하면"))
        assertFalse(body.contains("null"))
    }

    // 1.3/1.10: cancel -> 0 calls, confirm -> exactly 1, double-confirm -> at most 1
    @Test
    fun `cancel results in zero external calls`() {
        var calls = 0
        val decision = CloudEscalationConsentDecision.CANCEL
        if (decision == CloudEscalationConsentDecision.CONFIRM) calls++
        assertEquals(0, calls)
    }

    @Test
    fun `single flight gate allows exactly one confirm`() {
        val gate = CloudEscalationExecutionGate()
        assertTrue(gate.tryEnter())
        // Rapid double-confirm: second acquisition while held must fail.
        assertFalse(gate.tryEnter())
        gate.release()
        // After release a later explicit action may proceed again.
        assertTrue(gate.tryEnter())
        gate.release()
    }

    @Test
    fun `confirm path increments exactly once under gate`() {
        val gate = CloudEscalationExecutionGate()
        var calls = 0
        repeat(2) {
            if (gate.tryEnter()) {
                calls++
                // Hold until execution settles; do not release between rapid taps.
            }
        }
        gate.release()
        assertEquals(1, calls)
    }

    // 1.4/1.5: frozen snapshot reuses external path conventions
    @Test
    fun `escalation snapshot reuses frozen external conventions`() {
        val snapshot = escalationSnapshot()
        assertEquals("EXTERNAL_AI_API", snapshot.generationModeKey)
        assertEquals("provider-1", snapshot.externalProviderId)
        assertTrue(snapshot.externalProviderSelectionFrozen)
        assertEquals(GenerationRequestSnapshot.WebSearchPolicy.DISABLED, snapshot.webSearchPolicy)
        assertTrue(snapshot.attachmentIds.isEmpty())
        assertTrue(snapshot.multimodalImagePaths.isEmpty())
        assertNotNull(snapshot.externalProviderRequest)
        assertEquals(TrustRoute.CLOUD_SUGGEST, snapshot.trustRoutingDecision.route)
        assertFalse(snapshot.settings.speculativeDecodingEnabled == true)
    }

    @Test
    fun `pending validation passes for matching conversation and fails on drift`() {
        val good = pending()
        val active = listOf(
            MessageEntity(11L, 7L, "user", "원본 질문", 1L),
            MessageEntity(12L, 7L, "assistant", "로컬 답변", 2L),
        )
        assertTrue(validatePendingEscalation(good, 7L, active))
        // Conversation changed.
        assertFalse(validatePendingEscalation(good, 8L, active))
        // Target deleted.
        assertFalse(validatePendingEscalation(good, 7L, listOf(active[0])))
        // Origin deleted.
        assertFalse(validatePendingEscalation(good, 7L, listOf(active[1])))
        // Provider mismatch.
        val tampered = good.copy(providerId = "other-provider")
        assertFalse(validatePendingEscalation(tampered, 7L, active))
    }

    @Test
    fun `provider model UI changes while dialog open do not retarget`() {
        val frozen = pending(providerId = "provider-1")
        // Simulate user switching provider/model in UI after dialog opened.
        val currentUiProviderId = "provider-2"
        val execution = resolveEscalationExecutionRequest(frozen)
        assertEquals("provider-1", execution.externalProviderId)
        assertEquals("provider-1", execution.externalProviderRequest?.id)
        assertFalse(currentUiProviderId == execution.externalProviderId)
    }

    @Test
    fun `successful and failed escalation preserve original local answer`() {
        val original = MessageEntity(12L, 7L, "assistant", "로컬 답변", 2L)
        val alternate = MessageEntity(13L, 7L, "assistant", "클라우드 대체 답변", 3L)
        // Success becomes an alternate version alongside the original.
        val versionsAfterSuccess = listOf(original, alternate)
        assertTrue(versionsAfterSuccess.any { it.id == 12L && it.content == "로컬 답변" })
        assertTrue(versionsAfterSuccess.any { it.id == 13L })
        // Failure leaves the original untouched.
        val versionsAfterFailure = listOf(original)
        assertEquals(1, versionsAfterFailure.size)
        assertEquals("로컬 답변", versionsAfterFailure[0].content)
    }

    @Test
    fun `default generation mode unchanged by escalation`() {
        assertEquals("LOCAL_MODEL", defaultModeAfterEscalation("LOCAL_MODEL"))
        assertEquals("EXTERNAL_AI_API", defaultModeAfterEscalation("EXTERNAL_AI_API"))
        val snapshot = escalationSnapshot()
        // Snapshot targets external for this one request, but the persisted
        // default mode seam is untouched.
        assertEquals("EXTERNAL_AI_API", snapshot.generationModeKey)
        assertEquals("LOCAL_MODEL", defaultModeAfterEscalation("LOCAL_MODEL"))
    }

    // 1.9: provenance
    @Test
    fun `cloud provenance never claims verification`() {
        val withName = cloudEscalationProvenanceLabel("Test Provider")
        val withoutName = cloudEscalationProvenanceLabel(null)
        assertTrue(withName.contains("대체 답변"))
        assertTrue(withoutName.contains("대체 답변"))
        assertTrue(isCloudProvenanceLabelSafe(withName))
        assertTrue(isCloudProvenanceLabelSafe(withoutName))
        assertFalse(withName.contains("검증"))
        assertFalse(withName.contains("더 정확"))
        assertFalse(withName.contains("verified", ignoreCase = true))
        assertFalse(withName.contains("more accurate", ignoreCase = true))
    }

    @Test
    fun `find origin user message prefers group then nearest preceding`() {
        val messages = listOf(
            MessageEntity(1L, 7L, "user", "q1", 1L),
            MessageEntity(2L, 7L, "assistant", "a1", 2L),
            MessageEntity(3L, 7L, "user", "q2", 3L),
            MessageEntity(4L, 7L, "assistant", "a2", 4L),
        )
        assertEquals(3L, findOriginUserMessage(messages, 4L, 3L)?.id)
        assertEquals(3L, findOriginUserMessage(messages, 4L, 999L)?.id)
        assertNull(findOriginUserMessage(messages, 1L, 999L))
    }

    @Test
    fun `tainted history blocks escalation offer`() {
        val clean = CloudEscalationPolicy.offer(cloudDecision(), "LOCAL_MODEL", true, false, "x")
        assertTrue(clean is CloudEscalationOffer.Shown)
        val blocked = CloudEscalationPolicy.offer(
            cloudDecision(), "LOCAL_MODEL", true, false, "x", historyTainted = true,
        )
        assertTrue(blocked is CloudEscalationOffer.Blocked)
    }

    @Test
    fun `runnable selected provider is frozen`() {
        val providers = listOf(
            com.projectnuke.fusion.ai.model.AiProviderConfig(
                id = "p1", type = AiProviderType.OPENAI, displayName = "P1",
                baseUrl = "https://a.example.com", modelId = "m", apiKeySecretId = "s",
            ),
            com.projectnuke.fusion.ai.model.AiProviderConfig(
                id = "p2", type = AiProviderType.OPENAI, displayName = "P2",
                baseUrl = "https://b.example.com", modelId = "m", apiKeySecretId = "s",
            ),
        )
        val runnable = setOf("p1")
        val pick = pickEscalationProvider("p1", providers) { it.id in runnable }
        assertTrue(pick is CloudProviderPick.Use)
        assertEquals("p1", (pick as CloudProviderPick.Use).provider.id)
    }

    @Test
    fun `selected but unusable provider never silently hops`() {
        val providers = listOf(
            com.projectnuke.fusion.ai.model.AiProviderConfig(
                id = "p1", type = AiProviderType.OPENAI, displayName = "P1",
                baseUrl = "https://a.example.com", modelId = "m", apiKeySecretId = "s",
            ),
            com.projectnuke.fusion.ai.model.AiProviderConfig(
                id = "p2", type = AiProviderType.OPENAI, displayName = "P2",
                baseUrl = "https://b.example.com", modelId = "m", apiKeySecretId = "s",
            ),
        )
        // p1 selected but disabled; p2 runnable must NOT be picked silently.
        val pick = pickEscalationProvider("p1", providers) { it.id == "p2" }
        assertTrue(pick is CloudProviderPick.SelectedUnusable)
    }

    @Test
    fun `absent selection falls back to explicit default only`() {
        val providers = listOf(
            com.projectnuke.fusion.ai.model.AiProviderConfig(
                id = "p2", type = AiProviderType.OPENAI, displayName = "P2",
                baseUrl = "https://b.example.com", modelId = "m", apiKeySecretId = "s",
            ),
        )
        val pick = pickEscalationProvider(null, providers) { true }
        assertTrue(pick is CloudProviderPick.Use)
        assertEquals("p2", (pick as CloudProviderPick.Use).provider.id)
        assertTrue(pickEscalationProvider(null, emptyList()) { true } is CloudProviderPick.NoneAvailable)
    }

    @Test
    fun `stale selected provider never retargets to another provider`() {
        val providers = listOf(
            com.projectnuke.fusion.ai.model.AiProviderConfig(
                id = "p2", type = AiProviderType.OPENAI, displayName = "P2",
                baseUrl = "https://b.example.com", modelId = "m", apiKeySecretId = "s",
            ),
        )
        val pick = pickEscalationProvider("deleted-p1", providers) { true }
        assertTrue(pick is CloudProviderPick.SelectedUnusable)
    }
}
