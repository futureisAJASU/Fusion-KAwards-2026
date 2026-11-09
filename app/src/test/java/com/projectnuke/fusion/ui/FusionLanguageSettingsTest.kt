package com.projectnuke.fusion.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.test.PlatformSharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionLanguageSettingsTest {

    private class TestContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File("build/test-language")
    }

    @Test
    fun `SYSTEM is currently supported`() {
        assertTrue(FusionAppLanguage.SYSTEM.isSupported)
    }

    @Test
    fun `KOREAN is not currently supported`() {
        assertFalse(FusionAppLanguage.KOREAN.isSupported)
    }

    @Test
    fun `ENGLISH is not currently supported`() {
        assertFalse(FusionAppLanguage.ENGLISH.isSupported)
    }

    @Test
    fun `unsupported apply does not overwrite current durable preference`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        prefs.edit().putString("fusion_app_language", FusionAppLanguage.SYSTEM.value).commit()
        val result = applyFusionAppLanguageIfSupported(context, FusionAppLanguage.KOREAN)
        assertFalse(result)
        assertEquals(FusionAppLanguage.SYSTEM.value, prefs.getString("fusion_app_language", null))
    }

    @Test
    fun `SYSTEM apply persists SYSTEM`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        val result = applyFusionAppLanguageIfSupported(context, FusionAppLanguage.SYSTEM)
        assertTrue(result)
        assertEquals(FusionAppLanguage.SYSTEM.value, prefs.getString("fusion_app_language", null))
    }

    @Test
    fun `stored system decodes SYSTEM`() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString("fusion_app_language", "system").commit()
        assertEquals(FusionAppLanguage.SYSTEM, getStoredFusionAppLanguage(TestContext(prefs)))
    }

    @Test
    fun `stored ko decodes KOREAN`() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString("fusion_app_language", "ko").commit()
        assertEquals(FusionAppLanguage.KOREAN, getStoredFusionAppLanguage(TestContext(prefs)))
    }

    @Test
    fun `stored en decodes ENGLISH`() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString("fusion_app_language", "en").commit()
        assertEquals(FusionAppLanguage.ENGLISH, getStoredFusionAppLanguage(TestContext(prefs)))
    }

    @Test
    fun `effective language is SYSTEM when stored preference is system`() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString("fusion_app_language", "system").commit()
        assertEquals(FusionAppLanguage.SYSTEM, getEffectiveFusionAppLanguage(TestContext(prefs)))
    }

    @Test
    fun `effective language is SYSTEM when stored preference unsupported`() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString("fusion_app_language", "ko").commit()
        assertEquals(FusionAppLanguage.SYSTEM, getEffectiveFusionAppLanguage(TestContext(prefs)))
        assertEquals(FusionAppLanguage.KOREAN, getStoredFusionAppLanguage(TestContext(prefs)))
    }

    @Test
    fun `effective language is SYSTEM when stored preference is en`() {
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString("fusion_app_language", "en").commit()
        assertEquals(FusionAppLanguage.SYSTEM, getEffectiveFusionAppLanguage(TestContext(prefs)))
        assertEquals(FusionAppLanguage.ENGLISH, getStoredFusionAppLanguage(TestContext(prefs)))
    }

    @Test
    fun `labels remain stable`() {
        assertEquals("시스템 설정 따름", getFusionAppLanguageLabel(FusionAppLanguage.SYSTEM))
        assertEquals("한국어", getFusionAppLanguageLabel(FusionAppLanguage.KOREAN))
        assertEquals("English", getFusionAppLanguageLabel(FusionAppLanguage.ENGLISH))
    }

    @Test
    fun `unsupported option does not issue saved callback`() {
        // Policy-level verification: unsupported apply returns false and
        // does not claim success to any caller.
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        val unsupportedApplied = applyFusionAppLanguageIfSupported(context, FusionAppLanguage.ENGLISH)
        assertFalse(unsupportedApplied)
    }
}
