package com.projectnuke.fusion.model

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelSettingsProfileStoreTest {
    @Test
    fun `invalid sampler floats are rejected without replacing valid durable profile`() {
        val prefs = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        val valid = LocalModelSettingsOverride(temperature = 0.75f, topP = 0.8f)
        assertTrue(store.writeModelOverride("model-a", valid))
        val before = prefs.getString(LocalModelSettingsProfileStore.KEY, null)

        assertFalse(store.writeModelOverride("model-a", valid.copy(temperature = Float.NaN)))
        assertFalse(store.writeGlobalDefaults(valid.copy(topP = Float.POSITIVE_INFINITY)))

        assertEquals(before, prefs.getString(LocalModelSettingsProfileStore.KEY, null))
        assertEquals(valid, LocalModelSettingsProfileStore(prefs).read().modelOverrides["model-a"])
    }

    @Test
    fun `strict profile decoder rejects out of range sampler floats`() {
        assertEquals(null, LocalModelSettingsProfileCodec.decodeStrict("""{"version":1,"global":{"topP":1.1}}"""))
        assertEquals(null, LocalModelSettingsProfileCodec.decodeStrict("""{"version":1,"global":{"temperature":-0.1}}"""))
    }

    @Test
    fun `quick reasoning and MTP writes preserve the complete model override and global defaults`() {
        val prefs = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        val global = LocalModelSettingsOverride(maxOutputTokens = 2048, reasoningEnabled = true)
        val model = LocalModelSettingsOverride(
            maxOutputTokens = 4096, temperature = 1.2f, topK = 72, topP = .91f,
            reasoningBudgetTokens = 768, reasoningEnabled = false,
            speculativeDecodingEnabled = false, accelerator = AcceleratorMode.CPU,
            contextMode = LocalContextMode.MANUAL, manualKvCapacityTokens = 8192,
            compactPolicy = LocalCompactPolicy(false, 75, 30),
        )
        assertTrue(store.writeGlobalDefaults(global))
        assertTrue(store.writeModelOverride("model-a", model))
        assertTrue(store.writeModelReasoning("model-a", true))
        assertEquals(model.copy(reasoningEnabled = true), store.read().modelOverrides["model-a"])
        assertTrue(store.writeModelMtp("model-a", true))
        assertEquals(model.copy(reasoningEnabled = true, speculativeDecodingEnabled = true), store.read().modelOverrides["model-a"])
        assertEquals(global, store.read().globalDefaults)
        val effective = LocalModelSettingsResolver.resolve(
            "model-a", perModelOverride = store.read().modelOverrides["model-a"],
            globalDefaults = store.read().globalDefaults,
        )
        assertTrue(effective.reasoningEnabled)
        assertTrue(effective.generationSettings.speculativeDecodingEnabled == true)
        assertEquals(4096, effective.generationSettings.maxTokens)
    }

    @Test
    fun `quick toggle creates a sparse override over authoritative global profile`() {
        val store = LocalModelSettingsProfileStore(InMemoryPrefs())
        assertTrue(store.writeGlobalDefaults(LocalModelSettingsOverride(maxOutputTokens = 4096, reasoningEnabled = true)))
        assertTrue(store.writeModelReasoning("model-a", false))
        val document = store.read()
        assertEquals(LocalModelSettingsOverride(reasoningEnabled = false), document.modelOverrides["model-a"])
        assertTrue(LocalModelSettingsResolver.resolve("model-b", globalDefaults = document.globalDefaults).reasoningEnabled)
        assertFalse(LocalModelSettingsResolver.resolve("model-a", perModelOverride = document.modelOverrides["model-a"], globalDefaults = document.globalDefaults).reasoningEnabled)
    }

    internal class InMemoryPrefs : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = EditorImpl()

        private inner class EditorImpl : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean {
                removed.forEach(values::remove)
                values.putAll(pending)
                return true
            }
            override fun apply() { commit() }
        }
    }

    @Test
    fun `model override does not mutate legacy global generation values`() {
        val prefs = InMemoryPrefs()
        prefs.edit()
            .putInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, 1024)
            .putInt(LocalGenerationSettingsPolicy.KEY_KV_CACHE, 4096)
            .putFloat(LocalGenerationSettingsPolicy.KEY_TEMPERATURE, 0.7f)
            .putInt(LocalGenerationSettingsPolicy.KEY_TOP_K, 32)
            .putBoolean("reasoning_enabled", false)
            .putBoolean("speculative_decoding_enabled", false)
            .apply()

        LocalModelSettingsProfileStore(prefs).writeModelOverride(
            "model-a",
            LocalModelSettingsOverride(
                maxOutputTokens = 4096,
                manualKvCapacityTokens = 12288,
                temperature = 1.4f,
                accelerator = AcceleratorMode.CPU,
                reasoningEnabled = true,
                speculativeDecodingEnabled = true,
            ),
        )

        assertEquals(1024, prefs.getInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, -1))
        assertEquals(4096, prefs.getInt(LocalGenerationSettingsPolicy.KEY_KV_CACHE, -1))
        assertEquals(0.7f, prefs.getFloat(LocalGenerationSettingsPolicy.KEY_TEMPERATURE, -1f), 0f)
        assertEquals(32, prefs.getInt(LocalGenerationSettingsPolicy.KEY_TOP_K, -1))
        assertFalse(prefs.getBoolean("reasoning_enabled", true))
        assertFalse(prefs.getBoolean("speculative_decoding_enabled", true))

        val model = LocalModelSettingsResolver.resolve(
            "model-a",
            globalDefaults = LocalModelSettingsProfileStore(prefs).globalDefaultsOrLegacy(),
            perModelOverride = LocalModelSettingsProfileStore(prefs).read().modelOverrides["model-a"],
        )
        val other = LocalModelSettingsResolver.resolve(
            "model-b",
            globalDefaults = LocalModelSettingsProfileStore(prefs).globalDefaultsOrLegacy(),
        )
        assertEquals(4096, model.generationSettings.maxTokens)
        assertEquals(12288, model.effectiveKvCapacityTokens)
        assertEquals(1024, other.generationSettings.maxTokens)
        assertEquals(4096, other.effectiveKvCapacityTokens)
    }

    @Test
    fun `global write and model reset keep the two scopes isolated`() {
        val prefs = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        val modelOverride = LocalModelSettingsOverride(
            maxOutputTokens = 4096,
            manualKvCapacityTokens = 12288,
            temperature = 1.2f,
        )
        store.writeModelOverride("model-a", modelOverride)
        store.writeGlobalDefaults(
            LocalModelSettingsOverride(
                maxOutputTokens = 1024,
                manualKvCapacityTokens = 4096,
                temperature = 0.7f,
            )
        )

        val document = store.read()
        assertEquals(modelOverride, document.modelOverrides["model-a"])
        assertEquals(1024, document.globalDefaults?.maxOutputTokens)
        store.deleteModelOverride("model-a")
        assertEquals(null, store.read().modelOverrides["model-a"])
        assertEquals(1024, store.read().globalDefaults?.maxOutputTokens)
    }

    @Test
    fun `simple model edit writes one model override and preserves expert fields`() {
        val prefs = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        val current = LocalSettingsEditValues(
            settings = GenerationSettings(
                maxTokens = 1024,
                temperature = 1f,
                topK = 64,
                topP = .95f,
                accelerator = AcceleratorMode.CPU,
                speculativeDecodingEnabled = true,
                reasoningBudgetTokens = 512,
            ),
            reasoningEnabled = false,
            contextMode = LocalContextMode.MANUAL,
            manualKvCapacityTokens = 8192,
            compactPolicy = LocalCompactPolicy(false, 75, 30),
        )
        assertTrue(writeSimpleModelOverride(store, "model-a", current) {
            applySimpleStyle(it, SimpleResponseStyle.CAREFUL, null)
        })
        val saved = store.read().modelOverrides.getValue("model-a")
        assertEquals(.35f, saved.temperature!!, 0f)
        assertEquals(.85f, saved.topP!!, 0f)
        assertEquals(32, saved.topK)
        assertEquals(AcceleratorMode.CPU, saved.accelerator)
        assertEquals(true, saved.speculativeDecodingEnabled)
        assertEquals(LocalContextMode.MANUAL, saved.contextMode)
        assertEquals(8192, saved.manualKvCapacityTokens)
        assertEquals(LocalCompactPolicy(false, 75, 30), saved.compactPolicy)
        assertEquals(null, store.read().globalDefaults)
    }

    @Test
    fun `chat benchmark and AB resolve the same persisted simple model profile`() {
        val prefs = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        val current = LocalSettingsEditValues(
            settings = GenerationSettings(maxTokens = 1024, temperature = 1f, topK = 64, topP = .95f),
            reasoningEnabled = false,
            contextMode = LocalContextMode.AUTO,
            manualKvCapacityTokens = 4096,
            compactPolicy = LocalCompactPolicy(),
        )
        assertTrue(writeSimpleModelOverride(store, "model-a", current) {
            applySimpleLength(it, SimpleAnswerLength.DETAILED)
        })
        val override = store.read().modelOverrides.getValue("model-a")
        val chat = LocalModelSettingsResolver.resolve("model-a", perModelOverride = override)
        val benchmark = LocalModelSettingsResolver.resolve("model-a", perModelOverride = override)
        val ab = LocalModelSettingsResolver.resolve("model-a", perModelOverride = override)
        assertEquals(2048, chat.generationSettings.maxTokens)
        assertEquals(chat.generationSettings, benchmark.generationSettings)
        assertEquals(chat.generationSettings, ab.generationSettings)
    }

    @Test
    fun `sequential simple writes read latest state and preserve independent changes`() {
        val prefs = InMemoryPrefs()
        val store = LocalModelSettingsProfileStore(prefs)
        var latest = LocalSettingsEditValues(
            settings = GenerationSettings(maxTokens = 1024, temperature = 1f, topK = 64, topP = .95f),
            reasoningEnabled = false,
            contextMode = LocalContextMode.AUTO,
            manualKvCapacityTokens = 4096,
            compactPolicy = LocalCompactPolicy(),
        )
        assertTrue(writeSimpleModelOverrideFromLatest(store, "model-a", { latest }) {
            applySimpleStyle(it, SimpleResponseStyle.CAREFUL, null)
        })
        latest = applySimpleStyle(latest, SimpleResponseStyle.CAREFUL, null)
        assertTrue(writeSimpleModelOverrideFromLatest(store, "model-a", { latest }) {
            applySimpleLength(it, SimpleAnswerLength.DETAILED)
        })
        latest = applySimpleLength(latest, SimpleAnswerLength.DETAILED)
        assertTrue(writeSimpleModelOverrideFromLatest(store, "model-a", { latest }) {
            requireNotNull(applyReasoningEffort(it, ReasoningEffortPreset.MEDIUM))
        })
        val saved = store.read().modelOverrides.getValue("model-a")
        assertEquals(.35f, saved.temperature!!, 0f)
        assertEquals(.85f, saved.topP!!, 0f)
        assertEquals(32, saved.topK)
        assertEquals(2048, saved.maxOutputTokens)
        assertEquals(true, saved.reasoningEnabled)
        assertEquals(1024, saved.reasoningBudgetTokens)
    }
}
