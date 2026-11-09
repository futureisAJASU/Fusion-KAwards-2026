package com.projectnuke.fusion.ui

import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceCodec
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceReportCompatibilityPolicy
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceValidation
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.util.SafeWebUrlPolicy
import java.net.URI
import java.util.Locale

/** Origin is part of every comparison record; source ids are report-local. */
enum class ClaimEvidenceReportOrigin {
    STORED,
    TRANSIENT,
}

/**
 * A presentation snapshot bound to the answer surface that currently owns it.
 * Transient snapshots are accepted only while their existing operation owner
 * still owns the result. This type has no persistence or execution dependency.
 */
data class ClaimEvidenceReportSnapshot(
    val identity: EvidenceAnswerIdentity,
    val report: EvidenceReport,
    val origin: ClaimEvidenceReportOrigin,
    /** The existing owner-held result, when this snapshot is transient. */
    val transientOwnerResult: SelectedClaimReverificationState.Result? = null,
)

data class ClaimEvidenceSourceRecord(
    val answerIdentity: EvidenceAnswerIdentity,
    val reportOrigin: ClaimEvidenceReportOrigin,
    val reportCheckedAt: Long,
    val sourceId: String,
    val source: EvidenceSource?,
    /** Non-null only when the existing safe-link policy accepts the URL. */
    val safeOriginalUrl: String?,
    /** Cross-report identity uses only an explicitly validated normalized URL. */
    val normalizedUrlIdentity: String?,
)

enum class ClaimEvidenceSourceUnavailableReason {
    ANSWER_IDENTITY_MISMATCH,
    INVALID_REPORT,
    STORED_REPORT_OUTDATED,
    TRANSIENT_RESULT_NOT_OWNED,
    CLAIM_NOT_FOUND,
}

enum class ClaimEvidenceSourceSelectionIssue {
    READY,
    NO_LINKED_SOURCES,
    NO_RESOLVED_SOURCES,
    ONE_SOURCE_ONLY,
    INVALID_SOURCE_SELECTION,
    SAME_SOURCE_SELECTED,
}

sealed interface ClaimEvidenceSourceProjection {
    data class Unavailable(val reason: ClaimEvidenceSourceUnavailableReason) :
        ClaimEvidenceSourceProjection

    data class Ready(
        val identity: EvidenceAnswerIdentity,
        val origin: ClaimEvidenceReportOrigin,
        val claimId: String,
        val claimText: String,
        val checkedAt: Long,
        /** Follows the exact claim.sourceIds order, including unresolved refs. */
        val linkedSources: List<ClaimEvidenceSourceRecord>,
        val sourceA: ClaimEvidenceSourceRecord?,
        val sourceB: ClaimEvidenceSourceRecord?,
        val selectionIssue: ClaimEvidenceSourceSelectionIssue,
        /** Data observations only; no source receives a support/contradiction verdict. */
        val observations: List<ClaimEvidenceSourceObservation>,
    ) : ClaimEvidenceSourceProjection
}

enum class ClaimEvidenceSourceObservation {
    RECORDED_PUBLICATION_DATES_DIFFER,
    RECORDED_EXCERPTS_DIFFER,
    ACCURACY_CANNOT_BE_DETERMINED_FROM_THESE_RECORDS,
}

enum class ClaimEvidenceSourceIdentityRelation {
    SAME_VALIDATED_ORIGINAL_URL,
    DISTINCT_OR_UNRESOLVED_RECORDS,
}

enum class ClaimEvidenceVerdictTransition {
    SAME_RECORDED_VERDICT,
    SUPPORTED_TO_INSUFFICIENT,
    INSUFFICIENT_TO_SUPPORTED,
    SUPPORTED_TO_CONFLICTING,
    CONFLICTING_TO_INSUFFICIENT,
    NOT_CHECKED_TRANSITION,
    OTHER_RECORDED_CHANGE,
}

enum class ClaimEvidenceResultComparisonUnavailableReason {
    NO_STORED_REPORT,
    NO_TRANSIENT_RESULT,
    ANSWER_IDENTITY_MISMATCH,
    INVALID_REPORT,
    STORED_REPORT_OUTDATED,
    TRANSIENT_RESULT_NOT_OWNED,
    TRANSIENT_REPORT_NOT_SINGLE_CLAIM,
    STORED_CLAIM_NOT_FOUND,
    CLAIM_ID_MISMATCH,
    CLAIM_TEXT_MISMATCH,
}

data class ClaimEvidenceRecordedResult(
    val origin: ClaimEvidenceReportOrigin,
    val checkedAt: Long,
    val claimId: String,
    val claimText: String,
    val verdict: EvidenceVerdict,
    val reasonCode: String?,
    val sourceRecords: List<ClaimEvidenceSourceRecord>,
    val candidateWindowTruncated: Boolean,
    val overlengthSegmentsExcluded: Boolean,
    /** Null for a stored report; true/false only for the owned transient run. */
    val webSearchAuthorized: Boolean?,
)

data class ClaimEvidenceResultComparison(
    val identity: EvidenceAnswerIdentity,
    val stored: ClaimEvidenceRecordedResult,
    val transient: ClaimEvidenceRecordedResult,
    val transition: ClaimEvidenceVerdictTransition,
    /** Count of distinct validated original URLs represented on both sides. */
    val sharedValidatedUrlCount: Int,
    /** Records without a validated same-URL counterpart remain independent. */
    val storedUnmatchedByValidatedUrlCount: Int,
    val transientUnmatchedByValidatedUrlCount: Int,
)

sealed interface ClaimEvidenceResultComparisonProjection {
    data class Unavailable(val reason: ClaimEvidenceResultComparisonUnavailableReason) :
        ClaimEvidenceResultComparisonProjection

    /** The transient report is valid and owned, but stored history cannot be compared. */
    data class TemporaryOnly(
        val reason: ClaimEvidenceResultComparisonUnavailableReason,
        val transientSources: ClaimEvidenceSourceProjection.Ready,
    ) : ClaimEvidenceResultComparisonProjection

    data class Available(val comparison: ClaimEvidenceResultComparison) :
        ClaimEvidenceResultComparisonProjection
}

/**
 * Pure, fail-closed projection for both comparison modes. It never calls a
 * verifier, search executor, store, message writer, or response-version owner.
 */
object ClaimEvidenceComparisonProjection {

    fun projectSources(
        currentIdentity: EvidenceAnswerIdentity,
        snapshot: ClaimEvidenceReportSnapshot,
        claimId: String,
        nowMillis: Long,
        selectedSourceAId: String? = null,
        selectedSourceBId: String? = null,
    ): ClaimEvidenceSourceProjection {
        sourceUnavailableReason(snapshot, currentIdentity, nowMillis)?.let {
            return ClaimEvidenceSourceProjection.Unavailable(it)
        }
        val claim = snapshot.report.claims.firstOrNull { it.id == claimId }
            ?: return ClaimEvidenceSourceProjection.Unavailable(
                ClaimEvidenceSourceUnavailableReason.CLAIM_NOT_FOUND,
            )
        if (snapshot.origin == ClaimEvidenceReportOrigin.TRANSIENT &&
            snapshot.transientOwnerResult?.claimId != claimId
        ) {
            return ClaimEvidenceSourceProjection.Unavailable(
                ClaimEvidenceSourceUnavailableReason.TRANSIENT_RESULT_NOT_OWNED,
            )
        }
        return projectClaimSources(
            snapshot = snapshot,
            claim = claim,
            selectedSourceAId = selectedSourceAId,
            selectedSourceBId = selectedSourceBId,
        )
    }

    /**
     * Compares only exact same-answer, same-id, same-text claims. Report-local
     * source ids are never joined across reports; only validated URL identity
     * can establish an optional cross-report source correspondence.
     */
    fun projectStoredAgainstTransient(
        currentIdentity: EvidenceAnswerIdentity,
        claimId: String,
        exactClaimText: String,
        stored: ClaimEvidenceReportSnapshot?,
        transient: ClaimEvidenceReportSnapshot?,
        nowMillis: Long,
        webSearchAuthorized: Boolean,
    ): ClaimEvidenceResultComparisonProjection {
        if (transient == null) {
            return ClaimEvidenceResultComparisonProjection.Unavailable(
                ClaimEvidenceResultComparisonUnavailableReason.NO_TRANSIENT_RESULT,
            )
        }
        val validatedTransient = when (
            val validation = validateTransientClaim(
                currentIdentity = currentIdentity,
                claimId = claimId,
                exactClaimText = exactClaimText,
                snapshot = transient,
                nowMillis = nowMillis,
            )
        ) {
            is TransientClaimValidation.Invalid ->
                return ClaimEvidenceResultComparisonProjection.Unavailable(validation.reason)
            is TransientClaimValidation.Valid -> validation
        }
        val storedReport = stored ?: return ClaimEvidenceResultComparisonProjection.TemporaryOnly(
            reason = ClaimEvidenceResultComparisonUnavailableReason.NO_STORED_REPORT,
            transientSources = validatedTransient.sources,
        )
        val storedFailure = storedComparisonUnavailableReason(
            currentIdentity = currentIdentity,
            claimId = claimId,
            exactClaimText = exactClaimText,
            snapshot = storedReport,
            nowMillis = nowMillis,
        )
        if (storedFailure != null) {
            return ClaimEvidenceResultComparisonProjection.TemporaryOnly(
                reason = storedFailure,
                transientSources = validatedTransient.sources,
            )
        }
        val storedClaim = requireNotNull(storedReport.report.claims.firstOrNull { it.id == claimId })
        val transientClaim = validatedTransient.claim
        val storedSources = projectClaimSources(storedReport, storedClaim).linkedSources
        val transientSources = validatedTransient.sources.linkedSources
        val storedUrls = storedSources.mapNotNull(ClaimEvidenceSourceRecord::normalizedUrlIdentity).toSet()
        val transientUrls = transientSources.mapNotNull(ClaimEvidenceSourceRecord::normalizedUrlIdentity).toSet()
        val sharedUrls = storedUrls intersect transientUrls
        val storedResult = storedClaim.toRecordedResult(storedReport, storedSources, webSearchAuthorized = null)
        val transientResult = transientClaim.toRecordedResult(
            transient,
            transientSources,
            webSearchAuthorized = webSearchAuthorized,
        )
        return ClaimEvidenceResultComparisonProjection.Available(
            ClaimEvidenceResultComparison(
                identity = currentIdentity,
                stored = storedResult,
                transient = transientResult,
                transition = transition(storedClaim.verdict, transientClaim.verdict),
                sharedValidatedUrlCount = sharedUrls.size,
                storedUnmatchedByValidatedUrlCount = storedSources.count {
                    it.normalizedUrlIdentity == null || it.normalizedUrlIdentity !in transientUrls
                },
                transientUnmatchedByValidatedUrlCount = transientSources.count {
                    it.normalizedUrlIdentity == null || it.normalizedUrlIdentity !in storedUrls
                },
            ),
        )
    }

    private data class SourcePairSelection(
        val resolved: List<ClaimEvidenceSourceRecord>,
        val sourceA: ClaimEvidenceSourceRecord?,
        val sourceB: ClaimEvidenceSourceRecord?,
        val issue: ClaimEvidenceSourceSelectionIssue,
    )

    private sealed interface TransientClaimValidation {
        data class Valid(
            val claim: EvidenceClaim,
            val sources: ClaimEvidenceSourceProjection.Ready,
        ) : TransientClaimValidation

        data class Invalid(val reason: ClaimEvidenceResultComparisonUnavailableReason) :
            TransientClaimValidation
    }

    private fun projectClaimSources(
        snapshot: ClaimEvidenceReportSnapshot,
        claim: EvidenceClaim,
        selectedSourceAId: String? = null,
        selectedSourceBId: String? = null,
    ): ClaimEvidenceSourceProjection.Ready {
        val sourcesById = snapshot.report.sources.associateBy(EvidenceSource::id)
        val linkedSources = claim.sourceIds.map { sourceId ->
            val source = sourcesById[sourceId]
            ClaimEvidenceSourceRecord(
                answerIdentity = snapshot.identity,
                reportOrigin = snapshot.origin,
                reportCheckedAt = snapshot.report.checkedAt,
                sourceId = sourceId,
                source = source,
                safeOriginalUrl = source?.let { SafeWebUrlPolicy.normalize(it.url) },
                normalizedUrlIdentity = source?.let { normalizedSourceUrlIdentity(it.url) },
            )
        }
        val pair = selectSourcePair(linkedSources, selectedSourceAId, selectedSourceBId)
        return ClaimEvidenceSourceProjection.Ready(
            identity = snapshot.identity,
            origin = snapshot.origin,
            claimId = claim.id,
            claimText = claim.text,
            checkedAt = snapshot.report.checkedAt,
            linkedSources = linkedSources,
            sourceA = pair.sourceA,
            sourceB = pair.sourceB,
            selectionIssue = pair.issue,
            observations = sourceObservations(pair),
        )
    }

    private fun selectSourcePair(
        linkedSources: List<ClaimEvidenceSourceRecord>,
        selectedSourceAId: String?,
        selectedSourceBId: String?,
    ): SourcePairSelection {
        val resolved = linkedSources.filter { it.source != null }
        val sourceA = selectedSourceAId?.let { id -> resolved.firstOrNull { it.sourceId == id } }
            ?: if (selectedSourceAId == null) resolved.firstOrNull() else null
        val sourceB = selectedSourceBId?.let { id -> resolved.firstOrNull { it.sourceId == id } }
            ?: if (selectedSourceBId == null) resolved.firstOrNull { it.sourceId != sourceA?.sourceId } else null
        return SourcePairSelection(
            resolved = resolved,
            sourceA = sourceA,
            sourceB = sourceB,
            issue = sourceSelectionIssue(linkedSources, resolved, sourceA, sourceB, selectedSourceAId, selectedSourceBId),
        )
    }

    private fun sourceSelectionIssue(
        linked: List<ClaimEvidenceSourceRecord>,
        resolved: List<ClaimEvidenceSourceRecord>,
        sourceA: ClaimEvidenceSourceRecord?,
        sourceB: ClaimEvidenceSourceRecord?,
        selectedSourceAId: String?,
        selectedSourceBId: String?,
    ): ClaimEvidenceSourceSelectionIssue = when {
        linked.isEmpty() -> ClaimEvidenceSourceSelectionIssue.NO_LINKED_SOURCES
        resolved.isEmpty() -> ClaimEvidenceSourceSelectionIssue.NO_RESOLVED_SOURCES
        selectedSourceAId != null && sourceA == null || selectedSourceBId != null && sourceB == null ->
            ClaimEvidenceSourceSelectionIssue.INVALID_SOURCE_SELECTION
        sourceA != null && sourceB != null && sourceA.sourceId == sourceB.sourceId ->
            ClaimEvidenceSourceSelectionIssue.SAME_SOURCE_SELECTED
        resolved.size < 2 -> ClaimEvidenceSourceSelectionIssue.ONE_SOURCE_ONLY
        sourceA == null || sourceB == null -> ClaimEvidenceSourceSelectionIssue.INVALID_SOURCE_SELECTION
        else -> ClaimEvidenceSourceSelectionIssue.READY
    }

    private fun sourceObservations(pair: SourcePairSelection): List<ClaimEvidenceSourceObservation> {
        if (pair.issue != ClaimEvidenceSourceSelectionIssue.READY) return emptyList()
        val first = requireNotNull(pair.sourceA?.source)
        val second = requireNotNull(pair.sourceB?.source)
        return buildList {
            if (first.publishedAt != null && second.publishedAt != null && first.publishedAt != second.publishedAt) {
                add(ClaimEvidenceSourceObservation.RECORDED_PUBLICATION_DATES_DIFFER)
            }
            if (first.snippet != second.snippet) {
                add(ClaimEvidenceSourceObservation.RECORDED_EXCERPTS_DIFFER)
            }
            add(ClaimEvidenceSourceObservation.ACCURACY_CANNOT_BE_DETERMINED_FROM_THESE_RECORDS)
        }
    }

    private fun validateTransientClaim(
        currentIdentity: EvidenceAnswerIdentity,
        claimId: String,
        exactClaimText: String,
        snapshot: ClaimEvidenceReportSnapshot,
        nowMillis: Long,
    ): TransientClaimValidation {
        if (snapshot.origin != ClaimEvidenceReportOrigin.TRANSIENT) {
            return TransientClaimValidation.Invalid(ClaimEvidenceResultComparisonUnavailableReason.INVALID_REPORT)
        }
        sourceUnavailableReason(snapshot, currentIdentity, nowMillis)?.let { reason ->
            return TransientClaimValidation.Invalid(reason.toComparisonUnavailableReason())
        }
        val claim = snapshot.report.claims.singleOrNull() ?: return TransientClaimValidation.Invalid(
            ClaimEvidenceResultComparisonUnavailableReason.TRANSIENT_REPORT_NOT_SINGLE_CLAIM,
        )
        if (claim.id != claimId) {
            return TransientClaimValidation.Invalid(ClaimEvidenceResultComparisonUnavailableReason.CLAIM_ID_MISMATCH)
        }
        if (claim.text != exactClaimText) {
            return TransientClaimValidation.Invalid(ClaimEvidenceResultComparisonUnavailableReason.CLAIM_TEXT_MISMATCH)
        }
        if (snapshot.transientOwnerResult?.claimId != claimId) {
            return TransientClaimValidation.Invalid(
                ClaimEvidenceResultComparisonUnavailableReason.TRANSIENT_RESULT_NOT_OWNED,
            )
        }
        return TransientClaimValidation.Valid(
            claim = claim,
            sources = projectClaimSources(snapshot, claim),
        )
    }

    private fun storedComparisonUnavailableReason(
        currentIdentity: EvidenceAnswerIdentity,
        claimId: String,
        exactClaimText: String,
        snapshot: ClaimEvidenceReportSnapshot,
        nowMillis: Long,
    ): ClaimEvidenceResultComparisonUnavailableReason? {
        if (snapshot.origin != ClaimEvidenceReportOrigin.STORED) {
            return ClaimEvidenceResultComparisonUnavailableReason.INVALID_REPORT
        }
        sourceUnavailableReason(snapshot, currentIdentity, nowMillis)?.let { return it.toComparisonUnavailableReason() }
        val storedClaim = snapshot.report.claims.firstOrNull { it.id == claimId }
            ?: return ClaimEvidenceResultComparisonUnavailableReason.STORED_CLAIM_NOT_FOUND
        return if (storedClaim.text == exactClaimText) {
            null
        } else {
            ClaimEvidenceResultComparisonUnavailableReason.CLAIM_TEXT_MISMATCH
        }
    }

    private fun ClaimEvidenceSourceUnavailableReason.toComparisonUnavailableReason():
        ClaimEvidenceResultComparisonUnavailableReason = when (this) {
        ClaimEvidenceSourceUnavailableReason.ANSWER_IDENTITY_MISMATCH ->
            ClaimEvidenceResultComparisonUnavailableReason.ANSWER_IDENTITY_MISMATCH
        ClaimEvidenceSourceUnavailableReason.INVALID_REPORT ->
            ClaimEvidenceResultComparisonUnavailableReason.INVALID_REPORT
        ClaimEvidenceSourceUnavailableReason.STORED_REPORT_OUTDATED ->
            ClaimEvidenceResultComparisonUnavailableReason.STORED_REPORT_OUTDATED
        ClaimEvidenceSourceUnavailableReason.TRANSIENT_RESULT_NOT_OWNED ->
            ClaimEvidenceResultComparisonUnavailableReason.TRANSIENT_RESULT_NOT_OWNED
        ClaimEvidenceSourceUnavailableReason.CLAIM_NOT_FOUND ->
            ClaimEvidenceResultComparisonUnavailableReason.CLAIM_ID_MISMATCH
    }

    fun sourceIdentityRelation(
        first: ClaimEvidenceSourceRecord,
        second: ClaimEvidenceSourceRecord,
    ): ClaimEvidenceSourceIdentityRelation = when {
        first.normalizedUrlIdentity != null &&
            first.normalizedUrlIdentity == second.normalizedUrlIdentity ->
            ClaimEvidenceSourceIdentityRelation.SAME_VALIDATED_ORIGINAL_URL
        else -> ClaimEvidenceSourceIdentityRelation.DISTINCT_OR_UNRESOLVED_RECORDS
    }

    private fun sourceUnavailableReason(
        snapshot: ClaimEvidenceReportSnapshot,
        currentIdentity: EvidenceAnswerIdentity,
        nowMillis: Long,
    ): ClaimEvidenceSourceUnavailableReason? {
        if (snapshot.identity != currentIdentity ||
            snapshot.report.answerSha256 != currentIdentity.answerSha256
        ) {
            return ClaimEvidenceSourceUnavailableReason.ANSWER_IDENTITY_MISMATCH
        }
        if (EvidenceCodec.validate(snapshot.report) !is EvidenceValidation.Valid) {
            return ClaimEvidenceSourceUnavailableReason.INVALID_REPORT
        }
        return when (snapshot.origin) {
            ClaimEvidenceReportOrigin.STORED -> {
                if (EvidenceReportCompatibilityPolicy.isCurrentDisplayCompatible(
                        snapshot.report,
                        currentIdentity.answerSha256,
                        nowMillis,
                    )
                ) {
                    null
                } else {
                    ClaimEvidenceSourceUnavailableReason.STORED_REPORT_OUTDATED
                }
            }
            ClaimEvidenceReportOrigin.TRANSIENT ->
                when {
                    snapshot.transientOwnerResult?.let { owner ->
                        owner.report === snapshot.report
                    } != true ->
                        ClaimEvidenceSourceUnavailableReason.TRANSIENT_RESULT_NOT_OWNED
                    EvidenceReportCompatibilityPolicy.isClockInvalid(snapshot.report, nowMillis) ->
                        ClaimEvidenceSourceUnavailableReason.INVALID_REPORT
                    else -> null
                }
        }
    }

    private fun EvidenceClaim.toRecordedResult(
        snapshot: ClaimEvidenceReportSnapshot,
        sources: List<ClaimEvidenceSourceRecord>,
        webSearchAuthorized: Boolean?,
    ) = ClaimEvidenceRecordedResult(
        origin = snapshot.origin,
        checkedAt = snapshot.report.checkedAt,
        claimId = id,
        claimText = text,
        verdict = verdict,
        reasonCode = reasonCode,
        sourceRecords = sources,
        candidateWindowTruncated = snapshot.report.candidateWindowTruncated,
        overlengthSegmentsExcluded = snapshot.report.overlengthSegmentsExcluded,
        webSearchAuthorized = webSearchAuthorized,
    )

    private fun transition(
        stored: EvidenceVerdict,
        current: EvidenceVerdict,
    ): ClaimEvidenceVerdictTransition = when {
        stored == current -> ClaimEvidenceVerdictTransition.SAME_RECORDED_VERDICT
        stored == EvidenceVerdict.SUPPORTED_BY_EVIDENCE &&
            current == EvidenceVerdict.INSUFFICIENT_EVIDENCE ->
            ClaimEvidenceVerdictTransition.SUPPORTED_TO_INSUFFICIENT
        stored == EvidenceVerdict.INSUFFICIENT_EVIDENCE &&
            current == EvidenceVerdict.SUPPORTED_BY_EVIDENCE ->
            ClaimEvidenceVerdictTransition.INSUFFICIENT_TO_SUPPORTED
        stored == EvidenceVerdict.SUPPORTED_BY_EVIDENCE &&
            current == EvidenceVerdict.CONFLICTING_EVIDENCE ->
            ClaimEvidenceVerdictTransition.SUPPORTED_TO_CONFLICTING
        stored == EvidenceVerdict.CONFLICTING_EVIDENCE &&
            current == EvidenceVerdict.INSUFFICIENT_EVIDENCE ->
            ClaimEvidenceVerdictTransition.CONFLICTING_TO_INSUFFICIENT
        stored == EvidenceVerdict.NOT_CHECKED || current == EvidenceVerdict.NOT_CHECKED ->
            ClaimEvidenceVerdictTransition.NOT_CHECKED_TRANSITION
        else -> ClaimEvidenceVerdictTransition.OTHER_RECORDED_CHANGE
    }

    private fun normalizedSourceUrlIdentity(rawUrl: String): String? {
        val safe = SafeWebUrlPolicy.normalize(rawUrl) ?: return null
        return runCatching {
            val uri = URI(safe).normalize()
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching null
            val port = when {
                scheme == "http" && uri.port == 80 -> ""
                scheme == "https" && uri.port == 443 -> ""
                uri.port < 0 -> ""
                else -> ":".plus(uri.port)
            }
            buildString {
                append(scheme)
                append("://")
                append(host)
                append(port)
                append(uri.rawPath.orEmpty())
                uri.rawQuery?.let { append('?').append(it) }
                uri.rawFragment?.let { append('#').append(it) }
            }
        }.getOrNull()
    }
}
