package com.projectnuke.fusion.modelzoo

import java.io.*
import java.net.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BlockingDownloadCancellationTest {
    @Test fun `cancel owns socket interruption and releases stalled read and target lock`() = runBlocking {
        val root = Files.createTempDirectory("stalled-download").toFile()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL("https://example.test/model")) {
            @Volatile var disconnected = false
            override fun connect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getContentLengthLong() = 16L
            override fun getContentType() = "application/octet-stream"
            override fun disconnect() { disconnected = true; released.countDown() }
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int { entered.countDown(); check(released.await(5, TimeUnit.SECONDS)); throw IOException("closed") }
            }
        }
        val owner = ModelDownloadCoordinator(connectionFactory = ModelConnectionFactory { connection }, usableSpace = { Long.MAX_VALUE }, reserveBytes = 0, minimumPlausibleBytes = 8, payloadValidator = { true })
        val job = async(Dispatchers.IO) { owner.download("https://example.test/model", File(root, "m.litertlm")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            assertTrue("cancel must disconnect without waiting for read timeout", connection.disconnected)
            job.join()
            assertEquals(0, owner.targetLockRegistrySizeForTests())
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally { released.countDown(); job.cancelAndJoin(); root.deleteRecursively() }
    }
}
