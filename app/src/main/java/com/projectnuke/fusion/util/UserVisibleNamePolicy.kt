package com.projectnuke.fusion.util

internal fun normalizeUserVisibleName(
    raw: String?,
    fallback: String,
    maxCodePoints: Int,
): String {
    require(maxCodePoints > 0)
    val safe = raw.orEmpty()
        .filterNot { character ->
            character.code < 0x20 ||
                character.code in 0x7F..0x9F ||
                character.code in 0x202A..0x202E ||
                character.code in 0x2066..0x2069 ||
                character == '\u200E' ||
                character == '\u200F'
        }
        .replace(Regex("""\s+"""), " ")
        .trim()
    val selected = safe.ifBlank { fallback }
    val codePoints = selected.codePoints().limit(maxCodePoints.toLong()).toArray()
    return String(codePoints, 0, codePoints.size).ifBlank { fallback }
}
