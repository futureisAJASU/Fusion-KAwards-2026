package com.projectnuke.fusion.llm

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Background runtime priority/preemption ownership for dynamic follow-ups.
 *
 * Foreground admission (user generation, exclusive benchmark) always
 * outranks background work: the active background native conversation is
 * asked to stop and its coroutine is cancelled so the runtime mutex is
 * released ahead of the foreground request. Host tests prove the
 * ownership/cancellation trigger; final native stop latency under active
 * decode is user-owned device validation.
 */
class BackgroundRuntimeOwnerTest {
    @Test
    fun `foreground admission through the runtime lock preempts background work`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val background = launch {
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()
        val ticket = BackgroundRuntimeOwner.tryAcquire(background, 7L)
        try {
            assertNotNull(ticket)
            assertTrue(BackgroundRuntimeOwner.hasActiveOwner())
            FusionRuntimeLock.withChatGeneration(conversationId = null, lifecycleRequestId = null) {
                "foreground admitted"
            }
            background.join()
            assertTrue(background.isCancelled)
        } finally {
            ticket?.let { BackgroundRuntimeOwner.release(it) }
        }
        assertFalse(BackgroundRuntimeOwner.hasActiveOwner())
    }

    @Test
    fun `preempt requests native stop on the registered background conversation`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val background = launch {
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()
        val ticket = BackgroundRuntimeOwner.tryAcquire(background, 7L)
        try {
            assertNotNull(ticket)
            // Simulates the engine registering its native Conversation under
            // the ticket key during generate().
            val nativeStopRequested = AtomicBoolean(false)
            BackgroundRuntimeOwner.controller.registerCancellationHook(
                ticket!!.key,
                this@BackgroundRuntimeOwnerTest,
            ) { nativeStopRequested.set(true) }
            BackgroundRuntimeOwner.beginForegroundAdmission()
            try {
                background.join()
            } finally {
                BackgroundRuntimeOwner.endForegroundAdmission()
            }
            assertTrue(nativeStopRequested.get())
            assertTrue(background.isCancelled)
        } finally {
            ticket?.let { BackgroundRuntimeOwner.release(it) }
        }
        assertFalse(BackgroundRuntimeOwner.hasActiveOwner())
    }

    @Test
    fun `no concurrent background owner`() = runBlocking {
        val first = launch { awaitCancellation() }
        val ticket = BackgroundRuntimeOwner.tryAcquire(first, 7L)
        try {
            assertNotNull(ticket)
            val second = launch { awaitCancellation() }
            try {
                assertNull(BackgroundRuntimeOwner.tryAcquire(second, 7L))
            } finally {
                second.cancel()
                second.join()
            }
        } finally {
            ticket?.let { BackgroundRuntimeOwner.release(it) }
            first.cancel()
            first.join()
        }
        val third = launch { awaitCancellation() }
        val reacquired = BackgroundRuntimeOwner.tryAcquire(third, 7L)
        try {
            assertNotNull(reacquired)
        } finally {
            reacquired?.let { BackgroundRuntimeOwner.release(it) }
            third.cancel()
            third.join()
        }
    }

    @Test
    fun `cancellation releases background ownership`() = runBlocking {
        val background = launch { awaitCancellation() }
        val ticket = BackgroundRuntimeOwner.tryAcquire(background, 7L)
        assertNotNull(ticket)
        background.cancel()
        background.join()
        assertFalse(BackgroundRuntimeOwner.hasActiveOwner())
        ticket?.let { BackgroundRuntimeOwner.release(it) }
        assertFalse(BackgroundRuntimeOwner.hasActiveOwner())
    }

    @Test
    fun `foreign release and stray preempt cannot corrupt ownership`() = runBlocking {
        // Foreground admission with no owner is a safe no-op (and releases).
        BackgroundRuntimeOwner.beginForegroundAdmission()
        BackgroundRuntimeOwner.endForegroundAdmission()
        val owner = launch { awaitCancellation() }
        val ticket = BackgroundRuntimeOwner.tryAcquire(owner, 7L)
        try {
            assertNotNull(ticket)
            // A foreign ticket never clears the live owner.
            val foreign = launch { awaitCancellation() }
            val foreignTicket = BackgroundTicket(
                key = GenerationRequestKey(7L, "foreign"),
                job = foreign,
                lifetime = BackgroundNativeConversationLifetime(
                    ticketId = "foreign",
                    conversationId = 7L,
                    ownershipGeneration = 0L,
                    runtimeProfileIdentity = "test",
                    timeoutMs = 0L,
                    eventSink = {},
                ),
                ownershipLease = null,
            )
            try {
                BackgroundRuntimeOwner.release(foreignTicket)
                assertTrue(BackgroundRuntimeOwner.hasActiveOwner())
                // A cancelled-but-unreleased owner does not block a newer
                // admission; the stale release below is then a no-op.
                owner.cancel()
                owner.join()
                val next = launch { awaitCancellation() }
                val nextTicket = BackgroundRuntimeOwner.tryAcquire(next, 7L)
                try {
                    assertNotNull(nextTicket)
                    BackgroundRuntimeOwner.release(ticket!!)
                    assertTrue(BackgroundRuntimeOwner.hasActiveOwner())
                } finally {
                    nextTicket?.let { BackgroundRuntimeOwner.release(it) }
                    next.cancel()
                    next.join()
                }
            } finally {
                foreign.cancel()
                foreign.join()
            }
        } finally {
            ticket?.let { BackgroundRuntimeOwner.release(it) }
            if (owner.isActive) {
                owner.cancel()
                owner.join()
            }
        }
        assertFalse(BackgroundRuntimeOwner.hasActiveOwner())
    }

    // Foreground-admission atomicity: the lease closes the preempt/reserve
    // race with deterministic host synchronization (barriers + deferreds +
    // the owner's lease-count seam), never sleeps. ------------------------

    @Test
    fun `A - foreground reservation blocks a concurrent background acquire`() = runBlocking {
        val inForeground = CompletableDeferred<Unit>()
        val releaseForeground = CompletableDeferred<Unit>()
        val foreground = launch {
            FusionRuntimeLock.withChatGeneration(
                conversationId = null,
                lifecycleRequestId = null,
            ) {
                inForeground.complete(Unit)
                releaseForeground.await()
                "admitted"
            }
        }
        inForeground.await()
        val background = launch { awaitCancellation() }
        try {
            assertTrue(BackgroundRuntimeOwner.isForegroundPending())
            assertNull(BackgroundRuntimeOwner.tryAcquire(background, 7L))
        } finally {
            background.cancel()
            background.join()
            releaseForeground.complete(Unit)
            foreground.join()
        }
        assertFalse(BackgroundRuntimeOwner.isForegroundPending())
        assertFalse(BackgroundRuntimeOwner.hasActiveOwner())
    }

    @Test
    fun `B - foreground admission preempts an owner and blocks replacement while pending`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val owner = launch {
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()
        val ticket = BackgroundRuntimeOwner.tryAcquire(owner, 7L)
        requireNotNull(ticket)
        try {
            assertTrue(BackgroundRuntimeOwner.hasActiveOwner())
            val nativeStopRequested = AtomicBoolean(false)
            BackgroundRuntimeOwner.controller.registerCancellationHook(
                ticket.key,
                this@BackgroundRuntimeOwnerTest,
            ) { nativeStopRequested.set(true) }
            BackgroundRuntimeOwner.beginForegroundAdmission()
            try {
                owner.join()
                assertTrue(owner.isCancelled)
                assertTrue(nativeStopRequested.get())
                val replacement = launch { awaitCancellation() }
                try {
                    assertNull(BackgroundRuntimeOwner.tryAcquire(replacement, 7L))
                } finally {
                    replacement.cancel()
                    replacement.join()
                }
            } finally {
                BackgroundRuntimeOwner.endForegroundAdmission()
            }
        } finally {
            BackgroundRuntimeOwner.release(ticket)
            if (owner.isActive) {
                owner.cancel()
                owner.join()
            }
        }
        assertFalse(BackgroundRuntimeOwner.hasActiveOwner())
    }

    @Test
    fun `C - queued foreground reservations keep background blocked until all release`() = runBlocking {
        val background = launch { awaitCancellation() }
        try {
            BackgroundRuntimeOwner.beginForegroundAdmission()
            BackgroundRuntimeOwner.beginForegroundAdmission()
            assertNull(BackgroundRuntimeOwner.tryAcquire(background, 7L))
            // First foreground completes: the second still holds the gate.
            BackgroundRuntimeOwner.endForegroundAdmission()
            assertNull(BackgroundRuntimeOwner.tryAcquire(background, 7L))
            // Second completes: only now is background eligible again.
            BackgroundRuntimeOwner.endForegroundAdmission()
            val ticket = BackgroundRuntimeOwner.tryAcquire(background, 7L)
            try {
                assertNotNull(ticket)
            } finally {
                ticket?.let { BackgroundRuntimeOwner.release(it) }
            }
        } finally {
            while (BackgroundRuntimeOwner.foregroundReservationCount() > 0) {
                BackgroundRuntimeOwner.endForegroundAdmission()
            }
            background.cancel()
            background.join()
        }
        assertFalse(BackgroundRuntimeOwner.hasActiveOwner())
    }

    @Test
    fun `C2 - queued chat generations keep background blocked until both released`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val first = launch {
            FusionRuntimeLock.withChatGeneration(
                conversationId = null,
                lifecycleRequestId = null,
            ) {
                firstEntered.complete(Unit)
                releaseFirst.await()
                "first"
            }
        }
        firstEntered.await()
        val second = launch {
            FusionRuntimeLock.withChatGeneration(
                conversationId = null,
                lifecycleRequestId = null,
            ) {
                secondEntered.complete(Unit)
                releaseSecond.await()
                "second"
            }
        }
        // Cooperative event-loop yield until the second admission's lease is
        // actually taken (it is queued behind the first on the runtime mutex).
        while (BackgroundRuntimeOwner.foregroundReservationCount() < 2) yield()
        val background = launch { awaitCancellation() }
        try {
            assertNull(BackgroundRuntimeOwner.tryAcquire(background, 7L))
            releaseFirst.complete(Unit)
            first.join()
            // First fully released; second's lease alone keeps the gate shut.
            assertNull(BackgroundRuntimeOwner.tryAcquire(background, 7L))
            releaseSecond.complete(Unit)
            second.join()
            val ticket = BackgroundRuntimeOwner.tryAcquire(background, 7L)
            try {
                assertNotNull(ticket)
            } finally {
                ticket?.let { BackgroundRuntimeOwner.release(it) }
            }
        } finally {
            background.cancel()
            background.join()
        }
        assertFalse(BackgroundRuntimeOwner.hasActiveOwner())
    }

    @Test
    fun `D - foreground admission throwing before mutex still releases its lease`() = runBlocking {
        val inBenchmark = CompletableDeferred<Unit>()
        val releaseBenchmark = CompletableDeferred<Unit>()
        val benchmark = launch {
            FusionRuntimeLock.withExclusiveBenchmark(onPrepareExclusiveMode = {}) {
                inBenchmark.complete(Unit)
                releaseBenchmark.await()
            }
        }
        inBenchmark.await()
        try {
            var threwBenchmarkConflict = false
            try {
                FusionRuntimeLock.withChatGeneration(
                    conversationId = null,
                    lifecycleRequestId = null,
                ) {
                    error("foreground block must not run under a benchmark")
                }
            } catch (expected: BenchmarkRunningException) {
                threwBenchmarkConflict = true
            }
            assertTrue(threwBenchmarkConflict)
            // The failed chat admission released its own lease, but the
            // benchmark's lease is still held, so background stays blocked.
            val background = launch { awaitCancellation() }
            try {
                assertNull(BackgroundRuntimeOwner.tryAcquire(background, 7L))
            } finally {
                background.cancel()
                background.join()
            }
        } finally {
            releaseBenchmark.complete(Unit)
            benchmark.join()
        }
        val backgroundAgain = launch { awaitCancellation() }
        val ticket = BackgroundRuntimeOwner.tryAcquire(backgroundAgain, 7L)
        try {
            assertNotNull(ticket)
        } finally {
            ticket?.let { BackgroundRuntimeOwner.release(it) }
            backgroundAgain.cancel()
            backgroundAgain.join()
        }
    }

    @Test
    fun `D2 - exception inside the foreground block still releases its lease`() = runBlocking {
        runCatching {
            FusionRuntimeLock.withChatGeneration(
                conversationId = null,
                lifecycleRequestId = null,
            ) {
                error("boom inside block")
            }
        }
        val background = launch { awaitCancellation() }
        val ticket = BackgroundRuntimeOwner.tryAcquire(background, 7L)
        try {
            assertNotNull(ticket)
        } finally {
            ticket?.let { BackgroundRuntimeOwner.release(it) }
            background.cancel()
            background.join()
        }
    }

    @Test
    fun `E - benchmark admission uses the same atomic foreground rule`() = runBlocking {
        val inBenchmark = CompletableDeferred<Unit>()
        val releaseBenchmark = CompletableDeferred<Unit>()
        val benchmark = launch {
            FusionRuntimeLock.withExclusiveBenchmark(onPrepareExclusiveMode = {}) {
                inBenchmark.complete(Unit)
                releaseBenchmark.await()
            }
        }
        inBenchmark.await()
        val background = launch { awaitCancellation() }
        try {
            assertTrue(BackgroundRuntimeOwner.isForegroundPending())
            assertNull(BackgroundRuntimeOwner.tryAcquire(background, 7L))
        } finally {
            background.cancel()
            background.join()
            releaseBenchmark.complete(Unit)
            benchmark.join()
        }
        assertFalse(BackgroundRuntimeOwner.isForegroundPending())
        assertFalse(BackgroundRuntimeOwner.hasActiveOwner())
    }

    @Test
    fun `F - stale background release cannot clear a newer owner`() = runBlocking {        val first = launch { awaitCancellation() }
        val stale = BackgroundRuntimeOwner.tryAcquire(first, 7L)
        requireNotNull(stale)
        BackgroundRuntimeOwner.beginForegroundAdmission()
        first.cancel()
        first.join()
        BackgroundRuntimeOwner.release(stale)
        BackgroundRuntimeOwner.endForegroundAdmission()
        val newer = launch { awaitCancellation() }
        val newerTicket = BackgroundRuntimeOwner.tryAcquire(newer, 7L)
        try {
            assertNotNull(newerTicket)
            // A stale release of the already-superseded ticket is ignored:
            // it cannot clear the newer owner.
            BackgroundRuntimeOwner.release(stale)
            assertTrue(BackgroundRuntimeOwner.hasActiveOwner())
        } finally {
            newerTicket?.let { BackgroundRuntimeOwner.release(it) }
            newer.cancel()
            newer.join()
        }
        assertFalse(BackgroundRuntimeOwner.hasActiveOwner())
    }

    @Test
    fun `G - superseded ticket release still clears its own controller state`() = runBlocking {
        val baselineRegistered = BackgroundRuntimeOwner.controller.registeredRequestCount()
        val first = launch { awaitCancellation() }
        val stale = BackgroundRuntimeOwner.tryAcquire(first, 7L)
        requireNotNull(stale)
        // Simulate engine-side native registration + partial output under
        // the stale ticket key.
        BackgroundRuntimeOwner.controller.registerCancellationHook(
            stale.key,
            this@BackgroundRuntimeOwnerTest,
        ) {}
        BackgroundRuntimeOwner.controller.publishPartial(stale.key, "partial")
        assertEquals(
            baselineRegistered + 1,
            BackgroundRuntimeOwner.controller.registeredRequestCount(),
        )
        // Supersede: the stale job is cancelled (its finally has not run
        // yet) and a newer ticket overwrites the active slot.
        first.cancel()
        first.join()
        val newer = launch { awaitCancellation() }
        val newerTicket = BackgroundRuntimeOwner.tryAcquire(newer, 7L)
        try {
            requireNotNull(newerTicket)
            // Late release of the superseded ticket must keep the newer
            // owner while clearing its own native registration/partial.
            BackgroundRuntimeOwner.release(stale)
            assertTrue(BackgroundRuntimeOwner.hasActiveOwner())
            assertNull(BackgroundRuntimeOwner.controller.partialSnapshot(stale.key))
            assertEquals(
                baselineRegistered,
                BackgroundRuntimeOwner.controller.registeredRequestCount(),
            )
        } finally {
            newerTicket?.let { BackgroundRuntimeOwner.release(it) }
            newer.cancel()
            newer.join()
        }
        assertFalse(BackgroundRuntimeOwner.hasActiveOwner())
        assertEquals(
            baselineRegistered,
            BackgroundRuntimeOwner.controller.registeredRequestCount(),
        )
    }
}
