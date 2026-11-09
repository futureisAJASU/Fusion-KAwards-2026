package com.projectnuke.fusion.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchInterventionSearchPermitTest {
    @Test
    fun `permit requires explicit consent exact fingerprint and bounded distinct queries`() {
        val fingerprint = ResearchInterventionSearchPermit.sha256Fingerprint("draft")
        val query = ResearchInterventionSearchQuery("AC1", "Acme Alpha 2025")

        assertNull(grant(fingerprint, listOf(query), explicitConsent = false))
        assertNull(grant("not-a-fingerprint", listOf(query)))
        assertNull(grant(fingerprint, listOf(query, query.copy(query = "another query"))))
        assertNull(grant(fingerprint, listOf(query, query.copy(claimId = "AC2"))))
        assertNull(grant(fingerprint, emptyList()))
        assertNull(grant(fingerprint, listOf(query.copy(query = "a".repeat(241)))))
        assertNotNull(grant(fingerprint, listOf(query)))
    }

    @Test
    fun `permit rejects queries containing registered private excerpts or local file names`() {
        val fingerprint = ResearchInterventionSearchPermit.sha256Fingerprint("draft")
        val query = ResearchInterventionSearchQuery("AC1", "Acme private launch plan")

        assertNull(
            ResearchInterventionSearchPermit.grant(
                conversationId = 71L,
                requestId = "request-1",
                draftFingerprint = fingerprint,
                queries = listOf(query),
                explicitConsent = true,
                forbiddenPrivateExcerpts = listOf("private launch plan"),
            ),
        )
        assertNull(
            ResearchInterventionSearchPermit.grant(
                conversationId = 71L,
                requestId = "request-1",
                draftFingerprint = fingerprint,
                queries = listOf(ResearchInterventionSearchQuery("AC1", "Acme internal-report.pdf")),
                explicitConsent = true,
                forbiddenFileNames = listOf("internal-report.pdf"),
            ),
        )
    }

    @Test
    fun `permit is exact request bound and cannot be used before its one shot claim`() {
        val fingerprint = ResearchInterventionSearchPermit.sha256Fingerprint("draft")
        val query = ResearchInterventionSearchQuery("AC1", "Acme Alpha 2025")
        val permit = grant(fingerprint, listOf(query))!!

        assertFalse(permit.authorizes(query.claimId, query.query))
        assertTrue(permit.claimExecution(71L, "request-1", fingerprint))
        assertTrue(permit.authorizes(query.claimId, query.query))
        assertFalse(permit.authorizes(query.claimId, "unapproved query"))
        assertFalse(permit.authorizes("AC2", query.query))
        assertFalse(permit.claimExecution(71L, "request-1", fingerprint))
        assertFalse(permit.claimExecution(72L, "request-1", fingerprint))
        assertFalse(permit.wasClaimedFor(72L, "request-1", fingerprint))
        assertTrue(permit.wasClaimedFor(71L, "request-1", fingerprint))
    }

    @Test
    fun `claiming with a different draft or request cannot consume the permit`() {
        val fingerprint = ResearchInterventionSearchPermit.sha256Fingerprint("draft")
        val permit = grant(fingerprint, listOf(ResearchInterventionSearchQuery("AC1", "Acme Alpha 2025")))!!

        assertFalse(permit.claimExecution(71L, "other-request", fingerprint))
        assertFalse(permit.claimExecution(71L, "request-1", "b".repeat(64)))
        assertTrue(permit.claimExecution(71L, "request-1", fingerprint))
    }

    private fun grant(
        fingerprint: String,
        queries: List<ResearchInterventionSearchQuery>,
        explicitConsent: Boolean = true,
    ) = ResearchInterventionSearchPermit.grant(
        conversationId = 71L,
        requestId = "request-1",
        draftFingerprint = fingerprint,
        queries = queries,
        explicitConsent = explicitConsent,
    )
}
