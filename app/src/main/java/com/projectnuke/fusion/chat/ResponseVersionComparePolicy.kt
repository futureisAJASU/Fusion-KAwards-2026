package com.projectnuke.fusion.chat

/**
 * Response Version Compare v1: transient presentation-only comparison of
 * the current answer against another durable response version.
 *
 * Pure, Android-free policy with no Room, no callbacks, no persistence and
 * no generation entry point: opening, selecting or closing a comparison
 * performs ZERO response-version writes by construction (the surface below
 * accepts data only). Normal previous/next controls remain the only active
 * response navigation.
 *
 * Entries carry the canonical visible answer mapped by the caller through
 * the same user-visible authority the bubble renders; blank candidates are
 * unusable and filtered here as well, so fewer than two usable answers
 * means unavailable. Ordinals are the stable 1-based version positions the
 * existing navigation labels use; [totalCount] is the full version count so
 * headers stay truthful even when blank versions are filtered out.
 */
internal data class ResponseVersionCompareEntry(
    val messageId: Long,
    val ordinal: Int,
    val visibleAnswer: String,
)

internal data class ResponseVersionCompareSnapshot(
    val entries: List<ResponseVersionCompareEntry>,
    val activeMessageId: Long,
    val comparisonMessageId: Long,
    val totalCount: Int,
)

internal object ResponseVersionComparePolicy {
    /**
     * Captures an immutable compare snapshot, or null when comparison is
     * unavailable: fewer than two usable entries, duplicate message IDs, or
     * an active message that is not an exact member. The default comparison
     * is the previous usable version, or the next usable version when the
     * active answer is the first one. Never claims quality: no best,
     * correct or recommended answer exists on this API.
     */
    fun snapshot(
        entries: List<ResponseVersionCompareEntry>,
        activeMessageId: Long,
        totalCount: Int = entries.size,
    ): ResponseVersionCompareSnapshot? {
        val usable = entries.filter { it.visibleAnswer.isNotBlank() }
        if (usable.size < 2) return null
        if (usable.map { it.messageId }.toSet().size != usable.size) return null
        val activeIndex = usable.indexOfFirst { it.messageId == activeMessageId }
        if (activeIndex < 0) return null
        val comparison = if (activeIndex > 0) {
            usable[activeIndex - 1]
        } else {
            usable[activeIndex + 1]
        }
        return ResponseVersionCompareSnapshot(
            entries = usable.toList(),
            activeMessageId = activeMessageId,
            comparisonMessageId = comparison.messageId,
            totalCount = totalCount,
        )
    }

    /**
     * Transiently selects another comparison entry within a frozen
     * snapshot. Returns null (caller keeps the current selection) when the
     * target is the active answer, is not a snapshot member, or the
     * snapshot itself is malformed. The input snapshot is never mutated.
     */
    fun select(
        snapshot: ResponseVersionCompareSnapshot,
        comparisonMessageId: Long,
    ): ResponseVersionCompareSnapshot? {
        if (snapshot.entries.size < 2) return null
        if (snapshot.entries.map { it.messageId }.toSet().size != snapshot.entries.size) return null
        if (snapshot.entries.none { it.messageId == snapshot.activeMessageId }) return null
        if (comparisonMessageId == snapshot.activeMessageId) return null
        if (snapshot.entries.none { it.messageId == comparisonMessageId }) return null
        if (comparisonMessageId == snapshot.comparisonMessageId) return snapshot
        return snapshot.copy(comparisonMessageId = comparisonMessageId)
    }

    /** Exact active entry; null only for a malformed snapshot (never rendered). */
    fun activeEntry(snapshot: ResponseVersionCompareSnapshot): ResponseVersionCompareEntry? =
        snapshot.entries.firstOrNull { it.messageId == snapshot.activeMessageId }

    /** Exact comparison entry; null only for a malformed snapshot (never rendered). */
    fun comparisonEntry(snapshot: ResponseVersionCompareSnapshot): ResponseVersionCompareEntry? =
        snapshot.entries.firstOrNull { it.messageId == snapshot.comparisonMessageId }
}
