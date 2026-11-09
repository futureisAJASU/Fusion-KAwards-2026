package com.projectnuke.fusion.ui

import android.content.Context
import com.projectnuke.fusion.model.LocalGenerationSettingsPolicy
import com.projectnuke.fusion.test.PlatformSharedPreferences
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import com.projectnuke.fusion.ui.theme.FusionThemePreferenceKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings-restore durability contract.
 *
 * [PlatformSharedPreferences] models the real SharedPreferences boundary:
 * editor mutations become PROCESS-VISIBLE before [SharedPreferences.Editor.commit]
 * reports whether disk persistence succeeded. A restore that only checks the
 * commit result therefore leaves process-visible restored values behind while
 * disk keeps the pre-restore state — the user is told restore FAILED yet the
 * running process already uses the uncommitted values, and a restart snaps
 * back to the old ones.
 *
 * Required invariant:
 * - commit succeeds  -> process == durable == restored state
 * - commit fails     -> result CommitFailed, process and durable both back at
 *                       the exact pre-restore values, scoped to the keys the
 *                       restore itself mutated.
 */
class SettingsBackupRestoreDurabilityTest {

    private class TestContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    private class ModelRootContext(private val modelRoot: File) : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
        override fun getExternalFilesDir(type: String?): File? = modelRoot
    }

    private fun seeded(vararg entries: Pair<String, Any?>): PlatformSharedPreferences =
        PlatformSharedPreferences().apply {
            entries.forEach { (key, value) ->
                values[key] = value
                durableValues[key] = value
            }
        }

    private fun payload(vararg settingsFields: Pair<String, Any?>): String {
        val settings = JSONObject()
        settingsFields.forEach { (key, value) -> settings.put(key, value ?: JSONObject.NULL) }
        return JSONObject()
            .put("schemaVersion", FusionSettingsBackupSchemaVersion)
            .put("app", "Fusion")
            .put("settings", settings)
            .put("modelLibrary", JSONObject())
            .toString()
    }

    @Test
    fun `cancellation during failed commit still rolls back process and durable state`() = runTest {
        val prefs = seeded(
            LocalGenerationSettingsPolicy.KEY_MAX_TOKENS to 4000,
            "selected_model_path" to "/models/kept.litertlm",
            "some_unrelated_key" to "keep-me",
        )
        val before = prefs.values.toMap()
        prefs.enqueueCommitResults(false, true)
        val restore = async(start = CoroutineStart.LAZY) {
            restoreSettingsBackupJson(
                TestContext(), prefs,
                payload("maxTokens" to 1024, "selectedModel" to "New Model"),
            )
        }
        prefs.onCommitBeforeResult { restore.cancel() }
        restore.start()

        assertTrue(runCatching { restore.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals("rollback must finish before the cancelled restore settles", 2, prefs.commitCalls.get())
        assertEquals(before, prefs.values)
        assertEquals("restart must see the same restored snapshot", before, prefs.durableValues)
    }

    @Test
    fun `cancellation during successful commit preserves the committed restore`() = runTest {
        val prefs = seeded(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS to 4000)
        val restore = async(start = CoroutineStart.LAZY) {
            restoreSettingsBackupJson(TestContext(), prefs, payload("maxTokens" to 1024))
        }
        prefs.onCommitBeforeResult { restore.cancel() }
        restore.start()

        assertTrue(runCatching { restore.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(1, prefs.commitCalls.get())
        assertEquals(1024, prefs.values[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS])
        assertEquals(prefs.values, prefs.durableValues)
    }

    // ── P1.1 failed commit must not leave process-only restored values ────

    @Test
    fun `failed restore commit rolls process and durable prefs back to pre-restore values`() = runTest {
        val prefs = seeded(
            LocalGenerationSettingsPolicy.KEY_MAX_TOKENS to 4000,
            LocalGenerationSettingsPolicy.KEY_KV_CACHE to 8192,
            FusionThemePreferenceKey to FusionThemeMode.LIGHT.storageValue,
            "selected_model" to "Old Model",
        )
        prefs.enqueueCommitResults(false)

        val result = restoreSettingsBackupJson(
            TestContext(),
            prefs,
            payload(
                "maxTokens" to 1024,
                "kvCacheCapacityTokens" to 4096,
                "themeMode" to FusionThemeMode.DARK.storageValue,
                "selectedModel" to "New Model",
            ),
        )

        assertEquals(SettingsRestoreResult.CommitFailed, result)
        assertEquals(4000, prefs.values[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS])
        assertEquals(8192, prefs.values[LocalGenerationSettingsPolicy.KEY_KV_CACHE])
        assertEquals(FusionThemeMode.LIGHT.storageValue, prefs.values[FusionThemePreferenceKey])
        assertEquals("Old Model", prefs.values["selected_model"])

        assertEquals(4000, prefs.durableValues[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS])
        assertEquals(8192, prefs.durableValues[LocalGenerationSettingsPolicy.KEY_KV_CACHE])
        assertEquals(FusionThemeMode.LIGHT.storageValue, prefs.durableValues[FusionThemePreferenceKey])
        assertEquals("Old Model", prefs.durableValues["selected_model"])
    }

    // ── P1.2 removed key is part of the mutation-owned key set ────────────

    @Test
    fun `failed restore commit restores a key the restore removed`() = runTest {
        val prefs = seeded(
            "selected_model_path" to "/models/kept.litertlm",
            "selected_model" to "Old Model",
        )
        prefs.enqueueCommitResults(false)

        val result = restoreSettingsBackupJson(
            TestContext(),
            prefs,
            payload("selectedModel" to "New Model"),
        )

        assertEquals(SettingsRestoreResult.CommitFailed, result)
        assertTrue(
            "selected_model_path is removed by restore and must be rolled back",
            prefs.values.containsKey("selected_model_path"),
        )
        assertEquals("/models/kept.litertlm", prefs.values["selected_model_path"])
        assertEquals("/models/kept.litertlm", prefs.durableValues["selected_model_path"])
        assertEquals("Old Model", prefs.values["selected_model"])
        assertEquals("Old Model", prefs.durableValues["selected_model"])
    }

    // ── P1.3 rollback never touches unrelated keys ────────────────────────

    @Test
    fun `failed restore commit leaves unrelated keys untouched`() = runTest {
        val prefs = seeded(
            LocalGenerationSettingsPolicy.KEY_MAX_TOKENS to 4000,
            "some_unrelated_key" to "keep-me",
        )
        prefs.enqueueCommitResults(false)

        val result = restoreSettingsBackupJson(
            TestContext(),
            prefs,
            payload("maxTokens" to 1024),
        )

        assertEquals(SettingsRestoreResult.CommitFailed, result)
        assertEquals("keep-me", prefs.values["some_unrelated_key"])
        assertEquals("keep-me", prefs.durableValues["some_unrelated_key"])
        assertEquals(4000, prefs.values[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS])
    }

    @Test
    fun `failed restore rollback does not overwrite a newer write to the same key`() = runTest {
        val key = LocalGenerationSettingsPolicy.KEY_MAX_TOKENS
        val prefs = seeded(key to 4000)
        prefs.enqueueCommitResults(false)
        prefs.onCommitBeforeResult {
            // Model an independent settings writer that runs after restore made
            // its process-visible value but before restore observes commit failure.
            prefs.values[key] = 8000
            prefs.durableValues[key] = 8000
        }

        val result = restoreSettingsBackupJson(TestContext(), prefs, payload("maxTokens" to 1024))

        assertEquals(SettingsRestoreResult.CommitFailed, result)
        assertEquals("restore and rollback commits must both run", 2, prefs.commitCalls.get())
        assertEquals("newer process value must survive rollback", 8000, prefs.values[key])
        assertEquals("newer durable value must survive rollback", 8000, prefs.durableValues[key])
    }

    // ── P1.4 successful restore semantics unchanged ───────────────────────

    @Test
    fun `successful restore commits process and durable restored values`() = runTest {
        val prefs = seeded(
            LocalGenerationSettingsPolicy.KEY_MAX_TOKENS to 4000,
            LocalGenerationSettingsPolicy.KEY_KV_CACHE to 8192,
            FusionThemePreferenceKey to FusionThemeMode.LIGHT.storageValue,
            "selected_model" to "Old Model",
        )

        val result = restoreSettingsBackupJson(
            TestContext(),
            prefs,
            payload(
                "maxTokens" to 1024,
                "kvCacheCapacityTokens" to 4096,
                "themeMode" to FusionThemeMode.DARK.storageValue,
                "selectedModel" to "New Model",
            ),
        )

        assertEquals(SettingsRestoreResult.Success, result)
        assertEquals(1024, prefs.values[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS])
        assertEquals(4096, prefs.values[LocalGenerationSettingsPolicy.KEY_KV_CACHE])
        assertEquals(FusionThemeMode.DARK.storageValue, prefs.values[FusionThemePreferenceKey])
        assertEquals("New Model", prefs.values["selected_model"])
        assertEquals(1, prefs.commitCalls.get())

        assertEquals(1024, prefs.durableValues[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS])
        assertEquals(4096, prefs.durableValues[LocalGenerationSettingsPolicy.KEY_KV_CACHE])
        assertEquals(FusionThemeMode.DARK.storageValue, prefs.durableValues[FusionThemePreferenceKey])
        assertEquals("New Model", prefs.durableValues["selected_model"])
    }

    // ── P1.5 ModelPathMissing semantics unchanged ─────────────────────────

    @Test
    fun `model path missing still restores other accepted settings and retains the path`() = runTest {
        val modelRoot = Files.createTempDirectory("fusion-model-root").toFile()
        val missingPath = File(modelRoot, "elsewhere.litertlm").absolutePath
        val prefs = seeded(
            LocalGenerationSettingsPolicy.KEY_MAX_TOKENS to 4000,
            LocalGenerationSettingsPolicy.KEY_KV_CACHE to 8192,
            FusionThemePreferenceKey to FusionThemeMode.LIGHT.storageValue,
            "selected_model_path" to missingPath,
            "selected_model" to "Old Model",
        )

        val result = restoreSettingsBackupJson(
            ModelRootContext(modelRoot),
            prefs,
            payload(
                "maxTokens" to 1024,
                "kvCacheCapacityTokens" to 4096,
                "themeMode" to FusionThemeMode.DARK.storageValue,
                "selectedModel" to "New Model",
                "selectedModelPath" to missingPath,
            ),
        )

        assertEquals(SettingsRestoreResult.ModelPathMissing, result)
        assertEquals(1024, prefs.values[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS])
        assertEquals(4096, prefs.values[LocalGenerationSettingsPolicy.KEY_KV_CACHE])
        assertEquals(FusionThemeMode.DARK.storageValue, prefs.values[FusionThemePreferenceKey])
        assertEquals(missingPath, prefs.values["selected_model_path"])
        assertEquals("Old Model", prefs.values["selected_model"])

        assertEquals(1024, prefs.durableValues[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS])
        assertEquals(missingPath, prefs.durableValues["selected_model_path"])
    }

    // ── 1B fatal parser Error must not become InvalidJson ─────────────────

    @Test
    fun `fatal parser Error propagates instead of becoming InvalidJson`() = runTest {
        val prefs = seeded(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS to 4000)
        val linkError = LinkageError("native json bind failed")

        val thrown = runCatching {
            restoreSettingsBackupJson(
                TestContext(),
                prefs,
                payload("maxTokens" to 1024),
                jsonFactory = { throw linkError },
            )
        }.exceptionOrNull()

        assertEquals(linkError, thrown)
        assertEquals("no preference commit may occur", 0, prefs.commitCalls.get())
        assertEquals(4000, prefs.values[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS])
    }

    @Test
    fun `cancellation during root parse propagates instead of becoming InvalidJson`() = runTest {
        val prefs = seeded(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS to 4000)
        val cancellation = kotlinx.coroutines.CancellationException("cancelled")

        val thrown = runCatching {
            restoreSettingsBackupJson(
                TestContext(),
                prefs,
                payload("maxTokens" to 1024),
                jsonFactory = { throw cancellation },
            )
        }.exceptionOrNull()

        assertEquals(cancellation, thrown)
        assertEquals(0, prefs.commitCalls.get())
    }

    @Test
    fun `ordinary parser failure is still classified as InvalidJson`() = runTest {
        val prefs = seeded(LocalGenerationSettingsPolicy.KEY_MAX_TOKENS to 4000)

        assertEquals(
            SettingsRestoreResult.InvalidJson,
            restoreSettingsBackupJson(
                TestContext(),
                prefs,
                "{not a json object",
            ),
        )
        assertEquals(0, prefs.commitCalls.get())
        assertEquals(4000, prefs.values[LocalGenerationSettingsPolicy.KEY_MAX_TOKENS])

        assertEquals(
            SettingsRestoreResult.InvalidJson,
            restoreSettingsBackupJson(
                TestContext(),
                prefs,
                payload("maxTokens" to 1024),
                jsonFactory = { throw RuntimeException("malformed") },
            ),
        )
        assertEquals(0, prefs.commitCalls.get())
        assertNull(prefs.values["themeMode"])
    }
}
