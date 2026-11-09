package com.projectnuke.fusion.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class EvidenceSource(
    val id: String,
    val title: String,
    val source: String,
    val url: String,
    val snippet: String,
    val publishedAt: String?,
    val providerType: String,
    val providerDisplayName: String,
    val queryUsed: String
)

data class EvidenceClaim(
    val id: String,
    val text: String,
    val verdict: EvidenceVerdict,
    val sourceIds: List<String>,
    val reasonCode: String?
)

data class EvidenceReport(
    val schemaVersion: Int,
    val answerSha256: String,
    val checkedAt: Long,
    val verifierVersion: String,
    val claims: List<EvidenceClaim>,
    val sources: List<EvidenceSource>,
    val candidateWindowTruncated: Boolean = false,
    val overlengthSegmentsExcluded: Boolean = false,
)

enum class EvidenceReportCompatibility {
    CURRENT,
    OUTDATED,
}

/** Why a durable report may no longer present its verdicts/citations. */
enum class EvidenceOutdatedReason {
    RULES_CHANGED,
    TIME_EXPIRED,
    CLOCK_INVALID,
}

/**
 * Presentation-only compatibility policy. Durable rows remain forensic data;
 * this policy decides whether their verdicts and citations may be shown now.
 */
object EvidenceReportCompatibilityPolicy {
    const val CURRENT_SENSITIVE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    const val CLOCK_SKEW_TOLERANCE_MS = 60_000L

    fun classify(
        report: EvidenceReport,
        currentAnswerSha256: String,
        nowMillis: Long,
    ): EvidenceReportCompatibility {
        if (EvidenceCodec.validate(report) is EvidenceValidation.Invalid) {
            return EvidenceReportCompatibility.OUTDATED
        }
        if (report.answerSha256 != currentAnswerSha256 ||
            report.verifierVersion != EVIDENCE_VERIFIER_VERSION ||
            isClockInvalid(report, nowMillis) ||
            isExpired(report, nowMillis)
        ) {
            return EvidenceReportCompatibility.OUTDATED
        }
        return EvidenceReportCompatibility.CURRENT
    }

    fun isCurrentDisplayCompatible(
        report: EvidenceReport,
        currentAnswerSha256: String,
        nowMillis: Long,
    ): Boolean = classify(report, currentAnswerSha256, nowMillis) == EvidenceReportCompatibility.CURRENT

    fun isExpired(report: EvidenceReport, nowMillis: Long): Boolean {
        val checkedCurrentClaim = report.claims.any {
            it.verdict != EvidenceVerdict.NOT_CHECKED &&
                EvidenceCurrentSensitivity.isCurrentSensitiveClaim(it.text)
        }
        if (!checkedCurrentClaim) return false
        // A materially future-dated report must not be treated as current
        // indefinitely; only a tiny clock-skew tolerance is allowed.
        if (nowMillis < report.checkedAt - CLOCK_SKEW_TOLERANCE_MS) return false
        return nowMillis - report.checkedAt > CURRENT_SENSITIVE_MAX_AGE_MS
    }

    /**
     * A materially future-dated report (beyond a tiny clock-skew tolerance) is
     * invalid and must fail closed to OUTDATED/CLOCK_INVALID with no citations.
     */
    fun isClockInvalid(report: EvidenceReport, nowMillis: Long): Boolean =
        report.checkedAt > nowMillis + CLOCK_SKEW_TOLERANCE_MS

    /**
     * Returns the presentation reason when [classify] yields OUTDATED, or null
     * when the report is CURRENT. Durable row semantics are unchanged; this only
     * selects the user-facing copy.
     */
    fun outdatedReason(
        report: EvidenceReport,
        currentAnswerSha256: String,
        nowMillis: Long,
    ): EvidenceOutdatedReason? {
        if (EvidenceCodec.validate(report) is EvidenceValidation.Invalid) {
            return EvidenceOutdatedReason.RULES_CHANGED
        }
        if (report.answerSha256 != currentAnswerSha256 ||
            report.verifierVersion != EVIDENCE_VERIFIER_VERSION
        ) {
            return EvidenceOutdatedReason.RULES_CHANGED
        }
        if (isClockInvalid(report, nowMillis)) return EvidenceOutdatedReason.CLOCK_INVALID
        if (isExpired(report, nowMillis)) return EvidenceOutdatedReason.TIME_EXPIRED
        return null
    }
}

/**
 * Renders a checkedAt epoch millis as a normal local date/time for humans.
 * Uses the device timezone (product UI convention); deterministic pattern
 * yyyy-MM-dd HH:mm.
 */
internal fun formatCheckedAt(checkedAt: Long): String {
    val dateTime = Instant.ofEpochMilli(checkedAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
    return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
