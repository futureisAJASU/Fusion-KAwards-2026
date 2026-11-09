package com.projectnuke.fusion.knowledge

import com.projectnuke.fusion.search.normalizeSearchHtmlText
import com.projectnuke.fusion.util.SafeWebUrlPolicy
import java.net.InetAddress
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class WebClipDocument internal constructor(
    val originalUrl: String,
    val pageTitle: String?,
    val displayTitle: String,
    val text: String,
    val textTruncated: Boolean,
    val retrievedAt: Long,
    /** Final URL that supplied the captured body; null means legacy provenance is unknown. */
    val effectiveUrl: String? = null,
)

sealed interface WebClipFetchResult {
    data class Captured(val capture: WebClipCapture) : WebClipFetchResult {
        val document: WebClipDocument get() = capture.document
    }
    data class Failed(val reason: WebClipFailure) : WebClipFetchResult
}

/** Immutable proof that this snapshot came from a completed guarded fetch. */
class WebClipCapture private constructor(val document: WebClipDocument) {
    companion object {
        internal fun fromGuardedFetch(document: WebClipDocument) = WebClipCapture(document)
    }
}

enum class WebClipFailure {
    INVALID_URL,
    PRIVATE_NETWORK,
    NETWORK,
    HTTP_STATUS,
    UNSUPPORTED_CONTENT,
    RESPONSE_TOO_LARGE,
    INVALID_CHARSET,
    MALFORMED_CONTENT,
    NO_READABLE_TEXT,
}

fun interface WebClipAddressResolver {
    fun resolve(host: String): Array<InetAddress>
}

/** Fetches one user-selected public web page through its isolated guarded HTTP transport. */
class WebClipFetcher private constructor(
    private val httpClient: GuardedWebClipHttpTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val allowNonDefaultPorts: Boolean,
    private val allowHttpForTests: Boolean,
    @Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) {
    constructor() : this(WebClipAddressResolver(InetAddress::getAllByName))

    /** Production construction; the transport owns DNS validation and the direct socket route. */
    internal constructor(
        addressResolver: WebClipAddressResolver,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(GuardedWebClipHttpTransport(addressResolver), clock, false, false, Unit)

    /** Fake DNS/socket seam for deterministic host tests. */
    internal constructor(
        httpClient: GuardedWebClipHttpTransport,
        allowNonDefaultPorts: Boolean = false,
        allowHttpForTests: Boolean = false,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(httpClient, clock, allowNonDefaultPorts, allowHttpForTests, Unit)

    suspend fun fetch(rawUrl: String): WebClipFetchResult {
        val normalizedUrl = WebClipUrlPolicy.normalize(rawUrl, allowNonDefaultPorts, allowHttpForTests)
            ?: return WebClipFetchResult.Failed(WebClipFailure.INVALID_URL)
        val response = try {
            httpClient.fetch(normalizedUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GuardedWebClipTransportException) {
            return WebClipFetchResult.Failed(failure.toWebClipFailure())
        } catch (_: Exception) {
            return WebClipFetchResult.Failed(WebClipFailure.NETWORK)
        }
        return process(normalizedUrl, response)
    }

    private suspend fun process(originalUrl: String, response: GuardedWebClipResponse): WebClipFetchResult =
        withContext(Dispatchers.Default) {
            if (response.statusCode !in 200..299) {
                return@withContext WebClipFetchResult.Failed(WebClipFailure.HTTP_STATUS)
            }
            val mime = response.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (mime !in ALLOWED_MIME_TYPES) {
                return@withContext WebClipFetchResult.Failed(WebClipFailure.UNSUPPORTED_CONTENT)
            }
            val context = currentCoroutineContext()
            val extracted = WebClipHtmlExtractor.extract(response.body) { context.ensureActive() }
                ?: return@withContext WebClipFetchResult.Failed(WebClipFailure.NO_READABLE_TEXT)
            context.ensureActive()
            val effectiveUrl = WebClipUrlPolicy.normalize(response.finalUrl, allowNonDefaultPorts, allowHttpForTests)
                ?: return@withContext WebClipFetchResult.Failed(WebClipFailure.INVALID_URL)
            // The guarded transport authorized the final hop and its socket before
            // receiving this body. DNS can change after that completed request.
            context.ensureActive()
            val host = try { URI(originalUrl).host.orEmpty() } catch (_: Exception) { "" }
            WebClipFetchResult.Captured(
                WebClipCapture.fromGuardedFetch(
                WebClipDocument(
                    originalUrl = originalUrl,
                    pageTitle = extracted.title,
                    displayTitle = extracted.title ?: "웹 클립 · $host".take(MAX_TITLE_CHARS),
                    text = extracted.text,
                    textTruncated = extracted.truncated,
                    retrievedAt = clock(),
                    effectiveUrl = effectiveUrl,
                ),
                ),
            )
        }

    private fun GuardedWebClipTransportException.toWebClipFailure(): WebClipFailure = when (reason) {
        GuardedWebClipFailure.INVALID_URL -> WebClipFailure.INVALID_URL
        GuardedWebClipFailure.DISALLOWED_ADDRESS -> WebClipFailure.PRIVATE_NETWORK
        GuardedWebClipFailure.RESPONSE_TOO_LARGE -> WebClipFailure.RESPONSE_TOO_LARGE
        GuardedWebClipFailure.INVALID_CHARSET -> WebClipFailure.INVALID_CHARSET
        GuardedWebClipFailure.MALFORMED_BODY -> WebClipFailure.MALFORMED_CONTENT
        GuardedWebClipFailure.NETWORK,
        GuardedWebClipFailure.TOO_MANY_REDIRECTS -> WebClipFailure.NETWORK
    }

    private companion object {
        const val MAX_TITLE_CHARS = 160
        val ALLOWED_MIME_TYPES = setOf("text/html", "application/xhtml+xml")
    }
}

/** URL guard checked before opening each redirect hop; never accepts non-public DNS answers. */
internal object WebClipUrlPolicy {
    fun normalize(raw: String?, allowNonDefaultPort: Boolean = false, allowHttpForTests: Boolean = false): String? {
        val normalized = SafeWebUrlPolicy.normalize(raw) ?: return null
        if (SavedKnowledgePolicy.containsSensitiveMaterial(normalized)) return null
        return try {
            val uri = URI(normalized)
            val scheme = uri.scheme.lowercase()
            if (scheme != "https" && !allowHttpForTests) return null
            val port = uri.port
            val expectedPort = if (scheme == "https") 443 else 80
            if (!allowNonDefaultPort && port != -1 && port != expectedPort) return null
            normalized
        } catch (_: Exception) {
            null
        }
    }

    fun allows(url: URL, resolver: WebClipAddressResolver): Boolean =
        allowedAddresses(url, resolver) != null

    internal fun allowedAddresses(
        url: URL,
        resolver: WebClipAddressResolver,
        addressAllowed: (InetAddress) -> Boolean = ::isPublicInternetAddress,
        allowNonDefaultPort: Boolean = false,
        allowHttpForTests: Boolean = false,
    ): List<InetAddress>? {
        val normalized = normalize(url.toString(), allowNonDefaultPort, allowHttpForTests) ?: return null
        val host = try { URI(normalized).host?.trim('[', ']')?.trimEnd('.') } catch (_: Exception) { null }
            ?: return null
        return resolveAllowedAddresses(host, resolver, addressAllowed)
    }

    internal fun resolveAllowedAddresses(
        rawHost: String,
        resolver: WebClipAddressResolver,
        addressAllowed: (InetAddress) -> Boolean = ::isPublicInternetAddress,
    ): List<InetAddress>? {
        val host = rawHost.trim('[', ']')
        if (host.isBlank() || isDisallowedHost(host)) return null
        val literal = numericLiteral(host)
        if (literal === NumericLiteral.Invalid) return null
        val addresses = when (literal) {
            is NumericLiteral.Valid -> listOf(literal.address)
            NumericLiteral.Invalid -> return null
            NumericLiteral.NotLiteral -> try {
                resolver.resolve(host).toList()
            } catch (_: Exception) {
                return null
            }
        }
        if (addresses.isEmpty() || addresses.any { address -> runCatching { !addressAllowed(address) }.getOrDefault(true) }) {
            return null
        }
        return addresses
    }

    internal fun isPublicNumericLiteral(host: String): Boolean {
        if (isDisallowedHost(host)) return false
        return when (val literal = numericLiteral(host)) {
            is NumericLiteral.Valid -> isPublicInternetAddress(literal.address)
            NumericLiteral.Invalid -> false
            NumericLiteral.NotLiteral -> true
        }
    }

    private fun isDisallowedHost(host: String): Boolean {
        val lowered = host.lowercase().trimEnd('.')
        return lowered == "localhost" || lowered.endsWith(".localhost") || lowered.endsWith(".local") ||
            lowered == "metadata.google.internal"
    }

    private sealed interface NumericLiteral {
        data class Valid(val address: InetAddress) : NumericLiteral
        data object Invalid : NumericLiteral
        data object NotLiteral : NumericLiteral
    }

    private fun numericLiteral(host: String): NumericLiteral {
        if (host.contains(':')) {
            if (!host.matches(Regex("^[0-9A-Fa-f:.]+$"))) return NumericLiteral.Invalid
            val address = runCatching { InetAddress.getByName(host) }.getOrNull()
                ?: return NumericLiteral.Invalid
            return if (address.address.size == 16) NumericLiteral.Valid(address) else NumericLiteral.Invalid
        }
        val candidate = host.matches(
            Regex("(?i)^(?:[0-9]+|0x[0-9a-f]+)(?:\\.(?:[0-9]+|0x[0-9a-f]+)){0,3}$"),
        )
        if (!candidate) return NumericLiteral.NotLiteral
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.length > 1 && it.startsWith('0') || it.startsWith("0x", true) }) {
            return NumericLiteral.Invalid
        }
        val bytes = ByteArray(4)
        for (index in parts.indices) {
            val octet = parts[index].toIntOrNull()?.takeIf { it in 0..255 } ?: return NumericLiteral.Invalid
            bytes[index] = octet.toByte()
        }
        return NumericLiteral.Valid(InetAddress.getByAddress(bytes))
    }

    internal fun isPublicInternetAddress(address: InetAddress): Boolean {
        val bytes = address.address
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            val c = bytes[2].toInt() and 0xff
            val d = bytes[3].toInt() and 0xff
            return when {
                a == 0 || a == 10 || a == 127 || a >= 224 -> false
                a == 100 && b in 64..127 -> false
                a == 169 && b == 254 -> false
                a == 172 && b in 16..31 -> false
                a == 192 && b == 0 && c == 0 && d != 9 && d != 10 -> false
                a == 192 && b == 0 && c == 2 -> false
                a == 192 && b == 168 -> false
                a == 192 && b == 88 && c == 99 -> false
                a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)) -> false
                a == 203 && b == 0 && c == 113 -> false
                a == 168 && b == 63 && c == 129 && d == 16 -> false // Azure WireServer/platform service VIP.
                a >= 240 -> false
                else -> true
            }
        }
        if (bytes.size != 16) return false
        if (isIpv4Mapped(bytes)) {
            return isPublicInternetAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        if ((first and 0xfe) == 0xfc || (first == 0xfe && (second and 0xc0) == 0x80) ||
            (first == 0xfe && (second and 0xc0) == 0xc0) || first == 0xff
        ) return false
        // The globally reachable well-known NAT64 prefix embeds IPv4 in /96;
        // classify the embedded destination too, so synthesized private IPv4
        // targets do not bypass this policy. Local-use NAT64 stays denied.
        if (isWellKnownNat64Prefix(bytes)) {
            return isPublicInternetAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        if (isLocalUseNat64Prefix(bytes)) return false

        // Only global-unicast 2000::/3 can be a public IPv6 page origin.
        if (first !in 0x20..0x3f) return false
        if (first == 0x20 && second == 0x01 &&
            ((bytes[2].toInt() and 0xff) and 0xfe) == 0 &&
            !isGloballyReachable2001Assignment(bytes)
        ) return false // 2001::/23 except the registry's more-specific globally reachable entries.
        if (first == 0x20 && second == 0x01 &&
            (bytes[2].toInt() and 0xff) == 0x0d && (bytes[3].toInt() and 0xff) == 0xb8
        ) return false // 2001:db8::/32 documentation.
        if (first == 0x20 && second == 0x02) return false // 6to4 transition addresses.
        if (first == 0x3f && second == 0xff &&
            ((bytes[2].toInt() and 0xff) and 0xf0) == 0
        ) return false // 3fff::/20 documentation.
        return true
    }

    private fun isWellKnownNat64Prefix(bytes: ByteArray): Boolean =
        bytes.size == 16 && (bytes[0].toInt() and 0xff) == 0x00 &&
            (bytes[1].toInt() and 0xff) == 0x64 && (bytes[2].toInt() and 0xff) == 0xff &&
            (bytes[3].toInt() and 0xff) == 0x9b &&
            (4 until 12).all { (bytes[it].toInt() and 0xff) == 0 }

    private fun isLocalUseNat64Prefix(bytes: ByteArray): Boolean =
        bytes.size == 16 && (bytes[0].toInt() and 0xff) == 0x00 &&
            (bytes[1].toInt() and 0xff) == 0x64 && (bytes[2].toInt() and 0xff) == 0xff &&
            (bytes[3].toInt() and 0xff) == 0x9b &&
            (bytes[4].toInt() and 0xff) == 0x00 && (bytes[5].toInt() and 0xff) == 0x01

    private fun isGloballyReachable2001Assignment(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        return when {
            third == 0 && fourth == 1 && bytes.copyOfRange(4, 15).all { it.toInt() == 0 } &&
                (bytes[15].toInt() and 0xff) in 1..3 -> true
            third == 0 && fourth == 3 -> true // 2001:3::/32 (AMT)
            third == 0 && fourth == 4 && (bytes[4].toInt() and 0xff) == 1 &&
                (bytes[5].toInt() and 0xff) == 0x12 -> true // 2001:4:112::/48 (AS112)
            third == 0 && fourth in 0x20..0x2f -> true // 2001:20::/28 (ORCHIDv2)
            third == 0 && fourth in 0x30..0x3f -> true // 2001:30::/28 (DET)
            else -> false
        }
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean =
        (0 until 10).all { bytes[it].toInt() == 0 } &&
            (bytes[10].toInt() and 0xff) == 0xff && (bytes[11].toInt() and 0xff) == 0xff
}

internal data class ExtractedWebClipText(val title: String?, val text: String, val truncated: Boolean)

/**
 * Bounded single-pass HTML text extraction. The input is capped before parsing;
 * tag and comment scans advance monotonically and cancellation is checked at
 * fixed intervals, so malformed markup cannot trigger regex backtracking.
 */
internal object WebClipHtmlExtractor {
    const val MAX_TEXT_CHARS = 12_000
    private const val MAX_HTML_CHARS = 1_000_000
    private const val MAX_BLOCKED_NESTING = 256
    private const val CHECK_INTERVAL = 4096
    private val blockedTags = setOf(
        "script", "style", "noscript", "svg", "iframe", "object", "template", "nav",
        "header", "footer", "form", "button", "select", "textarea",
    )
    private val blockTags = setOf(
        "br", "p", "div", "li", "tr", "section", "article", "main", "blockquote",
        "h1", "h2", "h3", "h4", "h5", "h6",
    )
    private val headStartTags = setOf(
        "base", "basefont", "bgsound", "link", "meta", "title", "noscript", "noframes",
        "style", "script", "template",
    )

    fun extract(html: String, cancellationCheck: () -> Unit = {}): ExtractedWebClipText? {
        if (html.isBlank()) return null
        val limit = minOf(html.length, MAX_HTML_CHARS)
        val body = NormalizedTextBuilder(MAX_TEXT_CHARS)
        val title = NormalizedTextBuilder(160)
        val blockedStack = ArrayList<String>()
        var headDepth = 0
        var titleSeen = false
        var capturingTitle = false
        var i = 0
        var steps = 0
        var nestingOverflow = false

        while (i < limit) {
            if (++steps >= CHECK_INTERVAL) {
                cancellationCheck()
                steps = 0
            }
            if (nestingOverflow) break

            if (html[i] == '<') {
                if (html.startsWith("<!--", i)) {
                    val end = findCommentEnd(html, i + 4, limit, cancellationCheck)
                    if (end < 0) break
                    i = end + 3
                    continue
                }
                val parsed = parseTag(html, i, limit, cancellationCheck)
                if (parsed == null) {
                    if (blockedStack.isEmpty() && headDepth == 0) body.appendCodePoint('<'.code)
                    if (capturingTitle && blockedStack.isEmpty()) title.appendCodePoint('<'.code)
                    i++
                    continue
                }
                if (parsed.end < 0) break
                val tag = parsed.name
                i = parsed.end + 1

                if (blockedStack.isNotEmpty()) {
                    if (tag in blockedTags) {
                        if (parsed.closing) {
                            val matching = blockedStack.indexOfLast { it == tag }
                            if (matching >= 0) {
                                blockedStack.subList(matching, blockedStack.size).clear()
                                if (blockedStack.isEmpty()) body.boundary()
                            }
                        } else if (!parsed.selfClosing) {
                            if (blockedStack.size >= MAX_BLOCKED_NESTING) nestingOverflow = true
                            else blockedStack += tag
                        }
                    }
                    continue
                }

                when (tag) {
                    "head" -> {
                        headDepth = if (parsed.closing) (headDepth - 1).coerceAtLeast(0)
                        else if (!parsed.selfClosing) headDepth + 1 else headDepth
                        continue
                    }
                    "body" -> {
                        if (!parsed.closing && !capturingTitle) headDepth = 0
                        continue
                    }
                    "title" -> {
                        if (parsed.closing) capturingTitle = false
                        else if (!titleSeen && !parsed.selfClosing) {
                            titleSeen = true
                            capturingTitle = true
                        }
                        continue
                    }
                }

                // HTML5 closes the head implicitly when body content starts.
                // This intentionally implements the extraction-relevant
                // subset: allowed metadata/resource starts stay in head;
                // ordinary elements are reprocessed as body content.
                if (tag.isNotEmpty() && headDepth > 0 && !capturingTitle && !parsed.closing && tag !in headStartTags) {
                    headDepth = 0
                }

                if (tag in blockedTags) {
                    if (parsed.closing) {
                        body.boundary()
                    } else if (!parsed.selfClosing) {
                        blockedStack += tag
                    }
                    continue
                }
                if (headDepth > 0) continue
                if (tag in blockTags) body.boundary()
                continue
            }

            val nextTag = html.indexOf('<', i).let { if (it < 0 || it > limit) limit else it }
            val textEnd = maxOf(i + 1, nextTag)
            while (i < textEnd) {
                if (++steps >= CHECK_INTERVAL) {
                    cancellationCheck()
                    steps = 0
                }
                if (capturingTitle && blockedStack.isEmpty()) {
                    i = title.appendHtmlText(html, i, textEnd)
                } else if (blockedStack.isEmpty() && headDepth == 0) {
                    i = body.appendHtmlText(html, i, textEnd)
                } else if (blockedStack.isEmpty() && headDepth > 0) {
                    val codePoint = Character.codePointAt(html, i)
                    val next = i + Character.charCount(codePoint)
                    if (!Character.isWhitespace(codePoint) && codePoint != 0x00a0) {
                        // Non-whitespace character data ends the HTML5 head
                        // insertion mode; retain it as body text.
                        headDepth = 0
                        i = body.appendHtmlText(html, i, textEnd)
                    } else {
                        i = next
                    }
                } else {
                    i++
                }
            }
        }
        cancellationCheck()
        val text = body.finish().take(MAX_TEXT_CHARS)
        if (text.isBlank()) return null
        val cleanTitle = title.finish().takeIf(String::isNotBlank)?.take(160)
        return ExtractedWebClipText(
            title = cleanTitle,
            text = text,
            truncated = html.length > MAX_HTML_CHARS || body.overflowed || nestingOverflow,
        )
    }

    private data class ParsedTag(
        val name: String,
        val closing: Boolean,
        val selfClosing: Boolean,
        val end: Int,
    )

    private fun parseTag(
        html: String,
        start: Int,
        limit: Int,
        cancellationCheck: () -> Unit,
    ): ParsedTag? {
        if (start + 1 < limit && (html[start + 1] == '!' || html[start + 1] == '?')) {
            var quote: Char? = null
            var cursor = start + 2
            while (cursor < limit) {
                if ((cursor - start) % CHECK_INTERVAL == 0) cancellationCheck()
                val char = html[cursor]
                if (quote != null) {
                    if (char == quote) quote = null
                } else when (char) {
                    '\'', '"' -> quote = char
                    '>' -> return ParsedTag("", closing = false, selfClosing = true, end = cursor)
                }
                cursor++
            }
            return ParsedTag("", closing = false, selfClosing = true, end = -1)
        }
        var nameStart = start + 1
        var closing = false
        if (nameStart < limit && html[nameStart] == '/') {
            closing = true
            nameStart++
        }
        if (nameStart >= limit || !html[nameStart].isLetter()) return null
        var nameEnd = nameStart
        while (nameEnd < limit && (html[nameEnd].isLetterOrDigit() || html[nameEnd] == '-' || html[nameEnd] == ':')) {
            nameEnd++
        }
        val nameLength = nameEnd - nameStart
        val name = if (nameLength <= MAX_TAG_NAME_CHARS) html.substring(nameStart, nameEnd).lowercase() else ""
        var quote: Char? = null
        var cursor = nameEnd
        while (cursor < limit) {
            if ((cursor - nameEnd) % CHECK_INTERVAL == 0) cancellationCheck()
            val char = html[cursor]
            if (quote != null) {
                if (char == quote) quote = null
            } else when (char) {
                '\'', '"' -> quote = char
                '>' -> {
                    var beforeEnd = cursor - 1
                    while (beforeEnd >= nameEnd && html[beforeEnd].isWhitespace()) beforeEnd--
                    return ParsedTag(name, closing, beforeEnd >= nameEnd && html[beforeEnd] == '/', cursor)
                }
            }
            cursor++
        }
        return ParsedTag(name, closing, selfClosing = false, end = -1)
    }

    private fun findCommentEnd(
        html: String,
        start: Int,
        limit: Int,
        cancellationCheck: () -> Unit,
    ): Int {
        var cursor = start
        while (cursor + 2 < limit) {
            if ((cursor - start) % CHECK_INTERVAL == 0) cancellationCheck()
            if (html[cursor] == '-' && html[cursor + 1] == '-' && html[cursor + 2] == '>') return cursor
            cursor++
        }
        return -1
    }

    private class NormalizedTextBuilder(private val maxChars: Int) {
        private val value = StringBuilder(minOf(maxChars, 256))
        private var pendingSpace = false
        var overflowed: Boolean = false
            private set

        fun boundary() {
            if (value.isNotEmpty()) pendingSpace = true
        }

        fun appendHtmlText(source: String, index: Int, end: Int): Int {
            val entity = decodeEntityAt(source, index, end)
            if (entity != null) {
                appendCodePoint(entity.first)
                return index + entity.second
            }
            val codePoint = Character.codePointAt(source, index)
            appendCodePoint(codePoint)
            return index + Character.charCount(codePoint)
        }

        fun appendCodePoint(codePoint: Int) {
            if (Character.isWhitespace(codePoint) || codePoint == 0x00a0) {
                boundary()
                return
            }
            val extra = Character.charCount(codePoint) + if (pendingSpace && value.isNotEmpty()) 1 else 0
            if (value.length + extra > maxChars) {
                overflowed = true
                return
            }
            if (pendingSpace && value.isNotEmpty()) value.append(' ')
            pendingSpace = false
            value.appendCodePoint(codePoint)
        }

        fun finish(): String = value.toString()

        private fun decodeEntityAt(source: String, index: Int, end: Int): Pair<Int, Int>? {
            if (source[index] != '&') return null
            val entityEndLimit = minOf(end, index + MAX_ENTITY_CHARS)
            var semicolon = index + 1
            while (semicolon < entityEndLimit && source[semicolon] != ';' &&
                !source[semicolon].isWhitespace() && source[semicolon] != '<'
            ) {
                semicolon++
            }
            if (semicolon <= index + 1 || semicolon >= entityEndLimit || source[semicolon] != ';') return null
            val entity = source.substring(index + 1, semicolon)
            val codePoint = when (entity) {
                "amp" -> '&'.code
                "quot" -> '"'.code
                "apos" -> '\''.code
                "lt" -> '<'.code
                "gt" -> '>'.code
                "nbsp" -> 0x00a0
                else -> {
                    if (!entity.startsWith('#')) return null
                    val number = entity.substring(if (entity.startsWith("#x", true)) 2 else 1)
                    if (number.isEmpty() || number.length > 8) return null
                    runCatching {
                        if (entity.startsWith("#x", true)) number.toInt(16) else number.toInt(10)
                    }.getOrNull()?.takeIf(Character::isValidCodePoint) ?: return null
                }
            }
            return codePoint to (semicolon - index + 1)
        }
    }

    private const val MAX_TAG_NAME_CHARS = 32
    private const val MAX_ENTITY_CHARS = 40
}
