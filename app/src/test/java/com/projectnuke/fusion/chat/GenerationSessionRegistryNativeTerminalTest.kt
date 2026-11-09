package com.projectnuke.fusion.chat

import com.projectnuke.fusion.llm.GenerationRequestKey
import com.projectnuke.fusion.llm.GenerationTerminationIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSessionRegistryNativeTerminalTest {
    @Test
    fun `user stop supersede delete and internal cancel request native stop before job cancel`() = runBlocking {
        listOf("user-stop", "supersede", "delete", "internal").forEachIndexed { index, termination ->
            val conversationId = 88_000L + index
            val requestId = "foreground-$termination"
            val key = GenerationRequestKey(conversationId, requestId)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val registry = GenerationSessionRegistry(userStopSettlementTimeoutMs = 10L)
            val events = mutableListOf<String>()
            val registered = CompletableDeferred<Unit>()
            val releaseReplacement = CompletableDeferred<Unit>()
            try {
                registry.start(scope, snapshot(conversationId, requestId)) {
                    registry.cancellationController.registerCancellationHook(key, Any()) {
                        events += "NATIVE_CANCEL_REQUESTED"
                    }
                    registered.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (cancelled: CancellationException) {
                        events += "JOB_CANCELLED"
                        throw cancelled
                    }
                }
                withTimeout(2_000) { registered.await() }

                when (termination) {
                    "user-stop" -> registry.stopAndJoin(conversationId, requestId)
                    "supersede" -> registry.start(scope, snapshot(conversationId, "replacement-$index")) {
                        releaseReplacement.await()
                    }
                    "delete" -> {
                        assertTrue(registry.claimDeletionOwnership(conversationId, "qualification-delete"))
                        registry.settleForDeletion(conversationId, "qualification-delete")
                    }
                    else -> registry.cancelAndJoin(
                        conversationId = conversationId,
                        requestId = requestId,
                        intent = GenerationTerminationIntent.INTERNAL_CANCEL,
                        reason = "qualification-internal-cancel",
                    )
                }

                assertTrue("$termination did not request native cancel: $events", events.contains("NATIVE_CANCEL_REQUESTED"))
                assertTrue("$termination did not cancel its Job: $events", events.contains("JOB_CANCELLED"))
                assertTrue(
                    "$termination cancelled the Job before requesting native cancel: $events",
                    events.indexOf("NATIVE_CANCEL_REQUESTED") < events.indexOf("JOB_CANCELLED"),
                )
            } finally {
                releaseReplacement.complete(Unit)
                scope.cancel()
                withTimeout(2_000) { scope.coroutineContext[Job]!!.join() }
            }
        }
    }

    private fun snapshot(conversationId: Long, requestId: String) = GenerationRequestSnapshot(
        requestId = requestId,
        conversationId = conversationId,
        generationModeKey = "LOCAL_MODEL",
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
