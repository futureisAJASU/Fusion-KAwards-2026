package com.projectnuke.fusion.inbox

import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.InboxInsertResult
import com.projectnuke.fusion.data.InboxItemEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

sealed interface InboxWriteResult {
    data class Stored(val id: String) : InboxWriteResult
    data object Invalid : InboxWriteResult
    data object SensitiveContent : InboxWriteResult
    data object CapacityReached : InboxWriteResult
    data object Failed : InboxWriteResult
}

class InboxRepository(private val database: AppDatabase) {
    fun observeLatest(limit: Int = MAX_INBOX_VISIBLE_ITEMS): Flow<List<InboxItemEntity>> =
        database.inboxItemDao().observeLatest(limit.coerceIn(1, MAX_INBOX_VISIBLE_ITEMS))

    suspend fun capture(
        capture: InboxCapture,
        now: Long = System.currentTimeMillis(),
        id: String = java.util.UUID.randomUUID().toString(),
    ): InboxWriteResult =
        withContext(Dispatchers.IO) {
            when (val prepared = InboxCapturePolicy.prepare(capture, now, id)) {
                InboxPreparation.Invalid -> InboxWriteResult.Invalid
                InboxPreparation.SensitiveContent -> InboxWriteResult.SensitiveContent
                is InboxPreparation.Ready -> try {
                    when (database.inboxItemDao().insertOrConfirmBounded(prepared.item)) {
                        InboxInsertResult.Inserted -> InboxWriteResult.Stored(prepared.item.id)
                        InboxInsertResult.AlreadyStored -> InboxWriteResult.Stored(prepared.item.id)
                        InboxInsertResult.Conflict -> InboxWriteResult.Failed
                        InboxInsertResult.CapacityReached -> InboxWriteResult.CapacityReached
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    InboxWriteResult.Failed
                }
            }
        }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            database.inboxItemDao().delete(id) == 1
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun get(id: String): InboxItemEntity? = withContext(Dispatchers.IO) {
        database.inboxItemDao().get(id)
    }
}

const val MAX_INBOX_VISIBLE_ITEMS = 100
