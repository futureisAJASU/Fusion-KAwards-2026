package com.projectnuke.fusion.search

import java.text.Normalizer
import java.util.Locale

/** Canonical identity for duplicate-search budgets; authorization keeps the exact query. */
internal fun normalizeSearchQueryIdentity(query: String): String {
    val compatible = Normalizer.normalize(query, Normalizer.Form.NFKC)
    val withoutDirectionControls = compatible.filterNot { it in BidiControls }
    return withoutDirectionControls
        .replace(ZeroWidthSeparators, " ")
        .lowercase(Locale.ROOT)
        .replace(Whitespace, " ")
        .trim()
}

private val Whitespace = Regex("\\s+")
private val ZeroWidthSeparators = Regex("[\\u200B\\uFEFF]+")
private val BidiControls = setOf(
    '\u200E', '\u200F',
    '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
    '\u2066', '\u2067', '\u2068', '\u2069',
)
