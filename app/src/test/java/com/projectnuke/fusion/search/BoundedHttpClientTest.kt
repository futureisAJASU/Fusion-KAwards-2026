package com.projectnuke.fusion.search

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedHttpClientTest {
    @Test
    fun `declared oversized success response is rejected and disconnected`() = runBlocking {
        val connection = FakeConnection(
            url = URL("https://example.test/search"),
            body = ByteArray(1),
            declaredLength = 33,
        )
        val client = client(connection, successCap = 32)

        val failure = runCatching {
            client.execute(BoundedHttpRequest("https://example.test/search"))
        }.exceptionOrNull() as BoundedHttpException

        assertEquals(BoundedHttpFailure.RESPONSE_TOO_LARGE, failure.kind)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `strict HTML decoding supports BOM UTF8 and bounded Korean meta declarations`() = runBlocking {
        val utf8 = "<p>안녕하세요</p>".toByteArray(StandardCharsets.UTF_8)
        val utf8Result = client(FakeConnection(
            url = URL("https://example.test/page"),
            body = utf8,
            contentType = "text/html",
        )).execute(BoundedHttpRequest("https://example.test/page", strictCharset = true, sniffHtmlCharset = true))
        assertEquals("<p>안녕하세요</p>", utf8Result.body)

        val eucKrHtml = "<meta charset=\"EUC-KR\"><p>안녕하세요</p>".toByteArray(Charset.forName("EUC-KR"))
        val eucKrResult = client(FakeConnection(
            url = URL("https://example.test/page"),
            body = eucKrHtml,
            contentType = "text/html",
        )).execute(BoundedHttpRequest("https://example.test/page", strictCharset = true, sniffHtmlCharset = true))
        assertEquals("<meta charset=\"EUC-KR\"><p>안녕하세요</p>", eucKrResult.body)

        val legacyHtml = "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=EUC-KR\"><p>한글</p>"
            .toByteArray(Charset.forName("EUC-KR"))
        val legacyResult = client(FakeConnection(
            url = URL("https://example.test/page"),
            body = legacyHtml,
            contentType = "text/html",
        )).execute(BoundedHttpRequest("https://example.test/page", strictCharset = true, sniffHtmlCharset = true))
        assertEquals(String(legacyHtml, Charset.forName("EUC-KR")), legacyResult.body)

        val withBom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + utf8
        val bomResult = client(FakeConnection(
            url = URL("https://example.test/page"), body = withBom, contentType = "text/html",
        )).execute(BoundedHttpRequest("https://example.test/page", strictCharset = true, sniffHtmlCharset = true))
        assertEquals("<p>안녕하세요</p>", bomResult.body)
    }

    @Test
    fun `strict HTML decoding rejects malformed bytes and unknown declared meta charset`() = runBlocking {
        val malformed = "<meta charset=utf-8>".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0xc3.toByte(), 0x28)
        val malformedFailure = runCatching {
            client(FakeConnection(
                url = URL("https://example.test/page"), body = malformed, contentType = "text/html",
            )).execute(BoundedHttpRequest("https://example.test/page", strictCharset = true, sniffHtmlCharset = true))
        }.exceptionOrNull() as BoundedHttpException
        assertEquals(BoundedHttpFailure.MALFORMED_BODY, malformedFailure.kind)

        val unknownFailure = runCatching {
            client(FakeConnection(
                url = URL("https://example.test/page"),
                body = "<meta charset=x-unknown><p>text</p>".toByteArray(),
                contentType = "text/html",
            )).execute(BoundedHttpRequest("https://example.test/page", strictCharset = true, sniffHtmlCharset = true))
        }.exceptionOrNull() as BoundedHttpException
        assertEquals(BoundedHttpFailure.INVALID_CHARSET, unknownFailure.kind)
    }

    @Test
    fun `unknown length success response is bounded while streaming`() = runBlocking {
        val connection = FakeConnection(
            url = URL("https://example.test/search"),
            body = ByteArray(33),
            declaredLength = -1,
        )
        val failure = runCatching {
            client(connection, successCap = 32)
                .execute(BoundedHttpRequest("https://example.test/search"))
        }.exceptionOrNull() as BoundedHttpException

        assertEquals(BoundedHttpFailure.RESPONSE_TOO_LARGE, failure.kind)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `cancellation disconnects active request and does not start fallback`() = runBlocking {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val connection = FakeConnection(
            url = URL("https://example.test/search"),
            declaredLength = -1,
            inputOverride = object : InputStream() {
                override fun read(): Int = error("unused")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    readStarted.countDown()
                    releaseRead.await(5, TimeUnit.SECONDS)
                    if (connectionDisconnected) throw java.io.IOException("disconnected")
                    return -1
                }

                var connectionDisconnected = false
            },
            onDisconnect = {
                releaseRead.countDown()
            },
        )
        var fallbackStarted = false
        val task = async(Dispatchers.Default) {
            try {
                client(connection).execute(BoundedHttpRequest("https://example.test/search"))
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
            fallbackStarted = true
        }
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        task.cancel(CancellationException("cancel search"))
        task.cancelAndJoin()

        assertTrue(connection.disconnected)
        assertFalse(fallbackStarted)
    }

    @Test
    fun `same-origin 307 preserves post body`() = runBlocking {
        assertSameOriginBodyPreserved(307)
    }

    @Test
    fun `same-origin 308 preserves post body`() = runBlocking {
        assertSameOriginBodyPreserved(308)
    }

    @Test
    fun `cross-origin 307 with body is rejected before second open`() = runBlocking {
        assertCrossOriginBodyRejected(307)
    }

    @Test
    fun `cross-origin 308 with body is rejected before second open`() = runBlocking {
        assertCrossOriginBodyRejected(308)
    }

    @Test
    fun `cross-origin 301 with preserved body is rejected`() = runBlocking {
        assertCrossOriginBodyRejected(HttpURLConnection.HTTP_MOVED_PERM)
    }

    @Test
    fun `cross-origin 302 with preserved body is rejected`() = runBlocking {
        assertCrossOriginBodyRejected(HttpURLConnection.HTTP_MOVED_TEMP)
    }

    @Test
    fun `cross-origin 303 drops body and follows as get`() = runBlocking {
        val redirect = FakeConnection(
            url = URL("https://api.example.test/search"),
            code = HttpURLConnection.HTTP_SEE_OTHER,
            location = "https://other.example/search",
        )
        val destination = FakeConnection(
            url = URL("https://other.example/search"),
        )
        val (client, opened) = clientForConnections(redirect, destination)

        val response = client.execute(
            BoundedHttpRequest(
                url = "https://api.example.test/search",
                method = "POST",
                body = "private-body".toByteArray(StandardCharsets.UTF_8),
            ),
        )

        assertEquals(200, response.statusCode)
        assertEquals(2, opened.size)
        assertEquals("GET", destination.requestMethod)
        assertEquals(0, destination.requestBody.size())
    }

    @Test
    fun `cookie makes cross-origin redirect authenticated`() = runBlocking {
        val redirect = FakeConnection(
            url = URL("https://api.example.test/search"),
            code = HttpURLConnection.HTTP_MOVED_TEMP,
            location = "https://other.example/search",
        )
        val (client, opened) = clientForConnections(redirect)

        val failure = runCatching {
            client.execute(
                BoundedHttpRequest(
                    url = "https://api.example.test/search",
                    headers = mapOf("Cookie" to "session=secret"),
                ),
            )
        }.exceptionOrNull() as BoundedHttpException

        assertEquals(BoundedHttpFailure.AUTHENTICATED_CROSS_HOST_REDIRECT, failure.kind)
        assertEquals(1, opened.size)
    }

    @Test
    fun `cross-origin body rejection does not expose body text`() = runBlocking {
        val secretBody = "private query that must not appear"
        val redirect = FakeConnection(
            url = URL("https://api.example.test/search"),
            code = 307,
            location = "https://other.example/search",
        )
        val (client, opened) = clientForConnections(redirect)

        val failure = runCatching {
            client.execute(
                BoundedHttpRequest(
                    url = "https://api.example.test/search",
                    method = "POST",
                    body = secretBody.toByteArray(StandardCharsets.UTF_8),
                ),
            )
        }.exceptionOrNull() as BoundedHttpException

        assertEquals(BoundedHttpFailure.CROSS_ORIGIN_BODY_REDIRECT, failure.kind)
        assertEquals(1, opened.size)
        assertFalse(failure.message.orEmpty().contains(secretBody))
    }

    @Test
    fun `cross-origin get without secret header remains allowed`() = runBlocking {
        val redirect = FakeConnection(
            url = URL("https://api.example.test/search"),
            code = HttpURLConnection.HTTP_MOVED_TEMP,
            location = "https://other.example/search",
        )
        val destination = FakeConnection(url = URL("https://other.example/search"))
        val (client, opened) = clientForConnections(redirect, destination)

        val response = client.execute(BoundedHttpRequest("https://api.example.test/search"))

        assertEquals(200, response.statusCode)
        assertEquals(2, opened.size)
        assertEquals("GET", destination.requestMethod)
    }

    @Test
    fun `https to http redirect remains rejected`() = runBlocking {
        val redirect = FakeConnection(
            url = URL("https://api.example.test/search"),
            code = HttpURLConnection.HTTP_MOVED_TEMP,
            location = "http://other.example/search",
        )
        val (client, opened) = clientForConnections(redirect)

        val failure = runCatching {
            client.execute(BoundedHttpRequest("https://api.example.test/search"))
        }.exceptionOrNull() as BoundedHttpException

        assertEquals(BoundedHttpFailure.INVALID_REDIRECT, failure.kind)
        assertEquals(1, opened.size)
    }

    @Test
    fun `redirect cap remains five redirects`() = runBlocking {
        val redirects = (0..5).map { index ->
            FakeConnection(
                url = URL("https://example.test/$index"),
                code = HttpURLConnection.HTTP_MOVED_TEMP,
                location = "https://example.test/${index + 1}",
            )
        }
        val (client, opened) = clientForConnections(*redirects.toTypedArray())

        val failure = runCatching {
            client.execute(BoundedHttpRequest("https://example.test/0"))
        }.exceptionOrNull() as BoundedHttpException

        assertEquals(BoundedHttpFailure.TOO_MANY_REDIRECTS, failure.kind)
        assertEquals(6, opened.size)
    }

    @Test
    fun `authenticated cross-host redirect is rejected before second request`() = runBlocking {
        val redirect = FakeConnection(
            url = URL("https://api.example.test/search"),
            code = 302,
            location = "https://evil.example/steal",
        )
        var opens = 0
        val client = BoundedHttpClient(
            connectionFactory = SearchConnectionFactory {
                opens++
                redirect
            },
        )

        val failure = runCatching {
            client.execute(
                BoundedHttpRequest(
                    url = "https://api.example.test/search",
                    headers = mapOf("Authorization" to "Bearer secret"),
                )
            )
        }.exceptionOrNull() as BoundedHttpException

        assertEquals(BoundedHttpFailure.AUTHENTICATED_CROSS_HOST_REDIRECT, failure.kind)
        assertEquals(1, opens)
        assertTrue(redirect.disconnected)
    }

    private fun client(
        connection: FakeConnection,
        successCap: Int = 1024,
    ) = BoundedHttpClient(
        connectionFactory = SearchConnectionFactory { connection },
        maxSuccessBytes = successCap,
        maxErrorBytes = 32,
    )

    private suspend fun assertSameOriginBodyPreserved(status: Int) {
        val redirect = FakeConnection(
            url = URL("https://example.test/search"),
            code = status,
            location = "https://example.test/next",
        )
        val destination = FakeConnection(url = URL("https://example.test/next"))
        val (client, opened) = clientForConnections(redirect, destination)

        val response = client.execute(
            BoundedHttpRequest(
                url = "https://example.test/search",
                method = "POST",
                body = "private-body".toByteArray(StandardCharsets.UTF_8),
            ),
        )

        assertEquals(200, response.statusCode)
        assertEquals(2, opened.size)
        assertEquals("POST", destination.requestMethod)
        assertEquals("private-body", String(destination.requestBody.toByteArray(), StandardCharsets.UTF_8))
    }

    private suspend fun assertCrossOriginBodyRejected(status: Int) {
        val redirect = FakeConnection(
            url = URL("https://example.test/search"),
            code = status,
            location = "https://other.example/search",
        )
        val (client, opened) = clientForConnections(redirect)

        val failure = runCatching {
            client.execute(
                BoundedHttpRequest(
                    url = "https://example.test/search",
                    method = "POST",
                    body = "private-body".toByteArray(StandardCharsets.UTF_8),
                ),
            )
        }.exceptionOrNull() as BoundedHttpException

        assertEquals(BoundedHttpFailure.CROSS_ORIGIN_BODY_REDIRECT, failure.kind)
        assertEquals(1, opened.size)
        assertTrue(redirect.disconnected)
    }

    private fun clientForConnections(
        vararg connections: FakeConnection,
    ): Pair<BoundedHttpClient, MutableList<URL>> {
        val pending = ArrayDeque(connections.toList())
        val opened = mutableListOf<URL>()
        return BoundedHttpClient(
            connectionFactory = SearchConnectionFactory { url ->
                opened += url
                pending.removeFirst()
            },
        ) to opened
    }

    private class FakeConnection(
        url: URL,
        private val code: Int = 200,
        private val body: ByteArray = ByteArray(0),
        private val declaredLength: Long = body.size.toLong(),
        private val location: String? = null,
        private val inputOverride: InputStream? = null,
        private val contentType: String = "application/json; charset=utf-8",
        private val onDisconnect: () -> Unit = {},
    ) : HttpURLConnection(url) {
        var disconnected = false
        val requestBody = ByteArrayOutputStream()

        override fun connect() {
            connected = true
        }

        override fun disconnect() {
            disconnected = true
            onDisconnect()
        }

        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getContentLengthLong(): Long = declaredLength
        override fun getContentType(): String = contentType
        override fun getOutputStream() = requestBody
        override fun getHeaderField(name: String?): String? =
            if (name.equals("Location", ignoreCase = true)) location else null
        override fun getInputStream(): InputStream = inputOverride ?: ByteArrayInputStream(body)
        override fun getErrorStream(): InputStream? = ByteArrayInputStream(body)
    }
}
