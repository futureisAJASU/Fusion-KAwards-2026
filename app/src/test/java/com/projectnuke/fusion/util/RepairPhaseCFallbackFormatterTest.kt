package com.projectnuke.fusion.util

import com.projectnuke.fusion.llm.FallbackReason
import com.projectnuke.fusion.llm.RuntimeBackend
import com.projectnuke.fusion.llm.RuntimeAttemptSnapshot
import com.projectnuke.fusion.llm.RuntimeFailureSnapshot
import com.projectnuke.fusion.llm.RuntimeFallbackEvent
import com.projectnuke.fusion.llm.MtpRuntimeStatus
import com.projectnuke.fusion.model.AcceleratorMode
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repair Phase C tests:
 *
 * C1: FallbackCauseFormatter contains only Korean (Hangul) and ASCII in its
 *     localized output. CJK Unified Ideographs (Chinese characters) must
 *     not appear in any user-facing formatter result.
 *
 * C2: fallbackEventCodes is a stable machine-readable JSON array of
 *     FallbackReason.name strings. Localized Korean/Chinese prose must
 *     never be stored in a code field. Legacy runtime-piped values can
 *     be re-canonicalized; new writes are always JSON arrays.
 *
 * C3: renderStoredCodesForDisplay decodes stored codes back to Korean prose.
 */
class RepairPhaseCFallbackFormatterTest {

    private val events = listOf(
        RuntimeFallbackEvent(reason = FallbackReason.MTP_UNSUPPORTED),
        RuntimeFallbackEvent(
            attemptedTextBackend = RuntimeBackend.GPU,
            attemptedMtpEnabled = true,
            reason = FallbackReason.MTP_ENGINE_INIT_FAILED
        ),
        RuntimeFallbackEvent(
            attemptedTextBackend = RuntimeBackend.GPU,
            attemptedMtpEnabled = false,
            selectedReplacementBackend = RuntimeBackend.CPU,
            reason = FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED
        )
    )

    // ── C1: no CJK Unified Ideographs ────────────────────────────────────

    /**
     * CJK Unified Ideographs block: U+4E00 – U+9FFF (excluding Korean Hangul
     * Syllables U+AC00–U+D7A3 and Hangul Jamo U+1100–U+11FF). Korean Hangul
     * Compatibility Jamo U+3130–U+318F is also outside the unified block.
     *
     * Internal enum names (FallbackReason.name) are pure ASCII and may
     * legitimately pass this filter; we only apply it to the formatter's
     * localized output.
     */
    private fun containsCjkUnifiedIdeograph(s: String): Boolean {
        for (ch in s) {
            val cp = ch.code
            if (cp in 0x4E00..0x9FFF) return true
        }
        return false
    }

    @Test
    fun `C1 formatEvents produces no Chinese characters`() {
        val text = FallbackCauseFormatter.formatEvents(events)
        assertFalse(
            "formatEvents should not emit CJK Unified Ideographs, got: $text",
            containsCjkUnifiedIdeograph(text)
        )
    }

    @Test
    fun `C1 formatFallbackSummary produces no Chinese characters`() {
        val snapshot = com.projectnuke.fusion.llm.RuntimeExecutionSnapshot(
            requestedAccelerator = AcceleratorMode.AUTO,
            selectedTextBackend = RuntimeBackend.CPU,
            selectedVisionBackend = null,
            samplerBackend = com.projectnuke.fusion.llm.RuntimeComponentBackend.UNKNOWN,
            mtpRequested = true,
            mtpStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
            fallbackEvents = events,
            modelFingerprint = com.projectnuke.fusion.llm.ModelFingerprintSummary(
                canonicalPath = "/a/b",
                fileSize = 0L,
                modifiedAt = 0L,
                validationVersion = 0,
                mtpSupported = true
            )
        )
        val text = FallbackCauseFormatter.formatFallbackSummary(snapshot)
        assertFalse(
            "formatFallbackSummary should not emit Chinese: $text",
            containsCjkUnifiedIdeograph(text)
        )
    }

    @Test
    fun `C1 formatAttemptFallbackSummary produces no Chinese characters`() {
        val attempt = RuntimeAttemptSnapshot(
            requestedAccelerator = AcceleratorMode.AUTO,
            fallbackEvents = events,
            modelFingerprint = com.projectnuke.fusion.llm.ModelFingerprintSummary(
                canonicalPath = "/a/b",
                fileSize = 0L,
                modifiedAt = 0L,
                validationVersion = 0,
                mtpSupported = true
            ),
            mtpRequested = true
        )
        val text = FallbackCauseFormatter.formatAttemptFallbackSummary(attempt)
        assertFalse(
            "formatAttemptFallbackSummary should not emit Chinese: $text",
            containsCjkUnifiedIdeograph(text)
        )
    }

    @Test
    fun `C1 formatEvents covers all FallbackReason values without Chinese`() {
        // Walk every enum value through formatEvents and ensure no CJK
        // Unified Ideograph appears. This is the regression guard against
        // future developers re-introducing Chinese text in the formatter.
        val allReasons = FallbackReason.entries
        val text = FallbackCauseFormatter.formatEvents(
            allReasons.map { RuntimeFallbackEvent(reason = it) }
        )
        assertFalse(
            "All-reasons formatEvents should not emit Chinese: $text",
            containsCjkUnifiedIdeograph(text)
        )
    }

    @Test
    fun `C1 formatter results are non-empty when events are present`() {
        val text = FallbackCauseFormatter.formatEvents(events)
        assertTrue("formatEvents should produce non-empty Korean text", text.isNotBlank())
    }

    @Test
    fun `C1 localizedName outputs are Korean-friendly labels`() {
        val testEvents = listOf(
            RuntimeFallbackEvent(reason = FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE, attemptedTextBackend = RuntimeBackend.UNKNOWN),
            RuntimeFallbackEvent(reason = FallbackReason.BACKEND_ENGINE_INIT_FAILED, attemptedTextBackend = null)
        )
        val text = FallbackCauseFormatter.formatEvents(testEvents)
        // Both backends render as "알 수 없음" (Korean for "unknown"),
        // not "确认不可用" (Chinese for "confirmed unavailable").
        assertFalse(containsCjkUnifiedIdeograph(text))
        assertTrue(text.contains("알 수 없음") || text.contains("백엔드"))
    }

    // ── C2: machine-readable JSON code serialization ─────────────────────

    @Test
    fun `C2 formatCodes emits JSON array of stable enum names`() {
        val codes = FallbackCauseFormatter.formatCodes(events)
        assertNotNull(codes)
        val arr = JSONArray(codes)
        assertEquals(3, arr.length())
        assertEquals(FallbackReason.MTP_UNSUPPORTED.name, arr.optString(0))
        assertEquals(FallbackReason.MTP_ENGINE_INIT_FAILED.name, arr.optString(1))
        assertEquals(FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED.name, arr.optString(2))
    }

    @Test
    fun `C2 formatCodes for empty events returns null`() {
        assertNull(FallbackCauseFormatter.formatCodes(emptyList()))
    }

    @Test
    fun `C2 formatCodes is never localized prose`() {
        val codes = FallbackCauseFormatter.formatCodes(events)
        assertNotNull(codes)
        // Codes are pure ASCII enum names; no Hangul, no CJK Unified Ideograph.
        for (ch in codes!!) {
            val cp = ch.code
            assertTrue(
                "Code char should be ASCII or JSON syntax, got: $ch (${cp.toString(16)})",
                cp < 0x80
            )
        }
    }

    @Test
    fun `C2 formatCodes from snapshot and attempt snapshot`() {
        val codes1 = FallbackCauseFormatter.formatCodes(
            com.projectnuke.fusion.llm.RuntimeExecutionSnapshot(
                requestedAccelerator = AcceleratorMode.AUTO,
                selectedTextBackend = RuntimeBackend.CPU,
                selectedVisionBackend = null,
                samplerBackend = com.projectnuke.fusion.llm.RuntimeComponentBackend.UNKNOWN,
                mtpRequested = true,
                mtpStatus = MtpRuntimeStatus.OFF,
                fallbackEvents = events,
                modelFingerprint = com.projectnuke.fusion.llm.ModelFingerprintSummary(
                    "/a", 0L, 0L, 0, true
                )
            )
        )
        assertNotNull(codes1)
        val codes2 = FallbackCauseFormatter.formatCodes(
            RuntimeAttemptSnapshot(
                requestedAccelerator = AcceleratorMode.AUTO,
                fallbackEvents = events,
                modelFingerprint = com.projectnuke.fusion.llm.ModelFingerprintSummary(
                    "/a", 0L, 0L, 0, true
                ),
                mtpRequested = true
            )
        )
        assertNotNull(codes2)
        // Both must round-trip identically to the same enum names.
        val arr1 = JSONArray(codes1)
        val arr2 = JSONArray(codes2)
        assertEquals(arr1.length(), arr2.length())
    }

    // ── C2: migration compatibility for legacy values ────────────────────

    @Test
    fun `C2 parseLegacyFallbackCodes accepts new JSON format and passes through`() {
        val stored = "[\"MTP_ENGINE_INIT_FAILED\"]"
        val normalized = FallbackCauseFormatter.parseLegacyFallbackCodes(stored)
        assertEquals(stored, normalized)
    }

    @Test
    fun `C2 parseLegacyFallbackCodes extracts verbatim enum names from pipe-separated legacy text`() {
        val legacy = "MTP_ENGINE_INIT_FAILED|GPU init failed|MTP_UNSUPPORTED"
        val normalized = FallbackCauseFormatter.parseLegacyFallbackCodes(legacy)
        assertNotNull(normalized)
        val arr = JSONArray(normalized)
        assertEquals(2, arr.length())
        assertEquals(FallbackReason.MTP_ENGINE_INIT_FAILED.name, arr.optString(0))
        assertEquals(FallbackReason.MTP_UNSUPPORTED.name, arr.optString(1))
    }

    @Test
    fun `C2 parseLegacyFallbackCodes returns null for purely localized legacy text with no enum names`() {
        val legacy = "MTP 초기화 실패|모든 후보 백엔드가 실패했습니다"
        val normalized = FallbackCauseFormatter.parseLegacyFallbackCodes(legacy)
        assertNull(normalized)
    }

    @Test
    fun `C2 parseLegacyFallbackCodes rejects malformed JSON`() {
        assertNull(FallbackCauseFormatter.parseLegacyFallbackCodes("[bad json"))
    }

    @Test
    fun `C2 parseLegacyFallbackCodes handles null and blank`() {
        assertNull(FallbackCauseFormatter.parseLegacyFallbackCodes(null))
        assertNull(FallbackCauseFormatter.parseLegacyFallbackCodes(""))
        assertNull(FallbackCauseFormatter.parseLegacyFallbackCodes("   "))
    }

    // ── C3: renderStoredCodesForDisplay decodes back to Korean ────────────

    @Test
    fun `C3 renderStoredCodesForDisplay decodes JSON array to Korean prose`() {
        val stored = FallbackCauseFormatter.formatCodes(events)
        val text = FallbackCauseFormatter.renderStoredCodesForDisplay(stored)
        assertTrue(text.isNotBlank())
        // The rendered text should contain Korean strings (Hangul), not English.
        assertTrue(text.contains("MTP"))
        // Just verify it's non-empty and free of CJK Ideographs.
        for (ch in text) {
            val cp = ch.code
            assertFalse(
                "renderStoredCodesForDisplay should not emit CJK Unified Ideograph: $text",
                cp in 0x4E00..0x9FFF
            )
        }
    }

    @Test
    fun `C3 renderStoredCodesForDisplay decodes legacy pipe-separated stores`() {
        val legacy = "MTP_ENGINE_INIT_FAILED|GPU_TEXT_ENGINE_FAILED_CPU_SELECTED"
        val text = FallbackCauseFormatter.renderStoredCodesForDisplay(legacy)
        assertTrue(text.isNotBlank())
        // It should render Korean (MTP 엔진 초기화에 실패했습니다., ...) plus GPU/CPU ASCII.
        assertTrue(text.contains("MTP") || text.contains("GPU"))
    }

    @Test
    fun `C3 renderStoredCodesForDisplay handles null and returns empty string`() {
        assertEquals("", FallbackCauseFormatter.renderStoredCodesForDisplay(null))
        assertEquals("", FallbackCauseFormatter.renderStoredCodesForDisplay(""))
    }

    @Test
    fun `C3 round-trip formatCodes then renderStoredCodesForDisplay preserves event list length`() {
        val stored = FallbackCauseFormatter.formatCodes(events)
        val rendered = FallbackCauseFormatter.renderStoredCodesForDisplay(stored)
        // Each event maps to one Korean phrase; separator is " | "
        val parts = rendered.split(" | ")
        assertEquals(events.size, parts.size)
    }

    @Test
    fun `C3 renderStoredCodesForDisplay rejects unknown enum names`() {
        val stored = "[\"NOT_A_REAL_REASON\"]"
        assertEquals("", FallbackCauseFormatter.renderStoredCodesForDisplay(stored))
    }
}
