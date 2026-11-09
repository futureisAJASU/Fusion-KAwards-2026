package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.NextActionEligibilityPolicy
import com.projectnuke.fusion.retrieval.LocalRagSourceEntry
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.search.appendSearchSourcesMetadata
import com.projectnuke.fusion.trust.TrustRecoveryAction
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingMetadataCodec
import com.projectnuke.fusion.trust.TrustRoutingPresentationKind
import com.projectnuke.fusion.trust.TrustRoutingPresentationMapper
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.util.AttachmentMessageCodec
import com.projectnuke.fusion.util.AttachmentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.1 single visible-answer authority: Copy, Share, Next Actions and the
 * read-aloud/translation handoffs all operate on exactly the text the
 * bubble renders ([assistantVisibleAnswerForHandoff]). No parallel
 * parser/stripper may diverge from it.
 */
class AssistantVisibleAnswerAuthorityTest {

    private fun seed(
        content: String,
        reasoningEnabled: Boolean = false,
        generationModeKey: String? = "LOCAL_MODEL",
    ): String = assistantVisibleAnswerForHandoff(content, reasoningEnabled, generationModeKey)

    private fun webSource() = WebSearchSource(
        title = "Hidden Title",
        source = "HiddenSource",
        url = "https://hidden.example.test/a",
        snippet = "hidden-snippet",
        publishedAt = null,
        providerType = WebSearchProviderType.FREE_DEFAULT,
        providerDisplayName = "HiddenProvider",
        searchCategory = "web",
        queryUsed = "hidden-query",
        rank = 1,
        score = 0.9,
    )

    private fun ragEntry() = LocalRagSourceEntry(
        fileName = "secret-doc.pdf",
        attachmentId = "att-secret",
        chunkIndex = 3,
        startOffset = 128,
        endOffset = 512,
        contentHashHex = "deadbeefcafe",
        excerpt = "비밀 발췌 본문",
        rank = 0,
        lexicalScore = 5,
        semanticScore = 0.7,
        pageNumber = 7,
    )

    @Test
    fun `normal assistant answer is the action-visible text`() {
        val answer = "평범한 답변입니다.\n두 번째 줄도 그대로."
        assertEquals(answer, seed(answer))
    }

    @Test
    fun `attachment envelope paths tags and encoded payload are excluded`() {
        val raw = AttachmentMessageCodec.serializeAttachmentMessage(
            listOf(AttachmentRecord("notes.txt", "text/plain", "/root/secret/notes.txt")),
            "보이는 답변입니다.",
        )
        val seeded = seed(raw)

        assertEquals("보이는 답변입니다.", seeded)
        assertFalse(seeded.contains("/root/secret"))
        assertFalse(seeded.contains("notes.txt"))
        assertFalse(seeded.contains("fusion_attachment"))
    }

    @Test
    fun `hidden search trust and rag metadata are excluded`() {
        val withSearch = appendSearchSourcesMetadata("보이는 답변입니다.", listOf(webSource()))
        val withRag = LocalRagSourceMetadata.append(withSearch, listOf(ragEntry()))
        val stored = TrustRoutingMetadataCodec.append(
            withRag,
            TrustRoutingDecision(
                route = TrustRoute.SEARCH,
                reason = TrustRoutingReason.CURRENT_OR_RECENT,
                signals = emptySet(),
            ),
        )
        val seeded = seed(stored)

        assertEquals("보이는 답변입니다.", seeded)
        assertFalse(seeded.contains("hidden.example.test"))
        assertFalse(seeded.contains("FUSION_SEARCH_SOURCES_JSON"))
        assertFalse(seeded.contains("FUSION_TRUST_ROUTE_JSON"))
        assertFalse(seeded.contains("FUSION_LOCAL_RAG_SOURCES_JSON"))
        assertFalse(seeded.contains("비밀 발췌 본문"))
        assertFalse(seeded.contains("secret-doc.pdf"))
        assertFalse(seeded.contains("hidden-query"))
    }

    @Test
    fun `abstain visible fallback matches rendered text but suppresses next actions`() {
        val decision = TrustRoutingDecision(
            route = TrustRoute.ABSTAIN,
            reason = TrustRoutingReason.FUTURE_UNKNOWABLE,
            signals = emptySet(),
        )
        // Production ABSTAIN storage: blank answer body plus the terminal
        // trust marker; the presentation fallback supplies visible text.
        val stored = TrustRoutingMetadataCodec.append("", decision)
        val seeded = seed(stored)

        val rendered = TrustRoutingPresentationMapper.map(decision, "LOCAL_MODEL").assistantResponse.orEmpty()
        assertTrue(rendered.isNotBlank())
        assertEquals(rendered, seeded)

        val kind = TrustRoutingPresentationMapper.map(decision, "LOCAL_MODEL").kind
        assertEquals(TrustRoutingPresentationKind.ABSTAIN, kind)
        assertFalse(
            NextActionEligibilityPolicy.isEligible(
                routingKind = kind,
                isRetrySearchRecovery = false,
                isStudyMode = false,
            ),
        )
    }

    @Test
    fun `retry-search recovery suppresses next actions`() {
        val recovery: TrustRecoveryAction = TrustRecoveryAction.RetrySearch("request-1")
        assertTrue(recovery is TrustRecoveryAction.RetrySearch)
        assertFalse(
            NextActionEligibilityPolicy.isEligible(
                routingKind = TrustRoutingPresentationKind.NONE,
                isRetrySearchRecovery = recovery is TrustRecoveryAction.RetrySearch,
                isStudyMode = false,
            ),
        )
    }
}
