package com.projectnuke.fusion.retrieval

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

sealed interface DocumentIndexResult {
    data class Ready(val index: PdfDocumentIndex) : DocumentIndexResult
    data class NotReady(val status: DocumentExtractionStatus) : DocumentIndexResult
}

/**
 * Index seam used by the RAG coordinator: cache-then-build obtain plus
 * bounded selected-page re-extraction for fail-closed validation.
 */
interface LocalDocumentIndexSource {
    suspend fun obtain(canonicalPath: String, mimeType: String?): DocumentIndexResult
    suspend fun reextractPages(
        canonicalPath: String,
        pageNumbers: Set<Int>,
    ): Map<Int, ExtractedDocumentPage>?
}

/**
 * Demand-driven document index repository.
 *
 * - Cache-first: derives from [DocumentIndexStore]; on miss/staleness it
 *   builds the index through [extractor] once and persists it atomically.
 * - Bounded concurrency: ONE active PDF extraction per process (global
 *   [Mutex]); concurrent requests for the same source serialize and the
 *   second reuses the freshly written cache. No uncontrolled parallel
 *   8x256-page extraction.
 * - Cancellation propagates: a cancelled build releases the lock and the
 *   atomic writer never publishes a partial index.
 * - Read-only [status] normally reads only a bounded metadata sidecar. A
 *   missing sidecar gets one serialized, off-main authoritative legacy-v2
 *   validation, after which only the sidecar is retained by the UI path.
 */
class PdfDocumentIndexRepository(
    private val store: DocumentIndexStore,
    private val extractor: LocalDocumentExtractor,
    private val engine: PdfTextLayerEngine? = null,
) : LocalDocumentIndexSource {

    private val buildMutex = Mutex()
    private val statusFallbackMutex = Mutex()
    private val localOcr by lazy { AndroidLocalOcr() }

    internal suspend fun recognizeText(path: String, mime: String?, progress: (Int, Int) -> Unit): LocalOcrResult =
        withContext(Dispatchers.IO) {
            buildMutex.withLock {
                val source = File(path)
                val hash = AndroidLocalOcr.sourceHash(source)
                val pdf = LocalDocumentKindPolicy.isPdf(mime, source.name)
                val native = if (pdf) (extractor.extract(path, mime) as? DocumentExtractionResult.Ready)?.document else null
                val nativePages = native?.pages.orEmpty().associate { page -> page.pageNumber to IndexedDocumentPage(
                    page.pageNumber, page.text, page.textCharCount, page.contentHashHex, page.hasImages, page.imageElementCount) }
                val result = if (pdf) {
                    android.os.ParcelFileDescriptor.open(source, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                            LocalOcrPipeline.collect(renderer.pageCount, nativePages, { page ->
                                renderer.openPage(page - 1).use { localOcr.recognize(localOcr.render(it)) }
                            }, progress)
                        }
                    }
                } else LocalOcrPipeline.collect(1, emptyMap(), { localOcr.recognize(localOcr.image(source)) }, progress)
                if (result is LocalOcrResult.Complete) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    check(AndroidLocalOcr.sourceHash(source) == hash) { "인식 중 파일이 변경되었습니다." }
                    val pages = result.pages
                    val index = PdfDocumentIndex(DocumentIndexMetadata(DocumentIndexContract.DOCUMENT_INDEX_VERSION,
                        if (pdf) LocalDocumentKind.PDF_TEXT_LAYER else LocalDocumentKind.OCR_IMAGE,
                        pages.size, pages.count { it.text.isNotBlank() }, pages.count { it.text.isBlank() },
                        pages.sumOf { it.imageElementCount }, pages.sumOf { it.text.length }, source.length(), source.lastModified(),
                        if (pages.any { it.ocrDerived }) hash else ""), pages)
                    check(store.write(path, source, index)) { "인식한 텍스트를 저장하지 못했습니다." }
                    store.enforceBudget()
                }
                result
            }
        }

    override suspend fun obtain(canonicalPath: String, mimeType: String?): DocumentIndexResult = withContext(Dispatchers.IO) {
        if (canonicalPath.isBlank()) return@withContext DocumentIndexResult.NotReady(DocumentExtractionStatus.UNREADABLE)
        val source = File(canonicalPath)
        if (!source.isFile) return@withContext DocumentIndexResult.NotReady(DocumentExtractionStatus.UNREADABLE)
        store.load(canonicalPath, source)?.let { return@withContext DocumentIndexResult.Ready(it) }
        if (LocalDocumentKindPolicy.isOcrImage(mimeType, source.name)) return@withContext DocumentIndexResult.NotReady(DocumentExtractionStatus.IMAGE_ONLY_NO_OCR)
        buildMutex.withLock {
            // Another coroutine may have completed the build while we waited.
            store.load(canonicalPath, source)?.let { return@withLock DocumentIndexResult.Ready(it) }
            build(canonicalPath, mimeType, source)
        }
    }

    private suspend fun build(
        canonicalPath: String,
        mimeType: String?,
        source: File,
    ): DocumentIndexResult {
        val extraction = try {
            extractor.extract(canonicalPath, mimeType)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return DocumentIndexResult.NotReady(DocumentExtractionStatus.UNREADABLE)
        }
        val document = (extraction as? DocumentExtractionResult.Ready)?.document
            ?: return DocumentIndexResult.NotReady(
                (extraction as DocumentExtractionResult.NotReady).status,
            )
        if (document.kind != LocalDocumentKind.PDF_TEXT_LAYER) {
            return DocumentIndexResult.NotReady(DocumentExtractionStatus.UNSUPPORTED)
        }
        val index = PdfDocumentIndex(
            metadata = DocumentIndexMetadata(
                version = DocumentIndexContract.DOCUMENT_INDEX_VERSION,
                kind = document.kind,
                pageCount = document.pageCount,
                textPageCount = document.textPageCount,
                imageOnlyPageCount = document.imageOnlyPageCount,
                imageElementCount = document.imageElementCount,
                totalExtractedChars = document.totalExtractedChars,
                sourceSizeBytes = source.length(),
                sourceLastModifiedMillis = source.lastModified(),
            ),
            pages = document.pages.map { page ->
                IndexedDocumentPage(
                    pageNumber = page.pageNumber,
                    text = page.text,
                    textCharCount = page.textCharCount,
                    contentHashHex = page.contentHashHex,
                    hasImages = page.hasImages,
                    imageElementCount = page.imageElementCount,
                )
            },
        )
        // Persist atomically; a refused write (oversized encoding) simply
        // means next use rebuilds — never a partial artifact.
        store.write(canonicalPath, source, index)
        store.enforceBudget()
        return DocumentIndexResult.Ready(index)
    }

    /**
     * Bounded re-extraction of ONLY the selected pages from the canonical
     * source (fail-closed snapshot validation). Returns null when the PDF
     * disappeared, became unreadable/password-protected, or a page no longer
     * yields content. Never re-extracts the whole document.
     */
    override suspend fun reextractPages(
        canonicalPath: String,
        pageNumbers: Set<Int>,
    ): Map<Int, ExtractedDocumentPage>? {
        val cachedOcr = withContext(Dispatchers.IO) {
            val source = File(canonicalPath)
            if (store.readStatus(canonicalPath, source)?.hasOcrText == true) store.load(canonicalPath, source) else null
        }
        if (cachedOcr?.pages?.any { it.ocrDerived } == true) {
            val pages = cachedOcr.toExtractedLocalDocument().pages.filter { it.pageNumber in pageNumbers }
            if (pages.size != pageNumbers.size) return null
            return pages.associateBy { it.pageNumber }
        }
        val engine = engine ?: return null
        if (pageNumbers.isEmpty()) return emptyMap()
        if (canonicalPath.isBlank()) return null
        val source = File(canonicalPath)
        if (!source.isFile) return null
        val document = try {
            engine.open(canonicalPath)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        document.use { doc ->
            if (pageNumbers.any { it !in 1..doc.pageCount }) return null
            val out = LinkedHashMap<Int, ExtractedDocumentPage>()
            for (pageNumber in pageNumbers.sorted()) {
                val layer = try {
                    doc.pageTextLayer(pageNumber)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return null
                }
                // A selected page must still carry its text layer.
                if (layer == null) return null
                if (layer.text.length > PdfDocumentBounds.MAX_PDF_PAGE_CHARS) return null
                out[pageNumber] = ExtractedDocumentPage(
                    pageNumber = pageNumber,
                    text = layer.text,
                    textCharCount = layer.text.length,
                    contentHashHex = LocalDocumentChunker.sha256Hex(layer.text),
                    hasImages = layer.imageElementCount > 0,
                    imageElementCount = layer.imageElementCount,
                )
            }
            return out
        }
    }

    /**
     * Metadata-only UI status. The steady-state path never reads or decodes
     * the full index. Only a legacy current-version index with no sidecar is
     * validated once, off the main thread, under a process-local single-flight
     * mutex; the decoded index is immediately discarded after sidecar publish.
     */
    suspend fun status(canonicalPath: String, mimeType: String?): DocumentIndexStatusSnapshot? =
        withContext(Dispatchers.IO) {
            if (canonicalPath.isBlank()) return@withContext null
            val source = File(canonicalPath)
            if (!source.isFile) return@withContext null

            store.readStatus(canonicalPath, source)?.let { snapshot ->
                return@withContext snapshot.takeIf { it.kind == LocalDocumentKind.PDF_TEXT_LAYER || it.kind == LocalDocumentKind.OCR_IMAGE }
            }
            // A malformed or stale sidecar fails closed. Only a genuinely
            // missing sidecar qualifies for the one-time legacy fallback.
            if (store.statusFileFor(canonicalPath).isFile) return@withContext null

            statusFallbackMutex.withLock {
                // Another UI surface may have populated the sidecar while
                // this coroutine waited for the bounded fallback slot.
                store.readStatus(canonicalPath, source)?.let { snapshot ->
                    return@withLock snapshot.takeIf { it.kind == LocalDocumentKind.PDF_TEXT_LAYER || it.kind == LocalDocumentKind.OCR_IMAGE }
                }
                if (store.statusFileFor(canonicalPath).isFile) return@withLock null

                val index = store.load(canonicalPath, source) ?: return@withLock null
                if (index.metadata.kind != LocalDocumentKind.PDF_TEXT_LAYER && index.metadata.kind != LocalDocumentKind.OCR_IMAGE) return@withLock null
                if (!store.writeStatus(canonicalPath, source, index.metadata)) return@withLock null
                store.readStatus(canonicalPath, source)
            }
        }

    /** Test/inspection seam for the metadata-only sidecar path. */
    internal fun statusFile(canonicalPath: String): File = store.statusFileFor(canonicalPath)

    /** Deletes the derived index for a managed source (source deleted). */
    fun deleteIndex(canonicalPath: String) {
        if (canonicalPath.isBlank()) return
        store.delete(canonicalPath)
    }

    /** Sweeps derived indexes whose physical source no longer exists. */
    fun cleanupOrphans(liveSources: Set<File>) {
        store.cleanupOrphans(liveSources)
        store.enforceBudget()
    }
}
