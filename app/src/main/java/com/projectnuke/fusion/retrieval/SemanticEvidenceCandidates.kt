package com.projectnuke.fusion.retrieval

import com.projectnuke.fusion.data.EvidenceLimits
import com.projectnuke.fusion.data.EvidenceSource
import com.projectnuke.fusion.data.EvidenceVerificationLimits

/**
 * S1 Evidence integration: semantic candidate discovery only.
 *
 * The matcher may ADD candidate source ids that lexical planning missed. It
 * must never remove lexical candidates, never change verdict semantics, and
 * never turn `similarity >= threshold` into SUPPORTED. The deterministic
 * [com.projectnuke.fusion.data.ConservativeRuleEvidenceAssessor] remains the
 * sole verdict authority (entity binding, value/unit, modality/negation,
 * temporal, interrogative, chronology checks all still apply).
 *
 * Fallback: matcher null or [SemanticMatchResult.unavailableReason] != null
 * → return [lexicalCandidateIds] unchanged.
 */
object SemanticEvidenceCandidates {

    /**
     * Dedicated semantic addition budget per claim.
     *
     * Justification: lexical planning already contributes up to 5 candidates
     * per claim, so +3 keeps a claim's assessor input at most 8 sources while
     * still recovering paraphrase/cross-lingual misses. Across 6 claims the
     * semantic path adds at most 18 sources, still under the global
     * MAX_TOTAL_EVIDENCE_SOURCES cap, and embedding cost stays bounded (one
     * query vector plus one bounded passage batch per claim, CPU, offline).
     * Top-K recall aid only — no cosine threshold exists anywhere.
     */
    const val MAX_SEMANTIC_CANDIDATES_PER_CLAIM = 3

    /**
     * Builds bounded [SemanticSegment]s from title/snippet only. `queryUsed`
     * is deliberately excluded (provenance must never become evidence), and
     * E5 prefixes are applied at encode time, never stored.
     */
    fun segmentsForSources(sources: List<EvidenceSource>): List<SemanticSegment> =
        sources
            .asSequence()
            .filter { it.id.isNotBlank() }
            .take(EvidenceVerificationLimits.MAX_TOTAL_EVIDENCE_SOURCES)
            .map { source ->
                val text = listOf(
                    source.title.take(EvidenceLimits.MAX_TITLE_LENGTH),
                    source.snippet.take(EvidenceLimits.MAX_SNIPPET_LENGTH),
                ).filter { it.isNotBlank() }
                    .joinToString("\n")
                    .take(EvidenceLimits.MAX_TITLE_LENGTH + EvidenceLimits.MAX_SNIPPET_LENGTH)
                SemanticSegment(source.id, text)
            }
            .filter { it.text.isNotBlank() }
            .take(FusionEmbeddingLimits.MAX_SEGMENTS_PER_RANK)
            .toList()

    /**
     * Unions lexical candidate ids with semantic top-k, bounded and
     * deterministic. Semantic order is preserved after lexical order; no
     * scores, thresholds, or prefixes are persisted.
     */
    fun expand(
        lexicalCandidateIds: List<String>,
        semantic: SemanticMatchResult?,
        maxTotal: Int = EvidenceVerificationLimits.MAX_TOTAL_EVIDENCE_SOURCES,
    ): List<String> {
        val bounded = maxTotal.coerceIn(1, EvidenceVerificationLimits.MAX_TOTAL_EVIDENCE_SOURCES)
        val out = LinkedHashSet<String>()
        for (id in lexicalCandidateIds) {
            if (out.size >= bounded) break
            if (id.isNotBlank()) out += id
        }
        if (semantic?.unavailableReason == null) {
            for (match in semantic?.matches.orEmpty()) {
                if (out.size >= bounded) break
                if (match.segmentId.isNotBlank()) out += match.segmentId
            }
        }
        return out.toList()
    }

    /**
     * Ranks evidence sources for [claimText] without deciding anything.
     * Returns null when [matcher] is null (feature disabled) so callers keep
     * the existing deterministic path bit-for-bit.
     */
    suspend fun rankSources(
        claimText: String,
        sources: List<EvidenceSource>,
        matcher: SemanticCandidateMatcher?,
        limit: Int = FusionEmbeddingLimits.MAX_SEGMENTS_PER_RANK,
    ): SemanticMatchResult? {
        if (matcher == null) return null
        if (claimText.isBlank()) return SemanticMatchResult(emptyList(), EmbeddingFailureReason.INVALID_INPUT)
        val segments = segmentsForSources(sources)
        if (segments.isEmpty()) return SemanticMatchResult(emptyList(), null)
        return try {
            matcher.rank(claimText, segments, limit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SemanticMatchResult(emptyList(), EmbeddingFailureReason.INFERENCE_FAILURE)
        }
    }
}
