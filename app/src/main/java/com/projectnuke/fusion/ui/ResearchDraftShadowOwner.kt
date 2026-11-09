package com.projectnuke.fusion.ui

import com.projectnuke.fusion.audit.AnswerAuditExecutionOrchestrator
import com.projectnuke.fusion.audit.AnswerAuditExecutionSnapshot
import com.projectnuke.fusion.audit.AnswerAuditExecutionOrigin
import com.projectnuke.fusion.audit.AuditBackendFailureReason
import com.projectnuke.fusion.audit.AuditExecutionClaimPlan
import com.projectnuke.fusion.audit.AtomicClaimCandidate
import com.projectnuke.fusion.audit.AtomicClaimCoverageAnalyzer
import com.projectnuke.fusion.audit.ClaimSemanticVerification
import com.projectnuke.fusion.audit.ResearchDraftClaimFinding
import com.projectnuke.fusion.audit.ResearchDraftClaimFindingStatus
import com.projectnuke.fusion.audit.ResearchDraftEvidenceExcerpt
import com.projectnuke.fusion.audit.ResearchVerificationDecision
import com.projectnuke.fusion.audit.ResearchVerificationDecisionPolicy
import com.projectnuke.fusion.audit.VerifierBackend
import com.projectnuke.fusion.audit.VerifierBackendAvailability
import com.projectnuke.fusion.audit.VerifierBackendResult
import com.projectnuke.fusion.audit.VerifierClaimInput
import com.projectnuke.fusion.audit.VerifierCandidateAdapter
import com.projectnuke.fusion.audit.UnavailableVerifierBackend
import com.projectnuke.fusion.chat.ChatInteractionMode
import com.projectnuke.fusion.chat.GenerationRequestSnapshot
import com.projectnuke.fusion.chat.ResearchRunPolicy
import com.projectnuke.fusion.data.EvidenceFingerprint
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.retrieval.LocalRagBounds
import com.projectnuke.fusion.retrieval.LocalRagSelection
import com.projectnuke.fusion.search.WebSearchSource
import com.projectnuke.fusion.util.visibleMessageContentPreservingWhitespace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import java.util.Collections

/** Explicit internal activation seam. Production construction stays disabled. */
internal fun interface ResearchDraftShadowActivationPolicy {
    fun enabledFor(request: GenerationRequestSnapshot): Boolean

    companion object {
        val DISABLED = ResearchDraftShadowActivationPolicy { false }
    }
}

/** Request-scoped observation of what the verifier actually returned. */
internal enum class ResearchDraftVerifierStatus {
    NOT_EXECUTED,
    AVAILABLE,
    PARTIAL,
    TIMEOUT,
    FAILED,
    UNAVAILABLE,
}

/** Whether the exact draft had a non-empty request-side evidence pair plan. */
internal enum class ResearchDraftEvidenceStatus { MISSING, PARTIAL, PRESENT }

internal enum class ResearchDraftStalePhase {
    BEFORE_ANALYSIS,
    AFTER_INFERENCE,
    AFTER_COVERAGE_ANALYSIS,
}

internal enum class ResearchDraftFailureReason {
    DRAFT_FINGERPRINT_CHANGED,
    EXECUTION_PLAN_MISMATCH,
    ANALYSIS_FAILED,
}

/** Typed result of one bounded shadow inspection. */
internal sealed interface ResearchDraftShadowOutcome {
    data object Disabled : ResearchDraftShadowOutcome
    data object NotResearchRequest : ResearchDraftShadowOutcome
    data object NotOwned : ResearchDraftShadowOutcome
    data class Completed(val diagnostic: ResearchDraftShadowDiagnostic) : ResearchDraftShadowOutcome
    data class Stale(val phase: ResearchDraftStalePhase) : ResearchDraftShadowOutcome
    data class Failed(val reason: ResearchDraftFailureReason) : ResearchDraftShadowOutcome
}

/** Exact response slot being regenerated; null for a first response. */
internal data class ResearchDraftResponseVersionTarget(
    val targetAssistantMessageId: Long,
    val groupId: Long,
) {
    init {
        require(targetAssistantMessageId > 0L)
        require(groupId != 0L)
    }
}

internal data class ResearchDraftClaimIdentity(
    val claimId: String,
    val claimTextSha256: String,
    val sourceSegmentOrdinal: Int,
)

internal data class ResearchDraftEvidencePairIdentity(
    val claimId: String,
    /** Content-derived identity, not a reusable source label such as E1. */
    val evidenceIdentity: String,
)

/**
 * Request-scoped identity emitted only by [ResearchDraftShadowOwner]. It binds
 * the exact visible persisted draft, claim text, evidence content identities,
 * existing (conversationId, requestId) owner, and response-version target.
 * The hash is an association key; it is not proof that inference ran. The
 * owner builds the verifier inputs from the same frozen plan before returning
 * the diagnostic.
 */
internal class ResearchDraftVerificationIdentity internal constructor(
    val conversationId: Long,
    val generationRequestId: String,
    val responseVersionTarget: ResearchDraftResponseVersionTarget?,
    val canonicalDraftFingerprint: String,
    claimIdentities: List<ResearchDraftClaimIdentity>,
    evidencePairIdentities: List<ResearchDraftEvidencePairIdentity>,
    internal val executionOrigin: AnswerAuditExecutionOrigin,
) {
    val claimIdentities: List<ResearchDraftClaimIdentity> = immutableList(claimIdentities)
    val evidencePairIdentities: List<ResearchDraftEvidencePairIdentity> = immutableList(evidencePairIdentities)

    override fun equals(other: Any?): Boolean = other is ResearchDraftVerificationIdentity &&
        conversationId == other.conversationId &&
        generationRequestId == other.generationRequestId &&
        responseVersionTarget == other.responseVersionTarget &&
        canonicalDraftFingerprint == other.canonicalDraftFingerprint &&
        claimIdentities == other.claimIdentities &&
        evidencePairIdentities == other.evidencePairIdentities

    override fun hashCode(): Int = listOf(
        conversationId,
        generationRequestId,
        responseVersionTarget,
        canonicalDraftFingerprint,
        claimIdentities,
        evidencePairIdentities,
    ).hashCode()
}

/** Ephemeral, non-persisted diagnostic summary; it contains no source excerpts. */
internal data class ResearchDraftShadowDiagnostic(
    val identity: ResearchDraftVerificationIdentity,
    val coverageState: com.projectnuke.fusion.audit.AnswerCoverageState,
    val authoritativeCoverage: Boolean,
    /** Always false while the claim splitter is heuristic; never a whole-answer certification. */
    val wholeAnswerVerificationClaimed: Boolean,
    val verifierStatus: ResearchDraftVerifierStatus,
    val evidenceStatus: ResearchDraftEvidenceStatus,
    val selectedClaimIds: Set<String>,
    val submittedClaimIds: Set<String>,
    val checkedClaimIds: Set<String>,
    val expectedPairCount: Int,
    val submittedPairCount: Int,
    val claimAggregateStatuses: Map<String, com.projectnuke.fusion.audit.AuditAggregateStatus>,
    val decision: ResearchVerificationDecision,
    /**
     * In-memory, claim-bound findings. Evidence excerpts stay here only for a
     * bounded correction prompt and are redacted from [toString] so diagnostics
     * can never leak private source text.
     */
    val claimFindings: List<com.projectnuke.fusion.audit.ResearchDraftClaimFinding> = emptyList(),
)

/**
 * Bounded pre-persistence Research shadow analysis. It neither searches nor
 * persists. Every result is request-owned and discarded when ownership ends.
 */
internal class ResearchDraftShadowOwner(
    private val activationPolicy: ResearchDraftShadowActivationPolicy = ResearchDraftShadowActivationPolicy.DISABLED,
    backend: VerifierBackend = UnavailableVerifierBackend,
    private val execution: AnswerAuditExecutionOrchestrator = AnswerAuditExecutionOrchestrator(backend),
) {
    suspend fun inspectBeforePersistence(
        request: GenerationRequestSnapshot,
        exactStoredAssistantContent: String,
        webSources: List<WebSearchSource> = emptyList(),
        localSelections: List<LocalRagSelection> = emptyList(),
        responseVersionTarget: ResearchDraftResponseVersionTarget? = null,
        expectedCanonicalDraftFingerprint: String? = null,
        ownsDraft: () -> Boolean,
    ): ResearchDraftShadowOutcome {
        return try {
            if (request.interactionMode != ChatInteractionMode.RESEARCH) return ResearchDraftShadowOutcome.NotResearchRequest
            if (!activationPolicy.enabledFor(request)) return ResearchDraftShadowOutcome.Disabled
            if (!ownsDraft()) return ResearchDraftShadowOutcome.NotOwned
            currentCoroutineContext().ensureActive()
            val canonicalDraft = visibleMessageContentPreservingWhitespace(exactStoredAssistantContent)
            val canonicalDraftFingerprint = EvidenceFingerprint.canonicalAnswerHash(canonicalDraft)
            if (expectedCanonicalDraftFingerprint != null &&
                expectedCanonicalDraftFingerprint != canonicalDraftFingerprint
            ) {
                return ResearchDraftShadowOutcome.Failed(ResearchDraftFailureReason.DRAFT_FINGERPRINT_CHANGED)
            }
            val inventory = AtomicClaimCoverageAnalyzer.analyze(
                answer = canonicalDraft,
                maxClaimsToCheck = ResearchRunPolicy.MAX_AUDIT_CLAIMS,
            )
            val candidates = immutableList(
                webSources.asSequence()
                    .take(ResearchRunPolicy.MAX_WEB_SOURCES)
                    .mapIndexed(::toWebEvidenceSource)
                    .toList() + localSelections.asSequence()
                    .take(LocalRagBounds.FINAL_TOP_K)
                    .mapIndexed(::toLocalEvidenceSource)
                    .toList(),
            )
            val boundSources = immutableList(candidates.map(::bindSourceContent))
            val selectedClaims = inventory.detectedFactualClaims.filter {
                it.id in inventory.selectedForAuditClaimIds
            }
            val plan = selectedClaims.map { candidate ->
                val pairPlan = VerifierCandidateAdapter.requestsForSelectedCandidates(
                    claimText = candidate.text,
                    selectedCandidates = boundSources,
                )
                PlannedClaim(
                    candidate = candidate,
                    verifierInput = VerifierClaimInput(
                        claimId = candidate.id,
                        claimText = candidate.text,
                        selectedCandidates = boundSources,
                    ),
                    expectedEvidenceIdentities = pairPlan.map { it.evidenceIdentity },
                )
            }
            val origin = AnswerAuditExecutionOrigin(
                conversationId = request.conversationId,
                generationRequestId = request.requestId,
                responseVersionTargetAssistantMessageId = responseVersionTarget?.targetAssistantMessageId,
                responseVersionGroupId = responseVersionTarget?.groupId,
                canonicalDraftFingerprint = canonicalDraftFingerprint,
                claimIdentities = inventory.detectedFactualClaims.map { candidate ->
                    candidate.id to sha256(candidate.text)
                },
                plannedClaims = plan.map { entry ->
                    AuditExecutionClaimPlan(
                        claimId = entry.candidate.id,
                        claimTextSha256 = sha256(entry.candidate.text),
                        expectedEvidenceIdentities = entry.expectedEvidenceIdentities,
                    )
                },
            )
            val identity = ResearchDraftVerificationIdentity(
                conversationId = request.conversationId,
                generationRequestId = request.requestId,
                responseVersionTarget = responseVersionTarget,
                canonicalDraftFingerprint = canonicalDraftFingerprint,
                claimIdentities = inventory.detectedFactualClaims.map { candidate ->
                    ResearchDraftClaimIdentity(
                        claimId = candidate.id,
                        claimTextSha256 = sha256(candidate.text),
                        sourceSegmentOrdinal = candidate.sourceSegmentOrdinal,
                    )
                },
                evidencePairIdentities = plan.flatMap { entry ->
                    entry.expectedEvidenceIdentities.map { evidenceId ->
                        ResearchDraftEvidencePairIdentity(entry.candidate.id, evidenceId)
                    }
                },
                executionOrigin = origin,
            )

            currentCoroutineContext().ensureActive()
            if (!ownsDraft()) return ResearchDraftShadowOutcome.Stale(ResearchDraftStalePhase.BEFORE_ANALYSIS)
            val audit = execution.verifyClaims(
                inputs = plan.map(PlannedClaim::verifierInput),
                executionOrigin = origin,
            )
            currentCoroutineContext().ensureActive()
            if (!ownsDraft()) return ResearchDraftShadowOutcome.Stale(ResearchDraftStalePhase.AFTER_INFERENCE)

            // The output must correspond to the exact request-side pair plan.
            if (!matchesExecutionPlan(plan, audit)) {
                return ResearchDraftShadowOutcome.Failed(ResearchDraftFailureReason.EXECUTION_PLAN_MISMATCH)
            }
            val report = AtomicClaimCoverageAnalyzer.analyze(
                answer = canonicalDraft,
                maxClaimsToCheck = ResearchRunPolicy.MAX_AUDIT_CLAIMS,
                audit = audit,
                expectedExecutionOrigin = origin,
            )
            val decision = ResearchVerificationDecisionPolicy.decide(
                report.toDecisionInput(answer = canonicalDraft, audit = audit, expectedExecutionOrigin = origin),
            )
            val findings = buildClaimFindings(
                audit = audit,
                candidatesById = inventory.detectedFactualClaims.associateBy { it.id },
                sourcesById = boundSources.associateBy(EvidenceSource::id),
            )
            currentCoroutineContext().ensureActive()
            if (!ownsDraft()) {
                return ResearchDraftShadowOutcome.Stale(ResearchDraftStalePhase.AFTER_COVERAGE_ANALYSIS)
            }
            ResearchDraftShadowOutcome.Completed(
                ResearchDraftShadowDiagnostic(
                    identity = identity,
                    coverageState = report.state,
                    authoritativeCoverage = report.authoritativeCoverage,
                    wholeAnswerVerificationClaimed = report.authoritativeCoverage && decision.isFullyEvidenceSupported,
                    verifierStatus = verifierStatus(audit),
                    evidenceStatus = evidenceStatus(boundSources, plan),
                    selectedClaimIds = immutableSet(inventory.selectedForAuditClaimIds),
                    submittedClaimIds = immutableSet(report.submittedClaimIds),
                    checkedClaimIds = immutableSet(report.checkedClaimIds),
                    expectedPairCount = plan.sumOf { it.expectedEvidenceIdentities.size },
                    submittedPairCount = audit.claims.sumOf(ClaimSemanticVerification::actualSubmittedPairCount),
                    claimAggregateStatuses = immutableMap(
                        audit.claims.associate { it.claimId to it.aggregateStatus },
                    ),
                    decision = decision,
                    claimFindings = findings,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Shadow failures are not verification results and do not affect the answer.
            ResearchDraftShadowOutcome.Failed(ResearchDraftFailureReason.ANALYSIS_FAILED)
        }
    }

    private fun buildClaimFindings(
        audit: AnswerAuditExecutionSnapshot,
        candidatesById: Map<String, AtomicClaimCandidate>,
        sourcesById: Map<String, EvidenceSource>,
    ): List<ResearchDraftClaimFinding> = immutableList(
        audit.claims.mapNotNull { claim ->
            val candidate = candidatesById[claim.claimId] ?: return@mapNotNull null
            val status = when (claim.aggregateStatus) {
                com.projectnuke.fusion.audit.AuditAggregateStatus.SUPPORTED ->
                    ResearchDraftClaimFindingStatus.SUPPORTED
                com.projectnuke.fusion.audit.AuditAggregateStatus.CONTRADICTED ->
                    ResearchDraftClaimFindingStatus.CONTRADICTED
                com.projectnuke.fusion.audit.AuditAggregateStatus.CONFLICTING_EVIDENCE ->
                    ResearchDraftClaimFindingStatus.CONFLICTING
                com.projectnuke.fusion.audit.AuditAggregateStatus.INSUFFICIENT ->
                    ResearchDraftClaimFindingStatus.INSUFFICIENT
                com.projectnuke.fusion.audit.AuditAggregateStatus.NOT_VERIFIED ->
                    ResearchDraftClaimFindingStatus.UNVERIFIABLE
            }
            val wanted = when (status) {
                ResearchDraftClaimFindingStatus.SUPPORTED -> setOf(
                    com.projectnuke.fusion.audit.AuditVerificationLabel.SUPPORTED,
                )
                ResearchDraftClaimFindingStatus.CONTRADICTED -> setOf(
                    com.projectnuke.fusion.audit.AuditVerificationLabel.CONTRADICTED,
                )
                ResearchDraftClaimFindingStatus.CONFLICTING -> setOf(
                    com.projectnuke.fusion.audit.AuditVerificationLabel.SUPPORTED,
                    com.projectnuke.fusion.audit.AuditVerificationLabel.CONTRADICTED,
                )
                ResearchDraftClaimFindingStatus.INSUFFICIENT -> setOf(
                    com.projectnuke.fusion.audit.AuditVerificationLabel.INSUFFICIENT,
                )
                ResearchDraftClaimFindingStatus.UNVERIFIABLE -> emptySet()
            }
            val evidence = claim.pairExecutions.asSequence()
                .filter { it.submitted }
                .mapNotNull { pair ->
                    val verified = pair.result as? VerifierBackendResult.Verified ?: return@mapNotNull null
                    if (verified.label !in wanted) return@mapNotNull null
                    val source = sourcesById[pair.evidenceIdentity] ?: return@mapNotNull null
                    ResearchDraftEvidenceExcerpt(
                        evidenceIdentity = pair.evidenceIdentity,
                        title = source.title.take(300),
                        url = source.url.take(2_000),
                        source = source.source.take(300),
                        providerDisplayName = source.providerDisplayName.take(200),
                        excerpt = (source.title + "\n" + source.snippet).take(2_400),
                    )
                }
                .distinctBy(ResearchDraftEvidenceExcerpt::evidenceIdentity)
                .toList()
            ResearchDraftClaimFinding(
                claimId = claim.claimId,
                claimText = candidate.text,
                claimTextSha256 = sha256(candidate.text),
                status = status,
                evidence = immutableList(evidence),
            )
        },
    )

    private fun evidenceStatus(
        boundSources: List<EvidenceSource>,
        plan: List<PlannedClaim>,
    ): ResearchDraftEvidenceStatus = when {        boundSources.isEmpty() || plan.all { it.expectedEvidenceIdentities.isEmpty() } ->
            ResearchDraftEvidenceStatus.MISSING
        plan.any { it.expectedEvidenceIdentities.isEmpty() } -> ResearchDraftEvidenceStatus.PARTIAL
        else -> ResearchDraftEvidenceStatus.PRESENT
    }

    private fun verifierStatus(audit: AnswerAuditExecutionSnapshot): ResearchDraftVerifierStatus {
        val claims = audit.claims
        if (claims.isEmpty()) return ResearchDraftVerifierStatus.NOT_EXECUTED
        if (claims.all { it.backend.availability != VerifierBackendAvailability.AVAILABLE }) {
            return ResearchDraftVerifierStatus.UNAVAILABLE
        }
        val pairs = claims.flatMap(ClaimSemanticVerification::pairExecutions)
        if (pairs.isEmpty()) return ResearchDraftVerifierStatus.NOT_EXECUTED
        if (pairs.all { it.result is VerifierBackendResult.Unavailable }) {
            return ResearchDraftVerifierStatus.UNAVAILABLE
        }
        val anyVerified = pairs.any { it.result is VerifierBackendResult.Verified }
        if (pairs.any { (it.result as? VerifierBackendResult.Failure)?.reason == AuditBackendFailureReason.TIMEOUT }) {
            return ResearchDraftVerifierStatus.TIMEOUT
        }
        if (pairs.any { it.result is VerifierBackendResult.Failure }) {
            return if (anyVerified) ResearchDraftVerifierStatus.PARTIAL else ResearchDraftVerifierStatus.FAILED
        }
        return if (claims.all(ClaimSemanticVerification::hasCompleteVerification)) {
            ResearchDraftVerifierStatus.AVAILABLE
        } else {
            ResearchDraftVerifierStatus.PARTIAL
        }
    }

    private fun matchesExecutionPlan(
        plan: List<PlannedClaim>,
        audit: AnswerAuditExecutionSnapshot,
    ): Boolean {
        if (audit.claims.size != plan.size) return false
        return plan.all { expected ->
            val result = audit.claims.singleOrNull { it.claimId == expected.candidate.id } ?: return@all false
            result.expectedEvidenceIdentities == expected.expectedEvidenceIdentities
        }
    }

    private data class PlannedClaim(
        val candidate: AtomicClaimCandidate,
        val verifierInput: VerifierClaimInput,
        val expectedEvidenceIdentities: List<String>,
    )

    private fun bindSourceContent(source: EvidenceSource): EvidenceSource {
        val fingerprint = sha256(
            listOf(
                source.id,
                source.title,
                source.source,
                source.url,
                source.snippet,
                source.publishedAt.orEmpty(),
                source.providerType,
                source.providerDisplayName,
                source.queryUsed,
            ).joinToString(separator = "") { field(it) },
        )
        // A stable label is made content-derived so reused source IDs with new
        // backing content cannot alias in the verifier pair plan.
        return source.copy(id = "draft-$fingerprint")
    }

    private fun toWebEvidenceSource(index: Int, source: WebSearchSource): EvidenceSource = EvidenceSource(
        id = "web-$index-${sha256(listOf(
            source.title,
            source.source.orEmpty(),
            source.url.orEmpty(),
            source.snippet.orEmpty(),
            source.publishedAt.orEmpty(),
            source.providerType.name,
            source.providerDisplayName,
            source.searchCategory.orEmpty(),
            source.queryUsed,
            source.rank.toString(),
            source.score?.toString().orEmpty(),
            source.qualityHints.joinToString("\u0000"),
        ).joinToString(separator = "", transform = ::field))}",
        title = source.title,
        source = source.source.orEmpty(),
        url = source.url.orEmpty(),
        snippet = source.snippet.orEmpty(),
        publishedAt = source.publishedAt,
        providerType = source.providerType.name,
        providerDisplayName = source.providerDisplayName,
        queryUsed = source.queryUsed,
    )

    private fun toLocalEvidenceSource(index: Int, selection: LocalRagSelection): EvidenceSource = EvidenceSource(
        id = "local-$index-${sha256(listOf(
            selection.chunk.docId,
            selection.source.docId,
            selection.source.attachmentId,
            selection.chunk.chunkIndex.toString(),
            selection.chunk.startOffset.toString(),
            selection.chunk.endOffset.toString(),
            selection.source.contentHashHex,
            selection.source.excerpt,
            selection.source.pageNumber?.toString().orEmpty(),
            selection.source.ocrDerived.toString(),
            selection.rank.toString(),
            selection.lexicalScore.toString(),
            selection.semanticScore?.toString().orEmpty(),
        ).joinToString(separator = "", transform = ::field))}",
        title = selection.source.displayName,
        source = "local document",
        url = "",
        snippet = selection.source.excerpt.take(1_200),
        publishedAt = null,
        providerType = if (selection.source.ocrDerived) "LOCAL_OCR_DOCUMENT" else "LOCAL_DOCUMENT",
        providerDisplayName = "local document",
        queryUsed = "",
    )

    private fun field(value: String): String = "${value.length}:$value"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Set<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
