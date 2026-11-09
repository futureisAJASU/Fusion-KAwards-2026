package com.projectnuke.fusion.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnowledgeOrganizationPolicyTest {
    @Test
    fun `tag identity collapses unicode whitespace and case while preserving first display`() {
        val labels = KnowledgeOrganizationPolicy.normalizeTagSet(listOf(" HBM ", "hbm", "학교   자료"))!!
        assertEquals(listOf("hbm", "학교 자료"), labels.map { it.key })
        assertEquals(listOf("HBM", "학교 자료"), labels.map { it.displayName })
    }

    @Test
    fun `tag and collection bounds reject empty controls overlength and excessive counts`() {
        assertNull(KnowledgeOrganizationPolicy.normalizeTag("   "))
        assertNull(KnowledgeOrganizationPolicy.normalizeTag("bad\u0000name"))
        assertNull(KnowledgeOrganizationPolicy.normalizeTag("x".repeat(KnowledgeOrganizationPolicy.MAX_TAG_CHARS + 1)))
        assertNull(KnowledgeOrganizationPolicy.normalizeCollection("x".repeat(KnowledgeOrganizationPolicy.MAX_COLLECTION_CHARS + 1)))
        assertNull(KnowledgeOrganizationPolicy.normalizeTagSet(List(KnowledgeOrganizationPolicy.MAX_TAGS_PER_ITEM + 1) { "tag$it" }))
    }

    @Test
    fun `collection aliases share one stable normalized key`() {
        val labels = KnowledgeOrganizationPolicy.normalizeCollectionSet(listOf("반도체 논문", "  반도체   논문 "))!!
        assertEquals(1, labels.size)
        assertEquals("반도체 논문", labels.single().key)
    }
}
