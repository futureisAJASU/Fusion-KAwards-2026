package com.projectnuke.fusion.tasks

import com.projectnuke.fusion.data.ReminderRecoveryCursorEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P10-01 RED reproduction: persisted cursor at the end of the queue must not
 * defer reachable pending debt to a later invocation.
 */
class ReminderRecoveryCursorP10RedTest {
    @Test
    fun `cursor at last pending id still processes that debt in the same invocation`() = runBlocking {
        val cursors = mutableMapOf("task-deletion" to "100")
        val store = ReminderRecoveryCursorStore(
            readCursor = { key -> cursors[key] },
            writeCursor = { cursor: ReminderRecoveryCursorEntity ->
                cursors[cursor.recoveryKey] = cursor.cursor
            },
        )
        val rows = mutableListOf(100L)
        val processed = mutableListOf<Long>()

        // Simulate one recovery invocation: keep paging until the store
        // reports an empty page, exactly like the production coordinator.
        // Successful processing clears the durable debt, as finishDeletion /
        // clearNotificationCleanupPending do in production.
        var pages = 0
        while (pages < 16) {
            val page = store.nextLongPage(
                key = "task-deletion",
                limit = 128,
                load = { afterId, limit -> rows.filter { it > afterId }.take(limit) },
                idOf = { it },
            )
            if (page.isEmpty()) break
            pages++
            processed += page
            rows.removeAll(page.toSet())
        }

        assertEquals(listOf(100L), processed)
    }

    @Test
    fun `string cursor at last pending id still processes that debt in the same invocation`() = runBlocking {
        val cursors = mutableMapOf("card-deletion" to "card-099")
        val store = ReminderRecoveryCursorStore(
            readCursor = { key -> cursors[key] },
            writeCursor = { cursor: ReminderRecoveryCursorEntity ->
                cursors[cursor.recoveryKey] = cursor.cursor
            },
        )
        val rows = mutableListOf("card-099")
        val processed = mutableListOf<String>()

        var pages = 0
        while (pages < 16) {
            val page = store.nextStringPage(
                key = "card-deletion",
                limit = 128,
                load = { afterId, limit -> rows.filter { it > afterId }.take(limit) },
                idOf = { it },
            )
            if (page.isEmpty()) break
            pages++
            processed += page
            rows.removeAll(page.toSet())
        }

        assertEquals(listOf("card-099"), processed)
    }
}
