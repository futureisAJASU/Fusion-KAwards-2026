package com.projectnuke.fusion.projects

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSnapshotInputOwnerTest {
    @Test
    fun `provider stream closes after successful import`() = runBlocking {
        val input = CloseTrackingInputStream(byteArrayOf(1, 2, 3))
        val result = withOwnedProjectSnapshotInput({ input }) { stream ->
            assertEquals(1, stream.read())
            "imported"
        }
        assertEquals("imported", result)
        assertTrue(input.closed)
    }

    @Test
    fun `provider stream closes after failure and cancellation`() = runBlocking {
        val failureInput = CloseTrackingInputStream(byteArrayOf(1))
        try {
            withOwnedProjectSnapshotInput({ failureInput }) { throw IllegalStateException("decode failed") }
            throw AssertionError("expected failure")
        } catch (_: IllegalStateException) {
            assertTrue(failureInput.closed)
        }

        val cancellationInput = CloseTrackingInputStream(byteArrayOf(1))
        try {
            withOwnedProjectSnapshotInput({ cancellationInput }) { throw CancellationException("cancel import") }
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(cancellationInput.closed)
        }
    }

    @Test
    fun `missing provider stream returns null without invoking importer`() = runBlocking {
        var invoked = false
        val result = withOwnedProjectSnapshotInput({ null }) {
            invoked = true
            Unit
        }
        assertNull(result)
        assertTrue(!invoked)
    }

    private class CloseTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
