package com.projectnuke.fusion.retrieval

import com.projectnuke.fusion.util.AttachmentStorageManager
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase K+L: index/file ownership hardening and lifecycles.
//
// Derived indexes are NEVER source authorities: they follow the physical
// managed source. This file proves the K cases on host seams (the managed
// attachment root + DocumentIndexStore/repository + discard hook).
class DocumentOwnershipLifecycleTest {

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
    fun `pending import cancellation deletes pdf copy and its index together`() {
        val root = Files.createTempDirectory("fusion-ownership").toFile()
        try {
            val store = DocumentIndexStore(File(root, "document_index"))
            val source = File(root, "uuid_paper.pdf").apply { writeText("pdf") }
            assertTrue(store.write(source.canonicalPath, source, indexFor(source, listOf(1 to "내용"))))
            assertTrue(store.load(source.canonicalPath, source) != null)

            // Cancel a pending import the way the production code path does.
            val result = AttachmentStorageManager.discardPendingAttachmentFile(
                attachmentRoot = root,
                path = source.canonicalPath,
                onDeletedFile = { store.delete(it.canonicalPath) },
            )
            assertEquals(com.projectnuke.fusion.util.PendingAttachmentDiscardResult.Deleted, result)
            assertFalse(source.exists())
            // No orphan valid artifact remains.
            assertFalse(store.indexFileFor(source.canonicalPath).exists())
            assertTrue(store.storedIndexFiles().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `final source removal makes the index cleanup-eligible`() {
        val root = Files.createTempDirectory("fusion-ownership").toFile()
        try {
            val store = DocumentIndexStore(File(root, "document_index"))
            val live = File(root, "live.pdf").apply { writeText("live") }
            val dead = File(root, "dead.pdf").apply { writeText("dead") }
            store.write(live.canonicalPath, live, indexFor(live, listOf(1 to "live 내용")))
            store.write(dead.canonicalPath, dead, indexFor(dead, listOf(1 to "dead 내용")))
            // The physical source is removed by normal attachment cleanup.
            dead.delete()
            store.cleanupOrphans(liveSources = setOf(live))
            assertEquals(1, store.storedIndexFiles().size)
            assertTrue(store.load(live.canonicalPath, live) != null)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `removing one reference never deletes the index while the source lives`() = runTest {
        val root = Files.createTempDirectory("fusion-ownership").toFile()
        try {
            val store = DocumentIndexStore(File(root, "document_index"))
            val source = File(root, "shared.pdf").apply { writeText("shared") }
            store.write(source.canonicalPath, source, indexFor(source, listOf(1 to "공유 내용")))
            // Two projects reference the same physical PDF: there is exactly
            // ONE derived index, and deleting one project row (no physical
            // change) leaves the index untouched.
            val key = DocumentIndexContract.keyFor(source.canonicalPath)
            assertEquals(1, store.storedIndexFiles().count { it.name.startsWith(key) })
            store.delete(source.canonicalPath + "?ignored")
            assertTrue(store.load(source.canonicalPath, source) != null)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `index build cancellation leaves no valid partial artifact and the source may remain`() = runTest {
        val root = Files.createTempDirectory("fusion-ownership").toFile()
        try {
            val source = File(root, "paper.pdf").apply { writeText("pdf") }
            val store = DocumentIndexStore(File(root, "document_index"))
            val extractor = object : LocalDocumentExtractor {
                override suspend fun extract(path: String, mimeType: String?): DocumentExtractionResult {
                    throw kotlinx.coroutines.CancellationException("build cancelled")
                }
            }
            val repository = PdfDocumentIndexRepository(store, extractor)
            try {
                repository.obtain(source.canonicalPath, "application/pdf")
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
            // The legitimately attached source may remain; the derived index
            // must not exist in any half-written state.
            assertTrue(source.exists())
            assertFalse(store.indexFileFor(source.canonicalPath).exists())
            assertFalse(File(store.indexFileFor(source.canonicalPath).path + ".tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `document lifecycle closes the platform document exactly once`() = runTest {
        // L: bounded engine handle lifecycle — every open must be closed.
        val dir = Files.createTempDirectory("fusion-orphan").toFile()
        try {
            val source = File(dir, "paper.pdf").apply { writeText("pdf") }
            var opened = 0
            var closed = 0
            val engine = object : PdfTextLayerEngine {
                private inner class Stub(override val pageCount: Int) : PdfTextLayerDocument {
                    override suspend fun pageTextLayer(pageNumber: Int): PdfPageTextLayer? =
                        PdfPageTextLayer("내용 $pageNumber", 1, 0)

                    override fun close() {
                        closed++
                    }
                }

                override suspend fun open(sourcePath: String): PdfTextLayerDocument {
                    opened++
                    return Stub(2)
                }
            }
            val repository = PdfDocumentIndexRepository(
                store = DocumentIndexStore(File(dir, "index")),
                extractor = PdfTextLayerExtractor(engine),
                engine = engine,
            )
            val pages = repository.reextractPages(source.canonicalPath, setOf(1, 2))
            assertEquals(2, pages?.size)
            assertEquals(1, opened)
            assertEquals(1, closed)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cache budget trimming never deletes source attachments`() {
        val root = Files.createTempDirectory("fusion-ownership").toFile()
        try {
            val store = DocumentIndexStore(File(root, "document_index"))
            val sourceA = File(root, "a.pdf").apply { writeText("a") }
            val sourceB = File(root, "b.pdf").apply { writeText("b") }
            store.write(sourceA.canonicalPath, sourceA, indexFor(sourceA, listOf(1 to "A")))
            store.write(sourceB.canonicalPath, sourceB, indexFor(sourceB, listOf(1 to "B")))
            store.enforceBudget(maxBytes = 1)
            assertEquals(0, store.storedIndexFiles().size)
            assertTrue(sourceA.exists())
            assertTrue(sourceB.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}