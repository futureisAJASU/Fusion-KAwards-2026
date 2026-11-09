package com.projectnuke.fusion.retrieval

// PDF text-layer engine seam.
//
// The production binding uses the official AndroidX PDF stack
// (androidx.pdf:pdf-document-service SandboxedPdfLoader +
// PdfDocument.getPageContent / PdfPageContent.textContents). Host tests
// drive the same extraction pipeline through a programmable fake engine;
// no device, no OCR, no bitmap rendering.
//
// textContents are the text-layer authority; imageContents are inspected
// ONLY to determine image presence. Reading order is the platform-provided
// order; the extractor never reorders columns or infers layout.

/** A page's platform-provided text layer. */
data class PdfPageTextLayer(
    val text: String,
    val textContentCount: Int,
    val imageElementCount: Int,
)

/** Open document handle; must be closed through explicit lifecycle. */
interface PdfTextLayerDocument : AutoCloseable {
    /** Document page count (platform authority, 1-based pages). */
    val pageCount: Int

    /**
     * 1-based page text layer. Null means the platform produced no content
     * block for the page (blank page). Never throws raw platform exceptions:
     * typed [PdfEngineStatusException] only.
     */
    suspend fun pageTextLayer(pageNumber: Int): PdfPageTextLayer?

    override fun close()
}

interface PdfTextLayerEngine {
    /**
     * Opens the canonical managed PDF. Typed failures:
     * [DocumentExtractionStatus.PASSWORD_PROTECTED] for password-protected
     * documents and [DocumentExtractionStatus.UNREADABLE] for malformed or
     * unreadable sources.
     */
    suspend fun open(sourcePath: String): PdfTextLayerDocument
}

/** Typed engine failure carrying the product status (no raw Java errors). */
class PdfEngineStatusException(
    val status: DocumentExtractionStatus,
) : Exception(status.name)