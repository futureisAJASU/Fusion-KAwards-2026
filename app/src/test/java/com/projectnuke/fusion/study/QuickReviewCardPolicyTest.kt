package com.projectnuke.fusion.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickReviewCardPolicyTest {
    @Test
    fun `prepares bounded personal and project cards with frozen provenance`() {
        val personal = QuickReviewCardPolicy.prepare(
            QuickReviewCardDraft(
                front = "  Question  ", back = "  Answer  ",
                sourceKind = ReviewCardOrigin.ASSISTANT_ANSWER,
                sourceConversationId = 10, sourceMessageId = 11,
            ),
            "Question", "Answer", 100, "card-1",
        ) as QuickReviewCardPreparation.Ready
        assertEquals(ReviewCardScope.PERSONAL, personal.proposal.scope)
        assertEquals(null, personal.proposal.projectId)
        assertEquals(10L, personal.proposal.sourceConversationId)
        assertEquals(11L, personal.proposal.sourceMessageId)

        val project = QuickReviewCardPolicy.prepare(
            QuickReviewCardDraft("Q", "A", ReviewCardScope.PROJECT, 9, ReviewCardOrigin.USER_TEXT),
            "Q", "A", 100, "card-2",
        ) as QuickReviewCardPreparation.Ready
        assertEquals(9L, project.proposal.projectId)
    }

    @Test
    fun `rejects incomplete sensitive overlong and malformed provenance`() {
        val valid = QuickReviewCardDraft("Q", "A")
        assertEquals(QuickReviewCardPreparation.Invalid, QuickReviewCardPolicy.prepare(valid, "", "A", 1))
        assertEquals(QuickReviewCardPreparation.Invalid,
            QuickReviewCardPolicy.prepare(valid, "Q".repeat(QuickReviewCardPolicy.MAX_FRONT_CHARS + 1), "A", 1))
        assertEquals(QuickReviewCardPreparation.Invalid,
            QuickReviewCardPolicy.prepare(valid, "Q", "A".repeat(QuickReviewCardPolicy.MAX_BACK_CHARS + 1), 1))
        assertEquals(QuickReviewCardPreparation.Invalid,
            QuickReviewCardPolicy.prepare(valid.copy(scope = ReviewCardScope.PROJECT), "Q", "A", 1))
        assertEquals(QuickReviewCardPreparation.Invalid,
            QuickReviewCardPolicy.prepare(valid.copy(sourceKind = ReviewCardOrigin.STUDY_TOPIC), "Q", "A", 1))
        assertEquals(QuickReviewCardPreparation.SensitiveContent,
            QuickReviewCardPolicy.prepare(valid, "Q", "api_key=abcdefghijklmnopqrstuvwxyz", 1))
    }

    @Test
    fun `rating uses a simple deterministic revisit interval`() {
        val card = QuickReviewCardPolicy.toEntity(
            (QuickReviewCardPolicy.prepare(QuickReviewCardDraft("Q", "A"), "Q", "A", 1, "id")
                as QuickReviewCardPreparation.Ready).proposal,
        )
        val again = QuickReviewCardPolicy.rate(card, ReviewCardRating.AGAIN, 10) as ReviewCardDecision.Updated
        val hard = QuickReviewCardPolicy.rate(card, ReviewCardRating.HARD, 10) as ReviewCardDecision.Updated
        val understood = QuickReviewCardPolicy.rate(card, ReviewCardRating.UNDERSTOOD, 10) as ReviewCardDecision.Updated
        assertEquals(10 + QuickReviewCardPolicy.AGAIN_INTERVAL_MILLIS, again.card.dueAt)
        assertEquals(10 + QuickReviewCardPolicy.HARD_INTERVAL_MILLIS, hard.card.dueAt)
        assertEquals(10 + QuickReviewCardPolicy.UNDERSTOOD_INTERVAL_MILLIS, understood.card.dueAt)
        assertEquals(1, understood.card.reviewCount)
        assertEquals("UNDERSTOOD", understood.card.lastRating)
        assertTrue(QuickReviewCardPolicy.rate(card, ReviewCardRating.AGAIN, Long.MAX_VALUE) is ReviewCardDecision.InvalidTime)
    }
}
