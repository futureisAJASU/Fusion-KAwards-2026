package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCompactionAdoptionTest {
    private val oldMemory = AutoCompactMemory(
        conversationId = 7L,
        summary = "old summary",
        compactedThroughMessageId = 10L,
        updatedAt = 1L,
        sourceTurnCount = 2,
    )
    private val newMemory = oldMemory.copy(
        summary = "new summary",
        compactedThroughMessageId = 20L,
        updatedAt = 2L,
        sourceTurnCount = 4,
    )

    @Test
    fun `successful durable adoption keeps new projection and boundary`() = runBlocking {
        val newPreparation = PreparedLocalAutoCompaction(
            promptMessages = listOf(ChatMessage("system", "new summary")),
            memory = newMemory,
            compactConsidered = true,
            compactUsed = true,
            fallbackTrimmed = false,
        )

        val adopted = adoptPreparedLocalAutoCompaction(
            preparation = newPreparation,
            persist = { saved -> saved == newMemory },
            fallback = { error("fallback must not run") },
        )

        assertEquals(newPreparation, adopted)
        assertTrue(adopted.compactUsed)
        assertEquals(20L, adopted.memory?.compactedThroughMessageId)
    }

    @Test
    fun `failed durable adoption uses old projection and does not claim compact use`() = runBlocking {
        val newPreparation = PreparedLocalAutoCompaction(
            promptMessages = listOf(ChatMessage("system", "new summary")),
            memory = newMemory,
            compactConsidered = true,
            compactUsed = true,
            fallbackTrimmed = false,
        )
        val oldProjection = PreparedLocalAutoCompaction(
            promptMessages = listOf(ChatMessage("system", "old summary")),
            memory = oldMemory,
            compactConsidered = true,
            compactUsed = false,
            fallbackTrimmed = true,
        )

        val adopted = adoptPreparedLocalAutoCompaction(
            preparation = newPreparation,
            persist = { false },
            fallback = { oldProjection },
        )

        assertEquals(oldProjection.promptMessages, adopted.promptMessages)
        assertEquals(oldMemory, adopted.memory)
        assertTrue(adopted.compactConsidered)
        assertFalse(adopted.compactUsed)
        assertTrue(adopted.fallbackTrimmed)
        assertTrue(adopted.adoptionFailed)
    }

    @Test
    fun `nonfatal durable adoption exception uses fallback`() = runBlocking {
        val newPreparation = PreparedLocalAutoCompaction(
            promptMessages = listOf(ChatMessage("system", "new summary")),
            memory = newMemory,
            compactConsidered = true,
            compactUsed = true,
            fallbackTrimmed = false,
        )
        val oldProjection = newPreparation.copy(
            promptMessages = listOf(ChatMessage("system", "old summary")),
            memory = oldMemory,
            compactUsed = false,
            fallbackTrimmed = false,
        )

        val adopted = adoptPreparedLocalAutoCompaction(
            preparation = newPreparation,
            persist = { throw IllegalStateException("storage unavailable") },
            fallback = { oldProjection },
        )

        assertEquals(oldProjection.promptMessages, adopted.promptMessages)
        assertEquals(oldMemory, adopted.memory)
        assertFalse(adopted.compactUsed)
        assertTrue(adopted.adoptionFailed)
    }

    @Test
    fun `cancellation before adoption propagates without persisting new memory`() = runBlocking {
        val newPreparation = PreparedLocalAutoCompaction(
            promptMessages = listOf(ChatMessage("system", "new summary")),
            memory = newMemory,
            compactConsidered = true,
            compactUsed = true,
            fallbackTrimmed = false,
        )
        var persisted = false
        val validationStarted = CompletableDeferred<Unit>()
        val job: Job = launch {
            adoptPreparedLocalAutoCompaction(
                preparation = newPreparation,
                persist = { persisted = true; true },
                fallback = { error("cancelled adoption must not use fallback") },
                isCurrentBranchValid = {
                    validationStarted.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                },
            )
        }
        validationStarted.await()
        job.cancelAndJoin()

        assertFalse(persisted)
        assertTrue(job.isCancelled)
    }
}
