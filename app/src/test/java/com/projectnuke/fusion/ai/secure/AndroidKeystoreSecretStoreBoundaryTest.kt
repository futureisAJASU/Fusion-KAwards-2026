package com.projectnuke.fusion.ai.secure

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AndroidKeystoreSecretStoreBoundaryTest {
    @Test
    fun `recoverable decrypt failures remain absent while cancellation and fatal errors propagate`() {
        assertEquals("secret", secretReadOrNull { "secret" })
        assertNull(secretReadOrNull<String> { throw IllegalArgumentException("bad credential") })

        val cancelled = CancellationException("cancelled")
        assertSame(cancelled, runCatching {
            secretReadOrNull<String> { throw cancelled }
        }.exceptionOrNull())

        val fatal = LinkageError("fatal provider failure")
        assertSame(fatal, runCatching {
            secretReadOrNull<String> { throw fatal }
        }.exceptionOrNull())
    }
}
