package com.projectnuke.fusion.retrieval

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalOcrPipelineTest {
    private fun native(n: Int) = IndexedDocumentPage(n, "native text", 11, LocalDocumentChunker.sha256Hex("native text"), false, 0)
    @Test fun nativeLayerIsPreferredAndOnlyMissingPagesUseOcr() = runTest {
        val recognized = mutableListOf<Int>()
        val result = LocalOcrPipeline.collect(2, mapOf(1 to native(1)), { recognized += it; "OCR text" }, { _, _ -> }) as LocalOcrResult.Complete
        assertEquals(listOf(2), recognized)
        assertFalse(result.pages[0].ocrDerived)
        assertTrue(result.pages[1].ocrDerived)
    }
    @Test fun textPdfDoesNotInvokeOcr() = runTest {
        val result = LocalOcrPipeline.collect(1, mapOf(1 to native(1)), { error("must not OCR") }, { _, _ -> })
        assertTrue(result is LocalOcrResult.Complete)
    }
    @Test fun partialFailureIsNeverACompletePublishableIndex() = runTest {
        val result = LocalOcrPipeline.collect(3, emptyMap(), { if (it == 2) throw java.io.IOException(); "text" }, { _, _ -> })
        assertEquals(LocalOcrResult.Partial(2, listOf(2)), result)
    }
    @Test fun boundsRejectBeforeRecognition() = runTest {
        var calls = 0
        assertEquals(LocalOcrResult.TooManyPages, LocalOcrPipeline.collect(33, emptyMap(), { calls++; "text" }, { _, _ -> }))
        assertEquals(0, calls)
    }
    @Test fun cancellationReleasesPageOwnerAndDoesNotPublish() = runTest {
        val entered = CompletableDeferred<Unit>()
        var active = 0
        var published = false
        val job = launch {
            LocalOcrPipeline.collect(3, emptyMap(), {
                active++
                try { entered.complete(Unit); CompletableDeferred<Unit>().await(); "text" } finally { active-- }
            }, { _, _ -> })
            published = true
        }
        entered.await()
        job.cancel()
        job.join()
        assertEquals(0, active)
        assertFalse(published)
        assertTrue(LocalOcrPipeline.collect(1, emptyMap(), { "retry text" }, { _, _ -> }) is LocalOcrResult.Complete)
    }
    @Test fun fatalFailureKeepsIdentity() = runTest {
        val fatal = LinkageError("native fatal")
        try { LocalOcrPipeline.collect(1, emptyMap(), { throw fatal }, { _, _ -> }); fail() }
        catch (actual: LinkageError) { assertSame(fatal, actual) }
    }
    @Test fun noTextAndOversizedOutputRemainExplicit() = runTest {
        assertEquals(LocalOcrResult.NoText, LocalOcrPipeline.collect(1, emptyMap(), { " " }, { _, _ -> }))
        assertTrue(LocalOcrPipeline.collect(1, emptyMap(), { "x".repeat(32_001) }, { _, _ -> }) is LocalOcrResult.Partial)
    }
    @Test fun provenanceSurvivesChunkingMetadataAndFrozenValidation() = runTest {
        val page = (LocalOcrPipeline.collect(1, emptyMap(), { "Fusion source" }, { _, _ -> }) as LocalOcrResult.Complete).pages.single()
        val chunks = LocalDocumentPageChunker.chunkPages("doc", "file.pdf", listOf(page))
        assertTrue(chunks.single().ocrDerived)
        val entry = LocalRagSourceEntry("file.pdf", "doc", 0, 0, 13, page.contentHashHex, page.text, 1, 1, null, 1, true)
        assertTrue(LocalRagSourceMetadata.parse(LocalRagSourceMetadata.append("answer", listOf(entry))).single().ocrDerived)
        val snapshot = LocalRagRequestSnapshot("r", listOf("doc"), listOf("doc#${chunks.single().chunkIndex}"),
            listOf(LocalDocumentChunker.sha256Hex(chunks.single().text)), listOf(1), listOf(true))
        assertTrue(LocalRagSnapshotPolicy.validate(snapshot, chunks))
        assertFalse(LocalRagSnapshotPolicy.validate(snapshot, chunks.map { it.copy(ocrDerived = false) }))
    }
}
