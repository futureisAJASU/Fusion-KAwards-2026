package com.projectnuke.fusion.search

import com.projectnuke.fusion.chat.SearchStrategy

/**
 * User-facing labels for the web-search strategy.
 *
 * Every label describes the strategy that actually ran. A request that fell
 * back to Classic Search is never presented as an AI Search Agent run, and a
 * request whose agent produced no usable result is never presented as a
 * successful agent search.
 */
internal object SearchStrategyPresentationPolicy {
    fun choiceTitle(strategy: SearchStrategy): String = when (strategy) {
        SearchStrategy.AGENT_HARNESS -> "AI Search Agent"
        SearchStrategy.LEGACY_RULE_BASED -> "Classic Search"
        SearchStrategy.MANUAL_QUERY -> "Manual Search Query"
    }

    fun choiceDescription(strategy: SearchStrategy): String = when (strategy) {
        SearchStrategy.AGENT_HARNESS -> "모델이 필요한 검색을 계획하고 결과를 확인합니다."
        SearchStrategy.LEGACY_RULE_BASED -> "기존 규칙 기반 검색 계획을 사용합니다."
        SearchStrategy.MANUAL_QUERY -> "직접 입력한 검색어만 사용합니다."
    }

    /**
     * Notice for a request whose executed strategy differs from the selected
     * one, or whose agent run produced no usable result. Null when the
     * selected strategy ran and produced the answer's search evidence.
     */
    fun executedNotice(route: SearchStrategyRouteResult): String? = when {
        route.outcome == SearchRouteOutcome.CANCELLED || route.outcome == SearchRouteOutcome.OWNERSHIP_LOST ||
            route.outcome == SearchRouteOutcome.SEARCH_DISABLED -> null
        route.outcome == SearchRouteOutcome.PERMISSION_REVOKED -> "검색 권한이 취소되어 검색을 중단했습니다."
        route.outcome == SearchRouteOutcome.PRIVACY_DENIED -> "검색어를 현재 요청에 직접 입력하고 개인 정보를 제외해 주세요."
        route.fallbackAttempted && route.fallbackQueriesIssued == 0 && route.outcome == SearchRouteOutcome.TIMEOUT ->
            "Classic Search 전환이 시간 초과되어 검색 요청을 보내지 못했습니다."
        route.fallbackAttempted && route.fallbackQueriesIssued == 0 ->
            "Classic Search 전환을 시도했지만 검색 요청을 보내지 못했습니다."
        route.fallbackQueriesIssued > 0 && route.outcome == SearchRouteOutcome.TIMEOUT ->
            if (route.response.hasSemanticSuccess()) {
                "Classic Search가 시간 초과됐지만 확보된 검색 결과는 유지했습니다."
            } else {
                "Classic Search가 시간 초과되어 사용 가능한 결과를 확보하지 못했습니다."
            }
        route.fallbackQueriesIssued > 0 && route.outcome == SearchRouteOutcome.FALLBACK_FAILED ->
            "Classic Search를 시도했지만 사용 가능한 결과를 확보하지 못했습니다."
        route.executedStrategy == null -> null
        route.executedStrategy != route.selectedStrategy && route.fallbackQueriesIssued > 0 ->
            "${choiceTitle(route.selectedStrategy)} 대신 ${choiceTitle(route.executedStrategy)}로 검색했습니다."
        route.selectedStrategy == SearchStrategy.AGENT_HARNESS &&
            route.outcome == SearchRouteOutcome.AGENT_FAILED ->
            if (route.response.hasSemanticSuccess()) {
                "AI Search Agent가 실패했지만 사용 가능한 검색 결과는 확보했습니다."
            } else {
                "AI Search Agent가 사용 가능한 검색 결과를 찾지 못했습니다."
            }
        route.executedStrategy == SearchStrategy.AGENT_HARNESS &&
            route.outcome == SearchRouteOutcome.TIMEOUT ->
            if (route.response.hasSemanticSuccess()) {
                "AI Search Agent 검색이 시간 초과됐지만 확보된 검색 결과를 포함했습니다."
            } else {
                "AI Search Agent 검색이 시간 초과되어 사용 가능한 결과를 확보하지 못했습니다."
            }
        else -> null
    }
}
