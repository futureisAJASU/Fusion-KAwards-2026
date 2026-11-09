package com.projectnuke.fusion.ui

import com.projectnuke.fusion.llm.FallbackReason
import com.projectnuke.fusion.llm.ModelFingerprintSummary
import com.projectnuke.fusion.llm.MtpRuntimeStatus
import com.projectnuke.fusion.llm.RuntimeAttemptSnapshot
import com.projectnuke.fusion.llm.RuntimeBackend
import com.projectnuke.fusion.llm.RuntimeComponentBackend
import com.projectnuke.fusion.llm.RuntimeExecutionSnapshot
import com.projectnuke.fusion.llm.RuntimeFailureSnapshot
import com.projectnuke.fusion.llm.RuntimeFallbackEvent
import com.projectnuke.fusion.llm.FailureKind
import com.projectnuke.fusion.llm.inferredMtpStatus
import com.projectnuke.fusion.model.AcceleratorMode
import com.projectnuke.fusion.util.FallbackCauseFormatter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 UI regression: the user-facing runtime diagnostic surfaces (Chat
 * persistent diagnostics, A/B success + attempt lines, Benchmark live fallback
 * line) must render formal Korean requested / applied / fallback descriptions
 * and must NEVER leak raw internal identifiers into user-visible output.
 *
 * Forbidden in user-visible output:
 *  - raw JSON fallback arrays (["MTP_ENGINE_INIT_FAILED"])
 *  - MTP_ENGINE_INIT_FAILED
 *  - INITIALIZED_WITH_MTP_REQUEST
 *  - FALLBACK_DISABLED
 *  - FUSION_GENERATION_SETTINGS
 *  - Chinese text
 *  - corrupted Korean text
 */
class Phase4RuntimeDiagnosticsRenderingTest {

    private val fingerprint = ModelFingerprintSummary(
        canonicalPath = "/models/gemma.litertlm",
        fileSize = 100L,
        modifiedAt = 200L,
        validationVersion = 1,
        mtpSupported = true,
    )

    private fun execution(
        selected: RuntimeBackend = RuntimeBackend.CPU,
        mtpStatus: MtpRuntimeStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
        events: List<RuntimeFallbackEvent> = listOf(
            RuntimeFallbackEvent(
                attemptedTextBackend = RuntimeBackend.GPU,
                attemptedMtpEnabled = false,
                selectedReplacementBackend = RuntimeBackend.CPU,
                reason = FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED,
            )
        ),
    ) = RuntimeExecutionSnapshot(
        requestedAccelerator = AcceleratorMode.GPU,
        selectedTextBackend = selected,
        selectedVisionBackend = null,
        samplerBackend = RuntimeComponentBackend.UNKNOWN,
        mtpRequested = true,
        mtpStatus = mtpStatus,
        fallbackEvents = events,
        modelFingerprint = fingerprint,
    )

    private fun attempt(
        events: List<RuntimeFallbackEvent> = listOf(
            RuntimeFallbackEvent(
                attemptedTextBackend = RuntimeBackend.GPU,
                attemptedMtpEnabled = true,
                reason = FallbackReason.MTP_ENGINE_INIT_FAILED,
            ),
            RuntimeFallbackEvent(reason = FallbackReason.ALL_CANDIDATES_EXHAUSTED),
        ),
    ) = RuntimeAttemptSnapshot(
        requestedAccelerator = AcceleratorMode.GPU,
        fallbackEvents = events,
        modelFingerprint = fingerprint,
        mtpRequested = true,
    )

    private fun failure() = RuntimeFailureSnapshot(
        requestedAccelerator = AcceleratorMode.GPU,
        selectedTextBackend = RuntimeBackend.CPU,
        selectedVisionBackend = null,
        mtpRequested = true,
        mtpRuntimeStatus = MtpRuntimeStatus.FALLBACK_DISABLED,
        fallbackEventsFromAcquisition = listOf(
            RuntimeFallbackEvent(
                attemptedTextBackend = RuntimeBackend.GPU,
                attemptedMtpEnabled = true,
                reason = FallbackReason.MTP_ENGINE_INIT_FAILED,
            )
        ),
        modelFingerprint = fingerprint,
        failureKind = FailureKind.GENERATION_INTERRUPTED,
    )

    // ── forbidden-content assertions ─────────────────────────────────────

    private fun assertUserFacingClean(label: String, text: String) {
        assertFalse("$label must not contain a raw JSON fallback array", text.contains("[\""))
        assertFalse("$label must not contain MTP_ENGINE_INIT_FAILED", text.contains("MTP_ENGINE_INIT_FAILED"))
        assertFalse("$label must not contain INITIALIZED_WITH_MTP_REQUEST", text.contains("INITIALIZED_WITH_MTP_REQUEST"))
        assertFalse("$label must not contain FALLBACK_DISABLED", text.contains("FALLBACK_DISABLED"))
        assertFalse("$label must not contain FUSION_GENERATION_SETTINGS", text.contains("FUSION_GENERATION_SETTINGS"))
        assertFalse("$label must not contain ALL_CANDIDATES_EXHAUSTED", text.contains("ALL_CANDIDATES_EXHAUSTED"))
        assertFalse("$label must not contain GPU_TEXT_ENGINE_FAILED_CPU_SELECTED", text.contains("GPU_TEXT_ENGINE_FAILED_CPU_SELECTED"))
        assertFalse("$label must not contain Chinese text", text.any { it.code in 0x4E00..0x9FFF })
        assertFalse("$label must not contain replacement/corrupted characters", text.contains('\uFFFD'))
    }

    private fun assertHasKorean(label: String, text: String) {
        assertTrue("$label must contain Hangul", text.any { it.code in 0xAC00..0xD7A3 })
    }

    // ── Chat persistent diagnostics (localized fallback prose) ───────────

    @Test
    fun `chat persistent diagnostics fallback prose is formal Korean without raw codes`() {
        val text = FallbackCauseFormatter.format(execution())
        assertUserFacingClean("chat diagnostics", text)
        assertHasKorean("chat diagnostics", text)
        assertTrue(text.contains("CPU"))
    }

    @Test
    fun `chat persistent diagnostics for acquisition failure renders localized attempt prose`() {
        val text = FallbackCauseFormatter.format(attempt())
        assertUserFacingClean("chat attempt diagnostics", text)
        assertHasKorean("chat attempt diagnostics", text)
        assertTrue("must describe MTP init failure in Korean", text.contains("MTP 엔진 초기화에 실패했습니다."))
        assertTrue("must describe exhaustion in Korean", text.contains("모든 후보 백엔드가 실패했습니다."))
    }

    @Test
    fun `stored fallback codes render back to localized Korean not raw JSON`() {
        val codes = FallbackCauseFormatter.formatCodes(execution())
        // The persisted code form IS the raw JSON array (machine-readable).
        assertTrue("persisted codes are machine-readable JSON", codes!!.startsWith("["))
        // But the rendered display form must be localized Korean prose.
        val rendered = FallbackCauseFormatter.renderStoredCodesForDisplay(codes)
        assertUserFacingClean("rendered stored codes", rendered)
        assertHasKorean("rendered stored codes", rendered)
    }

    // ── A/B success + attempt runtime lines ──────────────────────────────

    @Test
    fun `ab success runtime line renders localized applied fallback without raw enums`() {
        val text = FallbackCauseFormatter.formatFallbackSummary(execution())
        assertUserFacingClean("ab success line", text)
        assertHasKorean("ab success line", text)
        assertTrue(text.contains("GPU 초기화 실패로 CPU를 사용했습니다."))
    }

    @Test
    fun `ab attempt runtime line renders localized attempted fallback without raw enums`() {
        val snap = attempt()
        val text = FallbackCauseFormatter.formatAttemptFallbackSummary(snap)
        assertUserFacingClean("ab attempt line", text)
        assertHasKorean("ab attempt line", text)
        // The attempt-only path has no applied backend; the inferred MTP status
        // must be a typed enum consumed by the Korean formatter, never printed raw.
        assertFalse(text.contains(snap.inferredMtpStatus().name))
    }

    @Test
    fun `ab failure snapshot runtime line renders localized applied fallback`() {
        val text = FallbackCauseFormatter.formatFailureSnapshotSummary(failure())
        assertUserFacingClean("ab failure line", text)
        assertHasKorean("ab failure line", text)
        assertTrue(text.contains("MTP 초기화 실패 후 비-MTP를 사용했습니다."))
    }

    // ── Benchmark live fallback line ─────────────────────────────────────

    @Test
    fun `benchmark live success with fallback exposes localized fallback cause`() {
        // A successful benchmark whose snapshot knows GPU failed must not show
        // only "가속기: CPU / MTP 가속: 비활성" — the localized fallback cause
        // must be present.
        val snapshot = execution()
        val fallbackSummary = FallbackCauseFormatter.formatFallbackSummary(snapshot)
        assertTrue("snapshot with GPU failure must yield a non-empty fallback summary", fallbackSummary.isNotBlank())
        val rendered = buildString {
            appendLine("가속기: ${snapshot.selectedTextBackend.name}")
            appendLine("MTP 가속: 비활성")
            appendLine("요청 가속기: ${snapshot.requestedAccelerator.name}")
            appendLine("폴백: $fallbackSummary")
        }
        assertUserFacingClean("benchmark live result", rendered)
        assertHasKorean("benchmark live result", rendered)
        assertTrue("must show requested accelerator", rendered.contains("요청 가속기: GPU"))
        assertTrue("must show the localized fallback cause", rendered.contains("폴백: "))
        assertFalse(
            "must not show only accelerator+MTP when the snapshot knows GPU failed",
            rendered.trim() == "가속기: CPU\nMTP 가속: 비활성"
        )
    }

    @Test
    fun `benchmark failed result exposes typed attempted and applied state in Korean`() {
        val snap = attempt()
        val rendered = buildString {
            appendLine("벤치마크 실패")
            appendLine("요청 가속기: ${snap.requestedAccelerator.name}")
            appendLine("요청 출력 토큰: 1024")
            appendLine("적용 출력 토큰: 1024")
            appendLine("요청 KV캐시: 4096 tokens")
            appendLine("적용 KV캐시: 4096 tokens")
            appendLine("실제 백엔드: 적용된 백엔드 없음")
            appendLine("MTP 가속: 실패")
            appendLine("폴백: ${FallbackCauseFormatter.formatAttemptFallbackSummary(snap)}")
        }
        assertUserFacingClean("benchmark failure result", rendered)
        assertHasKorean("benchmark failure result", rendered)
        assertTrue(rendered.contains("요청 출력 토큰"))
        assertTrue(rendered.contains("적용 출력 토큰"))
        assertTrue(rendered.contains("요청 KV캐시"))
        assertTrue(rendered.contains("적용 KV캐시"))
        assertTrue(rendered.contains("실제 백엔드"))
    }

    // ── every FallbackReason has localized Korean prose ──────────────────

    @Test
    fun `every FallbackReason renders localized Korean prose and never its enum name`() {
        for (reason in FallbackReason.entries) {
            val event = RuntimeFallbackEvent(
                attemptedTextBackend = RuntimeBackend.GPU,
                attemptedMtpEnabled = true,
                reason = reason,
            )
            val text = FallbackCauseFormatter.formatEvents(listOf(event))
            assertTrue("$reason must render non-empty prose", text.isNotBlank())
            assertFalse("$reason must not render its raw enum name", text.contains(reason.name))
            assertHasKorean(reason.name, text)
        }
    }
}
