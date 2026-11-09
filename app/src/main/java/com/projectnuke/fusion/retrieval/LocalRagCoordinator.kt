package com.projectnuke.fusion.retrieval

import com.projectnuke.fusion.util.AttachmentRecord
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// C4 production coordinator for bounded local attachment RAG.
//
// Production caller: ChatScreen local generation path (attachments present,
// local execution mode). The coordinator owns the actual pipeline:
//
// attachment -> typed document extraction (text contract unchanged; PDF text
// layer with real pages) -> page-aware bounded chunking -> lexical candidate
// discovery -> bounded KoEn semantic additions -> frozen
// LocalRagRequestSnapshot (with pageNumbers for PDFs) -> generation prompt
// excerpts with truthful coverage framing -> local provenance metadata.
//
// Plain-text behavior is preserved exactly (32,000-char bounded read, one
// logical document); PDF pages are real and survive through
// retrieval/provenance. Unsupported formats fail explicitly (never faked),
// typed extraction failures fail closed, deletion/content change fails
// closed, and nothing here requires or performs network I/O.

internal fun interface LocalAttachmentTextStore {
    // Null when the file is missing/unreadable; bounded by maxChars.
    fun readText(path: String, maxChars: Int): String?
}

internal class FileLocalAttachmentTextStore : LocalAttachmentTextStore {
    override fun readText(path: String, maxChars: Int): String? {
        if (path.isBlank() || maxChars <= 0) return null
        val result = StrictBoundedTextDecoder.read(File(path), maxChars) ?: return null
        return result.text.takeIf { it.isNotEmpty() }
    }
}

sealed interface LocalRagPreparationResult {
    // No supported text attachment: the request simply continues without RAG.
    data object NoSupportedAttachment : LocalRagPreparationResult

    // Only unsupported attachments were present: report the first explicitly.
    data class UnsupportedAttachment(val fileName: String) : LocalRagPreparationResult

    // The attachment text could not be read from storage; RAG quietly absent
    // rather than fabricated.
    data object TextUnavailable : LocalRagPreparationResult

    // A supported document (e.g. PDF) is unavailable with a typed status:
    // password-protected, image-only-no-OCR, oversize, unreadable, ...
    // Generation aborts RAG with the typed message instead of silently
    // ignoring the attached document.
    data class DocumentUnusable(
        val fileName: String,
        val status: DocumentExtractionStatus,
    ) : LocalRagPreparationResult

    data class Prepared(
        val selections: List<LocalRagSelection>,
        val snapshot: LocalRagRequestSnapshot,
        val docDisplayNames: List<String>,
        // docId -> full extracted text coverage (false = only representative
        // excerpts were provided; the model must not claim full coverage).
        val coverageByDoc: Map<String, Boolean> = emptyMap(),
    ) : LocalRagPreparationResult
}

internal class LocalRagCoordinator(
    private val textStore: LocalAttachmentTextStore,
    private val batchRanker: LocalSemanticBatchRanker? = null,
    private val pdfIndexSource: LocalDocumentIndexSource? = null,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Freezes one request's bounded retrieval result. Fails closed on any
     * supported-but-unreadable attachment; never substitutes text.
     */
    suspend fun prepare(
        requestId: String,
        query: String,
        attachments: List<AttachmentRecord>,
    ): LocalRagPreparationResult = withContext(workerDispatcher) {
        prepareOnWorker(requestId, query, attachments)
    }

    private suspend fun prepareOnWorker(
        requestId: String,
        query: String,
        attachments: List<AttachmentRecord>,
    ): LocalRagPreparationResult {
        val distinct = attachments.filter { it.localPath.isNotBlank() }.distinctBy { it.localPath }
        val supported = distinct.filter { LocalRagInputPolicy.isSupported(it.mimeType, it.name) || LocalDocumentKindPolicy.isOcrImage(it.mimeType, it.name) }
        if (supported.isEmpty()) {
            val firstUnsupported = distinct.firstOrNull {
                !LocalRagInputPolicy.isSupported(it.mimeType, it.name)
            }
            return if (firstUnsupported != null) {
                LocalRagPreparationResult.UnsupportedAttachment(firstUnsupported.name)
            } else {
                LocalRagPreparationResult.NoSupportedAttachment
            }
        }

        // Attachment -> typed extraction -> bounded chunks. Plain text keeps
        // the exact existing contract; PDF pages go through the derived
        // document index plus cheap page preselection.
        val parts = ArrayList<Pair<AttachmentRecord, List<LocalDocumentChunk>>>(supported.size)
        val pdfIndexesByDoc = HashMap<String, PdfDocumentIndex>()
        val sourceContentHashesByDoc = HashMap<String, String>()
        for (record in supported) {
            val docId = docIdFor(record.localPath)
            val chunks = when {
                LocalDocumentKindPolicy.isPdf(record.mimeType, record.name) || LocalDocumentKindPolicy.isOcrImage(record.mimeType, record.name) -> {
                    val repository = pdfIndexSource
                        ?: return LocalRagPreparationResult.DocumentUnusable(
                            record.name,
                            DocumentExtractionStatus.UNSUPPORTED,
                        )
                    when (val result = repository.obtain(record.localPath, record.mimeType)) {
                        is DocumentIndexResult.NotReady -> return LocalRagPreparationResult.DocumentUnusable(
                            record.name,
                            result.status,
                        )
                        is DocumentIndexResult.Ready -> {
                            pdfIndexesByDoc[docId] = result.index
                            pdfChunksFor(docId, record, result.index, query).map {
                                if (result.index.metadata.kind == LocalDocumentKind.OCR_IMAGE) it.copy(pageNumber = null) else it
                            }
                        }
                    }
                }
                else -> {
                    val text = textStore.readText(record.localPath, LocalRagBounds.MAX_DOC_CHARS)
                        ?: return LocalRagPreparationResult.TextUnavailable
                    if (text.isBlank()) continue
                    sourceContentHashesByDoc[docId] = LocalDocumentChunker.sha256Hex(text)
                    LocalDocumentChunker.chunk(docId, record.name, text)
                }
            }
            if (chunks.isNotEmpty()) parts += record to chunks
        }
        if (parts.isEmpty()) return LocalRagPreparationResult.NoSupportedAttachment

        // Deterministic doc identity from the stable attachment path.
        val attachmentIdByDoc = HashMap<String, String>()
        val chunkPool = ArrayList<LocalDocumentChunk>()
        for ((record, chunks) in parts) {
            val docId = docIdFor(record.localPath)
            attachmentIdByDoc[docId] = record.localPath
            chunkPool += chunks
        }
        if (chunkPool.isEmpty()) return LocalRagPreparationResult.NoSupportedAttachment

        val selections = if (batchRanker != null) {
            LocalRagRetrieval.retrieve(
                query = query,
                chunks = chunkPool,
                attachmentIdByDoc = attachmentIdByDoc,
                topK = LocalRagBounds.FINAL_TOP_K,
                batchRanker = batchRanker,
            )
        } else {
            LocalRagRetrieval.retrieve(
                query = query,
                chunks = chunkPool,
                attachmentIdByDoc = attachmentIdByDoc,
            )
        }
        if (selections.isEmpty()) return LocalRagPreparationResult.NoSupportedAttachment

        // Truthful coverage map: false when only representative excerpts of a
        // PDF were selected (the model must not claim full-document coverage).
        val coverageByDoc = computeCoverageByDoc(selections, pdfIndexesByDoc)

        // Bound the process-visible private-text ledger so the production
        // logging path can redact these exact excerpts if they ever appear
        // in diagnostics.
        com.projectnuke.fusion.privacy.DataEgressPrivateTextLedger.register(
            selections.map { it.source.excerpt },
        )

        return LocalRagPreparationResult.Prepared(
            selections = selections,
            snapshot = LocalRagSnapshotPolicy.snapshot(
                requestId,
                selections,
                sourceContentHashes = sourceContentHashesByDoc.filterKeys { docId ->
                    selections.any { it.chunk.docId == docId }
                },
            ),
            docDisplayNames = selections.map { it.source.displayName }.distinct(),
            coverageByDoc = coverageByDoc,
        )
    }

    /**
     * Fail-closed validation of a frozen RAG selection: the attachment must
     * still exist and still produce identical chunk text (content hash).
* PDFs are re-validated with BOUNDED selected-page re-extraction only —
     * never a full-document re-extraction. A disappeared/changed/
     * password-protected/unreadable source fails closed.
     */
    suspend fun validate(
        snapshot: LocalRagRequestSnapshot,
        attachments: List<AttachmentRecord>,
    ): Boolean = withContext(workerDispatcher) {
        validateOnWorker(snapshot, attachments)
    }

    private suspend fun validateOnWorker(
        snapshot: LocalRagRequestSnapshot,
        attachments: List<AttachmentRecord>,
    ): Boolean {
        if (snapshot.chunkKeys.isEmpty()) return true
        val chunks = ArrayList<LocalDocumentChunk>()
        val liveSourceContentHashes = HashMap<String, String>()
        for (record in attachments.filter { it.localPath.isNotBlank() }.distinctBy { it.localPath }) {
            val docId = docIdFor(record.localPath)
            if (!snapshot.docIds.contains(docId)) continue
            if (LocalDocumentKindPolicy.isPdf(record.mimeType, record.name) || LocalDocumentKindPolicy.isOcrImage(record.mimeType, record.name)) {
                val image = LocalDocumentKindPolicy.isOcrImage(record.mimeType, record.name)
                val pages = if (image) setOf(1) else snapshotPagesFor(docId, snapshot)
                if (pages.isEmpty()) return false
                val repository = pdfIndexSource ?: return false
                val live = repository.reextractPages(record.localPath, pages) ?: return false
                chunks += live.values.flatMap { page ->
                    LocalDocumentPageChunker.chunkPage(docId, record.name, page).map { if (image) it.copy(pageNumber = null) else it }
                }
            } else {
                val text = textStore.readText(record.localPath, LocalRagBounds.MAX_DOC_CHARS) ?: return false
                if (snapshot.sourceContentHashes.containsKey(docId)) {
                    liveSourceContentHashes[docId] = LocalDocumentChunker.sha256Hex(text)
                }
                chunks += LocalDocumentChunker.chunk(docId, record.name, text)
            }
        }
        return LocalRagSnapshotPolicy.validate(snapshot, chunks, liveSourceContentHashes)
    }

    internal fun docIdForTest(localPath: String): String = docIdFor(localPath)

    private fun docIdFor(localPath: String): String =
        "att-" + LocalDocumentChunker.sha256Hex(localPath).take(16)

    private fun pdfChunksFor(
        docId: String,
        record: AttachmentRecord,
        index: PdfDocumentIndex,
        query: String,
    ): List<LocalDocumentChunk> {
        // PdfPageCandidatePolicy returns selected pages in MEANINGFUL priority
        // (strong lexical hits + adjacency + weak hits), not physical order.
        // Preserve exactly that order when materializing pages into chunks:
        // under MAX_CHUNKS_PER_DOC a long earlier candidate must never consume
        // the per-document chunk budget before a strong-hit page is chunked.
        val orderedPageNumbers = PdfPageCandidatePolicy.selectPages(
            query,
            index,
            DocumentRetrievalIntentPolicy.classify(query),
        )
        if (orderedPageNumbers.isEmpty()) return emptyList()
        val byPageNumber = index.pages.associateBy { it.pageNumber }
        val orderedPages = orderedPageNumbers.mapNotNull(byPageNumber::get)
        if (orderedPages.isEmpty()) return emptyList()
        return LocalDocumentPageChunker.chunkPages(docId, record.name, orderedPages)
    }

    private fun snapshotPagesFor(docId: String, snapshot: LocalRagRequestSnapshot): Set<Int> {
        if (snapshot.pageNumbers.size != snapshot.chunkKeys.size) return emptySet()
        return buildSet {
            for (i in snapshot.chunkKeys.indices) {
                if (snapshot.chunkKeys[i].startsWith("$docId#")) {
                    snapshot.pageNumbers[i]?.let { add(it) }
                }
            }
        }
    }

    private fun computeCoverageByDoc(
        selections: List<LocalRagSelection>,
        pdfIndexesByDoc: Map<String, PdfDocumentIndex>,
    ): Map<String, Boolean> {
        // Chunk-precise coverage (C07): selections are already the final
        // prompt-included set (excerpt-budget bounded), so missing chunks
        // here mean the model was not shown that text.
        val selectedKeysByDoc = HashMap<String, MutableSet<String>>()
        for (selection in selections) {
            selectedKeysByDoc.getOrPut(selection.chunk.docId) { mutableSetOf() }
                .add("${selection.chunk.docId}#${selection.chunk.chunkIndex}")
        }
        return pdfIndexesByDoc.mapValues { (docId, index) ->
            DocumentRetrievalIntentPolicy.hasFullTextCoverage(
                index,
                docId,
                selectedKeysByDoc[docId].orEmpty(),
            )
        }
    }
}

/**
 * Production generation gate for frozen local RAG selections. Called after
 * RAG prepare/freeze and immediately before the excerpts are committed to
 * model generation, in both normal send and regeneration paths.
 *
 * Returns the frozen selections only when [validate] confirms the snapshot
 * still matches current attachment state; otherwise null so the caller
 * aborts with a bounded changed-source message instead of generating from
 * stale or substituted content. The suspend [validate] seam is the
 * production [LocalRagCoordinator.validate] bound by callers; tests inject
 * counting fakes to prove the gate is actually consulted.
 */
internal suspend fun selectRagForGeneration(
    prepared: LocalRagPreparationResult?,
    attachments: List<AttachmentRecord>,
    validate: suspend (LocalRagRequestSnapshot, List<AttachmentRecord>) -> Boolean,
): List<LocalRagSelection>? {
    val frozen = prepared as? LocalRagPreparationResult.Prepared ?: return null
    if (!validate(frozen.snapshot, attachments)) return null
    return frozen.selections
}

