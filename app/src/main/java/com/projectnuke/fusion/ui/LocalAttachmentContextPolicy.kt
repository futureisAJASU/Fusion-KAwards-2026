package com.projectnuke.fusion.ui

import com.projectnuke.fusion.retrieval.LocalRagInputPolicy
import com.projectnuke.fusion.retrieval.LocalRagSourceEntry

// Presentation-only policy for the local attachment RAG product surface.
//
// This file never touches retrieval, snapshots or stored provenance: it only
// maps existing retrieval truth (LocalRagInputPolicy.isSupported,
// LocalRagSourceEntry) to Korean-first, non-technical UI copy. It never
// converts capability or similarity into certainty, and it never labels local
// reference context as "verified" evidence.

enum class FusionSourceKind {
    WEB_SOURCE,
    LOCAL_ATTACHMENT,
}

object LocalAttachmentContextPolicy {

    fun isLocalContextType(mimeType: String?, fileName: String): Boolean =
        LocalRagInputPolicy.isSupported(mimeType, fileName)

    /** Subtle pending-attachment affordance. Null means "no local-context
     *  claim" — unsupported files must never imply textual RAG use. */
    fun localContextLabel(mimeType: String?, fileName: String): String? =
        if (isLocalContextType(mimeType, fileName)) "로컬 문서" else null

    /** Quiet capability copy near the affordance. Real: supported types can
     *  be read as local context by the existing retrieval path. */
    fun localContextDescription(mimeType: String?, fileName: String): String? =
        if (isLocalContextType(mimeType, fileName)) {
            "기기 안에서 읽은 문서 내용을 참고할 수 있어요."
        } else {
            null
        }

    /** Web and local sources stay visually distinct. */
    fun sourceKindLabel(kind: FusionSourceKind): String = when (kind) {
        FusionSourceKind.WEB_SOURCE -> "검색 출처"
        FusionSourceKind.LOCAL_ATTACHMENT -> "첨부 문서"
    }

    /** Primary capsule copy. Human-readable; no chunk counts or ranking
     *  internals. Counts distinct documented attachments. */
    fun answerReferenceSummary(sources: List<LocalRagSourceEntry>): String {
        val distinct = sources.map { it.attachmentId }.distinct().size
        return "첨부 문서 ${distinct}개 참고"
    }

    /** Approximate location inside the document (chunk position is the
     *  coarsest honest locator we expose in the primary view). */
    fun documentLocationLabel(chunkIndex: Int): String = "문서 #${chunkIndex + 1}"

    fun detailDisclosureLabel(): String = "세부 정보"

    fun detailContentHashLabel(): String = "내용 지문"

    fun detailOffsetsLabel(): String = "원본 위치"

    fun detailOffsetsValue(startOffset: Int, endOffset: Int): String =
        "${startOffset}–${endOffset} 글자"

    fun localSourcesDialogTitle(): String = "첨부 문서 참조"

    fun localProvenanceDisclaimer(): String =
        "첨부 문서 발췌는 참고 자료예요. 웹 확인 결과와 별개이며, 검증된 내용이라는 뜻이 아니에요."

    fun changedSourceMessage(): String =
        "첨부 문서가 변경되어 답변을 시작하지 않았어요. 다시 첨부해 주세요."

    fun textUnavailableMessage(): String =
        "첨부 문서를 읽지 못했어요. 파일을 다시 추가해 주세요."

    fun unsupportedTypeMessage(): String =
        "이 형식은 문서 내용으로 읽을 수 없어요. txt, md, json, csv 파일을 사용해 주세요."

    /** Surface-visible primary labels. These must never carry
     *  verification-certainty words. */
    fun primaryLabels(): List<String> = listOf(
        localSourcesDialogTitle(),
        sourceKindLabel(FusionSourceKind.LOCAL_ATTACHMENT),
    )
}