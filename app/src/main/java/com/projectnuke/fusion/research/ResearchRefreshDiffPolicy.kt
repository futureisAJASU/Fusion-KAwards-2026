package com.projectnuke.fusion.research

/** Deterministic visible-text comparison. Output describes candidates, never truth. */
object ResearchRefreshDiffPolicy {
    const val MAX_DIFF_INPUT_CHARS_PER_SIDE = 12_000
    const val MAX_DIFF_ITEMS_PER_SECTION = 8
    private const val MAX_SEGMENTS = 160
    private const val MAX_ITEM_CHARS = 500
    private const val TEXT_MATCH_THRESHOLD = 0.68f
    private const val NUMERIC_MATCH_THRESHOLD = 0.38f
    private val numberPattern = Regex("(?i)(?:\\b\\d+(?:[.,]\\d+)?\\s?(?:%|gb|mb|tb|kg|km|cm|mm|년|월|일|원|달러|usd|krw|회|명|개|버전)\\b|\\b(?:19|20)\\d{2}(?:[-./]\\d{1,2}){0,2}\\b|\\b\\d+(?:\\.\\d+){1,3}\\b)")

    data class NumericChange(val previous: String, val current: String)
    data class SourceReference(val identity: String, val title: String)
    data class SourceChanges(
        val added: List<String>,
        val notSeenThisTime: List<String>,
        val continued: List<String>,
    )
    data class Result(
        val newlyMentioned: List<String>,
        val notFoundPrevious: List<String>,
        val numericCandidates: List<NumericChange>,
        val sources: SourceChanges,
    ) {
        val hasChanges: Boolean get() = newlyMentioned.isNotEmpty() || notFoundPrevious.isNotEmpty() ||
            numericCandidates.isNotEmpty() || sources.added.isNotEmpty() || sources.notSeenThisTime.isNotEmpty()
    }

    data class OwnedMessage(val id: Long, val role: String, val createdAt: Long, val active: Boolean, val research: Boolean)

    fun hasExactRefreshRelationship(
        sourceConversationId: Long,
        sourceMessageId: Long,
        refreshedConversationId: Long,
        relationshipSourceConversationId: Long?,
        relationshipSourceMessageId: Long?,
        branchSourceConversationId: Long?,
        branchSourceMessageId: Long?,
    ): Boolean = sourceConversationId > 0 && sourceMessageId > 0 && refreshedConversationId > 0 &&
        relationshipSourceConversationId == sourceConversationId && relationshipSourceMessageId == sourceMessageId &&
        branchSourceConversationId == sourceConversationId && branchSourceMessageId == sourceMessageId

    /** Refresh branch ownership is the first post-creation user turn and its active Research answer. */
    fun resolveRefreshedAssistant(messages: List<OwnedMessage>, conversationCreatedAt: Long): Long? {
        if (conversationCreatedAt < 0L) return null
        val ordered = messages.sortedWith(compareBy<OwnedMessage> { it.createdAt }.thenBy { it.id })
        val requestIndex = ordered.indexOfFirst { it.role == "user" && it.createdAt > conversationCreatedAt }
        if (requestIndex < 0) return null
        val answer = ordered.drop(requestIndex + 1).firstOrNull { it.role == "assistant" && it.active }
            ?: return null
        return answer.id.takeIf { answer.research && it > 0L }
    }

    fun compare(previousVisible: String, currentVisible: String, previousSources: List<SourceReference>, currentSources: List<SourceReference>): Result {
        val old = segments(previousVisible.take(MAX_DIFF_INPUT_CHARS_PER_SIDE))
        val new = segments(currentVisible.take(MAX_DIFF_INPUT_CHARS_PER_SIDE))
        val newItems = ArrayList<String>()
        val oldItems = ArrayList<String>()
        val numeric = ArrayList<NumericChange>()
        val consumedOld = mutableSetOf<Int>()
        val consumedNew = mutableSetOf<Int>()
        for (newIndex in new.indices) {
            val best = old.indices.maxByOrNull { overlap(new[newIndex], old[it]) } ?: continue
            val similarity = overlap(new[newIndex], old[best])
            val oldNumbers = numbers(old[best])
            val newNumbers = numbers(new[newIndex])
            if (similarity >= NUMERIC_MATCH_THRESHOLD && oldNumbers.isNotEmpty() && newNumbers.isNotEmpty() && oldNumbers != newNumbers) {
                numeric += NumericChange(old[best].take(MAX_ITEM_CHARS), new[newIndex].take(MAX_ITEM_CHARS))
                consumedOld += best
                consumedNew += newIndex
            } else if (similarity >= TEXT_MATCH_THRESHOLD) {
                consumedOld += best
                consumedNew += newIndex
            }
        }
        new.indices.filter { it !in consumedNew }.forEach { index ->
            if (newItems.size < MAX_DIFF_ITEMS_PER_SECTION) newItems += new[index].take(MAX_ITEM_CHARS)
        }
        old.indices.filter { it !in consumedOld }.forEach { index ->
            val appearsNow = new.any { overlap(old[index], it) >= TEXT_MATCH_THRESHOLD }
            if (!appearsNow && oldItems.size < MAX_DIFF_ITEMS_PER_SECTION) oldItems += old[index].take(MAX_ITEM_CHARS)
        }
        return Result(
            newlyMentioned = newItems,
            notFoundPrevious = oldItems,
            numericCandidates = numeric.distinctBy { it.previous to it.current }.take(MAX_DIFF_ITEMS_PER_SECTION),
            sources = compareSources(previousSources, currentSources),
        )
    }

    private fun segments(text: String): List<String> = text.lines()
        .flatMap { line ->
            val cleaned = line.trim().replace(Regex("^\\s*(?:[-*+•]|\\d+[.)])\\s+"), "")
                .replace(Regex("^#{1,6}\\s+"), "").replace(Regex("[`*_~]"), "").trim()
            if (cleaned.isBlank()) emptyList() else cleaned.split(Regex("(?<=[.!?。！？])\\s+"))
        }
        .asSequence().map(::normalize).filter { it.isNotBlank() }.take(MAX_SEGMENTS).toList()

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()
    private fun tokens(value: String): Set<String> = normalize(value).lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }.toSet()
    private fun overlap(left: String, right: String): Float {
        val a = tokens(left); val b = tokens(right)
        if (a.isEmpty() || b.isEmpty()) return 0f
        return a.intersect(b).size.toFloat() / a.union(b).size
    }
    private fun numbers(value: String): List<String> = numberPattern.findAll(value.lowercase())
        .map { it.value.replace(Regex("\\s+"), "") }.toList()

    private fun compareSources(old: List<SourceReference>, new: List<SourceReference>): SourceChanges {
        val oldById = old.take(24).associateBy { canonicalIdentity(it.identity) }
        val newById = new.take(24).associateBy { canonicalIdentity(it.identity) }
        return SourceChanges(
            added = (newById.keys - oldById.keys).filter(String::isNotBlank).take(MAX_DIFF_ITEMS_PER_SECTION)
                .mapNotNull { newById[it]?.title?.take(MAX_ITEM_CHARS) },
            notSeenThisTime = (oldById.keys - newById.keys).filter(String::isNotBlank).take(MAX_DIFF_ITEMS_PER_SECTION)
                .mapNotNull { oldById[it]?.title?.take(MAX_ITEM_CHARS) },
            continued = (oldById.keys intersect newById.keys).filter(String::isNotBlank).take(MAX_DIFF_ITEMS_PER_SECTION)
                .mapNotNull { newById[it]?.title?.take(MAX_ITEM_CHARS) },
        )
    }

    private fun canonicalIdentity(value: String): String = value.trim().lowercase().removeSuffix("/")
}
