package com.projectnuke.fusion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rules-v11 version compatibility: old codec-valid rules-v5/rules-v7/rules-v8/rules-v9/rules-v10 durable reports
 * are preserved (round-trip byte-identical, never deleted) but present as
 * OUTDATED / RULES_CHANGED.
 * Current rules-v11 reports remain CURRENT.
 */
class EvidenceVersionCompatibilityTest {

    private val now = 1_755_000_000_000L

    private fun v5Report() = EvidenceReport(
        schemaVersion = 1,
        answerSha256 = "a".repeat(64),
        checkedAt = now,
        verifierVersion = "rules-v5",
        claims = listOf(
            EvidenceClaim("C1", "ProductX2 supports 12GB.", EvidenceVerdict.SUPPORTED_BY_EVIDENCE, listOf("E1"), null),
        ),
        sources = listOf(
            EvidenceSource("E1", "ProductX2 supports 12GB.", "test", "https://example.com/e1", "", null, "TEST", "Test", "q"),
        ),
    )

    @Test
    fun `rules-v5 report is codec-valid and roundtrips intact`() {
        val report = v5Report()
        assertTrue(EvidenceCodec.validate(report) is EvidenceValidation.Valid)
        val encoded = EvidenceCodec.serialize(report)
        val loaded = EvidenceCodec.load(encoded)
        assertTrue(loaded is EvidenceCodecResult.Valid)
        assertEquals(report, (loaded as EvidenceCodecResult.Valid).report)
    }

    @Test
    fun `rules-v5 report presents OUTDATED with RULES_CHANGED under rules-v10`() {
        val report = v5Report()
        assertEquals(
            EvidenceReportCompatibility.OUTDATED,
            EvidenceReportCompatibilityPolicy.classify(report, "a".repeat(64), now),
        )
        assertEquals(
            EvidenceOutdatedReason.RULES_CHANGED,
            EvidenceReportCompatibilityPolicy.outdatedReason(report, "a".repeat(64), now),
        )
    }

    @Test
    fun `rules-v7 report presents OUTDATED with RULES_CHANGED under rules-v11`() {
        val report = v5Report().copy(verifierVersion = "rules-v7")
        assertEquals("rules-v11", EVIDENCE_VERIFIER_VERSION)
        assertEquals(
            EvidenceReportCompatibility.OUTDATED,
            EvidenceReportCompatibilityPolicy.classify(report, "a".repeat(64), now),
        )
        assertEquals(
            EvidenceOutdatedReason.RULES_CHANGED,
            EvidenceReportCompatibilityPolicy.outdatedReason(report, "a".repeat(64), now),
        )
    }

    @Test
    fun `rules-v8 report presents OUTDATED with RULES_CHANGED under rules-v11`() {
        val report = v5Report().copy(verifierVersion = "rules-v8")
        assertEquals("rules-v11", EVIDENCE_VERIFIER_VERSION)
        assertEquals(
            EvidenceReportCompatibility.OUTDATED,
            EvidenceReportCompatibilityPolicy.classify(report, "a".repeat(64), now),
        )
        assertEquals(
            EvidenceOutdatedReason.RULES_CHANGED,
            EvidenceReportCompatibilityPolicy.outdatedReason(report, "a".repeat(64), now),
        )
    }

    @Test
    fun `rules-v9 report is invalidated because it has no candidate window coverage bit`() {
        val report = v5Report().copy(verifierVersion = "rules-v9")
        assertEquals("rules-v11", EVIDENCE_VERIFIER_VERSION)
        assertEquals(
            EvidenceReportCompatibility.OUTDATED,
            EvidenceReportCompatibilityPolicy.classify(report, "a".repeat(64), now),
        )
        assertEquals(
            EvidenceOutdatedReason.RULES_CHANGED,
            EvidenceReportCompatibilityPolicy.outdatedReason(report, "a".repeat(64), now),
        )
    }

    @Test
    fun `rules-v10 report without overlength coverage is invalidated`() {
        val report = v5Report().copy(verifierVersion = "rules-v10")
        assertEquals("rules-v11", EVIDENCE_VERIFIER_VERSION)
        assertEquals(
            EvidenceReportCompatibility.OUTDATED,
            EvidenceReportCompatibilityPolicy.classify(report, "a".repeat(64), now),
        )
    }

    @Test
    fun `current rules-v11 report stays CURRENT`() {
        val report = v5Report().copy(verifierVersion = EVIDENCE_VERIFIER_VERSION)
        assertEquals("rules-v11", EVIDENCE_VERIFIER_VERSION)
        assertEquals(
            EvidenceReportCompatibility.CURRENT,
            EvidenceReportCompatibilityPolicy.classify(report, "a".repeat(64), now),
        )
    }
}
