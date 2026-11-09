package com.projectnuke.fusion.chat

import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.trust.TrustRoutingPresentationKind
import com.projectnuke.fusion.ui.EvidenceAnswerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Next Action Flow v1A — answer utility rail eligibility + layer separation.
 *
 * Covers the pure decision surface only: which direct actions (사용한 자료 /
 * 근거 확인 / 이 주제로 공부) belong to a given visible answer, that the rail
 * never consumes the semantic follow-up max-3 question capacity, and that the
 * Evidence seam binds to the exact active message identity (response-version
 * safe). No Compose, no timing, no device.
 */
class AnswerUtilityRailPolicyTest {

    private fun context(
        routingKind: TrustRoutingPresentationKind = TrustRoutingPresentationKind.NONE,
        isRetrySearchRecovery: Boolean = false,
        isStudyMode: Boolean = false,
        hasWebSources: Boolean = false,
        hasLocalRagSources: Boolean = false,
        evidenceAvailable: Boolean = false,
    ) = AnswerUtilityRailContext(
        routingKind = routingKind,
        isRetrySearchRecovery = isRetrySearchRecovery,
        isStudyMode = isStudyMode,
        hasWebSources = hasWebSources,
        hasLocalRagSources = hasLocalRagSources,
        evidenceAvailable = evidenceAvailable,
    )

    private fun message(id: Long, content: String) = MessageEntity(
        id = id,
        conversationId = 1L,
        role = "assistant",
        content = content,
        createdAt = 1L,
    )

    @Test
    fun `no-source answer has no sources action`() {
        assertFalse(AnswerUtilityAction.SOURCES in AnswerUtilityRailPolicy.actions(context()))
    }

    @Test
    fun `web-source answer is eligible for sources action`() {
        val actions = AnswerUtilityRailPolicy.actions(context(hasWebSources = true))
        assertTrue(AnswerUtilityAction.SOURCES in actions)
    }

    @Test
    fun `local-rag-source answer is eligible for sources action`() {
        val actions = AnswerUtilityRailPolicy.actions(context(hasLocalRagSources = true))
        assertTrue(AnswerUtilityAction.SOURCES in actions)
    }

    @Test
    fun `normal answer is eligible for study and evidence actions`() {
        val actions = AnswerUtilityRailPolicy.actions(context(evidenceAvailable = true))
        assertTrue(AnswerUtilityAction.EVIDENCE in actions)
        assertTrue(AnswerUtilityAction.STUDY in actions)
        // Presentation order: sources (absent) -> evidence -> study.
        assertEquals(AnswerUtilityAction.EVIDENCE, actions.first())
        assertEquals(AnswerUtilityAction.STUDY, actions.last())
    }

    @Test
    fun `study answer hides the study action and suppresses the whole rail`() {
        val actions = AnswerUtilityRailPolicy.actions(
            context(isStudyMode = true, hasWebSources = true, evidenceAvailable = true),
        )
        assertFalse(AnswerUtilityAction.STUDY in actions)
        assertTrue("the rail is suppressed in STUDY like the follow-up row", actions.isEmpty())
    }

    @Test
    fun `abstain and retry-search-recovery suppress the whole rail`() {
        assertTrue(
            AnswerUtilityRailPolicy.actions(
                context(routingKind = TrustRoutingPresentationKind.ABSTAIN),
            ).isEmpty(),
        )
        assertTrue(AnswerUtilityRailPolicy.actions(context(isRetrySearchRecovery = true)).isEmpty())
    }

    @Test
    fun `utility actions never consume the follow-up max-3 capacity`() {
        // A rail with a full three direct actions...
        val railActions = AnswerUtilityRailPolicy.actions(
            context(hasWebSources = true, hasLocalRagSources = true, evidenceAvailable = true),
        )
        assertEquals(3, railActions.size)

        // ...does not shrink the question layer: four dynamic questions still
        // compose to exactly the max-3 question row.
        val dynamic = listOf("질문1?", "질문2?", "질문3?", "질문4?")
        val deterministic = NextActionSuggestionPolicy.suggestions(
            NextActionContext(
                visibleAnswer = "SRAM은 캐시에 사용됩니다.",
                webSourceCount = 0,
                localSourceCount = 0,
                localDocumentCount = 0,
                isProjectConversation = false,
                projectFileCount = 0,
            ),
        )
        val composed = DynamicFollowUpPolicy.composeRow(dynamic, deterministic)
        assertTrue(composed.size <= NextActionSuggestionPolicy.MAX_SUGGESTIONS)
        assertEquals(3, composed.size)
        // Two independent bounded layers.
        assertTrue(railActions.size + composed.size > NextActionSuggestionPolicy.MAX_SUGGESTIONS)
    }

    @Test
    fun `rail binds to exact message identity never list position`() {
        val a = message(100L, "답변 A")
        val b = message(200L, "답변 B")
        assertEquals(EvidenceAnswerIdentity.of(a), EvidenceAnswerIdentity.of(a))
        assertNotEquals(EvidenceAnswerIdentity.of(a), EvidenceAnswerIdentity.of(b))
        assertEquals(100L, EvidenceAnswerIdentity.of(a).messageId)
    }

    @Test
    fun `evidence seam targets the exact active message`() {
        val m = message(7L, "내용 7")
        val identity = EvidenceAnswerIdentity.of(m)
        assertEquals(7L, identity.messageId)
        assertEquals(identity, EvidenceAnswerIdentity.of(message(7L, "내용 7")))
    }

    @Test
    fun `response-version switch invalidates a stale evidence target`() {
        val v1 = message(300L, "이전 버전 답변")
        val v2 = message(300L, "새 버전 답변")
        val stale = EvidenceAnswerIdentity.of(v1)
        val current = EvidenceAnswerIdentity.of(v2)
        assertNotEquals("content-hash identity must change on version switch", stale, current)
        // The in-flight controller guards on state.identity == recomposed identity,
        // so the stale report cannot surface under the new version.
        assertFalse(stale == current)
    }

    @Test
    fun `sources action is a pure presentation toggle`() {
        assertTrue(AnswerUtilityRailPolicy.isSourcePresentationToggle(AnswerUtilityAction.SOURCES))
        assertFalse(AnswerUtilityRailPolicy.isSourcePresentationToggle(AnswerUtilityAction.EVIDENCE))
        assertFalse(AnswerUtilityRailPolicy.isSourcePresentationToggle(AnswerUtilityAction.STUDY))
    }
}