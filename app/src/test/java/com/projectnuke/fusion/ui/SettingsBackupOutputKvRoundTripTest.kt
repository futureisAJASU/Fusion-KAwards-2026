package com.projectnuke.fusion.ui

import android.content.Context
import android.content.SharedPreferences
import com.projectnuke.fusion.model.KvCacheCapacityPolicy
import com.projectnuke.fusion.model.LocalGenerationSettingsPolicy
import com.projectnuke.fusion.model.LocalContextMode
import com.projectnuke.fusion.model.LocalModelSettingsOverride
import com.projectnuke.fusion.model.LocalModelSettingsProfileCodec
import com.projectnuke.fusion.model.LocalModelSettingsProfileStore
import com.projectnuke.fusion.ui.model.MaxRecentModels
import com.projectnuke.fusion.ui.model.RecentModelLibraryCodec
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import com.projectnuke.fusion.ui.theme.FusionThemePreferenceKey
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 regression: the authoritative KV preference
 * (`engine_kv_cache_capacity_tokens`) must be part of the settings backup
 * schema and restored INDEPENDENTLY from the output limit.
 *
 * Also asserts that restore uses the same accepted production range/policy as
 * [LocalGenerationSettingsPolicy] — the old `coerceIn(2000, 32000)` silently
 * rewrote the legitimate low-memory value 1024 to 2000.
 */
class SettingsBackupOutputKvRoundTripTest {
    @Test
    fun `legacy backups replace modern profile and discard target model overrides`() = runTest {
        for (schema in listOf(1, 2)) {
            val target = InMemoryPrefs()
            val store = LocalModelSettingsProfileStore(target)
            assertTrue(store.writeGlobalDefaults(LocalModelSettingsOverride(maxOutputTokens = 4096, reasoningEnabled = true)))
            assertTrue(store.writeModelOverride("model-a", LocalModelSettingsOverride(maxOutputTokens = 8192, reasoningEnabled = true)))
            val legacy = JSONObject()
                .put("maxTokens", 1024)
                .put("temperature", 0.4)
                .put("topK", 32)
                .put("topP", 0.8)
                .put("reasoningEnabled", false)
                .put("reasoningBudget", 256)
                .put("mtpEnabled", true)
                .put("accelerator", "CPU")
            if (schema == 2) legacy.put("kvCacheCapacityTokens", 6144)
            assertEquals(SettingsRestoreResult.Success,
                restoreSettingsBackupJson(TestContext(), target, backupPayload(schemaVersion = schema, settings = legacy)))
            val restored = store.read()
            assertTrue(restored.modelOverrides.isEmpty())
            val effective = com.projectnuke.fusion.model.LocalModelSettingsResolver.resolve(
                "model-a", globalDefaults = restored.globalDefaults,
                perModelOverride = restored.modelOverrides["model-a"],
            )
            assertEquals(1024, effective.generationSettings.maxTokens)
            assertFalse(effective.reasoningEnabled)
            assertEquals(32, effective.generationSettings.topK)
            assertEquals(0.4f, effective.generationSettings.temperature, 0f)
            assertTrue(effective.generationSettings.speculativeDecodingEnabled == true)
            assertEquals(if (schema == 2) 6144 else LocalGenerationSettingsPolicy.deriveKvCapacityForOutput(1024), effective.effectiveKvCapacityTokens)
        }
    }


    private class TestContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    private class InMemoryPrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = InMemoryEditor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}

        private inner class InMemoryEditor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removals += key }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                if (clearAll) map.clear()
                removals.forEach { map.remove(it) }
                map.putAll(pending)
                return true
            }
            override fun apply() { commit() }
        }
    }

    private fun prefsWith(output: Int, kv: Int): InMemoryPrefs = InMemoryPrefs().apply {
        map[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS] = output
        map[LocalGenerationSettingsPolicy.KEY_KV_CACHE] = kv
    }

    private fun prefsWithLocalGenerationBounds(topK: Int, reasoningBudget: Int): InMemoryPrefs = InMemoryPrefs().apply {
        map[LocalGenerationSettingsPolicy.KEY_TOP_K] = topK
        map[LocalGenerationSettingsPolicy.KEY_REASONING_BUDGET] = reasoningBudget
    }

    private fun backupPayload(
        schemaVersion: Int = FusionSettingsBackupSchemaVersion,
        settings: JSONObject = JSONObject(),
        modelLibrary: JSONObject = JSONObject(),
    ): String = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("app", "Fusion")
        .put("settings", settings)
        .put("modelLibrary", modelLibrary)
        .toString()

    @Test
    fun `valid symbolic domains restore through their existing authorities`() = runTest {
        val target = InMemoryPrefs()
        val settings = JSONObject()
            .put("accelerator", "CPU")
            .put("themeMode", FusionThemeMode.DARK.storageValue)
            .put("appLanguage", "ko")
            .put("memoryManagerSortMode", MemoryManagerSortMode.UPDATED_DESC.name)
        val modelLibrary = JSONObject().put("sortMode", "name")

        assertEquals(
            SettingsRestoreResult.Success,
            restoreSettingsBackupJson(TestContext(), target, backupPayload(settings = settings, modelLibrary = modelLibrary)),
        )
        assertEquals("CPU", target.getString("accelerator", null))
        assertEquals(FusionThemeMode.DARK.storageValue, target.getString(FusionThemePreferenceKey, null))
        assertEquals("ko", target.getString("fusion_app_language", null))
        assertEquals(MemoryManagerSortMode.UPDATED_DESC.name, target.getString(PrefMemoryManagerSortMode, null))
        assertEquals("name", target.getString("model_library_sort_mode", null))
    }

    @Test
    fun `all supported stored app language values remain accepted`() = runTest {
        listOf("system", "ko", "en").forEach { language ->
            val target = InMemoryPrefs()
            assertEquals(
                SettingsRestoreResult.Success,
                restoreSettingsBackupJson(
                    TestContext(),
                    target,
                    backupPayload(settings = JSONObject().put("appLanguage", language)),
                ),
            )
            assertEquals(language, target.getString("fusion_app_language", null))
        }
    }

    @Test
    fun `unknown symbolic domains fail before any preference commit`() = runTest {
        val cases = listOf(
            "accelerator" to "BROKEN",
            "themeMode" to "neon",
            "appLanguage" to "fr",
            "memoryManagerSortMode" to "BROKEN",
        )
        cases.forEach { (field, value) ->
            val target = InMemoryPrefs().apply { map["sentinel"] = "unchanged" }
            val before = target.map.toMap()
            val result = restoreSettingsBackupJson(
                TestContext(),
                target,
                backupPayload(settings = JSONObject().put(field, value)),
            )
            assertEquals("field=$field", SettingsRestoreResult.InvalidFields, result)
            assertEquals("field=$field", before, target.map)
        }

        val target = InMemoryPrefs().apply { map["sentinel"] = "unchanged" }
        val before = target.map.toMap()
        assertEquals(
            SettingsRestoreResult.InvalidFields,
            restoreSettingsBackupJson(
                TestContext(),
                target,
                backupPayload(modelLibrary = JSONObject().put("sortMode", "BROKEN")),
            ),
        )
        assertEquals(before, target.map)
    }

    @Test
    fun `one invalid symbolic field prevents unrelated settings from partially committing`() = runTest {
        val target = InMemoryPrefs().apply { map["sentinel"] = "unchanged" }
        val before = target.map.toMap()
        val settings = JSONObject()
            .put("accelerator", "BROKEN")
            .put("themeMode", FusionThemeMode.DARK.storageValue)
            .put("maxTokens", 1234)

        assertEquals(
            SettingsRestoreResult.InvalidFields,
            restoreSettingsBackupJson(TestContext(), target, backupPayload(settings = settings)),
        )
        assertEquals(before, target.map)
    }

    @Test
    fun `missing symbolic fields remain accepted`() = runTest {
        val target = InMemoryPrefs()
        assertEquals(
            SettingsRestoreResult.Success,
            restoreSettingsBackupJson(TestContext(), target, backupPayload()),
        )
    }

    // ── round trips ───────────────────────────────────────────────────────

    @Test
    fun `output 1024 and KV 4096 survive an exact backup restore round trip`() = runTest {
        val context = TestContext()
        val source = prefsWith(output = 1024, kv = 4096)
        val json = buildSettingsBackupJson(context, source)

        val target = InMemoryPrefs()
        val result = restoreSettingsBackupJson(context, target, json)
        assertEquals(SettingsRestoreResult.Success, result)

        assertEquals(
            "output=1024 must NOT be clamped up to 2000",
            1024,
            target.getInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, -1),
        )
        assertEquals(
            "KV must be restored from the backup, not derived from the output",
            4096,
            target.getInt(LocalGenerationSettingsPolicy.KEY_KV_CACHE, -1),
        )
    }

    @Test
    fun `output 2048 and KV 8192 survive an exact backup restore round trip`() = runTest {
        val context = TestContext()
        val source = prefsWith(output = 2048, kv = 8192)
        val json = buildSettingsBackupJson(context, source)

        val target = InMemoryPrefs()
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, target, json))
        assertEquals(2048, target.getInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, -1))
        assertEquals(8192, target.getInt(LocalGenerationSettingsPolicy.KEY_KV_CACHE, -1))
    }

    @Test
    fun `KV is restored independently and is not re-derived from the output limit`() = runTest {
        val context = TestContext()
        // Deliberately choose a KV that the "capacity = 2 * output" heuristic
        // would NOT produce, so a re-coupled restore would be detected.
        val source = prefsWith(output = 4000, kv = 16384)
        val json = buildSettingsBackupJson(context, source)

        val target = InMemoryPrefs()
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, target, json))
        assertEquals(4000, target.getInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, -1))
        assertEquals(
            "restore must not re-couple KV to output (2*4000=8000 would be wrong)",
            16384,
            target.getInt(LocalGenerationSettingsPolicy.KEY_KV_CACHE, -1),
        )
    }

    @Test
    fun `valid local generation boundary values survive backup restore`() = runTest {
        val context = TestContext()
        listOf(1 to 1, 100 to 8192).forEach { (topK, reasoningBudget) ->
            val source = prefsWithLocalGenerationBounds(topK, reasoningBudget)
            val json = buildSettingsBackupJson(context, source)
            val target = InMemoryPrefs()

            assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, target, json))
            assertEquals(topK, target.getInt(LocalGenerationSettingsPolicy.KEY_TOP_K, -1))
            assertEquals(
                reasoningBudget,
                target.getInt(LocalGenerationSettingsPolicy.KEY_REASONING_BUDGET, -1),
            )
        }
    }

    @Test
    fun `out of range local generation values use the shared restore bounds`() = runTest {
        val source = prefsWithLocalGenerationBounds(topK = -10, reasoningBudget = 99999)
        val json = buildSettingsBackupJson(TestContext(), source)
        val target = InMemoryPrefs()

        assertEquals(
            SettingsRestoreResult.Success,
            restoreSettingsBackupJson(TestContext(), target, json),
        )
        assertEquals(1, target.getInt(LocalGenerationSettingsPolicy.KEY_TOP_K, -1))
        assertEquals(8192, target.getInt(LocalGenerationSettingsPolicy.KEY_REASONING_BUDGET, -1))
    }

    // ── backup payload shape ─────────────────────────────────────────────

    @Test
    fun `backup payload includes the authoritative KV field`() {
        val json = buildSettingsBackupJson(TestContext(), prefsWith(output = 1024, kv = 2048))
        val settings = JSONObject(json).getJSONObject("settings")
        assertTrue("backup must carry the KV capacity", settings.has("kvCacheCapacityTokens"))
        assertEquals(2048, settings.getInt("kvCacheCapacityTokens"))
        assertEquals(1024, settings.getInt("maxTokens"))
    }

    @Test
    fun `malformed optional model-library metadata does not abort export or mutate preferences`() {
        val source = prefsWith(output = 2048, kv = 8192).apply {
            map["recent_models"] = "not-json"
            map["model_notes"] = "{broken"
        }
        val before = source.map.toMap()

        val json = JSONObject(buildSettingsBackupJson(TestContext(), source))
        val library = json.getJSONObject("modelLibrary")

        assertEquals(0, library.getJSONArray("recent").length())
        assertEquals(0, library.getJSONObject("notes").length())
        assertEquals(before, source.map)
        assertEquals(2048, json.getJSONObject("settings").getInt("maxTokens"))
        assertEquals(8192, json.getJSONObject("settings").getInt("kvCacheCapacityTokens"))
    }

    @Test
    fun `oversized optional metadata is bounded in export and accepted by restore`() = runTest {
        val recent = (0 until 300).joinToString(prefix = "[", postfix = "]") { index ->
            "{\"id\":\"model-$index\",\"name\":\"${"x".repeat(40)}\",\"lastUsedAt\":${1_000L + index},\"useCount\":${index + 1}}"
        }
        val notes = JSONObject().put("odd", "y".repeat(9_000)).toString()
        val source = prefsWith(output = 2048, kv = 8192).apply {
            map["recent_models"] = recent
            map["model_notes"] = notes
        }
        val before = source.map.toMap()

        val json = buildSettingsBackupJson(TestContext(), source)
        val library = JSONObject(json).getJSONObject("modelLibrary")

        // Bounded to the production recent-model bound, newest first.
        assertEquals(10, library.getJSONArray("recent").length())
        assertEquals(
            "model-63",
            library.getJSONArray("recent").getJSONObject(0).getString("id"),
        )
        // Oversized note omitted, never emitted un-restorable.
        assertEquals(0, library.getJSONObject("notes").length())
        // Export is read-only: source prefs untouched.
        assertEquals(before, source.map)
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= MaxSettingsBackupBytes)

        val target = InMemoryPrefs()
        assertEquals(
            "bounded export must be restorable",
            SettingsRestoreResult.Success,
            restoreSettingsBackupJson(TestContext(), target, json),
        )
        val restored = target.getString("recent_models", null) ?: "[]"
        assertEquals(10, JSONArray(restored).length())
    }

    @Test
    fun `oversized recent entry string is omitted from export and remains restorable`() = runTest {
        val recent = JSONArray().put(
            JSONObject()
                .put("id", "model-huge-name")
                .put("name", "y".repeat(9_000))
                .put("lastUsedAt", 5_000L)
                .put("useCount", 3)
        ).put(
            JSONObject()
                .put("id", "model-normal")
                .put("name", "Normal")
                .put("lastUsedAt", 4_000L)
                .put("useCount", 2)
        ).toString()
        val source = prefsWith(output = 2048, kv = 8192).apply {
            map["recent_models"] = recent
        }
        val before = source.map.toMap()

        val json = buildSettingsBackupJson(TestContext(), source)
        val library = JSONObject(json).getJSONObject("modelLibrary")

        assertEquals(1, library.getJSONArray("recent").length())
        assertEquals("model-normal", library.getJSONArray("recent").getJSONObject(0).getString("id"))
        assertEquals(before, source.map)

        val target = InMemoryPrefs()
        assertEquals(
            SettingsRestoreResult.Success,
            restoreSettingsBackupJson(TestContext(), target, json),
        )
        assertEquals(1, JSONArray(target.getString("recent_models", "[]")).length())
    }

    @Test
    fun `malformed recent object field type produces no un-restorable export output`() = runTest {
        // Writer-impossible entry: useCount cannot be a string, and a numeric
        // id must be normalized to a String by the export codec.
        val recent = JSONArray()
            .put(JSONObject().put("id", "model-a").put("name", "A").put("lastUsedAt", 1L).put("useCount", "two"))
            .put(JSONObject().put("id", 7).put("name", "Numeric").put("lastUsedAt", 2L).put("useCount", 1))
            .toString()
        val source = prefsWith(output = 2048, kv = 8192).apply {
            map["recent_models"] = recent
        }

        val json = buildSettingsBackupJson(TestContext(), source)
        val library = JSONObject(json).getJSONObject("modelLibrary")
        val restored = library.getJSONArray("recent")

        // Strict runtime durable-row validation skips both malformed rows.
        assertEquals(0, restored.length())

        val target = InMemoryPrefs()
        assertEquals(
            SettingsRestoreResult.Success,
            restoreSettingsBackupJson(TestContext(), target, json),
        )
    }

    @Test
    fun `pathological favorites and hidden sets are bounded in export`() = runTest {
        val source = prefsWith(output = 2048, kv = 8192).apply {
            map["favorite_model_ids"] = (0 until 500).map { "fav-$it" }.toMutableSet()
            map["hidden_model_ids"] = (0 until 500).map { "hid-$it" }.toMutableSet()
        }
        val json = buildSettingsBackupJson(TestContext(), source)
        val library = JSONObject(json).getJSONObject("modelLibrary")

        assertTrue(library.getJSONArray("favorites").length() <= MaxBackupArrayItems)
        assertTrue(library.getJSONArray("hidden").length() <= MaxBackupArrayItems)

        val target = InMemoryPrefs()
        assertEquals(
            SettingsRestoreResult.Success,
            restoreSettingsBackupJson(TestContext(), target, json),
        )
    }

    // ── current-schema round trip (realistic production contract) ────────

    @Test
    fun `realistic current-schema backup round trips with recent objects favorites hidden notes and sort mode`() = runTest {
        val context = TestContext()
        val source = prefsWith(output = 2048, kv = 8192).apply {
            map["favorite_model_ids"] = setOf("catalog-gemma-e2b", "catalog-qwen").toMutableSet()
            map["hidden_model_ids"] = setOf("catalog-deepseek").toMutableSet()
            map["model_notes"] = JSONObject().put("catalog-gemma-e2b", "빠르고 가벼움").toString()
            map["model_library_sort_mode"] = "name"
        }
        // Realistic recent_models OBJECT array exactly as the production writer emits.
        RecentModelLibraryCodec.record(source, "catalog-qwen", "Qwen 3 1.7B")
        RecentModelLibraryCodec.record(source, "catalog-gemma-e2b", "Gemma 4 E2B-it")
        RecentModelLibraryCodec.record(source, "catalog-kanana", "Kanana 2 1.3B")

        val json = buildSettingsBackupJson(context, source)

        val target = InMemoryPrefs()
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, target, json))

        // Semantic equality for supported fields.
        assertEquals(2048, target.getInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, -1))
        assertEquals(8192, target.getInt(LocalGenerationSettingsPolicy.KEY_KV_CACHE, -1))
        assertEquals(setOf("catalog-gemma-e2b", "catalog-qwen"), target.getStringSet("favorite_model_ids", mutableSetOf<String>()))
        assertEquals(setOf("catalog-deepseek"), target.getStringSet("hidden_model_ids", mutableSetOf<String>()))
        assertEquals("name", target.getString("model_library_sort_mode", null))

        val restoredRecent = RecentModelLibraryCodec.load(target)
        assertEquals(
            RecentModelLibraryCodec.load(source).map { it.modelId },
            restoredRecent.map { it.modelId },
        )
        assertTrue(restoredRecent.size in 1..MaxRecentModels)
        assertEquals("catalog-kanana", restoredRecent.first().modelId)

        val restoredNotes = JSONObject(target.getString("model_notes", "{}") ?: "{}")
        assertEquals("빠르고 가벼움", restoredNotes.optString("catalog-gemma-e2b"))
    }

    @Test
    fun `exactly MaxRecentModels realistic recent entries round trip`() = runTest {
        val context = TestContext()
        val source = prefsWith(output = 2048, kv = 8192)
        repeat(MaxRecentModels) { index ->
            RecentModelLibraryCodec.record(source, "catalog-model-$index", "Model $index")
        }

        val json = buildSettingsBackupJson(context, source)
        assertEquals(
            MaxRecentModels,
            JSONObject(json).getJSONObject("modelLibrary").getJSONArray("recent").length(),
        )

        val target = InMemoryPrefs()
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, target, json))
        assertEquals(MaxRecentModels, RecentModelLibraryCodec.load(target).size)
        assertEquals("catalog-model-9", RecentModelLibraryCodec.load(target).first().modelId)
    }

    @Test
    fun `valid current-schema backup emitted by export passes the restore field validator`() = runTest {
        val context = TestContext()
        val source = prefsWith(output = 2048, kv = 8192).apply {
            map["favorite_model_ids"] = setOf("catalog-gemma-e2b")
            map["hidden_model_ids"] = setOf("catalog-deepseek")
            map["model_notes"] = JSONObject().put("catalog-gemma-e2b", "note").toString()
            map["model_library_sort_mode"] = "recommendation"
        }
        RecentModelLibraryCodec.record(source, "catalog-qwen", "Qwen 3 1.7B")

        val json = buildSettingsBackupJson(context, source)
        val root = JSONObject(json)

        assertTrue(json.toByteArray(Charsets.UTF_8).size <= MaxSettingsBackupBytes)
        assertTrue(
            hasValidBackupFieldTypes(
                root.getJSONObject("settings"),
                root.getJSONObject("modelLibrary"),
            ),
        )
        assertEquals(
            SettingsRestoreResult.Success,
            restoreSettingsBackupJson(context, InMemoryPrefs(), json),
        )
    }

    @Test
    fun `export never materializes migration keys`() {
        val context = TestContext()
        // Legacy-only prefs: a materializing export would write the authoritative
        // keys and/or the migration state marker.
        val source = InMemoryPrefs().apply {
            map["max_tokens"] = 1024
            map["kv_cache_capacity_tokens"] = 2048
        }
        val before = source.map.toMap()

        buildSettingsBackupJson(context, source)

        assertEquals(before, source.map)
        assertTrue(!source.map.containsKey(LocalGenerationSettingsPolicy.KEY_SETTINGS_MIGRATION_STATE))
        assertTrue(!source.map.containsKey(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS))
        assertTrue(!source.map.containsKey(LocalGenerationSettingsPolicy.KEY_KV_CACHE))
    }

    @Test
    fun `every bounded export emitted from supported prefs is acceptable to the restore parser`() = runTest {
        val context = TestContext()
        // A supported/readable source exercising every model-library field.
        val source = prefsWith(output = 2048, kv = 8192).apply {
            map["favorite_model_ids"] = setOf("catalog-gemma-e2b", "catalog-qwen", "catalog-kanana")
            map["hidden_model_ids"] = setOf("catalog-kimi")
            map["model_notes"] = JSONObject()
                .put("catalog-gemma-e2b", "note-a")
                .put("catalog-qwen", "note-b")
                .toString()
            map["model_library_sort_mode"] = "favorites_first"
        }
        repeat(MaxRecentModels) { index ->
            RecentModelLibraryCodec.record(source, "catalog-model-$index", "Model $index")
        }
        LocalModelSettingsProfileStore(source).writeGlobalDefaults(
            LocalModelSettingsOverride(contextMode = LocalContextMode.MANUAL, manualKvCapacityTokens = 4096)
        )
        LocalModelSettingsProfileStore(source).writeModelOverride(
            "catalog-gemma-e2b",
            LocalModelSettingsOverride(maxOutputTokens = 3072),
        )

        val json = buildSettingsBackupJson(context, source)
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= MaxSettingsBackupBytes)

        val target = InMemoryPrefs()
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, target, json))
        assertEquals(2048, target.getInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, -1))
        val restoredProfile = LocalModelSettingsProfileCodec.decode(
            target.getString(LocalModelSettingsProfileStore.KEY, null),
        )
        assertEquals(3072, restoredProfile.modelOverrides["catalog-gemma-e2b"]?.maxOutputTokens)
        assertEquals(4096, restoredProfile.globalDefaults?.manualKvCapacityTokens)
    }

    @Test
    fun `theme preference round trips and validates storage values`() = runTest {
        val context = TestContext()
        val source = prefsWith(output = 2048, kv = 8192).apply {
            map[FusionThemePreferenceKey] = FusionThemeMode.DARK.storageValue
        }
        val json = buildSettingsBackupJson(context, source)
        val target = prefsWith(output = 2048, kv = 8192).apply {
            map[FusionThemePreferenceKey] = FusionThemeMode.LIGHT.storageValue
        }
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, target, json))
        assertEquals(FusionThemeMode.DARK.storageValue, target.getString(FusionThemePreferenceKey, null))
    }

    @Test
    fun `legacy backup without theme preserves current theme`() = runTest {
        val context = TestContext()
        val legacy = JSONObject()
            .put("schemaVersion", 3)
            .put("app", "Fusion")
            .put("settings", JSONObject().put("maxTokens", 2048))
            .put("modelLibrary", JSONObject())
            .toString()
        val target = prefsWith(output = 2048, kv = 8192).apply {
            map[FusionThemePreferenceKey] = FusionThemeMode.LIGHT.storageValue
        }
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, target, legacy))
        assertEquals(FusionThemeMode.LIGHT.storageValue, target.getString(FusionThemePreferenceKey, null))
    }

    @Test
    fun `profile defaults and stable model override survive backup restore`() = runTest {
        val context = TestContext()
        val source = prefsWith(output = 2048, kv = 8192)
        LocalModelSettingsProfileStore(source).writeGlobalDefaults(
            LocalModelSettingsOverride(
                contextMode = LocalContextMode.MANUAL,
                manualKvCapacityTokens = 8192,
            )
        )
        LocalModelSettingsProfileStore(source).writeModelOverride(
            "catalog-gemma-e2b",
            LocalModelSettingsOverride(maxOutputTokens = 3072),
        )

        val target = InMemoryPrefs()
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, target, buildSettingsBackupJson(context, source)))
        val restored = LocalModelSettingsProfileCodec.decode(
            target.getString(LocalModelSettingsProfileStore.KEY, null)!!
        )
        assertEquals(8192, restored.globalDefaults?.manualKvCapacityTokens)
        assertEquals(3072, restored.modelOverrides["catalog-gemma-e2b"]?.maxOutputTokens)
    }

    // ── legacy compatibility ─────────────────────────────────────────────

    @Test
    fun `legacy v1 backup without the KV field remains readable and derives KV via the central policy`() = runTest {
        val context = TestContext()
        val legacy = JSONObject()
            .put("schemaVersion", 1)
            .put("app", "Fusion")
            .put("exportedAt", 0L)
            .put(
                "settings",
                JSONObject()
                    .put("selectedModel", "Gemma 4 E2B-it")
                    .put("accelerator", "GPU")
                    .put("maxTokens", 1024)
            )
            .put("modelLibrary", JSONObject())
            .toString()

        val target = InMemoryPrefs()
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, target, legacy))
        assertEquals("legacy output must be preserved verbatim", 1024, target.getInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, -1))
        assertEquals(
            "legacy KV must come from the central policy",
            LocalGenerationSettingsPolicy.deriveKvCapacityForOutput(1024),
            target.getInt(LocalGenerationSettingsPolicy.KEY_KV_CACHE, -1),
        )
    }

    @Test
    fun `current schema version payload is accepted`() = runTest {
        val context = TestContext()
        val json = buildSettingsBackupJson(context, prefsWith(output = 2048, kv = 8192))
        assertEquals(FusionSettingsBackupSchemaVersion, JSONObject(json).getInt("schemaVersion"))
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, InMemoryPrefs(), json))
    }

    @Test
    fun `future schema version payload is rejected as unsupported`() = runTest {
        val context = TestContext()
        val future = JSONObject()
            .put("schemaVersion", 99)
            .put("app", "Fusion")
            .put("settings", JSONObject().put("maxTokens", 1024))
            .toString()
        assertEquals(
            SettingsRestoreResult.UnsupportedSchema,
            restoreSettingsBackupJson(context, InMemoryPrefs(), future),
        )
    }

    @Test
    fun `present primitive fields with impossible types are rejected`() = runTest {
        val context = TestContext()
        val malformed = JSONObject()
            .put("schemaVersion", 3)
            .put("app", "Fusion")
            .put("settings", JSONObject().put("maxTokens", "4000"))
            .put("modelLibrary", JSONObject())
            .toString()
        assertEquals(SettingsRestoreResult.InvalidFields, restoreSettingsBackupJson(context, InMemoryPrefs(), malformed))
    }

    // ── nested profile-document retention (future/malformed inner version) ─

    private fun backupWithProfiles(profileJson: JSONObject): String = JSONObject()
        .put("schemaVersion", 3)
        .put("app", "Fusion")
        .put("settings", JSONObject().put("localModelSettingsProfiles", profileJson))
        .put("modelLibrary", JSONObject())
        .toString()

    @Test
    fun `future inner profile version retains current overrides instead of wiping them`() = runTest {
        val context = TestContext()
        val existing = LocalModelSettingsProfileCodec.encode(
            LocalModelSettingsProfileCodec.decode(
                """{"version":1,"models":{"catalog-gemma":{"maxOutputTokens":2048}}}""",
            ),
        )
        val target = InMemoryPrefs().apply { map[LocalModelSettingsProfileStore.KEY] = existing }

        val futureProfile = JSONObject()
            .put("version", 99)
            .put("models", JSONObject().put("catalog-other", JSONObject().put("maxOutputTokens", 512)))
        assertEquals(
            SettingsRestoreResult.Success,
            restoreSettingsBackupJson(context, target, backupWithProfiles(futureProfile)),
        )
        // Last-known-good must survive: no empty-document overwrite.
        assertEquals(existing, target.getString(LocalModelSettingsProfileStore.KEY, null))
        val decoded = LocalModelSettingsProfileCodec.decode(target.getString(LocalModelSettingsProfileStore.KEY, null))
        assertEquals(2048, decoded.modelOverrides["catalog-gemma"]?.maxOutputTokens)
    }

    @Test
    fun `malformed inner profile document retains current overrides`() = runTest {
        val context = TestContext()
        val existing = LocalModelSettingsProfileCodec.encode(
            LocalModelSettingsProfileCodec.decode(
                """{"version":1,"models":{"catalog-gemma":{"maxOutputTokens":3072}}}""",
            ),
        )
        val target = InMemoryPrefs().apply { map[LocalModelSettingsProfileStore.KEY] = existing }

        // Inner "models" maps an id to a non-object value -> strict decode null.
        val malformedProfile = JSONObject().put("version", 1).put("models", JSONObject().put("catalog-x", "not-an-object"))
        assertEquals(
            SettingsRestoreResult.Success,
            restoreSettingsBackupJson(context, target, backupWithProfiles(malformedProfile)),
        )
        assertEquals(existing, target.getString(LocalModelSettingsProfileStore.KEY, null))
    }

    @Test
    fun `valid inner profile document still restores normally`() = runTest {
        val context = TestContext()
        val profile = JSONObject()
            .put("version", 1)
            .put("models", JSONObject().put("catalog-restored", JSONObject().put("maxOutputTokens", 4096)))
        val target = InMemoryPrefs()
        assertEquals(
            SettingsRestoreResult.Success,
            restoreSettingsBackupJson(context, target, backupWithProfiles(profile)),
        )
        val decoded = LocalModelSettingsProfileCodec.decode(target.getString(LocalModelSettingsProfileStore.KEY, null))
        assertEquals(4096, decoded.modelOverrides["catalog-restored"]?.maxOutputTokens)
    }

    @Test
    fun `current schema omitted optional profile retains target authority`() = runTest {
        val target = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(target)
        assertTrue(store.writeGlobalDefaults(LocalModelSettingsOverride(maxOutputTokens = 4096, reasoningEnabled = true)))
        assertTrue(store.writeModelOverride("catalog-gemma", LocalModelSettingsOverride(maxOutputTokens = 8192)))
        val before = target.getString(LocalModelSettingsProfileStore.KEY, null)
        assertEquals(
            SettingsRestoreResult.Success,
            restoreSettingsBackupJson(
                TestContext(), target,
                backupPayload(schemaVersion = 3, settings = JSONObject().put("maxTokens", 1024)),
            ),
        )
        assertEquals(before, target.getString(LocalModelSettingsProfileStore.KEY, null))
        assertEquals(8192, store.read().modelOverrides["catalog-gemma"]?.maxOutputTokens)
    }

    // ── policy range parity ──────────────────────────────────────────────

    @Test
    fun `restored KV is clamped to the central policy range`() = runTest {
        val context = TestContext()
        val tooSmall = JSONObject()
            .put("schemaVersion", 2)
            .put("app", "Fusion")
            .put("settings", JSONObject().put("maxTokens", 1024).put("kvCacheCapacityTokens", 1))
            .toString()
        val target = InMemoryPrefs()
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, target, tooSmall))
        assertEquals(
            KvCacheCapacityPolicy.MIN_CAPACITY,
            target.getInt(LocalGenerationSettingsPolicy.KEY_KV_CACHE, -1),
        )

        val tooLarge = JSONObject()
            .put("schemaVersion", 2)
            .put("app", "Fusion")
            .put("settings", JSONObject().put("maxTokens", 1024).put("kvCacheCapacityTokens", 999_999))
            .toString()
        val target2 = InMemoryPrefs()
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, target2, tooLarge))
        assertEquals(
            KvCacheCapacityPolicy.MAX_CAPACITY,
            target2.getInt(LocalGenerationSettingsPolicy.KEY_KV_CACHE, -1),
        )
    }

    @Test
    fun `restored output uses the production range and does not force a 2000 minimum`() = runTest {
        val context = TestContext()
        for (output in listOf(1, 512, 1024, 1999, 2000, 4000, 32000)) {
            val json = JSONObject()
                .put("schemaVersion", 2)
                .put("app", "Fusion")
                .put("settings", JSONObject().put("maxTokens", output).put("kvCacheCapacityTokens", 4096))
                .toString()
            val target = InMemoryPrefs()
            assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, target, json))
            assertEquals(
                "output=$output must round trip without a 2000 floor",
                output,
                target.getInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, -1),
            )
        }
    }

    // ── favorites/hidden string-bound gap (item 1) ──────────────────────

    @Test
    fun `oversized favorite and hidden ids are bounded out but neighbors survive`() = runTest {
        val source = prefsWith(output = 2048, kv = 8192).apply {
            map["favorite_model_ids"] = setOf("valid-fav", "x".repeat(10_000), "another-valid").toMutableSet()
            map["hidden_model_ids"] = setOf("hidden-ok", "y".repeat(10_000)).toMutableSet()
        }
        val before = source.map.toMap()
        val json = buildSettingsBackupJson(TestContext(), source)
        val library = JSONObject(json).getJSONObject("modelLibrary")
        val favorites = library.getJSONArray("favorites")
        val hidden = library.getJSONArray("hidden")
        assertTrue("oversized favorite excluded", favorites.length() == 2)
        assertTrue("oversized hidden excluded", hidden.length() == 1)
        assertTrue("valid favorite survives", favorites.toString().contains("valid-fav"))
        assertTrue("valid hidden survives", hidden.toString().contains("hidden-ok"))
        assertEquals(before, source.map)
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= MaxSettingsBackupBytes)
        val target = InMemoryPrefs()
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(TestContext(), target, json))
    }

    // ── recent entry validity (item 2) ────────────────────────────────────

    @Test
    fun `corrupt recent entries are omitted from export by pure validity policy`() = runTest {
        val source = prefsWith(output = 2048, kv = 8192)
        val recent = JSONArray()
            .put(JSONObject().put("id", "good").put("name", "Good").put("lastUsedAt", 1_000L).put("useCount", 2))
            .put(JSONObject().put("id", "bad-neg-time").put("name", "Bad").put("lastUsedAt", -1L).put("useCount", 1))
            .put(JSONObject().put("id", "bad-zero-count").put("name", "Bad").put("lastUsedAt", 500L).put("useCount", 0))
            .put(JSONObject().put("id", "bad-oversized-id").put("name", "x".repeat(10_000)).put("lastUsedAt", 500L).put("useCount", 1))
            .put(JSONObject().put("id", "bad-oversized-name").put("name", "y".repeat(10_000)).put("lastUsedAt", 500L).put("useCount", 1))
            .put(JSONObject().put("id", "bad-neg-count").put("name", "Bad").put("lastUsedAt", 500L).put("useCount", -3))
        source.map["recent_models"] = recent.toString()
        val before = source.map.toMap()
        val json = buildSettingsBackupJson(TestContext(), source)
        val library = JSONObject(json).getJSONObject("modelLibrary")
        val emitted = library.getJSONArray("recent")
        assertEquals(1, emitted.length())
        assertEquals("good", emitted.getJSONObject(0).getString("id"))
        assertEquals(before, source.map)
        val target = InMemoryPrefs()
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(TestContext(), target, json))
    }

    // ── current-schema recent validation (item 3) ─────────────────────────

    @Test
    fun `current schema requires all four recent fields and rejects malformed numeric values`() = runTest {
        val context = TestContext()
        val base = JSONObject()
            .put("schemaVersion", 3)
            .put("app", "Fusion")
            .put("settings", JSONObject())
            .put("modelLibrary", JSONObject().put("recent", JSONArray()))

        fun recentWith(overrides: Map<String, Any?>) = JSONObject()
            .put("id", overrides["id"] ?: "m")
            .apply { if (overrides.containsKey("name")) put("name", overrides["name"] ?: "N") }
            .apply { if (overrides.containsKey("lastUsedAt")) put("lastUsedAt", overrides["lastUsedAt"] ?: 1L) }
            .apply { if (overrides.containsKey("useCount")) put("useCount", overrides["useCount"] ?: 1) }

        // Helper to clone base JSON
        fun cloneBase(): JSONObject = JSONObject(base.toString())

        // Missing name -> InvalidFields
        val missingName = cloneBase().apply {
            getJSONObject("modelLibrary").put("recent", JSONArray().put(recentWith(mapOf("id" to "m", "lastUsedAt" to 1L, "useCount" to 1))))
        }.toString()
        assertEquals(SettingsRestoreResult.InvalidFields, restoreSettingsBackupJson(context, InMemoryPrefs(), missingName))

        // Missing lastUsedAt -> InvalidFields
        val missingTime = cloneBase().apply {
            getJSONObject("modelLibrary").put("recent", JSONArray().put(recentWith(mapOf("id" to "m", "name" to "N", "useCount" to 1))))
        }.toString()
        assertEquals(SettingsRestoreResult.InvalidFields, restoreSettingsBackupJson(context, InMemoryPrefs(), missingTime))

        // Missing useCount -> InvalidFields
        val missingCount = cloneBase().apply {
            getJSONObject("modelLibrary").put("recent", JSONArray().put(recentWith(mapOf("id" to "m", "name" to "N", "lastUsedAt" to 1L))))
        }.toString()
        assertEquals(SettingsRestoreResult.InvalidFields, restoreSettingsBackupJson(context, InMemoryPrefs(), missingCount))

        // String useCount -> InvalidFields
        val stringCount = cloneBase().apply {
            getJSONObject("modelLibrary").put("recent", JSONArray().put(recentWith(mapOf("id" to "m", "name" to "N", "lastUsedAt" to 1L, "useCount" to "two"))))
        }.toString()
        assertEquals(SettingsRestoreResult.InvalidFields, restoreSettingsBackupJson(context, InMemoryPrefs(), stringCount))

        // Fractional numeric -> InvalidFields
        val fractional = cloneBase().apply {
            getJSONObject("modelLibrary").put("recent", JSONArray().put(recentWith(mapOf("id" to "m", "name" to "N", "lastUsedAt" to 1.5, "useCount" to 1))))
        }.toString()
        assertEquals(SettingsRestoreResult.InvalidFields, restoreSettingsBackupJson(context, InMemoryPrefs(), fractional))

        // useCount > Int.MAX_VALUE -> InvalidFields
        val hugeCount = cloneBase().apply {
            getJSONObject("modelLibrary").put("recent", JSONArray().put(recentWith(mapOf("id" to "m", "name" to "N", "lastUsedAt" to 1L, "useCount" to Int.MAX_VALUE.toLong() + 1))))
        }.toString()
        assertEquals(SettingsRestoreResult.InvalidFields, restoreSettingsBackupJson(context, InMemoryPrefs(), hugeCount))

        // Valid production object -> Success
        val valid = cloneBase().apply {
            getJSONObject("modelLibrary").put("recent", JSONArray().put(recentWith(mapOf("id" to "m", "name" to "N", "lastUsedAt" to 1L, "useCount" to 1))))
        }.toString()
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(context, InMemoryPrefs(), valid))
    }

    // ── payload size guarantee and strong invariant (items 4 + 5) ──────────

    @Test
    fun `successful export never exceeds MaxSettingsBackupBytes and passes restore`() = runTest {
        val context = TestContext()
        val source = prefsWith(output = 2048, kv = 8192).apply {
            map["favorite_model_ids"] = setOf("catalog-gemma-e2b", "catalog-qwen", "catalog-kanana")
            map["hidden_model_ids"] = setOf("catalog-deepseek")
            map["model_notes"] = JSONObject().put("catalog-gemma-e2b", "note").toString()
            map["model_library_sort_mode"] = "favorites_first"
        }
        repeat(MaxRecentModels) { index -> RecentModelLibraryCodec.record(source, "catalog-model-$index", "Model $index") }

        val json = buildSettingsBackupJson(context, source)
        assertTrue("payload must be within bound", json.toByteArray(Charsets.UTF_8).size <= MaxSettingsBackupBytes)

        val root = JSONObject(json)
        assertFalse("payload must have no oversized values", containsOversizedValue(root))
        assertTrue(
            hasValidBackupFieldTypes(
                root.getJSONObject("settings"),
                root.getJSONObject("modelLibrary"),
                root.getInt("schemaVersion"),
            ),
        )

        val target = InMemoryPrefs()
        val restoreResult = restoreSettingsBackupJson(context, target, json)
        assertTrue("restore must not be TooLarge", restoreResult != SettingsRestoreResult.TooLarge)
        assertTrue(
            "restore must be Success or ModelPathMissing",
            restoreResult == SettingsRestoreResult.Success || restoreResult == SettingsRestoreResult.ModelPathMissing,
        )
    }

    @Test
    fun `maximum valid favorites hidden recent and notes survive together`() = runTest {
        val context = TestContext()
        val source = prefsWith(output = 2048, kv = 8192).apply {
            map["favorite_model_ids"] = (1..MaxBackupArrayItems).map { "fav-$it" }.toMutableSet()
            map["hidden_model_ids"] = (1..MaxBackupArrayItems).map { "hid-$it" }.toMutableSet()
            map["model_notes"] = JSONObject().apply {
                repeat(MaxBackupObjectFields) { put("key-$it", "v".repeat(MaxBackupStringChars)) }
            }.toString()
            map["model_library_sort_mode"] = "name"
        }
        repeat(MaxRecentModels) { index -> RecentModelLibraryCodec.record(source, "catalog-model-$index", "Name $index") }

        val json = buildSettingsBackupJson(context, source)
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= MaxSettingsBackupBytes)
        val root = JSONObject(json)
        assertFalse(containsOversizedValue(root))
        assertTrue(
            hasValidBackupFieldTypes(
                root.getJSONObject("settings"),
                root.getJSONObject("modelLibrary"),
                root.getInt("schemaVersion"),
            ),
        )
        val target = InMemoryPrefs()
        val result = restoreSettingsBackupJson(context, target, json)
        assertTrue(result == SettingsRestoreResult.Success || result == SettingsRestoreResult.ModelPathMissing)
        assertTrue(result != SettingsRestoreResult.TooLarge)
    }
}
