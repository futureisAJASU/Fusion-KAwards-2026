package com.projectnuke.fusion.modelzoo

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedModelDeletionCoordinatorTest {
    @Test
    fun `metadata failure restores staged binary byte for byte`() = runTest {
        val root = Files.createTempDirectory("fusion-delete").toFile()
        val target = File(root, "model.litertlm").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val result = ManagedModelDeletionCoordinator(root).delete(target.absolutePath) { false }

        assertEquals(
            ManagedModelDeletionResult.Failed(ManagedModelDeletionFailure.METADATA_FAILED),
            result,
        )
        assertTrue(target.isFile)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), target.readBytes())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".fusion-delete-") })
        root.deleteRecursively()
    }

    @Test
    fun `metadata success commits deletion and leaves no referenced binary`() = runTest {
        val root = Files.createTempDirectory("fusion-delete").toFile()
        val target = File(root, "model.litertlm").apply { writeBytes(ByteArray(128) { 7 }) }
        var removeCalls = 0
        val result = ManagedModelDeletionCoordinator(root).delete(target.absolutePath) {
            removeCalls++
            true
        }

        assertEquals(ManagedModelDeletionResult.Committed, result)
        assertEquals(1, removeCalls)
        assertFalse(target.exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".fusion-delete-") })
        root.deleteRecursively()
    }

    @Test
    fun `cancellation before metadata boundary restores original`() = runTest {
        val root = Files.createTempDirectory("fusion-delete").toFile()
        val target = File(root, "model.litertlm").apply { writeText("original") }
        val coordinator = ManagedModelDeletionCoordinator(root)
        val cancelled = async {
            coordinator.delete(target.absolutePath) {
                throw AssertionError("metadata must not be reached after cancellation")
            }
        }
        cancelled.cancel()
        try {
            cancelled.await()
        } catch (_: CancellationException) {
            // Expected: the staged file is restored by the NonCancellable exit.
        }
        assertEquals("original", target.readText())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".fusion-delete-") })
        root.deleteRecursively()
    }

    @Test
    fun `missing binary can still commit metadata removal`() = runTest {
        val root = Files.createTempDirectory("fusion-delete").toFile()
        val target = File(root, "already-missing.litertlm")
        var removed = false
        val result = ManagedModelDeletionCoordinator(root).delete(target.absolutePath) {
            removed = true
            true
        }
        assertEquals(ManagedModelDeletionResult.Committed, result)
        assertTrue(removed)
        root.deleteRecursively()
    }
}
