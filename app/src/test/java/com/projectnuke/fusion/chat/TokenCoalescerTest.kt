package com.projectnuke.fusion.chat

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenCoalescerTest {
    @Test
    fun concurrentFinishWaitsForOneTerminalOperationAndPublishesOnce() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val snapshots = mutableListOf<String>()
        val scope = testScope()
        try {
            val coalescer = TokenCoalescer(scope, onPublish = {
                count.incrementAndGet()
                synchronized(snapshots) { snapshots += it }
                entered.countDown()
                awaitGate(release)
            })
            val first = async(Dispatchers.Default) { coalescer.finish() }
            awaitGate(entered)
            val second = async { coalescer.finish() }
            assertTrue("second finish returned before final callback completed", !second.isCompleted)
            release.countDown()
            val firstText = withTimeout(2_000) { first.await() }
            val secondText = withTimeout(2_000) { second.await() }
            assertEquals("", firstText)
            assertEquals(firstText, secondText)
            assertEquals(1, count.get())
            assertEquals(listOf(""), synchronized(snapshots) { snapshots.toList() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentAbortCallersWaitForConsumerCleanup() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = testScope()
        try {
            val coalescer = TokenCoalescer(scope, onPublish = {
                entered.countDown()
                awaitGate(release)
            })
            coalescer.append("x")
            awaitGate(entered)
            val first = async { coalescer.abort() }
            val second = async { coalescer.abort() }
            assertTrue("abort completed before consumer cleanup", !first.isCompleted && !second.isCompleted)
            release.countDown()
            withTimeout(2_000) { first.await(); second.await() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun abortDuringFinishingSuppressesFinalPublication() = runBlocking {
        val intermediateEntered = CountDownLatch(1)
        val releaseIntermediate = CountDownLatch(1)
        val snapshots = mutableListOf<String>()
        val scope = testScope()
        try {
            val coalescer = TokenCoalescer(scope, onPublish = {
                synchronized(snapshots) { snapshots += it }
                intermediateEntered.countDown()
                awaitGate(releaseIntermediate)
            })
            coalescer.append("partial")
            awaitGate(intermediateEntered)
            val finish = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                coalescer.finish()
            }
            val abort = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { coalescer.abort() }
            releaseIntermediate.countDown()
            withTimeout(2_000) { finish.await(); abort.await() }
            assertEquals(listOf("partial"), synchronized(snapshots) { snapshots.toList() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun abortWaitsForInFlightIntermediatePublicationAndPreventsLaterPublication() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val scope = testScope()
        try {
            val coalescer = TokenCoalescer(scope, publishIntervalMs = 0, onPublish = {
                count.incrementAndGet()
                entered.countDown()
                awaitGate(release)
            })
            coalescer.append("a")
            awaitGate(entered)
            val abort = async { coalescer.abort() }
            assertTrue("abort returned while callback was in flight", !abort.isCompleted)
            release.countDown()
            withTimeout(2_000) { abort.await() }
            coalescer.append("b")
            assertEquals(1, count.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun appendDuringPublishingIsExcludedFromFinalizedText() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = testScope()
        try {
            val coalescer = TokenCoalescer(scope, onPublish = {
                entered.countDown()
                awaitGate(release)
            })
            coalescer.append("early")
            awaitGate(entered)
            val finish = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { coalescer.finish() }
            coalescer.append("late")
            release.countDown()
            assertEquals("early", withTimeout(2_000) { finish.await() })
            assertEquals("early", withTimeout(2_000) { coalescer.finish() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun throwingShouldPublishSettlesTerminalCompletion() = runBlocking {
        val scope = testScope()
        try {
            val failure = IllegalStateException("should")
            val coalescer = TokenCoalescer(scope, shouldPublish = { throw failure })
            try {
                withTimeout(2_000) { coalescer.finish() }
                assertTrue("finish unexpectedly succeeded", false)
            } catch (actual: IllegalStateException) {
                assertEquals(failure.message, actual.message)
            }
            withTimeout(2_000) { coalescer.finish(); coalescer.abort() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun throwingOnPublishSettlesTerminalCompletion() = runBlocking {
        val scope = testScope()
        try {
            val failure = IllegalArgumentException("publish")
            val coalescer = TokenCoalescer(scope, onPublish = { throw failure })
            try {
                withTimeout(2_000) { coalescer.finish() }
                assertTrue("finish unexpectedly succeeded", false)
            } catch (actual: IllegalArgumentException) {
                assertEquals(failure.message, actual.message)
            }
            withTimeout(2_000) { coalescer.finish(); coalescer.abort() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellationDuringFinalPublicationCleansUpAndPropagates() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val scope = testScope()
        try {
            val coalescer = TokenCoalescer(scope, onPublish = {
                active.incrementAndGet()
                entered.countDown()
                awaitGate(release)
                active.decrementAndGet()
            })
            val finish = async(Dispatchers.Default) { coalescer.finish() }
            awaitGate(entered)
            finish.cancel()
            release.countDown()
            try {
                withTimeout(2_000) { finish.await() }
                assertTrue("cancelled finish unexpectedly succeeded", false)
            } catch (_: CancellationException) {
                // Expected cancellation from the primary finisher.
            }
            withTimeout(2_000) { coalescer.abort() }
            assertEquals(0, active.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repeatedFinishReturnsSameFinalizedText() = runBlocking {
        val scope = testScope()
        try {
            val coalescer = TokenCoalescer(scope)
            coalescer.append("stable")
            assertEquals("stable", withTimeout(2_000) { coalescer.finish() })
            assertEquals("stable", withTimeout(2_000) { coalescer.finish() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repeatedAbortDoesNotStartAnotherCleanup() = runBlocking {
        val count = AtomicInteger()
        val scope = testScope()
        try {
            val coalescer = TokenCoalescer(scope, onPublish = { count.incrementAndGet() })
            withTimeout(2_000) { coalescer.abort(); coalescer.abort() }
            coalescer.append("ignored")
            assertEquals(0, count.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun noPublicationOccursAfterAbortReturns() = runBlocking {
        val count = AtomicInteger()
        val scope = testScope()
        try {
            val coalescer = TokenCoalescer(scope, publishIntervalMs = 0, onPublish = { count.incrementAndGet() })
            withTimeout(2_000) { coalescer.abort() }
            coalescer.append("after")
            withTimeout(2_000) { kotlinx.coroutines.yield() }
            assertEquals(0, count.get())
        } finally {
            scope.cancel()
        }
    }

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun awaitGate(gate: CountDownLatch) {
        assertTrue("synchronization gate was not reached", gate.await(2, TimeUnit.SECONDS))
    }
}
