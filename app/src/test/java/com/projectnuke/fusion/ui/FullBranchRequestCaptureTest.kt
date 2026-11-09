package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.chat.AutoCompactMemory
import com.projectnuke.fusion.chat.AutoCompactionBranchIdentity
import com.projectnuke.fusion.chat.AutoCompactionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullBranchRequestCaptureTest {
    @Test
    fun `active branch is resolved from full history when visible window starts later`() {
        val full = (1L..200L).map { id ->
            MessageEntity(id, 1L, if (id % 2L == 1L) "user" else "assistant", "m$id", id)
        }
        val visible = full.takeLast(100)
        val active = activeTimelineMessages(full, ResponseVersionState())
        assertEquals(200, active.size)
        assertEquals(101L, visible.first().id)
        assertEquals(1L, active.first().id)
        assertTrue(active.take(120).map { it.id }.containsAll((1L..120L).toList()))
    }

    @Test
    fun `retry and style preserve canonical full branch compact identity`() {
        val ids = (1L..200L).toList()
        val visibleIds = ids.takeLast(100)
        val existing = AutoCompactMemory(
            conversationId = 1L,
            summary = "old",
            compactedThroughMessageId = 120L,
            updatedAt = 1L,
            sourceTurnCount = 60,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(ids, 120L),
        )

        assertTrue(AutoCompactionEngine.isValidMemoryForActiveBranch(existing, 1L, ids, 25))
        assertTrue(AutoCompactionEngine.isValidMemoryForActiveBranch(existing, 1L, ids.take(190), 25))
        assertTrue(!AutoCompactionEngine.isValidMemoryForActiveBranch(existing, 1L, visibleIds, 25))

        val retryIncremental = existing.copy(
            summary = "retry incremental",
            compactedThroughMessageId = 140L,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(ids.take(190), 140L),
        )
        val styleIncremental = existing.copy(
            summary = "style incremental",
            compactedThroughMessageId = 160L,
            branchFingerprint = AutoCompactionBranchIdentity.fromMessageIds(ids.take(190), 160L),
        )
        assertTrue(AutoCompactionEngine.isValidMemoryForActiveBranch(retryIncremental, 1L, ids, 25))
        assertTrue(AutoCompactionEngine.isValidMemoryForActiveBranch(styleIncremental, 1L, ids, 25))
        assertTrue(AutoCompactionEngine.isValidMemoryForActiveBranch(retryIncremental, 1L, ids.take(190), 25))
        assertTrue(AutoCompactionEngine.isValidMemoryForActiveBranch(styleIncremental, 1L, ids.take(190), 25))
    }
}
