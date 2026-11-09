package com.projectnuke.fusion.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import com.projectnuke.fusion.chat.ConversationStructuralMutationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseVersionMutationCoordinatorTest {
    @Test
    fun `same conversation mutations observe and merge durable state in order`() = runTest {
        var state = ResponseVersionState()
        val firstLoaded = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            ResponseVersionMutationCoordinator.withMutation(901L) {
                val previous = state
                firstLoaded.complete(Unit)
                releaseFirst.await()
                state = previous.copy(
                    groupByMessageId = previous.groupByMessageId + (101L to 11L),
                    activeMessageIdByGroup = previous.activeMessageIdByGroup + (11L to 101L),
                )
            }
        }
        firstLoaded.await()

        val second = async {
            ResponseVersionMutationCoordinator.withMutation(901L) {
                // This read is inside the same per-conversation mutation
                // lock, after the first durable logical mutation.
                val previous = state
                assertTrue(previous.groupByMessageId.containsKey(101L))
                state = previous.copy(
                    groupByMessageId = previous.groupByMessageId + (102L to 11L),
                    activeMessageIdByGroup = previous.activeMessageIdByGroup + (11L to 102L),
                )
            }
        }
        assertFalse(second.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(mapOf(101L to 11L, 102L to 11L), state.groupByMessageId)
        assertEquals(102L, state.activeMessageIdByGroup[11L])
    }

    @Test
    fun `different conversations do not share the response mutex`() = runTest {
        val enteredA = CompletableDeferred<Unit>()
        val enteredB = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val first = async {
            ResponseVersionMutationCoordinator.withMutation(902L) {
                enteredA.complete(Unit)
                releaseA.await()
            }
        }
        enteredA.await()
        val second = async {
            ResponseVersionMutationCoordinator.withMutation(903L) {
                enteredB.complete(Unit)
            }
        }
        withTimeout(2_000) { enteredB.await() }
        releaseA.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun `deletion waits for response mutation and then future mutation is fenced`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mutation = async {
            ResponseVersionMutationCoordinator.withMutation(904L) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val deletion = com.projectnuke.fusion.chat.ConversationStructuralMutationRegistry.beginDeletion(904L)
        val settled = async { deletion.awaitQuiescent() }
        assertFalse(settled.isCompleted)
        release.complete(Unit)
        mutation.await()
        withTimeout(2_000) { settled.await() }
        try {
            ResponseVersionMutationCoordinator.withMutation(904L) { error("must not enter") }
            throw AssertionError("expected deletion rejection")
        } catch (_: ResponseVersionMutationRejectedException) {
            // New mutations are rejected until the worker finishes.
        } finally {
            deletion.finish()
        }
    }

    @Test
    fun `deletion first rejects response mutation without entering its block`() = runTest {
        val deletion = com.projectnuke.fusion.chat.ConversationStructuralMutationRegistry.beginDeletion(905L)
        try {
            var entered = false
            try {
                ResponseVersionMutationCoordinator.withMutation(905L) { entered = true }
            } catch (_: ResponseVersionMutationRejectedException) {
                // expected
            }
            assertFalse(entered)
        } finally {
            deletion.finish()
        }
    }

    @Test
    fun `cancelled waiter releases its structural lease`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            ResponseVersionMutationCoordinator.withMutation(906L) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val waiting = async {
            ResponseVersionMutationCoordinator.withMutation(906L) { error("must not enter") }
        }
        waiting.cancel(CancellationException("obsolete mutation"))
        release.complete(Unit)
        first.await()
        try {
            waiting.await()
        } catch (_: CancellationException) {
            // expected
        }
        val deletion = com.projectnuke.fusion.chat.ConversationStructuralMutationRegistry.beginDeletion(906L)
        withTimeout(2_000) { deletion.awaitQuiescent() }
        deletion.finish()
    }

    @Test
    fun `navigation updates current durable map without losing a concurrent new mapping`() = runTest {
        var state = ResponseVersionState()
        val releasePersistence = CompletableDeferred<Unit>()
        val persistence = async {
            ResponseVersionMutationCoordinator.withMutation(907L) {
                state = state.copy(
                    groupByMessageId = state.groupByMessageId + (201L to 21L),
                    activeMessageIdByGroup = state.activeMessageIdByGroup + (21L to 201L),
                )
                releasePersistence.await()
            }
        }
        val navigation = async {
            ResponseVersionMutationCoordinator.withMutation(907L) {
                val current = state
                state = current.copy(
                    activeMessageIdByGroup = current.activeMessageIdByGroup + (21L to 200L),
                )
            }
        }
        releasePersistence.complete(Unit)
        persistence.await()
        navigation.await()
        assertEquals(21L, state.groupByMessageId[201L])
        assertEquals(200L, state.activeMessageIdByGroup[21L])
    }

    @Test
    fun `branch snapshot cannot observe messages without their version metadata`() = runTest {
        val conversationId = 908L
        var messages = listOf(101L)
        var state = ResponseVersionState(
            groupByMessageId = mapOf(101L to 31L),
            activeMessageIdByGroup = mapOf(31L to 101L),
        )
        val persistenceEntered = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val persistence = async {
            ResponseVersionMutationCoordinator.withMutation(conversationId) {
                messages = messages + 102L
                persistenceEntered.complete(Unit)
                releasePersistence.await()
                state = state.copy(groupByMessageId = state.groupByMessageId + (102L to 31L))
            }
        }
        persistenceEntered.await()

        val branchLease = requireNotNull(
            ConversationStructuralMutationRegistry.tryAcquire(conversationId),
        )
        val snapshot = async {
            ResponseVersionMutationCoordinator.withVersionLockUnderLease(branchLease) {
                messages to state
            }
        }
        assertFalse(snapshot.isCompleted)
        releasePersistence.complete(Unit)
        persistence.await()
        val (capturedMessages, capturedState) = snapshot.await()
        assertTrue(capturedMessages.contains(102L))
        assertEquals(31L, capturedState.groupByMessageId[102L])

        val transactionStarted = CompletableDeferred<Unit>()
        val releaseTransaction = CompletableDeferred<Unit>()
        val transaction = async {
            transactionStarted.complete(Unit)
            releaseTransaction.await()
        }
        transactionStarted.await()
        val laterMutationEntered = CompletableDeferred<Unit>()
        val laterMutation = async {
            ResponseVersionMutationCoordinator.withMutation(conversationId) {
                laterMutationEntered.complete(Unit)
            }
        }
        withTimeout(2_000) { laterMutationEntered.await() }
        releaseTransaction.complete(Unit)
        transaction.await()
        laterMutation.await()
        branchLease.release()
    }
}
