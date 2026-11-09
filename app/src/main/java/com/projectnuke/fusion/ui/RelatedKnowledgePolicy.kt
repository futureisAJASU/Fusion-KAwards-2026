package com.projectnuke.fusion.ui

internal const val RELATED_KNOWLEDGE_VISIBLE_LIMIT = 4
internal const val RELATED_KNOWLEDGE_QUERY_MAX_CHARS = 100

internal data class RelatedKnowledgeSeed(val title: String, val text: String)

/** Keep a compact source label as the semantic query; source bodies remain local to the retrieval owner. */
internal fun relatedKnowledgeQuery(seed: RelatedKnowledgeSeed): String =
    buildString {
        val title = seed.title.trim().replace(Regex("\\s+"), " ")
        val text = seed.text.trim().replace(Regex("\\s+"), " ")
        append(title.take(48))
        if (title.isNotBlank() && text.isNotBlank()) append(' ')
        append(text.take((RELATED_KNOWLEDGE_QUERY_MAX_CHARS - length).coerceAtLeast(0)))
    }.take(RELATED_KNOWLEDGE_QUERY_MAX_CHARS)

internal fun relatedKnowledgeFallbackQuery(seed: RelatedKnowledgeSeed): String =
    (seed.title.trim().ifBlank { seed.text.trim() })
        .replace(Regex("\\s+"), " ")
        .take(RELATED_KNOWLEDGE_QUERY_MAX_CHARS)

internal fun selectRelatedKnowledge(
    source: KnowledgeTarget,
    candidates: List<KnowledgeCandidate>,
    limit: Int = RELATED_KNOWLEDGE_VISIBLE_LIMIT,
): List<KnowledgeCandidate> {
    if (limit <= 0) return emptyList()
    return candidates.asSequence()
        .filter { it.target != source }
        .distinctBy { it.target }
        .take(limit.coerceAtMost(RELATED_KNOWLEDGE_VISIBLE_LIMIT))
        .toList()
}
