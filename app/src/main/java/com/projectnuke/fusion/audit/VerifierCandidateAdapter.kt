package com.projectnuke.fusion.audit

import com.projectnuke.fusion.data.EvidenceLimits
import com.projectnuke.fusion.data.EvidenceSource

/**
 * Converts already-selected Evidence passages into bounded backend inputs.
 * Source titles/provenance remain attached to each request through its stable
 * evidence identity, but are not injected into the semantic premise.
 * Ranking and similarity stay with the existing Evidence retrieval path; this
 * adapter carries no score and cannot produce a verification label.
 */
object VerifierCandidateAdapter {
    const val MAX_CANDIDATES_PER_CLAIM = 8

    fun requestsForSelectedCandidates(
        claimText: String,
        selectedCandidates: List<EvidenceSource>,
        claimLanguageHint: String? = null,
        evidenceLanguageHint: String? = null,
    ): List<VerificationPairRequest> {
        val boundedClaim = claimText.take(MAX_PAIR_TEXT_LENGTH)
        if (boundedClaim.isBlank()) return emptyList()
        return selectedCandidates.asSequence()
            .filter { it.id.isNotBlank() && it.id.length <= EvidenceLimits.MAX_ID_LENGTH }
            .distinctBy(EvidenceSource::id)
            .take(MAX_CANDIDATES_PER_CLAIM)
            .mapNotNull { source ->
                val text = source.snippet.take(MAX_PAIR_TEXT_LENGTH)
                if (text.isBlank()) return@mapNotNull null
                VerificationPairRequest(
                    claimText = boundedClaim,
                    evidenceText = text,
                    claimLanguageHint = claimLanguageHint,
                    evidenceLanguageHint = evidenceLanguageHint,
                    evidenceIdentity = source.id,
                )
            }
            .toList()
    }
}
