package com.projectnuke.fusion.search

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.ai.secure.SecretStore
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.system.measureNanoTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionWebSearchTest {
    private class InMemoryPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = error("not needed")
    }

    private class TestContext(
        private val root: File,
        private val prefs: InMemoryPrefs,
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    private class CountingSecretStore : SecretStore {
        val secretValues = mutableMapOf("retained-api-key" to "retained-secret")
        var getSecretCalls = 0

        override suspend fun putSecret(id: String, value: String): Boolean {
            secretValues[id] = value
            return true
        }

        override suspend fun getSecret(id: String): String? {
            getSecretCalls += 1
            return secretValues[id]
        }

        override suspend fun deleteSecret(id: String): Boolean {
            secretValues.remove(id)
            return true
        }
    }

    @Test
    fun `unsafe source URL is removed while title and snippet survive`() {
        val sanitized = FusionWebSearch.sanitizeSearchResultForTest(
            FusionSearchResult(
                title = "Useful title",
                source = "provider",
                url = "javascript:alert(1)",
                snippet = "Useful snippet",
                publishedAt = null,
                queryUsed = "query",
            ),
        )

        assertNull(sanitized.url)
        assertEquals("Useful title", sanitized.title)
        assertEquals("Useful snippet", sanitized.snippet)
    }

    @Test
    fun `safe source URL is trimmed and preserved`() {
        val sanitized = FusionWebSearch.sanitizeSearchResultForTest(
            FusionSearchResult(
                title = "Useful title",
                source = "provider",
                url = "  https://example.com/source  ",
                snippet = "Useful snippet",
                publishedAt = null,
                queryUsed = "query",
            ),
        )

        assertEquals("https://example.com/source", sanitized.url)
    }

    @Test
    fun `unsafe URL does not count as valid search evidence`() {
        val sanitized = FusionWebSearch.sanitizeSearchResultForTest(
            FusionSearchResult(
                title = "Useful title",
                source = "provider",
                url = "intent://example.com/#Intent;scheme=https;end",
                snippet = "Useful snippet",
                publishedAt = null,
                queryUsed = "query",
            ),
        )

        val quality = FusionWebSearch.evaluateResultQualityForTest(
            results = listOf(sanitized),
            query = "query",
            intent = SearchIntent.GENERAL,
        )
        assertTrue(quality.reasons.contains("valid=0"))
    }

    @Test
    fun `search HTML normalization strips tags and decodes entities once`() {
        assertEquals("Hello", normalizeSearchHtmlText("<b>Hello</b>"))
        assertEquals("hello world", normalizeSearchHtmlText("<span\n class=\"x\">hello</span> world"))
        assertEquals("&", normalizeSearchHtmlText("&amp;"))
        assertEquals("<", normalizeSearchHtmlText("&lt;"))
        assertEquals("&lt;", normalizeSearchHtmlText("&amp;lt;"))
        assertEquals("&amp;", normalizeSearchHtmlText("&amp;amp;"))
        assertEquals("' \" ' < >", normalizeSearchHtmlText("&apos; &quot; &#x27; &lt; &gt;"))
        assertEquals("A B C D", normalizeSearchHtmlText("A&#32;B&#x20;C&nbsp;D"))
    }

    @Test
    fun `search HTML normalization preserves invalid and unknown entities`() {
        assertEquals("&#x110000; &#999999999999999999999; &unknown;", normalizeSearchHtmlText("&#x110000; &#999999999999999999999; &unknown;"))
        assertEquals("before after", normalizeSearchHtmlText("before <div\n class=\"x\">\n</div> after"))
    }

    @Test
    fun `provider title and snippet fragments retain visible text`() {
        assertEquals("네이버 제목", normalizeSearchHtmlText("<b>네이버</b> 제목"))
        assertEquals("카카오 & 다음", normalizeSearchHtmlText("카카오 &amp; 다음"))
        assertEquals("Brave <result>", normalizeSearchHtmlText("Brave &lt;result&gt;"))
        assertEquals("2 < 3 and 4 > 1", normalizeSearchHtmlText("2 < 3 and 4 > 1"))
        assertEquals("x < y > z", normalizeSearchHtmlText("x < y > z"))
        assertEquals("I <3 this", normalizeSearchHtmlText("I <3 this"))
    }

    @Test
    fun `DuckDuckGo URL cleanup decodes query separators once`() {
        assertEquals(
            "https://example.com/search?a=1&b=2",
            FusionWebSearch.cleanDuckDuckGoUrlForTest(
                "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fsearch%3Fa%3D1%26b%3D2&amp;rut=ignored",
            ),
        )
        assertEquals(
            "https://example.com/?value=%26lt%3B",
            FusionWebSearch.cleanDuckDuckGoUrlForTest(
                "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2F%3Fvalue%3D%2526lt%253B&amp;rut=ignored",
            ),
        )
    }

    @Test
    fun `DuckDuckGo HTML scanner extracts nested results with encoded fields and safe URLs`() {
        val html = """
            <div data-rank='1' class='other results_links result'>
              <div class='result__body'><h2><a href='https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fstory%3Fa%3D1%26b%3D2&amp;rut=x' rel='nofollow' class='result__a'>한글 &amp; <b>제목</b></a></h2>
              <div data-x='>' class='result__snippet'>요약 &lt;안전&gt;</div></div>
            </div>
            <div class="result results_links"><a class="result__a" href="javascript:alert(1)">unsafe</a></div>
            <div class="result results_links"><a class="result__a" href="https://user:secret@example.com/">credential</a></div>
        """.trimIndent()
        val parsed = FusionWebSearch.parseDuckDuckGoHtmlForTest(html)
        assertEquals(1, parsed.size)
        assertEquals("한글 & 제목", parsed.single().title)
        assertEquals("요약 <안전>", parsed.single().snippet)
        assertEquals("https://example.com/story?a=1&b=2", parsed.single().url)
        assertFalse(parsed.single().url.orEmpty().contains("secret"))
    }

    @Test
    fun `DuckDuckGo Lite scanner handles reordered and single quoted attributes`() {
        val html = """
            <table><tr data-id='1'><td><a href='https://result.example.test/p?a=1&amp;b=2' rel='nofollow' class='result-link extra'>결과 &amp; 제목</a></td><td>요약 문장</td></tr></table>
            <tr><td><a class="result-link" href="/relative">상대 경로</a></td></tr>
        """.trimIndent()
        val parsed = FusionWebSearch.parseDuckDuckGoLiteForTest(html)
        assertEquals(1, parsed.size)
        assertEquals("결과 & 제목", parsed.single().title)
        assertEquals("https://result.example.test/p?a=1&b=2", parsed.single().url)
        assertTrue(parsed.single().snippet.orEmpty().contains("요약 문장"))
    }

    @Test(timeout = 10_000)
    fun `DuckDuckGo malformed blocks and rows scan bounded response without suffix rescans`() {
        val malformedHtml = "<div class='result results_links'>".repeat(60_000)
        val malformedLite = "<tr><td>".repeat(60_000)
        assertTrue(FusionWebSearch.parseDuckDuckGoHtmlForTest(malformedHtml).isEmpty())
        assertTrue(FusionWebSearch.parseDuckDuckGoLiteForTest(malformedLite).isEmpty())

        val repeatedPrefixes = "<div class='result results_links'><div>".repeat(30_000)
        assertTrue(FusionWebSearch.parseDuckDuckGoHtmlForTest(repeatedPrefixes).isEmpty())
    }

    @Test(timeout = 20_000)
    fun `warmed DuckDuckGo parsers scale across full malformed response`() {
        val sizes = listOf(32 * 1024, 128 * 1024, 512 * 1024)
        val rows = ArrayList<String>()
        for ((label, makeInput, parse) in listOf(
            Triple("html-unclosed-nested-div", { n: Int -> "<div class='result results_links'>".repeat(n / 34 + 1).take(n) }, { s: String -> FusionWebSearch.parseDuckDuckGoHtmlForTest(s) }),
            Triple("lite-unclosed-rows", { n: Int -> "<tr><td>".repeat(n / 9 + 1).take(n) }, { s: String -> FusionWebSearch.parseDuckDuckGoLiteForTest(s) }),
        )) {
            for (size in sizes) {
                val input = makeInput(size)
                repeat(5) { assertTrue(parse(input).isEmpty()) }
                val samples = List(9) {
                    var output: List<FusionSearchResult> = emptyList()
                    val elapsed = measureNanoTime { output = parse(input) }
                    assertTrue("malformed response unexpectedly yielded a result for $label/$size", output.isEmpty())
                    elapsed / 1_000_000.0
                }.sorted()
                rows += "$label inputChars=${input.length} fullInput=true medianMs=${"%.3f".format(java.util.Locale.ROOT, samples[samples.size / 2])}"
            }
        }
        System.out.println("P7_DDG_PERF jdk=${System.getProperty("java.version")} vendor=${System.getProperty("java.vendor")} ${rows.joinToString(" | ")}")
    }

    @Test
    fun `DuckDuckGo parser observes cancellation while scanning a large response`() {
        var checks = 0
        try {
            FusionWebSearch.parseDuckDuckGoHtmlForTest("x".repeat(100_000)) {
                if (++checks == 2) throw CancellationException("test cancellation")
            }
            throw AssertionError("expected parser cancellation")
        } catch (_: CancellationException) {
            assertTrue(checks >= 2)
        }

        var nameChecks = 0
        try {
            FusionWebSearch.parseDuckDuckGoHtmlForTest("<" + "a".repeat(100_000)) {
                if (++nameChecks == 1) throw CancellationException("long tag cancellation")
            }
            throw AssertionError("expected cancellation during long tag-name scan")
        } catch (_: CancellationException) {
            assertEquals(1, nameChecks)
        }
    }

    @Test
    fun `request authorization is rechecked after provider preparation and before HTTP emission`() = runTest {
        var authorized = true
        var emittedRequests = 0
        var openedConnections = 0
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                openedConnections++
                // Model revocation in the gap after provider preflight and before HttpURLConnection emits.
                authorized = false
                object : HttpURLConnection(url) {
                    override fun connect() { connected = true }
                    override fun disconnect() { connected = false }
                    override fun usingProxy(): Boolean = false
                    override fun getResponseCode(): Int {
                        emittedRequests++
                        return 200
                    }
                    override fun getContentLengthLong(): Long = 0L
                    override fun getContentType(): String = "text/html; charset=utf-8"
                    override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
                    override fun getErrorStream() = null
                }
            },
        )
        try {
            FusionWebSearch.searchFreeDefaultForTest(
                query = "public query",
                authorizationStillValid = { authorized },
            )
            assertEquals("The test must reach the bounded HTTP client", 1, openedConnections)
            assertEquals("Revocation before request emission must block transport", 0, emittedRequests)
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
    }

    @Test
    fun `empty malformed DuckDuckGo HTML allows the default provider chain to use Lite`() = runBlocking {
        val requests = mutableListOf<String>()
        val encodedTarget = java.net.URLEncoder.encode("https://lite.example.test/ok?a=1&b=2", "UTF-8")
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                requests += url.host + url.path
                val body = if (url.host == "html.duckduckgo.com") {
                    "<div class='result results_links'>unclosed".repeat(20_000)
                } else if (url.host == "lite.duckduckgo.com") {
                    "<tr><td><a rel='nofollow' class='result-link' href='https://duckduckgo.com/l/?uddg=$encodedTarget'>Lite &amp; result</a></td><td>Snippet &lt;safe&gt;</td></tr>"
                } else {
                    "{}"
                }
                RecordingConnection(url, responseBody = body)
            },
        )
        try {
            val results = FusionWebSearch.searchFreeDefaultForTest("fallback query")
            assertEquals(listOf("Lite & result"), results.map { it.title })
            assertEquals("Snippet <safe>", results.single().snippet)
            assertEquals("https://lite.example.test/ok?a=1&b=2", results.single().url)
            assertEquals(listOf("html.duckduckgo.com/html/", "lite.duckduckgo.com/lite/"), requests)
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
    }

    private open class RecordingConnection(
        url: URL,
        private val responseBody: String = """{"results":[{"title":"result","url":"https://result.example.test"}]}""",
        private val responseDelayMs: Long = 0L,
    ) : HttpURLConnection(url) {
        val requestHeaders = mutableMapOf<String, String>()
        var disconnected = false

        override fun setRequestProperty(key: String?, value: String?) {
            if (key != null && value != null) requestHeaders[key] = value
        }

        override fun connect() {
            connected = true
        }

        override fun disconnect() {
            disconnected = true
            connected = false
        }

        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int {
            if (responseDelayMs > 0L) Thread.sleep(responseDelayMs)
            return 200
        }
        override fun getContentLengthLong(): Long = responseBody.toByteArray(Charsets.UTF_8).size.toLong()
        override fun getContentType(): String = "application/json; charset=utf-8"
        override fun getInputStream() = ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8))
        override fun getErrorStream() = null
    }

    @Test
    fun `persisted custom runtime honors noAuth and authenticated transport boundaries`() = runTest {
        val dir = Files.createTempDirectory("fusion-web-search-runtime").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] = JSONArray().put(
            JSONObject()
                .put("id", "persisted-custom")
                .put("displayName", "Persisted Custom")
                .put("type", "CUSTOM_COMPATIBLE")
                .put("isEnabled", true)
                .put("baseUrl", "http://search.example.test/api")
                .put("apiKeySecretId", "retained-api-key")
                .put("noAuth", true)
        ).toString()
        val secrets = CountingSecretStore()
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), secrets)
        val persisted = repo.listProviders().first { it.id == "persisted-custom" }

        var noAuthOpens = 0
        val noAuthConnection = RecordingConnection(URL("http://search.example.test/api?q=query"))
        val noAuthClient = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory {
                noAuthOpens += 1
                noAuthConnection
            }
        )
        val noAuthResults = FusionWebSearch.searchCustomCompatibleForTest(
            repo, persisted, "query", SearchIntent.GENERAL, noAuthClient
        )

        assertEquals(1, noAuthResults.size)
        assertEquals(1, noAuthOpens)
        assertFalse(noAuthConnection.requestHeaders.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertEquals(0, secrets.getSecretCalls)
        assertEquals("retained-secret", secrets.secretValues["retained-api-key"])
        assertTrue(noAuthConnection.disconnected)

        secrets.getSecretCalls = 0
        var authenticatedOpens = 0
        val authenticatedConnection = RecordingConnection(URL("https://search.example.test/api?q=query"))
        val authenticatedClient = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory {
                authenticatedOpens += 1
                authenticatedConnection
            }
        )
        val authenticatedResults = FusionWebSearch.searchCustomCompatibleForTest(
            repo,
            persisted.copy(baseUrl = "https://search.example.test/api", noAuth = false),
            "query",
            SearchIntent.GENERAL,
            authenticatedClient,
        )

        assertEquals(1, authenticatedResults.size)
        assertEquals(1, authenticatedOpens)
        assertEquals(1, secrets.getSecretCalls)
        assertEquals("Bearer retained-secret", authenticatedConnection.requestHeaders["Authorization"])

        secrets.getSecretCalls = 0
        var rejectedOpens = 0
        val rejectedClient = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory {
                rejectedOpens += 1
                error("authenticated HTTP must not open a connection")
            }
        )
        val rejectedResults = FusionWebSearch.searchCustomCompatibleForTest(
            repo,
            persisted.copy(noAuth = false),
            "query",
            SearchIntent.GENERAL,
            rejectedClient,
        )

        assertTrue(rejectedResults.isEmpty())
        assertEquals(0, rejectedOpens)
        assertEquals(0, secrets.getSecretCalls)
        dir.deleteRecursively()
    }

    @Test
    fun `manual disabled provider opens zero transport calls`() = runBlocking {
        val dir = Files.createTempDirectory("fusion-web-search-disabled").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] = JSONArray().put(
            JSONObject()
                .put("id", "disabled-custom")
                .put("displayName", "Disabled Custom")
                .put("type", "CUSTOM_COMPATIBLE")
                .put("isEnabled", false)
                .put("baseUrl", "https://search.example.test/api")
                .put("noAuth", true)
                .put("allowFallbackInManualMode", false)
        ).toString()
        prefs.values["selected_web_search_provider_id"] = "disabled-custom"
        prefs.values["web_search_mode"] = WebSearchMode.MANUAL.name
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), CountingSecretStore())
        var opens = 0
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory {
                opens += 1
                error("disabled provider must not open a connection")
            }
        )
        try {
            val response = FusionWebSearch.search("disabled provider query", null, repo)
            assertTrue(response.results.isEmpty())
            assertEquals(0, opens)
        } finally {
            FusionWebSearch.httpClientForTests = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun `exact approved query bypasses planner rewrites and alternate query fallback`() = runBlocking {
        val dir = Files.createTempDirectory("fusion-web-search-exact-query").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] = JSONArray().put(
            JSONObject()
                .put("id", "exact-custom")
                .put("displayName", "Exact Custom")
                .put("type", "CUSTOM_COMPATIBLE")
                .put("isEnabled", true)
                .put("baseUrl", "https://search.example.test/api")
                .put("noAuth", true)
                .put("allowFallbackInManualMode", false)
        ).toString()
        prefs.values["selected_web_search_provider_id"] = "exact-custom"
        prefs.values["web_search_mode"] = WebSearchMode.MANUAL.name
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), CountingSecretStore())
        assertEquals(WebSearchMode.MANUAL, repo.selectedMode())
        assertEquals("exact-custom", repo.selectedProvider().id)
        assertTrue("not runnable: ${repo.selectedProvider()}", repo.isRunnable(repo.selectedProvider()))
        val approvedQuery = "Acme ProductX2 latest 2026"
        val outboundQueries = mutableListOf<String>()
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                val encoded = url.query.substringAfter("q=")
                outboundQueries += java.net.URLDecoder.decode(encoded, "UTF-8")
                RecordingConnection(
                    url,
                    responseBody = """{"results":[{"title":"$approvedQuery","url":"https://result.example.test","snippet":"$approvedQuery"}]}""",
                )
            },
        )
        try {
            val response = FusionWebSearch.searchExactApprovedQuery(approvedQuery, repo)

            assertEquals("response=$response", listOf(approvedQuery), outboundQueries)
            assertEquals(approvedQuery, response.plan?.primaryQuery)
            assertTrue(response.plan?.alternateQueries.orEmpty().isEmpty())
            assertEquals(1, response.sources.size)
        } finally {
            FusionWebSearch.httpClientForTests = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun `owned overall deadline returns bounded unsuccessful response`() = runBlocking {
        val query = "deadline-owned search"
        val privateMaterial = "private attachment material must stay out of diagnostics"
        val expectedPlan = FusionWebSearch.buildSearchPlan(query, null)
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                RecordingConnection(url, responseDelayMs = 250L)
            },
        )
        try {
            val response = FusionWebSearch.searchWithDeadlineForTest(
                userInput = query,
                previousUserMessage = privateMaterial,
                requestToken = "request-secret-must-not-leak",
                overallDeadlineMs = 25L,
            )

            assertTrue(response.results.isEmpty())
            assertTrue(response.sources.isEmpty())
            assertFalse(response.quality?.isUsable == true)
            assertEquals(expectedPlan, response.plan)
            assertEquals(expectedPlan.primaryQuery, response.normalizedQuery)
            assertEquals(expectedPlan.intent, response.intent)
            assertTrue(response.debugMessage.orEmpty().isNotBlank())
            assertTrue(response.debugMessage.orEmpty().length <= 128)
            assertTrue(response.traces.any { it.fallbackReason == "search deadline exceeded" })
            assertFalse(response.debugMessage.orEmpty().contains("request-secret-must-not-leak"))
            val diagnostics = buildList {
                add(response.debugMessage.orEmpty())
                response.traces.forEach { trace ->
                    addAll(listOf(trace.fallbackReason, trace.debugLabel, trace.exceptionMessage).filterNotNull())
                }
            }.joinToString(" ")
            assertFalse(diagnostics.contains("request-secret-must-not-leak"))
            assertFalse(diagnostics.contains(privateMaterial))
            assertFalse(response.traces.any { trace ->
                listOf(trace.fallbackReason, trace.debugLabel, trace.exceptionMessage)
                    .filterNotNull()
                    .any { it.contains("request-secret-must-not-leak") }
            })
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
    }

    @Test
    fun `revoked request authorization blocks transport before the first outbound call`() = runBlocking {
        var outboundCalls = 0
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                outboundCalls += 1
                RecordingConnection(url)
            },
        )
        try {
            val rejection = runCatching {
                FusionWebSearch.searchWithDeadlineForTest(
                    userInput = "Acme Alpha 2025",
                    previousUserMessage = null,
                    overallDeadlineMs = 1_000L,
                    authorizationStillValid = { false },
                )
            }.exceptionOrNull()

            assertTrue(rejection is IllegalArgumentException)
            assertEquals(0, outboundCalls)
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
    }

    @Test
    fun `authorization revocation between planned query and transport blocks network call`() = runBlocking {
        var authorizationChecks = 0
        var outboundCalls = 0
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                outboundCalls += 1
                RecordingConnection(url)
            },
        )
        try {
            FusionWebSearch.searchWithDeadlineForTest(
                userInput = "Acme Alpha 2025",
                previousUserMessage = null,
                overallDeadlineMs = 1_000L,
                authorizationStillValid = { ++authorizationChecks == 1 },
            )

            assertTrue(authorizationChecks >= 2)
            assertEquals(0, outboundCalls)
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
    }

    @Test
    fun `parent cancellation during provider work remains cancellation`() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val disconnected = CountDownLatch(1)
        val parent = Job()
        val thrown = CompletableDeferred<Throwable>()
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                opened.complete(Unit)
                object : RecordingConnection(url) {
                    override fun getResponseCode(): Int {
                        disconnected.await()
                        return 200
                    }

                    override fun disconnect() {
                        disconnected.countDown()
                        super.disconnect()
                    }
                }
            },
        )
        try {
            val searchJob = launch(parent + Dispatchers.Default) {
                try {
                    FusionWebSearch.searchWithDeadlineForTest(
                        userInput = "cancelled search",
                        previousUserMessage = null,
                        overallDeadlineMs = 10_000L,
                    )
                    thrown.complete(AssertionError("cancelled search completed normally"))
                } catch (failure: Throwable) {
                    thrown.complete(failure)
                }
            }
            opened.await()
            parent.cancel()
            searchJob.join()
            val cancellation = thrown.await()
            assertTrue(cancellation is CancellationException)
            assertFalse(cancellation is TimeoutCancellationException)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            FusionWebSearch.httpClientForTests = null
            parent.cancel()
        }
    }

    // P10-C: an already authorized exact query must not depend on legacy query
    // rewriting. The planner boundary below breaks every rewrite path.

    @Test
    fun `exact approved query still executes when the legacy planner fails`() = runBlocking {
        val dir = Files.createTempDirectory("fusion-web-search-exact-planner-failure").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] = JSONArray().put(
            JSONObject()
                .put("id", "exact-custom")
                .put("displayName", "Exact Custom")
                .put("type", "CUSTOM_COMPATIBLE")
                .put("isEnabled", true)
                .put("baseUrl", "https://search.example.test/api")
                .put("noAuth", true)
                .put("allowFallbackInManualMode", false)
        ).toString()
        prefs.values["selected_web_search_provider_id"] = "exact-custom"
        prefs.values["web_search_mode"] = WebSearchMode.MANUAL.name
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), CountingSecretStore())
        val approvedQuery = "Acme ProductX2 latest 2026"
        val outboundQueries = mutableListOf<String>()
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                val encoded = url.query.substringAfter("q=")
                outboundQueries += java.net.URLDecoder.decode(encoded, "UTF-8")
                RecordingConnection(
                    url,
                    responseBody = """{"results":[{"title":"$approvedQuery","url":"https://result.example.test","snippet":"$approvedQuery"}]}""",
                )
            },
        )
        FusionWebSearch.plannerFailureForTests = IllegalStateException("legacy rewriting unavailable")
        try {
            val response = FusionWebSearch.searchExactApprovedQuery(approvedQuery, repo)

            assertEquals(1, outboundQueries.size)
            assertEquals(approvedQuery, outboundQueries.single())
            assertEquals(approvedQuery, response.plan?.primaryQuery)
            assertEquals(approvedQuery, response.query)
            assertTrue(response.plan?.alternateQueries.orEmpty().isEmpty())
            assertEquals(1, response.sources.size)
            assertEquals(approvedQuery, response.sources.single().queryUsed)
        } finally {
            FusionWebSearch.plannerFailureForTests = null
            FusionWebSearch.httpClientForTests = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun `exact approved query with unusual punctuation is never rewritten or split`() = runBlocking {
        val dir = Files.createTempDirectory("fusion-web-search-exact-punctuation").toFile()
        val prefs = InMemoryPrefs()
        prefs.values["providers"] = JSONArray().put(
            JSONObject()
                .put("id", "exact-custom")
                .put("displayName", "Exact Custom")
                .put("type", "CUSTOM_COMPATIBLE")
                .put("isEnabled", true)
                .put("baseUrl", "https://search.example.test/api")
                .put("noAuth", true)
                .put("allowFallbackInManualMode", false)
        ).toString()
        prefs.values["selected_web_search_provider_id"] = "exact-custom"
        prefs.values["web_search_mode"] = WebSearchMode.MANUAL.name
        val repo = WebSearchProviderRepository(TestContext(dir, prefs), CountingSecretStore())
        // Quoting, dash, ampersand, parentheses, brackets and a percent sign.
        val approvedQuery = "\"Q3 report\" - Acme & Co. (2026) [draft] 50%+"
        val outboundQueries = mutableListOf<String>()
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                val encoded = url.query.substringAfter("q=")
                outboundQueries += java.net.URLDecoder.decode(encoded, "UTF-8")
                RecordingConnection(url, responseBody = """{"results":[]}""")
            },
        )
        try {
            val response = FusionWebSearch.searchExactApprovedQuery(approvedQuery, repo)

            assertEquals(1, outboundQueries.size)
            assertEquals(approvedQuery, outboundQueries.single())
            assertEquals(approvedQuery, response.plan?.primaryQuery)
            assertEquals(approvedQuery, response.query)
            assertTrue(response.plan?.alternateQueries.orEmpty().isEmpty())
        } finally {
            FusionWebSearch.httpClientForTests = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun `intent classification still runs for an exact query without mutating it`() = runBlocking {
        // Classification is preserved, and stays separate from query mutation.
        val stock = FusionWebSearch.searchExactApprovedQuery("삼성전자 005930 현재가")
        assertEquals(SearchIntent.STOCK, stock.intent)
        assertEquals("삼성전자 005930 현재가", stock.plan?.primaryQuery)

        val news = FusionWebSearch.searchExactApprovedQuery("삼성전자 반도체 최신 뉴스")
        assertEquals(SearchIntent.NEWS, news.intent)
        assertEquals("삼성전자 반도체 최신 뉴스", news.plan?.primaryQuery)
    }

    @Test
    fun `world scoped broad news uses the WORLD section feed`() = runBlocking {
        val query = "top world news stories today"
        val requests = mutableListOf<URL>()
        val rss = """<?xml version="1.0"?><rss><channel><item><title>World story</title><link>https://news.example.test/world</link><source>News Desk</source><pubDate>Thu, 05 Nov 2026 01:00:00 GMT</pubDate><description>Current world story</description></item></channel></rss>"""
        FusionWebSearch.httpClientForTests = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                requests += url
                RecordingConnection(url, responseBody = rss)
            },
        )
        try {
            FusionWebSearch.searchFreeDefaultForTest(query, SearchIntent.NEWS)

            assertTrue(requests.isNotEmpty())
            val uri = requests.first().toURI()
            assertTrue(uri.path.endsWith("/rss/headlines/section/topic/WORLD"))
            assertFalse(uri.rawQuery.orEmpty().contains("q="))
            assertTrue(uri.rawQuery.orEmpty().contains("hl=ko&gl=KR&ceid=KR:ko"))

            requests.clear()
            FusionWebSearch.searchFreeDefaultForTest("뉴스 알려줘", SearchIntent.NEWS)
            assertTrue(requests.isNotEmpty())
            val localUri = requests.first().toURI()
            assertEquals("/rss", localUri.path)
            assertFalse(localUri.rawQuery.orEmpty().contains("q="))
        } finally {
            FusionWebSearch.httpClientForTests = null
        }
    }

    @Test
    fun `generic news requests use broad feed while named topic news stays topic scoped`() {
        listOf(
            "뉴스 알려줘",
            "뉴스 좀 알려줘",
            "무슨 뉴스 있어?",
            "지금 뉴스 알려줘",
            "Tell me the latest news",
            "What are the latest major news headlines today?",
            "What are the top world news stories today",
        ).forEach { query ->
            assertTrue(query, FusionWebSearch.isBroadNewsQueryForTest(query))
        }
        listOf(
            "삼성전자 뉴스 알려줘",
            "Semiconductor news today",
            "What are Nvidia major chip news headlines today?",
        ).forEach { query ->
            assertFalse(query, FusionWebSearch.isBroadNewsQueryForTest(query))
        }
    }
}
