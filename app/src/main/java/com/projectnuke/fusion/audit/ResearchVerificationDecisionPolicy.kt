package com.projectnuke.fusion.audit

/**
 * KEIT P0-01: a pure, conservative decision contract for a DRAFT research answer.
 *
 * This is deliberately not wired to ChatScreen persistence yet. Callers must
 * verify a draft BEFORE persisting a final assistant message, enforce ownership,
 * and obtain explicit search authorization before executing SEARCH_AGAIN.
 *
 * NLI is a fallible evidence relation classifier, never a factual truth oracle.
 * A heuristic claim splitter cannot set [ResearchVerificationDecisionInput.authoritativeCoverage].
 */
internal enum class ResearchVerificationAction { FINAL, REVISE, SEARCH_AGAIN, ABSTAIN }

internal enum class ResearchVerificationReason {
    NON_FACTUAL_ANSWER,
    COMPLETE_EVIDENCE_SUPPORT,
    CONTRADICTED_CLAIM,
    CONFLICTING_EVIDENCE,
    INSUFFICIENT_EVIDENCE,
    SEARCH_NOT_AUTHORIZED,
    REVISION_BUDGET_EXHAUSTED,
    UNCERTAIN_CLAIM_EXTRACTION,
    INCOMPLETE_CLAIM_COVERAGE,
    VERIFIER_UNAVAILABLE,
    VERIFIER_RESULT_INCOMPLETE,
    EXPERIMENTAL_BACKEND_NOT_QUALIFIED,
}

internal data class ResearchVerificationDecisionInput(
    /** Independently enumerated atomic factual assertions in the draft. */
    val factualClaimIds: Set<String>,
    val audit: AnswerAuditExecutionSnapshot?,
    /** True only if an independent extraction/coverage authority certified the entire draft. */
    val authoritativeCoverage: Boolean,
    val candidateWindowTruncated: Boolean = false,
    val overlengthSegmentsExcluded: Boolean = false,
    /** Owned by the generation request, never globally shared across conversations. */
    val remainingRevisionAttempts: Int = 0,
    /** Set only after exact, identity-bound, explicit authorization is validated. */
    val hasExactSearchAuthorization: Boolean = false,
    /** Evaluation-only opt-in. Must remain false for unqualified production models. */
    val allowExperimentalBackendInEvaluation: Boolean = false,
) {
    init {
        require(factualClaimIds.all { it.isNotBlank() })
        require(remainingRevisionAttempts >= 0)
    }
}

internal data class ResearchVerificationDecision(
    val action: ResearchVerificationAction,
    val reason: ResearchVerificationReason,
    val factualClaimCount: Int,
    val observedClaimCount: Int,
) {
    /** FINAL does not imply fact checking unless the entire evidence gate passed. */
    val isFullyEvidenceSupported: Boolean
        get() = action == ResearchVerificationAction.FINAL &&
            reason == ResearchVerificationReason.COMPLETE_EVIDENCE_SUPPORT
}

/**
 * Does not run inference, search, change the draft, or mutate a durable response.
 * In particular, INSUFFICIENT and UNAVAILABLE must never be relabeled FALSE.
 */
internal object ResearchVerificationDecisionPolicy {
    fun decide(input: ResearchVerificationDecisionInput): ResearchVerificationDecision {
        val total = input.factualClaimIds.size
        val claims = input.audit?.claims.orEmpty()
        fun result(action: ResearchVerificationAction, reason: ResearchVerificationReason) =
            ResearchVerificationDecision(action, reason, total, claims.size)

        // Truncated/overlength windows can hide arbitrary additional factual claims.
        if (input.candidateWindowTruncated || input.overlengthSegmentsExcluded) {
            return result(ResearchVerificationAction.ABSTAIN, ResearchVerificationReason.UNCERTAIN_CLAIM_EXTRACTION)
        }
        if (total == 0) {
            // A contradictory or stray verifier claim cannot coexist with a
            // certified zero-factual-claims extraction.
            if (claims.isNotEmpty()) {
                return result(ResearchVerificationAction.ABSTAIN, ResearchVerificationReason.INCOMPLETE_CLAIM_COVERAGE)
            }
            return if (input.authoritativeCoverage) {
                result(ResearchVerificationAction.FINAL, ResearchVerificationReason.NON_FACTUAL_ANSWER)
            } else {
                result(ResearchVerificationAction.ABSTAIN, ResearchVerificationReason.INCOMPLETE_CLAIM_COVERAGE)
            }
        }
        if (input.audit == null || claims.any { it.backend.availability != VerifierBackendAvailability.AVAILABLE }) {
            return result(ResearchVerificationAction.ABSTAIN, ResearchVerificationReason.VERIFIER_UNAVAILABLE)
        }
        // A live descriptor can still return Unavailable for every pair when the
        // requested assets are missing. That is backend unavailability, not an
        // incomplete relation and never a contradiction.
        if (claims.isNotEmpty() && claims.all { claim ->
                claim.pairExecutions.isNotEmpty() &&
                    claim.pairExecutions.all { it.result == VerifierBackendResult.Unavailable }
            }
        ) {
            return result(ResearchVerificationAction.ABSTAIN, ResearchVerificationReason.VERIFIER_UNAVAILABLE)
        }
        if (!input.allowExperimentalBackendInEvaluation && claims.any { it.backend.experimental }) {
            return result(ResearchVerificationAction.ABSTAIN, ResearchVerificationReason.EXPERIMENTAL_BACKEND_NOT_QUALIFIED)
        }
        // A missing/extra/duplicate claim can never be interpreted as full coverage.
        if (claims.map { it.claimId }.toSet() != input.factualClaimIds || claims.size != total) {
            return result(ResearchVerificationAction.ABSTAIN, ResearchVerificationReason.INCOMPLETE_CLAIM_COVERAGE)
        }
        // Fail closed on partial pair execution even when an earlier pair was SUPPORTED.
        if (claims.any { !it.hasCompleteVerification }) {
            return result(ResearchVerificationAction.ABSTAIN, ResearchVerificationReason.VERIFIER_RESULT_INCOMPLETE)
        }

        val conflict = claims.any { it.aggregateStatus == AuditAggregateStatus.CONFLICTING_EVIDENCE }
        val contradicted = claims.any { it.aggregateStatus == AuditAggregateStatus.CONTRADICTED }
        if (conflict || contradicted) {
            return if (input.remainingRevisionAttempts > 0) {
                result(
                    ResearchVerificationAction.REVISE,
                    if (conflict) ResearchVerificationReason.CONFLICTING_EVIDENCE
                    else ResearchVerificationReason.CONTRADICTED_CLAIM,
                )
            } else {
                result(ResearchVerificationAction.ABSTAIN, ResearchVerificationReason.REVISION_BUDGET_EXHAUSTED)
            }
        }

        if (claims.any { it.aggregateStatus == AuditAggregateStatus.NOT_VERIFIED }) {
            return result(ResearchVerificationAction.ABSTAIN, ResearchVerificationReason.VERIFIER_RESULT_INCOMPLETE)
        }
        if (claims.any { it.aggregateStatus == AuditAggregateStatus.INSUFFICIENT }) {
            return if (input.hasExactSearchAuthorization) {
                result(ResearchVerificationAction.SEARCH_AGAIN, ResearchVerificationReason.INSUFFICIENT_EVIDENCE)
            } else {
                result(ResearchVerificationAction.ABSTAIN, ResearchVerificationReason.SEARCH_NOT_AUTHORIZED)
            }
        }

        // "All audited claims supported" is NOT the same as "all answer claims audited".
        if (!input.authoritativeCoverage) {
            return result(ResearchVerificationAction.ABSTAIN, ResearchVerificationReason.INCOMPLETE_CLAIM_COVERAGE)
        }
        return if (claims.all { it.aggregateStatus == AuditAggregateStatus.SUPPORTED }) {
            result(ResearchVerificationAction.FINAL, ResearchVerificationReason.COMPLETE_EVIDENCE_SUPPORT)
        } else {
            result(ResearchVerificationAction.ABSTAIN, ResearchVerificationReason.VERIFIER_RESULT_INCOMPLETE)
        }
    }
}
