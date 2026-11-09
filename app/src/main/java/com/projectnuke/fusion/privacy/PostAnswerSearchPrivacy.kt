package com.projectnuke.fusion.privacy

import com.projectnuke.fusion.chat.AnswerContextReceipt
import com.projectnuke.fusion.data.MessageEntity
import com.projectnuke.fusion.retrieval.LocalRagSourceMetadata

/**
 * Shared private-material check for the post-answer verification family.
 *
 * The full-answer owner ([com.projectnuke.fusion.ui.PostAnswerFactVerificationOwner])
 * and the UX-P2B selected-claim owner both use this ONE policy so the private
 * project/document/RAG safeguards cannot drift apart. It is a pure decision
 * over already-persisted answer metadata; it performs no I/O and no network.
 */
internal object PostAnswerSearchPrivacy {

    /**
     * Non-null when the answer carries project/attachment/local-RAG provenance
     * and therefore must never turn its claims into web queries.
     */
    fun privateSearchNotice(message: MessageEntity): String? {
        val receipt = AnswerContextReceipt.parse(message.content)
        val receiptHasPrivateMaterial = receipt?.let {
            it.projectContextUsed == true ||
                it.projectInstructionsApplied == true ||
                it.inputAttachmentCount > 0 ||
                it.inputImageCount > 0 ||
                it.localRagDocumentCount > 0 ||
                it.localRagChunkCount > 0 ||
                it.projectDecisionContextIncluded == true
        } == true
        return if (receiptHasPrivateMaterial || LocalRagSourceMetadata.hasProvenance(message.content)) {
            "로컬 문서·프로젝트 기반 답변은 문서 내용을 웹 검색으로 보내지 않습니다."
        } else null
    }

    /** Local attachment file names that must never appear in an outbound query. */
    fun localFileNames(message: MessageEntity): List<String> =
        LocalRagSourceMetadata.parse(message.content).map { it.fileName }
}
