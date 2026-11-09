package com.projectnuke.fusion.chat

import com.projectnuke.fusion.llm.GenerationRequestKey
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationCancellationOwnershipTest {

    @Test
    fun `concurrent stop callers settle one unregistered request idempotently`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val registry = GenerationSessionRegistry()
        val sessionEntered = CompletableDeferred<Unit>()
        val bothStopsAtReservation = CompletableDeferred<Unit>()
        val releaseReservations = CompletableDeferred<Unit>()
        val reservationEntrants = AtomicInteger()
        val conversationId = 57L
        val requestId = "concurrent-stop-before-register"
        registry.onBeforeIntentReservation = {
            if (reservationEntrants.incrementAndGet() == 2) bothStopsAtReservation.complete(Unit)
            releaseReservations.await()
        }
        try {
            val session = registry.start(scope, snapshot(conversationId, requestId)) {
                sessionEntered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            sessionEntered.await()

            val first = async(Dispatchers.Default) { registry.stopAndJoin(conversationId, requestId) }
            val second = async(Dispatchers.Default) { registry.stopAndJoin(conversationId, requestId) }
            withTimeout(2_000) { bothStopsAtReservation.await() }
            releaseReservations.complete(Unit)

            assertTrue(withTimeout(2_000) { first.await() })
            assertTrue(withTimeout(2_000) { second.await() })
            session.job.join()
            assertEquals(0, registry.registeredConversationCount())
            assertEquals(0, registry.recordedIntentCount())
            assertFalse("retired exact request is no longer stoppable", registry.stopAndJoin(conversationId, requestId))
        } finally {
            releaseReservations.complete(Unit)
            registry.onBeforeIntentReservation = {}
            scope.cancel()
        }
    }

    @Test
    fun `user stop before native registration settles the reserved request once`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val registry = GenerationSessionRegistry()
        val entered = CompletableDeferred<Unit>()
        val conversationId = 56L
        val requestId = "request-before-native-registration"
        val key = GenerationRequestKey(conversationId, requestId)
        try {
            val session = registry.start(scope, snapshot(conversationId, requestId)) {
                entered.complete(Unit)
                while (registry.cancellationController.intentFor(key) == null) yield()
            }
            entered.await()

            assertTrue(withTimeout(2_000) { registry.stopAndJoin(conversationId, requestId) })
            session.job.join()
            assertEquals(0, registry.registeredConversationCount())
            assertEquals(0, registry.recordedIntentCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `termination precedence only allows structural deletion promotion`() {
        assertTrue(
            com.projectnuke.fusion.llm.mayPromoteTerminationIntent(
                com.projectnuke.fusion.llm.GenerationTerminationIntent.USER_STOP,
                com.projectnuke.fusion.llm.GenerationTerminationIntent.CONVERSATION_DELETE,
            )
        )
        assertFalse(
            com.projectnuke.fusion.llm.mayPromoteTerminationIntent(
                com.projectnuke.fusion.llm.GenerationTerminationIntent.CONVERSATION_DELETE,
                com.projectnuke.fusion.llm.GenerationTerminationIntent.USER_STOP,
            )
        )
        assertFalse(
            com.projectnuke.fusion.llm.mayPromoteTerminationIntent(
                com.projectnuke.fusion.llm.GenerationTerminationIntent.SUPERSEDED,
                com.projectnuke.fusion.llm.GenerationTerminationIntent.USER_STOP,
            )
        )
    }

    @Test
    fun `deletion promotes user stop and rejects an unclaimed partial`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val hold = CompletableDeferred<Unit>()
        val reserved = CompletableDeferred<Unit>()
        val releaseReservation = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry()
        val conversationId = 53L
        val requestId = "request-a"
        val key = GenerationRequestKey(conversationId, requestId)
        try {
            registry.start(scope, snapshot(conversationId, requestId)) { hold.await() }
            registry.cancellationController.recordIntent(
                key,
                com.projectnuke.fusion.llm.GenerationTerminationIntent.USER_STOP,
            )
            assertTrue(registry.canAdoptUserStopPartial(conversationId, requestId))
            assertTrue(registry.claimDeletionOwnership(conversationId, "delete"))
            registry.onAfterIntentReservation = {
                reserved.complete(Unit)
                releaseReservation.await()
            }

            val deletion = async(Dispatchers.Default) {
                registry.settleForDeletion(conversationId, "delete")
            }
            reserved.await()
            assertEquals(
                com.projectnuke.fusion.llm.GenerationTerminationIntent.CONVERSATION_DELETE,
                registry.cancellationController.intentFor(key),
            )
            assertFalse(registry.canAdoptUserStopPartial(conversationId, requestId))
            releaseReservation.complete(Unit)
            hold.complete(Unit)
            assertTrue(withTimeout(2000) { deletion.await() })
            assertEquals(0, registry.registeredConversationCount())
            assertEquals(0, registry.recordedIntentCount())
            assertEquals(0, registry.partialOutputCount())
            assertEquals(0, registry.terminalAdoptionClaimCount())
        } finally {
            releaseReservation.complete(Unit)
            hold.complete(Unit)
            registry.onAfterIntentReservation = {}
            registry.releaseDeletionOwnership(conversationId, "delete")
            scope.cancel()
        }
    }

    @Test
    fun `deletion waits for an already claimed partial persistence`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val persistenceEntered = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry()
        val conversationId = 54L
        val requestId = "request-a"
        try {
            registry.start(scope, snapshot(conversationId, requestId)) {
                try {
                    CompletableDeferred<Unit>().await()
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        persistenceEntered.complete(Unit)
                        releasePersistence.await()
                    }
                    throw cancelled
                }
            }
            val key = GenerationRequestKey(conversationId, requestId)
            registry.cancellationController.recordIntent(
                key,
                com.projectnuke.fusion.llm.GenerationTerminationIntent.USER_STOP,
            )
            assertTrue(registry.claimUserStopPartial(conversationId, requestId))
            assertTrue(registry.claimDeletionOwnership(conversationId, "delete"))

            var committed = false
            val deletion = async(Dispatchers.Default) {
                registry.settleForDeletion(conversationId, "delete")
                committed = true
            }
            persistenceEntered.await()
            assertFalse("delete must wait for irreversible persistence", committed)
            releasePersistence.complete(Unit)
            assertTrue(withTimeout(2000) { deletion.await(); committed })
            assertEquals(0, registry.registeredConversationCount())
            assertEquals(0, registry.recordedIntentCount())
            assertEquals(0, registry.partialOutputCount())
            assertEquals(0, registry.terminalAdoptionClaimCount())
        } finally {
            releasePersistence.complete(Unit)
            registry.releaseDeletionOwnership(conversationId, "delete")
            scope.cancel()
        }
    }

    @Test
    fun `deletion joins an in-flight native user stop without double cancel`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val requestRelease = CompletableDeferred<Unit>()
        val nativeEntered = CompletableDeferred<Unit>()
        val nativeRelease = CompletableDeferred<Unit>()
        val nativeCalls = AtomicInteger(0)
        val registry = GenerationSessionRegistry()
        val conversationId = 55L
        val requestId = "request-a"
        val key = GenerationRequestKey(conversationId, requestId)
        try {
            registry.start(scope, snapshot(conversationId, requestId)) { requestRelease.await() }
            registry.cancellationController.registerCancellationHook(key, Any()) {
                nativeCalls.incrementAndGet()
                nativeEntered.complete(Unit)
                runBlocking { nativeRelease.await() }
            }
            val stop = async(Dispatchers.Default) {
                registry.stopAndJoin(conversationId, requestId)
            }
            nativeEntered.await()
            assertTrue(registry.claimDeletionOwnership(conversationId, "delete"))
            val deletion = async(Dispatchers.Default) {
                registry.settleForDeletion(conversationId, "delete")
            }
            nativeRelease.complete(Unit)
            requestRelease.complete(Unit)
            assertTrue(withTimeout(2000) { stop.await() })
            assertTrue(withTimeout(2000) { deletion.await() })
            assertEquals(1, nativeCalls.get())
            assertEquals(0, registry.registeredConversationCount())
            assertEquals(0, registry.recordedIntentCount())
            assertEquals(0, registry.partialOutputCount())
            assertEquals(0, registry.terminalAdoptionClaimCount())
        } finally {
            nativeRelease.complete(Unit)
            requestRelease.complete(Unit)
            registry.releaseDeletionOwnership(conversationId, "delete")
            scope.cancel()
        }
    }

    @Test
    fun `user stop invokes exact native hook once and joins normal settlement`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val release = CompletableDeferred<Unit>()
        val hookCount = AtomicInteger(0)
        val registry = GenerationSessionRegistry()
        val key = GenerationRequestKey(41L, "request-a")
        val owner = Any()
        try {
            val session = registry.start(scope, snapshot(41L, "request-a")) {
                release.await()
            }
            registry.cancellationController.registerCancellationHook(key, owner) {
                hookCount.incrementAndGet()
                release.complete(Unit)
            }

            assertTrue(withTimeout(2000) { registry.stopAndJoin(41L, "request-a") })
            assertEquals(1, hookCount.get())
            assertTrue(session.job.isCompleted)
            assertFalse(registry.isActive(41L, "request-a"))
            assertTrue(registry.stopAndJoin(41L, "request-a").not())
        } finally {
            registry.cancellationController.unregisterCancellationHook(key, owner)
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `stale request cannot cancel replacement after ownership unregister`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val aRelease = CompletableDeferred<Unit>()
        val bRelease = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry()
        val keyA = GenerationRequestKey(42L, "request-a")
        val keyB = GenerationRequestKey(42L, "request-b")
        val ownerA = Any()
        val ownerB = Any()
        val aCancelled = AtomicInteger(0)
        val bCancelled = AtomicInteger(0)
        try {
            registry.start(scope, snapshot(42L, "request-a")) { aRelease.await() }
            registry.cancellationController.registerCancellationHook(keyA, ownerA) { aCancelled.incrementAndGet() }
            registry.cancelAndJoin(42L, "superseded")
            assertEquals("internal cancellation must request native retirement before Job.cancel", 1, aCancelled.get())
            registry.cancellationController.unregisterCancellationHook(keyA, ownerA)

            registry.start(scope, snapshot(42L, "request-b")) { bRelease.await() }
            registry.cancellationController.registerCancellationHook(keyB, ownerB) { bCancelled.incrementAndGet() }

            assertFalse(registry.cancellationController.cancelProcess(keyA))
            assertTrue(registry.cancellationController.cancelProcess(keyB))
            assertEquals("a stale key cannot cancel its retired Conversation again", 1, aCancelled.get())
            assertEquals(1, bCancelled.get())
        } finally {
            aRelease.complete(Unit)
            bRelease.complete(Unit)
            registry.cancellationController.unregisterCancellationHook(keyA, ownerA)
            registry.cancellationController.unregisterCancellationHook(keyB, ownerB)
            scope.cancel()
        }
    }

    @Test
    fun `normal completion and repeated stale stops leave controller empty`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val release = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry()
        try {
            val session = registry.start(scope, snapshot(43L, "request-a")) { release.await() }
            assertFalse(registry.stopAndJoin(43L, "missing"))
            assertEquals(0, registry.recordedIntentCount())
            assertFalse(registry.stopAndJoin(43L, "request-b"))
            assertEquals(0, registry.recordedIntentCount())
            release.complete(Unit)
            session.job.join()
            assertEquals(0, registry.registeredConversationCount())
            assertEquals(0, registry.recordedIntentCount())
            assertEquals(0, registry.partialOutputCount())
            assertEquals(0, registry.terminalAdoptionClaimCount())
            assertFalse(registry.stopAndJoin(43L, "request-a"))
            assertEquals(0, registry.recordedIntentCount())
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `natural completion between stop observation and reservation leaves no intent`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val release = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry()
        try {
            val session = registry.start(scope, snapshot(50L, "request-a")) { release.await() }
            registry.onBeforeIntentReservation = {
                observed.complete(Unit)
                session.job.join()
            }
            val stop = async(Dispatchers.Default) { registry.stopAndJoin(50L, "request-a") }
            observed.await()
            release.complete(Unit)
            session.job.join()
            assertFalse(stop.await())
            assertEquals(0, registry.recordedIntentCount())
            assertEquals(0, registry.partialOutputCount())
            assertEquals(0, registry.terminalAdoptionClaimCount())
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `replacement between stop observation and reservation leaves no stale intent`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Unit>()
        val allowReservation = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry()
        try {
            val sessionA = registry.start(scope, snapshot(51L, "request-a")) { releaseA.await() }
            registry.onBeforeIntentReservation = {
                observed.complete(Unit)
                allowReservation.await()
            }
            val stop = async(Dispatchers.Default) { registry.stopAndJoin(51L, "request-a") }
            observed.await()
            releaseA.complete(Unit)
            sessionA.job.join()
            registry.onBeforeIntentReservation = {}
            val sessionB = registry.start(scope, snapshot(51L, "request-b")) { releaseB.await() }
            allowReservation.complete(Unit)
            assertFalse(stop.await())
            assertEquals(0, registry.recordedIntentCount())
            releaseB.complete(Unit)
            sessionB.job.join()
            assertEquals(0, registry.partialOutputCount())
            assertEquals(0, registry.terminalAdoptionClaimCount())
        } finally {
            releaseA.complete(Unit)
            releaseB.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `recoverable register cancellation is retryable and second native attempt settles`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val release = CompletableDeferred<Unit>()
        val sessionBodyEntered = CompletableDeferred<Unit>()
        val attempts = AtomicInteger(0)
        val registry = GenerationSessionRegistry(userStopSettlementTimeoutMs = 1000L)
        val key = GenerationRequestKey(52L, "request-a")
        try {
            registry.start(scope, snapshot(52L, "request-a")) {
                sessionBodyEntered.complete(Unit)
                release.await()
            }
            sessionBodyEntered.await()
            registry.cancellationController.recordIntent(key, com.projectnuke.fusion.llm.GenerationTerminationIntent.USER_STOP)
            registry.cancellationController.registerCancellationHook(key, Any()) {
                if (attempts.incrementAndGet() == 1) {
                    throw IllegalStateException("recoverable register cancel")
                }
                release.complete(Unit)
            }
            assertTrue(registry.stopAndJoin(52L, "request-a"))
            assertEquals(2, attempts.get())
            assertEquals(0, registry.registeredConversationCount())
            assertEquals(0, registry.recordedIntentCount())
            assertEquals(0, registry.partialOutputCount())
            assertEquals(0, registry.terminalAdoptionClaimCount())
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `stop before registration cancels only the exact later request`() {
        val controller = NativeConversationCancellationControllerForTest()
        val keyA = GenerationRequestKey(44L, "request-a")
        val keyB = GenerationRequestKey(44L, "request-b")
        controller.recordIntent(keyA, com.projectnuke.fusion.llm.GenerationTerminationIntent.USER_STOP)
        controller.register(keyB) { controller.bCancelled.incrementAndGet() }
        controller.register(keyA) { controller.aCancelled.incrementAndGet() }
        assertEquals(1, controller.aCancelled.get())
        assertEquals(0, controller.bCancelled.get())
    }

    @Test
    fun `register and stop race invokes one native cancel`() = runBlocking {
        val controller = NativeConversationCancellationControllerForTest()
        val key = GenerationRequestKey(45L, "request-a")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        controller.raw.onBeforeRegistrationStopCheck = {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        }
        controller.raw.recordIntent(key, com.projectnuke.fusion.llm.GenerationTerminationIntent.USER_STOP)
        val registration = async(Dispatchers.Default) {
            controller.raw.registerCancellationHook(key, Any()) {
                controller.cancelCount.incrementAndGet()
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(controller.raw.cancelProcess(key))
        release.countDown()
        registration.await()
        assertEquals(1, controller.cancelCount.get())
    }

    @Test
    fun `recoverable native cancel failure uses bounded job fallback and cleans up`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val release = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry()
        val key = GenerationRequestKey(46L, "request-a")
        try {
            registry.start(scope, snapshot(46L, "request-a")) { release.await() }
            registry.cancellationController.registerCancellationHook(key, Any()) {
                throw IllegalStateException("recoverable cancel failure")
            }
            assertTrue(registry.stopAndJoin(46L, "request-a"))
            assertEquals(0, registry.registeredConversationCount())
            assertEquals(0, registry.recordedIntentCount())
            assertEquals(0, registry.partialOutputCount())
            assertEquals(0, registry.terminalAdoptionClaimCount())
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `native cancellation timeout falls back without permanent state`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val release = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry(userStopSettlementTimeoutMs = 10L)
        val key = GenerationRequestKey(47L, "request-a")
        try {
            registry.start(scope, snapshot(47L, "request-a")) { release.await() }
            registry.cancellationController.registerCancellationHook(key, Any()) { }
            assertTrue(registry.stopAndJoin(47L, "request-a"))
            assertFalse(registry.hasActiveSession(47L))
            assertEquals(0, registry.registeredConversationCount())
            assertEquals(0, registry.recordedIntentCount())
            assertEquals(0, registry.partialOutputCount())
            assertEquals(0, registry.terminalAdoptionClaimCount())
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `fatal native cancellation error propagates`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val release = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry()
        val key = GenerationRequestKey(48L, "request-a")
        try {
            registry.start(scope, snapshot(48L, "request-a")) { release.await() }
            registry.cancellationController.registerCancellationHook(key, Any()) {
                throw OutOfMemoryError("fatal cancel")
            }
            var propagated = false
            try {
                registry.stopAndJoin(48L, "request-a")
            } catch (_: OutOfMemoryError) {
                propagated = true
            }
            assertTrue(propagated)
        } finally {
            release.complete(Unit)
            registry.cancelAndJoin(48L, "request-a", "fatal-test-cleanup")
            scope.cancel()
        }
    }

    @Test
    fun `cancelled stop caller still settles exact request`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val release = CompletableDeferred<Unit>()
        val registry = GenerationSessionRegistry(userStopSettlementTimeoutMs = 5_000L)
        val key = GenerationRequestKey(49L, "request-a")
        val stopIssued = CompletableDeferred<Unit>()
        try {
            registry.start(scope, snapshot(49L, "request-a")) { release.await() }
            registry.cancellationController.registerCancellationHook(key, Any()) { stopIssued.complete(Unit) }
            val waiter = async(Dispatchers.Default) { registry.stopAndJoin(49L, "request-a") }
            stopIssued.await()
            waiter.cancel()
            try { waiter.await() } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(0, registry.registeredConversationCount())
            assertEquals(0, registry.recordedIntentCount())
            assertEquals(0, registry.partialOutputCount())
            assertEquals(0, registry.terminalAdoptionClaimCount())
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    private class NativeConversationCancellationControllerForTest {
        val raw = com.projectnuke.fusion.llm.NativeConversationCancellationController()
        val aCancelled = AtomicInteger(0)
        val bCancelled = AtomicInteger(0)
        val cancelCount = AtomicInteger(0)
        fun recordIntent(key: GenerationRequestKey, intent: com.projectnuke.fusion.llm.GenerationTerminationIntent) = raw.recordIntent(key, intent)
        fun register(key: GenerationRequestKey, cancel: () -> Unit) =
            raw.registerCancellationHook(key, Any(), cancel = cancel)
    }

    private fun snapshot(conversationId: Long, requestId: String) =
        GenerationRequestSnapshot(
            requestId = requestId,
            conversationId = conversationId,
            generationModeKey = "LOCAL_MODEL",
            selectedModelId = "test",
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
}
