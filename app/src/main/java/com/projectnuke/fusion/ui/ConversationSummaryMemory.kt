package com.projectnuke.fusion.ui

import android.content.Context
import android.content.SharedPreferences
import com.projectnuke.fusion.chat.MAX_BRIEF_SUMMARY_CANDIDATES
import com.projectnuke.fusion.chat.sanitizeFusionMemoryText
import com.projectnuke.fusion.util.SharedPreferencesDurability
import com.projectnuke.fusion.util.SharedPreferencesScopedLock
import com.projectnuke.fusion.util.restoreKeys
import com.projectnuke.fusion.util.snapshotKeys

data class ConversationSummaryMemory(
    val conversationId: Long,
    val summary: String,
    val updatedAt: Long
)

private const val ConversationSummaryPrefs = "fusion_conversation_summaries"
private const val MaxSummaryContextChars = 1500

fun loadConversationSummary(
    context: Context,
    conversationId: Long
): ConversationSummaryMemory? {
    if (conversationId <= 0L) return null
    val prefs = context.getSharedPreferences(ConversationSummaryPrefs, Context.MODE_PRIVATE)
    if (isSummaryUnknown(prefs, conversationId)) return null
    val summary = prefs.getString(summaryKey(conversationId), null)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return ConversationSummaryMemory(
        conversationId = conversationId,
        summary = summary,
        updatedAt = prefs.getLong(updatedAtKey(conversationId), 0L)
    )
}

sealed interface ConversationSummaryBatchLoadResult {
    data class Loaded(
        val summaries: List<ConversationSummaryMemory>,
        val durabilityUnknownConversationIds: Set<Long>,
    ) : ConversationSummaryBatchLoadResult
}

/**
 * Loads only the requested, caller-bounded conversation IDs from one
 * SharedPreferences handle. Missing and malformed entries are skipped; an
 * unknown durability marker remains typed so the UI can fail closed.
 */
fun loadConversationSummaries(
    context: Context,
    conversationIds: Collection<Long>,
): List<ConversationSummaryMemory> = loadConversationSummariesResult(context, conversationIds)
    .summaries

internal fun loadConversationSummariesResult(
    context: Context,
    conversationIds: Collection<Long>,
): ConversationSummaryBatchLoadResult.Loaded {
    val requestedIds = conversationIds.asSequence()
        .filter { it > 0L }
        .distinct()
        .take(MAX_BRIEF_SUMMARY_CANDIDATES)
        .toList()
    if (requestedIds.isEmpty()) {
        return ConversationSummaryBatchLoadResult.Loaded(emptyList(), emptySet())
    }

    val prefs = context.getSharedPreferences(ConversationSummaryPrefs, Context.MODE_PRIVATE)
    val summaries = buildList {
        requestedIds.forEach { conversationId ->
            if (isSummaryUnknown(prefs, conversationId)) return@forEach
            val memory = runCatching {
                val cleanSummary = prefs.getString(summaryKey(conversationId), null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                ConversationSummaryMemory(
                    conversationId = conversationId,
                    summary = cleanSummary,
                    updatedAt = prefs.getLong(updatedAtKey(conversationId), 0L),
                )
            }.getOrNull()
            if (memory != null) add(memory)
        }
    }
    val unknownIds = requestedIds
        .filter { isSummaryUnknown(prefs, it) }
        .toSet()
    return ConversationSummaryBatchLoadResult.Loaded(summaries, unknownIds)
}

fun saveConversationSummary(
    context: Context,
    conversationId: Long,
    summary: String
): ConversationSummaryMemory? {
    val cleanSummary = summary.trim()
    if (conversationId <= 0L || cleanSummary.isBlank()) return null
    val updatedAt = System.currentTimeMillis()
    val prefs = context.getSharedPreferences(ConversationSummaryPrefs, Context.MODE_PRIVATE)
    return SharedPreferencesScopedLock.withKeyLock(prefs, summaryKey(conversationId)) {
        if (isSummaryUnknown(prefs, conversationId)) return@withKeyLock null
        val snapshot = prefs.snapshotKeys(conversationOwnedKeys(conversationId))
        val committed = prefs.edit()
            .putString(summaryKey(conversationId), cleanSummary)
            .putLong(updatedAtKey(conversationId), updatedAt)
            .commit()
        if (!committed) {
            restoreSummarySnapshot(prefs, conversationId, snapshot)
            return@withKeyLock null
        }
        ConversationSummaryMemory(conversationId, cleanSummary, updatedAt)
    }
}

fun deleteConversationSummary(
    context: Context,
    conversationId: Long
): Boolean {
    if (conversationId <= 0L) return true
    val prefs = context.getSharedPreferences(ConversationSummaryPrefs, Context.MODE_PRIVATE)
    return SharedPreferencesScopedLock.withKeyLock(prefs, summaryKey(conversationId)) {
        if (isSummaryUnknown(prefs, conversationId)) return@withKeyLock false
        val snapshot = prefs.snapshotKeys(conversationOwnedKeys(conversationId))
        val committed = prefs.edit()
            .remove(summaryKey(conversationId))
            .remove(updatedAtKey(conversationId))
            .commit()
        if (!committed) restoreSummarySnapshot(prefs, conversationId, snapshot)
        committed
    }
}

fun loadAllConversationSummaries(
    context: Context
): List<ConversationSummaryMemory> {
    val prefs = context.getSharedPreferences(ConversationSummaryPrefs, Context.MODE_PRIVATE)
    return prefs.all.keys
        .asSequence()
        .filter { it.startsWith("summary_") && !it.startsWith("summary_updated_at_") }
        .mapNotNull { key ->
            val conversationId = key.removePrefix("summary_").toLongOrNull() ?: return@mapNotNull null
            if (isSummaryUnknown(prefs, conversationId)) return@mapNotNull null
            val summary = prefs.getString(key, null)?.trim().orEmpty()
            if (summary.isBlank()) return@mapNotNull null
            ConversationSummaryMemory(
                conversationId = conversationId,
                summary = summary,
                updatedAt = prefs.getLong(updatedAtKey(conversationId), 0L)
            )
        }
        .sortedByDescending { it.updatedAt }
        .toList()
}

fun buildConversationSummaryContextText(summary: ConversationSummaryMemory?): String? {
    val text = summary?.summary
        ?.let(::sanitizeFusionMemoryText)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return "[대화 요약]\n${text.take(MaxSummaryContextChars)}"
}

private fun summaryKey(conversationId: Long): String = "summary_$conversationId"

private fun updatedAtKey(conversationId: Long): String = "summary_updated_at_$conversationId"

private fun conversationOwnedKeys(conversationId: Long): Collection<String> =
    setOf(summaryKey(conversationId), updatedAtKey(conversationId))

private fun isSummaryUnknown(prefs: SharedPreferences, conversationId: Long): Boolean =
    SharedPreferencesDurability.isUnknown(prefs, summaryKey(conversationId)) ||
        SharedPreferencesDurability.isUnknown(prefs, updatedAtKey(conversationId))

private fun restoreSummarySnapshot(
    prefs: SharedPreferences,
    conversationId: Long,
    snapshot: Map<String, Any?>,
): Boolean {
    val restored = prefs.restoreKeys(snapshot)
    if (!restored) {
        SharedPreferencesDurability.markUnknown(prefs, summaryKey(conversationId))
        SharedPreferencesDurability.markUnknown(prefs, updatedAtKey(conversationId))
    }
    return restored
}
