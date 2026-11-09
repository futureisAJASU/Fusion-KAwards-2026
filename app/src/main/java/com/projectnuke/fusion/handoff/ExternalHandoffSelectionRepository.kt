package com.projectnuke.fusion.handoff

import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ExternalHandoffReceiptEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface HandoffSelectionResult {
    data object Stored : HandoffSelectionResult
    data object Existing : HandoffSelectionResult
    data object Conflict : HandoffSelectionResult
    data object Invalid : HandoffSelectionResult
    data object Failed : HandoffSelectionResult
}

class ExternalHandoffSelectionRepository(private val database: AppDatabase) {
    suspend fun select(selection: ExternalHandoffSelection): HandoffSelectionResult = withContext(Dispatchers.IO) {
        if (selection.eventId.isBlank() || selection.eventId.length > 80 ||
            (selection.destination == ExternalHandoffDestination.PROJECT) != (selection.projectId != null) ||
            selection.projectId?.let { it <= 0L } == true
        ) return@withContext HandoffSelectionResult.Invalid
        try {
            val dao = database.externalHandoffReceiptDao()
            val existing = dao.get(selection.eventId)
            if (existing != null) {
                return@withContext if (existing.destination == selection.destination.name &&
                    existing.targetProjectId == selection.projectId
                ) HandoffSelectionResult.Existing else HandoffSelectionResult.Conflict
            }
            val receipt = ExternalHandoffReceiptEntity(
                eventId = selection.eventId,
                destination = selection.destination.name,
                targetProjectId = selection.projectId,
                selectedAt = System.currentTimeMillis(),
                settledAt = null,
                outcome = "SELECTED",
            )
            if (dao.insertIfAbsent(receipt) != -1L) {
                try {
                    dao.pruneSettled(MAX_SETTLED_RECEIPTS)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Selection persistence is authoritative; old receipts can be pruned later.
                }
                HandoffSelectionResult.Stored
            } else {
                val raced = dao.get(selection.eventId)
                if (raced?.destination == selection.destination.name && raced.targetProjectId == selection.projectId) {
                    HandoffSelectionResult.Existing
                } else HandoffSelectionResult.Conflict
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            HandoffSelectionResult.Failed
        }
    }

    suspend fun get(eventId: String): ExternalHandoffReceiptEntity? = withContext(Dispatchers.IO) {
        try { database.externalHandoffReceiptDao().get(eventId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    suspend fun settle(eventId: String, success: Boolean): Boolean = withContext(Dispatchers.IO) {
        settleOutcome(eventId, if (success) "IMPORTED" else "FAILED")
    }

    /** Persists the bounded outcome vocabulary so partial success is replayable. */
    suspend fun settle(eventId: String, outcome: String): Boolean = withContext(Dispatchers.IO) {
        val boundedOutcome = outcome.takeIf {
            it in setOf("IMPORTED", "PARTIALLY_IMPORTED", "FAILED", "REJECTED_TOO_LARGE", "FAILED_FRESH_CHAT")
        } ?: return@withContext false
        settleOutcome(eventId, boundedOutcome)
    }

    private suspend fun settleOutcome(eventId: String, outcome: String): Boolean {
        return try {
            database.externalHandoffReceiptDao().settle(
                eventId = eventId,
                settledAt = System.currentTimeMillis(),
                outcome = outcome,
            ) == 1
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val MAX_SETTLED_RECEIPTS = 256
    }
}
