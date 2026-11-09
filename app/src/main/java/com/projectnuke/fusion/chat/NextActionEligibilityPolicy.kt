package com.projectnuke.fusion.chat

import com.projectnuke.fusion.trust.TrustRoutingPresentationKind

/**
 * Contextual next-action ("다음에 해볼까요?") trust/failure eligibility.
 *
 * Pure, Android-free gate evaluated AFTER the canonical visible answer is
 * known: an ABSTAIN presentation may carry visible non-blank fallback text,
 * but ordinary recommendations must still stay hidden for it, for an
 * explicit failed-search recovery state, and while STUDY interaction mode
 * owns the contextual action surface (the Study tools replace the ordinary
 * F2 row). A CLOUD_SUGGESTION backed by a real local answer stays eligible.
 *
 * Typed state only: callers pass the already-decoded
 * [TrustRoutingPresentationKind] and the typed recovery/mode flags. No
 * message-string inspection happens here.
 */
internal object NextActionEligibilityPolicy {
    fun isEligible(
        routingKind: TrustRoutingPresentationKind,
        isRetrySearchRecovery: Boolean,
        isStudyMode: Boolean,
    ): Boolean {
        if (routingKind == TrustRoutingPresentationKind.ABSTAIN) return false
        if (isRetrySearchRecovery) return false
        if (isStudyMode) return false
        return true
    }
}
