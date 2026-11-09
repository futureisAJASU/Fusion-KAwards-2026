package com.projectnuke.fusion.util

import com.projectnuke.fusion.llm.FallbackReason
import com.projectnuke.fusion.llm.RuntimeBackend
import com.projectnuke.fusion.llm.RuntimeExecutionSnapshot
import com.projectnuke.fusion.llm.RuntimeAttemptSnapshot
import com.projectnuke.fusion.llm.RuntimeFailureSnapshot
import com.projectnuke.fusion.llm.RuntimeFallbackEvent
import org.json.JSONArray

object FallbackCauseFormatter {

    // ── Localized (Korean) user-facing prose ─────────────────────────────

    fun format(snapshot: RuntimeExecutionSnapshot): String {
        return formatEvents(snapshot.fallbackEvents)
    }

    fun format(attemptSnapshot: RuntimeAttemptSnapshot): String {
        return formatEvents(attemptSnapshot.fallbackEvents)
    }

    fun formatEvents(events: List<RuntimeFallbackEvent>): String {
        if (events.isEmpty()) return ""

        val parts = mutableListOf<String>()
        for (event in events) {
            val text = when (event.reason) {
                FallbackReason.MTP_UNSUPPORTED ->
                    "MTP를 지원하지 않아 MTP를 비활성화했습니다."
                FallbackReason.MTP_SKIPPED_RECENT_FAILURE ->
                    "이전 MTP 실패로 MTP를 건너뜁니다."
                FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE ->
                    "${event.attemptedTextBackend?.localizedName()} 백엔드가 최근 실패로 건너뛰었습니다."
                FallbackReason.BACKEND_ENGINE_INIT_FAILED ->
                    "${event.attemptedTextBackend?.localizedName()} 백엔드 초기화에 실패했습니다."
                FallbackReason.SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED ->
                    "예측 디코딩 활성화 플래그 적용에 실패했습니다."
                FallbackReason.SPECULATIVE_DISABLE_FLAG_SETTLEMENT_FAILED ->
                    "예측 디코딩 비활성화 플래그 적용에 실패했습니다."
                FallbackReason.MTP_ENGINE_INIT_FAILED ->
                    "MTP 엔진 초기화에 실패했습니다."
                FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED ->
                    "GPU 텍스트 엔진 실패로 CPU를 사용합니다."
                FallbackReason.GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED ->
                    "GPU 비전 백엔드를 사용할 수 없어 CPU 비전을 사용합니다."
                FallbackReason.ALL_CANDIDATES_EXHAUSTED ->
                    "모든 후보 백엔드가 실패했습니다."
                FallbackReason.ALL_CANDIDATES_SKIPPED_RECENT_FAILURE ->
                    "모든 후보 백엔드가 최근 실패로 건너뛰어졌습니다."
            }
            if (text.isNotBlank() && text !in parts) {
                parts.add(text)
            }
        }
        return parts.joinToString(" | ")
    }

    fun formatFallbackSummary(snapshot: RuntimeExecutionSnapshot): String {
        val events = snapshot.fallbackEvents
        if (events.isEmpty()) return ""

        val parts = mutableListOf<String>()
        for (event in events) {
            when (event.reason) {
                FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED ->
                    parts.add("GPU 초기화 실패로 CPU를 사용했습니다.")
                FallbackReason.GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED ->
                    parts.add("GPU 비전 백엔드를 사용할 수 없어 CPU 비전을 사용했습니다.")
                FallbackReason.MTP_ENGINE_INIT_FAILED -> {
                    if (event.attemptedMtpEnabled == true) {
                        parts.add("MTP 초기화 실패로 비-MTP를 사용했습니다.")
                    }
                }
                FallbackReason.BACKEND_ENGINE_INIT_FAILED ->
                    parts.add("${event.attemptedTextBackend?.localizedName()} 백엔드 초기화에 실패했습니다.")
                FallbackReason.ALL_CANDIDATES_EXHAUSTED ->
                    parts.add("모든 후보 백엔드가 실패했습니다.")
                FallbackReason.ALL_CANDIDATES_SKIPPED_RECENT_FAILURE ->
                    parts.add("모든 후보 백엔드가 최근 실패로 건너뛰어졌습니다.")
                else -> {
                    val text = formatSingleEvent(event)
                    if (text.isNotBlank()) parts.add(text)
                }
            }
        }
        return parts.joinToString("\n")
    }

    fun formatAttemptFallbackSummary(attemptSnapshot: RuntimeAttemptSnapshot): String {
        val events = attemptSnapshot.fallbackEvents
        if (events.isEmpty()) return ""

        val parts = mutableListOf<String>()
        for (event in events) {
            when (event.reason) {
                FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED ->
                    parts.add("GPU 초기화 실패 후 CPU를 사용했습니다.")
                FallbackReason.GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED ->
                    parts.add("GPU 비전 백엔드를 사용할 수 없어 CPU 비전을 사용했습니다.")
                FallbackReason.MTP_ENGINE_INIT_FAILED -> {
                    if (event.attemptedMtpEnabled == true) {
                        parts.add("MTP 초기화 실패 후 비-MTP를 사용했습니다.")
                    }
                }
                FallbackReason.BACKEND_ENGINE_INIT_FAILED ->
                    parts.add("${event.attemptedTextBackend?.localizedName()} 백엔드 초기화에 실패했습니다.")
                FallbackReason.ALL_CANDIDATES_EXHAUSTED ->
                    parts.add("모든 후보 백엔드가 실패했습니다.")
                FallbackReason.ALL_CANDIDATES_SKIPPED_RECENT_FAILURE ->
                    parts.add("모든 후보 백엔드가 최근 실패로 건너뛰어졌습니다.")
                else -> {
                    val text = formatSingleEvent(event)
                    if (text.isNotBlank()) parts.add(text)
                }
            }
        }
        return parts.joinToString("\n")
    }

    fun formatFailureSnapshot(snapshot: RuntimeFailureSnapshot): String {
        return formatEvents(snapshot.fallbackEventsFromAcquisition)
    }

    fun formatFailureSnapshotSummary(snapshot: RuntimeFailureSnapshot): String {
        val events = snapshot.fallbackEventsFromAcquisition
        if (events.isEmpty()) return ""
        val parts = mutableListOf<String>()
        for (event in events) {
            when (event.reason) {
                FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED ->
                    parts.add("GPU 초기화 실패 후 CPU를 사용했습니다.")
                FallbackReason.GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED ->
                    parts.add("GPU 비전 백엔드를 사용할 수 없어 CPU 비전을 사용했습니다.")
                FallbackReason.MTP_ENGINE_INIT_FAILED -> {
                    if (event.attemptedMtpEnabled == true) {
                        parts.add("MTP 초기화 실패 후 비-MTP를 사용했습니다.")
                    }
                }
                FallbackReason.ALL_CANDIDATES_EXHAUSTED ->
                    parts.add("모든 후보 백엔드가 실패했습니다.")
                FallbackReason.ALL_CANDIDATES_SKIPPED_RECENT_FAILURE ->
                    parts.add("모든 후보 백엔드가 최근 실패로 건너뛰어졌습니다.")
                else -> {
                    val text = formatSingleEvent(event)
                    if (text.isNotBlank()) parts.add(text)
                }
            }
        }
        return parts.joinToString("\n")
    }

    private fun formatSingleEvent(event: RuntimeFallbackEvent): String {
        return when (event.reason) {
            FallbackReason.MTP_UNSUPPORTED ->
                "MTP 미지원"
            FallbackReason.MTP_SKIPPED_RECENT_FAILURE ->
                "MTP 최근 실패 건너뜀"
            FallbackReason.BACKEND_SKIPPED_RECENT_FAILURE ->
                "${event.attemptedTextBackend?.localizedName()} 최근 실패 건너뜀"
            FallbackReason.BACKEND_ENGINE_INIT_FAILED ->
                "${event.attemptedTextBackend?.localizedName()} 초기화 실패"
            FallbackReason.SPECULATIVE_ENABLE_FLAG_SETTLEMENT_FAILED ->
                "예측 디코딩 활성화 실패"
            FallbackReason.SPECULATIVE_DISABLE_FLAG_SETTLEMENT_FAILED ->
                "예측 디코딩 비활성화 실패"
            FallbackReason.MTP_ENGINE_INIT_FAILED ->
                "MTP 초기화 실패"
            FallbackReason.GPU_TEXT_ENGINE_FAILED_CPU_SELECTED ->
                "GPU 텍스트 실패 → CPU"
            FallbackReason.GPU_VISION_BACKEND_FAILED_CPU_VISION_SELECTED ->
                "GPU 비전 실패 → CPU 비전"
            FallbackReason.ALL_CANDIDATES_EXHAUSTED ->
                "후보 없음"
            FallbackReason.ALL_CANDIDATES_SKIPPED_RECENT_FAILURE ->
                "후보 모두 건너뜀"
        }
    }

    private fun RuntimeBackend?.localizedName(): String = when (this) {
        RuntimeBackend.GPU -> "GPU"
        RuntimeBackend.CPU -> "CPU"
        RuntimeBackend.UNKNOWN -> "알 수 없음"
        null -> "알 수 없음"
    }

    // ── Stable machine-readable codes ────────────────────────────────────

    /**
     * Serialize fallback events to a stable machine-readable JSON array of
     * reason-code strings. The output is a JSON array like
     * `["MTP_ENGINE_INIT_FAILED","GPU_TEXT_ENGINE_FAILED_CPU_SELECTED"]`.
     *
     * Callers must use THIS function to populate `fallbackEventCodes` in
     * persisted entities / history stores. Never store
     * [format]/[formatEvents]/[formatFallbackSummary]/[formatAttemptFallbackSummary]
     * output in a code field — those return localized Korean prose.
     */
    fun formatCodes(events: List<RuntimeFallbackEvent>): String? {
        if (events.isEmpty()) return null
        val arr = JSONArray()
        for (event in events) {
            arr.put(event.reason.name)
        }
        return arr.toString()
    }

    fun formatCodes(snapshot: RuntimeExecutionSnapshot): String? =
        formatCodes(snapshot.fallbackEvents)

    fun formatCodes(attemptSnapshot: RuntimeAttemptSnapshot): String? =
        formatCodes(attemptSnapshot.fallbackEvents)

    fun formatCodes(failureSnapshot: RuntimeFailureSnapshot): String? =
        formatCodes(failureSnapshot.fallbackEventsFromAcquisition)

    /**
     * Migration-compatible parsing for legacy history values that may
     * contain localized strings or pipe-separated codes. Always returns
     * the stable [FallbackReason.name] when the stored value matches one,
     * and never produces localized prose as a code.
     *
     * Output: a JSON array string of stable codes, or null.
     */
    /**
     * Renders persisted machine-readable codes (output of [formatCodes]) back
     * into localized Korean prose for on-screen display. Migration-compatible:
     * accepts legacy pipe-separated or localized strings via
     * [parseLegacyFallbackCodes] first.
     */
    fun renderStoredCodesForDisplay(stored: String?): String {
        if (stored.isNullOrBlank()) return ""
        val normalized = parseLegacyFallbackCodes(stored) ?: return ""
        val codes = runCatching {
            val arr = JSONArray(normalized)
            (0 until arr.length()).mapNotNull { idx ->
                runCatching { FallbackReason.valueOf(arr.optString(idx)) }.getOrNull()
            }
        }.getOrNull().orEmpty()
        val events = codes.map { code ->
            RuntimeFallbackEvent(reason = code)
        }
        return formatEvents(events)
    }

    /** Renders an in-memory diagnostic list without flattening event codes. */
    fun renderCodesForDisplay(codes: List<String>): String {
        if (codes.isEmpty()) return ""
        val events = codes.mapNotNull { code ->
            runCatching { FallbackReason.valueOf(code) }
                .getOrNull()
                ?.let { RuntimeFallbackEvent(reason = it) }
        }
        return formatEvents(events)
    }

    /**
     * Migration-compatible parsing for legacy history values that may
     * contain localized strings or pipe-separated codes. Always returns
     * the stable [FallbackReason.name] when the stored value matches one,
     * and never produces localized prose as a code.
     *
     * Output: a JSON array string of stable codes, or null.
     */
    fun parseLegacyFallbackCodes(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        // Already a JSON array (new format)?
        if (stored.trimStart().startsWith("[")) {
            return runCatching {
                val arr = JSONArray(stored)
                if (arr.length() == 0) return@runCatching null
                val parsed = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i)
                    if (name.isNotBlank() && runCatching { FallbackReason.valueOf(name) }.isSuccess) {
                        parsed += name
                    }
                }
                if (parsed.isEmpty()) null else JSONArray(parsed).toString()
            }.getOrNull()
        }
        // Legacy pipe-separated text — extract any token that matches a
        // FallbackReason name verbatim; discard the rest.
        val tokens = stored.split("|")
        val matched = tokens.mapNotNull { tok ->
            val trimmed = tok.trim()
            runCatching { FallbackReason.valueOf(trimmed) }.getOrNull()?.name
        }.distinct()
        return if (matched.isEmpty()) null else JSONArray(matched).toString()
    }
}
