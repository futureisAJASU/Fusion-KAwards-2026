package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.model.RuntimePerformanceMode
import com.projectnuke.fusion.model.RuntimePerformancePreference
import com.projectnuke.fusion.model.RuntimePerformancePreferenceStore
import com.projectnuke.fusion.test.PlatformSharedPreferences
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePerformanceSettingsBackupTest {
    private class TestContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    private fun payload(mode: String, level: Any): String = JSONObject()
        .put("schemaVersion", FusionSettingsBackupSchemaVersion)
        .put("app", "Fusion")
        .put("settings", JSONObject().put("runtimePerformance", JSONObject()
            .put("mode", mode).put("manualLevel", level)))
        .put("modelLibrary", JSONObject())
        .toString()

    @Test
    fun exportIncludesGlobalRuntimePreferenceAndNormalizedLevel() {
        val prefs = PlatformSharedPreferences()
        RuntimePerformancePreferenceStore(prefs).write(RuntimePerformancePreference(RuntimePerformanceMode.MANUAL, 83))
        val root = JSONObject(buildSettingsBackupJson(TestContext(), prefs))
        val performance = root.getJSONObject("settings").getJSONObject("runtimePerformance")
        assertEquals(FusionSettingsBackupSchemaVersion, root.getInt("schemaVersion"))
        assertEquals("MANUAL", performance.getString("mode"))
        assertEquals(83, performance.getInt("manualLevel"))
    }

    @Test
    fun restorePersistsGlobalPreferenceAndFailedCommitRollsItBack() = runTest {
        val prefs = PlatformSharedPreferences().apply {
            values[RuntimePerformancePreferenceStore.KEY] = "AUTO:50"
            durableValues[RuntimePerformancePreferenceStore.KEY] = "AUTO:50"
        }
        assertEquals(SettingsRestoreResult.Success, restoreSettingsBackupJson(TestContext(), prefs, payload("MANUAL", 100)))
        assertEquals(RuntimePerformancePreference(RuntimePerformanceMode.MANUAL, 100), RuntimePerformancePreferenceStore(prefs).read())

        val rollbackPrefs = PlatformSharedPreferences().apply {
            values[RuntimePerformancePreferenceStore.KEY] = "AUTO:50"
            durableValues[RuntimePerformancePreferenceStore.KEY] = "AUTO:50"
            enqueueCommitResults(false, true)
        }
        assertEquals(SettingsRestoreResult.CommitFailed, restoreSettingsBackupJson(TestContext(), rollbackPrefs, payload("MANUAL", 100)))
        assertEquals("AUTO:50", rollbackPrefs.values[RuntimePerformancePreferenceStore.KEY])
        assertEquals("AUTO:50", rollbackPrefs.durableValues[RuntimePerformancePreferenceStore.KEY])
    }

    @Test
    fun malformedPreferenceInBackupIsRejected() = runTest {
        assertEquals(SettingsRestoreResult.InvalidFields, restoreSettingsBackupJson(TestContext(), PlatformSharedPreferences(), payload("MAXIMUM", 101)))
        assertEquals(SettingsRestoreResult.InvalidFields, restoreSettingsBackupJson(TestContext(), PlatformSharedPreferences(), payload("MANUAL", 50.5)))
    }
}
