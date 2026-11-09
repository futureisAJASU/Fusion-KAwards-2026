package com.projectnuke.fusion.retrieval

import java.io.File
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Derived local document-index cache (a disposable derived artifact, NOT a
 * database of user memory and NOT a source authority). The original managed
 * attachment remains canonical; the index only accelerates re-use.
 *
 * - Deterministic key: sha256(canonical managed path) -> 16 hex chars.
 * - Identity metadata: version, source size, source lastModified.
 * - Atomic writes: temp file -> validated complete index -> atomic replace.
 *   A cancelled/failed extraction never leaves a valid-looking partial index.
 * - Corruption recovery: malformed cache is treated as a miss and ignored;
 *   the canonical PDF is the only authority, so the next use rebuilds.
 * - Bounded budget: [DocumentIndexContract.MAX_CACHE_BUDGET_BYTES] with
 *   oldest-written eviction. Derived indexes may be deleted freely; the
 *   managed source is NEVER deleted by cache maintenance.
 * - No paths are logged anywhere; keys are content hashes.
 */
object DocumentIndexContract {
    /**
     * Derived document cache format version. Bumped to 2 when canonical
     * extracted PDF text semantics changed (NormalizedPdfText applied before
     * bounds/count/hash). Old-version caches become simple cache misses and
     * rebuild from the canonical source; old index JSON is never migrated.
     */
    const val DOCUMENT_INDEX_VERSION = 2
    const val MAX_CACHE_BUDGET_BYTES = 64L * 1024L * 1024L
    const val MAX_SINGLE_INDEX_BYTES = 8L * 1024L * 1024L
    const val STATUS_SIDECAR_FORMAT_VERSION = 1
    const val MAX_STATUS_SIDECAR_BYTES = 16L * 1024L
    const val KEY_LENGTH = 16

    fun keyFor(canonicalPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalPath.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(KEY_LENGTH)
    }
}

data class DocumentIndexMetadata(
    val version: Int,
    val kind: LocalDocumentKind,
    val pageCount: Int,
    val textPageCount: Int,
    val imageOnlyPageCount: Int,
    val imageElementCount: Int,
    val totalExtractedChars: Int,
    val sourceSizeBytes: Long,
    val sourceLastModifiedMillis: Long,
    val ocrSourceHash: String = "",
)

/** Metadata-only derived status. It deliberately has no page text or pages. */
data class DocumentIndexStatusSnapshot(
    val sidecarFormatVersion: Int,
    val documentIndexVersion: Int,
    val kind: LocalDocumentKind,
    val pageCount: Int,
    val textPageCount: Int,
    val imageOnlyPageCount: Int,
    val imageElementCount: Int,
    val totalExtractedChars: Int,
    val sourceSizeBytes: Long,
    val sourceLastModifiedMillis: Long,
    val indexFileSizeBytes: Long,
    val indexFileLastModifiedMillis: Long,
    val hasOcrText: Boolean = false,
) {
    val isReadablePdf: Boolean
        get() = kind == LocalDocumentKind.PDF_TEXT_LAYER &&
            pageCount > 0 && textPageCount > 0 && totalExtractedChars > 0
}

data class IndexedDocumentPage(
    val pageNumber: Int,
    val text: String,
    val textCharCount: Int,
    val contentHashHex: String,
    val hasImages: Boolean,
    val imageElementCount: Int,
    val ocrDerived: Boolean = false,
)

/** Source-derived normalized text, including explicitly recognized text.
 * No bitmaps or model-generated summaries are retained. */
data class PdfDocumentIndex(
    val metadata: DocumentIndexMetadata,
    val pages: List<IndexedDocumentPage>,
) {
    fun toExtractedLocalDocument(): ExtractedLocalDocument = ExtractedLocalDocument(
        kind = metadata.kind,
        pageCount = metadata.pageCount,
        textPageCount = metadata.textPageCount,
        imageOnlyPageCount = metadata.imageOnlyPageCount,
        imageElementCount = metadata.imageElementCount,
        totalExtractedChars = metadata.totalExtractedChars,
        pages = pages.map { page ->
            ExtractedDocumentPage(
                pageNumber = page.pageNumber,
                text = page.text,
                textCharCount = page.textCharCount,
                contentHashHex = page.contentHashHex,
                hasImages = page.hasImages,
                imageElementCount = page.imageElementCount,
                ocrDerived = page.ocrDerived,
            )
        },
    )
}

class DocumentIndexStore(
    private val root: File,
    internal val beforeIndexBoundedRead: () -> Unit = {},
    // Delete seam for budget eviction only. Defaults to production
    // File.delete(); tests script deterministic deletion failures instead of
    // relying on platform-dependent permission tricks.
    internal val budgetDeleteFile: (File) -> Boolean = { it.delete() },
    internal val stagingFileFactory: (canonicalTarget: File, kind: String) -> File = { _, kind ->
        File.createTempFile("$kind-", ".tmp", root)
    },
) {
    init {
        runCatching { root.mkdirs() }
    }

    fun indexFileFor(canonicalPath: String): File =
        File(root, DocumentIndexContract.keyFor(canonicalPath) + ".json")

    fun statusFileFor(canonicalPath: String): File =
        File(root, DocumentIndexContract.keyFor(canonicalPath) + ".status")

    /**
     * Loads a valid index for [canonicalPath] against live [source]. Returns
     * null on miss, staleness (size/mtime mismatch), version mismatch, or
     * corruption — the caller rebuilds from the canonical PDF. Never throws
     * for cache problems.
     */
    fun load(canonicalPath: String, source: File): PdfDocumentIndex? {
        if (!source.isFile) return null
        val file = indexFileFor(canonicalPath)
        if (!file.isFile) return null
        // Defect A: bounded UTF-8 read before JSON decode; oversized
        // derived cache files are treated as corrupt (derived, not source
        // authority) and safely discarded.
        val rawText = if (file.length() > DocumentIndexContract.MAX_SINGLE_INDEX_BYTES) {
            // Oversized derived index treated as corrupt; safely discard.
            runCatching {
                file.delete()
                invalidateStatus(canonicalPath)
            }
            return null
        } else {
            runCatching {
                beforeIndexBoundedRead()
                readBoundedUtf8(file, DocumentIndexContract.MAX_SINGLE_INDEX_BYTES.toInt())
            }.getOrElse {
                runCatching {
                    file.delete()
                    invalidateStatus(canonicalPath)
                }
                return null
            }
        }
        val decoded = runCatching {
            decode(JSONObject(rawText))
        }.getOrElse {
            // Malformed cache: ignore and let the caller rebuild. The cache
            // is never treated as source authority.
            runCatching {
                file.delete()
                invalidateStatus(canonicalPath)
            }
            return null
        }
        if (decoded.metadata.version != DocumentIndexContract.DOCUMENT_INDEX_VERSION) return null
        if (decoded.metadata.sourceSizeBytes != source.length()) return null
        if (decoded.metadata.sourceLastModifiedMillis != source.lastModified()) return null
        if (decoded.pages.any { it.ocrDerived } &&
            (decoded.metadata.ocrSourceHash.isBlank() || decoded.metadata.ocrSourceHash != AndroidLocalOcr.sourceHash(source))) return null
        // Hash re-verification: corrupted content must not reach RAG.
        for (page in decoded.pages) {
            val liveHash = LocalDocumentChunker.sha256Hex(page.text)
            if (!liveHash.equals(page.contentHashHex, ignoreCase = true)) {
                runCatching {
                    file.delete()
                    invalidateStatus(canonicalPath)
                }
                return null
            }
        }
        return decoded
    }

    /**
     * Atomic complete-index write. Never leaves a partial index behind:
     * temp write -> validated complete index -> atomic replacement. Any
     * failure deletes the temp file.
     */
    fun write(canonicalPath: String, source: File, index: PdfDocumentIndex): Boolean {
        // A status snapshot is valid only for the exact full index that is
        // about to be published. Invalidate it before replacing anything.
        invalidateStatus(canonicalPath)
        val json = runCatching { encode(index) }.getOrNull() ?: return false
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > DocumentIndexContract.MAX_SINGLE_INDEX_BYTES) return false
        val target = indexFileFor(canonicalPath)
        var ownedTemp: File? = null
        return runCatching {
            // Collision-safe per-write temp file in the same filesystem root.
            ownedTemp = stagingFileFactory(target, "index")
            target.parentFile?.mkdirs()
            val tmp = checkNotNull(ownedTemp)
            tmp.outputStream().use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            ownedTemp = null
            // The full index is authoritative for RAG. Publishing its tiny
            // UI snapshot is best effort; a failure leaves UI status unknown.
            writeStatus(canonicalPath, source, index.metadata)
            true
        }.onFailure { runCatching { ownedTemp?.delete() } }.getOrDefault(false)
    }

    fun delete(canonicalPath: String) {
        runCatching { indexFileFor(canonicalPath).delete() }
        invalidateStatus(canonicalPath)
    }

    /** Reads only the bounded metadata sidecar; never decodes the full index. */
    fun readStatus(canonicalPath: String, source: File): DocumentIndexStatusSnapshot? {
        if (canonicalPath.isBlank() || !source.isFile) return null
        val indexFile = indexFileFor(canonicalPath)
        val statusFile = statusFileFor(canonicalPath)
        if (!indexFile.isFile || !statusFile.isFile) return null
        val snapshot = runCatching {
            decodeStatus(JSONObject(readBoundedUtf8(statusFile, DocumentIndexContract.MAX_STATUS_SIDECAR_BYTES.toInt())))
        }.getOrNull() ?: return null
        if (snapshot.documentIndexVersion != DocumentIndexContract.DOCUMENT_INDEX_VERSION) return null
        if (snapshot.sourceSizeBytes != source.length()) return null
        if (snapshot.sourceLastModifiedMillis != source.lastModified()) return null
        if (snapshot.indexFileSizeBytes != indexFile.length()) return null
        if (snapshot.indexFileLastModifiedMillis != indexFile.lastModified()) return null
        return snapshot
    }

    private fun invalidateStatus(canonicalPath: String) {
        runCatching { statusFileFor(canonicalPath).delete() }
    }

    fun writeStatus(
        canonicalPath: String,
        source: File,
        metadata: DocumentIndexMetadata,
    ): Boolean {
        val indexFile = indexFileFor(canonicalPath)
        if (!indexFile.isFile || !source.isFile) return false
        val snapshot = DocumentIndexStatusSnapshot(
            sidecarFormatVersion = DocumentIndexContract.STATUS_SIDECAR_FORMAT_VERSION,
            documentIndexVersion = metadata.version,
            kind = metadata.kind,
            pageCount = metadata.pageCount,
            textPageCount = metadata.textPageCount,
            imageOnlyPageCount = metadata.imageOnlyPageCount,
            imageElementCount = metadata.imageElementCount,
            totalExtractedChars = metadata.totalExtractedChars,
            sourceSizeBytes = metadata.sourceSizeBytes,
            sourceLastModifiedMillis = metadata.sourceLastModifiedMillis,
            indexFileSizeBytes = indexFile.length(),
            indexFileLastModifiedMillis = indexFile.lastModified(),
            hasOcrText = metadata.ocrSourceHash.isNotEmpty(),
        )
        val bytes = runCatching { encodeStatus(snapshot).toString().toByteArray(Charsets.UTF_8) }
            .getOrNull() ?: return false
        if (bytes.size.toLong() > DocumentIndexContract.MAX_STATUS_SIDECAR_BYTES) return false
        val target = statusFileFor(canonicalPath)
        var ownedTemp: File? = null
        return runCatching {
            ownedTemp = stagingFileFactory(target, "status")
            target.parentFile?.mkdirs()
            val tmp = checkNotNull(ownedTemp)
            tmp.outputStream().use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            ownedTemp = null
            true
        }.onFailure { runCatching { ownedTemp?.delete() } }.getOrDefault(false)
    }

    /**
     * Deletes indexes whose physical managed source no longer exists.
     * [liveSources] is every physically-present managed source file (plus
     * registered pending copies). An index survives as long as the physical
     * source stays live anywhere (project/conversation sharing unaffected);
     * it is garbage only once the managed source itself is gone.
     */
    fun cleanupOrphans(liveSources: Set<File>) {
        val liveKeys = liveSources.mapNotNull { file ->
            runCatching { DocumentIndexContract.keyFor(file.canonicalPath) }.getOrNull()
        }.toSet()
        val files = root.listFiles()?.filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".status")) }
            ?: return
        val indexKeys = files.filter { it.name.endsWith(".json") }
            .map { it.name.removeSuffix(".json") }
            .toSet()
        for (file in files) {
            val suffix = if (file.name.endsWith(".json")) ".json" else ".status"
            val key = file.name.removeSuffix(suffix)
            if (key.length != DocumentIndexContract.KEY_LENGTH || key !in liveKeys) {
                runCatching { file.delete() }
            } else if (suffix == ".status" && key !in indexKeys) {
                runCatching { file.delete() }
            }
        }
    }

    /** Bounded total budget; evicts oldest-written first. Never touches the
     *  managed source files. Returns remaining bytes.
     *
     *  Only a SUCCESSFUL eviction is subtracted: if the index file still
     *  exists after the delete attempt its size stays counted, later
     *  candidates keep being tried, and the status sidecar of a surviving
     *  index is preserved. The returned value therefore never undercounts
     *  the surviving stored indexes — and may truthfully remain above
     *  [maxBytes] when every deletion fails. */
    fun enforceBudget(maxBytes: Long = DocumentIndexContract.MAX_CACHE_BUDGET_BYTES): Long {
        val files = root.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?: return 0
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return total
        val evictionOrder = files.sortedBy { it.lastModified() }
        for (file in evictionOrder) {
            if (total <= maxBytes) break
            val priorSize = file.length()
            deleteForBudget(file)
            // Existence is the ground truth, regardless of what the delete
            // reported or whether it threw: a surviving index keeps its bytes
            // in the accounting and keeps its status sidecar.
            if (file.exists()) continue
            total -= priorSize
            deleteForBudget(File(root, file.name.removeSuffix(".json") + ".status"))
        }
        return total
    }

    /** Budget-eviction delete. An ordinary failure only means "not evicted";
     *  a fatal [Error] is never converted into a harmless cache miss. */
    private fun deleteForBudget(file: File): Boolean = try {
        budgetDeleteFile(file)
    } catch (_: Exception) {
        false
    }

    /** Index files currently stored (budget/inspection). */
    fun storedIndexFiles(): List<File> =
        root.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.toList()
            ?: emptyList()

    private fun encode(index: PdfDocumentIndex): JSONObject {
        val pages = JSONArray()
        for (page in index.pages) {
            pages.put(
                JSONObject()
                    .put("n", page.pageNumber)
                    .put("t", page.text)
                    .put("c", page.textCharCount)
                    .put("h", page.contentHashHex)
                    .put("i", page.hasImages)
                    .put("e", page.imageElementCount)
                    .put("ocr", page.ocrDerived),
            )
        }
        return JSONObject()
            .put("v", index.metadata.version)
            .put("kind", index.metadata.kind.name)
            .put("pageCount", index.metadata.pageCount)
            .put("textPageCount", index.metadata.textPageCount)
            .put("imageOnlyPageCount", index.metadata.imageOnlyPageCount)
            .put("imageElementCount", index.metadata.imageElementCount)
            .put("totalExtractedChars", index.metadata.totalExtractedChars)
            .put("srcSize", index.metadata.sourceSizeBytes)
            .put("srcMtime", index.metadata.sourceLastModifiedMillis)
            .put("ocrSourceHash", index.metadata.ocrSourceHash)
            .put("pages", pages)
    }

    private fun decode(json: JSONObject): PdfDocumentIndex {
        val pagesArray = json.optJSONArray("pages") ?: JSONArray()
        val pages = buildList<IndexedDocumentPage> {
            for (i in 0 until pagesArray.length()) {
                val page = pagesArray.optJSONObject(i) ?: throw IllegalArgumentException("bad page")
                add(
                    IndexedDocumentPage(
                        pageNumber = page.optInt("n", 0),
                        text = page.optString("t", ""),
                        textCharCount = page.optInt("c", 0),
                        contentHashHex = page.optString("h", ""),
                        hasImages = page.optBoolean("i", false),
                        imageElementCount = page.optInt("e", 0),
                        ocrDerived = page.optBoolean("ocr", false),
                    ),
                )
            }
        }
        val metadata = DocumentIndexMetadata(
            version = json.optInt("v", -1),
            kind = runCatching {
                LocalDocumentKind.valueOf(json.optString("kind", LocalDocumentKind.PLAIN_TEXT.name))
            }.getOrDefault(LocalDocumentKind.PLAIN_TEXT),
            pageCount = json.optInt("pageCount", 0),
            textPageCount = json.optInt("textPageCount", 0),
            imageOnlyPageCount = json.optInt("imageOnlyPageCount", 0),
            imageElementCount = json.optInt("imageElementCount", 0),
            totalExtractedChars = json.optInt("totalExtractedChars", 0),
            sourceSizeBytes = json.optLong("srcSize", -1L),
            sourceLastModifiedMillis = json.optLong("srcMtime", -1L),
            ocrSourceHash = json.optString("ocrSourceHash", ""),
        )
        check(metadata.version > 0)
        check(metadata.pageCount >= 0)
        check(metadata.sourceSizeBytes >= 0)
        check(metadata.sourceLastModifiedMillis >= 0)
        check(pages.all { it.pageNumber in 1..(metadata.pageCount.coerceAtLeast(1)) })
        check(pages.all { it.textCharCount == it.text.length })
        return PdfDocumentIndex(metadata, pages)
    }

    private fun encodeStatus(snapshot: DocumentIndexStatusSnapshot): JSONObject = JSONObject()
        .put("sv", snapshot.sidecarFormatVersion)
        .put("v", snapshot.documentIndexVersion)
        .put("kind", snapshot.kind.name)
        .put("pageCount", snapshot.pageCount)
        .put("textPageCount", snapshot.textPageCount)
        .put("imageOnlyPageCount", snapshot.imageOnlyPageCount)
        .put("imageElementCount", snapshot.imageElementCount)
        .put("totalExtractedChars", snapshot.totalExtractedChars)
        .put("srcSize", snapshot.sourceSizeBytes)
        .put("srcMtime", snapshot.sourceLastModifiedMillis)
        .put("indexSize", snapshot.indexFileSizeBytes)
        .put("indexMtime", snapshot.indexFileLastModifiedMillis)
        .put("hasOcrText", snapshot.hasOcrText)

    private fun decodeStatus(json: JSONObject): DocumentIndexStatusSnapshot {
        val snapshot = DocumentIndexStatusSnapshot(
            sidecarFormatVersion = json.getInt("sv"),
            documentIndexVersion = json.getInt("v"),
            kind = LocalDocumentKind.valueOf(json.getString("kind")),
            pageCount = json.getInt("pageCount"),
            textPageCount = json.getInt("textPageCount"),
            imageOnlyPageCount = json.getInt("imageOnlyPageCount"),
            imageElementCount = json.getInt("imageElementCount"),
            totalExtractedChars = json.getInt("totalExtractedChars"),
            sourceSizeBytes = json.getLong("srcSize"),
            sourceLastModifiedMillis = json.getLong("srcMtime"),
            indexFileSizeBytes = json.getLong("indexSize"),
            indexFileLastModifiedMillis = json.getLong("indexMtime"),
            hasOcrText = json.optBoolean("hasOcrText", false),
        )
        check(snapshot.sidecarFormatVersion == DocumentIndexContract.STATUS_SIDECAR_FORMAT_VERSION)
        check(snapshot.documentIndexVersion == DocumentIndexContract.DOCUMENT_INDEX_VERSION)
        check(snapshot.pageCount in 0..PdfDocumentBounds.MAX_PDF_PAGES)
        check(snapshot.textPageCount in 0..snapshot.pageCount)
        check(snapshot.imageOnlyPageCount in 0..snapshot.pageCount)
        check(snapshot.imageElementCount >= 0)
        check(snapshot.totalExtractedChars in 0..PdfDocumentBounds.MAX_PDF_EXTRACTED_CHARS)
        check(snapshot.sourceSizeBytes >= 0)
        check(snapshot.sourceLastModifiedMillis >= 0)
        check(snapshot.indexFileSizeBytes in 1..DocumentIndexContract.MAX_SINGLE_INDEX_BYTES)
        check(snapshot.indexFileLastModifiedMillis >= 0)
        return snapshot
    }

    private fun readBoundedUtf8(file: File, maxBytes: Int): String {
        val bytes = ByteArray(maxBytes + 1)
        var count = 0
        BufferedInputStream(file.inputStream()).use { input ->
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
        }
        check(count <= maxBytes) { "bounded UTF-8 read overflow (max $maxBytes bytes)" }
        return bytes.copyOf(count).toString(Charsets.UTF_8)
    }
}
