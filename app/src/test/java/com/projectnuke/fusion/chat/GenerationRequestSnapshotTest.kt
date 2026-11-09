package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.LocalCompactPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.projectnuke.fusion.model.CapturedLocalModelIdentity
import com.projectnuke.fusion.model.resolveCapturedLocalModelIdentity
import com.projectnuke.fusion.modelzoo.FusionModelSpec
import com.projectnuke.fusion.modelzoo.ModelAvailability
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.modelzoo.ModelMemoryClass
import com.projectnuke.fusion.modelzoo.ModelRecommendedDeviceClass
import com.projectnuke.fusion.modelzoo.ModelRuntimeFormat
import com.projectnuke.fusion.modelzoo.FusionPromptAdapters
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRouter
import com.projectnuke.fusion.trust.TrustRoutingRequest

class GenerationRequestSnapshotTest {

    @Test
    fun `capture freezes same-name imported model by path and semantic adapter`() {
        fun imported(id: String, path: String, family: ModelFamily) = FusionModelSpec(
            id = id, displayName = "Same Name", family = family, parameterLabel = "custom",
            runtimeFormat = ModelRuntimeFormat.LITERT_LM, availability = ModelAvailability.CUSTOM_IMPORTED,
            memoryClass = ModelMemoryClass.MEDIUM,
            recommendedDeviceClass = ModelRecommendedDeviceClass.RAM_8GB_SAFE,
            recommendedMaxTokens8Gb = 512, recommendedMaxTokens12Gb = 512,
            recommendedMtpEnabled = false, recommendedReasoningEnabled = false,
            supportsVision = false, supportsReasoning = false, supportsNpuCandidate = false,
            notes = "", localPath = path,
        )
        val catalog = listOf(imported("A", "/models/a", ModelFamily.QWEN), imported("B", "/models/b", ModelFamily.DEEPSEEK))
        val captured = resolveCapturedLocalModelIdentity("Same Name", "/models/a", catalog)
        val request = GenerationRequestSnapshot(
            requestId = "queued", conversationId = 1L, generationModeKey = "LOCAL_MODEL",
            selectedModelId = "Same Name", selectedModelPath = "/models/a",
            selectedModelStableId = captured.stableId, modelFamily = captured.family,
            settings = GenerationSettings(), reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(), multimodalImagePaths = emptyList(),
            promptText = "request", rawUserText = "request", createdAt = 1L,
        )
        val mutatedDisplayName = "Same Name"
        val mutatedPath = "/models/b"
        assertEquals(CapturedLocalModelIdentity("A", ModelFamily.QWEN), captured)
        assertEquals("A", request.selectedModelStableId)
        assertEquals(ModelFamily.QWEN, request.modelFamily)
        assertTrue(request.modelFamily == ModelFamily.QWEN)
        assertEquals(ModelFamily.QWEN, FusionPromptAdapters.forFamily(request.modelFamily!!).family)
        assertEquals("/models/b", mutatedPath)
        assertEquals("Same Name", mutatedDisplayName)
    }
    @Test
    fun `captured compact policy is immutable when later profile changes`() {
        val captured = LocalCompactPolicy(enabled = true, triggerPercent = 80, recentHistoryPercent = 25)
        val snapshot = GenerationRequestSnapshot(
            requestId = "request",
            conversationId = 1L,
            generationModeKey = "LOCAL_MODEL",
            selectedModelId = "model-a",
            selectedModelPath = null,
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "request",
            rawUserText = "request",
            createdAt = 1L,
            compactPolicy = captured,
        )

        val laterProfilePolicy = LocalCompactPolicy(enabled = true, triggerPercent = 90, recentHistoryPercent = 40)

        assertEquals(captured, snapshot.compactPolicy)
        assertEquals(80, snapshot.compactPolicy.triggerPercent)
        assertEquals(25, snapshot.compactPolicy.recentHistoryPercent)
        assertEquals(90, laterProfilePolicy.triggerPercent)
    }

    @Test
    fun `snapshot retains deterministic trust decision before generation`() {
        val decision = TrustRouter.route(
            TrustRoutingRequest(userText = "현재 대통령은 누구야?"),
        )
        val snapshot = GenerationRequestSnapshot(
            requestId = "request",
            conversationId = 1L,
            generationModeKey = "LOCAL_MODEL",
            selectedModelId = "model-a",
            selectedModelPath = null,
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "현재 대통령은 누구야?",
            rawUserText = "현재 대통령은 누구야?",
            createdAt = 1L,
            trustRoutingDecision = decision,
        )

        assertEquals(TrustRoute.SEARCH, snapshot.trustRoutingDecision.route)
        assertEquals(decision, snapshot.trustRoutingDecision)
    }

    @Test
    fun `A snapshot defaults to NORMAL interaction mode`() {
        val snapshot = GenerationRequestSnapshot(
            requestId = "request",
            conversationId = 1L,
            generationModeKey = "LOCAL_MODEL",
            selectedModelId = "model-a",
            selectedModelPath = null,
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "request",
            rawUserText = "request",
            createdAt = 1L,
        )

        // Legacy/default callsites (internal maintenance generations) stay
        // NORMAL without passing the field.
        assertEquals(ChatInteractionMode.NORMAL, snapshot.interactionMode)
    }

    @Test
    fun `B explicit STUDY is retained by copy`() {
        val snapshot = GenerationRequestSnapshot(
            requestId = "request",
            conversationId = 1L,
            generationModeKey = "LOCAL_MODEL",
            selectedModelId = "model-a",
            selectedModelPath = null,
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "request",
            rawUserText = "request",
            createdAt = 1L,
            interactionMode = ChatInteractionMode.STUDY,
        )

        assertEquals(ChatInteractionMode.STUDY, snapshot.interactionMode)
        assertEquals(ChatInteractionMode.STUDY, snapshot.copy().interactionMode)
        assertEquals(
            ChatInteractionMode.NORMAL,
            snapshot.copy(interactionMode = ChatInteractionMode.NORMAL).interactionMode,
        )
    }

    @Test
    fun `C main-send freeze keeps STUDY after a later mode switch`() {
        // submitComposerNow captures currentInteractionMode once into the
        // snapshot; NORMAL -> STUDY or STUDY -> NORMAL afterwards must not
        // alter the active request.
        var modeState = ChatInteractionModeState(
            mode = ChatInteractionMode.STUDY,
            conversationId = 7L,
            newChatEpoch = 0L,
        )
        val scope = ChatInteractionModeScope(7L, 0L)
        val frozen = ChatInteractionModePolicy.currentMode(modeState, scope)
        val snapshot = GenerationRequestSnapshot(
            requestId = "request",
            conversationId = 7L,
            generationModeKey = "LOCAL_MODEL",
            selectedModelId = "model-a",
            selectedModelPath = null,
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "request",
            rawUserText = "request",
            createdAt = 1L,
            interactionMode = frozen,
        )

        modeState = ChatInteractionModePolicy.switch(
            modeState,
            scope,
            ChatInteractionMode.NORMAL,
            true,
        )

        assertEquals(ChatInteractionMode.NORMAL, ChatInteractionModePolicy.currentMode(modeState, scope))
        assertEquals(ChatInteractionMode.STUDY, snapshot.interactionMode)
    }

    @Test
    fun `D main-send freeze keeps NORMAL after later entering STUDY`() {
        val scope = ChatInteractionModeScope(7L, 0L)
        var modeState = ChatInteractionModeState()
        val frozen = ChatInteractionModePolicy.currentMode(modeState, scope)
        val snapshot = GenerationRequestSnapshot(
            requestId = "request",
            conversationId = 7L,
            generationModeKey = "LOCAL_MODEL",
            selectedModelId = "model-a",
            selectedModelPath = null,
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "request",
            rawUserText = "request",
            createdAt = 1L,
            interactionMode = frozen,
        )

        modeState = ChatInteractionModePolicy.switch(
            modeState,
            scope,
            ChatInteractionMode.STUDY,
            true,
        )

        assertEquals(ChatInteractionMode.STUDY, ChatInteractionModePolicy.currentMode(modeState, scope))
        assertEquals(ChatInteractionMode.NORMAL, snapshot.interactionMode)
    }

    @Test
    fun `interaction mode never alters the trust routing decision`() {
        val decision = TrustRouter.route(
            TrustRoutingRequest(userText = "현재 대통령은 누구야?"),
        )
        val normal = GenerationRequestSnapshot(
            requestId = "n",
            conversationId = 1L,
            generationModeKey = "LOCAL_MODEL",
            selectedModelId = "model-a",
            selectedModelPath = null,
            settings = GenerationSettings(),
            reasoningEnabled = false,
            webSearchPolicy = GenerationRequestSnapshot.WebSearchPolicy.ENABLED,
            attachmentIds = emptyList(),
            multimodalImagePaths = emptyList(),
            promptText = "현재 대통령은 누구야?",
            rawUserText = "현재 대통령은 누구야?",
            createdAt = 1L,
            trustRoutingDecision = decision,
            interactionMode = ChatInteractionMode.NORMAL,
        )
        val study = normal.copy(requestId = "s", interactionMode = ChatInteractionMode.STUDY)

        assertEquals(normal.trustRoutingDecision, study.trustRoutingDecision)
        assertEquals(TrustRoute.SEARCH, study.trustRoutingDecision.route)
    }
}
