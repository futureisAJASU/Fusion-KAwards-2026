package com.projectnuke.fusion.chat

import android.content.SharedPreferences

/**
 * Single authority for the automatic semantic follow-up generation
 * preference ("추천 질문 자동 생성").
 *
 * Default enabled: eligible answers keep the existing dynamic semantic
 * follow-up behavior. When disabled, no background runtime is acquired and
 * no local generation runs for semantic follow-ups; the deterministic
 * [NextActionSuggestionPolicy] row keeps working and Study behavior is
 * unchanged. No Room migration: plain SharedPreferences boolean.
 */
internal object DynamicFollowUpPreference {
    const val KEY_AUTO_GENERATE = "dynamic_followup_auto_generate"
    const val DEFAULT_AUTO_GENERATE = true

    fun isAutoGenerateEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_AUTO_GENERATE, DEFAULT_AUTO_GENERATE)
}
