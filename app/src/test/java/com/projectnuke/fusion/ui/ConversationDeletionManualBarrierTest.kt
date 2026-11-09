package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ManualCompactionOperationRegistry
import com.projectnuke.fusion.chat.ConversationStructuralMutationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDeletionManualBarrierTest {

    @Test
    fun `caller cancellation does not finish barrier before deletion worker terminal`() = runBlocking {
        coroutineScope {
            val releaseWorker = CompletableDeferred<Unit>()
            val enteredAwait = CompletableDeferred<Unit>()
            val barrier = ManualCompactionOperationRegistry.beginDeletion(401L)
            val worker = async {
                releaseWorker.await()
                "deleted"
            }
            val caller = async {
                awaitDeletionWorkerBeforeReleasingManualBarrier(
                    barrier = barrier,
                    worker = worker,
                    beforeAwait = { enteredAwait.complete(Unit) },
                )
            }

            withTimeout(2_000) { enteredAwait.await() }
            caller.cancel(CancellationException("screen left"))
            assertFalse(caller.isCompleted)
            assertNull("worker lifetime still owns deletion", ManualCompactionOperationRegistry.tryAcquire(401L))

            releaseWorker.complete(Unit)
            withTimeout(2_000) { caller.join() }
            assertTrue(caller.isCancelled)
            val newManual = ManualCompactionOperationRegistry.tryAcquire(401L)
            assertTrue(newManual != null)
            ManualCompactionOperationRegistry.release(newManual!!)
        }
    }

    @Test
    fun `cancellation before worker creation releases the settled barrier`() = runBlocking {
        coroutineScope {
            val beforeWorkerStart = CompletableDeferred<Unit>()
            var workerStarted = false
            val barrier = ManualCompactionOperationRegistry.beginDeletion(405L)
            val caller = async {
                runDeletionWorkerAfterManualBarrier(
                    conversationId = 406L,
                    barrier = barrier,
                    beforeWorkerStart = {
                        beforeWorkerStart.complete(Unit)
                        kotlinx.coroutines.awaitCancellation()
                    },
                    startWorker = {
                        workerStarted = true
                        CompletableDeferred("must not start")
                    },
                )
            }

            withTimeout(2_000) { beforeWorkerStart.await() }
            caller.cancel(CancellationException("cancel before deletion worker"))
            withTimeout(2_000) { caller.join() }

            assertFalse(workerStarted)
            val newManual = ManualCompactionOperationRegistry.tryAcquire(405L)
            assertTrue(newManual != null)
            ManualCompactionOperationRegistry.release(newManual!!)
        }
    }

    @Test
    fun `cleanup state cannot be resurrected after cancelled deletion await`() = runBlocking {
        coroutineScope {
            var compactMemoryPresent = true
            val releaseWorker = CompletableDeferred<Unit>()
            val enteredAwait = CompletableDeferred<Unit>()
            val barrier = ManualCompactionOperationRegistry.beginDeletion(402L)
            val worker = async {
                releaseWorker.await()
                compactMemoryPresent = false
            }
            val caller = async {
                awaitDeletionWorkerBeforeReleasingManualBarrier(
                    barrier = barrier,
                    worker = worker,
                    beforeAwait = { enteredAwait.complete(Unit) },
                )
            }

            withTimeout(2_000) { enteredAwait.await() }
            caller.cancel(CancellationException("conversation switched"))
            assertNull(ManualCompactionOperationRegistry.tryAcquire(402L))
            releaseWorker.complete(Unit)
            withTimeout(2_000) { caller.join() }

            assertFalse("deletion cleanup removed the old compact memory", compactMemoryPresent)
            val afterDeletion = ManualCompactionOperationRegistry.tryAcquire(402L)
            assertTrue(afterDeletion != null)
            ManualCompactionOperationRegistry.release(afterDeletion!!)
        }
    }

    @Test
    fun `successful and failed deletion workers both release the barrier only at terminal`() = runBlocking {
        coroutineScope {
            val successBarrier = ManualCompactionOperationRegistry.beginDeletion(403L)
            val successWorker = async { "ok" }
            assertTrue(awaitDeletionWorkerBeforeReleasingManualBarrier(successBarrier, successWorker) == "ok")
            val afterSuccess = ManualCompactionOperationRegistry.tryAcquire(403L)
            assertTrue(afterSuccess != null)
            ManualCompactionOperationRegistry.release(afterSuccess!!)

            val failureBarrier = ManualCompactionOperationRegistry.beginDeletion(404L)
            val failureWorker = CompletableDeferred<String>().also {
                it.completeExceptionally(IllegalStateException("cleanup failed"))
            }
            try {
                awaitDeletionWorkerBeforeReleasingManualBarrier(failureBarrier, failureWorker)
                throw AssertionError("expected deletion failure")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message == "cleanup failed")
            }
            val afterFailure = ManualCompactionOperationRegistry.tryAcquire(404L)
            assertTrue(afterFailure != null)
            ManualCompactionOperationRegistry.release(afterFailure!!)
        }
    }

    @Test
    fun `manual owners settle before structural deletion fence`() = runBlocking {
        coroutineScope {
            val conversationId = 407L
            val finalPersistenceLease = requireNotNull(
                ConversationStructuralMutationRegistry.tryAcquire(conversationId),
            )
            assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, "submission"))
            val manualBarrier = ManualCompactionOperationRegistry.beginDeletion(conversationId)
            val beforeWorker = CompletableDeferred<Unit>()
            val deletion = async<String> {
                runDeletionWorkerAfterManualBarrier(
                    conversationId = conversationId,
                    barrier = manualBarrier,
                    beforeWorkerStart = { beforeWorker.complete(Unit) },
                    startWorker = { CompletableDeferred("deleted") },
                )
            }

            // Existing submission ownership is allowed to settle before the
            // structural fence is established.
            ManualCompactionOperationRegistry.releaseSubmission(conversationId, "submission")
            assertFalse(beforeWorker.isCompleted)
            // The final response persistence lease may finish before the
            // structural deletion fence starts waiting for quiescence.
            finalPersistenceLease.release()
            withTimeout(2_000) { beforeWorker.await() }
            assertEquals("deleted", deletion.await())
        }
    }

    @Test
    fun `cancelled duplicate delete cannot reopen a surviving deletion fence`() = runBlocking {
        coroutineScope {
            val conversationId = 408L
            val firstBeforeWorker = CompletableDeferred<Unit>()
            val secondBeforeWorker = CompletableDeferred<Unit>()
            val releaseSecondWorker = CompletableDeferred<Unit>()
            val firstBarrier = ManualCompactionOperationRegistry.beginDeletion(conversationId)
            val secondBarrier = ManualCompactionOperationRegistry.beginDeletion(conversationId)

            val first = async<String> {
                runDeletionWorkerAfterManualBarrier(
                    conversationId = conversationId,
                    barrier = firstBarrier,
                    beforeWorkerStart = {
                        firstBeforeWorker.complete(Unit)
                        awaitCancellation()
                    },
                    startWorker = { error("cancelled deletion must not start worker") },
                )
            }
            withTimeout(2_000) { firstBeforeWorker.await() }

            val second = async<String> {
                runDeletionWorkerAfterManualBarrier(
                    conversationId = conversationId,
                    barrier = secondBarrier,
                    beforeWorkerStart = { secondBeforeWorker.complete(Unit) },
                    startWorker = {
                        async {
                            releaseSecondWorker.await()
                            "deleted"
                        }
                    },
                )
            }
            withTimeout(2_000) { secondBeforeWorker.await() }

            first.cancel(CancellationException("first delete caller abandoned"))
            withTimeout(2_000) { first.join() }
            assertNull(ConversationStructuralMutationRegistry.tryAcquire(conversationId))
            assertFalse(ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, "new"))
            assertNull(ManualCompactionOperationRegistry.tryAcquire(conversationId))

            releaseSecondWorker.complete(Unit)
            assertEquals("deleted", second.await())
            val futureStructural = requireNotNull(
                ConversationStructuralMutationRegistry.tryAcquire(conversationId),
            )
            futureStructural.release()
            val futureSubmission = ManualCompactionOperationRegistry.tryAcquireSubmission(
                conversationId,
                "future",
            )
            assertTrue(futureSubmission)
            ManualCompactionOperationRegistry.releaseSubmission(conversationId, "future")
        }
    }
}
