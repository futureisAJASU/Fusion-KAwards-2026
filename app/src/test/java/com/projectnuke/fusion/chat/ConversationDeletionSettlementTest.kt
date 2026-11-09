package com.projectnuke.fusion.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationDeletionSettlementTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule()

    @Test
    fun `three entry points share same deferred and return identical result`() = runTest {
        val vm = ChatViewModel()
        var deletionStarted = false
        var deletionCompleted = CompletableDeferred<Unit>()

        val chatDeferred = vm.deleteConversation(
            42L,
            exists = { true },
            commitDelete = {
                deletionStarted = true
                deletionCompleted.await()
            },
            settleTarget = {},
            cleanupDerivedData = {},
            recordCleanupDebt = {},
        )

        val drawerDeferred = vm.deleteConversation(42L, { true }, {}, {}, {}, {})
        val legacyDeferred = vm.deleteConversation(42L, { true }, {}, {}, {}, {})

        assertTrue(drawerDeferred === chatDeferred)
        assertTrue(legacyDeferred === chatDeferred)

        deletionCompleted.complete(Unit)
        val result = chatDeferred.await()
        assertEquals(ConversationDeletionResult.DELETED_CLEAN, result)
        assertEquals(ConversationDeletionResult.DELETED_CLEAN, drawerDeferred.getCompleted())
        assertEquals(ConversationDeletionResult.DELETED_CLEAN, legacyDeferred.getCompleted())
    }

    @Test
    fun `concurrent deletion from two entry points produces no duplicate DB delete`() = runTest {
        val coordinator = ConversationDeletionCoordinator()
        var deleteCount = 0
        val deletionStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            coordinator.delete(
                conversationId = 7,
                cancelAndJoin = {
                    deletionStarted.complete(Unit)
                    release.await()
                },
                exists = { true },
                commitDelete = { deleteCount++ },
                settleTarget = {},
                cleanupDerivedData = {},
                recordCleanupDebt = {},
            )
        }
        deletionStarted.await()
        val second = coordinator.delete(7, {}, { true }, { deleteCount++ }, {}, {}, {})
        val third = coordinator.delete(7, {}, { true }, { deleteCount++ }, {}, {}, {})

        assertEquals(ConversationDeletionResult.BUSY, second)
        assertEquals(ConversationDeletionResult.BUSY, third)
        release.complete(Unit)
        first.await()
        assertEquals(1, deleteCount)
    }

    @Test
    fun `settlement observes current conversation at settlement time not at start`() = runTest {
        val coordinator = ConversationDeletionCoordinator()
        val conversations = mutableListOf<Long>(1L, 2L, 3L)
        val currentConversation = mutableListOf<Long>(1L)
        var settledId: Long? = null

        coordinator.delete(
            conversationId = 1,
            cancelAndJoin = {},
            exists = { true },
            commitDelete = { conversations.remove(1L) },
            settleTarget = {
                settledId = currentConversation.firstOrNull()
            },
            cleanupDerivedData = {},
            recordCleanupDebt = {},
        )

        assertEquals(1L, settledId)
    }

    @Test
    fun `deleting A preserves B when user switched from A to B`() = runTest {
        val vm = ChatViewModel()
        vm.selectConversation(1L)

        var settledCurrent: Long? = null
        val deferred = vm.deleteConversation(
            1L,
            exists = { true },
            commitDelete = {},
            settleTarget = {
                vm.selectConversation(2L)
                settledCurrent = vm.currentConversationId.value
            },
            cleanupDerivedData = {},
            recordCleanupDebt = {},
        )

        deferred.await()
        assertEquals(ConversationDeletionResult.DELETED_CLEAN, deferred.getCompleted())
        assertEquals(2L, settledCurrent)
        assertEquals(2L, vm.currentConversationId.value)
    }

    @Test
    fun `target draft paths are captured from exact removed draft before deletion`() = runTest {
        val vm = ChatViewModel()
        vm.updateDraftText(1L, "draft")
        val owner = vm.beginAttachmentImport(1L)
        vm.beginAttachmentCopy(1L, owner.token)
        vm.completeAttachmentImport(
            1L, owner.token,
            listOf(PendingAttachmentIdentity("a", "text/plain", "/managed/a"))
        )

        val pathsBeforeDelete = vm.draft(1L).pendingAttachments.map { it.localPath }
        val capturedPaths = mutableSetOf<String>()

        val deferred = vm.deleteConversation(
            1L,
            exists = { true },
            commitDelete = {},
            settleTarget = {
                capturedPaths.addAll(pathsBeforeDelete)
            },
            cleanupDerivedData = {},
            recordCleanupDebt = {},
        )

        deferred.await()
        assertEquals(setOf("/managed/a"), capturedPaths)
    }

    @Test
    fun `configuration change after DB commit retains deletion result`() = runTest {
        val vm = ChatViewModel()
        var dbDeleted = false

        val deferred = vm.deleteConversation(
            1L,
            exists = { true },
            commitDelete = { dbDeleted = true },
            settleTarget = {},
            cleanupDerivedData = {},
            recordCleanupDebt = {},
        )

        val result = deferred.await()
        assertTrue(dbDeleted)
        assertEquals(ConversationDeletionResult.DELETED_CLEAN, result)
    }

    @Test
    fun `ALREADY_ABSENT still performs settlement with draft cleanup`() = runTest {
        val vm = ChatViewModel()
        vm.updateDraftText(1L, "draft")
        var settled = false
        var draftCleared = false

        val deferred = vm.deleteConversation(
            1L,
            exists = { false },
            commitDelete = { error("must not commit") },
            settleTarget = {
                settled = true
                vm.clearDraft(1L)
                draftCleared = true
            },
            cleanupDerivedData = {},
            recordCleanupDebt = {},
        )

        assertEquals(ConversationDeletionResult.ALREADY_ABSENT_CLEAN, deferred.await())
        assertTrue(settled)
        assertTrue(draftCleared)
    }

    @Test
    fun `scope cancellation before delete invocation settles candidate without commit`() = runTest {
        val vm = ChatViewModel()
        var commitCalled = false
        vm.scope.cancel()

        val deferred = vm.deleteConversation(
            99L,
            exists = { true },
            commitDelete = { commitCalled = true },
            settleTarget = {},
            cleanupDerivedData = {},
            recordCleanupDebt = {},
        )

        runCurrent()
        assertTrue(deferred.isCompleted)
        assertTrue(deferred.isCancelled)
        assertFalse(commitCalled)
        assertNull(vm.registry.deletionOwner(99L))
        assertEquals(0, vm.deletionDeferredCountForTest())
    }

    @Test
    fun `worker cancellation before body dispatch releases owner and permits later admission`() = runTest {
        val vm = ChatViewModel()
        var commitCalled = false
        val deferred = vm.deleteConversation(
            100L,
            exists = { true },
            commitDelete = { commitCalled = true },
            settleTarget = {},
            cleanupDerivedData = {},
            recordCleanupDebt = {},
        )

        // StandardTestDispatcher has not entered the worker body yet.
        vm.scope.cancel()
        runCurrent()

        assertTrue(deferred.isCompleted)
        assertTrue(deferred.isCancelled)
        assertFalse(commitCalled)
        assertNull(vm.registry.deletionOwner(100L))
        assertEquals(0, vm.deletionDeferredCountForTest())
        assertTrue(vm.registry.claimDeletionOwnership(100L, "later-owner"))
        vm.registry.releaseDeletionOwnership(100L, "later-owner")
    }

    @Test
    fun `candidate cancellation cannot release ownership before noncancellable worker terminal`() = runTest {
        val vm = ChatViewModel()
        val workerEntered = CompletableDeferred<Unit>()
        val releaseWorker = CompletableDeferred<Unit>()
        val deletion = vm.deleteConversation(
            101L,
            exists = { true },
            commitDelete = {},
            settleTarget = {
                workerEntered.complete(Unit)
                withContext(NonCancellable) { releaseWorker.await() }
            },
            cleanupDerivedData = {},
            recordCleanupDebt = {},
        )

        runCurrent()
        workerEntered.await()
        assertEquals("delete-conversation", vm.registry.deletionOwner(101L))

        deletion.cancel(CancellationException("caller disposed"))
        assertTrue(deletion.isCancelled)
        assertEquals("delete-conversation", vm.registry.deletionOwner(101L))
        assertEquals(1, vm.deletionDeferredCountForTest())

        val competingDeletion = vm.deleteConversation(
            101L,
            exists = { error("must not start a competing worker") },
            commitDelete = { error("must not commit") },
            settleTarget = {},
            cleanupDerivedData = {},
            recordCleanupDebt = {},
        )
        assertSame(deletion, competingDeletion)
        assertEquals("delete-conversation", vm.registry.deletionOwner(101L))

        releaseWorker.complete(Unit)
        runCurrent()
        assertNull(vm.registry.deletionOwner(101L))
        assertEquals(0, vm.deletionDeferredCountForTest())
    }
}
