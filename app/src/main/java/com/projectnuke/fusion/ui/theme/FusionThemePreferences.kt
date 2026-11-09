package com.projectnuke.fusion.ui.theme

import android.content.Context
import android.content.res.Configuration
import com.projectnuke.fusion.util.SharedPreferencesDurability
import com.projectnuke.fusion.util.SharedPreferencesScopedLock
import com.projectnuke.fusion.util.snapshotKeys
import com.projectnuke.fusion.util.restoreKeys
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

enum class FusionThemeMode(val storageValue: String, val label: String) {
    BLACK("black", "검정"),
    DARK("dark", "어둡게"),
    LIGHT("light", "밝게"),
    AUTO("auto", "시스템 설정");

    companion object {
        fun fromStorage(value: String?): FusionThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: AUTO
    }
}

internal const val FusionThemePreferenceKey = "fusion_theme_mode"
private const val FusionSettingsPreferences = "fusion_chat_settings"

fun readFusionThemeMode(context: Context): FusionThemeMode =
    FusionThemeMode.fromStorage(
        context.getSharedPreferences(FusionSettingsPreferences, Context.MODE_PRIVATE)
            .getString(FusionThemePreferenceKey, FusionThemeMode.AUTO.storageValue)
    )

fun resolveThemeMode(context: Context, mode: FusionThemeMode): FusionThemeMode {
    if (mode != FusionThemeMode.AUTO) return mode
    val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return if (nightMode == Configuration.UI_MODE_NIGHT_YES) FusionThemeMode.DARK else FusionThemeMode.LIGHT
}

fun saveFusionThemeMode(context: Context, mode: FusionThemeMode): Boolean {
    val prefs = context.getSharedPreferences(FusionSettingsPreferences, Context.MODE_PRIVATE)
    return SharedPreferencesScopedLock.withKeyLock(prefs, FusionThemePreferenceKey) {
        if (SharedPreferencesDurability.isUnknown(prefs, FusionThemePreferenceKey)) return@withKeyLock false
        val snapshot = prefs.snapshotKeys(setOf(FusionThemePreferenceKey))
        if (prefs.edit().putString(FusionThemePreferenceKey, mode.storageValue).commit()) {
            true
        } else {
            if (!prefs.restoreKeys(snapshot)) SharedPreferencesDurability.markUnknown(prefs, FusionThemePreferenceKey)
            false
        }
    }
}

@Composable
fun rememberFusionThemeMode(context: Context): FusionThemeMode {
    val prefs = remember(context) {
        context.getSharedPreferences(FusionSettingsPreferences, Context.MODE_PRIVATE)
    }
    var mode by remember { mutableStateOf(FusionThemeMode.fromStorage(prefs.getString(FusionThemePreferenceKey, null))) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == FusionThemePreferenceKey) {
                mode = FusionThemeMode.fromStorage(prefs.getString(FusionThemePreferenceKey, null))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return resolveThemeMode(context, mode)
}
