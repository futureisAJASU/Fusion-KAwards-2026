package com.projectnuke.fusion.audit

import com.projectnuke.fusion.data.EvidenceLimits
import kotlinx.coroutines.CancellationException

/** NLI relation labels. These are independent of provenance and Evidence verdicts. */
enum class AuditVerificationLabel { SUPPORTED, CONTRADICTED, INSUFFICIENT }

/** Where an explicit ownership or evidence link says a claim came from. */
enum class AuditProvenanceType { SOURCE, TOOL_DERIVED, INTERPRETATION, EXTERNAL, UNSOURCED }

enum class AuditCoverage { AUDITED, PARTIALLY_AUDITED, NOT_AUDITED }

enum class AuditAggregateStatus {
    SUPPORTED, CONTRADICTED, INSUFFICIENT, CONFLICTING_EVIDENCE, NOT_VERIFIED,
}

enum class AuditVerificationSource { RULE_EVIDENCE, NLI_BACKEND, DETERMINISTIC_TRACE, LOCAL_PROVENANCE }
enum class AuditVerificationRequirement { REQUIRED, NOT_REQUIRED }

enum class AuditBackendFailureReason { EXECUTION_FAILED, INVALID_OUTPUT, TIMEOUT }

enum class VerifierBackendAvailability { AVAILABLE, UNAVAILABLE }

data class VerifierBackendDescriptor(
    val id: String,
    val version: String,
    val supportedLanguages: Set<String>,
    val experimental: Boolean,
    val maxPairLength: Int? = null,
    val availability: VerifierBackendAvailability = VerifierBackendAvailability.AVAILABLE,
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(version.isNotBlank() && version.length <= 128)
        require(supportedLanguages.size <= 16 && supportedLanguages.all { it.isNotBlank() && it.length <= 16 })
        require(maxPairLength == null || maxPairLength in 1..100_000)
    }
}

data class VerificationPairRequest(
    val claimText: String,
    val evidenceText: String,
    val claimLanguageHint: String? = null,
    val evidenceLanguageHint: String? = null,
    val evidenceIdentity: String,
) {
    init {
        require(claimText.isNotBlank() && claimText.length <= MAX_PAIR_TEXT_LENGTH)
        require(evidenceText.isNotBlank() && evidenceText.length <= MAX_PAIR_TEXT_LENGTH)
        require(evidenceIdentity.isNotBlank() && evidenceIdentity.length <= EvidenceLimits.MAX_ID_LENGTH)
        require(claimLanguageHint == null || claimLanguageHint.length <= MAX_LANGUAGE_HINT_LENGTH)
        require(evidenceLanguageHint == null || evidenceLanguageHint.length <= MAX_LANGUAGE_HINT_LENGTH)
    }
}

data class VerifierConfidenceScore(val name: String, val value: Double) {
    init {
        require(name.isNotBlank() && name.length <= MAX_SCORE_NAME_LENGTH)
        require(value.isFinite() && value in 0.0..1.0)
    }
}

sealed interface VerifierBackendResult {
    data class Verified(
        val label: AuditVerificationLabel,
        val scores: List<VerifierConfidenceScore> = emptyList(),
    ) : VerifierBackendResult {
        init { require(scores.size <= MAX_BACKEND_SCORES) }
    }

    data object Unavailable : VerifierBackendResult
    data class Failure(val reason: AuditBackendFailureReason) : VerifierBackendResult
}

/** Decodes a backend's raw label without widening the domain enum or guessing. */
fun verifierBackendResultFromRawLabel(
    rawLabel: String,
    scores: List<VerifierConfidenceScore> = emptyList(),
): VerifierBackendResult {
    val label = AuditVerificationLabel.entries.firstOrNull { it.name == rawLabel }
        ?: return VerifierBackendResult.Failure(AuditBackendFailureReason.INVALID_OUTPUT)
    return try {
        VerifierBackendResult.Verified(label, scores.toList())
    } catch (_: IllegalArgumentException) {
        VerifierBackendResult.Failure(AuditBackendFailureReason.INVALID_OUTPUT)
    }
}

interface VerifierBackend {
    val descriptor: VerifierBackendDescriptor

    suspend fun verify(request: VerificationPairRequest): VerifierBackendResult
}

/** Safe default until a production verifier is explicitly selected. No I/O. */
object UnavailableVerifierBackend : VerifierBackend {
    override val descriptor = VerifierBackendDescriptor(
        id = "unavailable",
        version = "1",
        supportedLanguages = emptySet(),
        experimental = false,
        availability = VerifierBackendAvailability.UNAVAILABLE,
    )

    override suspend fun verify(request: VerificationPairRequest): VerifierBackendResult =
        VerifierBackendResult.Unavailable
}

/** Contains backend exceptions and malformed outputs without converting either into a relation. */
suspend fun VerifierBackend.verifySafely(request: VerificationPairRequest): VerifierBackendResult = try {
    when (val result = verify(request)) {
        is VerifierBackendResult.Verified -> {
            // Reconstruct to re-run validation even for results produced by unusual implementations.
            VerifierBackendResult.Verified(result.label, result.scores.toList())
        }
        VerifierBackendResult.Unavailable -> result
        is VerifierBackendResult.Failure -> result
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: IllegalArgumentException) {
    VerifierBackendResult.Failure(AuditBackendFailureReason.INVALID_OUTPUT)
} catch (_: Exception) {
    VerifierBackendResult.Failure(AuditBackendFailureReason.EXECUTION_FAILED)
}

data class AuditVerification(
    val status: AuditAggregateStatus,
    val source: AuditVerificationSource?,
) {
    init { require(status != AuditAggregateStatus.NOT_VERIFIED || source == null || source != AuditVerificationSource.NLI_BACKEND) }
}

data class AuditEvidenceReference(
    val id: String,
    val text: String,
    val provenance: AuditProvenanceType,
    val title: String? = null,
    val sourceName: String? = null,
    val externalUrl: String? = null,
    val attachmentId: String? = null,
    val chunkIndex: Int? = null,
    val pageNumber: Int? = null,
    val contentHashHex: String? = null,
    val rank: Int? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= EvidenceLimits.MAX_ID_LENGTH)
        require(text.length <= MAX_EVIDENCE_TEXT_LENGTH)
        require(title == null || title.length <= 1_000)
        require(sourceName == null || sourceName.length <= 500)
        require(externalUrl == null || (provenance == AuditProvenanceType.EXTERNAL && externalUrl.length <= 2_000))
        require(attachmentId == null || attachmentId.length <= 128)
        require(chunkIndex == null || chunkIndex >= 0)
        require(pageNumber == null || pageNumber > 0)
        require(contentHashHex == null || contentHashHex.length <= 64)
        require(rank == null || rank >= 0)
    }
}

data class AuditToolTrace(
    val traceId: String,
    val toolKind: String,
    val inputDigest: String?,
    val outputText: String,
) {
    init {
        require(traceId.isNotBlank() && traceId.length <= EvidenceLimits.MAX_ID_LENGTH)
        require(toolKind.isNotBlank() && toolKind.length <= 128)
        require(inputDigest == null || inputDigest.length <= 128)
        require(outputText.length <= MAX_EVIDENCE_TEXT_LENGTH)
    }
}

data class AuditInterpretationOwnership(
    val ownershipId: String,
    val policyId: String,
) {
    init {
        require(ownershipId.isNotBlank() && ownershipId.length <= EvidenceLimits.MAX_ID_LENGTH)
        require(policyId.isNotBlank() && policyId.length <= 128)
    }
}

data class AnswerAuditClaim(
    val id: String,
    val text: String,
    val provenanceTypes: Set<AuditProvenanceType>,
    val verification: AuditVerification,
    val evidenceRefs: List<AuditEvidenceReference> = emptyList(),
    val verificationRequirement: AuditVerificationRequirement = AuditVerificationRequirement.REQUIRED,
    val toolTrace: AuditToolTrace? = null,
    val interpretationOwnership: AuditInterpretationOwnership? = null,
    /** Supplemental transient backend output; the durable/rule projection remains [verification]. */
    val semanticVerification: ClaimSemanticVerification? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= EvidenceLimits.MAX_ID_LENGTH)
        require(text.isNotBlank() && text.length <= EvidenceLimits.MAX_CLAIM_TEXT_LENGTH)
        require(evidenceRefs.size <= EvidenceLimits.MAX_SOURCE_REFS_PER_CLAIM)
        require(provenanceTypes.isNotEmpty() && provenanceTypes.size <= AuditProvenanceType.entries.size)
        require(AuditProvenanceType.UNSOURCED !in provenanceTypes || provenanceTypes.size == 1)
        require(evidenceRefs.map(AuditEvidenceReference::provenance).all { it in provenanceTypes })
        require((AuditProvenanceType.TOOL_DERIVED in provenanceTypes) == (toolTrace != null))
        require((AuditProvenanceType.INTERPRETATION in provenanceTypes) == (interpretationOwnership != null))
        require((verificationRequirement == AuditVerificationRequirement.NOT_REQUIRED) == (toolTrace != null))
        require(semanticVerification == null || semanticVerification.claimId == id)
    }
}

/** NOT_VERIFIED is resolved only when a valid explicit authority makes NLI unnecessary. */
fun AnswerAuditClaim.isAuditResolved(): Boolean =
    verification.status != AuditAggregateStatus.NOT_VERIFIED ||
        (verificationRequirement == AuditVerificationRequirement.NOT_REQUIRED &&
            AuditProvenanceType.TOOL_DERIVED in provenanceTypes &&
            toolTrace != null)

data class AnswerAuditProjection(
    val claims: List<AnswerAuditClaim>,
    val coverage: AuditCoverage,
) {
    init { require(claims.size <= MAX_AUDIT_CLAIMS) }
}

/** Only authoritative answer-wide coverage may produce AUDITED. */
fun auditCoverage(
    claimCount: Int,
    checkedClaimCount: Int,
    hasAuthoritativeCompleteCoverage: Boolean,
): AuditCoverage {
    require(claimCount >= 0 && checkedClaimCount in 0..claimCount)
    if (claimCount == 0) return AuditCoverage.NOT_AUDITED
    if (hasAuthoritativeCompleteCoverage && checkedClaimCount == claimCount) return AuditCoverage.AUDITED
    return if (checkedClaimCount > 0) AuditCoverage.PARTIALLY_AUDITED else AuditCoverage.NOT_AUDITED
}

fun auditCoverage(
    claims: List<AnswerAuditClaim>,
    hasAuthoritativeCompleteCoverage: Boolean,
): AuditCoverage {
    if (claims.isEmpty()) return AuditCoverage.NOT_AUDITED
    return auditCoverage(
        claimCount = claims.size,
        checkedClaimCount = claims.count(AnswerAuditClaim::isAuditResolved),
        hasAuthoritativeCompleteCoverage = hasAuthoritativeCompleteCoverage,
    )
}

/** Aggregates only accepted categorical pair results; it does not inspect scores. */
fun aggregateVerificationResults(results: List<VerifierBackendResult>): AuditAggregateStatus {
    val labels = results.asSequence()
        .filterIsInstance<VerifierBackendResult.Verified>()
        .take(MAX_AGGREGATE_RESULTS)
        .map { it.label }
        .toSet()
    val supports = AuditVerificationLabel.SUPPORTED in labels
    val contradicts = AuditVerificationLabel.CONTRADICTED in labels
    return when {
        supports && contradicts -> AuditAggregateStatus.CONFLICTING_EVIDENCE
        supports -> AuditAggregateStatus.SUPPORTED
        contradicts -> AuditAggregateStatus.CONTRADICTED
        AuditVerificationLabel.INSUFFICIENT in labels -> AuditAggregateStatus.INSUFFICIENT
        else -> AuditAggregateStatus.NOT_VERIFIED
    }
}

const val MAX_PAIR_TEXT_LENGTH = 10_000
const val MAX_LANGUAGE_HINT_LENGTH = 16
const val MAX_SCORE_NAME_LENGTH = 32
const val MAX_BACKEND_SCORES = 8
const val MAX_EVIDENCE_TEXT_LENGTH = 4_000
const val MAX_AUDIT_CLAIMS = 200
const val MAX_AGGREGATE_RESULTS = 24
