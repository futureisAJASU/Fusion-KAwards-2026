package com.projectnuke.fusion.retrieval

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * PDF text-layer extraction pipeline: file/product bounds, per-page bounded
 * extraction in platform order, truthful classification.
 *
 * - Bounds exceed -> typed TOO_* status. NEVER silently truncates a PDF and
 *   reports full-document success.
 * - Password -> [DocumentExtractionStatus.PASSWORD_PROTECTED]
 * - No usable text anywhere + images -> IMAGE_ONLY_NO_OCR
 * - No usable text + no images -> NO_TEXT_LAYER
 * - I/O or malformed document -> UNREADABLE
 * - Blank pages are recorded only when they carry images (empty text);
 *   completely empty pages are skipped without lying about text pages.
 *
 * A mixed PDF (text pages + image-only pages) is ACCEPTED with exact truth:
 * pageCount / text pages / image-only pages / image element counts.
 */
class PdfTextLayerExtractor(
    private val engine: PdfTextLayerEngine,
) : LocalDocumentExtractor {

    override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
        if (path.isBlank()) return DocumentExtractionResult.NotReady(DocumentExtractionStatus.UNREADABLE)
        val file = File(path)
        if (!file.isFile) return DocumentExtractionResult.NotReady(DocumentExtractionStatus.UNREADABLE)
        if (file.length() > PdfDocumentBounds.MAX_PDF_FILE_BYTES) {
            return DocumentExtractionResult.NotReady(DocumentExtractionStatus.TOO_LARGE_FILE)
        }

        val document = try {
            engine.open(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PdfEngineStatusException) {
            return DocumentExtractionResult.NotReady(e.status)
        } catch (_: Exception) {
            return DocumentExtractionResult.NotReady(DocumentExtractionStatus.UNREADABLE)
        }

        document.use { doc ->
            if (doc.pageCount > PdfDocumentBounds.MAX_PDF_PAGES) {
                return DocumentExtractionResult.NotReady(DocumentExtractionStatus.TOO_MANY_PAGES)
            }
            val pages = ArrayList<ExtractedDocumentPage>(doc.pageCount)
            var totalChars = 0
            for (pageNumber in 1..doc.pageCount) {
                currentCoroutineContext().ensureActive()
                val layer = try {
                    doc.pageTextLayer(pageNumber)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PdfEngineStatusException) {
                    return DocumentExtractionResult.NotReady(e.status)
                } catch (_: Exception) {
                    return DocumentExtractionResult.NotReady(DocumentExtractionStatus.UNREADABLE)
                }
                // Null / fully empty page: blank page, no truth to record.
                if (layer == null) continue
                if (layer.text.isEmpty() && layer.imageElementCount == 0) continue
                // Conservative deterministic normalization (CRLF/control-char/
                // spacing cleanup) BEFORE bounds, counting and hashing so the
                // stored index text is exactly what retrieval and provenance
                // hash over.
                val text = NormalizedPdfText.normalize(layer.text)
                if (text.isEmpty() && layer.imageElementCount == 0) continue
                if (text.length > PdfDocumentBounds.MAX_PDF_PAGE_CHARS) {
                    return DocumentExtractionResult.NotReady(DocumentExtractionStatus.TOO_MUCH_TEXT)
                }
                totalChars += text.length
                if (totalChars > PdfDocumentBounds.MAX_PDF_EXTRACTED_CHARS) {
                    return DocumentExtractionResult.NotReady(DocumentExtractionStatus.TOO_MUCH_TEXT)
                }
                pages += ExtractedDocumentPage(
                    pageNumber = pageNumber,
                    text = text,
                    textCharCount = text.length,
                    contentHashHex = LocalDocumentChunker.sha256Hex(text),
                    hasImages = layer.imageElementCount > 0,
                    imageElementCount = layer.imageElementCount,
                )
            }

            val textPages = pages.filter { it.text.isNotEmpty() }
            val imageOnlyPages = pages.filter { it.text.isEmpty() && it.hasImages }
            val status = when {
                textPages.isNotEmpty() -> DocumentExtractionStatus.READY
                imageOnlyPages.isNotEmpty() -> DocumentExtractionStatus.IMAGE_ONLY_NO_OCR
                else -> DocumentExtractionStatus.NO_TEXT_LAYER
            }
            if (status != DocumentExtractionStatus.READY) {
                return DocumentExtractionResult.NotReady(status)
            }
            return DocumentExtractionResult.Ready(
                ExtractedLocalDocument(
                    kind = LocalDocumentKind.PDF_TEXT_LAYER,
                    pageCount = doc.pageCount,
                    textPageCount = textPages.size,
                    imageOnlyPageCount = imageOnlyPages.size,
                    imageElementCount = pages.sumOf { it.imageElementCount },
                    totalExtractedChars = totalChars,
                    pages = pages,
                ),
            )
        }
    }
}