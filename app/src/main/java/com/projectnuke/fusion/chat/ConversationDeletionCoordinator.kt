package com.projectnuke.fusion.chat

import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal enum class ConversationDeletionResult {
    DELETED_CLEAN,
    DELETED_CLEANUP_DEBT_RECORDED,
    DELETED_CLEANUP_DURABILITY_LOST,
    ALREADY_ABSENT_CLEAN,
    ALREADY_ABSENT_DEBT_RECORDED,
    ALREADY_ABSENT_DURABILITY_LOST,
    BUSY;
}

internal fun ConversationDeletionResult.isDeletedOrAbsent(): Boolean = when (this) {
    ConversationDeletionResult.DELETED_CLEAN,
    ConversationDeletionResult.DELETED_CLEANUP_DEBT_RECORDED,
    ConversationDeletionResult.DELETED_CLEANUP_DURABILITY_LOST,
    ConversationDeletionResult.ALREADY_ABSENT_CLEAN,
    ConversationDeletionResult.ALREADY_ABSENT_DEBT_RECORDED,
    ConversationDeletionResult.ALREADY_ABSENT_DURABILITY_LOST -> true
    ConversationDeletionResult.BUSY -> false
}

/**
 * Owns the ordering boundary for conversation deletion. Cleanup runs after the
 * database commit and therefore must never be allowed to resurrect the row.
 *
 * Durability: DB deletion is irreversible. If derived cleanup fails, debt
 * recording is attempted; if that also fails, DURABILITY_LOST is returned
 * (and logged) rather than claiming recoverability.
 */
internal class ConversationDeletionCoordinator {
    private val ownershipLock = Any()
    private val owners = mutableMapOf<Long, String>()

    suspend fun delete(
        conversationId: Long,
        cancelAndJoin: suspend () -> Unit,
        exists: suspend () -> Boolean,
        commitDelete: suspend () -> Unit,
        settleTarget: suspend () -> Unit,
        cleanupDerivedData: suspend () -> Unit,
        recordCleanupDebt: suspend () -> Unit,
        recordCleanupDebtResult: (suspend () -> Boolean)? = null,
    ): ConversationDeletionResult {
        require(conversationId > 0L)
        val owner = UUID.randomUUID().toString()
        synchronized(ownershipLock) {
            if (owners.putIfAbsent(conversationId, owner) != null) {
                return ConversationDeletionResult.BUSY
            }
        }

        suspend fun recordDebtDurably(): Boolean {
            // Prefer the Boolean result seam; fall back to the throwing seam.
            // Any failure (false or exception) means durability is lost and must
            // be reported, never swallowed as recoverable.
            return try {
                if (recordCleanupDebtResult != null) {
                    recordCleanupDebtResult.invoke()
                } else {
                    recordCleanupDebt()
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

        suspend fun completeCleanup(action: suspend () -> Unit): Boolean = try {
            action()
            true
        } catch (_: Exception) {
            // Ordinary cleanup failures retain the debt path. Fatal runtime
            // Errors are not recoverable cleanup evidence and must propagate.
            false
        }

        var committed = false
        return try {
            cancelAndJoin()
            if (!exists()) {
                withContext(NonCancellable) {
                    val settleOk = completeCleanup(settleTarget)
                    val cleanupOk = completeCleanup(cleanupDerivedData)
                    if (settleOk && cleanupOk) {
                        ConversationDeletionResult.ALREADY_ABSENT_CLEAN
                    } else {
                        if (recordDebtDurably()) {
                            ConversationDeletionResult.ALREADY_ABSENT_DEBT_RECORDED
                        } else {
                            Log.w("FusionRecovery", "Conversation $conversationId absent cleanup durability lost: debt not recorded")
                            ConversationDeletionResult.ALREADY_ABSENT_DURABILITY_LOST
                        }
                    }
                }
            } else {
                withContext(NonCancellable) {
                    commitDelete()
                    committed = true
                    val settleOk = completeCleanup(settleTarget)
                    val cleanupOk = completeCleanup(cleanupDerivedData)
                    if (settleOk && cleanupOk) {
                        ConversationDeletionResult.DELETED_CLEAN
                    } else {
                        if (recordDebtDurably()) {
                            ConversationDeletionResult.DELETED_CLEANUP_DEBT_RECORDED
                        } else {
                            Log.w("FusionRecovery", "Conversation $conversationId deletion durability lost: debt not recorded")
                            ConversationDeletionResult.DELETED_CLEANUP_DURABILITY_LOST
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (!committed) throw cancelled
            // Post-commit cancellation: cleanup state unknown, debt may not exist.
            Log.w("FusionRecovery", "Conversation $conversationId deletion cancelled after commit; durability unknown")
            ConversationDeletionResult.DELETED_CLEANUP_DURABILITY_LOST
        } finally {
            synchronized(ownershipLock) {
                if (owners[conversationId] == owner) owners.remove(conversationId)
            }
        }
    }
}
