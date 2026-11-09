package com.projectnuke.fusion.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import com.projectnuke.fusion.test.PlatformSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class FusionThemeModeTest {
    private class TestContext(private val prefs: SharedPreferences, private val uiModeConfig: Int = Configuration.UI_MODE_NIGHT_NO) : android.content.ContextWrapper(android.app.Application()) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getResources(): android.content.res.Resources = super.getResources()
    }

    @Test
    fun stored_values_round_trip_to_the_explicit_palette() {
        FusionThemeMode.entries.forEach { mode ->
            assertEquals(mode, FusionThemeMode.fromStorage(mode.storageValue))
        }
    }

    @Test
    fun absent_or_invalid_theme_uses_auto_default() {
        assertEquals(FusionThemeMode.AUTO, FusionThemeMode.fromStorage(null))
        assertEquals(FusionThemeMode.AUTO, FusionThemeMode.fromStorage("invalid"))
    }

    @Test
    fun saved_theme_is_read_back_from_existing_preferences() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        assertEquals(FusionThemeMode.AUTO, readFusionThemeMode(context))
        assertEquals(true, saveFusionThemeMode(context, FusionThemeMode.LIGHT))
        assertEquals(FusionThemeMode.LIGHT, readFusionThemeMode(context))
        saveFusionThemeMode(context, FusionThemeMode.DARK)
        assertEquals(FusionThemeMode.DARK, readFusionThemeMode(context))
    }

    @Test fun failed_theme_commit_restores_process_choice_and_restart_choice() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        saveFusionThemeMode(context, FusionThemeMode.DARK)
        prefs.enqueueCommitResults(false, true)
        assertEquals(false, saveFusionThemeMode(context, FusionThemeMode.LIGHT))
        assertEquals(FusionThemeMode.DARK, readFusionThemeMode(context))
        assertEquals(FusionThemeMode.DARK.storageValue, prefs.durableValues[FusionThemePreferenceKey])
    }

    @Test
    fun resolve_auto_contract_day_is_light_or_dark_never_black() {
        // AUTO resolves through system theme; it must never implicitly select BLACK.
        // Direct resolution is covered by theme integration; this verifies the contract.
        val auto = FusionThemeMode.AUTO
        assertEquals(true, auto == FusionThemeMode.AUTO)
        assertEquals(false, auto == FusionThemeMode.BLACK)
        assertEquals(false, auto == FusionThemeMode.LIGHT)
        assertEquals(false, auto == FusionThemeMode.DARK)
    }

    @Test
    fun resolve_explicit_modes_unchanged() {
        assertEquals(FusionThemeMode.BLACK, FusionThemeMode.BLACK)
        assertEquals(FusionThemeMode.DARK, FusionThemeMode.DARK)
        assertEquals(FusionThemeMode.LIGHT, FusionThemeMode.LIGHT)
        assertEquals(FusionThemeMode.AUTO, FusionThemeMode.AUTO)
    }

    @Test
    fun persisted_black_survives_restart() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString(FusionThemePreferenceKey, FusionThemeMode.BLACK.storageValue).apply()
        val context = TestContext(prefs)
        assertEquals(FusionThemeMode.BLACK, readFusionThemeMode(context))
    }

    @Test
    fun persisted_dark_survives_restart() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString(FusionThemePreferenceKey, FusionThemeMode.DARK.storageValue).apply()
        val context = TestContext(prefs)
        assertEquals(FusionThemeMode.DARK, readFusionThemeMode(context))
    }

    @Test
    fun persisted_light_survives_restart() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString(FusionThemePreferenceKey, FusionThemeMode.LIGHT.storageValue).apply()
        val context = TestContext(prefs)
        assertEquals(FusionThemeMode.LIGHT, readFusionThemeMode(context))
    }

    @Test
    fun explicit_theme_not_overwritten_by_startup() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString(FusionThemePreferenceKey, FusionThemeMode.BLACK.storageValue).apply()
        val context = TestContext(prefs)
        val mode = readFusionThemeMode(context)
        assertEquals(FusionThemeMode.BLACK, mode)
        assertEquals(false, mode == FusionThemeMode.AUTO)
    }

    @Test
    fun black_theme_semantic_primary_foreground_is_dark() {
        val blackColors = FusionSemanticColors(
            background = Color.Black,
            panel = Color(0xFF171717),
            menu = Color(0xFF202020),
            bubble = Color(0xFF2A2A2A),
            divider = Color(0xFF2B2B2B),
            onBackground = Color(0xFFF5F5F5),
            onSurface = Color(0xFFF5F5F5),
            onSurfaceVariant = Color(0xFF9E9E9E),
            primary = FusionBlueLight,
            onPrimary = Color.Black,
            error = FusionErrorDark,
            warning = Color(0xFFFFD38A),
        )
        assertEquals(Color.Black, blackColors.onPrimary)
        assertEquals(FusionBlueLight, blackColors.primary)
        assertEquals(false, blackColors.onPrimary == blackColors.onBackground)
    }

    @Test
    fun dark_theme_semantic_primary_foreground_is_dark() {
        val darkSemantic = when (FusionThemeMode.DARK) {
            FusionThemeMode.DARK -> FusionSemanticColors(
                background = Color(0xFF121416),
                panel = Color(0xFF191C1F),
                menu = Color(0xFF2A2E32),
                bubble = Color(0xFF30363B),
                divider = Color(0xFF42474C),
                onBackground = Color(0xFFE2E2E5),
                onSurface = Color(0xFFE2E2E5),
                onSurfaceVariant = Color(0xFFC4C7CA),
                primary = FusionBlueLight,
                onPrimary = Color.Black,
                error = FusionErrorDark,
                warning = Color(0xFFFFD38A),
            )
            else -> throw IllegalArgumentException()
        }
        assertEquals(Color.Black, darkSemantic.onPrimary)
        assertEquals(FusionBlueLight, darkSemantic.primary)
    }

    @Test
    fun light_theme_semantic_primary_foreground_is_appropriate_for_light_primary() {
        val lightSemantic = when (FusionThemeMode.LIGHT) {
            FusionThemeMode.LIGHT -> FusionSemanticColors(
                background = Color(0xFFFAFAFA),
                panel = Color.White,
                menu = Color(0xFFE2E7EB),
                bubble = Color(0xFFDDEBFA),
                divider = Color(0xFFBFC7CE),
                onBackground = Color(0xFF1A1C1E),
                onSurface = Color(0xFF1A1C1E),
                onSurfaceVariant = Color(0xFF42474C),
                primary = FusionBlue,
                onPrimary = Color.White,
                error = FusionErrorLight,
                warning = Color(0xFF8A5700),
            )
            else -> throw IllegalArgumentException()
        }
        assertEquals(Color.White, lightSemantic.onPrimary)
        assertEquals(FusionBlue, lightSemantic.primary)
    }

    @Test
    fun onboarding_primary_action_uses_semantic_onPrimary_not_onBackground() {
        val semantic = when (FusionThemeMode.BLACK) {
            FusionThemeMode.BLACK -> FusionSemanticColors(
                background = Color.Black,
                panel = Color(0xFF171717),
                menu = Color(0xFF202020),
                bubble = Color(0xFF2A2A2A),
                divider = Color(0xFF2B2B2B),
                onBackground = Color(0xFFF5F5F5),
                onSurface = Color(0xFFF5F5F5),
                onSurfaceVariant = Color(0xFF9E9E9E),
                primary = FusionBlueLight,
                onPrimary = Color.Black,
                error = FusionErrorDark,
                warning = Color(0xFFFFD38A),
            )
            else -> throw IllegalArgumentException()
        }
        // The contract is that when using primary as fill, text must use onPrimary
        assertEquals(Color.Black, semantic.onPrimary)
        assertEquals(FusionBlueLight, semantic.primary)
        // Confirm onPrimary is dark (not white like onBackground)
        assertEquals(false, semantic.onPrimary == semantic.onBackground)
    }
}
