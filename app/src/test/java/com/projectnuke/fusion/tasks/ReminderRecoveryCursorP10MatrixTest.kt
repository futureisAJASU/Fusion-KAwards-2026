package com.projectnuke.fusion.tasks

import com.projectnuke.fusion.data.ReminderRecoveryCursorEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P10 cursor-correctness matrix for all five recovery queues.
 *
 * Every test drives the production [ReminderRecoveryCursorStore] with a
 * controlled in-memory persistence map (the same seam the Room DAO backs in
 * production). Room-backed coordinator coverage lives in
 * `ReminderRecoveryFairnessAndroidTest` / `ReminderRecoveryCursorWrapAndroidTest`
 * (instrumentation; compiled, executed only when a target exists).
 */
class ReminderRecoveryCursorP10MatrixTest {

    private val longKeys = listOf(
        ReminderRecoveryCursorKeys.PROJECT_DELETION,
        ReminderRecoveryCursorKeys.TASK_DELETION,
        ReminderRecoveryCursorKeys.TASK_NOTIFICATION_CLEANUP,
    )
    private val stringKeys = listOf(
        ReminderRecoveryCursorKeys.CARD_DELETION,
        ReminderRecoveryCursorKeys.CARD_NOTIFICATION_CLEANUP,
    )

    private fun store(cursors: MutableMap<String, String>) = ReminderRecoveryCursorStore(
        readCursor = { key -> cursors[key] },
        writeCursor = { cursor: ReminderRecoveryCursorEntity ->
            cursors[cursor.recoveryKey] = cursor.cursor
        },
    )

    private fun longLoad(rows: List<Long>): suspend (Long, Int) -> List<Long> =
        { afterId, limit -> rows.filter { it > afterId }.take(limit) }

    private fun stringLoad(rows: List<String>): suspend (String, Int) -> List<String> =
        { afterId, limit -> rows.filter { it > afterId }.take(limit) }

    // ---- One recovery invocation helper (mirrors the production coordinator loop). ----

    private suspend fun runLongInvocation(
        store: ReminderRecoveryCursorStore,
        key: String,
        rows: () -> List<Long>,
        limit: Int,
        maxPages: Int,
        process: (Long) -> Boolean,
    ): List<Long> {
        val processed = mutableListOf<Long>()
        var pages = 0
        var queries = 0
        while (pages < maxPages) {
            queries++
            val page = store.nextLongPage(key, limit, { afterId, take -> rows().filter { it > afterId }.take(take) }, { it })
            if (page.isEmpty()) break
            pages++
            for (id in page) {
                if (process(id)) processed += id
            }
        }
        return processed
    }

    // ---- Matrix: cursor at 0, pending rows exist (all five queues). ----

    @Test
    fun `long queues process normally from cursor zero`() = runBlocking {
        for (key in longKeys) {
            val cursors = mutableMapOf<String, String>()
            val page = store(cursors).nextLongPage(key, 128, longLoad(listOf(1L, 2L, 3L)), { it })
            assertEquals("key=$key", listOf(1L, 2L, 3L), page)
            assertEquals("key=$key", "3", cursors[key])
        }
    }

    @Test
    fun `string queues process normally from empty cursor`() = runBlocking {
        for (key in stringKeys) {
            val cursors = mutableMapOf<String, String>()
            val page = store(cursors).nextStringPage(key, 128, stringLoad(listOf("a", "b")), { it })
            assertEquals("key=$key", listOf("a", "b"), page)
            assertEquals("key=$key", "b", cursors[key])
        }
    }

    // ---- Matrix: cursor below all pending rows. ----

    @Test
    fun `cursor below all pending rows makes normal forward progress`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "10")
        val page = store(cursors).nextLongPage(
            ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(100L, 200L)), { it },
        )
        assertEquals(listOf(100L, 200L), page)
        assertEquals("200", cursors[ReminderRecoveryCursorKeys.TASK_DELETION])
    }

    // ---- Matrix: cursor inside pending range. ----

    @Test
    fun `cursor inside pending range serves the correct suffix`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "2")
        val page = store(cursors).nextLongPage(
            ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(1L, 2L, 3L, 4L)), { it },
        )
        assertEquals(listOf(3L, 4L), page)
        assertEquals("4", cursors[ReminderRecoveryCursorKeys.TASK_DELETION])
    }

    @Test
    fun `string cursor inside pending range serves the correct suffix`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.CARD_DELETION to "b")
        val page = store(cursors).nextStringPage(
            ReminderRecoveryCursorKeys.CARD_DELETION, 128, stringLoad(listOf("a", "b", "c")), { it },
        )
        assertEquals(listOf("c"), page)
        assertEquals("c", cursors[ReminderRecoveryCursorKeys.CARD_DELETION])
    }

    // ---- Matrix: P10-01 core — cursor equals last pending ID (all five queues). ----

    @Test
    fun `long queues reach prefix debt in the same call when cursor equals last pending id`() = runBlocking {
        for (key in longKeys) {
            val cursors = mutableMapOf(key to "100")
            val processed = mutableListOf<Long>()
            var pages = 0
            while (pages < 16) {
                val page = store(cursors).nextLongPage(key, 128, longLoad(listOf(100L)), { it })
                if (page.isEmpty()) break
                pages++
                processed += page
                break // one wrapped page is enough to prove same-invocation reachability
            }
            assertEquals("key=$key", listOf(100L), processed)
        }
    }

    @Test
    fun `string queues reach prefix debt in the same call when cursor equals last pending id`() = runBlocking {
        for (key in stringKeys) {
            val cursors = mutableMapOf(key to "card-099")
            val page = store(cursors).nextStringPage(key, 128, stringLoad(listOf("card-099")), { it })
            assertEquals("key=$key", listOf("card-099"), page)
        }
    }

    // ---- Matrix: cursor greater than every pending ID. ----

    @Test
    fun `cursor beyond every pending row wraps and serves the prefix`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "9999")
        val page = store(cursors).nextLongPage(
            ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(3L, 9L)), { it },
        )
        assertEquals(listOf(3L, 9L), page)
        assertEquals("9", cursors[ReminderRecoveryCursorKeys.TASK_DELETION])
    }

    // ---- Matrix: cursor points to a deleted row. ----

    @Test
    fun `cursor at a deleted row does not skip later pending rows`() = runBlocking {
        // Row 5 was deleted; the cursor still points at it.
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "5")
        val page = store(cursors).nextLongPage(
            ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(6L, 7L)), { it },
        )
        assertEquals(listOf(6L, 7L), page)
    }

    // ---- Matrix: queue completely empty. ----

    @Test
    fun `empty queues terminate promptly for every queue family`() = runBlocking {
        for (key in longKeys) {
            val cursors = mutableMapOf<String, String>()
            val page = store(cursors).nextLongPage(key, 128, longLoad(emptyList()), { it })
            assertEquals("key=$key", emptyList<Long>(), page)
        }
        for (key in stringKeys) {
            val cursors = mutableMapOf<String, String>()
            val page = store(cursors).nextStringPage(key, 128, stringLoad(emptyList()), { it })
            assertEquals("key=$key", emptyList<String>(), page)
        }
        // A wrapped empty queue also terminates instead of looping.
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "42")
        val page = store(cursors).nextLongPage(
            ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(emptyList()), { it },
        )
        assertEquals(emptyList<Long>(), page)
        assertEquals("0", cursors[ReminderRecoveryCursorKeys.TASK_DELETION])
    }

    // ---- Matrix: one permanently failing record. ----

    @Test
    fun `single permanently failing record stays retryable and each call terminates`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
        val s = store(cursors)
        repeat(3) {
            val page = s.nextLongPage(
                ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(7L)), { it },
            )
            // Processing fails, so the row stays pending; the call still returns it
            // exactly once and then terminates (bounded, no infinite loop).
            assertEquals(listOf(7L), page)
        }
    }

    // ---- Matrix: failing prefix with later healthy rows (P9 fairness preserved). ----

    @Test
    fun `failing prefix cannot block later healthy rows across bounded passes`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
        val s = store(cursors)
        val pending = (1L..5L).toMutableList()
        val failing = setOf(1L, 2L)

        // Pass 1: checkpoint advances past the failing prefix.
        val first = s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(pending), { it })
        assertEquals(listOf(1L, 2L), first)
        // Both fail; nothing is removed.

        // Pass 2: later healthy rows are reached despite the failing prefix.
        val second = s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(pending), { it })
        assertEquals(listOf(3L, 4L), second)
        pending.removeAll { it == 3L || it == 4L }

        // Pass 3: the tail is reached.
        val third = s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(pending), { it })
        assertEquals(listOf(5L), third)
        pending.remove(5L)

        // Pass 4: suffix exhausted, so the same call wraps and re-serves the
        // still-failing prefix instead of deferring it.
        val fourth = s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(pending), { it })
        assertEquals(listOf(1L, 2L), fourth)
        assertTrue(failing.containsAll(fourth))
    }

    // ---- Matrix: one pending record only. ----

    @Test
    fun `single pending record is returned exactly once per call`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
        val page = store(cursors).nextLongPage(
            ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(9L)), { it },
        )
        assertEquals(listOf(9L), page)
        assertEquals(1, page.size)
    }

    // ---- Matrix: cursor persisted before interruption. ----

    @Test
    fun `interruption after checkpoint leaves debt reachable after wrap`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
        val s = store(cursors)
        // Checkpoint advances to 4, then the process "dies" before processing.
        val checkpointed = s.nextLongPage(
            ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(listOf(1L, 2L, 3L, 4L, 5L)), { it },
        )
        assertEquals(listOf(1L, 2L), checkpointed)
        assertEquals("2", cursors[ReminderRecoveryCursorKeys.TASK_DELETION])

        // A fresh store over the same durable map (simulated restart) continues
        // forward, and the skipped prefix is served again after wrap.
        val restarted = store(cursors)
        assertEquals(
            listOf(3L, 4L),
            restarted.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(listOf(1L, 2L, 3L, 4L, 5L)), { it }),
        )
        assertEquals(
            listOf(5L),
            restarted.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(listOf(1L, 2L, 3L, 4L, 5L)), { it }),
        )
        assertEquals(
            listOf(1L, 2L),
            restarted.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(listOf(1L, 2L, 3L, 4L, 5L)), { it }),
        )
    }

    // ---- Matrix: new rows arriving after the cursor. ----

    @Test
    fun `new low-id item added after cursor is reachable via wrap`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "50")
        // Only row 60 exists at first.
        assertEquals(
            listOf(60L),
            store(cursors).nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(60L)), { it }),
        )
        // A new low-ID row arrives later; the cursor sits at 60.
        val page = store(cursors).nextLongPage(
            ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(7L, 60L)), { it },
        )
        assertEquals(listOf(7L, 60L), page)
    }

    @Test
    fun `new high-id item keeps correct ordering after cursor`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "60")
        val page = store(cursors).nextLongPage(
            ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(60L, 61L)), { it },
        )
        assertEquals(listOf(61L), page)
    }

    // ---- Matrix: malformed / negative / max cursors. ----

    @Test
    fun `malformed or negative long cursor falls back safely to the beginning`() = runBlocking {
        for (bad in listOf("not-a-number", "-5", "")) {
            val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to bad)
            val page = store(cursors).nextLongPage(
                ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(1L, 2L)), { it },
            )
            assertEquals("bad=$bad", listOf(1L, 2L), page)
        }
    }

    @Test
    fun `cursor at max long key wraps without overflow`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to Long.MAX_VALUE.toString())
        val page = store(cursors).nextLongPage(
            ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(1L, 2L)), { it },
        )
        assertEquals(listOf(1L, 2L), page)
        assertEquals("2", cursors[ReminderRecoveryCursorKeys.TASK_DELETION])
    }

    @Test
    fun `overlong string cursor falls back to the beginning`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.CARD_DELETION to "z".repeat(81))
        val page = store(cursors).nextStringPage(
            ReminderRecoveryCursorKeys.CARD_DELETION, 128, stringLoad(listOf("a")), { it },
        )
        assertEquals(listOf("a"), page)
    }

    @Test
    fun `empty-string queue with max-length cursor still wraps`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.CARD_DELETION to "z".repeat(80))
        val page = store(cursors).nextStringPage(
            ReminderRecoveryCursorKeys.CARD_DELETION, 128, stringLoad(listOf("a", "m")), { it },
        )
        assertEquals(listOf("a", "m"), page)
    }

    // ---- Matrix: query failure must not claim completion. ----

    @Test
    fun `failing suffix query leaves the cursor untouched and propagates`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "10")
        try {
            store(cursors).nextLongPage<Long>(
                ReminderRecoveryCursorKeys.TASK_DELETION,
                128,
                load = { _, _ -> error("injected query failure") },
                idOf = { it },
            )
            fail("expected the query failure to propagate")
        } catch (failure: IllegalStateException) {
            assertEquals("injected query failure", failure.message)
        }
        assertEquals("10", cursors[ReminderRecoveryCursorKeys.TASK_DELETION])
    }

    @Test
    fun `failing wrap query keeps debt reachable and propagates`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "100")
        var calls = 0
        try {
            store(cursors).nextLongPage<Long>(
                ReminderRecoveryCursorKeys.TASK_DELETION,
                128,
                load = { afterId, _ ->
                    calls++
                    if (afterId == 0L) error("injected wrap query failure")
                    emptyList()
                },
                idOf = { it },
            )
            fail("expected the wrap query failure to propagate")
        } catch (failure: IllegalStateException) {
            assertEquals("injected wrap query failure", failure.message)
        }
        assertEquals(2, calls)
        // The wrap checkpoint never claims the debt is done; a retry from 0
        // still serves the pending prefix.
        val retry = store(cursors).nextLongPage(
            ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(100L)), { it },
        )
        assertEquals(listOf(100L), retry)
    }

    // ---- Matrix: cursor persistence failure must not destroy pending work. ----

    @Test
    fun `cursor write failure propagates and pending rows stay intact`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
        val rows = listOf(1L, 2L)
        val failingStore = ReminderRecoveryCursorStore(
            readCursor = { key -> cursors[key] },
            writeCursor = { error("injected cursor write failure") },
        )
        try {
            failingStore.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(rows), { it })
            fail("expected the cursor write failure to propagate")
        } catch (failure: IllegalStateException) {
            assertEquals("injected cursor write failure", failure.message)
        }
        // Durable state is unchanged; a healthy store still serves the same debt.
        assertEquals("0", cursors.getOrDefault(ReminderRecoveryCursorKeys.TASK_DELETION, "0"))
        assertEquals(rows, store(cursors).nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(rows), { it }))
    }

    // ---- Matrix: cancellation is never reported as success. ----

    @Test
    fun `cancellation during load propagates instead of returning an empty page`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
        try {
            store(cursors).nextLongPage<Long>(
                ReminderRecoveryCursorKeys.TASK_DELETION,
                128,
                load = { _, _ -> throw CancellationException("injected cancel") },
                idOf = { it },
            )
            fail("expected cancellation to propagate")
        } catch (cancelled: CancellationException) {
            assertEquals("injected cancel", cancelled.message)
        }
    }

    // ---- Matrix: concurrent reconciliations. ----

    @Test
    fun `two concurrent reconciliations do not corrupt the cursor`() = runBlocking {
        coroutineScope {
            val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
            val s = store(cursors)
            val rows = listOf(1L, 2L, 3L, 4L)
            val (first, second) = awaitAll(
                async { s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(rows), { it }) },
                async { s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(rows), { it }) },
            )
            // The mutex serializes checkpointing, so the two pages are disjoint
            // and jointly cover the first two pages with no overwrite loss.
            assertEquals(listOf(1L, 2L, 3L, 4L), (first + second).sorted())
            assertEquals("4", cursors[ReminderRecoveryCursorKeys.TASK_DELETION])
        }
    }

    // ---- Matrix: family independence. ----

    @Test
    fun `task and card families progress independently`() = runBlocking {
        val cursors = mutableMapOf(
            ReminderRecoveryCursorKeys.TASK_DELETION to "0",
            ReminderRecoveryCursorKeys.CARD_DELETION to "0",
        )
        val s = store(cursors)
        // The task family "fails" (rows stay), yet the card family is served.
        val tasks = s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(1L)), { it })
        assertEquals(listOf(1L), tasks)
        val cards = s.nextStringPage(ReminderRecoveryCursorKeys.CARD_DELETION, 128, stringLoad(listOf("c1")), { it })
        assertEquals(listOf("c1"), cards)
        // Neither cursor disturbed the other.
        assertEquals("1", cursors[ReminderRecoveryCursorKeys.TASK_DELETION])
        assertEquals("c1", cursors[ReminderRecoveryCursorKeys.CARD_DELETION])
    }

    @Test
    fun `all five queue keys hold independent cursors`() = runBlocking {
        val cursors = mutableMapOf<String, String>()
        val s = store(cursors)
        s.nextLongPage(ReminderRecoveryCursorKeys.PROJECT_DELETION, 128, longLoad(listOf(11L)), { it })
        s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(listOf(22L)), { it })
        s.nextStringPage(ReminderRecoveryCursorKeys.CARD_DELETION, 128, stringLoad(listOf("k3")), { it })
        s.nextLongPage(ReminderRecoveryCursorKeys.TASK_NOTIFICATION_CLEANUP, 128, longLoad(listOf(44L)), { it })
        s.nextStringPage(ReminderRecoveryCursorKeys.CARD_NOTIFICATION_CLEANUP, 128, stringLoad(listOf("k5")), { it })
        assertEquals("11", cursors[ReminderRecoveryCursorKeys.PROJECT_DELETION])
        assertEquals("22", cursors[ReminderRecoveryCursorKeys.TASK_DELETION])
        assertEquals("k3", cursors[ReminderRecoveryCursorKeys.CARD_DELETION])
        assertEquals("44", cursors[ReminderRecoveryCursorKeys.TASK_NOTIFICATION_CLEANUP])
        assertEquals("k5", cursors[ReminderRecoveryCursorKeys.CARD_NOTIFICATION_CLEANUP])
    }

    // ---- Boundedness: adversarial backlogs. ----

    @Test
    fun `healthy record after 512 failures is reached within the bounded invocation`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
        val s = store(cursors)
        val pending = ((1L..512L) + 513L).toMutableList()
        var queries = 0
        var attempts = 0
        var pages = 0
        var healthyReached = false
        while (pages < 16) {
            queries++
            val page = s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(pending), { it })
            if (page.isEmpty()) break
            pages++
            for (id in page) {
                attempts++
                if (id == 513L) {
                    healthyReached = true
                    pending.remove(id)
                }
                // All other rows fail and stay pending.
            }
        }
        assertTrue("healthy tail must be reached in the same bounded invocation", healthyReached)
        assertTrue("bounded pages", pages <= 16)
        assertTrue("bounded attempts", attempts <= 16 * 128)
    }

    @Test
    fun `healthy record after 1024 failures is reached within the bounded invocation`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
        val s = store(cursors)
        val pending = ((1L..1024L) + 1025L).toMutableList()
        var pages = 0
        var healthyReached = false
        while (pages < 16) {
            val page = s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(pending), { it })
            if (page.isEmpty()) break
            pages++
            for (id in page) {
                if (id == 1025L) {
                    healthyReached = true
                    pending.remove(id)
                }
            }
        }
        assertTrue(healthyReached)
        assertTrue(pages <= 16)
    }

    @Test
    fun `backlog beyond the per-invocation cap defers only the excess to the next invocation`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
        val s = store(cursors)
        val pending = (1L..2_100L).toMutableList()
        var pages = 0
        val seen = mutableListOf<Long>()
        while (pages < 16) {
            val page = s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(pending), { it })
            if (page.isEmpty()) break
            pages++
            seen += page
            pending.removeAll(page.toSet())
        }
        assertEquals(16, pages)
        assertEquals(2_048, seen.size)
        // The excess remains durably pending and is served first next invocation.
        val next = s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(pending), { it })
        assertEquals((2_049L..2_100L).toList(), next)
    }

    @Test
    fun `alternating failures and successes keep healthy rows progressing`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
        val s = store(cursors)
        val pending = (1L..10L).toMutableList()
        val succeeded = mutableListOf<Long>()
        var pages = 0
        while (pages < 16) {
            val page = s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 4, longLoad(pending), { it })
            if (page.isEmpty()) break
            pages++
            for (id in page) {
                if (id % 2L == 0L) {
                    succeeded += id
                    pending.remove(id)
                }
            }
            if (pending.all { it % 2L == 1L }) break
        }
        assertEquals(listOf(2L, 4L, 6L, 8L, 10L), succeeded.sorted())
    }

    @Test
    fun `rows disappearing between selection and processing still terminate`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
        val s = store(cursors)
        val pending = mutableListOf(1L, 2L, 3L)
        val page = s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(pending), { it })
        assertEquals(listOf(1L, 2L, 3L), page)
        // All rows vanish (deleted elsewhere) before processing.
        pending.clear()
        val next = s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 128, longLoad(pending), { it })
        // Suffix empty -> same-call wrap -> prefix also empty -> terminates.
        assertEquals(emptyList<Long>(), next)
    }

    @Test
    fun `keyset paging stays stable when earlier rows are deleted`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
        val s = store(cursors)
        val pending = (1L..5L).toMutableList()
        assertEquals(listOf(1L, 2L), s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(pending), { it }))
        pending.removeAll(listOf(1L, 2L))
        assertEquals(listOf(3L, 4L), s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(pending), { it }))
        pending.removeAll(listOf(3L, 4L))
        assertEquals(listOf(5L), s.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(pending), { it }))
    }

    @Test
    fun `cursor survives store reopen`() = runBlocking {
        val cursors = mutableMapOf(ReminderRecoveryCursorKeys.TASK_DELETION to "0")
        store(cursors).nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(listOf(1L, 2L, 3L)), { it })
        assertEquals("2", cursors[ReminderRecoveryCursorKeys.TASK_DELETION])
        // "Reopen": a brand-new store over the same durable map resumes forward.
        val reopened = store(cursors)
        assertEquals(
            listOf(3L),
            reopened.nextLongPage(ReminderRecoveryCursorKeys.TASK_DELETION, 2, longLoad(listOf(1L, 2L, 3L)), { it }),
        )
    }
}
