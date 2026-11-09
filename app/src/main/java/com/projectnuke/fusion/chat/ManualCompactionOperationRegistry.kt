package com.projectnuke.fusion.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Process-local ownership for operations which can mutate one conversation's
 * derived context state. The registry stores only active leases; it is not a
 * background worker or a durable operation queue.
 */
internal object ManualCompactionOperationRegistry {
    private val lock = Any()
    private val entries = mutableMapOf<Long, Entry>()

    internal class Entry(val conversationId: Long) {
        var manual: ManualLease? = null
        val submissions = mutableSetOf<String>()
        var deletionRequested: Boolean = false
        var activeDeletionBarriers: Int = 0
        val deletionSettled = CompletableDeferred<Unit>()
    }

    internal class ManualLease internal constructor(
        internal val entry: Entry,
    ) {
        private var attachedJob: Job? = null
        private var cancelRequested = false
        private var released = false

        internal fun attach(job: Job) {
            val cancel = synchronized(lock) {
                attachedJob = job
                cancelRequested
            }
            if (cancel) job.cancel()
        }

        internal fun requestCancellation(): Job? = synchronized(lock) {
            cancelRequested = true
            attachedJob
        }

        internal fun release() {
            synchronized(lock) {
                if (released) return
                released = true
                if (entry.manual === this) {
                    entry.manual = null
                    if (!entry.deletionRequested) {
                        entries.remove(entry.conversationId)
                    }
                }
                maybeCompleteDeletionSettlement(entry)
                retireIfDeletionsSettled(entry)
            }
        }
    }

    internal class DeletionBarrier internal constructor(
        private val entry: Entry,
    ) {
        private var finished = false

        suspend fun cancelAndJoin() {
            val job = synchronized(lock) { entry.manual?.requestCancellation() }
            job?.cancel()
            withContext(NonCancellable) {
                entry.deletionSettled.await()
            }
        }

        internal fun finish() {
            synchronized(lock) {
                if (finished) return
                finished = true
                if (entry.activeDeletionBarriers > 0) entry.activeDeletionBarriers--
                retireIfDeletionsSettled(entry)
            }
        }
    }

    internal enum class SubmissionRegistryRejection {
        MANUAL_COMPACTION_LEASE_HELD,
        EXISTING_SUBMISSION_OWNER,
        CONVERSATION_DELETION_IN_PROGRESS,
    }

    internal data class SubmissionAcquireResult(
        val acquired: Boolean,
        val rejection: SubmissionRegistryRejection? = null,
    )

    internal fun tryAcquire(conversationId: Long): ManualLease? = synchronized(lock) {
        val entry = entries.getOrPut(conversationId) { Entry(conversationId) }
        if (entry.deletionRequested || entry.manual != null || entry.submissions.isNotEmpty()) {
            return@synchronized null
        }
        ManualLease(entry).also { entry.manual = it }
    }

    internal fun beginDeletion(conversationId: Long): DeletionBarrier = synchronized(lock) {
        val entry = entries.getOrPut(conversationId) { Entry(conversationId) }
        entry.deletionRequested = true
        entry.activeDeletionBarriers++
        maybeCompleteDeletionSettlement(entry)
        DeletionBarrier(entry)
    }

    internal fun tryAcquireSubmission(conversationId: Long, token: String): Boolean =
        tryAcquireSubmissionDetailed(conversationId, token).acquired

    internal fun tryAcquireSubmissionDetailed(
        conversationId: Long,
        token: String,
    ): SubmissionAcquireResult = synchronized(lock) {
            val entry = entries.getOrPut(conversationId) { Entry(conversationId) }
            if (entry.deletionRequested) {
                return@synchronized SubmissionAcquireResult(
                    acquired = false,
                    rejection = SubmissionRegistryRejection.CONVERSATION_DELETION_IN_PROGRESS,
                )
            }
            if (entry.manual != null) {
                return@synchronized SubmissionAcquireResult(
                    acquired = false,
                    rejection = SubmissionRegistryRejection.MANUAL_COMPACTION_LEASE_HELD,
                )
            }
            if (entry.submissions.isNotEmpty()) {
                return@synchronized SubmissionAcquireResult(
                    acquired = false,
                    rejection = SubmissionRegistryRejection.EXISTING_SUBMISSION_OWNER,
                )
            }
            entry.submissions.add(token)
            SubmissionAcquireResult(acquired = true)
        }

    internal fun releaseSubmission(conversationId: Long, token: String) {
        synchronized(lock) {
            val entry = entries[conversationId] ?: return
            if (!entry.submissions.remove(token)) return
            maybeCompleteDeletionSettlement(entry)
            retireIfDeletionsSettled(entry)
        }
    }

    /**
     * Read-only liveness probe for one submission token.
     *
     * Admission acquires the process lease *before* the durable draft token is
     * published and every settlement path clears the durable token *before*
     * releasing the lease, so a held lease for `(conversationId, token)` is
     * authoritative evidence that the token belongs to a live operation of
     * this process and must never be treated as an abandoned previous-process
     * owner. A stranded token whose admission was rolled back (P16-01) or a
     * previous-process token holds no lease and stays recoverable.
     */
    internal fun holdsSubmission(conversationId: Long, token: String): Boolean = synchronized(lock) {
        entries[conversationId]?.submissions?.contains(token) == true
    }

    /** Completes deletion only after every pre-existing context owner settles. */
    private fun maybeCompleteDeletionSettlement(entry: Entry) {
        if (entry.deletionRequested &&
            entry.manual == null &&
            entry.submissions.isEmpty()
        ) {
            entry.deletionSettled.complete(Unit)
        }
    }

    /** Read-only test seam; production code must never reset process ownership. */
    internal fun residualOwnerCountForTest(): Int = synchronized(lock) {
        entries.values.sumOf { entry ->
            (if (entry.manual != null) 1 else 0) +
                entry.submissions.size +
                entry.activeDeletionBarriers
        }
    }

    private fun retireIfDeletionsSettled(entry: Entry) {
        if (entry.deletionRequested &&
            entry.activeDeletionBarriers == 0 &&
            entry.manual == null &&
            entry.submissions.isEmpty()
        ) {
            entries.remove(entry.conversationId, entry)
        }
    }

    internal fun release(lease: ManualLease) {
        lease.release()
    }
}
