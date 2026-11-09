package com.projectnuke.fusion.ui

import com.projectnuke.fusion.retrieval.LocalRagBounds
import com.projectnuke.fusion.util.AttachmentClassification
import com.projectnuke.fusion.util.AttachmentRecord
import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File

/**
 * Small pure contextual prompt policy for the composer quick-prompt chips.
 *
 * The composer itself is untouched: selecting a prompt still writes ordinary
 * text through the existing appendQuickPrompt path (no auto-send, no
 * generation, no model/search/document mutation). Only the offered labels
 * adapt to trusted local-document context.
 *
 * Source of truth reuses the same effective-document computation as local
 * RAG continuity ([ConversationAttachmentContinuity] plus
 * LocalRagInputPolicy-supported types), so historical trusted documents
 * count even after their composer chip is gone (normal attachment, external
 * document handoff, long-text managed handoff, later turns). Unsupported,
 * unavailable and suspicious documents never trigger document prompts, and
 * no semantic retrieval runs merely to choose labels.
 */
internal object ContextualPromptPolicy {

    const val MAX_PROMPTS = 5

    val GenericPrompts: List<String> = listOf(
        "자세히 설명해 주세요.",
        "핵심만 요약해 주세요.",
        "문제점을 분석해 주세요.",
        "표로 정리해 주세요.",
        "반박해 주세요.",
    )

    val SingleDocumentPrompts: List<String> = listOf(
        "이 문서의 핵심만 요약해 주세요.",
        "중요한 개념을 쉽게 설명해 주세요.",
        "시험에 나올 만한 질문을 만들어 주세요.",
        "중요한 근거를 찾아 정리해 주세요.",
        "이해하기 어려운 부분을 짚어 주세요.",
    )

    val MultiDocumentPrompts: List<String> = listOf(
        "문서별 핵심을 정리해 주세요.",
        "공통점과 차이점을 비교해 주세요.",
        "서로 다른 주장이나 모순을 찾아 주세요.",
        "중요한 근거를 문서별로 정리해 주세요.",
        "이 자료들을 바탕으로 질문을 만들어 주세요.",
    )

    // v1 note: readable PDF documents simply COUNT as effective documents;
    // there is deliberately NO PDF-specific quick-prompt set (Document
    // Intelligence v1 section 13). A text-layer PDF therefore switches the
    // existing single/multi-document sets, and an image-only/unreadable PDF
    // never counts (admission rejects it; see [pdfIsReadable]).

    fun promptsForDocumentCount(effectiveCount: Int): List<String> = when {
        effectiveCount <= 0 -> GenericPrompts
        effectiveCount == 1 -> SingleDocumentPrompts
        else -> MultiDocumentPrompts
    }

    /**
     * Production READ-ONLY PDF readability predicate for quick-prompt
     * counting. [statusLookup] resolves the true/false readability of one
     * managed PDF from CURRENT derived-index status only — never a build,
     * never a parser run, never a model/network call. A PDF counts as an
     * effective readable document ONLY when a VALID current index exists;
     * missing / stale / invalid / image-only / no-text / password-protected
     * states fail closed to false.
     *
     * Ordinary text/md/json/csv files are unaffected (the predicate only
     * gates PDF records), keeping the existing txt behavior byte-for-byte.
     */
    fun pdfIsReadableFromStatus(
        statusLookup: (path: String, mimeType: String?) -> Boolean,
    ): (AttachmentRecord) -> Boolean = { record ->
        val isPdf = com.projectnuke.fusion.retrieval.LocalDocumentKindPolicy.isPdf(
            record.mimeType,
            record.name,
        )
        !isPdf || statusLookup(record.localPath, record.mimeType)
    }

    /**
     * Counts effective trusted supported local text documents (canonical
     * duplicates counted once), mirroring local RAG continuity truth plus an
     * availability gate so missing files never trigger document prompts.
     *
     * [pdfIsReadable] is the PDF readability gate. Production MUST supply
     * [pdfIsReadableFromStatus]-style read-only index truth so a physically
     * trusted but currently-unreadable PDF never counts; the `{ true }`
     * default exists only so host policies can opt in explicitly.
     */
    fun countEffectiveDocuments(
        currentAttachments: List<AttachmentRecord>,
        projectAttachments: List<AttachmentRecord> = emptyList(),
        historicalMessages: List<String>,
        attachmentRoot: File,
        maxDocs: Int = LocalRagBounds.MAX_DOCS_PER_REQUEST,
        isAvailable: (AttachmentRecord) -> Boolean = { record ->
            AttachmentStorageManager.classifyAttachment(attachmentRoot, record.localPath) is AttachmentClassification.Trusted
        },
        pdfIsReadable: (AttachmentRecord) -> Boolean = { true },
    ): Int {
        if (maxDocs <= 0) return 0
        return com.projectnuke.fusion.retrieval.ProjectKnowledgeAttachmentPolicy.resolve(
            currentAttachments = currentAttachments,
            projectFiles = projectAttachments,
            historicalMessages = historicalMessages,
            attachmentRoot = attachmentRoot,
            maxDocs = maxDocs,
        ).count { record ->
            isAvailable(record) && when {
                !com.projectnuke.fusion.retrieval.LocalDocumentKindPolicy.isPdf(record.mimeType, record.name) -> true
                else -> pdfIsReadable(record)
            }
        }.coerceAtMost(maxDocs)
    }
}
