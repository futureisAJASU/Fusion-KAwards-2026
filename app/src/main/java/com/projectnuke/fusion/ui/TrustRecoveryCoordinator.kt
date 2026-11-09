package com.projectnuke.fusion.ui

import com.projectnuke.fusion.chat.ExternalProviderRequestSnapshot
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import com.projectnuke.fusion.model.LocalCompactPolicy
import com.projectnuke.fusion.model.LocalContextMode
import com.projectnuke.fusion.modelzoo.ModelFamily
import com.projectnuke.fusion.trust.TrustExecutionFailure
import com.projectnuke.fusion.trust.TrustRecoveryAction
import com.projectnuke.fusion.trust.TrustRetryGate
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustFailurePolicy

/**
 * Bounded frozen retry snapshot for a failed SEARCH request. Captured from
 * the LIVE failed request — never from current UI selections — so an
 * explicit retry repeats the original request semantics even after the user
 * changed model/provider/settings.
 *
 * Process-local only: after process death the frozen snapshot is gone and
 * the retry action becomes unavailable instead of rederiving silently from
 * mutable current settings.
 */
internal data class FrozenSearchRetry(
    val failureMessageId: Long,
    val requestId: String,
    val conversationId: Long,
    val generationModeKey: String,
    val webSearchPolicy: GenerationRequestSnapshot.WebSearchPolicy,
    val selectedModelId: String?,
    val selectedModelPath: String?,
    val selectedModelStableId: String?,
    val modelFamily: ModelFamily?,
    val reasoningCapabilityAvailable: Boolean = false,
    val settings: com.projectnuke.fusion.model.GenerationSettings,
    val reasoningEnabled: Boolean,
    val externalProviderId: String?,
    val externalMaxOutputTokens: Int?,
    val externalProviderRequest: ExternalProviderRequestSnapshot?,
    val compactPolicy: LocalCompactPolicy,
    val contextMode: LocalContextMode,
    val promptLabInstruction: String,
    val trustRoutingDecision: TrustRoutingDecision,
    val attachmentIds: List<String>,
    val multimodalImagePaths: List<String>,
    val rawUserText: String,
    val promptText: String,
    val historyMessageIds: List<Long>,
    /**
     * Frozen interaction mode of the original request. The explicit SEARCH
     * retry repeats the original request semantics; it never re-reads the
     * mutable current mode. Defaults NORMAL for legacy/absent data.
     */
    val interactionMode: com.projectnuke.fusion.chat.ChatInteractionMode =
        com.projectnuke.fusion.chat.ChatInteractionMode.NORMAL,
    val sourceScope: com.projectnuke.fusion.chat.ProjectSourceScope = com.projectnuke.fusion.chat.ProjectSourceScope(),
    val projectContext: com.projectnuke.fusion.chat.ProjectContextSnapshot? = null,
    /**
     * Frozen auto-routing flag of the original request. The explicit SEARCH
     * retry repeats the original execution semantics; defaults false for
     * legacy/absent data.
     */
    val autoRoutingEnabled: Boolean = false,
    /**
     * Diagnostics/presentation key for the user-facing execution profile
     * (ChatExecutionProfile.name). Never an execution authority; the frozen
     * mode + autoRouting fields above stay authoritative.
     */
    val executionProfileKey: String? = null,
    /** Bounded, process-local exact search semantics for this retry only. */
    val searchStrategy: SearchStrategySnapshot = SearchStrategySnapshot.DEFAULT,
    /** Frozen ownership of the query draft; retries never clear a newer edit. */
    val manualSearchQueryInputRevision: Long? = null,
) {
    companion object {
        fun from(request: GenerationRequestSnapshot, failureMessageId: Long): FrozenSearchRetry =
            FrozenSearchRetry(
                failureMessageId = failureMessageId,
                requestId = request.requestId,
                conversationId = request.conversationId,
                generationModeKey = request.generationModeKey,
                webSearchPolicy = request.webSearchPolicy,
                selectedModelId = request.selectedModelId,
                selectedModelPath = request.selectedModelPath,
                selectedModelStableId = request.selectedModelStableId,
                modelFamily = request.modelFamily,
                reasoningCapabilityAvailable = request.reasoningCapabilityAvailable,
                settings = request.settings,
                reasoningEnabled = request.reasoningEnabled,
                externalProviderId = request.externalProviderId,
                externalMaxOutputTokens = request.externalMaxOutputTokens,
                externalProviderRequest = request.externalProviderRequest,
                compactPolicy = request.compactPolicy,
                contextMode = request.contextMode,
                promptLabInstruction = request.promptLabInstruction,
                trustRoutingDecision = request.trustRoutingDecision,
                attachmentIds = request.attachmentIds,
                multimodalImagePaths = request.multimodalImagePaths,
                rawUserText = request.rawUserText,
                promptText = request.promptText,
                historyMessageIds = request.historyMessageIds,
                interactionMode = request.interactionMode,
                sourceScope = request.sourceScope.frozen(),
                projectContext = request.projectContext,
                autoRoutingEnabled = request.autoRoutingEnabled,
                executionProfileKey = request.executionProfileKey,
                searchStrategy = request.searchStrategy,
                manualSearchQueryInputRevision = request.manualSearchQueryInputRevision,
            )
    }
}

internal data class SearchRetryActionPresentation(
    val label: String,
    val correctManualQueryFirst: Boolean,
)

internal object SearchRetryActionPresentationPolicy {
    fun resolve(snapshot: FrozenSearchRetry?): SearchRetryActionPresentation {
        val manualQueryNeedsCorrection = snapshot?.searchStrategy?.let { strategy ->
            strategy.strategy == com.projectnuke.fusion.chat.SearchStrategy.MANUAL_QUERY &&
                strategy.manualQuery !is com.projectnuke.fusion.chat.ManualSearchQueryValidation.Valid
        } == true
        return if (manualQueryNeedsCorrection) {
            SearchRetryActionPresentation(label = "검색어 수정", correctManualQueryFirst = true)
        } else {
            SearchRetryActionPresentation(label = "다시 검색", correctManualQueryFirst = false)
        }
    }
}

/**
 * C2-E production coordinator: typed execution failure recovery for routed
 * SEARCH attempts and cloud escalations.
 *
 * Production callers live in ChatScreen. The coordinator never generates or
 * substitutes an answer itself; it only classifies typed failures and owns
 * the explicit single-flight retry gate. The actual retry reuses the exact
 * production regeneration path (same original user turn, frozen SEARCH
 * route, no user turn duplication), guarded additionally by the retry gate.
 */
internal class TrustRecoveryCoordinator(
    private val retryGate: TrustRetryGate = TrustRetryGate(),
) {
    // Bounded process-local frozen retry snapshots keyed by failure message
    // id. Single-use: consumed on take so a retry can never run twice from
    // one freeze; a failed retry freezes a fresh snapshot for its own new
    // failure message.
    private val frozenRetries = LinkedHashMap<Long, FrozenSearchRetry>()

    /**
     * Freezes the failed SEARCH request for one explicit retry. The snapshot
     * comes from the live request object; current UI selections are never
     * consulted.
     */
    @Synchronized
    fun freezeSearchRetry(failureMessageId: Long, request: GenerationRequestSnapshot): FrozenSearchRetry {
        val frozen = FrozenSearchRetry.from(request, failureMessageId)
        frozenRetries[failureMessageId] = frozen
        while (frozenRetries.size > TrustRecoveryBounds.MAX_RETAINED_SEARCH_RETRIES) {
            frozenRetries.remove(frozenRetries.keys.first())
        }
        return frozen
    }

    /**
     * Consumes the frozen retry for one explicit attempt. Null (missing or
     * already consumed) means the retry action is unavailable — the caller
     * must NOT rederive a retry from current UI state.
     */
    @Synchronized
    fun takeSearchRetry(failureMessageId: Long): FrozenSearchRetry? =
        frozenRetries.remove(failureMessageId)

    /** Peek while the retry gate is held; retain the payload if validation aborts. */
    @Synchronized
    fun pendingSearchRetry(failureMessageId: Long): FrozenSearchRetry? = frozenRetries[failureMessageId]

    /** Consume only the exact snapshot that passed validation. */
    @Synchronized
    fun consumeSearchRetry(failureMessageId: Long, expected: FrozenSearchRetry): Boolean {
        if (frozenRetries[failureMessageId] !== expected) return false
        frozenRetries.remove(failureMessageId)
        return true
    }

    /** Restore an exact retry only after registration itself failed before ownership began. */
    @Synchronized
    fun restoreSearchRetryIfAbsent(failureMessageId: Long, expected: FrozenSearchRetry): Boolean {
        if (frozenRetries.containsKey(failureMessageId)) return frozenRetries[failureMessageId] === expected
        frozenRetries[failureMessageId] = expected
        while (frozenRetries.size > TrustRecoveryBounds.MAX_RETAINED_SEARCH_RETRIES) {
            frozenRetries.remove(frozenRetries.keys.first())
        }
        return true
    }

    @Synchronized
    internal fun retainedRetryCountForTest(): Int = frozenRetries.size
    // Typed SEARCH failure for a frozen SEARCH route, or null when the route
    // succeeded (no failure state).
    fun searchFailure(
        decision: TrustRoutingDecision?,
        attempted: Boolean,
        usableResult: Boolean,
        requestId: String,
    ): TrustExecutionFailure? {
        if (decision == null || decision.route != com.projectnuke.fusion.trust.TrustRoute.SEARCH) return null
        return TrustFailurePolicy.searchFailure(decision, attempted, usableResult, requestId)
            .takeIf { it.isErrorState }
    }

    // Actual C2-D provider failure -> typed CloudEscalationFailed, explicit
    // retry only, no automatic fallback provider.
    fun cloudFailure(pendingKey: String): TrustExecutionFailure.CloudEscalationFailed =
        TrustFailurePolicy.cloudFailureAsExecutionFailure(pendingKey)
            as TrustExecutionFailure.CloudEscalationFailed

    fun resolveAfterRetry(previousStableAnswer: String, candidateAnswer: String?, succeeded: Boolean): String =
        TrustFailurePolicy.resolveAfterRetry(previousStableAnswer, candidateAnswer, succeeded)

    fun classifierFallbackMessage(fallbackDecision: TrustRoutingDecision): String =
        TrustFailurePolicy.classifierFallbackMessage(fallbackDecision)

    fun containsClassifierInternals(userVisible: String): Boolean =
        TrustFailurePolicy.containsClassifierInternals(userVisible)

    fun sanitizeClassifierDiagnostics(raw: String): String = TrustFailurePolicy.sanitizeClassifierDiagnostics(raw)

    fun abstainNotice(reason: com.projectnuke.fusion.trust.TrustRoutingReason): String =
        TrustFailurePolicy.abstainNotice(reason)

    fun isAbstainError(): Boolean = TrustFailurePolicy.isAbstainError()

    /**
     * Explicit retry ownership: at most one bounded success = retry attempt
     * per instant. The gate is released by the production flow boundary that
     * owns the attempt (ChatScreen insertion/completion lifecycle); the
     * existing regeneration path itself refuses busy targets.
     */
    fun tryBeginSearchRetry(pendingKey: String): Boolean = retryGate.tryEnter()

    fun endSearchRetry(pendingKey: String) = retryGate.release()

    // Validation helper to keep the bounded typed recovery action value.
    fun retryAction(requestId: String): TrustRecoveryAction = TrustRecoveryAction.RetrySearch(requestId)
}

internal object TrustRecoveryBounds {
    const val MAX_RETAINED_SEARCH_RETRIES = 8
}
