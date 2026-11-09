package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.model.KvCacheCapacityPolicy
import com.projectnuke.fusion.model.LocalGenerationSettingBounds
import com.projectnuke.fusion.model.LocalGenerationSettingsPolicy
import com.projectnuke.fusion.model.LocalModelSettingsProfileStore
import com.projectnuke.fusion.test.PlatformSharedPreferences
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsBackupNumericBoundaryTest {
    private class TestContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    private fun payload(schema: Any = FusionSettingsBackupSchemaVersion, settings: JSONObject = JSONObject()) =
        JSONObject().put("schemaVersion", schema).put("app", "Fusion")
            .put("settings", settings).put("modelLibrary", JSONObject()).toString()

    @Test
    fun `wide schema identities cannot wrap into supported versions`() = runTest {
        for (schema in listOf(4294967300L, -4294967292L, Long.MAX_VALUE)) {
            assertRejectedWithoutMutation(payload(schema), SettingsRestoreResult.UnsupportedSchema)
        }
    }

    @Test
    fun `wide manual levels cannot wrap into the accepted slider range`() = runTest {
        for (level in listOf(4294967346L, -4294967246L, Long.MAX_VALUE)) {
            val settings = JSONObject().put("runtimePerformance",
                JSONObject().put("mode", "MANUAL").put("manualLevel", level))
            assertRejectedWithoutMutation(payload(settings = settings), SettingsRestoreResult.InvalidFields)
        }
    }

    private suspend fun assertRejectedWithoutMutation(raw: String, expected: SettingsRestoreResult) {
        val prefs = PlatformSharedPreferences().apply {
            values["sentinel"] = "keep"
            durableValues["sentinel"] = "keep"
        }
        assertEquals(expected, restoreSettingsBackupJson(TestContext(), prefs, raw))
        assertEquals(mapOf("sentinel" to "keep"), prefs.values)
        assertEquals(prefs.values, prefs.durableValues)
        assertEquals(0, prefs.commitCalls.get())
    }

    @Test
    fun `wide generation values clamp by magnitude in current and legacy backups`() = runTest {
        for (schema in 1..FusionSettingsBackupSchemaVersion) {
            for (value in listOf(4294967297L, -4294967295L)) {
                for (kvField in listOf("kvCacheCapacityTokens", "kv_cache_capacity_tokens")) {
                    val prefs = PlatformSharedPreferences()
                    val settings = JSONObject().put("maxTokens", value).put(kvField, value)
                        .put("topK", value).put("reasoningBudget", value)
                    assertEquals(SettingsRestoreResult.Success,
                        restoreSettingsBackupJson(TestContext(), prefs, payload(schema, settings)))
                    val positive = value > 0
                    val output = if (positive) 32000 else 1
                    val kv = if (positive) KvCacheCapacityPolicy.MAX_CAPACITY else KvCacheCapacityPolicy.MIN_CAPACITY
                    val topK = if (positive) LocalGenerationSettingBounds.TOP_K_MAX else LocalGenerationSettingBounds.TOP_K_MIN
                    val budget = if (positive) LocalGenerationSettingBounds.REASONING_BUDGET_MAX else LocalGenerationSettingBounds.REASONING_BUDGET_MIN
                    assertEquals(output, prefs.values[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS])
                    assertEquals(kv, prefs.values[LocalGenerationSettingsPolicy.KEY_KV_CACHE])
                    assertEquals(topK, prefs.values[LocalGenerationSettingsPolicy.KEY_TOP_K])
                    assertEquals(budget, prefs.values[LocalGenerationSettingsPolicy.KEY_REASONING_BUDGET])
                    if (schema <= 2) {
                        val profile = requireNotNull(LocalModelSettingsProfileStore(prefs).read().globalDefaults)
                        assertEquals(output, profile.maxOutputTokens)
                        assertEquals(kv, profile.manualKvCapacityTokens)
                        assertEquals(topK, profile.topK)
                        assertEquals(budget, profile.reasoningBudgetTokens)
                    }
                    assertEquals(prefs.values, prefs.durableValues)
                }
            }
        }
    }
}
