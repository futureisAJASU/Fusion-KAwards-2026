package com.projectnuke.fusion.llm

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAdmissionFatalFailureTest {
    @Test
    fun `fatal native preemption releases chat admission and cancels background`() = runBlocking {
        assertFailedAdmissionSettles(OutOfMemoryError("native stop")) {
            FusionRuntimeLock.withChatGeneration { error("must not enter chat") }
        }
    }

    @Test
    fun `fatal native preemption releases benchmark admission and cancels background`() = runBlocking {
        assertFailedAdmissionSettles(LinkageError("native stop")) {
            FusionRuntimeLock.withExclusiveBenchmark(
                onPrepareExclusiveMode = { error("must not prepare benchmark") },
            ) { error("must not enter benchmark") }
        }
    }

    private suspend fun assertFailedAdmissionSettles(fatal: Error, admit: suspend () -> Unit) {
        val baseline = BackgroundRuntimeOwner.foregroundReservationCount()
        assertEquals(0, baseline)
        val background = Job()
        val ticket = requireNotNull(BackgroundRuntimeOwner.tryAcquire(background, 7L))
        BackgroundRuntimeOwner.controller.registerCancellationHook(ticket.key, this) { throw fatal }
        try {
            assertSame(fatal, runCatching { admit() }.exceptionOrNull())
            assertEquals("failed admission must release its reservation", baseline,
                BackgroundRuntimeOwner.foregroundReservationCount())
            assertTrue("fatal native stop must still cancel the background job", background.isCancelled)
            assertFalse(FusionRuntimeLock.isChatGenerationRunning)
            assertFalse(FusionRuntimeLock.isBenchmarkRunning)
            BackgroundRuntimeOwner.release(ticket)
            val next = Job()
            val nextTicket = BackgroundRuntimeOwner.tryAcquire(next, 8L)
            try {
                assertNotNull("a failed admission must not block future background work", nextTicket)
            } finally {
                nextTicket?.let(BackgroundRuntimeOwner::release)
                next.cancel()
            }
        } finally {
            BackgroundRuntimeOwner.release(ticket)
            background.cancel()
            // Keep a RED assertion from leaking process-global state into another test.
            while (BackgroundRuntimeOwner.foregroundReservationCount() > baseline) {
                BackgroundRuntimeOwner.endForegroundAdmission()
            }
        }
    }

    @Test
    fun `failed preemption retains a separately completed foreground admission`() {
        val background = Job()
        val ticket = requireNotNull(BackgroundRuntimeOwner.tryAcquire(background, 7L))
        val fatal = LinkageError("outer native stop")
        var nestedAdmitted = false
        BackgroundRuntimeOwner.controller.registerCancellationHook(ticket.key, this) {
            BackgroundRuntimeOwner.beginForegroundAdmission()
            nestedAdmitted = true
            throw fatal
        }
        try {
            assertSame(fatal, runCatching { BackgroundRuntimeOwner.beginForegroundAdmission() }.exceptionOrNull())
            assertTrue(nestedAdmitted)
            assertEquals("only the nested admission still owns a reservation", 1,
                BackgroundRuntimeOwner.foregroundReservationCount())
            BackgroundRuntimeOwner.endForegroundAdmission()
            assertEquals(0, BackgroundRuntimeOwner.foregroundReservationCount())
        } finally {
            BackgroundRuntimeOwner.release(ticket)
            background.cancel()
            while (BackgroundRuntimeOwner.foregroundReservationCount() > 0) {
                BackgroundRuntimeOwner.endForegroundAdmission()
            }
        }
    }
}
