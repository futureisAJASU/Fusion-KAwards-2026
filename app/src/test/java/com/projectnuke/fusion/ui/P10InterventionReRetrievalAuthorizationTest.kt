package com.projectnuke.fusion.ui

import com.projectnuke.fusion.audit.ResearchInterventionSearchAuthorizationContext
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.privacy.ResearchInterventionSearchQuery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P10-D: the P9 intervention re-retrieval path keeps its own exact,
 * request-scoped authorization. The agentic harness's explicit-retrieval rule
 * never widens it, and a re-retrieval query that the user did not type stays
 * unauthorized even when a planner would have produced it.
 */
class P10InterventionReRetrievalAuthorizationTest {

    @Test
    fun reRetrievalOfTheExactTypedQueryStaysAuthorized() {
        val request = request("Acme Alpha 2025")
        val query = ResearchInterventionSearchQuery("AC1", "Acme Alpha 2025")

        val permit = ResearchInterventionSearchFactory.authorizer(request, emptyList(), true)
            ?.authorize(context(request, query))

        assertNotNull(permit)
        assertTrue(permit!!.claimExecution(request.conversationId, request.requestId, fingerprint()))
        assertTrue(permit.authorizes(query.claimId, query.query))
    }

    @Test
    fun reRetrievalOfAPlannerInventedQueryStaysUnauthorized() {
        val request = request("Acme Alpha 2025")
        val plannerQuery = ResearchInterventionSearchQuery("AC1", "Acme Alpha 2025 internal payroll")

        val permit = ResearchInterventionSearchFactory.authorizer(request, emptyList(), true)
            ?.authorize(context(request, plannerQuery))

        assertNull(permit)
    }

    @Test
    fun inheritedPriorTurnContextStillBlocksReRetrieval() {
        val request = request("Acme Alpha 2025").copy(
            safeWebSearchPriorUserTurns = listOf("earlier private context"),
        )
        val query = ResearchInterventionSearchQuery("AC1", "Acme Alpha 2025")

        val permit = ResearchInterventionSearchFactory.authorizer(request, emptyList(), true)
            ?.authorize(context(request, query))

        assertNull(permit)
    }

    @Test
    fun aClaimedPermitCannotAuthorizeASecondRetrievalAttempt() {
        val request = request("Acme Alpha 2025")
        val query = ResearchInterventionSearchQuery("AC1", "Acme Alpha 2025")
        val permit = ResearchInterventionSearchFactory.authorizer(request, emptyList(), true)
            ?.authorize(context(request, query))
            ?: error("expected request-scoped permit")

        assertTrue(permit.claimExecution(request.conversationId, request.requestId, fingerprint()))
        assertFalse(permit.claimExecution(request.conversationId, request.requestId, fingerprint()))
        assertTrue(permit.authorizes(query.claimId, query.query))
        assertFalse(permit.authorizes(query.claimId, "Acme Alpha 2025 internal payroll"))
    }

    private fun context(
        request: GenerationRequestSnapshot,
        query: ResearchInterventionSearchQuery,
    ) = ResearchInterventionSearchAuthorizationContext(
        conversationId = request.conversationId,
        requestId = request.requestId,
        draftFingerprint = fingerprint(),
        queries = listOf(query),
        localFileNames = emptyList(),
    )

    private fun fingerprint() = "a".repeat(64)

    private fun request(rawUserText: String) = GenerationRequestSnapshot(
        requestId = "p10d-intervention",
        conversationId = 91L,
        generationModeKey = "LOCAL_MODEL",
        selectedModelId = null,
        selectedModelPath = null,
        settings = GenerationSettings(),
        reasoningEnabled = false,
        webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
        attachmentIds = emptyList(),
        multimodalImagePaths = emptyList(),
        promptText = rawUserText,
        rawUserText = rawUserText,
        createdAt = 1L,
        interactionMode = ChatInteractionMode.RESEARCH,
    )
}
