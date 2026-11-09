package com.projectnuke.fusion.ui

import androidx.compose.ui.text.LinkAnnotation
import com.projectnuke.fusion.data.*
import com.projectnuke.fusion.privacy.AuthorizedTargetedQuery
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchAuthorization
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchIdentity
import com.projectnuke.fusion.search.*
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EvidenceCitationsTest {
    private val text = "Acme released ProductX2 in 2026."
    private val message = MessageEntity(1, 10, "assistant", text, 1)
    private fun source(id: String = "E1") = EvidenceSource(id, text, "Acme", "https://example.com/$id", text, "2026", "FREE_DEFAULT", "Search", "Acme ProductX2")
    private fun claim(id: String = "C1", body: String = text, verdict: EvidenceVerdict = EvidenceVerdict.SUPPORTED_BY_EVIDENCE, ids: List<String> = listOf("E1")) =
        EvidenceClaim(id, body, verdict, ids, null)
    private fun evidence(owner: MessageEntity = message, claims: List<EvidenceClaim> = listOf(claim()), sources: List<EvidenceSource> = listOf(source())) =
        MessageEvidenceReport(owner.id, EvidenceReport(1, EvidenceFingerprint.canonicalAnswerHash(owner.content), 1, EVIDENCE_VERIFIER_VERSION, claims, sources))

    @Test fun `supported exact claim gets interactive marker mapped to source`() {
        var selected: EvidenceSource? = null
        val result = buildEvidenceCitationText(message, text, evidence()) { selected = it }
        assertEquals("$text[E1]", result.text)
        val annotation = result.getLinkAnnotations(0, result.length).single()
        assertEquals(text.length, annotation.start)
        val link = annotation.item as LinkAnnotation.Clickable
        link.linkInteractionListener!!.onClick(link)
        assertEquals(source(), selected)
        assertEquals(text, message.content)
    }

    @Test fun `insufficient conflicting and not checked receive no support citation`() {
        for (verdict in listOf(EvidenceVerdict.INSUFFICIENT_EVIDENCE, EvidenceVerdict.CONFLICTING_EVIDENCE, EvidenceVerdict.NOT_CHECKED)) {
            val result = buildEvidenceCitationText(message, text, evidence(claims = listOf(claim(verdict = verdict)))) {}
            assertEquals(text, result.text)
            assertTrue(result.getLinkAnnotations(0, result.length).isEmpty())
        }
    }

    @Test fun `multiple sources use deterministic report source order`() {
        val report = evidence(claims = listOf(claim(ids = listOf("E3", "E1"))), sources = listOf(source(), source("E3")))
        val first = buildEvidenceCitationText(message, text, report) {}
        assertEquals("$text[E1][E3]", first.text)
        assertEquals(first.text, buildEvidenceCitationText(message, text, report) {}.text)
        assertEquals(2, first.getLinkAnnotations(0, first.length).size)
    }

    @Test fun `dangling source rejects entire invalid report`() {
        assertTrue(EvidenceCitationMapper.map(message, evidence(claims = listOf(claim(ids = listOf("E1", "E999"))))).isEmpty())
    }

    @Test fun `codec-valid empty claim cannot create a zero-length mapping loop`() {
        val report = evidence(claims = listOf(claim(body = "")))
        assertTrue(EvidenceCodec.validate(report.report) is EvidenceValidation.Valid)
        assertTrue(EvidenceCitationMapper.map(message, report).isEmpty())
    }

    @Test fun `fabricated E999 remains plain text even beside valid citations`() {
        val owner = message.copy(content = "$text [E999]")
        val result = buildEvidenceCitationText(owner, owner.content, evidence(owner)) {}
        assertEquals("$text[E1] [E999]", result.text)
        val fakeStart = result.text.indexOf("[E999]")
        assertTrue(result.getLinkAnnotations(fakeStart, result.length).isEmpty())
        assertEquals(1, result.getLinkAnnotations(0, result.length).size)
        val withoutReport = buildEvidenceCitationText(owner, owner.content, null) {}
        assertTrue(withoutReport.getLinkAnnotations(0, withoutReport.length).isEmpty())
    }

    @Test fun `stale report changed answer hash and invalid codec yield no markers`() {
        val current = message.copy(content = "Acme released ProductX2 in 2025.")
        assertTrue(EvidenceCitationMapper.map(current, evidence()).isEmpty())
        assertTrue(EvidenceCitationMapper.map(message, evidence().let { it.copy(report = it.report.copy(answerSha256 = "a".repeat(64))) }).isEmpty())
        assertTrue(EvidenceCitationMapper.map(message, evidence().let { it.copy(report = it.report.copy(schemaVersion = 99)) }).isEmpty())
    }

    @Test fun `old verifier reports and expired current reports have no interactive citations`() {
        val old = evidence().let {
            it.copy(report = it.report.copy(verifierVersion = "rules-v1"))
        }
        assertTrue(EvidenceCitationMapper.map(message, old).isEmpty())

        val now = Instant.parse("2026-09-05T00:00:00Z").toEpochMilli()
        val currentText = "Acme currently supports ProductX2."
        val currentMessage = message.copy(content = currentText)
        val currentSource = source().copy(title = currentText, snippet = currentText)
        val currentClaim = EvidenceClaim(
            "C1",
            currentText,
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            listOf("E1"),
            null,
        )
        val expired = MessageEvidenceReport(
            currentMessage.id,
            EvidenceReport(
                1,
                EvidenceFingerprint.canonicalAnswerHash(currentText),
                now - EvidenceReportCompatibilityPolicy.CURRENT_SENSITIVE_MAX_AGE_MS - 1L,
                EVIDENCE_VERIFIER_VERSION,
                listOf(currentClaim),
                listOf(currentSource),
            ),
        )
        assertTrue(EvidenceCitationMapper.map(currentMessage, expired, nowMillis = now).isEmpty())
        assertEquals(1, EvidenceCitationMapper.map(message, evidence(), nowMillis = now).size)
    }

    @Test fun `duplicate claims map forward by ordinal despite priority order`() {
        val owner = message.copy(content = "$text\n$text")
        val report = evidence(owner,
            claims = listOf(claim("C2", ids = listOf("E2")), claim("C1")),
            sources = listOf(source(), source("E2")),
        )
        assertEquals("$text[E1]\n$text[E2]", buildEvidenceCitationText(owner, owner.content, report) {}.text)
    }

    @Test fun `unsupported duplicate consumes its own ordinal without stealing supported placement`() {
        val owner = message.copy(content = "$text\n$text")
        val report = evidence(owner, listOf(claim("C1", verdict = EvidenceVerdict.INSUFFICIENT_EVIDENCE), claim("C2")))
        assertEquals("$text\n$text[E1]", buildEvidenceCitationText(owner, owner.content, report) {}.text)
    }

    @Test fun `ambiguous duplicates absent substrings and overlapping spans fail closed`() {
        val duplicate = message.copy(content = "$text\n$text")
        assertTrue(EvidenceCitationMapper.map(duplicate, evidence(duplicate)).isEmpty())
        assertTrue(EvidenceCitationMapper.map(message, evidence(claims = listOf(claim(body = "Absent claim.")))).isEmpty())
        val overlap = evidence(claims = listOf(claim(), claim("C2", body = "ProductX2 in 2026.")))
        assertTrue(EvidenceCitationMapper.map(message, overlap).isEmpty())
    }

    @Test fun `unknown duplicate ordinals fail closed`() {
        val owner = message.copy(content = "$text\n$text")
        val report = evidence(owner, listOf(claim("custom-a"), claim("custom-b")))
        assertTrue(EvidenceCitationMapper.map(owner, report).isEmpty())
    }

    @Test fun `fenced code is unchanged and only outside prose receives citation`() {
        for (fence in listOf("```", "~~~")) {
            val code = "$fence\n$text\n$fence\n"
            val owner = message.copy(content = code + text)
            val result = buildEvidenceCitationText(owner, owner.content, evidence(owner)) {}
            assertEquals("$text\n$text[E1]", result.text)
            assertTrue(result.getLinkAnnotations(0, text.length).isEmpty())
            assertEquals(code + text, owner.content)
        }
    }

    @Test fun `inline indented and unclosed code never receives markers`() {
        for (body in listOf("`$text`", "``$text``", "    $text", "\t$text", "```kotlin\n$text")) {
            val owner = message.copy(content = "Intro\n$body")
            val result = buildEvidenceCitationText(owner, owner.content, evidence(owner)) {}
            assertEquals("Intro\n$text", result.text)
            assertTrue(result.getLinkAnnotations(0, result.length).isEmpty())
        }
    }

    @Test fun `hidden metadata is excluded without modifying stored content`() {
        val raw = "<fusion_thinking>$text</fusion_thinking><fusion_answer>$text</fusion_answer>\n<fusion_metrics>42 tokens</fusion_metrics>"
        val owner = message.copy(content = appendSearchSourcesMetadata(raw, listOf(webSource())))
        val before = owner.copy()
        val visible = visibleMessageContentPreservingWhitespace(owner.content)
        assertEquals("$text[E1]", buildEvidenceCitationText(owner, visible, evidence(owner)) {}.text)
        assertEquals(before, owner)
        assertTrue(owner.content.contains("[FUSION_SEARCH_SOURCES_JSON]"))
    }

    @Test fun `display text differing from canonical body fails closed`() {
        assertTrue(EvidenceCitationMapper.map(message, evidence(), "$text extra").isEmpty())
    }

    @Test fun `regenerated version requires own message binding even with same answer hash`() {
        val version = message.copy(id = 2)
        assertTrue(EvidenceCitationMapper.map(version, evidence()).isEmpty())
        assertEquals(1, EvidenceCitationMapper.map(version, evidence(version)).size)
        assertEquals(1, EvidenceCitationMapper.map(message, evidence()).size)
    }

    @Test fun `user messages and oversized visible answers have no citations`() {
        val user = message.copy(role = "user")
        assertTrue(EvidenceCitationMapper.map(user, evidence(user)).isEmpty())
        val huge = message.copy(content = "x".repeat(100_001))
        assertTrue(EvidenceCitationMapper.map(huge, evidence(huge)).isEmpty())
    }

    @Test fun `web grounded message reuses attached source through existing pipeline without extra search`() = runTest {
        val owner = message.copy(content = appendSearchSourcesMetadata(text, listOf(webSource())))
        var searches = 0
        val verifier = SearchBackedEvidenceUiVerifier(object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                searches++
                return EvidenceSearchResult.Success(emptyList())
            }
        })
        val result = verifier.verify(owner, EvidenceVerificationPlanner.plan(owner.content)) as EvidenceVerificationResult.Complete
        assertEquals(0, searches)
        assertEquals(1, EvidenceCitationMapper.map(owner, MessageEvidenceReport(owner.id, result.report)).size)
    }

    @Test fun `explicit web recheck stays within six claims three searches twenty four sources`() = runTest {
        val owner = message.copy(content = appendSearchSourcesMetadata((1..10).joinToString("\n") { "Acme released ProductX$it in 2026." }, listOf(webSource())))
        var searches = 0
        val verifier = SearchBackedEvidenceUiVerifier(object : EvidenceSearchExecutor {
            override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                searches++
                return EvidenceSearchResult.Success(emptyList())
            }
        })
        val plan = EvidenceVerificationPlanner.plan(owner.content)
        val authorization = requireNotNull(
            PostAnswerVerificationSearchAuthorization.afterExplicitConfirmation(
                operationId = "citation-bounds-operation",
                identity = PostAnswerVerificationSearchIdentity(
                    conversationId = owner.conversationId,
                    assistantMessageId = owner.id,
                    answerSha256 = EvidenceFingerprint.canonicalAnswerHash(owner.content),
                    responseVersionId = owner.id,
                ),
                queries = plan.targetedSearchRequests.map { AuthorizedTargetedQuery(it.claimId, it.query) },
            ),
        )
        val result = verifier.verifyWithSearchAuthorization(
            owner,
            plan,
            "citation-bounds-operation",
            authorization,
        ) { true } as EvidenceVerificationResult.Complete
        assertTrue(searches <= 3)
        assertTrue(result.report.claims.size <= EvidencePlanningLimits.MAX_CLAIM_CANDIDATES)
        assertTrue(result.report.claims.any { it.verdict == EvidenceVerdict.NOT_CHECKED })
        assertTrue(result.report.sources.size <= 24)
    }

    private fun webSource() = WebSearchSource(text, "Acme", "https://example.com", text, "2026", WebSearchProviderType.FREE_DEFAULT, "Search", null, text, 1)
}
