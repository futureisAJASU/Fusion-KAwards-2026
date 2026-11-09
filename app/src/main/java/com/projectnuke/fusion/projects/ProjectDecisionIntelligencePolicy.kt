package com.projectnuke.fusion.projects

import com.projectnuke.fusion.data.ProjectDecisionEntity

/** Decision Intelligence v1 — local, bounded candidate review only.
 *
 * Never claims supersede authority. Never exposes similarity scores.
 * Semantic proximity is ranking support, never admission proof.
 */
internal object ProjectDecisionIntelligencePolicy {
    const val MAX_DECISION_CANDIDATES = 24
    const val MAX_DECISION_SUGGESTIONS = 3
    const val MAX_DECISION_EMBED_CHARS = 1500
    const val MAX_DECISION_LINEAGE = 8

    data class Candidate(
        val decision: ProjectDecisionEntity,
        val label: String,
        val reason: String,
        /** Internal ranking support only; never exposed or called confidence. */
        val rankingScore: Float = Float.NaN,
    )

    /** Neutral presentation labels only. Never claim supersede truth. */
    fun labelForReason(reason: String): String = when (reason) {
        "title_near" -> "제목이 비슷한 현재 결정"
        "content_related" -> "내용이 비슷한 현재 결정"
        "same_source" -> "같은 출처에서 만든 현재 결정"
        else -> "관련된 현재 결정"
    }
}
