package com.projectnuke.fusion.ui

import com.projectnuke.fusion.audit.AnswerAuditExecutionSnapshot
import com.projectnuke.fusion.data.ClaimClassification
import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceClaimCandidate
import com.projectnuke.fusion.data.EvidenceClaimClassifier
import com.projectnuke.fusion.data.EvidenceClock
import com.projectnuke.fusion.data.EvidenceCodec
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.EvidencePlanningLimits
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceReportCompatibilityPolicy
import com.projectnuke.fusion.data.EvidenceSourceCandidateMatcher
import com.projectnuke.fusion.data.EvidenceStore
import com.projectnuke.fusion.data.EvidenceValidation
import com.projectnuke.fusion.data.EvidenceVerificationFailureReason
import com.projectnuke.fusion.data.EvidenceVerificationPlanner
import com.projectnuke.fusion.data.EvidenceVerificationResult
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.data.PlannedClaim
import com.projectnuke.fusion.data.TargetedSearchRequest
import com.projectnuke.fusion.data.VerificationPlan
import com.projectnuke.fusion.privacy.AuthorizedTargetedQuery
import com.projectnuke.fusion.privacy.DataEgressPolicy
import com.projectnuke.fusion.privacy.PostAnswerSearchPrivacy
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchAuthorization
import com.projectnuke.fusion.privacy.PostAnswerVerificationSearchIdentity
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UX-P2B — explicit, user-triggered reverification of ONE previously recorded
 * claim.
 *
 * This file contains the pure, testable selected-claim execution-plan policy and
 * a narrowly scoped single-flight execution owner. It is deliberately separate
 * from the read-only [SavedClaimEvidenceExplorerOwner] (which stays read-only by
 * construction) and from the full-answer [PostAnswerFactVerificationOwner].
 *
 * Hard contracts:
 *  - exactly ONE selected claim enters a bounded [VerificationPlan];
 *  - the selected claim is matched by exact stable id AND exact stored text
 *    against a currently reconstructible canonical checkable candidate;
 *  - the real assistant answer remains the verification/privacy identity (no
 *    synthetic single-claim message);
 *  - web search requires an exact one-shot
 *    [PostAnswerVerificationSearchAuthorization] bound to the operation id,
 *    answer identity, claim id and query;
 *  - NOTHING is ever persisted: no [EvidenceStore.saveComplete], no message or
 *    response-version write. The original [EvidenceReport] is never mutated.
 */

/** Why one stored claim cannot be re-verified right now. Fail-closed reasons. */
enum class SelectedClaimIneligibleReason {
    UNKNOWN_CLAIM_ID,
    ANSWER_CHANGED,
    REPORT_INCOMPATIBLE,
    CLAIM_NOT_RECONSTRUCTIBLE,
    OUTSIDE_EXECUTION_WINDOW,
    CLAIM_TEXT_MISMATCH,
    NOT_CHECKABLE,
    SELECTED_TEXT_UNSUPPORTED,
    STALE_SELECTION,
}

/**
 * A bounded, single-claim execution plan. It binds the exact answer identity,
 * the exact selected stored claim, the currently reconstructible canonical
 * candidate and exactly one [PlannedClaim].
 *
 * [sourceOnlyPlan] never contains a targeted-search requirement (so the
 * existing-sources path can never accidentally perform network egress or emit
 * an artificial search-budget failure). [targetedQuery] is present only when the
 * canonical planner determined that targeted search is genuinely needed for the
 * selected claim.
 */
data class SelectedClaimExecutionPlan(
    val identity: EvidenceAnswerIdentity,
    val storedClaim: EvidenceClaim,
    val candidate: EvidenceClaimCandidate,
    val sourceOnlyPlan: VerificationPlan,
    val targetedQuery: TargetedSearchRequest?,
    /** The compatible base report travels only with this owner-held transient result. */
    val storedReport: EvidenceReport? = null,
) {
    val claimId: String get() = storedClaim.id
    val claimText: String get() = storedClaim.text

    /** True when a targeted web query exists and may be offered to the user. */
    val offersWebSearch: Boolean get() = targetedQuery != null

    /**
     * The web-authorized variant of the same single-claim plan: identical bounds
     * and candidate, with the exact targeted query re-attached. Falls back to the
     * source-only plan when no query was derived.
     */
    fun webPlan(): VerificationPlan {
        val request = targetedQuery ?: return sourceOnlyPlan
        return sourceOnlyPlan.copy(
            claims = sourceOnlyPlan.claims.map { it.copy(needsTargetedSearch = true) },
            targetedSearchRequests = listOf(request),
        )
    }
}

/**
 * Shared bounded plan passed to the P2B operation machinery. It intentionally
 * contains no stored claim or report: a native selection can be checked
 * without pretending it was previously recorded.
 */
internal data class SingleClaimVerificationPlan(
    val identity: EvidenceAnswerIdentity,
    val candidate: EvidenceClaimCandidate,
    val sourceOnlyPlan: VerificationPlan,
    val targetedQuery: TargetedSearchRequest?,
    val storedReport: EvidenceReport? = null,
) {
    val claimId: String get() = candidate.id
    val claimText: String get() = candidate.text
    val offersWebSearch: Boolean get() = targetedQuery != null

    fun webPlan(): VerificationPlan {
        val request = targetedQuery ?: return sourceOnlyPlan
        return sourceOnlyPlan.copy(
            claims = sourceOnlyPlan.claims.map { it.copy(needsTargetedSearch = true) },
            targetedSearchRequests = listOf(request),
        )
    }
}

private fun SelectedClaimExecutionPlan.asSingleClaimPlan() = SingleClaimVerificationPlan(
    identity = identity,
    candidate = candidate,
    sourceOnlyPlan = sourceOnlyPlan,
    targetedQuery = targetedQuery,
    storedReport = storedReport,
)

internal sealed interface SingleClaimPlanDecision {
    data class Eligible(val plan: SingleClaimVerificationPlan) : SingleClaimPlanDecision
    data class Ineligible(
        val claimId: String,
        val reason: SelectedClaimIneligibleReason,
        val explanation: String,
    ) : SingleClaimPlanDecision
}

/**
 * Pure one-claim planner for text selected directly from the rendered answer.
 * It uses the canonical source snapshot and matcher but keeps only the exact
 * selected claim in both the plan and its candidate window.
 */
internal object SelectedAnswerTextVerificationPolicy {
    fun plan(
        message: MessageEntity,
        identity: EvidenceAnswerIdentity,
        snapshot: AnswerTextSelectionSnapshot,
        currentRenderedAnswerIdentity: String,
    ): SingleClaimPlanDecision {
        fun reject(
            reason: SelectedClaimIneligibleReason,
            explanation: String,
        ) = SingleClaimPlanDecision.Ineligible(snapshot.claimId, reason, explanation)

        if (!AnswerTextSelectionPolicy.isCurrent(
                snapshot = snapshot,
                currentIdentity = AnswerSurfaceIdentity.of(message.conversationId, message),
                currentRenderedAnswerIdentity = currentRenderedAnswerIdentity,
                message = message,
            ) || identity != EvidenceAnswerIdentity.of(message)
        ) {
            return reject(
                SelectedClaimIneligibleReason.STALE_SELECTION,
                "답변 표시 또는 선택이 변경되어 확인을 시작할 수 없습니다. 현재 답변에서 다시 선택해 주세요.",
            )
        }
        val selectedText = snapshot.exactSelectedText
        if (selectedText.isBlank() || selectedText.length > AnswerTextSelectionPolicy.MAX_SELECTION_LENGTH) {
            return reject(
                SelectedClaimIneligibleReason.SELECTED_TEXT_UNSUPPORTED,
                "한 문장 전체를 다시 선택해 주세요.",
            )
        }
        val split = com.projectnuke.fusion.data.EvidenceClaimSplitter.splitWindow(selectedText)
        val endingChar = selectedText.lastOrNull { !it.isWhitespace() }
        if (split.truncated || split.claims.singleOrNull() != selectedText || endingChar == null ||
            endingChar !in ".!?。！？"
        ) {
            return reject(
                SelectedClaimIneligibleReason.SELECTED_TEXT_UNSUPPORTED,
                "한 문장 전체가 선택되지 않았거나 여러 문장이 포함되어 있습니다. 완전한 사실 문장 하나를 선택해 주세요.",
            )
        }
        val classification = EvidenceClaimClassifier.classifyWithReason(selectedText)
        if (classification.classification != ClaimClassification.CHECKABLE_FACT) {
            return reject(
                SelectedClaimIneligibleReason.NOT_CHECKABLE,
                "선택한 내용은 확인 가능한 사실 문장으로 분류되지 않았습니다.",
            )
        }

        val canonical = EvidenceVerificationPlanner.plan(message.content, allowTargetedSearch = true)
        if (selectedText.length > EvidencePlanningLimits.MAX_CLAIM_TEXT_LENGTH) {
            return reject(
                SelectedClaimIneligibleReason.SELECTED_TEXT_UNSUPPORTED,
                "선택한 범위가 검증 길이 한도를 넘었습니다.",
            )
        }
        val match = EvidenceSourceCandidateMatcher.match(selectedText, canonical.existingSources)
        val candidate = EvidenceClaimCandidate(
            id = snapshot.claimId,
            text = selectedText,
            ordinal = 1,
            classification = classification.classification,
            priority = classification.priority,
            reason = classification.reason,
        )
        val planned = PlannedClaim(
            candidate = candidate,
            existingSourceCandidateIds = match.sourceIds
                .take(EvidencePlanningLimits.MAX_EXISTING_SOURCE_CANDIDATES_PER_CLAIM),
            needsTargetedSearch = !match.hasStrongCandidate,
        )
        val sourceOnlyPlan = VerificationPlan(
            claims = listOf(planned.copy(needsTargetedSearch = false)),
            existingSources = canonical.existingSources,
            targetedSearchRequests = emptyList(),
            allCandidates = listOf(candidate),
            candidateWindowTruncated = canonical.candidateWindowTruncated,
            overlengthSegmentsExcluded = canonical.overlengthSegmentsExcluded,
        )
        val targetedQuery = if (planned.needsTargetedSearch) {
            TargetedSearchRequest(
                claimId = candidate.id,
                query = EvidenceVerificationPlanner.targetedQueryFor(candidate.text),
            )
        } else {
            null
        }
        return SingleClaimPlanDecision.Eligible(
            SingleClaimVerificationPlan(
                identity = identity,
                candidate = candidate,
                sourceOnlyPlan = sourceOnlyPlan,
                targetedQuery = targetedQuery,
            ),
        )
    }
}

/** Outcome of binding one stored claim to a bounded single-claim execution plan. */
sealed interface SelectedClaimExecutionDecision {
    data class Eligible(val plan: SelectedClaimExecutionPlan) : SelectedClaimExecutionDecision
    data class Ineligible(
        val reason: SelectedClaimIneligibleReason,
        val explanation: String,
    ) : SelectedClaimExecutionDecision
}

/**
 * Pure, deterministic selected-claim execution-plan policy. It reuses the
 * canonical [EvidenceVerificationPlanner] (the real message is always the
 * planning input) and fails closed on every ambiguous or unsupported case.
 */
object SelectedClaimVerificationPolicy {

    fun plan(
        message: MessageEntity,
        identity: EvidenceAnswerIdentity,
        report: EvidenceReport,
        claimId: String,
        nowMillis: Long,
    ): SelectedClaimExecutionDecision {
        // 1. The bound answer must still be the exact captured answer.
        if (EvidenceAnswerIdentity.of(message) != identity ||
            EvidenceFingerprint.canonicalAnswerHash(message.content) != identity.answerSha256
        ) {
            return ineligible(SelectedClaimIneligibleReason.ANSWER_CHANGED)
        }
        // 2. The original stored report must still be display-compatible for it
        //    (same hash, same verifier rules, not expired, clock sane).
        if (report.answerSha256 != identity.answerSha256 ||
            !EvidenceReportCompatibilityPolicy.isCurrentDisplayCompatible(
                report,
                identity.answerSha256,
                nowMillis,
            )
        ) {
            return ineligible(SelectedClaimIneligibleReason.REPORT_INCOMPATIBLE)
        }
        // 3. The selected id must name a stored claim (never by position/text).
        val storedClaim = report.claims.firstOrNull { it.id == claimId }
            ?: return ineligible(SelectedClaimIneligibleReason.UNKNOWN_CLAIM_ID)
        // 4. Reconstruct the canonical candidate window from the REAL answer.
        val canonical = EvidenceVerificationPlanner.plan(message.content, allowTargetedSearch = true)
        val candidate = canonical.allCandidates.firstOrNull { it.id == claimId }
            ?: return ineligible(SelectedClaimIneligibleReason.CLAIM_NOT_RECONSTRUCTIBLE)
        // 5. Exact text identity (no fuzzy/positional substitution).
        if (candidate.text != storedClaim.text) {
            return ineligible(SelectedClaimIneligibleReason.CLAIM_TEXT_MISMATCH)
        }
        // 6. Only checkable facts may be re-verified.
        if (candidate.classification != ClaimClassification.CHECKABLE_FACT) {
            return ineligible(SelectedClaimIneligibleReason.NOT_CHECKABLE)
        }
        // 7. The claim must be inside the planner's supported execution window
        //    (i.e. the canonical planner would itself select it). Claims excluded
        //    by the claim budget fail closed rather than being silently promoted.
        val selectedPlanned = canonical.claims.firstOrNull { it.candidate.id == claimId }
            ?: return ineligible(SelectedClaimIneligibleReason.OUTSIDE_EXECUTION_WINDOW)

        val sourceOnlyPlan = VerificationPlan(
            claims = listOf(selectedPlanned.copy(needsTargetedSearch = false)),
            existingSources = canonical.existingSources,
            targetedSearchRequests = emptyList(),
            // Exactly one candidate so no unrelated stored claim can be appended
            // as a NOT_CHECKED row in the transient single-claim result.
            allCandidates = listOf(candidate),
            // Inherited answer-level limitations are preserved truthfully.
            candidateWindowTruncated = canonical.candidateWindowTruncated,
            overlengthSegmentsExcluded = canonical.overlengthSegmentsExcluded,
        )
        val targetedQuery = if (selectedPlanned.needsTargetedSearch) {
            TargetedSearchRequest(claimId, EvidenceVerificationPlanner.targetedQueryFor(candidate.text))
        } else {
            null
        }
        return SelectedClaimExecutionDecision.Eligible(
            SelectedClaimExecutionPlan(
                identity = identity,
                storedClaim = storedClaim,
                candidate = candidate,
                sourceOnlyPlan = sourceOnlyPlan,
                targetedQuery = targetedQuery,
                storedReport = report,
            ),
        )
    }

    private fun ineligible(reason: SelectedClaimIneligibleReason) =
        SelectedClaimExecutionDecision.Ineligible(reason, selectedClaimIneligibleExplanation(reason))
}

/** User's explicit source decision for one selected-claim operation. */
enum class SelectedClaimSourceChoice { EXISTING_SOURCES_ONLY, WEB, CANCEL }

/** Why a selected-claim operation produced no valid result. */
enum class SelectedClaimFailureReason {
    INVALID,
    DEADLINE_EXCEEDED,
    SEARCH_UNAVAILABLE,
    VERIFIER_UNAVAILABLE,
}

/**
 * Immutable UX-P2B presentation state for ONE selected-claim operation. It is
 * transient only and is never persisted.
 */
sealed interface SelectedClaimReverificationState {
    /** No transient result; the stored record is shown alone. */
    data object Idle : SelectedClaimReverificationState

    data class Preparing(val claimId: String) : SelectedClaimReverificationState

    data class AwaitingConsent(
        val claimId: String,
        val operationId: String,
        val identity: PostAnswerVerificationSearchIdentity,
        val query: AuthorizedTargetedQuery,
    ) : SelectedClaimReverificationState

    data class Running(
        val claimId: String,
        val webSearch: Boolean,
        val notice: String? = null,
    ) : SelectedClaimReverificationState

    /** The freshly computed single-claim result, kept separate from the stored report. */
    data class Result(
        val claimId: String,
        val report: EvidenceReport,
        val transientAudit: AnswerAuditExecutionSnapshot?,
        val webSearchAuthorized: Boolean,
        val notice: String? = null,
        /** Compatible stored snapshot, if the exact durable claim was reused. */
        val storedReport: EvidenceReport? = null,
    ) : SelectedClaimReverificationState

    data class Failed(
        val claimId: String,
        val reason: SelectedClaimFailureReason,
    ) : SelectedClaimReverificationState

    data class Ineligible(
        val claimId: String,
        val reason: SelectedClaimIneligibleReason,
        val explanation: String,
    ) : SelectedClaimReverificationState

    val claimIdOrNull: String?
        get() = when (this) {
            Idle -> null
            is Preparing -> claimId
            is AwaitingConsent -> claimId
            is Running -> claimId
            is Result -> claimId
            is Failed -> claimId
            is Ineligible -> claimId
        }
}

/**
 * Narrow, single-flight owner for ONE explicit selected-claim reverification.
 *
 * It performs real verification through the existing [EvidenceUiVerifier] seams
 * but NEVER persists anything. Every suspension point re-checks ownership
 * (epoch, answer identity, persisted message, currently selected claim), so a
 * late result can never surface under a different claim, answer or conversation.
 */
class SelectedClaimReverificationOwner(
    private val scope: CoroutineScope,
    private val verifier: EvidenceUiVerifier,
    private val clock: EvidenceClock = EvidenceClock { System.currentTimeMillis() },
    private val privateTextsProvider: (() -> List<String>)? = null,
    private val currentMessageReader: suspend (MessageEntity) -> MessageEntity? = { it },
    private val isCurrentAnswer: () -> Boolean = { true },
    private val isSelectedClaimCurrent: (String) -> Boolean = { true },
    private val evidenceStore: EvidenceUiStore? = null,
    private val isCapturedSelectionCurrent: (String) -> Boolean = { true },
    private val operationIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableState = MutableStateFlow<SelectedClaimReverificationState>(SelectedClaimReverificationState.Idle)
    val state: StateFlow<SelectedClaimReverificationState> = mutableState.asStateFlow()

    private var job: Job? = null
    private var epoch = 0L
    private var pendingChoice: CompletableDeferred<SelectedClaimSourceChoice>? = null
    private var activeRequestId: String? = null

    /**
     * Starts one bounded selected-claim operation. Repeated taps while an
     * operation is in flight are ignored (single-flight); a new operation after a
     * settled one supersedes the previous result.
     */
    fun reverify(
        message: MessageEntity,
        identity: EvidenceAnswerIdentity,
        report: EvidenceReport,
        claimId: String,
    ) {
        if (job?.isActive == true) return
        if (message.id <= 0L || message.role != EvidenceStore.ASSISTANT_ROLE || message.conversationId <= 0L) return
        if (claimId.isBlank()) return
        startOperation(
            message = message,
            identity = identity,
            requestId = claimId,
            initialClaimId = claimId,
            supersedeDifferentRequest = false,
            isRequestCurrent = { isSelectedClaimCurrent(claimId) },
            planProvider = {
                when (val decision = SelectedClaimVerificationPolicy.plan(
                    message = message,
                    identity = identity,
                    report = report,
                    claimId = claimId,
                    nowMillis = clock.nowMillis(),
                )) {
                    is SelectedClaimExecutionDecision.Eligible ->
                        SingleClaimPlanDecision.Eligible(decision.plan.asSingleClaimPlan())
                    is SelectedClaimExecutionDecision.Ineligible -> SingleClaimPlanDecision.Ineligible(
                        claimId = claimId,
                        reason = decision.reason,
                        explanation = decision.explanation,
                    )
                }
            },
        )
    }

    /** Starts only after the user confirms the preview of a native selection. */
    internal fun verifySelectedText(
        message: MessageEntity,
        snapshot: AnswerTextSelectionSnapshot,
        currentRenderedAnswerIdentity: () -> String,
    ) {
        if (message.id <= 0L || message.role != EvidenceStore.ASSISTANT_ROLE ||
            message.conversationId != snapshot.conversationId || snapshot.selectionId.isBlank()
        ) return
        startOperation(
            message = message,
            identity = EvidenceAnswerIdentity.of(message),
            requestId = snapshot.selectionId,
            initialClaimId = snapshot.claimId,
            supersedeDifferentRequest = true,
            isRequestCurrent = { isCapturedSelectionCurrent(snapshot.selectionId) },
            planProvider = {
                val evidence = try {
                    evidenceStore?.load(message)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                val report = (evidence as? EvidenceStore.LoadResult.Valid)?.report
                val canonical = EvidenceVerificationPlanner.plan(message.content, allowTargetedSearch = true)
                val exactCandidate = canonical.allCandidates.singleOrNull {
                    it.text == snapshot.exactSelectedText
                }
                val storedClaim = exactCandidate?.let { candidate ->
                    report?.claims?.singleOrNull { it.id == candidate.id && it.text == snapshot.exactSelectedText }
                }
                if (report != null && storedClaim != null) {
                    val storedDecision = SelectedClaimVerificationPolicy.plan(
                        message = message,
                        identity = EvidenceAnswerIdentity.of(message),
                        report = report,
                        claimId = storedClaim.id,
                        nowMillis = clock.nowMillis(),
                    )
                    if (storedDecision is SelectedClaimExecutionDecision.Eligible) {
                        return@startOperation SingleClaimPlanDecision.Eligible(
                            storedDecision.plan.asSingleClaimPlan(),
                        )
                    }
                }
                SelectedAnswerTextVerificationPolicy.plan(
                    message = message,
                    identity = EvidenceAnswerIdentity.of(message),
                    snapshot = snapshot,
                    currentRenderedAnswerIdentity = currentRenderedAnswerIdentity(),
                )
            },
        )
    }

    private fun startOperation(
        message: MessageEntity,
        identity: EvidenceAnswerIdentity,
        requestId: String,
        initialClaimId: String,
        supersedeDifferentRequest: Boolean,
        isRequestCurrent: () -> Boolean,
        planProvider: suspend () -> SingleClaimPlanDecision,
    ) {
        if (job?.isActive == true) {
            if (!supersedeDifferentRequest || activeRequestId == requestId) return
            cancel()
        }
        val run = ++epoch
        val operationId = operationIdFactory()
        activeRequestId = requestId
        mutableState.value = SelectedClaimReverificationState.Preparing(initialClaimId)
        job = scope.launch {
            runOperation(
                message = message,
                identity = identity,
                requestId = requestId,
                initialClaimId = initialClaimId,
                operationId = operationId,
                run = run,
                isRequestCurrent = isRequestCurrent,
                planProvider = planProvider,
            )
        }
    }

    /** Answers the pending web-search consent for the exact operation id. */
    fun choose(operationId: String, choice: SelectedClaimSourceChoice) {
        val prompt = mutableState.value as? SelectedClaimReverificationState.AwaitingConsent ?: return
        if (prompt.operationId != operationId) return
        pendingChoice?.complete(choice)
        pendingChoice = null
    }

    /**
     * Cancels any in-flight operation and clears the transient result. Called on
     * dismissal, claim switch, answer/conversation change and lifecycle stop.
     */
    fun cancel() {
        epoch++
        pendingChoice?.cancel()
        pendingChoice = null
        job?.cancel()
        job = null
        activeRequestId = null
        mutableState.value = SelectedClaimReverificationState.Idle
    }

    fun dismiss() = cancel()

    private suspend fun runOperation(
        message: MessageEntity,
        identity: EvidenceAnswerIdentity,
        requestId: String,
        initialClaimId: String,
        operationId: String,
        run: Long,
        isRequestCurrent: () -> Boolean,
        planProvider: suspend () -> SingleClaimPlanDecision,
    ) {
        var ownedClaimId = initialClaimId
        fun owns(): Boolean = epoch == run &&
            activeRequestId == requestId &&
            isCurrentAnswer() &&
            isRequestCurrent() &&
            mutableState.value.claimIdOrNull == ownedClaimId

        suspend fun requireOwner() {
            currentCoroutineContext().ensureActive()
            if (!owns()) throw CancellationException("selected-claim reverification owner changed")
        }

        suspend fun currentStoredMessage(): MessageEntity? {
            requireOwner()
            val current = currentMessageReader(message)
            requireOwner()
            return current
        }

        /**
         * P2B-01: re-reads the exact persisted message through the read-only
         * message-reader seam and fails closed when it is missing or no longer
         * byte-for-byte equal to the captured message. The suspendable read is
         * bracketed by ownership checks, so persisted-message identity is
         * established independently of `isCurrentAnswer()` and any captured
         * Compose identity. Returns true when the caller must stop.
         */
        suspend fun failClosedIfStoredMessageChanged(): Boolean {
            if (currentStoredMessage() == message) return false
            mutableState.value = SelectedClaimReverificationState.Ineligible(
                claimId = ownedClaimId,
                reason = SelectedClaimIneligibleReason.ANSWER_CHANGED,
                explanation = selectedClaimIneligibleExplanation(SelectedClaimIneligibleReason.ANSWER_CHANGED),
            )
            return true
        }

        suspend fun publishResult(plan: SingleClaimVerificationPlan, webSearch: Boolean, notice: String?) {
            requireOwner()
            if (failClosedIfStoredMessageChanged()) return
            mutableState.value = SelectedClaimReverificationState.Running(plan.claimId, webSearch, notice)
            val result = verifier.verify(message, plan.sourceOnlyPlan)
            // Verification is suspendable; the persisted message must still exist
            // unchanged before any terminal result is published.
            requireOwner()
            if (failClosedIfStoredMessageChanged()) return
            settleVerificationResult(plan, result, webSearch, notice)
        }

        suspend fun publishAuthorizedResult(
            plan: SingleClaimVerificationPlan,
            authorization: PostAnswerVerificationSearchAuthorization,
            notice: String?,
        ) {
            requireOwner()
            if (failClosedIfStoredMessageChanged()) return
            mutableState.value = SelectedClaimReverificationState.Running(plan.claimId, webSearch = true, notice = notice)
            val result = verifier.verifyWithSearchAuthorization(
                message = message,
                plan = plan.webPlan(),
                operationId = operationId,
                authorization = authorization,
                isCurrent = { owns() && currentStoredMessage() == message },
            )
            // Re-read the persisted message after the (possibly search-backed)
            // verification before publishing, not just during search gating.
            requireOwner()
            if (failClosedIfStoredMessageChanged()) return
            settleVerificationResult(plan, result, webSearch = true, notice = notice)
        }

        try {
            requireOwner()
            if (failClosedIfStoredMessageChanged()) return

            val decision = planProvider()
            requireOwner()
            if (failClosedIfStoredMessageChanged()) return
            val plan = when (decision) {
                is SingleClaimPlanDecision.Ineligible -> {
                    mutableState.value = SelectedClaimReverificationState.Ineligible(
                        claimId = decision.claimId,
                        reason = decision.reason,
                        explanation = decision.explanation,
                    )
                    return
                }
                is SingleClaimPlanDecision.Eligible -> decision.plan
            }
            ownedClaimId = plan.claimId
            mutableState.value = SelectedClaimReverificationState.Preparing(ownedClaimId)
            requireOwner()

            val privateTexts = try {
                privateTextsProvider?.invoke()
            } catch (_: Exception) {
                null
            }
            val privateNotice = PostAnswerSearchPrivacy.privateSearchNotice(message)
            val queryOverlapsPrivate = plan.targetedQuery?.let { request ->
                privateTexts != null && DataEgressPolicy.webQueryContainsPrivate(
                    webQuery = request.query,
                    excerpts = privateTexts,
                    fileNames = PostAnswerSearchPrivacy.localFileNames(message),
                )
            } ?: false
            // The privacy notice is only meaningful when web search is offered.
            val notice = if (!plan.offersWebSearch) {
                null
            } else {
                privateNotice ?: when {
                    privateTexts == null -> PRIVATE_TEXT_UNAVAILABLE_NOTICE
                    queryOverlapsPrivate -> PRIVATE_QUERY_SUPPRESSED_NOTICE
                    else -> null
                }
            }
            val webAllowed = plan.offersWebSearch &&
                privateNotice == null &&
                privateTexts != null &&
                !queryOverlapsPrivate

            if (!webAllowed) {
                publishResult(plan, webSearch = false, notice = notice)
                return
            }

            val egressIdentity = plan.identity.toPostAnswerSearchIdentity()
            val query = AuthorizedTargetedQuery(plan.claimId, requireNotNull(plan.targetedQuery).query)
            val decisionDeferred = CompletableDeferred<SelectedClaimSourceChoice>()
            pendingChoice = decisionDeferred
            mutableState.value = SelectedClaimReverificationState.AwaitingConsent(
                claimId = ownedClaimId,
                operationId = operationId,
                identity = egressIdentity,
                query = query,
            )
            val choice = decisionDeferred.await()
            pendingChoice = null
            requireOwner()
            if (failClosedIfStoredMessageChanged()) return
            when (choice) {
                SelectedClaimSourceChoice.CANCEL -> {
                    mutableState.value = SelectedClaimReverificationState.Idle
                }
                SelectedClaimSourceChoice.EXISTING_SOURCES_ONLY -> {
                    publishResult(plan, webSearch = false, notice = notice)
                }
                SelectedClaimSourceChoice.WEB -> {
                    val authorization = PostAnswerVerificationSearchAuthorization.afterExplicitConfirmation(
                        operationId = operationId,
                        identity = egressIdentity,
                        queries = listOf(query),
                    )
                    if (authorization == null) {
                        mutableState.value = SelectedClaimReverificationState.Failed(
                            ownedClaimId,
                            SelectedClaimFailureReason.INVALID,
                        )
                    } else {
                        publishAuthorizedResult(plan, authorization, notice)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // Ownership lost, superseded or dismissed. Never clobber a newer run.
            if (epoch == run && mutableState.value.claimIdOrNull == ownedClaimId) {
                mutableState.value = SelectedClaimReverificationState.Idle
            }
            throw cancelled
        } catch (_: Exception) {
            if (owns()) {
                mutableState.value = SelectedClaimReverificationState.Failed(
                    ownedClaimId,
                    SelectedClaimFailureReason.INVALID,
                )
            }
        }
    }

    private fun settleVerificationResult(
        plan: SingleClaimVerificationPlan,
        result: EvidenceVerificationResult,
        webSearch: Boolean,
        notice: String?,
    ) {
        val claimId = plan.claimId
        when (result) {
            is EvidenceVerificationResult.Complete -> {
                val report = result.report
                val single = report.claims.singleOrNull()
                // P2B-02: bind the returned claim to the immutable expected claim
                // identity (exact id AND exact text) and require a codec-valid
                // report for the bound answer. A result that changes the selected
                // claim, includes an unrelated claim, or otherwise violates result
                // ownership is rejected; a different claim is never substituted.
                val boundToExpectedClaim = single != null &&
                    single.id == plan.claimId &&
                    single.text == plan.claimText
                val belongsToAnswer = report.answerSha256 == plan.identity.answerSha256
                val codecValid = EvidenceCodec.validate(report) is EvidenceValidation.Valid
                if (!boundToExpectedClaim || !belongsToAnswer || !codecValid) {
                    mutableState.value = SelectedClaimReverificationState.Failed(
                        claimId,
                        SelectedClaimFailureReason.INVALID,
                    )
                    return
                }
                mutableState.value = SelectedClaimReverificationState.Result(
                    claimId = claimId,
                    report = report,
                    transientAudit = result.transientAudit,
                    webSearchAuthorized = webSearch,
                    notice = notice,
                    storedReport = plan.storedReport,
                )
            }
            is EvidenceVerificationResult.Failed -> {
                mutableState.value = SelectedClaimReverificationState.Failed(
                    claimId,
                    result.reason.toSelectedClaimFailureReason(),
                )
            }
            EvidenceVerificationResult.Cancelled -> {
                mutableState.value = SelectedClaimReverificationState.Failed(
                    claimId,
                    SelectedClaimFailureReason.INVALID,
                )
            }
        }
    }
}

internal const val PRIVATE_QUERY_SUPPRESSED_NOTICE =
    "개인 자료와 겹치는 검색어는 웹 검색으로 보내지 않습니다."
internal const val PRIVATE_TEXT_UNAVAILABLE_NOTICE =
    "개인 자료 안전성을 확인할 수 없어 웹 검색을 제공하지 않습니다."

private fun EvidenceAnswerIdentity.toPostAnswerSearchIdentity() = PostAnswerVerificationSearchIdentity(
    conversationId = conversationId,
    assistantMessageId = messageId,
    answerSha256 = answerSha256,
    responseVersionId = messageId,
)

private fun EvidenceVerificationFailureReason.toSelectedClaimFailureReason(): SelectedClaimFailureReason = when (this) {
    EvidenceVerificationFailureReason.DEADLINE_EXCEEDED -> SelectedClaimFailureReason.DEADLINE_EXCEEDED
    EvidenceVerificationFailureReason.SEARCH_UNAVAILABLE -> SelectedClaimFailureReason.SEARCH_UNAVAILABLE
    EvidenceVerificationFailureReason.INVALID_MESSAGE,
    EvidenceVerificationFailureReason.INVALID_PLAN,
    EvidenceVerificationFailureReason.STALE_ANSWER,
    EvidenceVerificationFailureReason.CODEC_REJECTED,
    EvidenceVerificationFailureReason.EGRESS_REFUSED,
    -> SelectedClaimFailureReason.INVALID
}

/** Truthful, non-substituting explanation for an ineligible selected claim. */
internal fun selectedClaimIneligibleExplanation(reason: SelectedClaimIneligibleReason): String = when (reason) {
    SelectedClaimIneligibleReason.UNKNOWN_CLAIM_ID ->
        "선택한 주장을 저장된 기록에서 찾을 수 없어 다시 확인하지 않았습니다."
    SelectedClaimIneligibleReason.ANSWER_CHANGED ->
        "답변이 변경되었거나 삭제되어 이 주장을 다시 확인할 수 없습니다."
    SelectedClaimIneligibleReason.REPORT_INCOMPATIBLE ->
        "저장된 검증 기록이 현재 답변과 호환되지 않아 다시 확인할 수 없습니다."
    SelectedClaimIneligibleReason.CLAIM_NOT_RECONSTRUCTIBLE ->
        "현재 답변에서 이 주장을 다시 구성할 수 없어 다시 확인하지 않았습니다."
    SelectedClaimIneligibleReason.OUTSIDE_EXECUTION_WINDOW ->
        "이 주장은 원래 확인 대상 범위에 포함되지 않아 다시 확인할 수 없습니다."
    SelectedClaimIneligibleReason.CLAIM_TEXT_MISMATCH ->
        "저장된 주장 문장이 현재 답변과 달라 다시 확인하지 않았습니다. 다른 주장을 선택해 주세요."
    SelectedClaimIneligibleReason.NOT_CHECKABLE ->
        "이 주장은 사실 확인 대상으로 분류되지 않아 다시 확인할 수 없습니다."
    SelectedClaimIneligibleReason.SELECTED_TEXT_UNSUPPORTED ->
        "선택한 범위를 한 문장 전체로 다시 선택해 주세요."
    SelectedClaimIneligibleReason.STALE_SELECTION ->
        "답변이나 선택이 변경되어 확인을 시작하지 않았습니다. 현재 답변에서 다시 선택해 주세요."
}

/** Failure copy that never masquerades as a fresh verified result. */
internal fun selectedClaimFailureExplanation(reason: SelectedClaimFailureReason): String = when (reason) {
    SelectedClaimFailureReason.INVALID ->
        "이 주장을 다시 확인하지 못했습니다. 저장된 기록은 그대로 유지됩니다."
    SelectedClaimFailureReason.DEADLINE_EXCEEDED ->
        "다시 확인 시간이 초과되어 결과를 만들지 못했습니다. 저장된 기록은 그대로 유지됩니다."
    SelectedClaimFailureReason.SEARCH_UNAVAILABLE ->
        "웹 검색을 사용할 수 없어 다시 확인을 완료하지 못했습니다. 저장된 기록은 그대로 유지됩니다."
    SelectedClaimFailureReason.VERIFIER_UNAVAILABLE ->
        "확인 기능을 사용할 수 없어 다시 확인하지 못했습니다. 저장된 기록은 그대로 유지됩니다."
}
