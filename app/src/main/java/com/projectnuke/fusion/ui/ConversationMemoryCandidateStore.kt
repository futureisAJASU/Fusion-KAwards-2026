package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.util.BoundedUtf8FileRead
import com.projectnuke.fusion.util.readUtf8FileBounded
import com.projectnuke.fusion.util.writeTextAtomically
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.FusionModelSpec
import com.projectnuke.fusion.model.resolveStableLocalModelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ConversationMemoryCandidate(
    val id: String,
    val text: String,
    val conversationId: Long,
    val createdAt: Long,
    val updatedAt: Long? = null,
    val conversationTitle: String? = null,
    val enabled: Boolean = true,
    val scope: MemoryScope = MemoryScope.GLOBAL,
    val modelId: String? = null,
    val savedByUser: Boolean = true
)

enum class MemoryScope {
    GLOBAL,
    CONVERSATION_ONLY,
    MODEL_ONLY,
    DISABLED
}

enum class MemoryManagerSortMode {
    UPDATED_DESC,
    CREATED_DESC,
    SHORTEST_FIRST,
    LONGEST_FIRST,
    ENABLED_FIRST
}

private const val ConversationMemoryCandidatePrefs = "fusion_memory_candidates"
private const val ConversationMemoryCandidateKey = "saved_candidates"
const val PrefMemoryManagerSortMode = "memory_manager_sort_mode"
private const val MaxCandidateRecords = 1_000
internal const val MaxCandidateTextChars = 2_000
private const val MaxCandidateSourceChars = 512
private const val MaxCandidateFileBytes = 1 * 1024 * 1024
private const val MaxHistoricalRecoveryFileBytes = 16 * MaxCandidateFileBytes

private val storeMutex = Mutex()

private fun candidateFile(context: Context): File =
    File(context.filesDir, "conversation_memory_candidates.json")

private data class ExistingCandidateFile(
    val exists: Boolean,
    val readableJson: String?,
)

/**
 * Typed candidate-source state distinguishing the authority of the data source.
 *
 * CURRENT_VALID: the current candidate file is readable and authoritative.
 *   Normal reads/mutations/migrations proceed.
 *
 * CURRENT_MISSING: no current file exists. A valid legacy SharedPreferences
 *   source may be migrated into a new current file (bounded legacy migration).
 *
 * CURRENT_UNREADABLE_WITH_OPTIONAL_LEGACY_FALLBACK: the current file exists but
 *   is unreadable (malformed, oversized). Legacy data may be shown as a
 *   non-authoritative fallback for display, but NO automatic migration write,
 *   NO update/write/delete derived from the legacy fallback, and NO
 *   single-candidate mutation may replace the current unreadable artifact.
 *   The unreadable current artifact must remain byte-identical.
 */
private enum class CandidateSource {
    CURRENT_VALID,
    CURRENT_MISSING,
    CURRENT_UNREADABLE_WITH_OPTIONAL_LEGACY_FALLBACK,
}

private data class CandidateSourceResolution(
    val source: CandidateSource,
    val currentJson: String?,
    val legacyJson: String?,
)

private fun resolveCandidateSource(file: File, prefs: android.content.SharedPreferences): CandidateSourceResolution {
    val existingFile = readValidatedCandidateFile(file)
    val legacy = prefs.getString(ConversationMemoryCandidateKey, "[]") ?: "[]"
    return when {
        existingFile.readableJson != null -> CandidateSourceResolution(
            source = CandidateSource.CURRENT_VALID,
            currentJson = existingFile.readableJson,
            legacyJson = legacy,
        )
        existingFile.exists -> CandidateSourceResolution(
            source = CandidateSource.CURRENT_UNREADABLE_WITH_OPTIONAL_LEGACY_FALLBACK,
            currentJson = null,
            legacyJson = legacy,
        )
        else -> CandidateSourceResolution(
            source = CandidateSource.CURRENT_MISSING,
            currentJson = null,
            legacyJson = legacy,
        )
    }
}

/**
 * Single authoritative mutation read.
 *
 * A mutation must decide its base state from ONE source resolution — never
 * from a separate readability pre-check followed by the display-capable
 * reader, because the two observations are not atomic and the display reader
 * is explicitly allowed to fall back to legacy/empty data.
 *
 * Contract:
 * - CURRENT_VALID -> the current file's authoritative JSON.
 * - CURRENT_MISSING + no legacy -> authoritative empty list.
 * - CURRENT_MISSING + valid legacy -> the existing bounded migration result,
 *   but only when that migration actually succeeds.
 * - CURRENT_UNREADABLE_WITH_OPTIONAL_LEGACY_FALLBACK -> null. Legacy/empty
 *   display fallback must NEVER authorize a write, so the caller fails closed.
 *
 * Returns null when no authoritative base exists.
 */
internal suspend fun readCandidateJsonForMutation(context: Context): String? =
    withContext(Dispatchers.IO) {
        val file = candidateFile(context)
        val prefs = context.getSharedPreferences(ConversationMemoryCandidatePrefs, Context.MODE_PRIVATE)
        val resolution = resolveCandidateSource(file, prefs)
        when (resolution.source) {
            CandidateSource.CURRENT_VALID -> resolution.currentJson
            CandidateSource.CURRENT_UNREADABLE_WITH_OPTIONAL_LEGACY_FALLBACK -> null
            CandidateSource.CURRENT_MISSING -> {
                val legacy = resolution.legacyJson ?: "[]"
                if (legacy == "[]") "[]"
                else migrateLegacyCandidateJson(file, prefs, legacy)
            }
        }
    }

private fun readValidatedCandidateFile(file: File): ExistingCandidateFile {
    if (!file.isFile) return ExistingCandidateFile(exists = false, readableJson = null)
    if (file.length() > MaxHistoricalRecoveryFileBytes) {
        // Preserve an artifact too large for bounded recovery. It is not
        // treated as a writable/current representation.
        return ExistingCandidateFile(exists = true, readableJson = null)
    }
    val raw = when (val read = readUtf8FileBounded(file, MaxHistoricalRecoveryFileBytes)) {
        is BoundedUtf8FileRead.Success -> read.text
        is BoundedUtf8FileRead.Oversized,
        is BoundedUtf8FileRead.Failed -> return ExistingCandidateFile(exists = true, readableJson = null)
    }
    // Ordinary malformed JSON is unreadable data; cancellation and fatal
    // Error propagate and are never converted into unreadable/empty (C15).
    val parseable = try {
        JSONArray(raw)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    return ExistingCandidateFile(exists = true, readableJson = raw.takeIf { parseable })
}

/**
 * Parses an authoritative mutation base. An unreadable authoritative list is
 * NOT an empty authoritative list: an ordinary parse failure fails the
 * mutation closed (null), while fatal [Error] propagates untouched.
 */
private fun parseMutationBaseJson(raw: String): JSONArray? = try {
    JSONArray(raw)
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    null
}

private suspend fun readCandidateJson(context: Context): String = withContext(Dispatchers.IO) {
    val file = candidateFile(context)
    val prefs = context.getSharedPreferences(ConversationMemoryCandidatePrefs, Context.MODE_PRIVATE)
    val resolution = resolveCandidateSource(file, prefs)
    when (resolution.source) {
        CandidateSource.CURRENT_VALID -> {
            // A readable existing file is authoritative. Legacy preferences may
            // be stale duplicate cleanup material after a failed remove().commit;
            // they must never overwrite newer file contents.
            val legacy = resolution.legacyJson ?: "[]"
            if (legacy != "[]") {
                prefs.edit().remove(ConversationMemoryCandidateKey).commit()
            }
            resolution.currentJson!!
        }
        CandidateSource.CURRENT_UNREADABLE_WITH_OPTIONAL_LEGACY_FALLBACK -> {
            // Do not overwrite an unreadable or historically oversized artifact
            // while attempting legacy recovery. A readable legacy source may be
            // used in memory for display, but the artifact remains untouched.
            // IMPORTANT: this legacy data is NOT authoritative mutation state.
            resolution.legacyJson ?: "[]"
        }
        CandidateSource.CURRENT_MISSING -> {
            val legacy = resolution.legacyJson ?: "[]"
            if (legacy == "[]") return@withContext legacy
            val migrated = migrateLegacyCandidateJson(file, prefs, legacy)
                ?: return@withContext legacy
            migrated
        }
    }
}

private fun migrateLegacyCandidateJson(
    file: File,
    prefs: android.content.SharedPreferences,
    legacy: String,
): String? {
    // Recoverable malformed legacy data fails the migration closed;
    // cancellation and fatal Error propagate (C15).
    val canonical: String = try {
        val array = JSONArray(legacy)
        if (array.length() > MaxCandidateRecords) return null
        array.toString().takeIf {
            it.toByteArray(Charsets.UTF_8).size <= MaxCandidateFileBytes
        } ?: return null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    }

    val written: Boolean = try {
        writeTextAtomically(file, canonical)
        file.isFile && file.length() <= MaxCandidateFileBytes &&
            (readUtf8FileBounded(file, MaxCandidateFileBytes) as? BoundedUtf8FileRead.Success)
                ?.text?.let { JSONArray(it).toString() == canonical } == true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (!written) return null

    // Legacy data is removed only after the bounded replacement has been
    // written and read back successfully. If deletion fails, retaining the
    // duplicate legacy source is safer than risking data loss.
    prefs.edit().remove(ConversationMemoryCandidateKey).commit()
    return canonical
}

private suspend fun writeCandidateJson(context: Context, json: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val bounded = JSONArray(json)
            if (bounded.length() > MaxCandidateRecords) return@withContext false
            val encoded = bounded.toString()
            if (encoded.toByteArray(Charsets.UTF_8).size > MaxCandidateFileBytes) return@withContext false
            writeTextAtomically(candidateFile(context), encoded)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Recoverable encode/IO failure; fatal Error propagates (C15).
            false
        }
    }

suspend fun loadConversationMemoryCandidates(
    context: Context,
    conversationId: Long
): List<ConversationMemoryCandidate> = storeMutex.withLock {
    if (conversationId <= 0L) return@withLock emptyList()
    loadAllConversationMemoryCandidatesLocked(context)
        .filter { it.conversationId == conversationId }
}

suspend fun loadAllConversationMemoryCandidates(
    context: Context
): List<ConversationMemoryCandidate> = storeMutex.withLock {
    loadAllConversationMemoryCandidatesLocked(context)
}

private suspend fun loadAllConversationMemoryCandidatesLocked(
    context: Context,
): List<ConversationMemoryCandidate> {
    val file = candidateFile(context)
    val prefs = context.getSharedPreferences(ConversationMemoryCandidatePrefs, Context.MODE_PRIVATE)
    val resolution = resolveCandidateSource(file, prefs)
    val raw = readCandidateJson(context)
    // Malformed persisted data uses corruption/recovery behavior;
    // cancellation and fatal Error propagate, never empty-list (C15).
    val arr = try {
        JSONArray(raw)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return emptyList()
    }
    val catalog = FusionModelCatalog.all(context)
    // When the current artifact is unreadable, model-ID normalization may be
    // performed in-memory for display, but must NOT persist the normalized
    // legacy fallback over the unreadable file.
    val isUnreadable = resolution.source == CandidateSource.CURRENT_UNREADABLE_WITH_OPTIONAL_LEGACY_FALLBACK
    var migrated = false
    return buildList {
        for (index in 0 until minOf(arr.length(), MaxCandidateRecords)) {
            val obj = arr.optJSONObject(index) ?: continue
            val text = obj.optString("text").trim().take(MaxCandidateTextChars)
            if (text.isBlank()) continue
            val enabled = obj.optBoolean("enabled", true)
            val scope = obj.optString("scope")
                .takeIf { it.isNotBlank() }
                ?.let {
                    try {
                        MemoryScope.valueOf(it)
                    } catch (_: Exception) {
                        null
                    }
                }
                ?: if (enabled) MemoryScope.GLOBAL else MemoryScope.DISABLED
            val storedModelId = obj.optString("modelId").takeIf { it.isNotBlank() }
            val normalizedModelId = if (scope == MemoryScope.MODEL_ONLY) {
                normalizeModelOnlyModelId(storedModelId, catalog)
            } else {
                storedModelId
            }
            if (!isUnreadable && scope == MemoryScope.MODEL_ONLY && storedModelId != normalizedModelId) {
                if (normalizedModelId == null) obj.remove("modelId") else obj.put("modelId", normalizedModelId)
                migrated = true
            }
            add(ConversationMemoryCandidate(
                    id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                    text = text,
                    conversationId = obj.optLong("conversationId"),
                    createdAt = obj.optLong("createdAt"),
                    updatedAt = obj.optLong("updatedAt").takeIf { it > 0L },
                    conversationTitle = obj.optString("conversationTitle")
                        .take(MaxCandidateSourceChars).takeIf { it.isNotBlank() },
                    enabled = enabled,
                    scope = scope,
                    // For unreadable current artifact: return display-normalized
                    // model ID in memory without persisting it.
                    modelId = if (isUnreadable) normalizedModelId else obj.optString("modelId").takeIf { it.isNotBlank() },
                    savedByUser = obj.optBoolean("savedByUser", true)
                ))
        }
        // Never persist a migration write when the current artifact is
        // unreadable: that would overwrite unreadable evidence with
        // normalized legacy fallback data.
        if (migrated && !isUnreadable) writeCandidateJson(context, arr.toString())
    }.sortedByDescending { it.updatedAt ?: it.createdAt }
}

internal fun normalizeModelOnlyModelId(
    modelId: String?,
    catalog: List<FusionModelSpec>,
): String? = modelId?.takeIf { it.isNotBlank() }?.let {
    if (catalog.any { spec -> spec.id == it }) return@let it
    val displayMatches = catalog.filter { spec -> spec.displayName == it }
    displayMatches.singleOrNull()?.id ?: it
}

enum class ConversationMemorySaveStatus {
    SAVED,
    NO_VALID_CANDIDATES,
    CAPACITY_REJECTED,
    WRITE_FAILED,
}

data class ConversationMemorySaveResult(
    val savedCount: Int,
    val status: ConversationMemorySaveStatus,
)

suspend fun saveConversationMemoryCandidatesWithResult(
    context: Context,
    conversationId: Long,
    candidates: List<String>,
    conversationTitle: String? = null
): ConversationMemorySaveResult = storeMutex.withLock {
    if (conversationId <= 0L) {
        return@withLock ConversationMemorySaveResult(0, ConversationMemorySaveStatus.NO_VALID_CANDIDATES)
    }
    val cleanCandidates = candidates
        .map { normalizeMemoryCandidateText(it) }
        .map { it.take(MaxCandidateTextChars) }
        .filter { it.isNotBlank() }
        .distinct()
    if (cleanCandidates.isEmpty()) {
        return@withLock ConversationMemorySaveResult(0, ConversationMemorySaveStatus.NO_VALID_CANDIDATES)
    }

    // One authoritative mutation read. Appending to a display-only legacy or
    // empty fallback would overwrite current candidates with a partial
    // rewrite, so an unavailable authoritative base fails the save closed.
    val baseJson = readCandidateJsonForMutation(context)
        ?: return@withLock ConversationMemorySaveResult(0, ConversationMemorySaveStatus.WRITE_FAILED)
    val existing = parseMutationBaseJson(baseJson)
        ?: return@withLock ConversationMemorySaveResult(0, ConversationMemorySaveStatus.WRITE_FAILED)

    val existingKeys = buildSet {
        for (index in 0 until existing.length()) {
            val obj = existing.optJSONObject(index) ?: continue
            add("${obj.optLong("conversationId")}:${normalizeMemoryCandidateText(obj.optString("text"))}")
        }
    }

    val now = System.currentTimeMillis()
    val updated = JSONArray()
    var savedCount = 0
    cleanCandidates.forEach { candidate ->
        val dedupeKey = "$conversationId:$candidate"
        if (existingKeys.contains(dedupeKey)) return@forEach
        updated.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("text", candidate)
                .put("conversationId", conversationId)
                .put("createdAt", now)
                .put("updatedAt", now)
                .put("conversationTitle", conversationTitle?.take(MaxCandidateSourceChars))
                .put("enabled", true)
                .put("scope", MemoryScope.CONVERSATION_ONLY.name)
                .put("savedByUser", true)
        )
        savedCount++
    }
    if (savedCount == 0) {
        return@withLock ConversationMemorySaveResult(0, ConversationMemorySaveStatus.NO_VALID_CANDIDATES)
    }
    for (index in 0 until existing.length()) {
        updated.put(existing.get(index))
    }
    val candidateJson = updated.toString()
    if (updated.length() > MaxCandidateRecords ||
        candidateJson.toByteArray(Charsets.UTF_8).size > MaxCandidateFileBytes
    ) {
        return@withLock ConversationMemorySaveResult(0, ConversationMemorySaveStatus.CAPACITY_REJECTED)
    }
    if (writeCandidateJson(context, candidateJson)) {
        ConversationMemorySaveResult(savedCount, ConversationMemorySaveStatus.SAVED)
    } else {
        ConversationMemorySaveResult(0, ConversationMemorySaveStatus.WRITE_FAILED)
    }
}

suspend fun saveConversationMemoryCandidates(
    context: Context,
    conversationId: Long,
    candidates: List<String>,
    conversationTitle: String? = null
): Int = saveConversationMemoryCandidatesWithResult(
    context = context,
    conversationId = conversationId,
    candidates = candidates,
    conversationTitle = conversationTitle,
).savedCount

suspend fun updateConversationMemoryCandidate(
    context: Context,
    candidateId: String,
    newText: String
): Boolean = storeMutex.withLock {
    val cleanText = normalizeMemoryCandidateText(newText)
    if (candidateId.isBlank() || cleanText.isBlank() || cleanText.length > MaxCandidateTextChars) {
        return@withLock false
    }
    // One authoritative mutation read: a present unreadable current artifact
    // must never be overwritten using legacy/empty fallback as the write base.
    val baseJson = readCandidateJsonForMutation(context) ?: return@withLock false
    val existing = parseMutationBaseJson(baseJson) ?: return@withLock false
    var changed = false
    for (index in 0 until existing.length()) {
        // Non-object raws stay untouched in place; only the addressed entry
        // is modified, so malformed entries are never dropped by a rewrite.
        val obj = existing.optJSONObject(index) ?: continue
        if (obj.optString("id") != candidateId) continue
        obj.put("text", cleanText)
        obj.put("updatedAt", System.currentTimeMillis())
        changed = true
        break
    }
    if (!changed) return@withLock false
    writeCandidateJson(context, existing.toString())
}

suspend fun setConversationMemoryCandidateEnabled(
    context: Context,
    candidateId: String,
    enabled: Boolean
): Boolean = storeMutex.withLock {
    if (candidateId.isBlank()) return@withLock false
    // One authoritative mutation read: a present unreadable current artifact
    // must never be overwritten using legacy/empty fallback as the write base.
    val baseJson = readCandidateJsonForMutation(context) ?: return@withLock false
    val existing = parseMutationBaseJson(baseJson) ?: return@withLock false
    var changed = false
    for (index in 0 until existing.length()) {
        val obj = existing.optJSONObject(index) ?: continue
        if (obj.optString("id") != candidateId) continue
        obj.put("enabled", enabled)
        obj.put("scope", if (enabled) MemoryScope.GLOBAL.name else MemoryScope.DISABLED.name)
        if (!enabled) obj.remove("modelId")
        obj.put("updatedAt", System.currentTimeMillis())
        changed = true
        break
    }
    if (!changed) return@withLock false
    writeCandidateJson(context, existing.toString())
}

suspend fun setConversationMemoryCandidateScope(
    context: Context,
    candidateId: String,
    scope: MemoryScope,
    modelId: String? = null
): Boolean = storeMutex.withLock {
    if (candidateId.isBlank()) return@withLock false
    if (scope == MemoryScope.MODEL_ONLY && modelId.isNullOrBlank()) return@withLock false
    // One authoritative mutation read: a present unreadable current artifact
    // must never be overwritten using legacy/empty fallback as the write base.
    val baseJson = readCandidateJsonForMutation(context) ?: return@withLock false
    val existing = parseMutationBaseJson(baseJson) ?: return@withLock false
    var changed = false
    for (index in 0 until existing.length()) {
        val obj = existing.optJSONObject(index) ?: continue
        if (obj.optString("id") != candidateId) continue
        obj.put("enabled", scope != MemoryScope.DISABLED)
        obj.put("scope", scope.name)
        if (scope == MemoryScope.MODEL_ONLY) {
            obj.put("modelId", modelId)
        } else {
            obj.remove("modelId")
        }
        obj.put("updatedAt", System.currentTimeMillis())
        changed = true
        break
    }
    if (!changed) return@withLock false
    writeCandidateJson(context, existing.toString())
}

suspend fun deleteConversationMemoryCandidate(
    context: Context,
    candidateId: String
): Boolean = storeMutex.withLock {
    if (candidateId.isBlank()) return@withLock false
    // One authoritative mutation read: a present unreadable current artifact
    // must never be overwritten using legacy/empty fallback as the write base.
    val baseJson = readCandidateJsonForMutation(context) ?: return@withLock false
    val existing = parseMutationBaseJson(baseJson) ?: return@withLock false
    val updated = JSONArray()
    var removed = false
    for (index in 0 until existing.length()) {
        // Preserve non-object raws byte-for-byte so a delete never drops
        // malformed entries it was not asked to remove.
        val obj = existing.optJSONObject(index) ?: run {
            updated.put(existing.get(index))
            continue
        }
        if (obj.optString("id") == candidateId) {
            removed = true
            continue
        }
        updated.put(obj)
    }
    if (!removed) return@withLock false
    writeCandidateJson(context, updated.toString())
}

suspend fun deleteConversationOnlyMemoryCandidates(
    context: Context,
    conversationId: Long,
): Boolean = storeMutex.withLock {
    if (conversationId <= 0L) return@withLock true
    // One authoritative mutation read: rewriting from a display-only legacy or
    // empty fallback would delete current candidates instead of only this
    // conversation's candidates.
    val baseJson = readCandidateJsonForMutation(context) ?: return@withLock false
    val existing = parseMutationBaseJson(baseJson) ?: return@withLock false
    val updated = JSONArray()
    for (index in 0 until existing.length()) {
        // Preserve non-object raws byte-for-byte; only well-formed entries
        // participate in the conversation/promotion filter.
        val item = existing.optJSONObject(index) ?: run {
            updated.put(existing.get(index))
            continue
        }
        val belongsToConversation = item.optLong("conversationId") == conversationId
        val promoted = item.optBoolean("enabled", false) &&
            item.optString("scope") in setOf(MemoryScope.GLOBAL.name, MemoryScope.MODEL_ONLY.name)
        if (!belongsToConversation || promoted) updated.put(item)
    }
    writeCandidateJson(context, updated.toString())
}

internal fun normalizeMemoryCandidateText(value: String): String {
    return value
        .replace(Regex("""^(?:[\-\*\u2022\u25CF\u25E6]\s*|\d+[\.\)]\s*)"""), "")
        .trim()
}
