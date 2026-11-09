package com.projectnuke.fusion.ui

import com.projectnuke.fusion.util.AttachmentImportResult
import com.projectnuke.fusion.util.AttachmentReferenceMutationCoordinator
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFileImportCoordinatorTest {
    @Test
    fun `copied file and pending registration are cleaned after persistence failure`() = runTest {
        val root = Files.createTempDirectory("fusion-project-import").toFile()
        val generated = root.resolve("generated.txt").apply { writeText("content") }
        var pending = true
        try {
            val result = coordinator(generated) { pending = false }.copyAndPersist(
                copy = { AttachmentImportResult.Success(generated, generated.length()) },
                persist = { error("db failure") },
                isAdopted = { true },
            )

            assertEquals(ProjectFileImportResult.PersistenceFailed, result)
            assertFalse(generated.exists())
            assertFalse(pending)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancellation cleans copied file in non cancellable cleanup`() = runTest {
        val root = Files.createTempDirectory("fusion-project-import").toFile()
        val generated = root.resolve("generated.txt").apply { writeText("content") }
        var pending = true
        try {
            var cancelled = false
            try {
                coordinator(generated) { pending = false }.copyAndPersist(
                    copy = { AttachmentImportResult.Success(generated, generated.length()) },
                    persist = { throw CancellationException("test cancellation") },
                    isAdopted = { true },
                )
            } catch (_: CancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
            assertFalse(generated.exists())
            assertFalse(pending)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `successful adoption keeps file and releases pending registration`() = runTest {
        val root = Files.createTempDirectory("fusion-project-import").toFile()
        val generated = root.resolve("generated.txt").apply { writeText("content") }
        var pending = true
        try {
            val result = coordinator(generated) { pending = false }.copyAndPersist(
                copy = { AttachmentImportResult.Success(generated, generated.length()) },
                persist = { "adopted" },
                isAdopted = { it == "adopted" },
            )

            assertEquals(ProjectFileImportResult.Persisted("adopted"), result)
            assertTrue(generated.exists())
            assertFalse(pending)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `exception after durable commit preserves file and settles pending ownership`() = runTest {
        val root = Files.createTempDirectory("fusion-project-import-throw-after-commit").toFile()
        val generated = root.resolve("generated.txt").apply { writeText("content") }
        val durablePaths = mutableSetOf<String>()
        var pending = true
        try {
            val result = coordinator(generated) { pending = false }.copyAndPersist(
                copy = { AttachmentImportResult.Success(generated, generated.length()) },
                persist = {
                    durablePaths += generated.canonicalPath
                    throw IllegalStateException("post-commit observer failure")
                },
                isAdopted = { true },
                isDurablyReferenced = { it in durablePaths },
            )

            assertEquals(ProjectFileImportResult.PersistenceFailed, result)
            assertTrue("durable ownership must preserve the committed file", generated.exists())
            assertFalse("durably referenced file must leave pending ownership", pending)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed durable ownership lookup preserves file and pending registration`() = runTest {
        val root = Files.createTempDirectory("fusion-project-import-unknown-owner").toFile()
        val generated = root.resolve("generated.txt").apply { writeText("content") }
        var pending = true
        try {
            val result = coordinator(generated) { pending = false }.copyAndPersist(
                copy = { AttachmentImportResult.Success(generated, generated.length()) },
                persist = { error("persistence failed") },
                isAdopted = { true },
                isDurablyReferenced = { error("authority unavailable") },
            )

            assertEquals(ProjectFileImportResult.PersistenceFailed, result)
            assertTrue("unknown durable authority must fail closed", generated.exists())
            assertFalse("failed persistence settles the pending-copy registration", pending)
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- P2.4: the import transition is serialized with destructive GC -----

    @Test
    fun `durable import transition waits for the reference mutation gate`() = runTest {
        val root = Files.createTempDirectory("fusion-project-import-gate").toFile()
        val generated = root.resolve("generated.txt").apply { writeText("content") }
        var pending = true
        val gateHeld = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val gateHolder = launch {
            AttachmentReferenceMutationCoordinator.withMutation {
                gateHeld.complete(Unit)
                releaseGate.await()
            }
        }
        advanceUntilIdle()
        assertTrue(gateHeld.isCompleted)
        try {
            var persistStarted = false
            val import = launch {
                val result = coordinator(generated) { pending = false }.copyAndPersist(
                    copy = { AttachmentImportResult.Success(generated, generated.length()) },
                    persist = {
                        persistStarted = true
                        "adopted"
                    },
                    isAdopted = { it == "adopted" },
                )
                assertEquals(ProjectFileImportResult.Persisted("adopted"), result)
            }
            runCurrent()
            assertFalse("persist must wait for the mutation gate", persistStarted)

            releaseGate.complete(Unit)
            advanceUntilIdle()
            gateHolder.join()
            import.join()
            assertTrue(persistStarted)
            assertTrue(generated.exists())
            assertFalse(pending)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cleanup cannot observe pending settlement without the durable project row`() = runTest {
        val root = Files.createTempDirectory("fusion-project-import-gap").toFile()
        val generated = root.resolve("generated.txt").apply { writeText("content") }
        // Production pending-copy ownership for the freshly imported copy.
        AttachmentStorageManager.registerPendingAttachment(generated)
        val projectRows = mutableSetOf<String>()
        val persistStarted = CompletableDeferred<Unit>()
        val letPersistProceed = CompletableDeferred<Unit>()

        val import = launch {
            val result = ProjectFileImportCoordinator(
                deletePendingFile = { path ->
                    java.io.File(path).delete() || !java.io.File(path).exists()
                },
                unregisterPending = { AttachmentStorageManager.unregisterPendingAttachment(it) },
            ).copyAndPersist(
                copy = { AttachmentImportResult.Success(generated, generated.length()) },
                persist = {
                    persistStarted.complete(Unit)
                    letPersistProceed.await()
                    projectRows += generated.canonicalPath
                    "adopted"
                },
                isAdopted = { it == "adopted" },
            )
            assertEquals(ProjectFileImportResult.Persisted("adopted"), result)
        }
        advanceUntilIdle()
        assertTrue(persistStarted.isCompleted)
        try {
            var cleanupDeleted = -1
            val cleanup = launch {
                cleanupDeleted = AttachmentStorageManager.cleanupUnreferencedAttachmentsUnderMutationGate(
                    attachmentDir = root,
                    listFiles = { listOf(generated) },
                    messageAuthority = {
                        com.projectnuke.fusion.util.AttachmentReferenceAuthority.Complete(emptySet())
                    },
                    projectPaths = { projectRows },
                    durableDraftPaths = { emptySet() },
                    pendingPaths = { AttachmentStorageManager.pendingAttachmentPathsSnapshot() },
                    now = { System.currentTimeMillis() + 3_600_000L },
                )
            }
            runCurrent()
            // Cleanup is deterministically blocked while the import owns the
            // gate: no authority snapshot may observe "pending registration
            // gone AND the ProjectFileEntity not yet durable".
            assertEquals(-1, cleanupDeleted)

            letPersistProceed.complete(Unit)
            advanceUntilIdle()
            import.join()
            cleanup.join()

            assertEquals(
                "the durable project row must protect the imported file from cleanup",
                0,
                cleanupDeleted,
            )
            assertTrue(generated.exists())
            assertTrue(generated.canonicalPath in projectRows)
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(generated.canonicalPath)
            root.deleteRecursively()
        }
        }

    // ---- P11-07: post-commit cancellation must not relabel a committed success ----

    @Test
    fun `cancel after durable commit reports Persisted and preserves the adopted file`() = runTest {
        val root = Files.createTempDirectory("fusion-project-import-cancelled-after-commit").toFile()
        val generated = root.resolve("generated.txt").apply { writeText("content") }
        var pending = true
        AttachmentStorageManager.registerPendingAttachment(generated)
        try {
            val persistStarted = CompletableDeferred<Unit>()
            val letPersistFinish = CompletableDeferred<Unit>()
            var persisted: ProjectFileImportResult<String>? = null
            var thrown: CancellationException? = null
            val import = launch {
                try {
                    persisted = coordinator(generated) { pending = false }.copyAndPersist(
                        copy = { AttachmentImportResult.Success(generated, generated.length()) },
                        persist = {
                            persistStarted.complete(Unit)
                            // Cancellation is requested while persist is suspended
                            // HERE, inside withContext(NonCancellable): the commit
                            // and adoption settle before control can observe it.
                            letPersistFinish.await()
                            "adopted"
                        },
                        isAdopted = { it == "adopted" },
                        isDurablyReferenced = { path -> path == generated.canonicalPath },
                    )
                } catch (ce: CancellationException) {
                    thrown = ce
                    null
                }
            }
            advanceUntilIdle()
            assertTrue(persistStarted.isCompleted)
            import.cancel() // request cancellation during the NonCancellable commit
            letPersistFinish.complete(Unit) // let the commit + adoption settle
            advanceUntilIdle()

            assertNull(
                "P11-07: a committed success must not be rethrown as cancellation",
                thrown,
            )
            assertEquals(
                "P11-07: the durable Persisted outcome must reach the cancelled caller",
                ProjectFileImportResult.Persisted("adopted"),
                persisted,
            )
            assertTrue("adopted file must survive a post-commit cancellation", generated.exists())
            assertFalse("pending ownership must be released on adoption", pending)
        } finally {
            AttachmentStorageManager.unregisterPendingAttachment(generated.canonicalPath)
            root.deleteRecursively()
        }
    }

    private fun coordinator(file: java.io.File, onUnregister: () -> Unit) =
        ProjectFileImportCoordinator(
            deletePendingFile = {
                val deleted = file.delete() || !file.exists()
                if (deleted) onUnregister()
                deleted
            },
            unregisterPending = { onUnregister() },
        )
}
