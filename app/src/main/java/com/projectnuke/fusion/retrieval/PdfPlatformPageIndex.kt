package com.projectnuke.fusion.retrieval

/**
 * Pure page-index mapper at the AndroidX adapter boundary.
 *
 * Fusion's product/document contract uses REAL 1-based PDF page numbers
 * everywhere (extraction, chunking, provenance, user copy):
 *
 *   Fusion page 1 = physical first PDF page
 *   Fusion page 2 = physical second PDF page
 *
 * The androidx.pdf platform adapter (PdfDocument.getPageContent /
 * getPageInfo) accepts ZERO-BASED page indices ("Parameters: int pageNumber
 * — The page number (0-based)").
 *
 * Mapping: Fusion N -> platform index N - 1.
 *
 * This helper is the ONLY place the -1 arithmetic lives; production
 * [AndroidXPdfTextLayerDocument] consumes it. It fails closed (returns null)
 * for product page <= 0, product page > pageCount, and an invalid
 * (non-positive) pageCount so no unsigned/out-of-range index can ever reach
 * the platform.
 */
object PdfPlatformPageIndex {

    /**
     * Maps Fusion's 1-based [pageNumber] to the AndroidX 0-based page index
     * within a document of [pageCount] physical pages.
     *
     * Returns null when the request is out of range or the document is
     * invalid — caller must fail closed.
     */
    fun platformIndex(pageNumber: Int, pageCount: Int): Int? {
        if (pageCount <= 0) return null
        if (pageNumber <= 0 || pageNumber > pageCount) return null
        return pageNumber - 1
    }
}