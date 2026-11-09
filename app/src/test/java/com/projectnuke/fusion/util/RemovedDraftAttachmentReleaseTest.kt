package com.projectnuke.fusion.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RemovedDraftAttachmentReleaseTest {

    private fun freshRoot(tag: String): File =
        Files.createTempDirectory("removed_draft_release_$tag").toFile().apply {
            deleteOnExit()
        }

    private fun complete(paths: Collection<String> = emptyList()) =
        AttachmentReferenceAuthority.Complete(paths.map { it.canonicalize() }.toSet())

    private fun String.canonicalize(): String = File(this).canonicalPath

    private fun releaseCore(
        root: File,
        path: String,
        messageAuthority: AttachmentReferenceAuthority = complete(),
        projectPaths: Collection<String>? = emptyList(),
        durableDraftPaths: Collection<String>? = emptyList(),
        deleteFile: (File) -> Boolean = { it.delete() },
    ) = AttachmentStorageManager.releaseRemovedDraftAttachment(
        attachmentRoot = root,
        path = path,
        messageAuthority = messageAuthority,
        projectPaths = projectPaths,
        durableDraftPaths = durableDraftPaths,
        deleteFile = deleteFile,
    )

    @Test
    fun `durable removal failure never deletes and never unregisters`() {
        val root = freshRoot("durable_fail")
        val file = File(root, "F.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(file)

        var releaseCalled = false
        val outcome = kotlinx.coroutines.runBlocking {
            ComposerAttachmentRelease.remove(
                removeDurableReference = { false },
                releasePhysicalCopy = {
                    releaseCalled = true
                    RemovedAttachmentReleaseResult.Released
                },
            )
        }

        assertEquals(RemovedAttachmentReleaseResult.DurableRemovalFailed, outcome)
        assertFalse("physical release must not run after a failed durable removal", releaseCalled)
        assertTrue(file.exists())
        assertTrue(AttachmentStorageManager.isPendingAttachmentRegistered(file.absolutePath))
        root.deleteRecursively()
    }

    @Test
    fun `historical reattach removal is reference-only and preserves the source`() {
        val root = freshRoot("reattach")
        val file = File(root, "history.bin").apply { writeText("historical source") }

        // The historical message names F; performInventoryReattach put the
        // SAME path into the composer WITHOUT pending-copy registration;
        // the durable draft removal already committed.
        val outcome = releaseCore(
            root = root,
            path = file.absolutePath,
            messageAuthority = complete(listOf(file.canonicalPath)),
        )

        assertEquals(RemovedAttachmentReleaseResult.ReferenceOnly, outcome)
        assertTrue("shared historical source must survive composer removal", file.exists())
        root.deleteRecursively()
    }

    @Test
    fun `project-shared pending file is preserved after draft removal`() {
        val root = freshRoot("project_shared")
        val file = File(root, "shared.pdf").apply { writeText("content") }
        AttachmentStorageManager.registerPendingAttachment(file)

        // Durable draft removal committed; a project row still names F.
        val outcome = releaseCore(
            root = root,
            path = file.absolutePath,
            projectPaths = listOf(file.canonicalPath),
        )

        assertEquals(RemovedAttachmentReleaseResult.PreservedSharedReference, outcome)
        assertTrue("project-owned source must survive composer removal", file.exists())
        assertFalse(
            "pending ownership is settled once the durable draft released it",
            AttachmentStorageManager.isPendingAttachmentRegistered(file.absolutePath),
        )
        root.deleteRecursively()
    }

    @Test
    fun `truly unreferenced pending copy is deleted`() {
        val root = freshRoot("unreferenced")
        val file = File(root, "fresh.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(file)
        var deletedForIndex = 0
        AttachmentStorageManager.releaseRemovedDraftAttachment(
            attachmentRoot = root,
            path = file.absolutePath,
            messageAuthority = complete(),
            projectPaths = emptyList(),
            durableDraftPaths = emptyList(),
            onDeletedFile = { deletedForIndex++; },
        )

        assertFalse(file.exists())
        assertFalse(AttachmentStorageManager.isPendingAttachmentRegistered(file.absolutePath))
        assertEquals(1, deletedForIndex)
        root.deleteRecursively()
    }

    @Test
    fun `incomplete authority fails closed and preserves the file`() {
        val root = freshRoot("incomplete")

        val messageUnknown = File(root, "m.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(messageUnknown)
        val m = releaseCore(
            root,
            messageUnknown.absolutePath,
            messageAuthority = AttachmentReferenceAuthority.IncompleteAuthority,
        )
        assertEquals(RemovedAttachmentReleaseResult.PreservedAuthorityIncomplete, m)
        assertTrue(messageUnknown.exists())

        val projectUnknown = File(root, "p.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(projectUnknown)
        val p = releaseCore(
            root,
            projectUnknown.absolutePath,
            projectPaths = null,
        )
        assertEquals(RemovedAttachmentReleaseResult.PreservedAuthorityIncomplete, p)
        assertTrue(projectUnknown.exists())

        val draftUnknown = File(root, "d.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(draftUnknown)
        val d = releaseCore(
            root,
            draftUnknown.absolutePath,
            durableDraftPaths = null,
        )
        assertEquals(RemovedAttachmentReleaseResult.PreservedAuthorityIncomplete, d)
        assertTrue(draftUnknown.exists())

        // Settlement is intentional either way: no pending ownership leaks.
        assertFalse(AttachmentStorageManager.isPendingAttachmentRegistered(messageUnknown.absolutePath))
        assertFalse(AttachmentStorageManager.isPendingAttachmentRegistered(projectUnknown.absolutePath))
        assertFalse(AttachmentStorageManager.isPendingAttachmentRegistered(draftUnknown.absolutePath))
        root.deleteRecursively()
    }

    @Test
    fun `another durable draft reference preserves the file`() {
        val root = freshRoot("other_draft")
        val file = File(root, "shared.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(file)

        // Draft A's removal committed; draft B (another conversation) still
        // names the same canonical managed file.
        val outcome = releaseCore(
            root = root,
            path = file.absolutePath,
            durableDraftPaths = listOf(file.canonicalPath),
        )

        assertEquals(RemovedAttachmentReleaseResult.PreservedSharedReference, outcome)
        assertTrue("file still referenced by another durable draft", file.exists())
        root.deleteRecursively()
    }

    @Test
    fun `message reference alone preserves a pending-owned file`() {
        val root = freshRoot("message_ref")
        val file = File(root, "msg.bin").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(file)

        val outcome = releaseCore(
            root = root,
            path = file.absolutePath,
            messageAuthority = complete(listOf(file.canonicalPath)),
        )

        assertEquals(RemovedAttachmentReleaseResult.PreservedSharedReference, outcome)
        assertTrue(file.exists())
        root.deleteRecursively()
    }

    @Test
    fun `deletion failure re-settles pending protection`() {
        val root = freshRoot("delete_failed")
        val file = File(root, "stuck.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(file)

        val outcome = releaseCore(
            root = root,
            path = file.absolutePath,
            deleteFile = { false },
        )

        assertEquals(RemovedAttachmentReleaseResult.DeletionFailed, outcome)
        assertTrue(file.exists())
        assertTrue(
            "a file that failed to delete stays protected from cleanup",
            AttachmentStorageManager.isPendingAttachmentRegistered(file.absolutePath),
        )
        AttachmentStorageManager.unregisterPendingAttachment(file.absolutePath)
        root.deleteRecursively()
    }

    @Test
    fun `release refuses a registered path outside the attachment root`() {
        val root = freshRoot("outside_root")
        val outside = Files.createTempFile("removed_release_outside", ".txt").toFile()
        outside.writeText("outside")
        outside.deleteOnExit()
        AttachmentStorageManager.registerPendingAttachment(outside)

        val outcome = releaseCore(root = root, path = outside.absolutePath)

        assertEquals(RemovedAttachmentReleaseResult.InvalidPath, outcome)
        assertTrue(outside.exists())
        AttachmentStorageManager.unregisterPendingAttachment(outside.absolutePath)
        root.deleteRecursively()
    }

    @Test
    fun `blank path is rejected without any mutation`() {
        val root = freshRoot("blank")
        val file = File(root, "F.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(file)

        val outcome = releaseCore(root = root, path = "")

        assertEquals(RemovedAttachmentReleaseResult.InvalidPath, outcome)
        assertTrue(file.exists())
        assertTrue(AttachmentStorageManager.isPendingAttachmentRegistered(file.absolutePath))
        AttachmentStorageManager.unregisterPendingAttachment(file.absolutePath)
        root.deleteRecursively()
    }

    @Test
    fun `cancellation during durable removal never settles the release`() {
        val root = freshRoot("cancel_durable")
        val file = File(root, "F.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(file)

        val durableGate = CompletableDeferred<Boolean>()
        var releaseCalled = false
        val job: Job = CoroutineScope(Dispatchers.Unconfined).launch {
            ComposerAttachmentRelease.remove(
                removeDurableReference = { durableGate.await() },
                releasePhysicalCopy = {
                    releaseCalled = true
                    RemovedAttachmentReleaseResult.Released
                },
            )
        }
        job.cancel()
        kotlinx.coroutines.runBlocking { job.join() }

        assertFalse("cancellation before durable success must never touch the file", releaseCalled)
        assertTrue(file.exists())
        assertTrue(AttachmentStorageManager.isPendingAttachmentRegistered(file.absolutePath))
        AttachmentStorageManager.unregisterPendingAttachment(file.absolutePath)
        root.deleteRecursively()
    }

    @Test
    fun `cancellation after durable success still settles the physical release`() {
        val root = freshRoot("cancel_after")
        val file = File(root, "F.txt").apply { writeText("data") }
        AttachmentStorageManager.registerPendingAttachment(file)

        val midRelease = CompletableDeferred<Unit>()
        var releaseEntered = false
        val job: Job = CoroutineScope(Dispatchers.Unconfined).launch {
            ComposerAttachmentRelease.remove(
                removeDurableReference = { true },
                releasePhysicalCopy = {
                    releaseEntered = true
                    // Simulate suspension inside the authority scan while
                    // the enclosing work cancels; NonCancellable settlement
                    // must keep the physical decision running to the end.
                    midRelease.await()
                    AttachmentStorageManager.releaseRemovedDraftAttachment(
                        attachmentRoot = root,
                        path = file.absolutePath,
                        messageAuthority = complete(),
                        projectPaths = emptyList(),
                        durableDraftPaths = emptyList(),
                    )
                },
            )
        }
        job.cancel()
        midRelease.complete(Unit)
        kotlinx.coroutines.runBlocking { job.join() }

        assertTrue("release must enter after durable success", releaseEntered)
        assertFalse("cancelled work cannot abort a committed durable removal's release", file.exists())
        assertFalse(AttachmentStorageManager.isPendingAttachmentRegistered(file.absolutePath))
        assertTrue(
            "cancellation must be recorded on the job, not silently lost",
            job.isCancelled,
        )
        root.deleteRecursively()
    }
}
