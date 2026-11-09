package com.projectnuke.fusion.util

import com.projectnuke.fusion.llm.FallbackReason
import com.projectnuke.fusion.llm.MtpRuntimeStatus
import com.projectnuke.fusion.llm.ModelFingerprintSummary
import com.projectnuke.fusion.llm.RuntimeAttemptSnapshot
import com.projectnuke.fusion.llm.RuntimeBackend
import com.projectnuke.fusion.llm.RuntimeExecutionSnapshot
import com.projectnuke.fusion.llm.RuntimeFallbackEvent
import com.projectnuke.fusion.model.AcceleratorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3: presentation-state tests for fallback UI wiring.
 */
class RepairPhase3FallbackUiWiringTest {

    private val testFingerprint = ModelFingerprintSummary(
        canonicalPath = "/models/test.litertlm",
        fileSize = 12345L,
        modifiedAt = 1000000L,
        validationVersion = 1,
        mtpSupported = true
    )

    @Test
    fun `toKoreanMtpStatus returns Korean strings`() {
        val label = MtpRuntimeStatus.INITIALIZED_WITH_MTP_REQUEST.toKoreanMtpStatus()
        assertFalse("label must not contain enum name: $label", label.contains("INITIALIZED"))
        assertEquals("MTP 요청으로 초기화됨", label)
    }

    @Test
    fun `formatEvents does not contain raw JSON`() {
        val events = listOf(RuntimeFallbackEvent(
            reason = FallbackReason.MTP_ENGINE_INIT_FAILED,
            attemptedTextBackend = RuntimeBackend.GPU,
            attemptedMtpEnabled = true
        ))
        val output = FallbackCauseFormatter.formatEvents(events)
        assertFalse(output.contains("["))
        assertFalse(output.contains("]"))
        assertFalse(output.contains("MTP_ENGINE_INIT_FAILED"))
    }

    @Test
    fun `renderStoredCodesForDisplay returns Korean from stored JSON`() {
        val codes = FallbackCauseFormatter.formatCodes(
            listOf(RuntimeFallbackEvent(reason = FallbackReason.MTP_ENGINE_INIT_FAILED,
                attemptedTextBackend = RuntimeBackend.GPU, attemptedMtpEnabled = true))
        ) ?: ""
        val rendered = FallbackCauseFormatter.renderStoredCodesForDisplay(codes)
        assertFalse("must not contain JSON: $rendered", rendered.contains("["))
        assertFalse("must not contain enum: $rendered", rendered.contains("MTP_ENGINE_INIT_FAILED"))
        assertTrue("must contain Korean", rendered.isNotBlank())
    }

    @Test
    fun `renderStoredCodesForDisplay handles empty input`() {
        assertEquals("", FallbackCauseFormatter.renderStoredCodesForDisplay(""))
    }

    @Test
    fun `formatCodes returns JSON array`() {
        val codes = FallbackCauseFormatter.formatCodes(
            listOf(RuntimeFallbackEvent(reason = FallbackReason.MTP_UNSUPPORTED,
                attemptedTextBackend = RuntimeBackend.GPU, attemptedMtpEnabled = true))
        )
        assertEquals("""["MTP_UNSUPPORTED"]""", codes)
    }

    private fun hasChineseChars(text: String): Boolean =
        text.any { it.code in 0x4E00..0x9FFF }

    @Test
    fun `no Chinese characters in fallback display output`() {
        for (reason in FallbackReason.values()) {
            val events = listOf(RuntimeFallbackEvent(
                reason = reason,
                attemptedTextBackend = RuntimeBackend.GPU,
                attemptedMtpEnabled = true
            ))
            val output = FallbackCauseFormatter.formatEvents(events)
            assertFalse("formatEvents output for $reason should not contain Chinese: $output",
                nonHangulChineseCjk(output))
        }
    }

    fun nonHangulChineseCjk(text: String): Boolean = hasChineseChars(text)    
}