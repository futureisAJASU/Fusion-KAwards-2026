package com.projectnuke.fusion.search

import android.util.Log
import com.projectnuke.fusion.chat.SearchStrategy

/** Bounded per-request diagnostics. It deliberately contains no query or source text. */
data class SearchStrategyDiagnosticEvent(
    val selectedStrategy: SearchStrategy,
    val executedStrategy: SearchStrategy?,
    val adapter: SearchAgentProtocol?,
    val planningCalls: Int?,
    val searchQueriesIssued: Int?,
    val searchRounds: Int?,
    val fallbackUsed: Boolean,
    val fallbackReason: SearchFallbackReason?,
    val sourceCount: Int?,
    val deadlineMs: Long,
    /** This measures only the search strategy stage, before final answer generation. */
    val elapsedMs: Long,
    val outcome: SearchRouteOutcome,
    val fallbackEligible: Boolean = fallbackReason != null,
    val fallbackAttempted: Boolean = fallbackUsed,
    val fallbackTransportExecuted: Boolean = fallbackUsed,
    val fallbackCompleted: Boolean = outcome == SearchRouteOutcome.FALLBACK_COMPLETED,
    val planningElapsedMs: Long? = null,
    val searchTransportElapsedMs: Long? = null,
    /**
     * Bounded, privacy-safe reason code explaining why the agent did not
     * produce usable evidence. It never carries query, source or model text.
     */
    val agentReasonCode: String? = null,
    /** True when a bound (decision rounds or issued queries) stopped the agent. */
    val budgetLimitHit: Boolean = false,
) {
    init {
        require(planningCalls == null || planningCalls in 0..3)
        require(searchQueriesIssued == null || searchQueriesIssued in 0..4)
        require(searchRounds == null || searchRounds in 0..3)
        require(sourceCount == null || sourceCount in 0..16)
        require(deadlineMs in 1_000..180_000)
        require(elapsedMs in 0..180_000)
        require(planningElapsedMs == null || planningElapsedMs in 0..180_000)
        require(searchTransportElapsedMs == null || searchTransportElapsedMs in 0..180_000)
        require(agentReasonCode == null || agentReasonCode.length <= MAX_AGENT_REASON_CODE_CHARS)
        require(agentReasonCode == null || agentReasonCode.isValidReasonCode())
    }

    /** Safe for internal logs: values are enums, bounded counts, and durations only. */
    fun toSafeLogLine(): String = listOf(
        "selected=$selectedStrategy",
        "executed=${executedStrategy ?: "NONE"}",
        "adapter=${adapter ?: "NONE"}",
        "planning=${planningCalls ?: "UNKNOWN"}",
        "queries=${searchQueriesIssued ?: "UNKNOWN"}",
        "rounds=${searchRounds ?: "UNKNOWN"}",
        "fallback=$fallbackUsed",
        "fallbackReason=${fallbackReason ?: "NONE"}",
        "fallbackEligible=$fallbackEligible",
        "fallbackAttempted=$fallbackAttempted",
        "fallbackTransport=$fallbackTransportExecuted",
        "fallbackCompleted=$fallbackCompleted",
        "sources=${sourceCount ?: "UNKNOWN"}",
        "deadlineMs=$deadlineMs",
        "elapsedMs=$elapsedMs",
        "planningMs=${planningElapsedMs ?: "UNKNOWN"}",
        "searchTransportMs=${searchTransportElapsedMs ?: "UNKNOWN"}",
        "agentReason=${agentReasonCode ?: "NONE"}",
        "budgetLimitHit=$budgetLimitHit",
        "outcome=$outcome",
    ).joinToString(" ")

    companion object {
        const val MAX_AGENT_REASON_CODE_CHARS = 48

        /**
         * Keeps only a bounded, identifier-shaped reason code. Anything else
         * is dropped rather than logged, so an unexpected value can never
         * smuggle query, source or model text into diagnostics.
         */
        internal fun sanitizeReasonCode(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.length > MAX_AGENT_REASON_CODE_CHARS) return null
            if (!trimmed.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
            return trimmed
        }

        private fun String.isValidReasonCode(): Boolean =
            all { it.isLetterOrDigit() || it == '_' || it == '-' }

        internal fun from(
            route: SearchStrategyRouteResult,
            deadlineMs: Long,
            elapsedMs: Long,
        ) = SearchStrategyDiagnosticEvent(
            selectedStrategy = route.selectedStrategy,
            executedStrategy = route.executedStrategy,
            adapter = route.agentDiagnostics?.protocol,
            planningCalls = route.agentDiagnostics?.planningCalls?.coerceIn(0, 3) ?: 0,
            searchQueriesIssued = route.searchQueriesIssued.coerceIn(0, 4),
            searchRounds = route.agentDiagnostics?.rounds?.coerceIn(0, 3) ?: 0,
            fallbackUsed = route.fallbackQueriesIssued > 0,
            fallbackReason = route.fallbackReason,
            fallbackEligible = route.fallbackReason != null,
            fallbackAttempted = route.fallbackAttempted,
            fallbackTransportExecuted = route.fallbackQueriesIssued > 0,
            fallbackCompleted = route.outcome == SearchRouteOutcome.FALLBACK_COMPLETED,
            sourceCount = route.response?.sources?.size?.coerceIn(0, 16) ?: 0,
            deadlineMs = deadlineMs.coerceIn(1_000, 180_000),
            elapsedMs = elapsedMs.coerceIn(0, 180_000),
            outcome = route.outcome,
            planningElapsedMs = route.planningElapsedMs.coerceIn(0, 180_000),
            searchTransportElapsedMs = route.searchTransportElapsedMs.coerceIn(0, 180_000),
            agentReasonCode = sanitizeReasonCode(route.agentDiagnostics?.failureCode),
            budgetLimitHit = route.agentDiagnostics?.outcome == AgenticSearchOutcome.BUDGET_EXHAUSTED,
        )
    }
}

fun interface SearchStrategyDiagnosticsSink {
    fun record(event: SearchStrategyDiagnosticEvent)
}

internal object AndroidSearchStrategyDiagnosticsSink : SearchStrategyDiagnosticsSink {
    override fun record(event: SearchStrategyDiagnosticEvent) {
        Log.i(TAG, event.toSafeLogLine())
    }

    private const val TAG = "FusionSearchStrategy"
}
