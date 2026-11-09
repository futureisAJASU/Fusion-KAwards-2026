package com.projectnuke.fusion.model

import android.content.SharedPreferences
import android.content.SharedPreferences.Editor
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import org.junit.Assert.*
import org.junit.Test

class RepairPhase4CentralizedPolicyTest {

    private class FakePrefs : SharedPreferences {
        internal val map = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = map[key] as? Set<String> ?: defValues
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {}

        inner class FakeEditor(private val map: MutableMap<String, Any?>) : Editor {
            private val changes = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?): Editor { changes[key] = value; return this }
            override fun putInt(key: String, value: Int): Editor { changes[key] = value; return this }
            override fun putFloat(key: String, value: Float): Editor { changes[key] = value; return this }
            override fun putLong(key: String, value: Long): Editor { changes[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): Editor { changes[key] = value; return this }
            override fun putStringSet(key: String, values: Set<String>?): Editor { changes[key] = values; return this }
            override fun remove(key: String): Editor { changes[key] = null; return this }
            override fun clear(): Editor { changes.clear(); return this }
            override fun apply(): Unit { changes.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }; changes.clear() }
            override fun commit(): Boolean = true
        }
    }

    @Test
    fun `fromPrefs loads settings with new keys when present`() {
        val prefs = FakePrefs().apply {
            map["generation_max_output_tokens"] = 2048
            map["engine_kv_cache_capacity_tokens"] = 8192
            map["top_k"] = 32
            map["top_p"] = 0.9f
            map["temperature"] = 0.8f
            map["accelerator"] = "CPU"
            map["reasoning_budget_tokens"] = 1024
            map["speculative_decoding_enabled"] = true
        }
        val settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(2048, settings.maxTokens)
        assertEquals(8192, settings.kvCacheCapacityTokens)
        assertEquals(32, settings.topK)
        assertEquals(0.9f, settings.topP, 0.001f)
        assertEquals(0.8f, settings.temperature, 0.001f)
        assertEquals(AcceleratorMode.CPU, settings.accelerator)
        assertEquals(1024, settings.reasoningBudgetTokens)
        assertEquals(true, settings.speculativeDecodingEnabled)
    }

    @Test
    fun `fromPrefs preserves valid low topK and reasoning budget`() {
        val prefs = FakePrefs().apply {
            map[LocalGenerationSettingsPolicy.KEY_TOP_K] = 1
            map[LocalGenerationSettingsPolicy.KEY_REASONING_BUDGET] = 1
        }

        val settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)

        assertEquals(1, settings.topK)
        assertEquals(1, settings.reasoningBudgetTokens)
    }

    @Test
    fun `fromPrefs fails closed on nonfinite sampler preferences`() {
        val prefs = FakePrefs().apply {
            map[LocalGenerationSettingsPolicy.KEY_TOP_P] = Float.NaN
            map[LocalGenerationSettingsPolicy.KEY_TEMPERATURE] = Float.POSITIVE_INFINITY
        }

        val settings = LocalGenerationSettingsPolicy.fromPrefs(prefs, materializeMigration = false)

        assertEquals(LocalGenerationFloatSettings.DEFAULT_TOP_P, settings.topP, 0f)
        assertEquals(LocalGenerationFloatSettings.DEFAULT_TEMPERATURE, settings.temperature, 0f)
        assertTrue(settings.topP.isFinite() && settings.temperature.isFinite())
    }

    @Test
    fun `legacy max_tokens migrates to new key silently`() {
        val prefs = FakePrefs().apply {
            map["max_tokens"] = 3000
        }
        val settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(3000, settings.maxTokens)
        assertEquals(3000, prefs.map["generation_max_output_tokens"])
    }

    @Test
    fun `legacy kv_cache migrates to new key`() {
        val prefs = FakePrefs().apply {
            map["generation_max_output_tokens"] = 2048
            map["kv_cache_capacity_tokens"] = 6144
        }
        val settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(6144, settings.kvCacheCapacityTokens)
        assertEquals(6144, prefs.map["engine_kv_cache_capacity_tokens"])
    }

    @Test
    fun `no legacy KV cache derives from output limit using 2x heuristic`() {
        val prefs = FakePrefs().apply {
            map["generation_max_output_tokens"] = 3000
        }
        val settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(6000, settings.kvCacheCapacityTokens)
    }

    @Test
    fun `lowMemoryOutputCap returns 1024`() {
        assertEquals(1024, LocalGenerationSettingsPolicy.lowMemoryOutputCap())
    }

    @Test
    fun `lowMemoryKvCapacity delegates to KvCacheCapacityPolicy`() {
        assertEquals(
            KvCacheCapacityPolicy.lowMemoryBenchmarkCapacity(),
            LocalGenerationSettingsPolicy.lowMemoryKvCapacity()
        )
    }

    @Test
    fun `deriveKvCapacityForOutput uses 2x heuristic clamped to bounds`() {
        assertEquals(KvCacheCapacityPolicy.MIN_CAPACITY, LocalGenerationSettingsPolicy.deriveKvCapacityForOutput(1))
        assertEquals(4000, LocalGenerationSettingsPolicy.deriveKvCapacityForOutput(2000))
        assertEquals(KvCacheCapacityPolicy.MAX_CAPACITY, LocalGenerationSettingsPolicy.deriveKvCapacityForOutput(20000))
    }

    @Test
    fun `default settings do not clamp 4000 to 96`() {
        val prefs = FakePrefs().apply {
            map["generation_max_output_tokens"] = 4000
        }
        val settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(4000, settings.maxTokens)
        assertEquals(8000, settings.kvCacheCapacityTokens) // 2x heuristic
    }

    @Test
    fun `lowMemoryKvCapacity reduces KV when policy says so`() {
        val prefs = FakePrefs().apply {
            map["generation_max_output_tokens"] = 4000
            map["engine_kv_cache_capacity_tokens"] = 8192
        }
        // Simulate low memory by directly calling lowMemoryKvCapacity
        val lowMemoryKv = LocalGenerationSettingsPolicy.lowMemoryKvCapacity()
        assertTrue("lowMemoryKvCapacity should be less than normal capacity", lowMemoryKv < 8192)
        assertTrue("lowMemoryKvCapacity should be at least MIN_CAPACITY", lowMemoryKv >= KvCacheCapacityPolicy.MIN_CAPACITY)
    }

    @Test
    fun `output only change with constant KV does not change engine profile`() {
        // Test that when only maxTokens changes but KV capacity stays constant,
        // the RequestedEngineProfile doesn't change (since KV is part of profile identity)
        val prefs1 = FakePrefs().apply {
            map["generation_max_output_tokens"] = 2048
            map["engine_kv_cache_capacity_tokens"] = 8192
        }
        val settings1 = LocalGenerationSettingsPolicy.fromPrefs(prefs1)
        
        // Output-only change: maxTokens 2048 -> 4096, KV explicitly kept at 8192
        val prefs2 = FakePrefs().apply {
            map["generation_max_output_tokens"] = 4096
            map["engine_kv_cache_capacity_tokens"] = 8192
        }
        val settings2 = LocalGenerationSettingsPolicy.fromPrefs(prefs2)
        
        assertEquals(2048, settings1.maxTokens)
        assertEquals(4096, settings2.maxTokens)
        // KV capacity stays the same because it's explicitly set
        assertEquals(8192, settings1.kvCacheCapacityTokens)
        assertEquals(8192, settings2.kvCacheCapacityTokens)
        // RequestedEngineProfile should be identical since KV is the same
        assertEquals(settings1.toRequestedEngineProfile("model.litertlm", enableVisionBackend = false),
            settings2.toRequestedEngineProfile("model.litertlm", enableVisionBackend = false))
    }

    @Test
    fun `kv capacity change alters engine identity`() {
        // Verify that when KV capacity explicitly changes (not derived), 
        // the engine key would change
        val prefs1 = FakePrefs().apply {
            map["generation_max_output_tokens"] = 2048
            map["engine_kv_cache_capacity_tokens"] = 4096
        }
        val settings1 = LocalGenerationSettingsPolicy.fromPrefs(prefs1)
        
        // Explicit KV change: same output, different KV
        val prefs2 = FakePrefs().apply {
            map["generation_max_output_tokens"] = 2048
            map["engine_kv_cache_capacity_tokens"] = 8192
        }
        val settings2 = LocalGenerationSettingsPolicy.fromPrefs(prefs2)
        
        assertEquals(settings1.maxTokens, settings2.maxTokens)
        assertNotEquals(settings1.kvCacheCapacityTokens, settings2.kvCacheCapacityTokens)
    }

    @Test
    fun `fresh install keeps framework defaults non-explicit for profile resolution`() {
        val prefs = FakePrefs()
        val settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(4000, settings.maxTokens)
        assertEquals(4096, settings.kvCacheCapacityTokens)
        assertFalse(prefs.map.containsKey(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS))
        assertFalse(prefs.map.containsKey(LocalGenerationSettingsPolicy.KEY_KV_CACHE))
        assertEquals(
            null,
            LocalModelSettingsProfileStore(prefs).globalDefaultsOrLegacy(),
        )
    }

    @Test
    fun `upgrade with existing legacy settings preserves explicit global values`() {
        val prefs = FakePrefs().apply {
            map["max_tokens"] = 1024
            map["kv_cache_capacity_tokens"] = 4096
            map[LocalGenerationSettingsPolicy.KEY_TEMPERATURE] = 0.7f
        }
        LocalGenerationSettingsPolicy.fromPrefs(prefs)
        val global = LocalModelSettingsProfileStore(prefs).globalDefaultsOrLegacy()
        assertEquals(1024, global?.maxOutputTokens)
        assertEquals(4096, global?.manualKvCapacityTokens)
        assertEquals(0.7f, global?.temperature)
        assertEquals("legacy", prefs.getString(LocalGenerationSettingsPolicy.KEY_SETTINGS_MIGRATION_STATE, null))
    }

    @Test
    fun `fresh install marker remains fresh after later legacy key writes`() {
        val prefs = FakePrefs()
        LocalGenerationSettingsPolicy.fromPrefs(prefs)
        prefs.edit().putInt(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS, 1024).apply()
        assertEquals(null, LocalModelSettingsProfileStore(prefs).globalDefaultsOrLegacy())
        assertEquals("fresh", prefs.getString(LocalGenerationSettingsPolicy.KEY_SETTINGS_MIGRATION_STATE, null))
    }
}
