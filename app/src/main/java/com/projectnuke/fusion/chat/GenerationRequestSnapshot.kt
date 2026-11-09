package com.projectnuke.fusion.chat

import com.projectnuke.fusion.model.ChatMessage
import com.projectnuke.fusion.model.GenerationSettings
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.model.LocalContextMode
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.ai.model.AiProviderAuthMode
import com.projectnuke.fusion.ai.model.AiProviderConfig
import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.ExternalRequestPatch
import com.projectnuke.fusion.ai.model.ExternalVariantSelectionPolicy
import com.projectnuke.fusion.ai.model.UserExternalVariant
import com.projectnuke.fusion.ai.network.ExternalProviderDialectResolver
import com.projectnuke.fusion.ai.network.ExternalProviderDialect
import com.projectnuke.fusion.ai.network.ExternalRequestCompatibility
import com.projectnuke.fusion.ai.provider.ExternalModelCapabilityResolver
import com.projectnuke.fusion.ai.provider.ExternalModelCapabilityRegistry
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoute
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.util.HistoryMessageWithId

data class ExternalProviderRequestSnapshot(
    val id: String,
    val type: AiProviderType,
    val baseUrl: String,
    val modelId: String,
    val temperature: Double,
    val maxTokens: Int,
    val authMode: AiProviderAuthMode,
    val authHeaderName: String?,
    val reasoningContentEnabled: Boolean,
    val apiKeySecretId: String?,
    val displayName: String,
    val selectedVariantId: String? = null,
    val resolvedRequestPatch: ExternalRequestPatch = ExternalRequestPatch(),
    val requestCompatibility: ExternalRequestCompatibility = ExternalRequestCompatibility.FALLBACK,
) {
    companion object {
        fun from(
            config: AiProviderConfig,
            maxTokens: Int,
            capabilityResolver: ExternalModelCapabilityResolver = ExternalModelCapabilityRegistry,
            userVariants: List<UserExternalVariant> = emptyList(),
            compatibilityResolver: (AiProviderType, String) -> ExternalRequestCompatibility =
                ExternalModelCapabilityRegistry::resolveRequestCompatibility,
        ): ExternalProviderRequestSnapshot {
            // Persist only variant ID; resolve the one typed patch from trusted code.
            val variantId = ExternalVariantSelectionPolicy.editorVariantId(config.selectedVariantId)
            val variants = if (capabilityResolver === ExternalModelCapabilityRegistry) {
                ExternalModelCapabilityRegistry.resolveComposed(config, userVariants)
            } else {
                capabilityResolver.resolveVariants(config.type, config.modelId)
            }
            val variant = variants.firstOrNull { it.id == variantId } ?: variants.firstOrNull()
                ?: com.projectnuke.fusion.ai.model.ExternalModelVariant(
                    id = ExternalVariantSelectionPolicy.DEFAULT_UI_VARIANT_ID,
                    label = "기본",
                    requestPatch = ExternalRequestPatch(),
                )
            val patch = ExternalProviderDialectResolver.resolveRequestPatch(
                ExternalProviderDialectResolver.resolveDialect(config.type),
                variant,
            )
            val requestCompatibility = ExternalProviderDialectResolver.resolveRequestCompatibility(
                ExternalProviderDialectResolver.resolveDialect(config.type),
                compatibilityResolver(config.type, config.modelId),
            )
            return ExternalProviderRequestSnapshot(
                id = config.id,
                type = config.type,
                baseUrl = config.baseUrl,
                modelId = config.modelId,
                temperature = config.temperature,
                maxTokens = maxTokens,
                authMode = config.authMode,
                authHeaderName = config.authHeaderName,
                reasoningContentEnabled = config.reasoningContentEnabled,
                apiKeySecretId = config.apiKeySecretId,
                displayName = config.displayName,
                selectedVariantId = variant.id,
                resolvedRequestPatch = patch,
                requestCompatibility = requestCompatibility,
            )
        }
    }
}

/**
 * Immutable snapshot of a generation request captured exactly once when the
 * request starts. All later stages must verify [requestId] still matches the
 * active session before mutating visible UI state.
 *
 * [generationModeKey] is the string form of the caller's ChatGenerationMode
 * enum (kept as String here to avoid leaking the ui-package enum across
 * module boundaries).
 */
data class GenerationRequestSnapshot(
    val requestId: String,
    val conversationId: Long,
    val generationModeKey: String,
    val selectedModelId: String?,
    val selectedModelPath: String?,
    /** Stable catalog identity captured with the request; never re-resolve later. */
    val selectedModelStableId: String? = null,
    /** Model-family adapter captured with the request; never infer from mutable UI state. */
    val modelFamily: ModelFamily? = null,
    /** Catalog capability captured with the request; never infer from family. */
    val reasoningCapabilityAvailable: Boolean = false,
    val settings: GenerationSettings,
    val reasoningEnabled: Boolean,
    val webSearchPolicy: WebSearchPolicy,
    val attachmentIds: List<String>,
    val multimodalImagePaths: List<String>,
    val promptText: String,
    val rawUserText: String,
    val createdAt: Long,
    val history: List<ChatMessage> = emptyList(),
    val historyMessageIds: List<Long> = emptyList(),
    /** Identity-preserving semantic history for local compaction. */
    val historyItems: List<HistoryMessageWithId> = emptyList(),
    val compactPolicy: LocalCompactPolicy = LocalCompactPolicy(true, 80, 25),
    val contextMode: LocalContextMode = LocalContextMode.AUTO,
    val promptLabInstruction: String = "",
    val externalProviderId: String? = null,
    val externalProviderSelectionFrozen: Boolean = false,
    val externalMaxOutputTokens: Int? = null,
    val     externalProviderRequest: ExternalProviderRequestSnapshot? = null,
    /** Frozen explicit project context; never used by external-provider paths. */
    val projectContext: ProjectContextSnapshot? = null,
    val sourceScope: ProjectSourceScope = ProjectSourceScope(),
    val autoRoutingEnabled: Boolean = false,
    /**
     * Diagnostics/presentation key for the user-facing execution profile
     * (ChatExecutionProfile.name) this request was frozen under. Never an
     * execution authority: generationModeKey, autoRoutingEnabled, provider
     * identity, trust decision, and DataEgress tickets stay authoritative.
     */
    val executionProfileKey: String? = null,
    val autoPerformancePreference: com.projectnuke.fusion.model.RuntimePerformancePreference = com.projectnuke.fusion.model.RuntimePerformancePreference(),
    val autoSelectionExplanation: String? = null,
    /**
     * Frozen local attachment RAG selection captured with a LOCAL request
     * (C4). The final prompt assembly must consume the excerpts bound to this
     * snapshot; a snapshot mismatch fails closed and drops the excerpts.
     */
    val localRag: com.projectnuke.fusion.retrieval.LocalRagRequestSnapshot? = null,
    /**
     * Frozen SAFE prior user turns for web-search follow-up context (v2),
     * newest first, bounded by MAX_SAFE_PRIOR_USER_TURNS x
     * MAX_SAFE_PRIOR_TURN_CHARS. Built once from RAW frozen history with the
     * attachment-privacy gate applied BEFORE sanitization; later stages must
     * use this list and never re-derive context from mutable UI history.
     * Retry/regeneration carries the frozen list belonging to its request.
     */
    val safeWebSearchPriorUserTurns: List<String> = emptyList(),
    /** Deterministic capability decision captured before generation begins. */
    val trustRoutingDecision: TrustRoutingDecision = TrustRoutingDecision(
        route = TrustRoute.LOCAL,
        reason = TrustRoutingReason.SIMPLE_STABLE_KNOWLEDGE,
        signals = emptySet(),
    ),
    /**
     * Explicit chat interaction mode frozen at the request boundary.
     * Presentation/session state only: later mode switches never mutate an
     * active request, and internal maintenance generations keep NORMAL.
     */
    val interactionMode: ChatInteractionMode = ChatInteractionMode.NORMAL,
    /** Search-selected local source scope, frozen at Send and never widened. */
    val selectionContext: SelectionContextSnapshot? = null,
    /** Immutable strategy settings captured at request creation; never grants search permission. */
    val searchStrategy: SearchStrategySnapshot = SearchStrategySnapshot.DEFAULT,
    /** UI input revision owning the frozen manual query; used only to clear the matching draft after dispatch. */
    val manualSearchQueryInputRevision: Long? = null,
) {
    enum class WebSearchPolicy { DISABLED, ENABLED, AUTO }

    companion object {
        fun resolveWebSearchPolicy(
            externalApiAttachmentBlocked: Boolean,
            webSearchEnabled: Boolean,
            autoWebSearchSuggested: Boolean,
        ): WebSearchPolicy = when {
            externalApiAttachmentBlocked -> WebSearchPolicy.DISABLED
            webSearchEnabled -> WebSearchPolicy.ENABLED
            autoWebSearchSuggested -> WebSearchPolicy.AUTO
            else -> WebSearchPolicy.DISABLED
        }
    }
}
