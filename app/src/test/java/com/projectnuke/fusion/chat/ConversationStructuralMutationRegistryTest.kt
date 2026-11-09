package com.projectnuke.fusion.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStructuralMutationRegistryTest {
    @Test
    fun `pre-existing lease blocks deletion and new leases are rejected`() = runBlocking {
        coroutineScope {
            val lease = requireNotNull(ConversationStructuralMutationRegistry.tryAcquire(801L))
            val barrier = ConversationStructuralMutationRegistry.beginDeletion(801L)
            val settled = async { barrier.awaitQuiescent() }
            assertNull(ConversationStructuralMutationRegistry.tryAcquire(801L))
            assertTrue(!settled.isCompleted)

            lease.release()
            withTimeout(2_000) { settled.await() }
            barrier.finish()
            requireNotNull(ConversationStructuralMutationRegistry.tryAcquire(801L)).release()
        }
    }

    @Test
    fun `all pre-existing leases must release before deletion settles`() = runBlocking {
        val first = requireNotNull(ConversationStructuralMutationRegistry.tryAcquire(802L))
        val second = requireNotNull(ConversationStructuralMutationRegistry.tryAcquire(802L))
        val barrier = ConversationStructuralMutationRegistry.beginDeletion(802L)
        val settled = async { barrier.awaitQuiescent() }

        first.release()
        assertTrue(!settled.isCompleted)
        second.release()
        withTimeout(2_000) { settled.await() }
        barrier.finish()
    }

    @Test
    fun `aborted deletion can release its fence for a later lease`() = runBlocking {
        val existing = requireNotNull(ConversationStructuralMutationRegistry.tryAcquire(803L))
        val barrier = ConversationStructuralMutationRegistry.beginDeletion(803L)
        val waiter = async { barrier.awaitQuiescent() }
        waiter.cancel(CancellationException("deletion caller abandoned"))
        try {
            existing.release()
            barrier.finish()
        } finally {
            waiter.cancel()
        }
        requireNotNull(ConversationStructuralMutationRegistry.tryAcquire(803L)).release()
    }

    @Test
    fun `ordinary leases remain independent across conversations`() = runBlocking {
        val first = requireNotNull(ConversationStructuralMutationRegistry.tryAcquire(804L))
        val second = requireNotNull(ConversationStructuralMutationRegistry.tryAcquire(805L))
        first.release()
        second.release()
    }

    @Test
    fun `duplicate deletion barriers keep the conversation fenced until all finish`() = runBlocking {
        val first = ConversationStructuralMutationRegistry.beginDeletion(805L)
        val second = ConversationStructuralMutationRegistry.beginDeletion(805L)
        try {
            first.finish()
            assertNull(ConversationStructuralMutationRegistry.tryAcquire(805L))
        } finally {
            second.finish()
        }
        requireNotNull(ConversationStructuralMutationRegistry.tryAcquire(805L)).release()
    }

    @Test
    fun `last duplicate barrier retires only after pre-existing lease settles`() = runBlocking {
        val lease = requireNotNull(ConversationStructuralMutationRegistry.tryAcquire(806L))
        val first = ConversationStructuralMutationRegistry.beginDeletion(806L)
        val second = ConversationStructuralMutationRegistry.beginDeletion(806L)
        first.finish()
        lease.release()
        assertNull(ConversationStructuralMutationRegistry.tryAcquire(806L))
        second.finish()
        requireNotNull(ConversationStructuralMutationRegistry.tryAcquire(806L)).release()
    }
}
