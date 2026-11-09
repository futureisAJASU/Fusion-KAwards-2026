package com.projectnuke.fusion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveAccessPolicyTest {
    @Test
    fun `archive page fails closed when session authorization is revoked`() {
        assertFalse(ArchiveAccessPolicy.canRenderArchive(true, false))
    }

    @Test
    fun `non archived shortcut remains allowed`() {
        assertTrue(ArchiveAccessPolicy.canAccessConversation(false, true, false))
    }

    @Test
    fun `archived shortcut is allowed without archive lock`() {
        assertTrue(ArchiveAccessPolicy.canAccessConversation(true, false, false))
    }

    @Test
    fun `locked archived shortcut and export are denied without authorization`() {
        assertFalse(ArchiveAccessPolicy.canAccessConversation(true, true, false))
        assertFalse(ArchiveAccessPolicy.canIncludeArchivedInExport(true, false))
    }

    @Test
    fun `authorized archive access remains allowed`() {
        assertTrue(ArchiveAccessPolicy.canAccessConversation(true, true, true))
        assertTrue(ArchiveAccessPolicy.canRenderArchive(true, true))
    }

    @Test
    fun `conversation-only memory remains accessible when its active conversation is outside search candidates`() {
        val oldConversationMemory = ConversationMemoryCandidate(
            id = "old-private-memory",
            text = "remember this phrase",
            conversationId = 1L,
            createdAt = 1L,
            conversationTitle = "old conversation",
            scope = MemoryScope.CONVERSATION_ONLY,
        )
        val activeConversationIds = (1L..80L).toSet()

        val accessible = ArchiveAccessPolicy.accessibleMemories(
            listOf(oldConversationMemory),
            activeConversationIds,
        )

        assertEquals(listOf(oldConversationMemory), accessible)
    }
}
