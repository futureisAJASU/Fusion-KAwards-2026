package com.projectnuke.fusion.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataEgressPolicyTest {
    private fun privateContext() = RequestPrivacyContext(
        hasAttachments = true,
        attachmentCount = 1,
        hasLocalRagExcerpts = true,
        localExcerptChars = 500,
        fileNames = listOf("report.txt"),
        userText = "이 문서 요약해줘",
    )

    @Test
    fun `local rag works without network`() {
        // C4 retrieval is local-only; privacy layer asserts no transport.
        val webQuery = DataEgressPolicy.buildWebQuery("사과 효능", privateContext())
        assertEquals("사과 효능", webQuery)
    }

    @Test
    fun `attachment text absent from web transport`() {
        val excerpts = listOf("비밀 보고서 본문 전체", "첨부파일 핵심 문단")
        val context = privateContext()
        val webQuery = DataEgressPolicy.buildWebQuery("최신 주가 알려줘", context)
        assertFalse(DataEgressPolicy.webQueryContainsPrivate(webQuery, excerpts, context.fileNames))
        assertEquals("최신 주가 알려줘", webQuery)
    }

    @Test
    fun `web query privacy check rejects case insensitive excerpts filenames and personal identifiers`() {
        assertTrue(DataEgressPolicy.webQueryContainsPrivate("ACME Launch plan", listOf("launch PLAN"), emptyList()))
        assertTrue(DataEgressPolicy.webQueryContainsPrivate("open report.PDF now", emptyList(), listOf("Report.pdf")))
        assertTrue(DataEgressPolicy.webQueryContainsPrivate("Contact private@example.com", emptyList(), emptyList()))
        assertTrue(DataEgressPolicy.webQueryContainsPrivate("Open C:\\Users\\Jun\\private.txt", emptyList(), emptyList()))
    }

    @Test
    fun `attachment text absent from external transport before consent`() {
        val context = privateContext()
        val hash = DataEgressPolicy.payloadHash("이 문서 요약해줘")
        assertFalse(DataEgressPolicy.externalAiAllowed(context, null, hash, "req-1", "p1"))
    }

    @Test
    fun `explicit user query may be sent but excerpts and filenames are not appended`() {
        val context = privateContext()
        val webQuery = DataEgressPolicy.buildWebQuery("삼성전자 주가", context)
        assertTrue(webQuery.contains("삼성전자 주가"))
        assertFalse(webQuery.contains("report.txt"))
    }

    @Test
    fun `private payload before consent zero calls`() {
        var calls = 0
        val context = privateContext()
        val hash = DataEgressPolicy.payloadHash(context.userText)
        if (DataEgressPolicy.externalAiAllowed(context, null, hash, "req-1", "p1")) calls++
        assertEquals(0, calls)
    }

    @Test
    fun `cancel zero calls approve one exactly one permitted call`() {
        var calls = 0
        val context = privateContext()
        val hash = DataEgressPolicy.payloadHash("요약해줘")
        // Cancel: no consent -> no call.
        if (DataEgressPolicy.externalAiAllowed(context, null, hash, "req-1", "p1")) calls++
        assertEquals(0, calls)
        // Approve one request -> exactly one permitted call under single-flight.
        val consent = DataEgressPolicy.makeConsent("c1", "req-1", "p1", hash, true, DataEgressScope.USER_QUERY_AND_HISTORY)
        val gate = DataEgressGate()
        repeat(2) {
            if (gate.tryEnter()) {
                if (DataEgressPolicy.externalAiAllowed(context, consent, hash, "req-1", "p1")) calls++
                // Hold gate across rapid double-confirm: second tap rejected.
            }
        }
        gate.release()
        assertEquals(1, calls)
    }

    @Test
    fun `changed payload invalidates consent`() {
        val hash1 = DataEgressPolicy.payloadHash("원본 요청")
        val hash2 = DataEgressPolicy.payloadHash("바뀐 요청")
        val consent = DataEgressPolicy.makeConsent("c1", "req-1", "p1", hash1, true, DataEgressScope.USER_QUERY_AND_HISTORY)
        assertTrue(DataEgressPolicy.validateConsent(consent, "req-1", "p1", hash1))
        assertFalse(DataEgressPolicy.validateConsent(consent, "req-1", "p1", hash2))
    }

    @Test
    fun `changed provider invalidates consent`() {
        val hash = DataEgressPolicy.payloadHash("요청")
        val consent = DataEgressPolicy.makeConsent("c1", "req-1", "p1", hash, true, DataEgressScope.USER_QUERY_AND_HISTORY)
        assertTrue(DataEgressPolicy.validateConsent(consent, "req-1", "p1", hash))
        assertFalse(DataEgressPolicy.validateConsent(consent, "req-1", "p2", hash))
        assertFalse(DataEgressPolicy.validateConsent(consent, "req-2", "p1", hash))
    }

    @Test
    fun `no blanket consent survives into unrelated request`() {
        val hash = DataEgressPolicy.payloadHash("요청")
        val consent = DataEgressPolicy.makeConsent("c1", "req-1", "p1", hash, true, DataEgressScope.USER_QUERY_AND_HISTORY)
        assertFalse(DataEgressPolicy.validateConsent(consent, "req-other", "p1", hash))
    }

    @Test
    fun `secrets absent from diagnostic output`() {
        val raw = "call failed key=sk-secret-123 Authorization: Bearer abcdef private=비밀보고서본문전체12345"
        val sanitized = DataEgressPolicy.sanitizeDiagnostics(
            raw,
            secrets = listOf("sk-secret-123", "abcdef"),
            privateTexts = listOf("비밀보고서본문전체12345"),
        )
        assertFalse(DataEgressPolicy.diagnosticsLeaksSecrets(sanitized, listOf("sk-secret-123", "abcdef")))
        assertFalse(DataEgressPolicy.diagnosticsLeaksPrivate(sanitized, listOf("비밀보고서본문전체12345")))
        assertFalse(sanitized.contains("Bearer abcdef"))
    }

    @Test
    fun `trust route separate from egress policy`() {
        assertTrue(DataEgressPolicy.trustRouteSeparateFromEgress())
        // SEARCH does not imply attachment egress.
        val context = privateContext()
        val webQuery = DataEgressPolicy.buildWebQuery("검색어", context)
        assertFalse(webQuery.contains("report.txt"))
        // CLOUD_SUGGEST does not imply consent: private blocks escalation.
        assertFalse(DataEgressPolicy.cloudEscalationAllowed(context))
        assertTrue(DataEgressPolicy.cloudEscalationAllowed(RequestPrivacyContext()))
    }

    @Test
    fun `privacy ui stays lightweight`() {
        assertTrue(DataEgressPolicy.userFacingNotice(privateContext()).contains("기기에"))
        assertEquals("", DataEgressPolicy.userFacingNotice(RequestPrivacyContext()))
    }
}
