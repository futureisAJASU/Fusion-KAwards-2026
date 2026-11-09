package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelatedKnowledgePolicyTest {
    @Test
    fun `query uses a compact title and enforces the search bound`() {
        val query = relatedKnowledgeQuery(
            RelatedKnowledgeSeed("  HBM thermal design  ", "detail ".repeat(80)),
        )

        assertTrue(query.startsWith("HBM thermal design "))
        assertTrue(relatedKnowledgeQuery(RelatedKnowledgeSeed("", "  a  b\n c ")).length <= RELATED_KNOWLEDGE_QUERY_MAX_CHARS)
        assertTrue(relatedKnowledgeQuery(RelatedKnowledgeSeed("", " ")).isEmpty())
    }

    @Test
    fun `results remove source and duplicates and cap visible items`() {
        val source = KnowledgeTarget.SavedKnowledge("source")
        fun item(id: String) = KnowledgeCandidate(
            key = id,
            kind = KnowledgeKind.SAVED_KNOWLEDGE,
            title = id,
            preview = "",
            metadata = "저장 지식",
            target = KnowledgeTarget.SavedKnowledge(id),
        )

        val result = selectRelatedKnowledge(
            source,
            listOf(item("source"), item("one"), item("one"), item("two"), item("three"), item("four"), item("five")),
            limit = 9,
        )

        assertEquals(listOf("one", "two", "three", "four"), result.map { it.key })
    }
}
