package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ConversationEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ArchiveSurfaceAccessTest {
    private val active = ConversationEntity(1, "public title", 1, 1)
    private val archived = ConversationEntity(2, "secret title", 1, 1, isArchived = true)
    private val rows = listOf(active, archived)

    @Test fun `project enumeration and summary inputs exclude locked archive content`() {
        val visible = ArchiveAccessPolicy.accessibleConversations(rows, true, false)
        assertEquals(listOf(active), visible)
        assertEquals(listOf(1L), ArchiveAccessPolicy.accessibleSummaries(
            listOf(ConversationSummaryMemory(1, "public", 1), ConversationSummaryMemory(2, "secret", 1)),
            visible.map { it.id }.toSet(),
        ).map { it.conversationId })
    }

    @Test fun `unlocked archive and authenticated session retain normal access`() {
        assertEquals(rows, ArchiveAccessPolicy.accessibleConversations(rows, false, false))
        assertEquals(rows, ArchiveAccessPolicy.accessibleConversations(rows, true, true))
    }

    @Test fun `explicitly shared memories retain text without leaking locked source title`() {
        val memory = ConversationMemoryCandidate("m", "explicitly shared", 2, 1, conversationTitle = "secret title")
        assertEquals(listOf(memory.copy(conversationTitle = null)),
            ArchiveAccessPolicy.accessibleMemories(listOf(memory), setOf(1)))
        assertTrue(ArchiveAccessPolicy.accessibleMemories(listOf(memory.copy(scope = MemoryScope.CONVERSATION_ONLY)), setOf(1)).isEmpty())
        assertEquals(listOf(memory), ArchiveAccessPolicy.accessibleMemories(listOf(memory), setOf(1, 2)))
    }

    @Test fun `durable opening rejects stale rows and permits authenticated archive`() = runTest {
        assertFalse(archiveConversationAdmission({ archived }, { true }, false))
        assertTrue(archiveConversationAdmission({ active }, { true }, false))
        assertTrue(archiveConversationAdmission({ archived }, { true }, true))
        assertTrue(archiveConversationAdmission({ archived }, { false }, false))
        assertFalse(archiveConversationAdmission({ null }, { false }, true))
        assertFalse(archiveConversationAdmission({ throw IllegalStateException() }, { false }, true))
    }

    @Test fun `opening preserves cancellation and fatal identity`() = runTest {
        val cancellation = CancellationException("cancel")
        try { archiveConversationAdmission({ throw cancellation }, { true }, true); fail() }
        catch (actual: CancellationException) { assertSame(cancellation, actual) }
        val fatal = LinkageError("fatal")
        try { archiveConversationAdmission({ throw fatal }, { true }, true); fail() }
        catch (actual: LinkageError) { assertSame(fatal, actual) }
    }
}
