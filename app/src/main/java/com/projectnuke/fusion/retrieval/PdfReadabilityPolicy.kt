package com.projectnuke.fusion.retrieval

/**
 * v1 user-visible PDF readability states (section 3). Four coarse states the
 * product surfaces can distinguish; detailed Korean copy stays in
 * [DocumentStatusPolicy] so status messages never leak parser jargon.
 */
enum class PdfReadabilityState {
    /** Text layer present and bounded extraction succeeded. */
    PDF_SUPPORTED,

    /** No meaningful text layer (image-only / scanned-like / blank). */
    PDF_NO_TEXT,

    /** Encrypted/unreadable/unparsable. */
    PDF_UNREADABLE,

    /** Exceeds the v1 extraction bounds (pages/chars/file size). */
    PDF_TOO_LARGE_OR_COMPLEX,
}

object PdfReadabilityPolicy {

    fun state(status: DocumentExtractionStatus): PdfReadabilityState = when (status) {
        DocumentExtractionStatus.READY -> PdfReadabilityState.PDF_SUPPORTED
        DocumentExtractionStatus.NO_TEXT_LAYER,
        DocumentExtractionStatus.IMAGE_ONLY_NO_OCR -> PdfReadabilityState.PDF_NO_TEXT
        DocumentExtractionStatus.TOO_MANY_PAGES,
        DocumentExtractionStatus.TOO_MUCH_TEXT,
        DocumentExtractionStatus.TOO_LARGE_FILE -> PdfReadabilityState.PDF_TOO_LARGE_OR_COMPLEX
        DocumentExtractionStatus.PASSWORD_PROTECTED,
        DocumentExtractionStatus.UNREADABLE,
        DocumentExtractionStatus.UNSUPPORTED -> PdfReadabilityState.PDF_UNREADABLE
    }

    /** Bounded Korean copy for the coarse state. */
    fun userCopy(state: PdfReadabilityState): String = when (state) {
        PdfReadabilityState.PDF_SUPPORTED ->
            "이 PDF를 로컬 문서로 사용할 수 있어요."
        PdfReadabilityState.PDF_NO_TEXT ->
            "이 PDF에서는 읽을 수 있는 텍스트를 찾지 못했습니다."
        PdfReadabilityState.PDF_UNREADABLE ->
            "이 PDF를 읽지 못했습니다. 암호가 걸렸거나 손상된 파일일 수 있어요."
        PdfReadabilityState.PDF_TOO_LARGE_OR_COMPLEX ->
            "이 PDF는 현재 로컬 문서 처리 한도를 넘어 사용할 수 없습니다."
    }
}