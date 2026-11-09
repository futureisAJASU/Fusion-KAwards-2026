package com.projectnuke.fusion.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchInterventionAuditRecordTest {
    @Test
    fun `codec round trip preserves the bounded audit summary`() {
        val record = record()
        val decoded = ResearchInterventionAuditCodec.decode(
            ResearchInterventionAuditCodec.encode(record),
        )

        assertEquals(record, decoded)
    }

    @Test
    fun `codec records draft subject and stage for abstention and accepted revision`() {
        val withheld = record().copy(
            action = ResearchInterventionAction.ABSTAIN,
            findingSubject = ResearchInterventionFindingSubject.PRE_PERSISTENCE_DRAFT,
            draftStage = ResearchInterventionDraftStage.ORIGINAL,
        )
        val revised = record().copy(
            findingSubject = ResearchInterventionFindingSubject.FINAL_USER_VISIBLE_ANSWER,
            draftStage = ResearchInterventionDraftStage.REVISED,
        )

        assertEquals(withheld, ResearchInterventionAuditCodec.decode(ResearchInterventionAuditCodec.encode(withheld)))
        assertEquals(revised, ResearchInterventionAuditCodec.decode(ResearchInterventionAuditCodec.encode(revised)))
    }

    @Test
    fun `legacy abstention record is marked as findings about pre persistence draft`() {
        val decoded = ResearchInterventionAuditCodec.decode(
            """{"v":1,"messageId":42,"conversationId":71,"answerSha256":"${"a".repeat(64)}","savedAt":1000,"action":"ABSTAIN","reason":"INSUFFICIENT_EVIDENCE","wholeAnswerCertified":false,"claimScopedOnly":true,"revisionAttempted":false,"retrievalAttempted":false,"claims":[]}""",
        )

        assertEquals(ResearchInterventionFindingSubject.PRE_PERSISTENCE_DRAFT, decoded?.findingSubject)
        assertEquals(ResearchInterventionDraftStage.LEGACY_UNKNOWN, decoded?.draftStage)
    }

    @Test
    fun `codec never persists source excerpt text`() {
        val record = record().copy(
            claims = listOf(
                record().claims.first().copy(
                    claimText = "Acme released Alpha in 2024.",
                    evidence = listOf(
                        ResearchInterventionAuditEvidenceRecord(
                            evidenceIdentity = "draft-abc",
                            title = "Acme release note",
                            url = "https://example.com/alpha",
                            providerDisplayName = "Example",
                        ),
                    ),
                ),
            ),
        )
        val encoded = ResearchInterventionAuditCodec.encode(record)

        assertFalse(encoded.contains("PRIVATE_EXCERPT_MARKER"))
        assertTrue(encoded.contains("Acme released Alpha in 2024."))
    }

    @Test
    fun `codec bounds claim text and evidence count`() {
        val record = record().copy(
            claims = listOf(
                record().claims.first().copy(
                    claimText = "x".repeat(5_000),
                    evidence = (1..20).map { index ->
                        ResearchInterventionAuditEvidenceRecord(
                            evidenceIdentity = "e$index",
                            title = "title $index",
                            url = "https://example.com/$index",
                            providerDisplayName = "Example",
                        )
                    },
                ),
            ),
        )
        val decoded = ResearchInterventionAuditCodec.decode(
            ResearchInterventionAuditCodec.encode(record),
        )!!

        assertEquals(600, decoded.claims.single().claimText.length)
        assertEquals(8, decoded.claims.single().evidence.size)
    }

    @Test
    fun `decode rejects malformed and unknown records`() {
        assertNull(ResearchInterventionAuditCodec.decode("not json"))
        assertNull(ResearchInterventionAuditCodec.decode("{}"))
        assertNull(
            ResearchInterventionAuditCodec.decode(
                """{"v":1,"messageId":1,"conversationId":2,"action":"NOT_AN_ACTION","reason":"INSUFFICIENT_EVIDENCE"}""",
            ),
        )
    }

    private fun record() = ResearchInterventionAuditRecord(
        messageId = 42L,
        conversationId = 71L,
        answerSha256 = "a".repeat(64),
        savedAt = 1_000L,
        action = ResearchInterventionAction.DELIVER_WITH_SCOPED_AUDIT,
        reason = ResearchInterventionReason.SUPPORTED_CLAIMS_COVERAGE_INCOMPLETE,
        wholeAnswerCertified = false,
        claimScopedOnly = true,
        revisionAttempted = false,
        retrievalAttempted = true,
        claims = listOf(
            ResearchInterventionAuditClaimRecord(
                claimId = "AC1",
                claimText = "Acme released Alpha in 2024.",
                claimTextSha256 = "b".repeat(64),
                status = ResearchDraftClaimFindingStatus.INSUFFICIENT,
                evidence = listOf(
                    ResearchInterventionAuditEvidenceRecord(
                        evidenceIdentity = "draft-abc",
                        title = "Acme release note",
                        url = "https://example.com/alpha",
                        providerDisplayName = "Example",
                    ),
                ),
            ),
        ),
    )
}
