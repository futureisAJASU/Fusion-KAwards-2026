package com.projectnuke.fusion.knowledge

import java.text.Normalizer
import java.util.Locale

data class NormalizedLabel(val key: String, val displayName: String)

object KnowledgeOrganizationPolicy {
    const val MAX_TAGS_PER_ITEM = 12
    const val MAX_TAG_CHARS = 32
    const val MAX_TAGS = 256
    const val MAX_COLLECTION_CHARS = 48
    const val MAX_COLLECTIONS = 100
    const val MAX_COLLECTION_ITEMS = 200

    fun normalizeTag(raw: String): NormalizedLabel? = normalize(raw, MAX_TAG_CHARS)
    fun normalizeCollection(raw: String): NormalizedLabel? = normalize(raw, MAX_COLLECTION_CHARS)

    fun normalizeTagSet(raw: List<String>): List<NormalizedLabel>? {
        if (raw.size > MAX_TAGS_PER_ITEM) return null
        val labels = raw.map { normalizeTag(it) ?: return null }
        return labels.distinctBy(NormalizedLabel::key)
    }

    fun normalizeCollectionSet(raw: List<String>): List<NormalizedLabel>? {
        if (raw.size > MAX_COLLECTIONS) return null
        val labels = raw.map { normalizeCollection(it) ?: return null }
        return labels.distinctBy(NormalizedLabel::key)
    }

    private fun normalize(raw: String, maxChars: Int): NormalizedLabel? {
        val display = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim().replace(Regex("\\s+"), " ")
        if (display.isEmpty() || display.length > maxChars || display.any(Char::isISOControl)) return null
        return NormalizedLabel(display.lowercase(Locale.ROOT), display)
    }
}
