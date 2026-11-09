package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ResearchRunPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reuses the approved-query planner, provider consent and bounded transport. */
internal object ResearchSearchOwner {
    suspend fun search(userInput: String, previousUserMessage: String?, providerRepository: WebSearchProviderRepository?,
        safePriorUserTurns: List<String>, interactionMode: ChatInteractionMode): FusionSearchResponse {
        val response = FusionWebSearch.search(userInput, previousUserMessage, providerRepository, safePriorUserTurns = safePriorUserTurns)
        currentCoroutineContext().ensureActive()
        return bound(response, interactionMode)
    }
    fun bound(response: FusionSearchResponse, mode: ChatInteractionMode): FusionSearchResponse {
        if (mode != ChatInteractionMode.RESEARCH) return response
        val results = response.results.distinctBy(FusionSearchResult::evidenceIdentity)
            .take(ResearchRunPolicy.MAX_WEB_SOURCES)
        val retainedEvidence = results.mapTo(linkedSetOf(), FusionSearchResult::evidenceIdentity)
        val sources = response.sources.asSequence()
            .filter { it.evidenceIdentity() in retainedEvidence }
            .distinctBy(WebSearchSource::evidenceIdentity)
            .take(ResearchRunPolicy.MAX_WEB_SOURCES)
            .toList()
        return response.copy(results = results, sources = sources)
    }

    internal fun productionExactSearchTool(
        providerRepository: WebSearchProviderRepository?,
    ): AgenticExactSearchTool = object : AgenticExactSearchTool {
        override suspend fun search(
            query: String,
            requestId: String,
            stillAuthorized: () -> Boolean,
        ): FusionSearchResponse = FusionWebSearch.searchExactApprovedQuery(
            query = query,
            providerRepository = providerRepository,
            requestToken = requestId,
            isStillAuthorized = stillAuthorized,
        )

        override suspend fun searchWithTransportObserver(
            query: String,
            requestId: String,
            stillAuthorized: () -> Boolean,
            onTransportAttempt: () -> Unit,
        ): FusionSearchResponse = FusionWebSearch.searchExactApprovedQuery(
            query = query,
            providerRepository = providerRepository,
            requestToken = requestId,
            isStillAuthorized = stillAuthorized,
            onTransportAttempt = onTransportAttempt,
        )
    }

    /** Shared production entry for strategy-selected searches; all modes use the same exact transport. */
    suspend fun searchWithStrategy(
        request: GenerationRequestSnapshot,
        previousUserMessage: String?,
        providerRepository: WebSearchProviderRepository?,
        privacySnapshot: AgenticSearchPrivacySnapshot,
        agentModel: SearchAgentModel?,
        ownerStillActive: () -> Boolean,
        limits: AgenticSearchLimits,
        exactSearchOverride: AgenticExactSearchTool? = null,
        agentUnavailableReasonCode: String? = null,
    ): SearchStrategyRouteResult {
        val queryAuthorizer = RequestScopedAgenticSearchQueryAuthorizer(privacySnapshot)
        val exactSearch = exactSearchOverride ?: productionExactSearchTool(providerRepository)
        val route = SearchStrategyRouter(
            exactSearchTool = exactSearch,
            queryAuthorizer = queryAuthorizer,
            legacyFallback = RuleBasedLegacySearchFallback(exactSearch, queryAuthorizer),
        ).execute(
            request = SearchStrategyRouteRequest(
                agentRequest = AgenticSearchRequest(
                    requestId = request.requestId,
                    originalUserQuery = request.rawUserText,
                    webSearchPolicy = request.webSearchPolicy,
                    limits = limits,
                    ownerStillActive = ownerStillActive,
                    searchPermissionStillValid = {
                        ownerStillActive() && request.webSearchPolicy != GenerationRequestSnapshot.WebSearchPolicy.DISABLED
                    },
                    priorUserContext = boundedAgentPlanningContext(previousUserMessage, request.safeWebSearchPriorUserTurns),
                    webSearchRequired = request.interactionMode == ChatInteractionMode.RESEARCH,
                ),
                strategy = request.searchStrategy,
                previousUserMessage = previousUserMessage,
                safePriorUserTurns = request.safeWebSearchPriorUserTurns,
                agentUnavailableReasonCode = agentUnavailableReasonCode,
            ),
            agentModel = agentModel,
        )
        val bounded = route.response?.let { bound(it, request.interactionMode) }
        return route.copy(response = bounded)
    }

    /**
     * Builds the bounded public planning context from the frozen request's
     * already privacy-approved user turns. The immediate previous turn comes
     * first for parity with the legacy planner; the frozen safe-turn list
     * follows, deduplicated, within the documented turn and character bounds.
     */
    fun boundedAgentPlanningContext(previousUserMessage: String?, safePriorUserTurns: List<String>): List<String> {
        var remaining = MAX_AGENT_PLANNING_CONTEXT_CHARS
        val bounded = ArrayList<String>(MAX_AGENT_PLANNING_CONTEXT_TURNS)
        val candidates = listOfNotNull(previousUserMessage?.trim()?.takeIf(String::isNotBlank)) +
            safePriorUserTurns.map { it.trim() }.filter { it.isNotBlank() }
        for (candidate in candidates) {
            if (bounded.size >= MAX_AGENT_PLANNING_CONTEXT_TURNS || remaining <= 0) break
            val clipped = candidate.take(minOf(MAX_SAFE_PRIOR_TURN_CHARS, remaining))
            if (clipped in bounded) continue
            bounded += clipped
            remaining -= clipped.length
        }
        return bounded
    }
}
