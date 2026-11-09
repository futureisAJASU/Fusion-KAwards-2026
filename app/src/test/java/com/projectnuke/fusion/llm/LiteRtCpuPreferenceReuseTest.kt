package com.projectnuke.fusion.llm

import android.content.Context
import android.content.SharedPreferences
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.RequestedEngineProfile
import com.projectnuke.fusion.model.RuntimeCpuDeviceSignals
import com.projectnuke.fusion.model.RuntimePerformanceMode
import com.projectnuke.fusion.model.RuntimePerformancePreference
import com.projectnuke.fusion.model.RuntimePerformancePreferenceStore
import com.projectnuke.fusion.test.PlatformSharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the actual wrapper acquisition/reuse path without initializing native inference. */
class LiteRtCpuPreferenceReuseTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private class TestContext(private val directory: File, private val prefs: SharedPreferences) :
        android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = directory
        override fun getSystemService(name: String): Any? = null
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }

    @Test
    fun `manual to constrained auto reuse retains the lower budget after cooling`() {
        assertAutoBudgetRetained(AcceleratorMode.CPU, cpuVisionFallback = false)
    }

    @Test
    fun `gpu text with cpu vision also retains the auto budget after preference reuse`() {
        assertAutoBudgetRetained(AcceleratorMode.GPU, cpuVisionFallback = true)
    }

    private fun assertAutoBudgetRetained(accelerator: AcceleratorMode, cpuVisionFallback: Boolean) {
        val prefs = PlatformSharedPreferences()
        val preferenceStore = RuntimePerformancePreferenceStore(prefs)
        preferenceStore.write(RuntimePerformancePreference(RuntimePerformanceMode.MANUAL, 0))
        var signals = RuntimeCpuDeviceSignals(availableProcessors = 8, thermalStatus = 2)
        var flag = false
        var builds = 0
        val wrapper = LiteRtLlmEngine(
            context = TestContext(temporaryFolder.root, prefs),
            engineFactory = { config ->
                builds++
                if (cpuVisionFallback && config.visionBackend !is Backend.CPU) {
                    throw IllegalStateException("GPU vision unavailable")
                }
                Engine(config)
            },
            flagSetter = { enabled -> flag = enabled; true },
            flagReader = { flag },
            mtpCapabilityProbe = { false },
            cpuDeviceSignals = { signals },
        )
        val profile = RequestedEngineProfile(
            modelPath = temporaryFolder.newFile("model.litertlm").absolutePath,
            accelerator = accelerator,
            mtpRequested = false,
            kvCacheCapacityTokens = 4096,
            enableVisionBackend = cpuVisionFallback,
        )
        val acquire = LiteRtLlmEngine::class.java.getDeclaredMethod("getOrCreateEngine", RequestedEngineProfile::class.java)
            .apply { isAccessible = true }
        val first = acquire.invoke(wrapper, profile)
        val initialBuilds = builds
        preferenceStore.write(RuntimePerformancePreference())
        assertSame(first, acquire.invoke(wrapper, profile))

        signals = signals.copy(thermalStatus = RuntimeCpuDeviceSignals.THERMAL_NONE)
        assertTrue("cooling must keep the existing AUTO runtime warm", wrapper.isRuntimeReusableFor(profile))
        assertSame("AUTO must retain this runtime's lower budget", first, acquire.invoke(wrapper, profile))
        assertEquals("preference changes with the same thread count must not rebuild", initialBuilds, builds)
    }
}
