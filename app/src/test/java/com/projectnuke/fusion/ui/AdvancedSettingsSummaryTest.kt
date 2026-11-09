package com.projectnuke.fusion.ui

import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.model.LocalContextMode
import com.projectnuke.fusion.model.LocalModelSettingsProfileStore
import com.projectnuke.fusion.model.RuntimePerformanceMode
import com.projectnuke.fusion.model.RuntimePerformancePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedSettingsSummaryTest {
    @Test
    fun `applied summary has one compact representation and valid separators`() {
        val lines = buildAdvancedSettingsAppliedSummary(
            selectedModel = "custom-model",
            accelerator = AcceleratorMode.GPU,
            mtpEnabled = true,
            maxTokensText = "2048",
            contextMode = LocalContextMode.AUTO,
            contextCapacityText = "8192",
            temperatureText = "0.70",
            topKText = "40",
            topPText = "0.90",
            compactEnabled = true,
            compactTriggerText = "80",
            recentHistoryText = "25",
            reasoningEnabled = false,
            webSearchEnabled = false,
        )

        assertEquals(6, lines.size)
        assertEquals(1, lines.count { it.startsWith("출력:") })
        assertEquals(1, lines.count { it.startsWith("온도 ") })
        assertEquals(1, lines.count { it.startsWith("자동 압축:") })
        assertFalse(lines.any { it.contains("maxTokens=") })
        assertFalse(lines.any { it.contains('쨌') || it.contains('夷') })
        assertTrue(lines.any { it.contains("자동 (8192 토큰)") })
        assertTrue(lines.any { it.contains("GPU · MTP: 켜짐") })
    }
    @Test
    fun `profile store preference changes are refresh signals`() {
        assertTrue(isLocalModelSettingsProfilePreference(LocalModelSettingsProfileStore.KEY))
        assertFalse(isLocalModelSettingsProfilePreference("unrelated_key"))
        assertFalse(isLocalModelSettingsProfilePreference(null))
    }

    @Test
    fun `runtime performance summary distinguishes gpu fallback from active cpu`() {
        val preference = RuntimePerformancePreference(RuntimePerformanceMode.AUTO, 50)
        assertEquals("성능: 자동 · CPU fallback 2 threads", buildRuntimePerformanceSummaryLine(preference, 2, AcceleratorMode.GPU).replace("CPU 폴백", "CPU fallback"))
        assertEquals("성능: 자동 · CPU 1 thread", buildRuntimePerformanceSummaryLine(preference, 1, AcceleratorMode.CPU))
    }
}
