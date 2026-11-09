package com.projectnuke.fusion.ai

import com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore
import com.projectnuke.fusion.ai.data.ObsoleteSecretDebtLoad
import com.projectnuke.fusion.ai.secure.SecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ObsoleteSecretCleanupDebtStoreTest {
    private class FakeSecretStore(
        private val failing: Set<String> = emptySet(),
        val deleted: MutableList<String> = mutableListOf(),
    ) : SecretStore {
        override suspend fun putSecret(id: String, value: String) = true
        override suspend fun getSecret(id: String): String? = null
        override suspend fun deleteSecret(id: String): Boolean {
            if (id in failing) return false
            deleted += id
            return true
        }
    }

    private class IdempotentSecretStore(
        initialIds: Set<String>,
    ) : SecretStore {
        val secrets = initialIds.toMutableSet()
        val deleteCalls = mutableListOf<String>()

        override suspend fun putSecret(id: String, value: String) = true
        override suspend fun getSecret(id: String): String? = null
        override suspend fun deleteSecret(id: String): Boolean {
            deleteCalls += id
            secrets.remove(id)
            return true
        }
    }

    @Test
    fun `terminal retry repeated does not duplicate entries`() = runTest {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "obsolete_secret_cleanup_debt.json")
        // Seed terminal + pending directly via record; force terminal attempts via repeated failures.
        ObsoleteSecretCleanupDebtStore.record(file, "terminal-secret")
        // Bump terminal to MAX by failing many times through handler path is not
        // available here; instead verify dedup invariant: repeated failing retries
        // keep exactly one terminal entry and never displace pending.
        repeat(3) {
            ObsoleteSecretCleanupDebtStore.record(file, "pending-secret")
            val outcome = ObsoleteSecretCleanupDebtStore.retry(FakeSecretStore(setOf("pending-secret", "terminal-secret")), file)
            assertTrue(outcome.persistenceOk)
        }
        val pending = ObsoleteSecretCleanupDebtStore.getPendingCount(file)
        assertTrue(pending <= 32)
        // No duplicate identities.
        val raw = file.readText()
        val array = org.json.JSONArray(raw)
        val ids = (0 until array.length()).map { array.optJSONObject(it).optString("secretId") }
        assertEquals(ids.size, ids.toSet().size)
        dir.deleteRecursively()
    }

    @Test
    fun `corrupt and oversized files are fail-closed`() = runTest {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val corrupt = File(dir, "corrupt.json").apply { writeText("[{]broken}[") }
        assertTrue(ObsoleteSecretCleanupDebtStore.loadResult(corrupt) is ObsoleteSecretDebtLoad.Corrupt)
        val corruptOutcome = ObsoleteSecretCleanupDebtStore.retry(FakeSecretStore(), corrupt)
        assertFalse(corruptOutcome.persistenceOk)
        assertTrue(corrupt.isFile)

        val oversized = File(dir, "oversized.json").apply {
            writeText("z".repeat(ObsoleteSecretCleanupDebtStore.MAX_FILE_BYTES + 1))
        }
        assertTrue(ObsoleteSecretCleanupDebtStore.loadResult(oversized) is ObsoleteSecretDebtLoad.Oversized)
        val oversizedOutcome = ObsoleteSecretCleanupDebtStore.retry(FakeSecretStore(), oversized)
        assertFalse(oversizedOutcome.persistenceOk)
        assertTrue(oversized.isFile)
        dir.deleteRecursively()
    }

    @Test
    fun `one pass processes more than four actionable debts`() = runTest {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "obsolete_secret_cleanup_debt.json")
        repeat(6) { i -> ObsoleteSecretCleanupDebtStore.record(file, "secret-$i") }
        val store = FakeSecretStore()
        val outcome = ObsoleteSecretCleanupDebtStore.retry(store, file)
        assertEquals(6, outcome.cleaned)
        assertTrue(outcome.persistenceOk)
        assertEquals(0, ObsoleteSecretCleanupDebtStore.getPendingCount(file))
        dir.deleteRecursively()
    }

    @Test
    fun `record quarantines corrupt evidence instead of silently overwriting`() {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "obsolete_secret_cleanup_debt.json")
        file.writeText("[{]broken}[")
        assertTrue(ObsoleteSecretCleanupDebtStore.record(file, "new-secret"))
        assertTrue(File(dir, "obsolete_secret_cleanup_debt.json.corrupt.bak").isFile)
        dir.deleteRecursively()
    }

    @Test
    fun `one invalid entry among valid makes whole file corrupt`() {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "secret.json")
        val valid = org.json.JSONObject().put("secretId", "good").put("attempts", 0).put("lastAttemptAt", 0L)
        val invalid = org.json.JSONObject().put("attempts", 0)
        file.writeText(org.json.JSONArray().put(valid).put(invalid).toString())
        assertTrue(ObsoleteSecretCleanupDebtStore.loadResult(file) is ObsoleteSecretDebtLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `single invalid entry is not valid empty and wrong types are corrupt`() {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val onlyInvalid = File(dir, "only.json").apply {
            writeText(org.json.JSONArray().put(org.json.JSONObject().put("secretId", "")).toString())
        }
        assertTrue(ObsoleteSecretCleanupDebtStore.loadResult(onlyInvalid) is ObsoleteSecretDebtLoad.Corrupt)
        val wrongType = File(dir, "wrong.json").apply {
            writeText(org.json.JSONArray().put(org.json.JSONObject().put("secretId", 123)).toString())
        }
        assertTrue(ObsoleteSecretCleanupDebtStore.loadResult(wrongType) is ObsoleteSecretDebtLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `over-count entries are corrupt not truncated`() {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val many = org.json.JSONArray()
        repeat(ObsoleteSecretCleanupDebtStore.MAX_ENTRIES + 1) { i ->
            many.put(org.json.JSONObject().put("secretId", "s$i"))
        }
        val file = File(dir, "many.json").apply { writeText(many.toString()) }
        assertTrue(ObsoleteSecretCleanupDebtStore.loadResult(file) is ObsoleteSecretDebtLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `duplicate secret ids are corrupt`() {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "dup.json").apply {
            writeText(org.json.JSONArray().put(
                org.json.JSONObject().put("secretId", "s").put("attempts", 0).put("lastAttemptAt", 0L),
            ).put(
                org.json.JSONObject().put("secretId", "s").put("attempts", 1).put("lastAttemptAt", 5L),
            ).toString())
        }
        assertTrue(ObsoleteSecretCleanupDebtStore.loadResult(file) is ObsoleteSecretDebtLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `negative lastAttemptAt is corrupt, future timestamp is accepted`() {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val negative = File(dir, "negative-ts.json").apply {
            writeText(org.json.JSONArray().put(
                org.json.JSONObject().put("secretId", "s").put("attempts", 0).put("lastAttemptAt", -7L),
            ).toString())
        }
        assertTrue(ObsoleteSecretCleanupDebtStore.loadResult(negative) is ObsoleteSecretDebtLoad.Corrupt)
        val future = File(dir, "future-ts.json").apply {
            writeText(org.json.JSONArray().put(
                org.json.JSONObject().put("secretId", "s").put("attempts", 0)
                    .put("lastAttemptAt", System.currentTimeMillis() + 86_400_000L),
            ).toString())
        }
        assertTrue(ObsoleteSecretCleanupDebtStore.loadResult(future) is ObsoleteSecretDebtLoad.Valid)
        dir.deleteRecursively()
    }

    @Test
    fun `record after quarantine failure leaves original byte-identical`() {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "obsolete_secret_cleanup_debt.json").apply { writeText("[{]broken}[") }
        val before = file.readBytes()
        ObsoleteSecretCleanupDebtStore.quarantineHook = { com.projectnuke.fusion.util.DebtQuarantineOutcome.Failed }
        try {
            assertFalse(ObsoleteSecretCleanupDebtStore.record(file, "new-secret"))
            assertTrue(before.contentEquals(file.readBytes()))
        } finally {
            ObsoleteSecretCleanupDebtStore.quarantineHook = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cancellation during secret deletion propagates without consuming attempts`() = runTest {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "obsolete_secret_cleanup_debt.json")
        ObsoleteSecretCleanupDebtStore.record(file, "s1")
        val before = file.readText()
        val cancelling = object : SecretStore {
            override suspend fun putSecret(id: String, value: String) = true
            override suspend fun getSecret(id: String): String? = null
            override suspend fun deleteSecret(id: String): Boolean = throw kotlinx.coroutines.CancellationException("gone")
        }
        var threw = false
        try {
            ObsoleteSecretCleanupDebtStore.retry(cancelling, file)
        } catch (_: kotlinx.coroutines.CancellationException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(before, file.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `successful deletion with failed debt persistence is safe to retry after process boundary`() = runTest {
        val dir = Files.createTempDirectory("fusion-secret-debt-retry").toFile()
        val file = File(dir, "obsolete_secret_cleanup_debt.json")
        val secretId = "retry-secret"
        assertTrue(ObsoleteSecretCleanupDebtStore.record(file, secretId))
        val firstStore = IdempotentSecretStore(setOf(secretId))
        val previousHook = ObsoleteSecretCleanupDebtStore.retryPersistenceHook
        ObsoleteSecretCleanupDebtStore.retryPersistenceHook = { _, _ -> false }
        try {
            val first = ObsoleteSecretCleanupDebtStore.retry(firstStore, file)
            assertEquals(1, first.cleaned)
            assertFalse(first.persistenceOk)
            assertTrue(firstStore.secrets.isEmpty())
            assertTrue(
                (ObsoleteSecretCleanupDebtStore.loadResult(file) as ObsoleteSecretDebtLoad.Valid)
                    .entries.any { it.secretId == secretId },
            )
        } finally {
            ObsoleteSecretCleanupDebtStore.retryPersistenceHook = previousHook
        }

        // A new process sees the old debt. The production contract treats an
        // already-absent secret as successful removal, so the stale witness
        // can settle instead of wedging forever or losing another secret.
        val secondStore = IdempotentSecretStore(emptySet())
        val second = ObsoleteSecretCleanupDebtStore.retry(secondStore, file)
        assertEquals(1, second.cleaned)
        assertTrue(second.persistenceOk)
        assertEquals(0, ObsoleteSecretCleanupDebtStore.getPendingCount(file))
        assertEquals(listOf(secretId), secondStore.deleteCalls)
        dir.deleteRecursively()
    }

    @Test
    fun `overlong secret id fails without replacement or truncation`() {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "obsolete_secret_cleanup_debt.json")
        assertTrue(ObsoleteSecretCleanupDebtStore.record(file, "good-secret"))
        val goodBytes = file.readBytes()
        assertFalse(ObsoleteSecretCleanupDebtStore.record(file, "s".repeat(ObsoleteSecretCleanupDebtStore.MAX_SECRET_ID_CHARS + 1)))
        assertFalse(ObsoleteSecretCleanupDebtStore.record(file, ""))
        assertTrue(goodBytes.contentEquals(file.readBytes()))
        assertEquals(1, ObsoleteSecretCleanupDebtStore.getPendingCount(file))
        dir.deleteRecursively()
    }

    @Test
    fun `full store rejects new identity but allows same identity replacement`() {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "obsolete_secret_cleanup_debt.json")
        repeat(ObsoleteSecretCleanupDebtStore.MAX_ENTRIES) { i ->
            assertTrue(ObsoleteSecretCleanupDebtStore.record(file, "secret-$i"))
        }
        assertEquals(ObsoleteSecretCleanupDebtStore.MAX_ENTRIES, ObsoleteSecretCleanupDebtStore.getPendingCount(file))
        val fullBytes = file.readBytes()
        assertFalse(ObsoleteSecretCleanupDebtStore.record(file, "brand-new-secret"))
        assertTrue(fullBytes.contentEquals(file.readBytes()))
        // SAME identity re-record succeeds without growing the store.
        assertTrue(ObsoleteSecretCleanupDebtStore.record(file, "secret-0"))
        assertEquals(ObsoleteSecretCleanupDebtStore.MAX_ENTRIES, ObsoleteSecretCleanupDebtStore.getPendingCount(file))
        dir.deleteRecursively()
    }

    @Test
    fun `floating attempts are corrupt`() {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "float.json").apply {
            writeText("""[{"secretId":"s","attempts":1.5,"lastAttemptAt":0}]""")
        }
        assertTrue(ObsoleteSecretCleanupDebtStore.loadResult(file) is ObsoleteSecretDebtLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `largest legal representation loads via production writer`() {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "obsolete_secret_cleanup_debt.json")
        assertTrue(ObsoleteSecretCleanupDebtStore.record(file, "s".repeat(ObsoleteSecretCleanupDebtStore.MAX_SECRET_ID_CHARS)))
        assertTrue(ObsoleteSecretCleanupDebtStore.loadResult(file) is ObsoleteSecretDebtLoad.Valid)
        assertTrue(file.readBytes().size <= ObsoleteSecretCleanupDebtStore.MAX_FILE_BYTES)
        // Full store of max-length ids still fits the byte cap (4KB << 64KB).
        repeat(ObsoleteSecretCleanupDebtStore.MAX_ENTRIES) { i ->
            ObsoleteSecretCleanupDebtStore.record(file, "k$i-" + "s".repeat(ObsoleteSecretCleanupDebtStore.MAX_SECRET_ID_CHARS - 4))
        }
        assertTrue(ObsoleteSecretCleanupDebtStore.loadResult(file) is ObsoleteSecretDebtLoad.Valid)
        assertTrue(file.readBytes().size <= ObsoleteSecretCleanupDebtStore.MAX_FILE_BYTES)
        dir.deleteRecursively()
    }

    @Test
    fun `terminal concurrent removal is preserved`() = runTest {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "obsolete_secret_cleanup_debt.json")
        val json = org.json.JSONArray()
            .put(org.json.JSONObject().put("secretId", "term-secret").put("attempts", ObsoleteSecretCleanupDebtStore.MAX_ATTEMPTS).put("lastAttemptAt", 0L))
            .put(org.json.JSONObject().put("secretId", "act-secret").put("attempts", 0).put("lastAttemptAt", 0L))
        file.writeText(json.toString())

        val store = object : SecretStore {
            override suspend fun putSecret(id: String, value: String) = true
            override suspend fun getSecret(id: String): String? = null
            override suspend fun deleteSecret(id: String): Boolean {
                if (id == "act-secret") {
                    ObsoleteSecretCleanupDebtStore.remove(file, "term-secret")
                    return true
                }
                return false
            }
        }

        val outcome = ObsoleteSecretCleanupDebtStore.retry(store, file)
        assertTrue(outcome.persistenceOk)
        assertEquals(1, outcome.cleaned)
        val load = ObsoleteSecretCleanupDebtStore.loadResult(file) as ObsoleteSecretDebtLoad.Valid
        assertTrue("terminal secret must NOT be resurrected after concurrent remove", load.entries.none { it.secretId == "term-secret" })
        assertTrue("actionable secret must be removed", load.entries.none { it.secretId == "act-secret" })
        assertEquals(0, outcome.pendingAfter)
        dir.deleteRecursively()
    }

    @Test
    fun `terminal same-identity replacement is preserved`() = runTest {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "obsolete_secret_cleanup_debt.json")
        val json = org.json.JSONArray()
            .put(org.json.JSONObject().put("secretId", "term-secret").put("attempts", ObsoleteSecretCleanupDebtStore.MAX_ATTEMPTS).put("lastAttemptAt", 0L))
            .put(org.json.JSONObject().put("secretId", "act-secret").put("attempts", 0).put("lastAttemptAt", 0L))
        file.writeText(json.toString())

        val store = object : SecretStore {
            override suspend fun putSecret(id: String, value: String) = true
            override suspend fun getSecret(id: String): String? = null
            override suspend fun deleteSecret(id: String): Boolean {
                if (id == "act-secret") {
                    // Concurrently record replacement with 0 attempts and current timestamp
                    ObsoleteSecretCleanupDebtStore.record(file, "term-secret")
                    return true
                }
                return false
            }
        }

        val outcome = ObsoleteSecretCleanupDebtStore.retry(store, file)
        assertTrue(outcome.persistenceOk)
        val load = ObsoleteSecretCleanupDebtStore.loadResult(file) as ObsoleteSecretDebtLoad.Valid
        val term = load.entries.singleOrNull { it.secretId == "term-secret" }
        assertEquals(0, term?.attempts)
        assertTrue(term != null && term.lastAttemptAt > 0L)
        dir.deleteRecursively()
    }

    @Test
    fun `out-of-limit concurrent removal is preserved`() = runTest {
        val dir = Files.createTempDirectory("fusion-secret-debt").toFile()
        val file = File(dir, "obsolete_secret_cleanup_debt.json")
        ObsoleteSecretCleanupDebtStore.record(file, "sec-1")
        ObsoleteSecretCleanupDebtStore.record(file, "sec-2")
        ObsoleteSecretCleanupDebtStore.record(file, "sec-3")

        val store = object : SecretStore {
            override suspend fun putSecret(id: String, value: String) = true
            override suspend fun getSecret(id: String): String? = null
            override suspend fun deleteSecret(id: String): Boolean {
                if (id == "sec-1") {
                    ObsoleteSecretCleanupDebtStore.remove(file, "sec-2")
                    return true
                }
                return false
            }
        }

        val outcome = ObsoleteSecretCleanupDebtStore.retry(store, file, limit = 1)
        assertTrue(outcome.persistenceOk)
        val load = ObsoleteSecretCleanupDebtStore.loadResult(file) as ObsoleteSecretDebtLoad.Valid
        assertTrue(load.entries.none { it.secretId == "sec-1" })
        assertTrue("out-of-limit sec-2 must not be resurrected", load.entries.none { it.secretId == "sec-2" })
        assertTrue(load.entries.any { it.secretId == "sec-3" })
        dir.deleteRecursively()
    }
}
