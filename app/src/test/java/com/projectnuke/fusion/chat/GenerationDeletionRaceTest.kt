package com.projectnuke.fusion.chat

import com.projectnuke.fusion.llm.GenerationRequestKey
import com.projectnuke.fusion.llm.GenerationTerminationIntent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * P17-02 conversation deletion versus delayed generation installation.
 *
 * Drives the real [GenerationSessionRegistry] start/install boundary with
 * deterministic coroutine gates at the actual lifecycle seams
 * ([GenerationSessionRegistry.onBeforeInstall],
 * [GenerationSessionRegistry.onAfterTokenClaim]) plus the real
 * [ChatViewModel.deleteConversation] deferred-ownership path. No production
 * behavior is bypassed; the downstream persistence barrier (SQLite foreign
 * key) is covered separately by `DeletionPersistenceBarrierTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenerationDeletionRaceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule { scheduler ->
        UnconfinedTestDispatcher(scheduler)
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

    private fun awaitGate(gate: CountDownLatch) {
        assertTrue("synchronization gate was not reached", gate.await(5, TimeUnit.SECONDS))
    }

    private fun assertRegistryClean(registry: GenerationSessionRegistry, conversationId: Long) {
        assertNull(registry.activeSession(conversationId))
        assertNull(registry.latestToken(conversationId))
        assertFalse(registry.hasLogicalRequest(conversationId))
        assertEquals(0, registry.registeredConversationCount())
        assertEquals(0, registry.recordedIntentCount())
        assertEquals(0, registry.partialOutputCount())
        assertEquals(0, registry.terminalAdoptionClaimCount())
        assertEquals(0, registry.conversationLockRegistrySizeForTests())
    }

    // P17-02-A — start before deletion: a generation claims its token, pauses
    // before session installation, then deletion begins. Deletion must wait
    // for (and cancel) the owned operation; the delayed install must never
    // become active afterwards.
    @Test
    fun `P17-02-A start before deletion cannot install after deletion`() = runBlocking {
        val scope = testScope()
        val registry = GenerationSessionRegistry()
        val conversationId = 301L
        val installEntered = CountDownLatch(1)
        val installRelease = CompletableDeferred<Unit>()
        val blockRelease = CompletableDeferred<Unit>()
        val cancellationApplied = CountDownLatch(1)
        registry.onAfterSettlementCancel = { cancellationApplied.countDown() }
        var blockRan = false
        registry.onBeforeInstall = {
            installEntered.countDown()
            installRelease.await()
        }
        try {
            val start = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(conversationId, "gen-A")) {
                        blockRan = true
                        blockRelease.await()
                    }
                    "installed"
                } catch (_: CancellationException) {
                    "cancelled"
                }
            }
            awaitGate(installEntered)

            assertTrue(registry.claimDeletionOwnership(conversationId, "test-delete-A"))
            val settle = async(Dispatchers.Default) {
                registry.settleForDeletion(conversationId, "test-delete-A")
            }
            // Deletion observes and cancels the owned token...
            awaitGate(cancellationApplied)
            // ...but cannot finish before the owned start observes cancellation.
            assertTrue("deletion must join the owned operation, not abandon it", settle.isActive)
            assertNull("no session may be active while deletion waits", registry.activeSession(conversationId))

            installRelease.complete(Unit)
            assertEquals("cancelled", withTimeout(5000) { start.await() })
            assertTrue(withTimeout(5000) { settle.await() })
            assertFalse("cancelled install must never run its block", blockRan)
            assertNull(registry.activeSession(conversationId))
            registry.releaseDeletionOwnership(conversationId, "test-delete-A")
            assertRegistryClean(registry, conversationId)
        } finally {
            registry.onBeforeInstall = {}
            registry.onAfterSettlementCancel = {}
            installRelease.complete(Unit)
            blockRelease.complete(Unit)
            registry.releaseDeletionOwnership(conversationId, "test-delete-A")
            scope.cancel()
            withTimeout(5000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    // P17-02-B — start during deletion: a token claimed while deletion owns
    // the conversation stays refused even if the barrier is released before
    // the delayed start continues.
    @Test
    fun `P17-02-B start claimed during deletion never becomes valid after release`() = runBlocking {
        val scope = testScope()
        val registry = GenerationSessionRegistry()
        val conversationId = 302L
        val claimProceed = CompletableDeferred<Unit>()
        val blockRelease = CompletableDeferred<Unit>()
        registry.onAfterTokenClaim = { claimProceed.await() }
        try {
            assertTrue(registry.claimDeletionOwnership(conversationId, "test-delete-B"))
            val start = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(conversationId, "gen-B")) {
                        blockRelease.await()
                    }
                    "installed"
                } catch (e: CancellationException) {
                    "cancelled:${e.message}"
                }
            }
            withTimeout(5000) {
                while (registry.latestToken(conversationId) == null) yield()
            }
            // The barrier is released while the claimed start is still parked.
            registry.releaseDeletionOwnership(conversationId, "test-delete-B")
            claimProceed.complete(Unit)
            val outcome = withTimeout(5000) { start.await() }
            assertTrue("claimed-during-deletion start must stay refused, got $outcome", outcome.startsWith("cancelled:"))
            assertTrue(outcome.contains("being deleted"))
            assertNull(registry.activeSession(conversationId))
            assertRegistryClean(registry, conversationId)

            // Liveness: no tombstone blocks a valid future conversation.
            val session = withTimeout(5000) {
                registry.start(scope, snapshot(conversationId, "gen-B2")) {
                    blockRelease.await()
                }
            }
            assertTrue(registry.isActive(conversationId, "gen-B2"))
            assertTrue(
                withTimeout(5000) {
                    registry.cancelAndJoin(
                        conversationId, "gen-B2",
                        GenerationTerminationIntent.INTERNAL_CANCEL, "test-done-B2",
                    )
                }
            )
            blockRelease.complete(Unit)
            withTimeout(5000) { session.job.join() }
            assertRegistryClean(registry, conversationId)
        } finally {
            registry.onAfterTokenClaim = {}
            blockRelease.complete(Unit)
            claimProceed.complete(Unit)
            registry.releaseDeletionOwnership(conversationId, "test-delete-B")
            scope.cancel()
            withTimeout(5000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    // P17-02-C — cancellation acknowledgement failure: the outer waiter is
    // cancelled while joining a gated install. Registry and native ownership
    // must still settle exactly once the delayed operation is released.
    @Test
    fun `P17-02-C cancelled waiter leaves no session or native ownership`() = runBlocking {
        val scope = testScope()
        val registry = GenerationSessionRegistry()
        val conversationId = 303L
        val requestId = "gen-C"
        val key = GenerationRequestKey(conversationId, requestId)
        val installEntered = CountDownLatch(1)
        val installRelease = CompletableDeferred<Unit>()
        val blockRelease = CompletableDeferred<Unit>()
        val cancellationApplied = CountDownLatch(1)
        registry.onAfterSettlementCancel = { cancellationApplied.countDown() }
        registry.onBeforeInstall = {
            installEntered.countDown()
            installRelease.await()
        }
        try {
            val start = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(conversationId, requestId)) {
                        blockRelease.await()
                    }
                    "installed"
                } catch (_: CancellationException) {
                    "cancelled"
                }
            }
            awaitGate(installEntered)
            val waiter = async(Dispatchers.Default) {
                registry.cancelAndJoin(
                    conversationId, requestId,
                    GenerationTerminationIntent.INTERNAL_CANCEL, "test-cancel-C",
                )
            }
            // The waiter reserves the exact intent and cancels the token...
            awaitGate(cancellationApplied)
            // ...then loses its acknowledgement while joining.
            waiter.cancel()
            installRelease.complete(Unit)
            assertEquals("cancelled", withTimeout(5000) { start.await() })
            withTimeout(5000) { waiter.join() }
            assertTrue("waiter must observe cancellation", waiter.isCancelled)
            assertNull(registry.activeSession(conversationId))
            assertNull(registry.latestToken(conversationId))
            assertNull("native intent must be cleared", registry.cancellationController.intentFor(key))
            assertRegistryClean(registry, conversationId)
        } finally {
            registry.onBeforeInstall = {}
            registry.onAfterSettlementCancel = {}
            installRelease.complete(Unit)
            blockRelease.complete(Unit)
            scope.cancel()
            withTimeout(5000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    // P17-02-D — deletion on A while independent B starts generation:
    // preventing A's late installation must not block B.
    @Test
    fun `P17-02-D deletion on A does not block independent conversation B`() = runBlocking {
        val scope = testScope()
        val registry = GenerationSessionRegistry()
        val conversationA = 304L
        val conversationB = 305L
        val installEnteredA = CountDownLatch(1)
        val installReleaseA = CompletableDeferred<Unit>()
        val blockReleaseB = CompletableDeferred<Unit>()
        val cancellationAppliedA = CountDownLatch(1)
        registry.onAfterSettlementCancel = { cancellationAppliedA.countDown() }
        registry.onBeforeInstall = {
            // Only A's install parks here; B's requestId differs.
            if (registry.latestToken(conversationA)?.requestId == "gen-A") {
                installEnteredA.countDown()
                installReleaseA.await()
            }
        }
        try {
            val startA = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(conversationA, "gen-A")) {
                        CompletableDeferred<Unit>().await()
                    }
                    "installed"
                } catch (_: CancellationException) {
                    "cancelled"
                }
            }
            awaitGate(installEnteredA)
            assertTrue(registry.claimDeletionOwnership(conversationA, "test-delete-A"))
            val settleA = async(Dispatchers.Default) {
                registry.settleForDeletion(conversationA, "test-delete-A")
            }
            awaitGate(cancellationAppliedA)
            // A's in-flight start already captured the gate lambda; resetting
            // the seam affects only future starts, so B installs immediately.
            registry.onBeforeInstall = {}
            registry.onAfterSettlementCancel = {}

            // Independent conversation B installs while A's deletion waits.
            val sessionB = withTimeout(5000) {
                registry.start(scope, snapshot(conversationB, "gen-B")) {
                    blockReleaseB.await()
                }
            }
            assertTrue("B must install while A is being deleted", registry.isActive(conversationB, "gen-B"))

            installReleaseA.complete(Unit)
            assertEquals("cancelled", withTimeout(5000) { startA.await() })
            assertTrue(withTimeout(5000) { settleA.await() })
            registry.releaseDeletionOwnership(conversationA, "test-delete-A")
            assertNull(registry.activeSession(conversationA))
            assertRegistryClean(registry, conversationA)

            assertTrue("B must still be active after A's deletion settles", registry.isActive(conversationB, "gen-B"))
            assertTrue(
                withTimeout(5000) {
                    registry.cancelAndJoin(
                        conversationB, "gen-B",
                        GenerationTerminationIntent.INTERNAL_CANCEL, "test-done-B",
                    )
                }
            )
            blockReleaseB.complete(Unit)
            withTimeout(5000) { sessionB.job.join() }
            assertRegistryClean(registry, conversationB)
        } finally {
            registry.onBeforeInstall = {}
            installReleaseA.complete(Unit)
            blockReleaseB.complete(Unit)
            registry.releaseDeletionOwnership(conversationA, "test-delete-A")
            scope.cancel()
            withTimeout(5000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `deletion before install gate can settle after start cleans its token`() = runBlocking {
        val scope = testScope()
        val registry = GenerationSessionRegistry()
        val conversationId = 308L
        val claimed = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val installEntered = CountDownLatch(1)
        val cancellationApplied = CountDownLatch(1)
        registry.onAfterTokenClaim = { claimed.complete(Unit); proceed.await() }
        registry.onBeforeInstall = { installEntered.countDown() }
        registry.onAfterSettlementCancel = { cancellationApplied.countDown() }
        try {
            val start = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(conversationId, "before-install")) {}
                    "installed"
                } catch (_: CancellationException) {
                    "cancelled"
                }
            }
            withTimeout(5000) { claimed.await() }
            assertTrue(registry.claimDeletionOwnership(conversationId, "test-delete-before-install"))
            proceed.complete(Unit)
            assertEquals("cancelled", withTimeout(5000) { start.await() })
            assertEquals(1L, installEntered.count)
            assertNull(registry.latestToken(conversationId))
            assertTrue(withTimeout(5000) { registry.settleForDeletion(conversationId, "test-delete-before-install") })
            assertEquals("no pending token remains for settlement to cancel", 1L, cancellationApplied.count)
            registry.releaseDeletionOwnership(conversationId, "test-delete-before-install")
            assertNull(registry.deletionOwner(conversationId))
            assertRegistryClean(registry, conversationId)
        } finally {
            proceed.complete(Unit)
            registry.releaseDeletionOwnership(conversationId, "test-delete-before-install")
            scope.cancel()
            withTimeout(5000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    // P17-02-E — repeated cleanup in different valid orders: no stale
    // session, pending token, ownership lock, or native state may survive
    // terminal completion.
    @Test
    fun `P17-02-E repeated cleanup orders leave no residue`() = runBlocking {
        val scope = testScope()
        val registry = GenerationSessionRegistry()
        val conversationId = 306L
        val blockRelease = CompletableDeferred<Unit>()
        val installRelease = CompletableDeferred<Unit>()
        val installRelease3 = CompletableDeferred<Unit>()
        try {
            // Order 1: normal terminal completion, twice.
            repeat(2) { round ->
                val session = withTimeout(5000) {
                    registry.start(scope, snapshot(conversationId, "gen-E$round")) {}
                }
                withTimeout(5000) { session.job.join() }
                assertRegistryClean(registry, conversationId)
            }
            // Order 2: exact cancellation of a gated install.
            val installEntered = CountDownLatch(1)
            val cancelApplied = CountDownLatch(1)
            registry.onBeforeInstall = {
                installEntered.countDown()
                installRelease.await()
            }
            registry.onAfterSettlementCancel = { cancelApplied.countDown() }
            val gated = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(conversationId, "gen-gated")) {
                        blockRelease.await()
                    }
                    "installed"
                } catch (_: CancellationException) {
                    "cancelled"
                }
            }
            awaitGate(installEntered)
            val cancelWaiter = async(Dispatchers.Default) {
                registry.cancelAndJoin(
                    conversationId, "gen-gated",
                    GenerationTerminationIntent.INTERNAL_CANCEL, "test-cancel-E",
                )
            }
            awaitGate(cancelApplied)
            installRelease.complete(Unit)
            assertEquals("cancelled", withTimeout(5000) { gated.await() })
            assertTrue(withTimeout(5000) { cancelWaiter.await() })
            registry.onBeforeInstall = {}
            registry.onAfterSettlementCancel = {}
            assertRegistryClean(registry, conversationId)

            // Order 3: deletion settlement of a gated install (fresh gate:
            // the Order-2 gate is already completed and must not be reused).
            val installEntered3 = CountDownLatch(1)
            val deleteApplied = CountDownLatch(1)
            registry.onBeforeInstall = {
                installEntered3.countDown()
                installRelease3.await()
            }
            registry.onAfterSettlementCancel = { deleteApplied.countDown() }
            val gated2 = async(Dispatchers.Default) {
                try {
                    registry.start(scope, snapshot(conversationId, "gen-gated2")) {
                        blockRelease.await()
                    }
                    "installed"
                } catch (_: CancellationException) {
                    "cancelled"
                }
            }
            awaitGate(installEntered3)
            assertTrue(registry.claimDeletionOwnership(conversationId, "test-delete-E"))
            val settle = async(Dispatchers.Default) {
                registry.settleForDeletion(conversationId, "test-delete-E")
            }
            awaitGate(deleteApplied)
            installRelease3.complete(Unit)
            assertEquals("cancelled", withTimeout(5000) { gated2.await() })
            assertTrue(withTimeout(5000) { settle.await() })
            registry.releaseDeletionOwnership(conversationId, "test-delete-E")
            assertNull(registry.deletionOwner(conversationId))
            registry.onBeforeInstall = {}
            registry.onAfterSettlementCancel = {}
            assertRegistryClean(registry, conversationId)

            // Order 4: install after the deletion ownership is gone works.
            val session = withTimeout(5000) {
                registry.start(scope, snapshot(conversationId, "gen-after")) {
                    blockRelease.await()
                }
            }
            assertTrue(registry.isActive(conversationId, "gen-after"))
            assertTrue(
                withTimeout(5000) {
                    registry.cancelAndJoin(
                        conversationId, "gen-after",
                        GenerationTerminationIntent.INTERNAL_CANCEL, "test-done-E",
                    )
                }
            )
            blockRelease.complete(Unit)
            withTimeout(5000) { session.job.join() }
            assertRegistryClean(registry, conversationId)
        } finally {
            registry.onBeforeInstall = {}
            registry.onAfterSettlementCancel = {}
            installRelease.complete(Unit)
            installRelease3.complete(Unit)
            blockRelease.complete(Unit)
            registry.releaseDeletionOwnership(conversationId, "test-delete-E")
            scope.cancel()
            withTimeout(5000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    // P17-02-E2 — repeated production deletion calls settle cleanly and
    // release deferred deletion ownership every time.
    @Test
    fun `P17-02-E2 repeated deleteConversation settles without residue`() = runBlocking {
        val viewModel = ChatViewModel()
        try {
            val conversationId = 307L
            repeat(2) {
                val deletion = viewModel.deleteConversation(
                    conversationId = conversationId,
                    exists = { false },
                    commitDelete = { error("must not commit for an absent conversation") },
                    settleTarget = {},
                    cleanupDerivedData = {},
                    recordCleanupDebt = { error("no debt expected on the clean path") },
                )
                assertEquals(
                    ConversationDeletionResult.ALREADY_ABSENT_CLEAN,
                    withTimeout(5000) { deletion.await() },
                )
                assertNull(viewModel.registry.deletionOwner(conversationId))
            }
        } finally {
            viewModel.scope.cancel()
            withTimeout(5000) { viewModel.scope.coroutineContext[Job]!!.join() }
        }
    }
}
