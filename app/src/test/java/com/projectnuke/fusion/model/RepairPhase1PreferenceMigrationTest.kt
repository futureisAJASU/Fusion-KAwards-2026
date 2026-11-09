package com.projectnuke.fusion.model

import android.content.SharedPreferences
import android.content.SharedPreferences.Editor
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1: regression tests for the centralized output/KV policy migration.
 *
 * Verifies that the unified LocalGenerationSettingsPolicy correctly handles:
 * - legacy-only installs migrating to authoritative keys
 * - user changes surviving reload after migration
 * - KV capacity changes persisting correctly
 * - device recommendations updating authoritative keys
 * - backup/restore preserving authoritative values
 * - stale legacy keys not overriding authoritative values
 */
class RepairPhase1PreferenceMigrationTest {

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
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {}

inner class FakeEditor(private val map: MutableMap<String, Any?>) : android.content.SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor { changes[key] = value; return this }
            override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor { changes[key] = value; return this }
            override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor { changes[key] = value; return this }
            override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor { changes[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor { changes[key] = value; return this }
            override fun putStringSet(key: String, values: MutableSet<String>?): android.content.SharedPreferences.Editor { changes[key] = values; return this }
            override fun remove(key: String): android.content.SharedPreferences.Editor { changes[key] = null; return this }
            override fun clear(): android.content.SharedPreferences.Editor { changes.clear(); return this }
            override fun apply(): Unit { changes.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }; changes.clear() }
            override fun commit(): Boolean = true
        }
    }

    @Test
    fun `legacy-only install migrates both keys silently`() {
        val prefs = FakePrefs().apply {
            map["max_tokens"] = 4000
            map["kv_cache_capacity_tokens"] = 8192
        }

        val settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(4000, settings.maxTokens)
        assertEquals(8192, settings.kvCacheCapacityTokens)
        assertEquals(4000, prefs.map[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS])
        assertEquals(8192, prefs.map[LocalGenerationSettingsPolicy.KEY_KV_CACHE])
    }

    @Test
    fun `user change after migration survives reload`() {
        val prefs = FakePrefs().apply {
            map["max_tokens"] = 4000
            map["kv_cache_capacity_tokens"] = 4096
        }

        // First load triggers migration
        var settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(4000, settings.maxTokens)

        // Simulate user changing output to 8000 through UI
        prefs.edit().putInt("generation_max_output_tokens", 8000).apply()

        // Reload should see the new value
        settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(8000, settings.maxTokens)
        assertEquals(8000, prefs.map[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS])
    }

    @Test
    fun `KV capacity change after migration persists`() {
        val prefs = FakePrefs().apply {
            map["max_tokens"] = 4000
            map["kv_cache_capacity_tokens"] = 4096
        }

        var settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(4096, settings.kvCacheCapacityTokens)

        // Change KV capacity
        prefs.edit().putInt("engine_kv_cache_capacity_tokens", 16384).apply()

        settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(16384, settings.kvCacheCapacityTokens)
        assertEquals(16384, prefs.map[LocalGenerationSettingsPolicy.KEY_KV_CACHE])
    }

    @Test
    fun `device recommendation after migration updates authoritative key`() {
        val prefs = FakePrefs().apply {
            map["max_tokens"] = 4000
            map["kv_cache_capacity_tokens"] = 4096
        }

        // Initial load
        val settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(4000, settings.maxTokens)

        // Device recommendation applies new value
        prefs.edit().putInt("generation_max_output_tokens", 2048).apply()

        val settings2 = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(2048, settings2.maxTokens)
    }

    @Test
    fun `backup restore preserves authoritative keys`() {
        val prefs = FakePrefs().apply {
            map["generation_max_output_tokens"] = 8192
            map["engine_kv_cache_capacity_tokens"] = 16384
            map["top_k"] = 32
            map["top_p"] = 0.9f
            map["temperature"] = 0.8f
            map["accelerator"] = "CPU"
            map["reasoning_budget_tokens"] = 1024
            map["speculative_decoding_enabled"] = true
        }

        val settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(8192, settings.maxTokens)
        assertEquals(16384, settings.kvCacheCapacityTokens)
        assertEquals(32, settings.topK)
        assertEquals(0.9f, settings.topP, 0.001f)
        assertEquals(0.8f, settings.temperature, 0.001f)
        assertEquals(com.projectnuke.fusion.model.AcceleratorMode.CPU, settings.accelerator)
        assertEquals(true, settings.speculativeDecodingEnabled)
    }

    @Test
    fun `stale legacy keys cannot override newer authoritative value`() {
        val prefs = FakePrefs().apply {
            map["generation_max_output_tokens"] = 8000
            map["engine_kv_cache_capacity_tokens"] = 16384
            map["max_tokens"] = 100
            map["kv_cache_capacity_tokens"] = 100
        }

        val settings = LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertEquals(8000, settings.maxTokens)
        assertEquals(16384, settings.kvCacheCapacityTokens)
    }

    @Test
    fun `migration complete flag works`() {
        val prefs = FakePrefs().apply {
            map["max_tokens"] = 4000
            map["kv_cache_capacity_tokens"] = 4096
        }

        assertTrue(!LocalGenerationSettingsPolicy.isMigrationComplete(prefs))
        LocalGenerationSettingsPolicy.fromPrefs(prefs)
        assertTrue(LocalGenerationSettingsPolicy.isMigrationComplete(prefs))
    }
}