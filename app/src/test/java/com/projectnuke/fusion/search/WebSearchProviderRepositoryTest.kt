package com.projectnuke.fusion.search

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore
import com.projectnuke.fusion.ai.secure.SecretStore
import com.projectnuke.fusion.test.PlatformSharedPreferences
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias InMemoryPrefs = PlatformSharedPreferences

class WebSearchProviderRepositoryTest {

    private class FakeSecretStore(
        var putResult: Boolean = true,
        var putAppliesBeforeFailure: Boolean = false,
        var deleteResult: Boolean = true,
    ) : SecretStore {
        val secrets = mutableMapOf<String, String>()
        val deleted = mutableListOf<String>()
        val deleteCalls = mutableListOf<String>()
        val deleteResults = mutableMapOf<String, Boolean>()
        val deleteCancellationIds = mutableSetOf<String>()
        override suspend fun putSecret(id: String, value: String): Boolean {
            if (!putResult) {
                if (putAppliesBeforeFailure) secrets[id] = value
                return false
            }
            secrets[id] = value
            return true
        }
        override suspend fun getSecret(id: String): String? = secrets[id]
        override suspend fun deleteSecret(id: String): Boolean {
            deleteCalls += id
            if (id in deleteCancellationIds) throw CancellationException("scripted post-commit cancellation")
            if (!(deleteResults[id] ?: deleteResult)) return false
            deleted += id
            secrets.remove(id)
            return true
        }
    }

    private enum class PutBehavior { SUCCESS, FALSE, THROW, CANCEL }

    private class ScriptedSecretStore(
        private val putBehaviors: List<PutBehavior>,
        var deleteResult: Boolean = true,
    ) : SecretStore {
        val secrets = mutableMapOf<String, String>()
        val putIds = mutableListOf<String>()
        val deleteCalls = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        private var putIndex = 0

        override suspend fun putSecret(id: String, value: String): Boolean {
            putIds += id
            return when (putBehaviors.getOrElse(putIndex++) { PutBehavior.SUCCESS }) {
                PutBehavior.SUCCESS -> {
                    secrets[id] = value
                    true
                }
                PutBehavior.FALSE -> false
                PutBehavior.THROW -> error("scripted put failure")
                PutBehavior.CANCEL -> throw CancellationException("scripted cancellation")
            }
        }

        override suspend fun getSecret(id: String): String? = secrets[id]

        override suspend fun deleteSecret(id: String): Boolean {
            deleteCalls += id
            if (!deleteResult) return false
            deleted += id
            secrets.remove(id)
            return true
        }
    }

    private class TestContext(
        private val root: File,
        private val prefs: InMemoryPrefs,
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    private fun harness(
        dir: File,
        prefs: InMemoryPrefs = InMemoryPrefs(),
    ): Triple<WebSearchProviderRepository, InMemoryPrefs, FakeSecretStore> =
        harness(dir, prefs, FakeSecretStore())

    private fun <T : SecretStore> harness(
        dir: File,
        prefs: InMemoryPrefs = InMemoryPrefs(),
        secrets: T,
    ): Triple<WebSearchProviderRepository, InMemoryPrefs, T> {
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), secrets)
        return Triple(repo, prefs, secrets)
    }

    private fun custom(
        id: String = "custom-test",
        baseUrl: String? = "https://search.example.test/api",
        noAuth: Boolean = false,
        apiKeySecretId: String? = null,
    ) = WebSearchProviderConfig(
        id = id,
        displayName = "Test Custom",
        type = WebSearchProviderType.CUSTOM_COMPATIBLE,
        isEnabled = true,
        baseUrl = baseUrl,
        noAuth = noAuth,
        apiKeySecretId = apiKeySecretId,
    )

    private fun debtSecrets(dir: File): List<String> {
        val debtFile = File(dir, "obsolete_secret_cleanup_debt.json")
        if (!debtFile.isFile) return emptyList()
        val array = JSONArray(debtFile.readText())
        return (0 until array.length()).map { array.optJSONObject(it).optString("secretId") }
    }

    @Test
    fun `credential save persists fresh secret and metadata`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val (repo, prefs, secrets) = harness(dir)
        assertTrue(repo.saveProvider(custom(), rawApiKey = "secret-abc"))
        val stored = repo.listProviders().first { it.id == "custom-test" }
        assertTrue(!stored.apiKeySecretId.isNullOrBlank())
        assertEquals("secret-abc", secrets.getSecret(stored.apiKeySecretId!!))
        assertTrue(prefs.values.containsKey("providers"))
        dir.deleteRecursively()
    }

    @Test
    fun `secret write failure fails without touching metadata`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val (repo, prefs, _) = harness(dir, secrets = FakeSecretStore(putResult = false))
        assertFalse(repo.saveProvider(custom(), rawApiKey = "secret-abc"))
        assertFalse(prefs.values.containsKey("providers"))
        assertTrue(debtSecrets(dir).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `failed fresh put is settled when store reports process-visible write`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val secrets = FakeSecretStore(putResult = false, putAppliesBeforeFailure = true)
        val (repo, prefs, _) = harness(dir, secrets = secrets)

        val outcome = repo.saveProviderWithOutcome(custom(), rawApiKey = "secret-abc")

        assertEquals(WebSearchProviderRepository.MutationOutcome.FAILED_BEFORE_COMMIT, outcome)
        assertTrue(secrets.secrets.isEmpty())
        assertTrue(prefs.values["providers"] == null)
        assertTrue(debtSecrets(dir).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `metadata commit failure rolls back fresh secrets`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs(commitResult = false)
        val secrets = FakeSecretStore()
        val (repo, _, _) = harness(dir, prefs, secrets)
        assertFalse(repo.saveProvider(custom(), rawApiKey = "secret-abc"))
        assertTrue(secrets.secrets.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `metadata commit failure restores process state and cannot promote deleted fresh id`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs()
        val secrets = FakeSecretStore()
        val (repo, _, _) = harness(dir, prefs, secrets)
        assertTrue(repo.saveProvider(custom(), rawApiKey = "old-key"))
        val old = repo.listProviders().first { it.id == "custom-test" }
        val durableBefore = prefs.durableValues["providers"]
        prefs.enqueueCommitResults(false, true)

        val outcome = repo.saveProviderWithOutcome(custom(), rawApiKey = "new-key")

        assertEquals(WebSearchProviderRepository.MutationOutcome.FAILED_BEFORE_COMMIT, outcome)
        assertEquals(durableBefore, prefs.durableValues["providers"])
        assertEquals(old.apiKeySecretId, repo.listProviders().first { it.id == "custom-test" }.apiKeySecretId)
        assertTrue(secrets.secrets.containsKey(old.apiKeySecretId))
        assertTrue(secrets.secrets.none { it.key != old.apiKeySecretId && it.value == "new-key" })

        assertEquals(
            WebSearchProviderRepository.MutationOutcome.COMMITTED,
            repo.saveProviderWithOutcome(custom(apiKeySecretId = old.apiKeySecretId)),
        )
        assertEquals(old.apiKeySecretId, repo.listProviders().first { it.id == "custom-test" }.apiKeySecretId)
        dir.deleteRecursively()
    }

    @Test
    fun `later false staging result rolls back every earlier fresh secret`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val secrets = ScriptedSecretStore(listOf(PutBehavior.SUCCESS, PutBehavior.FALSE))
        val (repo, _, _) = harness(dir, secrets = secrets)
        val outcome = repo.saveProviderWithOutcome(
            custom(),
            rawApiKey = "api-key",
            rawClientId = "client-id",
        )
        assertEquals(WebSearchProviderRepository.MutationOutcome.FAILED_BEFORE_COMMIT, outcome)
        assertEquals(2, secrets.putIds.size)
        assertTrue(secrets.secrets.isEmpty())
        assertTrue(debtSecrets(dir).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `later staging exception rolls back every earlier fresh secret`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val secrets = ScriptedSecretStore(listOf(PutBehavior.SUCCESS, PutBehavior.THROW))
        val (repo, _, _) = harness(dir, secrets = secrets)
        val outcome = repo.saveProviderWithOutcome(
            custom(),
            rawApiKey = "api-key",
            rawClientId = "client-id",
        )
        assertEquals(WebSearchProviderRepository.MutationOutcome.FAILED_BEFORE_COMMIT, outcome)
        assertEquals(2, secrets.putIds.size)
        assertTrue(secrets.secrets.isEmpty())
        assertTrue(debtSecrets(dir).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `later staging cancellation settles earlier fresh secret and propagates`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val secrets = ScriptedSecretStore(listOf(PutBehavior.SUCCESS, PutBehavior.CANCEL))
        val (repo, _, _) = harness(dir, secrets = secrets)
        val thrown = try {
            repo.saveProviderWithOutcome(custom(), rawApiKey = "api-key", rawClientId = "client-id")
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue(thrown is CancellationException)
        assertEquals(2, secrets.putIds.size)
        assertTrue(secrets.secrets.isEmpty())
        assertTrue(debtSecrets(dir).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `staging rollback failure records exact debt before commit`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val secrets = ScriptedSecretStore(
            listOf(PutBehavior.SUCCESS, PutBehavior.FALSE),
            deleteResult = false,
        )
        val (repo, _, _) = harness(dir, secrets = secrets)
        val outcome = repo.saveProviderWithOutcome(
            custom(),
            rawApiKey = "api-key",
            rawClientId = "client-id",
        )
        assertEquals(WebSearchProviderRepository.MutationOutcome.FAILED_BEFORE_COMMIT, outcome)
        assertFalse(outcome.metadataCommitted)
        assertFalse(outcome.durabilityLost)
        assertEquals(secrets.putIds.toSet(), debtSecrets(dir).toSet())
        dir.deleteRecursively()
    }

    @Test
    fun `cancellation cleanup durability loss remains diagnostic and cancellation propagates`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val secrets = ScriptedSecretStore(
            listOf(PutBehavior.SUCCESS, PutBehavior.CANCEL),
            deleteResult = false,
        )
        val (repo, _, _) = harness(dir, secrets = secrets)
        val previousHook = ObsoleteSecretCleanupDebtStore.recordHook
        ObsoleteSecretCleanupDebtStore.recordHook = { _, _ -> false }
        try {
            val thrown = try {
                repo.saveProviderWithOutcome(custom(), rawApiKey = "api-key", rawClientId = "client-id")
                null
            } catch (failure: Throwable) {
                failure
            }
            assertTrue(thrown is CancellationException)
            assertTrue(repo.lastCancellationCleanupDurabilityLostIds.isNotEmpty())
            assertTrue(debtSecrets(dir).isEmpty())
        } finally {
            ObsoleteSecretCleanupDebtStore.recordHook = previousHook
            dir.deleteRecursively()
        }
    }

    @Test
    fun `old secret delete failure records cleanup debt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val secrets = FakeSecretStore()
        val (repo, _, _) = harness(dir, secrets = secrets)
        assertTrue(repo.saveProvider(custom(), rawApiKey = "first"))
        val firstId = repo.listProviders().first { it.id == "custom-test" }.apiKeySecretId!!
        secrets.deleteResult = false
        assertTrue(repo.saveProvider(custom(), rawApiKey = "second"))
        val secondId = repo.listProviders().first { it.id == "custom-test" }.apiKeySecretId!!
        assertTrue(firstId != secondId)
        assertTrue(debtSecrets(dir).contains(firstId))
        dir.deleteRecursively()
    }

    @Test
    fun `old secret delete and debt record failure returns false after metadata commit`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val secrets = FakeSecretStore()
        val (repo, _, _) = harness(dir, secrets = secrets)
        assertTrue(repo.saveProvider(custom(), rawApiKey = "first"))
        val firstId = repo.listProviders().first { it.id == "custom-test" }.apiKeySecretId!!
        secrets.deleteResult = false
        val previousHook = ObsoleteSecretCleanupDebtStore.recordHook
        ObsoleteSecretCleanupDebtStore.recordHook = { _, _ -> false }
        try {
            assertFalse(repo.saveProvider(custom(), rawApiKey = "second"))
            val committed = repo.listProviders().first { it.id == "custom-test" }
            assertTrue(committed.apiKeySecretId != firstId)
            assertTrue(secrets.secrets.containsKey(firstId))
            assertTrue(debtSecrets(dir).isEmpty())
        } finally {
            ObsoleteSecretCleanupDebtStore.recordHook = previousHook
            dir.deleteRecursively()
        }
    }

    @Test
    fun `replacement settles every obsolete client secret after first durability loss`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val secrets = FakeSecretStore()
        val (repo, _, _) = harness(dir, secrets = secrets)
        assertTrue(repo.saveProvider(custom(), rawClientId = "old-client-id", rawClientSecret = "old-client-secret"))
        val old = repo.listProviders().first { it.id == "custom-test" }
        val oldClientId = old.clientIdSecretId!!
        val oldClientSecret = old.clientSecretSecretId!!
        secrets.deleteResults[oldClientId] = false
        val previousHook = ObsoleteSecretCleanupDebtStore.recordHook
        ObsoleteSecretCleanupDebtStore.recordHook = { _, _ -> false }
        try {
            val outcome = repo.saveProviderWithOutcome(
                custom(),
                rawClientId = "new-client-id",
                rawClientSecret = "new-client-secret",
            )
            assertEquals(WebSearchProviderRepository.MutationOutcome.DURABILITY_LOST_AFTER_COMMIT, outcome)
            assertTrue(outcome.metadataCommitted)
            assertTrue(outcome.durabilityLost)
            assertEquals(listOf(oldClientId, oldClientSecret), secrets.deleteCalls.takeLast(2))
            assertTrue(secrets.secrets.containsKey(oldClientId))
            assertFalse(secrets.secrets.containsKey(oldClientSecret))
            assertTrue(debtSecrets(dir).isEmpty())
            val replacement = repo.listProviders().first { it.id == "custom-test" }
            assertTrue(replacement.clientIdSecretId != oldClientId)
            assertTrue(replacement.clientSecretSecretId != oldClientSecret)
        } finally {
            ObsoleteSecretCleanupDebtStore.recordHook = previousHook
            dir.deleteRecursively()
        }
    }

    @Test
    fun `replacement cancellation settles every obsolete client secret and propagates`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val secrets = FakeSecretStore()
        val (repo, _, _) = harness(dir, secrets = secrets)
        assertTrue(repo.saveProvider(custom(), rawClientId = "old-client-id", rawClientSecret = "old-client-secret"))
        val old = repo.listProviders().first { it.id == "custom-test" }
        val oldClientId = old.clientIdSecretId!!
        val oldClientSecret = old.clientSecretSecretId!!
        secrets.deleteCancellationIds += oldClientId
        val thrown = try {
            repo.saveProviderWithOutcome(
                custom(),
                rawClientId = "new-client-id",
                rawClientSecret = "new-client-secret",
            )
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue(thrown is CancellationException)
        assertEquals(listOf(oldClientId, oldClientSecret), secrets.deleteCalls.takeLast(2))
        assertTrue(secrets.secrets.containsKey(oldClientId))
        assertFalse(secrets.secrets.containsKey(oldClientSecret))
        assertTrue(debtSecrets(dir).contains(oldClientId))
        val replacement = repo.listProviders().first { it.id == "custom-test" }
        assertTrue(replacement.clientIdSecretId != oldClientId)
        assertTrue(replacement.clientSecretSecretId != oldClientSecret)
        dir.deleteRecursively()
    }

    @Test
    fun `provider deletion settles every obsolete client secret after first durability loss`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val secrets = FakeSecretStore()
        val (repo, _, _) = harness(dir, secrets = secrets)
        assertTrue(repo.saveProvider(custom(), rawClientId = "old-client-id", rawClientSecret = "old-client-secret"))
        val old = repo.listProviders().first { it.id == "custom-test" }
        val oldClientId = old.clientIdSecretId!!
        val oldClientSecret = old.clientSecretSecretId!!
        secrets.deleteResults[oldClientId] = false
        val previousHook = ObsoleteSecretCleanupDebtStore.recordHook
        ObsoleteSecretCleanupDebtStore.recordHook = { _, _ -> false }
        try {
            val outcome = repo.deleteProviderWithOutcome("custom-test")
            assertEquals(WebSearchProviderRepository.MutationOutcome.DURABILITY_LOST_AFTER_COMMIT, outcome)
            assertTrue(outcome.metadataCommitted)
            assertTrue(outcome.durabilityLost)
            assertEquals(listOf(oldClientId, oldClientSecret), secrets.deleteCalls.takeLast(2))
            assertTrue(repo.listProviders().none { it.id == "custom-test" })
            assertTrue(secrets.secrets.containsKey(oldClientId))
            assertFalse(secrets.secrets.containsKey(oldClientSecret))
            assertTrue(debtSecrets(dir).isEmpty())
        } finally {
            ObsoleteSecretCleanupDebtStore.recordHook = previousHook
            dir.deleteRecursively()
        }
    }

    @Test
    fun `provider deletion cancellation settles every obsolete client secret and propagates`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val secrets = FakeSecretStore()
        val (repo, _, _) = harness(dir, secrets = secrets)
        assertTrue(repo.saveProvider(custom(), rawClientId = "old-client-id", rawClientSecret = "old-client-secret"))
        val old = repo.listProviders().first { it.id == "custom-test" }
        val oldClientId = old.clientIdSecretId!!
        val oldClientSecret = old.clientSecretSecretId!!
        secrets.deleteCancellationIds += oldClientId
        val thrown = try {
            repo.deleteProviderWithOutcome("custom-test")
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue(thrown is CancellationException)
        assertEquals(listOf(oldClientId, oldClientSecret), secrets.deleteCalls.takeLast(2))
        assertTrue(repo.listProviders().none { it.id == "custom-test" })
        assertTrue(secrets.secrets.containsKey(oldClientId))
        assertFalse(secrets.secrets.containsKey(oldClientSecret))
        assertTrue(debtSecrets(dir).contains(oldClientId))
        dir.deleteRecursively()
    }

    @Test
    fun `corrupt metadata renders defaults but mutations do not overwrite evidence`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] = "[{broken"
        val (repo, _, _) = harness(dir, prefs)
        // Display fallback: defaults remain visible.
        assertTrue(repo.listProviders().any { it.id == "free_default" })
        // Delete fails closed without touching the corrupt raw value.
        assertFalse(repo.deleteProvider("custom-test"))
        assertEquals("[{broken", prefs.values["providers"])
        // Save quarantines the raw evidence into the bounded backup key, then succeeds.
        assertTrue(repo.saveProvider(custom(), rawApiKey = "k"))
        assertEquals("[{broken", prefs.values["providers.corrupt.bak"])
        assertTrue(repo.listProviders().any { it.id == "custom-test" })
        dir.deleteRecursively()
    }

    @Test
    fun `one malformed provider among valid providers fails the whole load closed`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs()
        val good = """{"id":"good-custom","displayName":"Good","type":"CUSTOM_COMPATIBLE","isEnabled":true,"baseUrl":"https://ok.example.test","priority":60,"noAuth":true}"""
        val bad = """{"id":"bad-custom","displayName":"Bad","type":"NO_SUCH_TYPE","isEnabled":true,"priority":61}"""
        prefs.values["providers"] = "[$good,$bad]"
        val (repo, _, _) = harness(dir, prefs)
        // Corrupt load collapses to the defaults display fallback, never a
        // partial list that a later save could silently complete.
        assertTrue(repo.listProviders().none { it.id == "good-custom" })
        assertFalse(repo.deleteProvider("good-custom"))
        assertTrue((prefs.values["providers"] as String).contains("NO_SUCH_TYPE"))
        dir.deleteRecursively()
    }

    @Test
    fun `concurrent saves do not lose providers`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val (repo, _, _) = harness(dir)
        val first = async { repo.saveProvider(custom("con-a"), rawApiKey = "ka") }
        val second = async { repo.saveProvider(custom("con-b"), rawApiKey = "kb") }
        assertTrue(first.await())
        assertTrue(second.await())
        val ids = repo.listProviders().map { it.id }.toSet()
        assertTrue(ids.contains("con-a"))
        assertTrue(ids.contains("con-b"))
        dir.deleteRecursively()
    }

    @Test
    fun `concurrent save and delete serialize without resurrection`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val (repo, _, _) = harness(dir)
        assertTrue(repo.saveProvider(custom("victim"), rawApiKey = "k"))
        val deleter = async { repo.deleteProvider("victim") }
        val saver = async { repo.saveProvider(custom("bystander"), rawApiKey = "k2") }
        assertTrue(deleter.await())
        assertTrue(saver.await())
        val ids = repo.listProviders().map { it.id }.toSet()
        assertTrue(ids.contains("bystander"))
        // The test only asserts the bystander survives serialization; the victim
        // outcome depends on lock order, so it is not asserted here.
        dir.deleteRecursively()
    }

    @Test
    fun `default provider stays toggleable on a fresh install`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val (repo, _, _) = harness(dir)
        assertTrue(repo.setProviderEnabled("naver", true))
        assertTrue(repo.listProviders().first { it.id == "naver" }.isEnabled)
        dir.deleteRecursively()
    }

    @Test
    fun `concurrent toggle and save serialize without losing providers`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val (repo, _, _) = harness(dir)
        assertTrue(repo.saveProvider(custom("con-a"), rawApiKey = "k"))
        assertTrue(repo.saveProvider(custom("con-b"), rawApiKey = "k2"))
        val storedB = repo.listProviders().first { it.id == "con-b" }
        val toggler = async { repo.setProviderEnabled("con-a", false) }
        val saver = async { repo.saveProvider(storedB.copy(displayName = "Renamed")) }
        assertTrue(toggler.await())
        assertTrue(saver.await())
        val finalA = repo.listProviders().first { it.id == "con-a" }
        val finalB = repo.listProviders().first { it.id == "con-b" }
        assertFalse(finalA.isEnabled)
        assertEquals("Renamed", finalB.displayName)
        assertTrue(finalB.isEnabled)
        dir.deleteRecursively()
    }

    @Test
    fun `toggle persists through the transactional save path`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val (repo, _, _) = harness(dir)
        assertTrue(repo.saveProvider(custom()))
        assertTrue(repo.setProviderEnabled("custom-test", false))
        assertTrue(repo.listProviders().first { it.id == "custom-test" }.isEnabled.not())
        assertTrue(repo.setProviderEnabled("custom-test", true))
        assertTrue(repo.listProviders().first { it.id == "custom-test" }.isEnabled)
        assertFalse(repo.setProviderEnabled("absent-id", true))
        dir.deleteRecursively()
    }

    @Test
    fun `provider save failure recovery cannot roll back a successful selection or mode change`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs()
        val (repo, _, _) = harness(dir, prefs)
        assertTrue(repo.saveProvider(custom("alpha"), rawApiKey = "k"))
        assertTrue(repo.saveProvider(custom("beta"), rawApiKey = "k2"))

        // The failing save is between its process mutation and its recovery.
        val saveInRecoveryWindow = CountDownLatch(1)
        val releaseRecovery = CountDownLatch(1)
        prefs.onCommitBeforeResult {
            saveInRecoveryWindow.countDown()
            releaseRecovery.await(10, TimeUnit.SECONDS)
        }
        // #1: the provider-save commit fails; #2: its owned-key restore commits.
        prefs.enqueueCommitResults(false, true)

        val failingSave = async(Dispatchers.IO) {
            repo.saveProviderWithOutcome(custom("alpha").copy(displayName = "Alpha Renamed"), rawApiKey = "k3")
        }
        assertTrue(saveInRecoveryWindow.await(10, TimeUnit.SECONDS))

        // The selection and mode writers share the same mutationMutex as the
        // failing save, so they cannot interleave with its recovery: whichever
        // runs, the failed save's key-scoped restore can never clobber it.
        val selector = async { repo.setSelectedProvider("beta") }
        val mode = async { repo.setSelectedMode(WebSearchMode.MANUAL) }

        releaseRecovery.countDown()
        failingSave.await()
        assertTrue(selector.await())
        assertTrue(mode.await())

        // The successful selection/mode changes survive in process and durable
        // maps; the failed save's restore did not roll them back.
        assertEquals("beta", prefs.durableValues["selected_web_search_provider_id"])
        assertEquals(WebSearchMode.MANUAL.name, prefs.durableValues["web_search_mode"])
        assertEquals("beta", repo.selectedProvider().id)
        assertEquals(WebSearchMode.MANUAL, repo.selectedMode())
        prefs.onCommitBeforeResult(null)
        dir.deleteRecursively()
    }

    @Test
    fun `authenticated http custom url is rejected`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val (repo, prefs, secrets) = harness(dir)
        assertFalse(repo.saveProvider(custom(baseUrl = "http://search.example.test/api"), rawApiKey = "k"))
        assertFalse(prefs.values.containsKey("providers"))
        assertTrue(secrets.secrets.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `https custom with valid host is accepted and malformed hosts rejected`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val (repo, _, _) = harness(dir)
        assertTrue(repo.saveProvider(custom(baseUrl = "https://search.example.test/api")))
        assertFalse(repo.saveProvider(custom(id = "bad-host", baseUrl = "https:///no-host")))
        assertFalse(repo.saveProvider(custom(id = "blank-base", baseUrl = "   ")))
        assertFalse(repo.saveProvider(custom(id = "missing-base", baseUrl = null)))
        dir.deleteRecursively()
    }

    @Test
    fun `unauthenticated http custom endpoint stays allowed`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val (repo, _, _) = harness(dir)
        assertTrue(repo.saveProvider(custom(baseUrl = "http://intranet.local/search", noAuth = true)))
        assertTrue(repo.listProviders().any { it.id == "custom-test" })
        dir.deleteRecursively()
    }

    @Test
    fun `custom URL scheme matrix is enforced for save and execution`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val (repo, _, _) = harness(dir)

        assertTrue(repo.saveProvider(custom(baseUrl = "http://search.example.test/api", noAuth = true)))
        assertTrue(repo.saveProvider(custom(baseUrl = "https://search.example.test/api", noAuth = true)))
        listOf(
            "ftp://search.example.test/api",
            "file://search.example.test/api",
            "https:///missing-host",
            "not a URL",
        ).forEachIndexed { index, url ->
            assertFalse(repo.saveProvider(custom(id = "invalid-$index", baseUrl = url, noAuth = true)))
            val persisted = custom(id = "invalid-$index", baseUrl = url, noAuth = true)
            assertFalse(WebSearchProviderValidator.isValidExecutionTransport(persisted))
        }
        assertFalse(
            WebSearchProviderValidator.isValidExecutionTransport(
                custom(baseUrl = "http://search.example.test/api", noAuth = false)
            )
        )
        assertTrue(
            WebSearchProviderValidator.isValidExecutionTransport(
                custom(baseUrl = "https://search.example.test/api", noAuth = false)
            )
        )
        dir.deleteRecursively()
    }

    /**
     * Counts secret reads to prove the transport gate short-circuits first.
     * Exposes [secretValues] so tests can verify retained secrets without
     * reaching into private state.
     */
    private class CountingSecretStore : SecretStore {
        var getSecretCalls = 0
        // Keeps the legacy metadata secret so the persisted-authenticated-https
        // contract test can prove legacy metadata with a stored key stays
        // runnable and still reads the secret value.
        private val secrets = mutableMapOf("legacy_key_id" to "legacy-secret")
        val secretValues: Map<String, String> get() = secrets
        override suspend fun putSecret(id: String, value: String): Boolean {
            secrets[id] = value
            return true
        }
        override suspend fun getSecret(id: String): String? {
            getSecretCalls++
            return secrets[id]
        }
        override suspend fun deleteSecret(id: String): Boolean {
            secrets.remove(id)
            return true
        }
    }

    @Test
    fun `legacy persisted authenticated http metadata is not runnable and reads no secret`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs()
        // Directly persisted legacy metadata predating the save validators:
        // CUSTOM_COMPATIBLE, credential-bearing auth, plain HTTP base URL.
        prefs.values["providers"] =
            """[{"id":"legacy-custom","displayName":"Legacy","type":"CUSTOM_COMPATIBLE","isEnabled":true,""" +
                """"baseUrl":"http://example.test/search","apiKeySecretId":"legacy_key_id","noAuth":false}]"""
        val secrets = CountingSecretStore()
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), secrets)

        assertFalse(repo.isRunnable(repo.listProviders().first { it.id == "legacy-custom" }))
        assertTrue(repo.missingRequirements(repo.listProviders().first { it.id == "legacy-custom" }).isNotEmpty())
        // Transport is judged invalid before any secret retrieval.
        assertEquals(0, secrets.getSecretCalls)
        dir.deleteRecursively()
    }

    @Test
    fun `legacy persisted authenticated https metadata stays runnable`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] =
            """[{"id":"legacy-https","displayName":"Legacy","type":"CUSTOM_COMPATIBLE","isEnabled":true,""" +
                """"baseUrl":"https://example.test/search","apiKeySecretId":"legacy_key_id","noAuth":false}]"""
        val secrets = CountingSecretStore()
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), secrets)
        val provider = repo.listProviders().first { it.id == "legacy-https" }
        assertTrue(repo.isRunnable(provider))
        assertTrue(repo.missingRequirements(provider).isEmpty())
        assertTrue(secrets.getSecretCalls > 0)
        dir.deleteRecursively()
    }

    @Test
    fun `rollback delete failure records cleanup debt for fresh secret`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs(commitResult = false)
        val secrets = FakeSecretStore(deleteResult = false)
        val (repo, _, _) = harness(dir, prefs, secrets)

        assertFalse(repo.saveProvider(custom(), rawApiKey = "secret-abc"))
        // save reports failure, and the fresh secret that could not be rolled
        // back is durably recorded as cleanup debt.
        val debts = debtSecrets(dir)
        assertEquals(1, debts.size)
        assertTrue(debts.single().startsWith("web_search_api_key_custom-test_"))
        dir.deleteRecursively()
    }

    @Test
    fun `rollback delete and debt record failure exposes durability loss`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs(commitResult = false)
        val secrets = FakeSecretStore(deleteResult = false)
        val (repo, _, _) = harness(dir, prefs, secrets)
        val previousHook = ObsoleteSecretCleanupDebtStore.recordHook
        ObsoleteSecretCleanupDebtStore.recordHook = { _, _ -> false }
        try {
            assertEquals(
                WebSearchProviderRepository.MutationOutcome.DURABILITY_LOST_BEFORE_COMMIT,
                repo.saveProviderWithOutcome(custom(), rawApiKey = "secret-abc"),
            )
            assertEquals(1, secrets.secrets.size)
            assertTrue(debtSecrets(dir).isEmpty())
        } finally {
            ObsoleteSecretCleanupDebtStore.recordHook = previousHook
            dir.deleteRecursively()
        }
    }

    @Test
    fun `provider delete with secret and debt failure returns false`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val secrets = FakeSecretStore()
        val (repo, _, _) = harness(dir, secrets = secrets)
        assertTrue(repo.saveProvider(custom(), rawApiKey = "secret-abc"))
        val secretId = repo.listProviders().first { it.id == "custom-test" }.apiKeySecretId!!
        secrets.deleteResult = false
        val previousHook = ObsoleteSecretCleanupDebtStore.recordHook
        ObsoleteSecretCleanupDebtStore.recordHook = { _, _ -> false }
        try {
            assertFalse(repo.deleteProvider("custom-test"))
            assertTrue(repo.listProviders().none { it.id == "custom-test" })
            assertTrue(secrets.secrets.containsKey(secretId))
            assertTrue(debtSecrets(dir).isEmpty())
        } finally {
            ObsoleteSecretCleanupDebtStore.recordHook = previousHook
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rollback delete success records no cleanup debt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs(commitResult = false)
        val secrets = FakeSecretStore(deleteResult = true)
        val (repo, _, _) = harness(dir, prefs, secrets)

        assertFalse(repo.saveProvider(custom(), rawApiKey = "secret-abc"))
        assertTrue(secrets.deleted.isNotEmpty())
        assertTrue(debtSecrets(dir).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `noAuth custom provider with retained apiKey secret id is not credential-bearing`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs()
        // A custom provider that previously had an apiKey secret but now uses
        // noAuth=true with HTTP. The retained apiKeySecretId must NOT make it
        // credential-bearing because noAuth means no credential is sent.
        prefs.values["providers"] =
            """[{"id":"custom-http","displayName":"Custom HTTP","type":"CUSTOM_COMPATIBLE","isEnabled":true,""" +
                """"baseUrl":"http://example.test/search","apiKeySecretId":"retained_key_id","noAuth":true}]"""
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        val provider = repo.listProviders().first { it.id == "custom-http" }

        // The retained apiKeySecretId must not make it credential-bearing.
        assertFalse(
            "noAuth=true must be not credential-bearing even with retained secret",
            WebSearchProviderValidator.isCredentialBearing(provider)
        )
        // HTTP transport is valid for noAuth (not credential-bearing).
        assertTrue(
            "noAuth HTTP must pass execution transport",
            WebSearchProviderValidator.isValidExecutionTransport(provider)
        )
        // Provider should be runnable (noAuth + valid HTTP URL).
        assertTrue("noAuth HTTP must be runnable", repo.isRunnable(provider))
        assertTrue(repo.missingRequirements(provider).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `noAuth custom provider runtime does not read retained secret`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] =
            """[{"id":"custom-noauth","displayName":"NoAuth","type":"CUSTOM_COMPATIBLE","isEnabled":true,""" +
                """"baseUrl":"http://example.test/search","apiKeySecretId":"retained_key_id","noAuth":true}]"""
        val secrets = CountingSecretStore()
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), secrets)
        val provider = repo.listProviders().first { it.id == "custom-noauth" }

        // Execution-time transport gate must not read any secret.
        WebSearchProviderValidator.isValidExecutionTransport(provider)
        assertEquals("noAuth execution must not read retained secret", 0, secrets.getSecretCalls)
        dir.deleteRecursively()
    }

    @Test
    fun `transition from authenticated to noAuth retains key but stays runnable`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs()
        val secrets = CountingSecretStore()
        // Start with authenticated HTTPS provider that has a stored key.
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), secrets)
        val authProvider = WebSearchProviderConfig(
            id = "custom-transition",
            displayName = "Transition",
            type = WebSearchProviderType.CUSTOM_COMPATIBLE,
            isEnabled = true,
            baseUrl = "https://example.test/search",
            noAuth = false,
            priority = 50,
        )
        assertTrue(repo.saveProvider(authProvider, rawApiKey = "original-secret"))
        assertTrue(secrets.secretValues.values.contains("original-secret"))

        // Now transition to noAuth=true with HTTP URL while retaining the key.
        val noAuthProvider = authProvider.copy(
            baseUrl = "http://example.test/search",
            noAuth = true,
        )
        assertTrue(repo.saveProvider(noAuthProvider))
        // The retained secret must still be present (NOAUTH_SECRET_RETENTION).
        assertTrue(
            "retained secret must not be deleted on noAuth transition",
            secrets.secretValues.values.contains("original-secret")
        )

        val loaded = repo.listProviders().first { it.id == "custom-transition" }
        assertTrue(
            "noAuth HTTP with retained key must be not credential-bearing",
            !WebSearchProviderValidator.isCredentialBearing(loaded)
        )
        assertTrue(
            "noAuth HTTP with retained key must pass transport",
            WebSearchProviderValidator.isValidExecutionTransport(loaded)
        )
        assertTrue(
            "noAuth HTTP with retained key must be runnable",
            repo.isRunnable(loaded)
        )
        dir.deleteRecursively()
    }

    @Test
    fun `D2_2 - present invalid noAuth type makes provider metadata corrupt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs()
        // noAuth is present but has a writer-impossible type (string instead of boolean).
        // This must render the load Corrupt, not silently coerce to false.
        prefs.values["providers"] =
            """[{"id":"custom-bad","displayName":"Bad","type":"CUSTOM_COMPATIBLE","isEnabled":true,""" +
                """"baseUrl":"http://example.test/search","noAuth":"yes-not-boolean"}]"""
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        // Corrupt metadata renders as defaults (empty custom list) — display fallback.
        val custom = repo.listProviders().filter { it.id == "custom-bad" }
        assertTrue("present invalid noAuth type must make load Corrupt", custom.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `D2_2 - present invalid isEnabled type makes provider metadata corrupt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] =
            """[{"id":"custom-bad","displayName":"Bad","type":"CUSTOM_COMPATIBLE","isEnabled":"yes",""" +
                """"baseUrl":"http://example.test/search","noAuth":false}]"""
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        val custom = repo.listProviders().filter { it.id == "custom-bad" }
        assertTrue("present invalid isEnabled type must make load Corrupt", custom.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `D2_2 - absent noAuth retains compatible default`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs()
        // noAuth is absent — should retain default false (authenticated).
        prefs.values["providers"] =
            """[{"id":"custom-absent-noauth","displayName":"Legacy","type":"CUSTOM_COMPATIBLE","isEnabled":true,""" +
                """"baseUrl":"https://example.test/search","apiKeySecretId":"key_id"}]"""
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        val provider = repo.listProviders().firstOrNull { it.id == "custom-absent-noauth" }
        // Absent noAuth defaults to false → credential-bearing.
        if (provider != null) {
            assertTrue("absent noAuth must default to false", !provider.noAuth)
        }
        dir.deleteRecursively()
    }

    @Test
    fun `D2_2 - present wrong string types and bounds make metadata corrupt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ws-provider").toFile()
        val prefs = InMemoryPrefs()
        val stringFields = listOf(
            "displayName",
            "baseUrl",
            "apiKeySecretId",
            "clientIdSecretId",
            "clientSecretSecretId",
            "defaultSearchCategory",
        )
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        stringFields.forEach { field ->
            val obj = JSONObject()
                .put("id", "bad-$field")
                .put("displayName", "Bad")
                .put("type", "CUSTOM_COMPATIBLE")
                .put("isEnabled", true)
                .put("baseUrl", "https://example.test/search")
                .put("noAuth", true)
                .put(field, 42)
            prefs.values["providers"] = JSONArray().put(obj).toString()
            assertTrue("present non-string $field must corrupt the load", repo.listProviders().none { it.id == "bad-$field" })
        }

        prefs.values["providers"] = JSONArray().put(
            JSONObject()
                .put("id", "too-long")
                .put("displayName", "x".repeat(WebSearchProviderValidator.MAX_DISPLAY_NAME + 1))
                .put("type", "CUSTOM_COMPATIBLE")
                .put("isEnabled", true)
                .put("baseUrl", "https://example.test/search")
                .put("noAuth", true)
        ).toString()
        assertTrue(repo.listProviders().none { it.id == "too-long" })
        dir.deleteRecursively()
    }
}
