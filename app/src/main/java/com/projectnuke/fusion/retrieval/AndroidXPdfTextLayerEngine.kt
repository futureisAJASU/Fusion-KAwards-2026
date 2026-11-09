package com.projectnuke.fusion.retrieval

import android.content.Context
import android.os.ParcelFileDescriptor
import android.net.Uri
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.SandboxedPdfLoader
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers

/**
 * Production PDF text-layer engine bound to the official AndroidX PDF stack.
 *
 * DEPENDENCY DECISION (Document Intelligence v1):
 *  - library: androidx.pdf:pdf-core + androidx.pdf:pdf-document-service
 *  - version: 1.0.0-beta01 (current official release)
 *  - license: Apache-2.0 (permissive; compatible with Fusion)
 *  - why: the sanctioned AndroidX PDF stack — SandboxedPdfLoader + a
 *    sandboxed (isolated-process) PdfDocumentService — parses the PDF text
 *    layer page-by-page without any network, any cloud parser, any second
 *    model/runtime, or any PDFBox-family dependency. Android's platform
 *    PdfRenderer is NOT a text extractor, so parsing is delegated to this
 *    real parser (pdf-document-service bundles both platform-API and
 *    pre-API-35 compatibility adapters).
 *  - what it does NOT provide: no OCR, no image/figure understanding, no
 *    layout/reading-order reconstruction, no tables/math reshaping, no
 *    page rendering for vision. Extracted PDF content therefore flows only
 *    into the existing bounded local RAG pipeline as untrusted text data.
 *
 * Architecture:
 *  - [SandboxedPdfLoader] is created LAZILY on the first open; nothing is
 *    initialized at app startup and no background service persists.
 *  - Each [open] binds the sandboxed document service for exactly one
 *    [PdfDocument]; closing the document disconnects the service
 *    (PdfDocument.close -> connection.disconnect + PFD close), so no PDF
 *    service/session leaks after extraction.
 *  - [PdfDocument.getPageContent] textContents are the text-layer authority;
 *    imageContents are counted only for image presence.
 *  - No OCR, no page bitmaps, no PDFBox.
 */
class AndroidXPdfTextLayerEngine(
    private val context: Context,
) : PdfTextLayerEngine {

    @Volatile
    private var loader: SandboxedPdfLoader? = null

    private val loaderContext: CoroutineContext = Dispatchers.IO

    private fun loader(): SandboxedPdfLoader =
        loader ?: synchronized(this) {
            loader ?: SandboxedPdfLoader(context.applicationContext, loaderContext).also { loader = it }
        }

    override suspend fun open(sourcePath: String): PdfTextLayerDocument {
        if (sourcePath.isBlank()) throw PdfEngineStatusException(DocumentExtractionStatus.UNREADABLE)
        val file = File(sourcePath)
        if (!file.isFile) throw PdfEngineStatusException(DocumentExtractionStatus.UNREADABLE)
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw PdfEngineStatusException(DocumentExtractionStatus.UNREADABLE)
        }
        val document = try {
            loader().openDocument(
                uri = Uri.fromFile(file),
                fileDescriptor = pfd,
                password = null,
            )
        } catch (e: CancellationException) {
            runCatching { pfd.close() }
            throw e
        } catch (e: PdfPasswordException) {
            runCatching { pfd.close() }
            throw PdfEngineStatusException(DocumentExtractionStatus.PASSWORD_PROTECTED)
        } catch (e: IOException) {
            runCatching { pfd.close() }
            throw PdfEngineStatusException(DocumentExtractionStatus.UNREADABLE)
        } catch (e: PdfEngineStatusException) {
            runCatching { pfd.close() }
            throw e
        } catch (_: Exception) {
            runCatching { pfd.close() }
            throw PdfEngineStatusException(DocumentExtractionStatus.UNREADABLE)
        }
        if (document == null) {
            runCatching { pfd.close() }
            throw PdfEngineStatusException(DocumentExtractionStatus.UNREADABLE)
        }
        return AndroidXPdfTextLayerDocument(document)
    }
}

private class AndroidXPdfTextLayerDocument(
    private val document: PdfDocument,
) : PdfTextLayerDocument {

    override val pageCount: Int
        get() = document.pageCount

    override suspend fun pageTextLayer(pageNumber: Int): PdfPageTextLayer? {
        // Fusion pages are REAL 1-based; the AndroidX platform adapter is
        // 0-based. The mapping lives in one tested helper; out-of-range or
        // invalid requests fail closed as UNREADABLE.
        val platformIndex = PdfPlatformPageIndex.platformIndex(pageNumber, document.pageCount)
            ?: throw PdfEngineStatusException(DocumentExtractionStatus.UNREADABLE)
        val content = try {
            document.getPageContent(platformIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PdfEngineStatusException) {
            throw e
        } catch (_: Exception) {
            throw PdfEngineStatusException(DocumentExtractionStatus.UNREADABLE)
        }
        if (content == null) return PdfPageTextLayer(text = "", textContentCount = 0, imageElementCount = 0)
        // Platform-provided order is authoritative; join conservatively and
        // never invent text (no bbox heuristics, no spelling fixes).
        val textContents = content.textContents
        val imageContents = content.imageContents
        val text = textContents.joinToString(separator = "\n") { it.text }
        return PdfPageTextLayer(
            text = text,
            textContentCount = textContents.size,
            imageElementCount = imageContents.size,
        )
    }

    override fun close() {
        runCatching { document.close() }
    }
}