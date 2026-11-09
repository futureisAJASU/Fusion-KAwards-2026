package com.projectnuke.fusion.knowledge

import java.net.InetAddress
import java.net.Inet6Address
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureNanoTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebClipFetcherTest {
    private val publicResolver = WebClipAddressResolver { arrayOf(InetAddress.getByName("93.184.216.34")) }

    @Test
    fun `completed capture survives DNS outage after guarded connection`() = runBlocking {
        var resolutions = 0
        val resolver = WebClipAddressResolver {
            if (++resolutions > 2) throw java.net.UnknownHostException("DNS unavailable after response")
            arrayOf(InetAddress.getByName("93.184.216.34"))
        }
        HttpTestServer { HttpTestReply() }.use { server ->
            val result = fetcher(server, resolver).fetch("http://example.com:${server.port}/page")
            assertTrue("A completed response must remain available: $result", result is WebClipFetchResult.Captured)
        }
    }

    @Test
    fun `production fetcher rejects HTTP before DNS or socket work`() = runBlocking {
        var resolutions = 0
        val fetcher = WebClipFetcher(WebClipAddressResolver {
            resolutions++
            arrayOf(InetAddress.getByName("93.184.216.34"))
        })
        assertEquals(WebClipFetchResult.Failed(WebClipFailure.INVALID_URL), fetcher.fetch("http://example.com/article"))
        assertEquals(0, resolutions)
    }

    @Test
    fun `web clip policy requires HTTPS and blocks unsupported authority and private ranges`() {
        assertEquals("https://example.com/a", WebClipUrlPolicy.normalize(" https://example.com/a "))
        assertNull(WebClipUrlPolicy.normalize("http://example.com/a"))
        assertEquals("http://example.com/a", WebClipUrlPolicy.normalize("http://example.com/a", allowHttpForTests = true))
        assertNull(WebClipUrlPolicy.normalize("file:///etc/passwd"))
        assertNull(WebClipUrlPolicy.normalize("https://user@example.com/a"))
        assertNull(WebClipUrlPolicy.normalize("https://example.com:8443/a"))
        assertNull(WebClipUrlPolicy.normalize("https://example.com/?access_token=abcdefghijklmnop"))
        assertFalse(WebClipUrlPolicy.allows(URL("http://127.0.0.1/"), WebClipAddressResolver {
            InetAddress.getAllByName(it)
        }))
        assertFalse(WebClipUrlPolicy.allows(URL("http://example.com/"), WebClipAddressResolver {
            arrayOf(InetAddress.getByName("10.2.3.4"))
        }))
        assertTrue(WebClipUrlPolicy.allows(URL("https://example.com/"), publicResolver))
    }

    @Test
    fun `persistence accepts only completed capture shape and rejects private source literals`() {
        fun capture(source: String, effective: String? = null) = WebClipCapture.fromGuardedFetch(
            WebClipDocument(source, null, "Captured page", "Frozen text", false, 7L, effective),
        )
        assertTrue(WebClipCapturePersistencePolicy.isValid(capture("https://public.example/article")))
        assertTrue(WebClipCapturePersistencePolicy.isValid(capture("https://public.example/legacy")))
        assertFalse(WebClipCapturePersistencePolicy.isValid(capture("https://127.0.0.1/admin")))
        assertFalse(WebClipCapturePersistencePolicy.isValid(capture("https://[::1]/admin")))
        assertFalse(WebClipCapturePersistencePolicy.isValid(capture("https://public.example/article", "https://10.0.0.1/private")))
        assertTrue(WebClipCapture::class.java.declaredConstructors.filterNot { it.isSynthetic }
            .all { java.lang.reflect.Modifier.isPrivate(it.modifiers) })
    }

    @Test
    fun `address policy follows precise special-purpose IPv4 and IPv6 ranges`() {
        fun ip(value: String) = InetAddress.getByName(value)
        fun allowed(value: String) = WebClipUrlPolicy.isPublicInternetAddress(ip(value))

        assertFalse(allowed("192.0.0.1"))
        assertFalse(allowed("192.0.0.8"))
        assertTrue(allowed("192.0.0.9"))
        assertTrue(allowed("192.0.0.10"))
        assertFalse(allowed("192.0.0.170"))
        assertFalse(allowed("192.0.2.1"))
        assertTrue("192.0.64.0/18 is outside the IANA restricted entries", allowed("192.0.64.1"))
        assertFalse(allowed("192.168.1.1"))
        assertFalse(allowed("198.18.0.1"))
        assertFalse(allowed("198.51.100.1"))
        assertFalse(allowed("203.0.113.1"))
        assertFalse(allowed("100.64.0.1"))
        assertFalse(allowed("127.0.0.1"))
        assertFalse(allowed("169.254.1.1"))
        assertFalse(allowed("224.0.0.1"))
        assertFalse(allowed("0.0.0.0"))

        assertTrue(allowed("2606:4700:4700::1111"))
        assertFalse(allowed("fc00::1"))
        assertFalse(allowed("fe80::1"))
        assertFalse(allowed("fec0::1"))
        assertFalse(allowed("2001:db8::1"))
        assertFalse(allowed("2001::1"))
        assertFalse(allowed("2001:2::1"))
        assertFalse(allowed("2001:4::1"))
        assertFalse(allowed("2001:10::1"))
        val pcpAnycast = ip("2001:1::1")
        assertTrue("${pcpAnycast.javaClass.simpleName} ${pcpAnycast.address.joinToString { (it.toInt() and 0xff).toString(16) } }", WebClipUrlPolicy.isPublicInternetAddress(pcpAnycast))
        assertTrue(allowed("2001:1::2"))
        assertTrue(allowed("2001:1::3"))
        assertTrue(allowed("2001:3::1"))
        assertTrue(allowed("2001:4:112::1"))
        assertTrue(allowed("2001:20::1"))
        assertTrue(allowed("2001:30::1"))
        assertFalse(allowed("2002::1"))
        assertFalse(allowed("3fff::1"))
        assertFalse(allowed("ff02::1"))
        assertTrue(allowed("64:ff9b::8.8.8.8"))
        assertFalse(allowed("64:ff9b::10.0.0.1"))
        assertFalse(allowed("64:ff9b:1::808:808"))
        val mappedPrivate = ByteArray(16).also {
            it[10] = 0xff.toByte()
            it[11] = 0xff.toByte()
            it[12] = 10
            it[15] = 1
        }
        assertFalse(WebClipUrlPolicy.isPublicInternetAddress(Inet6Address.getByAddress(null, mappedPrivate, -1)))

        assertFalse(WebClipUrlPolicy.allows(URL("https://mixed.example/"), WebClipAddressResolver {
            arrayOf(ip("93.184.216.34"), ip("10.1.2.3"))
        }))
    }

    @Test
    fun `extractor keeps readable text and removes active and navigation content`() {
        val extracted = WebClipHtmlExtractor.extract(
            """<html><head><title> Example &amp; title </title></head><body><nav>Menu</nav><main><h1>Heading</h1><p>Useful &amp; safe text</p><script>steal()</script><style>.hidden{}</style><footer>Footer</footer></main></body></html>""",
        )!!

        assertEquals("Example & title", extracted.title)
        assertEquals("Heading Useful & safe text", extracted.text)
        assertFalse(extracted.truncated)
        assertFalse(extracted.text.contains("steal"))
        assertFalse(extracted.text.contains("Footer"))
    }

    @Test
    fun `extractor closes an omitted HTML5 head at body content`() {
        val omittedHeadClose = WebClipHtmlExtractor.extract(
            "<!doctype html><head><title>Example</title><body class='page'><p>실제 본문입니다.</p>",
        )!!
        val explicitHeadClose = WebClipHtmlExtractor.extract(
            "<!doctype html><head><title>Example</title></head><body class='page'><p>실제 본문입니다.</p>",
        )!!

        assertEquals("Example", omittedHeadClose.title)
        assertEquals("실제 본문입니다.", omittedHeadClose.text)
        assertEquals(explicitHeadClose.title, omittedHeadClose.title)
        assertEquals(explicitHeadClose.text, omittedHeadClose.text)
    }

    @Test
    fun `extractor recognizes implicit head close without body start but ignores apparent body text`() {
        val omittedBodyStart = WebClipHtmlExtractor.extract(
            "<HEAD><TITLE>Example</TITLE>Body text starts here <p>and continues</p>",
        )!!
        assertEquals("Example", omittedBodyStart.title)
        assertEquals("Body text starts here and continues", omittedBodyStart.text)

        val commentAndAttribute = WebClipHtmlExtractor.extract(
            "<head><!-- <body><p>comment</p> --><meta content=\"<body>\"><title>Page</title></head><p>real body</p>",
        )!!
        assertEquals("Page", commentAndAttribute.title)
        assertEquals("real body", commentAndAttribute.text)

        val scriptInHead = WebClipHtmlExtractor.extract(
            "<head><script>const apparent = '<body><p>hidden</p>';</script><title>Safe title</title><body><main>visible</main>",
        )!!
        assertEquals("Safe title", scriptInHead.title)
        assertEquals("visible", scriptInHead.text)
    }

    @Test
    fun `bounded parser handles malformed nested and oversized HTML safely`() {
        val nested = WebClipHtmlExtractor.extract(
            "<article>before<nav>nav<form>form</form></nav><section><p>after</p></section></article>",
        )!!
        assertEquals("before after", nested.text)

        assertNull(WebClipHtmlExtractor.extract("<script>${"<script>".repeat(300)}hidden"))
        assertNull(WebClipHtmlExtractor.extract("<form>${"<form>".repeat(300)}hidden"))
        assertNull(WebClipHtmlExtractor.extract("<head><title>Unclosed title and head"))
        assertNull(WebClipHtmlExtractor.extract("<!-- malformed comment hides the rest"))
        assertNull(WebClipHtmlExtractor.extract("<form attribute=\"${"x".repeat(900_000)}"))

        val longAttribute = "<p data-long=\"${"x".repeat(900_000)}\">Readable body</p>"
        assertEquals("Readable body", WebClipHtmlExtractor.extract(longAttribute)!!.text)
        assertNull(WebClipHtmlExtractor.extract("<p " + "x".repeat(900_000)))

        val atLimit = WebClipHtmlExtractor.extract("<p>${"a".repeat(10_000)}${" ".repeat(989_993)}</p>")!!
        assertEquals(1_000_000, "<p>${"a".repeat(10_000)}${" ".repeat(989_993)}</p>".length)
        assertTrue(atLimit.text.length <= WebClipHtmlExtractor.MAX_TEXT_CHARS)
        assertFalse(atLimit.truncated)
        val aboveLimit = WebClipHtmlExtractor.extract("<p>${"a".repeat(1_000_010)}</p>")!!
        assertTrue(aboveLimit.truncated)
        assertTrue(aboveLimit.text.length <= WebClipHtmlExtractor.MAX_TEXT_CHARS)
    }

    @Test
    fun `parser is bounded for pathological unclosed elements and scales linearly on full scans`() {
        val pathological = listOf("form", "script").flatMap { tag ->
            listOf(50_000, 100_000, 200_000).map { size -> "<$tag>".repeat(size) + "hidden" }
        }
        val boundedTimes = pathological.map { html ->
            measureNanoTime { WebClipHtmlExtractor.extract(html) }
        }
        val pathologicalLabels = pathological.mapIndexed { index, html ->
            val tag = if (index < 3) "form" else "script"
            val count = listOf(50_000, 100_000, 200_000)[index % 3]
            "$tag $count tags (${html.length} chars)"
        }
        println("WebClip unclosed-element timings ns: ${pathologicalLabels.zip(boundedTimes)}")
        assertTrue("pathological cases must finish under 2 seconds: $boundedTimes", boundedTimes.all { it < 2_000_000_000L })

        val scanTimes = listOf(64_000, 256_000, 1_000_000).map { size ->
            val html = "<head>" + "x".repeat(size) // forces a complete, unclosed-head scan
            measureNanoTime { WebClipHtmlExtractor.extract(html) }
        }
        println("WebClip linear scan timings ns for 64KB/256KB/1000KB: $scanTimes")
        assertTrue("1MB scan should complete in 2 seconds: $scanTimes", scanTimes.last() < 2_000_000_000L)
        assertTrue("4x input growth must stay below 12x time growth: $scanTimes", scanTimes[1] <= scanTimes[0] * 12 + 25_000_000L)
        assertTrue("4x input growth must stay below 12x time growth: $scanTimes", scanTimes[2] <= scanTimes[1] * 12 + 25_000_000L)

        val malformedEntitySamples = listOf(64_000, 256_000, 1_000_000).map { size ->
            val html = "&".repeat(size)
            WebClipHtmlExtractor.extract(html) // warm the parser before measuring
            List(5) { measureNanoTime { WebClipHtmlExtractor.extract(html) } }
        }
        val malformedEntityTimes = malformedEntitySamples.map { it.sorted()[2] }
        println("WebClip malformed-entity samples ns (5 each) for 64KB/256KB/1000KB: $malformedEntitySamples; medians: $malformedEntityTimes")
        assertTrue("all malformed-entity scans should complete in 2 seconds: $malformedEntitySamples",
            malformedEntitySamples.flatten().all { it < 2_000_000_000L })
        assertTrue("4x input growth must stay below 12x time growth: $malformedEntityTimes", malformedEntityTimes[1] <= malformedEntityTimes[0] * 12 + 25_000_000L)
        assertTrue("4x input growth must stay below 12x time growth: $malformedEntityTimes", malformedEntityTimes[2] <= malformedEntityTimes[1] * 12 + 25_000_000L)
    }

    @Test
    fun `parser checks cancellation during a long scan`() {
        var checks = 0
        val failure = runCatching {
            WebClipHtmlExtractor.extract("x".repeat(500_000)) {
                checks++
                throw CancellationException("cancel parser")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, checks)
    }

    @Test
    fun `initial DNS resolution runs off caller thread and concurrent captures stay isolated`() = runBlocking {
        val caller = Thread.currentThread()
        val resolverThread = AtomicReference<Thread>()
        val resolver = WebClipAddressResolver {
            resolverThread.set(Thread.currentThread())
            publicResolver.resolve(it)
        }
        val server = HttpTestServer { request ->
            HttpTestReply(body = "<p>${request.target.removePrefix("/")}</p>".toByteArray())
        }
        server.use {
            val fetcher = fetcher(server, resolver, clock = { 1L })
            val results = (1..8).map { index ->
                async(Dispatchers.Default) { fetcher.fetch("http://example.com:${server.port}/$index") }
            }.map { it.await() }

            assertTrue(resolverThread.get() !== caller)
            assertEquals((1..8).map { it.toString() }, results.map {
                (it as WebClipFetchResult.Captured).document.text
            })
        }
    }

    @Test
    fun `fetch stores a bounded snapshot from supported HTML`() = runBlocking {
        val html = "<html><head><title>Research page</title></head><body><article>Captured text</article><script>secret</script></body></html>"
        val server = HttpTestServer { HttpTestReply(body = html.toByteArray()) }
        server.use {
            val enteredUrl = "http://example.com:${server.port}/page"
            val result = fetcher(server, publicResolver).fetch(enteredUrl) as WebClipFetchResult.Captured
            assertEquals(enteredUrl, result.document.originalUrl)
            assertEquals(enteredUrl, result.document.effectiveUrl)
            assertEquals("Research page", result.document.pageTitle)
            assertEquals("Captured text", result.document.text)
            assertEquals(1234L, result.document.retrievedAt)
        }
    }

    @Test
    fun `public redirect preserves entered URL separately from the effective content source`() = runBlocking {
        val (result, opened) = fetchRedirectChain(
            start = "http://entered.example.test/article",
            locations = listOf("http://content.example.test/final"),
        )

        val document = (result as WebClipFetchResult.Captured).document
        assertEquals(2, opened.size)
        assertEquals(opened.first(), document.originalUrl)
        assertTrue(opened.last().startsWith("http://content.example.test:"))
        assertEquals(opened.last(), document.effectiveUrl)
        assertEquals("Readable page text", document.text)
    }

    @Test
    fun `relative and multiple public redirects record the URL of the final body`() = runBlocking {
        val (relative, relativeOpened) = fetchRedirectChain(
            start = "http://example.com/articles/start",
            locations = listOf("../current", "http://cdn.example.test/current.html"),
        )
        val relativeDocument = (relative as WebClipFetchResult.Captured).document
        assertEquals(3, relativeOpened.size)
        assertTrue(relativeDocument.originalUrl.startsWith("http://example.com:"))
        assertTrue(relativeOpened[1].endsWith("/current"))
        assertTrue(relativeDocument.effectiveUrl!!.startsWith("http://cdn.example.test:"))
        assertEquals(relativeOpened.last(), relativeDocument.effectiveUrl)

        val (multiple, multipleOpened) = fetchRedirectChain(
            start = "http://a.example.test/start",
            locations = listOf("http://b.example.test/step", "http://c.example.test/content"),
        )
        assertEquals(3, multipleOpened.size)
        assertTrue((multiple as WebClipFetchResult.Captured).document.originalUrl.startsWith("http://a.example.test:"))
        assertEquals(multipleOpened.last(), multiple.document.effectiveUrl)
    }

    @Test
    fun `redirect limit fails without recording a body source`() = runBlocking {
        val (result, opened) = fetchRedirectChain(
            start = "http://a.example.test/start",
            locations = (1..6).map { "http://a.example.test/$it" },
        )

        assertEquals(WebClipFailure.NETWORK, (result as WebClipFetchResult.Failed).reason)
        assertEquals(6, opened.size)
    }

    @Test
    fun `unsafe effective metadata and a failed final response never produce a captured source`() = runBlocking {
        val privateServerRef = AtomicReference<HttpTestServer>()
        val privateServer = HttpTestServer { request ->
            HttpTestReply(
                status = 302,
                headers = mapOf("Location" to "http://127.0.0.1:${privateServerRef.get().port}/private"),
                body = byteArrayOf(),
            )
        }
        privateServerRef.set(privateServer)
        privateServer.use {
            val failure = fetcher(privateServer, publicResolver).fetch(
                "http://example.com:${privateServer.port}/start",
            ) as WebClipFetchResult.Failed
            assertEquals(WebClipFailure.PRIVATE_NETWORK, failure.reason)
        }

        val (failedFinal, _) = fetchRedirectChain(
            start = "http://example.com/start",
            locations = listOf("http://content.example.test/final"),
            finalCode = 403,
        )
        assertEquals(WebClipFailure.HTTP_STATUS, (failedFinal as WebClipFetchResult.Failed).reason)
    }

    @Test
    fun `legacy web clip document leaves effective source unknown`() {
        val legacy = WebClipDocument(
            originalUrl = "https://example.com/legacy",
            pageTitle = null,
            displayTitle = "Legacy clip",
            text = "Saved text",
            textTruncated = false,
            retrievedAt = 12L,
        )

        assertNull(legacy.effectiveUrl)
    }

    @Test
    fun `production fetcher enters guarded DNS and does not report transport unavailable`() = runBlocking {
        var resolutions = 0
        val fetcher = WebClipFetcher(
            addressResolver = WebClipAddressResolver {
                resolutions++
                throw java.net.UnknownHostException("controlled host-side DNS failure")
            },
        )

        val failure = fetcher.fetch("https://public.example.test/article") as WebClipFetchResult.Failed

        assertEquals(WebClipFailure.PRIVATE_NETWORK, failure.reason)
        assertTrue(resolutions > 0)
    }

    private suspend fun fetchRedirectChain(
        start: String,
        locations: List<String>,
        finalCode: Int = 200,
    ): Pair<WebClipFetchResult, List<String>> {
        val opened = mutableListOf<String>()
        var hop = 0
        val serverRef = AtomicReference<HttpTestServer>()
        val server = HttpTestServer { request ->
            val host = request.headers["host"] ?: error("missing Host")
            val currentUrl = "http://$host${request.target}"
            opened += currentUrl
            val location = locations.getOrNull(hop++)
            if (location == null) {
                HttpTestReply(status = finalCode)
            } else {
                val port = serverRef.get().port
                val localized = if (location.startsWith("http://") || location.startsWith("https://")) {
                    val parsed = URL(location)
                    URL(parsed.protocol, parsed.host, port, parsed.file).toExternalForm()
                } else location
                HttpTestReply(status = 302, headers = mapOf("Location" to localized), body = byteArrayOf())
            }
        }
        serverRef.set(server)
        server.use {
            val entered = localizeStartUrl(start, server.port)
            return fetcher(server, publicResolver, clock = { 1L }).fetch(entered) to opened
        }
    }

    @Test
    fun `redirect to private IP is stopped before opening the private target`() = runBlocking {
        val serverRef = AtomicReference<HttpTestServer>()
        val server = HttpTestServer {
            HttpTestReply(
                status = 302,
                headers = mapOf("Location" to "http://127.0.0.1:${serverRef.get().port}/admin"),
                body = byteArrayOf(),
            )
        }
        serverRef.set(server)
        server.use {
            val result = fetcher(server, publicResolver).fetch("http://example.com:${server.port}/page") as WebClipFetchResult.Failed
            assertEquals(WebClipFailure.PRIVATE_NETWORK, result.reason)
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `non HTML MIME, invalid charset, malformed bytes and oversized responses fail closed`() = runBlocking {
        assertFailure(HttpTestReply(headers = mapOf("Content-Type" to "application/pdf")), WebClipFailure.UNSUPPORTED_CONTENT)
        assertFailure(
            HttpTestReply(headers = mapOf("Content-Type" to "text/html; charset=made-up")),
            WebClipFailure.INVALID_CHARSET,
        )
        assertFailure(
            HttpTestReply(
                headers = mapOf("Content-Type" to "text/html; charset=utf-8"),
                body = byteArrayOf(0xc3.toByte(), 0x28),
            ),
            WebClipFailure.MALFORMED_CONTENT,
        )
        assertFailure(
            HttpTestReply(body = ByteArray(1_000_001) { 'x'.code.toByte() }),
            WebClipFailure.RESPONSE_TOO_LARGE,
        )
    }

    private suspend fun assertFailure(reply: HttpTestReply, expected: WebClipFailure) {
        val server = HttpTestServer { reply }
        server.use {
            val result = fetcher(server, publicResolver).fetch("http://example.com:${server.port}/a") as WebClipFetchResult.Failed
            assertEquals(expected, result.reason)
        }
    }

    private fun fetcher(
        server: HttpTestServer,
        resolver: WebClipAddressResolver,
        clock: () -> Long = { 1234L },
    ): WebClipFetcher {
        val transport = GuardedWebClipHttpTransport.forTest(
            addressResolver = resolver,
            socketFactory = RecordingTestSocketFactory(server.port),
            allowNonDefaultPorts = true,
            allowHttpForTests = true,
        )
        return WebClipFetcher(transport, allowNonDefaultPorts = true, allowHttpForTests = true, clock = clock)
    }

    private fun localizeStartUrl(raw: String, port: Int): String {
        val url = URL(raw)
        return URL(url.protocol, url.host, port, url.file).toExternalForm()
    }
}
