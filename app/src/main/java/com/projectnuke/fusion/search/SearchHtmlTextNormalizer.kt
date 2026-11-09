package com.projectnuke.fusion.search

/**
 * Bounded, non-rendering normalization for untrusted provider HTML fragments.
 * Each input character is consumed as text or by one bounded tag/entity probe;
 * no malformed opener searches an unbounded suffix.
 */
internal fun normalizeSearchHtmlText(
    raw: String,
    cancellationCheck: () -> Unit = {},
): String {
    val limit = minOf(raw.length, SearchHtmlTextBounds.MAX_INPUT_CHARS)
    val out = StringBuilder(minOf(limit, 4096))
    var pendingSpace = false
    var index = 0
    var work = 0

    fun tick(amount: Int = 1) {
        work += amount
        if (work >= SearchHtmlTextBounds.CHECK_INTERVAL) {
            cancellationCheck()
            work = 0
        }
    }

    fun appendCodePoint(codePoint: Int) {
        if (Character.isWhitespace(codePoint) || codePoint == 0x00a0) {
            pendingSpace = out.isNotEmpty()
            return
        }
        if (pendingSpace && out.isNotEmpty()) out.append(' ')
        pendingSpace = false
        out.appendCodePoint(codePoint)
    }

    while (index < limit) {
        tick()
        if (raw[index] == '<' && startsTag(raw, index, limit)) {
            when (val scan = scanTagEnd(raw, index, limit, ::tick)) {
                is TagScan.Closed -> {
                    pendingSpace = out.isNotEmpty()
                    index = scan.endExclusive
                    continue
                }
                is TagScan.NestedOpener -> {
                    appendCodePoint('<'.code)
                    index++
                    continue
                }
                is TagScan.BoundedOut -> {
                    // Preserve malformed/oversized markup as ordinary text,
                    // but consume the inspected range so nested openers are
                    // not repeatedly rescanned.
                    var cursor = index
                    while (cursor < scan.endExclusive) {
                        tick()
                        val entity = decodeEntityAt(raw, cursor, scan.endExclusive)
                        if (entity != null) {
                            appendCodePoint(entity.codePoint)
                            cursor += entity.charCount
                        } else {
                            val cp = Character.codePointAt(raw, cursor)
                            appendCodePoint(cp)
                            cursor += Character.charCount(cp)
                        }
                    }
                    index = scan.endExclusive
                    continue
                }
            }
        }

        val entity = if (raw[index] == '&') decodeEntityAt(raw, index, limit) else null
        if (entity != null) {
            appendCodePoint(entity.codePoint)
            index += entity.charCount
            continue
        }
        val codePoint = Character.codePointAt(raw, index)
        appendCodePoint(codePoint)
        index += Character.charCount(codePoint)
    }
    cancellationCheck()
    return out.toString()
}

internal object SearchHtmlTextBounds {
    /** Matches the bounded HTTP response ceiling; direct callers are bounded too. */
    const val MAX_INPUT_CHARS = 2 * 1024 * 1024
    const val MAX_TAG_SCAN_CHARS = 512
    const val MAX_ENTITY_SCAN_CHARS = 16
    const val CHECK_INTERVAL = 4096
}

private sealed interface TagScan {
    data class Closed(val endExclusive: Int) : TagScan
    data object NestedOpener : TagScan
    data class BoundedOut(val endExclusive: Int) : TagScan
}

private data class DecodedEntity(val codePoint: Int, val charCount: Int)

private fun startsTag(raw: String, start: Int, limit: Int): Boolean {
    var cursor = start + 1
    if (cursor < limit && raw[cursor] == '/') cursor++
    return cursor < limit && raw[cursor] in 'A'..'Z' ||
        cursor < limit && raw[cursor] in 'a'..'z'
}

private fun scanTagEnd(
    raw: String,
    start: Int,
    limit: Int,
    tick: (Int) -> Unit,
): TagScan {
    var quote: Char? = null
    var cursor = start + 2
    val maxEnd = minOf(limit, start + SearchHtmlTextBounds.MAX_TAG_SCAN_CHARS)
    while (cursor < maxEnd) {
        tick(1)
        val char = raw[cursor]
        if (quote != null) {
            if (char == quote) quote = null
        } else when (char) {
            '\'', '"' -> quote = char
            '<' -> return TagScan.NestedOpener
            '>' -> return TagScan.Closed(cursor + 1)
        }
        cursor++
    }
    return TagScan.BoundedOut(maxEnd)
}

private fun decodeEntityAt(raw: String, start: Int, limit: Int): DecodedEntity? {
    if (start >= limit || raw[start] != '&') return null
    val maxEnd = minOf(limit, start + SearchHtmlTextBounds.MAX_ENTITY_SCAN_CHARS + 1)
    var end = start + 1
    while (end < maxEnd) {
        val char = raw[end]
        if (char == ';') break
        if (!(char == '#' || char in '0'..'9' || char in 'A'..'Z' || char in 'a'..'z')) return null
        end++
    }
    if (end >= maxEnd || end >= limit || raw[end] != ';' || end == start + 1) return null
    val entityStart = start + 1
    val entityLength = end - entityStart
    val named = when {
        entityLength == 3 && raw.regionMatches(entityStart, "amp", 0, 3) -> '&'.code
        entityLength == 4 && raw.regionMatches(entityStart, "quot", 0, 4) -> '"'.code
        entityLength == 4 && raw.regionMatches(entityStart, "apos", 0, 4) -> '\''.code
        entityLength == 2 && raw.regionMatches(entityStart, "lt", 0, 2) -> '<'.code
        entityLength == 2 && raw.regionMatches(entityStart, "gt", 0, 2) -> '>'.code
        entityLength == 4 && raw.regionMatches(entityStart, "nbsp", 0, 4) -> 0x00a0
        else -> null
    }
    if (named != null) return DecodedEntity(named, end - start + 1)

    if (raw[entityStart] != '#') return null
    var numberStart = entityStart + 1
    val radix = if (numberStart < end && (raw[numberStart] == 'x' || raw[numberStart] == 'X')) {
        numberStart++
        16
    } else 10
    if (numberStart >= end) return null
    var value = 0
    while (numberStart < end) {
        val char = raw[numberStart]
        val digit = when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> return null
        }
        if (digit >= radix || value > (Character.MAX_CODE_POINT - digit) / radix) return null
        value = value * radix + digit
        numberStart++
    }
    if (!Character.isValidCodePoint(value)) return null
    return DecodedEntity(value, end - start + 1)
}
