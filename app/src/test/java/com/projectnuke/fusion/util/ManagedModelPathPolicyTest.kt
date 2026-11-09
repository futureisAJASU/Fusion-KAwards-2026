package com.projectnuke.fusion.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class ManagedModelPathPolicyTest {
    @Test
    fun `accepts only direct runnable model files`() {
        val root = Files.createTempDirectory("fusion-models").toFile()
        val direct = File(root, "model.litertlm").apply { writeText("model") }
        val nested = File(root, "nested").apply { mkdirs() }
        val nestedModel = File(nested, "nested.litertlm").apply { writeText("model") }
        val unsupported = File(root, "model.gguf").apply { writeText("model") }
        val mediaPipeTask = File(root, "model.task").apply { writeText("model") }
        val sibling = File(root.parentFile, "${root.name}_other").apply { mkdirs() }
        val siblingModel = File(sibling, "outside.litertlm").apply { writeText("model") }

        assertEquals(direct.canonicalFile, ManagedModelPathPolicy.resolveRunnableModel(root, direct.absolutePath))
        assertNull(ManagedModelPathPolicy.resolveRunnableModel(root, nestedModel.absolutePath))
        assertNull(ManagedModelPathPolicy.resolveRunnableModel(root, unsupported.absolutePath))
        assertNull(ManagedModelPathPolicy.resolveRunnableModel(root, mediaPipeTask.absolutePath))
        assertNull(ManagedModelPathPolicy.resolveRunnableModel(root, siblingModel.absolutePath))
    }

    @Test
    fun `managed target accepts an absent direct child for idempotent cleanup`() {
        val root = Files.createTempDirectory("fusion-models").toFile()
        val absent = File(root, "missing.gguf")
        val nestedAbsent = File(File(root, "nested"), "missing.gguf")

        assertEquals(absent.canonicalFile, ManagedModelPathPolicy.resolveManagedTarget(root, absent.absolutePath))
        assertNull(ManagedModelPathPolicy.resolveManagedTarget(root, nestedAbsent.absolutePath))
    }

    @Test
    fun `managed file rejects directories`() {
        val root = Files.createTempDirectory("fusion-models").toFile()
        val directory = File(root, "model.task").apply { mkdirs() }

        assertNull(ManagedModelPathPolicy.resolveManagedFile(root, directory.absolutePath))
    }

    @Test
    fun `rejects direct and intermediate symbolic links`() {
        val root = Files.createTempDirectory("fusion-models").toFile()
        val outside = Files.createTempDirectory("fusion-outside").toFile()
        val outsideModel = File(outside, "outside.litertlm").apply { writeText("model") }

        val directLink = File(root, "direct.litertlm").toPath()
        val nestedLink = File(root, "nested-link").toPath()
        val symlinkSupported = runCatching {
            Files.createSymbolicLink(directLink, outsideModel.toPath())
            Files.createSymbolicLink(nestedLink, outside.toPath())
        }.isSuccess
        assumeTrue(symlinkSupported)

        assertNull(ManagedModelPathPolicy.resolveRunnableModel(root, directLink.toString()))
        assertNull(ManagedModelPathPolicy.resolveRunnableModel(root, File(nestedLink.toFile(), "outside.litertlm").absolutePath))
    }
}
