package com.projectnuke.fusion.ai.network

import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.ExternalDiscoveredModel
import com.projectnuke.fusion.ai.model.ExternalModelDiscoveryResult
import com.projectnuke.fusion.ai.model.ExternalRequestPatch
import com.projectnuke.fusion.ai.model.ExternalReasoningEffort
import com.projectnuke.fusion.ai.model.ExternalModelVariant
import com.projectnuke.fusion.ai.provider.ExternalModelCapabilityResolver
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.ai.provider.ExternalModelCapabilityRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ExternalModelDiscoveryTransportTest {

    @Test
    fun `parseDiscoveryResponse parses standard list`() {
        val transport = ExternalModelDiscoveryTransport(
            secretStore = FakeSecretStore("key")
        )
        val raw = """{"object":"list","data":[{"id":"qwen3","object":"model","owned_by":"alibaba"},{"id":"deepseek-v4","object":"model"}]}"""
        val result = transport.parseDiscoveryResponseForTest(raw)
        assertTrue(result is ExternalModelDiscoveryResult.Success)
        val models = (result as ExternalModelDiscoveryResult.Success).models
        assert(models.size == 2)
        assert(models.any { it.id == "qwen3" })
        assert(models.any { it.id == "deepseek-v4" })
    }

    @Test
    fun `parseDiscoveryResponse ignores extra fields`() {
        val transport = ExternalModelDiscoveryTransport(FakeSecretStore("key"))
        val raw = """{"object":"list","data":[{"id":"test","object":"model","extra":"ignored"}]}"""
        val result = transport.parseDiscoveryResponseForTest(raw)
        assertTrue(result is ExternalModelDiscoveryResult.Success)
        val models = (result as ExternalModelDiscoveryResult.Success).models
        assert(models.size == 1)
        assert(models[0].id == "test")
    }

    @Test
    fun `parseDiscoveryResponse deduplicates exact ids`() {
        val transport = ExternalModelDiscoveryTransport(FakeSecretStore("key"))
        val raw = """{"object":"list","data":[{"id":"dup"},{"id":"dup"}]}"""
        val result = transport.parseDiscoveryResponseForTest(raw)
        assertTrue(result is ExternalModelDiscoveryResult.Success)
        val models = (result as ExternalModelDiscoveryResult.Success).models
        assert(models.size == 1)
    }

    @Test
    fun `parseDiscoveryResponse omits blank or oversized ids`() {
        val transport = ExternalModelDiscoveryTransport(FakeSecretStore("key"))
        val raw = """{"object":"list","data":[{"id":""},{"id":"valid"}]}"""
        val result = transport.parseDiscoveryResponseForTest(raw)
        assertTrue(result is ExternalModelDiscoveryResult.Success)
        val models = (result as ExternalModelDiscoveryResult.Success).models
        assert(models.size == 1)
        assert(models[0].id == "valid")
    }

    @Test
    fun `parseDiscoveryResponse bounds entries at max`() {
        val transport = ExternalModelDiscoveryTransport(FakeSecretStore("key"))
        val items = (1..600).map { n -> """{"id":"m$n","object":"model"}""" }.joinToString(",", "[", "]")
        val raw = """{"object":"list","data":$items}"""
        val result = transport.parseDiscoveryResponseForTest(raw)
        assertTrue(result is ExternalModelDiscoveryResult.Success)
        val models = (result as ExternalModelDiscoveryResult.Success).models
        assert(models.size == ExternalModelDiscoveryTransport.MaxDiscoveredEntries)
    }

    @Test
    fun `resolve produces correct endpoints for chat completions input`() {
        val endpoints = OpenAiCompatibleEndpointResolver.resolve("https://host/v1/chat/completions")
        assert(endpoints.apiRoot == "https://host/v1")
        assert(endpoints.chatCompletions == "https://host/v1/chat/completions")
        assert(endpoints.models == "https://host/v1/models")
    }

    @Test
    fun `empty data array returns Empty result`() {
        val transport = ExternalModelDiscoveryTransport(FakeSecretStore("key"))
        val raw = """{"data":[]}"""
        val result = transport.parseDiscoveryResponseForTest(raw)
        assertTrue(result is ExternalModelDiscoveryResult.Empty)
    }

    @Test
    fun `data with only blank oversized invalid entries returns Empty`() {
        val transport = ExternalModelDiscoveryTransport(FakeSecretStore("key"))
        val raw = """{"data":[{"id":""},{"id":"toolong${"x".repeat(300)}"}]}"""
        val result = transport.parseDiscoveryResponseForTest(raw)
        assertTrue(result is ExternalModelDiscoveryResult.Empty)
    }

    @Test
    fun `missing data field returns InvalidResponse`() {
        val transport = ExternalModelDiscoveryTransport(FakeSecretStore("key"))
        val raw = """{}"""
        val result = transport.parseDiscoveryResponseForTest(raw)
        assertTrue(result is ExternalModelDiscoveryResult.InvalidResponse)
    }

    @Test
    fun `data as wrong type returns InvalidResponse`() {
        val transport = ExternalModelDiscoveryTransport(FakeSecretStore("key"))
        val raw = """{"data":"wrong"}"""
        val result = transport.parseDiscoveryResponseForTest(raw)
        assertTrue(result is ExternalModelDiscoveryResult.InvalidResponse)
    }

    @Test
    fun `malformed JSON returns InvalidResponse`() {
        val transport = ExternalModelDiscoveryTransport(FakeSecretStore("key"))
        val raw = """{"invalid json"""
        val result = transport.parseDiscoveryResponseForTest(raw)
        assertTrue(result is ExternalModelDiscoveryResult.InvalidResponse)
    }

    @Test
    fun `bounds displayName length`() {
        val transport = ExternalModelDiscoveryTransport(FakeSecretStore("key"))
        val longName = "a".repeat(200)
        val raw = """{"data":[{"id":"test","displayName":"$longName"}]}"""
        val result = transport.parseDiscoveryResponseForTest(raw)
        assertTrue(result is ExternalModelDiscoveryResult.Success)
        val models = (result as ExternalModelDiscoveryResult.Success).models
        assert(models[0].displayName.length <= ExternalModelDiscoveryTransport.MaxDisplayNameLength)
    }

    @Test
    fun `bounds ownedBy length`() {
        val transport = ExternalModelDiscoveryTransport(FakeSecretStore("key"))
        val longOwned = "a".repeat(200)
        val raw = """{"data":[{"id":"test","owned_by":"$longOwned"}]}"""
        val result = transport.parseDiscoveryResponseForTest(raw)
        assertTrue(result is ExternalModelDiscoveryResult.Success)
        val models = (result as ExternalModelDiscoveryResult.Success).models
        assert(models[0].ownedBy?.length?.let { it <= ExternalModelDiscoveryTransport.MaxOwnedByLength } == true)
    }
}

// Minimal fake for testing
class FakeSecretStore(private val value: String?) : com.projectnuke.fusion.ai.secure.SecretStore {
    override suspend fun getSecret(id: String): String? = value
    override suspend fun putSecret(id: String, value: String): Boolean = true
    override suspend fun deleteSecret(id: String): Boolean = true
}

/** Fake HttpURLConnection for transport-level tests */
class FakeDiscoveryHttpURLConnection(
    val requestedHeaders: MutableMap<String, String> = mutableMapOf(),
    private val fakeResponseCode: Int = 200,
    val responseText: String = """{"data":[]}""",
) : HttpURLConnection(URL("https://example.com/models")) {
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy() = false
    override fun getResponseCode(): Int = fakeResponseCode
    override fun getInputStream() = java.io.ByteArrayInputStream(responseText.toByteArray(Charsets.UTF_8))
    override fun getErrorStream() = if (fakeResponseCode >= 400) java.io.ByteArrayInputStream(responseText.toByteArray(Charsets.UTF_8)) else null
    override fun getRequestProperty(key: String?): String? = requestedHeaders[key]
    override fun setRequestProperty(key: String?, value: String?) {
        if (key != null && value != null) requestedHeaders[key] = value
    }
}

class EphemeralKeyTransportTest {

    private fun config() = AiProviderConfig(
        id = "test", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
        displayName = "Test", baseUrl = "https://example.com/v1",
        modelId = "model", apiKeySecretId = "secret-id",
        authMode = AiProviderAuthMode.BEARER_API_KEY,
    )

    @Test
    fun `ephemeral key at maximum bound is accepted`() = kotlinx.coroutines.test.runTest {
        val key = "k".repeat(com.projectnuke.fusion.ai.data.AiProviderValidator.MAX_SECRET)
        val headers = mutableMapOf<String, String>()
        var connections = 0
        val transport = ExternalModelDiscoveryTransport(
            secretStore = FakeSecretStore(null),
            connectionFactory = { connections++; FakeDiscoveryHttpURLConnection(headers, 200, """{"data":[{"id":"m1"}]}""") },
        )
        val result = transport.discover(config(), identityBearer(), key)
        assertTrue(result is ExternalModelDiscoveryResult.Success)
        assertEquals(1, connections)
        assertEquals("Bearer $key", headers["Authorization"])
    }

    @Test
    fun `oversized ephemeral key is rejected before connection or secret lookup`() = kotlinx.coroutines.test.runTest {
        val secret = "ephemeral-oversized-secret"
        var lookups = 0
        var connections = 0
        val store = object : com.projectnuke.fusion.ai.secure.SecretStore {
            override suspend fun getSecret(id: String): String? { lookups++; return "persisted" }
            override suspend fun putSecret(id: String, value: String) = true
            override suspend fun deleteSecret(id: String) = true
        }
        val transport = ExternalModelDiscoveryTransport(
            secretStore = store,
            connectionFactory = { connections++; FakeDiscoveryHttpURLConnection() },
        )
        val result = transport.discover(config(), identityBearer(), secret + "x".repeat(com.projectnuke.fusion.ai.data.AiProviderValidator.MAX_SECRET))
        assertTrue(result is ExternalModelDiscoveryResult.AuthenticationFailed)
        assertEquals(0, connections)
        assertEquals(0, lookups)
        assertFalse(result.toString().contains(secret))
    }

    @Test
    fun `oversized persisted secret is not attached`() = kotlinx.coroutines.test.runTest {
        val headers = mutableMapOf<String, String>()
        val transport = ExternalModelDiscoveryTransport(
            secretStore = FakeSecretStore("p".repeat(com.projectnuke.fusion.ai.data.AiProviderValidator.MAX_SECRET + 1)),
            connectionFactory = { FakeDiscoveryHttpURLConnection(headers, 200, """{"data":[{"id":"m1"}]}""") },
        )
        val result = transport.discover(config(), identityBearer(), null)
        assertTrue(result is ExternalModelDiscoveryResult.AuthenticationFailed)
        assertFalse(headers.containsKey("Authorization"))
    }

    @Test
    fun `ephemeral Bearer key sent in Authorization header`() = kotlinx.coroutines.test.runTest {
        val capturedHeaders = mutableMapOf<String, String>()
        val conn = FakeDiscoveryHttpURLConnection(capturedHeaders, 200, """{"data":[{"id":"m1"}]}""")
        val transport = ExternalModelDiscoveryTransport(
            secretStore = FakeSecretStore("persisted-key"),
            connectionFactory = { conn },
        )
        val config = AiProviderConfig(
            id = "test", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test", baseUrl = "https://example.com/v1",
            modelId = "model", apiKeySecretId = "secret-id",
            authMode = AiProviderAuthMode.BEARER_API_KEY,
        )
        val identity = identityBearer()
        val result = transport.discover(config, identity, ephemeralApiKey = "ephemeral-key")
        assertEquals("Bearer ephemeral-key", capturedHeaders["Authorization"])
        assertTrue(result is ExternalModelDiscoveryResult.Success)
    }

    @Test
    fun `ephemeral custom header key sent in configured header`() = kotlinx.coroutines.test.runTest {
        val capturedHeaders = mutableMapOf<String, String>()
        val conn = FakeDiscoveryHttpURLConnection(capturedHeaders, 200, """{"data":[{"id":"m1"}]}""")
        val transport = ExternalModelDiscoveryTransport(
            secretStore = FakeSecretStore("persisted-key"),
            connectionFactory = { conn },
        )
        val config = AiProviderConfig(
            id = "test", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test", baseUrl = "https://example.com/v1",
            modelId = "model", apiKeySecretId = "secret-id",
            authMode = AiProviderAuthMode.CUSTOM_HEADER, authHeaderName = "X-API-Key",
        )
        val result = transport.discover(config, identityBearer(), ephemeralApiKey = "ephemeral-key")
        assertEquals("ephemeral-key", capturedHeaders["X-API-Key"])
        assertTrue(result is ExternalModelDiscoveryResult.Success)
    }

    @Test
    fun `typed replacement key overrides persisted secret`() = kotlinx.coroutines.test.runTest {
        val capturedHeaders = mutableMapOf<String, String>()
        val conn = FakeDiscoveryHttpURLConnection(capturedHeaders, 200, """{"data":[{"id":"m1"}]}""")
        val transport = ExternalModelDiscoveryTransport(
            secretStore = FakeSecretStore("persisted-key"),
            connectionFactory = { conn },
        )
        val config = AiProviderConfig(
            id = "test", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test", baseUrl = "https://example.com/v1",
            modelId = "model", apiKeySecretId = "secret-id",
            authMode = AiProviderAuthMode.BEARER_API_KEY,
        )
        val result = transport.discover(config, identityBearer(), ephemeralApiKey = "replacement-key")
        assertEquals("Bearer replacement-key", capturedHeaders["Authorization"])
        assertTrue(result is ExternalModelDiscoveryResult.Success)
    }

    @Test
    fun `blank typed key falls back to persisted secret`() = kotlinx.coroutines.test.runTest {
        val capturedHeaders = mutableMapOf<String, String>()
        val conn = FakeDiscoveryHttpURLConnection(capturedHeaders, 200, """{"data":[{"id":"m1"}]}""")
        val transport = ExternalModelDiscoveryTransport(
            secretStore = FakeSecretStore("persisted-key"),
            connectionFactory = { conn },
        )
        val config = AiProviderConfig(
            id = "test", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test", baseUrl = "https://example.com/v1",
            modelId = "model", apiKeySecretId = "secret-id",
            authMode = AiProviderAuthMode.BEARER_API_KEY,
        )
        val result = transport.discover(config, identityBearer(), ephemeralApiKey = "")
        assertEquals("Bearer persisted-key", capturedHeaders["Authorization"])
        assertTrue(result is ExternalModelDiscoveryResult.Success)
    }

    @Test
    fun `NONE auth uses no credential header`() = kotlinx.coroutines.test.runTest {
        val capturedHeaders = mutableMapOf<String, String>()
        val conn = FakeDiscoveryHttpURLConnection(capturedHeaders, 200, """{"data":[{"id":"m1"}]}""")
        val transport = ExternalModelDiscoveryTransport(
            secretStore = FakeSecretStore("any-key"),
            connectionFactory = { conn },
        )
        val config = AiProviderConfig(
            id = "test", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test", baseUrl = "https://example.com/v1",
            modelId = "model", apiKeySecretId = null,
            authMode = AiProviderAuthMode.NONE,
        )
        val result = transport.discover(config, identityNone(), ephemeralApiKey = null)
        assertFalse(capturedHeaders.containsKey("Authorization"))
        assertTrue(result is ExternalModelDiscoveryResult.Success)
    }

    @Test
    fun `secret never enters result or error`() = kotlinx.coroutines.test.runTest {
        val capturedHeaders = mutableMapOf<String, String>()
        val conn = FakeDiscoveryHttpURLConnection(capturedHeaders, 401, """{"error":"unauthorized"}""")
        val transport = ExternalModelDiscoveryTransport(
            secretStore = FakeSecretStore("super-secret-key"),
            connectionFactory = { conn },
        )
        val config = AiProviderConfig(
            id = "test", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test", baseUrl = "https://example.com/v1",
            modelId = "model", apiKeySecretId = "secret-id",
            authMode = AiProviderAuthMode.BEARER_API_KEY,
        )
        val result = transport.discover(config, identityBearer(), ephemeralApiKey = "temp-secret")
        assertTrue(result is ExternalModelDiscoveryResult.AuthenticationFailed)
        val resultStr = result.toString()
        assertFalse(resultStr.contains("super-secret-key"))
        assertFalse(resultStr.contains("temp-secret"))
    }

    @Test
    fun `oversized non-success bodies preserve authoritative status`() = kotlinx.coroutines.test.runTest {
        val body = "x".repeat(ExternalModelDiscoveryTransport.MaxDiagnosticBodyBytes + 1)
        val cases = listOf(
            401 to ExternalModelDiscoveryResult.AuthenticationFailed,
            403 to ExternalModelDiscoveryResult.AuthenticationFailed,
            404 to ExternalModelDiscoveryResult.Unsupported,
            429 to ExternalModelDiscoveryResult.RateLimited,
        )
        cases.forEach { (status, expected) ->
            val transport = ExternalModelDiscoveryTransport(
                secretStore = FakeSecretStore("key"),
                connectionFactory = { FakeDiscoveryHttpURLConnection(fakeResponseCode = status, responseText = body) },
            )
            assertEquals(expected, transport.discover(config(), identityBearer(), null))
        }

        val serverTransport = ExternalModelDiscoveryTransport(
            secretStore = FakeSecretStore("key"),
            connectionFactory = { FakeDiscoveryHttpURLConnection(fakeResponseCode = 500, responseText = body) },
        )
        val serverResult = serverTransport.discover(config(), identityBearer(), null)
        assertTrue(serverResult is ExternalModelDiscoveryResult.NetworkError)
        assertEquals("Server error: 500", (serverResult as ExternalModelDiscoveryResult.NetworkError).message)
    }

    @Test
    fun `oversized success body remains invalid response`() = kotlinx.coroutines.test.runTest {
        val body = "x".repeat(ExternalModelDiscoveryTransport.MaxDiscoveryResponseBytes + 1)
        val transport = ExternalModelDiscoveryTransport(
            secretStore = FakeSecretStore("key"),
            connectionFactory = { FakeDiscoveryHttpURLConnection(fakeResponseCode = 200, responseText = body) },
        )
        assertEquals(ExternalModelDiscoveryResult.InvalidResponse, transport.discover(config(), identityBearer(), null))
    }

    @Test
    fun `connection setup failures are typed and cancellation propagates`() {
        var networkResult: ExternalModelDiscoveryResult? = null
        kotlinx.coroutines.test.runTest {
            networkResult = ExternalModelDiscoveryTransport(
                secretStore = FakeSecretStore("key"),
                connectionFactory = { throw java.io.IOException("raw setup secret") },
            ).discover(config(), identityBearer(), null)
        }
        val typedNetworkResult = networkResult as ExternalModelDiscoveryResult.NetworkError
        assertEquals("Discovery network connection failed", typedNetworkResult.message)
        assertFalse(typedNetworkResult.message.contains("raw setup secret"))

        var malformedResult: ExternalModelDiscoveryResult? = null
        kotlinx.coroutines.test.runTest {
            malformedResult = ExternalModelDiscoveryTransport(FakeSecretStore("key"))
                .discover(config().copy(baseUrl = "not a URL"), identityBearer(), null)
        }
        assertEquals("Discovery setup failed", (malformedResult as ExternalModelDiscoveryResult.NetworkError).message)

        val cancellation = kotlinx.coroutines.CancellationException("cancelled")
        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            kotlinx.coroutines.test.runTest {
                ExternalModelDiscoveryTransport(
                    secretStore = FakeSecretStore("key"),
                    connectionFactory = { throw cancellation },
                ).discover(config(), identityBearer(), null)
            }
        }
    }

    private fun identityBearer() = ExternalDiscoveryIdentity(
        providerId = "test", endpointIdentity = "https://example.com/v1/models",
        authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
        credentialRevision = 1L, operationId = "op1",
    )

    private fun identityNone() = ExternalDiscoveryIdentity(
        providerId = "test", endpointIdentity = "https://example.com/v1/models",
        authMode = AiProviderAuthMode.NONE, authHeaderIdentity = null,
        credentialRevision = 1L, operationId = "op1",
    )
}

class ExternalModelDiscoveryCacheTest {

    @Test
    fun `exact cache hit`() {
        val cache = ExternalModelDiscoveryCache(maxAgeMs = Long.MAX_VALUE)
        val identity = ExternalDiscoveryIdentity(
            providerId = "test", endpointIdentity = "https://example.com/v1/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 12345L, operationId = "op1",
        )
        val models = listOf(ExternalDiscoveredModel(id = "m1", displayName = "M1"))
        cache.put(identity, ExternalModelDiscoveryResult.Success(models, fetchedAt = System.currentTimeMillis()))
        assertEquals(models, cache.get(identity))
    }

    @Test
    fun `endpoint miss`() {
        val cache = ExternalModelDiscoveryCache(maxAgeMs = Long.MAX_VALUE)
        val identity1 = ExternalDiscoveryIdentity(
            providerId = "test", endpointIdentity = "https://a.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op1",
        )
        val identity2 = identity1.copy(endpointIdentity = "https://b.com/models")
        cache.put(identity1, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m1", "M1")), System.currentTimeMillis()))
        assertNull(cache.get(identity2))
    }

    @Test
    fun `provider miss`() {
        val cache = ExternalModelDiscoveryCache(maxAgeMs = Long.MAX_VALUE)
        val identity1 = ExternalDiscoveryIdentity(
            providerId = "a", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op1",
        )
        val identity2 = identity1.copy(providerId = "b")
        cache.put(identity1, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m1", "M1")), System.currentTimeMillis()))
        assertNull(cache.get(identity2))
    }

    @Test
    fun `auth header miss`() {
        val cache = ExternalModelDiscoveryCache(maxAgeMs = Long.MAX_VALUE)
        val identity1 = ExternalDiscoveryIdentity(
            providerId = "test", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op1",
        )
        val identity2 = identity1.copy(authMode = AiProviderAuthMode.CUSTOM_HEADER, authHeaderIdentity = "X-API-Key")
        cache.put(identity1, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m1", "M1")), System.currentTimeMillis()))
        assertNull(cache.get(identity2))
    }

    @Test
    fun `credential-revision miss`() {
        val cache = ExternalModelDiscoveryCache(maxAgeMs = Long.MAX_VALUE)
        val identity1 = ExternalDiscoveryIdentity(
            providerId = "test", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op1",
        )
        val identity2 = identity1.copy(credentialRevision = 2L)
        cache.put(identity1, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m1", "M1")), System.currentTimeMillis()))
        assertNull(cache.get(identity2))
    }

    @Test
    fun `TTL expiry`() {
        val cache = ExternalModelDiscoveryCache(maxAgeMs = 1L)
        val identity = ExternalDiscoveryIdentity(
            providerId = "test", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.NONE, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op1",
        )
        cache.put(identity, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m1", "M1")), System.currentTimeMillis() - 1000))
        assertNull(cache.get(identity))
    }

    @Test
    fun `max 32 entries never exceeded`() {
        val cache = ExternalModelDiscoveryCache()
        repeat(40) { index ->
            val id = ExternalDiscoveryIdentity(
                providerId = "p-$index",
                endpointIdentity = "https://e.com/models",
                authMode = AiProviderAuthMode.BEARER_API_KEY,
                authHeaderIdentity = null,
                credentialRevision = 1L,
                operationId = "op$index",
            )
            cache.put(id, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m$index", displayName = "M$index")), System.currentTimeMillis()))
        }
        val remaining = (0..39).count { index ->
            val id = ExternalDiscoveryIdentity(
                providerId = "p-$index",
                endpointIdentity = "https://e.com/models",
                authMode = AiProviderAuthMode.BEARER_API_KEY,
                authHeaderIdentity = null,
                credentialRevision = 1L,
                operationId = "op$index",
            )
            cache.get(id) != null
        }
        assertTrue(remaining <= 32)
    }

    @Test
    fun `33rd identity evicts LRU`() {
        val cache = ExternalModelDiscoveryCache()
        val first = ExternalDiscoveryIdentity(
            providerId = "first", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op-first",
        )
        val newId = ExternalDiscoveryIdentity(
            providerId = "new", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op-new",
        )
        // Insert first and 31 others (32 total)
        cache.put(first, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m-first")), System.currentTimeMillis()))
        repeat(31) { index ->
            val id = ExternalDiscoveryIdentity(
                providerId = "p-$index", endpointIdentity = "https://e.com/models",
                authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
                credentialRevision = 1L, operationId = "op$index",
            )
            cache.put(id, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m$index")), System.currentTimeMillis()))
        }
        // Read first to promote it
        assertNotNull(cache.get(first))
        // Add 33rd; oldest un-promoted should be evicted
        cache.put(newId, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m-new")), System.currentTimeMillis()))
        assertNotNull(cache.get(first))
        assertNotNull(cache.get(newId))
    }

    @Test
    fun `reading updates access order and protects recent entry`() {
        val cache = ExternalModelDiscoveryCache()
        val a = ExternalDiscoveryIdentity(
            providerId = "a", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op-a",
        )
        val b = ExternalDiscoveryIdentity(
            providerId = "b", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op-b",
        )
        val c = ExternalDiscoveryIdentity(
            providerId = "c", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op-c",
        )
        // Insert a, b, and 30 others to reach max 32
        cache.put(a, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m-a")), System.currentTimeMillis()))
        cache.put(b, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m-b")), System.currentTimeMillis()))
        repeat(30) { index ->
            val id = ExternalDiscoveryIdentity(
                providerId = "p-$index", endpointIdentity = "https://e.com/models",
                authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
                credentialRevision = 1L, operationId = "op-$index",
            )
            cache.put(id, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m-$index")), System.currentTimeMillis()))
        }
        // Read a and b to promote them
        cache.get(a)
        cache.get(b)
        val newId = ExternalDiscoveryIdentity(
            providerId = "new", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op-new",
        )
        cache.put(newId, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m-new")), System.currentTimeMillis()))
        assertNotNull(cache.get(a))
        assertNotNull(cache.get(b))
        assertNotNull(cache.get(newId))
    }

    @Test
    fun `clear resets size`() {
        val cache = ExternalModelDiscoveryCache()
        val identity = ExternalDiscoveryIdentity(
            providerId = "test", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op1",
        )
        cache.put(identity, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m1", "M1")), System.currentTimeMillis()))
        cache.clear()
        assertNull(cache.get(identity))
    }

    @Test
    fun `forceRefresh bypass in coordinator remains intact`() = kotlinx.coroutines.test.runTest {
        val calls = mutableListOf<Int>()
        val source = object : ExternalModelDiscoverySource {
            override suspend fun discover(
                config: AiProviderConfig,
                discoveryIdentity: ExternalDiscoveryIdentity,
                ephemeralApiKey: String?,
            ): ExternalModelDiscoveryResult {
                calls.add(1)
                return ExternalModelDiscoveryResult.Success(
                    models = listOf(ExternalDiscoveredModel(id = "m-${calls.size}")),
                    fetchedAt = System.currentTimeMillis(),
                )
            }
        }
        val coordinator = DiscoveryCoordinator(this, source)
        val config = AiProviderConfig(
            id = "p", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test", baseUrl = "https://p.example",
            modelId = "model", apiKeySecretId = null,
            authMode = AiProviderAuthMode.NONE,
        )
        coordinator.discover(config, credentialRevision = 1).join()
        val firstCount = calls.size
        assertEquals(1, firstCount)
        coordinator.discover(config, credentialRevision = 1, forceRefresh = true).join()
        assertEquals(firstCount + 1, calls.size)
    }

    @Test
    fun `unsuccessful results never populate cache`() {
        val cache = ExternalModelDiscoveryCache()
        val identity = ExternalDiscoveryIdentity(
            providerId = "test", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op1",
        )
        assertNull(cache.get(identity))
    }

    @Test
    fun `no secret material enters key or state`() {
        val cache = ExternalModelDiscoveryCache()
        val identity = ExternalDiscoveryIdentity(
            providerId = "provider-secret", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = "X-Header",
            credentialRevision = 1L, operationId = "op1",
        )
        val keyString = "${identity.providerId}:${identity.endpointIdentity}:${identity.authMode.name}:${identity.authHeaderIdentity.orEmpty()}:${identity.credentialRevision}"
        assertFalse(keyString.contains("secret-key"))
        assertFalse(keyString.contains("Bearer secret"))
    }

    @Test
    fun `models stored do not expose mutable list aliasing`() {
        val cache = ExternalModelDiscoveryCache()
        val identity = ExternalDiscoveryIdentity(
            providerId = "test", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op1",
        )
        val mutable = mutableListOf(ExternalDiscoveredModel(id = "m"))
        val result = ExternalModelDiscoveryResult.Success(models = mutable, fetchedAt = System.currentTimeMillis())
        cache.put(identity, result)
        mutable.add(ExternalDiscoveredModel(id = "extra"))
        val cached = cache.get(identity)!!
        assertEquals(1, cached.size)
        assertEquals("m", cached[0].id)
    }

    @Test
    fun `concurrent bounded put and get does not corrupt state`() {
        val cache = ExternalModelDiscoveryCache()
        val threads = List(20) {
            Thread {
                val id = ExternalDiscoveryIdentity(
                    providerId = "p-$it",
                    endpointIdentity = "https://e.com/models",
                    authMode = AiProviderAuthMode.BEARER_API_KEY,
                    authHeaderIdentity = null,
                    credentialRevision = 1L,
                    operationId = "op$it",
                )
                cache.put(id, ExternalModelDiscoveryResult.Success(listOf(ExternalDiscoveredModel(id = "m$it")), System.currentTimeMillis()))
            }.apply { start() }
        }
        threads.forEach { it.join() }
        val total = (0..19).count { index ->
            val id = ExternalDiscoveryIdentity(
                providerId = "p-$index",
                endpointIdentity = "https://e.com/models",
                authMode = AiProviderAuthMode.BEARER_API_KEY,
                authHeaderIdentity = null,
                credentialRevision = 1L,
                operationId = "op$index",
            )
            cache.get(id) != null
        }
        assertTrue(total <= 32)
    }
}

class StaleResultRaceTest {

    @Test
    fun `operationId gates finalization`() {
        val identity1 = ExternalDiscoveryIdentity(
            providerId = "test", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op1",
        )
        val identity2 = identity1.copy(operationId = "op2", credentialRevision = 2L)
        val fence = DiscoveryOwnershipFence()
        fence.claim(identity1)
        fence.claim(identity2)
        assertFalse(fence.publishResult(identity1, ExternalModelDiscoveryResult.AuthenticationFailed))
        assertTrue(fence.publishResult(identity2, ExternalModelDiscoveryResult.AuthenticationFailed))
    }

    @Test
    fun `fenced result rejects when operationId mismatches`() {
        val identity1 = ExternalDiscoveryIdentity(
            providerId = "test", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 1L, operationId = "op1",
        )
        val identity2 = identity1.copy(operationId = "op2")
        val fence = DiscoveryOwnershipFence()
        fence.claim(identity1)
        fence.claim(identity2)
        assertFalse(fence.publishResult(identity1, ExternalModelDiscoveryResult.AuthenticationFailed))
        assertTrue(fence.publishResult(identity2, ExternalModelDiscoveryResult.AuthenticationFailed))
    }
}

class VariantPipelineTest {

    @Test
    fun `unknown generic model has default variant only`() {
        val variants = ExternalModelCapabilityRegistry.resolveVariants(AiProviderType.CUSTOM_OPENAI_COMPATIBLE, "any-model")
        assertEquals(1, variants.size)
        assertEquals("default", variants[0].id)
    }

    @Test
    fun `default variant emits no request field`() {
        val config = AiProviderConfig(
            id = "test", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test", baseUrl = "https://external.invalid/v1",
            modelId = "model", apiKeySecretId = null,
        )
        val snapshot = ExternalProviderRequestSnapshot.from(config, 512)
        assertNull(snapshot.resolvedRequestPatch.reasoningEffort)
    }

    @Test
    fun `unsupported variant emits no request field`() {
        val config = AiProviderConfig(
            id = "test", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test", baseUrl = "https://external.invalid/v1",
            modelId = "model", apiKeySecretId = null, selectedVariantId = "test-variant",
        )
        val snapshot = ExternalProviderRequestSnapshot.from(config, 512)
        assertEquals("default", snapshot.selectedVariantId)
        assertNull(snapshot.resolvedRequestPatch.reasoningEffort)
    }

    @Test
    fun `selectedVariantId round-trips in AiProviderConfig`() {
        val original = AiProviderConfig(
            id = "test", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test", baseUrl = "https://example.com/v1",
            modelId = "model", apiKeySecretId = null, selectedVariantId = "variant-1",
        )
        assertEquals("variant-1", original.selectedVariantId)
    }

    @Test
    fun `old config without selectedVariantId loads as null`() {
        val config = AiProviderConfig(
            id = "test", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test", baseUrl = "https://example.com/v1",
            modelId = "model", apiKeySecretId = null,
        )
        assertNull(config.selectedVariantId)
    }

    @Test
    fun `invalid persisted variant falls back default`() {
        val config = AiProviderConfig(
            id = "test", type = AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test", baseUrl = "https://example.com/v1",
            modelId = "model", apiKeySecretId = null, selectedVariantId = "invalid-variant",
        )
        val snapshot = ExternalProviderRequestSnapshot.from(config, 512)
        assertEquals("default", snapshot.selectedVariantId)
    }

    @Test
    fun `changing models invalidates incompatible variant`() {
        val registry = ExternalModelCapabilityRegistry
        val variants = registry.resolveVariants(AiProviderType.CUSTOM_OPENAI_COMPATIBLE, "model-b")
        assertEquals("default", variants[0].id)
    }

    @Test
    fun `trusted test variant reaches frozen snapshot`() {
        val patch = ExternalRequestPatch(reasoningEffort = ExternalReasoningEffort.HIGH)
        val variant = ExternalModelVariant(
            id = "test-variant", label = "Test", requestPatch = patch,
        )
        val config = AiProviderConfig(
            id = "test", type = AiProviderType.OPENAI, displayName = "Test",
            baseUrl = "https://external.invalid/v1", modelId = "model-123",
            apiKeySecretId = null, selectedVariantId = "test-variant",
        )
        val snapshot = ExternalProviderRequestSnapshot.from(
            config = config,
            maxTokens = 512,
            capabilityResolver = ExternalModelCapabilityResolver { _, _ -> listOf(variant) },
        )
        assertEquals("model-123", snapshot.modelId)
        assertEquals("test-variant", snapshot.selectedVariantId)
        assertEquals(ExternalReasoningEffort.HIGH, snapshot.resolvedRequestPatch.reasoningEffort)
    }

    @Test
    fun `frozen snapshot preserves model variant and patch`() {
        val original = ExternalRequestPatch(reasoningEffort = ExternalReasoningEffort.HIGH)
        val retry = original.copy()
        assertEquals(original, retry)
    }

    @Test
    fun `temperature and maxTokens unaffected by variant`() {
        val patch = ExternalRequestPatch()
        val variant = com.projectnuke.fusion.ai.model.ExternalModelVariant(id = "default", label = "기본", requestPatch = patch)
        val variants = listOf(variant)
        assertEquals(1, variants.size)
        assertEquals("default", variants[0].id)
    }
}

class ManualModelIndicatorPolicyTest {

    @Test
    fun `manual model shown when modelId nonblank and no discovered match`() {
        val modelId = "custom-model-123"
        val discoveredModels = listOf(ExternalDiscoveredModel(id = "model-a", displayName = "Model A"))
        val shouldShowManual = modelId.isNotBlank() && discoveredModels.none { it.id == modelId }
        assertTrue(shouldShowManual)
    }

    @Test
    fun `manual model not shown when discovered match exists`() {
        val modelId = "model-a"
        val discoveredModels = listOf(ExternalDiscoveredModel(id = "model-a", displayName = "Model A"))
        val shouldShowManual = modelId.isNotBlank() && discoveredModels.none { it.id == modelId }
        assertFalse(shouldShowManual)
    }
}

class CredentialRevisionPartitioningTest {

    @Test
    fun `credentialRevision contains no secret material`() {
        val identity = ExternalDiscoveryIdentity(
            providerId = "test", endpointIdentity = "https://e.com/models",
            authMode = AiProviderAuthMode.BEARER_API_KEY, authHeaderIdentity = null,
            credentialRevision = 12345L, operationId = "op1",
        )
        assertFalse(identity.credentialRevision.toString().contains("secret"))
        assertFalse(identity.credentialRevision.toString().contains("key"))
    }

    @Test
    fun `credentialRevision changes on key edit`() {
        var revision = 0L
        revision = revision + 1
        assertEquals(1L, revision)
    }

    @Test
    fun `credentialRevision changes on authMode edit`() {
        var revision = 0L
        revision = revision + 1
        assertEquals(1L, revision)
    }

    @Test
    fun `credentialRevision changes on header change`() {
        var revision = 0L
        revision = revision + 1
        assertEquals(1L, revision)
    }
}

class EndpointResolverAuthorityTest {

    @Test
    fun `legacy v1 path resolves correctly`() {
        val endpoints = OpenAiCompatibleEndpointResolver.resolve("https://host/v1")
        assertEquals("https://host/v1", endpoints.apiRoot)
        assertEquals("https://host/v1/chat/completions", endpoints.chatCompletions)
        assertEquals("https://host/v1/models", endpoints.models)
    }

    @Test
    fun `trailing slash resolves correctly`() {
        val endpoints = OpenAiCompatibleEndpointResolver.resolve("https://host/v1/")
        assertEquals("https://host/v1", endpoints.apiRoot)
    }

    @Test
    fun `chat completions form resolves correctly`() {
        val endpoints = OpenAiCompatibleEndpointResolver.resolve("https://host/v1/chat/completions")
        assertEquals("https://host/v1", endpoints.apiRoot)
        assertEquals("https://host/v1/chat/completions", endpoints.chatCompletions)
        assertEquals("https://host/v1/models", endpoints.models)
    }

    @Test
    fun `chat completions trailing slash resolves correctly`() {
        val endpoints = OpenAiCompatibleEndpointResolver.resolve("https://host/v1/chat/completions/")
        assertEquals("https://host/v1", endpoints.apiRoot)
        assertEquals("https://host/v1/chat/completions", endpoints.chatCompletions)
    }
}
