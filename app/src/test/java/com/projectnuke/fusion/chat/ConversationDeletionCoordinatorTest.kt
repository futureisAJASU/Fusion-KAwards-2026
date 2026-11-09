package com.projectnuke.fusion.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDeletionCoordinatorTest {
    @Test
    fun `cancellation before commit leaves database and settlement untouched`() = runTest {
        val coordinator = ConversationDeletionCoordinator()
        var committed = false
        var settled = false

        runCatching {
            coordinator.delete(
                conversationId = 1,
                cancelAndJoin = { throw CancellationException("cancelled") },
                exists = { true },
                commitDelete = { committed = true },
                settleTarget = { settled = true },
                cleanupDerivedData = {},
                recordCleanupDebt = {},
            )
        }

        assertFalse(committed)
        assertFalse(settled)
    }

    @Test
    fun `all entry points share exact deletion ownership`() = runTest {
        val coordinator = ConversationDeletionCoordinator()
        val cancellationEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            coordinator.delete(
                conversationId = 7,
                cancelAndJoin = {
                    cancellationEntered.complete(Unit)
                    release.await()
                },
                exists = { true },
                commitDelete = {},
                settleTarget = {},
                cleanupDerivedData = {},
                recordCleanupDebt = {},
            )
        }
        cancellationEntered.await()

        val drawer = coordinator.delete(7, {}, { true }, {}, {}, {}, {})
        val legacy = coordinator.delete(7, {}, { true }, {}, {}, {}, {})
        val currentChat = coordinator.delete(7, {}, { true }, {}, {}, {}, {})
        assertEquals(ConversationDeletionResult.BUSY, drawer)
        assertEquals(ConversationDeletionResult.BUSY, legacy)
        assertEquals(ConversationDeletionResult.BUSY, currentChat)

        release.complete(Unit)
        assertEquals(ConversationDeletionResult.DELETED_CLEAN, first.await())
    }

    @Test
    fun `post commit cleanup failure records debt without resurrecting conversation`() = runTest {
        val coordinator = ConversationDeletionCoordinator()
        var rowExists = true
        var settled = false
        var debtRecorded = false

        val result = coordinator.delete(
            conversationId = 9,
            cancelAndJoin = {},
            exists = { rowExists },
            commitDelete = { rowExists = false },
            settleTarget = { settled = true },
            cleanupDerivedData = { error("disk full") },
            recordCleanupDebt = { debtRecorded = true },
        )

        assertEquals(ConversationDeletionResult.DELETED_CLEANUP_DEBT_RECORDED, result)
        assertFalse(rowExists)
        assertTrue(settled)
        assertTrue(debtRecorded)
    }

    @Test
    fun `deletion for A settles only A`() = runTest {
        val coordinator = ConversationDeletionCoordinator()
        val drafts = mutableSetOf(1L, 2L)

        coordinator.delete(1, {}, { true }, {}, { drafts.remove(1L) }, {}, {})

        assertFalse(1L in drafts)
        assertTrue(2L in drafts)
    }

    @Test
    fun `already absent still settles target and cleans derived data`() = runTest {
        val coordinator = ConversationDeletionCoordinator()
        var settled = false
        var cleaned = false
        val result = coordinator.delete(
            conversationId = 11,
            cancelAndJoin = {},
            exists = { false },
            commitDelete = { error("must not commit") },
            settleTarget = { settled = true },
            cleanupDerivedData = { cleaned = true },
            recordCleanupDebt = {},
        )
        assertEquals(ConversationDeletionResult.ALREADY_ABSENT_CLEAN, result)
        assertTrue(settled)
        assertTrue(cleaned)
    }

    @Test
    fun `cleanup debt failure is reported as durability lost, not swallowed`() = runTest {
        val coordinator = ConversationDeletionCoordinator()
        var committed = false
        val result = coordinator.delete(
            conversationId = 12,
            cancelAndJoin = {},
            exists = { true },
            commitDelete = { committed = true },
            settleTarget = { throw IllegalStateException("settlement failure") },
            cleanupDerivedData = { throw IllegalStateException("cleanup failure") },
            recordCleanupDebt = { throw IllegalStateException("debt store failure") },
        )
        assertTrue(committed)
        assertEquals(ConversationDeletionResult.DELETED_CLEANUP_DURABILITY_LOST, result)
    }

    @Test
    fun `boolean debt-record seam false is reported as durability lost`() = runTest {
        val coordinator = ConversationDeletionCoordinator()
        val result = coordinator.delete(
            conversationId = 13,
            cancelAndJoin = {},
            exists = { true },
            commitDelete = {},
            settleTarget = {},
            cleanupDerivedData = { error("cleanup failed") },
            recordCleanupDebt = { error("unused throwing seam") },
            recordCleanupDebtResult = { false },
        )
        assertEquals(ConversationDeletionResult.DELETED_CLEANUP_DURABILITY_LOST, result)
    }

    @Test
    fun `boolean debt-record seam true is reported as debt recorded`() = runTest {
        val coordinator = ConversationDeletionCoordinator()
        val result = coordinator.delete(
            conversationId = 14,
            cancelAndJoin = {},
            exists = { true },
            commitDelete = {},
            settleTarget = {},
            cleanupDerivedData = { error("cleanup failed") },
            recordCleanupDebt = { error("unused throwing seam") },
            recordCleanupDebtResult = { true },
        )
        assertEquals(ConversationDeletionResult.DELETED_CLEANUP_DEBT_RECORDED, result)
    }
}
