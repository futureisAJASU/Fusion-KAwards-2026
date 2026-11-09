package com.projectnuke.fusion.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Batch authority-aware release for every post-draft-removal production path
 * (fresh New Chat reset, external handoff replacement, conversation
 * deletion settlement, cleanup-debt retry). "Removed from a draft" never
 * proves exclusive physical ownership, so shared managed files must survive
 * every one of these paths while genuine orphan pending copies are still
 * deleted.
 */
class RemovedDraftAttachmentBatchReleaseTest {

    private fun freshRoot(tag: String): File =
        Files.createTempDirectory("removed_draft_batch_$tag").toFile().apply {
            deleteOnExit()
        }

    private fun complete(paths: Collection<String> = emptyList()) =
        AttachmentReferenceAuthority.Complete(paths.map { File(it).canonicalPath }.toSet())

    private fun releaseBatch(
        root: File,
        paths: Collection<String?>,
        messageAuthority: AttachmentReferenceAuthority = complete(),
        projectPaths: Collection<String>? = emptyList(),
        durableDraftPaths: Collection<String>? = emptyList(),
    ): Map<String, RemovedAttachmentReleaseResult> = AttachmentStorageManager.releaseRemovedDraftAttachments(
        attachmentRoot = root,
        paths = paths,
        messageAuthority = messageAuthority,
        projectPaths = projectPaths,
        durableDraftPaths = durableDraftPaths,
    )

    // ---- P1.1: fresh New Chat preserves a historical reattach -------------

    @Test
    fun `fresh new chat release preserves a historical reattach by reference`() {
        val root = freshRoot("reattach_ref")
        val historical = File(root, "F.bin").apply { writeText("historical source") }

        // An inventory reattach put the SAME path into the NEW_CHAT draft
        // WITHOUT pending-copy registration; the durable draft reset already
        // committed (what openFreshNewChat's returned paths mean).
        val outcomes = releaseBatch(root, listOf(historical.canonicalPath))

        assertEquals(
            RemovedAttachmentReleaseResult.ReferenceOnly,
            outcomes[historical.canonicalPath],
        )
        assertTrue("shared historical source must survive the fresh reset", historical.exists())
        root.deleteRecursively()
    }

    @Test
    fun `fresh new chat release preserves a pending copy also named by a historical message`() {
        val root = freshRoot("reattach_pending")
        val historical = File(root, "F.bin").apply { writeText("historical source") }
        AttachmentStorageManager.registerPendingAttachment(historical)

        val outcomes = releaseBatch(
            root,
            listOf(historical.canonicalPath),
            messageAuthority = complete(listOf(historical.canonicalPath)),
        )

        assertEquals(
            RemovedAttachmentReleaseResult.PreservedSharedReference,
            outcomes[historical.canonicalPath],
        )
        assertTrue("message-referenced source must survive the fresh reset", historical.exists())
        assertFalse(AttachmentStorageManager.isPendingAttachmentRegistered(historical.absolutePath))
        root.deleteRecursively()
    }

    // ---- P1.4: conversation deletion preserves other durable owners -------

    @Test
    fun `conversation deletion release preserves a project-owned path`() {
        val root = freshRoot("deletion_project")
        val shared = File(root, "F.pdf").apply { writeText("project-shared") }
        AttachmentStorageManager.registerPendingAttachment(shared)

        // The target conversation's draft is gone; a project row still names F.
        val outcomes = releaseBatch(
            root,
            listOf(shared.canonicalPath),
            projectPaths = listOf(shared.canonicalPath),
        )

        assertEquals(
            RemovedAttachmentReleaseResult.PreservedSharedReference,
            outcomes[shared.canonicalPath],
        )
        assertTrue("project-owned source must survive conversation deletion", shared.exists())
        root.deleteRecursively()
    }

    @Test
    fun `conversation deletion release preserves another durable draft reference`() {
        val root = freshRoot("deletion_draft")
        val shared = File(root, "F.txt").apply { writeText("draft-shared") }
        AttachmentStorageManager.registerPendingAttachment(shared)

        // Conversation A's draft is gone; conversation B's durable draft
        // (or the NEW_CHAT draft) still names the same file.
        val outcomes = releaseBatch(
            root,
            listOf(shared.canonicalPath),
            durableDraftPaths = listOf(shared.canonicalPath),
        )

        assertEquals(
            RemovedAttachmentReleaseResult.PreservedSharedReference,
            outcomes[shared.canonicalPath],
        )
        assertTrue("another draft's source must survive conversation deletion", shared.exists())
        root.deleteRecursively()
    }

    // ---- P1.5: cleanup debt cannot destructively retry a shared path ------

    @Test
    fun `cleanup debt release preserves a path with now-surviving durable authority`() {
        val root = freshRoot("debt_shared")
        val shared = File(root, "F.txt").apply { writeText("now-shared") }
        AttachmentStorageManager.registerPendingAttachment(shared)

        // The debt was recorded before a historical message (or any durable
        // owner) began referencing the path; a crash/retry must not turn the
        // conservative debt into permission to destroy the shared file.
        val outcomes = releaseBatch(
            root,
            listOf(shared.canonicalPath),
            messageAuthority = complete(listOf(shared.canonicalPath)),
        )

        assertEquals(
            RemovedAttachmentReleaseResult.PreservedSharedReference,
            outcomes[shared.canonicalPath],
        )
        assertTrue("shared path must not be deleted by debt retry", shared.exists())
        root.deleteRecursively()
    }

    @Test
    fun `settled classification keeps only genuinely finished outcomes`() {
        val settled = listOf(
            RemovedAttachmentReleaseResult.Released,
            RemovedAttachmentReleaseResult.AlreadyAbsent,
            RemovedAttachmentReleaseResult.ReferenceOnly,
            RemovedAttachmentReleaseResult.PreservedSharedReference,
        )
        val unsettled = listOf(
            RemovedAttachmentReleaseResult.DurableRemovalFailed,
            RemovedAttachmentReleaseResult.PreservedAuthorityIncomplete,
            RemovedAttachmentReleaseResult.InvalidPath,
            RemovedAttachmentReleaseResult.InvalidTarget,
            RemovedAttachmentReleaseResult.DeletionFailed,
        )
        settled.forEach { assertTrue("$it must settle cleanup bookkeeping", it.isPhysicalCleanupSettled()) }
        unsettled.forEach {
            assertFalse("$it must stay retryable in cleanup bookkeeping", it.isPhysicalCleanupSettled())
        }
    }

    // ---- genuine orphan batches still delete -------------------------------

    @Test
    fun `batch release deletes genuine orphan pending copies per path`() {
        val root = freshRoot("orphans")
        val orphanA = File(root, "a.txt").apply { writeText("a") }
        val orphanB = File(root, "b.txt").apply { writeText("b") }
        AttachmentStorageManager.registerPendingAttachment(orphanA)
        AttachmentStorageManager.registerPendingAttachment(orphanB)

        val outcomes = releaseBatch(root, listOf(orphanA.canonicalPath, orphanB.canonicalPath))

        assertEquals(RemovedAttachmentReleaseResult.Released, outcomes[orphanA.canonicalPath])
        assertEquals(RemovedAttachmentReleaseResult.Released, outcomes[orphanB.canonicalPath])
        assertFalse(orphanA.exists())
        assertFalse(orphanB.exists())
        assertFalse(AttachmentStorageManager.isPendingAttachmentRegistered(orphanA.absolutePath))
        assertFalse(AttachmentStorageManager.isPendingAttachmentRegistered(orphanB.absolutePath))
        root.deleteRecursively()
    }

    @Test
    fun `batch release settles mixed shared orphan and reference-only paths independently`() {
        val root = freshRoot("mixed")
        val shared = File(root, "shared.txt").apply { writeText("s") }
        val orphan = File(root, "orphan.txt").apply { writeText("o") }
        val reattached = File(root, "reattached.txt").apply { writeText("r") }
        AttachmentStorageManager.registerPendingAttachment(shared)
        AttachmentStorageManager.registerPendingAttachment(orphan)

        val outcomes = releaseBatch(
            root,
            listOf(shared.canonicalPath, orphan.canonicalPath, reattached.canonicalPath),
            projectPaths = listOf(shared.canonicalPath),
        )

        assertEquals(RemovedAttachmentReleaseResult.PreservedSharedReference, outcomes[shared.canonicalPath])
        assertEquals(RemovedAttachmentReleaseResult.Released, outcomes[orphan.canonicalPath])
        assertEquals(RemovedAttachmentReleaseResult.ReferenceOnly, outcomes[reattached.canonicalPath])
        assertTrue(shared.exists())
        assertFalse(orphan.exists())
        assertTrue(reattached.exists())
        root.deleteRecursively()
    }

    @Test
    fun `batch release fails closed on incomplete authority`() {
        val root = freshRoot("incomplete")
        val a = File(root, "a.txt").apply { writeText("a") }
        val b = File(root, "b.txt").apply { writeText("b") }
        AttachmentStorageManager.registerPendingAttachment(a)
        AttachmentStorageManager.registerPendingAttachment(b)

        val outcomes = releaseBatch(
            root,
            listOf(a.canonicalPath, b.canonicalPath),
            messageAuthority = AttachmentReferenceAuthority.IncompleteAuthority,
        )

        assertEquals(RemovedAttachmentReleaseResult.PreservedAuthorityIncomplete, outcomes[a.canonicalPath])
        assertEquals(RemovedAttachmentReleaseResult.PreservedAuthorityIncomplete, outcomes[b.canonicalPath])
        assertTrue(a.exists())
        assertTrue(b.exists())
        // Settlement is intentional either way: no pending ownership leaks.
        assertFalse(AttachmentStorageManager.isPendingAttachmentRegistered(a.absolutePath))
        assertFalse(AttachmentStorageManager.isPendingAttachmentRegistered(b.absolutePath))
        root.deleteRecursively()
    }

    @Test
    fun `blank null and duplicate batch entries settle without mutation`() {
        val root = freshRoot("blank")
        val orphan = File(root, "o.txt").apply { writeText("o") }
        AttachmentStorageManager.registerPendingAttachment(orphan)

        val outcomes = releaseBatch(root, listOf(null, "", orphan.canonicalPath, orphan.canonicalPath))

        // Blank/null entries are no-ops (nothing to release); duplicates
        // collapse to one entry. Only the genuine orphan settles.
        assertEquals(1, outcomes.size)
        assertEquals(RemovedAttachmentReleaseResult.Released, outcomes[orphan.canonicalPath])
        assertFalse(orphan.exists())
        assertTrue(outcomes.keys.none { it.isBlank() })
        root.deleteRecursively()
    }

    @Test
    fun `empty batch settles nothing`() {
        val root = freshRoot("empty")
        assertTrue(releaseBatch(root, emptyList()).isEmpty())
        assertTrue(releaseBatch(root, listOf(null)).isEmpty())
        root.deleteRecursively()
    }
}
