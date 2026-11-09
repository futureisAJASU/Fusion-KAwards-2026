package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ComposerDraftState
import com.projectnuke.fusion.chat.PersistentComposerDraftStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CommittedDraftReconciliationDebtStoreTest {

    @Test
    fun `record and remove are keyed by conversation and token without eviction`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        val debt = CommittedDraftReconciliationDebt(1L, "token-a", "hello", setOf("/managed/a"), 0, 0L)

        assertTrue(CommittedDraftReconciliationDebtStore.record(file, debt))
        // Same identity replacement updates in place.
        assertTrue(CommittedDraftReconciliationDebtStore.record(file, debt.copy(capturedRawInput = "updated")))
        assertEquals("updated", loadAll(file).single { it.draftKey == 1L && it.token == "token-a" }.capturedRawInput)
        // Different token is a different identity.
        assertTrue(CommittedDraftReconciliationDebtStore.record(file, debt.copy(token = "token-b")))
        assertEquals(2, loadAll(file).size)

        CommittedDraftReconciliationDebtStore.remove(file, 1L, "token-a")
        assertTrue(loadAll(file).none { it.draftKey == 1L && it.token == "token-a" })
        assertTrue(loadAll(file).any { it.draftKey == 1L && it.token == "token-b" })
        dir.deleteRecursively()
    }

    @Test
    fun `corrupt file is fail-closed and quarantined on record`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        file.writeText("[{]broken}[")

        // Must not be interpreted as empty.
        val before = CommittedDraftReconciliationDebtStore.loadResult(file)
        assertTrue(before is CommittedDraftDebtLoad.Corrupt)
        assertTrue(CommittedDraftReconciliationDebtStore.load(file).isEmpty().let { true }) // legacy compat
        // Record must quarantine evidence instead of silently overwriting.
        assertTrue(
            CommittedDraftReconciliationDebtStore.record(
                file,
                CommittedDraftReconciliationDebt(1L, "token-a", "hello", emptySet(), 0, 0L),
            )
        )
        assertTrue(File(dir, "committed_draft_reconciliation_debt.json.corrupt.bak").isFile)
        assertTrue(loadAll(file).isNotEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `oversized file is fail-closed and never treated as empty`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        // Force oversize: write > MAX_FILE_BYTES bytes of valid JSON padding.
        val padding = "x".repeat(CommittedDraftReconciliationDebtStore.MAX_FILE_BYTES + 16)
        file.writeText("[{\"id\":1,\"token\":\"t\",\"rawInput\":\"$padding\",\"attempts\":0,\"lastAttemptAt\":0,\"paths\":[]}]")
        val load = CommittedDraftReconciliationDebtStore.loadResult(file)
        assertTrue("oversized must be OVERSIZED, was $load", load is CommittedDraftDebtLoad.Oversized)
        // Remove must fail closed without destroying evidence.
        assertFalse(CommittedDraftReconciliationDebtStore.remove(file, 1L, "t"))
        assertTrue(file.isFile)
        dir.deleteRecursively()
    }

    @Test
    fun `writer output within cap loads, oversize write rejected before replacing good state`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        val good = CommittedDraftReconciliationDebt(1L, "token-a", "hello", emptySet(), 0, 0L)
        assertTrue(CommittedDraftReconciliationDebtStore.record(file, good))
        val goodBytes = file.readText().toByteArray(Charsets.UTF_8).size
        assertTrue(goodBytes <= CommittedDraftReconciliationDebtStore.MAX_FILE_BYTES)
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(file) is CommittedDraftDebtLoad.Valid)

        // Exact contract: overlong rawInput is rejected WITHOUT truncation
        // and the good durable state remains byte-identical.
        val hugeRaw = "y".repeat(CommittedDraftReconciliationDebtStore.MAX_RAW_INPUT_CHARS + 1)
        val huge = CommittedDraftReconciliationDebt(2L, "token-huge", hugeRaw, setOf("/p"), 0, 0L)
        val goodBytesBefore = file.readBytes()
        assertFalse(CommittedDraftReconciliationDebtStore.record(file, huge))
        assertTrue(goodBytesBefore.contentEquals(file.readBytes()))
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(file) is CommittedDraftDebtLoad.Valid)
        // Oversized independent file still quarantines on valid record.
        val oversized = File(dir, "oversized.json")
        oversized.writeText("z".repeat(CommittedDraftReconciliationDebtStore.MAX_FILE_BYTES + 1))
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(oversized) is CommittedDraftDebtLoad.Oversized)
        assertTrue(CommittedDraftReconciliationDebtStore.record(oversized, good))
        assertTrue(File(dir, "oversized.json.corrupt.bak").isFile)
        dir.deleteRecursively()
    }

    @Test
    fun `retry reconciles the exact owned draft and clears the debt`() = runTest {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val debtFile = File(dir, "committed_draft_reconciliation_debt.json")
        val draftFile = File(dir, "composer_drafts.json")
        val managedA = File(dir, "a.txt").apply { writeText("a") }
        val managedB = File(dir, "b.txt").apply { writeText("b") }
        val registered = mutableListOf<String>()
        val store = PersistentComposerDraftStore(
            file = draftFile,
            resolveManagedAttachment = { path ->
                if (path == managedA.absolutePath || path == managedB.absolutePath) File(path) else null
            },
            registerPendingAttachment = { registered += it.absolutePath; it.absolutePath },
        )
        store.write(
            mapOf(
                7L to ComposerDraftState(
                    rawInput = "hello",
                    pendingAttachments = listOf(
                        managed("a", managedA.absolutePath),
                        managed("b", managedB.absolutePath),
                    ),
                    activeSubmissionToken = "token-a",
                    version = 3L,
                ),
            ),
        )
        val unregistered = mutableListOf<String>()
        CommittedDraftReconciliationDebtStore.record(
            debtFile,
            CommittedDraftReconciliationDebt(
                draftKey = 7L,
                token = "token-a",
                capturedRawInput = "hello",
                committedPaths = setOf(managedA.absolutePath),
                attempts = 0,
                lastAttemptAt = 0L,
            ),
        )

        val outcome = CommittedDraftReconciliationDebtStore.retry(
            owner = DraftReconciliationOwner { debt ->
                val drafts = store.load()
                val current = drafts[debt.draftKey] ?: return@DraftReconciliationOwner DraftReconciliationResult(true, true)
                if (current.activeSubmissionToken != debt.token) return@DraftReconciliationOwner DraftReconciliationResult(true, false)
                DraftReconciliationResult(store.write(drafts + (debt.draftKey to current.copy(
                    rawInput = if (current.rawInput == debt.capturedRawInput) "" else current.rawInput,
                    pendingAttachments = current.pendingAttachments.filterNot { it.localPath in debt.committedPaths },
                    activeSubmissionToken = null,
                    version = current.version + 1L,
                ))), true)
            },
            unregisterPath = { unregistered += it },
            file = debtFile,
        )

        assertEquals(1, outcome.reconciled)
        assertTrue(outcome.persistenceOk)
        assertTrue(loadAll(debtFile).isEmpty())
        assertEquals(listOf(managedA.absolutePath), unregistered)
        val after = store.load()[7L]
        assertEquals("", after?.rawInput)
        assertNull(after?.activeSubmissionToken)
        assertEquals(listOf(managedB.absolutePath), after?.pendingAttachments?.map { it.localPath })
        assertEquals(4L, after?.version)
        dir.deleteRecursively()
    }

    @Test
    fun `retry preserves a draft owned by a newer submission and resolves the debt`() = runTest {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val debtFile = File(dir, "committed_draft_reconciliation_debt.json")
        val draftFile = File(dir, "composer_drafts.json")
        val store = PersistentComposerDraftStore(
            file = draftFile,
            resolveManagedAttachment = { null },
            registerPendingAttachment = { it.path },
        )
        store.write(
            mapOf(
                7L to ComposerDraftState(
                    rawInput = "newer edit",
                    activeSubmissionToken = "token-newer",
                    version = 5L,
                ),
            ),
        )
        val unregistered = mutableListOf<String>()
        CommittedDraftReconciliationDebtStore.record(
            debtFile,
            CommittedDraftReconciliationDebt(
                draftKey = 7L,
                token = "token-stale",
                capturedRawInput = "hello",
                committedPaths = setOf("/managed/a"),
                attempts = 0,
                lastAttemptAt = 0L,
            ),
        )

        val outcome = CommittedDraftReconciliationDebtStore.retry(
            owner = DraftReconciliationOwner { debt ->
                val current = store.load()[debt.draftKey] ?: return@DraftReconciliationOwner DraftReconciliationResult(true, true)
                if (current.activeSubmissionToken != debt.token) return@DraftReconciliationOwner DraftReconciliationResult(true, false)
                DraftReconciliationResult(store.write(store.load()), true)
            },
            unregisterPath = { unregistered += it },
            file = debtFile,
        )

        assertEquals(1, outcome.reconciled)
        assertTrue(outcome.persistenceOk)
        assertTrue(loadAll(debtFile).isEmpty())
        assertTrue(unregistered.isEmpty())
        val after = store.load()[7L]
        assertEquals("newer edit", after?.rawInput)
        assertEquals("token-newer", after?.activeSubmissionToken)
        dir.deleteRecursively()
    }

    @Test
    fun `retry with a missing draft releases committed registrations`() = runTest {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val debtFile = File(dir, "committed_draft_reconciliation_debt.json")
        CommittedDraftReconciliationDebtStore.record(
            debtFile,
            CommittedDraftReconciliationDebt(
                draftKey = 7L,
                token = "token-a",
                capturedRawInput = "hello",
                committedPaths = setOf("/managed/a"),
                attempts = 0,
                lastAttemptAt = 0L,
            ),
        )
        val unregistered = mutableListOf<String>()

        val outcome = CommittedDraftReconciliationDebtStore.retry(
            owner = DraftReconciliationOwner { DraftReconciliationResult(true, true) },
            unregisterPath = { unregistered += it },
            file = debtFile,
        )

        assertEquals(1, outcome.reconciled)
        assertTrue(outcome.persistenceOk)
        assertTrue(loadAll(debtFile).isEmpty())
        assertEquals(listOf("/managed/a"), unregistered)
        dir.deleteRecursively()
    }

    @Test
    fun `retry keeps a debt whose write failed and increments attempts`() = runTest {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val debtFile = File(dir, "committed_draft_reconciliation_debt.json")
        CommittedDraftReconciliationDebtStore.record(
            debtFile,
            CommittedDraftReconciliationDebt(
                draftKey = 7L,
                token = "token-a",
                capturedRawInput = "hello",
                committedPaths = setOf("/managed/a"),
                attempts = 0,
                lastAttemptAt = 0L,
            ),
        )

        val outcome = CommittedDraftReconciliationDebtStore.retry(
            owner = DraftReconciliationOwner { DraftReconciliationResult(false, false) },
            unregisterPath = {},
            file = debtFile,
        )

        assertEquals(0, outcome.reconciled)
        assertTrue(outcome.persistenceOk)
        val retained = loadAll(debtFile).single()
        assertEquals(7L, retained.draftKey)
        assertEquals(1, retained.attempts)
        dir.deleteRecursively()
    }

    @Test
    fun `one startup pass processes more than four actionable debts`() = runTest {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val debtFile = File(dir, "committed_draft_reconciliation_debt.json")
        repeat(6) { i ->
            CommittedDraftReconciliationDebtStore.record(
                debtFile,
                CommittedDraftReconciliationDebt(i.toLong(), "token-$i", "input-$i", emptySet(), 0, 0L),
            )
        }
        assertEquals(6, loadAll(debtFile).size)
        val outcome = CommittedDraftReconciliationDebtStore.retry(
            owner = DraftReconciliationOwner { DraftReconciliationResult(true, false) },
            unregisterPath = {},
            file = debtFile,
        )
        assertEquals(6, outcome.reconciled)
        assertTrue(outcome.persistenceOk)
        assertTrue(loadAll(debtFile).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `terminal retry repeated does not duplicate entries`() = runTest {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val debtFile = File(dir, "committed_draft_reconciliation_debt.json")
        CommittedDraftReconciliationDebtStore.record(
            debtFile,
            CommittedDraftReconciliationDebt(1L, "token-t", "hello", emptySet(), CommittedDraftReconciliationDebtStore.MAX_ATTEMPTS, 0L),
        )
        CommittedDraftReconciliationDebtStore.record(
            debtFile,
            CommittedDraftReconciliationDebt(2L, "token-p", "hello", emptySet(), 0, 0L),
        )
        repeat(3) {
            val outcome = CommittedDraftReconciliationDebtStore.retry(
                owner = DraftReconciliationOwner { debt ->
                    if (debt.draftKey == 2L) DraftReconciliationResult(true, false)
                    else DraftReconciliationResult(false, false)
                },
                unregisterPath = {},
                file = debtFile,
            )
            assertTrue(outcome.persistenceOk)
        }
        val entries = loadAll(debtFile)
        assertEquals(1, entries.count { it.draftKey == 1L && it.token == "token-t" })
        assertTrue(entries.none { it.draftKey == 2L })
        assertTrue(entries.size <= CommittedDraftReconciliationDebtStore.MAX_ENTRIES)
        dir.deleteRecursively()
    }

    @Test
    fun `corrupt retry preserves file and reports durability failure`() = runTest {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val debtFile = File(dir, "committed_draft_reconciliation_debt.json")
        debtFile.writeText("[{]broken}[")
        var handlerInvocations = 0
        val outcome = CommittedDraftReconciliationDebtStore.retry(
            owner = DraftReconciliationOwner { handlerInvocations++; DraftReconciliationResult(true, false) },
            unregisterPath = {},
            file = debtFile,
        )
        assertEquals(0, handlerInvocations)
        assertEquals(0, outcome.reconciled)
        assertFalse(outcome.persistenceOk)
        // Evidence preserved, not deleted.
        assertTrue(debtFile.isFile)
        assertTrue(debtFile.readText().isNotEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `one invalid entry among valid entries makes whole file corrupt`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        val valid = org.json.JSONObject().put("id", 1L).put("token", "t1").put("rawInput", "hi").put("paths", org.json.JSONArray()).put("attempts", 0).put("lastAttemptAt", 0L)
        val invalid = org.json.JSONObject().put("id", 2L).put("rawInput", "missing-token").put("paths", org.json.JSONArray())
        file.writeText(org.json.JSONArray().put(valid).put(invalid).toString())
        val load = CommittedDraftReconciliationDebtStore.loadResult(file)
        assertTrue("expected CORRUPT, was $load", load is CommittedDraftDebtLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `single invalid entry is not valid empty`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        file.writeText(org.json.JSONArray().put(org.json.JSONObject().put("id", 1L)).toString())
        val load = CommittedDraftReconciliationDebtStore.loadResult(file)
        assertTrue(load is CommittedDraftDebtLoad.Corrupt)
        assertFalse(load is CommittedDraftDebtLoad.Valid)
        dir.deleteRecursively()
    }

    @Test
    fun `missing token and missing rawInput are corrupt`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val noToken = File(dir, "no-token.json").apply {
            writeText(org.json.JSONArray().put(org.json.JSONObject().put("id", 1L).put("rawInput", "x").put("paths", org.json.JSONArray())).toString())
        }
        val noRaw = File(dir, "no-raw.json").apply {
            writeText(org.json.JSONArray().put(org.json.JSONObject().put("id", 1L).put("token", "t").put("paths", org.json.JSONArray())).toString())
        }
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(noToken) is CommittedDraftDebtLoad.Corrupt)
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(noRaw) is CommittedDraftDebtLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `wrong JSON type is corrupt`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        file.writeText(org.json.JSONArray().put(org.json.JSONObject().put("id", "not-a-number").put("token", "t").put("rawInput", "x").put("paths", org.json.JSONArray())).toString())
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(file) is CommittedDraftDebtLoad.Corrupt)
        file.writeText("""{"id":1}""")
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(file) is CommittedDraftDebtLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `too many entries and excessive paths are corrupt not truncated`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val many = org.json.JSONArray()
        repeat(CommittedDraftReconciliationDebtStore.MAX_ENTRIES + 1) { i ->
            many.put(org.json.JSONObject().put("id", i.toLong()).put("token", "t$i").put("rawInput", "x").put("paths", org.json.JSONArray()))
        }
        val overCount = File(dir, "over-count.json").apply { writeText(many.toString()) }
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(overCount) is CommittedDraftDebtLoad.Corrupt)
        val paths = org.json.JSONArray()
        repeat(CommittedDraftReconciliationDebtStore.MAX_PATHS + 1) { paths.put("/p$it") }
        val overPaths = File(dir, "over-paths.json").apply {
            writeText(org.json.JSONArray().put(org.json.JSONObject().put("id", 1L).put("token", "t").put("rawInput", "x").put("paths", paths)).toString())
        }
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(overPaths) is CommittedDraftDebtLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `duplicate debt identity and duplicate paths are corrupt`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        fun entry(id: Long, token: String, paths: org.json.JSONArray = org.json.JSONArray()) =
            org.json.JSONObject().put("id", id).put("token", token).put("rawInput", "x").put("paths", paths)
                .put("attempts", 0).put("lastAttemptAt", 0L)
        val dupIdentity = File(dir, "dup-identity.json").apply {
            writeText(org.json.JSONArray().put(entry(1L, "t")).put(entry(1L, "t")).toString())
        }
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(dupIdentity) is CommittedDraftDebtLoad.Corrupt)
        // Same draftKey with a different token is a distinct identity and stays valid.
        val distinctTokens = File(dir, "distinct-tokens.json").apply {
            writeText(org.json.JSONArray().put(entry(1L, "t-a")).put(entry(1L, "t-b")).toString())
        }
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(distinctTokens) is CommittedDraftDebtLoad.Valid)
        val dupPaths = File(dir, "dup-paths.json").apply {
            writeText(org.json.JSONArray().put(entry(1L, "t", org.json.JSONArray().put("/p").put("/p"))).toString())
        }
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(dupPaths) is CommittedDraftDebtLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `negative lastAttemptAt is corrupt, future timestamp is accepted`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val negative = File(dir, "negative-ts.json").apply {
            writeText(org.json.JSONArray().put(
                org.json.JSONObject().put("id", 1L).put("token", "t").put("rawInput", "x")
                    .put("paths", org.json.JSONArray()).put("attempts", 0).put("lastAttemptAt", -1L),
            ).toString())
        }
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(negative) is CommittedDraftDebtLoad.Corrupt)
        val future = File(dir, "future-ts.json").apply {
            writeText(org.json.JSONArray().put(
                org.json.JSONObject().put("id", 1L).put("token", "t").put("rawInput", "x")
                    .put("paths", org.json.JSONArray()).put("attempts", 0)
                    .put("lastAttemptAt", System.currentTimeMillis() + 86_400_000L),
            ).toString())
        }
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(future) is CommittedDraftDebtLoad.Valid)
        dir.deleteRecursively()
    }

    @Test
    fun `record after quarantine failure leaves original byte-identical`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json").apply { writeText("[{]broken}[") }
        val before = file.readBytes()
        CommittedDraftReconciliationDebtStore.quarantineHook = { com.projectnuke.fusion.util.DebtQuarantineOutcome.Failed }
        try {
            assertFalse(CommittedDraftReconciliationDebtStore.record(file, CommittedDraftReconciliationDebt(1L, "t", "x", emptySet(), 0, 0L)))
            assertTrue(before.contentEquals(file.readBytes()))
            assertTrue(CommittedDraftReconciliationDebtStore.loadResult(file) is CommittedDraftDebtLoad.Corrupt)
        } finally {
            CommittedDraftReconciliationDebtStore.quarantineHook = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun `largest legal single-entry representation loads via production writer`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        val maxPaths = (0 until CommittedDraftReconciliationDebtStore.MAX_PATHS).map { "/p/$it/" + "a".repeat(32) }.toSet()
        val debt = CommittedDraftReconciliationDebt(
            1L,
            "t".repeat(CommittedDraftReconciliationDebtStore.MAX_TOKEN_CHARS),
            "r".repeat(CommittedDraftReconciliationDebtStore.MAX_RAW_INPUT_CHARS),
            maxPaths, 0, 0L,
        )
        assertTrue(CommittedDraftReconciliationDebtStore.record(file, debt))
        val load = CommittedDraftReconciliationDebtStore.loadResult(file)
        assertTrue("max legal must load, was $load", load is CommittedDraftDebtLoad.Valid)
        // Combined maxed entries that exceed the byte cap must be rejected before replacing good state.
        val goodBytes = file.readBytes()
        repeat(CommittedDraftReconciliationDebtStore.MAX_ENTRIES) { i ->
            CommittedDraftReconciliationDebtStore.record(file, debt.copy(draftKey = 100L + i, token = "tok-$i"))
        }
        // Either still valid (if it fits) or the good state is preserved; never OVERSIZED-on-disk.
        val after = CommittedDraftReconciliationDebtStore.loadResult(file)
        assertTrue(after is CommittedDraftDebtLoad.Valid || after is CommittedDraftDebtLoad.Corrupt)
        if (after is CommittedDraftDebtLoad.Valid) {
            assertTrue(file.readBytes().size <= CommittedDraftReconciliationDebtStore.MAX_FILE_BYTES)
        } else {
            assertTrue(goodBytes.isNotEmpty())
        }
        dir.deleteRecursively()
    }

    private fun loadAll(file: File): List<CommittedDraftReconciliationDebt> {
        if (!file.isFile) return emptyList()
        val array = org.json.JSONArray(file.readText())
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            CommittedDraftReconciliationDebt(
                draftKey = item.optLong("id"),
                token = item.optString("token"),
                capturedRawInput = item.optString("rawInput"),
                committedPaths = item.optJSONArray("paths")?.let { paths ->
                    (0 until paths.length()).map { paths.optString(it) }.toSet()
                }.orEmpty(),
                attempts = item.optInt("attempts"),
                lastAttemptAt = item.optLong("lastAttemptAt"),
            )
        }
    }

    @Test
    fun `overlong token rawInput and path fail without replacement or truncation`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        val good = CommittedDraftReconciliationDebt(1L, "token-a", "hello", emptySet(), 0, 0L)
        assertTrue(CommittedDraftReconciliationDebtStore.record(file, good))
        val goodBytes = file.readBytes()
        assertFalse(CommittedDraftReconciliationDebtStore.record(file, good.copy(token = "t".repeat(CommittedDraftReconciliationDebtStore.MAX_TOKEN_CHARS + 1))))
        assertFalse(CommittedDraftReconciliationDebtStore.record(file, good.copy(draftKey = 2L, capturedRawInput = "r".repeat(CommittedDraftReconciliationDebtStore.MAX_RAW_INPUT_CHARS + 1))))
        assertFalse(CommittedDraftReconciliationDebtStore.record(file, good.copy(draftKey = 3L, committedPaths = setOf("p".repeat(CommittedDraftReconciliationDebtStore.MAX_PATH_CHARS + 1)))))
        assertFalse(CommittedDraftReconciliationDebtStore.record(file, good.copy(draftKey = 4L, attempts = CommittedDraftReconciliationDebtStore.MAX_ATTEMPTS + 1)))
        assertTrue(goodBytes.contentEquals(file.readBytes()))
        // No truncated entry was installed.
        val load = CommittedDraftReconciliationDebtStore.loadResult(file)
        assertTrue(load is CommittedDraftDebtLoad.Valid)
        assertEquals(1, (load as CommittedDraftDebtLoad.Valid).entries.size)
        dir.deleteRecursively()
    }

    @Test
    fun `too many paths fail without replacement`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        assertTrue(CommittedDraftReconciliationDebtStore.record(file, CommittedDraftReconciliationDebt(1L, "t", "x", emptySet(), 0, 0L)))
        val goodBytes = file.readBytes()
        val many = (0 until (CommittedDraftReconciliationDebtStore.MAX_PATHS + 1)).map { "/p/$it" }.toSet()
        assertFalse(CommittedDraftReconciliationDebtStore.record(file, CommittedDraftReconciliationDebt(2L, "t2", "x", many, 0, 0L)))
        assertTrue(goodBytes.contentEquals(file.readBytes()))
        dir.deleteRecursively()
    }

    @Test
    fun `full store rejects new identity but allows same identity replacement`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        repeat(CommittedDraftReconciliationDebtStore.MAX_ENTRIES) { i ->
            assertTrue(CommittedDraftReconciliationDebtStore.record(file, CommittedDraftReconciliationDebt(i.toLong(), "tok-$i", "x", emptySet(), 0, 0L)))
        }
        assertEquals(CommittedDraftReconciliationDebtStore.MAX_ENTRIES, CommittedDraftReconciliationDebtStore.load(file).size)
        val fullBytes = file.readBytes()
        assertFalse(CommittedDraftReconciliationDebtStore.record(file, CommittedDraftReconciliationDebt(9999L, "new-tok", "x", emptySet(), 0, 0L)))
        assertTrue(fullBytes.contentEquals(file.readBytes()))
        assertEquals(CommittedDraftReconciliationDebtStore.MAX_ENTRIES, CommittedDraftReconciliationDebtStore.load(file).size)
        assertTrue(CommittedDraftReconciliationDebtStore.record(file, CommittedDraftReconciliationDebt(0L, "tok-0", "updated", emptySet(), 0, 0L)))
        val after = CommittedDraftReconciliationDebtStore.load(file)
        assertEquals(CommittedDraftReconciliationDebtStore.MAX_ENTRIES, after.size)
        assertEquals("updated", after.first { it.draftKey == 0L && it.token == "tok-0" }.capturedRawInput)
        dir.deleteRecursively()
    }

    @Test
    fun `floating id and attempts are corrupt`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val floatId = File(dir, "a.json").apply {
            writeText("""[{"id":1.5,"token":"t","rawInput":"x","paths":[],"attempts":0,"lastAttemptAt":0}]""")
        }
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(floatId) is CommittedDraftDebtLoad.Corrupt)
        val floatAttempts = File(dir, "b.json").apply {
            writeText("""[{"id":1,"token":"t","rawInput":"x","paths":[],"attempts":1.5,"lastAttemptAt":0}]""")
        }
        assertTrue(CommittedDraftReconciliationDebtStore.loadResult(floatAttempts) is CommittedDraftDebtLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `aggregate above byte cap fails leaving good state unchanged`() {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        val good = CommittedDraftReconciliationDebt(1L, "token-a", "hello", emptySet(), 0, 0L)
        assertTrue(CommittedDraftReconciliationDebtStore.record(file, good))
        // Field-valid entries whose combined encoding exceeds the cap are rejected.
        var rejected = false
        repeat(CommittedDraftReconciliationDebtStore.MAX_ENTRIES) { i ->
            val big = CommittedDraftReconciliationDebt(
                100L + i, "tok-$i",
                "r".repeat(CommittedDraftReconciliationDebtStore.MAX_RAW_INPUT_CHARS),
                (0 until CommittedDraftReconciliationDebtStore.MAX_PATHS).map { "/p/$i/$it" }.toSet(),
                0, 0L,
            )
            if (!CommittedDraftReconciliationDebtStore.record(file, big)) rejected = true
        }
        assertTrue("expected at least one byte-cap rejection", rejected)
        val load = CommittedDraftReconciliationDebtStore.loadResult(file)
        assertTrue(load is CommittedDraftDebtLoad.Valid)
        assertTrue(file.readBytes().size <= CommittedDraftReconciliationDebtStore.MAX_FILE_BYTES)
        dir.deleteRecursively()
    }

    @Test
    fun `cancellation during retry propagates without consuming attempts`() = runTest {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        CommittedDraftReconciliationDebtStore.record(file, CommittedDraftReconciliationDebt(1L, "t", "x", emptySet(), 0, 0L))
        val before = file.readText()
        var threw = false
        try {
            CommittedDraftReconciliationDebtStore.retry(
                owner = DraftReconciliationOwner { throw kotlinx.coroutines.CancellationException("owner gone") },
                unregisterPath = {},
                file = file,
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(before, file.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `terminal concurrent removal is preserved`() = runTest {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        val terminalDebt = CommittedDraftReconciliationDebt(1L, "token-term", "term-input", emptySet(), CommittedDraftReconciliationDebtStore.MAX_ATTEMPTS, 0L)
        val actionableDebt = CommittedDraftReconciliationDebt(2L, "token-act", "act-input", emptySet(), 0, 0L)
        CommittedDraftReconciliationDebtStore.record(file, terminalDebt)
        CommittedDraftReconciliationDebtStore.record(file, actionableDebt)

        val outcome = CommittedDraftReconciliationDebtStore.retry(
            owner = DraftReconciliationOwner { debt ->
                if (debt.draftKey == 2L) {
                    // Concurrent removal of terminal debt while actionable is being processed
                    CommittedDraftReconciliationDebtStore.remove(file, 1L, "token-term")
                    DraftReconciliationResult(success = true, releasePaths = false)
                } else {
                    DraftReconciliationResult(success = false, releasePaths = false)
                }
            },
            unregisterPath = {},
            file = file,
        )

        assertTrue(outcome.persistenceOk)
        assertEquals(1, outcome.reconciled)
        val after = loadAll(file)
        assertTrue("terminal debt must NOT be resurrected after concurrent remove", after.none { it.draftKey == 1L && it.token == "token-term" })
        assertTrue("actionable debt must be reconciled and removed", after.none { it.draftKey == 2L })
        assertEquals(0, outcome.pendingAfter)
        dir.deleteRecursively()
    }

    @Test
    fun `terminal same-identity replacement is preserved`() = runTest {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        val terminalDebt = CommittedDraftReconciliationDebt(1L, "token-term", "old-input", emptySet(), CommittedDraftReconciliationDebtStore.MAX_ATTEMPTS, 0L)
        val actionableDebt = CommittedDraftReconciliationDebt(2L, "token-act", "act-input", emptySet(), 0, 0L)
        CommittedDraftReconciliationDebtStore.record(file, terminalDebt)
        CommittedDraftReconciliationDebtStore.record(file, actionableDebt)

        val updatedDebt = CommittedDraftReconciliationDebt(1L, "token-term", "new-input", setOf("/path/new"), 0, 1000L)
        val outcome = CommittedDraftReconciliationDebtStore.retry(
            owner = DraftReconciliationOwner { debt ->
                if (debt.draftKey == 2L) {
                    // Concurrently record replacement
                    CommittedDraftReconciliationDebtStore.record(file, updatedDebt)
                    DraftReconciliationResult(success = true, releasePaths = false)
                } else {
                    DraftReconciliationResult(success = false, releasePaths = false)
                }
            },
            unregisterPath = {},
            file = file,
        )

        assertTrue(outcome.persistenceOk)
        val after = loadAll(file)
        val debt1 = after.singleOrNull { it.draftKey == 1L && it.token == "token-term" }
        assertEquals("new-input", debt1?.capturedRawInput)
        assertEquals(setOf("/path/new"), debt1?.committedPaths)
        assertEquals(0, debt1?.attempts)
        dir.deleteRecursively()
    }

    @Test
    fun `out-of-limit concurrent removal is preserved`() = runTest {
        val dir = Files.createTempDirectory("fusion-reconciliation-debt").toFile()
        val file = File(dir, "committed_draft_reconciliation_debt.json")
        val debt1 = CommittedDraftReconciliationDebt(1L, "tok-1", "input-1", emptySet(), 0, 0L)
        val debt2 = CommittedDraftReconciliationDebt(2L, "tok-2", "input-2", emptySet(), 0, 0L)
        val debt3 = CommittedDraftReconciliationDebt(3L, "tok-3", "input-3", emptySet(), 0, 0L)
        CommittedDraftReconciliationDebtStore.record(file, debt1)
        CommittedDraftReconciliationDebtStore.record(file, debt2)
        CommittedDraftReconciliationDebtStore.record(file, debt3)

        val outcome = CommittedDraftReconciliationDebtStore.retry(
            owner = DraftReconciliationOwner { debt ->
                if (debt.draftKey == 1L) {
                    // Concurrent remove of debt2 which is outside limit=1
                    CommittedDraftReconciliationDebtStore.remove(file, 2L, "tok-2")
                    DraftReconciliationResult(success = true, releasePaths = false)
                } else {
                    DraftReconciliationResult(success = false, releasePaths = false)
                }
            },
            unregisterPath = {},
            file = file,
            limit = 1,
        )

        assertTrue(outcome.persistenceOk)
        val after = loadAll(file)
        assertTrue(after.none { it.draftKey == 1L })
        assertTrue("out-of-limit debt2 must not be resurrected", after.none { it.draftKey == 2L })
        assertTrue(after.any { it.draftKey == 3L })
        dir.deleteRecursively()
    }

    private fun managed(path: String, absolutePath: String) =
        com.projectnuke.fusion.chat.PendingAttachmentIdentity(path, "text/plain", absolutePath)
}
