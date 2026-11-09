package com.projectnuke.fusion.retrieval

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

// Phase C matrix: derived document index cache. Identity, staleness,
// atomicity, budget, orphan cleanup, single-flight concurrency.
class DocumentIndexStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("fusion-index-test").toFile()

    private fun writeTextFile(dir: File, name: String, content: String): File {
        val file = File(dir, name)
        file.writeText(content)
        return file
    }

    private fun indexFor(source: File, pages: List<Pair<Int, String>>): PdfDocumentIndex {
        val pageCount = pages.maxOfOrNull { it.first } ?: 0
        return PdfDocumentIndex(
            metadata = DocumentIndexMetadata(
                version = DocumentIndexContract.DOCUMENT_INDEX_VERSION,
                kind = LocalDocumentKind.PDF_TEXT_LAYER,
                pageCount = pageCount,
                textPageCount = pages.size,
                imageOnlyPageCount = 0,
                imageElementCount = 0,
                totalExtractedChars = pages.sumOf { it.second.length },
                sourceSizeBytes = source.length(),
                sourceLastModifiedMillis = source.lastModified(),
            ),
            pages = pages.map { (n, text) ->
                IndexedDocumentPage(
                    pageNumber = n,
                    text = text,
                    textCharCount = text.length,
                    contentHashHex = LocalDocumentChunker.sha256Hex(text),
                    hasImages = false,
                    imageElementCount = 0,
                )
            },
        )
    }

    @Test
    fun `initial build and cache hit`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "paper.pdf", "pdf content")
        val store = DocumentIndexStore(File(dir, "index"))
        var builds = 0
        val extractor = object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
                builds++
                return DocumentExtractionResult.Ready(
                    PdfTextLayerExtractorTestShared.doc(listOf(1 to "한 페이지 내용")),
                )
            }
        }
        val repository = PdfDocumentIndexRepository(store, extractor)
        val first = repository.obtain(source.canonicalPath, "application/pdf")
        assertTrue(first is DocumentIndexResult.Ready)
        val second = repository.obtain(source.canonicalPath, "application/pdf")
        assertTrue(second is DocumentIndexResult.Ready)
        assertEquals(1, builds)
        assertEquals(1, store.storedIndexFiles().size)
        assertEquals(
            1,
            ((second as DocumentIndexResult.Ready).index.pages.first()).pageNumber,
        )
        val snapshot = repository.status(source.canonicalPath, "application/pdf")
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.pageCount)
        assertEquals(1, snapshot.textPageCount)
        assertTrue(store.statusFileFor(source.canonicalPath).length() <= DocumentIndexContract.MAX_STATUS_SIDECAR_BYTES)
        assertFalse(store.statusFileFor(source.canonicalPath).readText().contains("한 페이지 내용"))
        assertFalse(store.statusFileFor(source.canonicalPath).readText().contains("pages"))
    }

    @Test
    fun `legacy current index without sidecar validates once and creates metadata only sidecar`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "legacy.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        val index = indexFor(source, listOf(1 to "legacy page text"))
        assertTrue(store.write(source.canonicalPath, source, index))
        assertTrue(store.statusFileFor(source.canonicalPath).delete())
        var extractorCalls = 0
        val repository = PdfDocumentIndexRepository(
            store,
            object : LocalDocumentExtractor {
                override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
                    extractorCalls++
                    throw AssertionError("UI status must not invoke extraction")
                }
            },
        )

        val migrated = repository.status(source.canonicalPath, "application/pdf")
        assertNotNull(migrated)
        assertEquals(1, migrated!!.pageCount)
        assertEquals(0, extractorCalls)
        assertTrue(store.statusFileFor(source.canonicalPath).isFile)
        // The next status hit is still sidecar-only; authoritative RAG
        // validation remains separate in DocumentIndexStore.load().
        assertNotNull(repository.status(source.canonicalPath, "application/pdf"))
    }

    @Test
    fun `concurrent legacy status requests converge through one sidecar`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "concurrent-legacy.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        assertTrue(store.write(source.canonicalPath, source, indexFor(source, listOf(1 to "page"))))
        assertTrue(store.statusFileFor(source.canonicalPath).delete())
        val repository = PdfDocumentIndexRepository(store, object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult =
                throw AssertionError("legacy status must not extract")
        })

        val results = coroutineScope {
            (0..3).map { async { repository.status(source.canonicalPath, "application/pdf") } }.awaitAll()
        }
        assertEquals(4, results.size)
        assertTrue(results.all { it?.isReadablePdf == true })
        assertTrue(store.statusFileFor(source.canonicalPath).isFile)
    }

    @Test
    fun `invalid legacy index never creates sidecar or changes canonical source`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "invalid-legacy.pdf", "canonical bytes")
        val before = source.readBytes()
        val store = DocumentIndexStore(File(dir, "index"))
        assertTrue(store.write(source.canonicalPath, source, indexFor(source, listOf(1 to "page"))))
        assertTrue(store.statusFileFor(source.canonicalPath).delete())
        store.indexFileFor(source.canonicalPath).writeText("not json")
        val repository = PdfDocumentIndexRepository(store, object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult =
                throw AssertionError("invalid legacy status must not rebuild")
        })

        assertNull(repository.status(source.canonicalPath, "application/pdf"))
        assertFalse(store.statusFileFor(source.canonicalPath).exists())
        assertTrue(source.readBytes().contentEquals(before))
    }

    @Test
    fun `malformed wrong-version and source-mismatched sidecars fail closed`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "status.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        assertTrue(store.write(source.canonicalPath, source, indexFor(source, listOf(1 to "page"))))
        val statusFile = store.statusFileFor(source.canonicalPath)
        val repository = PdfDocumentIndexRepository(store, object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult =
                throw AssertionError("invalid sidecar must not invoke extraction")
        })

        statusFile.writeText("{broken")
        assertNull(repository.status(source.canonicalPath, "application/pdf"))
        assertTrue(statusFile.isFile)

        statusFile.writeBytes(ByteArray(DocumentIndexContract.MAX_STATUS_SIDECAR_BYTES.toInt() + 1) { 'x'.code.toByte() })
        assertNull(repository.status(source.canonicalPath, "application/pdf"))

        assertTrue(store.write(source.canonicalPath, source, indexFor(source, listOf(1 to "page"))))
        val wrongVersion = JSONObject(statusFile.readText())
            .put("sv", DocumentIndexContract.STATUS_SIDECAR_FORMAT_VERSION + 1)
        statusFile.writeText(wrongVersion.toString())
        assertNull(repository.status(source.canonicalPath, "application/pdf"))

        assertTrue(store.write(source.canonicalPath, source, indexFor(source, listOf(1 to "page"))))
        source.writeText("a longer source")
        assertNull(repository.status(source.canonicalPath, "application/pdf"))

        assertTrue(store.write(source.canonicalPath, source, indexFor(source, listOf(1 to "page"))))
        source.setLastModified(source.lastModified() + 10_000)
        assertNull(repository.status(source.canonicalPath, "application/pdf"))
    }

    @Test
    fun `missing full index and sidecar publication failure are never ready`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "missing.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        val index = indexFor(source, listOf(1 to "page"))
        assertTrue(store.write(source.canonicalPath, source, index))
        assertTrue(store.indexFileFor(source.canonicalPath).delete())
        assertNull(store.readStatus(source.canonicalPath, source))

        val source2 = writeTextFile(dir, "sidecar-failure.pdf", "content")
        val store2 = DocumentIndexStore(File(dir, "index-failure"))
        assertTrue(store2.statusFileFor(source2.canonicalPath).mkdirs())
        File(store2.statusFileFor(source2.canonicalPath), "block").writeText("x")
        assertTrue(store2.write(source2.canonicalPath, source2, indexFor(source2, listOf(1 to "page"))))
        assertTrue(store2.indexFileFor(source2.canonicalPath).isFile)
        assertNull(store2.readStatus(source2.canonicalPath, source2))
    }

    @Test
    fun `typed extraction failure is cached as not ready without artifact`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "locked.pdf", "pdf content")
        val store = DocumentIndexStore(File(dir, "index"))
        var builds = 0
        val extractor = object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
                builds++
                return DocumentExtractionResult.NotReady(DocumentExtractionStatus.PASSWORD_PROTECTED)
            }
        }
        val repository = PdfDocumentIndexRepository(store, extractor)
        val result = repository.obtain(source.canonicalPath, "application/pdf")
        assertEquals(
            DocumentExtractionStatus.PASSWORD_PROTECTED,
            (result as DocumentIndexResult.NotReady).status,
        )
        assertEquals(0, store.storedIndexFiles().size)
    }

    @Test
    fun `source size change invalidates cache`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "paper.pdf", "original")
        val store = DocumentIndexStore(File(dir, "index"))
        var builds = 0
        val extractor = object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
                builds++
                val text = File(path).readText()
                return DocumentExtractionResult.Ready(
                    PdfTextLayerExtractorTestShared.doc(listOf(1 to text)),
                )
            }
        }
        val repository = PdfDocumentIndexRepository(store, extractor)
        repository.obtain(source.canonicalPath, "application/pdf")
        assertEquals(1, builds)
        source.writeText("changed content that is longer")
        repository.obtain(source.canonicalPath, "application/pdf")
        assertEquals(2, builds)
    }

    @Test
    fun `source mtime change invalidates cache`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "paper.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        var builds = 0
        val extractor = object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
                builds++
                return DocumentExtractionResult.Ready(
                    PdfTextLayerExtractorTestShared.doc(listOf(1 to "내용")),
                )
            }
        }
        val repository = PdfDocumentIndexRepository(store, extractor)
        repository.obtain(source.canonicalPath, "application/pdf")
        assertEquals(1, builds)
        source.setLastModified(source.lastModified() + 10_000)
        repository.obtain(source.canonicalPath, "application/pdf")
        assertEquals(2, builds)
    }

    @Test
    fun `malformed cache rebuilds from canonical source`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "paper.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        val initial = indexFor(source, listOf(1 to "한 페이지"))
        assertTrue(store.write(source.canonicalPath, source, initial))
        // Corrupt the stored index.
        store.indexFileFor(source.canonicalPath).writeText("{{{ not json")
        assertNull(store.load(source.canonicalPath, source))
        var builds = 0
        val extractor = object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
                builds++
                return DocumentExtractionResult.Ready(
                    PdfTextLayerExtractorTestShared.doc(listOf(1 to "한 페이지")),
                )
            }
        }
        val repository = PdfDocumentIndexRepository(store, extractor)
        val result = repository.obtain(source.canonicalPath, "application/pdf")
        assertTrue(result is DocumentIndexResult.Ready)
        assertEquals(1, builds)
    }

    @Test
    fun `corrupted page text hash fails load`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "paper.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        val index = indexFor(source, listOf(1 to "정상 텍스트"))
        assertTrue(store.write(source.canonicalPath, source, index))
        // Tamper with content without touching metadata.
        val file = store.indexFileFor(source.canonicalPath)
        val raw = file.readText().replace("정상 텍스트", "변조 텍스트")
        file.writeText(raw)
        assertNull(store.load(source.canonicalPath, source))
        assertFalse(store.statusFileFor(source.canonicalPath).exists())
        // Store drops the corrupt artifact and rebuild is clean.
        var builds = 0
        val extractor = object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
                builds++
                return DocumentExtractionResult.Ready(
                    PdfTextLayerExtractorTestShared.doc(listOf(1 to "정상 텍스트")),
                )
            }
        }
        val repository = PdfDocumentIndexRepository(store, extractor)
        assertTrue(repository.obtain(source.canonicalPath, "application/pdf") is DocumentIndexResult.Ready)
        assertEquals(1, builds)
    }

    @Test
    fun `cancelled extraction leaves no valid partial index`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "paper.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        val extractor = object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
                throw kotlinx.coroutines.CancellationException("cancelled build")
            }
        }
        val repository = PdfDocumentIndexRepository(store, extractor)
        var cancelled = false
        try {
            repository.obtain(source.canonicalPath, "application/pdf")
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertFalse(store.indexFileFor(source.canonicalPath).exists())
        assertFalse(File(store.indexFileFor(source.canonicalPath).path + ".tmp").exists())
        // A later request rebuilds cleanly.
        val okExtractor = object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult =
                DocumentExtractionResult.Ready(PdfTextLayerExtractorTestShared.doc(listOf(1 to "내용")))
        }
        val repository2 = PdfDocumentIndexRepository(store, okExtractor)
        assertTrue(repository2.obtain(source.canonicalPath, "application/pdf") is DocumentIndexResult.Ready)
    }

    @Test
    fun `duplicate concurrent index request builds once`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "paper.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        var startedBuilds = 0
        val extractor = object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
                startedBuilds++
                kotlinx.coroutines.delay(50)
                return DocumentExtractionResult.Ready(
                    PdfTextLayerExtractorTestShared.doc(listOf(1 to "동시 요청 내용")),
                )
            }
        }
        val repository = PdfDocumentIndexRepository(store, extractor)
        val results = coroutineScope {
            (0..2).map {
                async { repository.obtain(source.canonicalPath, "application/pdf") }
            }.awaitAll()
        }
        assertEquals(3, results.size)
        assertTrue(results.all { it is DocumentIndexResult.Ready })
        assertEquals(1, startedBuilds)
        assertEquals(1, store.storedIndexFiles().size)
    }

    @Test
    fun `cache budget trimming deletes indexes never sources`() = runTest {
        val dir = tempDir()
        val sourceA = writeTextFile(dir, "a.pdf", "content a")
        val sourceB = writeTextFile(dir, "b.pdf", "content b")
        val store = DocumentIndexStore(File(dir, "index"))
        assertTrue(store.write(sourceA.canonicalPath, sourceA, indexFor(sourceA, listOf(1 to "A 내용"))))
        assertTrue(store.write(sourceB.canonicalPath, sourceB, indexFor(sourceB, listOf(1 to "B 내용"))))
        store.enforceBudget(maxBytes = 1)
        assertEquals(0, store.storedIndexFiles().size)
        assertFalse(store.statusFileFor(sourceA.canonicalPath).exists())
        assertFalse(store.statusFileFor(sourceB.canonicalPath).exists())
        // Sources are untouched by budget maintenance.
        assertTrue(sourceA.exists())
        assertTrue(sourceB.exists())
    }

    @Test
    fun `stale index format version invalidates`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "paper.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        val index = indexFor(source, listOf(1 to "내용"))
        assertTrue(store.write(source.canonicalPath, source, index))
        // Explicit stale-version fixture: one version behind the CURRENT
        // contract (a pre-normalization v1 cache under a v2 contract).
        val file = store.indexFileFor(source.canonicalPath)
        val current = DocumentIndexContract.DOCUMENT_INDEX_VERSION
        val stale = current - 1
        file.writeText(file.readText().replace("\"v\":$current", "\"v\":$stale"))
        assertNull(store.load(source.canonicalPath, source))
        // Rebuild under the CURRENT contract produces a loadable v2 index.
        var builds = 0
        val extractor = object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
                builds++
                return DocumentExtractionResult.Ready(
                    PdfTextLayerExtractorTestShared.doc(listOf(1 to "정상 텍스트")),
                )
            }
        }
        val repository = PdfDocumentIndexRepository(store, extractor)
        val result = repository.obtain(source.canonicalPath, "application/pdf")
        assertTrue(result is DocumentIndexResult.Ready)
        assertEquals(current, (result as DocumentIndexResult.Ready).index.metadata.version)
        assertEquals(1, builds)
    }

    @Test
    fun `version mismatch never reaches rag and source stays untouched`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "paper.pdf", "source-bytes-unchanged")
        val sourceBytesBefore = source.readBytes()
        val store = DocumentIndexStore(File(dir, "index"))
        val index = indexFor(source, listOf(1 to "이전 추출 의미"))
        assertTrue(store.write(source.canonicalPath, source, index))
        // Degrade the stored index to the previous extraction-semantics version.
        val file = store.indexFileFor(source.canonicalPath)
        val current = DocumentIndexContract.DOCUMENT_INDEX_VERSION
        file.writeText(file.readText().replace("\"v\":$current", "\"v\":${current - 1}"))

        // The repository never hands the stale index to RAG: it rebuilds under
        // the current extraction contract (normalized text/hash) instead.
        var extracted: String? = null
        val extractor = object : LocalDocumentExtractor {
            override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
                extracted = "정상 텍스트"
                return DocumentExtractionResult.Ready(
                    PdfTextLayerExtractorTestShared.doc(listOf(1 to "정상 텍스트")),
                )
            }
        }
        val repository = PdfDocumentIndexRepository(store, extractor)
        val result = repository.obtain(source.canonicalPath, "application/pdf")
        assertTrue(result is DocumentIndexResult.Ready)
        val ready = result as DocumentIndexResult.Ready
        assertEquals(current, ready.index.metadata.version)
        // Rebuilt content/hash reflect the CURRENT extraction output, never
        // the stale index payload.
        assertEquals("정상 텍스트", ready.index.pages.single().text)
        assertEquals(extracted, ready.index.pages.single().text)
        assertEquals(
            LocalDocumentChunker.sha256Hex("정상 텍스트"),
            ready.index.pages.single().contentHashHex,
        )
        // The canonical managed source is never modified by cache maintenance.
        assertTrue(source.sourceBytesEqual(sourceBytesBefore))
    }

    private fun File.sourceBytesEqual(before: ByteArray): Boolean =
        readBytes().contentEquals(before)

    @Test
    fun `orphan cleanup keeps live shared source index`() = runTest {
        val dir = tempDir()
        val liveSource = writeTextFile(dir, "live.pdf", "live")
        val deadSource = writeTextFile(dir, "dead.pdf", "dead")
        val store = DocumentIndexStore(File(dir, "index"))
        assertTrue(store.write(liveSource.canonicalPath, liveSource, indexFor(liveSource, listOf(1 to "live 내용"))))
        assertTrue(store.write(deadSource.canonicalPath, deadSource, indexFor(deadSource, listOf(1 to "dead 내용"))))
        deadSource.delete()
        store.cleanupOrphans(liveSources = setOf(liveSource))
        val remaining = store.storedIndexFiles()
        assertEquals(1, remaining.size)
        assertEquals(
            DocumentIndexContract.keyFor(liveSource.canonicalPath) + ".json",
            remaining.single().name,
        )
        // Live source + its index stay usable.
        assertNotNull(store.load(liveSource.canonicalPath, liveSource))
    }

    @Test
    fun `orphan cleanup removes orphan status sidecar`() = runTest {
        val dir = tempDir()
        val liveSource = writeTextFile(dir, "live.pdf", "live")
        val deadSource = writeTextFile(dir, "dead.pdf", "dead")
        val store = DocumentIndexStore(File(dir, "index"))
        assertTrue(store.write(liveSource.canonicalPath, liveSource, indexFor(liveSource, listOf(1 to "live"))))
        assertTrue(store.write(deadSource.canonicalPath, deadSource, indexFor(deadSource, listOf(1 to "dead"))))
        val deadStatus = store.statusFileFor(deadSource.canonicalPath)
        deadSource.delete()
        store.cleanupOrphans(setOf(liveSource))
        assertFalse(deadStatus.exists())
        assertTrue(store.statusFileFor(liveSource.canonicalPath).exists())
    }

    @Test
    fun `key is deterministic path hash with no path leakage`() = runTest {
        val a = "/data/attachments/A.pdf"
        val b = "/data/attachments/B.pdf"
        assertEquals(DocumentIndexContract.keyFor(a), DocumentIndexContract.keyFor(a))
        assertFalse(DocumentIndexContract.keyFor(a) == DocumentIndexContract.keyFor(b))
        val key = DocumentIndexContract.keyFor(a)
        assertFalse(key.contains("A.pdf"))
        assertFalse(key.contains("data"))
        assertEquals(DocumentIndexContract.KEY_LENGTH, key.length)
    }

    @Test
    fun `index round trips through store with identical content`() {
        val dir = tempDir()
        val source = writeTextFile(dir, "paper.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        val original = indexFor(source, listOf(1 to "첫 페이지", 2 to "둘째 페이지"))
        assertTrue(store.write(source.canonicalPath, source, original))
        val loaded = store.load(source.canonicalPath, source)
        assertNotNull(loaded)
        assertEquals(2, loaded!!.pages.size)
        assertEquals(listOf(1, 2), loaded.pages.map { it.pageNumber })
        assertEquals(listOf("첫 페이지", "둘째 페이지"), loaded.pages.map { it.text })
        assertEquals(original.metadata, loaded.metadata)
    }

    @Test
    fun `oversized full index rejected before decode and cleaned up`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "oversized.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        val indexFile = store.indexFileFor(source.canonicalPath)
        // Write an oversized index that exceeds MAX_SINGLE_INDEX_BYTES.
        val oversizedPayload = "x".repeat(DocumentIndexContract.MAX_SINGLE_INDEX_BYTES.toInt() + 1)
        indexFile.writeText(oversizedPayload)
        assertNull(store.load(source.canonicalPath, source))
        assertFalse(indexFile.exists())
        assertFalse(store.statusFileFor(source.canonicalPath).exists())
    }

    @Test
    fun `valid bounded index loads correctly`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "valid.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        val index = indexFor(source, listOf(1 to "내용"))
        assertTrue(store.write(source.canonicalPath, source, index))
        val loaded = store.load(source.canonicalPath, source)
        assertNotNull(loaded)
        assertEquals(1, loaded!!.pages.size)
        assertEquals("내용", loaded.pages[0].text)
    }

    @Test
    fun `malformed bounded JSON behaves as cache miss`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "bad.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        val indexFile = store.indexFileFor(source.canonicalPath)
        indexFile.writeText("not-json")
        assertNull(store.load(source.canonicalPath, source))
        assertFalse(indexFile.exists())
    }

    @Test
    fun `status bounded reader remains intact`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "status.pdf", "content")
        val store = DocumentIndexStore(File(dir, "index"))
        val index = indexFor(source, listOf(1 to "페이지"))
        assertTrue(store.write(source.canonicalPath, source, index))
        val snapshot = store.readStatus(source.canonicalPath, source)
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.pageCount)
    }

    @Test
    fun `two staging allocations for same index are distinct`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "staging.pdf", "content")
        val staged = mutableListOf<Pair<String, File>>()
        val store = DocumentIndexStore(File(dir, "index")) { target, kind ->
            File(target.parentFile, "$kind-${staged.size}.tmp").also { staged += kind to it }
        }
        val index = indexFor(source, listOf(1 to "내용"))
        assertTrue(store.write(source.canonicalPath, source, index))
        assertTrue(store.write(source.canonicalPath, source, index))
        val indexPaths = staged.filter { it.first == "index" }.map { it.second }
        assertEquals(2, indexPaths.size)
        assertEquals(2, indexPaths.distinct().size)
        assertTrue(indexPaths.none { it.exists() })
    }

    @Test
    fun `two staging allocations for status are distinct`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "status-staging.pdf", "content")
        val staged = mutableListOf<Pair<String, File>>()
        val store = DocumentIndexStore(File(dir, "index")) { target, kind ->
            File(target.parentFile, "$kind-${staged.size}.tmp").also { staged += kind to it }
        }
        val index = indexFor(source, listOf(1 to "내용"))
        assertTrue(store.write(source.canonicalPath, source, index))
        assertTrue(store.writeStatus(source.canonicalPath, source, index.metadata))
        val statusPaths = staged.filter { it.first == "status" }.map { it.second }
        assertEquals(2, statusPaths.size)
        assertEquals(2, statusPaths.distinct().size)
        assertTrue(statusPaths.none { it.exists() })
    }

    @Test
    fun `concurrent same-key writes do not corrupt final readable index`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "concurrent.pdf", "content")
        val staged = mutableListOf<Pair<String, File>>()
        val nextName = AtomicInteger()
        val indexReady = CountDownLatch(2)
        val store = DocumentIndexStore(File(dir, "index")) { target, kind ->
            val path = File(target.parentFile, "$kind-${nextName.getAndIncrement()}.tmp")
            synchronized(staged) { staged += kind to path }
            if (kind == "index") {
                indexReady.countDown()
                check(indexReady.await(5, TimeUnit.SECONDS)) { "concurrent staging did not converge" }
            }
            path
        }
        val index = indexFor(source, listOf(1 to "동시"))
        val results = coroutineScope {
            (0..1).map {
                async(Dispatchers.Default) {
                    store.write(source.canonicalPath, source, index)
                }
            }.awaitAll()
        }
        // A platform-specific replacement race may fail-soft one writer;
        // the published canonical target must still be readable.
        assertTrue(results.any { it })
        val loaded = store.load(source.canonicalPath, source)
        assertNotNull(loaded)
        assertEquals(1, loaded!!.pages.size)
        assertEquals("동시", loaded.pages[0].text)
        val indexPaths = synchronized(staged) {
            staged.filter { it.first == "index" }.map { it.second }
        }
        assertEquals(2, indexPaths.size)
        assertEquals(2, indexPaths.distinct().size)
    }

    @Test
    fun `failed write cleans its own temp file without deleting other staging`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "fail.pdf", "content")
        val root = File(dir, "index")
        val store = DocumentIndexStore(root)
        val index = indexFor(source, listOf(1 to "내용"))
        assertTrue(store.write(source.canonicalPath, source, index))
        val indexFile = store.indexFileFor(source.canonicalPath)
        val targetBefore = indexFile.readBytes()
        val writerATemp = File(root, "writer-a.tmp").also { it.mkdirs() }
        val writerBTemp = File(root, "writer-b.tmp").also { it.writeText("writer B owns this") }
        val failingStore = DocumentIndexStore(root) { _, kind ->
            assertEquals("index", kind)
            writerATemp
        }
        assertFalse(failingStore.write(source.canonicalPath, source, index))
        assertFalse(writerATemp.exists())
        assertTrue(writerBTemp.exists())
        assertTrue(targetBefore.contentEquals(indexFile.readBytes()))
    }

    @Test
    fun `index temp creation failure is fail soft and preserves target`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "index-create-fail.pdf", "content")
        val root = File(dir, "index")
        val baseline = DocumentIndexStore(root)
        val index = indexFor(source, listOf(1 to "기존"))
        assertTrue(baseline.write(source.canonicalPath, source, index))
        val target = baseline.indexFileFor(source.canonicalPath)
        val before = target.readBytes()
        val failingStore = DocumentIndexStore(root) { _, _ ->
            throw IOException("staging unavailable")
        }
        assertFalse(failingStore.write(source.canonicalPath, source, index))
        assertTrue(before.contentEquals(target.readBytes()))
    }

    @Test
    fun `status temp creation failure is fail soft`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "status-create-fail.pdf", "content")
        val root = File(dir, "index")
        val baseline = DocumentIndexStore(root)
        val index = indexFor(source, listOf(1 to "내용"))
        assertTrue(baseline.write(source.canonicalPath, source, index))
        val status = baseline.statusFileFor(source.canonicalPath)
        val before = status.readBytes()
        val failingStore = DocumentIndexStore(root) { _, _ ->
            throw IOException("status staging unavailable")
        }
        assertFalse(failingStore.writeStatus(source.canonicalPath, source, index.metadata))
        assertTrue(before.contentEquals(status.readBytes()))
    }

    @Test
    fun `bounded read overflow deletes index and invalidates status`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "overflow.pdf", "content")
        val root = File(dir, "index")
        val baseline = DocumentIndexStore(root)
        val index = indexFor(source, listOf(1 to "내용"))
        assertTrue(baseline.write(source.canonicalPath, source, index))
        val indexFile = baseline.indexFileFor(source.canonicalPath)
        val overflowStore = DocumentIndexStore(
            root,
            beforeIndexBoundedRead = {
                indexFile.appendText("x".repeat(DocumentIndexContract.MAX_SINGLE_INDEX_BYTES.toInt() + 1))
            },
        )
        assertNull(overflowStore.load(source.canonicalPath, source))
        assertFalse(indexFile.exists())
        assertFalse(baseline.statusFileFor(source.canonicalPath).exists())
    }

    @Test
    fun `successful writes leave no owned temp files`() = runTest {
        val dir = tempDir()
        val source = writeTextFile(dir, "success.pdf", "content")
        val staged = mutableListOf<File>()
        val store = DocumentIndexStore(File(dir, "index")) { target, kind ->
            File(target.parentFile, "$kind-${staged.size}.tmp").also { staged += it }
        }
        val index = indexFor(source, listOf(1 to "내용"))
        assertTrue(store.write(source.canonicalPath, source, index))
        assertTrue(staged.isNotEmpty())
        assertTrue(staged.none { it.exists() })
    }

    // ---- Phase 4: budget eviction accounts only for successful deletions ----

    private class BudgetIndexes(
        val root: File,
        val oldestIndex: File,
        val newestIndex: File,
        val oldestSource: File,
        val newestSource: File,
    )

    /** Two stored indexes with deterministic oldest-first eviction order and
     *  published status sidecars, so eviction accounting can be asserted
     *  against concrete surviving bytes. */
    private suspend fun budgetIndexes(): BudgetIndexes {
        val dir = tempDir()
        val root = File(dir, "index")
        val sourceA = writeTextFile(dir, "a.pdf", "content a")
        val sourceB = writeTextFile(dir, "b.pdf", "content b")
        val setup = DocumentIndexStore(root)
        assertTrue(setup.write(sourceA.canonicalPath, sourceA, indexFor(sourceA, listOf(1 to "A 내용"))))
        assertTrue(setup.write(sourceB.canonicalPath, sourceB, indexFor(sourceB, listOf(1 to "B 내용"))))
        val oldest = setup.indexFileFor(sourceA.canonicalPath)
        val newest = setup.indexFileFor(sourceB.canonicalPath)
        assertTrue(oldest.setLastModified(1_600_000_000_000L))
        assertTrue(newest.setLastModified(1_700_000_000_000L))
        assertTrue(setup.statusFileFor(sourceA.canonicalPath).isFile)
        assertTrue(setup.statusFileFor(sourceB.canonicalPath).isFile)
        return BudgetIndexes(root, oldest, newest, sourceA, sourceB)
    }

    private fun failingDelete(idx: BudgetIndexes): (File) -> Boolean = { file ->
        if (file.name == idx.oldestIndex.name) false else file.delete()
    }

    @Test
    fun `P4_1 - a failed eviction keeps its bytes in the returned total`() = runTest {
        val idx = budgetIndexes()
        val store = DocumentIndexStore(idx.root, budgetDeleteFile = failingDelete(idx))
        val returned = store.enforceBudget(maxBytes = 1)
        val survivingBytes = store.storedIndexFiles().sumOf { it.length() }
        assertEquals(survivingBytes, returned)
        assertTrue(idx.oldestIndex.isFile)
        assertEquals(1, store.storedIndexFiles().size)
    }

    @Test
    fun `P4_2 - a failed candidate does not abort the eviction pass`() = runTest {
        val idx = budgetIndexes()
        val store = DocumentIndexStore(idx.root, budgetDeleteFile = failingDelete(idx))
        val returned = store.enforceBudget(maxBytes = 1)
        // The failed candidate survives with its bytes still counted...
        assertTrue(idx.oldestIndex.isFile)
        // ...and the pass still reaches and evicts the later candidate.
        assertFalse(idx.newestIndex.isFile)
        assertFalse(store.statusFileFor(idx.newestSource.canonicalPath).isFile)
        assertEquals(idx.oldestIndex.length(), returned)
    }

    @Test
    fun `P4_3 - when every deletion fails nothing is removed and the total stays truthful`() = runTest {
        val idx = budgetIndexes()
        val store = DocumentIndexStore(idx.root, budgetDeleteFile = { false })
        val storedBytes = listOf(idx.oldestIndex, idx.newestIndex).sumOf { it.length() }
        val returned = store.enforceBudget(maxBytes = 1)
        assertEquals(storedBytes, returned)
        assertEquals(2, store.storedIndexFiles().size)
        assertTrue(store.statusFileFor(idx.oldestSource.canonicalPath).isFile)
        assertTrue(store.statusFileFor(idx.newestSource.canonicalPath).isFile)
        // Sources are never budget candidates.
        assertTrue(idx.oldestSource.isFile)
        assertTrue(idx.newestSource.isFile)
    }

    @Test
    fun `P4_4 - only a successful eviction drops the status sidecar`() = runTest {
        val idx = budgetIndexes()
        val store = DocumentIndexStore(idx.root, budgetDeleteFile = failingDelete(idx))
        store.enforceBudget(maxBytes = 1)
        // A surviving index keeps its sidecar; a removed one loses it.
        assertTrue(idx.oldestIndex.isFile)
        assertTrue(store.statusFileFor(idx.oldestSource.canonicalPath).isFile)
        assertFalse(idx.newestIndex.isFile)
        assertFalse(store.statusFileFor(idx.newestSource.canonicalPath).isFile)
    }

    @Test
    fun `P4_5 - a fatal Error from the delete seam propagates out of the budget pass`() = runTest {
        val idx = budgetIndexes()
        val store = DocumentIndexStore(
            idx.root,
            budgetDeleteFile = { throw OutOfMemoryError("budget delete failure") },
        )
        var thrown: Throwable? = null
        try {
            store.enforceBudget(maxBytes = 1)
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected Error to propagate, got $thrown", thrown is OutOfMemoryError)
    }

    @Test
    fun `P4_6 - an ordinary Exception from the delete seam is a failed eviction not a crash`() = runTest {
        val idx = budgetIndexes()
        val store = DocumentIndexStore(
            idx.root,
            budgetDeleteFile = { file ->
                if (file.name == idx.oldestIndex.name) throw IOException("simulated delete failure")
                file.delete()
            },
        )
        val returned = store.enforceBudget(maxBytes = 1)
        assertTrue(idx.oldestIndex.isFile)
        assertTrue(store.statusFileFor(idx.oldestSource.canonicalPath).isFile)
        assertFalse(idx.newestIndex.isFile)
        assertEquals(idx.oldestIndex.length(), returned)
    }
}

// Shared synthetic document helper for index/rag tests.
object PdfTextLayerExtractorTestShared {
    fun doc(pages: List<Pair<Int, String>>): ExtractedLocalDocument {
        val pageCount = pages.maxOfOrNull { it.first } ?: 0
        val textPages = pages.filter { it.second.isNotEmpty() }
        return ExtractedLocalDocument(
            kind = LocalDocumentKind.PDF_TEXT_LAYER,
            pageCount = pageCount,
            textPageCount = textPages.size,
            imageOnlyPageCount = pages.size - textPages.size,
            imageElementCount = 0,
            totalExtractedChars = pages.sumOf { it.second.length },
            pages = pages.map { (n, text) ->
                ExtractedDocumentPage(
                    pageNumber = n,
                    text = text,
                    textCharCount = text.length,
                    contentHashHex = LocalDocumentChunker.sha256Hex(text),
                    hasImages = text.isEmpty(),
                    imageElementCount = 0,
                )
            },
        )
    }
}
