package com.projectnuke.fusion.chat

import com.projectnuke.fusion.chat.GenerationSessionRegistry.PendingStart
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.projectnuke.fusion.llm.GenerationRequestKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelRequestStateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule { scheduler ->
        UnconfinedTestDispatcher(scheduler)
    }

    private val viewModels = mutableListOf<ChatViewModel>()

    @After
    fun tearDown() {
        viewModels.asReversed().forEach { it.cancelForTest(mainDispatcherRule.dispatcher.scheduler) }
        viewModels.clear()
    }

    private fun tracked(vm: ChatViewModel): ChatViewModel {
        viewModels += vm
        return vm
    }

    @Test
    fun `user stop exposes stopping state until native settlement then returns idle`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val vm = tracked(ChatViewModel())
        val conversationId = 107L
        val requestId = "user-stop"
        val owner = Any()
        vm.update(conversationId) {
            it.copy(isGenerating = true, activeRequestId = requestId, streamingText = "partial")
        }
        try {
            vm.registry.start(scope, snapshot(conversationId, requestId)) { release.await() }
            vm.registry.cancellationController.registerCancellationHook(
                GenerationRequestKey(conversationId, requestId),
                owner,
            ) { /* native stop is observed by the fake conversation */ }
            val stop = async { vm.cancelGeneration(conversationId) }
            withTimeout(2000) {
                while (!vm.state(conversationId).isStopping) yield()
            }
            assertTrue(vm.state(conversationId).isGenerating)
            assertEquals("응답 중지 중...", vm.state(conversationId).generationStatus)
            release.complete(Unit)
            withTimeout(2000) { stop.await() }
            assertFalse(vm.state(conversationId).isGenerating)
            assertFalse(vm.state(conversationId).isStopping)
        } finally {
            vm.registry.cancellationController.unregisterCancellationHook(
                GenerationRequestKey(conversationId, requestId),
                owner,
            )
            release.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancelled view model stop caller still finalizes exact UI state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val nativeEntered = CompletableDeferred<Unit>()
        val vm = tracked(ChatViewModel())
        val conversationId = 109L
        val requestId = "cancelled-stop"
        val key = GenerationRequestKey(conversationId, requestId)
        vm.update(conversationId) {
            it.copy(isGenerating = true, activeRequestId = requestId, streamingText = "partial")
        }
        try {
            vm.registry.start(scope, snapshot(conversationId, requestId)) { release.await() }
            vm.registry.cancellationController.registerCancellationHook(key, Any()) {
                nativeEntered.complete(Unit)
            }
            val waiter = async(Dispatchers.Default) { vm.cancelGeneration(conversationId) }
            withTimeout(2000) { nativeEntered.await() }
            waiter.cancel()
            try {
                waiter.await()
            } catch (_: CancellationException) {
                // The caller is allowed to be disposed; request settlement is not.
            }
            assertFalse(vm.state(conversationId).isGenerating)
            assertFalse(vm.state(conversationId).isStopping)
            assertNull(vm.state(conversationId).activeRequestId)
        } finally {
            release.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `finishRequestState(A) clears state only when A is still the stored request`() {
        val vm = tracked(ChatViewModel())
        val convId = 100L
        vm.update(convId) {
            it.copy(
                activeRequestId = "A",
                isGenerating = true,
                streamingText = "hello",
                streamingMetricsLine = "metrics",
                generationStatus = "gen",
                regeneratingMessageId = 42L,
                extractingMemoryCandidates = true,
                actualWebSearchUsed = true,
            )
        }
        assertEquals("A", vm.state(convId).activeRequestId)

        vm.finishRequestState(convId, "A")
        val state = vm.state(convId)
        // State is not completely cleared - it preserves lastRuntimeDiagnostics and actualWebSearchUsed
        assertNull(state.activeRequestId)
        assertFalse(state.isGenerating)
        assertNull(state.streamingText)
        assertNull(state.streamingMetricsLine)
        assertNull(state.generationStatus)
        assertNull(state.regeneratingMessageId)
        assertFalse(state.extractingMemoryCandidates)
        assertTrue(state.actualWebSearchUsed) // Preserved after finishRequestState
    }

    @Test
    fun `late finishRequestState(A) cannot clear replacement B`() {
        val vm = tracked(ChatViewModel())
        val convId = 101L
        vm.update(convId) { it.copy(activeRequestId = "A", isGenerating = true) }
        vm.update(convId) { it.copy(activeRequestId = "B", isGenerating = true) }

        vm.finishRequestState(convId, "A")
        assertEquals("B", vm.state(convId).activeRequestId)
        assertTrue(vm.state(convId).isGenerating)
    }

    @Test
    fun `updateRequestState(A) cannot mutate replacement B`() {
        val vm = tracked(ChatViewModel())
        val convId = 102L
        vm.update(convId) { it.copy(activeRequestId = "A", streamingText = "from-A") }
        vm.update(convId) { it.copy(activeRequestId = "B", streamingText = "from-B") }

        vm.updateRequestState(convId, "A") { it.copy(streamingText = "A-tries-update") }
        assertEquals("from-B", vm.state(convId).streamingText)
    }

    @Test
    fun `requireActiveSession rejects updates after registry session ends`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = tracked(ChatViewModel())
            val convId = 103L
            vm.update(convId) { it.copy(activeRequestId = "R") }

            val blockRelease = CompletableDeferred<Unit>()
            val session = withTimeout(2000) {
                vm.registry.start(scope, snapshot(convId, "R")) {
                    blockRelease.await()
                }
            }
            blockRelease.complete(Unit)
            withTimeout(2000) { session.job.join() }

            vm.updateRequestState(convId, "R", requireActiveSession = true) {
                it.copy(streamingText = "should-not-appear")
            }
            assertNull(vm.state(convId).streamingText)
        } finally {
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `requireActiveSession false permits matching terminal reconciliation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = tracked(ChatViewModel())
            val convId = 104L
            vm.update(convId) { it.copy(activeRequestId = "R", isGenerating = true) }

            val blockRelease = CompletableDeferred<Unit>()
            val session = withTimeout(2000) {
                vm.registry.start(scope, snapshot(convId, "R")) {
                    blockRelease.await()
                }
            }
            blockRelease.complete(Unit)
            withTimeout(2000) { session.job.join() }

            vm.updateRequestState(convId, "R", requireActiveSession = false) {
                it.copy(isGenerating = false)
            }
            assertFalse(vm.state(convId).isGenerating)
        } finally {
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `new chat admission is independent of another conversation generation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vm = tracked(ChatViewModel())
        val release = CompletableDeferred<Unit>()
        try {
            vm.update(601L) { it.copy(isGenerating = true, activeRequestId = "A") }
            val session = vm.registry.start(scope, snapshot(601L, "A")) { release.await() }
            vm.selectConversation(ChatViewModel.NEW_CONVERSATION_ID)
            vm.updateDraftText(ChatViewModel.NEW_CONVERSATION_ID, "next")

            val admission = vm.beginSubmissionResult(ChatViewModel.NEW_CONVERSATION_ID)
            assertEquals(
                SubmissionAdmissionResult.Accepted::class,
                admission::class,
            )
            val accepted = (admission as SubmissionAdmissionResult.Accepted).snapshot
            assertEquals(ChatViewModel.NEW_CONVERSATION_ID, accepted.conversationId)
            assertTrue(vm.registry.hasLogicalRequest(601L))
            assertTrue(vm.settleSubmissionOwner(ChatViewModel.NEW_CONVERSATION_ID, accepted.token))

            release.complete(Unit)
            session.job.join()
        } finally {
            release.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `typed admission reports the exact manual owner`() = runBlocking {
        val vm = tracked(ChatViewModel())
        val lease = requireNotNull(ManualCompactionOperationRegistry.tryAcquire(602L))
        try {
            vm.updateDraftText(602L, "blocked")
            assertEquals(
                SubmissionAdmissionResult.Rejected(
                    SubmissionRejectionReason.MANUAL_COMPACTION_LEASE_HELD,
                ),
                vm.beginSubmissionResult(602L),
            )
        } finally {
            ManualCompactionOperationRegistry.release(lease)
        }
    }

    @Test
    fun `new chat remains admissible while exact user stop is still settling`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vm = tracked(ChatViewModel())
        val finalizationEntered = CompletableDeferred<Unit>()
        val releaseFinalization = CompletableDeferred<Unit>()
        val stopIssued = CompletableDeferred<Unit>()
        val key = GenerationRequestKey(603L, "A")
        val nativeOwner = Any()
        try {
            vm.update(603L) { it.copy(isGenerating = true, activeRequestId = "A") }
            vm.registry.start(scope, snapshot(603L, "A")) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        finalizationEntered.complete(Unit)
                        releaseFinalization.await()
                    }
                }
            }
            vm.registry.cancellationController.registerCancellationHook(key, nativeOwner) {
                stopIssued.complete(Unit)
            }

            val stopping = async(Dispatchers.Default) { vm.cancelGeneration(603L) }
            stopIssued.await()
            vm.selectConversation(ChatViewModel.NEW_CONVERSATION_ID)
            vm.updateDraftText(ChatViewModel.NEW_CONVERSATION_ID, "while stopping")
            val admission = vm.beginSubmissionResult(ChatViewModel.NEW_CONVERSATION_ID)
            assertTrue(admission is SubmissionAdmissionResult.Accepted)
            vm.settleSubmissionOwner(
                ChatViewModel.NEW_CONVERSATION_ID,
                (admission as SubmissionAdmissionResult.Accepted).snapshot.token,
            )
            finalizationEntered.await()
            releaseFinalization.complete(Unit)
            stopping.await()
            assertFalse(vm.state(603L).isGenerating)
            assertFalse(vm.registry.hasLogicalRequest(603L))
        } finally {
            releaseFinalization.complete(Unit)
            vm.registry.cancellationController.unregisterCancellationHook(key, nativeOwner)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `repeated user stop and new chat admission remains usable`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vm = tracked(ChatViewModel())
        try {
            repeat(3) { index ->
                val conversationId = 604L + index
                val requestId = "A-$index"
                val release = CompletableDeferred<Unit>()
                val key = GenerationRequestKey(conversationId, requestId)
                val owner = Any()
                vm.update(conversationId) { it.copy(isGenerating = true, activeRequestId = requestId) }
                val session = vm.registry.start(scope, snapshot(conversationId, requestId)) { release.await() }
                vm.registry.cancellationController.registerCancellationHook(key, owner) {
                    release.complete(Unit)
                }

                val stopping = async(Dispatchers.Default) { vm.cancelGeneration(conversationId) }
                vm.selectConversation(ChatViewModel.NEW_CONVERSATION_ID)
                vm.updateDraftText(ChatViewModel.NEW_CONVERSATION_ID, "next-$index")
                val admission = vm.beginSubmissionResult(ChatViewModel.NEW_CONVERSATION_ID)
                assertTrue(admission is SubmissionAdmissionResult.Accepted)
                vm.settleSubmissionOwner(
                    ChatViewModel.NEW_CONVERSATION_ID,
                    (admission as SubmissionAdmissionResult.Accepted).snapshot.token,
                )
                stopping.await()
                assertTrue(session.job.isCompleted)
                assertFalse(vm.registry.hasLogicalRequest(conversationId))
                vm.registry.cancellationController.unregisterCancellationHook(key, owner)
            }
        } finally {
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `state for conversation A cannot mutate conversation B`() {
        val vm = tracked(ChatViewModel())
        vm.update(100L) { it.copy(activeRequestId = "A1", streamingText = "text-A") }
        vm.update(200L) { it.copy(activeRequestId = "B1", streamingText = "text-B") }

        vm.finishRequestState(100L, "A1")
        assertNull(vm.state(100L).activeRequestId)
        assertEquals("B1", vm.state(200L).activeRequestId)
    }

    @Test
    fun `cancelGeneration joins the active session and clears generation fields`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val holdGate = CompletableDeferred<Unit>()
        val entered = CountDownLatch(1)
        try {
            val vm = tracked(ChatViewModel())
            val convId = 105L
            vm.update(convId) { it.copy(
                activeRequestId = "R",
                isGenerating = true,
                streamingText = "in-flight",
                streamingMetricsLine = "metrics",
                generationStatus = "generating",
                regeneratingMessageId = 42L,
                extractingMemoryCandidates = true,
                actualWebSearchUsed = true,
            ) }

            val session = withTimeout(2000) {
                vm.registry.start(scope, snapshot(convId, "R")) {
                    entered.countDown()
                    holdGate.await()
                }
            }

            awaitGate(entered)
            withTimeout(2000) { vm.cancelGeneration(convId, "test-stop") }

            val state = vm.state(convId)
            assertNull(state.activeRequestId)
            assertFalse(state.isGenerating)
            assertNull(state.streamingText)
            assertNull(state.streamingMetricsLine)
            assertNull(state.generationStatus)
            assertNull(state.regeneratingMessageId)
            assertFalse(state.extractingMemoryCandidates)
            assertTrue(state.actualWebSearchUsed) // Preserved after cancelGeneration
        } finally {
            holdGate.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancelGeneration with no active session is safe`() = runBlocking {
        val vm = tracked(ChatViewModel())
        vm.cancelGeneration(999L, "nothing")
        assertTrue(true)
    }

    @Test
    fun `cancelGeneration of A cannot clear replacement B`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aCleanupStarted = CountDownLatch(1)
        val aCleanupRelease = CompletableDeferred<Unit>()
        val aStarted = CountDownLatch(1)
        val bStarted = CountDownLatch(1)
        val bHold = CompletableDeferred<Unit>()
        try {
            val vm = tracked(ChatViewModel())
            val convId = 106L

            vm.update(convId) { it.copy(
                activeRequestId = "A",
                isGenerating = true,
                streamingText = "A-stream",
                streamingMetricsLine = "A-metrics",
                generationStatus = "A-status",
                regeneratingMessageId = 1L,
                extractingMemoryCandidates = true,
                actualWebSearchUsed = true,
            ) }

            val aSession = withTimeout(2000) {
                vm.registry.start(scope, snapshot(convId, "A")) {
                    aStarted.countDown()
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        aCleanupStarted.countDown()
                        withContext(NonCancellable) {
                            aCleanupRelease.await()
                        }
                    }
                }
            }

            awaitGate(aStarted)

            val cancelDone = async(Dispatchers.Default) {
                vm.cancelGeneration(convId, "stop-A")
            }

            awaitGate(aCleanupStarted)

            vm.update(convId) { it.copy(
                activeRequestId = "B",
                isGenerating = true,
                streamingText = "B-stream",
                streamingMetricsLine = "B-metrics",
                generationStatus = "B-status",
                regeneratingMessageId = 2L,
                extractingMemoryCandidates = false,
                actualWebSearchUsed = false,
            ) }

            val bSessionDeferred = async(Dispatchers.Default) {
                vm.registry.start(scope, snapshot(convId, "B")) {
                    bStarted.countDown()
                    bHold.await()
                }
            }

            aCleanupRelease.complete(Unit)
            withTimeout(2000) { cancelDone.await() }
            awaitGate(bStarted)

            val stateB = vm.state(convId)
            assertEquals("B", stateB.activeRequestId)
            assertTrue(stateB.isGenerating)
            assertEquals("B-stream", stateB.streamingText)
            assertEquals("B-metrics", stateB.streamingMetricsLine)
            assertEquals("B-status", stateB.generationStatus)
            assertEquals(2L, stateB.regeneratingMessageId)
            assertFalse(stateB.extractingMemoryCandidates)
            assertFalse(stateB.actualWebSearchUsed)

            assertTrue("B should be active in registry", vm.registry.isActive(convId, "B"))

            val stateAfter = vm.state(convId)
            assertEquals("B", stateAfter.activeRequestId)
            assertTrue(stateAfter.isGenerating)
            assertEquals("B-stream", stateAfter.streamingText)
            assertEquals("B-metrics", stateAfter.streamingMetricsLine)
            assertEquals("B-status", stateAfter.generationStatus)
            assertEquals(2L, stateAfter.regeneratingMessageId)
            assertFalse(stateAfter.extractingMemoryCandidates)
            assertFalse(stateAfter.actualWebSearchUsed)

            assertTrue("B should still be active after A cleanup completes",
                vm.registry.isActive(convId, "B"))
        } finally {
            aCleanupRelease.complete(Unit)
            bHold.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancelGeneration of pending or starting B stops B before block enters`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aCleanupStarted = CountDownLatch(1)
        val aCleanupRelease = CompletableDeferred<Unit>()
        val aStarted = CountDownLatch(1)
        val bBlockEntered = CountDownLatch(1)
        val bHold = CompletableDeferred<Unit>()
        val bPreInstall = CountDownLatch(1)
        val bPreInstallRelease = CompletableDeferred<Unit>()
        try {
            val vm = tracked(ChatViewModel())
            val convId = 107L

            vm.update(convId) { it.copy(
                activeRequestId = "A",
                isGenerating = true,
                streamingText = "A-stream",
                streamingMetricsLine = "A-metrics",
                generationStatus = "A-status",
                regeneratingMessageId = 1L,
                extractingMemoryCandidates = true,
                actualWebSearchUsed = true,
            ) }

            val aSession = withTimeout(2000) {
                vm.registry.start(scope, snapshot(convId, "A")) {
                    aStarted.countDown()
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        aCleanupStarted.countDown()
                        withContext(NonCancellable) {
                            aCleanupRelease.await()
                        }
                    }
                }
            }

            awaitGate(aStarted)

            async(Dispatchers.Default) {
                vm.cancelGeneration(convId, "stop-A")
            }

            awaitGate(aCleanupStarted)

            vm.update(convId) { it.copy(
                activeRequestId = "B",
                isGenerating = true,
                streamingText = "B-stream",
                streamingMetricsLine = "B-metrics",
                generationStatus = "B-status",
                regeneratingMessageId = 2L,
                extractingMemoryCandidates = false,
                actualWebSearchUsed = false,
            ) }

            val bDeferred = async(Dispatchers.Default) {
                try {
                    vm.registry.start(scope, snapshot(convId, "B")) {
                        bBlockEntered.countDown()
                        bHold.await()
                    }
                    null
                } catch (e: CancellationException) {
                    e
                }
            }

            vm.registry.onBeforeInstall = {
                bPreInstall.countDown()
                bPreInstallRelease.await()
            }

            withTimeout(2000) {
                while (vm.registry.latestToken(convId)?.requestId != "B") {
                    yield()
                }
            }

            val cancelB = async(Dispatchers.Default) {
                vm.cancelGeneration(convId, "stop-pending-B")
            }

            aCleanupRelease.complete(Unit)
            awaitGate(bPreInstall)
            withTimeout(2000) {
                while (vm.registry.latestToken(convId)?.state() != PendingStart.State.CANCELLED) {
                    yield()
                }
            }
            bPreInstallRelease.complete(Unit)

            val bResult = withTimeout(2000) { bDeferred.await() }
            assertTrue("B start should have thrown CancellationException", bResult is CancellationException)
            assertEquals(1, bBlockEntered.count)

            withTimeout(2000) { cancelB.await() }

            assertNull("registry should not contain A", vm.registry.activeSession(convId))
            assertFalse("registry should not have active session for A", vm.registry.isActive(convId, "A"))
            assertFalse("registry should not have active session for B", vm.registry.isActive(convId, "B"))

            val stateAfter = vm.state(convId)
            assertNull(stateAfter.activeRequestId)
            assertFalse(stateAfter.isGenerating)
            assertNull(stateAfter.streamingText)
            assertNull(stateAfter.streamingMetricsLine)
            assertNull(stateAfter.generationStatus)
            assertNull(stateAfter.regeneratingMessageId)
            assertFalse(stateAfter.extractingMemoryCandidates)
            assertFalse(stateAfter.actualWebSearchUsed)

            withTimeout(2000) { aSession.job.join() }
        } finally {
            aCleanupRelease.complete(Unit)
            bHold.complete(Unit)
            bPreInstallRelease.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancelAndAwait cancels pending B and active A and clears state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aCleanupStarted = CountDownLatch(1)
        val aCleanupRelease = CompletableDeferred<Unit>()
        val aStarted = CountDownLatch(1)
        val bBlockEntered = CountDownLatch(1)
        val bHold = CompletableDeferred<Unit>()
        val bPreInstall = CountDownLatch(1)
        val bPreInstallRelease = CompletableDeferred<Unit>()
        try {
            val vm = tracked(ChatViewModel())
            val convId = 108L

            vm.update(convId) { it.copy(
                activeRequestId = "A",
                isGenerating = true,
                streamingText = "A-stream",
            ) }

            val aSession = withTimeout(2000) {
                vm.registry.start(scope, snapshot(convId, "A")) {
                    aStarted.countDown()
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        aCleanupStarted.countDown()
                        withContext(NonCancellable) {
                            aCleanupRelease.await()
                        }
                    }
                }
            }

            awaitGate(aStarted)

            async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                vm.registry.cancelAndJoin(convId, "A", "stop-A")
            }

            awaitGate(aCleanupStarted)

            val bDeferred = async(Dispatchers.Default) {
                try {
                    vm.registry.start(scope, snapshot(convId, "B")) {
                        bBlockEntered.countDown()
                        bHold.await()
                    }
                    null
                } catch (e: CancellationException) {
                    e
                }
            }

            vm.registry.onBeforeInstall = {
                bPreInstall.countDown()
                bPreInstallRelease.await()
            }

            withTimeout(2000) {
                while (vm.registry.latestToken(convId)?.requestId != "B") {
                    yield()
                }
            }

            val deleteDone = async(Dispatchers.Default) {
                vm.cancelAndAwait(convId, "delete")
            }

            aCleanupRelease.complete(Unit)
            awaitGate(bPreInstall)
            withTimeout(2000) {
                while (vm.registry.latestToken(convId)?.state() != PendingStart.State.CANCELLED) {
                    yield()
                }
            }
            bPreInstallRelease.complete(Unit)

            val bResult = withTimeout(2000) { bDeferred.await() }
            assertTrue("B start should have thrown CancellationException",
                bResult is CancellationException)
            assertEquals(1, bBlockEntered.count)

            withTimeout(2000) { deleteDone.await() }

            assertFalse("no active session for A after deletion",
                vm.registry.isActive(convId, "A"))
            assertFalse("no active session for B after deletion",
                vm.registry.isActive(convId, "B"))
            assertFalse("no pending request after deletion",
                vm.registry.isPending(convId, "A") || vm.registry.isPending(convId, "B"))

            val state = vm.state(convId)
            assertNull("state should be cleared after deletion", state.activeRequestId)
            assertFalse(state.isGenerating)

            withTimeout(2000) { aSession.job.join() }
        } finally {
            aCleanupRelease.complete(Unit)
            bHold.complete(Unit)
            bPreInstallRelease.complete(Unit)
            scope.cancel()
            withTimeout(2000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    // -- helpers --

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

    // ── Phase 4: durable runtime diagnostics ─────────────────────────────

    @Test
    fun `finishRequestState clears transient fields but preserves lastRuntimeDiagnostics`() {
        val vm = tracked(ChatViewModel())
        val convId = 900L
        val diagnostics = RuntimeDiagnostics(
            requestedConfig = RequestedConfig(
                modelId = "Gemma 4 E2B-it",
                accelerator = com.projectnuke.fusion.model.AcceleratorMode.GPU,
                kvCacheCapacityTokens = 4096,
                mtpRequested = true,
                visionRequested = false,
                speculativeDecodingEnabled = true,
            ),
            attemptedBackend = "GPU",
            selectedBackend = "CPU",
            mtpRequested = true,
            mtpRuntimeStatus = com.projectnuke.fusion.llm.MtpRuntimeStatus.FALLBACK_DISABLED,
            fallbackEventCodes = listOf(com.projectnuke.fusion.llm.FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED.name),
            attemptSnapshot = null,
            failureSnapshot = null,
        )
        vm.update(convId) {
            it.copy(
                activeRequestId = "R1",
                isGenerating = true,
                streamingText = "partial",
                streamingMetricsLine = "metrics",
                generationStatus = "오류 발생",
                lastRuntimeDiagnostics = diagnostics,
            )
        }

        vm.finishRequestState(convId, "R1")

        val after = vm.state(convId)
        assertFalse(after.isGenerating)
        assertNull(after.activeRequestId)
        assertNull("streamingText must be cleared", after.streamingText)
        assertNull("streamingMetricsLine must be cleared", after.streamingMetricsLine)
        assertNull("generationStatus must be cleared", after.generationStatus)
        assertEquals(
            "lastRuntimeDiagnostics must survive request finalization",
            diagnostics,
            after.lastRuntimeDiagnostics,
        )
    }

    @Test
    fun `lastRuntimeDiagnostics survives repeated finalization and is replaced by a newer request`() {
        val vm = tracked(ChatViewModel())
        val convId = 901L
        fun diag(backend: String) = RuntimeDiagnostics(
            requestedConfig = RequestedConfig(
                modelId = "Gemma 4 E2B-it",
                accelerator = com.projectnuke.fusion.model.AcceleratorMode.GPU,
                kvCacheCapacityTokens = 4096,
                mtpRequested = false,
                visionRequested = false,
                speculativeDecodingEnabled = false,
            ),
            attemptedBackend = backend,
            selectedBackend = backend,
            mtpRequested = false,
            mtpRuntimeStatus = com.projectnuke.fusion.llm.MtpRuntimeStatus.OFF,
            fallbackEventCodes = emptyList(),
            attemptSnapshot = null,
            failureSnapshot = null,
        )

        vm.update(convId) { it.copy(activeRequestId = "R1", isGenerating = true, lastRuntimeDiagnostics = diag("GPU")) }
        vm.finishRequestState(convId, "R1")
        assertEquals("GPU", vm.state(convId).lastRuntimeDiagnostics?.selectedBackend)

        // Second request replaces the diagnostics with its own.
        vm.update(convId) { it.copy(activeRequestId = "R2", isGenerating = true) }
        vm.updateRequestState(convId, "R2") { it.copy(lastRuntimeDiagnostics = diag("CPU")) }
        vm.finishRequestState(convId, "R2")
        assertEquals("CPU", vm.state(convId).lastRuntimeDiagnostics?.selectedBackend)
    }

    companion object {
        private fun awaitGate(gate: CountDownLatch) {
            assertTrue("synchronization gate was not reached", gate.await(5, TimeUnit.SECONDS))
        }
    }
}
