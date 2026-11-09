package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.test.PlatformSharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class FusionCompetitionSurfacePolicyTest {
    @Test
    fun `missing selected path remains stored evidence and never becomes runnable`() {
        val context = testContext(temporaryFolder.newFolder("missing-app"))
        val missing = File(File(context.getExternalFilesDir(null), "models"), "gone.litertlm").absolutePath
        val prefs = PlatformSharedPreferences()
        prefs.edit().putString(LocalModelSelectedPathPreference, missing).commit()
        val stored = readSelectedModelPathEvidence(prefs)
        assertEquals(missing, stored)
        assertEquals(null, resolveRunnableSelectedLocalModelPath(context, "unknown", stored))
        assertEquals(missing, readSelectedModelPathEvidence(prefs))
        val readiness = resolveLocalModelReadinessTruth(context, prefs, "unknown", stored)
        assertEquals(FusionHealthStatus.FAIL,
            mapSelectedModelReadinessForHealth(readiness, false, false).status)
        // Only an explicit delete action clears the durable selection.
        prefs.edit().remove(LocalModelSelectedPathPreference).commit()
        assertEquals(null, readSelectedModelPathEvidence(prefs))
    }

    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun testContext(root: File): Context = object : android.content.ContextWrapper(null) {
        override fun getExternalFilesDir(type: String?): File = root
        override fun getFilesDir(): File = root
    }

    @Test
    fun `shared resolver uses actual validated explicit or installed built-in path`() {
        val root = temporaryFolder.newFolder("app")
        val context = testContext(root)
        val modelRoot = File(root, "models").apply { mkdirs() }
        val builtIn = FusionModelCatalog.builtIn.first { it.canRunLocalRuntime && !it.fileName.isNullOrBlank() }
        val explicit = File(modelRoot, "custom.litertlm").apply { writeText("test") }
        val installed = File(modelRoot, builtIn.fileName!!)
        assertEquals(explicit.canonicalPath, resolveRunnableSelectedLocalModelPath(context, builtIn.displayName, explicit.absolutePath))
        assertEquals(null, resolveRunnableSelectedLocalModelPath(context, builtIn.displayName, null))
        installed.writeText("test")
        val chatPath = resolveRunnableSelectedLocalModelPath(context, builtIn.displayName, null)
        val translationPath = resolveRunnableSelectedLocalModelPath(context, builtIn.displayName, null)
        assertEquals(installed.canonicalPath, chatPath)
        assertEquals(chatPath, translationPath)
        explicit.delete()
        assertEquals(installed.canonicalPath, resolveRunnableSelectedLocalModelPath(context, builtIn.displayName, explicit.absolutePath))
        installed.delete()
        assertEquals(null, resolveRunnableSelectedLocalModelPath(context, builtIn.displayName, explicit.absolutePath))
        val outside = temporaryFolder.newFile("outside.litertlm")
        assertEquals(null, resolveRunnableSelectedLocalModelPath(context, "unknown", outside.absolutePath))
    }


    @Test
    fun `app info title derives from package version name`() {
        assertEquals("Fusion 2.0.0-beta", resolveAppInfoTitle("2.0.0-beta"))
        assertEquals("Fusion 1.0.0", resolveAppInfoTitle("1.0.0"))
    }

    @Test
    fun `app info title handles blank or unknown version`() {
        assertEquals("Fusion unknown", resolveAppInfoTitle(""))
        assertEquals("Fusion unknown", resolveAppInfoTitle("   "))
    }

    @Test
    fun `stale marketing version literal must not appear in resolved title`() {
        val title = resolveAppInfoTitle("2.0.0-beta")
        assertFalse(title.contains("1.0 Beta"))
    }

    @Test
    fun `readiness truth is the same state for both presentation surfaces`() {
        val chat = resolveLocalModelReadinessTruth("Gemma", "/managed/model.litertlm", null, true)
        val settings = resolveLocalModelReadinessTruth("Gemma", "/managed/model.litertlm", null, true)
        assertEquals(chat, settings)
        assertTrue(chat.isRunnable)
        assertEquals("built-in:Gemma", chat.finalRunnablePath)
    }

    @Test
    fun `managed missing is unavailable`() {
        val truth = resolveLocalModelReadinessTruth("Gemma", "/managed/missing.litertlm", null, false)
        assertFalse(truth.isRunnable)
        assertEquals(null, truth.finalRunnablePath)
    }

    @Test
    fun `built-in runnable state is available and refresh invalidation observes removal`() {
        val available = resolveLocalModelReadinessTruth("Gemma", null, null, true)
        val removed = resolveLocalModelReadinessTruth("Gemma", null, null, false)
        assertTrue(available.isRunnable)
        assertFalse(removed.isRunnable)
    }

    @Test
    fun `model chip shows display name when model is runnable`() {
        assertEquals("Gemma 4 E2B-it", resolveLocalModelChipLabel("Gemma 4 E2B-it", hasRunnableModel = true))
    }

    @Test
    fun `model chip shows selection-required label when no model runnable`() {
        assertEquals(LocalModelSelectionRequiredLabel, resolveLocalModelChipLabel("Gemma 4 E2B-it", hasRunnableModel = false))
    }

    @Test
    fun `model chip selection-required label is in Korean`() {
        assertTrue(LocalModelSelectionRequiredLabel.contains("모델"))
    }

}
