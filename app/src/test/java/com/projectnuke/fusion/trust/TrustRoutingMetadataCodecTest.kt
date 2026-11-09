package com.projectnuke.fusion.trust

import com.projectnuke.fusion.util.visibleMessageContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustRoutingMetadataCodecTest {
    private val searchDecision = TrustRoutingDecision(
        route = TrustRoute.SEARCH,
        reason = TrustRoutingReason.CURRENT_OR_RECENT,
        signals = setOf(TrustRoutingSignal.CURRENT_INFORMATION_REQUESTED),
    )

    @Test
    fun `encode and decode round trip keeps only typed route and reason`() {
        val encoded = TrustRoutingMetadataCodec.append("answer", searchDecision)

        assertEquals(
            TrustRoutingDecision(
                route = TrustRoute.SEARCH,
                reason = TrustRoutingReason.CURRENT_OR_RECENT,
                signals = emptySet(),
            ),
            TrustRoutingMetadataCodec.decode(encoded),
        )
        assertTrue(encoded.contains("\"schemaVersion\":1"))
        assertFalse(encoded.contains("CURRENT_INFORMATION_REQUESTED"))
    }

    @Test
    fun `absent malformed oversized duplicate and future metadata are ignored`() {
        assertNull(TrustRoutingMetadataCodec.decode("plain answer"))
        assertNull(
            TrustRoutingMetadataCodec.decode(
                "answer\n\n${TrustRoutingMetadataCodec.START_MARKER}\nnot-json\n${TrustRoutingMetadataCodec.END_MARKER}",
            ),
        )
        assertNull(
            TrustRoutingMetadataCodec.decode(
                "answer\n\n${TrustRoutingMetadataCodec.START_MARKER}\n${"x".repeat(257)}\n${TrustRoutingMetadataCodec.END_MARKER}",
            ),
        )

        val encoded = TrustRoutingMetadataCodec.encode(searchDecision)!!
        assertNull(TrustRoutingMetadataCodec.decode("answer\n\n$encoded\n\n$encoded"))

        val futureSchema = """
            answer

            ${TrustRoutingMetadataCodec.START_MARKER}
            {"schemaVersion":2,"route":"SEARCH","reason":"CURRENT_OR_RECENT"}
            ${TrustRoutingMetadataCodec.END_MARKER}
        """.trimIndent()
        assertNull(TrustRoutingMetadataCodec.decode(futureSchema))
    }

    @Test
    fun `unknown route and reason are ignored safely`() {
        fun terminal(json: String) =
            "answer\n\n${TrustRoutingMetadataCodec.START_MARKER}\n$json\n${TrustRoutingMetadataCodec.END_MARKER}"

        assertNull(TrustRoutingMetadataCodec.decode(terminal("{\"schemaVersion\":1,\"route\":\"NOPE\",\"reason\":\"CURRENT_OR_RECENT\"}")))
        assertNull(TrustRoutingMetadataCodec.decode(terminal("{\"schemaVersion\":1,\"route\":\"SEARCH\",\"reason\":\"NOPE\"}")))
        assertNull(TrustRoutingMetadataCodec.decode(terminal("{\"schemaVersion\":1,\"route\":true,\"reason\":\"CURRENT_OR_RECENT\"}")))
        assertNull(TrustRoutingMetadataCodec.decode(terminal("{\"schemaVersion\":1,\"route\":\"SEARCH\",\"reason\":7}")))
    }

    @Test
    fun `visible text strips trusted terminal marker but preserves ordinary code prose`() {
        val stored = TrustRoutingMetadataCodec.append("visible answer", searchDecision)
        assertEquals("visible answer", visibleMessageContent(stored))
        assertFalse(visibleMessageContent(stored).contains("FUSION_TRUST_ROUTE_JSON"))

        val fakeCode = """
            ```text
            ${TrustRoutingMetadataCodec.START_MARKER}
            {"schemaVersion":1,"route":"SEARCH","reason":"CURRENT_OR_RECENT"}
            ${TrustRoutingMetadataCodec.END_MARKER}
            ```
        """.trimIndent()
        assertNull(TrustRoutingMetadataCodec.decode(fakeCode))
        assertEquals(fakeCode, TrustRoutingMetadataCodec.strip(fakeCode))

        val nonTerminal = "visible ${TrustRoutingMetadataCodec.START_MARKER} fake ${TrustRoutingMetadataCodec.END_MARKER} tail"
        assertNull(TrustRoutingMetadataCodec.decode(nonTerminal))
        assertEquals(nonTerminal, TrustRoutingMetadataCodec.strip(nonTerminal))
    }

    @Test
    fun `abstain marker blocks regeneration while old messages remain allowed`() {
        val abstain = TrustRoutingMetadataCodec.append(
            "정확하게 알 수 없는 미래 정보라 단정해서 답하기 어려워요.",
            TrustRoutingDecision(
                route = TrustRoute.ABSTAIN,
                reason = TrustRoutingReason.FUTURE_UNKNOWABLE,
                signals = emptySet(),
            ),
        )

        assertFalse(TrustRoutingMetadataCodec.allowsRegeneration(abstain))
        assertTrue(TrustRoutingMetadataCodec.allowsRegeneration("old answer without C2-C metadata"))
    }
}
