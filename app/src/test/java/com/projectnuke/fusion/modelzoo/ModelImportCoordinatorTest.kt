package com.projectnuke.fusion.modelzoo

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelImportCoordinatorTest {
    @Test
    fun `default validator rejects random bytes renamed to litertlm`() = runTest {
        val root = Files.createTempDirectory("fusion-model-import").toFile()
        val coordinator = ModelImportCoordinator(
            modelDirectory = root,
            openSource = { ByteArrayInputStream(ByteArray(1024 * 1024) { 7 }) },
            usableSpace = { Long.MAX_VALUE },
        )
        val result = coordinator.import(ModelImportRequest(sourceIdentity = "random", displayName = "random.litertlm"))
        assertEquals(ModelImportFailure.INVALID_MODEL, (result as ModelImportResult.Failure).kind)
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".litertlm") })
        root.deleteRecursively()
    }

    @Test
    fun `lost uri permission is reported distinctly`() = runTest {
        val root = Files.createTempDirectory("fusion-model-import").toFile()
        val coordinator = ModelImportCoordinator(
            modelDirectory = root,
            openSource = { throw SecurityException("Permission denied") },
            usableSpace = { Long.MAX_VALUE },
        )

        val result = coordinator.import(
            ModelImportRequest(sourceIdentity = "content://provider/model", displayName = "model.litertlm"),
        )

        assertEquals(ModelImportFailure.URI_PERMISSION_LOST, (result as ModelImportResult.Failure).kind)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        root.deleteRecursively()
    }

    @Test
    fun `invalid import never reports completion progress`() = runTest {
        val root = Files.createTempDirectory("fusion-model-import").toFile()
        val progress = mutableListOf<Int>()
        val coordinator = ModelImportCoordinator(
            modelDirectory = root,
            openSource = { ByteArrayInputStream(ByteArray(1024 * 1024) { 7 }) },
            usableSpace = { Long.MAX_VALUE },
            validator = LiteRtLmValidator { false },
        )

        val result = coordinator.import(
            ModelImportRequest(sourceIdentity = "invalid-progress", displayName = "invalid.litertlm"),
            onProgress = { progress += it },
        )

        assertEquals(ModelImportFailure.INVALID_MODEL, (result as ModelImportResult.Failure).kind)
        assertFalse(100 in progress)
        root.deleteRecursively()
    }

    @Test
    fun `successful validation adopts one uuid file`() = runTest {
        // успешная валидация файла — orphan cleanup is now startup-only and respects a grace period
        val root = Files.createTempDirectory("fusion-model-import").toFile()
        val coordinator = ModelImportCoordinator(
            modelDirectory = root,
            openSource = { ByteArrayInputStream(ByteArray(1024 * 1024) { 9 }) },
            usableSpace = { Long.MAX_VALUE },
            validator = LiteRtLmValidator { it.extension == "litertlm" || it.name.contains(".litertlm") },
        )
        val result = coordinator.import(ModelImportRequest(sourceIdentity = "source", displayName = "model.litertlm"))
        val file = (result as ModelImportResult.Success).file
        assertTrue(file.isFile)
        assertTrue(file.name.endsWith("model.litertlm"))
        root.deleteRecursively()
    }

    @Test
    fun `UTF8 bounded import filename keeps target and part at filesystem component limit`() = runTest {
        val root = Files.createTempDirectory("fusion-model-import-name").toFile()
        val payload = ByteArray(64) { it.toByte() }
        var observedPartNameBytes = -1
        val coordinator = ModelImportCoordinator(
            modelDirectory = root,
            openSource = {
                object : ByteArrayInputStream(payload) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val part = root.listFiles().orEmpty().singleOrNull { it.name.endsWith(".part") }
                        if (part != null) observedPartNameBytes = part.name.toByteArray(Charsets.UTF_8).size
                        return super.read(buffer, offset, length)
                    }
                }
            },
            usableSpace = { Long.MAX_VALUE },
            validator = LiteRtLmValidator { true },
        )
        val exactLimitDisplayName = "가".repeat(55) + "A.litertlm" // 175 UTF-8 bytes
        assertEquals(175, exactLimitDisplayName.toByteArray(Charsets.UTF_8).size)
        val result = coordinator.import(
            ModelImportRequest(sourceIdentity = "name-limit", displayName = exactLimitDisplayName)
        ) as ModelImportResult.Success

        assertEquals(255, observedPartNameBytes)
        assertTrue(result.file.name.toByteArray(Charsets.UTF_8).size <= 212)
        assertTrue(result.file.name.endsWith("A.litertlm"))

        val oneByteOver = "가".repeat(55) + "AA.litertlm"
        assertEquals(176, oneByteOver.toByteArray(Charsets.UTF_8).size)
        val bounded = coordinator.import(
            ModelImportRequest(sourceIdentity = "one-byte-over", displayName = oneByteOver)
        ) as ModelImportResult.Success
        assertEquals(255, observedPartNameBytes)
        assertTrue(bounded.file.name.endsWith("A.litertlm"))
        root.deleteRecursively()
    }

    @Test
    fun `official format package is adopted by default validator`() = runTest {
        val root = Files.createTempDirectory("fusion-model-import").toFile()
        val packageBytes = LiteRtLmPackageBuilder.buildBytes(fileSize = 2L * 1024 * 1024)
        val coordinator = ModelImportCoordinator(
            modelDirectory = root,
            openSource = { ByteArrayInputStream(packageBytes) },
            usableSpace = { Long.MAX_VALUE },
        )
        val result = coordinator.import(
            ModelImportRequest(sourceIdentity = "official", displayName = "gemma.litertlm")
        )
        val file = (result as ModelImportResult.Success).file
        assertTrue(file.isFile)
        assertEquals(packageBytes.size.toLong(), file.length())
        assertTrue(file.name.endsWith("gemma.litertlm"))
        root.deleteRecursively()
    }

    @Test
    fun `source length is queried once and unknown or zero size reports no progress`() = runTest {
        val root = Files.createTempDirectory("fusion-model-import").toFile()
        val sourceLengthCalls = AtomicInteger(0)
        val progress = mutableListOf<Int>()
        val bytes = LiteRtLmPackageBuilder.buildBytes(fileSize = 2L * 1024 * 1024)
        val coordinator = ModelImportCoordinator(
            modelDirectory = root,
            openSource = { ByteArrayInputStream(bytes) },
            sourceLength = { sourceLengthCalls.incrementAndGet(); 0L },
            usableSpace = { Long.MAX_VALUE },
            validator = LiteRtLmValidator { true },
        )
        val result = coordinator.import(
            ModelImportRequest(sourceIdentity = "zero", displayName = "zero.litertlm"),
            onProgress = { progress += it },
        )
        assertTrue(result is ModelImportResult.Success)
        assertEquals(1, sourceLengthCalls.get())
        assertFalse(progress.any { it in 0..99 })
        assertEquals(listOf(100), progress)
        root.deleteRecursively()
    }

    @Test
    fun `metadata failure rolls back adopted file`() = runTest {
        val root = Files.createTempDirectory("fusion-model-import").toFile()
        val bytes = LiteRtLmPackageBuilder.buildBytes(fileSize = 2L * 1024 * 1024)
        val coordinator = ModelImportCoordinator(
            modelDirectory = root,
            openSource = { ByteArrayInputStream(bytes) },
            usableSpace = { Long.MAX_VALUE },
            validator = LiteRtLmValidator { true },
        )
        val result = coordinator.import(
            ModelImportRequest(sourceIdentity = "metadata-failure", displayName = "model.litertlm"),
            commitMetadata = { false },
        )
        assertEquals(ModelImportFailure.METADATA_FAILED, (result as ModelImportResult.Failure).kind)
        assertTrue(root.listFiles().orEmpty().none { it.extension == "litertlm" })
        root.deleteRecursively()
    }

    @Test
    fun `cancellation during metadata finalization is atomic and releases ownership`() = runTest {
        val root = Files.createTempDirectory("fusion-model-import").toFile()
        val bytes = LiteRtLmPackageBuilder.buildBytes(fileSize = 2L * 1024 * 1024)
        val metadataEntered = CountDownLatch(1)
        val releaseMetadata = CountDownLatch(1)
        var metadataCommitted = false
        val coordinator = ModelImportCoordinator(
            modelDirectory = root,
            openSource = { ByteArrayInputStream(bytes) },
            usableSpace = { Long.MAX_VALUE },
            validator = LiteRtLmValidator { true },
        )

        val job = async(Dispatchers.IO) {
            coordinator.import(
                ModelImportRequest(sourceIdentity = "metadata-cancel", displayName = "model.litertlm"),
                commitMetadata = {
                    metadataEntered.countDown()
                    assertTrue(releaseMetadata.await(5, TimeUnit.SECONDS))
                    metadataCommitted = true
                    true
                },
            )
        }
        withTimeout(10_000) {
            while (!metadataEntered.await(10, TimeUnit.MILLISECONDS)) Unit
        }
        job.cancel()
        releaseMetadata.countDown()
        try {
            job.await()
            fail("caller cancellation must propagate after metadata finalization")
        } catch (_: CancellationException) {
            // The committed terminal state is not reported as normal success.
        }

        val finalFiles = root.listFiles().orEmpty().filter { it.extension == "litertlm" }
        assertTrue(
            "metadataCommitted=$metadataCommitted files=$finalFiles",
            (metadataCommitted && finalFiles.size == 1) || (!metadataCommitted && finalFiles.isEmpty()),
        )
        assertTrue(metadataCommitted)
        val retry = coordinator.import(
            ModelImportRequest(sourceIdentity = "metadata-cancel", displayName = "retry.litertlm"),
            commitMetadata = { true },
        )
        assertTrue(retry is ModelImportResult.Success)
        root.deleteRecursively()
    }

    @Test
    fun `cancellation after metadata commit preserves committed state and propagates`() = runTest {
        val root = Files.createTempDirectory("fusion-model-import").toFile()
        val bytes = LiteRtLmPackageBuilder.buildBytes(fileSize = 2L * 1024 * 1024)
        var metadataCommitted = false
        lateinit var job: kotlinx.coroutines.Deferred<ModelImportResult>
        val coordinator = ModelImportCoordinator(
            modelDirectory = root,
            openSource = { ByteArrayInputStream(bytes) },
            usableSpace = { Long.MAX_VALUE },
            validator = LiteRtLmValidator { true },
        )

        job = async(Dispatchers.IO) {
            coordinator.import(
                ModelImportRequest(sourceIdentity = "post-commit-cancel", displayName = "model.litertlm"),
                commitMetadata = {
                    metadataCommitted = true
                    job.cancel()
                    true
                },
            )
        }
        try {
            job.await()
            fail("cancellation after commit must still propagate")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertTrue(metadataCommitted)
        assertEquals(1, root.listFiles().orEmpty().count { it.extension == "litertlm" })
        val retry = coordinator.import(
            ModelImportRequest(sourceIdentity = "post-commit-cancel", displayName = "retry.litertlm"),
            commitMetadata = { true },
        )
        assertTrue(retry is ModelImportResult.Success)
        root.deleteRecursively()
    }

    @Test
    fun `completion progress failure cannot turn committed import into rollback`() = runTest {
        val root = Files.createTempDirectory("fusion-model-import").toFile()
        val bytes = LiteRtLmPackageBuilder.buildBytes(fileSize = 2L * 1024 * 1024)
        val coordinator = ModelImportCoordinator(
            modelDirectory = root,
            openSource = { ByteArrayInputStream(bytes) },
            usableSpace = { Long.MAX_VALUE },
            validator = LiteRtLmValidator { true },
        )

        val result = coordinator.import(
            ModelImportRequest(sourceIdentity = "progress-failure", displayName = "model.litertlm"),
            onProgress = { percent -> if (percent == 100) error("UI callback failed") },
        )

        assertTrue(result is ModelImportResult.Success)
        assertEquals(1, root.listFiles().orEmpty().count { it.extension == "litertlm" })
        root.deleteRecursively()
    }

    @Test
    fun `cancellation exception from terminal progress still returns success`() = runTest {
        val root = Files.createTempDirectory("fusion-model-import").toFile()
        val bytes = LiteRtLmPackageBuilder.buildBytes(fileSize = 2L * 1024 * 1024)
        val coordinator = ModelImportCoordinator(
            modelDirectory = root,
            openSource = { ByteArrayInputStream(bytes) },
            usableSpace = { Long.MAX_VALUE },
            validator = LiteRtLmValidator { true },
        )

        val result = coordinator.import(
            ModelImportRequest(sourceIdentity = "late-cancellation", displayName = "model.litertlm"),
            onProgress = { percent -> if (percent == 100) throw CancellationException("late") },
            commitMetadata = { true },
        )

        assertTrue(result is ModelImportResult.Success)
        assertEquals(1, root.listFiles().orEmpty().count { it.extension == "litertlm" })
        root.deleteRecursively()
    }

    @Test
    fun `caller cancellation cleans staging and releases source ownership`() = runTest {
        val root = Files.createTempDirectory("fusion-model-import").toFile()
        val bytes = ByteArray(8 * 1024 * 1024) { 3 }
        val firstRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val sourceOpens = AtomicInteger(0)
        val coordinator = ModelImportCoordinator(
            modelDirectory = root,
            openSource = {
                if (sourceOpens.getAndIncrement() == 0) object : InputStream() {
                    private var offset = 0
                    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                        firstRead.countDown()
                        assertTrue(releaseRead.await(5, TimeUnit.SECONDS))
                        if (offset >= bytes.size) return -1
                        val count = minOf(len, bytes.size - offset, 64 * 1024)
                        bytes.copyInto(buffer, off, offset, offset + count)
                        offset += count
                        return count
                    }
                    override fun read(): Int = 0
                }
                else ByteArrayInputStream(bytes)
            },
            usableSpace = { Long.MAX_VALUE },
            validator = LiteRtLmValidator { true },
        )
        val job = async(Dispatchers.IO) {
            coordinator.import(ModelImportRequest(sourceIdentity = "cancel", displayName = "cancel.litertlm"))
        }
        withTimeout(10_000) {
            while (!firstRead.await(10, TimeUnit.MILLISECONDS)) Unit
        }
        val duplicate = coordinator.import(
            ModelImportRequest(sourceIdentity = "cancel", displayName = "duplicate.litertlm"),
        )
        assertEquals(ModelImportFailure.ADOPTION_FAILED, (duplicate as ModelImportResult.Failure).kind)
        job.cancel()
        releaseRead.countDown()
        try {
            job.await()
            fail("copy cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") || it.extension == "litertlm" })
        val retry = coordinator.import(
            ModelImportRequest(sourceIdentity = "cancel", displayName = "retry.litertlm"),
        )
        assertTrue(retry is ModelImportResult.Success)
        root.deleteRecursively()
    }
}
