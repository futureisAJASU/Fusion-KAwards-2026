package com.projectnuke.fusion.ui

import com.projectnuke.fusion.audit.AuditCoverage
import com.projectnuke.fusion.audit.auditCoverageLabel
import com.projectnuke.fusion.audit.projectEvidenceReport
import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceClock
import com.projectnuke.fusion.data.EvidenceOutdatedReason
import com.projectnuke.fusion.data.EvidencePlanningLimits
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceReportCompatibilityPolicy
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceStore
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.data.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UX-P2A — READ-ONLY claim evidence explorer.
 *
 * This is a strictly read-only presentation layer over an already persisted
 * [EvidenceReport]. It:
 *  - never runs verification, inference, retrieval, web search, planning,
 *    translation or rewriting;
 *  - never writes Evidence, messages or response versions;
 *  - has NO verifier / search / planner dependency at all, so a read-only path
 *    is guaranteed by construction rather than by remembering to skip a call.
 *
 * It reuses the existing durable evidence identity and compatibility contracts
 * ([EvidenceAnswerIdentity], [EvidenceStore.load],
 * [EvidenceReportCompatibilityPolicy]) and the existing presentation components
 * ([EvidenceSourceCard]); it does not create another verification owner.
 *
 * The active selected-claim VERIFICATION roadmap (UX-P2B) is implemented by a
 * SEPARATE owner ([SelectedClaimReverificationOwner]); nothing in this file
 * verifies a claim, so this read-only owner stays read-only by construction.
 */

/** Why the read-only explorer cannot show stored claims for the current answer. */
enum class SavedClaimAbsence {
    /** No persisted report exists for this exact answer. */
    MISSING,

    /** The stored report belongs to a different canonical answer hash. */
    STALE,

    /** Rules/version/expiry/clock compatibility failed. */
    OUTDATED,

    /** The stored payload could not be parsed/validated; kept for forensics. */
    CORRUPT,

    /** The read itself failed (I/O, ownership). No data is shown. */
    READ_FAILED,
}

/** Immutable read-only explorer state; never carries verification work. */
sealed interface SavedClaimExplorerState {
    /** Nothing is shown. */
    data object Closed : SavedClaimExplorerState

    /** A read-only load is in flight for [identity]. */
    data class Loading(val identity: EvidenceAnswerIdentity) : SavedClaimExplorerState

    /** Fail-closed state: no verdicts or citations may be shown. */
    data class Unavailable(
        val identity: EvidenceAnswerIdentity,
        val absence: SavedClaimAbsence,
        val outdatedReason: EvidenceOutdatedReason? = null,
    ) : SavedClaimExplorerState

    /**
     * A compatible stored report is available for read-only inspection.
     * [selectedClaimId] is null while the claims list is shown.
     */
    data class Ready(
        val identity: EvidenceAnswerIdentity,
        val report: EvidenceReport,
        val selectedClaimId: String? = null,
    ) : SavedClaimExplorerState
}

/** One selectable claim row in the read-only claims list. */
data class SavedClaimSummary(
    val claimId: String,
    val text: String,
    val verdict: EvidenceVerdict,
    val reasonCode: String?,
    val sourceCount: Int,
)

/**
 * One resolved (or dangling) stored source reference for a claim.
 * [source] is null exactly when the claim references a source id that is absent
 * from the report; [danglingReferenceId] then names the missing reference so the
 * UI can show a conservative missing-source state instead of inventing material.
 */
data class SavedClaimSourceView(
    val source: EvidenceSource?,
    val danglingReferenceId: String?,
)

/** The focused, read-only projection of one selected claim. */
data class SavedClaimDetail(
    val claimId: String,
    val text: String,
    val verdict: EvidenceVerdict,
    val reasonCode: String?,
    val sources: List<SavedClaimSourceView>,
)

/**
 * Canonical Answer-Audit coverage for a stored report, projected through the
 * SAME presentation contract the original Evidence dialog uses
 * ([projectEvidenceReport] + [auditCoverageLabel]).
 *
 * Coverage is computed WITHOUT `hasAuthoritativeCompleteCoverage`, so the
 * read-only explorer can never claim an authoritative whole-answer AUDITED
 * state. It only ever surfaces [AuditCoverage.PARTIALLY_AUDITED] /
 * [AuditCoverage.NOT_AUDITED] together with explicit non-authoritative
 * [guidance] that never implies complete-answer verification.
 */
data class SavedClaimCoverageGuidance(
    val coverage: AuditCoverage,
    val label: String,
    val guidance: String,
)

/** Outcome of projecting a stored load into the read-only explorer. */
sealed interface SavedClaimLoadProjection {
    data class Ready(val report: EvidenceReport) : SavedClaimLoadProjection
    data class Unavailable(
        val absence: SavedClaimAbsence,
        val outdatedReason: EvidenceOutdatedReason? = null,
    ) : SavedClaimLoadProjection
}

/**
 * Pure, deterministic read-only projections. No Android, no I/O, no state.
 * Everything here is unit-testable on the JVM and side-effect free.
 */
object SavedClaimEvidenceProjection {

    /**
     * Decides whether a loaded stored result may be shown read-only for the
     * exact current answer. Fails closed on every non-compatible outcome; never
     * falls back to active verification.
     */
    fun projectLoad(
        loaded: EvidenceStore.LoadResult,
        answerSha256: String,
        nowMillis: Long,
    ): SavedClaimLoadProjection = when (loaded) {
        EvidenceStore.LoadResult.Missing ->
            SavedClaimLoadProjection.Unavailable(SavedClaimAbsence.MISSING)
        EvidenceStore.LoadResult.Corrupt ->
            SavedClaimLoadProjection.Unavailable(SavedClaimAbsence.CORRUPT)
        is EvidenceStore.LoadResult.Stale ->
            SavedClaimLoadProjection.Unavailable(SavedClaimAbsence.STALE)
        is EvidenceStore.LoadResult.Valid -> {
            if (EvidenceReportCompatibilityPolicy.isCurrentDisplayCompatible(
                    loaded.report,
                    answerSha256,
                    nowMillis,
                )
            ) {
                SavedClaimLoadProjection.Ready(loaded.report)
            } else {
                SavedClaimLoadProjection.Unavailable(
                    absence = SavedClaimAbsence.OUTDATED,
                    outdatedReason = EvidenceReportCompatibilityPolicy.outdatedReason(
                        loaded.report,
                        answerSha256,
                        nowMillis,
                    ),
                )
            }
        }
    }

    /** Stable, report-ordered claim summaries. Never reorders or invents claims. */
    fun summariesOf(report: EvidenceReport): List<SavedClaimSummary> =
        report.claims.map { claim ->
            SavedClaimSummary(
                claimId = claim.id,
                text = claim.text,
                verdict = claim.verdict,
                reasonCode = claim.reasonCode,
                sourceCount = claim.sourceIds.size,
            )
        }

    /**
     * Resolves one claim by its stable id. Returns null for an unrecognized id
     * (never matches by list position or fuzzy text).
     */
    fun detailOf(report: EvidenceReport, claimId: String): SavedClaimDetail? {
        val claim = report.claims.firstOrNull { it.id == claimId } ?: return null
        return detailOfClaim(claim, report)
    }

    /** Source references are resolved strictly from the claim's own sourceIds. */
    fun sourcesOf(report: EvidenceReport, claim: EvidenceClaim): List<SavedClaimSourceView> {
        val byId = report.sources.associateBy { it.id }
        return claim.sourceIds.map { referenceId ->
            val source = byId[referenceId]
            SavedClaimSourceView(
                source = source,
                danglingReferenceId = if (source == null) referenceId else null,
            )
        }
    }

    private fun detailOfClaim(claim: EvidenceClaim, report: EvidenceReport): SavedClaimDetail =
        SavedClaimDetail(
            claimId = claim.id,
            text = claim.text,
            verdict = claim.verdict,
            reasonCode = claim.reasonCode,
            sources = sourcesOf(report, claim),
        )

    /**
     * Verification-limitation copy for the stored report. Preserves the existing
     * coverage warnings (candidate-window truncation, overlength exclusion) so a
     * read-only view never overstates completeness.
     */
    fun limitationLines(report: EvidenceReport): List<String> = buildList {
        if (report.claims.isEmpty()) {
            add(
                if (report.candidateWindowTruncated || report.overlengthSegmentsExcluded) {
                    "기록된 확인 주장은 없지만, 답변 일부가 확인 범위에 포함되지 않았어요. 아래 제한 안내를 확인해 주세요."
                } else {
                    "확인할 사실 주장을 찾지 못했습니다."
                },
            )
        }
        if (report.candidateWindowTruncated) {
            add(
                "확인 후보는 최대 ${EvidencePlanningLimits.MAX_CLAIM_CANDIDATES}개입니다. 그보다 많은 후보는 확인 범위에서 빠질 수 있어요.",
            )
        }
        if (report.overlengthSegmentsExcluded) {
            add(
                "개별 확인 문장이 허용 길이(${EvidencePlanningLimits.MAX_CLAIM_TEXT_LENGTH}자)를 넘어 일부 문장을 확인하지 않았어요.",
            )
        }
    }

    /**
     * Canonical audit-coverage guidance for the read-only view. Delegates to the
     * existing [projectEvidenceReport] / [auditCoverageLabel] presentation
     * contracts (no new audit algorithm) and fails closed to null when the report
     * is not projectable or when coverage would be the authoritative
     * [AuditCoverage.AUDITED] state, which the read-only explorer never asserts.
     */
    fun coverageGuidance(report: EvidenceReport): SavedClaimCoverageGuidance? {
        val coverage = projectEvidenceReport(report)?.coverage ?: return null
        // AUDITED (authoritative complete coverage) is intentionally never shown
        // here: auditCoverageLabel returns null for it, so nothing is displayed.
        val label = auditCoverageLabel(coverage) ?: return null
        val guidance = when (coverage) {
            AuditCoverage.AUDITED -> return null
            AuditCoverage.PARTIALLY_AUDITED ->
                "이 기록은 답변의 일부 주장만 확인했습니다. 확인되지 않은 주장은 사실 여부가 판정되지 않았습니다."
            AuditCoverage.NOT_AUDITED ->
                "이 기록에서 확인된 주장이 없습니다. 답변 전체의 사실성을 보장하지 않습니다."
        }
        return SavedClaimCoverageGuidance(coverage = coverage, label = label, guidance = guidance)
    }
}

/**
 * Minimal READ-ONLY presentation-state holder for the saved-claims explorer.
 *
 * It holds a [store] (load only) and a clock; it deliberately has NO verifier,
 * no search executor, no planner and no writer. Every suspension point is
 * followed by an ownership recheck so a response-version switch, conversation
 * navigation, regeneration or deletion invalidates the load and dismisses the
 * explorer instead of displaying stale data. It cannot compete with the existing
 * [EvidenceMessageActionHost] because it performs no verification work.
 */
class SavedClaimEvidenceExplorerOwner(
    private val scope: CoroutineScope,
    private val store: EvidenceUiStore,
    private val clock: EvidenceClock = EvidenceClock { System.currentTimeMillis() },
    private val currentMessageReader: suspend (MessageEntity) -> MessageEntity? = { it },
    private val isCurrentAnswer: () -> Boolean = { true },
) {
    private val mutableState = MutableStateFlow<SavedClaimExplorerState>(SavedClaimExplorerState.Closed)
    val state: StateFlow<SavedClaimExplorerState> = mutableState.asStateFlow()

    private var job: Job? = null
    private var epoch = 0L

    /**
     * Opens/refreshes the read-only explorer for [message]. Performs exactly one
     * read-only [EvidenceUiStore.load]; it never triggers verification.
     */
    fun open(message: MessageEntity) {
        if (!isEligibleAnswer(message)) return
        val identity = EvidenceAnswerIdentity.of(message)
        if (job?.isActive == true && mutableState.value.identityOrNull() == identity) return

        job?.cancel()
        val run = ++epoch
        mutableState.value = SavedClaimExplorerState.Loading(identity)
        job = scope.launch { runLoad(message, identity, run) }
    }

    /** True while THIS run still owns the explorer state (epoch + identity). */
    private fun owns(identity: EvidenceAnswerIdentity, run: Long): Boolean =
        epoch == run && isCurrentAnswer() && mutableState.value.identityOrNull() == identity

    private suspend fun runLoad(message: MessageEntity, identity: EvidenceAnswerIdentity, run: Long) {
        suspend fun requireOwner() {
            currentCoroutineContext().ensureActive()
            if (!owns(identity, run)) throw CancellationException("saved-claims explorer owner changed")
        }
        try {
            requireOwner()
            val loaded = store.load(message)
            requireOwner()
            // Re-read immediately after the suspension point; the captured
            // Compose message is never treated as current by itself.
            if (currentMessageReader(message) != message) {
                mutableState.value = SavedClaimExplorerState.Closed
                return
            }
            requireOwner()
            applyProjection(
                identity,
                SavedClaimEvidenceProjection.projectLoad(loaded, identity.answerSha256, clock.nowMillis()),
            )
        } catch (cancelled: CancellationException) {
            // Ownership lost (answer changed / deleted / replaced) or the
            // explorer was dismissed. Only settle Closed when THIS run still
            // owns the state, so a newer open() is never clobbered.
            if (epoch == run && mutableState.value.identityOrNull() == identity) {
                mutableState.value = SavedClaimExplorerState.Closed
            }
            throw cancelled
        } catch (_: Exception) {
            if (owns(identity, run)) {
                mutableState.value = SavedClaimExplorerState.Unavailable(
                    identity = identity,
                    absence = SavedClaimAbsence.READ_FAILED,
                )
            }
        }
    }

    private fun applyProjection(identity: EvidenceAnswerIdentity, projected: SavedClaimLoadProjection) {
        mutableState.value = when (projected) {
            is SavedClaimLoadProjection.Ready ->
                SavedClaimExplorerState.Ready(identity, projected.report)
            is SavedClaimLoadProjection.Unavailable ->
                SavedClaimExplorerState.Unavailable(
                    identity = identity,
                    absence = projected.absence,
                    outdatedReason = projected.outdatedReason,
                )
        }
    }

    /**
     * Selects a stored claim by its stable id. Unknown ids are ignored (never
     * matched by position/text). Pure presentation mutation; no I/O.
     */
    fun selectClaim(claimId: String) {
        val current = mutableState.value
        if (current !is SavedClaimExplorerState.Ready) return
        if (SavedClaimEvidenceProjection.detailOf(current.report, claimId) == null) return
        mutableState.value = current.copy(selectedClaimId = claimId)
    }

    /** Returns from the focused claim detail to the claims list. No I/O. */
    fun backToClaims() {
        val current = mutableState.value
        if (current is SavedClaimExplorerState.Ready) mutableState.value = current.copy(selectedClaimId = null)
    }

    /** Dismisses and invalidates any in-flight read-only load. */
    fun dismiss() {
        epoch++
        job?.cancel()
        job = null
        mutableState.value = SavedClaimExplorerState.Closed
    }

    private fun SavedClaimExplorerState.identityOrNull(): EvidenceAnswerIdentity? = when (this) {
        is SavedClaimExplorerState.Loading -> identity
        is SavedClaimExplorerState.Unavailable -> identity
        is SavedClaimExplorerState.Ready -> identity
        SavedClaimExplorerState.Closed -> null
    }
}

/** True when a message may offer the read-only saved-claims explorer. */
internal fun isEligibleAnswer(message: MessageEntity?): Boolean =
    message != null &&
        message.role == EvidenceStore.ASSISTANT_ROLE &&
        message.id > 0L &&
        message.conversationId > 0L

/** Production entry-point availability: an assistant answer with an evidence store. */
internal fun canOfferSavedClaimEvidence(message: MessageEntity?, storeAvailable: Boolean): Boolean =
    storeAvailable && isEligibleAnswer(message)
