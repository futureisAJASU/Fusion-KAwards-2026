package com.projectnuke.fusion.ui

import android.content.SharedPreferences
import com.projectnuke.fusion.ui.model.RecentModelLibraryCodec

internal object ModelLibraryPreferences {
    fun recordRecentModel(prefs: SharedPreferences, modelId: String, displayName: String) {
        RecentModelLibraryCodec.record(prefs, modelId, displayName)
    }
}
