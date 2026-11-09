package com.projectnuke.fusion.knowledge

import java.text.Normalizer

/** Conservative, deterministic admission for local Saved Knowledge cleanup review. */
object KnowledgeHygienePolicy {
    const val MAX_HYGIENE_POOL = 96
    const val MAX_HYGIENE_RESULTS = 3
    const val MAX_EMBED_TEXT_CHARS = 2_000
    /** Lexical overlap is a review heuristic, never a probability or truth score. */
    const val MIN_LEXICAL_OVERLAP = 0.72f

    data class CandidateInput(
        val id: String,
        val title: String,
        val text: String,
        val sourceUrl: String?,
        val durableSourceIdentity: String?,
        val semanticRank: Int = Int.MAX_VALUE,
    )

    data class AdmittedCandidate(
        val id: String,
        val title: String,
        val classification: String,
        val semanticRank: Int,
    )

    fun overlap(left: String, right: String): Float {
        val a = tokens(left)
        val b = tokens(right)
        if (a.isEmpty() || b.isEmpty()) return 0f
        val shared = a.intersect(b).size
        return shared.toFloat() / minOf(a.size, b.size)
    }

    fun admit(
        sourceId: String,
        sourceTitle: String,
        sourceText: String,
        sourceUrl: String?,
        sourceIdentity: String?,
        candidates: List<CandidateInput>,
    ): List<AdmittedCandidate> {
        val sourceCanonicalTitle = canonical(sourceTitle)
        return candidates.asSequence()
            .take(MAX_HYGIENE_POOL)
            .filter { it.id != sourceId }
            .mapNotNull { candidate ->
                val overlap = overlap(sourceTitle + "\n" + sourceText, candidate.title + "\n" + candidate.text)
                val titleOverlap = overlap(sourceTitle, candidate.title)
                val exactTitle = sourceCanonicalTitle.isNotEmpty() && sourceCanonicalTitle == canonical(candidate.title)
                val sharedUrl = canonicalUrl(sourceUrl) != null && canonicalUrl(sourceUrl) == canonicalUrl(candidate.sourceUrl)
                val sameProvenance = sourceIdentity != null && sourceIdentity == candidate.durableSourceIdentity
                val stronglyCorroborated = overlap >= MIN_LEXICAL_OVERLAP || (exactTitle && overlap >= 0.45f) ||
                    (titleOverlap >= 0.8f && overlap >= 0.55f) ||
                    ((sharedUrl || sameProvenance) && overlap >= 0.55f)
                if (!stronglyCorroborated) return@mapNotNull null
                val label = when {
                    exactTitle && overlap >= MIN_LEXICAL_OVERLAP -> "매우 비슷한 내용"
                    (sharedUrl || sameProvenance) -> "같은 출처의 비슷한 저장"
                    else -> "일부 내용이 겹침"
                }
                AdmittedCandidate(candidate.id, candidate.title, label, candidate.semanticRank)
            }
            .sortedWith(compareBy<AdmittedCandidate> { it.semanticRank }.thenBy { it.id })
            .take(MAX_HYGIENE_RESULTS)
            .toList()
    }

    fun tokens(value: String): Set<String> = canonical(value)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .asSequence().filter { it.length >= 2 }.toSet()

    private fun canonical(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase().replace(Regex("[`*_>#\\[\\]()]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun canonicalUrl(value: String?): String? = value?.trim()?.lowercase()
        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
}
