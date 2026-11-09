package com.projectnuke.fusion.search

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.ai.secure.SecretStore
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ManualSearchQuery
import com.projectnuke.fusion.chat.ManualSearchQueryConsumptionPolicy
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import com.projectnuke.fusion.chat.ManualSearchQueryValidation
import com.projectnuke.fusion.test.PlatformSharedPreferences
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchManualTransportBoundaryTest {
    private class TestContext(
        private val root: java.io.File,
        private val prefs: SharedPreferences,
    ) : ContextWrapper(null) {
        override fun getFilesDir(): java.io.File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    private class FakeSecrets : SecretStore {
        override suspend fun putSecret(id: String, value: String): Boolean = true
        override suspend fun getSecret(id: String): String? = null
        override suspend fun deleteSecret(id: String): Boolean = true
    }

    private class RecordingConnection(
        url: URL,
        private val onEmission: () -> Unit,
        private val failOnEmission: Boolean = false,
    ) : HttpURLConnection(url) {
        override fun connect() { connected = true }
        override fun disconnect() { connected = false }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int {
            onEmission()
            if (failOnEmission) throw IOException("synthetic connection failure")
            return HTTP_OK
        }
        override fun getContentType(): String = if (url.host == "html.duckduckgo.com") "text/html" else "application/json"
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = null
    }

    @Test
    fun `missing manual provider requirements retain frozen query until fallback reaches HTTP`() = runBlocking {
        val fixture = repository(enabled = true, allowFallback = false, noAuth = false)
        val emitted = AtomicInteger()
        val opened = AtomicInteger()
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                opened.incrementAndGet()
                RecordingConnection(url, emitted::incrementAndGet)
            },
        )
        try {
            val noFallback = execute(fixture.repository)
            assertEquals(1, noFallback.searchQueriesIssued)
            assertEquals(0, noFallback.exactTransportAttempts)
            assertEquals(0, opened.get())
            assertFalse(consume(noFallback, CURRENT_QUERY, 4L, 4L))

            assertTrue(fixture.repository.saveProvider(config(enabled = true, allowFallback = true, noAuth = false)))
            val withFallback = execute(fixture.repository)
            assertEquals(1, withFallback.searchQueriesIssued)
            assertTrue(withFallback.exactTransportAttempts > 0)
            assertEquals(emitted.get(), withFallback.exactTransportAttempts)
            assertTrue(opened.get() > 0)
            assertTrue(consume(withFallback, CURRENT_QUERY, 4L, 4L))
        } finally {
            FusionWebSearch.httpClientForTests = null
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `configured manual provider sends the exact frozen query and counts failed dispatch`() = runBlocking {
        val fixture = repository(enabled = true, allowFallback = false)
        val emitted = AtomicInteger()
        val outboundQueries = Collections.synchronizedList(mutableListOf<String>())
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                val encoded = url.query.substringAfter("q=", "").substringBefore('&')
                if (encoded.isNotEmpty()) outboundQueries += URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                RecordingConnection(url, emitted::incrementAndGet, failOnEmission = true)
            },
        )
        try {
            val routed = execute(fixture.repository)
            assertEquals(1, routed.searchQueriesIssued)
            assertEquals(1, routed.exactTransportAttempts)
            assertEquals(1, emitted.get())
            assertEquals(listOf(CURRENT_QUERY), outboundQueries)
            assertFalse(ORIGINAL_CONVERSATION in outboundQueries)
            assertTrue(consume(routed, CURRENT_QUERY, 9L, 9L))
            assertFalse(consume(routed, "newer edit", 9L, 10L))

            val repeated = execute(fixture.repository)
            assertEquals(2, outboundQueries.size)
            assertEquals(CURRENT_QUERY, outboundQueries.last())
            assertEquals(1, repeated.exactTransportAttempts)
        } finally {
            FusionWebSearch.httpClientForTests = null
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `authorization revoked after provider preflight does not count or consume query`() = runBlocking {
        val fixture = repository(enabled = true, allowFallback = false)
        var stillAuthorized = true
        val emitted = AtomicInteger()
        var routed: SearchStrategyRouteResult? = null
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                stillAuthorized = false
                RecordingConnection(url, emitted::incrementAndGet)
            },
        )
        try {
            routed = execute(fixture.repository) { stillAuthorized }
            assertEquals(SearchRouteOutcome.PERMISSION_REVOKED, routed?.outcome)
            assertEquals(0, routed?.exactTransportAttempts)
            assertEquals(0, emitted.get())
            assertFalse(consume(requireNotNull(routed), CURRENT_QUERY, 12L, 12L))
        } finally {
            FusionWebSearch.httpClientForTests = null
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `cancellation while opening connection completes before any HTTP emission`() = runBlocking {
        val fixture = repository(enabled = true, allowFallback = false)
        val opened = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val emitted = AtomicInteger()
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                opened.countDown()
                check(releaseFactory.await(5, TimeUnit.SECONDS))
                RecordingConnection(url, emitted::incrementAndGet)
            },
        )
        try {
            val task = async(Dispatchers.IO) { execute(fixture.repository) }
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            task.cancel()
            releaseFactory.countDown()
            val result = runCatching { task.await() }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(0, emitted.get())
        } finally {
            releaseFactory.countDown()
            FusionWebSearch.httpClientForTests = null
            fixture.root.deleteRecursively()
        }
    }

    private data class RepositoryFixture(
        val root: java.io.File,
        val repository: WebSearchProviderRepository,
    )

    private suspend fun repository(enabled: Boolean, allowFallback: Boolean, noAuth: Boolean = true): RepositoryFixture {
        val root = Files.createTempDirectory("fusion-manual-search").toFile()
        val prefs = PlatformSharedPreferences()
        val repository = WebSearchProviderRepository(TestContext(root, prefs), FakeSecrets())
        assertTrue(repository.saveProvider(config(enabled, allowFallback, noAuth)))
        assertTrue(repository.setSelectedMode(WebSearchMode.MANUAL))
        assertTrue(repository.setSelectedProvider("custom"))
        return RepositoryFixture(root, repository)
    }

    private fun config(enabled: Boolean, allowFallback: Boolean, noAuth: Boolean = true) = WebSearchProviderConfig(
        id = "custom",
        displayName = "Test provider",
        type = WebSearchProviderType.CUSTOM_COMPATIBLE,
        isEnabled = enabled,
        baseUrl = "https://search.example.test/api/search",
        allowFallbackInManualMode = allowFallback,
        noAuth = noAuth,
    )

    private suspend fun execute(
        providerRepository: WebSearchProviderRepository,
        authorized: () -> Boolean = { true },
    ): SearchStrategyRouteResult {
        val exactSearch = ResearchSearchOwner.productionExactSearchTool(providerRepository)
        val authorizer = AgenticSearchQueryAuthorizer { _, proposed ->
            AgenticQueryAuthorization.Authorized(proposed)
        }
        val router = SearchStrategyRouter(exactSearch, authorizer, RuleBasedLegacySearchFallback(exactSearch, authorizer))
        val request = SearchStrategyRouteRequest(
            agentRequest = AgenticSearchRequest(
                requestId = "manual-test-request",
                originalUserQuery = ORIGINAL_CONVERSATION,
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                limits = AgenticSearchLimits.EXTERNAL_DEFAULT,
                ownerStillActive = { true },
                searchPermissionStillValid = authorized,
            ),
            strategy = SearchStrategySnapshot(
                strategy = SearchStrategy.MANUAL_QUERY,
                allowLegacyFallback = false,
                manualQuery = ManualSearchQuery.parse(CURRENT_QUERY),
            ),
            previousUserMessage = ORIGINAL_CONVERSATION,
        )
        return router.execute(request, agentModel = null)
    }

    private fun consume(result: SearchStrategyRouteResult, currentInput: String, frozenRevision: Long, currentRevision: Long) =
        ManualSearchQueryConsumptionPolicy.shouldConsume(
            strategy = SearchStrategy.MANUAL_QUERY,
            query = ManualSearchQuery.parse(CURRENT_QUERY),
            frozenInputRevision = frozenRevision,
            currentInputRevision = currentRevision,
            currentInput = currentInput,
            exactTransportAttempts = result.exactTransportAttempts,
        )

    private companion object {
        const val CURRENT_QUERY = "검색어 정확도 보존 0.75"
        const val ORIGINAL_CONVERSATION = "원래 대화 본문은 검색어가 아닙니다"
    }
}
