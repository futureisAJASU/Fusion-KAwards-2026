package com.projectnuke.fusion.chat

import com.projectnuke.fusion.llm.FallbackReason
import com.projectnuke.fusion.llm.RuntimeFallbackEvent
import com.projectnuke.fusion.util.FallbackCauseFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDiagnosticsTest {
    @Test
    fun `multiple fallback events remain separate diagnostic causes`() {
        val codes = fallbackEventCodesForDiagnostics(
            listOf(
                RuntimeFallbackEvent(reason = FallbackReason.MTP_ENGINE_INIT_FAILED),
                RuntimeFallbackEvent(reason = FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED),
            )
        )

        assertEquals(
            listOf("MTP_ENGINE_INIT_FAILED", "GPU_TEXT_ENGINE_FAILED_CPU_SELECTED"),
            codes,
        )
        val localized = FallbackCauseFormatter.renderCodesForDisplay(codes)
        assertTrue(localized.contains(" | "))
        assertFalse(localized.contains("MTP_ENGINE_INIT_FAILED"))
        assertFalse(localized.contains("GPU_TEXT_ENGINE_FAILED_CPU_SELECTED"))
    }

    @Test
    fun `vision request remains true before a vision backend is selected`() {
        assertTrue(visionWasRequestedForDiagnostics(listOf("/missing/image.jpg")))
        assertFalse(visionWasRequestedForDiagnostics(emptyList()))
    }
}
