package com.projectnuke.fusion.chat

import com.projectnuke.fusion.retrieval.LocalRagSourceEntry

/**
 * Contextual next actions (활용 추천 v1).
 *
 * Deterministic, pure recommendation policy: given already-known product
 * context (visible answer traits, decoded provenance counts, project
 * membership), selects at most [MAX_SUGGESTIONS] follow-up prompts.
 *
 * This is NOT LLM generation: every candidate is a fixed built-in string.
 * No callbacks, no database access, no parser invocation, no retrieval, no
 * web request, no embedding, no PDF parsing. A tap only seeds ordinary
 * composer text; the user still presses Send.
 *
 * Privacy/truth contract:
 * - the policy accepts scalar context only (counts, flags, visible text
 *   traits); no localPath, contentHash, snippets, queryUsed, provider
 *   secrets or callbacks ever enter;
 * - composer texts never interpolate answer excerpts;
 * - no label claims verification ("검증됨", "사실 확인", "진실",
 *   "신뢰할 수 있음") or document proof ("반드시 있다", "증명한다"):
 *   prompts ask the next turn to inspect, summarize or compare.
 */
internal enum class NextActionKind {
    COMPARE_SOURCES,
    FIND_DISAGREEMENTS,
    SUMMARIZE_EVIDENCE,
    DOCUMENT_SUMMARY,
    DOCUMENT_COMPARE,
    STUDY_QUESTIONS,
    EXPLAIN_DIFFICULT_PARTS,
    PROJECT_DECISIONS,
    PROJECT_NEXT_STEPS,
    PROJECT_SYNTHESIS,
    EXAMPLE,
    COUNTERPOINT,
    SHORT_SUMMARY,
    EXPLAIN_CODE,
    EXPLAIN_STEP_BY_STEP,
    DYNAMIC_FOLLOW_UP,
}

internal data class NextActionSuggestion(
    val id: String,
    val kind: NextActionKind,
    val label: String,
    val target: NextActionTarget,
)

/**
 * What a suggestion tap does. Most suggestions are ordinary composer
 * prompts; the Study entry is a contextual shortcut into the global Study
 * interaction mode (it only enters STUDY mode and appends nothing).
 */
internal sealed interface NextActionTarget {
    data class ComposerPrompt(val text: String) : NextActionTarget
    data object EnterStudyMode : NextActionTarget
}

/** Display text for [NextActionTarget.ComposerPrompt], null for UI actions. */
internal val NextActionSuggestion.promptText: String?
    get() = (target as? NextActionTarget.ComposerPrompt)?.text

internal data class NextActionContext(
    val visibleAnswer: String,
    val webSourceCount: Int,
    val localSourceCount: Int,
    val localDocumentCount: Int,
    val isProjectConversation: Boolean,
    val projectFileCount: Int,
)

/** Provenance family driving the small diversity rule. */
internal enum class Family {
    MULTI_DOC,
    SINGLE_DOC,
    WEB,
    PROJECT,
    SHAPE,
    GENERIC,
}

internal data class FamilyCandidate(
    val family: Family,
    val suggestion: NextActionSuggestion,
)

internal object NextActionSuggestionPolicy {
    const val MAX_SUGGESTIONS = 3
    const val MAX_LABEL_CHARS = 24
    const val MAX_COMPOSER_CHARS = 300
    const val MAX_ID_CHARS = 64

    /** Visible-text length above which the short-summary candidate unlocks. */
    const val LONG_ANSWER_CHARS = 1_200

    fun suggestions(context: NextActionContext): List<NextActionSuggestion> {
        if (context.visibleAnswer.isBlank()) return emptyList()
        val grouped = groupedCandidates(context)
        val primary = deduplicate(grouped.map { it.suggestion })
        val familyOf = grouped
            .distinctBy { it.suggestion.id }
            .associate { it.suggestion.id to it.family }
        val picked = ArrayList<NextActionSuggestion>(MAX_SUGGESTIONS)
        // Up to 2 high-priority contextual suggestions ...
        addAllNew(picked, primary.take(2))
        // ... then one complementary suggestion: prefer a contextual
        // candidate from a different family (no category monopolizes all
        // three slots), then a generic one, then any remainder.
        val pickedFamilies = picked.mapNotNull { familyOf[it.id] }.toSet()
        val remainder = primary.drop(2)
        val complementary = remainder.firstOrNull {
            familyOf[it.id] !in pickedFamilies
        } ?: genericCandidates().firstOrNull { generic ->
            picked.none { it.id == generic.id || it.target == generic.target || it.label == generic.label }
        } ?: remainder.firstOrNull()
        if (complementary != null) addAllNew(picked, listOf(complementary))
        // Fill any still-open slots (e.g. no contextual candidates at all).
        addAllNew(picked, genericCandidates())
        addAllNew(picked, remainder)
        return picked.take(MAX_SUGGESTIONS)
    }

    fun isValidSuggestion(suggestion: NextActionSuggestion): Boolean {
        if (suggestion.id.isBlank() || suggestion.id.length > MAX_ID_CHARS) return false
        if (suggestion.label.isBlank() || suggestion.label.length > MAX_LABEL_CHARS) return false
        val prompt = suggestion.promptText
        // UI-action targets carry no composer text; ordinary prompts must be
        // bounded non-blank text (rejected, never truncated).
        if (prompt != null && (prompt.isBlank() || prompt.length > MAX_COMPOSER_CHARS)) return false
        return true
    }

    /**
     * Distinct local-document identity for the visible answer's provenance.
     * Two managed documents may share a basename, so the existing
     * attachmentId is the preferred identity; entries without one fail safe
     * to the normalized file name. Counts only — attachmentId never enters
     * [NextActionContext].
     */
    internal fun distinctLocalDocumentKeys(
        entries: List<LocalRagSourceEntry>,
    ): Set<String> = buildSet {
        for (entry in entries) {
            val attachmentId = entry.attachmentId.trim()
            if (attachmentId.isNotEmpty()) {
                add("id:$attachmentId")
                continue
            }
            val fileName = entry.fileName.trim()
            if (fileName.isNotEmpty()) add("name:$fileName")
        }
    }

    internal fun countDistinctLocalDocuments(entries: List<LocalRagSourceEntry>): Int =
        distinctLocalDocumentKeys(entries).size

    internal fun deduplicate(
        candidates: List<NextActionSuggestion>,
    ): List<NextActionSuggestion> {
        val seenIds = HashSet<String>()
        val seenTargets = HashSet<NextActionTarget>()
        val seenLabels = HashSet<String>()
        val out = ArrayList<NextActionSuggestion>(candidates.size)
        for (candidate in candidates) {
            if (!isValidSuggestion(candidate)) continue
            if (!seenIds.add(candidate.id)) continue
            if (!seenTargets.add(candidate.target)) continue
            if (!seenLabels.add(candidate.label)) continue
            out.add(candidate)
        }
        return out
    }

    /** Cheap visible-text traits only; no semantic NLP. */
    internal fun hasCodeBlock(visibleAnswer: String): Boolean =
        "```" in visibleAnswer

    internal fun looksTabular(visibleAnswer: String): Boolean {
        var pipeLines = 0
        for (line in visibleAnswer.lines()) {
            if ('|' in line) pipeLines++
            if (pipeLines >= 2) break
        }
        return pipeLines >= 2 && "---" in visibleAnswer
    }

    internal fun isLongAnswer(visibleAnswer: String): Boolean =
        visibleAnswer.length > LONG_ANSWER_CHARS

    private fun addAllNew(
        into: MutableList<NextActionSuggestion>,
        candidates: List<NextActionSuggestion>,
    ) {
        if (into.size >= MAX_SUGGESTIONS) return
        val seenIds = into.map { it.id }.toHashSet()
        val seenTargets = into.map { it.target }.toHashSet()
        val seenLabels = into.map { it.label }.toHashSet()
        for (candidate in candidates) {
            if (into.size >= MAX_SUGGESTIONS) break
            if (!isValidSuggestion(candidate)) continue
            if (!seenIds.add(candidate.id)) continue
            if (!seenTargets.add(candidate.target)) continue
            if (!seenLabels.add(candidate.label)) continue
            into.add(candidate)
        }
    }

    /**
     * Deterministic priority: multiple local documents, single local
     * document, web sources, project context, answer-shape specialization.
     * Each candidate carries its provenance family for the diversity rule.
     */
    internal fun groupedCandidates(
        context: NextActionContext,
    ): List<FamilyCandidate> {
        val out = ArrayList<FamilyCandidate>(16)
        if (context.localDocumentCount >= 2) {
            out.add(FamilyCandidate(Family.MULTI_DOC, DOC_COMPARE_MULTI))
            out.add(FamilyCandidate(Family.MULTI_DOC, DOC_STUDY_QUESTIONS))
            out.add(FamilyCandidate(Family.MULTI_DOC, DOC_FIND_DISAGREEMENTS))
            out.add(FamilyCandidate(Family.MULTI_DOC, DOC_KEY_EVIDENCE))
        } else if (context.localDocumentCount == 1 || context.localSourceCount > 0) {
            out.add(FamilyCandidate(Family.SINGLE_DOC, DOC_SUMMARY))
            out.add(FamilyCandidate(Family.SINGLE_DOC, DOC_STUDY_QUESTIONS))
            out.add(FamilyCandidate(Family.SINGLE_DOC, DOC_EXPLAIN_HARD_PARTS))
        }
        if (context.webSourceCount > 0) {
            out.add(FamilyCandidate(Family.WEB, WEB_COMPARE_SOURCES))
            out.add(FamilyCandidate(Family.WEB, WEB_FIND_DISAGREEMENTS))
            out.add(FamilyCandidate(Family.WEB, WEB_SUMMARIZE_EVIDENCE))
        }
        if (context.isProjectConversation) {
            out.addAll(
                projectCandidates(context.projectFileCount).map {
                    FamilyCandidate(Family.PROJECT, it)
                },
            )
        }
        if (hasCodeBlock(context.visibleAnswer)) {
            out.add(FamilyCandidate(Family.SHAPE, CODE_EXPLAIN_FLOW))
        }
        if (isLongAnswer(context.visibleAnswer)) {
            out.add(FamilyCandidate(Family.SHAPE, ANSWER_SHORT_SUMMARY))
        }
        if (looksTabular(context.visibleAnswer)) {
            out.add(FamilyCandidate(Family.SHAPE, GENERIC_STEP_BY_STEP))
        }
        return out
    }

    /**
     * Project branch: synthesis wording only when shared project files
     * exist; otherwise the file-neutral recap variant. Never imply shared
     * materials for a zero-file project.
     */
    internal fun projectCandidates(projectFileCount: Int): List<NextActionSuggestion> =
        if (projectFileCount > 0) {
            listOf(PROJECT_DECISIONS, PROJECT_NEXT_STEPS, PROJECT_SYNTHESIS)
        } else {
            listOf(PROJECT_DECISIONS, PROJECT_NEXT_STEPS, PROJECT_CONTEXT_RECAP)
        }

    private fun genericCandidates(): List<NextActionSuggestion> =
        deduplicate(listOf(GENERIC_EXAMPLE, GENERIC_COUNTERPOINT, GENERIC_STEP_BY_STEP))

    // --- Built-in candidates (all bounds checked by isValidSuggestion) --------

    internal val DOC_COMPARE_MULTI = NextActionSuggestion(
        id = "doc_compare",
        kind = NextActionKind.DOCUMENT_COMPARE,
        label = "문서별 비교",
        target = NextActionTarget.ComposerPrompt("이 답변에 참고한 문서들의 공통점과 차이점을 문서별로 비교해 주세요."),
    )
    internal val DOC_FIND_DISAGREEMENTS = NextActionSuggestion(
        id = "doc_find_disagreements",
        kind = NextActionKind.DOCUMENT_COMPARE,
        label = "다른 주장 찾기",
        target = NextActionTarget.ComposerPrompt("참고한 문서들 사이에서 서로 다른 주장이나 모순되는 부분을 찾아 정리해 주세요."),
    )
    internal val DOC_KEY_EVIDENCE = NextActionSuggestion(
        id = "doc_key_evidence",
        kind = NextActionKind.DOCUMENT_SUMMARY,
        label = "핵심 근거 정리",
        target = NextActionTarget.ComposerPrompt("이 답변의 핵심 근거를 참고 문서별로 나누어 정리해 주세요."),
    )
    internal val DOC_SUMMARY = NextActionSuggestion(
        id = "doc_summary",
        kind = NextActionKind.DOCUMENT_SUMMARY,
        label = "문서 핵심 근거",
        target = NextActionTarget.ComposerPrompt("이 답변과 참고 문서를 바탕으로 중요한 근거를 다시 정리해 주세요."),
    )
    internal val DOC_STUDY_QUESTIONS = NextActionSuggestion(
        id = "doc_study_questions",
        kind = NextActionKind.STUDY_QUESTIONS,
        label = "학습 모드로 계속",
        target = NextActionTarget.EnterStudyMode,
    )
    internal val DOC_EXPLAIN_HARD_PARTS = NextActionSuggestion(
        id = "doc_explain_hard_parts",
        kind = NextActionKind.EXPLAIN_DIFFICULT_PARTS,
        label = "어려운 부분 설명",
        target = NextActionTarget.ComposerPrompt("이 문서에서 이해하기 어려울 만한 부분을 골라 쉽게 설명해 주세요."),
    )
    internal val WEB_COMPARE_SOURCES = NextActionSuggestion(
        id = "web_compare_sources",
        kind = NextActionKind.COMPARE_SOURCES,
        label = "출처끼리 비교",
        target = NextActionTarget.ComposerPrompt("이 답변에 사용된 출처들의 주장과 차이점을 비교해 주세요."),
    )
    internal val WEB_FIND_DISAGREEMENTS = NextActionSuggestion(
        id = "web_find_disagreements",
        kind = NextActionKind.FIND_DISAGREEMENTS,
        label = "엇갈리는 내용 찾기",
        target = NextActionTarget.ComposerPrompt("사용된 출처들 사이에서 내용이 엇갈리거나 주의해서 봐야 할 부분이 있는지 찾아 주세요."),
    )
    internal val WEB_SUMMARIZE_EVIDENCE = NextActionSuggestion(
        id = "web_summarize_evidence",
        kind = NextActionKind.SUMMARIZE_EVIDENCE,
        label = "핵심 근거 정리",
        target = NextActionTarget.ComposerPrompt("이 답변의 핵심 주장과 그 근거가 된 출처를 연결해서 정리해 주세요."),
    )
    internal val PROJECT_DECISIONS = NextActionSuggestion(
        id = "project_decisions",
        kind = NextActionKind.PROJECT_DECISIONS,
        label = "결정사항 정리",
        target = NextActionTarget.ComposerPrompt("이 프로젝트에서 지금까지 정해진 결정사항을 간결하게 정리해 주세요."),
    )
    internal val PROJECT_NEXT_STEPS = NextActionSuggestion(
        id = "project_next_steps",
        kind = NextActionKind.PROJECT_NEXT_STEPS,
        label = "다음 할 일",
        target = NextActionTarget.ComposerPrompt("이 프로젝트의 현재 맥락을 바탕으로 다음에 할 일을 우선순위대로 정리해 주세요."),
    )
    internal val PROJECT_SYNTHESIS = NextActionSuggestion(
        id = "project_synthesis",
        kind = NextActionKind.PROJECT_SYNTHESIS,
        label = "프로젝트 자료 종합",
        target = NextActionTarget.ComposerPrompt("현재 프로젝트의 자료와 대화 맥락을 함께 고려해 핵심 내용을 종합해 주세요."),
    )
    internal val PROJECT_CONTEXT_RECAP = NextActionSuggestion(
        id = "project_context_recap",
        kind = NextActionKind.PROJECT_SYNTHESIS,
        label = "프로젝트 맥락 정리",
        target = NextActionTarget.ComposerPrompt("이 프로젝트의 대화 맥락을 바탕으로 현재 상황과 다음 방향을 정리해 주세요."),
    )
    internal val CODE_EXPLAIN_FLOW = NextActionSuggestion(
        id = "code_explain_flow",
        kind = NextActionKind.EXPLAIN_CODE,
        label = "코드 흐름 설명",
        target = NextActionTarget.ComposerPrompt("이 코드가 어떤 순서로 동작하는지 단계별로 설명해 주세요."),
    )
    internal val ANSWER_SHORT_SUMMARY = NextActionSuggestion(
        id = "answer_short_summary",
        kind = NextActionKind.SHORT_SUMMARY,
        label = "핵심만 정리",
        target = NextActionTarget.ComposerPrompt("방금 답변의 핵심만 짧게 다시 정리해 주세요."),
    )
    internal val GENERIC_EXAMPLE = NextActionSuggestion(
        id = "generic_example",
        kind = NextActionKind.EXAMPLE,
        label = "예시로 설명",
        target = NextActionTarget.ComposerPrompt("방금 설명한 내용을 구체적인 예시를 들어 다시 설명해 주세요."),
    )
    internal val GENERIC_COUNTERPOINT = NextActionSuggestion(
        id = "generic_counterpoint",
        kind = NextActionKind.COUNTERPOINT,
        label = "반대 관점 보기",
        target = NextActionTarget.ComposerPrompt("방금 답변과 다른 관점이나 반론도 함께 설명해 주세요."),
    )
    internal val GENERIC_STEP_BY_STEP = NextActionSuggestion(
        id = "generic_step_by_step",
        kind = NextActionKind.EXPLAIN_STEP_BY_STEP,
        label = "단계별로 설명",
        target = NextActionTarget.ComposerPrompt("방금 내용을 순서대로 따라갈 수 있게 단계별로 설명해 주세요."),
    )

    internal val allBuiltIn: List<NextActionSuggestion> = listOf(
        DOC_COMPARE_MULTI,
        DOC_FIND_DISAGREEMENTS,
        DOC_KEY_EVIDENCE,
        DOC_SUMMARY,
        DOC_STUDY_QUESTIONS,
        DOC_EXPLAIN_HARD_PARTS,
        WEB_COMPARE_SOURCES,
        WEB_FIND_DISAGREEMENTS,
        WEB_SUMMARIZE_EVIDENCE,
        PROJECT_DECISIONS,
        PROJECT_NEXT_STEPS,
        PROJECT_SYNTHESIS,
        PROJECT_CONTEXT_RECAP,
        CODE_EXPLAIN_FLOW,
        ANSWER_SHORT_SUMMARY,
        GENERIC_EXAMPLE,
        GENERIC_COUNTERPOINT,
        GENERIC_STEP_BY_STEP,
    )
}
