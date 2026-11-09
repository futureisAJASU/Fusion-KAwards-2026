package com.projectnuke.fusion.audit

import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.chat.ResearchRunPolicy
import com.projectnuke.fusion.data.ClaimClassification
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.EvidencePlanningLimits
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicClaimCoverageTest {

    @Test
    fun `legacy planner remains coarse while the new inventory exposes both clauses`() {
        val answer = "Acme released Alpha in 2024, and Beta released Gamma in 2025."
        val plannerFacts = com.projectnuke.fusion.data.EvidenceVerificationPlanner.plan(answer)
            .allCandidates.count { it.classification == ClaimClassification.CHECKABLE_FACT }
        val inventoryFacts = analyze(answer).detectedFactualCandidateCount

        assertEquals(1, plannerFacts)
        assertEquals(2, inventoryFacts)
    }

    @Test
    fun `Korean same sentence with two explicit clauses yields two exact candidates`() {
        val answer = "삼성전자는 2025년에 HBM4를 발표했다, 그리고 SK하이닉스는 2026년에 HBM4를 발표했다."

        val report = analyze(answer)

        assertEquals(2, report.detectedFactualCandidateCount)
        assertEquals(2, report.detectedFactualClaims.size)
        assertEquals("삼성전자는 2025년에 HBM4를 발표했다", report.detectedFactualClaims[0].text)
        assertEquals("SK하이닉스는 2026년에 HBM4를 발표했다.", report.detectedFactualClaims[1].text)
        assertEquals("그리고", report.detectedFactualClaims[1].connectorFromPrevious)
        assertEquals(AnswerCoverageState.UNKNOWN, report.state)
        assertFalse(report.authoritativeCoverage)
    }

    @Test
    fun `English same sentence with two explicit clauses yields two exact candidates`() {
        val answer = "Acme released Alpha in 2024, and Beta released Gamma in 2025."

        val report = analyze(answer)

        assertEquals(2, report.detectedFactualClaims.size)
        assertEquals("Acme released Alpha in 2024", report.detectedFactualClaims[0].text)
        assertEquals("Beta released Gamma in 2025.", report.detectedFactualClaims[1].text)
        assertEquals("and", report.detectedFactualClaims[1].connectorFromPrevious)
    }

    @Test
    fun `numbers units dates and comparisons remain in the original claim text`() {
        val answer = "Acme recorded 17.5% growth on 2025년 3월 2일, 2.5 points above 2024."

        val report = analyze(answer)

        assertEquals(listOf(answer), report.detectedFactualClaims.map { it.text })
        assertTrue(report.detectedFactualClaims.single().text.contains("17.5%"))
        assertTrue(report.detectedFactualClaims.single().text.contains("2025년 3월 2일"))
        assertTrue(report.detectedFactualClaims.single().text.contains("2.5 points above 2024"))
    }

    @Test
    fun `mixed fact and inference stays a gap instead of being promoted to complete`() {
        val answer = "삼성전자는 2025년에 HBM4를 발표했으며 매출은 10% 증가할 전망이다."

        val report = analyze(answer)

        assertEquals(1, report.candidates.size)
        assertEquals(AtomicClaimKind.MIXED_FACT_AND_INFERENCE, report.candidates.single().kind)
        assertTrue(report.omissions.any { it.reason == CoverageOmissionReason.MIXED_FACT_AND_INFERENCE })
        assertEquals(AnswerCoverageState.INCOMPLETE, report.state)
    }

    @Test
    fun `negation and causal relationship stay intact in one candidate`() {
        val answer = "Acme did not launch Alpha in 2024 because the safety review failed."

        val report = analyze(answer)

        assertEquals(listOf(answer), report.candidates.map { it.text })
        assertFalse(report.omissions.any { it.reason == CoverageOmissionReason.AMBIGUOUS_COORDINATION })
        assertEquals(AnswerCoverageState.UNKNOWN, report.state)
    }

    @Test
    fun `attached citations remain part of the exact source substring`() {
        val answer = "Acme released Alpha in 2024 [1](https://example.com/source)."

        val report = analyze(answer)

        assertTrue(report.detectedFactualClaims.single().text.contains("[1](https://example.com/source)"))
    }

    @Test
    fun `markdown list entries are candidates while table cells remain an explicit gap`() {
        val answer = "- Acme released Alpha in 2024.\n- Beta released Gamma in 2025.\n\n| Company | Revenue | Year |\n| --- | --- | --- |\n| Acme | 10 billion dollars | 2025 |"

        val report = analyze(answer)

        assertTrue(report.detectedFactualClaims.any { it.text == "Acme released Alpha in 2024." })
        assertTrue(report.detectedFactualClaims.any { it.text == "Beta released Gamma in 2025." })
        assertTrue(report.omissions.any { it.reason == CoverageOmissionReason.MARKDOWN_TABLE_NOT_DECOMPOSED })
        assertEquals(AnswerCoverageState.INCOMPLETE, report.state)
    }

    @Test
    fun `fenced code is excluded and reported as an uninspected region`() {
        val answer = "Acme released Alpha in 2024.\n```kotlin\nval revenue = 10\n```\nBeta released Gamma in 2025."

        val report = analyze(answer)

        assertEquals(2, report.detectedFactualClaims.size)
        assertFalse(report.candidates.any { it.text.contains("val revenue") })
        assertTrue(report.omissions.any { it.reason == CoverageOmissionReason.CODE_BLOCK_EXCLUDED })
        assertEquals(AnswerCoverageState.INCOMPLETE, report.state)
    }

    @Test
    fun `claim and audit budgets are distinct and late factual claims are identified`() {
        val answer = (1..5).joinToString("\n") { "Company$it released Product$it in 202$it." }

        val report = analyze(answer, maxClaimsToCheck = ResearchRunPolicy.MAX_AUDIT_CLAIMS)

        assertEquals(5, report.detectedFactualCandidateCount)
        assertEquals(ResearchRunPolicy.MAX_AUDIT_CLAIMS, report.selectedForAuditClaimIds.size)
        assertTrue(report.omissions.any {
            it.reason == CoverageOmissionReason.AUDIT_CLAIM_BUDGET_EXCEEDED && "AC5" in it.candidateIds
        })
        assertEquals(6, EvidencePlanningLimits.MAX_CLAIMS_TO_CHECK)
        assertEquals(48, EvidencePlanningLimits.MAX_CLAIM_CANDIDATES)
        assertEquals(4, ResearchRunPolicy.MAX_AUDIT_CLAIMS)
        assertEquals(AnswerCoverageState.INCOMPLETE, report.state)
    }

    @Test
    fun `candidate window and overlength omissions are separately reported`() {
        val crowded = (1..60).joinToString("\n") { "Company$it released Product$it in 202$it." }
        val crowdedReport = analyze(crowded)
        val overlengthReport = analyze("가".repeat(EvidencePlanningLimits.MAX_CLAIM_TEXT_LENGTH + 1))
        val inputLimitReport = analyze("Acme released Alpha in 2024.\n".repeat(
            AtomicClaimCoverageAnalyzer.MAX_ANSWER_CHARS / 24 + 2,
        ))

        assertTrue(crowdedReport.candidateWindowTruncated)
        assertTrue(crowdedReport.omissions.any { it.reason == CoverageOmissionReason.CANDIDATE_WINDOW_TRUNCATED })
        assertTrue(overlengthReport.overlengthSegmentsExcluded)
        assertTrue(overlengthReport.omissions.any { it.reason == CoverageOmissionReason.OVERLENGTH_SEGMENT_EXCLUDED })
        assertTrue(inputLimitReport.inputTruncated)
        assertTrue(inputLimitReport.omissions.any { it.reason == CoverageOmissionReason.ANSWER_INPUT_LIMIT })
        assertEquals(AnswerCoverageState.INCOMPLETE, crowdedReport.state)
        assertEquals(AnswerCoverageState.INCOMPLETE, overlengthReport.state)
        assertEquals(AnswerCoverageState.INCOMPLETE, inputLimitReport.state)
    }

    @Test
    fun `ordinary heuristic success remains unknown and cannot authorize P0-01 final`() {
        val answer = "Acme released Alpha in 2024."
        val provisional = analyze(answer)
        val audit = supportedAudit(provisional.detectedFactualClaims.map { it.id })
        val report = analyze(answer, audit = audit)

        assertEquals(AnswerCoverageState.UNKNOWN, report.state)
        assertFalse(report.authoritativeCoverage)
        val decision = ResearchVerificationDecisionPolicy.decide(report.toDecisionInput(answer, audit))
        assertEquals(ResearchVerificationAction.ABSTAIN, decision.action)
        assertEquals(ResearchVerificationReason.UNCERTAIN_CLAIM_EXTRACTION, decision.reason)
    }

    @Test
    fun `matching fingerprint and caller supplied authority label still cannot authorize final`() {
        val answer = "Acme released Alpha in 2024."
        val provisional = analyze(answer)
        val manifest = UntrustedAnswerClaimManifest(
            answerFingerprintSha256 = provisional.answerFingerprintSha256,
            claimedAuthorityId = "trusted-independent-enumerator",
            factualClaims = provisional.detectedFactualClaims.map { UntrustedManifestClaim(it.id, it.text) },
        )
        val report = provisional.rejectUntrustedManifest(answer, manifest, ResearchRunPolicy.MAX_AUDIT_CLAIMS)
        val audit = supportedAudit(report.detectedFactualClaims.map { it.id })
        val reportWithAudit = analyze(answer, audit = audit)
            .rejectUntrustedManifest(answer, manifest, ResearchRunPolicy.MAX_AUDIT_CLAIMS)

        assertEquals(AnswerCoverageState.INCOMPLETE, report.state)
        assertFalse(report.authoritativeCoverage)
        val decision = ResearchVerificationDecisionPolicy.decide(reportWithAudit.toDecisionInput(answer, audit))
        assertEquals(ResearchVerificationAction.ABSTAIN, decision.action)
        assertEquals(ResearchVerificationReason.UNCERTAIN_CLAIM_EXTRACTION, decision.reason)
    }

    @Test
    fun `a caller supplied authority label cannot certify an answer that omits a factual claim`() {
        val answer = "Acme released Alpha in 2024.\nBeta released Gamma in 2025."
        val provisional = analyze(answer)
        val incompleteManifest = UntrustedAnswerClaimManifest(
            answerFingerprintSha256 = provisional.answerFingerprintSha256,
            claimedAuthorityId = "trusted-independent-enumerator",
            factualClaims = listOf(
                UntrustedManifestClaim("AC1", provisional.detectedFactualClaims.first().text),
            ),
        )

        val report = provisional.rejectUntrustedManifest(answer, incompleteManifest, 4)

        assertEquals(AnswerCoverageState.INCOMPLETE, report.state)
        assertFalse(report.authoritativeCoverage)
        assertTrue(report.omissions.any { it.reason == CoverageOmissionReason.UNAUTHENTICATED_MANIFEST })
    }

    @Test
    fun `caller manifest cannot replace bundled heuristic inventory without an authenticated authority`() {
        val answer = "Acme announced 2024 results, revenue was 10B."
        val heuristic = analyze(answer)
        val manifest = UntrustedAnswerClaimManifest(
            answerFingerprintSha256 = heuristic.answerFingerprintSha256,
            claimedAuthorityId = "trusted-independent-enumerator",
            factualClaims = listOf(
                UntrustedManifestClaim("M1", "Acme announced 2024 results"),
                UntrustedManifestClaim("M2", "revenue was 10B."),
            ),
        )

        val rejected = heuristic.rejectUntrustedManifest(answer, manifest, 4)

        assertEquals(AnswerCoverageState.INCOMPLETE, rejected.state)
        assertEquals(1, rejected.detectedFactualCandidateCount)
        assertEquals(heuristic.detectedFactualClaims.map { it.id }.toSet(), rejected.toDecisionInput(answer, null).factualClaimIds)
        assertFalse(rejected.authoritativeCoverage)
        assertTrue(rejected.omissions.any { it.reason == CoverageOmissionReason.UNAUTHENTICATED_MANIFEST })
    }

    @Test
    fun `stale answer fingerprint prevents reuse of coverage and audit snapshot`() {
        val answer = "Acme released Alpha in 2024."
        val report = analyze(answer)
        val audit = supportedAudit(report.detectedFactualClaims.map { it.id })
        val analyzedWithAudit = analyze(answer, audit = audit)
        val changedAnswer = "$answer Beta released Gamma in 2025."

        val input = analyzedWithAudit.toDecisionInput(changedAnswer, audit)

        assertFalse(input.authoritativeCoverage)
        assertTrue(input.candidateWindowTruncated)
        val decision = ResearchVerificationDecisionPolicy.decide(input)
        assertEquals(ResearchVerificationAction.ABSTAIN, decision.action)
        assertEquals(ResearchVerificationReason.UNCERTAIN_CLAIM_EXTRACTION, decision.reason)
        assertNotEquals(report.answerFingerprintSha256, EvidenceFingerprint.canonicalAnswerHash(changedAnswer))

        val staleManifest = UntrustedAnswerClaimManifest(
            answerFingerprintSha256 = report.answerFingerprintSha256,
            claimedAuthorityId = "trusted-independent-enumerator",
            factualClaims = report.detectedFactualClaims.map { UntrustedManifestClaim(it.id, it.text) },
        )
        val staleReport = report.rejectUntrustedManifest(changedAnswer, staleManifest, 4)
        assertEquals(AnswerCoverageState.INCOMPLETE, staleReport.state)
        assertTrue(staleReport.candidateWindowTruncated)
        assertFalse(staleReport.authoritativeCoverage)
    }

    @Test
    fun `missing extra duplicated and stale manifest claims cannot certify coverage`() {
        val answer = "Acme released Alpha in 2024.\nBeta released Gamma in 2025."
        val report = analyze(answer)
        val factual = report.detectedFactualClaims
        val missing = UntrustedAnswerClaimManifest(
            answerFingerprintSha256 = report.answerFingerprintSha256,
            claimedAuthorityId = "trusted-independent-enumerator",
            factualClaims = listOf(UntrustedManifestClaim(factual[0].id, factual[0].text)),
        )
        val extra = UntrustedAnswerClaimManifest(
            answerFingerprintSha256 = report.answerFingerprintSha256,
            claimedAuthorityId = "trusted-independent-enumerator",
            factualClaims = factual.map { UntrustedManifestClaim(it.id, it.text) } +
                UntrustedManifestClaim("EXTRA", "Alpha"),
        )
        val duplicatedText = UntrustedAnswerClaimManifest(
            answerFingerprintSha256 = report.answerFingerprintSha256,
            claimedAuthorityId = "trusted-independent-enumerator",
            factualClaims = listOf(
                UntrustedManifestClaim(factual[0].id, factual[0].text),
                UntrustedManifestClaim("DUPLICATE", factual[0].text),
                UntrustedManifestClaim(factual[1].id, factual[1].text),
            ),
        )
        val duplicatedId = UntrustedAnswerClaimManifest(
            answerFingerprintSha256 = report.answerFingerprintSha256,
            claimedAuthorityId = "trusted-independent-enumerator",
            factualClaims = listOf(
                UntrustedManifestClaim(factual[0].id, factual[0].text),
                UntrustedManifestClaim(factual[0].id, factual[0].text),
            ),
        )
        val staleFingerprint = UntrustedAnswerClaimManifest(
            answerFingerprintSha256 = EvidenceFingerprint.canonicalAnswerHash("different answer"),
            claimedAuthorityId = "trusted-independent-enumerator",
            factualClaims = factual.map { UntrustedManifestClaim(it.id, it.text) },
        )

        listOf(missing, extra, duplicatedText, duplicatedId, staleFingerprint).forEach { manifest ->
            val rejected = report.rejectUntrustedManifest(answer, manifest, 4)
            assertEquals(AnswerCoverageState.INCOMPLETE, rejected.state)
            assertFalse(rejected.authoritativeCoverage)
            assertTrue(rejected.omissions.any { it.reason == CoverageOmissionReason.UNAUTHENTICATED_MANIFEST })
        }
        assertTrue(analyze(answer, maxClaimsToCheck = 1)
            .rejectUntrustedManifest(answer, missing, 1).omissions.any {
            it.reason == CoverageOmissionReason.AUDIT_CLAIM_BUDGET_EXCEEDED
        })
    }

    @Test
    fun `actual checked count excludes unavailable and partially timed out claims`() = runTest {
        val answer = "Acme released Alpha in 2024.\nBeta released Gamma in 2025."
        val base = analyze(answer)
        val ids = base.detectedFactualClaims.map { it.id }
        val sources = listOf(
            EvidenceSource("E1", "First", "Web", "https://one.test", "First source", null, "WEB", "Web", ""),
            EvidenceSource("E2", "Second", "Web", "https://two.test", "Second source", null, "WEB", "Web", ""),
        )
        val inputs = listOf(
            VerifierClaimInput(ids[0], base.detectedFactualClaims[0].text, sources.take(1)),
            VerifierClaimInput(ids[1], base.detectedFactualClaims[1].text, sources),
        )
        val origin = boundOrigin(answer, inputs, requestId = "partial-run")
        val outputs = ArrayDeque(
            listOf(
                VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED),
                VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED),
                VerifierBackendResult.Failure(AuditBackendFailureReason.TIMEOUT),
            ),
        )
        val partial = AnswerAuditExecutionOrchestrator(testBackend { outputs.removeFirst() })
            .verifyClaims(inputs, origin)
        val report = analyze(answer, audit = partial, expectedExecutionOrigin = origin)

        assertEquals(setOf(ids[0]), report.checkedClaimIds)
        assertEquals(setOf(ids[0], ids[1]), report.submittedClaimIds)
        assertEquals(1, report.actualCheckedClaimCount)
    }

    @Test
    fun `selected unavailable claim is not counted as submitted or checked`() {
        val answer = "Acme released Alpha in 2024."
        val claimId = analyze(answer).detectedFactualClaims.single().id
        val unavailable = ClaimSemanticVerification(
            claimId = claimId,
            backend = VerifierBackendDescriptor(
                id = "unavailable",
                version = "1",
                supportedLanguages = emptySet(),
                experimental = false,
                availability = VerifierBackendAvailability.UNAVAILABLE,
            ),
            aggregateStatus = AuditAggregateStatus.NOT_VERIFIED,
            pairExecutions = listOf(
                VerifierPairExecution("E1", VerifierBackendResult.Unavailable, submitted = false),
            ),
            expectedEvidenceIdentities = listOf("E1"),
        )

        val report = analyze(answer, audit = AnswerAuditExecutionSnapshot(listOf(unavailable)))

        assertEquals(setOf(claimId), report.selectedForAuditClaimIds)
        assertTrue(report.submittedClaimIds.isEmpty())
        assertTrue(report.checkedClaimIds.isEmpty())
        assertEquals(0, report.actualCheckedClaimCount)
    }

    @Test
    fun `all returned valid pairs do not complete a claim when a planned pair is missing`() {
        val answer = "Acme released Alpha in 2024."
        val selectedSources = listOf(
            EvidenceSource("E1", "First", "Web", "https://one.test", "First source", null, "WEB", "Web", "query"),
            EvidenceSource("E2", "Second", "Web", "https://two.test", "Second source", null, "WEB", "Web", "query"),
        )
        val expectedPairs = VerifierCandidateAdapter.requestsForSelectedCandidates(
            claimText = answer,
            selectedCandidates = selectedSources,
        )
        assertEquals(2, expectedPairs.size)

        val claimId = analyze(answer).detectedFactualClaims.single().id
        // Model only the first returned pair. Its label is valid, but the second
        // pair from the immutable selected-source plan was never returned.
        val partial = AnswerAuditExecutionSnapshot(
            claims = listOf(semanticClaim(
                id = claimId,
                expectedEvidenceIdentities = expectedPairs.map { it.evidenceIdentity },
                results = listOf(VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)),
            )),
        )

        val report = analyze(answer, audit = partial)

        assertTrue(partial.claims.single().pairExecutions.all { it.result is VerifierBackendResult.Verified })
        assertEquals(0, report.actualCheckedClaimCount)
        assertTrue(report.checkedClaimIds.isEmpty())
    }

    @Test
    fun `same answer and claim label cannot reuse an audit planned for different evidence`() {
        val answer = "Acme released Alpha in 2024."
        val claimId = analyze(answer).detectedFactualClaims.single().id
        val firstEvidenceId = "E1-content-a"
        val secondEvidenceId = "E1-content-b"
        val firstAudit = AnswerAuditExecutionSnapshot(
            listOf(semanticClaim(
                claimId,
                listOf(firstEvidenceId),
                listOf(VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)),
            )),
        )
        val secondAudit = AnswerAuditExecutionSnapshot(
            listOf(semanticClaim(
                claimId,
                listOf(secondEvidenceId),
                listOf(VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)),
            )),
        )

        val staleInput = analyze(answer, audit = firstAudit).toDecisionInput(answer, secondAudit)

        assertTrue(staleInput.candidateWindowTruncated)
        assertEquals(
            ResearchVerificationReason.UNCERTAIN_CLAIM_EXTRACTION,
            ResearchVerificationDecisionPolicy.decide(staleInput).reason,
        )
    }

    @Test
    fun `audit executed for Alpha cannot be rebound to Beta with the same AC1`() = runTest {
        val alpha = "Acme released Alpha in 2024."
        val beta = "Acme released Beta in 2024."
        val source = EvidenceSource("E1", "Release", "Local", "", "Release record", null, "LOCAL", "Local", "")
        val alphaInput = VerifierClaimInput("AC1", alpha, listOf(source))
        val alphaOrigin = boundOrigin(alpha, listOf(alphaInput), requestId = "draft-alpha")
        val auditFromAlpha = AnswerAuditExecutionOrchestrator(testBackend {
            VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
        }).verifyClaims(listOf(alphaInput), alphaOrigin)

        val rebound = analyze(beta, audit = auditFromAlpha, expectedExecutionOrigin = alphaOrigin)
        val missingOrigin = analyze(alpha, audit = auditFromAlpha)
        val valid = analyze(alpha, audit = auditFromAlpha, expectedExecutionOrigin = alphaOrigin)

        assertEquals("AC1", analyze(alpha).detectedFactualClaims.single().id)
        assertEquals("AC1", analyze(beta).detectedFactualClaims.single().id)
        assertEquals(0, rebound.actualCheckedClaimCount)
        assertEquals(0, missingOrigin.actualCheckedClaimCount)
        assertEquals(1, valid.actualCheckedClaimCount)
    }

    @Test
    fun `same answer requires the same request version and source origin`() = runTest {
        val answer = "Acme released Alpha in 2024."
        val candidate = analyze(answer).detectedFactualClaims.single()
        val sourceA = EvidenceSource("E1", "Release", "Local", "", "source A", null, "LOCAL", "Local", "")
        val sourceB = sourceA.copy(id = "E1-content-B", snippet = "source B")
        val inputA = VerifierClaimInput(candidate.id, candidate.text, listOf(sourceA))
        val inputB = VerifierClaimInput(candidate.id, candidate.text, listOf(sourceB))
        val originA = boundOrigin(answer, listOf(inputA), requestId = "request-A", targetAssistantId = 44L)
        val otherRequestOrigin = boundOrigin(answer, listOf(inputA), requestId = "request-B", targetAssistantId = 44L)
        val otherVersionOrigin = boundOrigin(answer, listOf(inputA), requestId = "request-A", targetAssistantId = 45L)
        val otherSourceOrigin = boundOrigin(answer, listOf(inputB), requestId = "request-A", targetAssistantId = 44L)
        val auditA = AnswerAuditExecutionOrchestrator(testBackend {
            VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
        }).verifyClaims(listOf(inputA), originA)

        assertEquals(0, analyze(answer, audit = auditA, expectedExecutionOrigin = otherRequestOrigin).actualCheckedClaimCount)
        assertEquals(0, analyze(answer, audit = auditA, expectedExecutionOrigin = otherVersionOrigin).actualCheckedClaimCount)
        assertEquals(0, analyze(answer, audit = auditA, expectedExecutionOrigin = otherSourceOrigin).actualCheckedClaimCount)
        assertTrue(analyze(answer, audit = auditA, expectedExecutionOrigin = originA).actualCheckedClaimCount == 1)
        assertEquals(originA.canonicalDraftFingerprint, otherRequestOrigin.canonicalDraftFingerprint)
        assertEquals(originA.canonicalDraftFingerprint, otherVersionOrigin.canonicalDraftFingerprint)
        assertEquals(originA.canonicalDraftFingerprint, otherSourceOrigin.canonicalDraftFingerprint)
        assertNotEquals(originA.generationRequestId, otherRequestOrigin.generationRequestId)
        assertNotEquals(originA.responseVersionTargetAssistantMessageId, otherVersionOrigin.responseVersionTargetAssistantMessageId)
        assertNotEquals(originA.responseVersionGroupId, otherVersionOrigin.responseVersionGroupId)
        assertNotEquals(originA.plannedClaims, otherSourceOrigin.plannedClaims)
    }

    @Test
    fun `executor rejects a forged origin that does not match the submitted claim plan`() = runTest {
        val alpha = "Acme released Alpha in 2024."
        val beta = "Acme released Beta in 2024."
        val source = EvidenceSource("E1", "Release", "Local", "", "source", null, "LOCAL", "Local", "")
        val alphaInput = VerifierClaimInput("AC1", alpha, listOf(source))
        val betaInput = VerifierClaimInput("AC1", beta, listOf(source))
        val originA = boundOrigin(alpha, listOf(alphaInput), requestId = "request-A")
        var calls = 0
        val executor = AnswerAuditExecutionOrchestrator(testBackend {
            calls++
            VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
        })

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { executor.verifyClaims(listOf(betaInput), originA) }
        }
        val legacyForgedSnapshot = AnswerAuditExecutionSnapshot(
            listOf(semanticClaim("AC1", listOf("draft-forged"), listOf(VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)))),
        )
        assertEquals(0, analyze(alpha, audit = legacyForgedSnapshot, expectedExecutionOrigin = originA).actualCheckedClaimCount)
        assertEquals(0, calls)
    }

    @Test
    fun `insufficient NLI output is an executed relation not factual contradiction or support`() = runTest {
        val answer = "Acme released Alpha in 2024."
        val provisional = analyze(answer)
        val id = provisional.detectedFactualClaims.single().id
        val input = VerifierClaimInput(
            id,
            provisional.detectedFactualClaims.single().text,
            listOf(EvidenceSource("E1", "Release", "Local", "", "source", null, "LOCAL", "Local", "")),
        )
        val origin = boundOrigin(answer, listOf(input), requestId = "insufficient")
        val audit = AnswerAuditExecutionOrchestrator(testBackend {
            VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT)
        }).verifyClaims(listOf(input), origin)
        val report = analyze(answer, audit = audit, expectedExecutionOrigin = origin)
        val decision = ResearchVerificationDecisionPolicy.decide(
            report.toDecisionInput(answer, audit, expectedExecutionOrigin = origin),
        )

        assertEquals(1, report.actualCheckedClaimCount)
        assertEquals(AuditAggregateStatus.INSUFFICIENT, audit.claims.single().aggregateStatus)
        assertFalse(report.authoritativeCoverage)
        assertEquals(ResearchVerificationAction.ABSTAIN, decision.action)
        assertEquals(ResearchVerificationReason.SEARCH_NOT_AUTHORIZED, decision.reason)
    }

    private fun analyze(
        answer: String,
        maxClaimsToCheck: Int = ResearchRunPolicy.MAX_AUDIT_CLAIMS,
        audit: AnswerAuditExecutionSnapshot? = null,
        expectedExecutionOrigin: AnswerAuditExecutionOrigin? = null,
    ) = AtomicClaimCoverageAnalyzer.analyze(answer, maxClaimsToCheck, audit, expectedExecutionOrigin)

    private fun supportedAudit(ids: List<String>) = AnswerAuditExecutionSnapshot(
        claims = ids.map { id ->
            semanticClaim(id, listOf("E1"), listOf(VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)))
        },
    )

    private fun semanticClaim(
        id: String,
        expectedEvidenceIdentities: List<String>,
        results: List<VerifierBackendResult>,
    ) = ClaimSemanticVerification(
        claimId = id,
        backend = VerifierBackendDescriptor(
            id = "test-backend",
            version = "1",
            supportedLanguages = setOf("en", "ko"),
            experimental = false,
            availability = VerifierBackendAvailability.AVAILABLE,
        ),
        aggregateStatus = aggregateVerificationResults(results),
        expectedEvidenceIdentities = expectedEvidenceIdentities,
        pairExecutions = results.mapIndexed { index, result ->
            VerifierPairExecution(evidenceIdentity = expectedEvidenceIdentities[index], result = result)
        },
    )

    private fun boundOrigin(
        answer: String,
        inputs: List<VerifierClaimInput>,
        requestId: String,
        targetAssistantId: Long? = null,
    ): AnswerAuditExecutionOrigin {
        val inventory = analyze(answer)
        return AnswerAuditExecutionOrigin(
            conversationId = 71L,
            generationRequestId = requestId,
            responseVersionTargetAssistantMessageId = targetAssistantId,
            responseVersionGroupId = targetAssistantId?.plus(100L),
            canonicalDraftFingerprint = EvidenceFingerprint.canonicalAnswerHash(answer),
            claimIdentities = inventory.detectedFactualClaims.map { it.id to testSha256(it.text) },
            plannedClaims = inputs.map { input ->
                AuditExecutionClaimPlan(
                    input.claimId,
                    testSha256(input.claimText),
                    VerifierCandidateAdapter.requestsForSelectedCandidates(input.claimText, input.selectedCandidates)
                        .map(VerificationPairRequest::evidenceIdentity),
                )
            },
        )
    }

    private fun testBackend(verify: suspend (VerificationPairRequest) -> VerifierBackendResult) = object : VerifierBackend {
        override val descriptor = VerifierBackendDescriptor("test-backend", "1", setOf("en"), false)
        override suspend fun verify(request: VerificationPairRequest) = verify(request)
    }

    private fun testSha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
