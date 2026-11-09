package com.projectnuke.fusion.ui

import android.content.Context

private const val FusionDeveloperModeEnabledKey = "fusion_developer_mode_enabled"

// Developer mode is a UI visibility gate, not a security feature.
fun isFusionDeveloperModeEnabled(context: Context): Boolean {
    return context
        .getSharedPreferences("fusion_chat_settings", Context.MODE_PRIVATE)
        .getBoolean(FusionDeveloperModeEnabledKey, false)
}

fun setFusionDeveloperModeEnabled(context: Context, enabled: Boolean) {
    context
        .getSharedPreferences("fusion_chat_settings", Context.MODE_PRIVATE)
        .edit()
        .putBoolean(FusionDeveloperModeEnabledKey, enabled)
        .apply()
}
