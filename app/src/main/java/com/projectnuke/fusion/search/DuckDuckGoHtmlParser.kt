package com.projectnuke.fusion.search

import com.projectnuke.fusion.util.SafeWebUrlPolicy

/** Linear, bounded scanner for the small subset used by DDG HTML and Lite pages. */
internal object DuckDuckGoHtmlParser {
    const val MAX_RETAINED_RESULTS = 8
    const val MAX_FRAGMENT_CHARS = 16_384
    private const val MAX_TAG_CHARS = 4096
    private const val CHECK_INTERVAL = 4096

    fun parseHtml(
        html: String,
        query: String,
        cleanUrl: (String) -> String,
        cancellationCheck: () -> Unit = {},
    ): List<FusionSearchResult> = parse(html, query, cleanUrl, lite = false, cancellationCheck)

    fun parseLite(
        html: String,
        query: String,
        cleanUrl: (String) -> String,
        cancellationCheck: () -> Unit = {},
    ): List<FusionSearchResult> = parse(html, query, cleanUrl, lite = true, cancellationCheck)

    private fun parse(
        html: String,
        query: String,
        cleanUrl: (String) -> String,
        lite: Boolean,
        cancellationCheck: () -> Unit,
    ): List<FusionSearchResult> {
        val results = ArrayList<FusionSearchResult>(MAX_RETAINED_RESULTS)
        val seen = HashSet<String>(MAX_RETAINED_RESULTS)
        val scan = TagScanner(html, cancellationCheck)
        while (results.size < MAX_RETAINED_RESULTS) {
            val tag = scan.next() ?: break
            if (tag.closing) continue
            if (lite && tag.name == "tr") {
                val row = scanLiteRow(html, tag, scan, query, cleanUrl, cancellationCheck) ?: continue
                val key = (row.url ?: row.title).lowercase()
                if (seen.add(key)) results += row
            } else if (!lite && tag.name == "div" && tag.hasClass("result") && tag.hasClassPrefix("results_links")) {
                val block = scanHtmlBlock(html, tag, scan, query, cleanUrl, cancellationCheck) ?: break
                block.result?.let { row ->
                    val key = row.title.lowercase().replace(Regex("\\s+"), " ").trim()
                    if (seen.add(key)) results += row
                }
            }
        }
        cancellationCheck()
        return results
    }

    private data class ParsedBlock(val result: FusionSearchResult?)

    private fun scanHtmlBlock(
        html: String,
        open: Tag,
        scanner: TagScanner,
        query: String,
        cleanUrl: (String) -> String,
        cancellationCheck: () -> Unit,
    ): ParsedBlock? {
        var divDepth = 1
        var titleStart = -1
        var titleDepth = 0
        var title: String? = null
        var url: String? = null
        var snippetStart = -1
        var snippetTag = ""
        var snippetDepth = 0
        var snippet: String? = null

        while (true) {
            val tag = scanner.next() ?: return null
            if (tag.start >= html.length) return null

            if (titleDepth > 0 && tag.name == "a") {
                if (tag.closing) {
                    titleDepth--
                    if (titleDepth == 0) {
                        title = normalizeFragment(html, titleStart, tag.start, cancellationCheck)
                    }
                } else if (!tag.selfClosing) titleDepth++
            } else if (titleDepth == 0 && title == null && !tag.closing && tag.name == "a" && tag.hasClass("result__a")) {
                val href = tag.attribute("href")
                if (!href.isNullOrBlank()) {
                    url = SafeWebUrlPolicy.normalize(cleanUrl(href))
                    if (tag.selfClosing) title = ""
                    else {
                        titleStart = tag.endExclusive
                        titleDepth = 1
                    }
                }
            }

            if (snippetDepth > 0 && tag.name == snippetTag) {
                if (tag.closing) {
                    snippetDepth--
                    if (snippetDepth == 0) snippet = normalizeFragment(html, snippetStart, tag.start, cancellationCheck)
                } else if (!tag.selfClosing) snippetDepth++
            } else if (snippetDepth == 0 && snippet == null && !tag.closing &&
                (tag.name == "a" || tag.name == "div") && tag.hasClass("result__snippet")
            ) {
                snippetTag = tag.name
                if (tag.selfClosing) snippet = ""
                else {
                    snippetStart = tag.endExclusive
                    snippetDepth = 1
                }
            }

            if (tag.name == "div") {
                if (tag.closing) {
                    divDepth--
                    if (divDepth == 0) {
                        val cleanTitle = title?.takeIf(String::isNotBlank) ?: return ParsedBlock(null)
                        val target = url?.takeIf(String::isNotBlank) ?: return ParsedBlock(null)
                        return ParsedBlock(
                            FusionSearchResult(
                                title = cleanTitle,
                                source = hostFromUrl(target),
                                url = target,
                                snippet = snippet?.ifBlank { null },
                                publishedAt = null,
                                providerDisplayName = "DuckDuckGo HTML",
                                queryUsed = query,
                            ),
                        )
                    }
                } else if (!tag.selfClosing) divDepth++
            }
        }
    }

    private fun scanLiteRow(
        html: String,
        open: Tag,
        scanner: TagScanner,
        query: String,
        cleanUrl: (String) -> String,
        cancellationCheck: () -> Unit,
    ): FusionSearchResult? {
        var rowDepth = 1
        var titleStart = -1
        var titleDepth = 0
        var title: String? = null
        var url: String? = null
        while (true) {
            val tag = scanner.next() ?: return null
            if (titleDepth > 0 && tag.name == "a") {
                if (tag.closing) {
                    titleDepth--
                    if (titleDepth == 0) title = normalizeFragment(html, titleStart, tag.start, cancellationCheck)
                } else if (!tag.selfClosing) titleDepth++
            } else if (titleDepth == 0 && title == null && !tag.closing && tag.name == "a") {
                val href = tag.attribute("href")
                if (!href.isNullOrBlank()) {
                    url = SafeWebUrlPolicy.normalize(cleanUrl(href))
                    if (tag.hasClass("result-link") || tag.hasClass("result__a") || tag.hasClass("result__url") ||
                        (tag.attribute("rel")?.contains("nofollow", ignoreCase = true) == true)
                    ) {
                        if (tag.selfClosing) title = ""
                        else {
                            titleStart = tag.endExclusive
                            titleDepth = 1
                        }
                    }
                }
            }
            if (tag.name == "tr") {
                if (tag.closing) {
                    rowDepth--
                    if (rowDepth == 0) {
                        val cleanTitle = title?.takeIf(String::isNotBlank) ?: return null
                        val target = url?.takeIf(::isHttpUrl) ?: return null
                        val rowText = normalizeFragment(html, open.start, tag.endExclusive, cancellationCheck)
                        return FusionSearchResult(
                            title = cleanTitle,
                            source = hostFromUrl(target),
                            url = target,
                            snippet = rowText.removePrefix(cleanTitle).trim().ifBlank { null },
                            publishedAt = null,
                            providerDisplayName = "DuckDuckGo Lite",
                            queryUsed = query,
                        )
                    }
                } else if (!tag.selfClosing) rowDepth++
            }
        }
    }

    private fun normalizeFragment(
        html: String,
        start: Int,
        end: Int,
        cancellationCheck: () -> Unit,
    ): String {
        if (start < 0 || end <= start) return ""
        return normalizeSearchHtmlText(
            html.substring(start, minOf(end, start + MAX_FRAGMENT_CHARS)),
            cancellationCheck,
        )
    }

    private fun isHttpUrl(value: String): Boolean = SafeWebUrlPolicy.normalize(value) != null

    private fun hostFromUrl(value: String): String? = try {
        java.net.URL(value).host.removePrefix("www.").ifBlank { null }
    } catch (_: Exception) {
        null
    }

    private class TagScanner(
        private val html: String,
        private val cancellationCheck: () -> Unit,
    ) {
        private var cursor = 0
        private var work = 0

        fun next(): Tag? {
            while (cursor < html.length) {
                tick()
                val start = nextOpener()
                if (start < 0) {
                    cursor = html.length
                    return null
                }
                if (html.startsWith("<!--", start)) {
                    val commentEnd = findCommentEnd(start + 4)
                    if (commentEnd < 0) {
                        cursor = html.length
                        return null
                    }
                    cursor = commentEnd + 3
                    continue
                }
                val parsed = parseTag(start)
                if (parsed != null) return parsed
                if (cursor <= start) cursor = start + 1
            }
            cancellationCheck()
            return null
        }

        private fun nextOpener(): Int {
            while (cursor < html.length) {
                tick()
                if (html[cursor] == '<') return cursor
                cursor++
            }
            return -1
        }

        private fun findCommentEnd(from: Int): Int {
            var index = from
            while (index + 2 < html.length) {
                tick()
                if (html[index] == '-' && html[index + 1] == '-' && html[index + 2] == '>') return index
                index++
            }
            return -1
        }

        private fun parseTag(start: Int): Tag? {
            var nameStart = start + 1
            var closing = false
            if (nameStart < html.length && html[nameStart] == '/') {
                closing = true
                nameStart++
            }
            if (nameStart >= html.length || !isAsciiLetter(html[nameStart])) {
                cursor = start + 1
                return null
            }
            var nameEnd = nameStart + 1
            val scanLimit = minOf(html.length, start + MAX_TAG_CHARS)
            while (nameEnd < scanLimit && isNameChar(html[nameEnd])) {
                tick()
                nameEnd++
            }
            if (nameEnd == scanLimit && nameEnd < html.length && isNameChar(html[nameEnd])) {
                cursor = scanLimit
                return null
            }
            val rawName = html.substring(nameStart, nameEnd)
            var quote: Char? = null
            var end = nameEnd
            while (end < scanLimit) {
                tick()
                val char = html[end]
                if (quote != null) {
                    if (char == quote) quote = null
                } else when (char) {
                    '\'', '"' -> quote = char
                    '<' -> {
                        cursor = start + 1
                        return null
                    }
                    '>' -> {
                        var beforeEnd = end - 1
                        while (beforeEnd >= nameEnd && html[beforeEnd].isWhitespace()) beforeEnd--
                        cursor = end + 1
                        return Tag(
                            html = html,
                            start = start,
                            endExclusive = end + 1,
                            name = rawName.lowercase(),
                            closing = closing,
                            selfClosing = beforeEnd >= nameEnd && html[beforeEnd] == '/',
                            nameEnd = nameEnd,
                        )
                    }
                }
                end++
            }
            // Oversized or unterminated tokens are skipped in bounded chunks.
            // The next call resumes after the scanned prefix, avoiding suffix rescans.
            cursor = maxOf(start + 1, scanLimit)
            return null
        }

        private fun tick() {
            if (++work >= CHECK_INTERVAL) {
                cancellationCheck()
                work = 0
            }
        }
    }

    private data class Tag(
        val html: String,
        val start: Int,
        val endExclusive: Int,
        val name: String,
        val closing: Boolean,
        val selfClosing: Boolean,
        val nameEnd: Int,
    ) {
        fun hasClass(token: String): Boolean = attribute("class")
            ?.split(Regex("\\s+"))
            ?.any { it.equals(token, ignoreCase = true) } == true

        fun hasClassPrefix(token: String): Boolean = attribute("class")
            ?.split(Regex("\\s+"))
            ?.any { it.startsWith(token, ignoreCase = true) } == true

        fun attribute(name: String): String? {
            var index = nameEnd
            val end = endExclusive - 1
            while (index < end) {
                while (index < end && (html[index].isWhitespace() || html[index] == '/')) index++
                if (index >= end) return null
                val attrStart = index
                while (index < end && !html[index].isWhitespace() && html[index] !in "=/>") index++
                if (index == attrStart) {
                    index++
                    continue
                }
                val attrName = html.substring(attrStart, index)
                while (index < end && html[index].isWhitespace()) index++
                if (index >= end || html[index] != '=') continue
                index++
                while (index < end && html[index].isWhitespace()) index++
                if (index >= end) return null
                val quote = html[index].takeIf { it == '\'' || it == '"' }
                if (quote != null) index++
                val valueStart = index
                if (quote != null) {
                    while (index < end && html[index] != quote) index++
                } else {
                    while (index < end && !html[index].isWhitespace()) index++
                }
                val valueEnd = index
                if (quote != null && index < end) index++
                if (attrName.equals(name, ignoreCase = true)) {
                    if (valueEnd - valueStart > 4096) return null
                    return html.substring(valueStart, valueEnd)
                }
            }
            return null
        }
    }

    private fun isAsciiLetter(char: Char): Boolean = char in 'A'..'Z' || char in 'a'..'z'
    private fun isNameChar(char: Char): Boolean = isAsciiLetter(char) || char in '0'..'9' || char == '-' || char == ':'
}
