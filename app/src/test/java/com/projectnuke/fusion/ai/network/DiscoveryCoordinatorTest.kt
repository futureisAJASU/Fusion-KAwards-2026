package com.projectnuke.fusion.ai.network

import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.ExternalDiscoveredModel
import com.projectnuke.fusion.ai.model.ExternalModelDiscoveryResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryCoordinatorTest {
    @Test
    fun `provider A result cannot appear after switching to B and old finally cannot settle`() = runTest {
        val gates = mutableMapOf<String, CompletableDeferred<ExternalModelDiscoveryResult>>()
        val source = fakeSource(gates, ignoreCancellation = true)
        val coordinator = DiscoveryCoordinator(this, source)
        val a = provider("a", "https://a.example/v1")
        val b = provider("b", "https://b.example/v1")

        val op1 = coordinator.discover(a, credentialRevision = 1)
        runCurrent()
        val op2 = coordinator.discover(b, credentialRevision = 1)
        runCurrent()
        gates.getValue("b").complete(success("b-model"))
        op2.join()
        gates.getValue("a").complete(success("a-model"))
        op1.join()

        assertEquals(listOf("b-model"), coordinator.state.value.models.map { it.id })
        assertEquals("b", coordinator.state.value.editorFingerprint?.providerId)
        assertFalse(coordinator.state.value.loading)
    }

    @Test
    fun `editor fingerprint changes cancel and clear current discovery`() = runTest {
        val gate = CompletableDeferred<ExternalModelDiscoveryResult>()
        val source = object : ExternalModelDiscoverySource {
            override suspend fun discover(config: AiProviderConfig, discoveryIdentity: ExternalDiscoveryIdentity, ephemeralApiKey: String?): ExternalModelDiscoveryResult = gate.await()
        }
        val coordinator = DiscoveryCoordinator(this, source)
        val config = provider("p", "https://p.example/v1")
        val job = coordinator.discover(config, 1)
        runCurrent()
        coordinator.updateEditorFingerprint(
            DiscoveryEditorFingerprint.from("p", "https://p.example/v2", AiProviderAuthMode.BEARER_API_KEY, null, 1)
        )
        assertTrue(job.isCancelled)
        assertFalse(coordinator.state.value.loading)
        assertTrue(coordinator.state.value.models.isEmpty())
        assertEquals(null, coordinator.state.value.result)
    }

    @Test
    fun `exact cache hit populates state and refresh bypasses cache`() = runTest {
        var calls = 0
        val source = object : ExternalModelDiscoverySource {
            override suspend fun discover(config: AiProviderConfig, discoveryIdentity: ExternalDiscoveryIdentity, ephemeralApiKey: String?): ExternalModelDiscoveryResult {
                calls += 1
                return success("model-$calls")
            }
        }
        val coordinator = DiscoveryCoordinator(this, source)
        val config = provider("p", "https://p.example/v1")

        coordinator.discover(config, 1).join()
        coordinator.discover(config, 1).join()
        assertEquals(1, calls)
        assertEquals(listOf("model-1"), coordinator.state.value.models.map { it.id })

        coordinator.discover(config, 1, forceRefresh = true).join()
        assertEquals(2, calls)
        assertEquals(listOf("model-2"), coordinator.state.value.models.map { it.id })

        coordinator.discover(config, 2).join()
        assertEquals(3, calls)
    }

    @Test
    fun `reapplying the same semantic editor fingerprint preserves discovered models`() = runTest {
        val source = object : ExternalModelDiscoverySource {
            override suspend fun discover(
                config: AiProviderConfig,
                discoveryIdentity: ExternalDiscoveryIdentity,
                ephemeralApiKey: String?,
            ): ExternalModelDiscoveryResult = success("model")
        }
        val coordinator = DiscoveryCoordinator(this, source)
        val config = provider("p", "https://p.example/v1")

        coordinator.discover(config, credentialRevision = 4, forceRefresh = true).join()
        val sameFingerprint = DiscoveryEditorFingerprint.from(
            providerId = config.id,
            baseUrl = "${config.baseUrl}/",
            authMode = config.authMode,
            authHeaderName = config.authHeaderName,
            credentialRevision = 4,
        )
        coordinator.updateEditorFingerprint(sameFingerprint)

        assertEquals(listOf("model"), coordinator.state.value.models.map { it.id })
    }

    @Test
    fun `credential endpoint auth mode and header identity changes clear discovered models`() = runTest {
        val source = object : ExternalModelDiscoverySource {
            override suspend fun discover(
                config: AiProviderConfig,
                discoveryIdentity: ExternalDiscoveryIdentity,
                ephemeralApiKey: String?,
            ): ExternalModelDiscoveryResult = success("model")
        }
        val coordinator = DiscoveryCoordinator(this, source)
        val config = provider("p", "https://p.example/v1").copy(
            authMode = AiProviderAuthMode.BEARER_API_KEY,
        )
        val base = DiscoveryEditorFingerprint.from(
            providerId = config.id,
            baseUrl = config.baseUrl,
            authMode = AiProviderAuthMode.BEARER_API_KEY,
            authHeaderName = null,
            credentialRevision = 4,
        )
        val changedFingerprints = listOf(
            base.copy(credentialRevision = 5),
            base.copy(modelsEndpoint = "https://p.example/v2/models"),
            base.copy(authMode = AiProviderAuthMode.NONE),
            base.copy(
                authMode = AiProviderAuthMode.CUSTOM_HEADER,
                authHeaderIdentity = "X-Token",
            ),
        )

        changedFingerprints.forEach { changed ->
            coordinator.discover(config, credentialRevision = 4, forceRefresh = true).join()
            assertEquals(listOf("model"), coordinator.state.value.models.map { it.id })

            coordinator.updateEditorFingerprint(changed)

            assertTrue(coordinator.state.value.models.isEmpty())
            assertEquals(null, coordinator.state.value.result)
        }
    }

    private fun fakeSource(
        gates: MutableMap<String, CompletableDeferred<ExternalModelDiscoveryResult>>,
        ignoreCancellation: Boolean,
    ) = object : ExternalModelDiscoverySource {
        override suspend fun discover(config: AiProviderConfig, discoveryIdentity: ExternalDiscoveryIdentity, ephemeralApiKey: String?): ExternalModelDiscoveryResult {
            val gate = gates.getOrPut(config.id) { CompletableDeferred() }
            return if (ignoreCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
        }
    }

    private fun success(id: String) = ExternalModelDiscoveryResult.Success(
        models = listOf(ExternalDiscoveredModel(id, id)),
        fetchedAt = System.currentTimeMillis(),
    )

    private fun provider(id: String, baseUrl: String) = AiProviderConfig(
        id = id,
        type = AiProviderType.OPENAI,
        displayName = id,
        baseUrl = baseUrl,
        modelId = "model",
        apiKeySecretId = null,
        authMode = AiProviderAuthMode.NONE,
    )
}
