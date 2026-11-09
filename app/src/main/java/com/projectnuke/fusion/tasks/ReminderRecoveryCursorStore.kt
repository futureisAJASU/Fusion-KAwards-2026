package com.projectnuke.fusion.tasks

import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.data.ReminderRecoveryCursorEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Chooses and checkpoints one stable keyset page before processing it. A failed
 * record therefore cannot pin every later invocation to the same first page.
 * If the process stops after the checkpoint, the skipped page is reached again
 * after the cursor wraps at the end of the queue.
 *
 * When the suffix after the persisted cursor is empty but the cursor is not at
 * the beginning, the store wraps to the beginning and returns one bounded
 * prefix page within the same call, so pending debt before the saved cursor is
 * not deferred to a later invocation solely because the first suffix query was
 * empty. An empty return therefore means both the suffix and one bounded
 * prefix query were empty. Callers still bound the number of pages per
 * invocation, so a permanently failing record is re-attempted at most that
 * bounded number of times per invocation and never loops forever.
 */
internal class ReminderRecoveryCursorStore(
    private val readCursor: suspend (String) -> String?,
    private val writeCursor: suspend (ReminderRecoveryCursorEntity) -> Unit,
) {
    constructor(database: AppDatabase) : this(
        readCursor = database.reminderRecoveryCursorDao()::getCursor,
        writeCursor = database.reminderRecoveryCursorDao()::put,
    )

    suspend fun <T> nextLongPage(
        key: String,
        limit: Int,
        load: suspend (afterId: Long, limit: Int) -> List<T>,
        idOf: (T) -> Long,
    ): List<T> = cursorMutex.withLock {
        val afterId = readCursor(key)?.toLongOrNull()?.takeIf { it >= 0L } ?: 0L
        val page = load(afterId, limit)
        if (page.isNotEmpty()) {
            writeCursor(ReminderRecoveryCursorEntity(key, idOf(page.last()).toString()))
            return@withLock page
        }
        if (afterId == 0L) return@withLock emptyList()
        // Suffix exhausted while the cursor was parked past pending prefix debt:
        // wrap to the beginning and serve one bounded prefix page now instead of
        // forcing the caller to return without processing reachable debt.
        // The wrap checkpoint (0) never claims completion; skipped work stays
        // reachable because the durable pending rows are untouched.
        writeCursor(ReminderRecoveryCursorEntity(key, "0"))
        val wrapped = load(0L, limit)
        if (wrapped.isEmpty()) return@withLock emptyList()
        writeCursor(ReminderRecoveryCursorEntity(key, idOf(wrapped.last()).toString()))
        wrapped
    }

    suspend fun <T> nextStringPage(
        key: String,
        limit: Int,
        load: suspend (afterId: String, limit: Int) -> List<T>,
        idOf: (T) -> String,
    ): List<T> = cursorMutex.withLock {
        val afterId = readCursor(key)?.takeIf { it.length <= MAX_CURSOR_LENGTH } ?: ""
        val page = load(afterId, limit)
        if (page.isNotEmpty()) {
            writeCursor(ReminderRecoveryCursorEntity(key, idOf(page.last())))
            return@withLock page
        }
        if (afterId.isEmpty()) return@withLock emptyList()
        // Same wrap-and-serve contract as the long-key variant.
        writeCursor(ReminderRecoveryCursorEntity(key, ""))
        val wrapped = load("", limit)
        if (wrapped.isEmpty()) return@withLock emptyList()
        writeCursor(ReminderRecoveryCursorEntity(key, idOf(wrapped.last())))
        wrapped
    }

    private companion object {
        const val MAX_CURSOR_LENGTH = 80
        val cursorMutex = Mutex()
    }
}

internal object ReminderRecoveryCursorKeys {
    const val PROJECT_DELETION = "project-deletion"
    const val TASK_DELETION = "task-deletion"
    const val CARD_DELETION = "card-deletion"
    const val TASK_NOTIFICATION_CLEANUP = "task-notification-cleanup"
    const val CARD_NOTIFICATION_CLEANUP = "card-notification-cleanup"
}
