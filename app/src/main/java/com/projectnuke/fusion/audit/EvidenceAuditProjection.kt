package com.projectnuke.fusion.audit

import com.projectnuke.fusion.data.EvidenceClaim
import com.projectnuke.fusion.data.EvidenceReport
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceValidation
import com.projectnuke.fusion.data.EvidenceVerdict
import com.projectnuke.fusion.retrieval.LocalRagSourceEntry
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadataLimits

/** Projects a valid durable Evidence report into transient Answer Audit types. */
fun projectEvidenceReport(
    report: EvidenceReport,
    explicitLocalLinksByClaimId: Map<String, List<AuditEvidenceReference>> = emptyMap(),
    explicitToolTracesByClaimId: Map<String, AuditToolTrace> = emptyMap(),
    explicitInterpretationByClaimId: Map<String, AuditInterpretationOwnership> = emptyMap(),
    hasAuthoritativeCompleteCoverage: Boolean = false,
    semanticVerificationsByClaimId: Map<String, ClaimSemanticVerification> = emptyMap(),
): AnswerAuditProjection? {
    // Use the canonical validator without changing the durable codec.
    if (com.projectnuke.fusion.data.EvidenceCodec.validate(report) is EvidenceValidation.Invalid) return null
    if (semanticVerificationsByClaimId.size > com.projectnuke.fusion.data.EvidenceVerificationLimits.MAX_CLAIMS_TO_CHECK ||
        semanticVerificationsByClaimId.any { (claimId, verification) -> claimId != verification.claimId }
    ) return null

    val sourcesById = report.sources.associateBy(EvidenceSource::id)
    val claims = report.claims.take(MAX_AUDIT_CLAIMS).map { claim ->
        val webRefs = claim.sourceIds.asSequence()
            .mapNotNull(sourcesById::get)
            .map(::toExternalAuditReference)
        val localRefs = explicitLocalLinksByClaimId[claim.id].orEmpty().asSequence()
            .filter { it.provenance == AuditProvenanceType.SOURCE || it.provenance == AuditProvenanceType.EXTERNAL }
        val refs = (webRefs + localRefs)
            .distinctBy(AuditEvidenceReference::id)
            .take(com.projectnuke.fusion.data.EvidenceLimits.MAX_SOURCE_REFS_PER_CLAIM)
            .toList()
        val toolTrace = explicitToolTracesByClaimId[claim.id]
        val interpretation = explicitInterpretationByClaimId[claim.id]
        val provenanceTypes = buildSet {
            refs.forEach { add(it.provenance) }
            if (toolTrace != null) add(AuditProvenanceType.TOOL_DERIVED)
            if (interpretation != null) add(AuditProvenanceType.INTERPRETATION)
            if (isEmpty()) add(AuditProvenanceType.UNSOURCED)
        }
        val isToolDerived = toolTrace != null
        AnswerAuditClaim(
            id = claim.id,
            text = claim.text,
            provenanceTypes = provenanceTypes,
            verification = if (isToolDerived) {
                AuditVerification(AuditAggregateStatus.NOT_VERIFIED, AuditVerificationSource.DETERMINISTIC_TRACE)
            } else {
                claim.toAuditVerification()
            },
            evidenceRefs = refs,
            verificationRequirement = if (isToolDerived) AuditVerificationRequirement.NOT_REQUIRED else AuditVerificationRequirement.REQUIRED,
            toolTrace = toolTrace,
            interpretationOwnership = interpretation,
            semanticVerification = semanticVerificationsByClaimId[claim.id],
        )
    }
    return AnswerAuditProjection(
        claims = claims,
        coverage = auditCoverage(claims, hasAuthoritativeCompleteCoverage),
    )
}

/** A report's source record is external only when a claim explicitly references its source ID. */
private fun toExternalAuditReference(source: EvidenceSource): AuditEvidenceReference = AuditEvidenceReference(
    id = source.id,
    text = (source.title + "\n" + source.snippet).take(MAX_EVIDENCE_TEXT_LENGTH),
    provenance = AuditProvenanceType.EXTERNAL,
    title = source.title,
    sourceName = source.source,
    externalUrl = source.url,
)

private fun EvidenceClaim.toAuditVerification(): AuditVerification = when (verdict) {
    EvidenceVerdict.SUPPORTED_BY_EVIDENCE ->
        AuditVerification(AuditAggregateStatus.SUPPORTED, AuditVerificationSource.RULE_EVIDENCE)
    EvidenceVerdict.CONFLICTING_EVIDENCE ->
        AuditVerification(AuditAggregateStatus.CONFLICTING_EVIDENCE, AuditVerificationSource.RULE_EVIDENCE)
    EvidenceVerdict.INSUFFICIENT_EVIDENCE ->
        AuditVerification(AuditAggregateStatus.INSUFFICIENT, AuditVerificationSource.RULE_EVIDENCE)
    EvidenceVerdict.NOT_CHECKED ->
        AuditVerification(AuditAggregateStatus.NOT_VERIFIED, null)
}

/** Local references have no URL; identity comes only from existing attachment/chunk/hash metadata. */
fun LocalRagSourceEntry.toAuditEvidenceReference(): AuditEvidenceReference {
    val stableId = "local:${attachmentId.take(96)}:$chunkIndex:${contentHashHex.take(16)}"
        .take(com.projectnuke.fusion.data.EvidenceLimits.MAX_ID_LENGTH)
    return AuditEvidenceReference(
        id = stableId,
        text = excerpt.take(LocalRagSourceMetadataLimits.MAX_EXCERPT_LENGTH),
        provenance = AuditProvenanceType.SOURCE,
        title = fileName.take(LocalRagSourceMetadataLimits.MAX_FILE_NAME_LENGTH),
        attachmentId = attachmentId.take(LocalRagSourceMetadataLimits.MAX_ATTACHMENT_ID_LENGTH),
        chunkIndex = chunkIndex,
        pageNumber = pageNumber,
        contentHashHex = contentHashHex.take(LocalRagSourceMetadataLimits.MAX_HASH_LENGTH),
        rank = rank.coerceAtLeast(0),
    )
}

/** Creates an explicit source link for one known claim; never labels every answer claim from a local envelope. */
fun LocalRagSourceEntry.toExplicitAuditLink(claimId: String): Pair<String, AuditEvidenceReference> {
    require(claimId.isNotBlank() && claimId.length <= com.projectnuke.fusion.data.EvidenceLimits.MAX_ID_LENGTH)
    return claimId to toAuditEvidenceReference()
}
