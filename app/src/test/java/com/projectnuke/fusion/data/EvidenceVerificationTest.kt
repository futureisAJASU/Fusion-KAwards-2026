package com.projectnuke.fusion.data

import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceVerificationAssessorTest {
    private val assessor = ConservativeRuleEvidenceAssessor()

    @Test
    fun `claim mirroring provenance is never semantic evidence`() {
        val text = "Acme released ProductX2 12GB in 2026."
        val source = evidenceSource("Acme quarterly earnings").copy(
            snippet = "Revenue increased.", queryUsed = text, source = text,
            providerDisplayName = text, url = "https://example.com/$text",
        )
        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, assessor.assess(candidate(text), listOf(source)).verdict)
    }

    @Test
    fun `local subject binding rejects wrong subject in either order and across fields`() {
        val text = "ProductX2 supports 12GB."
        for (separator in listOf(". ", "; ", "\n")) {
            for (payload in listOf(
                "ProductX2 is mentioned${separator}ProductX3 supports 12GB.",
                "ProductX3 supports 12GB${separator}ProductX2 is mentioned.",
            )) {
                assertEquals(payload, EvidenceVerdict.INSUFFICIENT_EVIDENCE,
                    assessor.assess(candidate(text), listOf(evidenceSource(payload))).verdict)
            }
        }
        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, assessor.assess(candidate(text), listOf(
            evidenceSource("ProductX2 is mentioned").copy(snippet = "ProductX3 supports 12GB."),
        )).verdict)
    }

    @Test
    fun `multi SKU values cannot conflict with locally supported claim in either order`() {
        for (payload in listOf(
            "ProductX2 has 12GB RAM. ProductX3 has 8GB RAM.",
            "ProductX3 has 8GB RAM. ProductX2 has 12GB RAM.",
        )) {
            assertEquals(EvidenceVerdict.SUPPORTED_BY_EVIDENCE, assessor.assess(
                candidate("ProductX2 has 12GB RAM."), listOf(evidenceSource(payload)),
            ).verdict)
        }
    }

    @Test
    fun `release and announcement remain distinct while launch aligns`() {
        val claim = candidate("Acme released ProductX2 in 2026.")
        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, assessor.assess(claim,
            listOf(evidenceSource("Acme announced ProductX2 in 2026."))).verdict)
        assertEquals(EvidenceVerdict.SUPPORTED_BY_EVIDENCE, assessor.assess(claim,
            listOf(evidenceSource("Acme launched ProductX2 in 2026."))).verdict)
    }

    @Test
    fun `genuine conflicting source overrides support`() {
        val result = assessor.assess(candidate("ProductX2 has 12GB."), listOf(
            evidenceSource("ProductX2 has 12GB."),
            evidenceSource("ProductX2 has 8GB.").copy(id = "E2"),
        ))
        assertEquals(EvidenceVerdict.CONFLICTING_EVIDENCE, result.verdict)
        assertEquals(listOf("E1", "E2"), result.sourceIds)
    }

    @Test
    fun `negative contractions and unsafe modality never support a positive claim`() {
        val claim = candidate("ProductX2 supports 12GB.")
        val sources = listOf(
            "ProductX2 doesn't support 12GB.",
            "ProductX2 cannot support 12GB.",
            "ProductX2 may support 12GB.",
            "ProductX2 does\u2019t support 12GB.",
        )

        sources.forEach { text ->
            assertEquals(text, EvidenceVerdict.INSUFFICIENT_EVIDENCE,
                assessor.assess(claim, listOf(evidenceSource(text))).verdict)
        }
    }

    @Test
    fun `future and planned release language is never positive support`() {
        val claim = candidate("Acme released ProductX2 in 2026.")
        val sources = listOf(
            "Acme could release ProductX2 in 2026.",
            "Acme will release ProductX2 in 2026.",
            "Acme plans to release ProductX2 in 2026.",
            "Acme is expected to release ProductX2 in 2026.",
        )

        sources.forEach { text ->
            assertEquals(text, EvidenceVerdict.INSUFFICIENT_EVIDENCE,
                assessor.assess(claim, listOf(evidenceSource(text))).verdict)
        }
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            assessor.assess(claim, listOf(evidenceSource("Acme launched ProductX2 in 2026."))).verdict,
        )
    }

    @Test
    fun `same segment storage capacity does not conflict with exact RAM claim`() {
        val claim = candidate("ProductX2 has 12GB RAM.")
        for (text in listOf(
            "ProductX2 has 12GB RAM and 512GB storage.",
            "ProductX2 has 12GB RAM and 256GB storage.",
        )) {
            assertEquals(
                EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
                assessor.assess(claim, listOf(evidenceSource(text))).verdict,
            )
        }
    }

    @Test
    fun `same relation value conflicts only with a same unit and attribute`() {
        val claim = candidate("ProductX2 has 12GB RAM.")
        assertEquals(
            EvidenceVerdict.CONFLICTING_EVIDENCE,
            assessor.assess(claim, listOf(evidenceSource("ProductX2 has 8GB RAM."))).verdict,
        )
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(evidenceSource("ProductX2 has 12288MB RAM."))).verdict,
        )
    }

    @Test
    fun `precise dates require the same day and do not fall back to year`() {
        val claim = candidate("Acme released ProductX2 on September 5, 2026.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(claim, listOf(evidenceSource("Acme released ProductX2 on March 1, 2026."))).verdict,
        )
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            assessor.assess(claim, listOf(evidenceSource("Acme launched ProductX2 on September 5, 2026."))).verdict,
        )
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(candidate("Acme released ProductX2 on 2026-09-05."),
                listOf(evidenceSource("Acme released ProductX2 on 2026-03-01."))).verdict,
        )
    }

    @Test
    fun `current support requires recent parseable publication metadata`() {
        val now = Instant.parse("2026-09-05T00:00:00Z").toEpochMilli()
        val currentAssessor = ConservativeRuleEvidenceAssessor(EvidenceClock { now })
        val claim = candidate("Acme currently supports ProductX2.")
        val body = "Acme supports ProductX2."

        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            currentAssessor.assess(claim, listOf(evidenceSource(body).copy(publishedAt = "2020-01-01"))).verdict,
        )
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            currentAssessor.assess(claim, listOf(evidenceSource(body).copy(publishedAt = "2026-09-01"))).verdict,
        )
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            currentAssessor.assess(
                claim,
                listOf(evidenceSource(body).copy(publishedAt = "Sat, 05 Sep 2026 00:00:00 GMT")),
            ).verdict,
        )
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            currentAssessor.assess(claim, listOf(evidenceSource(body).copy(publishedAt = "not-a-date"))).verdict,
        )
    }

    @Test
    fun `bounded Korean relations support production and reject negative support`() {
        val productionClaim = candidate("삼성전자는 2026년에 HBM4를 양산했다.")
        assertEquals(
            EvidenceVerdict.SUPPORTED_BY_EVIDENCE,
            assessor.assess(
                productionClaim,
                listOf(evidenceSource("삼성전자가 2026년 HBM4 양산을 시작했다.")),
            ).verdict,
        )

        val supportClaim = candidate("삼성전자는 ProductX를 지원한다.")
        assertEquals(
            EvidenceVerdict.INSUFFICIENT_EVIDENCE,
            assessor.assess(
                supportClaim,
                listOf(evidenceSource("삼성전자는 ProductX를 지원하지 않는다.")),
            ).verdict,
        )
    }

    @Test
    fun `same sentence multiple subjects and predicates fail closed`() {
        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, assessor.assess(
            candidate("ProductX2 supports 12GB."),
            listOf(evidenceSource("ProductX2 is mentioned and ProductX3 supports 12GB.")),
        ).verdict)
    }

    @Test
    fun `materially aligned critical anchors with ambiguous source form are insufficient`() {
        val claim = candidate("Acme released ProductX2 12GB in 2026.")
        val source = evidenceSource("Acme launches ProductX2 12GB in 2026.")

        val result = assessor.assess(claim, listOf(source))

        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, result.verdict)
    }

    @Test
    fun `partial critical anchors are insufficient`() {
        val claim = candidate("Acme released ProductX2 12GB in 2026.")
        val source = evidenceSource("Acme financial results for 2026.")

        val result = assessor.assess(claim, listOf(source))

        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, result.verdict)
    }

    @Test
    fun `wrong product is not supported`() {
        val claim = candidate("Acme released ProductX2 12GB in 2026.")
        val source = evidenceSource("Acme released ProductX3 12GB in 2026.")

        val result = assessor.assess(claim, listOf(source))

        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, result.verdict)
    }

    @Test
    fun `numeric contradiction is reported conservatively`() {
        val claim = candidate("ProductX2 has 12GB RAM.")
        val source = evidenceSource("ProductX2 has 8GB RAM.")

        val result = assessor.assess(claim, listOf(source))

        assertEquals(EvidenceVerdict.CONFLICTING_EVIDENCE, result.verdict)
    }

    @Test
    fun `date contradiction is reported conservatively`() {
        val claim = candidate("Acme released ProductX2 in 2026.")
        val source = evidenceSource("Acme released ProductX2 in 2025.")

        val result = assessor.assess(claim, listOf(source))

        assertEquals(EvidenceVerdict.CONFLICTING_EVIDENCE, result.verdict)
    }

    @Test
    fun `different product number does not create false conflict`() {
        val claim = candidate("ProductX2 has 12GB RAM.")
        val source = evidenceSource("ProductX3 has 8GB RAM.")

        val result = assessor.assess(claim, listOf(source))

        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, result.verdict)
    }

    @Test
    fun `different relation with matching keywords is insufficient`() {
        val claim = candidate("Acme released ProductX2 in 2026.")
        val source = evidenceSource("Acme discussed ProductX2 in its 2026 annual report.")

        val result = assessor.assess(claim, listOf(source))

        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, result.verdict)
    }

    @Test
    fun `many overlapping keywords cannot support wrong subject relation`() {
        val claim = candidate("ProductX2 supports 12GB.")
        val source = evidenceSource("ProductX3 supports 12GB; ProductX2 is also mentioned.")

        val result = assessor.assess(claim, listOf(source))

        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, result.verdict)
    }

    @Test
    fun `non-checkable claim is not checked`() {
        val claim = candidate(
            text = "이 전략은 매우 좋다.",
            classification = ClaimClassification.SUBJECTIVE_OR_NORMATIVE,
            priority = ClaimPriority.NONE,
        )

        val result = assessor.assess(claim, listOf(evidenceSource("This contains unrelated text.")))

        assertEquals(EvidenceVerdict.NOT_CHECKED, result.verdict)
        assertEquals(claim.reason.name, result.reasonCode)
    }

    @Test
    fun `empty sources fail closed`() {
        val result = assessor.assess(candidate("Acme released ProductX2 in 2026."), emptyList())

        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, result.verdict)
    }
}

class EvidenceVerificationServiceTest {
    @Test
    fun `oversized custom strings rejected before trim hashing or matching`() = runTest {
        val huge = " ".repeat(1_000_000)
        val base = plannedClaim("ProductX2 supports 12GB.")
        val executor = FakeSearchExecutor()
        val invalid = VerificationPlan(listOf(base.copy(candidate = base.candidate.copy(id = huge))), emptyList(), emptyList())
        assertEquals(EvidenceVerificationFailureReason.INVALID_PLAN,
            (service(executor).verify(assistantMessage("answer"), invalid) as EvidenceVerificationResult.Failed).reason)
        val plan = VerificationPlan(
            listOf(base.copy(existingSourceCandidateIds = listOf(huge))),
            listOf(planningSource("ProductX2 supports 12GB.", "https://example.com").copy(id = huge)),
            listOf(TargetedSearchRequest("C1", huge + "query"), TargetedSearchRequest(huge, "query")),
        )
        val result = report(service(executor).verify(assistantMessage("answer"), plan))
        assertEquals(0, executor.calls)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `strong existing evidence with ambiguous source form is insufficient`() = runTest {
        val message = assistantMessage("Acme released ProductX2 12GB in 2026.")
        val existing = source("Acme launches ProductX2 12GB in 2026.", url = "https://example.com/release")
        val plan = EvidenceVerificationPlanner.plan(message.content, listOf(existing))
        val executor = FakeSearchExecutor()

        val result = service(executor).verify(message, plan)

        assertTrue(result is EvidenceVerificationResult.Complete)
        assertEquals(0, executor.calls)
        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, report(result).claims.single().verdict)
    }

    @Test
    fun `targeted search sources contribute to assessment`() = runTest {
        val message = assistantMessage("Acme released ProductX2 12GB in 2026.")
        val plan = EvidenceVerificationPlanner.plan(message.content)
        val executor = FakeSearchExecutor(
            source("Acme launches ProductX2 12GB in 2026.", url = "https://example.com/targeted"),
        )

        val result = service(executor).verify(message, plan)

        assertEquals(1, executor.calls)
        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, report(result).claims.single().verdict)
        assertEquals(1, report(result).sources.size)
    }

    @Test
    fun `custom plan request count is hard capped at three`() = runTest {
        val claims = (1..6).map { plannedClaim("Acme released ProductX$it in 2026.", "C$it") }
        val requests = claims.map { TargetedSearchRequest(it.candidate.id, it.candidate.text) } +
            TargetedSearchRequest("C1", "duplicate extra")
        val executor = FakeSearchExecutor()

        val result = service(executor).verify(assistantMessage("answer"), VerificationPlan(claims, emptyList(), requests))

        assertTrue(result is EvidenceVerificationResult.Complete)
        assertEquals(EvidenceVerificationLimits.MAX_TARGETED_SEARCH_REQUESTS, executor.calls)
    }

    @Test
    fun `custom plan claim count is hard capped at six`() = runTest {
        val claims = (1..8).map { plannedClaim("Acme released ProductX$it in 2026.", "C$it") }
        val executor = FakeSearchExecutor()

        val result = service(executor).verify(assistantMessage("answer"), VerificationPlan(claims, emptyList(), emptyList()))

        assertTrue(result is EvidenceVerificationResult.Complete)
        assertEquals(EvidenceVerificationLimits.MAX_CLAIMS_TO_CHECK, report(result).claims.size)
    }

    @Test
    fun `search result source count is hard capped`() = runTest {
        val plan = VerificationPlan(
            claims = listOf(plannedClaim("Acme released ProductX2 in 2026.")),
            existingSources = emptyList(),
            targetedSearchRequests = listOf(TargetedSearchRequest("C1", "Acme ProductX2 2026")),
        )
        val executor = FakeSearchExecutor(sources = (1..50).map { source("result $it", "https://example.com/$it") })

        val result = service(executor).verify(assistantMessage("answer"), plan)

        assertEquals(EvidenceVerificationLimits.MAX_SOURCES_PER_SEARCH_RESULT, report(result).sources.size)
    }

    @Test
    fun `custom plan source fields are bounded before assessment`() = runTest {
        val huge = "x".repeat(EvidenceLimits.MAX_RAW_JSON_CHARS)
        val boundedPlanSource = planningSource("P1", "https://example.com/plan").copy(
            title = huge,
            source = huge,
            url = huge,
            snippet = huge,
            publishedAt = huge,
            providerDisplayName = huge,
            searchCategory = huge,
            queryUsed = huge,
        )
        val plan = VerificationPlan(
            claims = listOf(plannedClaim("Acme released ProductX2 in 2026.").copy(existingSourceCandidateIds = listOf("P1"))),
            existingSources = listOf(boundedPlanSource),
            targetedSearchRequests = emptyList(),
        )

        val result = service(FakeSearchExecutor()).verify(assistantMessage("answer"), plan)
        val bounded = report(result).sources.single()

        assertTrue(bounded.title.length <= EvidenceLimits.MAX_TITLE_LENGTH)
        assertTrue(bounded.source.length <= EvidenceLimits.MAX_SOURCE_NAME_LENGTH)
        assertTrue(bounded.url.length <= EvidenceLimits.MAX_URL_LENGTH)
        assertTrue(bounded.snippet.length <= EvidenceLimits.MAX_SNIPPET_LENGTH)
        assertTrue(bounded.publishedAt.orEmpty().length <= EvidenceLimits.MAX_PUBLISHED_AT_LENGTH)
        assertTrue(bounded.providerDisplayName.length <= EvidenceLimits.MAX_PROVIDER_DISPLAY_NAME_LENGTH)
        assertTrue(bounded.queryUsed.length <= EvidenceLimits.MAX_QUERY_USED_LENGTH)
    }

    @Test
    fun `total evidence source count is hard capped`() = runTest {
        val claims = (1..3).map { plannedClaim("Acme released ProductX$it in 2026.", "C$it") }
        val requests = claims.map { TargetedSearchRequest(it.candidate.id, it.candidate.text) }
        val executor = FakeSearchExecutor(
            results = (1..3).associate { claimIndex ->
                "C$claimIndex" to EvidenceSearchResult.Success(
                    (1..8).map { source("result $claimIndex-$it", "https://example.com/$claimIndex/$it") },
                )
            },
        )

        val result = service(executor).verify(assistantMessage("answer"), VerificationPlan(claims, emptyList(), requests))

        assertEquals(EvidenceVerificationLimits.MAX_TOTAL_EVIDENCE_SOURCES, report(result).sources.size)
        assertEquals(3, executor.calls)
    }

    @Test
    fun `existing and targeted duplicate URL becomes one final source`() = runTest {
        val sameUrl = "https://example.com/same"
        val plan = VerificationPlan(
            claims = listOf(
                plannedClaim("Acme released ProductX2 in 2026.").copy(existingSourceCandidateIds = listOf("P1")),
            ),
            existingSources = listOf(planningSource("existing", sameUrl)),
            targetedSearchRequests = listOf(TargetedSearchRequest("C1", "Acme ProductX2 2026")),
        )
        val executor = FakeSearchExecutor(source("Acme launches ProductX2 12GB in 2026.", sameUrl))

        val result = service(executor).verify(assistantMessage("answer"), plan)

        assertEquals(1, report(result).sources.size)
        assertEquals(listOf("E1"), report(result).claims.single().sourceIds)
    }

    @Test
    fun `search failure produces insufficient evidence and continues`() = runTest {
        val claims = listOf(
            plannedClaim("Acme released ProductX2 in 2026.", "C1"),
            plannedClaim("Beta released ProductX3 in 2026.", "C2"),
        )
        val requests = claims.map { TargetedSearchRequest(it.candidate.id, it.candidate.text) }
        val executor = FakeSearchExecutor(
            results = mapOf(
                "C1" to EvidenceSearchResult.Failed("provider unavailable"),
                "C2" to EvidenceSearchResult.Success(listOf(source("Beta launches ProductX3 in 2026.", "https://example.com/beta"))),
            ),
        )

        val result = service(executor).verify(assistantMessage("answer"), VerificationPlan(claims, emptyList(), requests))
        val report = report(result)

        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, report.claims[0].verdict)
        assertEquals("SEARCH_FAILED", report.claims[0].reasonCode)
        assertEquals(EvidenceVerdict.INSUFFICIENT_EVIDENCE, report.claims[1].verdict)
        assertEquals(2, executor.calls)
    }

    @Test
    fun `canonical checkable candidates outside the execution budget remain not checked`() = runTest {
        val answer = (1..10).joinToString("\n") { index ->
            "Company$index announced a 2026 release of product $index."
        }
        val plan = EvidenceVerificationPlanner.plan(answer)
        val result = report(service(FakeSearchExecutor()).verify(assistantMessage(answer), plan))

        assertEquals(10, result.claims.size)
        assertEquals((7..10).map { "C$it" }.toSet(),
            result.claims.filter { it.reasonCode == "CLAIM_BUDGET_EXHAUSTED" }.map { it.id }.toSet())
        assertTrue(result.claims.filter { it.reasonCode == "CLAIM_BUDGET_EXHAUSTED" }
            .all { it.verdict == EvidenceVerdict.NOT_CHECKED })
    }

    @Test
    fun `truncated candidate window remains explicit in completed verification`() = runTest {
        val answer = (1..100).joinToString("\n") { index ->
            "Company$index announced a 2026 release of product $index."
        }
        val plan = EvidenceVerificationPlanner.plan(answer)

        val completed = report(service(FakeSearchExecutor()).verify(assistantMessage(answer), plan))

        assertTrue(plan.candidateWindowTruncated)
        assertEquals(48, completed.claims.size)
        assertTrue(completed.candidateWindowTruncated)
    }

    @Test
    fun `claims without an executed targeted request are search budget not checked`() = runTest {
        val answer = (1..6).joinToString("\n") { "Acme released ProductX$it in 2026." }
        val plan = EvidenceVerificationPlanner.plan(answer)
        val executor = FakeSearchExecutor()
        val result = report(service(executor).verify(assistantMessage(answer), plan))

        assertEquals(3, executor.calls)
        assertEquals(setOf("C4", "C5", "C6"),
            result.claims.filter { it.reasonCode == "SEARCH_BUDGET_EXHAUSTED" }.map { it.id }.toSet())
        assertTrue(result.claims.filter { it.reasonCode == "SEARCH_BUDGET_EXHAUSTED" }
            .all { it.verdict == EvidenceVerdict.NOT_CHECKED })
    }

    @Test
    fun `conflict report includes reliable support and conflict source ids`() = runTest {
        val claim = "ProductX2 has 12GB."
        val plan = EvidenceVerificationPlanner.plan(
            claim,
            listOf(
                source("ProductX2 has 12GB.", "https://example.com/support"),
                source("ProductX2 has 8GB.", "https://example.com/conflict"),
            ),
        )

        val result = report(service(FakeSearchExecutor()).verify(assistantMessage(claim), plan))
        assertEquals(EvidenceVerdict.CONFLICTING_EVIDENCE, result.claims.single().verdict)
        assertEquals(listOf("E1", "E2"), result.claims.single().sourceIds)
    }

    @Test
    fun `cancellation propagates without complete partial report`() = runTest {
        val plan = VerificationPlan(
            claims = listOf(plannedClaim("Acme released ProductX2 in 2026.")),
            existingSources = emptyList(),
            targetedSearchRequests = listOf(TargetedSearchRequest("C1", "Acme ProductX2 2026")),
        )
        val job = launch {
            service(object : EvidenceSearchExecutor {
                override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
                    awaitCancellation()
                }
            }).verify(assistantMessage("answer"), plan)
        }

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    @Test
    fun `hanging executor is bounded by overall deadline`() = runTest {
        val plan = VerificationPlan(
            claims = listOf(plannedClaim("Acme released ProductX2 in 2026.")),
            existingSources = emptyList(),
            targetedSearchRequests = listOf(TargetedSearchRequest("C1", "Acme ProductX2 2026")),
        )

        val result = service(
            executor = object : EvidenceSearchExecutor {
                override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult = awaitCancellation()
            },
            deadlineMs = 50L,
        ).verify(assistantMessage("answer"), plan)

        assertEquals(EvidenceVerificationFailureReason.DEADLINE_EXCEEDED, (result as EvidenceVerificationResult.Failed).reason)
    }

    @Test
    fun `same fixed inputs produce identical codec-valid report`() = runTest {
        val message = assistantMessage("Acme released ProductX2 12GB in 2026.")
        val plan = EvidenceVerificationPlanner.plan(message.content)
        val source = source("Acme launches ProductX2 12GB in 2026.", "https://example.com/stable")
        val first = service(FakeSearchExecutor(source)).verify(message, plan)
        val second = service(FakeSearchExecutor(source)).verify(message, plan)

        assertEquals(first, second)
        val report = report(first)
        assertEquals(EvidenceFingerprint.canonicalAnswerHash(message.content), report.answerSha256)
        val encoded = EvidenceCodec.serialize(report)
        assertEquals(EvidenceCodecResult.Valid(report), EvidenceCodec.load(encoded))
    }

    @Test
    fun `invalid answer owner fails before search`() = runTest {
        val executor = FakeSearchExecutor()

        val result = service(executor).verify(
            MessageEntity(1L, 1L, "user", "answer", 1L),
            VerificationPlan(emptyList(), emptyList(), emptyList()),
        )

        assertEquals(EvidenceVerificationFailureReason.INVALID_MESSAGE, (result as EvidenceVerificationResult.Failed).reason)
        assertEquals(0, executor.calls)
    }

    private fun service(executor: EvidenceSearchExecutor, deadlineMs: Long = 30_000L): EvidenceVerificationService =
        EvidenceVerificationService(
            searchExecutor = executor,
            clock = EvidenceClock { 1234L },
            overallDeadlineMs = deadlineMs,
        )

    private fun report(result: EvidenceVerificationResult): EvidenceReport {
        assertTrue("expected complete report but was $result", result is EvidenceVerificationResult.Complete)
        return (result as EvidenceVerificationResult.Complete).report
    }
}

private class FakeSearchExecutor(
    private val defaultSource: WebSearchSource? = null,
    private val sources: List<WebSearchSource> = emptyList(),
    private val results: Map<String, EvidenceSearchResult> = emptyMap(),
) : EvidenceSearchExecutor {
    var calls: Int = 0
        private set

    override suspend fun search(request: TargetedSearchRequest): EvidenceSearchResult {
        calls++
        return results[request.claimId] ?: EvidenceSearchResult.Success(
            if (defaultSource != null) listOf(defaultSource) else sources,
        )
    }
}

private fun candidate(
    text: String,
    classification: ClaimClassification = ClaimClassification.CHECKABLE_FACT,
    priority: ClaimPriority = ClaimPriority.HIGH,
    id: String = "C1",
): EvidenceClaimCandidate = EvidenceClaimCandidate(
    id = id,
    text = text,
    ordinal = id.removePrefix("C").toInt(),
    classification = classification,
    priority = priority,
    reason = ClaimClassificationReason.FACTUAL_DECLARATIVE,
)

private fun plannedClaim(text: String, id: String = "C1"): PlannedClaim = PlannedClaim(
    candidate = candidate(text, id = id),
    existingSourceCandidateIds = emptyList(),
    needsTargetedSearch = true,
)

private fun assistantMessage(content: String): MessageEntity = MessageEntity(
    id = 1L,
    conversationId = 1L,
    role = "assistant",
    content = content,
    createdAt = 1L,
)

private fun source(title: String, url: String = "https://example.com/source"): WebSearchSource = WebSearchSource(
    title = title,
    source = "Example",
    url = url,
    snippet = title,
    publishedAt = null,
    providerType = WebSearchProviderType.FREE_DEFAULT,
    providerDisplayName = "Free Search",
    searchCategory = null,
    queryUsed = title,
    rank = 1,
)

private fun evidenceSource(title: String, url: String = "https://example.com/evidence"): EvidenceSource = EvidenceSource(
    id = "E1",
    title = title,
    source = "Example",
    url = url,
    snippet = title,
    publishedAt = null,
    providerType = WebSearchProviderType.FREE_DEFAULT.name,
    providerDisplayName = "Free Search",
    queryUsed = title,
)

private fun planningSource(title: String, url: String): EvidencePlanningSource = EvidencePlanningSource(
    id = "P1",
    title = title,
    source = "Example",
    url = url,
    snippet = title,
    publishedAt = null,
    providerType = WebSearchProviderType.FREE_DEFAULT,
    providerDisplayName = "Free Search",
    searchCategory = null,
    queryUsed = title,
    rank = 1,
    qualityHints = emptyList(),
)
