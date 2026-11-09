package com.projectnuke.fusion.inbox

import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.knowledge.SavedKnowledgePolicy

/**
 * Smart Inbox Triage v1 — candidate generation only.
 *
 * Hard principle: Smart Triage NEVER mutates data. It produces a bounded,
 * reviewable [InboxSmartTriageProposal]. Every durable mutation still requires
 * an explicit user action routed into the EXISTING repositories
 * ([InboxTriageRepository], [com.projectnuke.fusion.knowledge.KnowledgeOrganizationRepository],
 * project/task/review-card paths owned by FusionInboxScreen).
 *
 * Local-only: this file (and the whole triage seam) performs zero web
 * searches, zero outside-model calls, zero cloud escalation, and never arms
 * the egress controller. The chat run mode (automatic or cloud-backed) is not
 * consulted and cannot alter this behavior.
 */
enum class InboxSmartActionKind {
    KEEP_IN_INBOX,
    SAVED_KNOWLEDGE,
    PROJECT,
    PROJECT_TASK,
    REVIEW_CARD,
}

data class InboxSmartProjectCandidate(
    val projectId: Long,
    val projectName: String,
    val reason: String,
)

data class InboxSmartTagSuggestion(
    val value: String,
)

/**
 * Ephemeral proposal bound to an exact Inbox item revision. Never persisted
 * (no Room entity, no migration): if the process dies the proposal is
 * recomputed on demand.
 *
 * Raw semantic scores are deliberately absent: ranking order is the only
 * signal, never a displayed confidence.
 */
data class InboxSmartTriageProposal(
    val itemId: String,
    /** Exact [InboxItemEntity.updatedAt] the proposal was built from. */
    val itemRevision: Long,
    val primaryAction: InboxSmartActionKind,
    val reason: String,
    val projectCandidates: List<InboxSmartProjectCandidate>,
    val tagSuggestions: List<InboxSmartTagSuggestion>,
    /** False when the embedding runtime was unavailable (deterministic-only). */
    val semanticAvailable: Boolean,
)

/** Explicit bounds for every Smart Triage input/output surface. */
object InboxSmartTriageLimits {
    const val MAX_PROJECT_CANDIDATES = 3
    const val MAX_TAG_SUGGESTIONS = 4
    const val MAX_ITEM_TEXT_CHARS = 2_000
    const val MAX_PROJECTS_CONSIDERED = 64
    const val MAX_EXISTING_TAGS_CONSIDERED = 128
    /** Per-project local profile text bound (512-1000 window per spec). */
    const val MAX_PROJECT_PROFILE_CHARS = 800
    /** Titles pulled per project for the local profile. */
    const val MAX_TITLES_PER_PROJECT = 4
}

/**
 * Ranking-only signal for one semantic candidate. The score orders
 * candidates; it is never persisted, never shown, and never alone admits a
 * candidate. Admission is decided by [InboxSemanticAdmissionPolicy] from rank
 * plus deterministic corroboration.
 */
data class SemanticCandidateSignal(
    val score: Float,
    val rank: Int,
    val lexicalCorroboration: Boolean,
    val separationFromNext: Float?,
    /** Index into the caller's original candidate list. */
    val sourceIndex: Int,
)

/**
 * Candidate admission: separates "how to order" (cosine rank) from "whether
 * to show" (deterministic corroboration + separation).
 *
 * Cosine similarity over dense sentence embeddings is a ranking signal, not
 * an eligibility test — unrelated candidates routinely score above zero, so
 * `score > 0` must never admit anything. These rules are admission
 * heuristics, never confidence: safe fallback (no candidate) is preferable
 * to a weak recommendation.
 *
 * A Project move deletes the Inbox source, so Project admission is strict:
 * corroborated, within rank, and separated from the next competitor (or the
 * only candidate). Tagging is additive metadata, so tag admission needs
 * corroboration plus rank only.
 */
object InboxSemanticAdmissionPolicy {
    const val MAX_PROJECT_RANK = 3
    const val MAX_TAG_RANK = 4
    /** Minimum gap to the next competitor for a Project to be shown. */
    const val MIN_PROJECT_SEPARATION = 0.02f
    /** Gap that counts as a strong top Project match (URL outrank). */
    const val STRONG_PROJECT_SEPARATION = 0.05f

    fun projectSignals(scores: List<Float>, corroborated: List<Boolean>): List<SemanticCandidateSignal> =
        signals(scores, corroborated)

    fun tagSignals(scores: List<Float>, corroborated: List<Boolean>): List<SemanticCandidateSignal> =
        signals(scores, corroborated)

    private fun signals(scores: List<Float>, corroborated: List<Boolean>): List<SemanticCandidateSignal> {
        require(scores.size == corroborated.size) { "signal mismatch" }
        val order = scores.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
        return order.mapIndexed { position, index ->
            val next = order.getOrNull(position + 1)?.let { scores[index] - scores[it] }
            SemanticCandidateSignal(
                score = scores[index],
                rank = position + 1,
                lexicalCorroboration = corroborated[index],
                separationFromNext = next?.coerceAtLeast(0f),
                sourceIndex = index,
            )
        }
    }

    /**
     * Positions (into the ranked [signals] order) admitted as Project
     * candidates. A merely top-ranked Project is not automatically
     * reasonable: without corroboration and separation it is rejected and
     * file-backed documents fall back to KEEP_IN_INBOX.
     */
    fun admittedProjectPositions(signals: List<SemanticCandidateSignal>): List<Int> =
        signals.indices.filter { position ->
            val signal = signals[position]
            signal.rank <= MAX_PROJECT_RANK &&
                signal.lexicalCorroboration &&
                (signals.size == 1 || (signal.separationFromNext ?: -1f) >= MIN_PROJECT_SEPARATION)
        }

    /** Strong top match: admitted rank 1, alone or clearly ahead. */
    fun strongTopProject(signals: List<SemanticCandidateSignal>): Boolean {
        if (signals.isEmpty() || 0 !in admittedProjectPositions(signals)) return false
        if (signals.size == 1) return true
        return (signals[0].separationFromNext ?: 0f) >= STRONG_PROJECT_SEPARATION
    }

    /** Positions (into the ranked [signals] order) admitted as tag suggestions. */
    fun admittedTagPositions(signals: List<SemanticCandidateSignal>): List<Int> =
        signals.indices.filter { position ->
            val signal = signals[position]
            signal.rank <= MAX_TAG_RANK && signal.lexicalCorroboration
        }
}

/** Deterministic classification output (no embeddings, no IO). */
data class InboxSmartClassification(
    val primaryAction: InboxSmartActionKind,
    val reason: String,
    /** True when semantic Project candidates may refine this item. */
    val projectSemanticEligible: Boolean,
)

/**
 * Pure rule-based classifier. Deterministic: same item -> same output.
 * No generative model decides anything here.
 */
object InboxSmartTriagePolicy {
    /** Ownership key: exact item identity + revision. */
    fun keyFor(item: InboxItemEntity): String = "${item.id}\n${item.updatedAt}"

    /** Revision fence: any content/identity drift makes a proposal stale. */
    fun isStale(proposal: InboxSmartTriageProposal, current: InboxItemEntity?): Boolean {
        if (current == null) return true
        if (current.id != proposal.itemId) return true
        if (current.updatedAt != proposal.itemRevision) return true
        return false
    }

    /** Exact content-identity check used before a confirmed action runs. */
    fun matchesSnapshot(item: InboxItemEntity, snapshot: InboxItemEntity): Boolean = item == snapshot

    /** Bounded item text shared by the classifier and the semantic query. */
    fun boundItemText(item: InboxItemEntity): String =
        (item.title.trim() + "\n" + item.content.trim()).trim()
            .take(InboxSmartTriageLimits.MAX_ITEM_TEXT_CHARS)

    /**
     * Compact local Project profile from EXISTING durable data only
     * (name, recent saved-knowledge titles, recent task titles, stored
     * instructions prefix). No model-written summary is generated for triage.
     */
    fun buildProjectProfile(
        projectName: String,
        knowledgeTitles: List<String>,
        taskTitles: List<String>,
        instructions: String,
    ): String {
        val parts = ArrayList<String>(4)
        val name = projectName.trim().take(120)
        if (name.isNotEmpty()) parts += name
        val knowledge = knowledgeTitles.asSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }
            .take(InboxSmartTriageLimits.MAX_TITLES_PER_PROJECT)
            .map { it.take(120) }.toList()
        if (knowledge.isNotEmpty()) parts += knowledge.joinToString(" · ")
        val tasks = taskTitles.asSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }
            .take(InboxSmartTriageLimits.MAX_TITLES_PER_PROJECT)
            .map { it.take(120) }.toList()
        if (tasks.isNotEmpty()) parts += tasks.joinToString(" · ")
        val guide = instructions.trim().take(200)
        if (guide.isNotEmpty()) parts += guide
        return parts.joinToString("\n").take(InboxSmartTriageLimits.MAX_PROJECT_PROFILE_CHARS)
    }

    fun classify(item: InboxItemEntity): InboxSmartClassification {
        val hasManagedFile = !item.managedFileId.isNullOrBlank()
        val text = boundItemText(item)
        val lowered = text.lowercase()
        val totalLen = text.length
        val type = runCatching { InboxCaptureType.valueOf(item.captureType) }.getOrNull()

        // Sensitive material: destination policies would reject it, so the
        // only safe proposal is to keep the item untouched.
        if (text.isNotBlank() &&
            SavedKnowledgePolicy.containsSensitiveMaterial(text + "\n" + item.originalUri.orEmpty())
        ) {
            return InboxSmartClassification(
                InboxSmartActionKind.KEEP_IN_INBOX,
                "민감한 정보가 감지되어 정리 제안을 만들지 않았어요.",
                projectSemanticEligible = false,
            )
        }

        // DOCUMENT with a managed file is never converted into text-only
        // knowledge merely because OCR text exists. Project is preferred only
        // when a reasonable candidate exists (checked by the coordinator);
        // otherwise the coordinator downgrades to KEEP_IN_INBOX.
        if (type == InboxCaptureType.DOCUMENT && hasManagedFile) {
            return InboxSmartClassification(
                InboxSmartActionKind.PROJECT,
                "문서 원본이 있어 프로젝트 자료로 정리하기 좋아 보여요.",
                projectSemanticEligible = true,
            )
        }

        if (text.isBlank()) {
            return InboxSmartClassification(
                InboxSmartActionKind.KEEP_IN_INBOX,
                "정리 근거가 약해 Inbox에 두는 게 안전해 보여요.",
                projectSemanticEligible = false,
            )
        }

        // Task-like text: bounded token patterns only — never every
        // imperative sentence.
        if (totalLen <= 1_000 && containsTaskSignal(lowered)) {
            return InboxSmartClassification(
                InboxSmartActionKind.PROJECT_TASK,
                "“측정”, “확인” 같은 실행 항목이 포함되어 있어요.",
                projectSemanticEligible = true,
            )
        }

        // Review-card shape: short concept / definition / Q&A only.
        if (totalLen in 20..600 && containsReviewSignal(lowered)) {
            return InboxSmartClassification(
                InboxSmartActionKind.REVIEW_CARD,
                "짧은 개념 설명이라 복습 카드 후보예요.",
                projectSemanticEligible = true,
            )
        }

        // URL / WEB_CLIP with substantial content defaults to saved knowledge;
        // a strong semantic Project signal may outrank it (coordinator).
        if (type == InboxCaptureType.URL || type == InboxCaptureType.WEB_CLIP) {
            return if (totalLen >= SUBSTANTIAL_TEXT_CHARS) {
                InboxSmartClassification(
                    InboxSmartActionKind.SAVED_KNOWLEDGE,
                    "긴 참고 자료라 저장 지식으로 보관하기 좋아 보여요.",
                    projectSemanticEligible = true,
                )
            } else {
                InboxSmartClassification(
                    InboxSmartActionKind.KEEP_IN_INBOX,
                    "정리 근거가 약해 Inbox에 두는 게 안전해 보여요.",
                    projectSemanticEligible = false,
                )
            }
        }

        // NOTE / TEXT / SHARED_TEXT / OCR / DOCUMENT-without-file.
        if (totalLen >= SUBSTANTIAL_TEXT_CHARS) {
            return InboxSmartClassification(
                InboxSmartActionKind.SAVED_KNOWLEDGE,
                "긴 참고 자료라 저장 지식으로 보관하기 좋아 보여요.",
                projectSemanticEligible = true,
            )
        }
        return InboxSmartClassification(
            InboxSmartActionKind.KEEP_IN_INBOX,
            "정리 근거가 약해 Inbox에 두는 게 안전해 보여요.",
            projectSemanticEligible = type == null || type != InboxCaptureType.DOCUMENT,
        )
    }

    private const val SUBSTANTIAL_TEXT_CHARS = 200

    private val taskTokens = listOf(
        "해야 함", "해야함", "해야해", "해야 해", "해주세요", "해 주세요",
        "todo", "to-do", "할 일", "할일",
        "제출", "마감", "데드라인", "deadline",
        "측정", "수정", "구현", "확인", "검토", "점검",
        "작성", "준비", "연락", "예약", "신청", "처리",
        "테스트", "반영", "해결", "대응",
    )

    private val dateLike = Regex(
        "(\\d{1,2}월\\s*\\d{1,2}일|\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|" +
            "내일|모레|이번\\s*주|다음\\s*주|\\d+시까지|오늘까지|내일까지|까지|d-\\d+)",
    )

    private fun containsTaskSignal(lowered: String): Boolean {
        if (taskTokens.any { lowered.contains(it) }) return true
        // Date/deadline context counts only together with an action verb, so
        // plain dated notes are not misclassified as tasks.
        if (!dateLike.containsMatchIn(lowered)) return false
        return taskTokens.any { lowered.contains(it) } ||
            listOf("하기", "하기 ", "하자", "합시다", "할 것", "할것", "예정").any { lowered.contains(it) }
    }

    private val reviewTokens = listOf(
        "이란", "란 ", "란?", "정의", "의미", "개념",
        "q:", "a:", "q&a", "문제", "정답", "외우", "암기", "용어",
        "차이점", "요약 정리", "?",
    )

    private fun containsReviewSignal(lowered: String): Boolean =
        reviewTokens.any { lowered.contains(it) }
}
