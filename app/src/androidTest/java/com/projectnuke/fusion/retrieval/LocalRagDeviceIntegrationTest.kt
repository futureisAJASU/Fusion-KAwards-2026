package com.projectnuke.fusion.retrieval

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.fusion.chat.ChatPromptComposition
import com.projectnuke.fusion.trust.TrustRoutingDecision
import com.projectnuke.fusion.trust.TrustRoutingReason
import com.projectnuke.fusion.ui.storedAssistantContentWithLocalRag
import com.projectnuke.fusion.util.AttachmentRecord
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Device integration through the same local attachment RAG and prompt paths used by ChatScreen. */
@RunWith(AndroidJUnit4::class)
class LocalRagDeviceIntegrationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var fixtureRoot: File
    private val ownedDerivedIndexFiles = mutableListOf<File>()

    @Before
    fun createTestOwnedRoot() {
        fixtureRoot = File(context.filesDir, "rag-r1-tests/${UUID.randomUUID()}")
        ownedDerivedIndexFiles.clear()
        assertTrue("qualification-private fixture root must be created", fixtureRoot.mkdirs())
    }

    @After
    fun cleanUpTestOwnedFilesAndOwner() {
        KoEnE5RetrievalOwner.resetForTests()
        assertTrue("only derived indexes for synthetic test PDFs are removed", ownedDerivedIndexFiles.all { !it.exists() || it.delete() })
        assertTrue("only this test's synthetic fixture directory is removed", fixtureRoot.deleteRecursively())
    }

    @Test
    fun productionPipelineReadsRanksFreezesValidatesAndComposesSelectedSources() = runBlocking {
        val document = File(fixtureRoot, "synthetic-notes.txt")
        document.writeText(syntheticDocument())
        val record = attachment(document)
        val query = "세종 1443 한글 창제"
        val privateModelDir = File(context.filesDir, "koen-e5")
        val matcher = KoEnE5RetrievalOwner.proxy(context.assets, privateModelDir)
        val coordinator = LocalRagCoordinator(
            textStore = FileLocalAttachmentTextStore(),
            batchRanker = KoEnE5LocalSemanticBatchRanker(matcher),
        )

        val prepared = coordinator.prepare("rag-r1-device", query, listOf(record))
            as? LocalRagPreparationResult.Prepared
        assertNotNull("production coordinator must read and prepare the synthetic attachment", prepared)
        val frozen = requireNotNull(prepared)
        assertTrue(frozen.selections.isNotEmpty())
        assertTrue(frozen.selections.size <= LocalRagBounds.FINAL_TOP_K)
        assertEquals("rag-r1-device", frozen.snapshot.requestId)
        assertTrue(frozen.snapshot.chunkKeys.size <= LocalRagBounds.FINAL_TOP_K)
        assertTrue(frozen.selections.first().lexicalScore > 0)
        assertTrue(
            "actual owner-backed KoEn-E5 inference must contribute at least one semantic-only candidate",
            frozen.selections.any { it.semanticScore != null && it.lexicalScore == 0 },
        )
        assertTrue("real bundled model must be provisioned under the qualification UID", File(privateModelDir, KoEnE5AssetProvisioner.FILE_MODEL_NAME).isFile)

        frozen.selections.forEachIndexed { index, selection ->
            assertEquals(document.absolutePath, selection.source.attachmentId)
            assertEquals(selection.chunk.text, selection.source.excerpt)
            assertEquals(selection.chunk.contentHashHex, selection.source.contentHashHex)
            assertEquals(LocalDocumentChunker.sha256Hex(selection.chunk.text), frozen.snapshot.excerptHashes[index])
            assertEquals("${selection.chunk.docId}#${selection.chunk.chunkIndex}", frozen.snapshot.chunkKeys[index])
            assertTrue(selection.chunk.text.length <= LocalRagBounds.MAX_CHUNK_CHARS)
        }

        val selectedAtGeneration = selectRagForGeneration(frozen, listOf(record), coordinator::validate)
        assertEquals(frozen.selections, selectedAtGeneration)
        val repeated = coordinator.prepare("rag-r1-repeat", query, listOf(record))
            as LocalRagPreparationResult.Prepared
        assertEquals(
            "semantic retrieval through the stable production owner must repeat deterministically",
            frozen.selections.map { it.chunk.docId to it.chunk.chunkIndex },
            repeated.selections.map { it.chunk.docId to it.chunk.chunkIndex },
        )

        // ChatScreen uses this exact composer before constructing its generation user message.
        val ragBody = ChatPromptComposition.localizedRagUserContent(
            query,
            requireNotNull(selectedAtGeneration),
            frozen.coverageByDoc,
        )
        val finalUserMessage = ChatPromptComposition.finalUserContent(
            body = ragBody,
            attachments = listOf(record),
            webSearchEnabled = false,
            webSearchResult = null,
        )
        frozen.selections.forEach { assertTrue(finalUserMessage.contains(it.source.excerpt)) }
        assertTrue(finalUserMessage.contains("참고용 데이터"))
        assertFalse(LocalRagPromptPolicy.containsRawMarkers(finalUserMessage))

        // This is the production persistence mapper; no answer generation occurs in this test.
        val persisted = storedAssistantContentWithLocalRag(
            answer = "synthetic no-LLM response",
            trustRoutingDecision = TrustRoutingDecision.local(TrustRoutingReason.REASONING_OR_TRANSFORMATION),
            ragSelections = frozen.selections,
        )
        val metadata = LocalRagSourceMetadata.parse(persisted)
        assertEquals(frozen.selections.size, metadata.size)
        metadata.forEachIndexed { index, source ->
            val selected = frozen.selections[index]
            assertEquals(selected.source.excerpt, source.excerpt)
            assertEquals(selected.source.contentHashHex, source.contentHashHex)
            assertEquals(selected.source.attachmentId, source.attachmentId)
            assertEquals(selected.chunk.chunkIndex, source.chunkIndex)
            assertEquals(selected.rank, source.rank)
        }

        val outboundQuery = LocalRagRetrieval.webQueryFromUser(query, frozen.selections)
        assertEquals(query, outboundQuery)
        assertFalse(outboundQuery.contains("SYNTHETIC_PRIVATE_SENTINEL_RAG_R1"))
        Log.i("LocalRagR1", "realE5=true chunks=${frozen.snapshot.chunkKeys.size} selected=${frozen.selections.size} promptChars=${finalUserMessage.length} metadata=${metadata.size}")
        Unit
    }

    @Test
    fun snapshotRejectsChangedDeletedAndSubstitutedTextSourcesAndNextRequestIsFresh() = runBlocking {
        val source = File(fixtureRoot, "source.txt")
        val original = "세종은 한글을 창제했다. PRIOR_EVIDENCE_RAG_R1\n" +
            ("기상 관측 장비가 바람과 기압을 측정한다. ").repeat(80) + "TAIL_A"
        source.writeText(original)
        val record = attachment(source)
        val coordinator = LocalRagCoordinator(FileLocalAttachmentTextStore())
        val prepared = coordinator.prepare("integrity-r1", "세종", listOf(record)) as LocalRagPreparationResult.Prepared

        source.writeText(original + " changed")
        assertFalse("changed source must invalidate the frozen snapshot", coordinator.validate(prepared.snapshot, listOf(record)))

        val sameLength = original.replace("TAIL_A", "TAIL_B")
        assertEquals(original.length, sameLength.length)
        source.writeText(sameLength)
        assertFalse("same-length edits must be caught by chunk hashes", coordinator.validate(prepared.snapshot, listOf(record)))

        source.writeText(original)
        val substitute = File(fixtureRoot, "substituted-path.txt")
        substitute.writeText(original)
        assertFalse("identical bytes at another attachment path are a different source", coordinator.validate(prepared.snapshot, listOf(attachment(substitute))))

        assertTrue(source.delete())
        assertFalse("deleted source must invalidate the frozen snapshot", coordinator.validate(prepared.snapshot, listOf(record)))
        assertEquals(LocalRagPreparationResult.TextUnavailable, coordinator.prepare("missing-r1", "세종", listOf(record)))
        val unsupported = AttachmentRecord(
            "unsupported.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            File(fixtureRoot, "unsupported.docx").absolutePath,
        )
        assertTrue(coordinator.prepare("unsupported-r1", "세종", listOf(unsupported)) is LocalRagPreparationResult.UnsupportedAttachment)

        val nextFile = File(fixtureRoot, "current-source.txt")
        nextFile.writeText("CURRENT_EVIDENCE_RAG_R1 새로운 문서의 합성 내용입니다.")
        val nextRecord = attachment(nextFile)
        val next = coordinator.prepare("fresh-r1", "CURRENT_EVIDENCE_RAG_R1", listOf(nextRecord)) as LocalRagPreparationResult.Prepared
        val currentSelections = selectRagForGeneration(next, listOf(nextRecord), coordinator::validate)
        assertNotNull(currentSelections)
        val prompt = ChatPromptComposition.localizedRagUserContent("CURRENT_EVIDENCE_RAG_R1", requireNotNull(currentSelections))
        assertTrue(prompt.contains("CURRENT_EVIDENCE_RAG_R1"))
        assertFalse(prompt.contains("PRIOR_EVIDENCE_RAG_R1"))
    }

    @Test
    fun cancellationDuringProductionRetrievalPropagates() = runBlocking {
        val document = File(fixtureRoot, "cancellation.txt")
        document.writeText(("해양 관측 장비는 합성 데이터의 기압 변화를 기록한다. ").repeat(90))
        val enteredRanker = CompletableDeferred<Unit>()
        val blockingRanker = LocalSemanticBatchRanker { _, _ ->
            enteredRanker.complete(Unit)
            awaitCancellation()
        }
        val coordinator = LocalRagCoordinator(FileLocalAttachmentTextStore(), blockingRanker)
        val request = async { coordinator.prepare("cancel-r1", "cancellation trigger", listOf(attachment(document))) }
        withTimeout(10_000) { enteredRanker.await() }
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
    }

    @Test
    fun concurrentIndependentRequestsKeepDistinctSourceIdentity() = runBlocking {
        val first = File(fixtureRoot, "first.txt").apply { writeText(syntheticDocument("FIRST_SYNTHETIC_SOURCE")) }
        val second = File(fixtureRoot, "second.txt").apply { writeText(syntheticDocument("SECOND_SYNTHETIC_SOURCE")) }
        val sharedMatcher = KoEnE5RetrievalOwner.proxy(context.assets, File(context.filesDir, "koen-e5"))
        val ranker = KoEnE5LocalSemanticBatchRanker(sharedMatcher)
        val coordinatorA = LocalRagCoordinator(FileLocalAttachmentTextStore(), ranker)
        val coordinatorB = LocalRagCoordinator(FileLocalAttachmentTextStore(), ranker)

        val results = coroutineScope {
            listOf(
                async { coordinatorA.prepare("concurrent-a", "세종 1443 한글 창제", listOf(attachment(first))) },
                async { coordinatorB.prepare("concurrent-b", "세종 1443 한글 창제", listOf(attachment(second))) },
            ).map { it.await() }
        }
        val preparedA = results[0] as LocalRagPreparationResult.Prepared
        val preparedB = results[1] as LocalRagPreparationResult.Prepared
        assertTrue(preparedA.selections.isNotEmpty())
        assertTrue(preparedB.selections.isNotEmpty())
        assertTrue(preparedA.selections.all { it.source.attachmentId == first.absolutePath })
        assertTrue(preparedB.selections.all { it.source.attachmentId == second.absolutePath })
        assertTrue(preparedA.selections.none { it.source.excerpt.contains("SECOND_SYNTHETIC_SOURCE") })
        assertTrue(preparedB.selections.none { it.source.excerpt.contains("FIRST_SYNTHETIC_SOURCE") })
    }

    @Test
    fun ownerCloseDuringActiveRankDetachesEngineAndStableProxyRecreates() = runBlocking {
        val activeInferenceEntered = CompletableDeferred<Unit>()
        val releaseActiveInference = CompletableDeferred<Unit>()
        val engineCreations = AtomicInteger(0)
        val firstEngineClosed = AtomicBoolean(false)
        val stubModel = File(fixtureRoot, "owner-fake-model.onnx").apply { writeText("synthetic") }
        val stubTokenizer = File(fixtureRoot, "owner-fake-tokenizer.json").apply { writeText("synthetic") }
        val provisioned = KoEnE5AssetProvisioner.Provisioned(stubModel, stubTokenizer)

        KoEnE5RetrievalOwner.provision = { _, _ -> KoEnE5AssetProvisioner.ProvisionResult.Ready(provisioned) }
        KoEnE5RetrievalOwner.engineFactory = { _ ->
            val generation = engineCreations.incrementAndGet()
            object : FusionEmbeddingEngine {
                private val closed = AtomicBoolean(false)
                override val diagnostics = EmbeddingDiagnostics(0, 0L, 0L, true, null)
                override val isClosed: Boolean get() = closed.get()

                override suspend fun embed(texts: List<String>): EmbeddingResult {
                    if (generation == 1) {
                        activeInferenceEntered.complete(Unit)
                        releaseActiveInference.await()
                    }
                    return EmbeddingResult.Success(texts.map { floatArrayOf(1f, 0f) }, 2)
                }

                override fun close() {
                    closed.set(true)
                    if (generation == 1) firstEngineClosed.set(true)
                }
            }
        }

        val proxy = KoEnE5RetrievalOwner.proxy(null, fixtureRoot)
        val active = async {
            proxy.rank("synthetic active query", listOf(SemanticSegment("synthetic", "synthetic passage")), 1)
        }
        withTimeout(10_000) { activeInferenceEntered.await() }
        KoEnE5RetrievalOwner.close()
        assertTrue("close detaches the active owner engine", firstEngineClosed.get())
        releaseActiveInference.complete(Unit)
        assertNull(active.await().unavailableReason)

        val recreated = proxy.rank("synthetic active query", listOf(SemanticSegment("synthetic", "synthetic passage")), 1)
        assertNull(recreated.unavailableReason)
        assertEquals(2, engineCreations.get())
    }

    @Test
    fun syntheticPdfUsesRealPageExtractionAndRejectsChangedOrDeletedSelectedPage() = runBlocking {
        val pdf = File(fixtureRoot, "synthetic-two-page.pdf")
        val originalPages = listOf(
            "Weather observations record wind direction and air pressure only.",
            "Sejong created Hangul in 1443. Synthetic page two source.",
        )
        pdf.writeBytes(TinyPdfBuilder.build(originalPages))
        val indexStore = DocumentIndexStore(File(context.filesDir, "document_index"))
        ownedDerivedIndexFiles += indexStore.indexFileFor(pdf.absolutePath)
        ownedDerivedIndexFiles += indexStore.statusFileFor(pdf.absolutePath)
        val record = AttachmentRecord(pdf.name, "application/pdf", pdf.absolutePath)
        val repository = DocumentIntelligenceProvider.indexRepository(context)
        val coordinator = LocalRagCoordinator(
            textStore = FileLocalAttachmentTextStore(),
            pdfIndexSource = repository,
        )

        val preparation = coordinator.prepare("pdf-r1", "Sejong Hangul 1443", listOf(record))
        assertTrue("production PDF extraction/index path result=$preparation", preparation is LocalRagPreparationResult.Prepared)
        val frozen = preparation as LocalRagPreparationResult.Prepared
        val selectedPageTwo = frozen.selections.filter { it.chunk.pageNumber == 2 }
        assertTrue("retrieval must carry the matching real page into its selected chunk", selectedPageTwo.isNotEmpty())
        assertTrue(selectedPageTwo.any { it.source.excerpt.contains("Sejong created Hangul in 1443") })
        assertTrue(coordinator.validate(frozen.snapshot, listOf(record)))

        val ragBody = ChatPromptComposition.localizedRagUserContent(
            "Sejong Hangul 1443",
            frozen.selections,
            frozen.coverageByDoc,
        )
        assertTrue(ragBody.contains("page=2"))
        assertTrue(ragBody.contains("Sejong created Hangul in 1443"))
        val stored = storedAssistantContentWithLocalRag(
            "synthetic no-LLM response",
            TrustRoutingDecision.local(TrustRoutingReason.REASONING_OR_TRANSFORMATION),
            ragSelections = frozen.selections,
        )
        assertTrue(LocalRagSourceMetadata.parse(stored).any { it.pageNumber == 2 && it.excerpt.contains("Sejong created Hangul in 1443") })

        val sourceTimestamp = pdf.lastModified()
        pdf.writeBytes(
            TinyPdfBuilder.build(
                listOf(originalPages[0], "The royal court introduced a different synthetic calendar in 1446."),
            ),
        )
        pdf.setLastModified(sourceTimestamp + 5_000L)
        assertFalse("changed selected PDF page must invalidate frozen evidence", coordinator.validate(frozen.snapshot, listOf(record)))
        assertNull(selectRagForGeneration(frozen, listOf(record), coordinator::validate))

        assertTrue(pdf.delete())
        assertFalse("deleted PDF source must invalidate frozen evidence", coordinator.validate(frozen.snapshot, listOf(record)))
    }

    private fun attachment(file: File) = AttachmentRecord(file.name, "text/plain", file.absolutePath)

    private fun syntheticDocument(marker: String = "SYNTHETIC_PRIVATE_SENTINEL_RAG_R1"): String =
        "세종은 1443년에 한글을 창제했다. $marker\n" +
            ("기상 관측 장비는 합성 문서의 바람과 기압을 측정한다. ").repeat(150)
}
