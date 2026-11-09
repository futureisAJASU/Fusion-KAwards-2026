package com.projectnuke.fusion.ui

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.LocalGenerationSettingsPolicy
import com.projectnuke.fusion.model.LocalGenerationSettingBounds
import com.projectnuke.fusion.model.LocalGenerationFloatSettings
import com.projectnuke.fusion.model.LocalModelSettingsProfileCodec
import com.projectnuke.fusion.model.LocalModelSettingsProfileStore
import com.projectnuke.fusion.model.LocalModelSettingsDocument
import com.projectnuke.fusion.model.LocalModelSettingsOverride
import com.projectnuke.fusion.model.LocalContextMode
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.model.RuntimePerformanceMode
import com.projectnuke.fusion.model.RuntimePerformancePreference
import com.projectnuke.fusion.model.RuntimePerformancePreferenceStore
import com.projectnuke.fusion.ui.model.RecentModelEntry
import com.projectnuke.fusion.ui.model.RecentModelLibraryCodec
import com.projectnuke.fusion.util.KeyAbsentInSnapshot
import com.projectnuke.fusion.util.ManagedModelPathPolicy
import com.projectnuke.fusion.util.restoreKeys
import com.projectnuke.fusion.util.snapshotKeys
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import com.projectnuke.fusion.ui.theme.FusionThemePreferenceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val settingsRestoreLock = Mutex()

/**
 * Current settings-backup schema version.
 *
 * v2 adds the authoritative KV preference [LocalGenerationSettingsPolicy.KEY_KV_CACHE]
 * (`engine_kv_cache_capacity_tokens`) to the payload. Restore accepts both v1
 * and v2 payloads: v1 backups simply have no `kvCacheCapacityTokens` field and
 * derive a safe KV value through the central policy
 * ([LocalGenerationSettingsPolicy.deriveKvCapacityForOutput]). v3 adds the
 * versioned local-model profile document without changing the older fields.
 * v4 adds the global runtime performance preference; older backups retain the
 * AUTO default when that field is absent.
 */
internal const val FusionSettingsBackupSchemaVersion = 4

/** Oldest schema version that restore still understands. */
private const val MinSupportedSettingsBackupSchemaVersion = 1

private const val PrefSelectedModel = "selected_model"
private const val PrefSelectedModelPath = "selected_model_path"
private const val PrefAccelerator = "accelerator"
private const val PrefMaxTokens = "generation_max_output_tokens"
private const val PrefTemperature = "temperature"
private const val PrefTopK = "top_k"
private const val PrefTopP = "top_p"
private const val PrefSpeculativeDecoding = "speculative_decoding_enabled"
private const val PrefReasoningEnabled = "reasoning_enabled"
private const val PrefReasoningBudget = "reasoning_budget_tokens"
private const val PrefWebSearchEnabled = "web_search_enabled"
private const val PrefFavoriteModelIds = "favorite_model_ids"
private const val PrefHiddenModelIds = "hidden_model_ids"
private const val PrefRecentModels = "recent_models"
private const val PrefModelNotes = "model_notes"
private const val PrefModelLibrarySortMode = "model_library_sort_mode"
private const val PrefFusionAppLanguage = "fusion_app_language"
// Legacy keys for backward compatibility with old backups
private const val LEGACY_PrefMaxTokens = "max_tokens"
private const val LEGACY_PrefKvCache = "kv_cache_capacity_tokens"

enum class SettingsRestoreResult {
    Success,
    ModelPathMissing,
    InvalidJson,
    UnsupportedSchema,
    TooLarge,
    InvalidFields,
    CommitFailed,
}

internal const val MaxSettingsBackupBytes = 512 * 1024
internal const val MaxBackupObjectFields = 64
internal const val MaxBackupArrayItems = 256
internal const val MaxBackupStringChars = 8_192

private val AllowedModelLibrarySortModes = setOf(
    "recommendation", "name", "lightweight", "memory_low", "local_execution", "favorites_first",
)

fun buildSettingsBackupJson(context: Context, prefs: SharedPreferences): String {
    // Phase 4C: build the backup from the effective centralized GenerationSettings
    // so backup cannot export a different authoritative KV merely by being run
    // before migration. fromPrefs() handles legacy-key migration and derives
    // the effective KV from output when the authoritative key does not yet exist.
    val effectiveSettings = LocalGenerationSettingsPolicy.fromPrefs(
        prefs,
        materializeMigration = false,
    )
    val lowMemoryMode = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.let {
        it.isLowRamDevice
    } ?: false
    val root = JSONObject()
        .put("schemaVersion", FusionSettingsBackupSchemaVersion)
        .put("app", "Fusion")
        .put("exportedAt", System.currentTimeMillis())
    val selectedModelVal = prefs.getString(PrefSelectedModel, "Gemma 4 E2B-it")
    enforceStringBound("selectedModel", selectedModelVal)
    val selectedModelPathVal = prefs.getString(PrefSelectedModelPath, null)
    enforceStringBound("selectedModelPath", selectedModelPathVal)
    val acceleratorVal = prefs.getString(PrefAccelerator, AcceleratorMode.GPU.name)
    enforceStringBound("accelerator", acceleratorVal)
    val runtimePerformancePreference = RuntimePerformancePreferenceStore(prefs).read()
    val themeModeVal = FusionThemeMode.fromStorage(
        prefs.getString(FusionThemePreferenceKey, FusionThemeMode.AUTO.storageValue)
    ).storageValue
    enforceStringBound("themeMode", themeModeVal)
    val appLanguageVal = prefs.getString("fusion_app_language", FusionAppLanguage.SYSTEM.value)
    enforceStringBound("appLanguage", appLanguageVal)
    val memorySortModeVal = prefs.getString("memory_manager_sort_mode", MemoryManagerSortMode.UPDATED_DESC.name)
    enforceStringBound("memoryManagerSortMode", memorySortModeVal)
    val settings = JSONObject()
        .put("selectedModel", selectedModelVal)
        .put("selectedModelPath", selectedModelPathVal)
        .put("accelerator", acceleratorVal)
        .put("maxTokens", effectiveSettings.maxTokens)
        // Schema v2: the authoritative KV preference is part of the backup so a
        // restore never has to re-derive it from the output limit. Restored
        // independently from maxTokens (output and KV are NOT re-coupled).
        .put(
            "kvCacheCapacityTokens",
            effectiveSettings.kvCacheCapacityTokens
        )
        .put("temperature", prefs.getFloat(PrefTemperature, 1.0f).toDouble())
        .put("topK", prefs.getInt(PrefTopK, 64))
        .put("topP", prefs.getFloat(PrefTopP, 0.95f).toDouble())
        .put("mtpEnabled", prefs.getBoolean("speculative_decoding_enabled", false))
        .put("reasoningEnabled", prefs.getBoolean("reasoning_enabled", false))
        .put("reasoningBudget", prefs.getInt("reasoning_budget_tokens", 512))
        .put("webSearchEnabled", prefs.getBoolean("web_search_enabled", false))
        .put("themeMode", themeModeVal)
        .put("appLanguage", appLanguageVal)
        .put("savedMemoryContextEnabled", prefs.getBoolean("saved_memory_context_enabled", false))
        .put("memoryManagerSortMode", memorySortModeVal)
        .put("lowMemoryMode", lowMemoryMode)
        .put(
            "runtimePerformance",
            JSONObject()
                .put("mode", runtimePerformancePreference.mode.name)
                .put("manualLevel", runtimePerformancePreference.manualLevel),
        )
        .put(
            "localModelSettingsProfiles",
            sanitizeProfileDocumentForExport(LocalModelSettingsProfileStore(prefs).read())?.let(::JSONObject)
        )
    // Recent models and notes are optional library metadata. They are not
    // authoritative settings, so a legacy/corrupt preference must not abort
    // an otherwise valid backup export. Export is read-only: no preference is
    // repaired or rewritten here. Every emitted field still obeys the SAME
    // bounds/type contract the restore validator enforces, so a successful
    // export can never produce a backup Fusion's own restore path rejects.
    val safeRecent = RecentModelLibraryCodec.encode(
        RecentModelLibraryCodec.load(prefs).filter { entry ->
            isCurrentBackupRecentEntryValid(entry)
        }
    )
    val safeNotes = sanitizeModelNotesForExport(prefs.getString(PrefModelNotes, null))
    val safeFavorites = boundedModelIdList(prefs.getStringSet(PrefFavoriteModelIds, emptySet()) ?: emptySet())
    val safeHidden = boundedModelIdList(prefs.getStringSet(PrefHiddenModelIds, emptySet()) ?: emptySet())
    val storedSortMode = prefs.getString(PrefModelLibrarySortMode, "recommendation")
    val modelLibrary = JSONObject()
        .put("favorites", safeFavorites)
        .put("hidden", safeHidden)
        .put("recent", JSONArray(safeRecent))
        .put("notes", safeNotes)
        .put("sortMode", storedSortMode?.takeIf { it in AllowedModelLibrarySortModes } ?: "recommendation")
    root.put("settings", settings)
    root.put("modelLibrary", modelLibrary)
    var payload = root.toString(2)
    if (payload.toByteArray(Charsets.UTF_8).size > MaxSettingsBackupBytes) {
        // Bounded omission: optional model-library metadata and the optional
        // profile document are dropped (never written back to prefs) so the
        // export still stays within the restorable payload bound.
        Log.d("FusionModelSelect", "settings_export optional metadata omitted (payload over limit)")
        val safeLibrary = JSONObject()
            .put("favorites", JSONArray())
            .put("hidden", JSONArray())
            .put("recent", JSONArray())
            .put("notes", JSONObject())
            .put("sortMode", "recommendation")
        root.put("modelLibrary", safeLibrary)
        root.optJSONObject("settings")?.remove("localModelSettingsProfiles")
        payload = root.toString(2)
    }
    // Final invariant: a successful backup must never exceed the restorable payload bound.
    val finalBytes = payload.toByteArray(Charsets.UTF_8).size
    if (finalBytes > MaxSettingsBackupBytes) {
        throw IllegalStateException(
            "SettingsBackup: final payload ($finalBytes bytes) exceeds MaxSettingsBackupBytes ($MaxSettingsBackupBytes). " +
                "Authoritative settings contain corrupt/oversized values; export aborted."
        )
    }
    if (containsOversizedValue(root)) {
        throw IllegalStateException(
            "SettingsBackup: payload contains oversized value exceeding MaxBackupStringChars ($MaxBackupStringChars). Export aborted."
        )
    }
    Log.d("FusionModelSelect", "settings_export schema=$FusionSettingsBackupSchemaVersion keys=settings,modelLibrary success=true")
    return payload
}

/**
 * Bounded export representation for the optional model notes object
 * (`String -> String`). Corrupt/oversized values are omitted from the
 * payload only; prefs are never mutated here.
 */
private fun sanitizeModelNotesForExport(raw: String?): JSONObject {
    if (raw.isNullOrBlank()) return JSONObject()
    return runCatching {
        val source = JSONObject(raw)
        val bounded = JSONObject()
        var fields = 0
        val keys = source.keys()
        while (keys.hasNext() && fields < MaxBackupObjectFields) {
            val key = keys.next()
            val value = source.opt(key)
            if (key.isBlank() || key.length > 200 || value !is String || value.length > MaxBackupStringChars) continue
            bounded.put(key, value)
            fields++
        }
        bounded
    }.getOrDefault(JSONObject())
}

/** Pure current-backup recent-entry validity policy: every field the writer emits must be restorable. */
private fun isCurrentBackupRecentEntryValid(entry: RecentModelEntry): Boolean {
    return entry.modelId.isNotBlank() &&
        entry.modelId.length <= MaxBackupStringChars &&
        entry.displayName.length <= MaxBackupStringChars &&
        entry.lastUsedAt >= 0L &&
        entry.useCount >= 1 && entry.useCount <= Int.MAX_VALUE
}

/** Throws a bounded, documented exception if any authoritative setting string exceeds the restorable bound. */
private fun enforceStringBound(field: String, value: String?) {
    if (value != null && value.length > MaxBackupStringChars) {
        throw IllegalStateException(
            "SettingsBackup: authoritative field '$field' exceeds MaxBackupStringChars " +
                "(${value.length} > $MaxBackupStringChars). Export aborted to prevent un-restorable payload."
        )
    }
}

/** Deterministic bounded array of nonblank model ids for favorites/hidden. */
private fun boundedModelIdList(ids: Set<String>): JSONArray {
    val bounded = ids.filter { it.isNotBlank() && it.length <= MaxBackupStringChars }
        .sorted()
        .take(MaxBackupArrayItems)
    return JSONArray(bounded)
}

/**
 * Bounded export representation for the optional local-model profile
 * document. A document that would violate the restore bounds (more model
 * overrides than [MaxBackupObjectFields], oversized model-id keys) is
 * omitted from the payload only: prefs are never mutated here and the
 * backup still restores (restore keeps the target's current profile when
 * the field is absent).
 */
private fun sanitizeProfileDocumentForExport(
    document: com.projectnuke.fusion.model.LocalModelSettingsDocument?,
): String? {
    if (document == null) return null
    if (document.modelOverrides.size > MaxBackupObjectFields) return null
    if (document.modelOverrides.keys.any { it.isBlank() || it.length > 200 }) return null
    return LocalModelSettingsProfileCodec.encode(document)
}

/**
 * Parses the backup root with explicit exception classification.
 *
 * Only an ordinary parsing failure means the user's payload is bad:
 * malformed JSON / ordinary [Exception] -> null -> [SettingsRestoreResult.InvalidJson].
 * A runtime failure inside the parser is NOT evidence about the backup, so
 * [CancellationException] and fatal [Error] (e.g. [LinkageError]) propagate
 * untouched instead of being mislabeled InvalidJson. (`runCatching` catches
 * every [Throwable] and would swallow both.)
 */
internal fun parseSettingsBackupRoot(
    raw: String,
    factory: (String) -> JSONObject = { JSONObject(it) },
): JSONObject? = try {
    factory(raw)
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    null
}

suspend fun restoreSettingsBackupJson(
    context: Context,
    prefs: SharedPreferences,
    raw: String,
    jsonFactory: (String) -> JSONObject = { JSONObject(it) },
): SettingsRestoreResult = settingsRestoreLock.withLock {
    if (raw.toByteArray(Charsets.UTF_8).size > MaxSettingsBackupBytes) {
        return@withLock SettingsRestoreResult.TooLarge
    }
    val root = parseSettingsBackupRoot(raw, jsonFactory)
        ?: return@withLock SettingsRestoreResult.InvalidJson
    if (!isJsonInteger(root, "schemaVersion") || !isJsonString(root, "app")) {
        return@withLock SettingsRestoreResult.InvalidFields
    }
    val schemaVersion = root.optSaturatingInt("schemaVersion", -1)
    // Accept the current schema and every still-supported older schema so
    // legacy backups written before the KV field existed remain readable.
    if (root.optString("app") != "Fusion" ||
        schemaVersion < MinSupportedSettingsBackupSchemaVersion ||
        schemaVersion > FusionSettingsBackupSchemaVersion
    ) {
        return@withLock SettingsRestoreResult.UnsupportedSchema
    }
    val settings = if (root.has("settings")) {
        root.optJSONObject("settings") ?: return@withLock SettingsRestoreResult.InvalidFields
    } else {
        JSONObject()
    }
    val modelLibrary = if (root.has("modelLibrary")) {
        root.optJSONObject("modelLibrary") ?: return@withLock SettingsRestoreResult.InvalidFields
    } else {
        JSONObject()
    }
    if (root.length() > MaxBackupObjectFields ||
        settings.length() > MaxBackupObjectFields ||
        modelLibrary.length() > MaxBackupObjectFields ||
        containsOversizedValue(root)
    ) {
        return@withLock SettingsRestoreResult.InvalidFields
    }
    if (!hasValidBackupFieldTypes(settings, modelLibrary, schemaVersion)) {
        return@withLock SettingsRestoreResult.InvalidFields
    }
    if (!hasValidBackupSymbolicDomains(settings, modelLibrary)) {
        return@withLock SettingsRestoreResult.InvalidFields
    }
    // Every key this restore schedules a put*/remove for. This is the exact
    // mutation-owned key set snapshotted before commit and rolled back if the
    // commit fails, so a failed restore can never leave process-only restored
    // values behind for the running process while disk keeps the old state.
    val mutatedKeys = mutableListOf<String>()
    // Non-key log annotations (never snapshotted, never written).
    val restoreNotes = mutableListOf<String>()
    val editor = RestoreTrackingEditor(prefs.edit())
    if (settings.has("accelerator")) {
        val rawAccelerator = settings.optString("accelerator", AcceleratorMode.GPU.name)
        val accelerator = AcceleratorMode.valueOf(rawAccelerator)
        editor.putString(PrefAccelerator, accelerator.name)
        mutatedKeys += PrefAccelerator
    }
    if (settings.has("runtimePerformance")) {
        val performance = settings.getJSONObject("runtimePerformance")
        val preference = RuntimePerformancePreference(
            mode = RuntimePerformanceMode.valueOf(performance.getString("mode")),
            manualLevel = performance.getInt("manualLevel"),
        ).normalized()
        editor.putString(RuntimePerformancePreferenceStore.KEY, RuntimePerformancePreferenceStore.encode(preference))
        mutatedKeys += RuntimePerformancePreferenceStore.KEY
    }
    // Output limit. Uses the same accepted production range as
    // LocalGenerationSettingsPolicy (1..32000): the old `coerceIn(2000, 32000)`
    // silently rewrote the legitimate low-memory value 1024 to 2000.
    val restoredMaxTokens = if (settings.has("maxTokens")) {
        val value = settings.optSaturatingInt("maxTokens", 4000).coerceIn(1, 32000)
        editor.putInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, value)
        mutatedKeys += LocalGenerationSettingsPolicy.KEY_MAX_TOKENS
        value
    } else {
        prefs.getInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, 4000)
    }
    // KV capacity is restored INDEPENDENTLY from the output limit — output and
    // KV must never be re-coupled during restore. Legacy backups without the
    // field derive a safe value through the central policy instead.
    if (settings.has("kvCacheCapacityTokens")) {
        val kv = settings.optSaturatingInt(
            "kvCacheCapacityTokens",
            com.projectnuke.fusion.model.KvCacheCapacityPolicy.DEFAULT_CAPACITY
        ).coerceIn(
            com.projectnuke.fusion.model.KvCacheCapacityPolicy.MIN_CAPACITY,
            com.projectnuke.fusion.model.KvCacheCapacityPolicy.MAX_CAPACITY
        )
        editor.putInt(LocalGenerationSettingsPolicy.KEY_KV_CACHE, kv)
        mutatedKeys += LocalGenerationSettingsPolicy.KEY_KV_CACHE
    } else if (settings.has(LEGACY_PrefKvCache)) {
        // Very old payloads that carried the legacy key name verbatim.
        val kv = settings.optSaturatingInt(
            LEGACY_PrefKvCache,
            com.projectnuke.fusion.model.KvCacheCapacityPolicy.DEFAULT_CAPACITY
        ).coerceIn(
            com.projectnuke.fusion.model.KvCacheCapacityPolicy.MIN_CAPACITY,
            com.projectnuke.fusion.model.KvCacheCapacityPolicy.MAX_CAPACITY
        )
        editor.putInt(LocalGenerationSettingsPolicy.KEY_KV_CACHE, kv)
        mutatedKeys += LocalGenerationSettingsPolicy.KEY_KV_CACHE
    } else if (settings.has("maxTokens")) {
        // Legacy (schema v1) backup: no KV field. Derive a safe value through
        // the central policy so the restored profile is still runnable.
        val derived = LocalGenerationSettingsPolicy.deriveKvCapacityForOutput(restoredMaxTokens)
        editor.putInt(LocalGenerationSettingsPolicy.KEY_KV_CACHE, derived)
        mutatedKeys += LocalGenerationSettingsPolicy.KEY_KV_CACHE
    }
if (settings.has("temperature")) { editor.putFloat(PrefTemperature, settings.optDouble("temperature", 1.0).toFloat().coerceIn(0f, 2f)); mutatedKeys += PrefTemperature }
if (settings.has("topK")) { editor.putInt(PrefTopK, LocalGenerationSettingBounds.clampTopK(settings.optSaturatingInt("topK", 64))); mutatedKeys += PrefTopK }
if (settings.has("topP")) { editor.putFloat(PrefTopP, settings.optDouble("topP", 0.95).toFloat().coerceIn(0f, 1f)); mutatedKeys += PrefTopP }
if (settings.has("mtpEnabled")) { editor.putBoolean("speculative_decoding_enabled", settings.optBoolean("mtpEnabled", false)); mutatedKeys += "speculative_decoding_enabled" }
if (settings.has("reasoningEnabled")) { editor.putBoolean("reasoning_enabled", settings.optBoolean("reasoningEnabled", false)); mutatedKeys += "reasoning_enabled" }
if (settings.has("reasoningBudget")) { editor.putInt("reasoning_budget_tokens", LocalGenerationSettingBounds.clampReasoningBudget(settings.optSaturatingInt("reasoningBudget", 512))); mutatedKeys += "reasoning_budget_tokens" }
    if (settings.has("webSearchEnabled")) { editor.putBoolean("web_search_enabled", settings.optBoolean("webSearchEnabled", false)); mutatedKeys += "web_search_enabled" }
    if (settings.has("themeMode")) {
        val mode = FusionThemeMode.fromStorage(settings.optString("themeMode", ""))
        editor.putString(FusionThemePreferenceKey, mode.storageValue)
        mutatedKeys += FusionThemePreferenceKey
    }
    if (settings.has("appLanguage")) {
        val rawLanguage = settings.optString("appLanguage", FusionAppLanguage.SYSTEM.value)
        val language = FusionAppLanguage.fromValue(rawLanguage)
        editor.putString(PrefFusionAppLanguage, language.value)
        mutatedKeys += PrefFusionAppLanguage
    }
    if (settings.has("savedMemoryContextEnabled")) { editor.putBoolean(PrefSavedMemoryContextEnabled, settings.optBoolean("savedMemoryContextEnabled", false)); mutatedKeys += PrefSavedMemoryContextEnabled }
    if (settings.has("memoryManagerSortMode")) {
        val sortMode = settings.optString("memoryManagerSortMode")
        MemoryManagerSortMode.valueOf(sortMode)
        editor.putString(PrefMemoryManagerSortMode, sortMode)
        mutatedKeys += PrefMemoryManagerSortMode
    }
    if (settings.has("lowMemoryMode")) { restoreNotes += "lowMemoryMode(ignored)" }
    if (schemaVersion <= 2) {
        // Old backups had one effective settings set and no model overrides.
        // Commit its modern authority with the compatibility keys below in one editor.
        val old = LocalGenerationSettingsPolicy.fromPrefs(prefs, materializeMigration = false)
        val legacyProfile = LocalModelSettingsOverride(
            maxOutputTokens = restoredMaxTokens,
            temperature = if (settings.has("temperature")) {
                settings.optDouble("temperature").toFloat().takeIf(LocalGenerationFloatSettings::isValidTemperature) ?: old.temperature
            } else old.temperature,
            topK = if (settings.has("topK")) LocalGenerationSettingBounds.clampTopK(settings.optSaturatingInt("topK")) else old.topK,
            topP = if (settings.has("topP")) {
                settings.optDouble("topP").toFloat().takeIf(LocalGenerationFloatSettings::isValidTopP) ?: old.topP
            } else old.topP,
            reasoningBudgetTokens = if (settings.has("reasoningBudget")) LocalGenerationSettingBounds.clampReasoningBudget(settings.optSaturatingInt("reasoningBudget")) else old.reasoningBudgetTokens,
            reasoningEnabled = if (settings.has("reasoningEnabled")) settings.optBoolean("reasoningEnabled") else prefs.getBoolean(PrefReasoningEnabled, false),
            accelerator = if (settings.has("accelerator")) AcceleratorMode.valueOf(settings.optString("accelerator")) else old.accelerator,
            speculativeDecodingEnabled = if (settings.has("mtpEnabled")) settings.optBoolean("mtpEnabled") else old.speculativeDecodingEnabled ?: false,
            contextMode = LocalContextMode.MANUAL,
            manualKvCapacityTokens = when {
                settings.has("kvCacheCapacityTokens") -> settings.optSaturatingInt("kvCacheCapacityTokens")
                settings.has(LEGACY_PrefKvCache) -> settings.optSaturatingInt(LEGACY_PrefKvCache)
                settings.has("maxTokens") -> LocalGenerationSettingsPolicy.deriveKvCapacityForOutput(restoredMaxTokens)
                else -> old.kvCacheCapacityTokens
            }.coerceIn(com.projectnuke.fusion.model.KvCacheCapacityPolicy.MIN_CAPACITY, com.projectnuke.fusion.model.KvCacheCapacityPolicy.MAX_CAPACITY),
            compactPolicy = LocalCompactPolicy(),
        )
        editor.putString(
            LocalModelSettingsProfileStore.KEY,
            LocalModelSettingsProfileCodec.encode(LocalModelSettingsDocument(globalDefaults = legacyProfile)),
        )
        mutatedKeys += LocalModelSettingsProfileStore.KEY
    }
settings.optJSONObject("localModelSettingsProfiles")?.takeIf { schemaVersion >= 3 }?.let { profileJson ->
        // Strict restore decode: a future inner version or a malformed profile
        // document returns null and must NEVER overwrite the device's current
        // per-model overrides with an empty decode (the forgiving codec maps
        // unknown future versions to an empty document). Retain last-known-good
        // exactly like the model-path retention path above.
        val document = LocalModelSettingsProfileCodec.decodeStrict(profileJson.toString())
        if (document == null) {
            Log.d("FusionModelSelect", "settings_restore localModelSettingsProfiles RETAINED current=true (unsupported version or malformed)")
        } else {
            editor.putString(LocalModelSettingsProfileStore.KEY, LocalModelSettingsProfileCodec.encode(document))
            mutatedKeys += LocalModelSettingsProfileStore.KEY
        }
    }

    val currentSelectedModel = prefs.getString(PrefSelectedModel, "Gemma 4 E2B-it") ?: "Gemma 4 E2B-it"
    val backupModel = settings.optString("selectedModel", currentSelectedModel)
    val hasBackupPath = settings.has("selectedModelPath")
    val backupPath = if (!hasBackupPath || settings.isNull("selectedModelPath")) {
        null
    } else {
        settings.optString("selectedModelPath")
    }
    var modelPathMissing = false
    if (hasBackupPath && !backupPath.isNullOrBlank()) {
        val managedModel = ManagedModelPathPolicy.resolveRunnableModel(context, backupPath)
        if (managedModel != null) {
            editor.putString(PrefSelectedModel, backupModel)
            editor.putString(PrefSelectedModelPath, managedModel.absolutePath)
            mutatedKeys += PrefSelectedModel
            mutatedKeys += PrefSelectedModelPath
        } else {
            modelPathMissing = true
        }
    } else if (hasBackupPath || settings.has("selectedModel")) {
        editor.putString(PrefSelectedModel, backupModel)
        editor.remove(PrefSelectedModelPath)
        mutatedKeys += PrefSelectedModel
        // The remove is a mutation of this key too: it must be snapshotted so a
        // failed commit can restore the exact pre-restore value (or absence).
        mutatedKeys += PrefSelectedModelPath
    }
    modelLibrary.optJSONArray("favorites")?.let { arr ->
        editor.putStringSet(PrefFavoriteModelIds, (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }.toMutableSet())
        mutatedKeys += PrefFavoriteModelIds
    }
    modelLibrary.optJSONArray("hidden")?.let { arr ->
        editor.putStringSet(PrefHiddenModelIds, (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }.toMutableSet())
        mutatedKeys += PrefHiddenModelIds
    }
    modelLibrary.optJSONArray("recent")?.let { arr ->
        // Write through the production recent-model codec so prefs always hold
        // the exact current-schema shape bounded to MaxRecentModels entries.
        val entries = (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            // For current schema, do not manufacture missing fields; validation
            // already guarantees all four writer fields are present.
            if (!isValidRecentEntry(obj, schemaVersion)) return@mapNotNull null
            val id = obj.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val nameValue = obj.opt("name")
            if (nameValue !is String) return@mapNotNull null
            val lastUsedAtValue = obj.opt("lastUsedAt")
            if (!isIntegralValue(lastUsedAtValue)) return@mapNotNull null
            val useCountValue = obj.opt("useCount")
            if (!isIntegralValue(useCountValue)) return@mapNotNull null
            val useCountLong = (useCountValue as Number).toLong()
            if (useCountLong < 1L || useCountLong > Int.MAX_VALUE) return@mapNotNull null
            RecentModelEntry(
                modelId = id,
                displayName = nameValue as String,
                lastUsedAt = (lastUsedAtValue as Number).toLong(),
                useCount = useCountLong.toInt(),
            )
        }
        editor.putString(PrefRecentModels, RecentModelLibraryCodec.encode(entries))
        mutatedKeys += PrefRecentModels
    }
    modelLibrary.optJSONObject("notes")?.let { notes -> editor.putString(PrefModelNotes, notes.toString()); mutatedKeys += PrefModelNotes }
    if (modelLibrary.has("sortMode")) {
        val sortMode = modelLibrary.optString("sortMode", "recommendation")
        editor.putString(PrefModelLibrarySortMode, sortMode)
        mutatedKeys += PrefModelLibrarySortMode
    }
    // Key-scoped durability snapshot of EXACTLY the keys this restore mutates.
    // SharedPreferences.Editor makes mutations process-visible before commit()
    // reports the disk result, so a failed commit must roll this exact key set
    // back: the user is told restore failed, and neither the running process
    // nor disk may keep restored values that were never durably committed.
    val preRestoreSnapshot = prefs.snapshotKeys(mutatedKeys)
    check(editor.expectedValues.keys == mutatedKeys.toSet()) {
        "settings restore mutation tracking diverged from its SharedPreferences editor"
    }
    currentCoroutineContext().ensureActive()
    // Once commit makes the restored values process-visible, settle its disk
    // result and any rollback before cancellation can release restore ownership.
    val committed = withContext(NonCancellable + Dispatchers.IO) {
        editor.commit().also { success ->
            if (!success) prefs.restoreKeysIfUnchanged(preRestoreSnapshot, editor.expectedValues)
        }
    }
    if (!committed) {
        return@withLock SettingsRestoreResult.CommitFailed
    }
    if (modelPathMissing) {
        Log.d("FusionModelSelect", "settings_restore schema=${root.optInt("schemaVersion", -1)} keys=${(mutatedKeys + restoreNotes).joinToString(",")} ModelPathMissing retained_current=true")
        return@withLock SettingsRestoreResult.ModelPathMissing
    }
    Log.d("FusionModelSelect", "settings_restore schema=${root.optInt("schemaVersion", -1)} keys=${(mutatedKeys + restoreNotes).joinToString(",")} success=true")
    return@withLock SettingsRestoreResult.Success
}

/** Records the exact process-visible value each restore edit intends to write. */
private class RestoreTrackingEditor(
    private val delegate: SharedPreferences.Editor,
) : SharedPreferences.Editor {
    val expectedValues = linkedMapOf<String, Any?>()

    override fun putString(key: String, value: String?): SharedPreferences.Editor = record(key, value ?: KeyAbsentInSnapshot) {
        delegate.putString(key, value)
    }

    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
        record(key, values?.toMutableSet() ?: KeyAbsentInSnapshot) { delegate.putStringSet(key, values) }

    override fun putInt(key: String, value: Int): SharedPreferences.Editor = record(key, value) { delegate.putInt(key, value) }
    override fun putLong(key: String, value: Long): SharedPreferences.Editor = record(key, value) { delegate.putLong(key, value) }
    override fun putFloat(key: String, value: Float): SharedPreferences.Editor = record(key, value) { delegate.putFloat(key, value) }
    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = record(key, value) { delegate.putBoolean(key, value) }

    override fun remove(key: String): SharedPreferences.Editor = record(key, KeyAbsentInSnapshot) { delegate.remove(key) }

    override fun clear(): SharedPreferences.Editor {
        delegate.clear()
        return this
    }

    override fun commit(): Boolean = delegate.commit()

    override fun apply() = delegate.apply()

    private inline fun record(key: String, value: Any?, write: () -> Unit): SharedPreferences.Editor {
        write()
        expectedValues[key] = value
        return this
    }
}

/** Preserve a same-key writer that ran after the restore editor became visible. */
private fun SharedPreferences.restoreKeysIfUnchanged(
    beforeRestore: Map<String, Any?>,
    expectedRestoreValues: Map<String, Any?>,
): Boolean {
    val currentValues = snapshotKeys(expectedRestoreValues.keys)
    val unchangedKeys = expectedRestoreValues.keys.filter { key -> currentValues[key] == expectedRestoreValues[key] }
    if (unchangedKeys.isEmpty()) return true
    val rollback = unchangedKeys.associateWith { key ->
        checkNotNull(beforeRestore[key]) { "missing settings rollback snapshot for key: $key" }
    }
    return restoreKeys(rollback)
}

internal fun containsOversizedValue(value: Any?): Boolean = when (value) {
    is JSONObject -> {
        if (value.length() > MaxBackupObjectFields) true
        else value.keys().asSequence().any { key ->
            key.length > 200 || containsOversizedValue(value.opt(key))
        }
    }
    is JSONArray -> {
        if (value.length() > MaxBackupArrayItems) true
        else (0 until value.length()).any { containsOversizedValue(value.opt(it)) }
    }
    is String -> value.length > MaxBackupStringChars
    else -> false
}

// JSON integers may be Longs. Saturate before applying the established bounds:
// Number.intValue() in optInt wraps wide values into unrelated small values.
private fun JSONObject.optSaturatingInt(key: String, fallback: Int = 0): Int =
    optDouble(key, fallback.toDouble()).toInt()

private fun isJsonInteger(objectValue: JSONObject, key: String): Boolean {
    if (!objectValue.has(key)) return true
    val value = objectValue.opt(key)
    return value is Int || value is Long || (value is Double && value.isFinite() && value % 1.0 == 0.0)
}

private fun isJsonNumber(objectValue: JSONObject, key: String): Boolean {
    if (!objectValue.has(key)) return true
    val value = objectValue.opt(key)
    return value is Number && value.toDouble().isFinite()
}

private fun isJsonString(objectValue: JSONObject, key: String, allowNull: Boolean = false): Boolean {
    if (!objectValue.has(key)) return true
    val value = objectValue.opt(key)
    return value is String || (allowNull && value == JSONObject.NULL)
}

private fun isJsonBoolean(objectValue: JSONObject, key: String): Boolean {
    if (!objectValue.has(key)) return true
    return objectValue.opt(key) is Boolean
}

internal fun hasValidBackupFieldTypes(settings: JSONObject, modelLibrary: JSONObject, schemaVersion: Int = FusionSettingsBackupSchemaVersion): Boolean {
    val settingsTypesValid =
        isJsonString(settings, "selectedModel") &&
            isJsonString(settings, "selectedModelPath", allowNull = true) &&
            isJsonString(settings, "accelerator") &&
            isJsonInteger(settings, "maxTokens") &&
            isJsonInteger(settings, "kvCacheCapacityTokens") &&
            isJsonInteger(settings, LEGACY_PrefKvCache) &&
            isJsonNumber(settings, "temperature") &&
            isJsonInteger(settings, "topK") &&
            isJsonNumber(settings, "topP") &&
            isJsonBoolean(settings, "mtpEnabled") &&
            isJsonBoolean(settings, "reasoningEnabled") &&
            isJsonInteger(settings, "reasoningBudget") &&
            isJsonBoolean(settings, "webSearchEnabled") &&
            isJsonString(settings, "themeMode") &&
            isJsonString(settings, "appLanguage") &&
            isJsonBoolean(settings, "savedMemoryContextEnabled") &&
            isJsonString(settings, "memoryManagerSortMode") &&
            (!settings.has("lowMemoryMode") || isJsonBoolean(settings, "lowMemoryMode")) &&
            (!settings.has("runtimePerformance") || settings.opt("runtimePerformance") is JSONObject) &&
            (!settings.has("localModelSettingsProfiles") || settings.opt("localModelSettingsProfiles") is JSONObject)
    // favorites/hidden remain bounded String arrays; recent is validated
    // separately as the current-schema OBJECT array the production writer
    // produces (a String-only recent array was never written by any writer).
    val arraysValid = listOf("favorites", "hidden").all { key ->
        if (!modelLibrary.has(key)) return@all true
        val array = modelLibrary.optJSONArray(key) ?: return@all false
        (0 until array.length()).all { index -> array.opt(index) is String }
    }
    val recentValid = if (!modelLibrary.has("recent")) {
        true
    } else {
        val recent = modelLibrary.optJSONArray("recent") ?: return false
        (0 until recent.length()).all { index -> isValidRecentEntry(recent.optJSONObject(index), schemaVersion) }
    }
    val notesValid = if (!modelLibrary.has("notes")) {
        true
    } else {
        val notes = modelLibrary.optJSONObject("notes") ?: return false
        notes.keys().asSequence().all { key -> notes.opt(key) is String }
    }
    return settingsTypesValid && arraysValid && recentValid && notesValid && isJsonString(modelLibrary, "sortMode")
}

internal fun hasValidBackupSymbolicDomains(settings: JSONObject, modelLibrary: JSONObject): Boolean {
    val acceleratorValid = !settings.has("accelerator") ||
        AcceleratorMode.entries.any { it.name == settings.optString("accelerator") }
    val themeValid = !settings.has("themeMode") ||
        FusionThemeMode.entries.any { it.storageValue == settings.optString("themeMode") }
    val languageValid = !settings.has("appLanguage") ||
        FusionAppLanguage.entries.any { it.value == settings.optString("appLanguage") }
    val memorySortValid = !settings.has("memoryManagerSortMode") ||
        MemoryManagerSortMode.entries.any { it.name == settings.optString("memoryManagerSortMode") }
    val runtimePerformanceValid = if (!settings.has("runtimePerformance")) {
        true
    } else {
        val performance = settings.optJSONObject("runtimePerformance") ?: return false
        performance.length() == 2 &&
            RuntimePerformanceMode.entries.any { it.name == performance.optString("mode") } &&
            isJsonInteger(performance, "manualLevel") &&
            performance.optSaturatingInt("manualLevel", -1) in 0..100
    }
    val modelSortValid = !modelLibrary.has("sortMode") ||
        settingsSortModeIsAllowed(modelLibrary.optString("sortMode"))
    return acceleratorValid && themeValid && languageValid && memorySortValid && runtimePerformanceValid && modelSortValid
}

private fun settingsSortModeIsAllowed(value: String): Boolean = value in AllowedModelLibrarySortModes

/**
 * Recent entry validator.
 *
 * All supported schema versions (v1-v3) were produced by the same writer
 * ([RecentModelLibraryCodec.encode]), which always emits exactly:
 * id (String), name (String), lastUsedAt (integral >= 0), useCount (integral >= 1).
 * The current schema (v3) must not weaken validation to match a weaker format
 * no writer produced; older versions are handled explicitly by passing
 * [schemaVersion] but still enforce the same object shape.
 */
private fun isValidRecentEntry(entry: JSONObject?, schemaVersion: Int): Boolean {
    if (entry == null) return false
    if (entry.length() > MaxBackupObjectFields) return false
    // id is always required and nonblank.
    val id = entry.opt("id")
    if (id !is String || id.isBlank()) return false
    // Current schema requires the full writer contract; older versions also
    // emitted the full shape, so we enforce all four fields for all versions.
    val name = entry.opt("name")
    if (name !is String) return false
    if (!entry.has("lastUsedAt") || entry.isNull("lastUsedAt")) return false
    val lastUsedAtValue = entry.opt("lastUsedAt")
    if (!isIntegralValue(lastUsedAtValue) || (lastUsedAtValue as Number).toLong() < 0L) return false
    if (!entry.has("useCount") || entry.isNull("useCount")) return false
    val useCountValue = entry.opt("useCount")
    if (!isIntegralValue(useCountValue)) return false
    val useCountLong = (useCountValue as Number).toLong()
    if (useCountLong < 1L || useCountLong > Int.MAX_VALUE) return false
    return true
}

private fun isIntegralValue(value: Any?): Boolean =
    value is Int || value is Long || (value is Double && value.isFinite() && value % 1.0 == 0.0)
