package com.projectnuke.fusion.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import com.projectnuke.fusion.llm.FusionRuntimeLock
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualCompactionOperationRegistryTest {

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
    fun `deletion waits for manual finalization before cleanup`() = runBlocking {
        coroutineScope {
            val finalizationEntered = CompletableDeferred<Unit>()
            val releaseFinalization = CompletableDeferred<Unit>()
            var deleted = false
            var compactPersisted = false
            val lease = requireNotNull(ManualCompactionOperationRegistry.tryAcquire(101L))
            val manual = launch {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        finalizationEntered.complete(Unit)
                        releaseFinalization.await()
                        compactPersisted = true
                    }
                    ManualCompactionOperationRegistry.release(lease)
                }
            }
            lease.attach(manual)

            val deletion = ManualCompactionOperationRegistry.beginDeletion(101L)
            val settle = async { deletion.cancelAndJoin() }
            withTimeout(2_000) { finalizationEntered.await() }
            assertFalse("deletion must not cross the cleanup boundary yet", settle.isCompleted)
            assertFalse(deleted)

            releaseFinalization.complete(Unit)
            withTimeout(2_000) { settle.await() }
            assertTrue("manual persistence completed before deletion cleanup", compactPersisted)
            deleted = true
            compactPersisted = false
            deletion.finish()

            assertTrue(deleted)
            assertFalse("old manual completion cannot recreate deleted state", compactPersisted)
            assertFalse(manual.isActive)
        }
    }

    @Test
    fun `deleting another conversation does not cancel manual owner`() = runBlocking {
        coroutineScope {
            val release = CompletableDeferred<Unit>()
            val leaseA = requireNotNull(ManualCompactionOperationRegistry.tryAcquire(201L))
            val manualA = launch { release.await() }
            leaseA.attach(manualA)

            val deletionB = ManualCompactionOperationRegistry.beginDeletion(202L)
            withTimeout(2_000) { deletionB.cancelAndJoin() }
            deletionB.finish()

            assertTrue(manualA.isActive)
            release.complete(Unit)
            manualA.cancelAndJoin()
            ManualCompactionOperationRegistry.release(leaseA)
        }
    }

    @Test
    fun `accepted submission and manual compaction share an atomic conflict owner`() {
        val submissionToken = "submission-owner"
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(301L, submissionToken))
        assertFalse(ManualCompactionOperationRegistry.tryAcquireSubmission(301L, "second-owner"))
        assertNull(ManualCompactionOperationRegistry.tryAcquire(301L))

        // A stale/wrong token must not release the first owner.
        ManualCompactionOperationRegistry.releaseSubmission(301L, "second-owner")
        assertFalse(ManualCompactionOperationRegistry.tryAcquireSubmission(301L, "still-blocked"))
        ManualCompactionOperationRegistry.releaseSubmission(301L, submissionToken)
        val manual = ManualCompactionOperationRegistry.tryAcquire(301L)
        assertNotNull(manual)
        ManualCompactionOperationRegistry.release(requireNotNull(manual))
    }

    @Test
    fun `submission owners are exclusive per conversation but independent across conversations`() {
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(304L, "a"))
        assertFalse(ManualCompactionOperationRegistry.tryAcquireSubmission(304L, "b"))
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(305L, "b"))

        ManualCompactionOperationRegistry.releaseSubmission(304L, "a")
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(304L, "c"))
        ManualCompactionOperationRegistry.releaseSubmission(304L, "c")
        ManualCompactionOperationRegistry.releaseSubmission(305L, "b")
        val manual = ManualCompactionOperationRegistry.tryAcquire(304L)
        assertNotNull(manual)
        ManualCompactionOperationRegistry.release(requireNotNull(manual))
    }

    @Test
    fun `accepted ChatViewModel submission rejects manual before runtime lock`() = runBlocking {
        val viewModel = tracked(ChatViewModel())
        viewModel.updateDraftText(303L, "pending submission")
        val submission = viewModel.beginSubmission(303L)

        assertNotNull(submission)
        assertFalse(FusionRuntimeLock.isChatGenerationRunning)
        assertNull(ManualCompactionOperationRegistry.tryAcquire(303L))

        viewModel.settleSubmissionOwner(303L, requireNotNull(submission).token)
        Unit
    }

    @Test
    fun `manual owner rejects a later submission before runtime reservation`() {
        val manual = requireNotNull(ManualCompactionOperationRegistry.tryAcquire(302L))
        assertFalse(ManualCompactionOperationRegistry.tryAcquireSubmission(302L, "late-submission"))
        ManualCompactionOperationRegistry.release(manual)
    }

    @Test
    fun `existing submission keeps deletion settlement suspended`() = runBlocking {
        val conversationId = 306L
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, "submission"))
        val barrier = ManualCompactionOperationRegistry.beginDeletion(conversationId)
        val settlement = async { barrier.cancelAndJoin() }

        assertFalse(settlement.isCompleted)
        ManualCompactionOperationRegistry.releaseSubmission(conversationId, "submission")
        withTimeout(2_000) { settlement.await() }
        barrier.finish()
    }

    @Test
    fun `existing attachment import owner keeps deletion settlement suspended`() = runBlocking {
        val conversationId = 307L
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, "import"))
        val barrier = ManualCompactionOperationRegistry.beginDeletion(conversationId)
        val settlement = async { barrier.cancelAndJoin() }

        assertFalse(settlement.isCompleted)
        ManualCompactionOperationRegistry.releaseSubmission(conversationId, "import")
        withTimeout(2_000) { settlement.await() }
        barrier.finish()
    }

    @Test
    fun `deletion waits for both manual and submission owners in either release order`() = runBlocking {
        suspend fun assertBothMustSettle(releaseManualFirst: Boolean, conversationId: Long) {
            val manual = requireNotNull(ManualCompactionOperationRegistry.tryAcquire(conversationId))
            // The public admission API correctly prevents this combination;
            // model the historical overlap directly to test deletion's
            // settlement predicate independently.
            manual.entry.submissions.add("submission")
            val barrier = ManualCompactionOperationRegistry.beginDeletion(conversationId)
            val settlement = async { barrier.cancelAndJoin() }

            if (releaseManualFirst) {
                ManualCompactionOperationRegistry.release(manual)
                assertFalse(settlement.isCompleted)
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, "submission")
            } else {
                ManualCompactionOperationRegistry.releaseSubmission(conversationId, "submission")
                assertFalse(settlement.isCompleted)
                ManualCompactionOperationRegistry.release(manual)
            }
            withTimeout(2_000) { settlement.await() }
            barrier.finish()
        }

        assertBothMustSettle(releaseManualFirst = true, conversationId = 308L)
        assertBothMustSettle(releaseManualFirst = false, conversationId = 309L)
    }

    @Test
    fun `wrong submission token cannot release a deletion waiter`() = runBlocking {
        val conversationId = 310L
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, "real"))
        val barrier = ManualCompactionOperationRegistry.beginDeletion(conversationId)
        val settlement = async { barrier.cancelAndJoin() }

        ManualCompactionOperationRegistry.releaseSubmission(conversationId, "stale")
        assertFalse(settlement.isCompleted)
        ManualCompactionOperationRegistry.releaseSubmission(conversationId, "real")
        withTimeout(2_000) { settlement.await() }
        barrier.finish()
    }

    @Test
    fun `idle deletion settles immediately and blocks new owners`() = runBlocking {
        val conversationId = 311L
        val barrier = ManualCompactionOperationRegistry.beginDeletion(conversationId)
        withTimeout(2_000) { barrier.cancelAndJoin() }
        assertNull(ManualCompactionOperationRegistry.tryAcquire(conversationId))
        assertFalse(ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, "new"))
        barrier.finish()
    }

    @Test
    fun `accepted submission cannot cross deletion boundary before it settles`() = runBlocking {
        val conversationId = 312L
        val viewModel = tracked(ChatViewModel())
        viewModel.updateDraftText(conversationId, "committed user message")
        val submission = requireNotNull(viewModel.beginSubmission(conversationId))
        val barrier = ManualCompactionOperationRegistry.beginDeletion(conversationId)
        var generationStartAttempted = false
        val deletionReady = async {
            barrier.cancelAndJoin()
            generationStartAttempted = true
        }

        assertFalse(deletionReady.isCompleted)
        assertFalse(generationStartAttempted)
        assertTrue(viewModel.settleSubmissionOwner(conversationId, submission.token))
        withTimeout(2_000) { deletionReady.await() }
        assertTrue(generationStartAttempted)
        barrier.finish()
    }

    @Test
    fun `duplicate deletion barriers keep submissions fenced until all finish`() = runBlocking {
        val conversationId = 313L
        val first = ManualCompactionOperationRegistry.beginDeletion(conversationId)
        val second = ManualCompactionOperationRegistry.beginDeletion(conversationId)
        try {
            first.finish()
            assertFalse(ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, "new"))
        } finally {
            second.finish()
        }
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, "new"))
        ManualCompactionOperationRegistry.releaseSubmission(conversationId, "new")
    }

    @Test
    fun `duplicate deletion barriers survive pre-existing owner settlement`() = runBlocking {
        val conversationId = 314L
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, "old"))
        val first = ManualCompactionOperationRegistry.beginDeletion(conversationId)
        val second = ManualCompactionOperationRegistry.beginDeletion(conversationId)
        first.finish()
        ManualCompactionOperationRegistry.releaseSubmission(conversationId, "old")
        assertFalse(ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, "new"))
        second.finish()
        assertTrue(ManualCompactionOperationRegistry.tryAcquireSubmission(conversationId, "new"))
        ManualCompactionOperationRegistry.releaseSubmission(conversationId, "new")
    }
}
