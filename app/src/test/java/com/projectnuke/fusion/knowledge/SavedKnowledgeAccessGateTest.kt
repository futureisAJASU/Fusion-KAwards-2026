package com.projectnuke.fusion.knowledge

import com.projectnuke.fusion.chat.SelectionSourceIdentity
import com.projectnuke.fusion.chat.SelectionSourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeGateLookups(
    var conversationArchived: Map<Long, Boolean> = emptyMap(),
    var saved: Map<String, SourceAccessLookups.SavedKnowledgeState> = emptyMap(),
    var noteStates: Map<String, List<Boolean>> = emptyMap(),
    var throwCancellation: Boolean = false,
    var throwFatal: Error? = null,
) : SourceAccessLookups {
    private suspend fun gate(): Unit {
        throwFatal?.let { throw it }
        if (throwCancellation) throw CancellationException("cancel")
    }
    override suspend fun conversation(conversationId: Long): SourceAccessLookups.ConversationState? {
        gate()
        return conversationArchived[conversationId]?.let { SourceAccessLookups.ConversationState(conversationId, it) }
    }
    override suspend fun attachmentReferenced(conversationId: Long, managedPath: String): Boolean { gate(); return true }
    override suspend fun managedAttachmentResolvable(managedPath: String): Boolean { gate(); return true }
    override suspend fun managedAttachmentExists(managedPath: String): Boolean { gate(); return true }
    override suspend fun projectExists(projectId: Long): Boolean { gate(); return true }
    override suspend fun projectFileResolvable(projectId: Long, fileId: Long): Boolean { gate(); return true }
    override suspend fun projectFileOwned(projectId: Long, fileId: Long): Boolean { gate(); return true }
    override suspend fun memory(memoryId: String): SourceAccessLookups.MemoryState? { gate(); return null }
    override suspend fun memoryRecorded(memoryId: String): Boolean { gate(); return false }
    override suspend fun savedKnowledge(savedKnowledgeId: String): SourceAccessLookups.SavedKnowledgeState? {
        gate()
        return saved[savedKnowledgeId]
    }
    override suspend fun savedKnowledgeExists(savedKnowledgeId: String): Boolean { gate(); return saved.containsKey(savedKnowledgeId) }
    override suspend fun noteSourceArchivedStates(noteId: String): List<Boolean> {
        gate()
        return noteStates[noteId].orEmpty()
    }
}

/** Phase 1 regressions: derived-knowledge archive leaks (C01-C04, C12, H01). */
class SavedKnowledgeAccessGateTest {
    @Test
    fun `locked archive titles are excluded from bounded semantic profile inputs`() = runTest {
        val orderedTitles = listOf("Confidential archived title" to true, "Visible project title" to false)
        suspend fun selectedTitles(archiveAllowed: Boolean): List<String> =
            collectAccessibleKnowledgePages(
                pageSize = 2,
                requiredCount = 2,
                maxRowsScanned = 4,
                loadPage = { limit, offset -> orderedTitles.drop(offset).take(limit) },
                accessible = { (_, archived) ->
                    SavedKnowledgeAccessGate.isVisible(archiveAllowed, archived, emptyList())
                },
            ).map { (title, _) -> title }

        assertEquals(listOf("Visible project title"), selectedTitles(archiveAllowed = false))
        assertEquals(listOf("Confidential archived title", "Visible project title"), selectedTitles(archiveAllowed = true))
    }

    @Test
    fun `transaction snapshot prevents concurrent insert from shifting OFFSET pages`() = runTest {
        val initial = listOf(1, 2, 3, 4)
        val snapshot = initial.toList()
        val live = initial.toMutableList()
        var inserted = false

        val stableResult = collectAccessibleKnowledgePages(
            pageSize = 2,
            requiredCount = 4,
            maxRowsScanned = 8,
            loadPage = { limit, offset ->
                snapshot.drop(offset).take(limit).also {
                    if (!inserted) {
                        inserted = true
                        live.add(0, 0)
                    }
                }
            },
            accessible = { true },
        )
        assertEquals(initial, stableResult)

        live.clear()
        live.addAll(initial)
        inserted = false
        val shiftedResult = collectAccessibleKnowledgePages(
            pageSize = 2,
            requiredCount = 4,
            maxRowsScanned = 8,
            loadPage = { limit, offset ->
                live.drop(offset).take(limit).also {
                    if (!inserted) {
                        inserted = true
                        live.add(0, 0)
                    }
                }
            },
            accessible = { true },
        )
        assertEquals(listOf(1, 2, 2, 3), shiftedResult)
    }

    private fun authority(lookups: FakeGateLookups, allowed: Boolean) =
        SourceAccessAuthority(archiveAccessIncluded = { allowed }, lookups = lookups)

    private fun identity(id: String) =
        SelectionSourceIdentity(SelectionSourceKind.SAVED_KNOWLEDGE, savedKnowledgeId = id)

    @Test fun accessibleDerivedKnowledgeVisibleWhileUnlocked() = runBlocking {
        val lookups = FakeGateLookups(
            conversationArchived = mapOf(1L to false),
            saved = mapOf("k1" to SourceAccessLookups.SavedKnowledgeState(1L, "ASSISTANT_COPY")),
        )
        assertTrue(authority(lookups, allowed = false).isAccessible(identity("k1")))
    }

    @Test fun relockImmediatelyDeniesPreviewAndBody() = runBlocking {
        val lookups = FakeGateLookups(
            conversationArchived = mapOf(1L to true),
            saved = mapOf("k1" to SourceAccessLookups.SavedKnowledgeState(1L, "ASSISTANT_COPY")),
        )
        assertFalse(authority(lookups, allowed = false).isAccessible(identity("k1")))
        assertTrue(authority(lookups, allowed = true).isAccessible(identity("k1")))
    }

    @Test fun userNoteLinkedSourceDeniedWhenLocked() = runBlocking {
        val lookups = FakeGateLookups(
            saved = mapOf("n1" to SourceAccessLookups.SavedKnowledgeState(null, "USER_NOTE")),
            noteStates = mapOf("n1" to listOf(false, true)),
        )
        assertFalse(authority(lookups, allowed = false).isAccessible(identity("n1")))
        assertTrue(authority(lookups, allowed = true).isAccessible(identity("n1")))
    }

    @Test fun gatePureVisibilityMatchesCanonicalPolicy() {
        assertTrue(SavedKnowledgeAccessGate.isVisible(false, false, emptyList()))
        assertEquals(false, SavedKnowledgeAccessGate.isVisible(false, true, emptyList()))
        assertEquals(false, SavedKnowledgeAccessGate.isVisible(false, false, listOf(false, true)))
        assertTrue(SavedKnowledgeAccessGate.isVisible(true, true, listOf(true)))
    }

    @Test fun cancellationPropagatesThroughAccessCheck() = runBlocking {
        val lookups = FakeGateLookups(throwCancellation = true)
        try {
            authority(lookups, allowed = false).isAccessible(identity("k1"))
            fail("expected CancellationException")
        } catch (cancelled: CancellationException) {
            // Propagated, never mapped to not-accessible.
        }
    }

    @Test fun fatalErrorPropagatesThroughAccessCheck() = runBlocking {
        val fatal = OutOfMemoryError("boom")
        val lookups = FakeGateLookups(throwFatal = fatal)
        try {
            authority(lookups, allowed = false).isAccessible(identity("k1"))
            fail("expected fatal Error")
        } catch (error: OutOfMemoryError) {
            assertTrue(error === fatal)
        }
    }

    @Test fun reviewCardDerivationDeniedAfterRelock() = runBlocking {
        // Same authority backs QuickReviewCardRepository SAVED_KNOWLEDGE branch:
        // newly inaccessible stored knowledge must yield AccessUnavailable.
        val lookups = FakeGateLookups(
            conversationArchived = mapOf(9L to true),
            saved = mapOf("k9" to SourceAccessLookups.SavedKnowledgeState(9L, "ASSISTANT_COPY")),
        )
        assertFalse(authority(lookups, allowed = false).isAccessible(identity("k9")))
    }
}
