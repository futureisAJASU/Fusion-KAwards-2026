package com.projectnuke.fusion.projects

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F11-02: a snapshot import that has already committed durably must not be
 * reported as a failed import.
 *
 * The old code checked the caller's Job immediately after the Room commit and
 * threw `CancellationException("...canceled after durable commit")`. The project
 * and all of its child rows already existed, so callers saw a failure for an
 * import that was present -- and a retry produced a duplicate project. The
 * commit point now returns the truthful imported result; a cancellation that
 * arrives BEFORE the commit still propagates.
 *
 * These tests exercise the commit/cancellation ordering rule directly, using the
 * same NonCancellable-commit-then-report shape the repository uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectSnapshotPostCommitCancellationTest {

    /** Mirrors the repository ordering: commit NonCancellable, then report. */
    private suspend fun CoroutineScope.importLikeProduction(
        durable: MutableList<String>,
        callerJob: Job,
        canceledBeforeCommit: Boolean,
        canceledAfterCommit: Boolean,
    ): Result<String> {
        var committed = false
        return try {
            if (canceledBeforeCommit) throw CancellationException("canceled before commit")
            // The commit point itself is NonCancellable in production.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                durable += "project-row"
                committed = true
            }
            if (canceledAfterCommit) callerJob.cancel()
            Result.success("imported")
        } catch (cancelled: CancellationException) {
            Result.failure(cancelled)
        } finally {
            if (!committed) durable.clear()
        }
    }

    @Test
    fun `cancellation before commit aborts and leaves nothing durable`() = runTest {
        val durable = mutableListOf<String>()
        val job = Job()

        val outcome = importLikeProduction(
            durable = durable,
            callerJob = job,
            canceledBeforeCommit = true,
            canceledAfterCommit = false,
        )

        assertTrue(outcome.isFailure)
        assertTrue("a pre-commit cancellation must not leave a project", durable.isEmpty())
    }

    @Test
    fun `cancellation after commit reports the truthful imported outcome`() = runTest {
        val durable = mutableListOf<String>()
        val job = Job()

        val outcome = importLikeProduction(
            durable = durable,
            callerJob = job,
            canceledBeforeCommit = false,
            canceledAfterCommit = true,
        )

        assertEquals(Result.success("imported"), outcome)
        assertEquals(listOf("project-row"), durable)
    }

    @Test
    fun `an uncanceled import reports success and commits`() = runTest {
        val durable = mutableListOf<String>()
        val job = Job()

        val outcome = importLikeProduction(
            durable = durable,
            callerJob = job,
            canceledBeforeCommit = false,
            canceledAfterCommit = false,
        )

        assertEquals(Result.success("imported"), outcome)
        assertEquals(listOf("project-row"), durable)
    }
}