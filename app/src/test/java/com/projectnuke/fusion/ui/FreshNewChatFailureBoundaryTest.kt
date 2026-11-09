package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.FreshNewChatResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Exception-classification contract for the Fresh New Chat commit boundary
 * (FusionApp.openFreshNewChat):
 *
 *  - an ordinary RuntimeException is a bounded retryable UI failure,
 *  - CancellationException propagates,
 *  - a fatal Error (LinkageError, VirtualMachineError, ...) propagates
 *    instead of being downgraded into an ordinary toast failure,
 *  - a committed result passes through unchanged.
 */
class FreshNewChatFailureBoundaryTest {
    // P5.4: an ordinary runtime failure stays bounded: the caller reports it
    // and treats the attempt as not committed.
    @Test
    fun `fresh new chat ordinary runtime failure is bounded`() = runTest {
        var bounded: RuntimeException? = null
        val result = attemptFreshNewChatCommit(
            commit = { throw IllegalStateException("commit failed") },
            onBoundedFailure = { bounded = it },
        )
        assertNull(result)
        assertTrue(bounded is IllegalStateException)
    }

    // P5.5: a fatal linkage failure propagates and is never bounded into a
    // retryable toast failure.
    @Test
    fun `fresh new chat linkage error propagates`() = runTest {
        try {
            attemptFreshNewChatCommit(
                commit = { throw LinkageError("commit fatal") },
                onBoundedFailure = { fail("LinkageError must not be bounded") },
            )
            fail("expected LinkageError to propagate")
        } catch (_: LinkageError) {
            // expected: fatal propagates
        }
    }

    @Test
    fun `fresh new chat cancellation propagates`() = runTest {
        try {
            attemptFreshNewChatCommit(
                commit = { throw CancellationException("cancelled") },
                onBoundedFailure = { fail("cancellation must not be bounded") },
            )
            fail("expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected: cancellation propagates
        }
    }

    @Test
    fun `fresh new chat success returns the committed result`() = runTest {
        val committed = FreshNewChatResult(success = true, epoch = 5L)
        val result = attemptFreshNewChatCommit(
            commit = { committed },
            onBoundedFailure = { fail("no failure expected") },
        )
        assertEquals(committed, result)
    }
}
