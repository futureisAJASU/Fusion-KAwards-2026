package com.projectnuke.fusion.validation

import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.chat.AnswerContextRoute
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.data.ResearchRefreshCandidate
import com.projectnuke.fusion.data.ResearchRefreshDao
import com.projectnuke.fusion.data.ResearchRefreshRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractPhase3Test {

    private fun researchContent(label: String): String {
        val receipt = AnswerContextReceipt.build(
            route = AnswerContextRoute.LOCAL_MODEL,
            interactionMode = ChatInteractionMode.RESEARCH.name,
        )
        return AnswerContextReceipt.append("$label answer", receipt)
    }

    private fun normalContent(label: String): String {
        val receipt = AnswerContextReceipt.build(
            route = AnswerContextRoute.LOCAL_MODEL,
            interactionMode = ChatInteractionMode.NORMAL.name,
        )
        return AnswerContextReceipt.append("$label answer", receipt)
    }

    /** Newest-first full window; honors LIMIT like the DAO. */
    private class FakeDao(val window: List<ResearchRefreshCandidate>) : ResearchRefreshDao {
        val fetchedLimits = mutableListOf<Int>()
        override suspend fun sourceAnswer(
            sourceConversationId: Long,
            sourceMessageId: Long,
            includeArchived: Boolean,
        ): String? = null

        override suspend fun targetsFrom(
            sourceConversationId: Long,
            sourceMessageId: Long,
            includeArchived: Boolean,
            limit: Int,
        ): List<ResearchRefreshCandidate> {
            fetchedLimits += limit
            return window.take(limit)
        }
    }

    private fun candidate(conv: Long, content: String) =
        ResearchRefreshCandidate(conv, "T$conv", conv, content)

    @Test
    fun h23_normalMessagesDoNotStarveOlderResearch() = runTest {
        val dao = FakeDao(
            listOf(
                candidate(1, normalContent("n1")),
                candidate(1, normalContent("n2")),
                candidate(1, normalContent("n3")),
                candidate(2, researchContent("r-old")),
            ),
        )
        val out = ResearchRefreshRepository(dao).targetsFrom(7L, 8L, false)
        assertEquals(listOf(2L), out.map { it.id })
        assertTrue("fetches stay bounded", (dao.fetchedLimits.maxOrNull() ?: 0) <= 256)
    }

    @Test
    fun h23_sameConversationDuplicatesDoNotStarveDistinctRelationship() = runTest {
        val dao = FakeDao(
            listOf(
                candidate(1, researchContent("r1a")),
                candidate(1, researchContent("r1b")),
                candidate(1, researchContent("r1c")),
                candidate(2, researchContent("r2")),
            ),
        )
        val out = ResearchRefreshRepository(dao).targetsFrom(7L, 8L, false)
        assertEquals(listOf(1L, 2L), out.map { it.id })
    }

    @Test
    fun h23_outputBoundedToNewestEligibleDistinct() = runTest {
        val dao = FakeDao((1L..10L).map { candidate(it, researchContent("r$it")) })
        val out = ResearchRefreshRepository(dao).targetsFrom(7L, 8L, false)
        assertEquals(3, out.size)
        // Window is newest-first in this fixture's id order; assert bound only.
        assertEquals(3, out.map { it.id }.toSet().size)
    }

    @Test
    fun h23_malformedReceiptsDoNotConsumeSlots() = runTest {
        val dao = FakeDao(
            listOf(
                candidate(1, "not a receipt"),
                candidate(2, ""),
                candidate(3, researchContent("r")),
            ),
        )
        val out = ResearchRefreshRepository(dao).targetsFrom(7L, 8L, false)
        assertEquals(listOf(3L), out.map { it.id })
    }

    @Test
    fun h23_orderingDeterministic() = runTest {
        val window = listOf(
            candidate(5, researchContent("a")),
            candidate(3, normalContent("n")),
            candidate(5, researchContent("b")),
            candidate(4, researchContent("c")),
        )
        val first = ResearchRefreshRepository(FakeDao(window)).targetsFrom(7L, 8L, false)
        val second = ResearchRefreshRepository(FakeDao(window)).targetsFrom(7L, 8L, false)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(listOf(5L, 4L), first.map { it.id })
    }
}
