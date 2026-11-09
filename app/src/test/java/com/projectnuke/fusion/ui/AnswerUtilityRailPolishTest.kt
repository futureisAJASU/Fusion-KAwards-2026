package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.AnswerUtilityAction
import com.projectnuke.fusion.chat.AnswerUtilityRailPolicy
import com.projectnuke.fusion.data.MessageEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Next Action Flow v1B — v1A presentation-history hardening (pure pins).
 *
 * A rail chips must satisfy the Fusion 48dp interactive-component floor.
 * B sourcesRevealed is bound to the visible answer identity (message content):
 * a response-version switch changes content, so the keyed reveal state cannot
 * be inherited from the previous version. No Compose, no device.
 */
class AnswerUtilityRailPolishTest {

    @Test
    fun `rail chip target satisfies the 48dp accessibility floor`() {
        assertTrue(
            "rail chip min height must be at least the 48dp floor",
            RAIL_CHIP_MIN_HEIGHT_DP >= 48,
        )
        assertEquals(48, RAIL_CHIP_MIN_HEIGHT_DP)
    }

    private fun assistantMessage(id: Long, content: String) = MessageEntity(
        id = id,
        conversationId = 1L,
        role = "assistant",
        content = content,
        createdAt = 1L,
    )

    @Test
    fun `response-version switch changes the source-reveal identity`() {
        val v1 = assistantMessage(7L, "이전 버전 답변")
        val v2 = assistantMessage(7L, "새 버전 답변")

        // The reveal toggle is keyed by the visible answer identity; two
        // response versions of the same message must resolve to different
        // identities so the previous version's expanded/collapsed state is
        // never carried over.
        assertFalse(v1.content == v2.content)
        assertFalse(
            EvidenceAnswerIdentity.of(v1) == EvidenceAnswerIdentity.of(v2),
        )
        // Same content (same version) keeps the identity stable.
        assertEquals(
            EvidenceAnswerIdentity.of(v1),
            EvidenceAnswerIdentity.of(assistantMessage(7L, "이전 버전 답변")),
        )
    }

    @Test
    fun `source reveal is purely presentation with no key overlap between answers`() {
        assertTrue(AnswerUtilityRailPolicy.isSourcePresentationToggle(AnswerUtilityAction.SOURCES))
        val a = assistantMessage(1L, "답변 A")
        val b = assistantMessage(2L, "답변 B")
        assertFalse(EvidenceAnswerIdentity.of(a) == EvidenceAnswerIdentity.of(b))
    }
}