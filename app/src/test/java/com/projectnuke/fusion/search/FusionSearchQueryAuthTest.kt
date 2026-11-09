package com.projectnuke.fusion.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 6: immutable per-search authorization contexts. No global mutable
// approved set exists; each search carries its own context.
class FusionSearchQueryAuthTest {

    @Test
    fun `allows only exact approved queries`() {
        val auth = SearchQueryAuthorization("tok-1", setOf("query one", "query two"))
        assertTrue(auth.allows("query one"))
        assertTrue(auth.allows("query two"))
        assertFalse(auth.allows("query"))
        assertFalse(auth.allows("query one "))
        assertFalse(auth.allows(""))
        assertFalse(auth.allows("QUERY ONE"))
    }

    @Test
    fun `requireApproved throws for foreign queries`() {
        val auth = SearchQueryAuthorization("tok-1", setOf("query one"))
        auth.requireApproved("query one")
        var refused = false
        try {
            auth.requireApproved("private excerpt material")
        } catch (_: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused)
    }

    @Test
    fun `contexts never share approval`() {
        val authA = SearchQueryAuthorization("tok-a", setOf("alpha"))
        val authB = SearchQueryAuthorization("tok-b", setOf("beta"))
        assertTrue(authA.allows("alpha"))
        assertFalse(authA.allows("beta"))
        assertTrue(authB.allows("beta"))
        assertFalse(authB.allows("alpha"))
    }

    @Test
    fun `empty approval approves nothing`() {
        val auth = SearchQueryAuthorization("tok-1", emptySet())
        assertFalse(auth.allows("anything"))
    }
}
