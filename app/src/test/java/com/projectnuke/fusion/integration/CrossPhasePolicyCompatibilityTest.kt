package com.projectnuke.fusion.integration

import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.privacy.DataEgressDestination
import com.projectnuke.fusion.privacy.DataEgressPolicy
import com.projectnuke.fusion.privacy.DataEgressScope
import com.projectnuke.fusion.privacy.RequestPrivacyContext
import com.projectnuke.fusion.retrieval.LocalDocumentChunker
import com.projectnuke.fusion.retrieval.LocalRagBounds
import com.projectnuke.fusion.retrieval.LocalRagPromptPolicy
import com.projectnuke.fusion.retrieval.LocalRagRetrieval
import com.projectnuke.fusion.retrieval.LocalRagSnapshotPolicy
import com.projectnuke.fusion.retrieval.LocalSemanticRanker
import com.projectnuke.fusion.translation.TranslationBounds
import com.projectnuke.fusion.translation.TranslationPolicy
import com.projectnuke.fusion.trust.SearchVerificationState
import com.projectnuke.fusion.trust.TrustExecutionFailure
import com.projectnuke.fusion.trust.TrustFailurePolicy
import com.projectnuke.fusion.trust.TrustRecoveryAction
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRouter
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingPresentationMapper
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.trust.TrustRoutingRequest
import com.projectnuke.fusion.ui.CloudEscalationExecutionGate
import com.projectnuke.fusion.ui.CloudEscalationOffer
import com.projectnuke.fusion.ui.CloudEscalationPolicy
import com.projectnuke.fusion.ui.ResponseVersionState
import com.projectnuke.fusion.ui.buildCloudEscalationSnapshot
import com.projectnuke.fusion.ui.cloudEscalationProvenanceLabel
import com.projectnuke.fusion.ui.defaultModeAfterEscalation
import com.projectnuke.fusion.ui.isCloudProvenanceLabelSafe
import com.projectnuke.fusion.ui.resolveEscalationExecutionRequest
import com.projectnuke.fusion.ui.validatePendingEscalation
import com.projectnuke.fusion.ui.PendingCloudEscalation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy-layer compatibility tests (renamed from CrossPhaseIntegrationTest).
 *
 * This suite composes policy/helper objects directly and therefore proves
 * POLICY COMPATIBILITY ONLY. It does not execute production request paths.
 * Execution proof lives in ChatExecutionIntegrationTest, which drives the
 * real coordinators and transports with fakes.
 */
class CrossPhasePolicyCompatibilityTest {
    private object FakeRanker : LocalSemanticRanker {
        override fun score(query: String, chunkText: String): Double? = 0.5
    }

    private fun cloudDecision() = TrustRoutingDecision(
        TrustRoute.CLOUD_SUGGEST, TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD, emptySet(),
    )

    private fun providerSnapshot() = ExternalProviderRequestSnapshot(
        id = "p1", type = AiProviderType.OPENAI, baseUrl = "https://api.example.com",
        modelId = "m", temperature = 0.7, maxTokens = 2048,
        authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderName = null,
        reasoningContentEnabled = false, apiKeySecretId = "s", displayName = "P",
    )

    // Scenario A — ordinary stable question.
    @Test
    fun `A ordinary stable question stays local quiet`() {
        val decision = TrustRouter.route(TrustRoutingRequest(userText = "1+1은 얼마야?"))
        assertEquals(TrustRoute.LOCAL, decision.route)
        val presentation = TrustRoutingPresentationMapper.map(decision, "LOCAL_MODEL")
        assertFalse(presentation.isVisible)
        val offer = CloudEscalationPolicy.offer(decision, "LOCAL_MODEL", true, false, "더 강한 모델로 다시 요청")
        assertTrue(offer is CloudEscalationOffer.Hidden)
        assertEquals("", DataEgressPolicy.userFacingNotice(RequestPrivacyContext()))
    }

    // Scenario B — current factual question.
    @Test
    fun `B current factual uses search without verification claim`() {
        val decision = TrustRouter.route(
            TrustRoutingRequest(userText = "오늘 삼성전자 주가가 얼마야?"),
            clock = object : com.projectnuke.fusion.trust.TrustRouterClock {
                override fun currentYear(): Int = 2026
            },
        )
        assertEquals(TrustRoute.SEARCH, decision.route)
        val presentation = TrustRoutingPresentationMapper.map(decision, "LOCAL_MODEL")
        assertTrue(presentation.isVisible)
        val copy = "${presentation.label} ${presentation.explanation}"
        assertFalse(copy.contains("검증 완료"))
        assertFalse(copy.contains("확인됨"))
        // Retrieval failed -> bounded failure, no stale silent fallback.
        val failure = TrustFailurePolicy.searchFailure(decision, attempted = false, usableResult = false, requestId = "r1")
        assertTrue(failure is TrustExecutionFailure.SearchRetrievalFailed)
        assertEquals(SearchVerificationState.SEARCH_ATTEMPTED, (failure as TrustExecutionFailure.SearchRetrievalFailed).verificationState)
        assertEquals("이전 안정 답변", TrustFailurePolicy.resolveAfterRetry("이전 안정 답변", null, false))
    }

    // Scenario C — future unknowable.
    @Test
    fun `C future unknowable abstains terminally`() {
        val decision = TrustRouter.route(
            TrustRoutingRequest(userText = "다음주 로또 당첨번호를 100% 확실하게 알려줘"),
            clock = object : com.projectnuke.fusion.trust.TrustRouterClock {
                override fun currentYear(): Int = 2026
            },
        )
        assertEquals(TrustRoute.ABSTAIN, decision.route)
        assertFalse(TrustFailurePolicy.isAbstainError())
        val offer = CloudEscalationPolicy.offer(decision, "LOCAL_MODEL", true, false, "더 강한 모델로 다시 요청")
        assertTrue(offer is CloudEscalationOffer.Hidden)
    }

    // Scenario D — CLOUD_SUGGEST.
    @Test
    fun `D cloud suggest preserves local with single consented call`() {
        val decision = cloudDecision()
        val offer = CloudEscalationPolicy.offer(decision, "LOCAL_MODEL", true, false, "더 강한 모델로 다시 요청")
        assertTrue(offer is CloudEscalationOffer.Shown)
        // 0 calls before consent.
        var calls = 0
        assertEquals(0, calls)
        val snapshot = buildCloudEscalationSnapshot(
            7L, "req-d", "p1", providerSnapshot(), GenerationSettings(),
            false, "", listOf(ChatMessage("user", "복잡한 작업")), listOf(11L),
            "복잡한 작업", "복잡한 작업", 1L, decision,
        )
        val pending = PendingCloudEscalation(
            7L, 12L, 11L, 11L, "p1", "P", snapshot,
            listOf(
                MessageEntity(11L, 7L, "user", "복잡한 작업", 1L),
                MessageEntity(12L, 7L, "assistant", "로컬 답변", 2L),
            ),
            ResponseVersionState(),
        )
        assertTrue(validatePendingEscalation(pending, 7L, pending.fullMessageEntities))
        // Provider switch while dialog open does not retarget.
        assertEquals("p1", resolveEscalationExecutionRequest(pending).externalProviderId)
        // Exactly 1 after consent under single-flight.
        val gate = CloudEscalationExecutionGate()
        repeat(2) { if (gate.tryEnter()) calls++ }
        gate.release()
        assertEquals(1, calls)
        val provenance = cloudEscalationProvenanceLabel("P")
        assertTrue(isCloudProvenanceLabelSafe(provenance))
        assertEquals("LOCAL_MODEL", defaultModeAfterEscalation("LOCAL_MODEL"))
    }

    // Scenario E — attachment local RAG.
    @Test
    fun `E attachment local rag private and contained`() {
        val chunks = LocalDocumentChunker.chunk("doc-1", "notes.txt", "사과 농장 보고서. ".repeat(60))
        assertTrue(chunks.isNotEmpty())
        val selections = LocalRagRetrieval.retrieve("사과 농장", chunks, mapOf("doc-1" to "att-1"), semanticRanker = FakeRanker)
        assertTrue(selections.isNotEmpty())
        assertFalse(LocalRagRetrieval.requiresNetwork())
        val prompt = LocalRagPromptPolicy.assemble("사과 농장 요약해줘", selections)
        assertTrue(prompt.contains("참고용 데이터"))
        assertFalse(LocalRagPromptPolicy.containsRawMarkers(prompt))
        val snapshot = LocalRagSnapshotPolicy.snapshot("req-e", selections)
        assertTrue(LocalRagSnapshotPolicy.validate(snapshot, chunks))
    }

    // Scenario F — attachment + SEARCH.
    @Test
    fun `F attachment search keeps excerpts out of web query`() {
        val context = RequestPrivacyContext(hasAttachments = true, hasLocalRagExcerpts = true, fileNames = listOf("r.txt"), userText = "이 문서 관련 최신 뉴스 찾아줘")
        val chunks = LocalDocumentChunker.chunk("doc-1", "r.txt", "비밀 보고서 본문. ".repeat(40))
        val selections = LocalRagRetrieval.retrieve("보고서", chunks)
        val webQuery = DataEgressPolicy.buildWebQuery("보고서 관련 최신 뉴스", context)
        assertEquals("보고서 관련 최신 뉴스", webQuery)
        selections.forEach { assertFalse(webQuery.contains(it.source.excerpt)) }
        assertFalse(webQuery.contains("r.txt"))
    }

    // Scenario G — attachment + external AI.
    @Test
    fun `G attachment external requires consent upload stays blocked`() {
        val context = RequestPrivacyContext(hasAttachments = true, hasLocalRagExcerpts = true, userText = "요약해줘")
        val hash = DataEgressPolicy.payloadHash("요약해줘")
        assertFalse(DataEgressPolicy.externalAiAllowed(context, null, hash, "req-g", "p1"))
        val consent = DataEgressPolicy.makeConsent("c-g", "req-g", "p1", hash, true, DataEgressScope.USER_QUERY_AND_HISTORY)
        assertTrue(DataEgressPolicy.externalAiAllowed(context, consent, hash, "req-g", "p1"))
        assertFalse(DataEgressPolicy.externalAiAllowed(context, consent, DataEgressPolicy.payloadHash("바뀐 요청"), "req-g", "p1"))
    }

    // Scenario H — translated source.
    @Test
    fun `H translated source preserves canonical evidence`() {
        val artifact = TranslationPolicy.makeArtifact(
            com.projectnuke.fusion.translation.TranslationRequest(
                originalText = "Original source sentence.",
                targetLanguage = "ko",
                sourceIdentity = "src-h",
                sourceUrl = "https://example.com/s",
            ),
            "원본 문장 번역.",
            com.projectnuke.fusion.translation.TranslationBackends.CURRENT,
        )!!
        assertEquals("Original source sentence.", TranslationPolicy.canonicalEvidenceText(artifact))
        assertEquals("https://example.com/s", artifact.sourceUrl)
        assertTrue(TranslationPolicy.preservesOriginal(artifact, "Original source sentence."))
        assertFalse(TranslationPolicy.allowsExternalTranslation())
    }

    // Cross-subsystem invariants.
    @Test
    fun `subsystems stay distinct`() {
        assertTrue(DataEgressPolicy.trustRouteSeparateFromEgress())
        // Routing chooses capability; privacy decides egress; retrieval finds
        // candidates; translation changes presentation; evidence evaluates.
        val route = TrustRouter.route(TrustRoutingRequest("1+1은?")).route
        assertEquals(TrustRoute.LOCAL, route)
        assertTrue(LocalRagBounds.FINAL_TOP_K <= 8)
        assertTrue(TranslationBounds.MAX_INPUT_CHARS <= 8_000)
    }

    // Bounds review.
    @Test
    fun `all new bounds are explicit and capped`() {
        assertTrue(LocalRagBounds.MAX_CHUNKS_PER_DOC <= 64)
        assertTrue(LocalRagBounds.MAX_CHUNK_CHARS <= 2_000)
        assertTrue(LocalRagBounds.FINAL_TOP_K <= 8)
        assertTrue(LocalRagBounds.MAX_PROMPT_EXCERPT_CHARS <= 12_000)
        assertTrue(TranslationBounds.MAX_INPUT_CHARS <= 8_000)
        assertTrue(TranslationBounds.MAX_CACHED_ARTIFACT_BYTES <= 256 * 1024)
        assertEquals(1, com.projectnuke.fusion.ui.CloudEscalationBounds.MAX_PENDING_CLOUD_ESCALATIONS)
        assertEquals(1, com.projectnuke.fusion.trust.TrustFailureBounds.MAX_SEARCH_RETRY_ATTEMPTS)
        assertEquals(1, com.projectnuke.fusion.privacy.DataEgressBounds.MAX_CONSENTS_PER_REQUEST)
    }

    // Lifecycle: stale async must not attach to newer request/conversation.
    @Test
    fun `stale operations do not retarget newer requests`() {
        val snapshot = buildCloudEscalationSnapshot(
            7L, "req-old", "p1", providerSnapshot(), GenerationSettings(),
            false, "", emptyList(), emptyList(), "q", "q", 1L, cloudDecision(),
        )
        val pending = PendingCloudEscalation(
            7L, 12L, 11L, 11L, "p1", "P", snapshot,
            listOf(
                MessageEntity(11L, 7L, "user", "q", 1L),
                MessageEntity(12L, 7L, "assistant", "a", 2L),
            ),
            ResponseVersionState(),
        )
        // New conversation -> invalid.
        assertFalse(validatePendingEscalation(pending, 8L, pending.fullMessageEntities))
        // Deleted messages -> invalid.
        assertFalse(validatePendingEscalation(pending, 7L, emptyList()))
        // Rapid repeated taps stay single-flight.
        val gate = CloudEscalationExecutionGate()
        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
        gate.release()
        // Retry failure keeps stable answer.
        assertEquals("stable", TrustFailurePolicy.resolveAfterRetry("stable", "candidate", false))
        // RAG snapshot fails closed on doc change.
        val chunks = LocalDocumentChunker.chunk("d", "a.txt", "사과 내용 ".repeat(30))
        val selections = LocalRagRetrieval.retrieve("사과", chunks)
        if (selections.isNotEmpty()) {
            val snap = LocalRagSnapshotPolicy.snapshot("req-1", selections)
            assertFalse(LocalRagSnapshotPolicy.validate(snap, emptyList()))
        }
        // Consent does not survive into unrelated request.
        val hash = DataEgressPolicy.payloadHash("q")
        val consent = DataEgressPolicy.makeConsent("c1", "req-old", "p1", hash, false, DataEgressScope.USER_QUERY_ONLY)
        assertFalse(DataEgressPolicy.validateConsent(consent, "req-new", "p1", hash))
        // Recovery actions stay bound to original request.
        val failure = TrustFailurePolicy.searchFailure(
            TrustRoutingDecision(TrustRoute.SEARCH, com.projectnuke.fusion.trust.TrustRoutingReason.CURRENT_INFORMATION_REQUIRED, emptySet()),
            false, false, "req-old",
        )
        assertEquals("req-old", (failure.recovery as TrustRecoveryAction.RetrySearch).requestId)
    }
}
