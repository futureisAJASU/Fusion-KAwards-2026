package com.projectnuke.fusion.modelzoo

import android.system.ErrnoException
import android.system.OsConstants
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloadCoordinatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `storage exhaustion classifier recognizes errno and wrapped filesystem evidence`() {
        assertTrue(isStorageExhaustionFailure(ErrnoException("write", OsConstants.ENOSPC)))
        assertTrue(
            isStorageExhaustionFailure(
                IOException("wrapped", FileSystemException("part", null, "No space left on device"))
            )
        )
        assertTrue(isStorageExhaustionFailure(FileSystemException("part", null, "ENOSPC")))
        assertTrue(isStorageExhaustionFailure(IOException("write failed: ENOSPC")))
    }

    @Test
    fun `unrelated filesystem and network IO evidence is not storage exhaustion`() {
        assertFalse(isStorageExhaustionFailure(IOException("Permission denied")))
        assertFalse(isStorageExhaustionFailure(FileSystemException("part", null, "Read-only file system")))
        assertFalse(isStorageExhaustionFailure(IOException("EIO: input/output error")))
        assertFalse(isStorageExhaustionFailure(IOException("Connection reset by peer")))
    }

    @Test
    fun `HTTP error is rejected disconnected and leaves no part`() = runBlocking {
        val connection = FakeConnection(code = 503, error = ByteArray(100_000) { 1 })
        val target = temp.root.resolve("model.litertlm")
        val result = coordinator(connection).download("https://example.test/model", target)

        assertEquals(ModelDownloadFailure.HTTP, (result as ModelDownloadResult.Failure).kind)
        assertTrue(connection.disconnected)
        assertFalse(temp.root.resolve(".model.litertlm.part").exists())
    }

    @Test
    fun `gated repository response is explicit and preserves installed model`() = runBlocking {
        listOf(HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN).forEach { status ->
            val installed = byteArrayOf(7, 6, 5)
            val target = temp.root.resolve("gated-$status.litertlm").apply { writeBytes(installed) }
            val connection = FakeConnection(code = status, error = "gated model".toByteArray())
            val result = coordinator(connection).download("https://example.test/gated/model", target)

            assertEquals(
                ModelDownloadFailure.AUTHORIZATION_REQUIRED,
                (result as ModelDownloadResult.Failure).kind,
            )
            assertEquals(status, result.httpStatus)
            assertArrayEquals(installed, target.readBytes())
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun `repository page and JSON error with HTTP 200 are rejected before replacing model`() = runBlocking {
        listOf("text/html" to "<html><title>Model repo</title></html>", "application/json" to "{\"error\":\"missing\"}")
            .forEachIndexed { index, (contentType, responseBody) ->
                val previous = byteArrayOf(4, 3, 2, 1)
                val target = temp.root.resolve("not-binary-$index.litertlm").apply { writeBytes(previous) }
                val connection = FakeConnection(
                    body = responseBody.toByteArray(),
                    declaredLength = responseBody.toByteArray().size.toLong(),
                    responseContentType = contentType,
                )
                val result = coordinator(connection).download("https://example.test/repository", target)

                assertEquals(ModelDownloadFailure.INVALID_PAYLOAD, (result as ModelDownloadResult.Failure).kind)
                assertArrayEquals(previous, target.readBytes())
                assertTrue(connection.disconnected)
            }
    }

    @Test
    fun `redirect reaches pinned binary and adopts only final response`() = runBlocking {
        val requests = mutableListOf<String>()
        val body = ByteArray(32) { (it + 1).toByte() }
        val coordinator = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory { url ->
                requests += url.toString()
                if (url.path == "/repo/model") {
                    FakeConnection(url = url, code = 302, redirectLocation = "/resolve/abc/model.litertlm")
                } else {
                    FakeConnection(url = url, body = body, declaredLength = body.size.toLong())
                }
            },
            usableSpace = { Long.MAX_VALUE },
            reserveBytes = 0L,
            minimumPlausibleBytes = 8L,
            payloadValidator = { true },
        )
        val target = temp.root.resolve("redirected.litertlm")

        val result = coordinator.download("https://example.test/repo/model", target)

        assertTrue(result is ModelDownloadResult.Success)
        assertEquals(
            listOf(
                "https://example.test/repo/model",
                "https://example.test/resolve/abc/model.litertlm",
            ),
            requests,
        )
        assertArrayEquals(body, target.readBytes())
    }

    @Test
    fun `partial body failure cleans staging and retry installs complete file`() = runBlocking {
        val target = temp.root.resolve("retry-model.litertlm")
        val previous = byteArrayOf(9, 8, 7)
        target.writeBytes(previous)
        val failingInput = object : InputStream() {
            private var sent = 0
            override fun read(): Int {
                if (sent >= 2048) throw IOException("connection reset during body")
                sent++
                return 1
            }
        }
        val failed = coordinator(
            FakeConnection(inputOverride = failingInput, declaredLength = 4096L),
            maximumBytes = 8192L,
        ).download("https://example.test/model", target)
        assertEquals(ModelDownloadFailure.NETWORK, (failed as ModelDownloadResult.Failure).kind)
        assertArrayEquals(previous, target.readBytes())
        assertTrue(temp.root.listFiles { file -> file.name.endsWith(".part") }.orEmpty().isEmpty())

        val replacement = ByteArray(32) { 5 }
        val retry = coordinator(FakeConnection(body = replacement, declaredLength = replacement.size.toLong()))
            .download("https://example.test/model", target)
        assertTrue(retry is ModelDownloadResult.Success)
        assertArrayEquals(replacement, target.readBytes())
    }

    @Test
    fun `timeout is categorized and connection is disconnected`() = runBlocking {
        val connection = object : FakeConnection() {
            override fun connect() {
                throw SocketTimeoutException("test timeout")
            }
        }
        val result = coordinator(connection).download(
            "https://example.test/model",
            temp.root.resolve("model.litertlm"),
        )

        assertEquals(ModelDownloadFailure.TIMEOUT, (result as ModelDownloadResult.Failure).kind)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `ENOSPC during staging write is storage full and rolls back without adoption`() = runBlocking {
        activeDownloadPartTokens.clear()
        try {
            val previous = byteArrayOf(9, 8, 7)
            val target = temp.root.resolve("model.litertlm").apply { writeBytes(previous) }
            val connection = FakeConnection(body = ByteArray(16) { 2 }, declaredLength = 16L)
            var adopted = false
            val progress = mutableListOf<Int>()
            val result = ModelDownloadCoordinator(
                connectionFactory = ModelConnectionFactory { connection },
                stagingOutputFactory = { part ->
                    object : FileOutputStream(part) {
                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            throw IOException("write failed: ENOSPC")
                        }
                    }
                },
                atomicAdopter = AtomicModelAdopter { _, _ -> adopted = true },
                usableSpace = { Long.MAX_VALUE },
                reserveBytes = 0L,
                minimumPlausibleBytes = 8L,
                payloadValidator = { true },
            ).download("https://example.test/model", target) { progress += it }

            assertEquals(ModelDownloadFailure.STORAGE_FULL, (result as ModelDownloadResult.Failure).kind)
            assertArrayEquals(previous, target.readBytes())
            assertTrue(connection.disconnected)
            assertFalse(adopted)
            assertFalse(progress.contains(100))
            assertTrue(temp.root.listFiles { file -> file.name.endsWith(".part") }.orEmpty().isEmpty())
            assertTrue(activeDownloadPartTokens.isEmpty())
        } finally {
            activeDownloadPartTokens.clear()
        }
    }

    @Test
    fun `ENOSPC while opening staging output is storage full and cleans ownership`() = runBlocking {
        activeDownloadPartTokens.clear()
        try {
            val target = temp.root.resolve("model.litertlm")
            val result = ModelDownloadCoordinator(
                connectionFactory = ModelConnectionFactory {
                    FakeConnection(body = ByteArray(16) { 3 }, declaredLength = 16L)
                },
                stagingOutputFactory = { throw IOException("No space left on device") },
                usableSpace = { Long.MAX_VALUE },
                reserveBytes = 0L,
                minimumPlausibleBytes = 8L,
                payloadValidator = { true },
            ).download("https://example.test/model", target)

            assertEquals(ModelDownloadFailure.STORAGE_FULL, (result as ModelDownloadResult.Failure).kind)
            assertFalse(target.exists())
            assertFalse(temp.root.listFiles { file -> file.name.endsWith(".part") }.orEmpty().isNotEmpty())
            assertTrue(activeDownloadPartTokens.isEmpty())
        } finally {
            activeDownloadPartTokens.clear()
        }
    }

    @Test
    fun `unrelated staging IOException remains network failure`() = runBlocking {
        val result = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory {
                FakeConnection(body = ByteArray(16) { 4 }, declaredLength = 16L)
            },
            stagingOutputFactory = { throw IOException("generic I/O error") },
            usableSpace = { Long.MAX_VALUE },
            reserveBytes = 0L,
            minimumPlausibleBytes = 8L,
            payloadValidator = { true },
        ).download("https://example.test/model", temp.root.resolve("model.litertlm"))

        assertEquals(ModelDownloadFailure.NETWORK, (result as ModelDownloadResult.Failure).kind)
    }

    @Test
    fun `declared oversized payload is rejected before copying`() = runBlocking {
        val connection = FakeConnection(body = ByteArray(1), declaredLength = 33L)
        val result = coordinator(connection, maximumBytes = 32L).download(
            "https://example.test/model",
            temp.root.resolve("model.litertlm"),
        )

        assertEquals(ModelDownloadFailure.TOO_LARGE, (result as ModelDownloadResult.Failure).kind)
        assertFalse(temp.root.resolve(".model.litertlm.part").exists())
    }

    @Test
    fun `unknown length overflow is bounded and partial file is cleaned`() = runBlocking {
        val connection = FakeConnection(body = ByteArray(33) { 7 }, declaredLength = -1L)
        val result = coordinator(connection, maximumBytes = 32L).download(
            "https://example.test/model",
            temp.root.resolve("model.litertlm"),
        )

        assertEquals(ModelDownloadFailure.TOO_LARGE, (result as ModelDownloadResult.Failure).kind)
        assertFalse(temp.root.resolve(".model.litertlm.part").exists())
    }

    @Test
    fun `insufficient storage preserves previous valid model`() = runBlocking {
        val target = temp.root.resolve("model.litertlm").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        val connection = FakeConnection(body = ByteArray(16) { 1 }, declaredLength = 16L)
        val result = coordinator(connection, usableBytes = 20L, reserveBytes = 10L).download(
            "https://example.test/model",
            target,
        )

        assertEquals(ModelDownloadFailure.STORAGE_FULL, (result as ModelDownloadResult.Failure).kind)
        assertArrayEquals(byteArrayOf(9, 9, 9), target.readBytes())
    }

    @Test
    fun `unknown length with no writable budget rejects before staging write`() = runBlocking {
        var stagingOpened = false
        val connection = FakeConnection(body = ByteArray(16) { 1 }, declaredLength = -1L)
        val result = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory { connection },
            stagingOutputFactory = { part ->
                stagingOpened = true
                FileOutputStream(part)
            },
            usableSpace = { 10L },
            reserveBytes = 10L,
            minimumPlausibleBytes = 8L,
            payloadValidator = { true },
        ).download("https://example.test/model", temp.root.resolve("model.litertlm"))

        assertEquals(ModelDownloadFailure.STORAGE_FULL, (result as ModelDownloadResult.Failure).kind)
        assertFalse(stagingOpened)
        assertTrue(connection.disconnected)
        assertFalse(temp.root.listFiles { file -> file.name.endsWith(".part") }.orEmpty().isNotEmpty())
    }

    @Test
    fun `unknown length exactly fitting frozen budget succeeds`() = runBlocking {
        val body = ByteArray(16) { 2 }
        val target = temp.root.resolve("model.litertlm")
        val result = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory {
                FakeConnection(body = body, declaredLength = -1L)
            },
            usableSpace = { 26L },
            reserveBytes = 10L,
            minimumPlausibleBytes = 8L,
            payloadValidator = { true },
        ).download("https://example.test/model", target)

        assertTrue(result is ModelDownloadResult.Success)
        assertArrayEquals(body, target.readBytes())
    }

    @Test
    fun `unknown length crossing frozen budget fails before crossing write`() = runBlocking {
        val previous = byteArrayOf(9, 8, 7)
        val target = temp.root.resolve("model.litertlm").apply { writeBytes(previous) }
        val connection = FakeConnection(body = ByteArray(16) { 3 }, declaredLength = -1L)
        var bytesWritten = 0
        val progress = mutableListOf<Int>()
        val result = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory { connection },
            stagingOutputFactory = { part ->
                object : FileOutputStream(part) {
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        bytesWritten += length
                        super.write(buffer, offset, length)
                    }
                }
            },
            usableSpace = { 20L },
            reserveBytes = 10L,
            minimumPlausibleBytes = 8L,
            payloadValidator = { true },
        ).download("https://example.test/model", target) { progress += it }

        assertEquals(ModelDownloadFailure.STORAGE_FULL, (result as ModelDownloadResult.Failure).kind)
        assertEquals(0, bytesWritten)
        assertArrayEquals(previous, target.readBytes())
        assertFalse(progress.contains(100))
        assertFalse(temp.root.listFiles { file -> file.name.endsWith(".part") }.orEmpty().isNotEmpty())
    }

    @Test
    fun `declared length larger than frozen budget rejects before body copy`() = runBlocking {
        var inputReads = 0
        val connection = FakeConnection(
            body = ByteArray(16) { 4 },
            declaredLength = 16L,
            inputOverride = object : InputStream() {
                override fun read(): Int = -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    inputReads++
                    return -1
                }
            },
        )
        val result = coordinator(connection, usableBytes = 20L, reserveBytes = 10L).download(
            "https://example.test/model",
            temp.root.resolve("model.litertlm"),
        )

        assertEquals(ModelDownloadFailure.STORAGE_FULL, (result as ModelDownloadResult.Failure).kind)
        assertEquals(0, inputReads)
    }

    @Test
    fun `declared length equal to frozen budget is allowed`() = runBlocking {
        val body = ByteArray(16) { 5 }
        val target = temp.root.resolve("model.litertlm")
        val result = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory {
                FakeConnection(body = body, declaredLength = body.size.toLong())
            },
            usableSpace = { 26L },
            reserveBytes = 10L,
            minimumPlausibleBytes = 8L,
            payloadValidator = { true },
        ).download("https://example.test/model", target)

        assertTrue(result is ModelDownloadResult.Success)
        assertArrayEquals(body, target.readBytes())
    }

    @Test
    fun `dishonest declared length rejects surplus before writing it`() = runBlocking {
        val previous = byteArrayOf(1, 2, 3)
        val target = temp.root.resolve("model.litertlm").apply { writeBytes(previous) }
        val connection = FakeConnection(body = ByteArray(16) { 6 }, declaredLength = 8L)
        var bytesWritten = 0
        val result = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory { connection },
            stagingOutputFactory = { part ->
                object : FileOutputStream(part) {
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        bytesWritten += length
                        super.write(buffer, offset, length)
                    }
                }
            },
            usableSpace = { Long.MAX_VALUE },
            reserveBytes = 0L,
            maximumBytes = 32L,
            minimumPlausibleBytes = 8L,
            payloadValidator = { true },
        ).download("https://example.test/model", target)

        assertEquals(ModelDownloadFailure.INVALID_PAYLOAD, (result as ModelDownloadResult.Failure).kind)
        assertEquals(0, bytesWritten)
        assertArrayEquals(previous, target.readBytes())
    }

    @Test
    fun `final usable-space recheck still rejects external space loss`() = runBlocking {
        var spaceChecks = 0
        val result = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory {
                FakeConnection(body = ByteArray(16) { 7 }, declaredLength = 16L)
            },
            usableSpace = {
                spaceChecks++
                if (spaceChecks == 1) Long.MAX_VALUE else 0L
            },
            reserveBytes = 0L,
            minimumPlausibleBytes = 8L,
            payloadValidator = { true },
        ).download("https://example.test/model", temp.root.resolve("model.litertlm"))

        assertEquals(ModelDownloadFailure.STORAGE_FULL, (result as ModelDownloadResult.Failure).kind)
        assertTrue(spaceChecks >= 2)
    }

    @Test
    fun `failed atomic replacement preserves previous model and cleans part`() = runBlocking {
        val previous = byteArrayOf(3, 4, 5)
        val target = temp.root.resolve("model.litertlm").apply { writeBytes(previous) }
        val connection = FakeConnection(body = ByteArray(16) { 8 }, declaredLength = 16L)
        val coordinator = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory { connection },
            atomicAdopter = AtomicModelAdopter { _, _ -> error("adoption failed") },
            usableSpace = { Long.MAX_VALUE },
            maximumBytes = 32L,
            reserveBytes = 0L,
            minimumPlausibleBytes = 8L,
            payloadValidator = { true },
        )

        val result = coordinator.download("https://example.test/model", target)

        assertEquals(ModelDownloadFailure.ATOMIC_ADOPTION, (result as ModelDownloadResult.Failure).kind)
        assertArrayEquals(previous, target.readBytes())
        assertFalse(temp.root.resolve(".model.litertlm.part").exists())
    }

    @Test
    fun `HTML payload is rejected without replacing prior model`() = runBlocking {
        val previous = byteArrayOf(1, 2, 3)
        val target = temp.root.resolve("model.litertlm").apply { writeBytes(previous) }
        val html = "<!doctype html><html>not a model</html>".toByteArray()
        val result = coordinator(FakeConnection(body = html, declaredLength = html.size.toLong()))
            .download("https://example.test/model", target)

        assertEquals(ModelDownloadFailure.INVALID_PAYLOAD, (result as ModelDownloadResult.Failure).kind)
        assertArrayEquals(previous, target.readBytes())
    }

    @Test
    fun `successful adoption validates header and cleans up backup`() = runBlocking {
        val previous = byteArrayOf(1, 2, 3)
        val target = temp.root.resolve("model.litertlm").apply { writeBytes(previous) }
        val body = LiteRtLmPackageBuilder.buildBytes(fileSize = 2L * 1024L * 1024L)
        val result = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory {
                FakeConnection(body = body, declaredLength = body.size.toLong())
            },
            usableSpace = { Long.MAX_VALUE },
            reserveBytes = 0L,
        )
            .download("https://example.test/model", target)

        assertEquals(ModelDownloadResult.Success::class, result::class)
        assertArrayEquals(body, target.readBytes())
        assertFalse(temp.root.listFiles { f -> f.name.endsWith(".bak") }!!.isNotEmpty())
    }

    @Test
    fun `large random binary is invalid and preserves previous model`() = runBlocking {
        val previous = byteArrayOf(4, 5, 6)
        val target = temp.root.resolve("model.litertlm").apply { writeBytes(previous) }
        val body = ByteArray(2 * 1024 * 1024) { (it * 31).toByte() }
        val result = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory {
                FakeConnection(body = body, declaredLength = body.size.toLong())
            },
            usableSpace = { Long.MAX_VALUE },
            reserveBytes = 0L,
        ).download("https://example.test/model", target)

        assertEquals(ModelDownloadFailure.INVALID_PAYLOAD, (result as ModelDownloadResult.Failure).kind)
        assertArrayEquals(previous, target.readBytes())
    }

    @Test
    fun `invalid staging is rejected before atomic adoption`() = runBlocking {
        var adopted = false
        val body = ByteArray(2 * 1024 * 1024) { 7 }
        val target = temp.root.resolve("model.litertlm")
        val result = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory {
                FakeConnection(body = body, declaredLength = body.size.toLong())
            },
            atomicAdopter = AtomicModelAdopter { _, _ -> adopted = true },
            usableSpace = { Long.MAX_VALUE },
            reserveBytes = 0L,
        ).download("https://example.test/model", target)

        assertEquals(ModelDownloadFailure.INVALID_PAYLOAD, (result as ModelDownloadResult.Failure).kind)
        assertFalse(adopted)
        assertFalse(target.exists())
    }

    @Test
    fun `cancellation is rethrown disconnects and cleans partial file`() = runBlocking {
        val firstRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val connection = FakeConnection(
            body = ByteArray(64) { 2 },
            declaredLength = -1L,
            inputOverride = object : InputStream() {
                private var reads = 0
                override fun read(): Int = error("unused")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ > 0) return -1
                    firstRead.countDown()
                    assertTrue(releaseRead.await(5, TimeUnit.SECONDS))
                    buffer[offset] = 2
                    return 1
                }
            },
        )
        val target = temp.root.resolve("model.litertlm")
        val task = async(Dispatchers.Default) {
            coordinator(connection).download("https://example.test/model", target)
        }
        assertTrue(firstRead.await(5, TimeUnit.SECONDS))
        task.cancel(CancellationException("test cancellation"))
        releaseRead.countDown()
        task.cancelAndJoin()

        assertTrue(task.isCancelled)
        assertTrue(connection.disconnected)
        assertFalse(temp.root.resolve(".model.litertlm.part").exists())
        assertFalse(target.exists())
    }

    @Test
    fun `trusted SHA256 gates adoption and mismatch preserves previous model`() = runBlocking {
        val payload = ByteArray(512) { (it % 239).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val previous = byteArrayOf(5, 4, 3, 2, 1)
        val target = temp.root.resolve("gemma.litertlm").apply { writeBytes(previous) }

        val mismatch = coordinator(
            FakeConnection(body = payload, declaredLength = payload.size.toLong())
        ).downloadWithExpectedSha256("https://example.test/model", target, "00".repeat(32))

        assertEquals(ModelDownloadFailure.CHECKSUM_MISMATCH, (mismatch as ModelDownloadResult.Failure).kind)
        assertArrayEquals(previous, target.readBytes())
        assertTrue(temp.root.listFiles().orEmpty().none { it.name.contains(".bak") || it.name.endsWith(".part") })

        val successTarget = temp.root.resolve("gemma-valid.litertlm")
        val success = coordinator(
            FakeConnection(body = payload, declaredLength = payload.size.toLong())
        ).downloadWithExpectedSha256("https://example.test/model", successTarget, expected)
        assertTrue(success is ModelDownloadResult.Success)
        assertArrayEquals(payload, successTarget.readBytes())
    }

    @Test
    fun `cancellation before adoption restores previous target`() = runBlocking {
        val previous = byteArrayOf(9, 8, 7)
        val target = temp.root.resolve("model.litertlm").apply { writeBytes(previous) }
        val firstRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val connection = FakeConnection(
            body = ByteArray(16) { 4 },
            declaredLength = -1L,
            inputOverride = object : InputStream() {
                private var reads = 0
                override fun read(): Int = error("unused")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ > 0) return -1
                    firstRead.countDown()
                    assertTrue(releaseRead.await(5, TimeUnit.SECONDS))
                    buffer[offset] = 4
                    return 1
                }
            },
        )
        val task = async(Dispatchers.Default) {
            ModelDownloadCoordinator(
                connectionFactory = ModelConnectionFactory { connection },
                usableSpace = { Long.MAX_VALUE },
                reserveBytes = 0L,
                minimumPlausibleBytes = 1L,
                payloadValidator = { true },
            ).download("https://example.test/model", target)
        }
        assertTrue(firstRead.await(5, TimeUnit.SECONDS))
        task.cancel(CancellationException("before adoption"))
        releaseRead.countDown()
        task.cancelAndJoin()
        assertArrayEquals(previous, target.readBytes())
    }

    @Test
    fun `cancellation during terminal progress cannot undo committed target`() = runBlocking {
        val body = ByteArray(16) { 6 }
        val target = temp.root.resolve("model.litertlm")
        val result = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory {
                FakeConnection(body = body, declaredLength = body.size.toLong())
            },
            usableSpace = { Long.MAX_VALUE },
            reserveBytes = 0L,
            minimumPlausibleBytes = 8L,
            payloadValidator = { true },
        ).download("https://example.test/model", target) { percent ->
            if (percent == 100) throw CancellationException("late progress cancellation")
        }

        assertTrue(result is ModelDownloadResult.Success)
        assertArrayEquals(body, target.readBytes())
    }

    @Test
    fun `refcounted target lock keeps waiter and third caller on one mutex`() = runBlocking {
        val target = temp.root.resolve("same.litertlm")
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val coordinator = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory {
                FakeConnection(body = ByteArray(16) { 1 }, declaredLength = 16L)
            },
            usableSpace = { Long.MAX_VALUE },
            reserveBytes = 0L,
            minimumPlausibleBytes = 8L,
            payloadValidator = {
                val call = calls.incrementAndGet()
                val now = active.incrementAndGet()
                maxActive.updateAndGet { old -> maxOf(old, now) }
                try {
                    when (call) {
                        1 -> {
                            firstEntered.countDown()
                            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                        }
                        2 -> {
                            secondEntered.countDown()
                            assertTrue(releaseSecond.await(5, TimeUnit.SECONDS))
                        }
                    }
                    true
                } finally {
                    active.decrementAndGet()
                }
            },
        )

        val first = async(Dispatchers.Default) { coordinator.download("https://example.test/1", target) }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) { coordinator.download("https://example.test/2", target) }
        Thread.sleep(100)
        releaseFirst.countDown()
        assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
        val third = async(Dispatchers.Default) { coordinator.download("https://example.test/3", target) }
        releaseSecond.countDown()
        first.await()
        second.await()
        third.await()

        assertEquals(1, maxActive.get())
        assertEquals(0, coordinator.targetLockRegistrySizeForTests())
    }

    @Test
    fun `different target locks execute concurrently`() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val coordinator = ModelDownloadCoordinator(
            connectionFactory = ModelConnectionFactory {
                FakeConnection(body = ByteArray(16) { 2 }, declaredLength = 16L)
            },
            usableSpace = { Long.MAX_VALUE },
            reserveBytes = 0L,
            minimumPlausibleBytes = 8L,
            payloadValidator = {
                val now = active.incrementAndGet()
                maxActive.updateAndGet { old -> maxOf(old, now) }
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                active.decrementAndGet()
                true
            },
        )

        val first = async(Dispatchers.Default) {
            coordinator.download("https://example.test/a", temp.root.resolve("a.litertlm"))
        }
        val second = async(Dispatchers.Default) {
            coordinator.download("https://example.test/b", temp.root.resolve("b.litertlm"))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        release.countDown()
        first.await()
        second.await()
        assertEquals(2, maxActive.get())
        assertEquals(0, coordinator.targetLockRegistrySizeForTests())
    }

    @Test
    fun `live backup owned before adoption survives recovery and rolls back to previous`() = runBlocking {
        activeDownloadBackupTokens.clear()
        try {
            val previous = byteArrayOf(10, 20, 30, 40)
            val replacement = ByteArray(16) { 8 }
            val target = temp.root.resolve("model.litertlm").apply { writeBytes(previous) }
            val coordinator = ModelDownloadCoordinator(
                connectionFactory = ModelConnectionFactory {
                    FakeConnection(body = replacement, declaredLength = replacement.size.toLong())
                },
                atomicAdopter = AtomicModelAdopter { _, _ ->
                    // Pause BEFORE replacement adoption: the previous target has
                    // been moved to the deterministic backup and the live token
                    // must already own it.
                    val backups = temp.root.listFiles { f ->
                        ModelStagingCodec.decodeDownloadBackup(f.name) != null
                    }!!
                    assertEquals(1, backups.size)
                    val backup = backups[0]
                    val entry = ModelStagingCodec.decodeDownloadBackup(backup.name)!!
                    assertTrue(activeDownloadBackupTokens.contains(entry.token))
                    assertTrue(isActiveDownloadBackup(backup))
                    assertFalse(target.exists())
                    assertArrayEquals(previous, backup.readBytes())

                    // Recovery running in this window must leave the live
                    // backup alone: no restore, no delete, target stays absent.
                    recoverModelStorageArtifacts(
                        temp.root,
                        FusionModelCatalog.ImportedCatalogLoad.Valid(emptyList()),
                        System.currentTimeMillis(),
                        orphanGraceMs = 0L,
                    )
                    assertTrue(backup.exists())
                    assertFalse(target.exists())
                    assertArrayEquals(previous, backup.readBytes())

                    // Force rollback before operationCommitted.
                    throw IllegalStateException("forced adoption failure before commit")
                },
                usableSpace = { Long.MAX_VALUE },
                reserveBytes = 0L,
                minimumPlausibleBytes = 8L,
                payloadValidator = { true },
            )

            val result = coordinator.download("https://example.test/model", target)

            assertEquals(ModelDownloadFailure.ATOMIC_ADOPTION, (result as ModelDownloadResult.Failure).kind)
            // Rollback restores exact previous bytes; replacement B is absent.
            assertTrue(target.isFile)
            assertArrayEquals(previous, target.readBytes())
            assertFalse(target.readBytes().contentEquals(replacement))
            // Backup staging is settled and the token registry is empty.
            assertFalse(
                temp.root.listFiles { f -> f.name.endsWith(".bak") }!!.isNotEmpty(),
            )
            assertTrue(activeDownloadBackupTokens.isEmpty())
        } finally {
            activeDownloadBackupTokens.clear()
        }
    }

    @Test
    fun `live backup owned through successful commit settles obsolete backup`() = runBlocking {
        activeDownloadBackupTokens.clear()
        try {
            val previous = byteArrayOf(11, 22, 33)
            val replacement = ByteArray(16) { 9 }
            val target = temp.root.resolve("model.litertlm").apply { writeBytes(previous) }
            val coordinator = ModelDownloadCoordinator(
                connectionFactory = ModelConnectionFactory {
                    FakeConnection(body = replacement, declaredLength = replacement.size.toLong())
                },
                atomicAdopter = AtomicModelAdopter { source, dest ->
                    // Live token protects the backup while the target is absent.
                    val backups = temp.root.listFiles { f ->
                        ModelStagingCodec.decodeDownloadBackup(f.name) != null
                    }!!
                    assertEquals(1, backups.size)
                    val backup = backups[0]
                    val entry = ModelStagingCodec.decodeDownloadBackup(backup.name)!!
                    assertTrue(activeDownloadBackupTokens.contains(entry.token))
                    assertTrue(isActiveDownloadBackup(backup))
                    assertFalse(target.exists())

                    recoverModelStorageArtifacts(
                        temp.root,
                        FusionModelCatalog.ImportedCatalogLoad.Valid(emptyList()),
                        System.currentTimeMillis(),
                        // Default grace: the fresh `.part` staging must survive
                        // recovery; only the active-token exclusion protects the
                        // backup, which is exactly what is asserted below.
                        orphanGraceMs = 5 * 60 * 1000L,
                    )
                    assertTrue(backup.exists())
                    assertFalse(target.exists())

                    Files.move(source, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                },
                usableSpace = { Long.MAX_VALUE },
                reserveBytes = 0L,
                minimumPlausibleBytes = 8L,
                payloadValidator = { true },
            )

            val result = coordinator.download("https://example.test/model", target)

            assertTrue(result is ModelDownloadResult.Success)
            // Replacement B commits and remains the target; obsolete backup settles.
            assertArrayEquals(replacement, target.readBytes())
            assertFalse(
                temp.root.listFiles { f -> f.name.endsWith(".bak") }!!.isNotEmpty(),
            )
            assertTrue(activeDownloadBackupTokens.isEmpty())
        } finally {
            activeDownloadBackupTokens.clear()
        }
    }

    @Test
    fun `live download part is owned during write and released after settlement`() = runBlocking {
        activeDownloadPartTokens.clear()
        try {
            val body = ByteArray(16) { 7 }
            val target = temp.root.resolve("model.litertlm")
            var observedToken: String? = null
            val coordinator = ModelDownloadCoordinator(
                connectionFactory = ModelConnectionFactory {
                    FakeConnection(body = body, declaredLength = body.size.toLong())
                },
                usableSpace = { Long.MAX_VALUE },
                reserveBytes = 0L,
                minimumPlausibleBytes = 8L,
                payloadValidator = { part ->
                    // The part token is registered before the staging file
                    // is created, so it already owns the open part here.
                    assertEquals(1, activeDownloadPartTokens.size)
                    observedToken = activeDownloadPartTokens.single()
                    assertTrue(part.name.endsWith(".$observedToken.part"))
                    // Recovery running in this window must leave the live
                    // part alone even past grace and even with an empty
                    // import set.
                    recoverModelStorageArtifacts(
                        temp.root,
                        FusionModelCatalog.ImportedCatalogLoad.Valid(emptyList()),
                        System.currentTimeMillis(),
                        orphanGraceMs = 0L,
                        activeImportTokens = emptySet(),
                        activeDownloadPartTokens = activeDownloadPartTokens.toSet(),
                    )
                    assertTrue(part.exists())
                    true
                },
            )

            val result = coordinator.download("https://example.test/model", target)

            assertTrue(result is ModelDownloadResult.Success)
            assertArrayEquals(body, target.readBytes())
            // Settlement adopted the part and released its token; no live
            // part staging remains.
            assertTrue(activeDownloadPartTokens.isEmpty())
            assertFalse(
                temp.root.listFiles { f -> f.name.endsWith(".part") }!!.isNotEmpty(),
            )
        } finally {
            activeDownloadPartTokens.clear()
        }
    }

    private fun coordinator(
        connection: FakeConnection,
        maximumBytes: Long = 1024L,
        usableBytes: Long = Long.MAX_VALUE,
        reserveBytes: Long = 0L,
        payloadValidator: (File) -> Boolean = { true },
    ) = ModelDownloadCoordinator(
        connectionFactory = ModelConnectionFactory { connection },
        usableSpace = { usableBytes },
        maximumBytes = maximumBytes,
        reserveBytes = reserveBytes,
        minimumPlausibleBytes = 8L,
        payloadValidator = payloadValidator,
    )

    private open class FakeConnection(
        url: URL = URL("https://example.test/model"),
        private val code: Int = 200,
        private val body: ByteArray = ByteArray(16) { 1 },
        private val error: ByteArray? = null,
        private val declaredLength: Long = body.size.toLong(),
        private val inputOverride: InputStream? = null,
        private val redirectLocation: String? = null,
        private val responseContentType: String = "application/octet-stream",
    ) : HttpURLConnection(url) {
        var disconnected = false

        override fun connect() {
            connected = true
        }

        override fun disconnect() {
            disconnected = true
            connected = false
        }

        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getContentLengthLong(): Long = declaredLength
        override fun getContentType(): String = responseContentType
        override fun getHeaderField(name: String): String? =
            if (name.equals("Location", ignoreCase = true)) redirectLocation else null
        override fun getInputStream(): InputStream = inputOverride ?: ByteArrayInputStream(body)
        override fun getErrorStream(): InputStream? = error?.inputStream()
    }
}
