package com.projectnuke.fusion.ui

import com.projectnuke.fusion.audit.AuditAggregateStatus
import com.projectnuke.fusion.audit.AuditVerificationLabel
import com.projectnuke.fusion.audit.VerifierBackend
import com.projectnuke.fusion.audit.VerifierBackendDescriptor
import com.projectnuke.fusion.audit.VerifierBackendResult
import com.projectnuke.fusion.audit.VerifierBackendAvailability
import com.projectnuke.fusion.audit.VerificationPairRequest
import com.projectnuke.fusion.audit.ResearchVerificationAction
import com.projectnuke.fusion.audit.ResearchVerificationReason
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.retrieval.LocalDocumentChunk
import com.projectnuke.fusion.retrieval.LocalDocumentSource
import com.projectnuke.fusion.retrieval.LocalRagSelection
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
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

class ResearchDraftShadowOwnerTest {
    @Test
    fun `same AC1 on different draft text cannot reuse the first draft audit`() = runTest {
        val firstAnswer = "Acme released Alpha in 2024."
        val secondAnswer = "Acme released Beta in 2024."
        val owner = owner { VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) }

        val first = inspect(owner, request(), firstAnswer)
        val second = inspect(owner, request(), secondAnswer)

        assertNotEquals(first.identity, second.identity)
        assertEquals("AC1", first.identity.claimIdentities.single().claimId)
        assertEquals("AC1", second.identity.claimIdentities.single().claimId)
        assertNotEquals(first.identity.claimIdentities.single().claimTextSha256,
            second.identity.claimIdentities.single().claimTextSha256)
    }

    @Test
    fun `same answer is bound to its exact conversation request and response version target`() = runTest {
        val content = "Acme released Alpha in 2024."
        val owner = owner { VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) }
        val original = inspect(owner, request(requestId = "request-a"), content)
        val otherRequest = inspect(owner, request(requestId = "request-b"), content)
        val otherConversation = inspect(owner, request(requestId = "request-a", conversationId = 72L), content)
        val versionOne = inspect(
            owner, request(requestId = "version-a"), content,
            target = ResearchDraftResponseVersionTarget(targetAssistantMessageId = 100L, groupId = 90L),
        )
        val versionTwo = inspect(
            owner, request(requestId = "version-b"), content,
            target = ResearchDraftResponseVersionTarget(targetAssistantMessageId = 101L, groupId = 90L),
        )

        assertNotEquals(original.identity, otherRequest.identity)
        assertNotEquals(original.identity, otherConversation.identity)
        assertNotEquals(versionOne.identity, versionTwo.identity)
    }

    @Test
    fun `reused source label with changed underlying content gets a new evidence identity`() = runTest {
        val owner = owner { VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) }
        val first = inspect(owner, request(), "Acme released Alpha in 2024.", local = localSelection("source text A"))
        val changedSource = inspect(owner, request(), "Acme released Alpha in 2024.", local = localSelection("source text B"))

        assertEquals("local-document-7", localSelection("source text A").source.attachmentId)
        assertEquals(1, first.identity.evidencePairIdentities.size)
        assertNotEquals(first.identity.evidencePairIdentities, changedSource.identity.evidencePairIdentities)
    }

    @Test
    fun `default disabled policy does not call verifier or alter the stored answer`() = runTest {
        var calls = 0
        val storedContent = "Acme released Alpha in 2024.<fusion_metrics>metric details</fusion_metrics>"
        val owner = ResearchDraftShadowOwner(backend = backend { calls++; VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) })

        val outcome = owner.inspectBeforePersistence(
            request = request(),
            exactStoredAssistantContent = storedContent,
            localSelections = listOf(localSelection("private context")),
            ownsDraft = { true },
        )
        val savedAnswer = storedContent

        assertEquals(ResearchDraftShadowOutcome.Disabled, outcome)
        assertEquals(0, calls)
        assertEquals(storedContent, savedAnswer)
    }

    @Test
    fun `enabled shadow returns a decision for the canonical stored answer without changing it`() = runTest {
        val answer = "Acme released Alpha in 2024."
        val local = localSelection("private persisted source excerpt")
        val storedContent = storedAssistantContentWithLocalRag(
            answer = "$answer\n\n<fusion_metrics>metric details</fusion_metrics>",
            trustRoutingDecision = TrustRoutingDecision.local(TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE),
            ragSelections = listOf(local),
        )
        val owner = owner { VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) }
        val outcome = owner.inspectBeforePersistence(
            request = request(),
            exactStoredAssistantContent = storedContent,
            localSelections = listOf(local),
            ownsDraft = { true },
        )
        val savedAnswer = storedContent

        assertTrue(outcome is ResearchDraftShadowOutcome.Completed)
        val diagnostic = (outcome as ResearchDraftShadowOutcome.Completed).diagnostic
        assertEquals(EvidenceFingerprint.canonicalAnswerHash(answer), diagnostic.identity.canonicalDraftFingerprint)
        assertFalse(diagnostic.toString().contains("private persisted source excerpt"))
        assertFalse(diagnostic.authoritativeCoverage)
        assertEquals(ResearchVerificationAction.ABSTAIN, diagnostic.decision.action)
        assertEquals(ResearchVerificationReason.INCOMPLETE_CLAIM_COVERAGE, diagnostic.decision.reason)
        assertEquals(storedContent, savedAnswer)
    }

    @Test
    fun `heuristic inventory remains unknown or incomplete and never claims authoritative coverage`() = runTest {
        val owner = owner { VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) }
        val unknown = inspect(owner, request(), "Acme released Alpha in 2024.")
        val incomplete = inspect(
            owner,
            request(requestId = "request-code"),
            "Acme released Alpha in 2024.\n```text\nAcme released Beta in 2025.\n```",
        )

        assertEquals(com.projectnuke.fusion.audit.AnswerCoverageState.UNKNOWN, unknown.coverageState)
        assertEquals(com.projectnuke.fusion.audit.AnswerCoverageState.INCOMPLETE, incomplete.coverageState)
        assertFalse(unknown.authoritativeCoverage)
        assertFalse(incomplete.authoritativeCoverage)
        assertTrue(unknown.checkedClaimIds.isNotEmpty())
        assertEquals(ResearchVerificationReason.INCOMPLETE_CLAIM_COVERAGE, unknown.decision.reason)
    }

    @Test
    fun `research mode is required even when a test activation policy is enabled`() = runTest {
        var calls = 0
        val owner = owner { calls++; VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) }

        val outcome = owner.inspectBeforePersistence(
            request(mode = ChatInteractionMode.NORMAL),
            "Acme released Alpha in 2024.",
            localSelections = listOf(localSelection("source text")),
            ownsDraft = { true },
        )

        assertEquals(ResearchDraftShadowOutcome.NotResearchRequest, outcome)
        assertEquals(0, calls)
    }

    @Test
    fun `local private source is passed only to verifier and never to a search or egress owner`() = runTest {
        val privateExcerpt = "Private report: Acme released Alpha in 2024."
        val requests = mutableListOf<VerificationPairRequest>()
        val owner = owner { request ->
            requests += request
            VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
        }

        val diagnostic = inspect(owner, request(), "Acme released Alpha in 2024.", local = localSelection(privateExcerpt))

        assertEquals(1, requests.size)
        assertTrue(requests.single().evidenceText.contains(privateExcerpt))
        assertFalse(diagnostic.toString().contains(privateExcerpt))
        assertTrue(diagnostic.identity.evidencePairIdentities.single().evidenceIdentity.startsWith("draft-"))
        assertFalse(diagnostic.identity.evidencePairIdentities.single().evidenceIdentity.contains(privateExcerpt))
        assertEquals(AuditAggregateStatus.SUPPORTED, diagnostic.claimAggregateStatuses["AC1"])
    }

    @Test
    fun `navigation or conversation deletion during inference discards the diagnostic`() = runTest {
        for (transition in listOf("navigation", "conversation deletion")) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var owns = true
            val owner = owner {
                entered.complete(Unit)
                release.await()
                VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
            }
            var result: ResearchDraftShadowOutcome? = null
            val job = launch {
                result = owner.inspectBeforePersistence(
                    request = request(requestId = transition),
                    exactStoredAssistantContent = "Acme released Alpha in 2024.",
                    localSelections = listOf(localSelection("source text")),
                    ownsDraft = { owns },
                )
            }

            entered.await()
            owns = false
            release.complete(Unit)
            job.join()

            assertEquals(
                "$transition must discard stale result",
                ResearchDraftShadowOutcome.Stale(ResearchDraftStalePhase.AFTER_INFERENCE),
                result,
            )
        }
    }

    @Test
    fun `parent cancellation propagates and cancels shadow inference`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val owner = owner {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = launch {
            inspect(owner, request(), "Acme released Alpha in 2024.")
        }
        entered.await()
        job.cancel(CancellationException("user stop"))
        job.join()

        assertTrue(job.isCancelled)
    }

    @Test
    fun `unsupported contradictory and insufficient outcomes remain distinct`() = runTest {
        val content = "Acme released Alpha in 2024."
        val unsupported = inspect(
            ResearchDraftShadowOwner(activationPolicy = enabled(), backend = unavailableBackend()),
            request(), content,
        )
        val contradicted = inspect(owner { VerifierBackendResult.Verified(AuditVerificationLabel.CONTRADICTED) }, request(), content)
        val insufficient = inspect(owner { VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT) }, request(), content)

        assertEquals(AuditAggregateStatus.NOT_VERIFIED, unsupported.claimAggregateStatuses["AC1"])
        assertEquals(ResearchVerificationReason.VERIFIER_UNAVAILABLE, unsupported.decision.reason)
        assertEquals(AuditAggregateStatus.CONTRADICTED, contradicted.claimAggregateStatuses["AC1"])
        assertEquals(ResearchVerificationReason.REVISION_BUDGET_EXHAUSTED, contradicted.decision.reason)
        assertEquals(AuditAggregateStatus.INSUFFICIENT, insufficient.claimAggregateStatuses["AC1"])
        assertEquals(ResearchVerificationReason.SEARCH_NOT_AUTHORIZED, insufficient.decision.reason)
    }

    @Test
    fun `missing verifier assets are typed unavailable and never become a contradiction`() = runTest {
        var calls = 0
        val liveButMissingAssets = object : VerifierBackend {
            override val descriptor = VerifierBackendDescriptor(
                id = "live-but-missing-assets",
                version = "1",
                supportedLanguages = setOf("en"),
                experimental = false,
                availability = VerifierBackendAvailability.AVAILABLE,
            )

            override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult {
                calls++
                return VerifierBackendResult.Unavailable
            }
        }
        val outcome = inspectOutcome(
            ResearchDraftShadowOwner(activationPolicy = enabled(), backend = liveButMissingAssets),
            request(),
            "Acme released Alpha in 2024.",
        )

        val diagnostic = (outcome as ResearchDraftShadowOutcome.Completed).diagnostic
        assertEquals(1, calls)
        assertEquals(ResearchDraftVerifierStatus.UNAVAILABLE, diagnostic.verifierStatus)
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, diagnostic.claimAggregateStatuses["AC1"])
        assertEquals(ResearchVerificationAction.ABSTAIN, diagnostic.decision.action)
        assertEquals(ResearchVerificationReason.VERIFIER_UNAVAILABLE, diagnostic.decision.reason)
        assertFalse(diagnostic.wholeAnswerVerificationClaimed)
    }

    @Test
    fun `missing evidence is typed missing and never reported as unavailable or supported`() = runTest {
        var calls = 0
        val owner = owner { calls++; VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) }

        val outcome = inspectOutcome(
            owner,
            request(),
            "Acme released Alpha in 2024.",
            localSelections = emptyList(),
        )

        val diagnostic = (outcome as ResearchDraftShadowOutcome.Completed).diagnostic
        assertEquals(0, calls)
        assertEquals(ResearchDraftEvidenceStatus.MISSING, diagnostic.evidenceStatus)
        assertEquals(0, diagnostic.expectedPairCount)
        assertEquals(0, diagnostic.submittedPairCount)
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, diagnostic.claimAggregateStatuses["AC1"])
        assertEquals(ResearchVerificationAction.ABSTAIN, diagnostic.decision.action)
        assertEquals(ResearchVerificationReason.VERIFIER_RESULT_INCOMPLETE, diagnostic.decision.reason)
    }

    @Test
    fun `bounded verifier deadline produces a typed timeout observation`() = runTest {
        val backend = object : VerifierBackend {
            override val descriptor = VerifierBackendDescriptor(
                id = "slow-verifier",
                version = "1",
                supportedLanguages = setOf("en"),
                experimental = false,
            )

            override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult {
                awaitCancellation()
            }
        }
        val owner = ResearchDraftShadowOwner(
            activationPolicy = enabled(),
            backend = backend,
            execution = com.projectnuke.fusion.audit.AnswerAuditExecutionOrchestrator(
                backend = backend,
                totalDeadlineMs = 10L,
            ),
        )

        val outcome = inspectOutcome(owner, request(), "Acme released Alpha in 2024.")

        val diagnostic = (outcome as ResearchDraftShadowOutcome.Completed).diagnostic
        assertEquals(ResearchDraftVerifierStatus.TIMEOUT, diagnostic.verifierStatus)
        assertEquals(AuditAggregateStatus.NOT_VERIFIED, diagnostic.claimAggregateStatuses["AC1"])
        assertEquals(ResearchVerificationAction.ABSTAIN, diagnostic.decision.action)
        assertEquals(ResearchVerificationReason.VERIFIER_RESULT_INCOMPLETE, diagnostic.decision.reason)
        assertEquals(1, diagnostic.submittedPairCount)
    }

    @Test
    fun `partial verifier results remain incomplete and cannot become final`() = runTest {
        val owner = owner { request ->
            if (request.evidenceText.contains("bad evidence")) {
                VerifierBackendResult.Failure(com.projectnuke.fusion.audit.AuditBackendFailureReason.EXECUTION_FAILED)
            } else {
                VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED)
            }
        }

        val outcome = inspectOutcome(
            owner,
            request(),
            "Acme released Alpha in 2024.",
            localSelections = listOf(localSelection("good evidence"), localSelection("bad evidence")),
        )

        val diagnostic = (outcome as ResearchDraftShadowOutcome.Completed).diagnostic
        assertEquals(ResearchDraftVerifierStatus.PARTIAL, diagnostic.verifierStatus)
        assertEquals(2, diagnostic.expectedPairCount)
        assertEquals(2, diagnostic.submittedPairCount)
        assertEquals(ResearchVerificationAction.ABSTAIN, diagnostic.decision.action)
        assertEquals(ResearchVerificationReason.VERIFIER_RESULT_INCOMPLETE, diagnostic.decision.reason)
        assertFalse(diagnostic.decision.isFullyEvidenceSupported)
        assertFalse(diagnostic.wholeAnswerVerificationClaimed)
    }

    @Test
    fun `changed answer fingerprint fails closed before verifier execution`() = runTest {
        var calls = 0
        val owner = owner { calls++; VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) }

        val outcome = inspectOutcome(
            owner,
            request(),
            "Acme released Beta in 2024.",
            expectedFingerprint = EvidenceFingerprint.canonicalAnswerHash("Acme released Alpha in 2024."),
        )

        assertEquals(
            ResearchDraftShadowOutcome.Failed(ResearchDraftFailureReason.DRAFT_FINGERPRINT_CHANGED),
            outcome,
        )
        assertEquals(0, calls)
    }

    @Test
    fun `heuristic inventory never claims whole answer verification`() = runTest {
        val owner = owner { VerifierBackendResult.Verified(AuditVerificationLabel.SUPPORTED) }

        val diagnostic = inspect(owner, request(), "Acme released Alpha in 2024.")

        assertFalse(diagnostic.authoritativeCoverage)
        assertFalse(diagnostic.wholeAnswerVerificationClaimed)
        assertFalse(diagnostic.decision.isFullyEvidenceSupported)
        assertEquals(ResearchVerificationAction.ABSTAIN, diagnostic.decision.action)
        assertEquals(ResearchVerificationReason.INCOMPLETE_CLAIM_COVERAGE, diagnostic.decision.reason)
    }

    private suspend fun inspect(
        owner: ResearchDraftShadowOwner,
        request: GenerationRequestSnapshot,
        content: String,
        local: LocalRagSelection = localSelection("source text"),
        target: ResearchDraftResponseVersionTarget? = null,
        owns: () -> Boolean = { true },
    ): ResearchDraftShadowDiagnostic = when (
        val outcome = inspectOutcome(owner, request, content, listOf(local), target, owns = owns)
    ) {
        is ResearchDraftShadowOutcome.Completed -> outcome.diagnostic
        else -> error("Expected enabled shadow diagnostic but was $outcome")
    }

    private suspend fun inspectOutcome(
        owner: ResearchDraftShadowOwner,
        request: GenerationRequestSnapshot,
        content: String,
        localSelections: List<LocalRagSelection> = listOf(localSelection("source text")),
        target: ResearchDraftResponseVersionTarget? = null,
        expectedFingerprint: String? = null,
        owns: () -> Boolean = { true },
    ): ResearchDraftShadowOutcome = owner.inspectBeforePersistence(
        request = request,
        exactStoredAssistantContent = content,
        localSelections = localSelections,
        responseVersionTarget = target,
        expectedCanonicalDraftFingerprint = expectedFingerprint,
        ownsDraft = owns,
    )

    private fun owner(verify: suspend (VerificationPairRequest) -> VerifierBackendResult) =
        ResearchDraftShadowOwner(activationPolicy = enabled(), backend = backend(verify))

    private fun enabled() = ResearchDraftShadowActivationPolicy { true }

    private fun backend(verify: suspend (VerificationPairRequest) -> VerifierBackendResult) = object : VerifierBackend {
        override val descriptor = VerifierBackendDescriptor(
            id = "test-local-verifier",
            version = "1",
            supportedLanguages = setOf("en", "ko"),
            experimental = false,
        )

        override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult = verify(request)
    }

    private fun unavailableBackend() = object : VerifierBackend {
        override val descriptor = VerifierBackendDescriptor(
            id = "test-unavailable",
            version = "1",
            supportedLanguages = emptySet(),
            experimental = false,
            availability = VerifierBackendAvailability.UNAVAILABLE,
        )

        override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult =
            error("Unavailable backend must not be called")
    }

    private fun request(
        requestId: String = "request-1",
        conversationId: Long = 71L,
        mode: ChatInteractionMode = ChatInteractionMode.RESEARCH,
    ) = GenerationRequestSnapshot(
        requestId = requestId,
        conversationId = conversationId,
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
        interactionMode = mode,
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
