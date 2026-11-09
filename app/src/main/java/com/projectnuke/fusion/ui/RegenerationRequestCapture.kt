package com.projectnuke.fusion.ui

import com.projectnuke.fusion.model.EffectiveLocalModelProfile
import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.model.LocalContextMode
import com.projectnuke.fusion.model.LocalSettingsProvenance
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.chat.ProjectContextSnapshot
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.modelzoo.FusionModelCatalog
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRouter
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingRequest
import com.projectnuke.fusion.util.AttachmentMessageCodec

/**
 * Reasoning truth captured at the Retry/Style tap boundary.
 *
 * EXTERNAL_AI_API: the current Fusion reasoning toggle is the only authority;
 * the selected LOCAL model's reasoning capability must never gate an external
 * provider request.
 *
 * LOCAL_MODEL: the requested reasoning state is gated by the captured local
 * model's reasoning capability, matching the normal Send boundary.
 */
internal fun captureRegenerationReasoning(
    generationMode: ChatGenerationMode,
    localReasoningCapabilityAvailable: Boolean,
    requestedReasoningEnabled: Boolean,
): Boolean = if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) {
    requestedReasoningEnabled
} else {
    FusionModelCatalog.effectiveReasoningEnabled(
        localReasoningCapabilityAvailable,
        requestedReasoningEnabled,
    )
}

/**
 * All mutable generation controls captured at the Retry/Style tap boundary.
 * The full Room branch is loaded after this object is created, but no UI
 * control is re-resolved from Compose state afterwards.
 */
internal data class RegenerationRequestCapture(
    val conversationId: Long,
    val targetMessageId: Long,
    val responseVersionState: ResponseVersionState,
    val generationMode: ChatGenerationMode,
    val selectedModel: String,
    val selectedModelPath: String?,
    val selectedModelStableId: String?,
    val modelFamily: ModelFamily?,
    val reasoningCapabilityAvailable: Boolean = false,
    val localProfile: EffectiveLocalModelProfile?,
    val generationSettings: GenerationSettings,
    val reasoningEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val promptLabInstruction: String,
    val externalProviderId: String?,
    val externalMaxOutputTokens: Int?,
    val externalProviderRequest: ExternalProviderRequestSnapshot? = null,
    /** Project membership/resources captured at this retry start. */
    val projectContext: ProjectContextSnapshot? = null,
    val sourceScope: com.projectnuke.fusion.chat.ProjectSourceScope = com.projectnuke.fusion.chat.ProjectSourceScope(),
    val autoRoutingEnabled: Boolean = false,
    /**
     * User-facing execution profile frozen at the Retry/Style tap boundary.
     * Diagnostics/presentation only: the snapshot below still carries the
     * authoritative low-level fields. Null preserves the legacy derivation
     * from generationMode + autoRoutingEnabled.
     */
    val executionProfile: ChatExecutionProfile? = null,
    val autoPerformancePreference: com.projectnuke.fusion.model.RuntimePerformancePreference = com.projectnuke.fusion.model.RuntimePerformancePreference(),
    /**
     * Interaction mode captured at the Retry/Style tap boundary. Mode v1 is
     * ephemeral: Retry/Regenerate answers under the mode currently selected
     * by the user ("regenerate this answer under the mode currently
     * selected"). Never recovered from hidden assistant text or Room.
     */
    val interactionMode: ChatInteractionMode = ChatInteractionMode.NORMAL,
    val selectionContext: com.projectnuke.fusion.chat.SelectionContextSnapshot? = null,
    /**
     * Frozen routing decision copied from the originating target message
     * metadata. Bounded retries must target the original request snapshot,
     * not reclassify; null keeps the historical re-derivation behavior.
     */
    val frozenTrustRoutingDecision: TrustRoutingDecision? = null,
    /** Request-only search mode and manual query for this retry/regeneration. */
    val searchStrategy: SearchStrategySnapshot = SearchStrategySnapshot.DEFAULT,
    /** Revision of the manual-query UI value captured with [searchStrategy]. */
    val manualSearchQueryInputRevision: Long? = null,
)

internal fun buildRegenerationGenerationRequestSnapshot(
    capture: RegenerationRequestCapture,
    requestId: String,
    settings: GenerationSettings,
    reasoningEnabled: Boolean,
    webSearchPolicy: GenerationRequestSnapshot.WebSearchPolicy,
    attachmentIds: List<String>,
    multimodalImagePaths: List<String>,
    promptText: String,
    rawUserText: String,
    createdAt: Long,
    history: List<ChatMessage>,
    historyMessageIds: List<Long>,
    historyItems: List<com.projectnuke.fusion.util.HistoryMessageWithId> = emptyList(),
    compactPolicy: LocalCompactPolicy = LocalCompactPolicy(),
    contextMode: LocalContextMode = LocalContextMode.AUTO,
    localRag: com.projectnuke.fusion.retrieval.LocalRagRequestSnapshot? = null,
    // Frozen safe search-context belonging to this retry/regeneration
    // request (built from RAW frozen history with the attachment-privacy
    // gate before sanitization). Never re-derived from mutable UI history
    // after generation begins.
    safeWebSearchPriorUserTurns: List<String> = emptyList(),
    // A frozen routing capture (typical for explicit bounded retries) wins
    // over the re-derivation so the retry targets the original request
    // snapshot instead of reclassifying.
    trustRoutingDecision: TrustRoutingDecision = capture.selectionContext?.let {
        TrustRoutingDecision.local(
            com.projectnuke.fusion.trust.TrustRoutingReason.PRIVATE_OR_SENSITIVE_LOCAL_ONLY,
            setOf(
                com.projectnuke.fusion.trust.TrustRoutingSignal.PRIVATE_OR_SENSITIVE_CONTENT,
                com.projectnuke.fusion.trust.TrustRoutingSignal.EXTERNAL_TRANSMISSION_NOT_AUTHORIZED,
            ),
        )
    } ?: capture.frozenTrustRoutingDecision ?: TrustRouter.route(
        TrustRoutingRequest(
            userText = rawUserText,
            manualSearchEnabled = webSearchPolicy != GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
            generationModeKey = capture.generationMode.name,
        ),
    ),
): GenerationRequestSnapshot = GenerationRequestSnapshot(
    requestId = requestId,
    conversationId = capture.conversationId,
    generationModeKey = capture.generationMode.name,
    selectedModelId = capture.selectedModel,
    selectedModelPath = capture.selectedModelPath.takeIf {
        capture.generationMode != ChatGenerationMode.EXTERNAL_AI_API
    },
    selectedModelStableId = capture.selectedModelStableId.takeIf {
        capture.generationMode != ChatGenerationMode.EXTERNAL_AI_API
    },
    modelFamily = capture.modelFamily.takeIf {
        capture.generationMode != ChatGenerationMode.EXTERNAL_AI_API
    },
    settings = settings,
    reasoningEnabled = reasoningEnabled,
    webSearchPolicy = when {
        capture.selectionContext != null -> GenerationRequestSnapshot.WebSearchPolicy.DISABLED
        webSearchPolicy == GenerationRequestSnapshot.WebSearchPolicy.AUTO &&
            trustRoutingDecision.route != TrustRoute.SEARCH -> GenerationRequestSnapshot.WebSearchPolicy.DISABLED
        else -> webSearchPolicy
    },
    attachmentIds = attachmentIds,
    multimodalImagePaths = com.projectnuke.fusion.chat.ProjectSourceScopePolicy.currentImagePaths(capture.sourceScope, multimodalImagePaths),
    promptText = capture.selectionContext?.let {
        com.projectnuke.fusion.chat.SelectionContextComposition.appendToQuestion(promptText, it)
    } ?: promptText,
    rawUserText = rawUserText,
    createdAt = createdAt,
    history = history,
    historyMessageIds = historyMessageIds,
    reasoningCapabilityAvailable = capture.reasoningCapabilityAvailable,
    historyItems = historyItems,
    compactPolicy = compactPolicy,
    contextMode = contextMode,
    promptLabInstruction = capture.promptLabInstruction,
    externalProviderId = capture.externalProviderId,
    externalProviderSelectionFrozen = capture.generationMode == ChatGenerationMode.EXTERNAL_AI_API,
    externalMaxOutputTokens = capture.externalMaxOutputTokens,
    externalProviderRequest = capture.externalProviderRequest,
    projectContext = capture.projectContext.takeIf {
        capture.generationMode != ChatGenerationMode.EXTERNAL_AI_API && capture.selectionContext == null
    },
    localRag = localRag.takeIf { capture.selectionContext == null },
    sourceScope = if (capture.selectionContext == null) capture.sourceScope.frozen() else
        com.projectnuke.fusion.chat.ProjectSourceScope(com.projectnuke.fusion.chat.ProjectSourceScopeKind.CURRENT_ATTACHMENTS),
    autoRoutingEnabled = capture.autoRoutingEnabled,
    // Diagnostics only: the authoritative execution fields above were
    // already frozen from the capture. Legacy captures without a profile
    // derive the key from those same frozen fields, never from live UI.
    executionProfileKey = capture.executionProfile?.name
        ?: ChatExecutionProfilePolicy.fromFrozen(
            capture.generationMode.name,
            capture.autoRoutingEnabled,
        ).name,
    autoPerformancePreference = capture.autoPerformancePreference,
    trustRoutingDecision = trustRoutingDecision,
    safeWebSearchPriorUserTurns = if (capture.selectionContext == null) safeWebSearchPriorUserTurns else emptyList(),
    interactionMode = capture.interactionMode,
    selectionContext = capture.selectionContext,
    searchStrategy = capture.searchStrategy,
    manualSearchQueryInputRevision = capture.manualSearchQueryInputRevision,
)

/**
 * Builds a regeneration capture purely from a frozen failed-SEARCH retry
 * snapshot. No current UI selection is consulted: model, provider, settings,
 * route, attachments, and history identity all come from the frozen
 * original request. Used by explicit Retry Search; a missing frozen
 * snapshot must disable the action, never rederive from UI.
 */
internal fun buildFrozenSearchRetryCapture(
    frozen: FrozenSearchRetry,
    targetMessageId: Long,
    versions: ResponseVersionState,
): RegenerationRequestCapture {
    val generationMode = ChatGenerationMode.valueOf(frozen.generationModeKey)
    return RegenerationRequestCapture(
        conversationId = frozen.conversationId,
        targetMessageId = targetMessageId,
        responseVersionState = versions,
        generationMode = generationMode,
        selectedModel = frozen.selectedModelId.orEmpty(),
        selectedModelPath = frozen.selectedModelPath,
        selectedModelStableId = frozen.selectedModelStableId,
        modelFamily = frozen.modelFamily,
        reasoningCapabilityAvailable = frozen.reasoningCapabilityAvailable,
        localProfile = if (generationMode == ChatGenerationMode.EXTERNAL_AI_API) {
            null
        } else {
            EffectiveLocalModelProfile(
                modelId = frozen.selectedModelId.orEmpty(),
                generationSettings = frozen.settings,
                reasoningEnabled = frozen.reasoningEnabled,
                contextMode = frozen.contextMode,
                effectiveKvCapacityTokens = frozen.settings.kvCacheCapacityTokens,
                compactPolicy = frozen.compactPolicy,
                provenance = LocalSettingsProvenance(
                    output = "frozen-search-retry",
                    context = "frozen-search-retry",
                    compact = "frozen-search-retry",
                ),
            )
        },
        generationSettings = frozen.settings,
        reasoningEnabled = frozen.reasoningEnabled,
        webSearchEnabled = frozen.webSearchPolicy != GenerationRequestSnapshot.WebSearchPolicy.DISABLED,
        promptLabInstruction = frozen.promptLabInstruction,
        externalProviderId = frozen.externalProviderId,
        externalMaxOutputTokens = frozen.externalMaxOutputTokens,
        externalProviderRequest = frozen.externalProviderRequest,
        // Frozen SEARCH retry repeats its frozen request: mode comes from
        // the frozen original, never from mutable current UI state.
        interactionMode = frozen.interactionMode,
        sourceScope = frozen.sourceScope.frozen(),
        projectContext = frozen.projectContext,
        autoRoutingEnabled = frozen.autoRoutingEnabled,
        executionProfile = frozen.executionProfileKey?.let {
            runCatching { ChatExecutionProfile.valueOf(it) }.getOrNull()
        } ?: ChatExecutionProfilePolicy.fromFrozen(
            frozen.generationModeKey,
            frozen.autoRoutingEnabled,
        ),
        frozenTrustRoutingDecision = frozen.trustRoutingDecision,
        searchStrategy = frozen.searchStrategy,
        manualSearchQueryInputRevision = frozen.manualSearchQueryInputRevision,
    )
}

/**
 * Scopes the live branch to a frozen retry: verifies the originating user
 * turn (nearest preceding user message, exact frozen request-text match,
 * exact frozen attachment identities) and the frozen history identity.
 * Returns the scoped history or null when anything drifted — the caller must
 * then refuse the retry instead of rebuilding from current state.
 */
internal fun resolveFrozenRetryScope(
    fullHistory: List<MessageEntity>,
    frozen: FrozenSearchRetry,
    targetMessageId: Long,
): List<MessageEntity>? {
    // The fence is only sound for the frozen request's own conversation: a
    // branch loaded for a different conversation must refuse instead of
    // silently scoping foreign messages into a persisted retry.
    if (frozen.conversationId <= 0L) return null
    if (fullHistory.any { it.conversationId != frozen.conversationId }) return null
    val targetIndex = fullHistory.indexOfFirst { it.id == targetMessageId }
    if (targetIndex < 0) return null
    val originIndex = (targetIndex - 1 downTo 0).firstOrNull { fullHistory[it].role == "user" }
        ?: return null
    val origin = fullHistory[originIndex]
    val parsed = AttachmentMessageCodec.parseAttachmentMessage(origin.content)
    if (parsed.body.trim() != frozen.rawUserText.trim()) return null
    if (parsed.records.map { it.localPath }.toSet() != frozen.attachmentIds.toSet()) return null
    val liveIds = fullHistory.map { it.id }.toSet()
    if (!frozen.historyMessageIds.all { it in liveIds }) return null
    val keep = (frozen.historyMessageIds + origin.id + targetMessageId).toSet()
    return fullHistory.filter { it.id in keep }
}
