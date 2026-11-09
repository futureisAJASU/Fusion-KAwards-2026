package com.projectnuke.fusion.projects

import java.text.Normalizer

internal const val MAX_PROJECT_CONSISTENCY_SOURCES = 48
internal const val MAX_PROJECT_CONSISTENCY_ISSUES = 12
internal const val MAX_PROJECT_CONSISTENCY_EXCERPT_CHARS = 1_800
/** Identity window for same-body comparison; pairs longer than this cannot claim identity (C09). */
internal const val MAX_CONSISTENCY_IDENTITY_CHARS = 8_000

internal enum class ProjectConsistencySourceKind(val label: String) {
    DECISION("Decision"),
    SAVED_KNOWLEDGE("저장 지식"),
    RESEARCH("Research"),
    PROJECT_SUMMARY("프로젝트 요약"),
    DOCUMENT("문서 발췌"),
}

internal data class ProjectConsistencySource(
    val id: String,
    val kind: ProjectConsistencySourceKind,
    val title: String,
    val text: String,
    val decisionStatus: String? = null,
    val supersedesDecisionId: String? = null,
    val conversationId: Long? = null,
    val savedKnowledgeId: String? = null,
    val projectId: Long? = null,
    val projectFileId: Long? = null,
)

internal enum class ProjectConsistencyIssueKind(val label: String) {
    POTENTIAL_CONFLICT("충돌 가능성"),
    DUPLICATE_CONCLUSION("중복된 결론 후보"),
    SUPERSEDED_REFERENCE("대체된 결정 참조"),
    UNRESOLVED_DIFFERENCE("서로 다른 결론"),
}

internal enum class ProjectConsistencyIssueOrigin { DETERMINISTIC, SEMANTIC_VERIFIER }

internal data class ProjectConsistencyIssue(
    val id: String,
    val kind: ProjectConsistencyIssueKind,
    val statement: String,
    val sourceA: ProjectConsistencySource,
    val sourceB: ProjectConsistencySource,
    val followUp: String,
    val origin: ProjectConsistencyIssueOrigin = ProjectConsistencyIssueOrigin.DETERMINISTIC,
)

/** Conservative text-only review. Similarity and wording never establish truth or factual relations. */
internal object ProjectConsistencyPolicy {
    private val whitespace = Regex("\\s+")
    // Meaning-preserving normalization (C09): sign, decimal separator and
    // comparison operators carry polarity/magnitude meaning, so they survive
    // canonicalization. Everything else non-alphanumeric still folds to space.
    // This makes canonicalization STRICTER (fewer false DUPLICATE claims).
    private val punctuation = Regex("[^\\p{L}\\p{N}\\-+.,<>=≤≥]+")
    private val negativeCue = Regex("미지원|지원하지\\s*(?:않|못)|불가능|불가|비활성|허용되지\\s*않|금지|not\\s+supported|unsupported|disabled", RegexOption.IGNORE_CASE)
    private val positiveCue = Regex("지원|가능|활성|허용|supported|enabled", RegexOption.IGNORE_CASE)

    fun review(sources: List<ProjectConsistencySource>): List<ProjectConsistencyIssue> {
        return reviewCandidates(sources).take(MAX_PROJECT_CONSISTENCY_ISSUES)
    }

    /** Full deterministic set for the supplemental pipeline; still bounded by the 48-source admission. */
    internal fun reviewCandidates(sources: List<ProjectConsistencySource>): List<ProjectConsistencyIssue> {
        // Identity comparison uses fuller text, not the display excerpt (C09):
        // sources diverging after the excerpt window must never read as
        // "same body". Bounded so one giant source cannot blow up pairwise
        // comparison; over-long pairs simply cannot claim identity.
        val admitted = sources.asSequence()
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.id }
            .take(MAX_PROJECT_CONSISTENCY_SOURCES)
            .toList()
        val bounded = admitted
            .map { it.copy(title = it.title.trim().take(180), text = it.text.take(MAX_PROJECT_CONSISTENCY_EXCERPT_CHARS)) }
        val identityText = admitted.associate { it.id to it.text.take(MAX_CONSISTENCY_IDENTITY_CHARS) }
        val identityEligible = admitted
            .filter { it.text.length <= MAX_CONSISTENCY_IDENTITY_CHARS }
            .map { it.id }
            .toSet()
        val issues = mutableListOf<ProjectConsistencyIssue>()
        val supersededPairs = mutableSetOf<Pair<String, String>>()
        val byDecisionId = bounded.filter { it.kind == ProjectConsistencySourceKind.DECISION }.associateBy { it.id.removePrefix("decision:") }

        bounded.asSequence()
            .filter { it.kind == ProjectConsistencySourceKind.DECISION && it.decisionStatus == "ACTIVE" }
            .forEach { current ->
                val previousId = current.supersedesDecisionId ?: return@forEach
                val previous = byDecisionId[previousId]
                    ?.takeIf { it.decisionStatus == "SUPERSEDED" }
                    ?: return@forEach
                supersededPairs += orderedPair(current.id, previous.id)
                issues += ProjectConsistencyIssue(
                    id = issueId(ProjectConsistencyIssueKind.SUPERSEDED_REFERENCE, current, previous),
                    kind = ProjectConsistencyIssueKind.SUPERSEDED_REFERENCE,
                    statement = "현재 Decision이 이전 Decision을 대체한다고 명시되어 있습니다.",
                    sourceA = current,
                    sourceB = previous,
                    followUp = "이전 결론을 인용하는 자료가 남아 있는지 확인해 주세요.",
                )
            }

        for (leftIndex in bounded.indices) {
            for (rightIndex in leftIndex + 1 until bounded.size) {
                val left = bounded[leftIndex]
                val right = bounded[rightIndex]
                if (orderedPair(left.id, right.id) in supersededPairs) continue
                val sameTitle = canonicalTitle(left.title).length >= 4 && canonicalTitle(left.title) == canonicalTitle(right.title)
                // Same-body requires identity over fuller text (C09), never
                // the truncated display excerpt: excerpt-equal but tail-divergent
                // sources must not read as duplicates.
                val sameText = left.id in identityEligible && right.id in identityEligible &&
                    canonicalText(requireNotNull(identityText[left.id])).length >= 24 &&
                    canonicalText(requireNotNull(identityText[left.id])) == canonicalText(requireNotNull(identityText[right.id]))
                val kind: ProjectConsistencyIssueKind
                val statement: String
                val followUp: String
                when {
                    sameText -> {
                        kind = ProjectConsistencyIssueKind.DUPLICATE_CONCLUSION
                        statement = "두 자료의 본문이 같습니다."
                        followUp = "원본과 현재 참조 범위를 확인해 주세요."
                    }
                    sameTitle && haveOpposingExplicitCues(left, right) -> {
                        kind = ProjectConsistencyIssueKind.POTENTIAL_CONFLICT
                        statement = "같은 제목의 자료에서 명시적 표현이 다릅니다. 오류나 거짓으로 판정하지 않습니다."
                        followUp = "근거와 적용 범위가 같은지 추가 확인해 주세요."
                    }
                    sameTitle && left.kind == ProjectConsistencySourceKind.DECISION &&
                        right.kind == ProjectConsistencySourceKind.DECISION -> {
                        kind = ProjectConsistencyIssueKind.UNRESOLVED_DIFFERENCE
                        statement = "같은 제목의 Decision 내용이 다릅니다."
                        followUp = "현재 적용되는 결론과 두 Decision의 관계를 확인해 주세요."
                    }
                    else -> continue
                }
                issues += ProjectConsistencyIssue(
                    id = issueId(kind, left, right), kind = kind, statement = statement,
                    sourceA = left, sourceB = right, followUp = followUp,
                )
            }
        }
        return issues.distinctBy { it.id }
    }

    private fun haveOpposingExplicitCues(left: ProjectConsistencySource, right: ProjectConsistencySource): Boolean {
        fun polarity(source: ProjectConsistencySource): Int {
            val text = "${source.title} ${source.text}"
            val hasNegative = negativeCue.containsMatchIn(text)
            val remaining = negativeCue.replace(text, " ")
            val hasPositive = positiveCue.containsMatchIn(remaining)
            return when {
                hasNegative && !hasPositive -> -1
                hasPositive && !hasNegative -> 1
                else -> 0
            }
        }
        return polarity(left) * polarity(right) == -1
    }

    private fun canonicalTitle(value: String): String = canonical(value)

    private fun canonicalText(value: String): String = canonical(value)

    private fun canonical(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(punctuation, " ")
        .replace(whitespace, " ")
        .trim()

    private fun orderedPair(first: String, second: String): Pair<String, String> =
        if (first <= second) first to second else second to first

    private fun issueId(kind: ProjectConsistencyIssueKind, first: ProjectConsistencySource, second: ProjectConsistencySource): String {
        val pair = orderedPair(first.id, second.id)
        return "${kind.name}:${pair.first}:${pair.second}"
    }
}
