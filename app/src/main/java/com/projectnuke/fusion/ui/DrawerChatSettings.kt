package com.projectnuke.fusion.ui

import android.content.SharedPreferences

/** A toggle owns only its own key, never the drawer's cached sibling value. */
internal fun saveDrawerChatBoolean(prefs: SharedPreferences, key: String, value: Boolean) {
    require(key == "web_search_enabled" || key == com.projectnuke.fusion.chat.DynamicFollowUpPreference.KEY_AUTO_GENERATE)
    prefs.edit().putBoolean(key, value).apply()
}
