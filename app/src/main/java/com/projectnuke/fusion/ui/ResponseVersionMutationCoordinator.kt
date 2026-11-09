package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ConversationStructuralMutationRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Typed rejection when deletion already owns the conversation lifetime. */
internal class ResponseVersionMutationRejectedException : Exception(
    "conversation is deleting",
)

/**
 * Serializes complete response-version read/modify/write operations per
 * conversation. The structural lease is intentionally acquired first so
 * deletion can never commit around a mutation which already loaded state.
 */
internal object ResponseVersionMutationCoordinator {
    private val entriesLock = Any()
    private val entries = mutableMapOf<Long, Entry>()

    private class Entry {
        val mutex = Mutex()
        var users: Int = 0
    }

    internal suspend fun <T> withMutation(
        conversationId: Long,
        block: suspend () -> T,
    ): T {
        require(conversationId > 0L)
        val structuralLease = ConversationStructuralMutationRegistry.tryAcquire(conversationId)
            ?: throw ResponseVersionMutationRejectedException()
        return try {
            // Lock ordering: structural lifetime lease -> response mutex.
            withVersionLockUnderLease(structuralLease, block)
        } finally {
            structuralLease.release()
        }
    }

    /** Runs a short mutation under a structural lease already owned by the caller. */
    internal suspend fun <T> withVersionLockUnderLease(
        structuralLease: ConversationStructuralMutationRegistry.Lease,
        block: suspend () -> T,
    ): T {
        val conversationId = structuralLease.conversationId
        val entry = synchronized(entriesLock) {
            entries.getOrPut(conversationId) { Entry() }.also { it.users++ }
        }
        return try {
            // Lock ordering: structural lifetime lease -> response mutex.
            entry.mutex.withLock { block() }
        } finally {
            synchronized(entriesLock) {
                entry.users--
                if (entry.users == 0) entries.remove(conversationId, entry)
            }
        }
    }
}
