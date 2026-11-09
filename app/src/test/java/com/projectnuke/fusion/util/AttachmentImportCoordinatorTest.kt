package com.projectnuke.fusion.util

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class AttachmentImportCoordinatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `declared per-file overflow is rejected without opening source`() = runBlocking {
        var opened = false
        val coordinator = coordinator(
            bytes = ByteArray(1),
            budget = AttachmentImportBudget(perFileBytes = 8, perBatchBytes = 16, reserveBytes = 0),
            onOpen = { opened = true },
        )
        val result = coordinator.copy("test", "large.txt", declaredLength = 9)

        assertEquals(
            AttachmentImportFailure.FILE_TOO_LARGE,
            (result as AttachmentImportResult.Failure).kind,
        )
        assertFalse(opened)
        assertNoArtifacts()
    }

    @Test
    fun `unknown length provider is hard capped and part is cleaned`() = runBlocking {
        val coordinator = coordinator(
            bytes = ByteArray(9) { 1 },
            budget = AttachmentImportBudget(perFileBytes = 8, perBatchBytes = 16, reserveBytes = 0),
        )
        val result = coordinator.copy("test", "unknown.bin", declaredLength = null)

        assertEquals(
            AttachmentImportFailure.FILE_TOO_LARGE,
            (result as AttachmentImportResult.Failure).kind,
        )
        assertNoArtifacts()
    }

    @Test
    fun `batch budget rejects later file and retains first adopted file`() = runBlocking {
        val coordinator = coordinator(
            bytes = ByteArray(6) { 2 },
            budget = AttachmentImportBudget(perFileBytes = 8, perBatchBytes = 10, reserveBytes = 0),
        )
        val first = coordinator.copy("test", "first.bin", declaredLength = 6)
        val second = coordinator.copy("test", "second.bin", declaredLength = 6)

        assertTrue(first is AttachmentImportResult.Success)
        assertEquals(
            AttachmentImportFailure.BATCH_TOO_LARGE,
            (second as AttachmentImportResult.Failure).kind,
        )
        assertEquals(6L, coordinator.copiedBatchBytes())
        assertEquals(1, visibleFiles().size)
    }

    @Test
    fun `insufficient storage rejects before copy and leaves no artifacts`() = runBlocking {
        val coordinator = AttachmentImportCoordinator(
            attachmentRoot = temp.root,
            inputFactory = { ByteArrayInputStream(ByteArray(4)) },
            budget = AttachmentImportBudget(perFileBytes = 8, perBatchBytes = 16, reserveBytes = 8),
            usableSpace = { 10L },
            registerPending = { it.absolutePath },
        )
        val result = coordinator.copy("test", "full.bin", declaredLength = 4)

        assertEquals(
            AttachmentImportFailure.STORAGE_FULL,
            (result as AttachmentImportResult.Failure).kind,
        )
        assertNoArtifacts()
    }

    @Test
    fun `failed atomic adoption cleans partial file`() = runBlocking {
        val coordinator = AttachmentImportCoordinator(
            attachmentRoot = temp.root,
            inputFactory = { ByteArrayInputStream(ByteArray(4) { 3 }) },
            budget = AttachmentImportBudget(perFileBytes = 8, perBatchBytes = 16, reserveBytes = 0),
            usableSpace = { Long.MAX_VALUE },
            atomicAdopter = AtomicAttachmentAdopter { _, _ -> error("move failed") },
            registerPending = { it.absolutePath },
        )
        val result = coordinator.copy("test", "move.bin", declaredLength = 4)

        assertEquals(
            AttachmentImportFailure.INVALID_TARGET,
            (result as AttachmentImportResult.Failure).kind,
        )
        assertNoArtifacts()
    }

    @Test
    fun `supported atomic adoption uses exactly the atomic path`() = runBlocking {
        var atomicCalls = 0
        val coordinator = AttachmentImportCoordinator(
            attachmentRoot = temp.root,
            inputFactory = { ByteArrayInputStream(ByteArray(4) { 3 }) },
            budget = AttachmentImportBudget(perFileBytes = 8, perBatchBytes = 16, reserveBytes = 0),
            usableSpace = { Long.MAX_VALUE },
            atomicAdopter = AtomicAttachmentAdopter { source, target ->
                atomicCalls++
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            },
            registerPending = { it.absolutePath },
        )

        assertTrue(coordinator.copy("test", "atomic.bin", declaredLength = 4) is AttachmentImportResult.Success)
        assertEquals(1, atomicCalls)
        assertEquals(1, visibleFiles().size)
    }

    @Test
    fun `unsupported atomic adoption falls back to same-directory move`() = runBlocking {
        var atomicCalls = 0
        val coordinator = AttachmentImportCoordinator(
            attachmentRoot = temp.root,
            inputFactory = { ByteArrayInputStream(ByteArray(4) { 4 }) },
            budget = AttachmentImportBudget(perFileBytes = 8, perBatchBytes = 16, reserveBytes = 0),
            usableSpace = { Long.MAX_VALUE },
            atomicAdopter = AtomicAttachmentAdopter { source, target ->
                atomicCalls++
                throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "unsupported")
            },
            registerPending = { it.absolutePath },
        )

        val result = coordinator.copy("test", "fallback.bin", declaredLength = 4)
        assertTrue(result is AttachmentImportResult.Success)
        assertEquals(1, atomicCalls)
        assertEquals(1, visibleFiles().size)
    }

    @Test
    fun `arbitrary filesystem failure does not trigger fallback`() = runBlocking {
        var calls = 0
        val coordinator = AttachmentImportCoordinator(
            attachmentRoot = temp.root,
            inputFactory = { ByteArrayInputStream(ByteArray(4) { 5 }) },
            budget = AttachmentImportBudget(perFileBytes = 8, perBatchBytes = 16, reserveBytes = 0),
            usableSpace = { Long.MAX_VALUE },
            atomicAdopter = AtomicAttachmentAdopter { _, _ ->
                calls++
                throw IOException("arbitrary filesystem failure")
            },
            registerPending = { it.absolutePath },
        )

        val result = coordinator.copy("test", "io.bin", declaredLength = 4)
        assertEquals(AttachmentImportFailure.INVALID_TARGET, (result as AttachmentImportResult.Failure).kind)
        assertEquals(1, calls)
        assertNoArtifacts()
    }

    @Test
    fun `existing target is never clobbered by unsupported atomic fallback`() = runBlocking {
        var existingTarget: java.io.File? = null
        val coordinator = AttachmentImportCoordinator(
            attachmentRoot = temp.root,
            inputFactory = { ByteArrayInputStream(ByteArray(4) { 6 }) },
            budget = AttachmentImportBudget(perFileBytes = 8, perBatchBytes = 16, reserveBytes = 0),
            usableSpace = { Long.MAX_VALUE },
            atomicAdopter = AtomicAttachmentAdopter { source, target ->
                existingTarget = target.toFile().apply { writeText("unrelated") }
                throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "unsupported")
            },
            registerPending = { it.absolutePath },
        )

        val result = coordinator.copy("test", "collision.bin", declaredLength = 4)
        assertEquals(AttachmentImportFailure.INVALID_TARGET, (result as AttachmentImportResult.Failure).kind)
        assertEquals("unrelated", existingTarget?.readText())
        assertEquals(1, visibleFiles().size)
        assertEquals("unrelated", visibleFiles().single().readText())
    }

    private fun coordinator(
        bytes: ByteArray,
        budget: AttachmentImportBudget,
        onOpen: () -> Unit = {},
    ) = AttachmentImportCoordinator(
        attachmentRoot = temp.root,
        inputFactory = {
            onOpen()
            ByteArrayInputStream(bytes)
        },
        budget = budget,
        usableSpace = { Long.MAX_VALUE },
        registerPending = { it.absolutePath },
        unregisterPending = {},
    )

    private fun visibleFiles() = temp.root.listFiles().orEmpty().filter { !it.name.endsWith(".part") }

    private fun assertNoArtifacts() {
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    }
}
