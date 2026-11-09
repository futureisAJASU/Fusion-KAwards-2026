package com.projectnuke.fusion.chat

import com.projectnuke.fusion.llm.BackgroundTerminationSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicFollowUpOwnershipLeaseTest {
    @Test
    fun `conversation switch lets native result finish but blocks stale publication`() = runBlocking {
        val lease = DynamicFollowUpOwnershipLease()
        val frozen = ownership()
        var current = frozen
        var retiredSource: BackgroundTerminationSource? = null
        lease.observeRetirement { retiredSource = it }
        val nativeStarted = CompletableDeferred<Unit>()
        val finishNative = CompletableDeferred<List<String>>()
        val native = async {
            nativeStarted.complete(Unit)
            finishNative.await()
        }

        nativeStarted.await()
        current = frozen.copy(conversationId = frozen.conversationId + 1)
        lease.invalidate(BackgroundTerminationSource.CONVERSATION_SWITCH)
        finishNative.complete(listOf("What should be explored next?"))
        val completedQuestions = native.await()
        val published = if (lease.isCurrent && DynamicFollowUpPolicy.isOwnershipCurrent(frozen, current)) {
            completedQuestions
        } else {
            emptyList()
        }

        assertEquals(BackgroundTerminationSource.CONVERSATION_SWITCH, retiredSource)
        assertEquals(listOf("What should be explored next?"), completedQuestions)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `release containment switch skips ticket and native generation while deterministic row stays`() = runBlocking {
        val previous = DynamicFollowUpRuntimePolicy.testDisableNative
        var tickets = 0
        var generations = 0
        try {
            DynamicFollowUpRuntimePolicy.testDisableNative = true
            assertFalse(DynamicFollowUpRuntimePolicy.isNativeGenerationEnabled())
            val result = DynamicFollowUpRuntimePolicy.runNativeGenerationIfEnabled {
                tickets++
                generations++
                listOf("dynamic")
            }

            assertNull(result)
            assertEquals(0, tickets)
            assertEquals(0, generations)

            val deterministic = NextActionSuggestion(
                id = "deterministic-summary",
                kind = NextActionKind.SHORT_SUMMARY,
                label = "Summarize",
                target = NextActionTarget.ComposerPrompt("Summarize this answer"),
            )
            assertEquals(
                listOf(deterministic),
                DynamicFollowUpPolicy.composeRow(emptyList(), listOf(deterministic)),
            )
        } finally {
            DynamicFollowUpRuntimePolicy.testDisableNative = previous
        }
    }

    private fun ownership() = DynamicFollowUpOwnership(
        conversationId = 9L,
        newChatEpoch = 3L,
        userTurnId = 14L,
        assistantMessageId = 15L,
        versionGroupId = 16L,
        activeResponseMessageId = 17L,
    )
}
