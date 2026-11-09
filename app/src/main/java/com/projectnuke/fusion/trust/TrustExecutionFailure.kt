package com.projectnuke.fusion.trust

// C2-E bounded trust failure / retry UX.
//
// Pure typed representation outside Compose. Compose renders the resulting
// state; it never re-derives trust policy inline. Route selection alone is
// never presented as verification.

// Distinct stages of a SEARCH attempt. Route selection alone must never be
// rendered as verified; only EVIDENCE_PRODUCED means usable retrieval plus
// an independent Evidence evaluation.
enum class SearchVerificationState {
    ROUTE_SELECTED,
    SEARCH_ATTEMPTED,
    RETRIEVAL_OBTAINED,
    EVIDENCE_PRODUCED,
}

// User-safe recovery actions. Retry is always explicit, bound to the
// original request, single-flight, and never automatic.
sealed interface TrustRecoveryAction {
    data object None : TrustRecoveryAction
    data class RetrySearch(val requestId: String) : TrustRecoveryAction
    data class RetryCloud(val pendingEscalationKey: String) : TrustRecoveryAction
    data object Dismiss : TrustRecoveryAction
}

// Typed execution failures. ABSTAIN is intentionally absent: it is policy
// behavior, not an error state, and must never receive failure treatment.
sealed interface TrustExecutionFailure {
    val userMessage: String
    val recovery: TrustRecoveryAction
    val isErrorState: Boolean

    data class SearchRetrievalFailed(
        override val userMessage: String =
            "웹 확인에 실패했어요. 최신 정보를 확인할 수 없어 단정해서 답하기 어려워요.",
        override val recovery: TrustRecoveryAction = TrustRecoveryAction.None,
        override val isErrorState: Boolean = true,
        val verificationState: SearchVerificationState = SearchVerificationState.SEARCH_ATTEMPTED,
    ) : TrustExecutionFailure

    data class SearchNoUsableResult(
        override val userMessage: String =
            "웹에서 usable한 확인 결과를 얻지 못했어요. 최신 정보를 확인할 수 없어 단정해서 답하기 어려워요.",
        override val recovery: TrustRecoveryAction = TrustRecoveryAction.None,
        override val isErrorState: Boolean = true,
        val verificationState: SearchVerificationState = SearchVerificationState.SEARCH_ATTEMPTED,
    ) : TrustExecutionFailure

    // Recoverable classifier fallback stays deterministic and quiet. Raw
    // failure details belong in diagnostics only, never in user copy.
    data class ClassifierFallback(
        val fallbackRoute: TrustRoute,
        override val userMessage: String = "",
        override val recovery: TrustRecoveryAction = TrustRecoveryAction.None,
        override val isErrorState: Boolean = false,
    ) : TrustExecutionFailure

    data class CloudEscalationFailed(
        override val userMessage: String =
            "외부 AI 요청에 실패했어요. 기존 로컬 답변을 그대로 유지해요.",
        override val recovery: TrustRecoveryAction = TrustRecoveryAction.None,
        override val isErrorState: Boolean = true,
        val preservesLocalAnswer: Boolean = true,
        val autoRetried: Boolean = false,
        val switchedProvider: Boolean = false,
    ) : TrustExecutionFailure
}

// Cloud escalation failure details. A consented external attempt that fails
// keeps the original local answer, never auto-switches providers, never
// retries automatically, and never mutates the default generation mode.
data class CloudEscalationFailure(
    val preservesLocalAnswer: Boolean = true,
    val autoRetried: Boolean = false,
    val switchedProvider: Boolean = false,
    val reportedSuccess: Boolean = false,
    val mutatedDefaultMode: Boolean = false,
    val retry: TrustRecoveryAction = TrustRecoveryAction.None,
)

internal object TrustFailureBounds {
    const val MAX_SEARCH_RETRY_ATTEMPTS = 1
    const val MAX_CLOUD_RETRY_ATTEMPTS = 1
}

// Single-flight guard for explicit retries. A retry must not duplicate user
// turns and must not mutate a successful previous response until the
// replacement succeeds.
class TrustRetryGate {
    private val held = java.util.concurrent.atomic.AtomicBoolean(false)
    fun tryEnter(): Boolean = held.compareAndSet(false, true)
    fun release() { held.set(false) }
}

object TrustFailurePolicy {
    // SEARCH with failed or unusable retrieval must not silently become a
    // stale local factual answer. Returns an explicit failure state bound to
    // the original request; the caller keeps the previous stable answer.
    fun searchFailure(
        decision: TrustRoutingDecision?,
        attempted: Boolean,
        usableResult: Boolean,
        requestId: String,
    ): TrustExecutionFailure {
        require(decision?.route == TrustRoute.SEARCH) {
            "searchFailure applies only to frozen SEARCH route"
        }
        return if (!attempted || !usableResult) {
            if (!attempted) {
                TrustExecutionFailure.SearchRetrievalFailed(
                    recovery = TrustRecoveryAction.RetrySearch(requestId),
                )
            } else {
                TrustExecutionFailure.SearchNoUsableResult(
                    recovery = TrustRecoveryAction.RetrySearch(requestId),
                )
            }
        } else {
            // Usable retrieval exists; no failure. Represent as non-error with
            // no recovery so callers cannot mistake route selection for proof.
            TrustExecutionFailure.ClassifierFallback(
                fallbackRoute = TrustRoute.SEARCH,
                userMessage = "",
            )
        }
    }

    // Explicit retry keeps the previous stable state until replacement
    // succeeds. A failed retry must leave the stable answer intact.
    fun resolveAfterRetry(
        previousStableAnswer: String,
        candidateAnswer: String?,
        succeeded: Boolean,
    ): String = if (succeeded && !candidateAnswer.isNullOrEmpty()) candidateAnswer else previousStableAnswer

    // Classifier fallback remains deterministic and quiet. User copy reflects
    // only the final safe routing consequence, never internals.
    fun classifierFallbackMessage(fallbackDecision: TrustRoutingDecision): String = when (fallbackDecision.route) {
        TrustRoute.LOCAL -> ""
        TrustRoute.SEARCH -> "웹 확인 경로로 계속 진행해요."
        TrustRoute.ABSTAIN -> "정확한 답을 확인할 수 없는 요청이라 단정해서 답하기 어려워요."
        TrustRoute.CLOUD_SUGGEST -> "현재 설정으로도 계속 처리해요."
    }

    // Strips raw classifier internals from anything user-visible. Diagnostics
    // may retain them; UX must not show enum names, stack traces, parser
    // internals, or classifier prompts.
    fun sanitizeClassifierDiagnostics(raw: String): String {
        var out = raw
        // Remove Java/Kotlin stack frames.
        out = out.lines().filterNot { it.trimStart().startsWith("at ") }.joinToString("\n")
        val forbidden = listOf(
            "TrustRoute.", "TrustRoutingReason.", "TrustRoutingSignal.",
            "TrustClassifierReason.", "Exception", "StackTrace",
            "classifier prompt", "parser internals",
        )
        for (token in forbidden) {
            out = out.replace(token, "[redacted]", ignoreCase = true)
        }
        return out
    }

    fun containsClassifierInternals(userVisible: String): Boolean {
        val markers = listOf(
            "TrustRoute", "TrustRoutingReason", "TrustRoutingSignal",
            "TrustClassifierReason", "Exception", "at com.", "at java.",
            "classifier prompt", "parser internals",
        )
        return markers.any { userVisible.contains(it, ignoreCase = true) }
    }

    // Cloud failure preserves the local answer with bounded explicit retry.
    fun cloudFailure(pendingKey: String): CloudEscalationFailure = CloudEscalationFailure(
        preservesLocalAnswer = true,
        autoRetried = false,
        switchedProvider = false,
        reportedSuccess = false,
        mutatedDefaultMode = false,
        retry = TrustRecoveryAction.RetryCloud(pendingKey),
    )

    fun cloudFailureAsExecutionFailure(pendingKey: String): TrustExecutionFailure =
        TrustExecutionFailure.CloudEscalationFailed(
            recovery = TrustRecoveryAction.RetryCloud(pendingKey),
        )

    // ABSTAIN is intentional policy, not a provider failure. It must not be
    // rendered with generic red failure treatment.
    fun isAbstainError(): Boolean = false

    fun abstainNotice(reason: TrustRoutingReason): String = when (reason) {
        TrustRoutingReason.FUTURE_UNKNOWABLE ->
            "정확하게 알 수 없는 미래 정보라 단정해서 답하기 어려워요."
        else -> "정확한 답을 확인할 수 없는 요청이라 단정해서 답하기 어려워요."
    }
}
