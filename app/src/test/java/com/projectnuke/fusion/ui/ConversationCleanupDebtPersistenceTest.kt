package com.projectnuke.fusion.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConversationCleanupDebtPersistenceTest {
    @Test
    fun `debt record and remove are bounded by max entries`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")

        repeat(70) { i ->
            ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(i.toLong() + 1, emptySet(), emptySet(), 0, 0L))
        }
        assertTrue(ConversationCleanupDebtStore.load(file).size <= 64)

        ConversationCleanupDebtStore.remove(file, 1L)
        assertTrue(ConversationCleanupDebtStore.load(file).none { it.conversationId == 1L })
        dir.deleteRecursively()
    }

    @Test
    fun `terminal debt is not silently discarded`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")

        val terminal = ConversationCleanupDebt(9000L, setOf(1L), setOf("/test"), 100, System.currentTimeMillis())
        ConversationCleanupDebtStore.record(file, terminal)

        val loaded = ConversationCleanupDebtStore.load(file)
        assertTrue(loaded.any { it.conversationId == 9000L && it.attempts >= 100 })
        dir.deleteRecursively()
    }

    @Test
    fun `corrupt file is fail-closed and quarantined on record`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        file.writeText("[{]broken}[")

        val load = ConversationCleanupDebtStore.loadResult(file)
        assertTrue(load is ConversationCleanupLoad.Corrupt)

        val recorded = ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(1L, setOf(2L), emptySet(), 0, 0L))
        assertTrue(recorded)
        assertTrue(File(dir, "conversation_cleanup_debt.json.corrupt.bak").isFile)
        assertTrue(ConversationCleanupDebtStore.load(file).isNotEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `oversized file is fail-closed`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        file.writeText("z".repeat(ConversationCleanupDebtStore.MAX_FILE_BYTES + 1))
        val load = ConversationCleanupDebtStore.loadResult(file)
        assertTrue(load is ConversationCleanupLoad.Oversized)
        assertFalse(ConversationCleanupDebtStore.remove(file, 1L))
        assertTrue(file.isFile)
        dir.deleteRecursively()
    }

    @Test
    fun `remove non-existent debt returns true`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        assertTrue(ConversationCleanupDebtStore.remove(file, Long.MAX_VALUE))
        dir.deleteRecursively()
    }

    @Test
    fun `debt at exact bounds round-trips without truncation`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")

        val maxMessages = (1L..ConversationCleanupDebtStore.MAX_MESSAGE_IDS).toSet()
        val maxPaths = (0 until ConversationCleanupDebtStore.MAX_PATHS).map { "/path/$it" }.toSet()

        val debt = ConversationCleanupDebt(1L, maxMessages, maxPaths, 0, 0L)
        assertTrue(ConversationCleanupDebtStore.record(file, debt))

        val loaded = ConversationCleanupDebtStore.load(file).firstOrNull { it.conversationId == 1L }!!
        assertEquals(maxMessages, loaded.messageIds)
        assertEquals(maxPaths, loaded.pendingPaths)
        dir.deleteRecursively()
    }

    @Test
    fun `terminal retry repeated does not duplicate entries`() = runTest {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(1L, setOf(1L), setOf("/a"), 100, 0L))
        ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(2L, setOf(2L), setOf("/b"), 0, 0L))
        repeat(3) {
            val outcome = ConversationCleanupDebtStore.retryWithHandler(file) { debt ->
                debt.conversationId == 2L
            }
            assertTrue(outcome.persistenceOk)
        }
        val entries = ConversationCleanupDebtStore.load(file)
        assertEquals(1, entries.count { it.conversationId == 1L })
        assertTrue(entries.none { it.conversationId == 2L })
        dir.deleteRecursively()
    }

    @Test
    fun `one pass processes more than four actionable debts`() = runTest {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        repeat(6) { i ->
            ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(i.toLong() + 10, emptySet(), emptySet(), 0, 0L))
        }
        val outcome = ConversationCleanupDebtStore.retryWithHandler(file) { true }
        assertEquals(6, outcome.attempted)
        assertTrue(outcome.persistenceOk)
        assertTrue(ConversationCleanupDebtStore.load(file).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `corrupt retry preserves file and reports durability failure`() = runTest {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        file.writeText("[{]broken}[")
        var calls = 0
        val outcome = ConversationCleanupDebtStore.retryWithHandler(file) { calls++; true }
        assertEquals(0, calls)
        assertFalse(outcome.persistenceOk)
        assertTrue(file.isFile)
        dir.deleteRecursively()
    }

    @Test
    fun `one invalid entry among valid makes whole file corrupt`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        val valid = org.json.JSONObject().put("id", 1L).put("messages", org.json.JSONArray()).put("paths", org.json.JSONArray()).put("attempts", 0).put("lastAttemptAt", 0L)
        val invalid = org.json.JSONObject().put("id", 2L).put("messages", "not-an-array").put("paths", org.json.JSONArray())
        file.writeText(org.json.JSONArray().put(valid).put(invalid).toString())
        assertTrue(ConversationCleanupDebtStore.loadResult(file) is ConversationCleanupLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `single invalid entry is not valid empty and malformed types are corrupt`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val onlyInvalid = File(dir, "only-invalid.json").apply {
            writeText(org.json.JSONArray().put(org.json.JSONObject().put("id", -5L)).toString())
        }
        assertTrue(ConversationCleanupDebtStore.loadResult(onlyInvalid) is ConversationCleanupLoad.Corrupt)
        val wrongType = File(dir, "wrong-type.json").apply {
            writeText(org.json.JSONArray().put(org.json.JSONObject().put("id", 1L).put("messages", org.json.JSONArray()).put("paths", org.json.JSONArray()).put("attempts", "many")).toString())
        }
        assertTrue(ConversationCleanupDebtStore.loadResult(wrongType) is ConversationCleanupLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `over-count entries and over-count messages paths are corrupt`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val many = org.json.JSONArray()
        repeat(ConversationCleanupDebtStore.MAX_ENTRIES + 1) { i ->
            many.put(org.json.JSONObject().put("id", (i + 1).toLong()).put("messages", org.json.JSONArray()).put("paths", org.json.JSONArray()))
        }
        assertTrue(ConversationCleanupDebtStore.loadResult(File(dir, "a.json").apply { writeText(many.toString()) }) is ConversationCleanupLoad.Corrupt)
        val msgs = org.json.JSONArray()
        repeat(ConversationCleanupDebtStore.MAX_MESSAGE_IDS + 1) { msgs.put((it + 1).toLong()) }
        val overMsgs = File(dir, "b.json").apply {
            writeText(org.json.JSONArray().put(org.json.JSONObject().put("id", 1L).put("messages", msgs).put("paths", org.json.JSONArray())).toString())
        }
        assertTrue(ConversationCleanupDebtStore.loadResult(overMsgs) is ConversationCleanupLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `duplicate identities and duplicate set members are corrupt`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        fun entry(id: Long, messages: org.json.JSONArray = org.json.JSONArray(), paths: org.json.JSONArray = org.json.JSONArray()) =
            org.json.JSONObject().put("id", id).put("messages", messages).put("paths", paths)
                .put("attempts", 0).put("lastAttemptAt", 0L)
        val dupId = File(dir, "dup-id.json").apply {
            writeText(org.json.JSONArray().put(entry(1L)).put(entry(1L)).toString())
        }
        assertTrue(ConversationCleanupDebtStore.loadResult(dupId) is ConversationCleanupLoad.Corrupt)
        val dupMessage = File(dir, "dup-message.json").apply {
            writeText(org.json.JSONArray().put(entry(1L, org.json.JSONArray().put(7L).put(7L))).toString())
        }
        assertTrue(ConversationCleanupDebtStore.loadResult(dupMessage) is ConversationCleanupLoad.Corrupt)
        val dupPath = File(dir, "dup-path.json").apply {
            writeText(org.json.JSONArray().put(entry(1L, org.json.JSONArray(), org.json.JSONArray().put("/p").put("/p"))).toString())
        }
        assertTrue(ConversationCleanupDebtStore.loadResult(dupPath) is ConversationCleanupLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `negative lastAttemptAt is corrupt, future timestamp is accepted`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val negative = File(dir, "negative-ts.json").apply {
            writeText(org.json.JSONArray().put(
                org.json.JSONObject().put("id", 1L).put("messages", org.json.JSONArray())
                    .put("paths", org.json.JSONArray()).put("attempts", 0).put("lastAttemptAt", -50L),
            ).toString())
        }
        assertTrue(ConversationCleanupDebtStore.loadResult(negative) is ConversationCleanupLoad.Corrupt)
        val future = File(dir, "future-ts.json").apply {
            writeText(org.json.JSONArray().put(
                org.json.JSONObject().put("id", 1L).put("messages", org.json.JSONArray())
                    .put("paths", org.json.JSONArray()).put("attempts", 0)
                    .put("lastAttemptAt", System.currentTimeMillis() + 86_400_000L),
            ).toString())
        }
        assertTrue(ConversationCleanupDebtStore.loadResult(future) is ConversationCleanupLoad.Valid)
        dir.deleteRecursively()
    }

    @Test
    fun `record after quarantine failure leaves original byte-identical`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json").apply { writeText("[{]broken}[") }
        val before = file.readBytes()
        ConversationCleanupDebtStore.quarantineHook = { com.projectnuke.fusion.util.DebtQuarantineOutcome.Failed }
        try {
            assertFalse(ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(1L, setOf(2L), emptySet(), 0, 0L)))
            assertTrue(before.contentEquals(file.readBytes()))
        } finally {
            ConversationCleanupDebtStore.quarantineHook = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cancellation during cleanup propagates without consuming attempts`() = runTest {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(1L, setOf(2L), emptySet(), 0, 0L))
        val before = file.readText()
        var threw = false
        try {
            ConversationCleanupDebtStore.retryWithHandler(file) { throw kotlinx.coroutines.CancellationException("gone") }
        } catch (_: kotlinx.coroutines.CancellationException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(before, file.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `over max message ids and paths fail without replacement or truncation`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        assertTrue(ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(1L, setOf(2L), emptySet(), 0, 0L)))
        val goodBytes = file.readBytes()
        val tooManyMessages = (1L..(ConversationCleanupDebtStore.MAX_MESSAGE_IDS + 1)).toSet()
        assertFalse(ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(2L, tooManyMessages, emptySet(), 0, 0L)))
        val tooManyPaths = (0..ConversationCleanupDebtStore.MAX_PATHS).map { "/p/$it" }.toSet()
        assertFalse(ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(3L, setOf(4L), tooManyPaths, 0, 0L)))
        assertFalse(ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(4L, setOf(5L), setOf("p".repeat(ConversationCleanupDebtStore.MAX_PATH_CHARS + 1)), 0, 0L)))
        assertFalse(ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(5L, setOf(6L), emptySet(), ConversationCleanupDebtStore.MAX_ATTEMPTS + 1, 0L)))
        assertTrue(goodBytes.contentEquals(file.readBytes()))
        assertEquals(1, ConversationCleanupDebtStore.load(file).size)
        dir.deleteRecursively()
    }

    @Test
    fun `full store rejects new identity but allows same identity replacement`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        repeat(ConversationCleanupDebtStore.MAX_ENTRIES) { i ->
            assertTrue(ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(i.toLong() + 1, emptySet(), emptySet(), 0, 0L)))
        }
        assertEquals(ConversationCleanupDebtStore.MAX_ENTRIES, ConversationCleanupDebtStore.load(file).size)
        val fullBytes = file.readBytes()
        assertFalse(ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(9999L, emptySet(), emptySet(), 0, 0L)))
        assertTrue(fullBytes.contentEquals(file.readBytes()))
        assertTrue(ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(1L, setOf(77L), emptySet(), 0, 0L)))
        val after = ConversationCleanupDebtStore.load(file)
        assertEquals(ConversationCleanupDebtStore.MAX_ENTRIES, after.size)
        assertEquals(setOf(77L), after.first { it.conversationId == 1L }.messageIds)
        dir.deleteRecursively()
    }

    @Test
    fun `floating id and attempts are corrupt`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val floatId = File(dir, "a.json").apply {
            writeText("""[{"id":1.5,"messages":[],"paths":[]}]""")
        }
        assertTrue(ConversationCleanupDebtStore.loadResult(floatId) is ConversationCleanupLoad.Corrupt)
        val floatMsg = File(dir, "b.json").apply {
            writeText("""[{"id":1,"messages":[2.5],"paths":[]}]""")
        }
        assertTrue(ConversationCleanupDebtStore.loadResult(floatMsg) is ConversationCleanupLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `aggregate above byte cap fails leaving good state unchanged`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        assertTrue(ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(1L, setOf(2L), emptySet(), 0, 0L)))
        var rejected = false
        repeat(ConversationCleanupDebtStore.MAX_ENTRIES) { i ->
            val big = ConversationCleanupDebt(
                100L + i,
                (1L..ConversationCleanupDebtStore.MAX_MESSAGE_IDS).toSet(),
                (0 until ConversationCleanupDebtStore.MAX_PATHS).map { "/p/$i/$it/" + "a".repeat(64) }.toSet(),
                0, 0L,
            )
            if (!ConversationCleanupDebtStore.record(file, big)) rejected = true
        }
        assertTrue("expected at least one byte-cap rejection", rejected)
        assertTrue(ConversationCleanupDebtStore.loadResult(file) is ConversationCleanupLoad.Valid)
        assertTrue(file.readBytes().size <= ConversationCleanupDebtStore.MAX_FILE_BYTES)
        dir.deleteRecursively()
    }

    @Test
    fun `largest legal single-entry representation loads via production writer`() {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        val debt = ConversationCleanupDebt(1L, setOf(1L, 2L), setOf("/p/a"), 0, 0L)
        assertTrue(ConversationCleanupDebtStore.record(file, debt))
        assertTrue(ConversationCleanupDebtStore.loadResult(file) is ConversationCleanupLoad.Valid)
        assertTrue(file.readBytes().size <= ConversationCleanupDebtStore.MAX_FILE_BYTES)
        dir.deleteRecursively()
    }

    @Test
    fun `terminal concurrent removal is preserved`() = runTest {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(1L, setOf(10L), emptySet(), ConversationCleanupDebtStore.MAX_ATTEMPTS, 0L))
        ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(2L, setOf(20L), emptySet(), 0, 0L))

        val outcome = ConversationCleanupDebtStore.retryWithHandler(file) { debt ->
            if (debt.conversationId == 2L) {
                // Concurrently remove terminal debt 1L
                ConversationCleanupDebtStore.remove(file, 1L)
                true
            } else {
                false
            }
        }

        assertTrue(outcome.persistenceOk)
        val entries = ConversationCleanupDebtStore.load(file)
        assertTrue("terminal debt must NOT be resurrected after concurrent remove", entries.none { it.conversationId == 1L })
        assertTrue("actionable debt must be cleaned and removed", entries.none { it.conversationId == 2L })
        assertEquals(0, outcome.pendingAfter)
        dir.deleteRecursively()
    }

    @Test
    fun `terminal same-identity replacement is preserved`() = runTest {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(1L, setOf(10L), emptySet(), ConversationCleanupDebtStore.MAX_ATTEMPTS, 0L))
        ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(2L, setOf(20L), emptySet(), 0, 0L))

        val outcome = ConversationCleanupDebtStore.retryWithHandler(file) { debt ->
            if (debt.conversationId == 2L) {
                // Concurrently record replacement for 1L
                ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(1L, setOf(99L), setOf("/new"), 0, 1000L))
                true
            } else {
                false
            }
        }

        assertTrue(outcome.persistenceOk)
        val entries = ConversationCleanupDebtStore.load(file)
        val debt1 = entries.singleOrNull { it.conversationId == 1L }
        assertEquals(setOf(99L), debt1?.messageIds)
        assertEquals(setOf("/new"), debt1?.pendingPaths)
        assertEquals(0, debt1?.attempts)
        dir.deleteRecursively()
    }

    @Test
    fun `out-of-limit concurrent removal is preserved`() = runTest {
        val dir = Files.createTempDirectory("fusion-cleanup-debt").toFile()
        val file = File(dir, "conversation_cleanup_debt.json")
        ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(1L, setOf(10L), emptySet(), 0, 0L))
        ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(2L, setOf(20L), emptySet(), 0, 0L))
        ConversationCleanupDebtStore.record(file, ConversationCleanupDebt(3L, setOf(30L), emptySet(), 0, 0L))

        val outcome = ConversationCleanupDebtStore.retryWithHandler(file, limit = 1) { debt ->
            if (debt.conversationId == 1L) {
                // Concurrent remove of debt2 outside limit
                ConversationCleanupDebtStore.remove(file, 2L)
                true
            } else {
                false
            }
        }

        assertTrue(outcome.persistenceOk)
        val entries = ConversationCleanupDebtStore.load(file)
        assertTrue(entries.none { it.conversationId == 1L })
        assertTrue("out-of-limit debt 2 must not be resurrected", entries.none { it.conversationId == 2L })
        assertTrue(entries.any { it.conversationId == 3L })
        dir.deleteRecursively()
    }
}
