package com.projectnuke.fusion.verifier

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VerifierBackendCleanupOwnerTest {
    @Test
    fun `ordinary cleanup failure is recorded`() = runTest {
        val failures = mutableListOf<Throwable>()
        val owner = VerifierBackendCleanupOwner(this) { failures += it }
        owner.closeAndRecord { throw IllegalStateException("close failed") }
        assertEquals("close failed", failures.single().message)
    }

    @Test
    fun `fatal cleanup failure is recorded and propagated`() = runTest {
        val failures = mutableListOf<Throwable>()
        val owner = VerifierBackendCleanupOwner(this) { failures += it }
        val fatal = LinkageError("fatal close")
        assertSame(fatal, runCatching { owner.closeAndRecord { throw fatal } }.exceptionOrNull())
        assertSame(fatal, failures.single())
    }

    @Test
    fun `cleanup cancellation propagates without a failure record`() = runTest {
        val failures = mutableListOf<Throwable>()
        val owner = VerifierBackendCleanupOwner(this) { failures += it }
        val cancelled = CancellationException("scope cancelled")
        assertSame(cancelled, runCatching { owner.closeAndRecord { throw cancelled } }.exceptionOrNull())
        assertEquals(emptyList<Throwable>(), failures)
    }
}
