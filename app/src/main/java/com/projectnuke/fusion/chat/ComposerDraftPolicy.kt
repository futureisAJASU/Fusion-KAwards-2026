package com.projectnuke.fusion.chat

/**
 * Presentation and persistence share this exact durable composer input bound.
 * The policy rejects a proposed edit; it never truncates existing text.
 */
internal object ComposerDraftPolicy {
    const val MAX_INPUT_CHARS = 32_768

    fun proposeText(currentText: String, proposedText: String): ComposerTextEditResult =
        if (proposedText.length <= MAX_INPUT_CHARS) {
            ComposerTextEditResult.Accepted(proposedText)
        } else {
            ComposerTextEditResult.Rejected(currentText)
        }

    fun inputLimitMessage(): String =
        "입력이 너무 길어요. 저장 가능한 최대 ${MAX_INPUT_CHARS}자까지 입력할 수 있어요."
}

internal sealed interface ComposerTextEditResult {
    data class Accepted(val text: String) : ComposerTextEditResult
    data class Rejected(val preservedText: String) : ComposerTextEditResult
}

/** Pure, bounded presentation copy for typed Send admission failures. */
internal object SubmissionRejectionPresentationPolicy {
    fun message(reason: SubmissionRejectionReason): String = when (reason) {
        SubmissionRejectionReason.MANUAL_COMPACTION_LEASE_HELD ->
            "컨텍스트 압축이 진행 중입니다. 완료 후 다시 보내 주세요."
        SubmissionRejectionReason.EXISTING_SUBMISSION_OWNER ->
            "이미 다른 답변을 생성 중입니다. 완료 후 다시 보내 주세요."
        SubmissionRejectionReason.IMPORT_OWNER_ACTIVE ->
            "첨부 파일을 가져오는 중입니다. 완료 후 다시 보내 주세요."
        SubmissionRejectionReason.CONVERSATION_DELETION_IN_PROGRESS ->
            "대화를 삭제하는 중입니다. 완료 후 다시 보내 주세요."
        SubmissionRejectionReason.DRAFT_PERSISTENCE_REJECTED ->
            "입력을 저장하지 못해 보내지 않았습니다. 잠시 후 다시 시도해 주세요."
        SubmissionRejectionReason.DRAFT_STORE_UNREADABLE ->
            "입력 저장 상태를 확인할 수 없어 보내지 않았습니다. 잠시 후 다시 시도해 주세요."
    }
}
