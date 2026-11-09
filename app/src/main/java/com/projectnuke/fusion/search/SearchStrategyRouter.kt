package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.ManualSearchQueryValidation
import com.projectnuke.fusion.chat.SearchStrategy
import com.projectnuke.fusion.chat.SearchStrategySnapshot
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.llm.isFatalError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

data class SearchStrategyRouteRequest(
    val agentRequest: AgenticSearchRequest,
    val strategy: SearchStrategySnapshot,
    val previousUserMessage: String? = null,
    val safePriorUserTurns: List<String> = emptyList(),
    /**
     * Bounded privacy-safe reason code explaining why no agent model was
     * constructed. Distinguishes "model not ready yet" from "agent failed
     * while planning"; it never carries query or document text.
     */
    val agentUnavailableReasonCode: String? = null,
)

data class LegacySearchFallbackRequest(
    val agentRequest: AgenticSearchRequest,
    val previousUserMessage: String?,
    val safePriorUserTurns: List<String>,
    /** Normalized identities; implementations must exclude these before transport. */
    val excludedQueryIdentities: Set<String>,
    val maxAdditionalQueries: Int,
    /** Called immediately before each exact transport invocation, including failed attempts. */
    val onQueryIssued: () -> Unit = {},
    /** Elapsed deterministic planner work, separate from model planning. */
    val onPlanningElapsed: (Long) -> Unit = {},
    /** Elapsed time inside each exact web-search transport. */
    val onSearchTransportElapsed: (Long) -> Unit = {},
)

sealed interface LegacySearchFallbackResult {
    data class Completed(val response: FusionSearchResponse?) : LegacySearchFallbackResult
    data object PrivacyDenied : LegacySearchFallbackResult
    data object PermissionRevoked : LegacySearchFallbackResult
    data object OwnershipLost : LegacySearchFallbackResult
    data class Failed(val reasonCode: String) : LegacySearchFallbackResult
}

fun interface LegacySearchFallbackExecutor {
    suspend fun search(request: LegacySearchFallbackRequest): LegacySearchFallbackResult
}

enum class SearchFallbackReason {
    MODEL_UNAVAILABLE,
    NATIVE_TOOLS_UNSUPPORTED,
    INVALID_TOOL_CALL,
    UNVERIFIED_QUERY_EXPANSION,
    TOOL_FAILURE,
    NO_USABLE_EVIDENCE,
}

/** A deliberately small, explicit policy: privacy, permission, ownership, timeout and budget never fall through. */
object SearchStrategyFallbackPolicy {
    fun reason(
        strategy: SearchStrategySnapshot,
        webSearchPolicy: GenerationRequestSnapshot.WebSearchPolicy,
        agentResult: AgenticSearchResult,
        requestStillOwned: Boolean,
        permissionStillValid: Boolean,
        maxAdditionalQueries: Int,
    ): SearchFallbackReason? {
        if (strategy.strategy != SearchStrategy.AGENT_HARNESS || !strategy.allowLegacyFallback) return null
        if (!agentResult.diagnostics.legacyFallbackAllowed) return null
        if (webSearchPolicy == GenerationRequestSnapshot.WebSearchPolicy.DISABLED) return null
        if (!requestStillOwned || !permissionStillValid || maxAdditionalQueries <= 0) return null
        if (agentResult.evidence != null) return null
        return when (agentResult.outcome) {
            AgenticSearchOutcome.MODEL_UNAVAILABLE -> SearchFallbackReason.MODEL_UNAVAILABLE
            AgenticSearchOutcome.NATIVE_TOOLS_UNSUPPORTED -> SearchFallbackReason.NATIVE_TOOLS_UNSUPPORTED
            AgenticSearchOutcome.INVALID_TOOL_CALL -> SearchFallbackReason.INVALID_TOOL_CALL
            AgenticSearchOutcome.UNVERIFIED_QUERY_EXPANSION -> SearchFallbackReason.UNVERIFIED_QUERY_EXPANSION
            AgenticSearchOutcome.TOOL_FAILURE -> SearchFallbackReason.TOOL_FAILURE
            AgenticSearchOutcome.NO_USABLE_EVIDENCE -> SearchFallbackReason.NO_USABLE_EVIDENCE
            AgenticSearchOutcome.COMPLETED,
            AgenticSearchOutcome.SEARCH_DISABLED,
            AgenticSearchOutcome.PRIVACY_DENIED,
            AgenticSearchOutcome.PERMISSION_REVOKED,
            AgenticSearchOutcome.OWNERSHIP_LOST,
            AgenticSearchOutcome.TIMEOUT,
            AgenticSearchOutcome.BUDGET_EXHAUSTED -> null
        }
    }
}

enum class SearchRouteOutcome {
    COMPLETED,
    CANCELLED,
    SEARCH_DISABLED,
    MANUAL_QUERY_MISSING,
    MANUAL_QUERY_INVALID,
    PRIVACY_DENIED,
    PERMISSION_REVOKED,
    OWNERSHIP_LOST,
    AGENT_FAILED,
    FALLBACK_COMPLETED,
    FALLBACK_FAILED,
    TIMEOUT,
}

data class SearchStrategyRouteResult(
    val outcome: SearchRouteOutcome,
    val response: FusionSearchResponse?,
    val selectedStrategy: SearchStrategy,
    val executedStrategy: SearchStrategy?,
    val fallbackReason: SearchFallbackReason? = null,
    /** The fallback executor was entered, even if it did not reach transport. */
    val fallbackAttempted: Boolean = false,
    /** Number of exact fallback transport invocations, including failed attempts. */
    val fallbackQueriesIssued: Int = 0,
    val agentDiagnostics: AgenticSearchDiagnostics? = null,
    /** Exact-search tool invocations, including provider preflight failures before network I/O. */
    val searchQueriesIssued: Int = 0,
    /** Network I/O attempts observed at BoundedHttpClient; separate from route/tool invocations. */
    val exactTransportAttempts: Int = 0,
    val planningElapsedMs: Long = 0,
    val searchTransportElapsedMs: Long = 0,
) {
    init {
        require(fallbackQueriesIssued in 0..4)
    }
}

/**
 * Routes one already-authorized application search. Strategy selection never
 * grants search permission; every path first honors the frozen request policy.
 */
class SearchStrategyRouter(
    private val exactSearchTool: AgenticExactSearchTool,
    private val queryAuthorizer: AgenticSearchQueryAuthorizer,
    private val legacyFallback: LegacySearchFallbackExecutor,
) {
    suspend fun execute(
        request: SearchStrategyRouteRequest,
        agentModel: SearchAgentModel?,
    ): SearchStrategyRouteResult {
        val exactTransportAttempts = AtomicInteger()
        val result = executeRouted(
            request.copy(
                agentRequest = request.agentRequest.copy(
                    onExactTransportAttempt = { exactTransportAttempts.incrementAndGet() },
                ),
            ),
            agentModel,
        )
        val response = result.response?.let { searchResponse ->
            if (request.strategy.strategy == SearchStrategy.MANUAL_QUERY ||
                request.agentRequest.originalUserQuery.isBlank()
            ) {
                searchResponse
            } else {
                // SearchResponse.query is the request whose facts must be
                // answered. Keep planner/transport rewrites in normalizedQuery
                // and each result's queryUsed; answer sufficiency and the
                // final prompt must retain the exact typed question.
                searchResponse.copy(query = request.agentRequest.originalUserQuery)
            }
        }
        return result.copy(
            response = response,
            exactTransportAttempts = exactTransportAttempts.get(),
        )
    }

    private suspend fun executeRouted(
        request: SearchStrategyRouteRequest,
        agentModel: SearchAgentModel?,
    ): SearchStrategyRouteResult {
        val selected = request.strategy.strategy
        if (request.agentRequest.webSearchPolicy == GenerationRequestSnapshot.WebSearchPolicy.DISABLED) {
            return route(SearchRouteOutcome.SEARCH_DISABLED, null, selected, null)
        }
        if (selected != SearchStrategy.MANUAL_QUERY &&
            (request.agentRequest.requestId.isBlank() || request.agentRequest.originalUserQuery.isBlank())
        ) return route(SearchRouteOutcome.AGENT_FAILED, null, selected, null)
        if (!request.agentRequest.ownerStillActive()) return route(SearchRouteOutcome.OWNERSHIP_LOST, null, selected, null)
        if (!request.agentRequest.searchPermissionStillValid()) return route(SearchRouteOutcome.PERMISSION_REVOKED, null, selected, null)

        return try {
            val deadlineNanos = System.nanoTime() + request.agentRequest.limits.overallDeadlineMs * 1_000_000
            executeWithinDeadline(request, agentModel, deadlineNanos)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private suspend fun executeWithinDeadline(
        request: SearchStrategyRouteRequest,
        agentModel: SearchAgentModel?,
        deadlineNanos: Long,
    ): SearchStrategyRouteResult {
        val selected = request.strategy.strategy
        when (selected) {
            SearchStrategy.LEGACY_RULE_BASED -> {
                val remaining = remainingMillis(deadlineNanos)
                if (remaining <= 0) return route(SearchRouteOutcome.TIMEOUT, null, selected, null)
                var issuedQueries = 0
                var planningElapsedMs = 0L
                var searchTransportElapsedMs = 0L
                val result = withTimeoutOrNull(remaining) {
                    runLegacy(
                        request,
                        excluded = emptySet(),
                        additionalBudget = request.agentRequest.limits.maxIssuedQueries,
                        onQueryIssued = { issuedQueries++ },
                        onPlanningElapsed = { planningElapsedMs += it },
                        onSearchTransportElapsed = { searchTransportElapsedMs += it },
                    )
                } ?: return route(SearchRouteOutcome.TIMEOUT, null, selected, null, searchQueriesIssued = issuedQueries)
                    .copy(planningElapsedMs = planningElapsedMs, searchTransportElapsedMs = searchTransportElapsedMs)
                return result.toRoute(selected, executed = SearchStrategy.LEGACY_RULE_BASED)
                    .copy(
                        searchQueriesIssued = issuedQueries,
                        planningElapsedMs = planningElapsedMs,
                        searchTransportElapsedMs = searchTransportElapsedMs,
                    )
            }
            SearchStrategy.MANUAL_QUERY -> {
                val remaining = remainingMillis(deadlineNanos)
                if (remaining <= 0) return route(SearchRouteOutcome.TIMEOUT, null, selected, null)
                var issuedQueries = 0
                var searchTransportElapsedMs = 0L
                return withTimeoutOrNull(remaining) {
                    runManual(request, { issuedQueries++ }) { elapsed -> searchTransportElapsedMs += elapsed }
                }?.copy(searchTransportElapsedMs = searchTransportElapsedMs)
                    ?: route(SearchRouteOutcome.TIMEOUT, null, selected, null, searchQueriesIssued = issuedQueries)
                        .copy(searchTransportElapsedMs = searchTransportElapsedMs)
            }
            SearchStrategy.AGENT_HARNESS -> Unit
        }

        val agent = if (agentModel == null) {
            AgenticSearchResult(
                outcome = AgenticSearchOutcome.MODEL_UNAVAILABLE,
                evidence = null,
                diagnostics = AgenticSearchDiagnostics(
                    protocol = SearchAgentProtocol.STRUCTURED_INTENT,
                    planningCalls = 0,
                    issuedQueries = 0,
                    rounds = 0,
                    outcome = AgenticSearchOutcome.MODEL_UNAVAILABLE,
                    failureCode = request.agentUnavailableReasonCode ?: "planner_unavailable",
                    legacyFallbackAllowed = true,
                ),
            )
        } else {
            AgenticSearchHarness(agentModel, exactSearchTool, queryAuthorizer).execute(request.agentRequest)
        }
        val stillOwned = request.agentRequest.ownerStillActive()
        val stillAllowed = request.agentRequest.searchPermissionStillValid()
        if (!stillOwned) return route(SearchRouteOutcome.OWNERSHIP_LOST, null, selected, null, agentDiagnostics = agent.diagnostics, searchQueriesIssued = agent.diagnostics.issuedQueries)
        if (!stillAllowed) return route(SearchRouteOutcome.PERMISSION_REVOKED, null, selected, null, agentDiagnostics = agent.diagnostics, searchQueriesIssued = agent.diagnostics.issuedQueries)
        if (agent.outcome == AgenticSearchOutcome.PRIVACY_DENIED) {
            return route(SearchRouteOutcome.PRIVACY_DENIED, null, selected, SearchStrategy.AGENT_HARNESS, agentDiagnostics = agent.diagnostics, searchQueriesIssued = agent.diagnostics.issuedQueries)
        }
        if (agent.outcome == AgenticSearchOutcome.PERMISSION_REVOKED) {
            return route(SearchRouteOutcome.PERMISSION_REVOKED, null, selected, SearchStrategy.AGENT_HARNESS, agentDiagnostics = agent.diagnostics, searchQueriesIssued = agent.diagnostics.issuedQueries)
        }
        if (agent.outcome == AgenticSearchOutcome.OWNERSHIP_LOST) {
            return route(SearchRouteOutcome.OWNERSHIP_LOST, null, selected, SearchStrategy.AGENT_HARNESS, agentDiagnostics = agent.diagnostics, searchQueriesIssued = agent.diagnostics.issuedQueries)
        }
        if (agent.outcome == AgenticSearchOutcome.TIMEOUT) {
            return route(SearchRouteOutcome.TIMEOUT, agent.evidence, selected, SearchStrategy.AGENT_HARNESS, agentDiagnostics = agent.diagnostics, searchQueriesIssued = agent.diagnostics.issuedQueries)
        }
        if (agent.outcome == AgenticSearchOutcome.SEARCH_DISABLED) {
            return route(SearchRouteOutcome.SEARCH_DISABLED, null, selected, null, agentDiagnostics = agent.diagnostics, searchQueriesIssued = agent.diagnostics.issuedQueries)
        }

        val remainingBudget = (request.agentRequest.limits.maxIssuedQueries - agent.diagnostics.issuedQueries).coerceAtLeast(0)
        val reason = SearchStrategyFallbackPolicy.reason(
            strategy = request.strategy,
            webSearchPolicy = request.agentRequest.webSearchPolicy,
            agentResult = agent,
            requestStillOwned = stillOwned,
            permissionStillValid = stillAllowed,
            maxAdditionalQueries = remainingBudget,
        )
        if (reason == null) {
            val routeOutcome = when (agent.outcome) {
                AgenticSearchOutcome.COMPLETED -> SearchRouteOutcome.COMPLETED
                AgenticSearchOutcome.BUDGET_EXHAUSTED -> SearchRouteOutcome.AGENT_FAILED
                else -> SearchRouteOutcome.AGENT_FAILED
            }
            return route(routeOutcome, agent.evidence, selected, SearchStrategy.AGENT_HARNESS, agentDiagnostics = agent.diagnostics, searchQueriesIssued = agent.diagnostics.issuedQueries)
        }

        if (!request.agentRequest.ownerStillActive()) return route(SearchRouteOutcome.OWNERSHIP_LOST, null, selected, null, reason, agent.diagnostics, agent.diagnostics.issuedQueries)
        if (!request.agentRequest.searchPermissionStillValid()) return route(SearchRouteOutcome.PERMISSION_REVOKED, null, selected, null, reason, agent.diagnostics, agent.diagnostics.issuedQueries)
        val remainingTime = remainingMillis(deadlineNanos)
        if (remainingTime <= 0) return route(SearchRouteOutcome.TIMEOUT, agent.evidence, selected, SearchStrategy.AGENT_HARNESS, reason, agent.diagnostics, agent.diagnostics.issuedQueries)
        var fallbackQueriesIssued = 0
        var fallbackAttempted = false
        var fallbackPlanningElapsedMs = 0L
        var fallbackSearchTransportElapsedMs = 0L
        val fallback = withTimeoutOrNull(remainingTime) {
            runLegacy(
                request,
                agent.executedQueryIdentities,
                remainingBudget,
                onFallbackAttempted = { fallbackAttempted = true },
                onQueryIssued = { fallbackQueriesIssued++ },
                onPlanningElapsed = { fallbackPlanningElapsedMs += it },
                onSearchTransportElapsed = { fallbackSearchTransportElapsedMs += it },
            )
        } ?: return route(SearchRouteOutcome.TIMEOUT, agent.evidence, selected, SearchStrategy.AGENT_HARNESS, reason, agent.diagnostics, agent.diagnostics.issuedQueries + fallbackQueriesIssued)
            .copy(
                fallbackAttempted = fallbackAttempted,
                fallbackQueriesIssued = fallbackQueriesIssued,
                planningElapsedMs = agent.diagnostics.planningElapsedMs + fallbackPlanningElapsedMs,
                searchTransportElapsedMs = agent.diagnostics.searchTransportElapsedMs + fallbackSearchTransportElapsedMs,
            )
        if (fallback.outcome == SearchRouteOutcome.PRIVACY_DENIED ||
            fallback.outcome == SearchRouteOutcome.PERMISSION_REVOKED ||
            fallback.outcome == SearchRouteOutcome.OWNERSHIP_LOST
        ) {
            return fallback.copy(
                selectedStrategy = selected,
                fallbackReason = reason,
                fallbackAttempted = fallbackAttempted,
                fallbackQueriesIssued = fallbackQueriesIssued,
                agentDiagnostics = agent.diagnostics,
                searchQueriesIssued = agent.diagnostics.issuedQueries + fallbackQueriesIssued,
                planningElapsedMs = agent.diagnostics.planningElapsedMs + fallbackPlanningElapsedMs,
                searchTransportElapsedMs = agent.diagnostics.searchTransportElapsedMs + fallbackSearchTransportElapsedMs,
            )
        }
        return fallback.copy(
            selectedStrategy = selected,
            executedStrategy = SearchStrategy.LEGACY_RULE_BASED,
            fallbackReason = reason,
            fallbackAttempted = fallbackAttempted,
            fallbackQueriesIssued = fallbackQueriesIssued,
            agentDiagnostics = agent.diagnostics,
            searchQueriesIssued = agent.diagnostics.issuedQueries + fallbackQueriesIssued,
            planningElapsedMs = agent.diagnostics.planningElapsedMs + fallbackPlanningElapsedMs,
            searchTransportElapsedMs = agent.diagnostics.searchTransportElapsedMs + fallbackSearchTransportElapsedMs,
            outcome = if (fallback.response?.hasSemanticSuccess() == true) SearchRouteOutcome.FALLBACK_COMPLETED else SearchRouteOutcome.FALLBACK_FAILED,
        )
    }

    private suspend fun runManual(
        request: SearchStrategyRouteRequest,
        onQueryIssued: () -> Unit,
        onSearchTransportElapsed: (Long) -> Unit,
    ): SearchStrategyRouteResult {
        val selected = SearchStrategy.MANUAL_QUERY
        val manualQuery = when (val validation = request.strategy.manualQuery) {
            is ManualSearchQueryValidation.Valid -> validation.query.value
            ManualSearchQueryValidation.Missing -> return route(SearchRouteOutcome.MANUAL_QUERY_MISSING, null, selected, null)
            is ManualSearchQueryValidation.Invalid -> return route(SearchRouteOutcome.MANUAL_QUERY_INVALID, null, selected, null)
        }
        val authorized = queryAuthorizer.authorize(manualQuery, manualQuery) as? AgenticQueryAuthorization.Authorized
        if (authorized?.exactQuery != manualQuery) return route(SearchRouteOutcome.PRIVACY_DENIED, null, selected, null)
        if (!request.agentRequest.ownerStillActive()) return route(SearchRouteOutcome.OWNERSHIP_LOST, null, selected, null)
        if (!request.agentRequest.searchPermissionStillValid()) return route(SearchRouteOutcome.PERMISSION_REVOKED, null, selected, null)
        return try {
            onQueryIssued()
            val searchStartedAtNanos = System.nanoTime()
            val response = try {
                exactSearchTool.searchWithTransportObserver(
                    query = manualQuery,
                    requestId = request.agentRequest.requestId,
                    stillAuthorized = { request.agentRequest.ownerStillActive() && request.agentRequest.searchPermissionStillValid() },
                    onTransportAttempt = request.agentRequest.onExactTransportAttempt,
                )
            } finally {
                onSearchTransportElapsed(elapsedMillisSince(searchStartedAtNanos))
            }
            when {
                !request.agentRequest.ownerStillActive() -> route(SearchRouteOutcome.OWNERSHIP_LOST, null, selected, null, searchQueriesIssued = 1)
                !request.agentRequest.searchPermissionStillValid() -> route(SearchRouteOutcome.PERMISSION_REVOKED, null, selected, null, searchQueriesIssued = 1)
                response.hasSemanticSuccess() -> route(SearchRouteOutcome.COMPLETED, response, selected, selected, searchQueriesIssued = 1)
                else -> route(SearchRouteOutcome.AGENT_FAILED, response, selected, selected, searchQueriesIssued = 1)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure.isFatalError()) throw failure
            route(SearchRouteOutcome.AGENT_FAILED, null, selected, selected, searchQueriesIssued = 1)
        }
    }

    private suspend fun runLegacy(
        request: SearchStrategyRouteRequest,
        excluded: Set<String>,
        additionalBudget: Int,
        onFallbackAttempted: () -> Unit = {},
        onQueryIssued: () -> Unit = {},
        onPlanningElapsed: (Long) -> Unit = {},
        onSearchTransportElapsed: (Long) -> Unit = {},
    ): SearchStrategyRouteResult {
        if (!request.agentRequest.ownerStillActive()) return route(SearchRouteOutcome.OWNERSHIP_LOST, null, request.strategy.strategy, null)
        if (!request.agentRequest.searchPermissionStillValid()) return route(SearchRouteOutcome.PERMISSION_REVOKED, null, request.strategy.strategy, null)
        if (additionalBudget <= 0) return route(SearchRouteOutcome.FALLBACK_FAILED, null, request.strategy.strategy, null)
        val fallbackResult = try {
            onFallbackAttempted()
            legacyFallback.search(
                LegacySearchFallbackRequest(
                    agentRequest = request.agentRequest,
                    previousUserMessage = request.previousUserMessage,
                    safePriorUserTurns = request.safePriorUserTurns,
                    excludedQueryIdentities = excluded,
                    maxAdditionalQueries = additionalBudget,
                    onQueryIssued = onQueryIssued,
                    onPlanningElapsed = onPlanningElapsed,
                    onSearchTransportElapsed = onSearchTransportElapsed,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure.isFatalError()) throw failure
            LegacySearchFallbackResult.Failed("fallback_exception")
        }
        if (!request.agentRequest.ownerStillActive()) return route(SearchRouteOutcome.OWNERSHIP_LOST, null, request.strategy.strategy, null)
        if (!request.agentRequest.searchPermissionStillValid()) return route(SearchRouteOutcome.PERMISSION_REVOKED, null, request.strategy.strategy, null)
        return when (val result = fallbackResult) {
            is LegacySearchFallbackResult.Completed -> SearchStrategyRouteResult(
                outcome = if (result.response?.hasSemanticSuccess() == true) SearchRouteOutcome.COMPLETED else SearchRouteOutcome.FALLBACK_FAILED,
                response = result.response,
                selectedStrategy = request.strategy.strategy,
                executedStrategy = SearchStrategy.LEGACY_RULE_BASED,
            )
            LegacySearchFallbackResult.PrivacyDenied -> route(SearchRouteOutcome.PRIVACY_DENIED, null, request.strategy.strategy, null)
            LegacySearchFallbackResult.PermissionRevoked -> route(SearchRouteOutcome.PERMISSION_REVOKED, null, request.strategy.strategy, null)
            LegacySearchFallbackResult.OwnershipLost -> route(SearchRouteOutcome.OWNERSHIP_LOST, null, request.strategy.strategy, null)
            is LegacySearchFallbackResult.Failed -> route(SearchRouteOutcome.FALLBACK_FAILED, null, request.strategy.strategy, SearchStrategy.LEGACY_RULE_BASED)
        }
    }

    private fun SearchStrategyRouteResult.toRoute(selected: SearchStrategy, executed: SearchStrategy) = copy(
        selectedStrategy = selected,
        executedStrategy = executed,
        outcome = if (response?.hasSemanticSuccess() == true) SearchRouteOutcome.COMPLETED else outcome,
    )

    private fun remainingMillis(deadlineNanos: Long): Long =
        ceil((deadlineNanos - System.nanoTime()).coerceAtLeast(0) / 1_000_000.0).toLong()

    private fun route(
        outcome: SearchRouteOutcome,
        response: FusionSearchResponse?,
        selected: SearchStrategy,
        executed: SearchStrategy?,
        fallbackReason: SearchFallbackReason? = null,
        agentDiagnostics: AgenticSearchDiagnostics? = null,
        searchQueriesIssued: Int = 0,
        planningElapsedMs: Long = agentDiagnostics?.planningElapsedMs ?: 0,
        searchTransportElapsedMs: Long = agentDiagnostics?.searchTransportElapsedMs ?: 0,
    ) = SearchStrategyRouteResult(
        outcome = outcome,
        response = response,
        selectedStrategy = selected,
        executedStrategy = executed,
        fallbackReason = fallbackReason,
        agentDiagnostics = agentDiagnostics,
        searchQueriesIssued = searchQueriesIssued,
        planningElapsedMs = planningElapsedMs,
        searchTransportElapsedMs = searchTransportElapsedMs,
    )
}

/**
 * Legacy adapter over the established rule planner and exact FusionWebSearch
 * transport seam. Duplicate agent-issued queries are filtered before search.
 */
class RuleBasedLegacySearchFallback(
    private val exactSearchTool: AgenticExactSearchTool,
    private val queryAuthorizer: AgenticSearchQueryAuthorizer,
) : LegacySearchFallbackExecutor {
    override suspend fun search(request: LegacySearchFallbackRequest): LegacySearchFallbackResult {
        if (!request.agentRequest.ownerStillActive()) return LegacySearchFallbackResult.OwnershipLost
        if (!request.agentRequest.searchPermissionStillValid()) return LegacySearchFallbackResult.PermissionRevoked
        if (request.maxAdditionalQueries <= 0) return LegacySearchFallbackResult.Completed(null)
        val planningStartedAtNanos = System.nanoTime()
        val plan = try {
            RuleBasedWebSearchPlanner.plan(
                RuleBasedWebSearchPlannerInput(
                    currentUserInput = request.agentRequest.originalUserQuery,
                    previousUserMessage = request.previousUserMessage,
                    safePriorUserTurns = request.safePriorUserTurns,
                ),
            )
        } finally {
            request.onPlanningElapsed(elapsedMillisSince(planningStartedAtNanos))
        }
        var best: FusionSearchResponse? = null
        var issued = 0
        val seen = request.excludedQueryIdentities.toMutableSet()
        for (candidate in (listOf(plan.primaryQuery) + plan.alternateQueries).distinctBy(::queryIdentity)) {
            if (issued >= request.maxAdditionalQueries) break
            if (!request.agentRequest.ownerStillActive()) return LegacySearchFallbackResult.OwnershipLost
            if (!request.agentRequest.searchPermissionStillValid()) return LegacySearchFallbackResult.PermissionRevoked
            val identity = queryIdentity(candidate)
            if (identity in seen) continue
            val authorization = queryAuthorizer.authorize(request.agentRequest.originalUserQuery, candidate)
            val exactQuery = (authorization as? AgenticQueryAuthorization.Authorized)?.exactQuery
            if (exactQuery == null || exactQuery != candidate) return LegacySearchFallbackResult.PrivacyDenied
            if (queryIdentity(exactQuery) in seen) continue
            seen += queryIdentity(exactQuery)
            issued++
            request.onQueryIssued()
            val searchStartedAtNanos = System.nanoTime()
            val response = try {
                exactSearchTool.searchWithTransportObserver(
                    query = exactQuery,
                    requestId = request.agentRequest.requestId,
                    stillAuthorized = { request.agentRequest.ownerStillActive() && request.agentRequest.searchPermissionStillValid() },
                    onTransportAttempt = request.agentRequest.onExactTransportAttempt,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (failure.isFatalError()) throw failure
                continue
            } finally {
                request.onSearchTransportElapsed(elapsedMillisSince(searchStartedAtNanos))
            }
            if (!request.agentRequest.ownerStillActive()) return LegacySearchFallbackResult.OwnershipLost
            if (!request.agentRequest.searchPermissionStillValid()) return LegacySearchFallbackResult.PermissionRevoked
            if (response.hasSemanticSuccess()) return LegacySearchFallbackResult.Completed(response)
            if (best == null || response.results.size > best!!.results.size) best = response
        }
        return LegacySearchFallbackResult.Completed(best)
    }
}

internal fun queryIdentity(query: String): String = normalizeSearchQueryIdentity(query)

private fun elapsedMillisSince(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos).coerceAtLeast(0) / 1_000_000L
