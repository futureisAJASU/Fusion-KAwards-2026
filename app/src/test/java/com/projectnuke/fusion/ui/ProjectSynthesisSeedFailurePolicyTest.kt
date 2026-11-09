package com.projectnuke.fusion.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fatal-error policy for the Project Synthesis fresh-chat seed boundary:
 * ordinary RuntimeException stays a bounded recoverable failure reported
 * through the onFailure seam, structural cancellation propagates, and fatal
 * runtime Errors (LinkageError, VirtualMachineError, ...) propagate instead of
 * being converted into the ordinary `seeded == null` false result.
 */
class ProjectSynthesisSeedFailurePolicyTest {

    @Test
    fun `runtime exception stays bounded and is reported`() = runTest {
        var reported: Throwable? = null
        val seeded = projectSynthesisSeedBounded<String>(
            onFailure = { reported = it },
            block = { throw RuntimeException("seed backend unavailable") },
        )
        assertNull(seeded)
        assertTrue(reported is RuntimeException)
    }

    @Test
    fun `linkage error propagates and is not reported as bounded`() = runTest {
        var reported = 0
        val failure = runCatching {
            projectSynthesisSeedBounded<String>(
                onFailure = { reported++ },
                block = { throw LinkageError("broken seed backend") },
            )
        }.exceptionOrNull()
        assertTrue("LinkageError must propagate, got: $failure", failure is LinkageError)
        assertEquals(0, reported)
    }

    @Test
    fun `cancellation propagates and is not reported as bounded`() = runTest {
        var reported = 0
        try {
            projectSynthesisSeedBounded<String>(
                onFailure = { reported++ },
                block = { throw CancellationException("seed cancelled") },
            )
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            assertEquals(0, reported)
        }
    }

    @Test
    fun `successful seed passes through untouched`() = runTest {
        var reported = 0
        val seeded = projectSynthesisSeedBounded(
            onFailure = { reported++ },
            block = { "seeded-result" },
        )
        assertEquals("seeded-result", seeded)
        assertEquals(0, reported)
    }
}
