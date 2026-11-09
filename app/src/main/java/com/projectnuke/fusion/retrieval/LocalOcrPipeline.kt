package com.projectnuke.fusion.retrieval

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal sealed interface LocalOcrResult {
    data class Complete(val pages: List<IndexedDocumentPage>) : LocalOcrResult
    data class Partial(val completed: Int, val failedPages: List<Int>) : LocalOcrResult
    data object TooManyPages : LocalOcrResult
    data object TooMuchText : LocalOcrResult
    data object NoText : LocalOcrResult
}

/** Sequential, bounded text collection. A partial run never reaches the commit callback. */
internal object LocalOcrPipeline {
    const val MAX_OCR_PAGES = 32
    suspend fun collect(
        pageCount: Int,
        nativePages: Map<Int, IndexedDocumentPage>,
        recognize: suspend (Int) -> String,
        progress: (Int, Int) -> Unit,
    ): LocalOcrResult {
        if (pageCount !in 1..PdfDocumentBounds.MAX_PDF_PAGES) return LocalOcrResult.TooManyPages
        val missing = (1..pageCount).filter { nativePages[it]?.text.isNullOrBlank() }
        if (missing.size > MAX_OCR_PAGES) return LocalOcrResult.TooManyPages
        val pages = mutableListOf<IndexedDocumentPage>()
        val failed = mutableListOf<Int>()
        for (number in 1..pageCount) {
            coroutineContext.ensureActive()
            val native = nativePages[number]?.takeIf { it.text.isNotBlank() }
            if (native != null) pages += native
            else try {
                val text = NormalizedPdfText.normalize(recognize(number))
                if (text.length > PdfDocumentBounds.MAX_PDF_PAGE_CHARS) throw IllegalArgumentException("OCR page too long")
                pages += IndexedDocumentPage(number, text, text.length, LocalDocumentChunker.sha256Hex(text),
                    nativePages[number]?.hasImages ?: false, nativePages[number]?.imageElementCount ?: 0, ocrDerived = true)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { failed += number }
            progress(number, pageCount)
        }
        if (failed.isNotEmpty()) return LocalOcrResult.Partial(pages.size, failed)
        if (pages.sumOf { it.text.length } > PdfDocumentBounds.MAX_PDF_EXTRACTED_CHARS) return LocalOcrResult.TooMuchText
        if (pages.none { it.text.isNotBlank() }) return LocalOcrResult.NoText
        return LocalOcrResult.Complete(pages.toList())
    }
}
