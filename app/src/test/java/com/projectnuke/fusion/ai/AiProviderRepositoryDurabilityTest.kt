package com.projectnuke.fusion.ai

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.ai.data.AiProviderRepository
import com.projectnuke.fusion.ai.data.AiProviderMutationOutcome
import com.projectnuke.fusion.ai.data.AiProviderValidator
import com.projectnuke.fusion.ai.data.AiProviderLoad
import com.projectnuke.fusion.ai.data.MAX_PROVIDER_COUNT
import com.projectnuke.fusion.ai.data.MAX_PROVIDER_JSON_BYTES
import com.projectnuke.fusion.ai.data.effectiveProviders
import com.projectnuke.fusion.ai.provider.AiProviderPresets
import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.ReasoningEffortMapping
import com.projectnuke.fusion.ai.model.UserExternalVariant
import com.projectnuke.fusion.ai.model.UserVariantPatch
import com.projectnuke.fusion.ai.data.UserExternalVariantStore
import com.projectnuke.fusion.ai.data.UserVariantDeletionOutcome
import com.projectnuke.fusion.ai.secure.SecretStore
import com.projectnuke.fusion.test.PlatformSharedPreferences
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias InMemoryPrefs = PlatformSharedPreferences

class AiProviderRepositoryDurabilityTest {

    private class FakeSecretStore(
        var putResult: Boolean = true,
        var putAppliesBeforeFailure: Boolean = false,
        var deleteResult: Boolean = true,
        var deleteCancellation: CancellationException? = null,
    ) : SecretStore {
        val secrets = mutableMapOf<String, String>()
        val deleteCalls = mutableListOf<String>()
        val getCalls = mutableListOf<String>()
        override suspend fun putSecret(id: String, value: String): Boolean {
            if (!putResult) {
                if (putAppliesBeforeFailure) secrets[id] = value
                return false
            }
            secrets[id] = value
            return true
        }
        override suspend fun getSecret(id: String): String? {
            getCalls += id
            return secrets[id]
        }
        override suspend fun deleteSecret(id: String): Boolean {
            deleteCalls += id
            deleteCancellation?.let { throw it }
            if (!deleteResult) return false
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

    private fun provider(
        id: String = "custom-test",
        baseUrl: String = "https://api.example.test/v1",
    ) = AiProviderConfig(
        id = id,
        type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
        displayName = "Test Provider",
        baseUrl = baseUrl,
        modelId = "model",
        apiKeySecretId = null,
    )

    private fun userVariant(
        id: String = "user:variant",
        providerId: String = "provider-a",
        modelId: String = "model",
    ) = UserExternalVariant(
        id = id,
        providerId = providerId,
        modelId = modelId,
        label = "Variant",
        patch = UserVariantPatch.ReasoningEffort("high", ReasoningEffortMapping.FLAT_REASONING_EFFORT),
    )

    private fun debtSecrets(dir: File): List<String> {
        val debtFile = File(dir, "obsolete_secret_cleanup_debt.json")
        if (!debtFile.isFile) return emptyList()
        val array = JSONArray(debtFile.readText())
        return (0 until array.length()).map { array.optJSONObject(it).optString("secretId") }
    }

    private fun persistedProvidersJson(count: Int): String = JSONArray().apply {
        repeat(count) { index ->
            put(
                JSONObject()
                    .put("id", "persisted-$index")
                    .put("type", AiProviderType.CUSTOM_OPENAI_COMPATIBLE.name)
                    .put("displayName", "Persisted $index")
                    .put("baseUrl", "https://example.test/$index")
                    .put("modelId", "model-$index"),
            )
        }
    }.toString()

    @Test
    fun `concurrent saves do not lose providers`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), FakeSecretStore())
        val first = async { repo.saveProvider(provider("con-a"), "ka") }
        val second = async { repo.saveProvider(provider("con-b"), "kb") }
        assertTrue(first.await())
        assertTrue(second.await())
        val ids = repo.getProviders().map { it.id }.toSet()
        assertTrue(ids.contains("con-a"))
        assertTrue(ids.contains("con-b"))
        dir.deleteRecursively()
    }

    @Test
    fun `provider metadata failure recovery cannot roll back a concurrent selected-provider change`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        assertTrue(repo.saveProvider(provider("alpha"), "ka"))
        assertTrue(repo.saveProvider(provider("beta"), "kb"))
        assertTrue(repo.setSelectedProvider("alpha"))

        // The failing metadata save is between its process mutation and its
        // recovery, pinned inside the shared mutationMutex.
        val saveInRecoveryWindow = CountDownLatch(1)
        val releaseRecovery = CountDownLatch(1)
        prefs.onCommitBeforeResult {
            saveInRecoveryWindow.countDown()
            releaseRecovery.await(10, TimeUnit.SECONDS)
        }
        prefs.enqueueCommitResults(false, true)

        val failingSave = async(Dispatchers.IO) {
            repo.saveProviderWithOutcome(provider("alpha").copy(displayName = "Alpha Renamed"), "k3")
        }
        assertTrue(saveInRecoveryWindow.await(10, TimeUnit.SECONDS))

        // setSelectedProvider shares mutationMutex with save/delete, so it can
        // never interleave with the failed save's key-scoped recovery.
        val selector = async { repo.setSelectedProvider("beta") }

        releaseRecovery.countDown()
        failingSave.await()
        assertTrue(selector.await())

        // The successful selection change survives in the durable map; the
        // failed metadata save's restore did not roll it back.
        assertEquals("beta", prefs.durableValues["selected_provider_id"])
        assertEquals("beta", prefs.values["selected_provider_id"])
        prefs.onCommitBeforeResult(null)
        dir.deleteRecursively()
    }

    @Test
    fun `corrupt metadata renders defaults and save quarantines before replacing`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] = "[{broken"
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        // Display fallback keeps working.
        assertTrue(repo.getProviders().isNotEmpty())
        // Built-in deletion is rejected before corrupt evidence is touched.
        assertFalse(repo.deleteProvider("openai"))
        assertEquals("[{broken", prefs.values["providers"])
        // Save quarantines the raw evidence, then succeeds.
        assertTrue(repo.saveProvider(provider(), "k"))
        assertEquals("[{broken", prefs.values["providers.corrupt.bak"])
        assertTrue(repo.getProviders().map { it.id }.containsAll(AiProviderPresets.defaults.map { it.id } + "custom-test"))
        dir.deleteRecursively()
    }

    @Test
    fun `effective provider authority uses defaults for missing and empty storage`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val missingPrefs = InMemoryPrefs()
        val missingRepo = AiProviderRepository(TestContext(dir, missingPrefs), FakeSecretStore())
        assertEquals(AiProviderPresets.defaults, missingRepo.getProviders())

        val emptyPrefs = InMemoryPrefs()
        emptyPrefs.values["providers"] = "[]"
        val emptyRepo = AiProviderRepository(TestContext(dir, emptyPrefs), FakeSecretStore())
        assertEquals(AiProviderPresets.defaults, emptyRepo.getProviders())
        assertEquals(
            AiProviderPresets.defaults,
            effectiveProviders(AiProviderLoad.Valid(emptyList())),
        )
        val custom = provider("exact")
        assertEquals(listOf(custom), effectiveProviders(AiProviderLoad.Valid(listOf(custom))))
        dir.deleteRecursively()
    }

    @Test
    fun `saving over an empty provider snapshot preserves every default`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] = "[]"
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())

        assertTrue(repo.saveProvider(provider(), null))
        assertTrue(repo.getProviders().map { it.id }.containsAll(AiProviderPresets.defaults.map { it.id }))
        assertTrue(repo.getProviders().any { it.id == "custom-test" })
        dir.deleteRecursively()
    }

    @Test
    fun `failed corrupt quarantine prevents provider mutation`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs(commitResult = false)
        val corrupt = "[{broken"
        prefs.values["providers"] = corrupt
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())

        assertFalse(repo.saveProvider(provider(), null))
        assertEquals(corrupt, prefs.values["providers"])
        assertFalse(prefs.values.containsKey("providers.corrupt.bak"))
        dir.deleteRecursively()
    }

    @Test
    fun `oversized provider metadata falls back and cannot be quarantined or overwritten`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val oversized = "x".repeat(MAX_PROVIDER_JSON_BYTES + 1)
        prefs.values["providers"] = oversized
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())

        assertTrue(repo.loadForTest() is AiProviderLoad.Oversized)
        assertEquals(AiProviderPresets.defaults, repo.getProviders())
        assertFalse(repo.saveProvider(provider(), null))
        assertEquals(oversized, prefs.values["providers"])
        assertFalse(prefs.values.containsKey("providers.corrupt.bak"))
        dir.deleteRecursively()
    }

    @Test
    fun `provider UTF-8 byte cap is rejected before JSON traversal`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val oversizedByBytes = "é".repeat(MAX_PROVIDER_JSON_BYTES / 2 + 1)
        assertTrue(oversizedByBytes.length <= MAX_PROVIDER_JSON_BYTES)
        prefs.values["providers"] = oversizedByBytes
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())

        assertTrue(repo.loadForTest() is AiProviderLoad.Oversized)
        assertEquals(AiProviderPresets.defaults, repo.getProviders())
        dir.deleteRecursively()
    }

    @Test
    fun `provider count bound accepts 64 and rejects 65 without truncation`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val acceptedPrefs = InMemoryPrefs()
        acceptedPrefs.values["providers"] = persistedProvidersJson(MAX_PROVIDER_COUNT)
        val acceptedRepo = AiProviderRepository(TestContext(dir, acceptedPrefs), FakeSecretStore())
        assertEquals(MAX_PROVIDER_COUNT, (acceptedRepo.loadForTest() as AiProviderLoad.Valid).providers.size)

        val rejectedPrefs = InMemoryPrefs()
        val rejectedRaw = persistedProvidersJson(MAX_PROVIDER_COUNT + 1)
        rejectedPrefs.values["providers"] = rejectedRaw
        val rejectedRepo = AiProviderRepository(TestContext(dir, rejectedPrefs), FakeSecretStore())
        assertTrue(rejectedRepo.loadForTest() is AiProviderLoad.Oversized)
        assertEquals(rejectedRaw, rejectedPrefs.values["providers"])
        dir.deleteRecursively()
    }

    @Test
    fun `provider mutation rejects the 65th provider before commit`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val raw = persistedProvidersJson(MAX_PROVIDER_COUNT)
        prefs.values["providers"] = raw
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())

        assertFalse(repo.saveProvider(provider("provider-65"), null))
        assertEquals(raw, prefs.values["providers"])
        dir.deleteRecursively()
    }

    @Test
    fun `canonical provider writer rejects oversized encoded metadata`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), FakeSecretStore())
        val oversized = provider().copy(baseUrl = "https://" + "a".repeat(MAX_PROVIDER_JSON_BYTES))

        assertFalse(runCatching { repo.buildHashForTest(listOf(oversized)) }.isSuccess)
        dir.deleteRecursively()
    }

    @Test
    fun `normal generated secret id is bounded and overlong persisted id is corrupt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), secrets)
        assertTrue(repo.saveProvider(provider(), "key"))
        val generatedId = repo.getProviders().single { it.id == "custom-test" }.apiKeySecretId!!
        assertTrue(generatedId.length <= AiProviderValidator.MAX_SECRET_ID)

        val prefs = InMemoryPrefs()
        prefs.values["providers"] = JSONArray().put(
            JSONObject()
                .put("id", "overlong-secret")
                .put("type", AiProviderType.CUSTOM_OPENAI_COMPATIBLE.name)
                .put("displayName", "Overlong")
                .put("baseUrl", "https://example.test/v1")
                .put("modelId", "model")
                .put("apiKeySecretId", "s".repeat(AiProviderValidator.MAX_SECRET_ID + 1)),
        ).toString()
        val corrupted = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        assertTrue(corrupted.loadForTest() is AiProviderLoad.Corrupt)
        assertEquals(AiProviderPresets.defaults, corrupted.getProviders())
        dir.deleteRecursively()
    }

    @Test
    fun `repository refuses preset deletion but supports custom deletion`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), FakeSecretStore())

        assertFalse(repo.deleteProvider(AiProviderPresets.OpenAi.id))
        assertTrue(repo.saveProvider(provider(), null))
        assertTrue(repo.deleteProvider("custom-test"))
        assertTrue(repo.getProviders().none { it.id == "custom-test" })
        dir.deleteRecursively()
    }

    @Test
    fun `selection requires an existing effective provider and preserves prior selection`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        assertTrue(repo.saveProvider(provider("provider-a"), null))
        assertTrue(repo.saveProvider(provider("provider-b"), null))
        assertTrue(repo.setSelectedProvider("provider-a"))
        val before = prefs.values["selected_provider_id"]

        assertFalse(repo.setSelectedProvider("missing-provider"))
        assertEquals(before, prefs.values["selected_provider_id"])
        assertTrue(repo.setSelectedProvider("provider-b"))
        assertEquals("provider-b", prefs.values["selected_provider_id"])
        dir.deleteRecursively()
    }

    @Test
    fun `display and execution snapshots share the same effective provider list`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] = "[]"
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())

        assertEquals(repo.getProviders(), repo.getExecutionSnapshot().providers)
        dir.deleteRecursively()
    }

    @Test
    fun `one malformed provider among valid providers fails the whole load closed`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val good = """{"id":"good-one","type":"CUSTOM_OPENAI_COMPATIBLE","displayName":"Good","baseUrl":"https://ok.example.test","modelId":"m"}"""
        val bad = """{"id":"good-one","type":"CUSTOM_OPENAI_COMPATIBLE","displayName":"Dup","baseUrl":"https://ok.example.test","modelId":"m"}"""
        prefs.values["providers"] = "[$good,$bad]"
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        // Duplicate identity collapses to the defaults display fallback.
        assertTrue(repo.getProviders().none { it.id == "good-one" })
        assertTrue(repo.deleteProvider("good-one"))
        dir.deleteRecursively()
    }

    @Test
    fun `malformed endpoint host is rejected`() {
        assertFalse(runCatching {
            AiProviderValidator.validate(provider(baseUrl = "https:///no-host"), null)
            true
        }.getOrDefault(false))
        assertFalse(runCatching {
            AiProviderValidator.validate(provider(baseUrl = "http://plain.example.test/v1"), null)
            true
        }.getOrDefault(false))
        assertTrue(runCatching {
            AiProviderValidator.validate(provider(), null)
            true
        }.getOrDefault(false))
    }

    @Test
    fun `secret failure and commit failure keep prior behavior`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val failingSecrets = AiProviderRepository(TestContext(dir, InMemoryPrefs()), FakeSecretStore(putResult = false))
        assertFalse(failingSecrets.saveProvider(provider(), "k"))

        val failingCommitPrefs = InMemoryPrefs(commitResult = false)
        val failingCommitSecrets = FakeSecretStore()
        val failingCommit = AiProviderRepository(TestContext(dir, failingCommitPrefs), failingCommitSecrets)
        assertFalse(failingCommit.saveProvider(provider(), "k"))
        assertTrue(failingCommitSecrets.secrets.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `old secret delete failure with debt record keeps committed metadata valid`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), secrets)
        assertTrue(repo.saveProvider(provider(), "first"))
        val firstId = repo.getProviders().first { it.id == "custom-test" }.apiKeySecretId!!
        secrets.deleteResult = false
        assertEquals(AiProviderMutationOutcome.COMMITTED, repo.saveProviderWithOutcome(provider(), "second"))
        val committed = repo.getProviders().first { it.id == "custom-test" }
        assertTrue(committed.apiKeySecretId != firstId)
        assertTrue(secrets.secrets.containsKey(firstId))
        assertTrue(secrets.secrets.containsKey(committed.apiKeySecretId))
        assertTrue(debtSecrets(dir).contains(firstId))
        dir.deleteRecursively()
    }

    @Test
    fun `metadata commit failure restores process state and cannot promote deleted fresh id`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, prefs), secrets)
        assertTrue(repo.saveProvider(provider(), "old-key"))
        val old = repo.getProviders().first { it.id == "custom-test" }
        val durableBefore = prefs.durableValues["providers"]
        prefs.enqueueCommitResults(false, true)

        val outcome = repo.saveProviderWithOutcome(provider(), "new-key")

        assertEquals(AiProviderMutationOutcome.FAILED_BEFORE_COMMIT, outcome)
        assertEquals(durableBefore, prefs.durableValues["providers"])
        assertEquals(old.apiKeySecretId, repo.getProviders().first { it.id == "custom-test" }.apiKeySecretId)
        assertTrue(secrets.secrets.containsKey(old.apiKeySecretId))
        assertTrue(secrets.secrets.none { it.key != old.apiKeySecretId && it.value == "new-key" })

        assertEquals(
            AiProviderMutationOutcome.COMMITTED,
            repo.saveProviderWithOutcome(provider().copy(apiKeySecretId = old.apiKeySecretId), null),
        )
        assertEquals(old.apiKeySecretId, repo.getProviders().first { it.id == "custom-test" }.apiKeySecretId)
        dir.deleteRecursively()
    }

    @Test
    fun `failed fresh put is settled when store reports process-visible write`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore(putResult = false, putAppliesBeforeFailure = true)
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), secrets)

        val outcome = repo.saveProviderWithOutcome(provider(), "fresh-key")

        assertEquals(AiProviderMutationOutcome.FAILED_BEFORE_COMMIT, outcome)
        assertTrue(secrets.secrets.isEmpty())
        assertTrue(repo.getProviders().none { it.id == "custom-test" })
        assertTrue(debtSecrets(dir).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `old secret delete and debt record failure surfaces durability loss`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), secrets)
        assertTrue(repo.saveProvider(provider(), "first"))
        val firstId = repo.getProviders().first { it.id == "custom-test" }.apiKeySecretId!!
        secrets.deleteResult = false
        val previousHook = com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook
        com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook = { _, _ -> false }
        try {
            val outcome = repo.saveProviderWithOutcome(provider(), "second")
            assertEquals(AiProviderMutationOutcome.DURABILITY_LOST_AFTER_COMMIT, outcome)
            assertTrue(outcome.metadataCommitted)
            assertTrue(outcome.durabilityLost)
            assertFalse(outcome == AiProviderMutationOutcome.COMMITTED)
            assertFalse(repo.saveProvider(provider(), "third"))
            assertTrue(repo.getProviders().first { it.id == "custom-test" }.apiKeySecretId != firstId)
            assertTrue(secrets.secrets.containsKey(firstId))
            assertTrue(debtSecrets(dir).isEmpty())
        } finally {
            com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook = previousHook
            dir.deleteRecursively()
        }
    }

    @Test
    fun `post-commit replacement cancellation settles old secret and propagates`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), secrets)
        assertTrue(repo.saveProvider(provider(), "first"))
        val oldId = repo.getProviders().first { it.id == "custom-test" }.apiKeySecretId!!
        secrets.deleteCancellation = CancellationException("post-commit cancellation")
        val thrown = try {
            repo.saveProviderWithOutcome(provider(), "second")
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue(thrown is CancellationException)
        val committed = repo.getProviders().first { it.id == "custom-test" }
        assertTrue(committed.apiKeySecretId != oldId)
        assertTrue(secrets.secrets.containsKey(committed.apiKeySecretId))
        assertTrue(secrets.secrets.containsKey(oldId))
        assertTrue(secrets.deleteCalls.contains(oldId))
        assertTrue(debtSecrets(dir).contains(oldId))
        dir.deleteRecursively()
    }

    @Test
    fun `post-commit provider deletion cancellation settles old secret and propagates`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), secrets)
        assertTrue(repo.saveProvider(provider(), "first"))
        val oldId = repo.getProviders().first { it.id == "custom-test" }.apiKeySecretId!!
        secrets.deleteCancellation = CancellationException("post-commit cancellation")
        val thrown = try {
            repo.deleteProviderWithOutcome("custom-test")
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue(thrown is CancellationException)
        assertTrue(repo.getProviders().none { it.id == "custom-test" })
        assertTrue(secrets.secrets.containsKey(oldId))
        assertTrue(secrets.deleteCalls.contains(oldId))
        assertTrue(debtSecrets(dir).contains(oldId))
        dir.deleteRecursively()
    }

    @Test
    fun `legacy persisted http provider is not runnable and https stays runnable`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        // Legacy metadata persisted before save-time validation enforced HTTPS.
        prefs.values["providers"] =
            """[{"id":"legacy-http","type":"CUSTOM_OPENAI_COMPATIBLE","displayName":"Legacy",""" +
                """"baseUrl":"http://legacy.example.test/v1/chat/completions","modelId":"m",""" +
                """"apiKeySecretId":"legacy_key","isEnabled":true,"temperature":0.7,"authMode":"BEARER_API_KEY"}]"""
        val secrets = FakeSecretStore()
        kotlinx.coroutines.runBlocking { secrets.putSecret("legacy_key", "k") }
        val repo = AiProviderRepository(TestContext(dir, prefs), secrets)

        assertNull(repo.getRunnableProviderById("legacy-http"))
        assertNull(repo.getSelectedRunnableProvider())

        // HTTPS legacy metadata keeps working unchanged.
        prefs.values["providers"] =
            """[{"id":"legacy-https","type":"CUSTOM_OPENAI_COMPATIBLE","displayName":"Legacy",""" +
                """"baseUrl":"https://legacy.example.test/v1/chat/completions","modelId":"m",""" +
                """"apiKeySecretId":"legacy_key","isEnabled":true,"temperature":0.7,"authMode":"BEARER_API_KEY"}]"""
        assertEquals("legacy-https", repo.getRunnableProviderById("legacy-https")?.id)
        assertEquals("legacy-https", repo.getSelectedRunnableProvider()?.id)
        dir.deleteRecursively()
    }

    @Test
    fun `rollback delete failure records cleanup debt for fresh secret`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore(deleteResult = false)
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs(commitResult = false)), secrets)

        assertFalse(repo.saveProvider(provider(), "fresh-key"))
        val debts = debtSecrets(dir)
        assertEquals(1, debts.size)
        assertTrue(debts.single().startsWith("ai_key_custom-test_"))
        assertEquals(1, secrets.secrets.size)
        dir.deleteRecursively()
    }

    @Test
    fun `metadata commit failure with rollback debt failure surfaces durability loss`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore(deleteResult = false)
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs(commitResult = false)), secrets)
        val previousHook = com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook
        com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook = { _, _ -> false }
        try {
            val outcome = repo.saveProviderWithOutcome(provider(), "fresh-key")
            assertEquals(AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT, outcome)
            assertFalse(outcome.metadataCommitted)
            assertTrue(outcome.durabilityLost)
            assertFalse(repo.saveProvider(provider(), "fresh-key-2"))
            assertEquals(1, secrets.secrets.size)
            assertTrue(debtSecrets(dir).isEmpty())
        } finally {
            com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook = previousHook
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rollback delete success records no cleanup debt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs(commitResult = false)), secrets)

        assertFalse(repo.saveProvider(provider(), "fresh-key"))
        assertTrue(secrets.secrets.isEmpty())
        assertTrue(debtSecrets(dir).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `NONE auth mode without secret still saves`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), FakeSecretStore())
        val noAuth = provider().copy(authMode = AiProviderAuthMode.NONE)
        assertTrue(repo.saveProvider(noAuth, null))
        assertTrue(repo.getProviders().any { it.id == "custom-test" })
        dir.deleteRecursively()
    }

    @Test
    fun `switching authenticated provider to NONE clears metadata and settles old secret after commit`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, prefs), secrets)
        assertTrue(repo.saveProvider(provider().copy(authHeaderName = "X-Legacy"), "old-key"))
        val oldId = repo.getProviders().single { it.id == "custom-test" }.apiKeySecretId!!

        val outcome = repo.saveProviderWithOutcome(
            provider().copy(authMode = AiProviderAuthMode.NONE, apiKeySecretId = oldId, authHeaderName = "X-Legacy"),
            null,
        )

        assertEquals(AiProviderMutationOutcome.COMMITTED, outcome)
        val saved = repo.getProviders().single { it.id == "custom-test" }
        assertNull(saved.apiKeySecretId)
        assertNull(saved.authHeaderName)
        assertFalse(secrets.secrets.containsKey(oldId))
        assertTrue(secrets.deleteCalls.contains(oldId))
        dir.deleteRecursively()
    }

    @Test
    fun `NONE metadata commit failure preserves old reference and secret`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, prefs), secrets)
        assertTrue(repo.saveProvider(provider(), "old-key"))
        val old = repo.getProviders().single { it.id == "custom-test" }
        prefs.enqueueCommitResults(false, true)

        val outcome = repo.saveProviderWithOutcome(
            provider().copy(authMode = AiProviderAuthMode.NONE, apiKeySecretId = old.apiKeySecretId, authHeaderName = "X-Legacy"),
            null,
        )

        assertEquals(AiProviderMutationOutcome.FAILED_BEFORE_COMMIT, outcome)
        assertEquals(old.apiKeySecretId, repo.getProviders().single { it.id == "custom-test" }.apiKeySecretId)
        assertTrue(secrets.secrets.containsKey(old.apiKeySecretId))
        assertFalse(secrets.deleteCalls.contains(old.apiKeySecretId))
        dir.deleteRecursively()
    }

    @Test
    fun `NONE post-commit cleanup failure follows debt semantics`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore(deleteResult = false)
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), secrets)
        assertTrue(repo.saveProvider(provider(), "old-key"))
        val oldId = repo.getProviders().single { it.id == "custom-test" }.apiKeySecretId!!

        val outcome = repo.saveProviderWithOutcome(
            provider().copy(authMode = AiProviderAuthMode.NONE, apiKeySecretId = oldId),
            null,
        )

        assertEquals(AiProviderMutationOutcome.COMMITTED, outcome)
        assertNull(repo.getProviders().single { it.id == "custom-test" }.apiKeySecretId)
        assertTrue(debtSecrets(dir).contains(oldId))
        dir.deleteRecursively()
    }

    @Test
    fun `NONE post-commit cleanup debt failure surfaces durability loss`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore(deleteResult = false)
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), secrets)
        assertTrue(repo.saveProvider(provider(), "old-key"))
        val oldId = repo.getProviders().single { it.id == "custom-test" }.apiKeySecretId!!
        val previousHook = com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook
        com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook = { _, _ -> false }
        try {
            val outcome = repo.saveProviderWithOutcome(
                provider().copy(authMode = AiProviderAuthMode.NONE, apiKeySecretId = oldId),
                null,
            )
            assertEquals(AiProviderMutationOutcome.DURABILITY_LOST_AFTER_COMMIT, outcome)
            assertNull(repo.getProviders().single { it.id == "custom-test" }.apiKeySecretId)
            assertTrue(secrets.secrets.containsKey(oldId))
        } finally {
            com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook = previousHook
            dir.deleteRecursively()
        }
    }

    @Test
    fun `legacy NONE secret remains ignored until an authoritative save`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] = JSONArray().put(
            JSONObject()
                .put("id", "legacy-none")
                .put("type", AiProviderType.CUSTOM_OPENAI_COMPATIBLE.name)
                .put("displayName", "Legacy")
                .put("baseUrl", "https://legacy.example.test/v1/chat/completions")
                .put("modelId", "m")
                .put("apiKeySecretId", "legacy-secret")
                .put("authMode", AiProviderAuthMode.NONE.name)
                .put("authHeaderName", "X-Legacy"),
        ).toString()
        val secrets = FakeSecretStore()
        secrets.secrets["legacy-secret"] = "must-not-be-read"
        val repo = AiProviderRepository(TestContext(dir, prefs), secrets)

        assertEquals("legacy-secret", repo.getProviders().single().apiKeySecretId)
        assertEquals("legacy-none", repo.getRunnableProviderById("legacy-none")?.id)
        assertTrue(secrets.getCalls.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `switching NONE back to bearer without a key is not runnable`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), FakeSecretStore())
        assertTrue(repo.saveProvider(provider().copy(authMode = AiProviderAuthMode.NONE, apiKeySecretId = "legacy"), null))

        assertTrue(repo.saveProvider(provider().copy(authMode = AiProviderAuthMode.BEARER_API_KEY, apiKeySecretId = null), null))

        assertNull(repo.getRunnableProviderById("custom-test"))
        dir.deleteRecursively()
    }

    @Test
    fun `NONE auth mode rejects a newly entered raw secret`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), secrets)
        val noAuth = provider().copy(authMode = AiProviderAuthMode.NONE)
        assertFalse(repo.saveProvider(noAuth, "must-not-be-staged"))
        assertTrue(secrets.secrets.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `runnable snapshot accepts no-auth local and rejects weak provider states`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, prefs), secrets)
        val local = provider("local").copy(authMode = AiProviderAuthMode.NONE, baseUrl = "https://localhost/v1")
        val bearer = provider("bearer")
        assertTrue(repo.saveProvider(local, null))
        assertTrue(repo.saveProvider(bearer, "secret"))
        assertTrue(repo.saveProvider(provider("disabled").copy(isEnabled = false), "disabled-secret"))
        assertTrue(repo.getRunnableProviderById("local") != null)
        assertTrue(repo.getRunnableProviderById("bearer") != null)
        assertNull(repo.getRunnableProviderById("disabled"))

        val persisted = repo.getProviders()
        prefs.values["providers"] = JSONArray().put(
            JSONObject()
                .put("id", "missing-secret")
                .put("type", AiProviderType.CUSTOM_OPENAI_COMPATIBLE.name)
                .put("displayName", "Missing")
                .put("baseUrl", "https://secure.example.test/v1/chat/completions")
                .put("modelId", "m")
                .put("apiKeySecretId", "not-decryptable")
                .put("isEnabled", true)
                .put("temperature", 0.7)
                .put("authMode", AiProviderAuthMode.BEARER_API_KEY.name)
        ).put(
            JSONObject()
                .put("id", "unsafe")
                .put("type", AiProviderType.CUSTOM_OPENAI_COMPATIBLE.name)
                .put("displayName", "Unsafe")
                .put("baseUrl", "http://plain.example.test/v1/chat/completions")
                .put("modelId", "m")
                .put("apiKeySecretId", "unsafe-key")
                .put("isEnabled", true)
                .put("temperature", 0.7)
                .put("authMode", AiProviderAuthMode.BEARER_API_KEY.name)
        ).toString()
        kotlinx.coroutines.runBlocking { secrets.putSecret("unsafe-key", "k") }
        assertNull(repo.getRunnableProviderById("missing-secret"))
        assertNull(repo.getRunnableProviderById("unsafe"))
        assertTrue(persisted.isNotEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `latest provider selection intent wins after adversarial completion order`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        assertTrue(repo.saveProvider(provider("provider-a"), "a"))
        assertTrue(repo.saveProvider(provider("provider-b"), "b"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var firstCommit = true
        prefs.onCommitBeforeResult {
            if (firstCommit) {
                firstCommit = false
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val first = async(Dispatchers.IO) { repo.setSelectedProvider("provider-a") }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { repo.setSelectedProvider("provider-b") }
        release.countDown()
        first.await()
        assertTrue(second.await())
        assertEquals("provider-b", prefs.values["selected_provider_id"])
        assertEquals("provider-b", repo.getSelectedRunnableProvider()?.id)
        prefs.onCommitBeforeResult(null)
        dir.deleteRecursively()
    }

    @Test
    fun `selection normalization is side-effect free and cannot overwrite newer selection`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        assertTrue(repo.saveProvider(provider("provider-a"), "a"))
        assertTrue(repo.saveProvider(provider("provider-b"), "b"))
        prefs.values["selected_provider_id"] = "invalid-selection"

        assertEquals("provider-a", repo.getSelectedRunnableProvider()?.id)
        assertTrue(repo.setSelectedProvider("provider-b"))
        assertEquals("provider-b", prefs.values["selected_provider_id"])
        assertEquals("provider-b", repo.getSelectedRunnableProvider()?.id)
        dir.deleteRecursively()
    }

    @Test
    fun `deleting a non-active provider preserves active selection`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), FakeSecretStore())
        assertTrue(repo.saveProvider(provider("provider-a"), "a"))
        assertTrue(repo.saveProvider(provider("provider-b"), "b"))
        assertTrue(repo.setSelectedProvider("provider-b"))
        assertEquals(AiProviderMutationOutcome.COMMITTED, repo.deleteProviderWithOutcome("provider-a"))
        assertEquals("provider-b", repo.getSelectedRunnableProvider()?.id)
        dir.deleteRecursively()
    }

    @Test
    fun `committed provider deletion cleans only that provider variants and remains idempotent`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        val variants = UserExternalVariantStore(TestContext(dir, prefs))
        assertTrue(repo.saveProvider(provider("provider-a"), null))
        assertTrue(repo.saveProvider(provider("provider-b"), null))
        assertTrue(variants.save(UserExternalVariant("user:a", "provider-a", "model", "A", UserVariantPatch.ReasoningEffort("high", ReasoningEffortMapping.FLAT_REASONING_EFFORT))))
        assertTrue(variants.save(UserExternalVariant("user:b", "provider-b", "model", "B", UserVariantPatch.ReasoningEffort("low", ReasoningEffortMapping.FLAT_REASONING_EFFORT))))

        assertEquals(AiProviderMutationOutcome.COMMITTED, repo.deleteProviderWithOutcome("provider-a"))
        assertTrue(variants.list("provider-a", "model").isEmpty())
        assertEquals(listOf("user:b"), variants.listAll().map { it.id })
        assertEquals(AiProviderMutationOutcome.COMMITTED, repo.deleteProviderWithOutcome("provider-a"))
        assertEquals(listOf("user:b"), variants.listAll().map { it.id })
        dir.deleteRecursively()
    }

    @Test
    fun `failed provider metadata deletion leaves variants untouched`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        val variants = UserExternalVariantStore(TestContext(dir, prefs))
        assertTrue(repo.saveProvider(provider("provider-a"), null))
        assertTrue(variants.save(UserExternalVariant("user:a", "provider-a", "model", "A", UserVariantPatch.ReasoningEffort("high", ReasoningEffortMapping.FLAT_REASONING_EFFORT))))
        prefs.commitResult = false

        assertTrue(repo.deleteProviderWithOutcome("provider-a") != AiProviderMutationOutcome.COMMITTED)
        assertEquals(listOf("user:a"), variants.listAll().map { it.id })
        dir.deleteRecursively()
    }

    @Test
    fun `selected variant deletion fallback preserves provider and secret identity`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, prefs), secrets)
        val variants = UserExternalVariantStore(TestContext(dir, prefs))
        val selected = provider("provider-a").copy(selectedVariantId = "user:selected")
        assertTrue(variants.save(UserExternalVariant("user:selected", "provider-a", "model", "Selected", UserVariantPatch.ReasoningEffort("high", ReasoningEffortMapping.FLAT_REASONING_EFFORT))))
        assertTrue(repo.saveProvider(selected, "secret-value"))
        val before = repo.getProviders().single { it.id == "provider-a" }

        assertEquals(UserVariantDeletionOutcome.COMMITTED, repo.deleteUserVariantWithOutcome("provider-a", "user:selected"))
        val after = repo.getProviders().single { it.id == "provider-a" }
        assertNull(after.selectedVariantId)
        assertEquals(before.copy(selectedVariantId = null), after)
        assertTrue(after.apiKeySecretId!!.isNotBlank())
        assertEquals("secret-value", secrets.secrets[after.apiKeySecretId])
        assertTrue(variants.listAll().none { it.id == "user:selected" })

        val unselected = provider("provider-b").copy(selectedVariantId = "default")
        assertTrue(repo.saveProvider(unselected, null))
        assertTrue(variants.save(UserExternalVariant("user:other", "provider-b", "model", "Other", UserVariantPatch.ReasoningEffort("low", ReasoningEffortMapping.FLAT_REASONING_EFFORT))))
        val unselectedBefore = repo.getProviders().single { it.id == "provider-b" }
        assertEquals(UserVariantDeletionOutcome.COMMITTED, repo.deleteUserVariantWithOutcome("provider-b", "user:other"))
        assertEquals(unselectedBefore, repo.getProviders().single { it.id == "provider-b" })
        assertTrue(variants.listAll().none { it.id == "user:other" })
        dir.deleteRecursively()
    }

    @Test
    fun `selected variant metadata fallback failure preserves selection and definition`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variantStore = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variantStore)
        assertTrue(variantStore.save(UserExternalVariant("user:selected", "provider-a", "model", "Selected", UserVariantPatch.ReasoningEffort("high", ReasoningEffortMapping.FLAT_REASONING_EFFORT))))
        assertTrue(repo.saveProvider(provider("provider-a").copy(selectedVariantId = "user:selected"), null))
        metadataPrefs.commitResult = false

        assertEquals(UserVariantDeletionOutcome.SELECTION_UPDATE_FAILED, repo.deleteUserVariantWithOutcome("provider-a", "user:selected"))
        metadataPrefs.values.clear()
        metadataPrefs.values.putAll(metadataPrefs.durableValues)
        val recoveredRepo = AiProviderRepository(context, FakeSecretStore(), variantStore)
        assertEquals("user:selected", recoveredRepo.getProviders().single { it.id == "provider-a" }.selectedVariantId)
        assertEquals(listOf("user:selected"), variantStore.listAll().map { it.id })
        dir.deleteRecursively()
    }

    @Test
    fun `user variant definition save succeeds for an existing provider`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variants = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variants)
        assertTrue(repo.saveProvider(provider("provider-a"), null))

        assertTrue(repo.saveUserVariantDefinition("provider-a", userVariant()))
        assertEquals(listOf("user:variant"), variants.listAll().map { it.id })
        dir.deleteRecursively()
    }

    @Test
    fun `repository variant snapshot reflects create edit and delete`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variants = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variants)
        assertTrue(repo.saveProvider(provider("provider-a"), null))

        val created = userVariant(id = "user:snapshot")
        assertTrue(repo.saveUserVariantDefinition("provider-a", created))
        assertEquals(listOf(created), repo.getUserVariantSnapshot())

        val edited = created.copy(label = "Edited")
        assertTrue(repo.saveUserVariantDefinition("provider-a", edited))
        assertEquals(listOf(edited), repo.getUserVariantSnapshot())

        assertEquals(
            UserVariantDeletionOutcome.COMMITTED,
            repo.deleteUserVariantWithOutcome("provider-a", created.id),
        )
        assertTrue(repo.getUserVariantSnapshot().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `user variant definition save is rejected after provider deletion`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variants = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variants)
        assertTrue(repo.saveProvider(provider("provider-a"), null))
        assertEquals(AiProviderMutationOutcome.COMMITTED, repo.deleteProviderWithOutcome("provider-a"))

        assertFalse(repo.saveUserVariantDefinition("provider-a", userVariant()))
        assertTrue(variants.listAll().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `user variant definition save accepts a draft model ID`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variants = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variants)
        assertTrue(repo.saveProvider(provider("provider-a").copy(modelId = "old-model"), null))

        assertTrue(repo.saveUserVariantDefinition("provider-a", userVariant(modelId = "draft-model")))
        assertEquals(listOf("user:variant"), variants.list("provider-a", "draft-model").map { it.id })
        assertEquals("old-model", repo.getProviders().single { it.id == "provider-a" }.modelId)
        dir.deleteRecursively()
    }

    @Test
    fun `provider deletion and definition save cannot resurrect an orphan`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variants = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variants)
        assertTrue(repo.saveProvider(provider("provider-a"), null))

        val saveStarted = CountDownLatch(1)
        lateinit var concurrentSave: kotlinx.coroutines.Deferred<Boolean>
        metadataPrefs.onCommitBeforeResult {
            concurrentSave = async(Dispatchers.IO) {
                saveStarted.countDown()
                repo.saveUserVariantDefinition("provider-a", userVariant())
            }
            assertTrue(saveStarted.await(10, TimeUnit.SECONDS))
            assertFalse(concurrentSave.isCompleted)
        }

        assertEquals(AiProviderMutationOutcome.COMMITTED, repo.deleteProviderWithOutcome("provider-a"))
        assertFalse(concurrentSave.await())
        assertTrue(repo.getProviders().none { it.id == "provider-a" })
        assertTrue(variants.listAll().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `definition save does not persist selected variant`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variants = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variants)
        assertTrue(repo.saveProvider(provider("provider-a").copy(selectedVariantId = "default"), null))

        assertTrue(repo.saveUserVariantDefinition("provider-a", userVariant()))
        assertNull(repo.getProviders().single { it.id == "provider-a" }.selectedVariantId)
        val serialized = repo.buildHashForTest(repo.getProviders())
        val serializedSelection = JSONArray(serialized).getJSONObject(0)
            .optString("selectedVariantId")
            .takeIf { it.isNotBlank() }
        assertNull(serializedSelection)
        assertFalse(serialized.contains("\"selectedVariantId\":\"default\""))
        dir.deleteRecursively()
    }

    @Test
    fun `main provider save persists selected variant for matching final model`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variants = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variants)
        assertTrue(repo.saveProvider(provider("provider-a").copy(modelId = "old-model"), null))
        assertTrue(repo.saveUserVariantDefinition("provider-a", userVariant(modelId = "final-model")))

        assertTrue(
            repo.saveProvider(
                provider("provider-a").copy(modelId = "final-model", selectedVariantId = "user:variant"),
                null,
            )
        )
        assertEquals("user:variant", repo.getProviders().single { it.id == "provider-a" }.selectedVariantId)
        dir.deleteRecursively()
    }

    @Test
    fun `successful fallback with failed definition cleanup leaves unselected orphan`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variantStore = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variantStore)
        assertTrue(variantStore.save(UserExternalVariant("user:selected", "provider-a", "model", "Selected", UserVariantPatch.ReasoningEffort("high", ReasoningEffortMapping.FLAT_REASONING_EFFORT))))
        assertTrue(repo.saveProvider(provider("provider-a").copy(selectedVariantId = "user:selected"), null))
        val durableVariantData = variantPrefs.durableValues.toMap()
        variantPrefs.commitResult = false

        assertEquals(UserVariantDeletionOutcome.DEFINITION_CLEANUP_FAILED, repo.deleteUserVariantWithOutcome("provider-a", "user:selected"))
        assertNull(repo.getProviders().single { it.id == "provider-a" }.selectedVariantId)
        variantPrefs.values.clear()
        variantPrefs.values.putAll(durableVariantData)
        assertEquals(listOf("user:selected"), variantStore.listAll().map { it.id })
        dir.deleteRecursively()
    }

    @Test
    fun `provider delete retries orphan cleanup after metadata is already absent`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variantStore = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variantStore)
        assertTrue(repo.saveProvider(provider("provider-a"), null))
        assertTrue(variantStore.save(UserExternalVariant("user:orphan", "provider-a", "model", "Orphan", UserVariantPatch.ReasoningEffort("high", ReasoningEffortMapping.FLAT_REASONING_EFFORT))))
        val durableVariantData = variantPrefs.durableValues.toMap()
        variantPrefs.commitResult = false

        assertEquals(AiProviderMutationOutcome.DURABILITY_LOST_AFTER_COMMIT, repo.deleteProviderWithOutcome("provider-a"))
        assertTrue(repo.getProviders().none { it.id == "provider-a" })
        variantPrefs.values.clear()
        variantPrefs.values.putAll(durableVariantData)
        assertEquals(listOf("user:orphan"), variantStore.listAll().map { it.id })

        variantPrefs.commitResult = true
        assertEquals(AiProviderMutationOutcome.COMMITTED, repo.deleteProviderWithOutcome("provider-a"))
        assertTrue(repo.getProviders().none { it.id == "provider-a" })
        assertTrue(variantStore.listAll().isEmpty())
        assertEquals(AiProviderMutationOutcome.COMMITTED, repo.deleteProviderWithOutcome("provider-a"))
        assertTrue(variantStore.listAll().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `variant deletion serializes a concurrent reselect after unselected observation`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variantStore = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variantStore)
        val variantId = "user:foo"
        assertTrue(variantStore.save(UserExternalVariant(variantId, "provider-a", "model", "Foo", UserVariantPatch.ReasoningEffort("high", ReasoningEffortMapping.FLAT_REASONING_EFFORT))))
        assertTrue(repo.saveProvider(provider("provider-a").copy(selectedVariantId = "default"), null))

        val saveStarted = CountDownLatch(1)
        lateinit var concurrentSave: kotlinx.coroutines.Deferred<Boolean>
        variantPrefs.onCommitBeforeResult {
            concurrentSave = async(Dispatchers.IO) {
                saveStarted.countDown()
                repo.saveProvider(provider("provider-a").copy(selectedVariantId = variantId), null)
            }
            assertTrue(saveStarted.await(10, TimeUnit.SECONDS))
            assertFalse(concurrentSave.isCompleted)
        }

        assertEquals(UserVariantDeletionOutcome.COMMITTED, repo.deleteUserVariantWithOutcome("provider-a", variantId))
        assertFalse(concurrentSave.await())
        assertNull(repo.getProviders().single { it.id == "provider-a" }.selectedVariantId)
        assertTrue(variantStore.listAll().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `variant deletion serializes a concurrent reselect after fallback commit`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variantStore = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variantStore)
        val variantId = "user:foo"
        assertTrue(variantStore.save(UserExternalVariant(variantId, "provider-a", "model", "Foo", UserVariantPatch.ReasoningEffort("high", ReasoningEffortMapping.FLAT_REASONING_EFFORT))))
        assertTrue(repo.saveProvider(provider("provider-a").copy(selectedVariantId = variantId), null))

        val saveStarted = CountDownLatch(1)
        lateinit var concurrentSave: kotlinx.coroutines.Deferred<Boolean>
        metadataPrefs.onCommitBeforeResult {
            concurrentSave = async(Dispatchers.IO) {
                saveStarted.countDown()
                repo.saveProvider(provider("provider-a").copy(selectedVariantId = variantId), null)
            }
            assertTrue(saveStarted.await(10, TimeUnit.SECONDS))
            assertFalse(concurrentSave.isCompleted)
        }

        assertEquals(UserVariantDeletionOutcome.COMMITTED, repo.deleteUserVariantWithOutcome("provider-a", variantId))
        assertFalse(concurrentSave.await())
        assertNull(repo.getProviders().single { it.id == "provider-a" }.selectedVariantId)
        assertTrue(variantStore.listAll().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `provider deletion and variant deletion serialize without resurrection`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variantStore = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variantStore)
        val variantId = "user:foo"
        assertTrue(variantStore.save(UserExternalVariant(variantId, "provider-a", "model", "Foo", UserVariantPatch.ReasoningEffort("high", ReasoningEffortMapping.FLAT_REASONING_EFFORT))))
        assertTrue(repo.saveProvider(provider("provider-a"), null))

        val deleteStarted = CountDownLatch(1)
        lateinit var concurrentVariantDelete: kotlinx.coroutines.Deferred<UserVariantDeletionOutcome>
        metadataPrefs.onCommitBeforeResult {
            concurrentVariantDelete = async(Dispatchers.IO) {
                deleteStarted.countDown()
                repo.deleteUserVariantWithOutcome("provider-a", variantId)
            }
            assertTrue(deleteStarted.await(10, TimeUnit.SECONDS))
            assertFalse(concurrentVariantDelete.isCompleted)
        }

        assertEquals(AiProviderMutationOutcome.COMMITTED, repo.deleteProviderWithOutcome("provider-a"))
        assertEquals(UserVariantDeletionOutcome.COMMITTED, concurrentVariantDelete.await())
        assertTrue(repo.getProviders().none { it.id == "provider-a" })
        assertTrue(variantStore.listAll().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `unrelated provider save is preserved across serialized variant deletion`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val metadataPrefs = InMemoryPrefs()
        val variantPrefs = InMemoryPrefs()
        val context = TestContext(dir, metadataPrefs)
        val variantStore = UserExternalVariantStore(TestContext(dir, variantPrefs))
        val repo = AiProviderRepository(context, FakeSecretStore(), variantStore)
        val variantId = "user:foo"
        assertTrue(variantStore.save(UserExternalVariant(variantId, "provider-a", "model", "Foo", UserVariantPatch.ReasoningEffort("high", ReasoningEffortMapping.FLAT_REASONING_EFFORT))))
        assertTrue(repo.saveProvider(provider("provider-a").copy(selectedVariantId = variantId), null))

        val saveStarted = CountDownLatch(1)
        lateinit var unrelatedSave: kotlinx.coroutines.Deferred<Boolean>
        metadataPrefs.onCommitBeforeResult {
            unrelatedSave = async(Dispatchers.IO) {
                saveStarted.countDown()
                repo.saveProvider(provider("provider-b"), null)
            }
            assertTrue(saveStarted.await(10, TimeUnit.SECONDS))
            assertFalse(unrelatedSave.isCompleted)
            metadataPrefs.onCommitBeforeResult(null)
        }

        assertEquals(UserVariantDeletionOutcome.COMMITTED, repo.deleteUserVariantWithOutcome("provider-a", variantId))
        assertTrue(unrelatedSave.await())
        assertNull(repo.getProviders().single { it.id == "provider-a" }.selectedVariantId)
        assertTrue(repo.getProviders().any { it.id == "provider-b" })
        assertTrue(variantStore.listAll().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `D2_2 - present invalid authMode enum makes provider metadata corrupt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        // authMode is present but has an invalid enum value. Must render Corrupt,
        // not silently become BEARER_API_KEY.
        prefs.values["providers"] =
            """[{"id":"custom-bad","type":"CUSTOM_OPENAI_COMPATIBLE","displayName":"Bad",""" +
                """"baseUrl":"https://ok.example.test/v1/chat/completions","modelId":"m",""" +
                """"authMode":"INVALID_MODE"}]"""
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        assertTrue("present invalid authMode must make load Corrupt", repo.getProviders().none { it.id == "custom-bad" })
        dir.deleteRecursively()
    }

    @Test
    fun `D2_2 - present invalid isEnabled type makes provider metadata corrupt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] =
            """[{"id":"custom-bad","type":"CUSTOM_OPENAI_COMPATIBLE","displayName":"Bad",""" +
                """"baseUrl":"https://ok.example.test/v1/chat/completions","modelId":"m",""" +
                """"isEnabled":"yes-not-boolean"}]"""
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        assertTrue("present invalid isEnabled must make load Corrupt", repo.getProviders().none { it.id == "custom-bad" })
        dir.deleteRecursively()
    }

    @Test
    fun `invalid config rollback debt loss preserves before commit outcome`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore(deleteResult = false)
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), secrets)
        val previousHook = com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook
        com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook = { _, _ -> false }
        try {
            val outcome = repo.saveProviderWithOutcome(provider(baseUrl = "http://plain.example.test/v1"), "fresh-key")
            assertEquals(AiProviderMutationOutcome.DURABILITY_LOST_BEFORE_COMMIT, outcome)
            assertFalse(outcome.metadataCommitted)
            assertTrue(outcome.durabilityLost)
            assertTrue(secrets.secrets.isNotEmpty())
            assertTrue(debtSecrets(dir).isEmpty())
        } finally {
            com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook = previousHook
            dir.deleteRecursively()
        }
    }

    @Test
    fun `invalid HTTP config returns validation outcome after fresh secret rollback`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), secrets)
        val outcome = repo.saveProviderWithOutcome(provider(baseUrl = "http://plain.example.test/v1"), "fresh-key")
        assertEquals(AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT, outcome)
        assertFalse(outcome.metadataCommitted)
        assertFalse(outcome.durabilityLost)
        assertTrue(secrets.secrets.isEmpty())
        assertTrue(debtSecrets(dir).isEmpty())
        assertTrue(repo.getProviders().none { it.id == "custom-test" })
        dir.deleteRecursively()
    }

    @Test
    fun `delete metadata commit with secret and debt failure is not ordinary success`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val secrets = FakeSecretStore()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), secrets)
        assertTrue(repo.saveProvider(provider(), "delete-me"))
        val secretId = repo.getProviders().first { it.id == "custom-test" }.apiKeySecretId!!
        secrets.deleteResult = false
        val previousHook = com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook
        com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook = { _, _ -> false }
        try {
            assertFalse(repo.deleteProvider("custom-test"))
            assertTrue(repo.getProviders().none { it.id == "custom-test" })
            assertTrue(secrets.secrets.containsKey(secretId))
            assertTrue(debtSecrets(dir).isEmpty())
        } finally {
            com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore.recordHook = previousHook
            dir.deleteRecursively()
        }
    }

    @Test
    fun `D2_2 - present wrong string types make provider metadata corrupt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        val stringFields = listOf("displayName", "baseUrl", "modelId", "apiKeySecretId", "authHeaderName")
        stringFields.forEach { field ->
            val obj = JSONObject()
                .put("id", "bad-$field")
                .put("type", "CUSTOM_OPENAI_COMPATIBLE")
                .put("displayName", "Bad")
                .put("baseUrl", "https://example.test/v1/chat/completions")
                .put("modelId", "model")
                .put(field, 42)
            prefs.values["providers"] = JSONArray().put(obj).toString()
            assertTrue("present non-string $field must corrupt the load", repo.getProviders().none { it.id == "bad-$field" })
        }

        val wrongType = JSONObject()
            .put("id", "bad-type")
            .put("type", 42)
            .put("displayName", "Bad")
            .put("baseUrl", "https://example.test/v1/chat/completions")
            .put("modelId", "model")
        prefs.values["providers"] = JSONArray().put(wrongType).toString()
        assertTrue(repo.getProviders().none { it.id == "bad-type" })

        val wrongReasoning = JSONObject()
            .put("id", "bad-reasoning")
            .put("type", "CUSTOM_OPENAI_COMPATIBLE")
            .put("displayName", "Bad")
            .put("baseUrl", "https://example.test/v1/chat/completions")
            .put("modelId", "model")
            .put("reasoningContentEnabled", "yes")
        prefs.values["providers"] = JSONArray().put(wrongReasoning).toString()
        assertTrue(repo.getProviders().none { it.id == "bad-reasoning" })
        dir.deleteRecursively()
    }

    @Test
    fun `D2_2 - impossible persisted numeric values make provider metadata corrupt`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        val base = JSONObject()
            .put("id", "numeric-bad")
            .put("type", "CUSTOM_OPENAI_COMPATIBLE")
            .put("displayName", "Bad")
            .put("baseUrl", "https://example.test/v1/chat/completions")
            .put("modelId", "model")
        prefs.values["providers"] = JSONArray().put(JSONObject(base.toString()).put("temperature", 999)).toString()
        assertTrue(repo.getProviders().none { it.id == "numeric-bad" })
        prefs.values["providers"] = JSONArray().put(JSONObject(base.toString()).put("maxTokens", -5)).toString()
        assertTrue(repo.getProviders().none { it.id == "numeric-bad" })
        dir.deleteRecursively()
    }

    @Test
    fun `D2_2 - historically absent optional fields retain compatible defaults`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] = JSONArray().put(
            JSONObject()
                .put("id", "legacy-compatible")
                .put("type", "CUSTOM_OPENAI_COMPATIBLE")
                .put("displayName", "Legacy")
                .put("baseUrl", "https://legacy.example.test/v1/chat/completions")
                .put("modelId", "legacy-model")
                .put("apiKeySecretId", JSONObject.NULL)
                .put("isEnabled", true)
                .put("temperature", 0.7)
        ).toString()
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        val loaded = repo.getProviders().first { it.id == "legacy-compatible" }
        assertEquals(0.7, loaded.temperature, 0.0)
        assertNull(loaded.maxTokens)
        assertEquals(AiProviderAuthMode.BEARER_API_KEY, loaded.authMode)
        assertFalse(loaded.reasoningContentEnabled)
        assertNull(loaded.authHeaderName)
        dir.deleteRecursively()
    }

    @Test
    fun `legacy default variant loads as canonical null`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] = JSONArray().put(
            JSONObject()
                .put("id", "legacy-default")
                .put("type", "CUSTOM_OPENAI_COMPATIBLE")
                .put("displayName", "Legacy")
                .put("baseUrl", "https://legacy.example.test/v1/chat/completions")
                .put("modelId", "legacy-model")
                .put("apiKeySecretId", JSONObject.NULL)
                .put("isEnabled", true)
                .put("temperature", 0.7)
                .put("selectedVariantId", "default")
        ).toString()

        val loaded = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
            .getProviders()
            .single { it.id == "legacy-default" }

        assertNull(loaded.selectedVariantId)
        dir.deleteRecursively()
    }

    @Test
    fun `selected variant durable decode rejects overlong identity without prefix salvage`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val validPrefix = "user:valid"
        prefs.values["providers"] = JSONArray().put(
            JSONObject()
                .put("id", "overlong-variant")
                .put("type", "CUSTOM_OPENAI_COMPATIBLE")
                .put("displayName", "Overlong")
                .put("baseUrl", "https://example.test/v1/chat/completions")
                .put("modelId", "model")
                .put("selectedVariantId", validPrefix + "x".repeat(AiProviderValidator.MAX_VARIANT_ID))
        ).toString()

        val loaded = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore()).loadForTest()

        assertTrue(loaded is AiProviderLoad.Corrupt)
        dir.deleteRecursively()
    }

    @Test
    fun `F2 - reasoningContentEnabled survives save and reload`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), FakeSecretStore())
        val withReasoning = provider().copy(reasoningContentEnabled = true)
        assertTrue(repo.saveProvider(withReasoning, null))
        val reloaded = repo.getProviders().first { it.id == "custom-test" }
        assertTrue(reloaded.reasoningContentEnabled)
        assertTrue(
            repo.buildHashForTest(listOf(reloaded)).contains("\"reasoningContentEnabled\":true")
        )
        dir.deleteRecursively()
    }

    @Test
    fun `F3 - save reload returns canonical normalized config`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val prefs = InMemoryPrefs()
        val repo = AiProviderRepository(TestContext(dir, prefs), FakeSecretStore())
        val input = provider(baseUrl = "https://api.example.test/v1").copy(reasoningContentEnabled = true)
        assertTrue(repo.saveProvider(input, null))
        val reloaded = repo.getProviders().first { it.id == "custom-test" }
        assertEquals("https://api.example.test/v1/chat/completions", reloaded.baseUrl)
        assertTrue(reloaded.reasoningContentEnabled)
        val canonical = repo.buildHashForTest(listOf(reloaded))
        assertTrue(canonical.contains("/chat/completions"))
        assertTrue(canonical.contains("\"reasoningContentEnabled\":true"))
        dir.deleteRecursively()
    }

    @Test
    fun `F10c - uppercase HTTPS scheme is accepted`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), FakeSecretStore())
        assertTrue(repo.saveProvider(provider(baseUrl = "HTTPS://api.example.test/v1"), null))
        val reloaded = repo.getProviders().first { it.id == "custom-test" }
        assertTrue(reloaded.baseUrl.endsWith("api.example.test/v1/chat/completions"))
        assertTrue(AiProviderValidator.isExecutionTransportSafe(reloaded))
        dir.deleteRecursively()
    }

    @Test
    fun `F10c - mixed-case HTTP scheme is rejected`() = runTest {
        val dir = Files.createTempDirectory("fusion-ai-provider").toFile()
        val repo = AiProviderRepository(TestContext(dir, InMemoryPrefs()), FakeSecretStore())
        val outcome = repo.saveProviderWithOutcome(
            provider(baseUrl = "Http://plain.example.test/v1"), null
        )
        assertEquals(AiProviderMutationOutcome.VALIDATION_FAILED_BEFORE_COMMIT, outcome)
        assertTrue(repo.getProviders().none { it.id == "custom-test" })
        dir.deleteRecursively()
    }
}
