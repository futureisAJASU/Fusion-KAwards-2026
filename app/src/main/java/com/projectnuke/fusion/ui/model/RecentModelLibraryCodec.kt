package com.projectnuke.fusion.ui.model

import android.content.SharedPreferences
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** One bounded production recent-model contract shared by every reader/writer. */
data class RecentModelEntry(
    val modelId: String,
    val displayName: String,
    val lastUsedAt: Long,
    val useCount: Int,
)

const val MaxRecentModels = 10

/**
 * Single-source `recent_models` codec.
 *
 * The production writer ([record]/[save]) persists an array of objects:
 * `{ "id": String, "name": String, "lastUsedAt": Long, "useCount": Int }`,
 * bounded to [MaxRecentModels] entries. Readers skip malformed entries and
 * sort by the most recent use. Backup export/restore must obey the SAME
 * shape and bounds; a String-only recent array was never produced by any
 * writer and is not a supported representation.
 */
object RecentModelLibraryCodec {
    const val KEY = "recent_models"
    const val MAX_RECENT_RAW_BYTES = 64 * 1024
    const val MAX_RECENT_SCAN_ITEMS = 64

    /** Production reader semantics: skip malformed entries, newest first, bounded. */
    fun decode(raw: String?): List<RecentModelEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_RECENT_RAW_BYTES) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            data class Candidate(val entry: RecentModelEntry, val scanIndex: Int)
            val newestById = linkedMapOf<String, Candidate>()
            for (i in 0 until minOf(arr.length(), MAX_RECENT_SCAN_ITEMS)) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.opt("id") as? String ?: continue
                if (id.isBlank()) continue
                val name = obj.opt("name") as? String ?: continue
                val lastUsedAt = obj.integralLongInRange("lastUsedAt", min = 0L, max = Long.MAX_VALUE) ?: continue
                val useCount = obj.integralLongInRange("useCount", min = 1L, max = Int.MAX_VALUE.toLong())?.toInt() ?: continue
                val candidate = Candidate(RecentModelEntry(id, name, lastUsedAt, useCount), i)
                val previous = newestById[id]
                if (previous == null || candidate.entry.lastUsedAt > previous.entry.lastUsedAt) {
                    newestById[id] = candidate
                }
            }
            newestById.values
                .sortedWith(compareByDescending<Candidate> { it.entry.lastUsedAt }.thenBy { it.scanIndex })
                .map { it.entry }
                .take(MaxRecentModels)
        }.getOrDefault(emptyList())
    }

    /** Production writer semantics: bounded object array with the exact contract fields. */
    fun encode(items: List<RecentModelEntry>): String {
        val arr = JSONArray()
        items.take(MaxRecentModels).forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.modelId)
                    put("name", item.displayName)
                    put("lastUsedAt", item.lastUsedAt)
                    put("useCount", item.useCount)
                }
            )
        }
        return arr.toString()
    }

    fun load(prefs: SharedPreferences): List<RecentModelEntry> =
        decode(prefs.getString(KEY, null))

    fun save(prefs: SharedPreferences, items: List<RecentModelEntry>) {
        prefs.edit().putString(KEY, encode(items)).apply()
    }

    fun readRecentIds(prefs: SharedPreferences): Set<String> =
        decode(prefs.getString(KEY, null)).map { it.modelId }.toSet()

    fun record(prefs: SharedPreferences, modelId: String, displayName: String) {
        if (modelId.isBlank()) return
        val now = System.currentTimeMillis()
        val current = load(prefs)
        val existing = current.firstOrNull { it.modelId == modelId }
        val lastUsedAt = current.maxOfOrNull { it.lastUsedAt }?.let { newest ->
            if (newest == Long.MAX_VALUE) Long.MAX_VALUE else maxOf(now, newest + 1L)
        } ?: now
        val updated = RecentModelEntry(
            modelId = modelId,
            displayName = displayName.ifBlank { existing?.displayName ?: modelId },
            lastUsedAt = lastUsedAt,
            useCount = when (val currentCount = existing?.useCount) {
                null -> 1
                Int.MAX_VALUE -> Int.MAX_VALUE
                else -> currentCount + 1
            },
        )
        save(prefs, listOf(updated) + current.filterNot { it.modelId == modelId })
    }

    private fun JSONObject.integralLongInRange(key: String, min: Long, max: Long): Long? {
        val value = opt(key)
        if (value !is Number) return null
        val decimal = runCatching { BigDecimal(value.toString()) }.getOrNull() ?: return null
        if (decimal.stripTrailingZeros().scale() > 0) return null
        if (decimal < BigDecimal.valueOf(min) || decimal > BigDecimal.valueOf(max)) return null
        return decimal.toLong()
    }
}
