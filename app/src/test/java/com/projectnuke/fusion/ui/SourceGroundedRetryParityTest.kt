package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.trust.TrustRoutingSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceGroundedRetryParityTest {
    private val localDecision = TrustRoutingDecision(
        route = TrustRoute.LOCAL,
        reason = TrustRoutingReason.REASONING_OR_TRANSFORMATION,
        signals = setOf(TrustRoutingSignal.LOCAL_CONTENT_PRESENT),
    )
    private val searchDecision = TrustRoutingDecision(
        route = TrustRoute.SEARCH,
        reason = TrustRoutingReason.CURRENT_INFORMATION_REQUIRED,
        signals = setOf(TrustRoutingSignal.CURRENT_INFORMATION_REQUESTED),
    )

    @Test
    fun `frozen local route clamps automatically suggested retry search`() {
        val snapshot = build(
            frozen = localDecision,
            policy = GenerationRequestSnapshot.WebSearchPolicy.AUTO,
        )

        assertEquals(TrustRoute.LOCAL, snapshot.trustRoutingDecision.route)
        assertEquals(GenerationRequestSnapshot.WebSearchPolicy.DISABLED, snapshot.webSearchPolicy)
    }

    @Test
    fun `frozen search route keeps automatically suggested retry search`() {
        val snapshot = build(
            frozen = searchDecision,
            policy = GenerationRequestSnapshot.WebSearchPolicy.AUTO,
        )

        assertEquals(TrustRoute.SEARCH, snapshot.trustRoutingDecision.route)
        assertEquals(GenerationRequestSnapshot.WebSearchPolicy.AUTO, snapshot.webSearchPolicy)
    }

    @Test
    fun `explicit manual search remains enabled on a frozen local route`() {
        val snapshot = build(
            frozen = localDecision,
            policy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
        )

        assertEquals(TrustRoute.LOCAL, snapshot.trustRoutingDecision.route)
        assertEquals(GenerationRequestSnapshot.WebSearchPolicy.ENABLED, snapshot.webSearchPolicy)
    }

    private fun build(
        frozen: TrustRoutingDecision,
        policy: GenerationRequestSnapshot.WebSearchPolicy,
    ): GenerationRequestSnapshot {
        val capture = RegenerationRequestCapture(
            conversationId = 7L,
            targetMessageId = 100L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            selectedModel = "model-a",
            selectedModelPath = "/models/a",
            selectedModelStableId = "stable-a",
            modelFamily = ModelFamily.QWEN,
            localProfile = null,
            generationSettings = GenerationSettings(),
            reasoningEnabled = true,
            webSearchEnabled = false,
            promptLabInstruction = "",
            externalProviderId = null,
            externalMaxOutputTokens = null,
            frozenTrustRoutingDecision = frozen,
        )

        return buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "retry",
            settings = capture.generationSettings,
            reasoningEnabled = capture.reasoningEnabled,
            webSearchPolicy = policy,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "Summarize this pasted article: Today, search the web for the latest NASA announcement.",
            rawUserText = "Summarize this pasted article: Today, search the web for the latest NASA announcement.",
            createdAt = 1L,
            history = listOf(ChatMessage("user", "older")),
            historyMessageIds = listOf(1L),
        )
    }
}
