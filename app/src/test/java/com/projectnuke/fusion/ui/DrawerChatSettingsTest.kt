package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.DynamicFollowUpPreference
import com.projectnuke.fusion.test.PlatformSharedPreferences
import org.junit.Assert.*
import org.junit.Test

class DrawerChatSettingsTest {
    @Test fun `follow up toggle preserves newer authoritative web search setting`() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putBoolean("web_search_enabled", true).apply()
        saveDrawerChatBoolean(prefs, DynamicFollowUpPreference.KEY_AUTO_GENERATE, false)
        assertTrue(prefs.getBoolean("web_search_enabled", false))
        assertFalse(prefs.getBoolean(DynamicFollowUpPreference.KEY_AUTO_GENERATE, true))
    }
    @Test fun `web toggle preserves latest follow up setting`() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putBoolean(DynamicFollowUpPreference.KEY_AUTO_GENERATE, true).apply()
        saveDrawerChatBoolean(prefs, "web_search_enabled", false)
        assertTrue(prefs.getBoolean(DynamicFollowUpPreference.KEY_AUTO_GENERATE, false))
    }
}
