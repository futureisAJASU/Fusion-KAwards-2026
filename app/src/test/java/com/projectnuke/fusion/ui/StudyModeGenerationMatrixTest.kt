package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.chat.FinalPromptBudget
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.LocalPromptPreparation
import com.projectnuke.fusion.chat.PromptTokenEstimator
import com.projectnuke.fusion.chat.StudyModePromptPolicy
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRouter
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.trust.TrustRoutingRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Study Mode production-path matrix (Phase 2).
 *
 * The frozen [GenerationRequestSnapshot.interactionMode] is the single
 * carrier from the request boundary to the model-facing system prompt.
 * Trust routing, retrieval selection, egress arming and internal
 * maintenance generations never observe it.
 */
class StudyModeGenerationMatrixTest {

    private fun mainSnapshot(mode: ChatInteractionMode): GenerationRequestSnapshot =
        GenerationRequestSnapshot(
            requestId = "main-$mode",
            conversationId = 7L,
            generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
            selectedModelId = "model-a",
            selectedModelPath = null,
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "광합성이 뭐야?",
            rawUserText = "광합성이 뭐야?",
            createdAt = 1L,
            interactionMode = mode,
        )

    /** The exact host instruction production inserts for a frozen request. */
    private fun instructionFor(snapshot: GenerationRequestSnapshot): String =
        StudyModePromptPolicy.modelInstruction(snapshot.interactionMode)

    @Test
    fun `O local main NORMAL carries zero Study instruction`() {
        assertEquals("", instructionFor(mainSnapshot(ChatInteractionMode.NORMAL)))
    }

    @Test
    fun `P local main STUDY carries the bounded instruction once`() {
        val instruction = instructionFor(mainSnapshot(ChatInteractionMode.STUDY))

        assertTrue(instruction.contains("현재 요청은 학습 모드입니다."))
        assertEquals(1, occurrences(instruction, "현재 요청은 학습 모드입니다."))
        assertTrue(instruction.length <= StudyModePromptPolicy.MAX_INSTRUCTION_CHARS)
    }

    @Test
    fun `Q local retry STUDY propagates through the regeneration builder`() {
        val capture = RegenerationRequestCapture(
            conversationId = 7L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            selectedModel = "model-a",
            selectedModelPath = null,
            selectedModelStableId = "A",
            modelFamily = ModelFamily.QWEN,
            localProfile = null,
            generationSettings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchEnabled = false,
            promptLabInstruction = "",
            externalProviderId = null,
            externalMaxOutputTokens = null,
            interactionMode = ChatInteractionMode.STUDY,
        )
        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "retry-study",
            settings = capture.generationSettings,
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = "retry", rawUserText = "retry", createdAt = 1L,
            history = emptyList(), historyMessageIds = emptyList(),
        )

        assertEquals(ChatInteractionMode.STUDY, snapshot.interactionMode)
        assertTrue(instructionFor(snapshot).contains("현재 요청은 학습 모드입니다."))
    }

    @Test
    fun `R local style STUDY propagates through the regeneration builder`() {
        val capture = RegenerationRequestCapture(
            conversationId = 7L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            selectedModel = "model-a",
            selectedModelPath = null,
            selectedModelStableId = "A",
            modelFamily = ModelFamily.QWEN,
            localProfile = null,
            generationSettings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchEnabled = false,
            promptLabInstruction = "",
            externalProviderId = null,
            externalMaxOutputTokens = null,
            interactionMode = ChatInteractionMode.STUDY,
        )
        // Style regeneration is a user-facing answer generation: same
        // builder contract as Retry, web-search disabled.
        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "style-study",
            settings = capture.generationSettings,
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = "style", rawUserText = "style", createdAt = 1L,
            history = emptyList(), historyMessageIds = emptyList(),
            trustRoutingDecision = TrustRoutingDecision.local(TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE),
        )

        assertEquals(ChatInteractionMode.STUDY, snapshot.interactionMode)
        assertTrue(instructionFor(snapshot).contains("현재 요청은 학습 모드입니다."))
    }

    @Test
    fun `S external main STUDY carries the generic host instruction`() {
        val provider = AiProviderConfig(
            id = "provider-a",
            type = AiProviderType.OPENAI,
            displayName = "Provider A",
            baseUrl = "https://a.example.com",
            modelId = "a-model",
            apiKeySecretId = "a-key",
            maxTokens = 512,
        )
        val snapshot = mainSnapshot(ChatInteractionMode.STUDY).copy(
            requestId = "external-main-study",
            generationModeKey = ChatGenerationMode.EXTERNAL_AI_API.name,
            externalProviderId = provider.id,
            externalProviderSelectionFrozen = true,
            externalMaxOutputTokens = 512,
            externalProviderRequest = ExternalProviderRequestSnapshot.from(provider, 512),
        )
        val instruction = instructionFor(snapshot)

        assertTrue(instruction.contains("현재 요청은 학습 모드입니다."))
        // Generic pedagogy only: identical for every user/request, so the
        // exact egress payload needs no extra consent for this section.
        assertEquals(
            StudyModePromptPolicy.modelInstruction(ChatInteractionMode.STUDY),
            instruction,
        )
        assertFalse(instruction.contains(snapshot.rawUserText))
    }

    @Test
    fun `T external style regenerate STUDY propagates`() {
        val provider = AiProviderConfig(
            id = "provider-a",
            type = AiProviderType.OPENAI,
            displayName = "Provider A",
            baseUrl = "https://a.example.com",
            modelId = "a-model",
            apiKeySecretId = "a-key",
            maxTokens = 512,
        )
        val capture = RegenerationRequestCapture(
            conversationId = 7L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.EXTERNAL_AI_API,
            selectedModel = "unused local display",
            selectedModelPath = null,
            selectedModelStableId = null,
            modelFamily = null,
            localProfile = null,
            generationSettings = GenerationSettings(maxTokens = 32000),
            reasoningEnabled = false,
            webSearchEnabled = false,
            promptLabInstruction = "",
            externalProviderId = provider.id,
            externalMaxOutputTokens = 512,
            externalProviderRequest = ExternalProviderRequestSnapshot.from(provider, 512),
            interactionMode = ChatInteractionMode.STUDY,
        )
        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "external-style-study",
            settings = capture.generationSettings,
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = "style", rawUserText = "style", createdAt = 1L,
            history = emptyList(), historyMessageIds = emptyList(),
            trustRoutingDecision = TrustRoutingDecision.local(TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE),
        )

        assertEquals(ChatInteractionMode.STUDY, snapshot.interactionMode)
        assertTrue(instructionFor(snapshot).contains("현재 요청은 학습 모드입니다."))
    }

    @Test
    fun `U cloud escalation preserves the frozen STUDY mode`() {
        val provider = AiProviderConfig(
            id = "provider-a",
            type = AiProviderType.OPENAI,
            displayName = "Provider A",
            baseUrl = "https://a.example.com",
            modelId = "a-model",
            apiKeySecretId = "a-key",
            maxTokens = 512,
        )
        val escalated = buildCloudEscalationSnapshot(
            conversationId = 7L,
            requestId = "escalation-study",
            providerId = provider.id,
            providerRequestSnapshot = ExternalProviderRequestSnapshot.from(provider, 512),
            generationSettings = GenerationSettings(),
            reasoningEnabled = false,
            promptLabInstruction = "",
            history = emptyList(),
            historyMessageIds = emptyList(),
            promptText = "escalated",
            rawUserText = "escalated",
            createdAt = 1L,
            trustRoutingDecision = TrustRoutingDecision(
                route = TrustRoute.CLOUD_SUGGEST,
                reason = TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD,
                signals = emptySet(),
            ),
            interactionMode = ChatInteractionMode.STUDY,
        )

        assertEquals(ChatInteractionMode.STUDY, escalated.interactionMode)
        assertTrue(instructionFor(escalated).contains("현재 요청은 학습 모드입니다."))
    }

    @Test
    fun `U2 cloud escalation defaults to NORMAL`() {
        val provider = AiProviderConfig(
            id = "provider-a",
            type = AiProviderType.OPENAI,
            displayName = "Provider A",
            baseUrl = "https://a.example.com",
            modelId = "a-model",
            apiKeySecretId = "a-key",
            maxTokens = 512,
        )
        val escalated = buildCloudEscalationSnapshot(
            conversationId = 7L,
            requestId = "escalation-default",
            providerId = provider.id,
            providerRequestSnapshot = ExternalProviderRequestSnapshot.from(provider, 512),
            generationSettings = GenerationSettings(),
            reasoningEnabled = false,
            promptLabInstruction = "",
            history = emptyList(),
            historyMessageIds = emptyList(),
            promptText = "escalated",
            rawUserText = "escalated",
            createdAt = 1L,
            trustRoutingDecision = TrustRoutingDecision(
                route = TrustRoute.CLOUD_SUGGEST,
                reason = TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD,
                signals = emptySet(),
            ),
        )

        assertEquals(ChatInteractionMode.NORMAL, escalated.interactionMode)
        assertEquals("", instructionFor(escalated))
    }

    @Test
    fun `V frozen SEARCH retry preserves its frozen interaction mode`() {
        val frozen = FrozenSearchRetry.from(mainSnapshot(ChatInteractionMode.STUDY), 99L)
        assertEquals(ChatInteractionMode.STUDY, frozen.interactionMode)

        val capture = buildFrozenSearchRetryCapture(frozen, 100L, ResponseVersionState())
        assertEquals(ChatInteractionMode.STUDY, capture.interactionMode)

        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "frozen-search-retry",
            settings = capture.generationSettings,
            reasoningEnabled = capture.reasoningEnabled,
            webSearchPolicy = frozen.webSearchPolicy,
            attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = frozen.promptText, rawUserText = frozen.rawUserText, createdAt = 1L,
            history = emptyList(), historyMessageIds = emptyList(),
            trustRoutingDecision = frozen.trustRoutingDecision,
        )
        assertEquals(ChatInteractionMode.STUDY, snapshot.interactionMode)

        // Legacy/absent data fails closed to NORMAL.
        assertEquals(
            ChatInteractionMode.NORMAL,
            FrozenSearchRetry(
                failureMessageId = 1L,
                requestId = "legacy",
                conversationId = 7L,
                generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                selectedModelId = null,
                selectedModelPath = null,
                selectedModelStableId = null,
                modelFamily = null,
                settings = GenerationSettings(),
                reasoningEnabled = false,
                externalProviderId = null,
                externalMaxOutputTokens = null,
                externalProviderRequest = null,
                compactPolicy = com.projectnuke.fusion.model.LocalCompactPolicy(),
                contextMode = com.projectnuke.fusion.model.LocalContextMode.AUTO,
                promptLabInstruction = "",
                trustRoutingDecision = TrustRoutingDecision(
                    route = TrustRoute.SEARCH,
                    reason = TrustRoutingReason.HIGH_COMPLEXITY_OPTIONAL_CLOUD,
                    signals = emptySet(),
                ),
                attachmentIds = emptyList(),
                multimodalImagePaths = emptyList(),
                rawUserText = "legacy",
                promptText = "legacy",
                historyMessageIds = emptyList(),
            ).interactionMode,
        )
    }

    @Test
    fun `W X internal maintenance snapshots stay NORMAL`() {
        // Manual compaction and memory-candidate extraction build default
        // snapshots and never set the mode: user-facing STUDY must not leak
        // into maintenance generations.
        assertEquals(
            ChatInteractionMode.NORMAL,
            GenerationRequestSnapshot(
                requestId = "maintenance",
                conversationId = 7L,
                generationModeKey = ChatGenerationMode.LOCAL_MODEL.name,
                selectedModelId = "model-a",
                selectedModelPath = null,
                settings = GenerationSettings(),
                reasoningEnabled = false,
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
                attachmentIds = emptyList(),
                multimodalImagePaths = emptyList(),
                promptText = "maintenance",
                rawUserText = "maintenance",
                createdAt = 1L,
            ).interactionMode,
        )
    }

    @Test
    fun `trust routing inputs carry no interaction mode`() {
        val fields = TrustRoutingRequest::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("interactionMode"))

        val searchText = "현재 대통령은 누구야?"
        val normalRoute = TrustRouter.route(TrustRoutingRequest(userText = searchText)).route
        // Identical raw request under STUDY classifies identically: the
        // Study instruction enters only model-facing assembly after routing.
        assertEquals(TrustRoute.SEARCH, normalRoute)
    }

    @Test
    fun `Y STUDY instruction fits through the ordinary prompt budget`() {
        val instruction = StudyModePromptPolicy.modelInstruction(ChatInteractionMode.STUDY)
        ModelFamily.entries.forEach { family ->
            val raw = listOf(
                ChatMessage("system", "FUSION_GENERATION_SETTINGS\nmaxTokens=256"),
                ChatMessage("system", "Fusion base system prompt"),
                ChatMessage("system", instruction),
                ChatMessage("system", "FUSION_SELECTED_MODEL_PATH=/models/$family"),
                ChatMessage("system", "FUSION_MODEL_FAMILY=${family.name}"),
                ChatMessage("system", "[FUSION_HISTORICAL_MEMORY_POLICY]\nold preference"),
                ChatMessage("assistant", "history"),
                ChatMessage("user", "current user request"),
            )
            val prepared = checkNotNull(
                LocalPromptPreparation.prepare(
                    rawMessages = raw,
                    budget = FinalPromptBudget(
                        modelId = "study-$family",
                        external = false,
                        maxOutputTokens = 256,
                        engineKvCapacityTokens = 8192,
                    ),
                    settings = GenerationSettings(maxTokens = 256, kvCacheCapacityTokens = 8192),
                ),
            )
            val modelReady = FusionPromptAdapters.prepareMessagesForModel(prepared.messages)
            val adapted = FusionPromptAdapters.forFamily(modelReady.modelFamily).buildMessages(modelReady.messages)

            assertTrue(adapted.any { it.content.contains("현재 요청은 학습 모드입니다.") })
            assertTrue(adapted.any { it.role == "user" && it.content == "current user request" })
            assertTrue(
                PromptTokenEstimator.estimateTokens(adapted) + prepared.appliedOutputTokens + 32 <= 8192,
            )
        }
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }
}
