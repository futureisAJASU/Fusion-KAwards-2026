package com.projectnuke.fusion.chat

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.projectnuke.fusion.llm.GenerationRequestKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSessionRegistryTest {

    @Test
    fun `started session becomes active with correct conversation and request ID`() = runBlocking {
        val scope = testScope()
        val blockEntered = CountDownLatch(1)
        val blockRelease = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()
            val session = withTimeout(2000) {
                registry.start(scope, snapshot(1L, "r1")) {
                    blockEntered.countDown()
                    blockRelease.await()
                }
            }
            awaitGate(blockEntered)
            assertEquals(1L, session.conversationId)
            assertEquals("r1", session.requestId)
            assertTrue(session.job.isActive)
            assertTrue(registry.isActive(1L, "r1"))
            assertNotNull(registry.activeSession(1L))
        } finally {
            blockRelease.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `assistant persistence admission is exact owner one shot and deletion aware`() = runBlocking {
        val scope = testScope()
        val release = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry()
        val key = GenerationRequestKey(101L, "admission")
        try {
            registry.start(scope, snapshot(101L, "admission")) { release.await() }
            assertTrue(registry.admitAssistantResponsePersistence(key))
            assertFalse(registry.admitAssistantResponsePersistence(key))

            val deleted = GenerationSessionRegistry()
            val deletedRelease = CompletableDeferred<Unit>()
            deleted.start(scope, snapshot(102L, "deleted")) { deletedRelease.await() }
            assertTrue(deleted.claimDeletionOwnership(102L, "test-delete"))
            assertFalse(
                deleted.admitAssistantResponsePersistence(
                    GenerationRequestKey(102L, "deleted"),
                ),
            )
            deleted.releaseDeletionOwnership(102L, "test-delete")
            deletedRelease.complete(Unit)
        } finally {
            release.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
        Unit
    }

    @Test
    fun `user stop partial keeps its claimed persistence slot while cancelled session settles`() = runBlocking {
        val scope = testScope()
        val bodyEntered = CompletableDeferred<Unit>()
        val catchEntered = CompletableDeferred<Unit>()
        val releaseCatch = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry()
        val key = GenerationRequestKey(104L, "user-stop-admission")
        try {
            val session = registry.start(scope, snapshot(key.conversationId, key.requestId)) {
                bodyEntered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    catchEntered.complete(Unit)
                    withContext(NonCancellable) { releaseCatch.await() }
                    throw cancelled
                }
            }
            withTimeout(2_000) { bodyEntered.await() }
            registry.cancellationController.recordIntent(key, com.projectnuke.fusion.llm.GenerationTerminationIntent.USER_STOP)
            session.job.cancel(CancellationException("user stop"))
            catchEntered.await()

            assertTrue(registry.claimUserStopPartial(key.conversationId, key.requestId))
            assertTrue(registry.admitAssistantResponsePersistence(key))
            assertFalse(registry.admitAssistantResponsePersistence(key))

            releaseCatch.complete(Unit)
            session.job.join()
        } finally {
            releaseCatch.complete(Unit)
            scope.cancel()
            withTimeout(2_000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `normal block completion removes the session`() = runBlocking {
        val scope = testScope()
        val blockRelease = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()
            val session = withTimeout(2000) {
                registry.start(scope, snapshot(2L, "r2")) {
                    blockRelease.await()
                }
            }
            blockRelease.complete(Unit)
            withTimeout(2000) { session.job.join() }
            assertFalse(registry.isActive(2L, "r2"))
            assertNull(registry.activeSession(2L))
        } finally {
            blockRelease.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `block exception removes the session`() = runBlocking {
        val scope = testScope()
        val errorRef = AtomicReference<Throwable>()
        val handler = CoroutineExceptionHandler { _, e -> errorRef.set(e) }
        val errorScope = CoroutineScope(scope.coroutineContext[Job]!! + Dispatchers.Default + handler)
        try {
            val registry = GenerationSessionRegistry()
            val session = withTimeout(2000) {
                registry.start(errorScope, snapshot(3L, "r3")) {
                    throw IllegalStateException("boom")
                }
            }
            withTimeout(2000) { session.job.join() }
            assertTrue("expected IllegalStateException in errorRef",
                errorRef.get() is IllegalStateException)
            assertFalse(registry.isActive(3L, "r3"))
            assertNull(registry.activeSession(3L))
        } finally {
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `replacing A cancels and fully joins A before B begins`() = runBlocking {
        val scope = testScope()
        val holdGate = CompletableDeferred<Unit>()
        val aCleanupStarted = CompletableDeferred<Unit>()
        val aCleanupRelease = CompletableDeferred<Unit>()
        val aStarted = CountDownLatch(1)
        val bEntered = AtomicBoolean(false)
        val bHold = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()

            val aDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(4L, "A")) {
                    aStarted.countDown()
                    try {
                        holdGate.await()
                    } finally {
                        aCleanupStarted.complete(Unit)
                        withContext(NonCancellable) {
                            aCleanupRelease.await()
                        }
                    }
                }
            }

            awaitGate(aStarted)

            val bDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(4L, "B")) {
                    bEntered.set(true)
                    bHold.await()
                }
            }

            withTimeout(2000) { aCleanupStarted.await() }
            assertFalse("B entered before A cleanup completed", bEntered.get())

            aCleanupRelease.complete(Unit)
            withTimeout(2000) { aDone.await(); bDone.await() }

            withTimeout(2000) { while (!bEntered.get()) { yield() } }
            assertTrue(registry.isActive(4L, "B"))
            assertFalse(registry.isActive(4L, "A"))
        } finally {
            holdGate.complete(Unit)
            aCleanupRelease.complete(Unit)
            bHold.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `A late completion callback cannot remove B`() = runBlocking {
        val scope = testScope()
        val holdGate = CompletableDeferred<Unit>()
        val aCleanupRelease = CompletableDeferred<Unit>()
        val aStarted = CountDownLatch(1)
        val bStarted = CountDownLatch(1)
        val bHold = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()

            val aDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(5L, "A")) {
                    aStarted.countDown()
                    try {
                        holdGate.await()
                    } finally {
                        withContext(NonCancellable) {
                            aCleanupRelease.await()
                        }
                    }
                }
            }

            awaitGate(aStarted)

            val bDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(5L, "B")) {
                    bStarted.countDown()
                    bHold.await()
                }
            }

            aCleanupRelease.complete(Unit)
            withTimeout(2000) { aDone.await() }
            awaitGate(bStarted)

            assertTrue("B should be the active session after replacement",
                registry.isActive(5L, "B"))
            assertEquals("B", registry.activeSession(5L)?.requestId)
        } finally {
            holdGate.complete(Unit)
            aCleanupRelease.complete(Unit)
            bHold.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `concurrent starts for one conversation leave only the newest active`() = runBlocking {
        val scope = testScope()
        val holdA = CompletableDeferred<Unit>()
        val aStarted = CountDownLatch(1)
        val bStarted = CountDownLatch(1)
        val bHold = CompletableDeferred<Unit>()
        val cStarted = CountDownLatch(1)
        val cHold = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()

            val aDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(6L, "A")) {
                    aStarted.countDown()
                    holdA.await()
                }
            }

            awaitGate(aStarted)

            val bDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(6L, "B")) {
                    bStarted.countDown()
                    bHold.await()
                }
            }

            holdA.complete(Unit)
            withTimeout(2000) { aDone.await() }
            awaitGate(bStarted)

            assertTrue("B should be active after A cleanup", registry.isActive(6L, "B"))

            val cDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(6L, "C")) {
                    cStarted.countDown()
                    cHold.await()
                }
            }

            awaitGate(cStarted)

            assertTrue("C should be active as the newest starting session",
                registry.isActive(6L, "C"))
            assertFalse("A should no longer be active", registry.isActive(6L, "A"))
            assertFalse("B should no longer be active", registry.isActive(6L, "B"))
        } finally {
            holdA.complete(Unit)
            bHold.complete(Unit)
            cHold.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `sessions for different conversations operate independently`() = runBlocking {
        val scope = testScope()
        val holdA = CompletableDeferred<Unit>()
        val holdB = CompletableDeferred<Unit>()
        val aStarted = CountDownLatch(1)
        val bStarted = CountDownLatch(1)
        try {
            val registry = GenerationSessionRegistry()
            val aSession = async(Dispatchers.Default) {
                registry.start(scope, snapshot(10L, "A")) {
                    aStarted.countDown()
                    holdA.await()
                }
            }
            val bSession = async(Dispatchers.Default) {
                registry.start(scope, snapshot(20L, "B")) {
                    bStarted.countDown()
                    holdB.await()
                }
            }

            awaitGate(aStarted)
            awaitGate(bStarted)

            assertTrue(registry.isActive(10L, "A"))
            assertTrue(registry.isActive(20L, "B"))

            holdA.complete(Unit)
            holdB.complete(Unit)
            val aSess = withTimeout(2000) { aSession.await() }
            val bSess = withTimeout(2000) { bSession.await() }
            withTimeout(2000) { aSess.job.join(); bSess.job.join() }

            assertFalse(registry.isActive(10L, "A"))
            assertFalse(registry.isActive(20L, "B"))
        } finally {
            holdA.complete(Unit)
            holdB.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancelAndJoin waits for NonCancellable cleanup before returning`() = runBlocking {
        val scope = testScope()
        val holdGate = CompletableDeferred<Unit>()
        val enteredBlock = CountDownLatch(1)
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()
            val aDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(7L, "A")) {
                    enteredBlock.countDown()
                    try {
                        holdGate.await()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            cleanupRelease.await()
                        }
                    }
                }
            }

            awaitGate(enteredBlock)

            val cancelDone = async(Dispatchers.Default) {
                withTimeout(2000) { registry.cancelAndJoin(7L, "test-cancel") }
            }

            withTimeout(2000) { cleanupStarted.await() }
            assertFalse("cancelAndJoin should still be blocked on cleanup",
                cancelDone.isCompleted)

            cleanupRelease.complete(Unit)
            withTimeout(2000) { cancelDone.await(); aDone.await() }

            assertFalse(registry.isActive(7L, "A"))
        } finally {
            holdGate.complete(Unit)
            cleanupRelease.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancelAndJoin rechecks when install wins between STARTING observation and cancel`() = runBlocking {
        val scope = testScope()
        val installEntered = CountDownLatch(1)
        val installRelease = CompletableDeferred<Unit>()
        val settlementObserved = CountDownLatch(1)
        val settlementRelease = CompletableDeferred<Unit>()
        val blockEntered = CountDownLatch(1)
        val blockRelease = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry()
        try {
            registry.onBeforeInstall = {
                installEntered.countDown()
                installRelease.await()
            }
            registry.onBeforeSettlementCancel = {
                settlementObserved.countDown()
                settlementRelease.await()
            }

            val startDeferred = async(Dispatchers.Default) {
                registry.start(scope, snapshot(71L, "race")) {
                    blockEntered.countDown()
                    blockRelease.await()
                }
            }
            awaitGate(installEntered)
            withTimeout(2000) {
                while (!registry.isStarting(71L, "race")) yield()
            }

            val cancelDeferred = async(Dispatchers.Default) {
                registry.cancelAndJoin(71L, "race", "stop-race")
            }
            awaitGate(settlementObserved)

            // The cancel path has observed STARTING and is paused before its
            // CAS. Let installation win so cancel() must return false.
            installRelease.complete(Unit)
            withTimeout(2000) {
                while (registry.latestToken(71L)?.state() != GenerationSessionRegistry.PendingStart.State.INSTALLED) {
                    yield()
                }
            }
            settlementRelease.complete(Unit)

            val session = withTimeout(2000) { startDeferred.await() }
            awaitGate(blockEntered)
            assertTrue("cancelAndJoin must settle the installed request", cancelDeferred.await())
            withTimeout(2000) { session.job.join() }
            assertTrue("installed session job must be completed before return", session.job.isCompleted)
            assertNull(registry.activeSession(71L))
            assertNull(registry.latestToken(71L))
        } finally {
            installRelease.complete(Unit)
            settlementRelease.complete(Unit)
            blockRelease.complete(Unit)
            registry.onBeforeInstall = {}
            registry.onBeforeSettlementCancel = {}
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancelAndJoin leaves no active session`() = runBlocking {
        val scope = testScope()
        val holdGate = CompletableDeferred<Unit>()
        val entered = CountDownLatch(1)
        try {
            val registry = GenerationSessionRegistry()
            async(Dispatchers.Default) {
                registry.start(scope, snapshot(8L, "r8")) {
                    entered.countDown()
                    holdGate.await()
                }
            }

            awaitGate(entered)
            withTimeout(2000) { registry.cancelAndJoin(8L, "stop") }

            assertNull(registry.activeSession(8L))
            assertFalse(registry.isActive(8L, "r8"))
            assertFalse(registry.hasActiveSession(8L))
        } finally {
            holdGate.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `starting with already-cancelled scope throws CancellationException`() {
        val deadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { it.cancel() }
        val registry = GenerationSessionRegistry()
        try {
            runBlocking {
                try {
                    registry.start(deadScope, snapshot(9L, "r9")) { }
                    assertTrue("expected CancellationException", false)
                } catch (_: CancellationException) {
                }
            }
            assertNull(registry.activeSession(9L))
            assertFalse(registry.isActive(9L, "r9"))
        } finally {
            deadScope.cancel()
        }
    }

    @Test
    fun `repeated cancel calls are harmless and do not target a replacement session`() = runBlocking {
        val scope = testScope()
        val holdGate = CompletableDeferred<Unit>()
        val aCleanupRelease = CompletableDeferred<Unit>()
        val aStarted = CountDownLatch(1)
        val bHold = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()
            val aDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(11L, "A")) {
                    aStarted.countDown()
                    try {
                        holdGate.await()
                    } finally {
                        withContext(NonCancellable) {
                            aCleanupRelease.await()
                        }
                    }
                }
            }

            awaitGate(aStarted)
            val bDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(11L, "B")) {
                    bHold.await()
                }
            }

            aCleanupRelease.complete(Unit)
            withTimeout(2000) { aDone.await(); bDone.await() }

            assertTrue("B should be active after replacement",
                registry.isActive(11L, "B"))

            assertTrue("cancelAndJoin on B should succeed and wait for completion",
                withTimeout(2000) { registry.cancelAndJoin(11L, "B", "cancel-B") })
            assertFalse("B should no longer be active", registry.isActive(11L, "B"))

            assertFalse("repeated cancel should be harmless",
                registry.cancel(11L, "again"))
            assertFalse("cancelAndJoin after no active session should return false",
                withTimeout(2000) { registry.cancelAndJoin(11L, "again") })
        } finally {
            holdGate.complete(Unit)
            aCleanupRelease.complete(Unit)
            bHold.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancelAndJoin called multiple times is harmless`() = runBlocking {
        val scope = testScope()
        val holdGate = CompletableDeferred<Unit>()
        val entered = CountDownLatch(1)
        try {
            val registry = GenerationSessionRegistry()
            async(Dispatchers.Default) {
                registry.start(scope, snapshot(12L, "r12")) {
                    entered.countDown()
                    holdGate.await()
                }
            }

            awaitGate(entered)
            assertTrue(withTimeout(2000) { registry.cancelAndJoin(12L, "first") })
            assertFalse("second cancelAndJoin should return false",
                withTimeout(2000) { registry.cancelAndJoin(12L, "second") })
            assertNull(registry.activeSession(12L))
        } finally {
            holdGate.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancel on non-existent conversation returns false`() = runBlocking {
        val registry = GenerationSessionRegistry()
        assertFalse(registry.cancel(99L, "nonexistent"))
        assertFalse(withTimeout(2000) { registry.cancelAndJoin(99L, "nonexistent") })
    }

    @Test
    fun `stale cancel after natural completion is harmless`() = runBlocking {
        val scope = testScope()
        val release = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()
            val session = withTimeout(2000) {
                registry.start(scope, snapshot(13L, "r13")) { release.await() }
            }
            release.complete(Unit)
            withTimeout(2000) { session.job.join() }
            assertFalse("cancel should return false for completed job",
                registry.cancel(13L, "late"))
            assertFalse("cancelAndJoin should return false for completed session",
                withTimeout(2000) { registry.cancelAndJoin(13L, "late") })
        } finally {
            release.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `request-aware cancelAndJoin returns false for stale request`() = runBlocking {
        val scope = testScope()
        val holdA = CompletableDeferred<Unit>()
        val aStarted = CountDownLatch(1)
        val bStarted = CountDownLatch(1)
        val bHold = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()

            val aDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(14L, "A")) {
                    aStarted.countDown()
                    holdA.await()
                }
            }

            awaitGate(aStarted)

            val bDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(14L, "B")) {
                    bStarted.countDown()
                    bHold.await()
                }
            }

            holdA.complete(Unit)
            withTimeout(2000) { aDone.await(); bDone.await() }
            awaitGate(bStarted)

            assertTrue("B should be active after replacement", registry.isActive(14L, "B"))

            assertFalse("stale request A cancellation should return false",
                withTimeout(2000) { registry.cancelAndJoin(14L, "A", "late-cancel-A") })
            assertTrue("B should still be active", registry.isActive(14L, "B"))

            assertTrue("cancel B with matching request should succeed",
                withTimeout(2000) { registry.cancelAndJoin(14L, "B", "cancel-B") })
            assertFalse("B should no longer be active", registry.isActive(14L, "B"))
        } finally {
            holdA.complete(Unit)
            bHold.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `pending request cancellation prevents block entry and throws CancellationException`() {
        runBlocking {
            val scope = testScope()
            val tokenGate = CompletableDeferred<Unit>()
            val tokenEntered = CompletableDeferred<Unit>()
            val bBlockEntered = CountDownLatch(1)
            val registry = GenerationSessionRegistry()
            try {
                registry.onAfterTokenClaim = {
                    if (registry.latestToken(15L)?.requestId == "B") {
                        tokenEntered.complete(Unit)
                        tokenGate.await()
                    }
                }

                val bDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(15L, "B")) {
                            bBlockEntered.countDown()
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) {
                    while (!registry.isPending(15L, "B")) { yield() }
                }
                withTimeout(2000) { tokenEntered.await() }

                val cancelB = async(Dispatchers.Default) {
                    withTimeout(2000) { registry.cancelAndJoin(15L, "B", "stop-pending-B") }
                }

                withTimeout(2000) {
                    while (registry.isPending(15L, "B")) { yield() }
                }

                assertFalse("cancelAndJoin should be blocked until B's start terminates",
                    cancelB.isCompleted)

                tokenGate.complete(Unit)

                val bResult = withTimeout(2000) { bDeferred.await() }
                assertEquals("cancelled", bResult)
                assertEquals(1, bBlockEntered.count)

                assertTrue(withTimeout(2000) { cancelB.await() })

                assertNull("registry should not have active session", registry.activeSession(15L))
                assertFalse("registry should not have active session for B", registry.isActive(15L, "B"))
                assertEquals(0, registry.conversationLockRegistrySizeForTests())
            } finally {
                tokenGate.complete(Unit)
                registry.onAfterTokenClaim = {}
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `isActive returns false for unknown conversation`() {
        val registry = GenerationSessionRegistry()
        assertFalse(registry.isActive(99L, "any"))
    }

    @Test
    fun `activeSession returns null for unknown conversation`() {
        val registry = GenerationSessionRegistry()
        assertNull(registry.activeSession(99L))
    }

    @Test
    fun `hasActiveSession returns false for unknown conversation`() {
        val registry = GenerationSessionRegistry()
        assertFalse(registry.hasActiveSession(99L))
    }

    @Test
    fun `pending cancelAndJoin waits for token completion before returning`() {
        runBlocking {
            val scope = testScope()
            val tokenGate = CompletableDeferred<Unit>()
            val tokenEntered = CompletableDeferred<Unit>()
            val bBlockEntered = CountDownLatch(1)
            val registry = GenerationSessionRegistry()
            try {
                registry.onAfterTokenClaim = {
                    if (registry.latestToken(16L)?.requestId == "B") {
                        tokenEntered.complete(Unit)
                        tokenGate.await()
                    }
                }

                val bDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(16L, "B")) {
                            bBlockEntered.countDown()
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) {
                    while (!registry.isPending(16L, "B")) { yield() }
                }
                withTimeout(2000) { tokenEntered.await() }

                val cancelB = async(Dispatchers.Default) {
                    withTimeout(2000) { registry.cancelAndJoin(16L, "B", "stop-pending-B") }
                }

                withTimeout(2000) {
                    while (registry.isPending(16L, "B")) { yield() }
                }

                assertFalse("cancelAndJoin should be blocked until B's start terminates",
                    cancelB.isCompleted)

                tokenGate.complete(Unit)

                val bResult = withTimeout(2000) { bDeferred.await() }
                assertEquals("cancelled", bResult)
                assertEquals(1, bBlockEntered.count)

                assertTrue(withTimeout(2000) { cancelB.await() })

                assertNull("registry should not have active session", registry.activeSession(16L))
                assertFalse("registry should not have active session for B", registry.isActive(16L, "B"))
                assertEquals(0, registry.conversationLockRegistrySizeForTests())
            } finally {
                tokenGate.complete(Unit)
                registry.onAfterTokenClaim = {}
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `B-then-C pending supersession cancels B and C becomes active`() = runBlocking {
        val scope = testScope()
        val tokenGate = CompletableDeferred<Unit>()
        val bClaimed = CompletableDeferred<Unit>()
        val cClaimed = CompletableDeferred<Unit>()
        val bBlockEntered = CountDownLatch(1)
        val cEntered = CountDownLatch(1)
        val cHold = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry()
        try {
            registry.onAfterTokenClaim = {
                val head = registry.latestToken(17L)?.requestId
                if (head == "B") {
                    bClaimed.complete(Unit)
                    tokenGate.await()
                } else if (head == "C") {
                    cClaimed.complete(Unit)
                    tokenGate.await()
                }
            }

            val bDeferred = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(17L, "B")) {
                        bBlockEntered.countDown()
                        CompletableDeferred<Unit>().await()
                    }
                    "installed"
                } catch (_: CancellationException) { "cancelled" }
            }

            withTimeout(2000) {
                while (!registry.isPending(17L, "B")) { yield() }
            }
            withTimeout(2000) { bClaimed.await() }

            val cDeferred = async(Dispatchers.Default) {
                registry.start(scope, snapshot(17L, "C")) {
                    cEntered.countDown()
                    cHold.await()
                }
            }

            withTimeout(2000) { cClaimed.await() }
            withTimeout(2000) {
                while (registry.isPending(17L, "B")) { yield() }
            }

            assertFalse("B should no longer be pending after C replaces it",
                registry.isPending(17L, "B"))

            withTimeout(2000) {
                while (!registry.isPending(17L, "C")) { yield() }
            }

            tokenGate.complete(Unit)

            val bResult = withTimeout(2000) { bDeferred.await() }
            assertEquals("B start should have been cancelled", "cancelled", bResult)
            assertEquals(1, bBlockEntered.count)

            awaitGate(cEntered)

            assertTrue("C should be active after B is superseded", registry.isActive(17L, "C"))
            assertFalse("B should not be active", registry.isActive(17L, "B"))
        } finally {
            tokenGate.complete(Unit)
            registry.onAfterTokenClaim = {}
            cHold.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancelled start cleanup leaves no residual token and later start works`() = runBlocking {
        val scope = testScope()
        val tokenGate = CompletableDeferred<Unit>()
        val tokenEntered = CompletableDeferred<Unit>()
        val bBlockEntered = CountDownLatch(1)
        val cEntered = CountDownLatch(1)
        val cHold = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry()
        try {
            registry.onAfterTokenClaim = {
                if (registry.latestToken(18L)?.requestId == "B") {
                    tokenEntered.complete(Unit)
                    tokenGate.await()
                }
            }

            val bDeferred = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(18L, "B")) {
                        bBlockEntered.countDown()
                        CompletableDeferred<Unit>().await()
                    }
                    "installed"
                } catch (_: CancellationException) { "cancelled" }
            }

            withTimeout(2000) {
                while (!registry.isPending(18L, "B")) { yield() }
            }
            withTimeout(2000) { tokenEntered.await() }

            async(Dispatchers.Default) {
                withTimeout(2000) { registry.cancelAndJoin(18L, "B", "cleanup-test") }
            }

            withTimeout(2000) {
                while (registry.isPending(18L, "B")) { yield() }
            }

            assertFalse("B should not be pending after cancellation",
                registry.isPending(18L, "B"))

            tokenGate.complete(Unit)

            val bResult = withTimeout(2000) { bDeferred.await() }
            assertEquals("B start should have been cancelled", "cancelled", bResult)
            assertEquals(1, bBlockEntered.count)

            registry.onAfterTokenClaim = {}
            val cDeferred = async(Dispatchers.Default) {
                registry.start(scope, snapshot(18L, "C")) {
                    cEntered.countDown()
                    cHold.await()
                }
            }

            awaitGate(cEntered)

            assertTrue("C should be active after B cleanup", registry.isActive(18L, "C"))
        } finally {
            tokenGate.complete(Unit)
            registry.onAfterTokenClaim = {}
            cHold.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `C superseding B in STARTING state prevents B from becoming active`() {
        runBlocking {
            val scope = testScope()
            val aBlock = CompletableDeferred<Unit>()
            val aCleanupStarted = CountDownLatch(1)
            val aCleanupRelease = CompletableDeferred<Unit>()
            val aStarted = CountDownLatch(1)
            val cEntered = CountDownLatch(1)
            val cHold = CompletableDeferred<Unit>()
            try {
                val registry = GenerationSessionRegistry()

                val aDone = async(Dispatchers.Default) {
                    registry.start(scope, snapshot(19L, "A")) {
                        aStarted.countDown()
                        try {
                            aBlock.await()
                        } finally {
                            aCleanupStarted.countDown()
                            withContext(NonCancellable) {
                                aCleanupRelease.await()
                            }
                        }
                    }
                }
                awaitGate(aStarted)

                val bDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(19L, "B")) {
                            CompletableDeferred<Unit>().await()
                        }
                        null
                    } catch (e: CancellationException) {
                        e
                    }
                }

                withTimeout(2000) {
                    while (!registry.isStarting(19L, "B")) {
                        yield()
                    }
                }

                aBlock.complete(Unit)
                awaitGate(aCleanupStarted)

                async(Dispatchers.Default) {
                    registry.start(scope, snapshot(19L, "C")) {
                        cEntered.countDown()
                        cHold.await()
                    }
                }

                withTimeout(2000) {
                    while (registry.latestToken(19L)?.requestId != "C") {
                        yield()
                    }
                }

                aCleanupRelease.complete(Unit)

                val bResult = withTimeout(2000) { bDeferred.await() }
                assertTrue("B start should have thrown CancellationException",
                    bResult is CancellationException)

                awaitGate(cEntered)

                assertTrue("C should be active", registry.isActive(19L, "C"))
                assertFalse("B should not be active", registry.isActive(19L, "B"))
                assertFalse("A should not be active", registry.isActive(19L, "A"))
            } finally {
                aBlock.complete(Unit)
                aCleanupRelease.complete(Unit)
                cHold.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `cancelling pending C settles installed predecessor B`() {
        runBlocking {
            val scope = testScope()
            val bBlock = CompletableDeferred<Unit>()
            val bStarted = CountDownLatch(1)
            val tokenGate = CompletableDeferred<Unit>()
            val tokenEntered = CompletableDeferred<Unit>()
            try {
                val registry = GenerationSessionRegistry()

                val bDone = async(Dispatchers.Default) {
                    registry.start(scope, snapshot(20L, "B")) {
                        bStarted.countDown()
                        bBlock.await()
                    }
                }
                awaitGate(bStarted)
                assertTrue("B should be active", registry.isActive(20L, "B"))

                registry.onAfterTokenClaim = {
                    if (registry.latestToken(20L)?.requestId == "C") {
                        tokenEntered.complete(Unit)
                        tokenGate.await()
                    }
                }

                val cDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(20L, "C")) {
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) {
                    while (!registry.isPending(20L, "C")) { yield() }
                }
                withTimeout(2000) { tokenEntered.await() }

                val cancelC = async(Dispatchers.Default) {
                    withTimeout(2000) {
                        registry.cancelAndJoin(20L, "C", "cancel-C")
                    }
                }

                withTimeout(2000) {
                    while (registry.isPending(20L, "C")) { yield() }
                }
                assertFalse("cancelAndJoin should be blocked on C's completion",
                    cancelC.isCompleted)

                tokenGate.complete(Unit)

                val cResult = withTimeout(2000) { cDeferred.await() }
                assertEquals("cancelled", cResult)

                assertTrue(withTimeout(2000) { cancelC.await() })

                assertFalse("B should no longer be active", registry.isActive(20L, "B"))
                assertNull("C should have no active session", registry.activeSession(20L))
                assertNull("No lifecycle token should remain", registry.latestToken(20L))
            } finally {
                bBlock.complete(Unit)
                tokenGate.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `C cancels B via predecessor chain preventing B installation`() {
        runBlocking {
            val scope = testScope()
            val aBlock = CompletableDeferred<Unit>()
            val aStarted = CountDownLatch(1)
            val bBeforeInstall = CompletableDeferred<Unit>()
            val bGate = CompletableDeferred<Unit>()
            val cEntered = CountDownLatch(1)
            val cBlock = CompletableDeferred<Unit>()
            try {
                val registry = GenerationSessionRegistry()

                val aDone = async(Dispatchers.Default) {
                    registry.start(scope, snapshot(21L, "A")) {
                        aStarted.countDown()
                        aBlock.await()
                    }
                }
                awaitGate(aStarted)

                registry.onBeforeInstall = {
                    bBeforeInstall.complete(Unit)
                    bGate.await()
                }

                val bDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(21L, "B")) {
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) {
                        "cancelled"
                    }
                }

                withTimeout(2000) { bBeforeInstall.await() }

                assertTrue("B should be in STARTING state",
                    registry.isStarting(21L, "B"))
                assertTrue("B should have published session",
                    registry.hasSessionPublished(21L, "B"))

                val cDeferred = async(Dispatchers.Default) {
                    registry.start(scope, snapshot(21L, "C")) {
                        cEntered.countDown()
                        cBlock.await()
                    }
                }

                withTimeout(2000) {
                    while (!registry.isPending(21L, "C")) {
                        yield()
                    }
                }

                bGate.complete(Unit)

                val bResult = withTimeout(2000) { bDeferred.await() }
                assertEquals("B should have been cancelled before install", "cancelled", bResult)

                assertFalse("B should not be pending after cleanup",
                    registry.isPending(21L, "B"))

                aBlock.complete(Unit)
                withTimeout(2000) { aDone.await() }

                awaitGate(cEntered)
                assertTrue("C should be active after B is cancelled", registry.isActive(21L, "C"))
                assertFalse("B should not be active", registry.isActive(21L, "B"))
                assertFalse("A should not be active", registry.isActive(21L, "A"))
            } finally {
                aBlock.complete(Unit)
                bGate.complete(Unit)
                cBlock.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `pre-install commit window shows published session then cleans up on cancellation`() {
        runBlocking {
            val scope = testScope()
            val aBlock = CompletableDeferred<Unit>()
            val aStarted = CountDownLatch(1)
            val bBeforeInstall = CompletableDeferred<Unit>()
            val bGate = CompletableDeferred<Unit>()
            val cEntered = CountDownLatch(1)
            val cBlock = CompletableDeferred<Unit>()
            try {
                val registry = GenerationSessionRegistry()

                val aDone = async(Dispatchers.Default) {
                    registry.start(scope, snapshot(22L, "A")) {
                        aStarted.countDown()
                        aBlock.await()
                    }
                }
                awaitGate(aStarted)

                registry.onBeforeInstall = {
                    bBeforeInstall.complete(Unit)
                    bGate.await()
                }

                val bDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(22L, "B")) {
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) {
                        "cancelled"
                    }
                }

                withTimeout(2000) { bBeforeInstall.await() }

                assertTrue("B should have session published during commit window",
                    registry.hasSessionPublished(22L, "B"))
                assertTrue("B should be STARTING during commit window",
                    registry.isStarting(22L, "B"))

                val cDeferred = async(Dispatchers.Default) {
                    registry.start(scope, snapshot(22L, "C")) {
                        cEntered.countDown()
                        cBlock.await()
                    }
                }

                withTimeout(2000) {
                    while (!registry.isPending(22L, "C")) {
                        yield()
                    }
                }

                bGate.complete(Unit)

                val bResult = withTimeout(2000) { bDeferred.await() }
                assertEquals("B should have been cancelled", "cancelled", bResult)

                assertFalse("B should not have published session after cleanup",
                    registry.hasSessionPublished(22L, "B"))
                assertFalse("B should not be pending after cleanup",
                    registry.isPending(22L, "B"))

                aBlock.complete(Unit)
                withTimeout(2000) { aDone.await() }

                awaitGate(cEntered)
                assertTrue("C should be active", registry.isActive(22L, "C"))
                assertEquals("C", registry.activeSession(22L)?.requestId)
            } finally {
                aBlock.complete(Unit)
                bGate.complete(Unit)
                cBlock.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `cancelAndJoin walks predecessor chain cancelling published sessions`() {
        runBlocking {
            val scope = testScope()
            val aBlock = CompletableDeferred<Unit>()
            val aStarted = CountDownLatch(1)
            val bBeforeInstall = CompletableDeferred<Unit>()
            val bGate = CompletableDeferred<Unit>()
            try {
                val registry = GenerationSessionRegistry()

                val aDone = async(Dispatchers.Default) {
                    registry.start(scope, snapshot(23L, "A")) {
                        aStarted.countDown()
                        aBlock.await()
                    }
                }
                awaitGate(aStarted)

                registry.onBeforeInstall = {
                    bBeforeInstall.complete(Unit)
                    bGate.await()
                }

                val bDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(23L, "B")) {
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) {
                        "cancelled"
                    }
                }

                withTimeout(2000) { bBeforeInstall.await() }

                val cDeferred = async(Dispatchers.Default) {
                    registry.start(scope, snapshot(23L, "C")) {
                        CompletableDeferred<Unit>().await()
                    }
                }

                withTimeout(2000) {
                    while (registry.latestToken(23L)?.requestId != "C") { yield() }
                }

                val cancelJob = async(Dispatchers.Default) {
                    registry.cancelAndJoin(23L, "cancel-chain")
                }

                bGate.complete(Unit)

                withTimeout(2000) { cancelJob.await() }

                assertEquals("cancelled", withTimeout(2000) { bDeferred.await() })
                assertFalse("B should not be active", registry.isActive(23L, "B"))
                assertFalse("C should not be pending", registry.isPending(23L, "C"))

                aBlock.complete(Unit)
                withTimeout(2000) { aDone.await() }

                assertFalse("A should not be active", registry.isActive(23L, "A"))
                assertFalse("B should not be active", registry.isActive(23L, "B"))
            } finally {
                aBlock.complete(Unit)
                bGate.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `cancelling start coroutine while waiting for lock cleans up token`() {
        runBlocking {
            val scope = testScope()
            val aBeforeLock = CompletableDeferred<Unit>()
            val aGate = CompletableDeferred<Unit>()
            val cEntered = CountDownLatch(1)
            val cHold = CompletableDeferred<Unit>()
            try {
                val registry = GenerationSessionRegistry()
                registry.onBeforeInstall = {
                    aBeforeLock.complete(Unit)
                    aGate.await()
                }

                val aDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(24L, "A")) {
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) {
                        "cancelled"
                    }
                }

                withTimeout(2000) { aBeforeLock.await() }
                assertTrue("A should be STARTING", registry.isStarting(24L, "A"))

                val bDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(24L, "B")) {
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) {
                        "cancelled"
                    }
                }

                withTimeout(2000) {
                    while (!registry.isPending(24L, "B")) {
                        yield()
                    }
                }

                bDeferred.cancel()
                aGate.complete(Unit)

                withTimeout(2000) { bDeferred.join() }

                assertFalse("B should not be pending after cancellation",
                    registry.isPending(24L, "B"))
                assertNull("B should not have active session",
                    registry.activeSession(24L))

                val aResult = withTimeout(2000) { aDeferred.await() }
                assertEquals("A should be cancelled (superseded by B)", "cancelled", aResult)
                assertNull("No active session should remain",
                    registry.activeSession(24L))

                val cDeferred = async(Dispatchers.Default) {
                    registry.start(scope, snapshot(24L, "C")) {
                        cEntered.countDown()
                        cHold.await()
                    }
                }
                awaitGate(cEntered)
                assertTrue("C should start fresh after A and B cleanup",
                    registry.isActive(24L, "C"))
                assertEquals("C", registry.activeSession(24L)?.requestId)

                cHold.complete(Unit)
                withTimeout(2000) { cDeferred.await() }
            } finally {
                aGate.complete(Unit)
                cHold.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `cancelling pending C with installed predecessor B settles both`() {
        runBlocking {
            val scope = testScope()
            val bBlock = CompletableDeferred<Unit>()
            val bStarted = CountDownLatch(1)
            val tokenGate = CompletableDeferred<Unit>()
            val tokenEntered = CompletableDeferred<Unit>()
            val cBlock = CompletableDeferred<Unit>()
            try {
                val registry = GenerationSessionRegistry()

                val bDone = async(Dispatchers.Default) {
                    registry.start(scope, snapshot(25L, "B")) {
                        bStarted.countDown()
                        bBlock.await()
                    }
                }
                awaitGate(bStarted)
                assertTrue("B should be installed and active", registry.isActive(25L, "B"))

                registry.onAfterTokenClaim = {
                    if (registry.latestToken(25L)?.requestId == "C") {
                        tokenEntered.complete(Unit)
                        tokenGate.await()
                    }
                }

                val cDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(25L, "C")) {
                            cBlock.await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) {
                    while (!registry.isPending(25L, "C")) { yield() }
                }
                withTimeout(2000) { tokenEntered.await() }

                val cancelDone = async(Dispatchers.Default) {
                    withTimeout(2000) {
                        registry.cancelAndJoin(25L, "C", "cancel-C")
                    }
                }

                withTimeout(2000) {
                    while (registry.isPending(25L, "C")) { yield() }
                }
                assertFalse("cancelAndJoin should be blocked on C's completion",
                    cancelDone.isCompleted)

                tokenGate.complete(Unit)

                val cResult = withTimeout(2000) { cDeferred.await() }
                assertEquals("cancelled", cResult)

                assertTrue(withTimeout(2000) { cancelDone.await() })

                assertFalse("B should not be active", registry.isActive(25L, "B"))
                assertNull("No active session should remain",
                    registry.activeSession(25L))
                assertNull("No lifecycle token should remain",
                    registry.latestToken(25L))
            } finally {
                bBlock.complete(Unit)
                tokenGate.complete(Unit)
                cBlock.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `lifecycle token is cleaned up after natural session completion`() {
        runBlocking {
            val scope = testScope()
            val release = CompletableDeferred<Unit>()
            try {
                val registry = GenerationSessionRegistry()
                val session = withTimeout(2000) {
                    registry.start(scope, snapshot(26L, "A")) { release.await() }
                }
                assertNotNull("A should have lifecycle token while active",
                    registry.latestToken(26L))

                release.complete(Unit)
                withTimeout(2000) { session.job.join() }

                assertNull("lifecycle token should be removed after completion",
                    registry.latestToken(26L))
                assertNull("active session should be null",
                    registry.activeSession(26L))
            } finally {
                release.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `completed predecessor lifecycle token does not leak into successor`() {
        runBlocking {
            val scope = testScope()
            val aRelease = CompletableDeferred<Unit>()
            val bRelease = CompletableDeferred<Unit>()
            try {
                val registry = GenerationSessionRegistry()

                val aSession = withTimeout(2000) {
                    registry.start(scope, snapshot(27L, "A")) { aRelease.await() }
                }
                aRelease.complete(Unit)
                withTimeout(2000) { aSession.job.join() }

                assertNull("A lifecycle token should be removed after completion",
                    registry.latestToken(27L))

                val bSession = withTimeout(2000) {
                    registry.start(scope, snapshot(27L, "B")) { bRelease.await() }
                }

                assertNotNull("B should be active", registry.activeSession(27L))
                assertEquals("B should be the lifecycle head", "B",
                    registry.latestToken(27L)?.requestId)
                assertNull("B's predecessor should be null (A was cleaned up)",
                    registry.latestToken(27L)?.predecessor)

                bRelease.complete(Unit)
                withTimeout(2000) { bSession.job.join() }

                assertNull("B lifecycle token should be removed after completion",
                    registry.latestToken(27L))
                assertNull("No active session should remain",
                    registry.activeSession(27L))
            } finally {
                aRelease.complete(Unit)
                bRelease.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `atomic registration preserves complete predecessor lineage`() {
        runBlocking {
            val scope = testScope()
            val bBlock = CompletableDeferred<Unit>()
            val bStarted = CountDownLatch(1)
            try {
                val registry = GenerationSessionRegistry()

                val bSession = withTimeout(2000) {
                    registry.start(scope, snapshot(28L, "B")) {
                        bStarted.countDown()
                        bBlock.await()
                    }
                }
                awaitGate(bStarted)

                val cDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(28L, "C")) {
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) {
                    while (registry.latestToken(28L)?.requestId != "C") { yield() }
                }

                val cToken = registry.latestToken(28L)
                assertNotNull("C should be lifecycle head", cToken)
                assertEquals("C", cToken!!.requestId)
                assertNotNull("C should have predecessor B", cToken.predecessor)
                assertEquals("B", cToken.predecessor!!.requestId)
                assertNull("C predecessor should have no further predecessor",
                    cToken.predecessor!!.predecessor)

                val dDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(28L, "D")) {
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) {
                    while (registry.latestToken(28L)?.requestId != "D") { yield() }
                }

                val dToken = registry.latestToken(28L)
                assertNotNull("D should be lifecycle head", dToken)
                assertEquals("D", dToken!!.requestId)
                assertNotNull("D should have predecessor C", dToken.predecessor)
                assertEquals("C", dToken.predecessor!!.requestId)
                assertNotNull("D should have C's predecessor B via chain",
                    dToken.predecessor!!.predecessor)
                assertEquals("B", dToken.predecessor!!.predecessor!!.requestId)

                withTimeout(2000) { registry.cancelAndJoin(28L, "D", "cancel-D") }

                assertFalse("B should not be active", registry.isActive(28L, "B"))
                assertNull("No lifecycle head should remain", registry.latestToken(28L))
            } finally {
                bBlock.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `direct start coroutine cancellation of pending C settles installed predecessor B`() {
        runBlocking {
            val scope = testScope()
            val bBlock = CompletableDeferred<Unit>()
            val bStarted = CountDownLatch(1)
            val tokenGate = CompletableDeferred<Unit>()
            val tokenEntered = CompletableDeferred<Unit>()
            try {
                val registry = GenerationSessionRegistry()

                val bDone = async(Dispatchers.Default) {
                    registry.start(scope, snapshot(29L, "B")) {
                        bStarted.countDown()
                        bBlock.await()
                    }
                }
                awaitGate(bStarted)
                assertTrue("B should be active", registry.isActive(29L, "B"))

                registry.onAfterTokenClaim = {
                    if (registry.latestToken(29L)?.requestId == "C") {
                        tokenEntered.complete(Unit)
                        tokenGate.await()
                    }
                }

                val cDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(29L, "C")) {
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) {
                    while (!registry.isPending(29L, "C")) { yield() }
                }
                withTimeout(2000) { tokenEntered.await() }

                cDeferred.cancel()
                withTimeout(2000) { cDeferred.join() }

                assertFalse("C should not be pending",
                    registry.isPending(29L, "C"))
                assertNull("C should have no active session",
                    registry.activeSession(29L))
                assertNull("No lifecycle head should remain",
                    registry.latestToken(29L))

                tokenGate.complete(Unit)

                assertFalse("B should not be active (settled via predecessor chain after direct cancel)",
                    registry.isActive(29L, "B"))
            } finally {
                bBlock.complete(Unit)
                tokenGate.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `direct cancellation of pending C awaits already-CANCELLED predecessor B`() {
        runBlocking {
            val scope = testScope()
            val convId = 35L
            val tokenGate = CompletableDeferred<Unit>()
            val bClaimed = CompletableDeferred<Unit>()
            val cClaimed = CompletableDeferred<Unit>()
            val bBlockEntered = CountDownLatch(1)
            val cBlockEntered = CountDownLatch(1)
            val registry = GenerationSessionRegistry()
            try {
                registry.onAfterTokenClaim = {
                    when (registry.latestToken(convId)?.requestId) {
                        "B" -> {
                            bClaimed.complete(Unit)
                            tokenGate.await()
                        }
                        "C" -> {
                            cClaimed.complete(Unit)
                            tokenGate.await()
                        }
                    }
                }

                val bDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(convId, "B")) {
                            bBlockEntered.countDown()
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) {
                    while (!registry.isPending(convId, "B")) { yield() }
                }
                withTimeout(2000) { bClaimed.await() }

                val cDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(convId, "C")) {
                            cBlockEntered.countDown()
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) {
                    while (registry.latestToken(convId)?.requestId != "C") { yield() }
                }
                withTimeout(2000) { cClaimed.await() }

                assertFalse("B should no longer be pending",
                    registry.isPending(convId, "B"))
                assertEquals("C should be lifecycle head", "C",
                    registry.latestToken(convId)?.requestId)

                val bToken = registry.latestToken(convId)!!.predecessor
                assertNotNull("C should have predecessor B", bToken)
                assertEquals("B", bToken!!.requestId)

                assertEquals("B should be CANCELLED after C superseded it",
                    GenerationSessionRegistry.PendingStart.State.CANCELLED, bToken.state())

                cDeferred.cancel()
                yield()

                assertFalse("C coroutine should not be completed while token gate is held",
                    cDeferred.isCompleted)
                assertFalse("B completed signal should not be completed",
                    bToken.completed.isCompleted)

                tokenGate.complete(Unit)

                val bResult = withTimeout(2000) { bDeferred.await() }
                assertEquals("B start should end with CancellationException", "cancelled", bResult)

                withTimeout(2000) { cDeferred.join() }
                assertTrue("C should have been cancelled", cDeferred.isCancelled)

                assertEquals(1, bBlockEntered.count)
                assertEquals(1, cBlockEntered.count)

                assertNull("No lifecycle head should remain",
                    registry.latestToken(convId))
                assertNull("No active session should remain",
                    registry.activeSession(convId))
            } finally {
                tokenGate.complete(Unit)
                registry.onAfterTokenClaim = {}
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `direct cancellation during STARTING pre-install window removes tentative session and settles predecessor`() {
        runBlocking {
            val scope = testScope()
            val aBlock = CompletableDeferred<Unit>()
            val aStarted = CountDownLatch(1)
            val cBeforeInstall = CompletableDeferred<Unit>()
            val cGate = CompletableDeferred<Unit>()
            try {
                val registry = GenerationSessionRegistry()

                val aDone = async(Dispatchers.Default) {
                    registry.start(scope, snapshot(30L, "A")) {
                        aStarted.countDown()
                        aBlock.await()
                    }
                }
                awaitGate(aStarted)
                assertTrue("A should be active", registry.isActive(30L, "A"))

                registry.onBeforeInstall = {
                    cBeforeInstall.complete(Unit)
                    cGate.await()
                }

                val cDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(30L, "C")) {
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) { cBeforeInstall.await() }
                assertTrue("C should be STARTING in pre-install window",
                    registry.isStarting(30L, "C"))
                assertTrue("C should have published session",
                    registry.hasSessionPublished(30L, "C"))

                val tentativeSession = registry.latestToken(30L)!!.publishedSession.get()!!

                cDeferred.cancel()
                withTimeout(2000) { cDeferred.join() }

                assertTrue("Tentative session job should be completed",
                    tentativeSession.job.isCompleted)
                assertTrue("Tentative session job should be cancelled",
                    tentativeSession.job.isCancelled)

                assertFalse("C should not have published session after cleanup",
                    registry.hasSessionPublished(30L, "C"))
                assertNull("C should have no active session",
                    registry.activeSession(30L))

                cGate.complete(Unit)

                assertFalse("A should be cancelled (settled via predecessor chain)",
                    registry.isActive(30L, "A"))
                assertNull("No lifecycle head should remain",
                    registry.latestToken(30L))
            } finally {
                aBlock.complete(Unit)
                cGate.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `concurrent repeated request-aware cancelAndJoin of same pending request`() {
        runBlocking {
            val scope = testScope()
            val bBlock = CompletableDeferred<Unit>()
            val bStarted = CountDownLatch(1)
            val tokenGate = CompletableDeferred<Unit>()
            val tokenEntered = CompletableDeferred<Unit>()
            try {
                val registry = GenerationSessionRegistry()

                val bDone = async(Dispatchers.Default) {
                    registry.start(scope, snapshot(31L, "B")) {
                        bStarted.countDown()
                        bBlock.await()
                    }
                }
                awaitGate(bStarted)
                assertTrue("B should be active", registry.isActive(31L, "B"))

                registry.onAfterTokenClaim = {
                    if (registry.latestToken(31L)?.requestId == "C") {
                        tokenEntered.complete(Unit)
                        tokenGate.await()
                    }
                }

                val cDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(31L, "C")) {
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) {
                    while (!registry.isPending(31L, "C")) { yield() }
                }
                withTimeout(2000) { tokenEntered.await() }

                val cancel1 = async(Dispatchers.Default) {
                    registry.cancelAndJoin(31L, "C", "cancel-C-1")
                }
                val cancel2 = async(Dispatchers.Default) {
                    registry.cancelAndJoin(31L, "C", "cancel-C-2")
                }

                withTimeout(5000) {
                    while (registry.isPending(31L, "C")) { yield() }
                }

                tokenGate.complete(Unit)

                val cResult = withTimeout(5000) { cDeferred.await() }
                assertEquals("cancelled", cResult)

                assertTrue(withTimeout(5000) { cancel1.await() })
                assertTrue(withTimeout(5000) { cancel2.await() })

                assertFalse("B should not be active", registry.isActive(31L, "B"))
                assertNull("No lifecycle head should remain", registry.latestToken(31L))
            } finally {
                bBlock.complete(Unit)
                tokenGate.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `concurrent repeated request-aware cancellation of already-CANCELLED C with incomplete B`() {
        runBlocking {
            val scope = testScope()
            val convId = 39L
            val tokenGate = CompletableDeferred<Unit>()
            val bClaimed = CompletableDeferred<Unit>()
            val cClaimed = CompletableDeferred<Unit>()
            val bothCancelReservations = CompletableDeferred<Unit>()
            val releaseCancelReservations = CompletableDeferred<Unit>()
            val cancelReservationCount = java.util.concurrent.atomic.AtomicInteger()
            val bBlockEntered = CountDownLatch(1)
            val cBlockEntered = CountDownLatch(1)
            val registry = GenerationSessionRegistry()
            try {
                registry.onAfterTokenClaim = {
                    when (registry.latestToken(convId)?.requestId) {
                        "B" -> {
                            bClaimed.complete(Unit)
                            tokenGate.await()
                        }
                        "C" -> {
                            cClaimed.complete(Unit)
                            tokenGate.await()
                        }
                    }
                }
                registry.onAfterIntentReservation = {
                    if (cancelReservationCount.incrementAndGet() == 2) {
                        bothCancelReservations.complete(Unit)
                    }
                    releaseCancelReservations.await()
                }

                val bDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(convId, "B")) {
                            bBlockEntered.countDown()
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) {
                    while (!registry.isPending(convId, "B")) { yield() }
                }
                withTimeout(2000) { bClaimed.await() }

                val cDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(convId, "C")) {
                            cBlockEntered.countDown()
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) {
                    while (registry.latestToken(convId)?.requestId != "C") { yield() }
                }
                withTimeout(2000) { cClaimed.await() }

                val bToken = registry.latestToken(convId)!!.predecessor!!
                assertEquals("CANCELLED", GenerationSessionRegistry.PendingStart.State.CANCELLED, bToken.state())

                cDeferred.cancel()
                yield()
                assertFalse("C cleanup should be blocked on B",
                    cDeferred.isCompleted)
                assertFalse("B should not have completed",
                    bToken.completed.isCompleted)

                val cancel1 = async(Dispatchers.Default) {
                    registry.cancelAndJoin(convId, "C", "cancel-C-1")
                }
                val cancel2 = async(Dispatchers.Default) {
                    registry.cancelAndJoin(convId, "C", "cancel-C-2")
                }

                withTimeout(2000) { bothCancelReservations.await() }
                assertFalse("cancel1 should be blocked", cancel1.isCompleted)
                assertFalse("cancel2 should be blocked", cancel2.isCompleted)

                releaseCancelReservations.complete(Unit)
                tokenGate.complete(Unit)

                val bResult = withTimeout(5000) { bDeferred.await() }
                assertEquals("cancelled", bResult)

                withTimeout(5000) { cDeferred.join() }
                assertTrue("C should have been cancelled", cDeferred.isCancelled)

                assertTrue(withTimeout(5000) { cancel1.await() })
                assertTrue(withTimeout(5000) { cancel2.await() })

                assertEquals(1, bBlockEntered.count)
                assertEquals(1, cBlockEntered.count)
                assertNull("No lifecycle head should remain", registry.latestToken(convId))
                assertNull("No active session should remain", registry.activeSession(convId))
            } finally {
                tokenGate.complete(Unit)
                releaseCancelReservations.complete(Unit)
                registry.onAfterTokenClaim = {}
                registry.onAfterIntentReservation = {}
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `conversation-wide cancelAndJoin awaits CANCELLED head not yet completed`() {
        runBlocking {
            val scope = testScope()
            val tokenGate = CompletableDeferred<Unit>()
            val tokenEntered = CompletableDeferred<Unit>()
            try {
                val registry = GenerationSessionRegistry()

                registry.onAfterTokenClaim = {
                    if (registry.latestToken(1L)?.requestId == "C") {
                        tokenEntered.complete(Unit)
                        tokenGate.await()
                    }
                }

                val cDeferred = async(Dispatchers.Default) {
                    try {
                        registry.start(scope, snapshot(1L, "C")) {
                            CompletableDeferred<Unit>().await()
                        }
                        "installed"
                    } catch (_: CancellationException) { "cancelled" }
                }

                withTimeout(2000) {
                    while (!registry.isPending(1L, "C")) { yield() }
                }
                withTimeout(2000) { tokenEntered.await() }

                assertNull("predecessor should be null",
                    registry.latestToken(1L)?.predecessor)

                val convCancel = async(Dispatchers.Default) {
                    withTimeout(5000) {
                        registry.cancelAndJoin(1L, "cancel-C")
                    }
                }

                withTimeout(2000) {
                    while (registry.isPending(1L, "C")) { yield() }
                }
                assertFalse("convCancel should block on C.completed",
                    convCancel.isCompleted)

                tokenGate.complete(Unit)

                assertTrue(withTimeout(5000) { convCancel.await() })

                assertNull("No lifecycle head should remain",
                    registry.latestToken(1L))

                withTimeout(2000) { cDeferred.await() }
            } finally {
                tokenGate.complete(Unit)
                scope.cancel()
                withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    @Test
    fun `start during deletion ownership refuses and installs nothing`() = runBlocking {
        val scope = testScope()
        val release = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()
            assertTrue("first deletion claim should succeed",
                registry.claimDeletionOwnership(21L, "delete-21"))

            val startResult = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(21L, "r21")) { release.await() }
                    "installed"
                } catch (e: CancellationException) { "cancelled" }
            }

            assertEquals("cancelled", withTimeout(2000) { startResult.await() })
            assertNull("no session must install while deletion owns the conversation",
                registry.activeSession(21L))
            assertNull("no lifecycle token must remain after refused start",
                registry.latestToken(21L))
            assertFalse("refused request must not be pending",
                registry.isPending(21L, "r21"))

            registry.releaseDeletionOwnership(21L, "delete-21")

            val session = withTimeout(2000) {
                registry.start(scope, snapshot(21L, "r21b")) { release.await() }
            }
            assertTrue("start must succeed after ownership release",
                registry.isActive(21L, "r21b"))
            session.job.cancel()
            session.job.join()
        } finally {
            release.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `deletion ownership is exclusive per conversation and releases on exact reason`() = runBlocking {
        val registry = GenerationSessionRegistry()
        assertTrue(registry.claimDeletionOwnership(31L, "first"))
        assertFalse("second claim must be refused",
            registry.claimDeletionOwnership(31L, "second"))
        assertTrue("other conversations must be independent",
            registry.claimDeletionOwnership(32L, "other"))
        assertEquals("first", registry.deletionOwner(31L))

        registry.releaseDeletionOwnership(31L, "wrong-reason")
        assertEquals("release must require the exact owning reason",
            "first", registry.deletionOwner(31L))

        registry.releaseDeletionOwnership(31L, "first")
        assertNull(registry.deletionOwner(31L))
        assertTrue("claim must succeed after release",
            registry.claimDeletionOwnership(31L, "third"))
    }

    @Test
    fun `start gated before exact conversation barrier during deletion ownership refuses at the barrier`() = runBlocking {
        val scope = testScope()
        val tokenGate = CompletableDeferred<Unit>()
        val tokenEntered = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()
            registry.onAfterTokenClaim = {
                if (registry.latestToken(1L)?.requestId == "target") {
                    tokenEntered.complete(Unit)
                    tokenGate.await()
                }
            }

            val target = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(1L, "target")) {
                        CompletableDeferred<Unit>().await()
                    }
                    "installed"
                } catch (e: CancellationException) { "cancelled:${e.message}" }
            }
            withTimeout(2000) {
                while (!registry.isPending(1L, "target")) { yield() }
            }
            withTimeout(2000) { tokenEntered.await() }

            assertTrue("claim must succeed while target waits before the exact barrier",
                registry.claimDeletionOwnership(1L, "delete-1"))

            tokenGate.complete(Unit)

            assertTrue("target must be refused once it reaches the barrier",
                withTimeout(2000) { target.await() }.startsWith("cancelled:"))
            assertNull("target must not install", registry.activeSession(1L))
            assertNull("target lifecycle token must be gone", registry.latestToken(1L))
            registry.releaseDeletionOwnership(1L, "delete-1")
        } finally {
            tokenGate.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `non-cancellation failure before install releases the slot and the token`() = runBlocking {
        val scope = testScope()
        val release = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()
            registry.onBeforeInstall = { error("install precondition failed") }

            val startResult = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(41L, "r41")) { release.await() }
                    "installed"
                } catch (e: IllegalStateException) { "failed:${e.message}" }
            }

            assertEquals("failed:install precondition failed",
                withTimeout(2000) { startResult.await() })
            assertNull("slot must be released after failed start",
                registry.activeSession(41L))
            assertNull("token must be released after failed start",
                registry.latestToken(41L))
            assertFalse("failed start must not leave a busy session",
                registry.hasActiveSession(41L))
            assertTrue("failed start must not be exposed as active",
                registry.activeConversationIds().isEmpty())

            registry.onBeforeInstall = {}
            val session = withTimeout(2000) {
                registry.start(scope, snapshot(41L, "r41b")) { release.await() }
            }
            assertTrue("subsequent start must install normally",
                registry.isActive(41L, "r41b"))
            session.job.cancel()
            session.job.join()
        } finally {
            release.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancelled session is not reported busy while cleanup is still running`() = runBlocking {
        val scope = testScope()
        val cleanupRelease = CompletableDeferred<Unit>()
        val entered = CountDownLatch(1)
        try {
            val registry = GenerationSessionRegistry()
            val session = withTimeout(2000) {
                registry.start(scope, snapshot(51L, "r51")) {
                    entered.countDown()
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        withContext(NonCancellable) { cleanupRelease.await() }
                    }
                }
            }
            awaitGate(entered)
            assertTrue("session must be busy before cancellation",
                registry.isActive(51L, "r51"))

            assertTrue("cancel must succeed while busy",
                registry.cancel(51L, "stop"))
            assertFalse("cancel must be a no-op once already cancelled",
                registry.cancel(51L, "stop-again"))

            assertFalse("cancelled session must not report active while cleanup runs",
                registry.isActive(51L, "r51"))
            assertNull("cancelled session must not be exposed",
                registry.activeSession(51L))
            assertFalse("cancelled session must not report busy",
                registry.hasActiveSession(51L))
            assertTrue("cancelled session must not appear in active ids",
                registry.activeConversationIds().isEmpty())

            cleanupRelease.complete(Unit)
            withTimeout(2000) { session.job.join() }
            assertNull("completed session must be removed", registry.activeSession(51L))
        } finally {
            cleanupRelease.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `old stripe collision pair 1 and 17 start independently`() = runBlocking {
        val scope = testScope()
        val installCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val aHeld = CompletableDeferred<Unit>()
        val aGate = CompletableDeferred<Unit>()
        val aStarted = CountDownLatch(1)
        val aHold = CompletableDeferred<Unit>()
        val bStarted = CountDownLatch(1)
        val bHold = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()
            registry.onBeforeInstall = {
                if (installCalls.incrementAndGet() == 1) {
                    aHeld.complete(Unit)
                    aGate.await()
                }
            }

            val aDeferred = async(Dispatchers.Default) {
                registry.start(scope, snapshot(1L, "A1")) {
                    aStarted.countDown()
                    aHold.await()
                }
            }
            withTimeout(2000) { aHeld.await() }

            val bDeferred = async(Dispatchers.Default) {
                registry.start(scope, snapshot(17L, "B17")) {
                    bStarted.countDown()
                    bHold.await()
                }
            }

            awaitGate(bStarted)
            assertTrue("conversation 17 must install while conversation 1 holds its exact lock",
                registry.isActive(17L, "B17"))

            aGate.complete(Unit)
            awaitGate(aStarted)
            withTimeout(2000) { aDeferred.await(); bDeferred.await() }

            assertTrue(registry.isActive(1L, "A1"))
            assertTrue(registry.isActive(17L, "B17"))
            // Installed sessions must not retain lock entries.
            assertEquals(0, registry.conversationLockRegistrySizeForTests())
        } finally {
            aGate.complete(Unit)
            aHold.complete(Unit)
            bHold.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `same conversation starts remain serialized with predecessor settlement`() = runBlocking {
        val scope = testScope()
        val aHold = CompletableDeferred<Unit>()
        val aCleanupStarted = CompletableDeferred<Unit>()
        val aCleanupRelease = CompletableDeferred<Unit>()
        val aStarted = CountDownLatch(1)
        val bHold = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()
            val aDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(100L, "A")) {
                    aStarted.countDown()
                    try {
                        aHold.await()
                    } finally {
                        aCleanupStarted.complete(Unit)
                        withContext(NonCancellable) {
                            aCleanupRelease.await()
                        }
                    }
                }
            }
            awaitGate(aStarted)

            val bDone = async(Dispatchers.Default) {
                registry.start(scope, snapshot(100L, "B")) {
                    bHold.await()
                }
            }
            withTimeout(2000) { aCleanupStarted.await() }
            assertFalse("B must not be active while A cleanup is held",
                registry.isActive(100L, "B"))

            aCleanupRelease.complete(Unit)
            withTimeout(5000) { aDone.await(); bDone.await() }
            withTimeout(2000) {
                while (!registry.isActive(100L, "B")) { yield() }
            }
            assertTrue(registry.isActive(100L, "B"))
            assertFalse(registry.isActive(100L, "A"))
            assertNull(registry.latestToken(100L)?.let {
                if (it.requestId == "A") it else null
            })
            assertEquals("B", registry.activeSession(100L)?.requestId)
        } finally {
            aHold.complete(Unit)
            aCleanupRelease.complete(Unit)
            bHold.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `fallback cancel path shares exact conversation lock and leaves clean state`() = runBlocking {
        val scope = testScope()
        val bStarted = CountDownLatch(1)
        val bHold = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()
            // No-token fallback with no session must return false and not leak.
            val emptyResults = (1..10).map {
                async(Dispatchers.Default) {
                    withTimeout(2000) { registry.cancelAndJoin(600L, "empty-$it") }
                }
            }.map { withTimeout(2000) { it.await() } }
            assertTrue(emptyResults.all { !it })
            assertEquals(0, registry.conversationLockRegistrySizeForTests())

            // Concurrent fallback + start for the same conversation must settle
            // without separate lock instances and without residue.
            val fallback = async(Dispatchers.Default) {
                withTimeout(5000) { registry.cancelAndJoin(601L, "race-fallback") }
            }
            val bDeferred = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(601L, "B")) {
                        bStarted.countDown()
                        bHold.await()
                    }
                    "installed"
                } catch (_: CancellationException) { "cancelled" }
            }
            val fallbackResult = withTimeout(5000) { fallback.await() }
            // Either ordering is clean: fallback-first returns false and B installs,
            // or start-first installs then fallback cancels it.
            val bResult = withTimeout(5000) { bDeferred.await() }
            if (!fallbackResult) {
                assertEquals("installed", bResult)
                assertTrue(registry.isActive(601L, "B"))
                bHold.complete(Unit)
                withTimeout(2000) { registry.activeSession(601L)?.job?.join() }
            } else {
                assertTrue(bResult == "installed" || bResult == "cancelled")
            }
            // A completed fallback cancel may settle B after start() returns "installed".
            withTimeout(2000) {
                while (registry.latestToken(601L) != null || registry.activeSession(601L) != null) {
                    yield()
                }
            }
            assertNull(registry.latestToken(601L))
            assertNull(registry.activeSession(601L))
            assertEquals(0, registry.conversationLockRegistrySizeForTests())
        } finally {
            bHold.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `same conversation lock serializes waiter and releases entry after settlement`() = runBlocking {
        val scope = testScope()
        val installCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val aHeld = CompletableDeferred<Unit>()
        val aGate = CompletableDeferred<Unit>()
        val bHold = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()
            registry.onBeforeInstall = {
                if (installCalls.incrementAndGet() == 1) {
                    aHeld.complete(Unit)
                    aGate.await()
                }
            }
            val aDeferred = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(700L, "A")) {
                        CompletableDeferred<Unit>().await()
                    }
                    "installed"
                } catch (_: CancellationException) { "cancelled" }
            }
            withTimeout(2000) { aHeld.await() }
            assertEquals(1, registry.conversationLockRegistrySizeForTests())

            val bDeferred = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(700L, "B")) {
                        bHold.await()
                    }
                    "installed"
                } catch (_: CancellationException) { "cancelled" }
            }
            withTimeout(2000) {
                while (!registry.isPending(700L, "B") && registry.latestToken(700L)?.requestId != "B") {
                    yield()
                }
            }
            // Waiter must share one entry, never two, and must not be removed.
            assertEquals(1, registry.conversationLockRegistrySizeForTests())
            assertFalse("B must not install while A holds the exact lock",
                bDeferred.isCompleted)

            aGate.complete(Unit)
            val aResult = withTimeout(5000) { aDeferred.await() }
            assertEquals("cancelled", aResult)
            withTimeout(5000) { bDeferred.await() }
            withTimeout(2000) {
                while (!registry.isActive(700L, "B")) { yield() }
            }
            assertTrue(registry.isActive(700L, "B"))
            bHold.complete(Unit)
            withTimeout(2000) { registry.activeSession(700L)?.job?.join() }
            withTimeout(2000) {
                while (registry.conversationLockRegistrySizeForTests() != 0) { yield() }
            }
            assertEquals(0, registry.conversationLockRegistrySizeForTests())
            assertNull(registry.latestToken(700L))
        } finally {
            aGate.complete(Unit)
            bHold.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancelled lock waiter releases refcount and later start works`() = runBlocking {
        val scope = testScope()
        val installCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val aHeld = CompletableDeferred<Unit>()
        val aGate = CompletableDeferred<Unit>()
        val cEntered = CountDownLatch(1)
        val cHold = CompletableDeferred<Unit>()
        try {
            val registry = GenerationSessionRegistry()
            registry.onBeforeInstall = {
                if (installCalls.incrementAndGet() == 1) {
                    aHeld.complete(Unit)
                    aGate.await()
                }
            }
            val aDeferred = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(701L, "A")) {
                        CompletableDeferred<Unit>().await()
                    }
                    "installed"
                } catch (_: CancellationException) { "cancelled" }
            }
            withTimeout(2000) { aHeld.await() }

            val bDeferred = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(701L, "B")) {
                        CompletableDeferred<Unit>().await()
                    }
                    "installed"
                } catch (_: CancellationException) { "cancelled" }
            }
            withTimeout(2000) {
                while (registry.latestToken(701L)?.requestId != "B") { yield() }
            }
            assertEquals(1, registry.conversationLockRegistrySizeForTests())

            bDeferred.cancel()
            yield()
            assertFalse("cancelled waiter must remain blocked on predecessor while owner holds gate",
                bDeferred.isCompleted)

            aGate.complete(Unit)
            withTimeout(5000) { bDeferred.join() }
            assertTrue(bDeferred.isCancelled)
            withTimeout(2000) { aDeferred.await() }
            withTimeout(2000) {
                while (registry.conversationLockRegistrySizeForTests() != 0) { yield() }
            }
            assertEquals(0, registry.conversationLockRegistrySizeForTests())
            assertNull(registry.latestToken(701L))
            assertNull(registry.activeSession(701L))

            registry.onBeforeInstall = {}
            val cDeferred = async(Dispatchers.Default) {
                registry.start(scope, snapshot(701L, "C")) {
                    cEntered.countDown()
                    cHold.await()
                }
            }
            awaitGate(cEntered)
            assertTrue("subsequent start after full removal must work",
                registry.isActive(701L, "C"))
            cHold.complete(Unit)
            withTimeout(2000) { cDeferred.await() }
            assertEquals(0, registry.conversationLockRegistrySizeForTests())
        } finally {
            aGate.complete(Unit)
            cHold.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    private fun testScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun snapshot(conversationId: Long, requestId: String): GenerationRequestSnapshot =
        GenerationRequestSnapshot(
            requestId = requestId,
            conversationId = conversationId,
            generationModeKey = "TEST",
            selectedModelId = null,
            selectedModelPath = null,
            settings = com.projectnuke.fusion.model.GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "test",
            rawUserText = "test",
            createdAt = 0L,
        )

    companion object {
        private fun awaitGate(gate: CountDownLatch) {
            assertTrue("synchronization gate was not reached", gate.await(5, TimeUnit.SECONDS))
        }
    }
}
