package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.ChatDao
import com.projectnuke.fusion.data.ConversationEntity
import com.projectnuke.fusion.data.MessageContentHit
import com.projectnuke.fusion.data.escapeSqlLikeQuery
import com.projectnuke.fusion.util.visibleMessageContent

internal const val ConversationSearchDebounceMs = 300L
internal const val ConversationSearchResultLimit = 100

internal enum class ConversationSearchDomain {
    ACTIVE,
    ARCHIVE,
}

internal data class ConversationRowArchiveAction(
    val restores: Boolean,
    val menuLabel: String,
)

internal fun conversationRowArchiveAction(
    domain: ConversationSearchDomain,
): ConversationRowArchiveAction = when (domain) {
    ConversationSearchDomain.ACTIVE -> ConversationRowArchiveAction(
        restores = false,
        menuLabel = "아카이브에 보관",
    )
    ConversationSearchDomain.ARCHIVE -> ConversationRowArchiveAction(
        restores = true,
        menuLabel = "복원",
    )
}

internal fun filterConversationsForSearchDomain(
    domain: ConversationSearchDomain,
    activeConversations: List<ConversationEntity>,
    archivedConversations: List<ConversationEntity>,
    query: String,
    matchingMessageConversationIds: Collection<Long>,
): List<ConversationEntity> {
    val base = when (domain) {
        ConversationSearchDomain.ACTIVE -> activeConversations
        ConversationSearchDomain.ARCHIVE -> archivedConversations
    }
    if (query.isBlank()) return base
    val matchingIds = matchingMessageConversationIds.toSet()
    return base.filter { conversation ->
        conversation.title.contains(query, ignoreCase = true) || conversation.id in matchingIds
    }
}

internal fun boundedConversationSearchQuery(raw: String): String? =
    raw.trim().take(100).takeIf { it.length >= 2 }

/**
 * Bounded overfetch for visible-content search. Hidden envelopes/metadata can
 * match the coarse SQL selector, so each page fetches more rows than the
 * visible result limit; pagination continues while a full page was returned
 * and verified hits are still below [limit]. Work is bounded by the
 * documented global raw-candidate cap [ConversationSearchCoarseMaxRawCandidates]
 * (never unbounded), so pathological hidden-metadata corpora degrade
 * gracefully (fewer results) instead of scanning the whole table.
 *
 * SEARCH_BOUNDED_RECALL = KNOWN_LIMITATION: recall below the raw-candidate
 * cap is bounded-but-much-wider than the previous 2-page policy; matches past
 * the cap can still be missed and this is not a COMPLETE-recall guarantee.
 */
internal const val ConversationSearchCoarseOverfetchFactor = 2
internal const val ConversationSearchCoarseMaxRawCandidates = 1_000

/**
 * Pure post-filter: keeps conversation IDs having at least one message whose
 * canonical visible content contains [query]. Hidden-envelope and hidden-
 * metadata matches never survive.
 */
internal fun filterVisibleMessageHits(
    hits: List<MessageContentHit>,
    query: String,
): Set<Long> {
    if (query.isBlank()) return emptySet()
    return buildSet {
        hits.forEach { hit ->
            if (visibleMessageContent(hit.content).contains(query, ignoreCase = true)) {
                add(hit.conversationId)
            }
        }
    }
}

/**
 * Visible-content conversation search. The raw SQL LIKE is only a coarse
 * candidate selector (visible body is still present in raw content); every
 * candidate row is verified in Kotlin against the canonical visible-content
 * contract before it can surface.
 *
 * Known coarse-selector limitation (no schema migration): a multi-word query
 * whose words are contiguous in visible text but split by a hidden block in
 * raw storage cannot match LIKE, so in-chat search may find phrases that
 * conversation-list search misses. Visibility semantics (what is hidden)
 * agree on both surfaces; only this exotic recall edge can differ. Recall is
 * additionally bounded by the documented raw-candidate cap above (see
 * SEARCH_BOUNDED_RECALL = KNOWN_LIMITATION).
 */
internal suspend fun searchVisibleConversationIds(
    dao: ChatDao,
    rawQuery: String,
    limit: Int = ConversationSearchResultLimit,
    allowedConversationIds: Set<Long>? = null,
): List<Long> {
    val query = rawQuery.trim()
    if (query.length < 2 || limit <= 0) return emptyList()
    val escaped = escapeSqlLikeQuery(query)
    val pageSize = (limit * ConversationSearchCoarseOverfetchFactor).coerceAtLeast(limit + 1)
    val found = LinkedHashSet<Long>()
    var offset = 0
    var rawCandidates = 0
    // Stop only on raw exhaustion, the visible-result limit, or the
    // documented global raw-candidate cap. Never unbounded.
    while (rawCandidates < ConversationSearchCoarseMaxRawCandidates) {
        val hits = dao.getMessageContentHitsLike(escaped, pageSize, offset)
        if (hits.isEmpty()) return found.toList()
        rawCandidates += hits.size
        hits.forEach { hit ->
            if (found.size >= limit) return@forEach
            if ((allowedConversationIds == null || hit.conversationId in allowedConversationIds) &&
                hit.conversationId !in found &&
                visibleMessageContent(hit.content).contains(query, ignoreCase = true)
            ) {
                found += hit.conversationId
            }
        }
        if (found.size >= limit) return found.toList()
        if (hits.size < pageSize) return found.toList()
        offset += pageSize
    }
    return found.toList()
}
