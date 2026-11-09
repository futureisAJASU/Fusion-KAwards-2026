package com.projectnuke.fusion.search

enum class WebSearchProviderType {
    FREE_DEFAULT,
    NAVER,
    KAKAO_DAUM,
    EXA,
    BRAVE,
    CUSTOM_COMPATIBLE
}

enum class WebSearchMode {
    AUTO,
    MANUAL
}

data class WebSearchProviderConfig(
    val id: String,
    val displayName: String,
    val type: WebSearchProviderType,
    val isEnabled: Boolean = true,
    val baseUrl: String? = null,
    val apiKeySecretId: String? = null,
    val clientIdSecretId: String? = null,
    val clientSecretSecretId: String? = null,
    val defaultSearchCategory: String? = null,
    val allowFallbackInManualMode: Boolean = false,
    val priority: Int = 100,
    val noAuth: Boolean = false
)

data class WebSearchProviderRequirement(
    val type: WebSearchProviderType,
    val requiresApiKey: Boolean = false,
    val requiresClientId: Boolean = false,
    val requiresClientSecret: Boolean = false,
    val requiresBaseUrl: Boolean = false,
    val supportsNoAuth: Boolean = false
)

data class WebSearchExecutionTrace(
    val providerType: WebSearchProviderType,
    val providerDisplayName: String,
    val queryUsed: String,
    val httpStatus: Int? = null,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val parsedResultCount: Int = 0,
    val qualityScore: Double? = null,
    val fallbackReason: String? = null,
    val debugLabel: String? = null
)

data class WebSearchSource(
    val title: String,
    val source: String?,
    val url: String?,
    val snippet: String?,
    val publishedAt: String?,
    val providerType: WebSearchProviderType,
    val providerDisplayName: String,
    val searchCategory: String?,
    val queryUsed: String,
    val rank: Int,
    val score: Double? = null,
    val qualityHints: List<String> = emptyList()
)

data class WebSearchPlan(
    val intent: SearchIntent,
    val primaryQuery: String,
    val originalInput: String = primaryQuery,
    val resolvedTopic: String = primaryQuery,
    val alternateQueries: List<String> = emptyList(),
    val preferredProviderTypes: List<WebSearchProviderType> = emptyList(),
    val freshnessRequired: Boolean = false,
    val languageRegion: String = "ko-KR",
    val regionHint: String? = null,
    val reason: String = "rule_based",
    val modifiers: List<String> = emptyList()
)

data class WebSearchQuality(
    val isUsable: Boolean,
    val score: Double,
    val reasons: List<String>,
    val shouldTryFallback: Boolean,
    val answerSufficiency: SearchAnswerSufficiencyStatus = SearchAnswerSufficiencyStatus.NOT_REQUIRED,
) {
    /** Existing usability is retrieval usability, independent of answer support. */
    val retrievalUsable: Boolean get() = isUsable
    val answerSufficient: Boolean get() = answerSufficiency != SearchAnswerSufficiencyStatus.INSUFFICIENT
    val satisfiesAnswerRequest: Boolean get() = retrievalUsable && answerSufficient
}

fun WebSearchProviderType.defaultDisplayName(): String = when (this) {
    WebSearchProviderType.FREE_DEFAULT -> "무료 기본 검색"
    WebSearchProviderType.NAVER -> "Naver"
    WebSearchProviderType.KAKAO_DAUM -> "Kakao/Daum"
    WebSearchProviderType.EXA -> "Exa"
    WebSearchProviderType.BRAVE -> "Brave"
    WebSearchProviderType.CUSTOM_COMPATIBLE -> "Custom Search"
}
