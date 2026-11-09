package com.projectnuke.fusion.util

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedUtf8FileReadTest {
    @Test
    fun `valid ASCII and Korean emoji decode strictly`() {
        assertEquals(
            BoundedUtf8FileRead.Success("plain"),
            readUtf8Bounded(ByteArrayInputStream("plain".toByteArray()), 5),
        )
        assertEquals(
            BoundedUtf8FileRead.Success("한글 😀"),
            readUtf8Bounded(ByteArrayInputStream("한글 😀".toByteArray()), 32),
        )
    }

    @Test
    fun `exact maximum succeeds and next byte is oversized`() {
        assertEquals(
            BoundedUtf8FileRead.Success("abcd"),
            readUtf8Bounded(ByteArrayInputStream("abcd".toByteArray()), 4),
        )
        assertEquals(
            BoundedUtf8FileRead.Oversized,
            readUtf8Bounded(ByteArrayInputStream("abcde".toByteArray()), 4),
        )
    }

    @Test
    fun `reader never consumes beyond sentinel byte`() {
        var consumed = 0
        val stream = object : InputStream() {
            override fun read(): Int {
                consumed++
                if (consumed > 5) throw AssertionError("read beyond max plus sentinel")
                return 'x'.code
            }
        }

        assertEquals(BoundedUtf8FileRead.Oversized, readUtf8Bounded(stream, 4))
        assertEquals(5, consumed)
    }

    @Test
    fun `malformed UTF8 and IO failure are failed`() {
        assertEquals(
            BoundedUtf8FileRead.Failed,
            readUtf8Bounded(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)), 8),
        )
        assertEquals(
            BoundedUtf8FileRead.Failed,
            readUtf8Bounded(object : InputStream() {
                override fun read(): Int = throw IOException("boom")
            }, 8),
        )
        assertTrue(readUtf8Bounded(ByteArrayInputStream(byteArrayOf()), 0) is BoundedUtf8FileRead.Failed)
    }
}
