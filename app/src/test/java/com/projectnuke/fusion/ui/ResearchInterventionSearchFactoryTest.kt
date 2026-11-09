package com.projectnuke.fusion.ui

import com.projectnuke.fusion.audit.ResearchInterventionSearchAuthorizationContext
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.privacy.DataEgressPrivateTextLedger
import com.projectnuke.fusion.privacy.ResearchInterventionSearchQuery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchInterventionSearchFactoryTest {
    @Test
    fun `explicit public user query grants exact bounded request authorization`() {
        val request = request("Tell me about Acme Alpha 2025")
        val query = ResearchInterventionSearchQuery("AC1", "Acme Alpha 2025")
        val authorizer = ResearchInterventionSearchFactory.authorizer(request, emptyList(), true)

        val permit = authorizer?.authorize(context(request, query))

        assertNotNull(permit)
        assertTrue(permit!!.claimExecution(request.conversationId, request.requestId, fingerprint()))
        assertTrue(permit.authorizes(query.claimId, query.query))
        assertFalse(permit.authorizes(query.claimId, query.query + " private payroll"))
    }

    @Test
    fun `explicitly typed ticker remains authorized as public query material`() {
        val request = request("Samsung Electronics 005930 stock")
        val query = ResearchInterventionSearchQuery("AC1", "Samsung Electronics 005930 stock")
        val authorizer = ResearchInterventionSearchFactory.authorizer(request, emptyList(), true)

        val permit = authorizer?.authorize(context(request, query))

        assertNotNull(permit)
        assertTrue(permit!!.claimExecution(request.conversationId, request.requestId, fingerprint()))
        assertTrue(permit!!.authorizes(query.claimId, query.query))
    }

    @Test
    fun `ticker expansion is rejected when the user did not type the ticker`() {
        val request = request("Samsung Electronics stock")
        val expandedQuery = ResearchInterventionSearchQuery(
            "AC1",
            "Samsung Electronics 005930 stock",
        )

        val permit = ResearchInterventionSearchFactory.authorizer(request, emptyList(), true)
            ?.authorize(context(request, expandedQuery))

        assertNull(permit)
    }

    @Test
    fun `assistant added claim material is not authorized by web search setting`() {
        val request = request("Who is Acme?")
        val authorizer = ResearchInterventionSearchFactory.authorizer(request, emptyList(), true)

        val permit = authorizer?.authorize(
            context(request, ResearchInterventionSearchQuery("AC1", "Acme private payroll 2025")),
        )

        assertNull(permit)
    }

    @Test
    fun `search authorization context must match its captured request identity`() {
        val request = request("Acme Alpha 2025")
        val query = ResearchInterventionSearchQuery("AC1", "Acme Alpha 2025")
        val mismatched = context(request, query).copy(requestId = "another-request")

        val permit = ResearchInterventionSearchFactory.authorizer(request, emptyList(), true)
            ?.authorize(mismatched)

        assertNull(permit)
    }

    @Test
    fun `claim derived retrieval rejects disabled or inherited private request context`() {
        val base = request("Acme Alpha 2025")

        assertNull(
            ResearchInterventionSearchFactory.authorizer(
                base.copy(webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.AUTO),
                emptyList(),
                true,
            ),
        )
        assertNull(
            ResearchInterventionSearchFactory.authorizer(
                base.copy(webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED),
                emptyList(),
                true,
            ),
        )
        assertNull(
            ResearchInterventionSearchFactory.authorizer(
                base.copy(attachmentIds = listOf("attachment-1")),
                emptyList(),
                true,
            ),
        )
        assertNull(
            ResearchInterventionSearchFactory.authorizer(
                base.copy(safeWebSearchPriorUserTurns = listOf("older private context")),
                emptyList(),
                true,
            ),
        )
        assertNull(ResearchInterventionSearchFactory.authorizer(base, emptyList(), false))
    }

    @Test
    fun `private text registered after permit issuance revokes authorization`() {
        val request = request("Acme Alpha 2025")
        val query = ResearchInterventionSearchQuery("AC1", "Acme Alpha 2025")
        val permit = ResearchInterventionSearchFactory.authorizer(request, emptyList(), true)
            ?.authorize(context(request, query))
            ?: error("expected request-scoped permit")
        assertTrue(permit.claimExecution(request.conversationId, request.requestId, fingerprint()))
        val previouslyRegisteredTexts = DataEgressPrivateTextLedger.currentTexts()

        try {
            DataEgressPrivateTextLedger.register(listOf(query.query))

            assertFalse(permit.authorizes(query.claimId, query.query))
        } finally {
            DataEgressPrivateTextLedger.clear()
            DataEgressPrivateTextLedger.register(previouslyRegisteredTexts)
        }
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
        requestId = "research-search-1",
        conversationId = 71L,
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
