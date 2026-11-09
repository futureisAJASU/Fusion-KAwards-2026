package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectKnowledgeDigestPolicyTest {
    @Test
    fun `range is restricted to seven or thirty days`() {
        val now = 10_000_000_000L
        assertEquals(7, ProjectKnowledgeDigestPolicy.boundedRangeDays(1))
        assertEquals(30, ProjectKnowledgeDigestPolicy.boundedRangeDays(30))
        assertTrue(ProjectKnowledgeDigestPolicy.since(now, 30) < ProjectKnowledgeDigestPolicy.since(now, 7))
        assertEquals(0L, ProjectKnowledgeDigestPolicy.since(1L, 30))
    }

    @Test
    fun `sections omit empties deduplicate targets and stay bounded`() {
        val duplicateConversationEntries = listOf(
            ProjectDigestEntry("assigned:1", ProjectDigestCategory.NEW_CONTENT, "Talk", "Added", 10,
                ProjectDigestTarget.Conversation(1)),
            ProjectDigestEntry("conversation:1", ProjectDigestCategory.NEW_CONTENT, "Talk", "Updated", 11,
                ProjectDigestTarget.Conversation(1)),
        )
        val many = duplicateConversationEntries +
            (1..25).map { index ->
                ProjectDigestEntry("decision:$index", ProjectDigestCategory.DECISIONS, "Decision $index", "", index.toLong())
            }
        val sections = ProjectKnowledgeDigestPolicy.sections(many)
        assertEquals(listOf(ProjectDigestCategory.NEW_CONTENT, ProjectDigestCategory.DECISIONS), sections.map { it.category })
        assertEquals(1, sections.first().entries.size)
        assertEquals(8, sections.last().entries.size)
        assertTrue(sections.sumOf { it.entries.size } <= ProjectKnowledgeDigestPolicy.MAX_ENTRIES_TOTAL)
    }
}
