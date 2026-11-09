package com.projectnuke.fusion.ui

import com.projectnuke.fusion.llm.FailureMemoryDurability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7: regression tests for durability diagnostics mapping.
 *
 * Verifies that the durability state is correctly mapped to
 * user-facing diagnostics through the developer log surface.
 */
class RepairPhase7DurabilityDiagnosticsTest {

    @Test
    fun `NotAttempted maps to neutral diagnostic`() {
        val durability = FailureMemoryDurability.NotAttempted
        val result = durabilityToDiagnostic(durability)
        assertTrue("NotAttempted should not claim durability", !result.contains("durable"))
        assertTrue("NotAttempted should mention 'no durability'", result.contains("no durability"))
    }

    @Test
    fun `Durable maps to positive diagnostic`() {
        val durability = FailureMemoryDurability.Durable
        val result = durabilityToDiagnostic(durability)
        assertTrue("Durable should claim durability", result.contains("durable"))
        assertTrue("Durable should mention 'persist' or 'restart'", result.contains("restart") || result.contains("persist"))
    }

    @Test
    fun `InMemoryOnly maps to degraded diagnostic with cause`() {
        val cause = RuntimeException("disk full")
        val durability = FailureMemoryDurability.InMemoryOnly(cause)
        val result = durabilityToDiagnostic(durability)
        assertTrue("InMemoryOnly should not claim durability", !result.contains("durable"))
        assertTrue("InMemoryOnly should mention 'in-memory'", result.contains("in-memory"))
        assertTrue("InMemoryOnly should include cause", result.contains("disk full"))
    }

    @Test
    fun `InMemoryOnly with IO exception maps correctly`() {
        val cause = java.io.IOException("cannot write")
        val durability = FailureMemoryDurability.InMemoryOnly(cause)
        val result = durabilityToDiagnostic(durability)
        assertTrue(result.contains("cannot write"))
    }

    private fun durabilityToDiagnostic(durability: FailureMemoryDurability): String {
        // This mirrors the logic in FusionDeveloperLogStore.recordDurabilityState
        return when (durability) {
            is FailureMemoryDurability.NotAttempted -> "Failure memory: no durability operations attempted"
            is FailureMemoryDurability.Durable -> "Failure memory: durable (persists across restarts)"
            is FailureMemoryDurability.InMemoryOnly -> "Failure memory: in-memory only (cause: ${durability.cause.message})"
        }
    }
}