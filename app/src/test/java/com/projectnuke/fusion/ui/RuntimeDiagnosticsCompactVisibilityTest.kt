package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.RequestedConfig
import com.projectnuke.fusion.chat.RuntimeDiagnostics
import com.projectnuke.fusion.llm.MtpRuntimeStatus
import com.projectnuke.fusion.model.AcceleratorMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDiagnosticsCompactVisibilityTest {
    @Test
    fun `compact diagnostics expose context and truthful compact state`() {
        val line = formatLastRuntimeDiagnosticsLine(
            RuntimeDiagnostics(
                requestedConfig = RequestedConfig(
                    modelId = "local",
                    accelerator = AcceleratorMode.AUTO,
                    kvCacheCapacityTokens = 8192,
                    mtpRequested = false,
                    visionRequested = false,
                    speculativeDecodingEnabled = false,
                ),
                attemptedBackend = null,
                selectedBackend = "CPU",
                mtpRequested = false,
                mtpRuntimeStatus = MtpRuntimeStatus.OFF,
                fallbackEventCodes = emptyList(),
                attemptSnapshot = null,
                failureSnapshot = null,
                contextMode = "AUTO",
                effectiveKvCapacityTokens = 8192,
                compactEnabled = true,
                compactConsidered = true,
                compactUsed = true,
                fallbackTrimmed = false,
            ),
        ) ?: error("diagnostic line should be available")

        assertTrue(line.contains("컨텍스트: 자동 · 8,192"))
        assertTrue(line.contains("자동 압축: 사용됨"))
        assertFalse(line.contains("AUTO"))
    }
}
