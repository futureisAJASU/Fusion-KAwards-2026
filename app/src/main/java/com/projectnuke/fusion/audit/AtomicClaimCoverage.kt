package com.projectnuke.fusion.audit

import com.projectnuke.fusion.data.ClaimClassification
import com.projectnuke.fusion.data.ClaimPriority
import com.projectnuke.fusion.data.EvidenceClaimClassifier
import com.projectnuke.fusion.data.EvidenceClaimSplitter
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.EvidencePlanningLimits
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace

internal enum class AnswerCoverageState { COMPLETE, INCOMPLETE, UNKNOWN }

internal enum class AtomicClaimKind {
    FACTUAL_CANDIDATE,
    INFERENCE_OR_PREDICTION,
    MIXED_FACT_AND_INFERENCE,
    NON_FACTUAL,
}
internal enum class CoverageOmissionReason {
    ANSWER_INPUT_LIMIT,
    CANDIDATE_WINDOW_TRUNCATED,
    OVERLENGTH_SEGMENT_EXCLUDED,
    AUDIT_CLAIM_BUDGET_EXCEEDED,
    AMBIGUOUS_COORDINATION,
    MIXED_FACT_AND_INFERENCE,
    MARKDOWN_TABLE_NOT_DECOMPOSED,
    CODE_BLOCK_EXCLUDED,
    UNTERMINATED_CODE_FENCE,
    UNAUTHENTICATED_MANIFEST,
}

internal data class AtomicClaimCandidate(
    val id: String,
    /** Exact source substring; no subject or predicate is synthesized. */
    val text: String,
    val sourceSegmentOrdinal: Int,
    val kind: AtomicClaimKind,
    val classification: ClaimClassification,
    val priority: ClaimPriority,
    /** The exact coordinator removed from this clause boundary, when applicable. */
    val connectorFromPrevious: String? = null,
)

internal data class CoverageOmission(
    val reason: CoverageOmissionReason,
    /** Ordinal within the canonical visible answer; null means the region was not enumerable. */
    val sourceSegmentOrdinal: Int? = null,
    /** Known candidate IDs omitted from this audit budget. */
    val candidateIds: List<String> = emptyList(),
    /** A lower bound when the exact number omitted cannot be computed within the window. */
    val additionalCountLowerBound: Int? = null,
)

/**
 * Caller-supplied, untrusted manifest payload. [claimedAuthorityId] is descriptive
 * metadata only; this module has no trust store or signature verifier.
 */
internal data class UntrustedAnswerClaimManifest(
    val answerFingerprintSha256: String,
    val claimedAuthorityId: String,
    val factualClaims: List<UntrustedManifestClaim>,
)

internal data class UntrustedManifestClaim(val id: String, val text: String)

/**
 * Bounded answer-wide claim inventory. Heuristic analysis can only produce
 * UNKNOWN or INCOMPLETE. COMPLETE remains unavailable until a separate trusted
 * authority authenticates an exhaustive answer-wide manifest.
 */
internal class AnswerClaimCoverageReport private constructor(
    val answerFingerprintSha256: String,
    val state: AnswerCoverageState,
    val candidates: List<AtomicClaimCandidate>,
    /** Detected factual candidates in the inspected window; always a lower bound in this batch. */
    val detectedFactualCandidateCount: Int,
    val factualCandidateCountIsExact: Boolean,
    private val completedExecutionClaimIds: Set<String>,
    private val submittedExecutionClaimIds: Set<String>,
    val selectedForAuditClaimIds: Set<String>,
    val omissions: List<CoverageOmission>,
    val candidateWindowTruncated: Boolean,
    val overlengthSegmentsExcluded: Boolean,
    val inputTruncated: Boolean,
    val limitations: List<String>,
    private val acceptedAudit: AnswerAuditExecutionSnapshot?,
    private val acceptedExecutionOrigin: AnswerAuditExecutionOrigin?,
) {
    init {
        require(state != AnswerCoverageState.COMPLETE)
        require(!factualCandidateCountIsExact)
    }

    val detectedFactualClaims: List<AtomicClaimCandidate>
        get() = candidates.filter { it.kind == AtomicClaimKind.FACTUAL_CANDIDATE || it.kind == AtomicClaimKind.MIXED_FACT_AND_INFERENCE }

    private val factualClaimInventory: List<UntrustedManifestClaim>
        get() = detectedFactualClaims.map { UntrustedManifestClaim(it.id, it.text) }

    /** Only completed claim IDs that belong to the current factual inventory. */
    val checkedClaimIds: Set<String>
        get() = completedExecutionClaimIds
            .intersect(selectedForAuditClaimIds)
            .intersect(factualClaimInventory.mapTo(mutableSetOf()) { it.id })

    /** Selected claims for which at least one planned pair reached a backend call. */
    val submittedClaimIds: Set<String>
        get() = submittedExecutionClaimIds
            .intersect(selectedForAuditClaimIds)
            .intersect(factualClaimInventory.mapTo(mutableSetOf()) { it.id })

    /** Counts only selected claims whose complete request-side pair plan verified. */
    val actualCheckedClaimCount: Int
        get() = checkedClaimIds.size

    /** No trusted manifest authority is configured in P0-02, so this stays false. */
    val authoritativeCoverage: Boolean
        get() = false

    /**
     * Records an attempted caller-supplied manifest without trusting it. A
     * fingerprint and a caller-controlled authority label cannot prove who
     * enumerated the claims or that the manifest covers every answer region.
     */
    fun rejectUntrustedManifest(
        answer: String,
        manifest: UntrustedAnswerClaimManifest,
        maxClaimsToCheck: Int,
    ): AnswerClaimCoverageReport {
        require(maxClaimsToCheck in 1..EvidencePlanningLimits.MAX_CLAIMS_TO_CHECK)
        val answerMatches = EvidenceFingerprint.canonicalAnswerHash(answer) == answerFingerprintSha256
        val manifestFingerprintMatches = manifest.answerFingerprintSha256 == answerFingerprintSha256
        val detail = if (answerMatches && manifestFingerprintMatches) {
            "Manifest payload is unauthenticated; its claimedAuthorityId and claim list cannot certify answer-wide coverage."
        } else {
            "Manifest or answer fingerprint does not match this report; the manifest is unauthenticated and rejected."
        }
        return createProvisional(
            answerFingerprintSha256 = answerFingerprintSha256,
            state = AnswerCoverageState.INCOMPLETE,
            candidates = candidates,
            detectedFactualCandidateCount = detectedFactualCandidateCount,
            factualCandidateCountIsExact = false,
            completedExecutionClaimIds = completedExecutionClaimIds,
            submittedExecutionClaimIds = submittedExecutionClaimIds,
            selectedForAuditClaimIds = selectedForAuditClaimIds,
            omissions = omissions + CoverageOmission(CoverageOmissionReason.UNAUTHENTICATED_MANIFEST),
            candidateWindowTruncated = candidateWindowTruncated || inputTruncated || !answerMatches,
            overlengthSegmentsExcluded = overlengthSegmentsExcluded,
            inputTruncated = inputTruncated,
            limitations = limitations + detail,
            acceptedAudit = acceptedAudit,
            acceptedExecutionOrigin = acceptedExecutionOrigin,
        )
    }

    /** Maps this transient report into P0-01's policy contract; it does not execute the policy. */
    fun toDecisionInput(
        answer: String,
        audit: AnswerAuditExecutionSnapshot?,
        expectedExecutionOrigin: AnswerAuditExecutionOrigin? = null,
        remainingRevisionAttempts: Int = 0,
        hasExactSearchAuthorization: Boolean = false,
    ): ResearchVerificationDecisionInput {
        val answerMatches = EvidenceFingerprint.canonicalAnswerHash(answer) == answerFingerprintSha256
        val auditMatches = audit == null || (
            audit === acceptedAudit &&
                expectedExecutionOrigin != null &&
                expectedExecutionOrigin === acceptedExecutionOrigin &&
                audit.executionOrigin === expectedExecutionOrigin &&
                expectedExecutionOrigin.matchesDraft(answer, candidates)
            )
        return ResearchVerificationDecisionInput(
            factualClaimIds = factualClaimInventory.map { it.id }.toSet(),
            audit = audit,
            authoritativeCoverage = false,
            candidateWindowTruncated = candidateWindowTruncated || inputTruncated || !answerMatches || !auditMatches,
            overlengthSegmentsExcluded = overlengthSegmentsExcluded,
            remainingRevisionAttempts = remainingRevisionAttempts,
            hasExactSearchAuthorization = hasExactSearchAuthorization,
        )
    }

    companion object {
        internal fun createProvisional(
            answerFingerprintSha256: String,
            state: AnswerCoverageState,
            candidates: List<AtomicClaimCandidate>,
            detectedFactualCandidateCount: Int,
            factualCandidateCountIsExact: Boolean,
            completedExecutionClaimIds: Set<String>,
            submittedExecutionClaimIds: Set<String>,
            selectedForAuditClaimIds: Set<String>,
            omissions: List<CoverageOmission>,
            candidateWindowTruncated: Boolean,
            overlengthSegmentsExcluded: Boolean,
            inputTruncated: Boolean,
            limitations: List<String>,
            acceptedAudit: AnswerAuditExecutionSnapshot?,
            acceptedExecutionOrigin: AnswerAuditExecutionOrigin?,
        ): AnswerClaimCoverageReport {
            require(state != AnswerCoverageState.COMPLETE)
            require(!factualCandidateCountIsExact)
            return AnswerClaimCoverageReport(
                answerFingerprintSha256 = answerFingerprintSha256,
                state = state,
                candidates = candidates,
                detectedFactualCandidateCount = detectedFactualCandidateCount,
                factualCandidateCountIsExact = false,
                completedExecutionClaimIds = completedExecutionClaimIds,
                submittedExecutionClaimIds = submittedExecutionClaimIds,
                selectedForAuditClaimIds = selectedForAuditClaimIds,
                omissions = omissions,
                candidateWindowTruncated = candidateWindowTruncated,
                overlengthSegmentsExcluded = overlengthSegmentsExcluded,
                inputTruncated = inputTruncated,
                limitations = limitations,
                acceptedAudit = acceptedAudit,
                acceptedExecutionOrigin = acceptedExecutionOrigin,
            )
        }
    }
}

/**
 * Conservative clause inventory for P0-02. The current Research caller must
 * pass its four-claim budget explicitly; planner windows and verifier caps
 * retain their separate meanings.
 */
internal object AtomicClaimCoverageAnalyzer {
    const val MAX_ANSWER_CHARS = 64_000

    private val Coordinator = Regex(
        """(?i)(?:,\s*|\s+)(and|but|while|whereas|however|그리고|또한|하지만|그러나|반면(?:에)?)(?:\s+|,\s*)""",
    )
    private val EnglishFinitePredicate = Regex(
        """(?i)\b(?:is|are|was|were|be|been|has|have|had|does|did|will|would|could|may|might|announced|released|reported|recorded|increased|decreased|launched|started|began|supplied|produced|manufactured|reached|rose|fell|grew|declined|contains|includes|located|founded|costs?|serves?)\b""",
    )
    private val KoreanFinitePredicate = Regex(
        """(?:발표(?:했다|했다는|한다|했습니다)|출시(?:했다|했다는|한다|했습니다)|기록(?:했다|했다는|한다|했습니다)|증가(?:했다|했다는|한다|했습니다)|감소(?:했다|했다는|한다|했습니다)|공급(?:했다|했다는|한다|했습니다)|양산(?:했다|했다는|한다|했습니다)|생산(?:했다|했다는|한다|했습니다)|시작(?:했다|했다는|한다|했습니다)|포함(?:한다|했다)|설립(?:됐다|되었다)|(?:[가-힣]+)(?:이다|였다|있다|없다|된다|됐다|되었다|했습니다|합니다|했다|한다|올랐다|늘었다|줄었다))""",
    )
    private val InferenceCue = Regex(
        """(?i)(?:예상|전망|가능성이|가능하다|아마|추정|\b(?:likely|possibly|perhaps|may|might|could|would|predict(?:ed|s)?|forecast|expect(?:ed|s)?)\b)""",
    )
    private val FactualCue = Regex(
        """(?i)(?:19\d{2}|20\d{2}|\d+(?:[.,]\d+)?\s*(?:%|억|만|명|개|대|원|달러|GB|TB|MB|km|kg|년|월|일)|발표|출시|기록|증가|감소|공급|양산|생산|시작|\b(?:announced|released|reported|recorded|increased|decreased|launched|started|began|supplied|produced|reached)\b)""",
    )
    private val MarkdownTableSeparator = Regex("""^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$""")
    private val MarkdownTableRow = Regex("""^\s*\|.+\|\s*$""")
    private val FenceLine = Regex("""(?m)^\s*```[^\r\n]*$""")

    fun analyze(
        answer: String,
        maxClaimsToCheck: Int,
        audit: AnswerAuditExecutionSnapshot? = null,
        expectedExecutionOrigin: AnswerAuditExecutionOrigin? = null,
    ): AnswerClaimCoverageReport {
        require(maxClaimsToCheck in 1..EvidencePlanningLimits.MAX_CLAIMS_TO_CHECK)
        val fingerprint = EvidenceFingerprint.canonicalAnswerHash(answer)
        val visible = visibleMessageContentPreservingWhitespace(answer)
        val inputTruncated = visible.length > MAX_ANSWER_CHARS
        val boundedVisible = if (inputTruncated) visible.take(MAX_ANSWER_CHARS) else visible
        val splitWindow = EvidenceClaimSplitter.splitWindow(boundedVisible)
        val candidates = ArrayList<AtomicClaimCandidate>(EvidencePlanningLimits.MAX_CLAIM_CANDIDATES)
        val omissions = mutableListOf<CoverageOmission>()
        val codeFenceCount = FenceLine.findAll(boundedVisible).count()
        val hasCodeBlock = codeFenceCount > 0
        val unterminatedCodeFence = codeFenceCount % 2 == 1

        if (inputTruncated) omissions += CoverageOmission(CoverageOmissionReason.ANSWER_INPUT_LIMIT)
        if (splitWindow.truncated) omissions += CoverageOmission(CoverageOmissionReason.CANDIDATE_WINDOW_TRUNCATED)
        if (splitWindow.overlengthSegmentsExcluded) omissions += CoverageOmission(CoverageOmissionReason.OVERLENGTH_SEGMENT_EXCLUDED)
        if (hasCodeBlock) omissions += CoverageOmission(CoverageOmissionReason.CODE_BLOCK_EXCLUDED)
        if (unterminatedCodeFence) omissions += CoverageOmission(CoverageOmissionReason.UNTERMINATED_CODE_FENCE)

        var detectedFactCount = 0
        var atomicWindowTruncated = false
        splitWindow.claims.forEachIndexed { segmentIndex, segment ->
            if (MarkdownTableRow.matches(segment) && !MarkdownTableSeparator.matches(segment)) {
                omissions += CoverageOmission(
                    reason = CoverageOmissionReason.MARKDOWN_TABLE_NOT_DECOMPOSED,
                    sourceSegmentOrdinal = segmentIndex + 1,
                )
            }
            val atomicSplit = splitCoordinatedClauses(segment)
            if (atomicSplit.ambiguous) {
                omissions += CoverageOmission(
                    reason = CoverageOmissionReason.AMBIGUOUS_COORDINATION,
                    sourceSegmentOrdinal = segmentIndex + 1,
                )
            }
            atomicSplit.parts.forEach { part ->
                val decision = EvidenceClaimClassifier.classifyWithReason(part.text)
                val mixed = hasFactualInferenceMixture(part.text)
                if (mixed) {
                    omissions += CoverageOmission(
                        reason = CoverageOmissionReason.MIXED_FACT_AND_INFERENCE,
                        sourceSegmentOrdinal = segmentIndex + 1,
                    )
                }
                val kind = when {
                    mixed -> AtomicClaimKind.MIXED_FACT_AND_INFERENCE
                    decision.classification == ClaimClassification.CHECKABLE_FACT -> AtomicClaimKind.FACTUAL_CANDIDATE
                    decision.classification == ClaimClassification.INFERENCE_OR_PREDICTION -> AtomicClaimKind.INFERENCE_OR_PREDICTION
                    else -> AtomicClaimKind.NON_FACTUAL
                }
                if (kind == AtomicClaimKind.FACTUAL_CANDIDATE || kind == AtomicClaimKind.MIXED_FACT_AND_INFERENCE) {
                    detectedFactCount++
                }
                if (candidates.size < EvidencePlanningLimits.MAX_CLAIM_CANDIDATES) {
                    candidates += AtomicClaimCandidate(
                        id = "AC${candidates.size + 1}",
                        text = part.text,
                        sourceSegmentOrdinal = segmentIndex + 1,
                        kind = kind,
                        classification = decision.classification,
                        priority = decision.priority,
                        connectorFromPrevious = part.connectorFromPrevious,
                    )
                } else {
                    atomicWindowTruncated = true
                }
            }
        }
        if (atomicWindowTruncated) omissions += CoverageOmission(CoverageOmissionReason.CANDIDATE_WINDOW_TRUNCATED)

        val factualCandidates = candidates.filter {
            it.kind == AtomicClaimKind.FACTUAL_CANDIDATE || it.kind == AtomicClaimKind.MIXED_FACT_AND_INFERENCE
        }
        val selectedForAudit = factualCandidates
            .sortedWith(compareByDescending<AtomicClaimCandidate> { priorityRank(it.priority) }
                .thenBy { it.sourceSegmentOrdinal }
                .thenBy { it.id })
            .take(maxClaimsToCheck)
            .map { it.id }
            .toSet()
        val omittedByAuditBudget = factualCandidates.map { it.id }.filterNot { it in selectedForAudit }
        if (omittedByAuditBudget.isNotEmpty()) {
            omissions += CoverageOmission(
                reason = CoverageOmissionReason.AUDIT_CLAIM_BUDGET_EXCEEDED,
                candidateIds = omittedByAuditBudget,
            )
        }

        val acceptedAudit = audit?.takeIf {
            expectedExecutionOrigin != null &&
                it.executionOrigin === expectedExecutionOrigin &&
                expectedExecutionOrigin.matchesDraft(answer, candidates)
        }
        val completedIds = acceptedAudit?.claims.orEmpty().asSequence()
            .filter(ClaimSemanticVerification::hasCompleteVerification)
            .map { it.claimId }
            .toSet()
        val submittedIds = acceptedAudit?.claims.orEmpty().asSequence()
            .filter { it.actualSubmittedPairCount > 0 }
            .map { it.claimId }
            .toSet()
        val auditMismatch = audit != null && acceptedAudit == null
        val hasKnownGaps = omissions.isNotEmpty()

        return AnswerClaimCoverageReport.createProvisional(
            answerFingerprintSha256 = fingerprint,
            state = if (hasKnownGaps) AnswerCoverageState.INCOMPLETE else AnswerCoverageState.UNKNOWN,
            candidates = candidates.toList(),
            detectedFactualCandidateCount = detectedFactCount,
            factualCandidateCountIsExact = false,
            completedExecutionClaimIds = completedIds,
            submittedExecutionClaimIds = submittedIds,
            selectedForAuditClaimIds = selectedForAudit,
            omissions = omissions.distinct(),
            candidateWindowTruncated = splitWindow.truncated || atomicWindowTruncated || auditMismatch,
            overlengthSegmentsExcluded = splitWindow.overlengthSegmentsExcluded,
            inputTruncated = inputTruncated,
            limitations = listOf(
                "Natural-language segmentation is heuristic and cannot certify that every factual assertion was found.",
                "Only exact source substrings are emitted; inherited subjects and implicit predicates are not reconstructed.",
                "Markdown table rows, mixed fact/inference clauses, code blocks, and ambiguous coordinators remain coverage gaps.",
                "Detected counts are lower bounds unless an independent exact-answer manifest is accepted.",
            ),
            acceptedAudit = acceptedAudit,
            acceptedExecutionOrigin = acceptedAudit?.executionOrigin,
        )
    }

    private data class ClausePart(val text: String, val connectorFromPrevious: String?)
    private data class AtomicSplit(val parts: List<ClausePart>, val ambiguous: Boolean)

    private fun splitCoordinatedClauses(text: String): AtomicSplit {
        val matches = Coordinator.findAll(text).toList()
        if (matches.isEmpty()) return AtomicSplit(listOf(ClausePart(text.trim(), null)), ambiguous = false)

        val parts = mutableListOf<ClausePart>()
        var cursor = 0
        var connectorFromPrevious: String? = null
        for (match in matches) {
            val left = text.substring(cursor, match.range.first).trim().trimEnd(',', ';').trim()
            val rightStart = match.range.last + 1
            val right = text.substring(rightStart).trim()
            val connector = match.groups[1]?.value.orEmpty()
            if (left.isBlank() || right.isBlank() || !isIndependentClause(left) || !isIndependentClause(right)) {
                return AtomicSplit(listOf(ClausePart(text.trim(), null)), ambiguous = true)
            }
            parts += ClausePart(left, connectorFromPrevious)
            cursor = rightStart
            connectorFromPrevious = connector
        }
        val last = text.substring(cursor).trim()
        if (last.isBlank() || !isIndependentClause(last)) {
            return AtomicSplit(listOf(ClausePart(text.trim(), null)), ambiguous = true)
        }
        parts += ClausePart(last, connectorFromPrevious)
        return AtomicSplit(parts, ambiguous = false)
    }

    private fun isIndependentClause(text: String): Boolean {
        val classification = EvidenceClaimClassifier.classifyWithReason(text).classification
        if (classification != ClaimClassification.CHECKABLE_FACT) return false
        return EnglishFinitePredicate.containsMatchIn(text) || KoreanFinitePredicate.containsMatchIn(text)
    }

    private fun hasFactualInferenceMixture(text: String): Boolean =
        FactualCue.containsMatchIn(text) && InferenceCue.containsMatchIn(text)

    private fun priorityRank(priority: ClaimPriority): Int = when (priority) {
        ClaimPriority.HIGH -> 3
        ClaimPriority.MEDIUM -> 2
        ClaimPriority.LOW -> 1
        ClaimPriority.NONE -> 0
    }

}
