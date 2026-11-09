package com.projectnuke.fusion.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupIoTest {
    @Test
    fun `malformed utf8 backup cannot become a successful altered document`() = runTest {
        val prefix = "{\"app\":\"Fusion\",\"schemaVersion\":4,\"settings\":{\"selectedModel\":\"".toByteArray(Charsets.UTF_8)
        val suffix = "\"}}".toByteArray(Charsets.UTF_8)
        val corrupt = prefix + byteArrayOf(0xc3.toByte(), 0x28) + suffix

        assertEquals(SettingsBackupReadResult.Malformed,
            readBoundedSettingsStream(ByteArrayInputStream(corrupt)))
    }

    @Test
    fun `truncated utf8 sequence fails instead of inserting a replacement character`() = runTest {
        assertEquals(SettingsBackupReadResult.Malformed,
            readBoundedSettingsStream(ByteArrayInputStream(byteArrayOf(0xe2.toByte(), 0x82.toByte()))))
    }

    @Test
    fun `valid utf8 replacement character remains valid user content`() = runTest {
        val text = "intentional \uFFFD character"
        assertEquals(SettingsBackupReadResult.Success(text),
            readBoundedSettingsStream(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun `bounded streaming read rejects oversized input`() = runTest {
        val result = readBoundedSettingsStream(ByteArrayInputStream(ByteArray(MaxSettingsBackupBytes + 1)))
        assertEquals(SettingsBackupReadResult.TooLarge, result)
    }

    @Test
    fun `bounded streaming write flushes complete payload`() = runTest {
        val output = ByteArrayOutputStream()
        assertTrue(writeBoundedSettingsStream(output, "payload"))
        assertEquals("payload", output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `utf8 characters split across read boundary remain intact`() = runTest {
        val prefix = "a".repeat(8 * 1024 - 1)
        val expected = prefix + "한🙂"
        val result = readBoundedSettingsStream(ByteArrayInputStream(expected.toByteArray(StandardCharsets.UTF_8)))
        assertEquals(SettingsBackupReadResult.Success(expected), result)
    }

    @Test
    fun `bounded streaming write preserves cancellation identity`() = runTest {
        val cancelled = CancellationException("same cancellation")
        val output = object : OutputStream() {
            override fun write(value: Int) {
                throw cancelled
            }
        }

        val thrown = runCatching {
            writeBoundedSettingsStream(output, "payload")
        }.exceptionOrNull()

        assertSame(cancelled, thrown)
    }

    @Test
    fun `backup source separates ordinary and fatal failures`() = runTest {
        val fatal = LinkageError("fatal-open")
        val thrown = runCatching {
            readSettingsBackupSource { throw fatal }
        }.exceptionOrNull()
        assertTrue(thrown is LinkageError)
        assertEquals(SettingsBackupReadResult.Missing, readSettingsBackupSource { null })
        assertEquals(SettingsBackupReadResult.IoFailure, readSettingsBackupSource { throw IOException("io") })
        val cancelled = CancellationException("cancelled-open")
        assertEquals(SettingsBackupReadResult.Cancelled, readSettingsBackupSource { throw cancelled })
    }

    @Test
    fun `backup stream separates malformed content from read failure`() = runTest {
        val ioFailure = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("read")
        }
        assertEquals(SettingsBackupReadResult.IoFailure, readBoundedSettingsStream(ioFailure))
    }
}
