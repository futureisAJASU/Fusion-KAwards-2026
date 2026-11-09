package com.projectnuke.fusion.retrieval

import com.projectnuke.fusion.util.AttachmentRecord
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// C4 production coordinator tests: the real LocalRagCoordinator runs with a
// fake attachment store and a fake KoEn batch adapter. Assertions prove the
// actual production path (store read -> chunk -> retrieve -> snapshot ->
// prompt excerpts -> provenance metadata), never a helper alone.
class LocalRagCoordinatorTest {

    private class RecordingWorkerDispatcher : CoroutineDispatcher() {
        val dispatchCount = AtomicInteger(0)
        private val delegate = Dispatchers.Default

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    private class FakeStore(private val files: MutableMap<String, String>) : LocalAttachmentTextStore {
        val reads = mutableListOf<String>()
        override fun readText(path: String, maxChars: Int): String? {
            reads += path
            return files[path]?.take(maxChars)
        }
    }

    private class CountingBatchRanker(
        private val scores: Map<String, Double> = emptyMap(),
        private val unavailable: Boolean = false,
    ) : LocalSemanticBatchRanker {
        var calls = 0
        var maxSeen = 0
        override suspend fun scoreBatch(query: String, chunks: List<LocalDocumentChunk>): Map<String, Double?> {
            calls++
            maxSeen = maxOf(maxSeen, chunks.size)
            if (unavailable) return emptyMap()
            return chunks.associate { "${it.docId}#${it.chunkIndex}" to (scores[it.text] ?: 0.0) }
        }
    }

    private fun notesRecord() = AttachmentRecord("notes.txt", "text/plain", "/doc/notes.txt")

    @Test
    fun `prepare freezes retrieval and prompt excerpts reach final prompt`() = runTest {
        val store = FakeStore(mutableMapOf("/doc/notes.txt" to "사과 농장 보고서 내용이다. ".repeat(40)))
        val ranker = CountingBatchRanker()
        val coordinator = LocalRagCoordinator(store, ranker)
        val result = coordinator.prepare("req-1", "사과 농장", listOf(notesRecord()))
        assertTrue(result is LocalRagPreparationResult.Prepared)
        val prepared = result as LocalRagPreparationResult.Prepared
        assertTrue(prepared.selections.isNotEmpty())
        assertEquals("req-1", prepared.snapshot.requestId)
        // The actual prompt excerpts carry the frozen selections.
        val prompt = LocalRagPromptPolicy.assemble("사과 농장 요약해줘", prepared.selections)
        assertTrue(prepared.selections.any { prompt.contains(it.source.excerpt.take(20)) })
        assertTrue(prompt.contains("참고용 데이터"))
        assertFalse(LocalRagPromptPolicy.containsRawMarkers(prompt))
        // The store was actually read through the production path.
        assertTrue(store.reads.contains("/doc/notes.txt"))
        // Frozen snapshot validates against the same live document.
        assertTrue(coordinator.validate(prepared.snapshot, listOf(notesRecord())))
        // Semantic work stayed bounded.
        assertTrue(ranker.maxSeen <= LocalRagBounds.MAX_SEMANTIC_CANDIDATES)
    }

    @Test
    fun `prepare and validate use the coordinator worker dispatcher`() = runTest {
        val store = FakeStore(mutableMapOf("/doc/notes.txt" to "worker dispatch proof ".repeat(40)))
        val worker = RecordingWorkerDispatcher()
        val coordinator = LocalRagCoordinator(
            textStore = store,
            workerDispatcher = worker,
        )
        val attachments = listOf(notesRecord())
        val prepared = coordinator.prepare("req-dispatch", "worker proof", attachments)
            as LocalRagPreparationResult.Prepared

        assertTrue(coordinator.validate(prepared.snapshot, attachments))
        assertTrue("prepare and validate must dispatch through the injected worker", worker.dispatchCount.get() >= 2)
    }

    @Test
    fun `unsupported attachment reports explicitly and never fakes text`() = runTest {
        val store = FakeStore(mutableMapOf())
        val coordinator = LocalRagCoordinator(store, null)
        val result = coordinator.prepare(
            "req-2", "요약해줘",
            listOf(AttachmentRecord("doc.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "/doc/doc.docx")),
        )
        assertTrue(result is LocalRagPreparationResult.UnsupportedAttachment)
        assertEquals("doc.docx", (result as LocalRagPreparationResult.UnsupportedAttachment).fileName)
        assertTrue(store.reads.isEmpty())
        assertTrue(
            LocalRagInputPolicy.unsupportedReason("application/pdf", "doc.pdf")
                .contains(".json"),
        )
    }

    @Test
    fun `mixed set still uses supported text only`() = runTest {
        val store = FakeStore(
            mutableMapOf("/doc/notes.txt" to "지원되는 사과 문서 내용. ".repeat(30)),
        )
        val coordinator = LocalRagCoordinator(store, null)
        val result = coordinator.prepare(
            "req-3", "사과",
            listOf(
                AttachmentRecord("evil.bin", "application/octet-stream", "/doc/evil.bin"),
                notesRecord(),
            ),
        )
        assertTrue(result is LocalRagPreparationResult.Prepared)
        val prepared = result as LocalRagPreparationResult.Prepared
        val prompt = LocalRagPromptPolicy.assemble("사과", prepared.selections)
        assertFalse(prompt.contains("evil"))
        assertFalse(store.reads.contains("/doc/evil.bin"))
    }

    @Test
    fun `unusable pdf fails closed with typed status`() = runTest {
        val store = FakeStore(mutableMapOf())
        val coordinator = LocalRagCoordinator(store, null)
        val result = coordinator.prepare(
            "req-pdf", "요약해줘",
            listOf(AttachmentRecord("locked.pdf", "application/pdf", "/doc/locked.pdf")),
        )
        assertTrue(result is LocalRagPreparationResult.DocumentUnusable)
        assertEquals(
            DocumentExtractionStatus.UNSUPPORTED,
            (result as LocalRagPreparationResult.DocumentUnusable).status,
        )
    }

    @Test
    fun `missing attachment text fails closed`() = runTest {
        val store = FakeStore(mutableMapOf())
        val coordinator = LocalRagCoordinator(store, null)
        val result = coordinator.prepare("req-4", "사과", listOf(notesRecord()))
        assertTrue(result is LocalRagPreparationResult.TextUnavailable)
    }

    @Test
    fun `changed or deleted attachment invalidates frozen snapshot`() = runTest {
        val files = mutableMapOf("/doc/notes.txt" to "사과 농장 보고서 내용이다. ".repeat(40))
        val store = FakeStore(files)
        val coordinator = LocalRagCoordinator(store, null)
        val prepared = coordinator.prepare("req-5", "사과 농장", listOf(notesRecord()))
            as LocalRagPreparationResult.Prepared
        // Changed content.
        files["/doc/notes.txt"] = "완전히 다른 내용으로 교체되었다. ".repeat(40)
        assertFalse(coordinator.validate(prepared.snapshot, listOf(notesRecord())))
        // Deleted attachment.
        files.remove("/doc/notes.txt")
        assertFalse(coordinator.validate(prepared.snapshot, listOf(notesRecord())))
    }

    @Test
    fun `same length edit outside selected chunks invalidates frozen snapshot`() = runTest {
        val original = "NEEDLE_RAG_R1. " + "neutral unselected filler. ".repeat(120) + "TAIL_A"
        val changed = original.replace("TAIL_A", "TAIL_B")
        assertEquals(original.length, changed.length)
        val files = mutableMapOf("/doc/source.txt" to original)
        val record = AttachmentRecord("source.txt", "text/plain", "/doc/source.txt")
        val coordinator = LocalRagCoordinator(FakeStore(files))

        val prepared = coordinator.prepare("whole-source-r1", "NEEDLE_RAG_R1", listOf(record))
            as LocalRagPreparationResult.Prepared
        assertTrue(prepared.selections.isNotEmpty())
        assertTrue(prepared.selections.none { it.source.excerpt.contains("TAIL_A") })

        files[record.localPath] = changed
        assertFalse(
            "full bounded source identity must be checked even when selected chunk bytes did not change",
            coordinator.validate(prepared.snapshot, listOf(record)),
        )
        assertNull(selectRagForGeneration(prepared, listOf(record), coordinator::validate))
    }

    @Test
    fun `batch adapter degrades to lexical on owner unavailability`() = runTest {
        val matcher = object : SemanticCandidateMatcher {
            override suspend fun rank(
                claim: String,
                segments: List<SemanticSegment>,
                limit: Int,
            ): SemanticMatchResult =
                SemanticMatchResult(emptyList(), EmbeddingFailureReason.ASSET_MISSING)
        }
        val adapter = KoEnE5LocalSemanticBatchRanker(matcher)
        val chunks = LocalDocumentChunker.chunk("doc-1", "notes.txt", "사과 내용. ".repeat(30))
        assertTrue(adapter.scoreBatch("사과", chunks).isEmpty())
    }

    @Test
    fun `batch adapter maps ranking signals by chunk key`() = runTest {
        var seenSegments = 0
        val matcher = object : SemanticCandidateMatcher {
            override suspend fun rank(
                claim: String,
                segments: List<SemanticSegment>,
                limit: Int,
            ): SemanticMatchResult {
                seenSegments = segments.size
                return SemanticMatchResult(
                    segments.mapIndexed { i, segment -> RankedSemanticMatch(segment.id, 0.9f - i * 0.01f, i + 1) },
                    null,
                )
            }
        }
        val adapter = KoEnE5LocalSemanticBatchRanker(matcher)
        val chunks = LocalDocumentChunker.chunk("doc-1", "notes.txt", "사과 내용. ".repeat(30))
        val scores = adapter.scoreBatch("사과", chunks)
        assertEquals(chunks.size, seenSegments)
        assertTrue(scores.keys.all { it.startsWith("doc-1#") })
        assertTrue(scores.values.all { (it ?: 0.0) in -1.0..1.0 })
    }

    @Test
    fun `generation gate consults validate before committing excerpts`() = runTest {
        val store = FakeStore(mutableMapOf("/doc/notes.txt" to "사과 농장 보고서 내용이다. ".repeat(40)))
        val coordinator = LocalRagCoordinator(store, null)
        val attachments = listOf(AttachmentRecord("notes.txt", "text/plain", "/doc/notes.txt"))
        val prepared = coordinator.prepare("req-gate", "사과 농장", attachments)
        assertTrue(prepared is LocalRagPreparationResult.Prepared)

        var validateCalls = 0
        val allowed = selectRagForGeneration(prepared, attachments) { _, _ ->
            validateCalls++
            true
        }
        assertEquals(1, validateCalls)
        assertEquals(
            (prepared as LocalRagPreparationResult.Prepared).selections,
            allowed,
        )
        val denied = selectRagForGeneration(prepared, attachments) { _, _ ->
            validateCalls++
            false
        }
        assertEquals(2, validateCalls)
        assertEquals(null, denied)
        // Non-prepared inputs never reach validation.
        assertEquals(2, validateCalls)
        assertEquals(null, selectRagForGeneration(LocalRagPreparationResult.TextUnavailable, attachments) { _, _ ->
            validateCalls++
            true
        })
        assertEquals(2, validateCalls)
    }

    @Test
    fun `production sequence aborts generation on changed source`() = runTest {
        val files = mutableMapOf("/doc/notes.txt" to "사과 농장 보고서 내용이다. ".repeat(40))
        val store = FakeStore(files)
        val coordinator = LocalRagCoordinator(store, null)
        val attachments = listOf(AttachmentRecord("notes.txt", "text/plain", "/doc/notes.txt"))
        val prepared = coordinator.prepare("req-seq", "사과 농장", attachments)
        assertTrue(prepared is LocalRagPreparationResult.Prepared)
        // Unchanged source: production gate proceeds with frozen selections.
        assertTrue(
            selectRagForGeneration(prepared, attachments, coordinator::validate)?.isNotEmpty() == true,
        )
        // Changed source: production gate returns null so the caller aborts
        // with a bounded message instead of generating from stale excerpts.
        files["/doc/notes.txt"] = "완전히 다른 내용으로 교체되었다. ".repeat(40)
        assertEquals(null, selectRagForGeneration(prepared, attachments, coordinator::validate))
    }

    // ── P3 batch semantic failure must degrade to lexical retrieval ────────
    //
    // Semantic ranking is optional ranking assistance. The documented contract
    // is "semantic unavailable/failure -> lexical fallback -> answer path stays
    // usable", which the per-pair path already implements; the batch path had
    // no failure boundary at all, so an ordinary KoEn runtime failure escaped
    // prepare() AFTER the attachment text had already been prepared.

    private class ThrowingBatchRanker(
        private val failure: Throwable,
    ) : LocalSemanticBatchRanker {
        var calls = 0
        override suspend fun scoreBatch(
            query: String,
            chunks: List<LocalDocumentChunk>,
        ): Map<String, Double?> {
            calls++
            throw failure
        }
    }

    private fun lexicallyRelevantChunks(): List<LocalDocumentChunk> =
        LocalDocumentChunker.chunk("doc-1", "notes.txt", "사과 농장 보고서 내용이다. ".repeat(40))

    @Test
    fun `P3_1 - throwing batch ranker falls back to lexical retrieval`() = runTest {
        val ranker = ThrowingBatchRanker(RuntimeException("koen batch blew up"))

        val selections = LocalRagRetrieval.retrieve(
            query = "사과 농장",
            chunks = lexicallyRelevantChunks(),
            attachmentIdByDoc = mapOf("doc-1" to "/doc/notes.txt"),
            topK = LocalRagBounds.FINAL_TOP_K,
            batchRanker = ranker,
        )

        assertEquals(1, ranker.calls)
        assertTrue("lexical candidates must survive a batch failure", selections.isNotEmpty())
        assertTrue(
            "the surviving selection must be positive lexical evidence",
            selections.all { it.lexicalScore > 0 },
        )
    }

    @Test
    fun `P3_2 - coordinator prepare stays usable when the batch ranker throws`() = runTest {
        val store = FakeStore(mutableMapOf("/doc/notes.txt" to "사과 농장 보고서 내용이다. ".repeat(40)))
        val ranker = ThrowingBatchRanker(RuntimeException("koen batch blew up"))
        val coordinator = LocalRagCoordinator(store, ranker)

        val result = coordinator.prepare("req-p3", "사과 농장", listOf(notesRecord()))

        assertTrue("prepare must not fail on an ordinary batch failure", result is LocalRagPreparationResult.Prepared)
        val prepared = result as LocalRagPreparationResult.Prepared
        assertTrue(prepared.selections.isNotEmpty())
        assertTrue(prepared.selections.all { it.lexicalScore > 0 })
        assertEquals(1, ranker.calls)
        // The frozen snapshot still validates against the unchanged source.
        assertTrue(coordinator.validate(prepared.snapshot, listOf(notesRecord())))
    }

    @Test
    fun `P3_3 - cancellation from the batch ranker propagates`() = runTest {
        val cancellation = kotlinx.coroutines.CancellationException("rank cancelled")
        val ranker = ThrowingBatchRanker(cancellation)

        val thrown = runCatching {
            LocalRagRetrieval.retrieve(
                query = "사과 농장",
                chunks = lexicallyRelevantChunks(),
                attachmentIdByDoc = mapOf("doc-1" to "/doc/notes.txt"),
                topK = LocalRagBounds.FINAL_TOP_K,
                batchRanker = ranker,
            )
        }.exceptionOrNull()

        assertSame("cancellation must propagate, never become a silent fallback", cancellation, thrown)
    }

    @Test
    fun `P3_4 - fatal Error from the batch ranker propagates`() = runTest {
        val linkError = LinkageError("koen native binding missing")
        val ranker = ThrowingBatchRanker(linkError)

        val thrown = runCatching {
            LocalRagRetrieval.retrieve(
                query = "사과 농장",
                chunks = lexicallyRelevantChunks(),
                attachmentIdByDoc = mapOf("doc-1" to "/doc/notes.txt"),
                topK = LocalRagBounds.FINAL_TOP_K,
                batchRanker = ranker,
            )
        }.exceptionOrNull()

        assertSame("fatal Error must propagate, never become a silent fallback", linkError, thrown)
    }

    @Test
    fun `local provenance survives metadata round trip`() {
        val selections = listOf(
            LocalRagSelection(
                chunk = LocalDocumentChunk("d", "notes.txt", 0, 0, 10, "h", "사과 내용"),
                source = LocalDocumentSource("d", "notes.txt", "att-1", 0, 0, 10, "h", "사과 내용"),
                lexicalScore = 2,
                semanticScore = 0.7,
                rank = 0,
            ),
        )
        val entries = selections.map {
            LocalRagSourceEntry(
                fileName = it.source.displayName,
                attachmentId = it.source.attachmentId,
                chunkIndex = it.chunk.chunkIndex,
                startOffset = it.chunk.startOffset,
                endOffset = it.chunk.endOffset,
                contentHashHex = it.source.contentHashHex,
                excerpt = it.source.excerpt,
                rank = it.rank,
                lexicalScore = it.lexicalScore,
                semanticScore = it.semanticScore,
            )
        }
        val content = LocalRagSourceMetadata.append("답변 본문", entries)
        assertTrue(content.startsWith("답변 본문"))
        val parsed = LocalRagSourceMetadata.parse(content)
        assertEquals(1, parsed.size)
        assertEquals("notes.txt", parsed[0].fileName)
        assertEquals("사과 내용", parsed[0].excerpt)
        val stripped = LocalRagSourceMetadata.strip(content)
        assertFalse(stripped.contains("FUSION_LOCAL_RAG"))
        assertEquals("답변 본문", stripped)
    }
}
