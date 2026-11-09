package com.projectnuke.fusion.chat

import kotlinx.coroutines.CompletableDeferred

/**
 * Process-local lifetime fencing for short mutations which must not outlive
 * their conversation. This is deliberately separate from generation and
 * submission ownership and contains no durable state.
 */
internal object ConversationStructuralMutationRegistry {
    private val lock = Any()
    private val entries = mutableMapOf<Long, Entry>()

    internal class Entry(val conversationId: Long) {
        var activeLeases: Int = 0
        var deletionRequested: Boolean = false
        var activeDeletionBarriers: Int = 0
        val quiescent = CompletableDeferred<Unit>()
    }

    internal class Lease internal constructor(
        private val entry: Entry,
    ) {
        private var released = false

        internal val conversationId: Long
            get() = entry.conversationId

        internal fun release() {
            synchronized(lock) {
                if (released) return
                released = true
                if (entry.activeLeases > 0) entry.activeLeases--
                if (entry.deletionRequested && entry.activeLeases == 0) {
                    entry.quiescent.complete(Unit)
                }
                if (entry.activeLeases == 0 &&
                    (!entry.deletionRequested || entry.activeDeletionBarriers == 0)
                ) {
                    entries.remove(entry.conversationId, entry)
                }
            }
        }
    }

    internal class DeletionBarrier internal constructor(
        private val entry: Entry,
    ) {
        private var finished = false

        /** Cancellable wait for leases which predated deletion. */
        internal suspend fun awaitQuiescent() {
            entry.quiescent.await()
        }

        /**
         * Ends the fence after the deletion worker has terminally settled.
         * If called early, the entry remains fenced until its last lease is
         * released; this keeps an accidental early finish fail-closed.
         */
        internal fun finish() {
            synchronized(lock) {
                if (finished) return
                finished = true
                if (entry.activeDeletionBarriers > 0) entry.activeDeletionBarriers--
                if (entry.activeLeases == 0 && entry.activeDeletionBarriers == 0) {
                    entries.remove(entry.conversationId, entry)
                }
            }
        }
    }

    /** Acquires a short lifetime lease, or null after deletion was requested. */
    internal fun tryAcquire(conversationId: Long): Lease? = synchronized(lock) {
        val entry = entries.getOrPut(conversationId) { Entry(conversationId) }
        if (entry.deletionRequested) return@synchronized null
        entry.activeLeases++
        Lease(entry)
    }

    /** Requests deletion and returns a barrier for pre-existing leases. */
    internal fun beginDeletion(conversationId: Long): DeletionBarrier = synchronized(lock) {
        val entry = entries.getOrPut(conversationId) { Entry(conversationId) }
        entry.deletionRequested = true
        entry.activeDeletionBarriers++
        if (entry.activeLeases == 0) entry.quiescent.complete(Unit)
        DeletionBarrier(entry)
    }

    /** Read-only test seam; production code must never reset process ownership. */
    internal fun residualOwnerCountForTest(): Int = synchronized(lock) {
        entries.values.sumOf { entry ->
            entry.activeLeases + entry.activeDeletionBarriers
        }
    }
}
