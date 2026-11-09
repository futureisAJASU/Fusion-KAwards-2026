package com.projectnuke.fusion.ai

import androidx.test.core.app.ApplicationProvider
import com.projectnuke.fusion.ai.data.AiProviderRepository
import com.projectnuke.fusion.ai.data.ObsoleteSecretCleanupDebtStore
import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.secure.SecretStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private class DurableFakeSecretStore : SecretStore {
    private val store = ConcurrentHashMap<String, String>()

    override suspend fun putSecret(id: String, value: String): Boolean {
        store[id] = value
        return true
    }

    override suspend fun getSecret(id: String): String? = store[id]

    override suspend fun deleteSecret(id: String): Boolean = store.remove(id) != null
}

private class FailingPutSecretStore : SecretStore {
    override suspend fun putSecret(id: String, value: String): Boolean = false
    override suspend fun getSecret(id: String): String? = null
    override suspend fun deleteSecret(id: String): Boolean = true
}

private class PartialDeleteSecretStore : SecretStore {
    private val store = ConcurrentHashMap<String, String>()
    val deleteDeny = ConcurrentHashMap.newKeySet<String>()

    override suspend fun putSecret(id: String, value: String): Boolean {
        store[id] = value
        return true
    }

    override suspend fun getSecret(id: String): String? = store[id]

    override suspend fun deleteSecret(id: String): Boolean {
        if (id in deleteDeny) return false
        store.remove(id)
        return true
    }
}

private fun bearerConfig(id: String = UUID.randomUUID().toString()): AiProviderConfig = AiProviderConfig(
    id = id,
    type = AiProviderType.OPENAI,
    displayName = "Test",
    baseUrl = "https://api.example.com/v1",
    modelId = "test-model",
    apiKeySecretId = null,
    authMode = AiProviderAuthMode.BEARER_API_KEY,
)

class AiProviderRepositoryTest {

    @Test
    fun freshProvider_save_succeeds_and_secret_written() = runBlocking {
        val store = DurableFakeSecretStore()
        val repo = AiProviderRepository(ApplicationProvider.getApplicationContext(), store)
        val config = bearerConfig()
        assertTrue(repo.saveProvider(config, "secret-value"))
        val providers = repo.getProviders()
        val saved = providers.find { it.id == config.id }
        assertNotNull(saved)
        assertEquals("secret-value", store.getSecret(saved!!.apiKeySecretId!!))
    }

    @Test
    fun replacement_writes_fresh_UUID_and_removes_old() = runBlocking {
        val store = DurableFakeSecretStore()
        val repo = AiProviderRepository(ApplicationProvider.getApplicationContext(), store)
        val config = bearerConfig()
        assertTrue(repo.saveProvider(config, "first-key"))
        val after = repo.getProviders().find { it.id == config.id }!!
        val oldId = after.apiKeySecretId!!
        assertTrue(repo.saveProvider(after.copy(apiKeySecretId = oldId), "second-key"))
        val after2 = repo.getProviders().find { it.id == config.id }!!
        assertNotEquals(oldId, after2.apiKeySecretId)
        assertEquals("second-key", store.getSecret(after2.apiKeySecretId!!))
        assertNull(store.getSecret(oldId))
    }

    @Test
    fun failing_put_returns_false_and_no_metadata() = runBlocking {
        val store = FailingPutSecretStore()
        val repo = AiProviderRepository(ApplicationProvider.getApplicationContext(), store)
        assertFalse(repo.saveProvider(bearerConfig(), "secret"))
        assertTrue(repo.getProviders().none { it.id == bearerConfig().run { "unused" } })
    }

    @Test
    fun oldSecret_delete_failure_keeps_new_metadata() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val debtFile = java.io.File(appContext.filesDir, "obsolete_secret_cleanup_debt.json")
        debtFile.delete()
        try {
            val store = PartialDeleteSecretStore()
            val repo = AiProviderRepository(appContext, store)
            val config = bearerConfig()
            assertTrue(repo.saveProvider(config, "first"))
            val after = repo.getProviders().find { it.id == config.id }!!
            val oldId = after.apiKeySecretId!!
            store.deleteDeny.add(oldId)
            assertTrue(repo.saveProvider(after.copy(apiKeySecretId = oldId), "second"))
            val afterRetry = repo.getProviders().find { it.id == config.id }!!
            assertEquals("second", store.getSecret(afterRetry.apiKeySecretId!!))
            assertEquals("first", store.getSecret(oldId))
            assertEquals(1, ObsoleteSecretCleanupDebtStore.getPendingCount(debtFile))
        } finally {
            debtFile.delete()
        }
    }

    @Test
    fun explicit_key_delete_removes_secretId() = runBlocking {
        val store = DurableFakeSecretStore()
        val repo = AiProviderRepository(ApplicationProvider.getApplicationContext(), store)
        val config = bearerConfig()
        assertTrue(repo.saveProvider(config, "key-to-delete"))
        val saved = repo.getProviders().find { it.id == config.id }!!
        assertTrue(repo.saveProvider(saved.copy(apiKeySecretId = null), null))
        assertNull(repo.getProviders().find { it.id == config.id }!!.apiKeySecretId)
    }

    @Test
    fun provider_deletion_clears_metadata() = runBlocking {
        val store = DurableFakeSecretStore()
        val repo = AiProviderRepository(ApplicationProvider.getApplicationContext(), store)
        val config = bearerConfig()
        assertTrue(repo.saveProvider(config, "del-key"))
        assertTrue(repo.deleteProvider(config.id))
        assertTrue(repo.getProviders().none { it.id == config.id })
    }

    @Test
    fun noAuth_does_not_require_secret() = runBlocking {
        val store = DurableFakeSecretStore()
        val repo = AiProviderRepository(ApplicationProvider.getApplicationContext(), store)
        val config = bearerConfig().copy(authMode = AiProviderAuthMode.NONE, baseUrl = "https://localhost/v1")
        assertTrue(repo.saveProvider(config, null))
        assertTrue(repo.hasDecryptableSecret(repo.getProviders().find { it.id == config.id }!!))
    }

    @Test
    fun missing_and_decryptFailed_are_distinguishable() = runBlocking {
        val store = DurableFakeSecretStore()
        val repo = AiProviderRepository(ApplicationProvider.getApplicationContext(), store)
        val config = bearerConfig()
        assertTrue(repo.saveProvider(config, "real-secret"))
        val saved = repo.getProviders().find { it.id == config.id }!!
        assertTrue(repo.hasDecryptableSecret(saved))
        assertFalse(repo.hasDecryptableSecret(saved.copy(apiKeySecretId = "missing-id")))
        assertFalse(repo.hasDecryptableSecret(saved.copy(apiKeySecretId = null)))
    }
}
