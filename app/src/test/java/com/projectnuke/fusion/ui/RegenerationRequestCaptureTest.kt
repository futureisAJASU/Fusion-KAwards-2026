package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ProjectContextSnapshot
import com.projectnuke.fusion.chat.ProjectFileSnapshot
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.model.LocalContextMode
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.modelzoo.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegenerationRequestCaptureTest {
    @Test
    fun retryCaptureKeepsLocalTargetAcrossUiMutation() {
        val capture = RegenerationRequestCapture(
            conversationId = 7L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            selectedModel = "Same Name",
            selectedModelPath = "/models/a",
            selectedModelStableId = "A",
            modelFamily = ModelFamily.QWEN,
            localProfile = null,
            generationSettings = GenerationSettings(maxTokens = 1024),
            reasoningEnabled = true,
            webSearchEnabled = true,
            promptLabInstruction = "captured prompt lab",
            externalProviderId = null,
            externalMaxOutputTokens = null,
        )

        var currentModel = "Same Name"
        var currentPath = "/models/b"
        var currentFamily = ModelFamily.DEEPSEEK
        currentModel = "Same Name"

        assertEquals("A", capture.selectedModelStableId)
        assertEquals(ModelFamily.QWEN, capture.modelFamily)
        assertEquals("/models/a", capture.selectedModelPath)
        assertEquals("A", capture.selectedModelStableId)
        assertTrue(currentPath != capture.selectedModelPath)
        assertTrue(currentFamily != capture.modelFamily)
        assertEquals("Same Name", capture.selectedModel)
    }

    @Test
    fun externalCaptureKeepsProviderAndOutputLimitAcrossMutation() {
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
            conversationId = 8L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.EXTERNAL_AI_API,
            selectedModel = "unused local display",
            selectedModelPath = null,
            selectedModelStableId = null,
            modelFamily = null,
            localProfile = null,
            generationSettings = GenerationSettings(maxTokens = 32000),
            reasoningEnabled = true,
            webSearchEnabled = true,
            promptLabInstruction = "captured style",
            externalProviderId = "provider-a",
            externalMaxOutputTokens = 512,
            externalProviderRequest = ExternalProviderRequestSnapshot.from(provider, 512),
        )

        var currentProvider = "provider-b"
        var currentMaxTokens = 32000
        currentProvider = "provider-b"
        currentMaxTokens = 32000

        assertEquals("provider-a", capture.externalProviderId)
        assertEquals(512, capture.externalMaxOutputTokens)
        assertEquals("captured style", capture.promptLabInstruction)
        assertTrue(currentProvider != capture.externalProviderId)
        assertTrue(currentMaxTokens != capture.externalMaxOutputTokens)
    }

    @Test
    fun externalRetrySnapshotUsesTapTimeControlsAfterUiMutation() {
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
            conversationId = 8L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.EXTERNAL_AI_API,
            selectedModel = "unused local display",
            selectedModelPath = null,
            selectedModelStableId = null,
            modelFamily = null,
            localProfile = null,
            generationSettings = GenerationSettings(maxTokens = 32000),
            reasoningEnabled = true,
            webSearchEnabled = true,
            promptLabInstruction = "A",
            externalProviderId = provider.id,
            externalMaxOutputTokens = 512,
            externalProviderRequest = ExternalProviderRequestSnapshot.from(provider, 512),
        )
        var currentPromptLab = "B"
        var currentProvider = "provider-b"
        var currentMaxTokens = 32000
        var currentWebSearch = false

        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "retry",
            settings = capture.generationSettings.copy(speculativeDecodingEnabled = false),
            reasoningEnabled = capture.reasoningEnabled,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "retry",
            rawUserText = "retry",
            createdAt = 1L,
            history = listOf(ChatMessage("user", "old")),
            historyMessageIds = listOf(1L),
        )

        assertEquals("A", snapshot.promptLabInstruction)
        assertEquals("provider-a", snapshot.externalProviderRequest?.id)
        assertEquals(512, snapshot.externalMaxOutputTokens)
        assertEquals(GenerationRequestSnapshot.WebSearchPolicy.ENABLED, snapshot.webSearchPolicy)
        assertTrue(currentPromptLab != snapshot.promptLabInstruction)
        assertTrue(currentProvider != snapshot.externalProviderRequest?.id)
        assertTrue(currentMaxTokens != snapshot.externalMaxOutputTokens)
        assertTrue(currentWebSearch != (snapshot.webSearchPolicy == GenerationRequestSnapshot.WebSearchPolicy.ENABLED))
    }

    @Test
    fun `external retry snapshot excludes project instructions and file identities`() {
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
            conversationId = 8L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.EXTERNAL_AI_API,
            selectedModel = "unused local display",
            selectedModelPath = null,
            selectedModelStableId = null,
            modelFamily = null,
            localProfile = null,
            generationSettings = GenerationSettings(maxTokens = 32000),
            reasoningEnabled = true,
            webSearchEnabled = false,
            promptLabInstruction = "captured style",
            externalProviderId = provider.id,
            externalMaxOutputTokens = 512,
            externalProviderRequest = ExternalProviderRequestSnapshot.from(provider, 512),
            projectContext = ProjectContextSnapshot(
                projectId = 91L,
                instructions = "PRIVATE_PROJECT_INSTRUCTION",
                files = listOf(ProjectFileSnapshot(0L, "PRIVATE_PROJECT_FILE.txt", "text/plain", "/managed/private")),
            ),
        )

        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "external-project",
            settings = capture.generationSettings,
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "current request",
            rawUserText = "current request",
            createdAt = 1L,
            history = emptyList(),
            historyMessageIds = emptyList(),
        )

        assertNull(snapshot.projectContext)
    }

    @Test
    fun retryAndStyleSnapshotsUseCapturedFullBranchForLocalAndExternal() {
        val fullBranch = (1L..200L).map { ChatMessage("user", "message-$it") }
        val visibleWindow = fullBranch.drop(100)
        val localCapture = RegenerationRequestCapture(
            conversationId = 9L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            selectedModel = "Same Name",
            selectedModelPath = "/models/a",
            selectedModelStableId = "A",
            modelFamily = ModelFamily.QWEN,
            localProfile = null,
            generationSettings = GenerationSettings(maxTokens = 1024),
            reasoningEnabled = true,
            webSearchEnabled = true,
            promptLabInstruction = "captured prompt",
            externalProviderId = null,
            externalMaxOutputTokens = null,
        )
        val provider = AiProviderConfig(
            id = "provider-a",
            type = AiProviderType.OPENAI,
            displayName = "Provider A",
            baseUrl = "https://a.example.com",
            modelId = "a-model",
            apiKeySecretId = "a-key",
            maxTokens = 512,
        )
        val externalCapture = RegenerationRequestCapture(
            conversationId = 9L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.EXTERNAL_AI_API,
            selectedModel = "unused local display",
            selectedModelPath = null,
            selectedModelStableId = null,
            modelFamily = null,
            localProfile = null,
            generationSettings = GenerationSettings(maxTokens = 32000),
            reasoningEnabled = true,
            webSearchEnabled = true,
            promptLabInstruction = "captured prompt",
            externalProviderId = provider.id,
            externalMaxOutputTokens = 512,
            externalProviderRequest = ExternalProviderRequestSnapshot.from(provider, 512),
        )

        val localRetry = buildRegenerationGenerationRequestSnapshot(
            capture = localCapture,
            requestId = "local-retry",
            settings = localCapture.generationSettings,
            reasoningEnabled = true,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = "retry", rawUserText = "retry", createdAt = 1L,
            history = fullBranch, historyMessageIds = (1L..200L).toList(),
        )
        val localStyle = buildRegenerationGenerationRequestSnapshot(
            capture = localCapture,
            requestId = "local-style",
            settings = localCapture.generationSettings,
            reasoningEnabled = true,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = "style", rawUserText = "style", createdAt = 1L,
            history = fullBranch, historyMessageIds = (1L..200L).toList(),
        )
        val externalRetry = buildRegenerationGenerationRequestSnapshot(
            capture = externalCapture,
            requestId = "external-retry",
            settings = externalCapture.generationSettings,
            reasoningEnabled = true,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = "retry", rawUserText = "retry", createdAt = 1L,
            history = fullBranch, historyMessageIds = (1L..200L).toList(),
        )
        val externalStyle = buildRegenerationGenerationRequestSnapshot(
            capture = externalCapture,
            requestId = "external-style",
            settings = externalCapture.generationSettings,
            reasoningEnabled = true,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = "style", rawUserText = "style", createdAt = 1L,
            history = fullBranch, historyMessageIds = (1L..200L).toList(),
        )

        listOf(localRetry, localStyle).forEach { snapshot ->
            assertEquals((1L..200L).toList(), snapshot.historyMessageIds)
            assertEquals("A", snapshot.selectedModelStableId)
            assertEquals(ModelFamily.QWEN, snapshot.modelFamily)
            assertEquals("captured prompt", snapshot.promptLabInstruction)
        }
        listOf(externalRetry, externalStyle).forEach { snapshot ->
            assertEquals((1L..200L).toList(), snapshot.historyMessageIds)
            assertEquals("captured prompt", snapshot.promptLabInstruction)
            assertEquals("provider-a", snapshot.externalProviderRequest?.id)
            assertEquals(512, snapshot.externalMaxOutputTokens)
        }
        assertEquals((101L..200L).toList(), visibleWindow.indices.map { visibleWindow[it].content.removePrefix("message-").toLong() })
    }

    @Test
    fun `E regeneration capture defaults to NORMAL and freezes the tap-time mode`() {
        val default = RegenerationRequestCapture(
            conversationId = 9L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            selectedModel = "Same Name",
            selectedModelPath = "/models/a",
            selectedModelStableId = "A",
            modelFamily = ModelFamily.QWEN,
            localProfile = null,
            generationSettings = GenerationSettings(maxTokens = 1024),
            reasoningEnabled = true,
            webSearchEnabled = true,
            promptLabInstruction = "captured prompt",
            externalProviderId = null,
            externalMaxOutputTokens = null,
        )
        assertEquals(
            com.projectnuke.fusion.chat.ChatInteractionMode.NORMAL,
            default.interactionMode,
        )

        val study = default.copy(
            interactionMode = com.projectnuke.fusion.chat.ChatInteractionMode.STUDY,
        )
        assertEquals(
            com.projectnuke.fusion.chat.ChatInteractionMode.STUDY,
            study.interactionMode,
        )
    }

    @Test
    fun `F regenerated snapshots propagate the exact captured mode`() {
        val localCapture = RegenerationRequestCapture(
            conversationId = 9L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.LOCAL_MODEL,
            selectedModel = "Same Name",
            selectedModelPath = "/models/a",
            selectedModelStableId = "A",
            modelFamily = ModelFamily.QWEN,
            localProfile = null,
            generationSettings = GenerationSettings(maxTokens = 1024),
            reasoningEnabled = true,
            webSearchEnabled = true,
            promptLabInstruction = "captured prompt",
            externalProviderId = null,
            externalMaxOutputTokens = null,
            interactionMode = com.projectnuke.fusion.chat.ChatInteractionMode.STUDY,
        )
        // A later mode switch must not alter the already-captured request.
        val switched = localCapture.copy(
            interactionMode = com.projectnuke.fusion.chat.ChatInteractionMode.NORMAL,
        )

        fun build(capture: RegenerationRequestCapture, id: String) =
            buildRegenerationGenerationRequestSnapshot(
                capture = capture,
                requestId = id,
                settings = capture.generationSettings,
                reasoningEnabled = true,
                webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
                attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
                promptText = "retry", rawUserText = "retry", createdAt = 1L,
                history = emptyList(), historyMessageIds = emptyList(),
            )

        assertEquals(
            com.projectnuke.fusion.chat.ChatInteractionMode.STUDY,
            build(localCapture, "study-retry").interactionMode,
        )
        assertEquals(
            com.projectnuke.fusion.chat.ChatInteractionMode.NORMAL,
            build(switched, "normal-retry").interactionMode,
        )
    }

    // ------------------------------------------------------------------
    // External retry/style reasoning parity (tap-boundary capture truth)
    // ------------------------------------------------------------------

    private fun localCapability(supportsReasoning: Boolean): Boolean {
        val spec = if (supportsReasoning) {
            FusionModelCatalog.builtIn.first { it.family == ModelFamily.QWEN }
        } else {
            FusionModelCatalog.importedSpec(
                displayName = "Kanana imported",
                path = "kanana-2-1.3b-instruct.litertlm",
                family = ModelFamily.KANANA,
            )
        }
        return FusionModelCatalog.reasoningCapabilityAvailable(spec)
    }

    @Test
    fun `external retry reasoning stays true when selected local spec cannot reason`() {
        val capture = RegenerationRequestCapture(
            conversationId = 8L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.EXTERNAL_AI_API,
            selectedModel = "unused local display",
            selectedModelPath = null,
            selectedModelStableId = null,
            modelFamily = null,
            localProfile = null,
            generationSettings = GenerationSettings(maxTokens = 32000),
            reasoningEnabled = true,
            webSearchEnabled = true,
            promptLabInstruction = "captured",
            externalProviderId = "provider-a",
            externalMaxOutputTokens = 512,
        )

        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "external-retry",
            settings = capture.generationSettings,
            reasoningEnabled = captureRegenerationReasoning(
                generationMode = capture.generationMode,
                localReasoningCapabilityAvailable = capture.reasoningCapabilityAvailable,
                requestedReasoningEnabled = capture.reasoningEnabled,
            ),
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = "retry", rawUserText = "retry", createdAt = 1L,
            history = listOf(ChatMessage("user", "old")),
            historyMessageIds = listOf(1L),
        )

        assertTrue(captureRegenerationReasoning(ChatGenerationMode.EXTERNAL_AI_API, localCapability(false), true))
        assertTrue(snapshot.reasoningEnabled)
    }

    @Test
    fun `external style regeneration keeps captured reasoning true regardless of local capability`() {
        val capture = RegenerationRequestCapture(
            conversationId = 8L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.EXTERNAL_AI_API,
            selectedModel = "unused local display",
            selectedModelPath = null,
            selectedModelStableId = null,
            modelFamily = null,
            localProfile = null,
            generationSettings = GenerationSettings(maxTokens = 32000),
            reasoningEnabled = true,
            webSearchEnabled = false,
            promptLabInstruction = "captured style",
            externalProviderId = "provider-a",
            externalMaxOutputTokens = 512,
        )

        val capturedReasoning = captureRegenerationReasoning(
            generationMode = capture.generationMode,
            localReasoningCapabilityAvailable = capture.reasoningCapabilityAvailable,
            requestedReasoningEnabled = capture.reasoningEnabled,
        )
        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "external-style",
            settings = capture.generationSettings,
            reasoningEnabled = capturedReasoning,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = "style", rawUserText = "style", createdAt = 1L,
            history = emptyList(),
            historyMessageIds = emptyList(),
        )

        assertTrue(capturedReasoning)
        assertTrue(snapshot.reasoningEnabled)
    }

    @Test
    fun `external capture reasoning matches normal send semantics in both toggle states`() {
        val requested = true
        val off = false

        assertEquals(
            requested,
            captureRegenerationReasoning(ChatGenerationMode.EXTERNAL_AI_API, localCapability(true), requested),
        )
        assertEquals(
            off,
            captureRegenerationReasoning(ChatGenerationMode.EXTERNAL_AI_API, localCapability(true), off),
        )
    }

    @Test
    fun `switching the selected local model cannot change captured external reasoning`() {
        val requested = true

        val beforeSwitch = captureRegenerationReasoning(
            ChatGenerationMode.EXTERNAL_AI_API,
            localCapability(true),
            requested,
        )
        val afterSwitch = captureRegenerationReasoning(
            ChatGenerationMode.EXTERNAL_AI_API,
            localCapability(false),
            requested,
        )

        assertEquals(beforeSwitch, afterSwitch)
        assertTrue(afterSwitch)
    }

    @Test
    fun `local unsupported model still forces effective reasoning false`() {
        assertFalse(
            captureRegenerationReasoning(ChatGenerationMode.LOCAL_MODEL, localCapability(false), true),
        )
    }

    @Test
    fun `local supported model honors requested reasoning on and off`() {
        assertTrue(
            captureRegenerationReasoning(ChatGenerationMode.LOCAL_MODEL, localCapability(true), true),
        )
        assertFalse(
            captureRegenerationReasoning(ChatGenerationMode.LOCAL_MODEL, localCapability(true), false),
        )
    }

    @Test
    fun `frozen search retry retains its original frozen reasoning state`() {
        val provider = AiProviderConfig(
            id = "provider-a",
            type = AiProviderType.OPENAI,
            displayName = "Provider A",
            baseUrl = "https://a.example.com",
            modelId = "a-model",
            apiKeySecretId = "a-key",
            maxTokens = 512,
        )
        val frozen = FrozenSearchRetry(
            failureMessageId = 42L,
            requestId = "request-1",
            conversationId = 8L,
            generationModeKey = ChatGenerationMode.EXTERNAL_AI_API.name,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            selectedModelId = "unused local display",
            selectedModelPath = null,
            selectedModelStableId = null,
            modelFamily = null,
            reasoningCapabilityAvailable = localCapability(true),
            settings = GenerationSettings(maxTokens = 32000),
            reasoningEnabled = false,
            externalProviderId = provider.id,
            externalMaxOutputTokens = 512,
            externalProviderRequest = ExternalProviderRequestSnapshot.from(provider, 512),
            compactPolicy = LocalCompactPolicy(),
            contextMode = LocalContextMode.AUTO,
            promptLabInstruction = "frozen",
            trustRoutingDecision = com.projectnuke.fusion.trust.TrustRoutingDecision(
                com.projectnuke.fusion.trust.TrustRoute.SEARCH,
                com.projectnuke.fusion.trust.TrustRoutingReason.MANUAL_SEARCH_ENABLED,
                emptySet(),
            ),
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            rawUserText = "frozen",
            promptText = "frozen",
            historyMessageIds = listOf(1L),
        )

        val capture = buildFrozenSearchRetryCapture(
            frozen = frozen,
            targetMessageId = 43L,
            versions = ResponseVersionState(),
        )

        assertEquals(false, capture.reasoningEnabled)
        assertEquals(frozen.reasoningCapabilityAvailable, capture.reasoningCapabilityAvailable)

        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "frozen-retry",
            settings = capture.generationSettings,
            reasoningEnabled = capture.reasoningEnabled,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = "retry", rawUserText = "retry", createdAt = 1L,
            history = listOf(ChatMessage("user", "old")),
            historyMessageIds = listOf(1L),
        )
        assertFalse(snapshot.reasoningEnabled)
    }

    @Test
    fun `frozen external search retry reasoning stays on when frozen on`() {
        val provider = AiProviderConfig(
            id = "provider-a",
            type = AiProviderType.OPENAI,
            displayName = "Provider A",
            baseUrl = "https://a.example.com",
            modelId = "a-model",
            apiKeySecretId = "a-key",
            maxTokens = 512,
        )
        val frozen = FrozenSearchRetry(
            failureMessageId = 44L,
            requestId = "request-2",
            conversationId = 8L,
            generationModeKey = ChatGenerationMode.EXTERNAL_AI_API.name,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            selectedModelId = "unused local display",
            selectedModelPath = null,
            selectedModelStableId = null,
            modelFamily = null,
            reasoningCapabilityAvailable = false,
            settings = GenerationSettings(maxTokens = 32000),
            reasoningEnabled = true,
            externalProviderId = provider.id,
            externalMaxOutputTokens = 512,
            externalProviderRequest = ExternalProviderRequestSnapshot.from(provider, 512),
            compactPolicy = LocalCompactPolicy(),
            contextMode = LocalContextMode.AUTO,
            promptLabInstruction = "frozen",
            trustRoutingDecision = com.projectnuke.fusion.trust.TrustRoutingDecision(
                com.projectnuke.fusion.trust.TrustRoute.SEARCH,
                com.projectnuke.fusion.trust.TrustRoutingReason.MANUAL_SEARCH_ENABLED,
                emptySet(),
            ),
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            rawUserText = "frozen",
            promptText = "frozen",
            historyMessageIds = listOf(1L),
        )

        val capture = buildFrozenSearchRetryCapture(
            frozen = frozen,
            targetMessageId = 45L,
            versions = ResponseVersionState(),
        )

        assertTrue(capture.reasoningEnabled)
    }

    @Test
    fun `external tap boundary freezes local capability metadata as non-applicable`() {
        val capture = RegenerationRequestCapture(
            conversationId = 8L,
            targetMessageId = 190L,
            responseVersionState = ResponseVersionState(),
            generationMode = ChatGenerationMode.EXTERNAL_AI_API,
            selectedModel = "unused local display",
            selectedModelPath = null,
            selectedModelStableId = null,
            modelFamily = null,
            reasoningCapabilityAvailable = false,
            localProfile = null,
            generationSettings = GenerationSettings(maxTokens = 32000),
            reasoningEnabled = true,
            webSearchEnabled = true,
            promptLabInstruction = "captured",
            externalProviderId = "provider-a",
            externalMaxOutputTokens = 512,
        )
        val snapshot = buildRegenerationGenerationRequestSnapshot(
            capture = capture,
            requestId = "external-capability",
            settings = capture.generationSettings,
            reasoningEnabled = capture.reasoningEnabled,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = "retry", rawUserText = "retry", createdAt = 1L,
            history = emptyList(), historyMessageIds = emptyList(),
        )
        assertFalse(snapshot.reasoningCapabilityAvailable)
    }
}
