package com.projectnuke.fusion.search

import com.projectnuke.fusion.llm.runNonFatal
import java.net.URI
import java.util.Locale

/** Stable identity shared by retained search results and their provenance. */
internal data class SearchEvidenceIdentity(
    val normalizedUrl: String?,
    val title: String,
    val source: String?,
    val snippet: String?,
    val publishedAt: String?,
    val providerType: WebSearchProviderType,
    val providerDisplayName: String,
    val searchCategory: String?,
    val queryUsed: String,
)

internal fun FusionSearchResult.evidenceIdentity(): SearchEvidenceIdentity = SearchEvidenceIdentity(
    normalizedUrl = normalizeEvidenceUrl(url),
    title = title,
    source = source,
    snippet = snippet,
    publishedAt = publishedAt,
    providerType = providerType,
    providerDisplayName = providerDisplayName,
    searchCategory = searchCategory,
    queryUsed = queryUsed,
)

internal fun WebSearchSource.evidenceIdentity(): SearchEvidenceIdentity = SearchEvidenceIdentity(
    normalizedUrl = normalizeEvidenceUrl(url),
    title = title,
    source = source,
    snippet = snippet,
    publishedAt = publishedAt,
    providerType = providerType,
    providerDisplayName = providerDisplayName,
    searchCategory = searchCategory,
    queryUsed = queryUsed,
)

/** Stable duplicate key for bounded model admission; identical URLs must not consume separate slots. */
internal fun FusionSearchResult.sourceIdentity(): String =
    normalizeEvidenceUrl(url) ?: "title:${title.trim().lowercase(Locale.ROOT)}"

private val trackingQueryKeys = setOf(
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "utm_name",
    "utm_reader", "utm_social", "utm_brand", "gclid", "dclid", "fbclid", "msclkid", "yclid",
    "igshid", "mc_cid", "mc_eid", "ref", "ref_src", "ref_url", "referrer", "source", "spm",
    "cmpid", "cmp", "ncid", "vero_id", "oly_enc_id", "oly_anon_id", "trk", "scid", "s_kwcid",
    "_hsenc", "_hsmi", "hsctatracking", "at_medium", "at_campaign", "sr_share", "xtor",
)

private fun canonicalEvidenceQuery(rawQuery: String?): String? {
    if (rawQuery.isNullOrBlank()) return null
    val kept = rawQuery.split('&')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { parameter ->
            val key = parameter.substringBefore('=').trim().lowercase(Locale.ROOT)
            key in trackingQueryKeys
        }
        .sorted()
    return kept.takeIf { it.isNotEmpty() }?.joinToString("&")
}

/**
 * URL-less records remain identifiable by their other evidence fields. A
 * malformed URL stays a distinct raw value, and only URI parsing failures are
 * treated as recoverable; fatal errors and cancellation still propagate.
 */
private fun normalizeEvidenceUrl(value: String?): String? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val normalized = runNonFatal {
        val uri = URI(raw).normalize()
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (scheme == null || host == null) return@runNonFatal raw
        val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
        val canonicalHost = host.removePrefix("www.")
        "$canonicalHost$port${uri.rawPath.orEmpty().trimEnd('/')}" +
            (canonicalEvidenceQuery(uri.rawQuery)?.let { "?$it" }.orEmpty())
    }
    return normalized ?: raw
}
