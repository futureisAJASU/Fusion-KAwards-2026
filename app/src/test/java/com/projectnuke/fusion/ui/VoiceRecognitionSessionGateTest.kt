package com.projectnuke.fusion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Production adapter seam: session identity is controller-owned. The gate
// only records the externally supplied token and invalidates it on
// cancel/destroy — it never mints a replacement token, so repeated
// cancel/start cycles cannot drift callback identity away from the
// controller's session.
class VoiceRecognitionSessionGateTest {

    @Test
    fun `start cancel start reports the newest session`() {
        val gate = RecognitionSessionGate()
        gate.onStart(1L)
        assertTrue(gate.accepts(1L))
        gate.onCancel()
        assertFalse(gate.accepts(1L))
        gate.onStart(2L)
        assertTrue(gate.accepts(2L))
        assertFalse(gate.accepts(1L))
        assertEquals(2L, gate.current())
    }

    @Test
    fun `repeated cancel start does not drift`() {
        val gate = RecognitionSessionGate()
        var token = 0L
        repeat(5) {
            gate.onCancel()
            token++
            gate.onStart(token)
            assertTrue(gate.accepts(token))
            assertEquals(token, gate.current())
        }
        // Every superseded token stays stale.
        for (stale in 1L until token) {
            assertFalse(gate.accepts(stale))
        }
    }

    @Test
    fun `replacing a live session does not drift`() {
        val gate = RecognitionSessionGate()
        gate.onStart(1L)
        gate.onStart(2L)
        assertTrue(gate.accepts(2L))
        assertFalse(gate.accepts(1L))
        assertEquals(2L, gate.current())
    }

    @Test
    fun `stale callback from old recognizer is ignored`() {
        val gate = RecognitionSessionGate()
        gate.onStart(7L)
        gate.onCancel()
        assertFalse(gate.accepts(7L))
        assertEquals(0L, gate.current())
    }

    @Test
    fun `zero token is never live`() {
        val gate = RecognitionSessionGate()
        assertFalse(gate.accepts(0L))
        gate.onStart(3L)
        assertFalse(gate.accepts(0L))
    }
}
