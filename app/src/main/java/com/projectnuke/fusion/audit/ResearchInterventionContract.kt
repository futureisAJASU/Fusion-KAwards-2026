package com.projectnuke.fusion.audit

import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.ui.ResearchDraftEvidenceStatus
import com.projectnuke.fusion.ui.ResearchDraftResponseVersionTarget
import com.projectnuke.fusion.ui.ResearchDraftShadowDiagnostic
import com.projectnuke.fusion.ui.ResearchDraftVerifierStatus

/**
 * KEIT P2: explicit, claim-scoped intervention contract.
 *
 * This is deliberately separate from [ResearchVerificationDecisionPolicy].
 * That policy answers "may the whole answer be certified?" and stays
 * conservative. This contract answers "may one exactly identified claim be
 * corrected, re-searched, or called out?" without implying whole-answer
 * verification.
 */
internal enum class ResearchInterventionAction {
    DELIVER_UNVERIFIED,
    DELIVER_WITH_SCOPED_AUDIT,
    REVISE,
    SEARCH_AGAIN,
    ABSTAIN,
    FINAL_FULLY_VERIFIED,
}

internal enum class ResearchInterventionReason {
    NON_FACTUAL_ANSWER,
    SUPPORTED_CLAIMS_SCOPED,
    SUPPORTED_CLAIMS_COVERAGE_INCOMPLETE,
    CONTRADICTED_CLAIM,
    CONFLICTING_EVIDENCE,
    INSUFFICIENT_EVIDENCE,
    NO_EVIDENCE_FOR_CLAIM,
    SEARCH_NOT_AUTHORIZED,
    SEARCH_ATTEMPT_FAILED,
    REVISION_BUDGET_EXHAUSTED,
    REVISION_FAILED,
    REVISION_UNSUPPORTED,
    CORRECTION_APPLIED,
    UNCERTAIN_CLAIM_EXTRACTION,
    INCOMPLETE_CLAIM_COVERAGE,
    VERIFIER_UNAVAILABLE,
    VERIFIER_TIMEOUT,
    VERIFIER_RESULT_INCOMPLETE,
    EXPERIMENTAL_BACKEND_NOT_QUALIFIED,
    EVIDENCE_MISSING,
    STALE_DRAFT,
    OWNERSHIP_LOST,
    INTERVENTION_TIMEOUT,
    SEARCH_DENIED,
    INSUFFICIENT_AFTER_RETRIEVAL,
}

/** A bounded excerpt bound to the exact evidence identity used by the verifier. */
internal class ResearchDraftEvidenceExcerpt internal constructor(
    val evidenceIdentity: String,
    val title: String,
    val url: String,
    val source: String,
    val providerDisplayName: String,
    /** Untrusted source text; never persisted in audit records or logged. */
    private val excerpt: String,
) {
    fun promptText(maxChars: Int): String {
        val bounded = excerpt.take(maxChars.coerceAtLeast(1))
        return listOf(title, source, url, bounded)
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")
    }

    override fun toString(): String =
        "ResearchDraftEvidenceExcerpt(evidenceIdentity=$evidenceIdentity, title=$title, url=$url)"
}

internal enum class ResearchDraftClaimFindingStatus {
    SUPPORTED,
    CONTRADICTED,
    CONFLICTING,
    INSUFFICIENT,
    UNVERIFIABLE,
}

internal data class ResearchDraftClaimFinding(
    val claimId: String,
    val claimText: String,
    val claimTextSha256: String,
    val status: ResearchDraftClaimFindingStatus,
    val evidence: List<ResearchDraftEvidenceExcerpt>,
) {
    init {
        require(claimId.isNotBlank())
        require(claimText.isNotBlank())
        require(claimTextSha256.isNotBlank())
    }
}

/**
 * Claim-scoped decision. [wholeAnswerCertified] may only be true for
 * [ResearchInterventionAction.FINAL_FULLY_VERIFIED], which requires an
 * independent coverage authority that the current heuristic extractor cannot
 * provide.
 */
internal data class ClaimScopedInterventionDecision(
    val action: ResearchInterventionAction,
    val reason: ResearchInterventionReason,
    val finding: ResearchDraftClaimFinding?,
    val revisionAttemptsRemaining: Int,
    val retrievalAttemptsRemaining: Int,
    val wholeAnswerCertified: Boolean,
) {
    init {
        require(revisionAttemptsRemaining >= 0)
        require(retrievalAttemptsRemaining >= 0)
        require(!wholeAnswerCertified || action == ResearchInterventionAction.FINAL_FULLY_VERIFIED)
        require(action != ResearchInterventionAction.FINAL_FULLY_VERIFIED || wholeAnswerCertified)
    }

    val isClaimScoped: Boolean
        get() = action != ResearchInterventionAction.FINAL_FULLY_VERIFIED
}

/** Explicit activation seam. Production construction stays OFF by default. */
internal fun interface ResearchInterventionActivationPolicy {
    fun enabledFor(
        request: GenerationRequestSnapshot,
        responseVersionTarget: ResearchDraftResponseVersionTarget?,
    ): Boolean

    companion object {
        val DISABLED = ResearchInterventionActivationPolicy { _, _ -> false }
    }
}

internal object ClaimScopedResearchInterventionPolicy {
    fun decide(
        diagnostic: ResearchDraftShadowDiagnostic,
        remainingRevisionAttempts: Int,
        remainingRetrievalAttempts: Int,
        searchAuthorizable: Boolean,
    ): ClaimScopedInterventionDecision {
        require(remainingRevisionAttempts >= 0)
        require(remainingRetrievalAttempts >= 0)

        fun result(
            action: ResearchInterventionAction,
            reason: ResearchInterventionReason,
            finding: ResearchDraftClaimFinding? = null,
            wholeAnswerCertified: Boolean = false,
        ) = ClaimScopedInterventionDecision(
            action = action,
            reason = reason,
            finding = finding,
            revisionAttemptsRemaining = remainingRevisionAttempts,
            retrievalAttemptsRemaining = remainingRetrievalAttempts,
            wholeAnswerCertified = wholeAnswerCertified,
        )

        // A whole-answer certificate is only reachable through a real coverage
        // authority. The current heuristic extractor always reports false.
        if (diagnostic.authoritativeCoverage &&
            diagnostic.wholeAnswerVerificationClaimed &&
            diagnostic.decision.isFullyEvidenceSupported
        ) {
            return result(
                ResearchInterventionAction.FINAL_FULLY_VERIFIED,
                ResearchInterventionReason.SUPPORTED_CLAIMS_SCOPED,
                wholeAnswerCertified = true,
            )
        }

        val findings = diagnostic.claimFindings.filter { it.claimId in diagnostic.checkedClaimIds }
        if (diagnostic.decision.factualClaimCount == 0 &&
            findings.isEmpty() &&
            diagnostic.expectedPairCount == 0
        ) {
            return result(
                ResearchInterventionAction.DELIVER_UNVERIFIED,
                ResearchInterventionReason.NON_FACTUAL_ANSWER,
            )
        }
        val contradicted = findings.firstOrNull {
            it.status == ResearchDraftClaimFindingStatus.CONTRADICTED
        }
        val conflicting = findings.firstOrNull {
            it.status == ResearchDraftClaimFindingStatus.CONFLICTING
        }
        // Source disagreement does not establish which side is accurate.
        // Resolve conflicts before ordinary contradictions when both appear
        // in one answer, so a selected source can never drive an automatic
        // correction while the verifier reports an unresolved disagreement.
        if (conflicting != null) {
            return result(
                ResearchInterventionAction.ABSTAIN,
                ResearchInterventionReason.CONFLICTING_EVIDENCE,
                conflicting,
            )
        }

        if (contradicted != null) {
            val finding = contradicted
            return if (remainingRevisionAttempts > 0) {
                result(ResearchInterventionAction.REVISE, ResearchInterventionReason.CONTRADICTED_CLAIM, finding)
            } else {
                result(
                    ResearchInterventionAction.ABSTAIN,
                    ResearchInterventionReason.REVISION_BUDGET_EXHAUSTED,
                    finding,
                )
            }
        }

        when (diagnostic.verifierStatus) {
            ResearchDraftVerifierStatus.TIMEOUT ->
                return result(ResearchInterventionAction.DELIVER_UNVERIFIED, ResearchInterventionReason.VERIFIER_TIMEOUT)
            ResearchDraftVerifierStatus.UNAVAILABLE ->
                return result(ResearchInterventionAction.DELIVER_UNVERIFIED, ResearchInterventionReason.VERIFIER_UNAVAILABLE)
            ResearchDraftVerifierStatus.FAILED,
            ResearchDraftVerifierStatus.PARTIAL,
            -> return result(
                ResearchInterventionAction.DELIVER_UNVERIFIED,
                ResearchInterventionReason.VERIFIER_RESULT_INCOMPLETE,
            )
            ResearchDraftVerifierStatus.NOT_EXECUTED,
            ResearchDraftVerifierStatus.AVAILABLE,
            -> Unit
        }

        val noEvidence = diagnostic.evidenceStatus == ResearchDraftEvidenceStatus.MISSING ||
            (diagnostic.submittedClaimIds.isEmpty() && diagnostic.submittedPairCount == 0)
        if (noEvidence) {
            return if (remainingRetrievalAttempts > 0 && searchAuthorizable) {
                result(
                    ResearchInterventionAction.SEARCH_AGAIN,
                    ResearchInterventionReason.NO_EVIDENCE_FOR_CLAIM,
                    findings.firstOrNull(),
                )
            } else {
                result(
                    ResearchInterventionAction.ABSTAIN,
                    if (searchAuthorizable) ResearchInterventionReason.EVIDENCE_MISSING
                    else ResearchInterventionReason.SEARCH_NOT_AUTHORIZED,
                    findings.firstOrNull(),
                )
            }
        }

        val insufficient = findings.firstOrNull { it.status == ResearchDraftClaimFindingStatus.INSUFFICIENT }
        if (insufficient != null) {
            return if (remainingRetrievalAttempts > 0 && searchAuthorizable) {
                result(ResearchInterventionAction.SEARCH_AGAIN, ResearchInterventionReason.INSUFFICIENT_EVIDENCE, insufficient)
            } else {
                result(
                    ResearchInterventionAction.ABSTAIN,
                    if (searchAuthorizable) ResearchInterventionReason.INSUFFICIENT_EVIDENCE
                    else ResearchInterventionReason.SEARCH_NOT_AUTHORIZED,
                    insufficient,
                )
            }
        }

        val supported = findings.filter { it.status == ResearchDraftClaimFindingStatus.SUPPORTED }
        if (supported.isNotEmpty()) {
            // Only the checked claims are reviewed. Whole-answer coverage stays
            // explicitly false, even when every checked claim was supported.
            return result(
                ResearchInterventionAction.DELIVER_WITH_SCOPED_AUDIT,
                if (diagnostic.coverageState == AnswerCoverageState.UNKNOWN ||
                    diagnostic.coverageState == AnswerCoverageState.INCOMPLETE ||
                    !diagnostic.authoritativeCoverage
                ) {
                    ResearchInterventionReason.SUPPORTED_CLAIMS_COVERAGE_INCOMPLETE
                } else {
                    ResearchInterventionReason.SUPPORTED_CLAIMS_SCOPED
                },
                supported.first(),
            )
        }

        if (diagnostic.claimFindings.isEmpty() && diagnostic.decision.factualClaimCount == 0) {
            return result(
                ResearchInterventionAction.DELIVER_UNVERIFIED,
                ResearchInterventionReason.NON_FACTUAL_ANSWER,
            )
        }

        return result(
            ResearchInterventionAction.DELIVER_UNVERIFIED,
            ResearchInterventionReason.VERIFIER_RESULT_INCOMPLETE,
            findings.firstOrNull(),
        )
    }

    fun infrastructureFailure(reason: ResearchInterventionReason): ClaimScopedInterventionDecision =
        ClaimScopedInterventionDecision(
            action = ResearchInterventionAction.DELIVER_UNVERIFIED,
            reason = reason,
            finding = null,
            revisionAttemptsRemaining = 0,
            retrievalAttemptsRemaining = 0,
            wholeAnswerCertified = false,
        )
}

internal object ResearchInterventionSettlementMessage {
    fun forAbstention(reason: ResearchInterventionReason): String = when (reason) {
        ResearchInterventionReason.REVISION_BUDGET_EXHAUSTED,
        ResearchInterventionReason.CONTRADICTED_CLAIM,
        ResearchInterventionReason.CONFLICTING_EVIDENCE,
        -> "제공된 자료와 충돌하는 주장이 있어 답변을 확정하지 않았습니다. 출처를 직접 확인하거나 질문을 좁혀 다시 시도해 주세요."

        ResearchInterventionReason.INSUFFICIENT_EVIDENCE,
        ResearchInterventionReason.INSUFFICIENT_AFTER_RETRIEVAL,
        -> "제공된 자료만으로는 핵심 주장을 확인하기에 근거가 부족합니다. 관련 자료를 더 추가하거나 질문을 좁혀 다시 시도해 주세요."

        ResearchInterventionReason.NO_EVIDENCE_FOR_CLAIM,
        ResearchInterventionReason.EVIDENCE_MISSING,
        -> "답변의 핵심 주장을 확인할 자료가 없어 확정하지 않았습니다. 자료를 추가하거나 출처를 지정해 주세요."

        ResearchInterventionReason.SEARCH_NOT_AUTHORIZED,
        ResearchInterventionReason.SEARCH_DENIED,
        -> "근거가 부족하지만 추가 검색 권한이 없어 답변을 확정하지 않았습니다. 웹 검색을 허용한 뒤 다시 시도해 주세요."

        ResearchInterventionReason.SEARCH_ATTEMPT_FAILED,
        -> "추가 자료를 가져오지 못해 답변을 확정하지 않았습니다. 잠시 후 다시 시도해 주세요."

        ResearchInterventionReason.REVISION_FAILED,
        ResearchInterventionReason.REVISION_UNSUPPORTED,
        -> "근거와 충돌하는 주장을 수정하지 못해 답변을 확정하지 않았습니다. 원문 출처를 직접 확인해 주세요."

        ResearchInterventionReason.VERIFIER_TIMEOUT,
        ResearchInterventionReason.VERIFIER_UNAVAILABLE,
        ResearchInterventionReason.VERIFIER_RESULT_INCOMPLETE,
        ResearchInterventionReason.EXPERIMENTAL_BACKEND_NOT_QUALIFIED,
        -> "근거 확인 도구가 일시적으로 응답하지 않아 답변을 확정하지 않았습니다. 잠시 후 다시 시도해 주세요."

        ResearchInterventionReason.INCOMPLETE_CLAIM_COVERAGE,
        ResearchInterventionReason.UNCERTAIN_CLAIM_EXTRACTION,
        -> "답변 전체의 주장을 확인할 수 없어 확정하지 않았습니다. 질문을 좁혀 다시 시도해 주세요."

        ResearchInterventionReason.INTERVENTION_TIMEOUT,
        -> "근거 확인 시간이 초과되어 답변을 확정하지 않았습니다. 다시 시도해 주세요."

        ResearchInterventionReason.STALE_DRAFT,
        ResearchInterventionReason.OWNERSHIP_LOST,
        -> "답변이 변경되거나 요청 소유권이 끝나 결과를 저장하지 않았습니다."

        ResearchInterventionReason.NON_FACTUAL_ANSWER,
        ResearchInterventionReason.CORRECTION_APPLIED,
        ResearchInterventionReason.SUPPORTED_CLAIMS_COVERAGE_INCOMPLETE,
        ResearchInterventionReason.SUPPORTED_CLAIMS_SCOPED,
        -> "답변을 확정하지 않았습니다. 다시 시도해 주세요."
    }
}

internal fun ResearchInterventionActivationPolicy.isEnabledForResearch(
    request: GenerationRequestSnapshot,
    target: ResearchDraftResponseVersionTarget?,
): Boolean = request.interactionMode == ChatInteractionMode.RESEARCH && enabledFor(request, target)
