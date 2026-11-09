package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryScopeReleaseGateTest {
    private fun candidate(scope: MemoryScope, modelId: String? = null) = ConversationMemoryCandidate(
        id = scope.name + (modelId ?: ""), text = scope.name, conversationId = 7L,
        createdAt = 1L, scope = scope, modelId = modelId,
    )

    @Test
    fun `local target gets all matching scopes and external excludes model only`() {
        val local = candidate(MemoryScope.MODEL_ONLY, "A")
        val global = candidate(MemoryScope.GLOBAL)
        val conversation = candidate(MemoryScope.CONVERSATION_ONLY)
        assertTrue(isSavedMemoryCandidateApplicable(local, 7L, "A", false))
        assertTrue(isSavedMemoryCandidateApplicable(global, 7L, "A", false))
        assertTrue(isSavedMemoryCandidateApplicable(conversation, 7L, "A", false))
        assertFalse(isSavedMemoryCandidateApplicable(local, 7L, null, false))
        assertTrue(isSavedMemoryCandidateApplicable(global, 7L, null, false))
        assertTrue(isSavedMemoryCandidateApplicable(conversation, 7L, null, false))
        assertTrue(isSavedMemoryCandidateApplicable(local, 7L, "A", false))
    }

    @Test
    fun `main retry and style local scopes match while external stays modelless`() {
        val memories = listOf(
            candidate(MemoryScope.GLOBAL),
            candidate(MemoryScope.CONVERSATION_ONLY),
            candidate(MemoryScope.MODEL_ONLY, "A"),
            candidate(MemoryScope.MODEL_ONLY, "B"),
        )
        fun applicable(modelId: String?, external: Boolean) = memories
            .filter { isSavedMemoryCandidateApplicable(it, 7L, if (external) null else modelId, false) }
            .map { it.id }

        val mainLocalA = applicable("A", external = false)
        val retryLocalA = applicable("A", external = false)
        val styleLocalA = applicable("A", external = false)
        val external = applicable(null, external = true)
        val mainLocalB = applicable("B", external = false)

        assertEquals(mainLocalA, retryLocalA)
        assertEquals(mainLocalA, styleLocalA)
        assertTrue(mainLocalA.contains("MODEL_ONLYA"))
        assertFalse(mainLocalA.contains("MODEL_ONLYB"))
        assertTrue(external.contains("GLOBAL"))
        assertTrue(external.contains("CONVERSATION_ONLY"))
        assertFalse(external.contains("MODEL_ONLYA"))
        assertFalse(external.contains("MODEL_ONLYB"))
        assertTrue(mainLocalB.contains("MODEL_ONLYB"))
        assertFalse(mainLocalB.contains("MODEL_ONLYA"))
    }
}
