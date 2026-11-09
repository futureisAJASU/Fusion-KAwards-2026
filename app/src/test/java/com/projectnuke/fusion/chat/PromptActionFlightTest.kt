package com.projectnuke.fusion.chat

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PromptActionFlightTest {

    @Test
    fun `first action owns the flight and second tap is ignored`() = runTest {
        val flight = PromptActionFlight()
        assertTrue(flight.tryAcquire())
        assertTrue(flight.inFlight.value)

        var appends = 0
        assertFalse(flight.runAction { appends++ })
        assertEquals(0, appends)

        flight.release()
        assertFalse(flight.inFlight.value)
        assertTrue(flight.runAction { appends++ })
        assertEquals(1, appends)
    }

    @Test
    fun `rapid duplicate action attempts perform one logical append`() = runTest {
        val flight = PromptActionFlight()
        var appends = 0

        // Simulate the winning tap holding the flight while a duplicate tap
        // races it: only the owner may run the append exactly once.
        assertTrue(flight.tryAcquire())
        assertFalse(flight.runAction { appends++ })
        flight.release()

        assertTrue(flight.runAction { appends++ })
        assertEquals(1, appends)
    }

    @Test
    fun `concurrent acquisition grants exactly one owner`() {
        val flight = PromptActionFlight()
        val wins = AtomicInteger(0)
        val threads = List(16) {
            Thread {
                if (flight.tryAcquire()) wins.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }

        assertEquals(1, wins.get())
        flight.release()
    }

    @Test
    fun `flight releases after action failure`() = runTest {
        val flight = PromptActionFlight()

        try {
            flight.runAction { throw IllegalStateException("boom") }
            fail("expected the action failure to propagate")
        } catch (_: IllegalStateException) {
        }
        assertFalse(flight.inFlight.value)
        assertTrue(flight.tryAcquire())
        flight.release()
    }

    @Test
    fun `release is idempotent and safe without ownership`() {
        val flight = PromptActionFlight()

        flight.release()
        flight.release()
        assertFalse(flight.inFlight.value)
        assertTrue(flight.tryAcquire())
        flight.release()
    }
}
