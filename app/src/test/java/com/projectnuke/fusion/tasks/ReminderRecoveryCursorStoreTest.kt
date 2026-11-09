package com.projectnuke.fusion.tasks

import com.projectnuke.fusion.data.ReminderRecoveryCursorEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderRecoveryCursorStoreTest {
    @Test
    fun `persisted keyset cursor reaches later records despite a failing prefix`() = runBlocking {
        val cursors = mutableMapOf<String, String>()
        val store = store(cursors)
        val rows = (1L..5L).toList()
        val key = ReminderRecoveryCursorKeys.PROJECT_DELETION

        val firstBoundedPass = buildList {
            repeat(2) {
                addAll(
                    store.nextLongPage(
                        key = key,
                        limit = 2,
                        load = { afterId, limit -> rows.filter { it > afterId }.take(limit) },
                        idOf = { it },
                    ),
                )
            }
        }
        assertEquals(listOf(1L, 2L, 3L, 4L), firstBoundedPass)
        assertEquals("4", cursors[key])

        val nextInvocation = store.nextLongPage(
            key = key,
            limit = 2,
            load = { afterId, limit -> rows.filter { it > afterId }.take(limit) },
            idOf = { it },
        )
        assertEquals(listOf(5L), nextInvocation)
        assertEquals("5", cursors[key])
    }

    @Test
    fun `empty suffix wraps and serves earlier failed rows in the same call`() = runBlocking {
        val cursors = mutableMapOf("task-delete" to "7")
        val store = store(cursors)
        val rows = listOf(2L, 4L, 7L)

        val wrapped = store.nextLongPage(
            key = "task-delete",
            limit = 2,
            load = { afterId, limit -> rows.filter { it > afterId }.take(limit) },
            idOf = { it },
        )

        assertEquals(listOf(2L, 4L), wrapped)
        assertEquals("4", cursors["task-delete"])
    }

    private fun store(cursors: MutableMap<String, String>) = ReminderRecoveryCursorStore(
        readCursor = { key -> cursors[key] },
        writeCursor = { cursor: ReminderRecoveryCursorEntity -> cursors[cursor.recoveryKey] = cursor.cursor },
    )
}
