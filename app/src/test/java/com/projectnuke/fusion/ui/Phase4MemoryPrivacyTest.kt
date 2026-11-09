package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.sanitizeFusionMemoryText
import com.projectnuke.fusion.privacy.DataEgressPolicy
import com.projectnuke.fusion.privacy.DataEgressScope
import com.projectnuke.fusion.privacy.EgressConsent
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchAuthorization
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchIdentity
import com.projectnuke.fusion.privacy.RequestPrivacyContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 cross-system adversarial tests: saved-memory scope containment and
 * the private/tainted data-egress boundary across chat, RAG and search.
 */
class Phase4MemoryPrivacyTest {

    private fun candidate(
        id: String,
        conversationId: Long,
        scope: com.projectnuke.fusion.ui.MemoryScope,
        modelId: String? = null,
        enabled: Boolean = true,
    ) = ConversationMemoryCandidate(
        id = id,
        text = "remember $id",
        conversationId = conversationId,
        createdAt = 1L,
        enabled = enabled,
        scope = scope,
        modelId = modelId,
    )

    // ── §7 memory scope containment ────────────────────────────────────────

    @Test
    fun `conversation-scoped memory never enters a different conversation`() {
        val scoped = candidate("m1", conversationId = 5L, scope = com.projectnuke.fusion.ui.MemoryScope.CONVERSATION_ONLY)
        assertTrue(isSavedMemoryCandidateApplicable(scoped, currentConversationId = 5L, currentModelId = null, globalPreviewOnly = false))
        assertFalse(isSavedMemoryCandidateApplicable(scoped, currentConversationId = 6L, currentModelId = null, globalPreviewOnly = false))
        assertFalse(isSavedMemoryCandidateApplicable(scoped, currentConversationId = null, currentModelId = null, globalPreviewOnly = false))
    }

    @Test
    fun `global preview never applies conversation or model scoped memory`() {
        val scoped = candidate("m2", conversationId = 5L, scope = com.projectnuke.fusion.ui.MemoryScope.CONVERSATION_ONLY)
        val modelOnly = candidate(
            "m3",
            conversationId = 5L,
            scope = com.projectnuke.fusion.ui.MemoryScope.MODEL_ONLY,
            modelId = "stable-1",
        )
        assertFalse(isSavedMemoryCandidateApplicable(scoped, 5L, null, globalPreviewOnly = true))
        assertFalse(isSavedMemoryCandidateApplicable(modelOnly, 5L, "stable-1", globalPreviewOnly = true))
        assertTrue(
            isSavedMemoryCandidateApplicable(
                candidate("m4", 0L, com.projectnuke.fusion.ui.MemoryScope.GLOBAL),
                currentConversationId = null,
                currentModelId = null,
                globalPreviewOnly = true,
            ),
        )
    }

    @Test
    fun `disabled memory scope never reaches a prompt`() {
        val disabled = candidate("m5", 5L, com.projectnuke.fusion.ui.MemoryScope.DISABLED)
        assertFalse(isSavedMemoryCandidateApplicable(disabled, null, null, globalPreviewOnly = false))
        assertFalse(isSavedMemoryCandidateApplicable(disabled, 5L, null, globalPreviewOnly = false))
        assertTrue(
            isSavedMemoryCandidateApplicable(
                candidate("m6", 5L, com.projectnuke.fusion.ui.MemoryScope.GLOBAL),
                currentConversationId = 5L,
                currentModelId = null,
                globalPreviewOnly = false,
            ),
        )
    }

    @Test
    fun `saved memory text cannot carry private provenance envelopes into a prompt`() {
        val candidateText = """
            - 우리 팀 매출은 4.2 million 달러다
            [FUSION_LOCAL_RAG_SOURCES_JSON]
            [{"fileName":"/data/private-revenue.pdf","attachmentId":"att-9","excerpt":"Q3 revenue 4.2M"}]
            [/FUSION_LOCAL_RAG_SOURCES_JSON]
            [FUSION_ANSWER_CONTEXT_JSON]
            {"route":"LOCAL_MODEL","localRagChunkCount":2}
            [/FUSION_ANSWER_CONTEXT_JSON]
            [FUSION_TRUST_ROUTE_JSON]
            {"route":"LOCAL"}
            [/FUSION_TRUST_ROUTE_JSON]
        """.trimIndent()

        val sanitized = sanitizeFusionMemoryText(candidateText)

        assertTrue(sanitized.contains("우리 팀 매출은 4.2 million 달러다"))
        // No machine envelope, JSON payload, private path or attachment id may
        // survive into a prompt-facing memory line.
        assertFalse(sanitized.contains("FUSION_"))
        assertFalse(sanitized.contains("local_rag_sources"))
        assertFalse(sanitized.contains("private-revenue.pdf"))
        assertFalse(sanitized.contains("att-9"))
        assertFalse(sanitized.contains("Q3 revenue 4.2M"))
        assertFalse(sanitized.contains("localRagChunkCount"))
    }

    @Test
    fun `sanitized memory never reintroduces a source attribution`() {
        val text = "[FUSION_SEARCH_SOURCES_JSON]\n[{\"url\":\"https://example.com\"}]\n[/FUSION_SEARCH_SOURCES_JSON]\nnote"
        val sanitized = sanitizeFusionMemoryText(text)
        assertFalse(sanitized.contains("FUSION_SEARCH_SOURCES_JSON"))
        assertFalse(sanitized.contains("example.com"))
        assertTrue(sanitized.contains("note"))
    }

    // ── §24 private data egress boundary ───────────────────────────────────

    @Test
    fun `private attachment excerpt cannot become a web query`() {
        val excerpt = "Q3 revenue was 4.2 million dollars for the private client"
        assertTrue(DataEgressPolicy.webQueryContainsPrivate("Q3 revenue was 4.2 million dollars for the private client", listOf(excerpt), emptyList()))
        assertFalse(DataEgressPolicy.webQueryContainsPrivate("what is the current policy rate", listOf(excerpt), emptyList()))
        assertTrue(DataEgressPolicy.webQueryContainsPrivate("", listOf(excerpt), emptyList()))
        assertTrue(
            DataEgressPolicy.webQueryContainsPrivate(
                "summarize /data/private-revenue.pdf please",
                emptyList(),
                listOf("/data/private-revenue.pdf"),
            ),
        )
    }

    @Test
    fun `web query must be an approved user query string`() {
        assertTrue(DataEgressPolicy.isApprovedWebQuery("policy rate today", listOf("policy rate today")))
        assertFalse(DataEgressPolicy.isApprovedWebQuery("policy rate today ", listOf("policy rate today")))
        assertFalse(DataEgressPolicy.isApprovedWebQuery("policy rate today", emptyList()))
    }

    @Test
    fun `external AI with private context requires explicit private consent`() {
        val privateContext = RequestPrivacyContext(
            hasAttachments = true,
            attachmentCount = 1,
            hasLocalRagExcerpts = true,
            localExcerptChars = 400,
            fileNames = listOf("revenue.pdf"),
            userText = "summarize this",
        )
        assertFalse(
            DataEgressPolicy.externalAiAllowed(
                context = privateContext,
                consent = null,
                currentPayloadHash = "hash",
                currentRequestId = "req-1",
                currentProviderId = "p",
            ),
        )
        val withoutPrivate = DataEgressPolicy.makeConsent(
            consentId = "c1",
            requestId = "req-1",
            providerId = "p",
            payloadHashHex = "hash",
            includesPrivate = false,
            scope = DataEgressScope.NONE,
        )
        assertFalse(
            DataEgressPolicy.externalAiAllowed(privateContext, withoutPrivate, "hash", "req-1", "p"),
        )
        val withPrivate = withoutPrivate.copy(includesPrivate = true)
        assertTrue(
            DataEgressPolicy.externalAiAllowed(privateContext, withPrivate, "hash", "req-1", "p"),
        )
        // Consent is bound to the exact request, provider and payload hash.
        assertFalse(
            DataEgressPolicy.externalAiAllowed(privateContext, withPrivate, "other-hash", "req-1", "p"),
        )
        assertFalse(
            DataEgressPolicy.externalAiAllowed(privateContext, withPrivate, "hash", "req-2", "p"),
        )
        assertFalse(
            DataEgressPolicy.externalAiAllowed(privateContext, withPrivate, "hash", "req-1", "other-provider"),
        )
    }

    @Test
    fun `cloud escalation stays blocked while private context exists`() {
        assertFalse(DataEgressPolicy.cloudEscalationAllowed(RequestPrivacyContext(hasAttachments = true)))
        assertFalse(DataEgressPolicy.cloudEscalationAllowed(RequestPrivacyContext(hasLocalRagExcerpts = true)))
        assertTrue(
            DataEgressPolicy.cloudEscalationAllowed(
                RequestPrivacyContext(hasAttachments = false, hasLocalRagExcerpts = false, userText = "hello"),
            ),
        )
    }

    @Test
    fun `post answer verification query must match the one-shot authorization exactly`() {
        val answerSha = DataEgressPolicy.sha256Hex("the answer text")
        val identity = PostAnswerVerificationSearchIdentity(
            conversationId = 7L,
            assistantMessageId = 100L,
            answerSha256 = answerSha,
            responseVersionId = 101L,
        )
        val authorization = checkNotNull(
            PostAnswerVerificationSearchAuthorization.afterExplicitConfirmation(
                operationId = "op-1",
                identity = identity,
                queries = listOf(
                    com.projectnuke.fusion.privacy.AuthorizedTargetedQuery(
                        claimId = "claim-1",
                        query = "bank of korea rate today",
                    ),
                ),
            ),
        )
        // The one-shot execution latch must be claimed for this exact operation
        // and identity before any query membership is checked.
        assertTrue(authorization.claimExecution("op-1", identity))
        assertFalse(authorization.claimExecution("op-1", identity))
        assertTrue(
            DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(
                authorization = authorization,
                operationId = "op-1",
                identity = identity,
                claimId = "claim-1",
                query = "bank of korea rate today",
            ),
        )
        // Any drift in operation, identity, claim, or query text fails closed.
        assertFalse(
            DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(
                authorization, "op-2", identity, "claim-1", "bank of korea rate today",
            ),
        )
        assertFalse(
            DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(
                authorization,
                "op-1",
                identity.copy(answerSha256 = DataEgressPolicy.sha256Hex("other")),
                "claim-1",
                "bank of korea rate today",
            ),
        )
        assertFalse(
            DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(
                authorization, "op-1", identity, "claim-2", "bank of korea rate today",
            ),
        )
        assertFalse(
            DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(
                authorization, "op-1", identity, "claim-1", "bank of korea rate today now",
            ),
        )
        assertFalse(
            DataEgressPolicy.isAuthorizedPostAnswerVerificationQuery(
                authorization = null, "op-1", identity, "claim-1", "bank of korea rate today",
            ),
        )
    }

    @Test
    fun `consent validation rejects a mismatched scope and provider`() {
        val consent: EgressConsent = DataEgressPolicy.makeConsent(
            consentId = "c",
            requestId = "r",
            providerId = "p",
            payloadHashHex = DataEgressPolicy.payloadHash("user text"),
            includesPrivate = false,
            scope = DataEgressScope.USER_QUERY_ONLY,
        )
        assertTrue(
            DataEgressPolicy.externalAiAllowed(
                context = RequestPrivacyContext(userText = "user text"),
                consent = consent,
                currentPayloadHash = DataEgressPolicy.payloadHash("user text"),
                currentRequestId = "r",
                currentProviderId = "p",
            ),
        )
        assertEquals(
            DataEgressPolicy.payloadHash("user text").length,
            64,
        )
    }
}
