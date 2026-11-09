package com.projectnuke.fusion.modelzoo

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedModelDeletionRestoreFailureTest {
    @Test
    fun `metadata failure plus rollback failure reports restore failed`() = runTest {
        val root = Files.createTempDirectory("fusion-delete-restore").toFile()
        val target = File(root, "model.litertlm").apply { writeText("original") }
        var moveCalls = 0
        val coordinator = ManagedModelDeletionCoordinator(root) { from, to ->
            moveCalls++
            if (moveCalls == 1) {
                Files.move(from.toPath(), to.toPath())
                true
            } else {
                false
            }
        }

        val result = coordinator.delete(target.absolutePath) { false }

        assertEquals(
            ManagedModelDeletionResult.Failed(ManagedModelDeletionFailure.RESTORE_FAILED),
            result,
        )
        assertFalse(target.exists())
        assertTrue(root.listFiles().orEmpty().any { it.name.startsWith(".fusion-delete-") })
        root.deleteRecursively()
    }
}
