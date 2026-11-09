package com.projectnuke.fusion.trust

/** The small, user-facing explanation attached to a completed assistant message. */
enum class TrustRoutingPresentationKind {
    NONE,
    WEB_CHECK,
    ABSTAIN,
    CLOUD_SUGGESTION,
}

data class TrustRoutingPresentation(
    val kind: TrustRoutingPresentationKind,
    val label: String? = null,
    val explanation: String? = null,
    val isVisible: Boolean,
    /** Deterministic assistant-side copy for routes that do not generate an answer. */
    val assistantResponse: String? = null,
    /**
     * Optional, non-dominant action label offered alongside a CLOUD_SUGGEST
     * explanation in local generation mode. It is a capability suggestion, not
     * a truth/factual claim, and it never auto-routes to a provider.
     */
    val suggestedAction: String? = null,
)

/**
 * Maps typed routing data to bounded copy. Routing is about the information
 * path, so this mapper intentionally has no confidence or verification state.
 */
object TrustRoutingPresentationMapper {
    fun map(
        decision: TrustRoutingDecision?,
        generationModeKey: String? = null,
    ): TrustRoutingPresentation {
        decision ?: return none()

        return when (decision.route) {
            TrustRoute.LOCAL -> none()
            TrustRoute.SEARCH -> TrustRoutingPresentation(
                kind = TrustRoutingPresentationKind.WEB_CHECK,
                label = "🌐 웹 확인",
                explanation = searchExplanation(decision.reason),
                isVisible = true,
            )
            TrustRoute.ABSTAIN -> TrustRoutingPresentation(
                kind = TrustRoutingPresentationKind.ABSTAIN,
                isVisible = true,
                assistantResponse = abstainResponse(decision.reason),
            )
            TrustRoute.CLOUD_SUGGEST -> {
                // An external provider is already selected by the user. Hide
                // this local-capability suggestion there so it cannot imply a
                // silent provider switch or a request to move content to cloud.
                if (generationModeKey == EXTERNAL_AI_API_MODE) {
                    TrustRoutingPresentation(
                        kind = TrustRoutingPresentationKind.CLOUD_SUGGESTION,
                        isVisible = false,
                    )
                } else {
                    TrustRoutingPresentation(
                        kind = TrustRoutingPresentationKind.CLOUD_SUGGESTION,
                        label = "더 강한 모델이 도움될 수 있어요",
                        explanation = "현재 설정으로도 계속 처리하지만, 복잡한 작업은 더 큰 모델이 유리할 수 있어요.",
                        isVisible = true,
                        suggestedAction = CLOUD_SUGGEST_ACTION_LABEL,
                    )
                }
            }
        }
    }

    private fun searchExplanation(reason: TrustRoutingReason): String = when (reason) {
        TrustRoutingReason.EXPLICIT_WEB_REQUEST -> "요청에 따라 웹 검색 경로를 사용했어요."
        TrustRoutingReason.AUTHORITY_SOURCE_REQUESTED -> "요청한 공식 출처를 확인하기 위해 웹 검색 경로를 선택했어요."
        TrustRoutingReason.MANUAL_SEARCH_ENABLED -> "검색 옵션이 켜져 있어 웹 검색 경로를 사용했어요."
        TrustRoutingReason.CURRENT_INFORMATION_REQUIRED,
        TrustRoutingReason.CURRENT_OR_RECENT -> "최신 정보가 필요할 수 있어 웹 확인 경로를 선택했어요."
        TrustRoutingReason.CHANGEABLE_FACT,
        TrustRoutingReason.SPECIFIC_MODERN_FACT,
        TrustRoutingReason.EXTERNAL_VERIFICATION_HELPFUL,
        TrustRoutingReason.UNCERTAIN_STORED_KNOWLEDGE -> "바뀔 수 있는 구체적인 정보를 확인하기 위해 웹 검색 경로를 선택했어요."
        else -> "필요한 정보를 확인하기 위해 웹 검색 경로를 선택했어요."
    }

    private fun abstainResponse(reason: TrustRoutingReason): String = when (reason) {
        TrustRoutingReason.FUTURE_UNKNOWABLE ->
            "정확하게 알 수 없는 미래 정보라 단정해서 답하기 어려워요.\n\n" +
                "예보, 공개 일정, 확률처럼 확인 가능한 정보라면 검색해서 도와드릴 수 있어요."
        else -> "정확한 답을 확인할 수 없는 요청이라 단정해서 답하기 어려워요."
    }

    private fun none() = TrustRoutingPresentation(
        kind = TrustRoutingPresentationKind.NONE,
        isVisible = false,
    )

    /** Capability suggestion copy, shared with the consent dialog model. */
    const val CLOUD_SUGGEST_ACTION_LABEL = "더 강한 모델로 다시 요청"

    private const val EXTERNAL_AI_API_MODE = "EXTERNAL_AI_API"
}
