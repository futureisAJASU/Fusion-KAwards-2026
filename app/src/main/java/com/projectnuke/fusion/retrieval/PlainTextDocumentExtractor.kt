package com.projectnuke.fusion.retrieval

import java.io.File

/**
 * Plain-text extractor. Preserves the existing whole-document contract
 * exactly: bounded 32,000-char read, one logical document source, no fake
 * PDF page numbers. [reader] is the existing text-store seam so host tests
 * keep injecting fakes.
 */
class PlainTextDocumentExtractor(
    private val reader: (path: String, maxChars: Int) -> String? = { path, maxChars ->
        FileLocalAttachmentTextStore().readText(path, maxChars)
    },
) : LocalDocumentExtractor {

    override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
        if (path.isBlank()) return DocumentExtractionResult.NotReady(DocumentExtractionStatus.UNREADABLE)
        val text = reader(path, LocalRagBounds.MAX_DOC_CHARS)
            ?: return DocumentExtractionResult.NotReady(DocumentExtractionStatus.UNREADABLE)
        return DocumentExtractionResult.Ready(
            ExtractedLocalDocument(
                kind = LocalDocumentKind.PLAIN_TEXT,
                pageCount = 1,
                textPageCount = if (text.isBlank()) 0 else 1,
                imageOnlyPageCount = 0,
                imageElementCount = 0,
                totalExtractedChars = text.length,
                pages = listOf(
                    ExtractedDocumentPage(
                        pageNumber = 1,
                        text = text,
                        textCharCount = text.length,
                        contentHashHex = LocalDocumentChunker.sha256Hex(text),
                        hasImages = false,
                        imageElementCount = 0,
                    ),
                ),
            ),
        )
    }

    companion object {
        /** Production reader: bounded canonical file read. */
        fun fileReader(path: String, maxChars: Int): String? {
            if (path.isBlank() || maxChars <= 0) return null
            val result = StrictBoundedTextDecoder.read(File(path), maxChars) ?: return null
            return result.text.takeIf { it.isNotEmpty() }
        }
    }
}
