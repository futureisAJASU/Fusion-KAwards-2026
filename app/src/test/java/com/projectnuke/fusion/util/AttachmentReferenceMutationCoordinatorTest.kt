package com.projectnuke.fusion.util

import com.projectnuke.fusion.ui.SubmissionCommitState
import com.projectnuke.fusion.ui.commitAndSettleUserSubmission
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Linearizability of managed-attachment ownership transitions: durable
 * reference creation/transfer (promotion, project import, submission
 * draft->message) is serialized with destructive physical cleanup
 * (targeted removed-draft release, global orphan GC) through the
 * process-wide [AttachmentReferenceMutationCoordinator] gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentReferenceMutationCoordinatorTest {

    private fun freshRoot(tag: String): File =
        Files.createTempDirectory("reference_mutation_$tag").toFile().apply {
            deleteOnExit()
        }

    private fun complete(paths: Collection<String> = emptyList()) =
        AttachmentReferenceAuthority.Complete(paths.map { File(it).canonicalPath }.toSet())

    // ---- P2.1: promotion begins first --------------------------------------

    @Test
    fun `promotion holding the gate commits its project reference before a concurrent release can delete`() =
        runTest {
            val root = freshRoot("promotion_first")
            val shared = File(root, "F.txt").apply { writeText("promotion source") }
            AttachmentStorageManager.registerPendingAttachment(shared)
            val projectRows = mutableSetOf<String>()
            val promotionEntered = CompletableDeferred<Unit>()
            val promotionHolds = CompletableDeferred<Unit>()

            val promotion: Job = launch {
                // Promotion of an existing managed file: admission + durable
                // ProjectFileEntity insert, exactly the critical section the
                // production promotion runs under withMutation.
                AttachmentReferenceMutationCoordinator.withMutation {
                    promotionEntered.complete(Unit)
                    promotionHolds.await()
                    projectRows += shared.canonicalPath
                }
            }
            advanceUntilIdle()
            assertTrue(promotionEntered.isCompleted)

            val releaseOutcome = CompletableDeferred<Map<String, RemovedAttachmentReleaseResult>>()
            val release: Job = launch {
                releaseOutcome.complete(
                    AttachmentStorageManager.releaseRemovedDraftAttachmentsUnderMutationGate(
                        attachmentRootProvider = { root },
                        paths = listOf(shared.canonicalPath),
                        messageAuthority = { complete() },
                        projectPaths = { projectRows },
                        durableDraftPaths = { emptySet() },
                    ),
                )
            }
            runCurrent()
            // The release is deterministically blocked while promotion owns
            // the gate: no deletion decision can be made yet.
            assertFalse(releaseOutcome.isCompleted)
            assertTrue(shared.exists())

            promotionHolds.complete(Unit)
            advanceUntilIdle()
            promotion.join()
            release.join()

            assertEquals(
                RemovedAttachmentReleaseResult.PreservedSharedReference,
                releaseOutcome.await()[shared.canonicalPath],
            )
            assertTrue("project-referenced source must survive the release", shared.exists())
            AttachmentStorageManager.unregisterPendingAttachment(shared.canonicalPath)
            root.deleteRecursively()
        }

    // ---- P2.2: release begins first ----------------------------------------

    @Test
    fun `release that proves orphanhood wins the gate and a later promotion revalidates instead of dangling`() =
        runTest {
            val root = freshRoot("release_first")
            val orphan = File(root, "F.txt").apply { writeText("genuinely orphaned") }
            AttachmentStorageManager.registerPendingAttachment(orphan)
            val releaseInsideDecision = CompletableDeferred<Unit>()
            val letDecisionProceed = CompletableDeferred<Unit>()

            val release: Job = launch {
                AttachmentStorageManager.releaseRemovedDraftAttachmentsUnderMutationGate(
                    attachmentRootProvider = { root },
                    paths = listOf(orphan.canonicalPath),
                    messageAuthority = { complete() },
                    projectPaths = { emptySet() },
                    durableDraftPaths = {
                        // Deterministic pause: the release owns the gate and
                        // its destructive decision is in flight.
                        releaseInsideDecision.complete(Unit)
                        letDecisionProceed.await()
                        emptySet()
                    },
                )
            }
            advanceUntilIdle()
            assertTrue(releaseInsideDecision.isCompleted)

            // A promotion starting now must wait for the mutation gate, never
            // racing the mid-decision release.
            val projectRows = mutableSetOf<String>()
            var danglingRowInserted = false
            val promotion: Job = launch {
                AttachmentReferenceMutationCoordinator.withMutation {
                    // Production admission revalidates the physical source
                    // (classifyAttachment + readable extraction) before any
                    // ProjectFileEntity insert.
                    if (orphan.isFile) {
                        projectRows += orphan.canonicalPath
                        danglingRowInserted = true
                    }
                }
            }
            runCurrent()
            assertFalse("promotion must wait for the mutation gate", danglingRowInserted)
            assertTrue(orphan.exists())

            letDecisionProceed.complete(Unit)
            advanceUntilIdle()
            release.join()
            promotion.join()

            assertFalse(orphan.exists())
            assertTrue(projectRows.isEmpty())
            assertFalse("no dangling ProjectFileEntity may be inserted", danglingRowInserted)
            root.deleteRecursively()
        }

    // ---- P2.3: global cleanup cannot observe a fake gap during submission ---

    @Test
    fun `cleanup blocked during a submission transfer preserves the committed message reference`() =
        runTest {
            val root = freshRoot("submission_transfer")
            val shared = File(root, "F.txt").apply { writeText("draft attachment") }
            AttachmentStorageManager.registerPendingAttachment(shared)
            val messagePaths = mutableSetOf<String>()
            val draftPaths = mutableSetOf(shared.canonicalPath)
            val insertStarted = CompletableDeferred<Unit>()
            val letInsertProceed = CompletableDeferred<Unit>()

            val submission: Job = launch {
                commitAndSettleUserSubmission(
                    state = SubmissionCommitState(conversationId = 7L),
                    parentJob = null,
                    runInTransaction = { block -> block() },
                    createConversation = { 7L },
                    insertUserMessage = { conversationId ->
                        insertStarted.complete(Unit)
                        letInsertProceed.await()
                        // Room user-message commit stores F's trusted envelope:
                        messagePaths += shared.canonicalPath
                    },
                    publishConversation = {},
                    reconcileCommittedDraft = {
                        // Durable draft reconciliation removes F...
                        draftPaths.clear()
                        // ...and pending ownership settles after the commit.
                        AttachmentStorageManager.unregisterPendingAttachment(shared.canonicalPath)
                        true
                    },
                    updateConversationTimestamp = {},
                )
            }
            advanceUntilIdle()
            assertTrue(insertStarted.isCompleted)

            var cleanupDeleted = -1
            val cleanup: Job = launch {
                cleanupDeleted = AttachmentStorageManager.cleanupUnreferencedAttachmentsUnderMutationGate(
                    attachmentDir = root,
                    listFiles = { root.listFiles()?.filter { it.isFile }.orEmpty() },
                    messageAuthority = { complete(messagePaths) },
                    projectPaths = { emptySet() },
                    durableDraftPaths = { draftPaths },
                    pendingPaths = { AttachmentStorageManager.pendingAttachmentPathsSnapshot() },
                    // Deterministically older than the grace period.
                    now = { System.currentTimeMillis() + 3_600_000L },
                )
            }
            runCurrent()
            // Cleanup is deterministically blocked while the submission
            // owns the gate: it cannot read "messages without F" and later
            // "pending without F" as deletion permission.
            assertEquals(-1, cleanupDeleted)
            assertTrue(shared.exists())

            letInsertProceed.complete(Unit)
            advanceUntilIdle()
            submission.join()
            cleanup.join()

            assertEquals("cleanup must preserve the committed attachment", 0, cleanupDeleted)
            assertTrue("the committed message never references a deleted file", shared.exists())
            assertTrue(shared.canonicalPath in messagePaths)
            root.deleteRecursively()
        }

    @Test
    fun `cleanup that begins first preserves the still-owned draft attachment through a later transfer`() =
        runTest {
            val root = freshRoot("cleanup_first")
            val shared = File(root, "F.txt").apply { writeText("draft attachment") }
            AttachmentStorageManager.registerPendingAttachment(shared)
            val messagePaths = mutableSetOf<String>()
            val draftPaths = mutableSetOf(shared.canonicalPath)
            val cleanupScanStarted = CompletableDeferred<Unit>()
            val letScanProceed = CompletableDeferred<Unit>()

            var cleanupDeleted = -1
            val cleanup: Job = launch {
                cleanupDeleted = AttachmentStorageManager.cleanupUnreferencedAttachmentsUnderMutationGate(
                    attachmentDir = root,
                    listFiles = { listOf(shared) },
                    messageAuthority = { complete(messagePaths) },
                    projectPaths = { emptySet() },
                    durableDraftPaths = {
                        // Deterministic pause: cleanup owns the gate and is
                        // mid authority scan.
                        cleanupScanStarted.complete(Unit)
                        letScanProceed.await()
                        draftPaths
                    },
                    pendingPaths = { AttachmentStorageManager.pendingAttachmentPathsSnapshot() },
                    now = { System.currentTimeMillis() + 3_600_000L },
                )
            }
            advanceUntilIdle()
            assertTrue(cleanupScanStarted.isCompleted)

            // A submission transfer starting now must wait: cleanup's
            // authority snapshot cannot be assembled across the transfer.
            val submission: Job = launch {
                commitAndSettleUserSubmission(
                    state = SubmissionCommitState(conversationId = 9L),
                    parentJob = null,
                    runInTransaction = { block -> block() },
                    createConversation = { 9L },
                    insertUserMessage = { messagePaths += shared.canonicalPath },
                    publishConversation = {},
                    reconcileCommittedDraft = {
                        draftPaths.clear()
                        AttachmentStorageManager.unregisterPendingAttachment(shared.canonicalPath)
                        true
                    },
                    updateConversationTimestamp = {},
                )
            }
            runCurrent()
            assertFalse(submission.isCompleted)

            letScanProceed.complete(Unit)
            advanceUntilIdle()
            cleanup.join()
            submission.join()

            assertEquals("cleanup saw the draft/pending owner and preserved F", 0, cleanupDeleted)
            assertTrue(shared.exists())
            assertTrue(shared.canonicalPath in messagePaths)
            root.deleteRecursively()
        }

    // ---- P2.5: cancellation releases the gate --------------------------------

    @Test
    fun `cancellation during a serialized reference mutation releases the gate`() = runTest {
        val never = CompletableDeferred<Unit>()
        val mutationEntered = CompletableDeferred<Unit>()
        val cancelledMutation: Job = launch {
            AttachmentReferenceMutationCoordinator.withMutation {
                mutationEntered.complete(Unit)
                never.await()
            }
        }
        advanceUntilIdle()
        assertTrue(mutationEntered.isCompleted)

        cancelledMutation.cancel()
        advanceUntilIdle()
        assertTrue(cancelledMutation.isCompleted)

        // The gate must be free: a later attachment mutation completes.
        val laterMutationCompleted = CompletableDeferred<Unit>()
        launch {
            AttachmentReferenceMutationCoordinator.withMutation { laterMutationCompleted.complete(Unit) }
        }
        advanceUntilIdle()
        assertTrue(laterMutationCompleted.isCompleted)
    }

    @Test
    fun `failure inside a serialized reference mutation releases the gate`() = runTest {
        val failing: Job = launch {
            try {
                AttachmentReferenceMutationCoordinator.withMutation { error("boom") }
            } catch (_: IllegalStateException) {
                // expected: the failure must not poison the gate
            }
        }
        advanceUntilIdle()
        assertTrue(failing.isCompleted)

        val laterMutationCompleted = CompletableDeferred<Unit>()
        launch {
            AttachmentReferenceMutationCoordinator.withMutation { laterMutationCompleted.complete(Unit) }
        }
        advanceUntilIdle()
        assertTrue(laterMutationCompleted.isCompleted)
    }

    // ---- P2.6: composed flows do not deadlock --------------------------------

    @Test
    fun `sequential release cleanup and promotion style mutations all complete without deadlock`() =
        runTest {
            val root = freshRoot("composed")
            val orphan = File(root, "a.txt").apply { writeText("a") }
            AttachmentStorageManager.registerPendingAttachment(orphan)
            val projectRows = mutableSetOf<String>()
            val allDone = CompletableDeferred<Unit>()

            launch {
                // Conversation-deletion style composition: a targeted release
                // followed by the global orphan sweep, then a promotion-style
                // insert. Sequential gate acquisitions, never nested.
                AttachmentStorageManager.releaseRemovedDraftAttachmentsUnderMutationGate(
                    attachmentRootProvider = { root },
                    paths = listOf(orphan.canonicalPath),
                    messageAuthority = { complete() },
                    projectPaths = { projectRows },
                    durableDraftPaths = { emptySet() },
                )
                AttachmentStorageManager.cleanupUnreferencedAttachmentsUnderMutationGate(
                    attachmentDir = root,
                    listFiles = { root.listFiles()?.filter { it.isFile }.orEmpty() },
                    messageAuthority = { complete() },
                    projectPaths = { projectRows },
                    durableDraftPaths = { emptySet() },
                    pendingPaths = { AttachmentStorageManager.pendingAttachmentPathsSnapshot() },
                    now = { System.currentTimeMillis() + 3_600_000L },
                )
                AttachmentReferenceMutationCoordinator.withMutation {
                    projectRows += root.canonicalPath
                }
                allDone.complete(Unit)
            }
            advanceUntilIdle()
            assertTrue(allDone.isCompleted)
            assertFalse(orphan.exists())
            root.deleteRecursively()
        }

    @Test
    fun `concurrent mutations are strictly serialized`() = runTest {
        var inside = 0
        var maxInside = 0
        val jobs = (0 until 8).map {
            launch {
                AttachmentReferenceMutationCoordinator.withMutation {
                    inside += 1
                    maxInside = maxOf(maxInside, inside)
                    // A suspension point inside the critical section: without
                    // the gate, concurrent mutations would interleave here.
                    kotlinx.coroutines.yield()
                    inside -= 1
                }
            }
        }
        advanceUntilIdle()
        jobs.forEach { it.join() }
        assertEquals("mutations must never overlap", 1, maxInside)
    }
}
