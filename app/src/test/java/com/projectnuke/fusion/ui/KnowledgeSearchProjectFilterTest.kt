package com.projectnuke.fusion.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeSearchProjectFilterTest {
    @Test
    fun `project filter includes exactly assigned conversations and their checkpoints`() = runTest {
        val memberships = mapOf(10L to 7L, 11L to 8L)
        suspend fun belongs(target: KnowledgeTarget) = knowledgeTargetBelongsToProject(
            target = target,
            projectId = 7L,
            conversationProjectId = { memberships[it] },
            savedKnowledgeProjectId = { null },
        )

        assertTrue(belongs(KnowledgeTarget.Conversation(10)))
        assertTrue(belongs(KnowledgeTarget.Conversation(10, "/managed/doc.pdf")))
        assertTrue(belongs(KnowledgeTarget.Checkpoint(1, 10, 3)))
        assertFalse(belongs(KnowledgeTarget.Conversation(11)))
        assertFalse(belongs(KnowledgeTarget.Checkpoint(2, 11, 4)))
        assertFalse(belongs(KnowledgeTarget.Conversation(12)))
    }
}
