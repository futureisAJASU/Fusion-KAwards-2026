package com.projectnuke.fusion.audit

import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.privacy.ResearchInterventionSearchPermit
import com.projectnuke.fusion.privacy.ResearchInterventionSearchQuery
import com.projectnuke.fusion.retrieval.LocalDocumentChunk
import com.projectnuke.fusion.retrieval.LocalDocumentSource
import com.projectnuke.fusion.retrieval.LocalRagSelection
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.search.appendSearchSourcesMetadata
import com.projectnuke.fusion.ui.ResearchDraftShadowOutcome
import com.projectnuke.fusion.ui.ResearchDraftShadowOwner
import com.projectnuke.fusion.ui.ResearchInterventionSearchFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchInterventionOrchestratorTest {
    @Test
    fun `supported only findings remain claim scoped and do not trigger intervention`() = runTest {
        var revisions = 0
        var retrievals = 0
        val outcome = orchestrator().run(
            input(
                answer = "Acme released Alpha in 2024.",
                backend = testBackend { VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) },
                generator = ResearchRevisionGenerator {
                    revisions++
                    ResearchRevisionResult.Revised("unexpected correction")
                },
                authorizer = ResearchInterventionSearchAuthorizer { error("supported claim must not search") },
                retriever = ResearchInterventionRetriever { _, _, _ ->
                    retrievals++
                    ResearchInterventionRetrievalResult.Empty
                },
            ),
        )

        assertTrue(outcome is ResearchInterventionOutcome.Delivered)
        val delivered = outcome as ResearchInterventionOutcome.Delivered
        assertEquals(ResearchInterventionAction.DELIVER_WITH_SCOPED_AUDIT, delivered.decision.action)
        assertFalse(delivered.decision.wholeAnswerCertified)
        assertFalse(delivered.audit.wholeAnswerCertified)
        assertEquals(0, revisions)
        assertEquals(0, retrievals)
    }

    @Test
    fun `contradiction is revised once and re-verified before delivery`() = runTest {
        val backend = testBackend { pair ->
            if (pair.claimText.contains("2025")) {
                VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
            } else {
                VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED)
            }
        }
        val orchestrator = orchestrator()
        val input = input(
            answer = "Acme released Alpha in 2024.",
            backend = backend,
            generator = ResearchRevisionGenerator {
                ResearchRevisionResult.Revised("Acme released Alpha in 2025.")
            },
        )

        val outcome = orchestrator.run(input)

        assertTrue(outcome is ResearchInterventionOutcome.Delivered)
        val delivered = outcome as ResearchInterventionOutcome.Delivered
        assertEquals("Acme released Alpha in 2025.", delivered.visibleAnswer)
        assertTrue(delivered.revisionAttempted)
        assertTrue(delivered.revised)
        assertTrue(delivered.audit.revisionAttempted)
        assertEquals(ResearchInterventionAction.DELIVER_WITH_SCOPED_AUDIT, delivered.decision.action)
        assertFalse(delivered.decision.wholeAnswerCertified)
        assertFalse(delivered.audit.wholeAnswerCertified)
        assertEquals(ResearchInterventionFindingSubject.FINAL_USER_VISIBLE_ANSWER, delivered.audit.findingSubject)
        assertEquals(ResearchInterventionDraftStage.REVISED, delivered.audit.draftStage)
        assertNotEquals(
            delivered.audit.originalDraftFingerprint,
            delivered.audit.finalDraftFingerprint,
        )
    }

    @Test
    fun `revision budget allows exactly one generator call then abstains`() = runTest {
        val backend = testBackend { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) }
        var calls = 0
        val input = input(
            answer = "Acme released Alpha in 2024.",
            backend = backend,
            generator = ResearchRevisionGenerator {
                calls++
                ResearchRevisionResult.Revised("Acme released Alpha in 2025.")
            },
        )

        val outcome = orchestrator().run(input)

        assertTrue(outcome is ResearchInterventionOutcome.Abstained)
        val abstained = outcome as ResearchInterventionOutcome.Abstained
        assertEquals(ResearchInterventionReason.REVISION_BUDGET_EXHAUSTED, abstained.decision.reason)
        assertEquals(1, calls)
        assertFalse(abstained.audit.wholeAnswerCertified)
        assertEquals(ResearchInterventionFindingSubject.PRE_PERSISTENCE_DRAFT, abstained.audit.findingSubject)
        assertEquals(ResearchInterventionDraftStage.REVISED, abstained.audit.draftStage)
    }

    @Test
    fun `conflicting sources abstain instead of choosing a side for revision`() = runTest {
        var revisions = 0
        var retrievals = 0
        val input = input(
            answer = "Acme released Alpha in 2024.",
            webSources = listOf(
                freshSource(title = "positive source", snippet = "positive source says Acme Alpha 2024"),
                freshSource(title = "negative source", snippet = "negative source disputes Acme Alpha 2024"),
            ),
            backend = testBackend { pair ->
                when {
                    pair.evidenceText.contains("positive source says") ->
                        VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
                    pair.evidenceText.contains("negative source disputes") ->
                        VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED)
                    else -> VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT)
                }
            },
            generator = ResearchRevisionGenerator {
                revisions++
                ResearchRevisionResult.Revised("Acme released Alpha in 2025.")
            },
            authorizer = ResearchInterventionSearchAuthorizer { context ->
                ResearchInterventionSearchPermit.grant(
                    context.conversationId,
                    context.requestId,
                    context.draftFingerprint,
                    context.queries,
                    explicitConsent = true,
                )
            },
            retriever = ResearchInterventionRetriever { _, _, _ ->
                retrievals++
                ResearchInterventionRetrievalResult.Success(listOf(freshSource()))
            },
        )

        val outcome = orchestrator().run(input)

        assertTrue(outcome is ResearchInterventionOutcome.Abstained)
        val abstained = outcome as ResearchInterventionOutcome.Abstained
        assertEquals(ResearchInterventionReason.CONFLICTING_EVIDENCE, abstained.decision.reason)
        assertTrue(abstained.audit.claims.any { it.status == ResearchDraftClaimFindingStatus.CONFLICTING })
        assertEquals(0, revisions)
        assertEquals(0, retrievals)
    }

    @Test
    fun `conflict revision instruction tells the model to disclose disagreement`() {
        val instruction = ResearchRevisionInstructionBuilder.build(
            visibleAnswer = "Acme released Alpha in 2024.",
            finding = ResearchDraftClaimFinding(
                claimId = "AC1",
                claimText = "Acme released Alpha in 2024.",
                claimTextSha256 = "a".repeat(64),
                status = ResearchDraftClaimFindingStatus.CONFLICTING,
                evidence = emptyList(),
            ),
            maxEvidenceExcerpts = 2,
            maxEvidenceChars = 500,
            maxInstructionChars = 4_000,
        )

        assertTrue(instruction.contains("어느 한쪽을 사실로 선택하거나"))
        assertTrue(instruction.contains("출처 간 불일치를 밝혀 주세요"))
        assertFalse(instruction.contains("그 주장만 근거에 맞게 고치고"))
    }

    @Test
    fun `conflict takes precedence over a separate contradicted claim`() = runTest {
        var revisions = 0
        val input = input(
            answer = "Acme released Alpha in 2024. Beta is based in Seoul.",
            webSources = listOf(
                freshSource(title = "positive source", snippet = "positive source Acme Alpha 2024"),
                freshSource(title = "negative source", snippet = "negative source disputes Acme Alpha 2024"),
                freshSource(title = "Beta source", snippet = "Beta is based in Busan"),
            ),
            backend = testBackend { pair ->
                when {
                    pair.claimText.contains("Alpha") && pair.evidenceText.contains("positive source Acme Alpha") ->
                        VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
                    pair.claimText.contains("Alpha") && pair.evidenceText.contains("negative source disputes") ->
                        VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED)
                    pair.claimText.contains("Beta") && pair.evidenceText.contains("Busan") ->
                        VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED)
                    else -> VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT)
                }
            },
            generator = ResearchRevisionGenerator {
                revisions++
                ResearchRevisionResult.Revised("revised")
            },
        )

        val outcome = orchestrator().run(input)

        assertTrue(outcome is ResearchInterventionOutcome.Abstained)
        assertEquals(
            ResearchInterventionReason.CONFLICTING_EVIDENCE,
            (outcome as ResearchInterventionOutcome.Abstained).decision.reason,
        )
        assertEquals(0, revisions)
    }

    @Test
    fun `authorized re-retrieval that yields conflicting evidence still abstains`() = runTest {
        var retrievals = 0
        val input = input(
            answer = "Acme released Alpha in 2024.",
            backend = testBackend { pair ->
                when {
                    pair.evidenceText.contains("positive source Acme Alpha") ->
                        VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
                    pair.evidenceText.contains("negative source disputes") ->
                        VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED)
                    else -> VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT)
                }
            },
            authorizer = ResearchInterventionSearchAuthorizer { context ->
                ResearchInterventionSearchPermit.grant(
                    context.conversationId,
                    context.requestId,
                    context.draftFingerprint,
                    context.queries,
                    explicitConsent = true,
                )
            },
            retriever = ResearchInterventionRetriever { _, _, _ ->
                retrievals++
                ResearchInterventionRetrievalResult.Success(
                    listOf(
                        freshSource(title = "positive source", snippet = "positive source Acme Alpha"),
                        freshSource(title = "negative source", snippet = "negative source disputes Acme Alpha"),
                    ),
                )
            },
        )

        val outcome = orchestrator().run(input)

        assertTrue(outcome is ResearchInterventionOutcome.Abstained)
        assertEquals(
            ResearchInterventionReason.CONFLICTING_EVIDENCE,
            (outcome as ResearchInterventionOutcome.Abstained).decision.reason,
        )
        assertTrue(outcome.audit.claims.any { it.status == ResearchDraftClaimFindingStatus.CONFLICTING })
        assertEquals(1, retrievals)
    }

    @Test
    fun `conflict after a completed revision is reported without another correction`() = runTest {
        var revisions = 0
        val input = input(
            answer = "Acme released Alpha in 2024.",
            webSources = listOf(
                freshSource(title = "positive source", snippet = "positive source Acme Alpha"),
                freshSource(title = "negative source", snippet = "negative source disputes Acme Alpha"),
            ),
            backend = testBackend { pair ->
                if (pair.claimText.contains("2024")) {
                    VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED)
                } else if (pair.evidenceText.contains("positive source Acme Alpha")) {
                    VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
                } else {
                    VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED)
                }
            },
            generator = ResearchRevisionGenerator {
                revisions++
                ResearchRevisionResult.Revised("Acme released Alpha in 2025.")
            },
        )

        val outcome = orchestrator().run(input)

        assertTrue(outcome is ResearchInterventionOutcome.Abstained)
        assertEquals(
            ResearchInterventionReason.CONFLICTING_EVIDENCE,
            (outcome as ResearchInterventionOutcome.Abstained).decision.reason,
        )
        assertEquals(1, revisions)
    }

    @Test
    fun `failed or unchanged revision abstains without delivering unverified text`() = runTest {
        val backend = testBackend { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) }
        for (result in listOf(
            ResearchRevisionResult.Unavailable,
            ResearchRevisionResult.Failed("bad output"),
            ResearchRevisionResult.Revised("Acme released Alpha in 2024."),
        )) {
            val outcome = orchestrator().run(
                input(
                    answer = "Acme released Alpha in 2024.",
                    backend = backend,
                    generator = ResearchRevisionGenerator { result },
                ),
            )
            assertTrue(outcome is ResearchInterventionOutcome.Abstained)
            assertEquals(
                ResearchInterventionReason.REVISION_FAILED,
                (outcome as ResearchInterventionOutcome.Abstained).decision.reason,
            )
        }
    }

    @Test
    fun `insufficient evidence without exact authorization never calls retrieval`() = runTest {
        var retrievals = 0
        val input = input(
            answer = "Acme released Alpha in 2024.",
            backend = testBackend { VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT) },
            retriever = ResearchInterventionRetriever { _, _, _ ->
                retrievals++
                ResearchInterventionRetrievalResult.Success(listOf(freshSource()))
            },
        )

        val outcome = orchestrator().run(input)

        assertTrue(outcome is ResearchInterventionOutcome.Abstained)
        assertEquals(
            ResearchInterventionReason.SEARCH_NOT_AUTHORIZED,
            (outcome as ResearchInterventionOutcome.Abstained).decision.reason,
        )
        assertEquals(0, retrievals)
    }

    @Test
    fun `prohibited ticker expansion falls back to explicit search authorization abstention`() = runTest {
        val request = request().copy(
            promptText = "Samsung Electronics stock",
            rawUserText = "Samsung Electronics stock",
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
        )
        var retrievals = 0
        val outcome = orchestrator().run(
            input(
                answer = "Samsung Electronics (005930) stock rose in 2025.",
                backend = testBackend {
                    VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT)
                },
                requestSnapshot = request,
                localSelections = emptyList(),
                authorizer = ResearchInterventionSearchFactory.authorizer(
                    request = request,
                    localSelections = emptyList(),
                    evaluationEnabled = true,
                ),
                retriever = ResearchInterventionRetriever { _, _, _ ->
                    retrievals++
                    ResearchInterventionRetrievalResult.Success(listOf(freshSource()))
                },
            ),
        )

        assertTrue(outcome is ResearchInterventionOutcome.Abstained)
        assertEquals(
            ResearchInterventionReason.SEARCH_NOT_AUTHORIZED,
            (outcome as ResearchInterventionOutcome.Abstained).decision.reason,
        )
        assertFalse(outcome.audit.retrievalAttempted)
        assertEquals(0, retrievals)
    }

    @Test
    fun `search permit is exact one shot and fresh relevant evidence is re-verified`() = runTest {
        var authorization: ResearchInterventionSearchAuthorizationContext? = null
        val input = input(
            answer = "Acme released Alpha in 2024.",
            backend = testBackend { pair ->
                if (pair.evidenceText.contains("2025")) {
                    VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
                } else {
                    VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT)
                }
            },
            authorizer = ResearchInterventionSearchAuthorizer { context ->
                authorization = context
                ResearchInterventionSearchPermit.grant(
                    conversationId = context.conversationId,
                    requestId = context.requestId,
                    draftFingerprint = context.draftFingerprint,
                    queries = context.queries,
                    explicitConsent = true,
                    forbiddenFileNames = context.localFileNames,
                )
            },
            retriever = ResearchInterventionRetriever { queries, permit, _ ->
                assertEquals(1, queries.size)
                assertTrue(permit.wasClaimedFor(71L, "request-1", permit.draftFingerprint))
                assertTrue(permit.authorizes(queries.single().claimId, queries.single().query))
                ResearchInterventionRetrievalResult.Success(listOf(freshSource()))
            },
        )

        val outcome = orchestrator().run(input)

        assertTrue(outcome is ResearchInterventionOutcome.Delivered)
        val delivered = outcome as ResearchInterventionOutcome.Delivered
        assertTrue(delivered.retrievalAttempted)
        assertEquals(ResearchInterventionAction.DELIVER_WITH_SCOPED_AUDIT, delivered.decision.action)
        assertFalse(delivered.decision.wholeAnswerCertified)
        assertTrue(authorization != null)
    }

    @Test
    fun `accepted retrieval updates durable source metadata even when answer text is unchanged`() = runTest {
        val input = input(
            answer = "Acme released Alpha in 2024.",
            backend = testBackend { pair ->
                if (pair.evidenceText.contains("2025")) {
                    VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
                } else {
                    VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT)
                }
            },
            authorizer = ResearchInterventionSearchAuthorizer { context ->
                ResearchInterventionSearchPermit.grant(
                    context.conversationId,
                    context.requestId,
                    context.draftFingerprint,
                    context.queries,
                    explicitConsent = true,
                )
            },
            retriever = ResearchInterventionRetriever { _, _, _ ->
                ResearchInterventionRetrievalResult.Success(listOf(freshSource()))
            },
        )

        val outcome = orchestrator().run(input)

        assertTrue(outcome is ResearchInterventionOutcome.Delivered)
        val delivered = outcome as ResearchInterventionOutcome.Delivered
        assertFalse(delivered.revised)
        assertTrue(delivered.retrievalAttempted)
        assertTrue(delivered.storedAnswer.contains("Acme Alpha 2025 release report"))
    }

    @Test
    fun `retrieved changed content is retained through revision and final verification`() = runTest {
        val original = freshSource(title = "Acme Alpha source", snippet = "prior evidence record")
        val updated = freshSource(title = "Acme Alpha source", snippet = "UPDATED_SOURCE_CONTENT Acme Alpha 2025")
        val input = input(
            answer = "Acme released Alpha in 2024.",
            webSources = listOf(original),
            backend = testBackend { pair ->
                when {
                    pair.evidenceText.contains("UPDATED_SOURCE_CONTENT") && pair.claimText.contains("2024") ->
                        VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED)
                    pair.evidenceText.contains("UPDATED_SOURCE_CONTENT") && pair.claimText.contains("2025") ->
                        VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
                    else -> VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT)
                }
            },
            generator = ResearchRevisionGenerator {
                ResearchRevisionResult.Revised("Acme released Alpha in 2025.")
            },
            authorizer = ResearchInterventionSearchAuthorizer { context ->
                ResearchInterventionSearchPermit.grant(
                    context.conversationId,
                    context.requestId,
                    context.draftFingerprint,
                    context.queries,
                    explicitConsent = true,
                )
            },
            retriever = ResearchInterventionRetriever { _, _, _ ->
                ResearchInterventionRetrievalResult.Success(listOf(updated))
            },
        )

        val outcome = orchestrator().run(input)

        assertTrue(outcome is ResearchInterventionOutcome.Delivered)
        val delivered = outcome as ResearchInterventionOutcome.Delivered
        assertTrue(delivered.retrievalAttempted)
        assertTrue(delivered.revised)
        assertTrue(delivered.storedAnswer.contains("UPDATED_SOURCE_CONTENT"))
        assertTrue(delivered.storedAnswer.contains("prior evidence record"))
        assertEquals(2, "Acme Alpha source".toRegex().findAll(delivered.storedAnswer).count())
        assertEquals("Acme released Alpha in 2025.", delivered.visibleAnswer)
        assertEquals(ResearchInterventionDraftStage.REVISED, delivered.audit.draftStage)
    }

    @Test
    fun `duplicate retrieved source identity is not attached or reverified`() = runTest {
        val duplicate = freshSource()
        var verifierCalls = 0
        var callsAtRetrieval = 0
        val input = input(
            answer = "Acme released Alpha in 2024.",
            webSources = listOf(duplicate),
            backend = testBackend {
                verifierCalls++
                VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT)
            },
            authorizer = ResearchInterventionSearchAuthorizer { context ->
                ResearchInterventionSearchPermit.grant(
                    context.conversationId,
                    context.requestId,
                    context.draftFingerprint,
                    context.queries,
                    explicitConsent = true,
                )
            },
            retriever = ResearchInterventionRetriever { _, _, _ ->
                callsAtRetrieval = verifierCalls
                ResearchInterventionRetrievalResult.Success(listOf(duplicate.copy()))
            },
        )

        val outcome = orchestrator().run(input)

        assertTrue(outcome is ResearchInterventionOutcome.Abstained)
        assertEquals(ResearchInterventionReason.INSUFFICIENT_AFTER_RETRIEVAL, (outcome as ResearchInterventionOutcome.Abstained).decision.reason)
        assertTrue(callsAtRetrieval > 0)
        assertEquals(callsAtRetrieval, verifierCalls)
        val duplicateSourceEvidence = outcome.audit.claims.flatMap { it.evidence }
            .filter { it.title == duplicate.title }
        assertEquals(1, duplicateSourceEvidence.map { it.evidenceIdentity }.distinct().size)
    }

    @Test
    fun `empty failed or unrelated retrieval abstains and never fabricates contradiction`() = runTest {
        val results = listOf(
            ResearchInterventionRetrievalResult.Empty to ResearchInterventionReason.INSUFFICIENT_AFTER_RETRIEVAL,
            ResearchInterventionRetrievalResult.Failed("down") to ResearchInterventionReason.SEARCH_ATTEMPT_FAILED,
            ResearchInterventionRetrievalResult.Success(
                listOf(freshSource(title = "Unrelated cooking site", snippet = "Best pasta recipe.")),
            ) to ResearchInterventionReason.INSUFFICIENT_AFTER_RETRIEVAL,
        )
        for ((retrievalResult, expected) in results) {
            val input = input(
                answer = "Acme released Alpha in 2024.",
                backend = testBackend { VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT) },
                authorizer = ResearchInterventionSearchAuthorizer { context ->
                    ResearchInterventionSearchPermit.grant(
                        context.conversationId,
                        context.requestId,
                        context.draftFingerprint,
                        context.queries,
                        explicitConsent = true,
                    )
                },
                retriever = ResearchInterventionRetriever { _, _, _ -> retrievalResult },
            )
            val outcome = orchestrator().run(input)
            assertTrue(outcome is ResearchInterventionOutcome.Abstained)
            assertEquals(
                "retrieval=$retrievalResult",
                expected,
                (outcome as ResearchInterventionOutcome.Abstained).decision.reason,
            )
        }
    }

    @Test
    fun `denied permit prevents every retrieval request`() = runTest {
        var retrievalCalls = 0
        val input = input(
            answer = "Acme released Alpha in 2024.",
            backend = testBackend { VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT) },
            authorizer = ResearchInterventionSearchAuthorizer { null },
            retriever = ResearchInterventionRetriever { _, _, _ ->
                retrievalCalls++
                ResearchInterventionRetrievalResult.Success(listOf(freshSource()))
            },
        )

        val outcome = orchestrator().run(input)

        assertTrue(outcome is ResearchInterventionOutcome.Abstained)
        assertEquals(ResearchInterventionReason.SEARCH_NOT_AUTHORIZED,
            (outcome as ResearchInterventionOutcome.Abstained).decision.reason)
        assertEquals(0, retrievalCalls)
    }

    @Test
    fun `ownership loss after verifier discards the draft`() = runTest {
        var owns = true
        val input = input(
            answer = "Acme released Alpha in 2024.",
            backend = testBackend {
                owns = false
                VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
            },
            owns = { owns },
        )

        assertEquals(ResearchInterventionOutcome.NotOwned, orchestrator().run(input))
    }

    @Test
    fun `ownership loss during revision discards the revised draft`() = runTest {
        var owns = true
        val input = input(
            answer = "Acme released Alpha in 2024.",
            backend = testBackend { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) },
            generator = ResearchRevisionGenerator {
                owns = false
                ResearchRevisionResult.Revised("Acme released Alpha in 2025.")
            },
            owns = { owns },
        )

        assertEquals(ResearchInterventionOutcome.NotOwned, orchestrator().run(input))
    }

    @Test
    fun `ownership loss during retrieval discards the retrieved draft`() = runTest {
        var owns = true
        val input = input(
            answer = "Acme released Alpha in 2024.",
            backend = testBackend { VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT) },
            authorizer = ResearchInterventionSearchAuthorizer { context ->
                ResearchInterventionSearchPermit.grant(
                    context.conversationId,
                    context.requestId,
                    context.draftFingerprint,
                    context.queries,
                    explicitConsent = true,
                )
            },
            retriever = ResearchInterventionRetriever { _, _, _ ->
                owns = false
                ResearchInterventionRetrievalResult.Success(listOf(freshSource()))
            },
            owns = { owns },
        )

        assertEquals(ResearchInterventionOutcome.NotOwned, orchestrator().run(input))
    }

    @Test
    fun `stale verifier result never becomes an intervention verdict`() = runTest {
        val input = input(
            answer = "Acme released Alpha in 2024.",
            backend = testBackend { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) },
        )
        val stale = ResearchInterventionInput(
            request = input.request,
            responseVersionTarget = input.responseVersionTarget,
            originalVisibleAnswer = input.originalVisibleAnswer,
            originalStoredAnswer = input.originalStoredAnswer,
            webSources = input.webSources,
            localSelections = input.localSelections,
            composeStoredAnswer = input.composeStoredAnswer,
            verifier = ResearchInterventionVerifier { _, _ ->
                ResearchInterventionVerification.Failed(ResearchInterventionReason.STALE_DRAFT)
            },
            revisionGenerator = input.revisionGenerator,
            searchAuthorizer = input.searchAuthorizer,
            retriever = input.retriever,
            owns = input.owns,
        )

        val outcome = orchestrator().run(stale)

        assertTrue(outcome is ResearchInterventionOutcome.Delivered)
        val delivered = outcome as ResearchInterventionOutcome.Delivered
        assertFalse(delivered.revised)
        assertEquals(
            ResearchInterventionReason.STALE_DRAFT,
            delivered.decision.reason,
        )
    }

    @Test
    fun `single flight rejects a second request with the same identity`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val orchestrator = orchestrator()
        val input = input(
            answer = "Acme released Alpha in 2024.",
            backend = testBackend {
                entered.complete(Unit)
                release.await()
                VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
            },
        )
        var first: ResearchInterventionOutcome? = null
        val job = launch { first = orchestrator.run(input) }
        entered.await()

        val second = orchestrator.run(input)

        assertEquals(ResearchInterventionOutcome.AlreadyRunning, second)
        release.complete(Unit)
        job.join()
        assertTrue(first is ResearchInterventionOutcome.Delivered)
    }

    @Test
    fun `overall deadline abstains and parent cancellation propagates`() = runTest {
        val timeoutInput = input(
            answer = "Acme released Alpha in 2024.",
            backend = object : VerifierBackend {
                override val descriptor = descriptor()
                override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult =
                    awaitCancellation()
            },
        )
        val timedOut = ResearchInterventionOrchestrator(
            activationPolicy = { _, _ -> true },
            budget = ResearchInterventionBudget(overallDeadlineMs = 10L),
        ).run(timeoutInput)
        assertTrue(timedOut is ResearchInterventionOutcome.Abstained)
        assertEquals(ResearchInterventionReason.INTERVENTION_TIMEOUT,
            (timedOut as ResearchInterventionOutcome.Abstained).decision.reason)

        val entered = CompletableDeferred<Unit>()
        val cancelledInput = input(
            answer = "Acme released Alpha in 2024.",
            backend = object : VerifierBackend {
                override val descriptor = descriptor()
                override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        val job = launch { orchestrator().run(cancelledInput) }
        entered.await()
        job.cancel(CancellationException("stop"))
        job.join()
        assertTrue(job.isCancelled)
    }

    private fun orchestrator() = ResearchInterventionOrchestrator(
        activationPolicy = { request, _ -> request.interactionMode == ChatInteractionMode.RESEARCH },
    )

    private fun input(
        answer: String,
        backend: VerifierBackend = testBackend {
            VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
        },
        webSources: List<WebSearchSource> = emptyList(),
        generator: ResearchRevisionGenerator? = null,
        authorizer: ResearchInterventionSearchAuthorizer? = null,
        retriever: ResearchInterventionRetriever? = null,
        requestSnapshot: GenerationRequestSnapshot = request(),
        localSelections: List<LocalRagSelection>? = null,
        owns: () -> Boolean = { true },
    ): ResearchInterventionInput {
        val request = requestSnapshot
        val local = localSelections ?: listOf(localSelection("Acme was founded in 2020."))
        val owner = ResearchDraftShadowOwner(
            activationPolicy = { true },
            backend = backend,
        )
        return ResearchInterventionInput(
            request = request,
            responseVersionTarget = null,
            originalVisibleAnswer = answer,
            originalStoredAnswer = answer,
            webSources = webSources,
            localSelections = local,
            composeStoredAnswer = { text, sources -> appendSearchSourcesMetadata(text, sources) },
            verifier = ResearchInterventionVerifier { draft, expectedFingerprint ->
                when (val outcome = owner.inspectBeforePersistence(
                    request = request,
                    exactStoredAssistantContent = draft.storedAnswer,
                    webSources = draft.webSources,
                    localSelections = draft.localSelections,
                    expectedCanonicalDraftFingerprint = expectedFingerprint,
                    ownsDraft = owns,
                )) {
                    is ResearchDraftShadowOutcome.Completed ->
                        ResearchInterventionVerification.Observed(outcome.diagnostic)
                    ResearchDraftShadowOutcome.NotOwned -> ResearchInterventionVerification.NotOwned
                    else -> ResearchInterventionVerification.Failed(
                        ResearchInterventionReason.VERIFIER_UNAVAILABLE,
                    )
                }
            },
            revisionGenerator = generator,
            searchAuthorizer = authorizer,
            retriever = retriever,
            owns = owns,
        )
    }

    private fun testBackend(verify: suspend (VerificationPairRequest) -> VerifierBackendResult): VerifierBackend =
        object : VerifierBackend {
            override val descriptor = descriptor()
            override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult = verify(request)
        }

    private fun request() = GenerationRequestSnapshot(
        requestId = "request-1",
        conversationId = 71L,
        generationModeKey = "LOCAL_MODEL",
        selectedModelId = null,
        selectedModelPath = null,
        settings = GenerationSettings(),
        reasoningEnabled = false,
        webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
        attachmentIds = emptyList(),
        multimodalImagePaths = emptyList(),
        promptText = "question",
        rawUserText = "question",
        createdAt = 1L,
        interactionMode = ChatInteractionMode.RESEARCH,
    )

    private fun descriptor() = VerifierBackendDescriptor(
        id = "test-local-verifier",
        version = "1",
        supportedLanguages = setOf("en", "ko"),
        experimental = false,
    )

    private fun freshSource(
        title: String = "Acme Alpha 2025 release report",
        snippet: String = "Acme released Alpha in 2025.",
    ) = WebSearchSource(
        title = title,
        source = "Example News",
        url = "https://example.com/alpha",
        snippet = snippet,
        publishedAt = null,
        providerType = WebSearchProviderType.FREE_DEFAULT,
        providerDisplayName = "Test Search",
        searchCategory = null,
        queryUsed = "Acme Alpha 2025",
        rank = 0,
    )

    private fun localSelection(excerpt: String) = LocalRagSelection(
        chunk = LocalDocumentChunk(
            docId = "doc-7",
            displayName = "private.txt",
            chunkIndex = 0,
            startOffset = 0,
            endOffset = excerpt.length,
            contentHashHex = "same-content-hash",
            text = excerpt,
        ),
        source = LocalDocumentSource(
            docId = "doc-7",
            displayName = "private.txt",
            attachmentId = "local-document-7",
            chunkIndex = 0,
            startOffset = 0,
            endOffset = excerpt.length,
            contentHashHex = "same-content-hash",
            excerpt = excerpt,
        ),
        lexicalScore = 2,
        semanticScore = null,
        rank = 0,
    )
}
