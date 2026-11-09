package com.projectnuke.fusion.ui

import com.projectnuke.fusion.audit.ResearchInterventionActivationPolicy
import com.projectnuke.fusion.audit.ResearchInterventionAction
import com.projectnuke.fusion.audit.ResearchInterventionAudit
import com.projectnuke.fusion.audit.ResearchInterventionDraftStage
import com.projectnuke.fusion.audit.ResearchInterventionFindingSubject
import com.projectnuke.fusion.audit.ResearchInterventionOrchestrator
import com.projectnuke.fusion.audit.ResearchInterventionReason
import com.projectnuke.fusion.audit.AuditVerificationLabel
import com.projectnuke.fusion.audit.VerificationPairRequest
import com.projectnuke.fusion.audit.VerifierBackend
import com.projectnuke.fusion.audit.VerifierBackendDescriptor
import com.projectnuke.fusion.audit.VerifierBackendResult
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.search.WebSearchProviderType
import com.projectnuke.fusion.search.WebSearchSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchInterventionCoordinatorTest {
    @Test
    fun `abstention does not attach draft evidence to the final notice`() = runTest {
        val source = WebSearchSource(
            title = "Acme Alpha report",
            source = "Example",
            url = "https://example.com/acme",
            snippet = "Acme released Alpha in 2024.",
            publishedAt = null,
            providerType = WebSearchProviderType.FREE_DEFAULT,
            providerDisplayName = "Test Search",
            searchCategory = null,
            queryUsed = "Acme Alpha 2024",
            rank = 0,
        )
        val backend = object : VerifierBackend {
            override val descriptor = VerifierBackendDescriptor(
                id = "coordinator-test",
                version = "1",
                supportedLanguages = setOf("en"),
                experimental = false,
            )

            override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult =
                VerifierBackendResult.Verified(AuditVerificationLabel.INSUFFICIENT)
        }
        val coordinator = ResearchInterventionCoordinator(
            orchestrator = ResearchInterventionOrchestrator(
                activationPolicy = ResearchInterventionActivationPolicy { _, _ -> true },
            ),
            shadowOwner = ResearchDraftShadowOwner(
                activationPolicy = ResearchDraftShadowActivationPolicy { true },
                backend = backend,
            ),
        )
        var composedSources: List<WebSearchSource>? = null

        val resolution = coordinator.resolve(
            request = request(),
            exactStoredAssistantContent = "Acme released Alpha in 2024.",
            webSources = listOf(source),
            localSelections = emptyList(),
            responseVersionTarget = null,
            composeStoredAnswer = { text, sources ->
                composedSources = sources
                "$text\n${sources.size}"
            },
            revisionGenerator = null,
            searchAuthorizer = null,
            retriever = null,
            owns = { true },
        )

        assertTrue(resolution.abstained)
        assertEquals(emptyList<WebSearchSource>(), composedSources)
        assertEquals(ResearchInterventionFindingSubject.PRE_PERSISTENCE_DRAFT, resolution.audit?.findingSubject)
    }

    @Test
    fun `abstention audit hash binds the final persisted notice not rejected draft`() {
        val finalNotice = "The available evidence is not enough to answer this confidently."
        val audit = ResearchInterventionAudit(
            conversationId = 71L,
            requestId = "request-audit-binding",
            action = ResearchInterventionAction.ABSTAIN,
            reason = ResearchInterventionReason.INSUFFICIENT_EVIDENCE,
            wholeAnswerCertified = false,
            claimScopedOnly = true,
            originalDraftFingerprint = "a".repeat(64),
            finalDraftFingerprint = "a".repeat(64),
            revisionAttempted = false,
            retrievalAttempted = false,
            claims = emptyList(),
            findingSubject = ResearchInterventionFindingSubject.PRE_PERSISTENCE_DRAFT,
            draftStage = ResearchInterventionDraftStage.ORIGINAL,
        )

        val record = buildResearchInterventionAuditRecord(
            audit = audit,
            messageId = 42L,
            storedAssistantContent = finalNotice,
            generationModeKey = "LOCAL_MODEL",
            savedAt = 10L,
        )

        assertEquals(
            EvidenceFingerprint.canonicalAnswerHash(
                assistantVisibleAnswerForHandoff(finalNotice, reasoningEnabled = true, generationModeKey = "LOCAL_MODEL"),
            ),
            record.answerSha256,
        )
        assertEquals(ResearchInterventionFindingSubject.PRE_PERSISTENCE_DRAFT, record.findingSubject)
    }

    @Test
    fun `ownership lost while shadow verifier runs is not a persistable original answer`() = runTest {
        val request = request()
        var ownershipChecks = 0
        val coordinator = ResearchInterventionCoordinator(
            orchestrator = ResearchInterventionOrchestrator(
                activationPolicy = ResearchInterventionActivationPolicy { _, _ -> true },
            ),
            shadowOwner = ResearchDraftShadowOwner(
                activationPolicy = ResearchDraftShadowActivationPolicy { true },
            ),
        )

        val resolution = coordinator.resolve(
            request = request,
            exactStoredAssistantContent = "Original generated answer.",
            webSources = emptyList(),
            localSelections = emptyList(),
            responseVersionTarget = null,
            composeStoredAnswer = { text, _ -> text },
            revisionGenerator = null,
            searchAuthorizer = null,
            retriever = null,
            // The verifier passes its request-side ownership checks, then the
            // request loses ownership while inference is suspended.
            owns = { ++ownershipChecks <= 3 },
        )

        assertEquals(
            ResearchInterventionResolutionDisposition.OWNERSHIP_LOST,
            resolution.disposition,
        )
        assertTrue(ownershipChecks >= 4)
        assertFalse(resolution.mayPersist)
    }

    @Test
    fun `feature off remains an ordinary persistable response`() = runTest {
        val request = request()
        val coordinator = ResearchInterventionCoordinator(
            orchestrator = ResearchInterventionOrchestrator(),
            shadowOwner = ResearchDraftShadowOwner(),
        )

        val resolution = coordinator.resolve(
            request = request,
            exactStoredAssistantContent = "Original generated answer.",
            webSources = emptyList(),
            localSelections = emptyList(),
            responseVersionTarget = null,
            composeStoredAnswer = { text, _ -> text },
            revisionGenerator = null,
            searchAuthorizer = null,
            retriever = null,
            owns = { true },
        )

        assertEquals(
            ResearchInterventionResolutionDisposition.NOT_APPLICABLE,
            resolution.disposition,
        )
        assertEquals("Original generated answer.", resolution.storedAssistantContent)
        assertTrue(resolution.mayPersist)
    }

    private fun request() = GenerationRequestSnapshot(
        requestId = "request-ownership-test",
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
}
