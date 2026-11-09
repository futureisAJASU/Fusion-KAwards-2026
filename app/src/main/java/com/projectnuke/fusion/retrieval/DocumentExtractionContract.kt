package com.projectnuke.fusion.retrieval

// Document Intelligence: unified local document extraction contract.
//
// The local RAG pipeline no longer assumes attachments are readable text.
// Every supported attachment is turned into structured truth
// (ExtractedLocalDocument) with deterministic page identity, or an explicit
// typed failure. Plain-text semantics are preserved exactly; PDF pages are
// real and survive through retrieval/provenance.
//
// Extraction NEVER renders bitmaps, NEVER runs OCR, and NEVER calls a model.
// It is deterministic source-derived data only.

/** Product document kinds the extraction layer can produce. */
enum class LocalDocumentKind {
    /** One logical text document (existing text-file contract, 32,000 chars). */
    PLAIN_TEXT,

    /** PDF with a usable text layer (bounded by [PdfDocumentBounds]). */
    PDF_TEXT_LAYER,
    OCR_IMAGE,
}

/**
 * Typed extraction outcome family. [READY] carries structured content;
 * every other status is a truthful rejection state, never a silent skip.
 */
enum class DocumentExtractionStatus {
    READY,
    NO_TEXT_LAYER,
    IMAGE_ONLY_NO_OCR,
    PASSWORD_PROTECTED,
    TOO_MANY_PAGES,
    TOO_MUCH_TEXT,
    TOO_LARGE_FILE,
    UNREADABLE,
    UNSUPPORTED,
}

/**
 * Product bounds for PDF document intelligence.
 *
 * A source that exceeds a hard bound is REJECTED truthfully (typed status);
 * the pipeline never silently truncates a PDF and claims full-document
 * indexing. A large accepted PDF may be fully indexed locally while only
 * bounded excerpts reach the model — indexing and prompt bounds are
 * different concepts.
 */
object PdfDocumentBounds {
    const val MAX_PDF_FILE_BYTES: Long = 32L * 1024L * 1024L
    const val MAX_PDF_PAGES = 256
    const val MAX_PDF_EXTRACTED_CHARS = 512_000
    const val MAX_PDF_PAGE_CHARS = 32_000
}

/** One extracted page. [pageNumber] is the 1-based product page number. */
data class ExtractedDocumentPage(
    val pageNumber: Int,
    val text: String,
    val textCharCount: Int,
    val contentHashHex: String,
    val hasImages: Boolean,
    val imageElementCount: Int,
    val ocrDerived: Boolean = false,
) {
    init {
        require(pageNumber >= 1) { "pageNumber must be 1-based, got $pageNumber" }
    }
}

/**
 * Bounded structured extraction result. For PLAIN_TEXT there is exactly one
 * logical page (page 1) and no fake PDF page semantics. For PDF_TEXT_LAYER
 * every entry is a real 1-based page.
 *
 * [pageCount] is the actual document page count (PDF authority).
 * [textPageCount] counts pages that yielded usable text.
 * [imageOnlyPageCount] counts pages without text that carry images.
 *
 * [truncated] is the v1 bounded-extraction truth flag. Under Fusion's strict
 * v1 policy a document that exceeds a hard extraction bound is REJECTED with
 * a typed status (TOO_MANY_PAGES / TOO_MUCH_TEXT / TOO_LARGE_FILE) — never
 * silently truncated — so every returned document carries `truncated ==
 * false`. The field makes that policy explicit and testable; if a future
 * bounded-prefix policy is adopted it will surface exactly here instead of
 * pretending full-document indexing.
 */
data class ExtractedLocalDocument(
    val kind: LocalDocumentKind,
    val pageCount: Int,
    val textPageCount: Int,
    val imageOnlyPageCount: Int,
    val imageElementCount: Int,
    val totalExtractedChars: Int,
    val pages: List<ExtractedDocumentPage>,
    val truncated: Boolean = false,
) {
    val isPdf: Boolean get() = kind == LocalDocumentKind.PDF_TEXT_LAYER

    init {
        require(!truncated) {
            "Fusion Document Intelligence v1 uses STRICT bounded rejection: extracted documents are never flagged truncated"
        }
    }
}

sealed interface DocumentExtractionResult {
    data class Ready(val document: ExtractedLocalDocument) : DocumentExtractionResult
    data class NotReady(val status: DocumentExtractionStatus) : DocumentExtractionResult
}

/**
 * Extraction seam per document kind. Implementations are Android-free where
 * possible so host tests exercise the full classification pipeline with a
 * fake engine; the production PDF engine binds the official AndroidX PDF
 * stack (SandboxedPdfLoader + PdfDocument.getPageContent).
 */
interface LocalDocumentExtractor {
    /** [path] is the canonical managed source path. Never null-free strings. */
    suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult
}

/** Deterministic kind routing shared by extraction, ingestion and display. */
object LocalDocumentKindPolicy {
    fun isOcrImage(mimeType: String?, fileName: String): Boolean =
        mimeType?.lowercase() in setOf("image/png", "image/jpeg", "image/webp") ||
            fileName.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp") }
    const val PDF_MIME_TYPE = "application/pdf"

    fun isPdf(mimeType: String?, fileName: String): Boolean {
        if (mimeType?.lowercase() == PDF_MIME_TYPE) return true
        return fileName.lowercase().endsWith(".pdf")
    }

    fun isPlainText(mimeType: String?, fileName: String): Boolean =
        !isPdf(mimeType, fileName) && LocalRagInputPolicy.isSupported(mimeType, fileName)
}
