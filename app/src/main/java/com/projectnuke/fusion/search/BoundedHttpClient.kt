package com.projectnuke.fusion.search

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal data class BoundedHttpRequest(
    val url: String,
    val method: String = "GET",
    val body: ByteArray? = null,
    val headers: Map<String, String> = emptyMap(),
    val strictCharset: Boolean = false,
    /** Applies the bounded HTML BOM/header/meta detection order before strict decoding. */
    val sniffHtmlCharset: Boolean = false,
    /** Rechecked before every hop, including redirects. */
    val urlPolicy: ((URL) -> Boolean)? = null,
    /** Rechecked at the HTTP emission boundary, including redirects. */
    val requestAuthorizationCheck: (() -> Unit)? = null,
    /** Invoked after cancellation and authorization checks, immediately before network I/O. */
    val onTransportAttempt: () -> Unit = {},
)

internal data class BoundedHttpResponse(
    val body: String,
    val statusCode: Int,
    val contentType: String?,
    val finalUrl: String,
)

internal enum class BoundedHttpFailure {
    RESPONSE_TOO_LARGE,
    ERROR_RESPONSE_TOO_LARGE,
    INVALID_REDIRECT,
    AUTHENTICATED_CROSS_HOST_REDIRECT,
    CROSS_ORIGIN_BODY_REDIRECT,
    TOO_MANY_REDIRECTS,
    URL_NOT_ALLOWED,
    INVALID_CHARSET,
    MALFORMED_BODY,
}

internal class BoundedHttpException(
    val kind: BoundedHttpFailure,
    message: String,
) : Exception(message)

internal fun interface SearchConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

internal class BoundedHttpClient(
    private val connectionFactory: SearchConnectionFactory = SearchConnectionFactory { url ->
        url.openConnection() as HttpURLConnection
    },
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 8_000,
    private val maxSuccessBytes: Int = 2 * 1024 * 1024,
    private val maxErrorBytes: Int = 64 * 1024,
) {
    suspend fun execute(request: BoundedHttpRequest): BoundedHttpResponse =
        withContext(Dispatchers.IO) {
            val activeConnection = AtomicReference<HttpURLConnection?>()
            val cancelled = AtomicBoolean(false)
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    cancelled.set(true)
                    activeConnection.getAndSet(null)?.disconnect()
                }
                try {
                    val response = executeBlocking(request, activeConnection, cancelled)
                    if (continuation.isActive) continuation.resume(response)
                } catch (cancellation: CancellationException) {
                    if (continuation.isActive) continuation.cancel(cancellation)
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        }

    private fun executeBlocking(
        initialRequest: BoundedHttpRequest,
        activeConnection: AtomicReference<HttpURLConnection?>,
        cancelled: AtomicBoolean,
    ): BoundedHttpResponse {
        var currentUrl = URL(initialRequest.url)
        var method = initialRequest.method.uppercase()
        var body = initialRequest.body
        val hasSensitiveHeaders = initialRequest.headers.keys.any(::isSensitiveHeader)

        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            checkCancellation(cancelled)
            initialRequest.requestAuthorizationCheck?.invoke()
            if (!isUrlAllowed(initialRequest.urlPolicy, currentUrl)) {
                throw BoundedHttpException(BoundedHttpFailure.URL_NOT_ALLOWED, "HTTP URL was not permitted")
            }
            val connection = connectionFactory.open(currentUrl).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                useCaches = false
                initialRequest.headers.forEach { (key, value) -> setRequestProperty(key, value) }
                if (body != null) doOutput = true
            }
            activeConnection.set(connection)
            var handedOff = false
            var transportAttemptReported = false
            fun reportTransportAttempt() {
                if (transportAttemptReported) return
                checkCancellation(cancelled)
                initialRequest.requestAuthorizationCheck?.invoke()
                checkCancellation(cancelled)
                initialRequest.onTransportAttempt()
                transportAttemptReported = true
            }
            try {
                checkCancellation(cancelled)
                initialRequest.requestAuthorizationCheck?.invoke()
                body?.let { requestBody ->
                    checkCancellation(cancelled)
                    initialRequest.requestAuthorizationCheck?.invoke()
                    reportTransportAttempt()
                    connection.outputStream.use { output ->
                        output.write(requestBody)
                        output.flush()
                    }
                }
                checkCancellation(cancelled)
                initialRequest.requestAuthorizationCheck?.invoke()
                reportTransportAttempt()
                val status = connection.responseCode
                if (status in REDIRECT_STATUSES) {
                    checkCancellation(cancelled)
                    if (redirectIndex >= MAX_REDIRECTS) {
                        throw BoundedHttpException(
                            BoundedHttpFailure.TOO_MANY_REDIRECTS,
                            "Too many redirects",
                        )
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw BoundedHttpException(
                            BoundedHttpFailure.INVALID_REDIRECT,
                            "Redirect did not contain Location",
                        )
                    val nextUrl = runCatching { URL(currentUrl, location) }.getOrNull()
                        ?: throw BoundedHttpException(
                            BoundedHttpFailure.INVALID_REDIRECT,
                            "Redirect Location was invalid",
                        )
                    if (nextUrl.protocol !in setOf("http", "https") ||
                        (currentUrl.protocol == "https" && nextUrl.protocol != "https")
                    ) {
                        throw BoundedHttpException(
                            BoundedHttpFailure.INVALID_REDIRECT,
                            "Redirect scheme was not permitted",
                        )
                    }
                    val nextMethod: String
                    val nextBody: ByteArray?
                    if (status == HttpURLConnection.HTTP_SEE_OTHER) {
                        nextMethod = "GET"
                        nextBody = null
                    } else {
                        nextMethod = method
                        nextBody = body
                    }
                    val crossesOrigin = !sameOrigin(currentUrl, nextUrl)
                    if (crossesOrigin && nextBody != null) {
                        throw BoundedHttpException(
                            BoundedHttpFailure.CROSS_ORIGIN_BODY_REDIRECT,
                            "Cross-origin redirect would resend the request body",
                        )
                    }
                    if (hasSensitiveHeaders && crossesOrigin) {
                        throw BoundedHttpException(
                            BoundedHttpFailure.AUTHENTICATED_CROSS_HOST_REDIRECT,
                            "Authenticated redirect crossed origin",
                        )
                    }
                    method = nextMethod
                    body = nextBody
                    currentUrl = nextUrl
                    return@repeat
                }

                val success = status in 200..299
                val cap = if (success) maxSuccessBytes else maxErrorBytes
                val declaredLength = connection.contentLengthLong
                if (declaredLength > cap) {
                    throw BoundedHttpException(
                        if (success) BoundedHttpFailure.RESPONSE_TOO_LARGE
                        else BoundedHttpFailure.ERROR_RESPONSE_TOO_LARGE,
                        "HTTP response exceeded byte budget",
                    )
                }
                val stream = if (success) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
                val bytes = stream.use { input ->
                    val output = ByteArrayOutputStream(minOf(cap, 32 * 1024))
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        checkCancellation(cancelled)
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > cap) {
                            throw BoundedHttpException(
                                if (success) BoundedHttpFailure.RESPONSE_TOO_LARGE
                                else BoundedHttpFailure.ERROR_RESPONSE_TOO_LARGE,
                                "HTTP response exceeded streaming byte budget",
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                val contentType = connection.contentType
                handedOff = true
                return BoundedHttpResponse(
                    body = decodeResponseBody(
                        bytes = bytes,
                        contentType = contentType,
                        strict = initialRequest.strictCharset,
                        sniffHtml = initialRequest.sniffHtmlCharset,
                    ),
                    statusCode = status,
                    contentType = contentType,
                    finalUrl = connection.url.toString(),
                )
            } finally {
                activeConnection.compareAndSet(connection, null)
                connection.disconnect()
                if (handedOff) {
                    // The response body is already detached; no connection ownership escapes.
                }
            }
        }
        throw BoundedHttpException(
            BoundedHttpFailure.TOO_MANY_REDIRECTS,
            "Too many redirects",
        )
    }

    private fun checkCancellation(cancelled: AtomicBoolean) {
        if (cancelled.get()) throw CancellationException("HTTP request cancelled")
    }

    private fun isUrlAllowed(policy: ((URL) -> Boolean)?, url: URL): Boolean {
        if (policy == null) return true
        return try {
            policy(url)
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeResponseBody(
        bytes: ByteArray,
        contentType: String?,
        strict: Boolean,
        sniffHtml: Boolean,
    ): String {
        val utf8BomLength = if (
            sniffHtml && bytes.size >= 3 &&
            bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
        ) 3 else 0
        val charset = if (utf8BomLength > 0) {
            StandardCharsets.UTF_8
        } else {
            contentType.charsetFromContentType(strict)
                ?: if (sniffHtml) sniffHtmlDeclaredCharset(bytes, strict) ?: StandardCharsets.UTF_8
                else StandardCharsets.UTF_8
        }
        return decodeBody(
            bytes = if (utf8BomLength == 0) bytes else bytes.copyOfRange(utf8BomLength, bytes.size),
            charset = charset,
            strict = strict,
        )
    }

    private fun String?.charsetFromContentType(strict: Boolean): Charset? {
        val value = this ?: return null
        val match = HTTP_CHARSET_PATTERN.find(value)
        val charsetDeclared = HTTP_CHARSET_DECLARED_PATTERN.containsMatchIn(value)
        val declared = match?.groupValues?.getOrNull(1)?.trim()
        if (declared.isNullOrBlank()) {
            if (strict && charsetDeclared) invalidCharset()
            return null
        }
        return try {
            Charset.forName(declared)
        } catch (_: Exception) {
            if (strict) invalidCharset()
            StandardCharsets.UTF_8
        }
    }

    /**
     * HTML metadata is inspected only in the first 1024 response bytes. The
     * supported labels are intentionally explicit: UTF-8 and Korean EUC-KR
     * aliases. An unknown declared label fails closed under strict decoding.
     */
    private fun sniffHtmlDeclaredCharset(bytes: ByteArray, strict: Boolean): Charset? {
        val prefix = String(bytes, 0, minOf(bytes.size, HTML_META_SNIFF_BYTES), StandardCharsets.US_ASCII)
        val label = HTML5_META_CHARSET_PATTERN.find(prefix)?.groupValues?.getOrNull(1)
            ?: LEGACY_META_CHARSET_PATTERN.find(prefix)?.groupValues?.getOrNull(1)
            ?: return null
        val charset = when (label.lowercase()) {
            "utf-8", "utf8" -> StandardCharsets.UTF_8
            "euc-kr", "ks_c_5601-1987", "ks_c_5601-1989", "cp949", "windows-949", "uhc" ->
                runCatching { Charset.forName("EUC-KR") }.getOrNull()
            else -> null
        }
        if (charset == null && strict) invalidCharset()
        return charset
    }

    private fun invalidCharset(): Nothing = throw BoundedHttpException(
        BoundedHttpFailure.INVALID_CHARSET,
        "HTTP charset was not supported",
    )

    private fun decodeBody(bytes: ByteArray, charset: Charset, strict: Boolean): String {
        if (!strict) return String(bytes, charset)
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw BoundedHttpException(BoundedHttpFailure.MALFORMED_BODY, "HTTP body could not be decoded safely")
        }
    }

    private fun isSensitiveHeader(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "authorization" ||
            normalized == "proxy-authorization" ||
            normalized == "cookie" ||
            normalized.contains("api-key") ||
            normalized.contains("subscription-token") ||
            normalized.contains("client-secret")
    }

    private fun sameOrigin(first: URL, second: URL): Boolean =
        first.protocol.equals(second.protocol, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)

    private fun effectivePort(url: URL): Int =
        if (url.port >= 0) url.port else url.defaultPort

    private companion object {
        const val MAX_REDIRECTS = 5
        const val HTML_META_SNIFF_BYTES = 1024
        val HTTP_CHARSET_PATTERN = Regex("""(?i)charset\s*=\s*["']?([^;"']+)""")
        val HTTP_CHARSET_DECLARED_PATTERN = Regex("""(?i)\bcharset\s*=""")
        val HTML5_META_CHARSET_PATTERN = Regex("""(?is)<meta\b[^>]*?\bcharset\s*=\s*["']?([a-z0-9._-]+)""")
        val LEGACY_META_CHARSET_PATTERN = Regex("""(?is)<meta\b[^>]*?\bcontent\s*=\s*["'][^"']*?charset\s*=\s*["']?([a-z0-9._-]+)""")
        val REDIRECT_STATUSES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}
