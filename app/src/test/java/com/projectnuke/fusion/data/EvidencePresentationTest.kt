package com.projectnuke.fusion.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the rules-v3 presentation gate: typed OUTDATED reasons, future-clock
 * fail-closed behavior, and human-formatted checkedAt.
 */
class EvidencePresentationTest {

    private val now = Instant.parse("2026-09-05T00:00:00Z").toEpochMilli()

    private fun claim(text: String, verdict: EvidenceVerdict = EvidenceVerdict.SUPPORTED_BY_EVIDENCE) =
        EvidenceClaim("C1", text, verdict, emptyList(), null)

    @Test
    fun `future checkedAt beyond tolerance is clock invalid`() {
        val report = EvidenceReport(1, "a".repeat(64), now + 10 * 60 * 1_000L, EVIDENCE_VERIFIER_VERSION, listOf(claim("ProductX2 supports 12GB.")), emptyList())
        assertEquals(
            EvidenceOutdatedReason.CLOCK_INVALID,
            EvidenceReportCompatibilityPolicy.outdatedReason(report, "a".repeat(64), now),
        )
        assertEquals(
            EvidenceReportCompatibility.OUTDATED,
            EvidenceReportCompatibilityPolicy.classify(report, "a".repeat(64), now),
        )
    }

    @Test
    fun `tiny future checkedAt within skew tolerance is still current`() {
        val report = EvidenceReport(1, "a".repeat(64), now + 10_000L, EVIDENCE_VERIFIER_VERSION, listOf(claim("ProductX2 supports 12GB.")), emptyList())
        assertEquals(
            EvidenceReportCompatibility.CURRENT,
            EvidenceReportCompatibilityPolicy.classify(report, "a".repeat(64), now),
        )
        assertNull(EvidenceReportCompatibilityPolicy.outdatedReason(report, "a".repeat(64), now))
    }

    @Test
    fun `expired current sensitive report reason is time expired`() {
        val report = EvidenceReport(1, "a".repeat(64), now - 25L * 60L * 60L * 1_000L, EVIDENCE_VERIFIER_VERSION, listOf(claim("Acme currently supports ProductX2.")), emptyList())
        assertEquals(
            EvidenceOutdatedReason.TIME_EXPIRED,
            EvidenceReportCompatibilityPolicy.outdatedReason(report, "a".repeat(64), now),
        )
    }

    @Test
    fun `rules version mismatch reason is rules changed`() {
        val report = EvidenceReport(1, "a".repeat(64), now, "rules-v2", listOf(claim("ProductX2 supports 12GB.")), emptyList())
        assertEquals(
            EvidenceOutdatedReason.RULES_CHANGED,
            EvidenceReportCompatibilityPolicy.outdatedReason(report, "a".repeat(64), now),
        )
    }

    @Test
    fun `answer hash mismatch reason is rules changed`() {
        // A report bound to a different answer hash is outdated (rules changed path).
        val report = EvidenceReport(1, "a".repeat(64), now, EVIDENCE_VERIFIER_VERSION, listOf(claim("ProductX2 supports 12GB.")), emptyList())
        assertEquals(
            EvidenceOutdatedReason.RULES_CHANGED,
            EvidenceReportCompatibilityPolicy.outdatedReason(report, "mismatchedhash", now),
        )
    }

    @Test
    fun `current report has no outdated reason`() {
        val report = EvidenceReport(1, "a".repeat(64), now, EVIDENCE_VERIFIER_VERSION, listOf(claim("ProductX2 supports 12GB.")), emptyList())
        assertNull(EvidenceReportCompatibilityPolicy.outdatedReason(report, "a".repeat(64), now))
    }

    @Test
    fun `formatCheckedAt renders local date time`() {
        val formatted = formatCheckedAt(Instant.parse("2026-09-05T20:31:00Z").toEpochMilli())
        assertNotNull(formatted)
        // Pattern is yyyy-MM-dd HH:mm; verify structure without assuming zone.
        assertEquals(16, formatted.length)
        assertEquals('-', formatted[4])
        assertEquals('-', formatted[7])
        assertEquals(' ', formatted[10])
        assertEquals(':', formatted[13])
    }
}
