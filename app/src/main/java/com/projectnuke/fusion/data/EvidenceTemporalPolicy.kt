package com.projectnuke.fusion.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Single bounded source of truth for current/relative-time policy shared by
 * claim classification, verification freshness, and durable report expiry.
 *
 * Deterministic and bounded: no NLP, no fuzzy matching. A claim is classified
 * into a [TemporalCategory]; that category governs both the publication
 * freshness window and the semantic event-time alignment check applied to each
 * evidence segment.
 *
 * English markers are matched with token/phrase boundaries so a word such as
 * "now" never fires inside "snow"/"known"/"snowfall". Korean markers remain a
 * bounded substring match where a boundary is not derivable without a parser.
 */
object EvidenceTemporalPolicy {

    enum class TemporalCategory {
        NONE,
        CURRENT_STATE,
        TODAY,
        RECENT,
        THIS_WEEK,
        THIS_MONTH,
        THIS_YEAR,
    }

    // ---- Canonical current/recent markers (Korean + English) ----

    val koreanCurrentMarkers = listOf(
        "현재", "지금", "오늘", "최근", "최신", "현재까지", "이번", "이번 주", "이번주",
        "이번 달", "이번달", "이번 해", "올해",
    )

    val englishCurrentMarkers = listOf(
        "currently", "now", "today", "latest", "recently", "as of",
        "this week", "this month", "this year",
    )

    // ---- Category-specific markers ----

    private val TodayMarkers = listOf("오늘", "today")
    private val CurrentStateMarkers = listOf("현재", "지금", "현재까지", "currently", "now", "as of", "이번")
    private val RecentMarkers = listOf("최근", "최신", "recently", "latest")
    private val ThisWeekMarkers = listOf("이번 주", "이번주", "this week")
    private val ThisMonthMarkers = listOf("이번 달", "이번달", "this month")
    private val ThisYearMarkers = listOf("이번 해", "올해", "this year")

    // ---- Independent current-state assertion markers (past-date escape hatch) ----

    private val CurrentAssertionMarkers = listOf("현재", "지금", "현재까지", "currently", "now", "as of")

    // ---- Historical / incompatible relative markers ----

    private val HistoricalKoreanMarkers = listOf("과거", "이전", "당시", "예전에", "작년", "지난해")
    private val HistoricalEnglishMarkers = listOf("previously", "formerly", "used to", "at the time", "last year")
    private val YesterdayMarkers = listOf("어제", "yesterday")
    private val TomorrowMarkers = listOf("내일", "tomorrow")

    // ---- Incompatible relative period markers (must reject THIS_* categories) ----

    private val IncompatibleWeekMarkers = listOf("last week", "previous week", "next week", "지난주", "지난 주", "저번주", "저번 주", "다음주", "다음 주")
    private val IncompatibleMonthMarkers = listOf("last month", "previous month", "next month", "지난달", "지난 달", "저번달", "저번 달", "다음달", "다음 달")
    private val IncompatibleYearMarkers = listOf("last year", "previous year", "next year", "작년", "지난해", "내년")

    // ---- Bounded historical relative-duration patterns: must not prove CURRENT/RECENT facts ----

    private val RelativeDurationPattern = Regex("""(?i)\b\d+\s*(?:days?|weeks?|months?|years?|일|주|개월|달|년)\s*ago\b""")
    private val KoreanRelativeDurationPattern = Regex("""\b\d+\s*(?:일|주|개월|달|년)\s*전\b""")

    // ---- Publication freshness windows (conservative, per category) ----

    const val TODAY_WINDOW_MS = 24L * 60L * 60L * 1_000L
    const val CURRENT_STATE_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L
    const val RECENT_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L
    const val THIS_WEEK_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L
    const val THIS_MONTH_WINDOW_MS = 14L * 24L * 60L * 60L * 1_000L
    const val THIS_YEAR_WINDOW_MS = 60L * 24L * 60L * 60L * 1_000L

    /** An explicit event date must fall inside this many days to be RECENT. */
    const val RECENT_EVENT_WINDOW_DAYS = 7L

    private val YearRegex = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")

    // ---- Marker matching. English markers use token/phrase boundaries so
    // substrings inside longer words are never treated as markers; Korean
    // markers remain a bounded substring match. ----

    private val markerRegexCache = ConcurrentHashMap<String, Regex>()

    private fun matchesMarker(normalized: String, marker: String): Boolean {
        if (marker.none { it in 'a'..'z' }) return normalized.contains(marker)
        return markerRegexCache.getOrPut(marker) {
            Regex("(?<![a-z0-9])${Regex.escape(marker)}(?![a-z0-9])")
        }.containsMatchIn(normalized)
    }

    private fun matchesAny(normalized: String, markers: List<String>): Boolean =
        markers.any { matchesMarker(normalized, it) }

    /** Broad current-sensitivity used by planning and durable report expiry. */
    fun isCurrentSensitive(text: String): Boolean {
        val normalized = normalize(text)
        return (koreanCurrentMarkers + englishCurrentMarkers).any { matchesMarker(normalized, it) }
    }

    /** Classifies a claim into a bounded temporal category. */
    fun classify(text: String): TemporalCategory {
        val normalized = normalize(text)
        return when {
            matchesAny(normalized, TodayMarkers) -> TemporalCategory.TODAY
            matchesAny(normalized, ThisWeekMarkers) -> TemporalCategory.THIS_WEEK
            matchesAny(normalized, ThisMonthMarkers) -> TemporalCategory.THIS_MONTH
            matchesAny(normalized, ThisYearMarkers) -> TemporalCategory.THIS_YEAR
            matchesAny(normalized, CurrentStateMarkers) -> TemporalCategory.CURRENT_STATE
            matchesAny(normalized, RecentMarkers) -> TemporalCategory.RECENT
            else -> TemporalCategory.NONE
        }
    }

    /** Conservative publication freshness window for a category. */
    fun freshnessWindowMs(category: TemporalCategory): Long = when (category) {
        TemporalCategory.TODAY -> TODAY_WINDOW_MS
        TemporalCategory.CURRENT_STATE -> CURRENT_STATE_WINDOW_MS
        TemporalCategory.RECENT -> RECENT_WINDOW_MS
        TemporalCategory.THIS_WEEK -> THIS_WEEK_WINDOW_MS
        TemporalCategory.THIS_MONTH -> THIS_MONTH_WINDOW_MS
        TemporalCategory.THIS_YEAR -> THIS_YEAR_WINDOW_MS
        TemporalCategory.NONE -> Long.MAX_VALUE
    }

    /**
     * Semantic event-time alignment. Returns true only if the evidence segment
     * is deterministically compatible with the claim's temporal category under
     * the verification local time. Fail closed: when alignment cannot be proven,
     * returns false (INSUFFICIENT).
     *
     * Source-relative markers (today / this week / this month / this year) are
     * evaluated at the source's publication local day, not at verification
     * time; when [publishedAtMillis] is absent/unparseable, the segment fails
     * closed for those markers.
     *
     * @param publishedAtMillis parsed source publication time, or null if absent/unparseable
     * @param nowMillis verification time from the injectable clock
     * @param zoneId injectable/system zone for calendar-relative interpretation
     */
     fun isTemporallyAligned(
         category: TemporalCategory,
         segmentText: String,
         claimText: String,
         publishedAtMillis: Long?,
         nowMillis: Long,
         zoneId: ZoneId,
     ): Boolean {
         if (category == TemporalCategory.NONE) return true
        val segmentNormalized = normalize(segmentText)
        val localDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        // Bounded historical relative-duration patterns cannot prove
        // CURRENT/RECENT facts regardless of publication time.
        if (category == TemporalCategory.RECENT || category == TemporalCategory.CURRENT_STATE) {
            if (RelativeDurationPattern.containsMatchIn(segmentText) ||
                KoreanRelativeDurationPattern.containsMatchIn(segmentText)) {
                return false
            }
        }
        // A source published before an explicit event date cannot prove
        // that the event occurred. Fail closed when publishedAt is available.
        if (publishedAtMillis != null) {
            val pubDate = pubLocalDate(publishedAtMillis, zoneId)
            val eventDates = EvidenceAssessmentText.extractDates(segmentText)
            if (eventDates.any { it > pubDate }) return false
        }

        return when (category) {
             TemporalCategory.TODAY -> {
                // Explicit incompatible relative markers override everything.
                if (matchesAny(segmentNormalized, IncompatibleWeekMarkers)) return false
                if (matchesAny(segmentNormalized, IncompatibleMonthMarkers)) return false
                if (matchesAny(segmentNormalized, IncompatibleYearMarkers)) return false
                if (matchesAny(segmentNormalized, YesterdayMarkers)) return false
                if (matchesAny(segmentNormalized, TomorrowMarkers)) return false
                val dates = EvidenceAssessmentText.extractDates(segmentText)
                if (dates.isNotEmpty()) return dates.any { it == localDate }
                if (matchesAny(segmentNormalized, TodayMarkers)) {
                    return publishedAtMillis?.let { pubLocalDate(it, zoneId) == localDate } ?: false
                }
                false
            }
            TemporalCategory.CURRENT_STATE -> {
                if (matchesAny(segmentNormalized, HistoricalKoreanMarkers)) return false
                if (matchesAny(segmentNormalized, HistoricalEnglishMarkers)) return false
                if (hasExplicitOldYear(segmentText, localDate.year)) return false
                if (EvidenceAssessmentText.hasCurrentStatePastFormOnly(claimText, segmentText)) return false
                val dates = EvidenceAssessmentText.extractDates(segmentText)
                if (dates.any { it < localDate }) {
                    return false
                }
                true
            }
            TemporalCategory.THIS_WEEK -> {
                if (matchesAny(segmentNormalized, IncompatibleWeekMarkers)) return false
                val dates = EvidenceAssessmentText.extractDates(segmentText)
                if (dates.isNotEmpty()) return dates.any { isSameWeek(it, localDate) }
                if (matchesAny(segmentNormalized, ThisWeekMarkers)) {
                    val pubMillis = publishedAtMillis ?: return false
                    return isSameWeek(pubLocalDate(pubMillis, zoneId), localDate)
                }
                false
            }
            TemporalCategory.THIS_MONTH -> {
                if (matchesAny(segmentNormalized, IncompatibleMonthMarkers)) return false
                val dates = EvidenceAssessmentText.extractDates(segmentText)
                if (dates.isNotEmpty()) {
                    return dates.any { it.year == localDate.year && it.month == localDate.month }
                }
                if (matchesAny(segmentNormalized, ThisMonthMarkers)) {
                    val pubMillis = publishedAtMillis ?: return false
                    val pubDate = pubLocalDate(pubMillis, zoneId)
                    return pubDate.year == localDate.year && pubDate.month == localDate.month
                }
                false
            }
            TemporalCategory.THIS_YEAR -> {
                if (matchesAny(segmentNormalized, IncompatibleYearMarkers)) return false
                val dates = EvidenceAssessmentText.extractDates(segmentText)
                if (dates.isNotEmpty()) return dates.any { it.year == localDate.year }
                if (matchesAny(segmentNormalized, ThisYearMarkers)) {
                    val pubMillis = publishedAtMillis ?: return false
                    return pubLocalDate(pubMillis, zoneId).year == localDate.year
                }
                false
            }
            TemporalCategory.RECENT -> {
                if (matchesAny(segmentNormalized, HistoricalKoreanMarkers)) return false
                if (matchesAny(segmentNormalized, HistoricalEnglishMarkers)) return false
                if (hasExplicitOldYear(segmentText, localDate.year)) return false
                val dates = EvidenceAssessmentText.extractDates(segmentText)
                if (dates.isNotEmpty()) {
                    // An explicit event date must itself fall inside the recent window.
                    return dates.any { date ->
                        ChronoUnit.DAYS.between(date, localDate) in 0..RECENT_EVENT_WINDOW_DAYS
                    }
                }
                // No explicit event date: a fresh publication alone never makes an
                // undated proposition "recent"; it needs a reliable recent marker.
                return matchesAny(segmentNormalized, RecentMarkers)
            }
            TemporalCategory.NONE -> true
        }
    }

    private fun hasExplicitOldYear(text: String, currentYear: Int): Boolean {
        return YearRegex.findAll(text).any { it.value.toIntOrNull()?.let { year -> year < currentYear } == true }
    }

    private fun isSameWeek(date: LocalDate, reference: LocalDate): Boolean {
        // ISO week (Monday start) is deterministic and locale-independent; the
        // week-alignment gate only needs a stable "within the current 7-day ISO
        // week" test.
        val refWeek = reference.get(WeekFields.ISO.weekOfWeekBasedYear())
        val refYear = reference.get(WeekFields.ISO.weekBasedYear())
        val dateWeek = date.get(WeekFields.ISO.weekOfWeekBasedYear())
        val dateYear = date.get(WeekFields.ISO.weekBasedYear())
        return refWeek == dateWeek && refYear == dateYear
    }

    private fun pubLocalDate(pubMillis: Long, zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(pubMillis).atZone(zoneId).toLocalDate()

    private fun normalize(text: String): String =
        text.replace('\u2018', '\'').replace('\u2019', '\'').lowercase(Locale.ROOT)
}