package com.projectnuke.fusion.chat

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeletionOwnershipTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherIsolationRule { scheduler ->
        UnconfinedTestDispatcher(scheduler)
    }

    private val mainDispatcher get() = mainDispatcherRule.dispatcher

    @Test
    fun `deleteConversation holds deferred deletion ownership until completion`() = runBlocking {
        val viewModel = ChatViewModel()
        val blockerInOnBeforeInstall = CountDownLatch(1)
        val replacementClaimed = CompletableDeferred<Unit>()
        val onBeforeInstallRelease = CompletableDeferred<Unit>()
        val blockerRelease = CompletableDeferred<Unit>()
        try {
            viewModel.registry.onBeforeInstall = {
                blockerInOnBeforeInstall.countDown()
                onBeforeInstallRelease.await()
            }
            val tokenClaims = AtomicInteger(0)
            viewModel.registry.onAfterTokenClaim = {
                if (tokenClaims.incrementAndGet() == 2) replacementClaimed.complete(Unit)
            }

            val stripeBlocker = async(Dispatchers.Default) {
                try {
                    viewModel.registry.start(viewModel.scope, snapshot(90L, "blocker")) {
                        blockerRelease.await()
                    }
                    "installed"
                } catch (_: CancellationException) { "cancelled" }
            }
            awaitGate(blockerInOnBeforeInstall)

            val deletion = viewModel.deleteConversation(
                conversationId = 90L,
                exists = { true },
                commitDelete = {},
                settleTarget = {},
                cleanupDerivedData = {},
                recordCleanupDebt = {},
            )

            withTimeout(2000) {
                while (viewModel.registry.deletionOwner(90L) == null) { yield() }
            }
            assertEquals("delete-conversation", viewModel.registry.deletionOwner(90L))

            val startDuringDeletion = async(Dispatchers.Default) {
                try {
                    viewModel.registry.start(viewModel.scope, snapshot(90L, "blocked")) {
                        CompletableDeferred<Unit>().await()
                    }
                    "installed"
                } catch (e: CancellationException) { "cancelled:${e.message}" }
            }
            replacementClaimed.await()
            onBeforeInstallRelease.complete(Unit)
            withTimeout(2000) { stripeBlocker.await() }

            mainDispatcher.scheduler.advanceUntilIdle()

            assertTrue("start must be refused while deletion owns the conversation",
                withTimeout(2000) { startDuringDeletion.await() }.startsWith("cancelled:"))
            assertNull(viewModel.registry.activeSession(90L))

            assertEquals(ConversationDeletionResult.DELETED_CLEAN,
                withTimeout(2000) { deletion.await() })
            assertNull("ownership must be released after deletion completes",
                viewModel.registry.deletionOwner(90L))
        } finally {
            onBeforeInstallRelease.complete(Unit)
            blockerRelease.complete(Unit)
            viewModel.registry.onAfterTokenClaim = {}
            viewModel.scope.cancel()
            withTimeout(2000) { viewModel.scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancelled deleteConversation still releases deferred ownership`() = runBlocking {
        val viewModel = ChatViewModel()
        val release = CompletableDeferred<Unit>()
        try {
            val deletion = viewModel.deleteConversation(
                conversationId = 91L,
                exists = { release.await(); true },
                commitDelete = {},
                settleTarget = {},
                cleanupDerivedData = {},
                recordCleanupDebt = {},
            )

            withTimeout(2000) {
                while (viewModel.registry.deletionOwner(91L) == null) { yield() }
            }

            deletion.cancel()

            withTimeout(2000) {
                while (viewModel.registry.deletionOwner(91L) != null) {
                    mainDispatcher.scheduler.runCurrent()
                }
            }
            assertNull("ownership must be released even when deletion is cancelled",
                viewModel.registry.deletionOwner(91L))
        } finally {
            release.complete(Unit)
            viewModel.scope.cancel()
            withTimeout(2000) { viewModel.scope.coroutineContext[Job]!!.join() }
        }
    }

    private fun awaitGate(gate: CountDownLatch) {
        assertTrue("synchronization gate was not reached", gate.await(5, TimeUnit.SECONDS))
    }

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
}
