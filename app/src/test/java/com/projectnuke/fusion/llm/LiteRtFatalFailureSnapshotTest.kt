package com.projectnuke.fusion.llm

import android.content.Context
import android.content.SharedPreferences
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.test.PlatformSharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiteRtFatalFailureSnapshotTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private class TestContext(private val directory: File, private val prefs: SharedPreferences) :
        android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = directory
        override fun getSystemService(name: String): Any? = null
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }

    @Test
    fun `fatal generation captures actual runtime provenance before unloading`() {
        listOf(OutOfMemoryError("allocation"), StackOverflowError("stack"), LinkageError("symbol")).forEach { error ->
            var flag = false
            val wrapper = LiteRtLlmEngine(
                context = TestContext(temporaryFolder.root, PlatformSharedPreferences()),
                // Wrapper ownership only: Engine construction does not initialize native inference.
                engineFactory = { config ->
                    if (config.backend !is Backend.CPU) throw IllegalStateException("GPU init failed")
                    Engine(config)
                },
                flagSetter = { enabled -> flag = enabled; true },
                flagReader = { flag },
                mtpCapabilityProbe = { false },
            )
            val profile = RequestedEngineProfile(
                modelPath = temporaryFolder.newFile().absolutePath,
                accelerator = AcceleratorMode.AUTO,
                mtpRequested = false,
                kvCacheCapacityTokens = 4096,
                enableVisionBackend = false,
            )
            LiteRtLlmEngine::class.java.getDeclaredMethod("getOrCreateEngine", RequestedEngineProfile::class.java)
                .apply { isAccessible = true }.invoke(wrapper, profile)
            val classify = LiteRtLlmEngine::class.java.getDeclaredMethod(
                "classifyGenerationException", Throwable::class.java, Boolean::class.javaPrimitiveType,
                RequestedEngineProfile::class.java,
            ).apply { isAccessible = true }
            val outcome = classify.invoke(wrapper, error, false, profile) as GenerationOutcome.Failure

            assertEquals(FailureKind.MODEL_LOAD_FAILED, outcome.kind)
            assertNull("the unsafe native runtime must still be retired", wrapper.lastRuntimeSelection)
            assertNotNull("failure must retain the runtime that actually ran", outcome.failureSnapshot)
            val snapshot = requireNotNull(outcome.failureSnapshot)
            assertEquals(RuntimeBackend.CPU, snapshot.selectedTextBackend)
            assertEquals(AcceleratorMode.AUTO, snapshot.requestedAccelerator)
            assertTrue(snapshot.fallbackEventsFromAcquisition.any {
                it.reason == FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED
            })
            assertEquals(FailureKind.MODEL_LOAD_FAILED, snapshot.failureKind)
        }
    }
}
