package com.projectnuke.fusion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionHealthCheckRequestGateTest {
    @Test
    fun `only latest health check may publish`() {
        val gate = FusionHealthCheckRequestGate()
        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.isLatest(first))
        assertTrue(gate.isLatest(second))
    }

    @Test
    fun `dialog disposal invalidates in flight result`() {
        val gate = FusionHealthCheckRequestGate()
        val request = gate.begin()

        gate.invalidate()

        assertFalse(gate.isLatest(request))
    }
}
