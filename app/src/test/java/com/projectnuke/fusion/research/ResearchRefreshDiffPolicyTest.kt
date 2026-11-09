package com.projectnuke.fusion.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRefreshDiffPolicyTest {
    private fun compare(old: String, new: String, oldSources: List<ResearchRefreshDiffPolicy.SourceReference> = emptyList(),
                       newSources: List<ResearchRefreshDiffPolicy.SourceReference> = emptyList()) =
        ResearchRefreshDiffPolicy.compare(old, new, oldSources, newSources)

    @Test fun `only durable exact refresh and branch identities establish a pair`() {
        assertTrue(ResearchRefreshDiffPolicy.hasExactRefreshRelationship(1, 2, 3, 1, 2, 1, 2))
        assertFalse(ResearchRefreshDiffPolicy.hasExactRefreshRelationship(1, 2, 3, 8, 9, 8, 9))
        assertFalse(ResearchRefreshDiffPolicy.hasExactRefreshRelationship(1, 2, 3, 1, 2, 1, 99))
        assertFalse(ResearchRefreshDiffPolicy.hasExactRefreshRelationship(0, 2, 3, 0, 2, 0, 2))
    }

    @Test fun `refresh answer requires first post creation user turn and active Research assistant`() {
        val unrelated = listOf(
            ResearchRefreshDiffPolicy.OwnedMessage(1, "user", 8, true, false),
            ResearchRefreshDiffPolicy.OwnedMessage(2, "assistant", 9, true, true),
        )
        assertEquals(null, ResearchRefreshDiffPolicy.resolveRefreshedAssistant(unrelated, 10))
        val refresh = unrelated + listOf(
            ResearchRefreshDiffPolicy.OwnedMessage(3, "user", 11, true, false),
            ResearchRefreshDiffPolicy.OwnedMessage(4, "assistant", 12, false, true),
            ResearchRefreshDiffPolicy.OwnedMessage(5, "assistant", 13, true, true),
        )
        assertEquals(5L, ResearchRefreshDiffPolicy.resolveRefreshedAssistant(refresh, 10))
    }

    @Test fun `arbitrary messages and non Research result cannot establish refresh ownership`() {
        val arbitrary = listOf(
            ResearchRefreshDiffPolicy.OwnedMessage(2, "user", 11, true, false),
            ResearchRefreshDiffPolicy.OwnedMessage(3, "assistant", 12, true, false),
        )
        assertEquals(null, ResearchRefreshDiffPolicy.resolveRefreshedAssistant(arbitrary, 10))
        assertEquals(null, ResearchRefreshDiffPolicy.resolveRefreshedAssistant(arbitrary.map { it.copy(research = true, active = false) }, 10))
    }

    @Test fun `new and missing content sections are bounded and unchanged lines stay out`() {
        val result = compare(
            "고정된 안내 문장입니다.\n\n이전 조사에서만 확인된 항목입니다.",
            "고정된 안내 문장입니다.\n\n이번 조사에서 새로 확인된 항목입니다.",
        )
        assertTrue(result.newlyMentioned.any { "새로 확인" in it })
        assertTrue(result.notFoundPrevious.any { "이전 조사에서만" in it })
        assertFalse(result.newlyMentioned.any { "고정된 안내" in it })
        assertFalse(result.notFoundPrevious.any { "고정된 안내" in it })
        assertTrue(result.newlyMentioned.size <= ResearchRefreshDiffPolicy.MAX_DIFF_ITEMS_PER_SECTION)
        assertTrue(result.notFoundPrevious.size <= ResearchRefreshDiffPolicy.MAX_DIFF_ITEMS_PER_SECTION)
    }

    @Test fun `numeric change is a candidate and equal number is not`() {
        val changed = compare("기기에는 12 GB 메모리가 포함됩니다.", "기기에는 16 GB 메모리가 포함됩니다.")
        assertEquals(1, changed.numericCandidates.size)
        assertTrue(changed.numericCandidates.single().previous.contains("12 GB"))
        assertTrue(changed.numericCandidates.single().current.contains("16 GB"))
        val same = compare("기기에는 12 GB 메모리가 포함됩니다.", "기기에는 12 GB 메모리가 포함됩니다.")
        assertTrue(same.numericCandidates.isEmpty())
        assertTrue(same.newlyMentioned.isEmpty())
        assertTrue(same.notFoundPrevious.isEmpty())
    }

    @Test fun `source identity changes are neutral and bounded`() {
        val old = listOf(
            ResearchRefreshDiffPolicy.SourceReference("https://a.example", "이전 출처"),
            ResearchRefreshDiffPolicy.SourceReference("https://b.example", "계속된 출처"),
        )
        val current = listOf(
            ResearchRefreshDiffPolicy.SourceReference("https://b.example", "계속된 출처"),
            ResearchRefreshDiffPolicy.SourceReference("https://c.example", "새 출처"),
        )
        val result = compare("같은 내용", "같은 내용", old, current).sources
        assertEquals(listOf("새 출처"), result.added)
        assertEquals(listOf("이전 출처"), result.notSeenThisTime)
        assertEquals(listOf("계속된 출처"), result.continued)
        assertFalse(result.notSeenThisTime.any { "무효" in it || "잘못" in it })
    }

    @Test fun `inputs and outputs remain bounded`() {
        val result = compare("이전 항목.\n".repeat(10_000), "현재 항목.\n".repeat(10_000))
        assertTrue(result.newlyMentioned.size <= 8)
        assertTrue(result.notFoundPrevious.size <= 8)
        assertTrue(result.newlyMentioned.all { it.length <= 500 })
        assertTrue(result.notFoundPrevious.all { it.length <= 500 })
    }
}
