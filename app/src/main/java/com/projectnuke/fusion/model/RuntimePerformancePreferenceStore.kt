package com.projectnuke.fusion.model

import android.content.SharedPreferences
import com.projectnuke.fusion.util.SharedPreferencesDurability
import com.projectnuke.fusion.util.SharedPreferencesScopedLock
import com.projectnuke.fusion.util.restoreKeys
import com.projectnuke.fusion.util.snapshotKeys

/** One global device preference; it is intentionally independent of model profiles. */
class RuntimePerformancePreferenceStore(private val prefs: SharedPreferences) {
    fun read(): RuntimePerformancePreference {
        if (SharedPreferencesDurability.isUnknown(prefs, KEY)) return RuntimePerformancePreference()
        return decode(prefs.all[KEY] as? String) ?: RuntimePerformancePreference()
    }

    fun write(value: RuntimePerformancePreference): Boolean =
        SharedPreferencesScopedLock.withKeyLock(prefs, KEY) {
            if (SharedPreferencesDurability.isUnknown(prefs, KEY)) return@withKeyLock false
            val oldRaw = prefs.all[KEY] as? String
            val needsQuarantine = oldRaw != null && decode(oldRaw) == null
            val ownedKeys = if (needsQuarantine) setOf(KEY, KEY_QUARANTINE) else setOf(KEY)
            val snapshot = prefs.snapshotKeys(ownedKeys)
            val editor = prefs.edit().putString(KEY, encode(value))
            if (needsQuarantine) editor.putString(KEY_QUARANTINE, oldRaw)
            val committed = editor.commit()
            if (!committed && !prefs.restoreKeys(snapshot)) {
                SharedPreferencesDurability.markUnknown(prefs, KEY)
            }
            committed
        }

    companion object {
        const val PREFERENCES_NAME = "fusion_chat_settings"
        const val KEY = "runtime_performance_preference"
        private const val KEY_QUARANTINE = "runtime_performance_preference.corrupt.bak"

        fun encode(value: RuntimePerformancePreference): String {
            val normalized = value.normalized()
            return "${normalized.mode.name}:${normalized.manualLevel}"
        }

        fun decode(raw: String?): RuntimePerformancePreference? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(':')
            if (parts.size != 2) return null
            val mode = RuntimePerformanceMode.entries.firstOrNull { it.name == parts[0] } ?: return null
            val level = parts[1].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
            return RuntimePerformancePreference(mode, level)
        }
    }
}
