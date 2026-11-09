package com.projectnuke.fusion.data

import com.projectnuke.fusion.search.WebSearchProviderType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rules-v5 temporal/assertion semantics: completed events
 * cannot be proven before they occur, current-state old-date escape
 * hatch is removed, incompatible relative-period markers are rejected,
 * completed and ambiguous event forms are distinguished, whether is
 * interrogative, and version-compatibility tests are codec-valid.
 */
class EvidenceTemporalTest {

    private val zone = ZoneId.of("UTC")

    private fun assessor(now: Long): ConservativeRuleEvidenceAssessor =
        ConservativeRuleEvidenceAssessor(EvidenceClock { now }, zone)

    private fun source(
        text: String,
        publishedAt: String?,
        id: String = "E1",
    ): EvidenceSource = EvidenceSource(
        id = id,
        title = text,
        source = "Example",
        url = "https://example.com/$id",
        snippet = text,
        publishedAt = publishedAt,
        providerType = WebSearchProviderType.FREE_DEFAULT.name,
        providerDisplayName = "Free Search",
        queryUsed = text,
    )

    private fun candidate(
        text: String,
        classification: ClaimClassification = ClaimClassification.CHECKABLE_FACT,
        priority: ClaimPriority = ClaimPriority.HIGH,
        id: String = "C1",
    ): EvidenceClaimCandidate = EvidenceClaimCandidate(
        id = id,
        text = text,
        ordinal = 1,
        classification = classification,
        priority = priority,
        reason = ClaimClassificationReason.FACTUAL_DECLARATIVE,
    )

    private val now = Instant.parse("2026-09-05T00:00:00Z").toEpochMilli()

    @Test
    fun `fresh article describing old state is insufficient`() {
        val assessor = assessor(now)
        val claim = candidate("Acme currently supports ProductX2.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme supported ProductX2 in 2020.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `previously formerly used to do not support current claim`() {
        val assessor = assessor(now)
        val claim = candidate("Acme currently supports ProductX2.")
        for (text in listOf(
            "Acme previously supported ProductX2.",
            "Acme formerly supported ProductX2.",
            "Acme used to support ProductX2.",
        )) {
            assertEquals(text,
                EvidenceVerdict.INSUFFICIENT_EVIDENCE,
                assessor.assess(claim, listOf(source(text, "2026-09-05"))).verdict,
            )
        }
    }

    @Test
    fun `korean historical marker is insufficient`() {
        val assessor = assessor(now)
        val claim = candidate("삼성전자는 현재 ProductX를 지원한다.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("삼성전자는 과거 ProductX를 지원했다.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `yesterday does not support today claim`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX2 today.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme released ProductX2 yesterday.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `korean yesterday does not support korean today claim`() {
        val assessor = assessor(now)
        val claim = candidate("Acme는 오늘 ProductX2를 출시했다.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme는 어제 ProductX2를 출시했다.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `explicit matching today date may support`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX2 today.")
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            assessor.assess(
                claim,
                listOf(source("Acme released ProductX2 on September 5, 2026.", "2026-09-05")),
            ).verdict,
        )
    }

    @Test
    fun `old period does not support this week claim`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX2 this week.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(
                claim,
                listOf(source("Acme released ProductX2 on August 1, 2026.", "2026-09-05")),
            ).verdict,
        )
    }

    @Test
    fun `korean period markers all activate current sensitivity`() {
        for (marker in listOf("이번 주", "이번 달", "올해")) {
            val text = "Acme는 $marker ProductX2를 출시했다."
            assertEquals(marker, true, EvidenceCurrentSensitivity.isCurrentSensitiveClaim(text))
        }
    }

    @Test
    fun `english period markers all activate current sensitivity`() {
        for (marker in listOf("this week", "this month", "this year")) {
            val text = "Acme released ProductX2 $marker."
            assertEquals(marker, true, EvidenceCurrentSensitivity.isCurrentSensitiveClaim(text))
        }
    }

    @Test
    fun `question title never supports`() {
        val assessor = assessor(now)
        val claim = candidate("ProductX2 supports 12GB.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("ProductX2 supports 12GB?", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `leading question never supports`() {
        val assessor = assessor(now)
        val claim = candidate("ProductX2 supports 12GB.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Does ProductX2 support 12GB?", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `positive declarative still supports`() {
        val assessor = assessor(now)
        val claim = candidate("ProductX2 supports 12GB.")
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            assessor.assess(claim, listOf(source("ProductX2 supports 12GB.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `rules-v2 report is outdated under rules-v5`() {
        val claim = EvidenceClaim("C1", "ProductX2 supports 12GB.", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null)
        val report = EvidenceReport(1, "a".repeat(64), 1, "rules-v2", listOf(claim), emptyList())
        assertTrue(EvidenceCodec.validate(report) is EvidenceValidation.Valid)
        assertEquals(
            EvidenceReportCompatibility.OUTDATED,
            EvidenceReportCompatibilityPolicy.classify(report, "a".repeat(64), now),
        )
    }

    @Test
    fun `rules-v4 report is outdated under rules-v5`() {
        val claim = EvidenceClaim("C1", "ProductX2 supports 12GB.", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, emptyList(), null)
        val report = EvidenceReport(1, "a".repeat(64), 1, "rules-v4", listOf(claim), emptyList())
        assertTrue(EvidenceCodec.validate(report) is EvidenceValidation.Valid)
        assertEquals(
            EvidenceReportCompatibility.OUTDATED,
            EvidenceReportCompatibilityPolicy.classify(report, "a".repeat(64), now),
        )
    }

    @Test
    fun `rumor hedge never supports`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX2 in 2026.")
        for (text in listOf(
            "Acme rumored to release ProductX2 in 2026.",
            "Acme allegedly released ProductX2 in 2026.",
            "Acme unconfirmed to release ProductX2 in 2026.",
            "Acme leaked ProductX2 in 2026.",
        )) {
            assertEquals(text,
                EvidenceVerdict.INSUFFICIENT_EVIDENCE,
                assessor.assess(claim, listOf(source(text, "2026-09-05"))).verdict,
            )
        }
    }

    @Test
    fun `korean hedge markers never support`() {
        val assessor = assessor(now)
        val claim = candidate("Acme는 2026년에 ProductX2를 출시했다.")
        for (text in listOf(
            "Acme 루머: 2026년 ProductX2 출시.",
            "Acme 소문으로 2026년 ProductX2 출시.",
            "Acme 유출: 2026년 ProductX2 출시.",
            "Acme 미확인 2026년 ProductX2 출시.",
        )) {
            assertEquals(text,
                EvidenceVerdict.INSUFFICIENT_EVIDENCE,
                assessor.assess(claim, listOf(source(text, "2026-09-05"))).verdict,
            )
        }
    }

    // ---- rules-v4 TODAY: source "today" binds to its publication local day ----

    @Test
    fun `today source published yesterday is insufficient across midnight`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX2 today.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme released ProductX2 today.", "2026-09-04T12:00:00Z"))).verdict,
        )
    }

    @Test
    fun `today source published same local day may support`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX2 today.")
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme released ProductX2 today.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `today claim with explicit future event date is insufficient`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX2 today.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(
                claim,
                listOf(source("Acme released ProductX2 on September 5, 2026.", "2026-09-04")),
            ).verdict,
        )
    }

    // ---- rules-v4 relative periods bind to the publication period ----

    @Test
    fun `previous publication ISO week saying this week is insufficient`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX this week.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme released ProductX this week.", "2026-08-30"))).verdict,
        )
    }

    @Test
    fun `same ISO week saying this week may support`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX this week.")
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme released ProductX this week.", "2026-09-01"))).verdict,
        )
    }

    @Test
    fun `previous month saying this month is insufficient`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX this month.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme released ProductX this month.", "2026-08-31"))).verdict,
        )
    }

    @Test
    fun `same month saying this month may support`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX this month.")
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme released ProductX this month.", "2026-09-02"))).verdict,
        )
    }

    @Test
    fun `previous year saying this year is insufficient`() {
        val january = Instant.parse("2027-01-05T00:00:00Z").toEpochMilli()
        val assessor = assessor(january)
        val claim = candidate("Acme released ProductX this year.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme released ProductX this year.", "2026-12-20"))).verdict,
        )
    }

    // ---- rules-v4 CURRENT_STATE: past forms and past event dates fail closed ----

    @Test
    fun `fresh supported does not prove currently supports`() {
        val assessor = assessor(now)
        val claim = candidate("Acme currently supports ProductX2.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme supported ProductX2.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `fresh korean past form does not prove current support`() {
        val assessor = assessor(now)
        val claim = candidate("삼성전자는 현재 ProductX를 지원한다.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("삼성전자는 ProductX를 지원했다.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `explicit january event does not prove september current state`() {
        val assessor = assessor(now)
        val claim = candidate("Acme currently supports ProductX2.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(
                claim,
                listOf(source("Acme supported ProductX2 on January 3, 2026.", "2026-09-05")),
            ).verdict,
        )
    }

    @Test
    fun `present tense current source remains supported`() {
        val assessor = assessor(now)
        val claim = candidate("Acme currently supports ProductX2.")
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme supports ProductX2.", "2026-09-05"))).verdict,
        )
    }

    // ---- rules-v4 RECENT: event time, not publication time ----

    @Test
    fun `fresh publication describing far past event is not recent`() {
        val assessor = assessor(now)
        val claim = candidate("Acme recently released ProductX.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(
                claim,
                listOf(source("Acme released ProductX on January 3, 2026.", "2026-09-05")),
            ).verdict,
        )
    }

    @Test
    fun `genuinely recent explicit event date may support`() {
        val assessor = assessor(now)
        val claim = candidate("Acme recently released ProductX.")
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            assessor.assess(
                claim,
                listOf(source("Acme released ProductX on September 3, 2026.", "2026-09-04")),
            ).verdict,
        )
    }

    @Test
    fun `undated fresh proposition without recent marker is not recent`() {
        val assessor = assessor(now)
        val claim = candidate("Acme recently released ProductX.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme released ProductX.", "2026-09-05"))).verdict,
        )
    }

    // ---- rules-v4 temporal admissibility applies to conflicts too ----

    @Test
    fun `stale source does not conflict with a current claim`() {
        val assessor = assessor(now)
        val claim = candidate("ProductX2 currently has 12GB RAM.")
        val result = assessor.assess(claim, listOf(source("ProductX2 has 8GB RAM.", "2020-01-01")))
        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, result.verdict)
    }

    @Test
    fun `fresh temporally aligned source may conflict with a current claim`() {
        val assessor = assessor(now)
        val claim = candidate("ProductX2 currently has 12GB RAM.")
        val result = assessor.assess(claim, listOf(source("ProductX2 has 8GB RAM.", "2026-09-05")))
        assertEquals(EvidenceVerdict.CONFLICTING_EVIDENCE, result.verdict)
    }

    // ---- rules-v4 punctuation-less questions never support ----

    @Test
    fun `punctuation-less leading questions never support`() {
        val assessor = assessor(now)
        val claim = candidate("ProductX2 supports 12GB.")
        for (text in listOf(
            "Does ProductX2 support 12GB",
            "Is ProductX2 available",
            "Can ProductX2 support 12GB",
            "Did Acme release ProductX2",
        )) {
            assertEquals(text,
                EvidenceVerdict.INSUFFICIENT_EVIDENCE,
                assessor.assess(claim, listOf(source(text, "2026-09-05"))).verdict,
            )
        }
    }

    @Test
    fun `korean interrogative ending never supports`() {
        val assessor = assessor(now)
        val claim = candidate("삼성전자는 ProductX를 지원한다.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("삼성전자가 ProductX를 지원합니까", "2026-09-05"))).verdict,
        )
    }

    // ---- rules-v4 English marker token boundaries ----

    @Test
    fun `english current markers require token boundaries`() {
        assertFalse(EvidenceTemporalPolicy.isCurrentSensitive("Acme reported snowfall."))
        assertFalse(EvidenceTemporalPolicy.isCurrentSensitive("Acme has a known issue."))
        assertFalse(EvidenceCurrentSensitivity.isCurrentSensitiveClaim("known issue"))
        assertTrue(EvidenceTemporalPolicy.isCurrentSensitive("Acme now supports ProductX."))
        assertTrue(EvidenceTemporalPolicy.isCurrentSensitive("Acme currently supports ProductX."))
        assertEquals(EvidenceTemporalPolicy.TemporalCategory.NONE, EvidenceTemporalPolicy.classify("Acme is a known vendor."))
        assertEquals(EvidenceTemporalPolicy.TemporalCategory.CURRENT_STATE, EvidenceTemporalPolicy.classify("Acme now supports ProductX."))
    }

    // ---- rules-v4 timezone-consistent publishedAt parsing ----

    @Test
    fun `timezone-less publishedAt is interpreted with injected zone`() {
        val ny = ZoneId.of("America/New_York")
        for (raw in listOf("2026-09-05", "2026-09-05T01:30:00", "2026/09/05")) {
            val millis = EvidencePublishedAtParser.parseMillis(raw, ny)
            assertEquals(raw, LocalDate.of(2026, 9, 5), Instant.ofEpochMilli(millis!!).atZone(ny).toLocalDate())
        }
        assertEquals(
            Instant.parse("2026-09-05T00:00:00Z").toEpochMilli(),
            EvidencePublishedAtParser.parseMillis("2026-09-05T00:00:00Z", ny),
        )
    }

    // ---- rules-v5 EVENT CHRONOLOGY TESTS ----

    // #1: Future event source cannot prove a completed event

    @Test
    fun `future event source cannot prove completed event`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX2 on September 5, 2026.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme released ProductX2 on September 5, 2026.", "2026-09-04"))).verdict,
        )
    }

    // #1: Same-day completed event may support

    @Test
    fun `same_day_completed_event_may_support`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX2 on September 5, 2026.")
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme released ProductX2 on September 5, 2026.", "2026-09-05"))).verdict,
        )
    }

    // #1: Historical exact event - source published before event date

    @Test
    fun `historical_source_published_before_event_cannot_support`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX2 on September 5, 2026.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme released ProductX2 on September 5, 2026.", "2026-09-01"))).verdict,
        )
    }

    // #2: Current as-of old date

    @Test
    fun `current_as_of_old_date_is_insufficient`() {
        val assessor = assessor(now)
        val claim = candidate("Acme currently supports ProductX2.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme supports ProductX2 as of January 3, 2026.", "2026-09-05"))).verdict,
        )
    }

    // #2: Current conflict as-of old date

    @Test
    fun `current_conflict_as_of_old_date_is_not_conflict`() {
        val assessor = assessor(now)
        val claim = candidate("ProductX2 currently has 12GB RAM.")
        val result = assessor.assess(claim, listOf(source("ProductX2 has 8GB RAM as of January 3, 2026.", "2026-09-05")))
        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, result.verdict)
    }

    // #3: Incompatible relative-period contradiction

    @Test
    fun `this_week_plus_last_week_is_insufficient`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX this week.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("This month, Acme reported ProductX was released last week.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `this_month_plus_last_month_is_insufficient`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX this month.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme reported ProductX was released last month.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `this_year_plus_last_year_is_insufficient`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX this year.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme reported ProductX was released last year.", "2026-09-05"))).verdict,
        )
    }

    // #4: Completed vs ambiguous event forms

    @Test
    fun `released_source_from_launched_may_support`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX in 2026.")
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme launched ProductX in 2026.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `released_source_from_launches_is_insufficient`() {
        val assessor = assessor(now)
        val claim = candidate("Acme released ProductX in 2026.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme launches ProductX in 2026.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `korean_completed_from_korean_started_may_support`() {
        val assessor = assessor(now)
        val claim = candidate("삼성전자는 2026년에 HBM4를 양산했다.")
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            assessor.assess(claim, listOf(source("삼성전자가 2026년 HBM4 양산을 시작했다.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `korean_completed_from_korean_present_is_insufficient`() {
        val assessor = assessor(now)
        val claim = candidate("삼성전자는 2026년에 HBM4를 양산했다.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("삼성전자가 2026년 HBM4 양산한다.", "2026-09-05"))).verdict,
        )
    }

    // #5: whether is interrogative

    @Test
    fun `whether_source_is_insufficient`() {
        val assessor = assessor(now)
        val claim = candidate("ProductX2 supports 12GB.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Whether ProductX2 supports 12GB", "2026-09-05"))).verdict,
        )
    }

    // #6: Relative duration patterns cannot prove CURRENT/RECENT

    @Test
    fun `days_ago_cannot_prove_current`() {
        val assessor = assessor(now)
        val claim = candidate("Acme currently supports ProductX2.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme supported ProductX2 3 days ago.", "2026-09-05"))).verdict,
        )
    }

    @Test
    fun `weeks_ago_cannot_prove_recent`() {
        val assessor = assessor(now)
        val claim = candidate("Acme recently released ProductX.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("Acme released ProductX 2 weeks ago.", "2026-09-05"))).verdict,
        )
    }

    // #6: N ago Korean patterns

    @Test
    fun `korean_days_ago_cannot_prove_current`() {
        val assessor = assessor(now)
        val claim = candidate("삼성전자는 현재 ProductX를 지원한다.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(source("삼성전자는 ProductX를 3일 전 지원했다.", "2026-09-05"))).verdict,
        )
    }
}
