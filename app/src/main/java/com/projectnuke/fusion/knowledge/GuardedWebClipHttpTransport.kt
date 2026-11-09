package com.projectnuke.fusion.knowledge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class GuardedWebClipFailure {
    INVALID_URL,
    DISALLOWED_ADDRESS,
    NETWORK,
    RESPONSE_TOO_LARGE,
    INVALID_CHARSET,
    MALFORMED_BODY,
    TOO_MANY_REDIRECTS,
}

internal class GuardedWebClipTransportException(
    val reason: GuardedWebClipFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal data class GuardedWebClipResponse(
    val body: String,
    val statusCode: Int,
    val contentType: String?,
    val finalUrl: String,
)

/**
 * Web-clip-only OkHttp transport. Each hop is authorized before a call, and
 * the OkHttp DNS callback independently returns only individually approved
 * addresses. Search continues to use its existing BoundedHttpClient.
 */
internal class GuardedWebClipHttpTransport private constructor(
    private val addressResolver: WebClipAddressResolver,
    private val addressAllowed: (InetAddress) -> Boolean,
    private val socketFactory: SocketFactory,
    private val allowNonDefaultPorts: Boolean,
    private val allowHttpForTests: Boolean,
    private val testSslSocketFactory: SSLSocketFactory?,
    private val testTrustManager: X509TrustManager?,
) {
    private val guardedDns = Dns { hostname ->
        WebClipUrlPolicy.resolveAllowedAddresses(hostname, addressResolver, addressAllowed)
            ?: throw UnknownHostException("Web-clip destination was not publicly permitted")
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(guardedDns)
        // Proxy peers cannot be DNS-bound by the clip policy, so all proxy modes stay disabled.
        .proxy(Proxy.NO_PROXY)
        .proxySelector(DirectOnlyProxySelector)
        .socketFactory(socketFactory)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        // Any alternative route must come from the individually validated DNS answer list.
        .retryOnConnectionFailure(false)
        .fastFallback(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        // A fresh direct connection is authorized for every request and redirect hop.
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(CALL_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .apply {
            if (testSslSocketFactory != null && testTrustManager != null) {
                sslSocketFactory(testSslSocketFactory, testTrustManager)
            }
        }
        .build()

    constructor(addressResolver: WebClipAddressResolver) : this(
        addressResolver = addressResolver,
        addressAllowed = WebClipUrlPolicy::isPublicInternetAddress,
        socketFactory = SocketFactory.getDefault(),
        allowNonDefaultPorts = false,
        allowHttpForTests = false,
        testSslSocketFactory = null,
        testTrustManager = null,
    )

    suspend fun fetch(startUrl: String): GuardedWebClipResponse {
        return try {
            withTimeout(OVERALL_TIMEOUT_MS) { fetchRedirectChain(startUrl) }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw transportFailure(GuardedWebClipFailure.NETWORK, "Web-clip request timed out", timeout)
        }
    }

    private suspend fun fetchRedirectChain(startUrl: String): GuardedWebClipResponse {
        var currentUrl = normalizeUrl(startUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val parsed = runCatching { URI(currentUrl).toURL() }.getOrNull()
                ?: throw transportFailure(GuardedWebClipFailure.INVALID_URL, "Invalid web-clip URL")

            val approved = withContext(Dispatchers.IO) {
                WebClipUrlPolicy.allowedAddresses(
                    url = parsed,
                    resolver = addressResolver,
                    addressAllowed = addressAllowed,
                    allowNonDefaultPort = allowNonDefaultPorts,
                    allowHttpForTests = allowHttpForTests,
                )
            }
            if (approved.isNullOrEmpty()) {
                throw transportFailure(
                    GuardedWebClipFailure.DISALLOWED_ADDRESS,
                    "Web-clip destination did not resolve exclusively to permitted addresses",
                )
            }

            val request = Request.Builder()
                .url(currentUrl)
                .get()
                .header("Accept", "text/html, application/xhtml+xml")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Fusion-WebClip/1.0")
                .build()
            val response = executeCall(client.newCall(request))
            if (response.statusCode in REDIRECT_STATUSES) {
                if (redirectCount >= MAX_REDIRECTS) {
                    throw transportFailure(GuardedWebClipFailure.TOO_MANY_REDIRECTS, "Too many web-clip redirects")
                }
                val location = response.location
                    ?: throw transportFailure(GuardedWebClipFailure.INVALID_URL, "Redirect did not contain Location")
                val next = runCatching { URL(parsed, location).toExternalForm() }.getOrNull()
                    ?: throw transportFailure(GuardedWebClipFailure.INVALID_URL, "Redirect Location was invalid")
                val nextUrl = normalizeUrl(next)
                val nextScheme = URI(nextUrl).scheme.lowercase()
                if (parsed.protocol.equals("https", ignoreCase = true) && nextScheme != "https") {
                    throw transportFailure(GuardedWebClipFailure.INVALID_URL, "HTTPS redirect downgrade is not permitted")
                }
                currentUrl = nextUrl
            } else {
                return GuardedWebClipResponse(
                    body = response.body,
                    statusCode = response.statusCode,
                    contentType = response.contentType,
                    finalUrl = response.finalUrl,
                )
            }
        }
        throw transportFailure(GuardedWebClipFailure.TOO_MANY_REDIRECTS, "Too many web-clip redirects")
    }

    private fun normalizeUrl(raw: String): String =
        WebClipUrlPolicy.normalize(raw, allowNonDefaultPorts, allowHttpForTests)
            ?: throw transportFailure(GuardedWebClipFailure.INVALID_URL, "Web-clip URL was not permitted")

    private suspend fun executeCall(call: Call): RawResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, failure: IOException) {
                    if (!continuation.isActive) return
                    val reason = if (failure is UnknownHostException) {
                        GuardedWebClipFailure.DISALLOWED_ADDRESS
                    } else {
                        GuardedWebClipFailure.NETWORK
                    }
                    continuation.resumeWithException(
                        GuardedWebClipTransportException(reason, "Web-clip request failed", failure),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val raw = response.use(::readResponse)
                        if (continuation.isActive) continuation.resume(raw)
                    } catch (cancelled: CancellationException) {
                        if (continuation.isActive) continuation.resumeWithException(cancelled)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }

    private fun readResponse(response: Response): RawResponse {
        val finalUrl = response.request.url.toString()
        val location = response.header("Location")
        if (response.code in REDIRECT_STATUSES) {
            return RawResponse(response.code, response.header("Content-Type"), finalUrl, location, "")
        }
        val body = response.body
        val limit = if (response.isSuccessful) MAX_SUCCESS_BYTES else MAX_ERROR_BYTES
        if (body.contentLength() > limit.toLong()) {
            throw transportFailure(GuardedWebClipFailure.RESPONSE_TOO_LARGE, "Web-clip response exceeded byte limit")
        }
        val contentEncoding = response.header("Content-Encoding")
        if (!contentEncoding.isNullOrBlank() && !contentEncoding.equals("identity", ignoreCase = true)) {
            throw transportFailure(GuardedWebClipFailure.MALFORMED_BODY, "Encoded web-clip responses are not supported")
        }
        val bytes = ByteArrayOutputStream(minOf(limit, 32 * 1024))
        val input = body.byteStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > limit) {
                throw transportFailure(GuardedWebClipFailure.RESPONSE_TOO_LARGE, "Web-clip response exceeded byte limit")
            }
            bytes.write(buffer, 0, read)
        }
        val responseBytes = bytes.toByteArray()
        return RawResponse(
            statusCode = response.code,
            contentType = response.header("Content-Type"),
            finalUrl = finalUrl,
            location = location,
            body = decodeHtmlBody(responseBytes, response.header("Content-Type")),
        )
    }

    private fun decodeHtmlBody(bytes: ByteArray, contentType: String?): String {
        val bomLength = if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) 3 else 0
        val charset = if (bomLength > 0) {
            StandardCharsets.UTF_8
        } else {
            declaredCharset(contentType)
                ?: sniffHtmlCharset(bytes)
                ?: StandardCharsets.UTF_8
        }
        val decoded = if (bomLength == 0) bytes else bytes.copyOfRange(bomLength, bytes.size)
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded))
                .toString()
        } catch (failure: Exception) {
            throw transportFailure(GuardedWebClipFailure.MALFORMED_BODY, "Web-clip body could not be decoded safely", failure)
        }
    }

    private fun declaredCharset(contentType: String?): Charset? {
        if (contentType == null) return null
        val declared = CHARSET_PATTERN.find(contentType)?.groupValues?.getOrNull(1)?.trim()
        if (declared.isNullOrBlank()) {
            if (CHARSET_DECLARED_PATTERN.containsMatchIn(contentType)) {
                throw transportFailure(GuardedWebClipFailure.INVALID_CHARSET, "Web-clip charset was invalid")
            }
            return null
        }
        return try {
            Charset.forName(declared)
        } catch (failure: Exception) {
            throw transportFailure(GuardedWebClipFailure.INVALID_CHARSET, "Web-clip charset was unsupported", failure)
        }
    }

    private fun sniffHtmlCharset(bytes: ByteArray): Charset? {
        val prefix = String(bytes, 0, minOf(bytes.size, HTML_SNIFF_BYTES), StandardCharsets.US_ASCII)
        val label = HTML_CHARSET_PATTERN.find(prefix)?.groupValues?.getOrNull(1)
            ?: LEGACY_HTML_CHARSET_PATTERN.find(prefix)?.groupValues?.getOrNull(1)
            ?: return null
        val charset = when (label.lowercase()) {
            "utf-8", "utf8" -> StandardCharsets.UTF_8
            "euc-kr", "ks_c_5601-1987", "ks_c_5601-1989", "cp949", "windows-949", "uhc" ->
                runCatching { Charset.forName("EUC-KR") }.getOrNull()
            else -> null
        }
        return charset ?: throw transportFailure(GuardedWebClipFailure.INVALID_CHARSET, "Web-clip HTML charset was unsupported")
    }

    private data class RawResponse(
        val statusCode: Int,
        val contentType: String?,
        val finalUrl: String,
        val location: String?,
        val body: String,
    )

    companion object {
        /** Test seam for controlled DNS and fake/local sockets; never used by the application constructor. */
        internal fun forTest(
            addressResolver: WebClipAddressResolver,
            socketFactory: SocketFactory = SocketFactory.getDefault(),
            addressAllowed: (InetAddress) -> Boolean = WebClipUrlPolicy::isPublicInternetAddress,
            allowNonDefaultPorts: Boolean = false,
            allowHttpForTests: Boolean = false,
            sslSocketFactory: SSLSocketFactory? = null,
            trustManager: X509TrustManager? = null,
        ): GuardedWebClipHttpTransport = GuardedWebClipHttpTransport(
            addressResolver = addressResolver,
            addressAllowed = addressAllowed,
            socketFactory = socketFactory,
            allowNonDefaultPorts = allowNonDefaultPorts,
            allowHttpForTests = allowHttpForTests,
            testSslSocketFactory = sslSocketFactory,
            testTrustManager = trustManager,
        )

        const val CONNECT_TIMEOUT_MS = 6_000L
        const val READ_TIMEOUT_MS = 8_000L
        const val WRITE_TIMEOUT_MS = 8_000L
        const val CALL_TIMEOUT_MS = 15_000L
        const val OVERALL_TIMEOUT_MS = 20_000L
        const val MAX_SUCCESS_BYTES = 1_000_000
        const val MAX_ERROR_BYTES = 16_384
        const val MAX_REDIRECTS = 5
        const val HTML_SNIFF_BYTES = 1024
        val CHARSET_PATTERN = Regex("(?i)charset\\s*=\\s*[\\\"']?([^;\\\"']+)")
        val CHARSET_DECLARED_PATTERN = Regex("(?i)\\bcharset\\s*=")
        val HTML_CHARSET_PATTERN = Regex("(?is)<meta\\b[^>]*?\\bcharset\\s*=\\s*[\\\"']?([a-z0-9._-]+)")
        val LEGACY_HTML_CHARSET_PATTERN = Regex("(?is)<meta\\b[^>]*?\\bcontent\\s*=\\s*[\\\"'][^\\\"']*?charset\\s*=\\s*[\\\"']?([a-z0-9._-]+)")
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        fun transportFailure(
            reason: GuardedWebClipFailure,
            message: String,
            cause: Throwable? = null,
        ) = GuardedWebClipTransportException(reason, message, cause)
    }
}

private object DirectOnlyProxySelector : ProxySelector() {
    override fun select(uri: URI): MutableList<Proxy> = mutableListOf(Proxy.NO_PROXY)

    override fun connectFailed(uri: URI, socketAddress: SocketAddress, failure: IOException) = Unit
}
