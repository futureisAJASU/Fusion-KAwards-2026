package com.projectnuke.fusion.retrieval

import java.io.File

/**
 * One shared admission seam for ALL local document ingestion paths
 * (attachment picker, Android share/handoff, project import, promotion).
 *
 * - PDF: extraction/index must produce READY (typed statuses reject —
 *   IMAGE_ONLY_NO_OCR, PASSWORD_PROTECTED, TOO_MANY_PAGES, TOO_MUCH_TEXT,
 *   TOO_LARGE_FILE, UNREADABLE). A PDF is never "attached and silently
 *   ignored".
 * - Plain text: [strictTextLimit] mirrors the shared-document handoff
 *   contract (existing 32,000-char rejection); the picker keeps its current
 *   behavior (bounded read at RAG time) when strict is false.
 * - Unsupported kinds reject with a typed status; nothing is faked.
 */
object DocumentAdmissionPolicy {

    sealed interface DocumentAdmissionResult {
        data object Accepted : DocumentAdmissionResult
        data class Rejected(val status: DocumentExtractionStatus) : DocumentAdmissionResult
    }

    /**
     * [pdfAdmission] is the injected production seam (extraction or derived
     * index); host tests substitute fakes.
     */
    suspend fun admit(
        path: String,
        mimeType: String?,
        fileName: String,
        pdfAdmission: (suspend (String, String?) -> DocumentAdmissionResult)?,
        strictTextLimit: Boolean,
    ): DocumentAdmissionResult {
        if (LocalDocumentKindPolicy.isPdf(mimeType, fileName)) {
            val admission = pdfAdmission ?: return DocumentAdmissionResult.Rejected(
                DocumentExtractionStatus.UNSUPPORTED,
            )
            return admission(path, mimeType)
        }
        if (!LocalRagInputPolicy.isSupported(mimeType, fileName)) {
            return DocumentAdmissionResult.Rejected(DocumentExtractionStatus.UNSUPPORTED)
        }
        if (strictTextLimit) {
            val file = File(path)
            if (!file.isFile) return DocumentAdmissionResult.Rejected(DocumentExtractionStatus.UNREADABLE)
            val chars = readBoundedCharCount(file, LocalRagBounds.MAX_DOC_CHARS)
            return when {
                chars < 0 -> DocumentAdmissionResult.Rejected(DocumentExtractionStatus.UNREADABLE)
                chars > LocalRagBounds.MAX_DOC_CHARS ->
                    DocumentAdmissionResult.Rejected(DocumentExtractionStatus.TOO_MUCH_TEXT)
                else -> DocumentAdmissionResult.Accepted
            }
        }
        return DocumentAdmissionResult.Accepted
    }

    /** True only for an actually usable PDF document. */
    fun isReadyPdf(result: DocumentAdmissionResult): Boolean =
        result is DocumentAdmissionResult.Accepted

    private fun readBoundedCharCount(file: File, maxChars: Int): Int {
        val result = StrictBoundedTextDecoder.read(file, maxChars) ?: return -1
        return if (result.exceededLimit) maxChars + 1 else result.text.length
    }
}
