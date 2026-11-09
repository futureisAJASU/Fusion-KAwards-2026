package com.projectnuke.fusion.tasks

import com.projectnuke.fusion.study.QuickReviewCardReminderMutationGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderMutationRaceTest {
    private data class ReminderState(
        var enabled: Boolean = true,
        var at: Long? = OLD_AT,
        var generation: Long = OLD_GENERATION,
        var visibleAt: Long? = null,
        var publishCount: Int = 0,
        var consumeCount: Int = 0,
    )

    private suspend fun deliver(
        gate: Mutex,
        state: ReminderState,
        expectedAt: Long,
        expectedGeneration: Long = state.generation,
        beforePublish: suspend () -> Unit = {},
        beforeConsume: suspend () -> Unit = {},
        publish: () -> Boolean = { true },
    ): Boolean = publishCurrentReminderUnderGate(
        mutex = gate,
        isCurrent = {
            state.enabled && state.at == expectedAt && state.generation == expectedGeneration
        },
        publish = {
            beforePublish()
            if (publish()) {
                // Models notify(stableId, ...) as an upsert, not a new alert.
                state.visibleAt = expectedAt
                state.publishCount++
                true
            } else false
        },
        consume = {
            beforeConsume()
            if (state.enabled && state.at == expectedAt && state.generation == expectedGeneration) {
                state.enabled = false
                state.consumeCount++
                1
            } else 0
        },
        discardPublished = { state.visibleAt = null },
    )

    private suspend fun cancel(gate: Mutex, state: ReminderState) {
        gate.withLock {
            state.enabled = false
            state.at = null
            state.generation++
            state.visibleAt = null
        }
    }

    private suspend fun reschedule(gate: Mutex, state: ReminderState, at: Long) {
        gate.withLock {
            state.visibleAt = null
            state.at = at
            state.enabled = true
            state.generation++
        }
    }

    @Test(timeout = 5_000)
    fun cancellationBetweenValidationAndPostWaitsThenRemovesPublishedReminder() = runBlocking {
        val gate = Mutex()
        val state = ReminderState()
        val validated = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val delivery = async {
            deliver(gate, state, OLD_AT, beforePublish = {
                validated.complete(Unit)
                release.await()
            })
        }
        validated.await()
        val cancellationStarted = CompletableDeferred<Unit>()
        val cancellation = async {
            cancellationStarted.complete(Unit)
            cancel(gate, state)
        }
        cancellationStarted.await()
        yield()
        assertFalse("cancellation must serialize behind publication/consume", cancellation.isCompleted)

        release.complete(Unit)
        assertTrue(delivery.await())
        cancellation.await()
        assertFalse(state.enabled)
        assertNull(state.visibleAt)
    }

    @Test(timeout = 5_000)
    fun rescheduleBeforePostRemovesOldNotificationAndNewReminderStillDelivers() = runBlocking {
        val gate = Mutex()
        val state = ReminderState()
        val validated = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val oldDelivery = async {
            deliver(gate, state, OLD_AT, beforePublish = {
                validated.complete(Unit)
                release.await()
            })
        }
        validated.await()
        val reschedule = async {
            reschedule(gate, state, NEW_AT)
        }
        yield()
        assertFalse(reschedule.isCompleted)

        release.complete(Unit)
        assertTrue(oldDelivery.await())
        reschedule.await()
        assertEquals(NEW_AT, state.at)
        assertTrue(state.enabled)
        assertNull("the old notification is cleared as part of replacement", state.visibleAt)

        assertTrue(deliver(gate, state, NEW_AT))
        assertEquals(NEW_AT, state.visibleAt)
        assertFalse(state.enabled)
    }

    @Test(timeout = 5_000)
    fun cancellationAfterPostBeforeConsumeCannotLeaveVisibleNotification() = runBlocking {
        val gate = Mutex()
        val state = ReminderState()
        val posted = CompletableDeferred<Unit>()
        val releaseConsume = CompletableDeferred<Unit>()

        val delivery = async {
            deliver(gate, state, OLD_AT, beforeConsume = {
                posted.complete(Unit)
                releaseConsume.await()
            })
        }
        posted.await()
        assertEquals(OLD_AT, state.visibleAt)
        val cancellation = async { cancel(gate, state) }
        yield()
        assertFalse(cancellation.isCompleted)

        releaseConsume.complete(Unit)
        assertTrue(delivery.await())
        cancellation.await()
        assertFalse(state.enabled)
        assertNull(state.visibleAt)
    }

    @Test(timeout = 5_000)
    fun delayedOldDeliveryCannotConsumeOrReplaceNewReminder() = runBlocking {
        val gate = Mutex()
        val state = ReminderState()
        val oldGeneration = state.generation
        cancel(gate, state)
        // Reusing the same target time is still a new durable generation.
        reschedule(gate, state, OLD_AT)

        assertFalse(deliver(gate, state, OLD_AT, expectedGeneration = oldGeneration))
        assertTrue(state.enabled)
        assertEquals(OLD_AT, state.at)
        assertEquals(oldGeneration + 2L, state.generation)
        assertNull(state.visibleAt)
        assertEquals(0, state.consumeCount)
    }

    @Test(timeout = 5_000)
    fun concurrentAlarmAndReconciliationDeliveriesPublishOnlyOnce() = runBlocking {
        val gate = Mutex()
        val state = ReminderState()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            deliver(gate, state, OLD_AT, beforePublish = {
                firstEntered.complete(Unit)
                releaseFirst.await()
            })
        }
        firstEntered.await()
        val second = async { deliver(gate, state, OLD_AT) }
        yield()
        assertFalse(second.isCompleted)

        releaseFirst.complete(Unit)
        assertTrue(first.await())
        assertFalse(second.await())
        assertEquals(1, state.publishCount)
        assertEquals(1, state.consumeCount)
        assertEquals(OLD_AT, state.visibleAt)
    }

    @Test(timeout = 5_000)
    fun failedPublicationLeavesReminderRetryableAndExceptionsPropagate() = runBlocking {
        val gate = Mutex()
        val state = ReminderState()
        assertFalse(deliver(gate, state, OLD_AT, publish = { false }))
        assertTrue(state.enabled)
        assertEquals(0, state.consumeCount)
        assertNull(state.visibleAt)

        val failure = runCatching {
            deliver(gate, state, OLD_AT, publish = { throw SecurityException("notify denied") })
        }.exceptionOrNull()
        assertTrue(failure is SecurityException)
        assertTrue(state.enabled)
        assertEquals(0, state.consumeCount)

        assertTrue(deliver(gate, state, OLD_AT))
        assertEquals(1, state.consumeCount)
    }

    @Test(timeout = 5_000)
    fun interruptedConsumptionCanRetryByUpdatingTheSameNotificationIdentity() = runBlocking {
        val gate = Mutex()
        val state = ReminderState()
        val interrupted = runCatching {
            publishCurrentReminderUnderGate(
                mutex = gate,
                isCurrent = { state.enabled && state.at == OLD_AT },
                publish = { state.visibleAt = OLD_AT; state.publishCount++; true },
                consume = { throw java.io.IOException("simulated interruption after notify") },
                discardPublished = { state.visibleAt = null },
            )
        }.exceptionOrNull()
        assertTrue(interrupted is java.io.IOException)
        assertTrue(state.enabled)
        assertEquals(OLD_AT, state.visibleAt)

        assertTrue(deliver(gate, state, OLD_AT))
        assertFalse(state.enabled)
        assertEquals(OLD_AT, state.visibleAt)
        assertEquals("stable notification identity is updated on retry", 2, state.publishCount)
    }

    @Test(timeout = 5_000)
    fun cardDeliveryUsesTheSameSerializationContractAsCardMutations() = runBlocking {
        val state = ReminderState()
        val posted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delivery = async {
            deliver(QuickReviewCardReminderMutationGate.mutex, state, OLD_AT, beforeConsume = {
                posted.complete(Unit)
                release.await()
            })
        }
        posted.await()
        val cancellation = async { cancel(QuickReviewCardReminderMutationGate.mutex, state) }
        yield()
        assertFalse(cancellation.isCompleted)
        release.complete(Unit)
        assertTrue(delivery.await())
        cancellation.await()
        assertFalse(state.enabled)
        assertNull(state.visibleAt)
    }

    @Test(timeout = 5_000)
    fun interruptionAfterDurableConsumeDoesNotRetryOrRestoreTheReminder() = runBlocking {
        val gate = Mutex()
        val state = ReminderState()
        val interrupted = runCatching {
            publishCurrentReminderUnderGate(
                mutex = gate,
                isCurrent = { state.enabled && state.at == OLD_AT && state.generation == OLD_GENERATION },
                publish = { state.visibleAt = OLD_AT; true },
                consume = {
                    state.enabled = false
                    state.consumeCount++
                    throw java.io.IOException("simulated interruption after durable consume")
                },
                discardPublished = { state.visibleAt = null },
            )
        }.exceptionOrNull()

        assertTrue(interrupted is java.io.IOException)
        assertFalse("post-commit state remains consumed", state.enabled)
        assertEquals(OLD_AT, state.visibleAt)
        assertFalse(deliver(gate, state, OLD_AT, OLD_GENERATION))
        assertEquals(1, state.consumeCount)
    }

    private companion object {
        const val OLD_AT = 1_000_000L
        const val OLD_GENERATION = 1L
        const val NEW_AT = 2_000_000L
    }
}
