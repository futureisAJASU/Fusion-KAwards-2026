package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.AnswerContextRoute
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.ProjectSourceScope
import com.projectnuke.fusion.chat.ProjectSourceScopeKind
import com.projectnuke.fusion.chat.ResearchRefreshPreparation
import com.projectnuke.fusion.chat.ResearchRefreshPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRefreshQueuePolicyTest {
    private val now = 1_800_000_000_000L
    private val oldTimestamp = now - 30L * DAY_MS
    private val scope = ProjectSourceScope(ProjectSourceScopeKind.AVAILABLE)
    private val validReceipt = receipt()

    private fun source(
        conversationId: Long = 1L,
        messageId: Long = 2L,
        timestamp: Long = oldTimestamp,
        answerReceipt: AnswerContextReceipt? = validReceipt,
        frozenScope: ProjectSourceScope? = scope,
        accessible: Boolean = true,
        refreshed: Boolean = false,
        question: String = "What changed?",
        answer: String = "Earlier answer",
    ) = ResearchRefreshQueueSource(
        sourceConversationId = conversationId,
        sourceMessageId = messageId,
        sourceTimestamp = timestamp,
        conversationTitle = "Research",
        question = question,
        visiblePreviousAnswer = answer,
        receipt = answerReceipt,
        frozenScope = frozenScope,
        accessible = accessible,
        hasNewerRefresh = refreshed,
    )

    @Test
    fun nonResearchInvalidReceiptAndUnrestorableScopeAreExcluded() {
        val nonResearch = validReceipt.copy(interactionMode = ChatInteractionMode.NORMAL.name)
        val invalidScopeReceipt = receipt(sourceScopeKind = ProjectSourceScopeKind.PROJECT.name)
        assertTrue(
            ResearchRefreshQueuePolicy.select(
                listOf(
                    source(answerReceipt = nonResearch),
                    source(answerReceipt = null),
                    source(answerReceipt = invalidScopeReceipt, frozenScope = null),
                ),
                now,
            ).isEmpty(),
        )
    }

    @Test
    fun ageUsesResearchTimestampAndRespectsFourteenDayThreshold() {
        val young = source(timestamp = now - 13L * DAY_MS)
        val boundary = source(timestamp = now - 14L * DAY_MS)
        val selected = ResearchRefreshQueuePolicy.select(listOf(young, boundary), now)
        assertEquals(listOf(boundary.sourceMessageId), selected.map { it.sourceMessageId })
        assertFalse(ResearchRefreshQueuePolicy.isOldEnough(young.sourceTimestamp, now))
        assertTrue(ResearchRefreshQueuePolicy.isOldEnough(boundary.sourceTimestamp, now))
        assertEquals(14L, ResearchRefreshQueuePolicy.DEFAULT_REFRESH_AGE_DAYS)
    }

    @Test
    fun inaccessibleArchiveAndSupersededSourceAreExcluded() {
        val selected = ResearchRefreshQueuePolicy.select(
            listOf(source(conversationId = 1L, accessible = false), source(conversationId = 2L, refreshed = true)),
            now,
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun queueIsBoundedAndOrderedDeterministicallyBySourceAgeAndIdentity() {
        val candidates = (1L..25L).map { id ->
            source(conversationId = id, messageId = id + 100, timestamp = oldTimestamp + id)
        }.reversed()
        val selected = ResearchRefreshQueuePolicy.select(candidates, now)
        assertEquals(ResearchRefreshQueuePolicy.MAX_CANDIDATES, selected.size)
        assertEquals((1L..16L).toList(), selected.map { it.sourceConversationId })
    }

    @Test
    fun rowPreparationUsesExactCandidateAndExistingRefreshPolicyWithoutSending() {
        val candidate = ResearchRefreshQueuePolicy.select(
            listOf(source(conversationId = 9L, messageId = 18L)),
            now,
        ).single()
        val prepared = ResearchRefreshQueuePolicy.prepare(candidate) as ResearchRefreshPreparation.Ready
        assertEquals(9L, prepared.plan.sourceConversationId)
        assertEquals(18L, prepared.plan.sourceMessageId)
        assertTrue(prepared.plan.prompt.contains("What changed?"))
        assertTrue(prepared.plan.prompt.contains("Earlier answer"))
    }

    private fun receipt(
        sourceScopeKind: String = ProjectSourceScopeKind.AVAILABLE.name,
    ) = AnswerContextReceipt.build(
        route = AnswerContextRoute.LOCAL_MODEL,
        interactionMode = ChatInteractionMode.RESEARCH.name,
        sourceScopeKind = sourceScopeKind,
    )

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
