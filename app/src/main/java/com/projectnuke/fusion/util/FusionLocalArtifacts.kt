package com.projectnuke.fusion.util

import android.content.Context
import android.util.Log
import com.projectnuke.fusion.model.GenerationSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FusionResponseRating(
    val messageId: Long,
    val rating: String,
    val modelName: String,
    val settingsSnapshot: String,
    val createdAt: Long
)

object FusionConversationSummaries {
    private const val PrefName = "fusion_conversation_summaries"

    fun get(context: Context, conversationId: Long): String? {
        if (conversationId == 0L) return null
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getString(conversationId.toString(), null)
            ?.takeIf { it.isNotBlank() }
    }

    fun save(context: Context, conversationId: Long, summary: String) {
        if (conversationId == 0L || summary.isBlank()) return
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putString(conversationId.toString(), summary.trim())
            .apply()
    }
}

object FusionMemoryCandidates {
    fun extract(userVisibleTexts: List<String>): List<String> {
        return userVisibleTexts
            .flatMap { it.lines() }
            .map { it.trim().removePrefix("-").trim() }
            .filter { line ->
                line.length in 12..120 &&
                    !line.contains("FUSION_", ignoreCase = true) &&
                    !line.contains("http", ignoreCase = true)
            }
            .distinct()
            .take(8)
            .map { "- $it" }
    }
}

internal sealed interface ResponseRatingsLoad {
    data object Missing : ResponseRatingsLoad
    data class Valid(val entries: List<FusionResponseRating>) : ResponseRatingsLoad
    data object Corrupt : ResponseRatingsLoad
}

object FusionResponseRatings {
    internal const val MaxRatings = 2_000
    private const val MaxStringChars = 4_096
    private const val QuarantineSuffix = ".corrupt.bak"
    private val writeLock = Any()
    private fun file(context: Context): File = File(context.filesDir, "fusion_response_ratings.json")

    /**
     * Strict parse shared by every accessor. The writer keeps at most one
     * entry per message, so duplicate IDs, non-object entries, and
     * non-integral IDs prove an out-of-contract artifact instead of
     * recoverable state. Display-only string bounds stay lenient: overlong
     * values are clamped on the next write, never a reason to brick reads.
     */
    internal fun parseStrict(raw: String): ResponseRatingsLoad {
        val array = try {
            JSONArray(raw)
        } catch (_: Throwable) {
            return ResponseRatingsLoad.Corrupt
        }
        if (array.length() > MaxRatings) return ResponseRatingsLoad.Corrupt
        val seenIds = HashSet<Long>(array.length())
        val entries = ArrayList<FusionResponseRating>(array.length())
        for (index in 0 until array.length()) {
            val item = try {
                array.optJSONObject(index) ?: return ResponseRatingsLoad.Corrupt
            } catch (_: Throwable) {
                return ResponseRatingsLoad.Corrupt
            }
            if (!item.has("messageId")) return ResponseRatingsLoad.Corrupt
            val idRaw = try { item.get("messageId") } catch (_: Throwable) {
                return ResponseRatingsLoad.Corrupt
            }
            val messageId = exactLongOrNull(idRaw) ?: return ResponseRatingsLoad.Corrupt
            if (!seenIds.add(messageId)) return ResponseRatingsLoad.Corrupt
            val ratingRaw = try { item.get("rating") } catch (_: Throwable) {
                return ResponseRatingsLoad.Corrupt
            }
            if (ratingRaw !is String) return ResponseRatingsLoad.Corrupt
            val modelName = if (!item.has("modelName") || item.isNull("modelName")) {
                ""
            } else {
                item.get("modelName") as? String ?: return ResponseRatingsLoad.Corrupt
            }
            val settingsSnapshot = if (!item.has("settingsSnapshot") || item.isNull("settingsSnapshot")) {
                ""
            } else {
                item.get("settingsSnapshot") as? String ?: return ResponseRatingsLoad.Corrupt
            }
            val createdAt = if (!item.has("createdAt") || item.isNull("createdAt")) {
                0L
            } else {
                exactLongOrNull(item.get("createdAt")) ?: return ResponseRatingsLoad.Corrupt
            }
            entries += FusionResponseRating(
                messageId = messageId,
                rating = ratingRaw,
                modelName = modelName,
                settingsSnapshot = settingsSnapshot,
                createdAt = createdAt,
            )
        }
        return ResponseRatingsLoad.Valid(entries)
    }

    private fun loadLocked(file: File): ResponseRatingsLoad {
        if (!file.isFile) return ResponseRatingsLoad.Missing
        val raw = runCatching { file.readText() }.getOrNull() ?: return ResponseRatingsLoad.Corrupt
        return parseStrict(raw)
    }

    fun get(context: Context, messageId: Long): String? {
        // Display fallback only: unreadable state yields no rating, and get
        // never writes, so it cannot destroy evidence.
        val entries = when (val load = synchronized(writeLock) { loadLocked(file(context)) }) {
            is ResponseRatingsLoad.Valid -> load.entries
            is ResponseRatingsLoad.Missing,
            is ResponseRatingsLoad.Corrupt -> return null
        }
        return entries.firstOrNull { it.messageId == messageId }
            ?.rating?.takeIf { it.isNotBlank() }
    }

    /**
     * Toggles a rating. Returns true when the message is now rated, false
     * when it was un-rated OR when durable persistence failed (fail-closed:
     * corrupt evidence is quarantined, never overwritten blindly). Callers
     * that need to distinguish failure should re-read via [get].
     */
    fun toggle(context: Context, rating: FusionResponseRating): Boolean = synchronized(writeLock) {
        val file = file(context)
        val base = when (val load = loadLocked(file)) {
            is ResponseRatingsLoad.Valid -> load.entries
            is ResponseRatingsLoad.Missing -> emptyList()
            is ResponseRatingsLoad.Corrupt -> {
                when (quarantineLocked(file)) {
                    is DebtQuarantineOutcome.Secured,
                    is DebtQuarantineOutcome.SourceMissing -> emptyList()
                    is DebtQuarantineOutcome.Failed -> return false
                }
            }
        }
        val hadSameRating = base.any { it.messageId == rating.messageId && it.rating == rating.rating }
        val kept = base.filterNot { it.messageId == rating.messageId }
        val updated = if (hadSameRating) {
            kept
        } else {
            kept + rating.copy(
                rating = rating.rating.take(40),
                modelName = rating.modelName.take(300),
                settingsSnapshot = rating.settingsSnapshot.take(MaxStringChars),
            )
        }
        // Exact intended bound: keep at most MaxRatings entries, evicting the
        // oldest first. (Previously `>=` capped the effective maximum at
        // MaxRatings - 1.)
        val bounded = if (updated.size > MaxRatings) updated.takeLast(MaxRatings) else updated
        if (!persistLocked(file, bounded)) return false
        !hadSameRating
    }

    fun deleteForMessages(context: Context, messageIds: Set<Long>): Boolean = synchronized(writeLock) {
        if (messageIds.isEmpty()) return true
        val target = file(context)
        // Fail closed: malformed entries are never silently dropped by a
        // rewrite, and corrupt evidence is never replaced with a partial list.
        val base = when (val load = loadLocked(target)) {
            is ResponseRatingsLoad.Valid -> load.entries
            is ResponseRatingsLoad.Missing -> return true
            is ResponseRatingsLoad.Corrupt -> return false
        }
        return persistLocked(target, base.filterNot { it.messageId in messageIds })
    }

    private fun quarantineLocked(file: File): DebtQuarantineOutcome {
        val outcome = DebtQuarantine.quarantine(file, QuarantineSuffix)
        if (outcome is DebtQuarantineOutcome.Failed) {
            Log.w("FusionRatings", "Quarantine FAILED; preserving original ratings file")
        }
        return outcome
    }

    private fun persistLocked(file: File, entries: List<FusionResponseRating>): Boolean {
        if (entries.size > MaxRatings) return false
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject()
                .put("messageId", entry.messageId)
                .put("rating", entry.rating.take(40))
                .put("modelName", entry.modelName.take(300))
                .put("settingsSnapshot", entry.settingsSnapshot.take(MaxStringChars))
                .put("createdAt", entry.createdAt))
        }
        return runCatching {
            writeTextAtomically(file, array.toString(2))
            true
        }.getOrDefault(false)
    }
}

fun GenerationSettings.toRatingSnapshot(): String {
    return "maxTokens=$maxTokens, temp=$temperature, topK=$topK, topP=$topP, accelerator=${accelerator.name}, mtp=${speculativeDecodingEnabled == true}"
}
