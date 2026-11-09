package com.projectnuke.fusion.audit

import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceVerificationLimits
import com.projectnuke.fusion.data.EvidenceFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.security.MessageDigest

const val AUDIT_BACKEND_TOTAL_DEADLINE_MS = 5_000L
const val MAX_AUDIT_PAIR_EXECUTIONS_PER_CLAIM = VerifierCandidateAdapter.MAX_CANDIDATES_PER_CLAIM

data class VerifierClaimInput(
    val claimId: String,
    val claimText: String,
    val selectedCandidates: List<EvidenceSource>,
    val verificationRequirement: AuditVerificationRequirement = AuditVerificationRequirement.REQUIRED,
    val toolTrace: AuditToolTrace? = null,
) {
    init {
        require(claimId.isNotBlank() && claimId.length <= com.projectnuke.fusion.data.EvidenceLimits.MAX_ID_LENGTH)
        require(claimText.isNotBlank() && claimText.length <= com.projectnuke.fusion.data.EvidenceLimits.MAX_CLAIM_TEXT_LENGTH)
        require(selectedCandidates.size <= EvidenceVerificationLimits.MAX_TOTAL_EVIDENCE_SOURCES)
        require((verificationRequirement == AuditVerificationRequirement.NOT_REQUIRED) == (toolTrace != null))
    }
}

data class VerifierPairExecution(
    val evidenceIdentity: String,
    val result: VerifierBackendResult,
    /** True only when this exact request was passed to the backend. */
    val submitted: Boolean = true,
) {
    init {
        require(evidenceIdentity.isNotBlank() && evidenceIdentity.length <= com.projectnuke.fusion.data.EvidenceLimits.MAX_ID_LENGTH)
    }
}

data class ClaimSemanticVerification(
    val claimId: String,
    val backend: VerifierBackendDescriptor,
    val aggregateStatus: AuditAggregateStatus,
    val pairExecutions: List<VerifierPairExecution>,
    /** Pair identities from the immutable request-side plan, before inference. */
    val expectedEvidenceIdentities: List<String> = emptyList(),
) {
    init {
        require(claimId.isNotBlank() && claimId.length <= com.projectnuke.fusion.data.EvidenceLimits.MAX_ID_LENGTH)
        require(expectedEvidenceIdentities.size <= MAX_AUDIT_PAIR_EXECUTIONS_PER_CLAIM)
        require(expectedEvidenceIdentities.all { it.isNotBlank() && it.length <= com.projectnuke.fusion.data.EvidenceLimits.MAX_ID_LENGTH })
        require(expectedEvidenceIdentities.distinct().size == expectedEvidenceIdentities.size)
        require(pairExecutions.size <= MAX_AUDIT_PAIR_EXECUTIONS_PER_CLAIM)
        require(pairExecutions.map(VerifierPairExecution::evidenceIdentity).distinct().size == pairExecutions.size)
        require(expectedEvidenceIdentities.isEmpty() || pairExecutions.all {
            it.evidenceIdentity in expectedEvidenceIdentities
        })
        require(aggregateStatus == aggregateVerificationResults(pairExecutions.map(VerifierPairExecution::result)))
    }

    val hasVerifiedPair: Boolean
        get() = pairExecutions.any { it.result is VerifierBackendResult.Verified }

    val actualSubmittedPairCount: Int
        get() = pairExecutions.count(VerifierPairExecution::submitted)

    /** A claim completes only when every request-planned pair was submitted and verified. */
    val hasCompleteVerification: Boolean
        get() = backend.availability == VerifierBackendAvailability.AVAILABLE &&
            expectedEvidenceIdentities.isNotEmpty() &&
            pairExecutions.size == expectedEvidenceIdentities.size &&
            pairExecutions.map(VerifierPairExecution::evidenceIdentity).toSet() == expectedEvidenceIdentities.toSet() &&
            pairExecutions.all { it.submitted && it.result is VerifierBackendResult.Verified }
}

internal data class AuditExecutionClaimPlan(
    val claimId: String,
    val claimTextSha256: String,
    val expectedEvidenceIdentities: List<String>,
)

/**
 * Request-side draft and verifier plan captured before inference. A digest is
 * only an equality key; the execution receipt is issued only after the
 * orchestrator validates these expectations against the actual prepared calls.
 */
internal class AnswerAuditExecutionOrigin internal constructor(
    val conversationId: Long,
    val generationRequestId: String,
    val responseVersionTargetAssistantMessageId: Long?,
    val responseVersionGroupId: Long?,
    val canonicalDraftFingerprint: String,
    claimIdentities: List<Pair<String, String>>,
    plannedClaims: List<AuditExecutionClaimPlan>,
) {
    val claimIdentities: List<Pair<String, String>> = immutableList(claimIdentities)
    val plannedClaims: List<AuditExecutionClaimPlan> = immutableList(plannedClaims.map {
        it.copy(expectedEvidenceIdentities = immutableList(it.expectedEvidenceIdentities))
    })

    init {
        require(conversationId > 0L && generationRequestId.isNotBlank())
        require((responseVersionTargetAssistantMessageId == null) == (responseVersionGroupId == null))
        require(canonicalDraftFingerprint.isNotBlank())
        require(this.claimIdentities.map(Pair<String, String>::first).distinct().size == this.claimIdentities.size)
        require(this.plannedClaims.map(AuditExecutionClaimPlan::claimId).distinct().size == this.plannedClaims.size)
    }

    internal fun matchesVerifierPlan(
        inputs: List<VerifierClaimInput>,
        requests: List<List<VerificationPairRequest>>,
    ): Boolean {
        if (inputs.size != plannedClaims.size || requests.size != plannedClaims.size) return false
        return plannedClaims.indices.all { index ->
            val input = inputs[index]
            val plan = plannedClaims[index]
            input.claimId == plan.claimId &&
                sha256(input.claimText) == plan.claimTextSha256 &&
                requests[index].map(VerificationPairRequest::evidenceIdentity) == plan.expectedEvidenceIdentities
        }
    }

    internal fun matchesDraft(answer: String, candidates: List<AtomicClaimCandidate>): Boolean {
        if (EvidenceFingerprint.canonicalAnswerHash(answer) != canonicalDraftFingerprint) return false
        val actual = candidates.asSequence()
            .filter { it.kind == AtomicClaimKind.FACTUAL_CANDIDATE || it.kind == AtomicClaimKind.MIXED_FACT_AND_INFERENCE }
            .map { it.id to sha256(it.text) }
            .toList()
        return actual == claimIdentities
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

class AnswerAuditExecutionSnapshot private constructor(
    val claims: List<ClaimSemanticVerification>,
    internal val executionOrigin: AnswerAuditExecutionOrigin?,
) {
    constructor(claims: List<ClaimSemanticVerification>) : this(immutableList(claims), null)

    init {
        require(claims.size <= EvidenceVerificationLimits.MAX_CLAIMS_TO_CHECK)
        require(claims.map(ClaimSemanticVerification::claimId).distinct().size == claims.size)
    }

    val displayableClaims: List<ClaimSemanticVerification>
        get() = claims.filter(ClaimSemanticVerification::hasVerifiedPair)

    override fun equals(other: Any?): Boolean = other is AnswerAuditExecutionSnapshot && claims == other.claims

    override fun hashCode(): Int = claims.hashCode()

    override fun toString(): String = "AnswerAuditExecutionSnapshot(claims=$claims)"

    internal companion object {
        fun completedExecution(
            claims: List<ClaimSemanticVerification>,
            origin: AnswerAuditExecutionOrigin,
        ) = AnswerAuditExecutionSnapshot(immutableList(claims), origin)
    }
}

/** Runs bounded, sequential evidence pairs under one supplemental audit deadline. */
class AnswerAuditExecutionOrchestrator(
    private val backend: VerifierBackend = UnavailableVerifierBackend,
    private val totalDeadlineMs: Long = AUDIT_BACKEND_TOTAL_DEADLINE_MS,
) {
    init { require(totalDeadlineMs in 1L..60_000L) }

    suspend fun verifyClaim(input: VerifierClaimInput): ClaimSemanticVerification =
        verifyClaims(listOf(input)).claims.single()

    suspend fun verifyClaims(inputs: List<VerifierClaimInput>): AnswerAuditExecutionSnapshot =
        verifyClaimsInternal(inputs, null)

    internal suspend fun verifyClaims(
        inputs: List<VerifierClaimInput>,
        executionOrigin: AnswerAuditExecutionOrigin,
    ): AnswerAuditExecutionSnapshot = verifyClaimsInternal(inputs, executionOrigin)

    private suspend fun verifyClaimsInternal(
        inputs: List<VerifierClaimInput>,
        executionOrigin: AnswerAuditExecutionOrigin?,
    ): AnswerAuditExecutionSnapshot {
        require(inputs.size <= EvidenceVerificationLimits.MAX_CLAIMS_TO_CHECK)
        require(inputs.map(VerifierClaimInput::claimId).distinct().size == inputs.size)

        val descriptor = backend.descriptor
        val prepared = inputs.map { input ->
            val requests = if (input.verificationRequirement == AuditVerificationRequirement.NOT_REQUIRED) {
                emptyList()
            } else {
                VerifierCandidateAdapter.requestsForSelectedCandidates(
                    claimText = input.claimText,
                    selectedCandidates = input.selectedCandidates.toList(),
                ).take(MAX_AUDIT_PAIR_EXECUTIONS_PER_CLAIM)
            }
            input to immutableList(requests)
        }
        val requestPlan = prepared.map { (_, requests) -> requests }
        require(executionOrigin == null || executionOrigin.matchesVerifierPlan(inputs, requestPlan)) {
            "Execution origin does not match the verifier request plan."
        }
        val executions = prepared.map { (_, requests) -> ArrayList<VerifierPairExecution>(requests.size) }

        if (descriptor.availability == VerifierBackendAvailability.UNAVAILABLE) {
            prepared.forEachIndexed { index, (_, requests) ->
                requests.forEach { request ->
                    executions[index] += VerifierPairExecution(
                        request.evidenceIdentity,
                        VerifierBackendResult.Unavailable,
                        submitted = false,
                    )
                }
            }
        } else {
            var inFlight: Pair<Int, String>? = null
            try {
                withTimeout(totalDeadlineMs) {
                    prepared.forEachIndexed { claimIndex, (_, requests) ->
                        requests.forEach { request ->
                            inFlight = claimIndex to request.evidenceIdentity
                            val result = backend.verifySafely(request)
                            executions[claimIndex] += VerifierPairExecution(
                                request.evidenceIdentity,
                                result,
                                submitted = true,
                            )
                            inFlight = null
                        }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                if (!currentCoroutineContext().isActive) throw timeout
                prepared.forEachIndexed { claimIndex, (_, requests) ->
                    val completed = executions[claimIndex].map(VerifierPairExecution::evidenceIdentity).toSet()
                    requests.filterNot { it.evidenceIdentity in completed }.forEach { request ->
                        executions[claimIndex] += VerifierPairExecution(
                            request.evidenceIdentity,
                            VerifierBackendResult.Failure(AuditBackendFailureReason.TIMEOUT),
                            submitted = inFlight == (claimIndex to request.evidenceIdentity),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }

        val completedClaims = prepared.mapIndexed { index, (input, requests) ->
                val pairs = executions[index].toList()
                ClaimSemanticVerification(
                    claimId = input.claimId,
                    backend = descriptor,
                    aggregateStatus = aggregateVerificationResults(pairs.map(VerifierPairExecution::result)),
                    pairExecutions = immutableList(pairs),
                    expectedEvidenceIdentities = immutableList(requests.map(VerificationPairRequest::evidenceIdentity)),
                )
            }
        return if (executionOrigin == null) AnswerAuditExecutionSnapshot(completedClaims)
        else AnswerAuditExecutionSnapshot.completedExecution(completedClaims, executionOrigin)
    }
}

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
