package com.projectnuke.fusion.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide serialization of managed-attachment durable reference
 * mutation against destructive physical cleanup.
 *
 * Both sides of an ownership transition participate:
 *  - creation/transfer: project promotion of an existing managed file,
 *    project-file import (durable ProjectFileEntity + pending ownership
 *    settlement), and user-message submission (draft/pending ownership ->
 *    committed user-message attachment reference + draft reconciliation).
 *  - destruction: targeted removed-draft release and the global
 *    unreferenced-attachment cleanup scan + physical delete sweep.
 *
 * The transition
 *
 *     no durable reference <-> durable reference <-> physical deletion
 *
 * is linearizable with respect to managed attachment files: a destructive
 * delete can never run concurrently with (or be sampled across) a durable
 * reference creation/transfer for the same attachment ownership domain, so
 * no cross-time authority sampling can manufacture a fake orphan.
 *
 * The gate is a coroutine Mutex (never a Java monitor across suspension),
 * released on cancellation and on failure, and intentionally coarse —
 * attachment reference mutation is low frequency and correctness is more
 * important than attachment-management parallelism. It is never held for
 * LLM generation; only the short durable ownership transfer is.
 */
internal object AttachmentReferenceMutationCoordinator {
    private val mutationMutex = Mutex()

    suspend fun <T> withMutation(block: suspend () -> T): T =
        mutationMutex.withLock { block() }
}
