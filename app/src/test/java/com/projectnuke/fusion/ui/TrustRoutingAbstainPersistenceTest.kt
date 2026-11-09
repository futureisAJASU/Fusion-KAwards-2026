package com.projectnuke.fusion.ui

import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingMetadataCodec
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustRoutingAbstainPersistenceTest {
    private data class StoredMessage(val role: String, val content: String)

    @Test
    fun `abstain persists a visible durable response without model or provider calls`() = runBlocking {
        val messages = mutableListOf<StoredMessage>()
        messages += StoredMessage("user", "다음 로또 번호 알려줘")
        val frozenDecision = TrustRoutingDecision(
            route = TrustRoute.ABSTAIN,
            reason = TrustRoutingReason.FUTURE_UNKNOWABLE,
            signals = emptySet(),
        )
        var modelCalls = 0
        var externalProviderCalls = 0
        var timestampUpdates = 0

        val assistantId = persistDeterministicTrustRoutingResponse(
            decision = frozenDecision,
            generationModeKey = "LOCAL_MODEL",
            insertMessage = { content ->
                messages += StoredMessage("assistant", content)
                messages.size.toLong()
            },
            updateConversationTimestamp = { timestampUpdates++ },
        )

        assertNotNull(assistantId)
        assertEquals(listOf("user", "assistant"), messages.map { it.role })
        assertEquals(1, timestampUpdates)
        assertEquals(0, modelCalls)
        assertEquals(0, externalProviderCalls)

        val reread = messages.last().content
        assertEquals(frozenDecision.copy(signals = emptySet()), TrustRoutingMetadataCodec.decode(reread))
        assertEquals(
            "정확하게 알 수 없는 미래 정보라 단정해서 답하기 어려워요.\n\n" +
                "예보, 공개 일정, 확률처럼 확인 가능한 정보라면 검색해서 도와드릴 수 있어요.",
            visibleMessageContentPreservingWhitespace(reread),
        )
        assertFalse(visibleMessageContentPreservingWhitespace(reread).contains("FUSION_TRUST_ROUTE_JSON"))
        assertTrue(reread.contains(TrustRoutingMetadataCodec.START_MARKER))
    }
}
