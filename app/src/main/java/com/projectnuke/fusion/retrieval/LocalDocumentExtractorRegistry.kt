package com.projectnuke.fusion.retrieval

// Routes each managed attachment to the correct extractor. Plain text keeps
// the existing behavior exactly; PDF flows through the sandboxed text-layer
// engine when the platform extractor is wired (production). Unknown kinds
// never become extractable by accident.

class LocalDocumentExtractorRegistry(
    private val textExtractor: LocalDocumentExtractor,
    private val pdfExtractor: LocalDocumentExtractor? = null,
) {
    fun extractorFor(mimeType: String?, fileName: String): LocalDocumentExtractor? = when {
        LocalDocumentKindPolicy.isPdf(mimeType, fileName) -> pdfExtractor
        LocalDocumentKindPolicy.isPlainText(mimeType, fileName) -> textExtractor
        else -> null
    }

    /**
     * Returns null only when the kind is not a supported local document
     * (same semantics as `LocalRagInputPolicy.isSupported == false`).
     */
    suspend fun extract(path: String, mimeType: String?, fileName: String): DocumentExtractionResult? {
        val extractor = extractorFor(mimeType, fileName) ?: return null
        return extractor.extract(path, mimeType)
    }
}