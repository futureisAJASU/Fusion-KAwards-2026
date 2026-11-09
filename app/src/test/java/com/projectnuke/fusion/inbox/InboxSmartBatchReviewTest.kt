package com.projectnuke.fusion.inbox

import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.retrieval.EmbeddingFailureReason
import com.projectnuke.fusion.retrieval.EmbeddingResult
import com.projectnuke.fusion.retrieval.SymmetricEmbeddingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InboxSmartBatchReviewTest {
    private fun item(id: String, revision: Long = 10L, title: String = "제목 $id") = InboxItemEntity(
        id = id,
        captureType = "NOTE",
        title = title,
        content = "보관할 내용",
        originalUri = null,
        managedFileId = null,
        managedMimeType = null,
        sourceHandoffEventId = null,
        createdAt = 1L,
        updatedAt = revision,
        processingState = "READY",
    )

    private class FakeProposalSource(
        private val handler: suspend (List<InboxItemEntity>) -> List<BatchProposal>,
    ) : InboxSmartBatchProposalSource {
        var calls = 0
        override suspend fun proposeBatch(items: List<InboxItemEntity>): List<BatchProposal> {
            calls++
            return handler(items)
        }
    }

    private class FakeTriageDataSource(
        val rows: MutableMap<String, InboxItemEntity>,
    ) : InboxSmartTriageDataSource {
        val scriptedReads = mutableMapOf<String, ArrayDeque<InboxItemEntity?>>()
        var reads = 0
        var activeReads = 0
        var maxConcurrentReads = 0
        var getFailure: CancellationException? = null

        override suspend fun getInboxItem(id: String): InboxItemEntity? {
            reads++
            activeReads++
            maxConcurrentReads = maxOf(maxConcurrentReads, activeReads)
            try {
                yield()
                getFailure?.let { throw it }
                val script = scriptedReads[id]
                return if (script != null && script.isNotEmpty()) script.removeFirst() else rows[id]
            } finally {
                activeReads--
            }
        }
        override suspend fun recentProjects(limit: Int) = emptyList<SmartProjectRow>()
        override suspend fun recentKnowledgeTitles(projectId: Long, limit: Int) = emptyList<String>()
        override suspend fun recentTaskTitles(projectId: Long, limit: Int) = emptyList<String>()
        override suspend fun existingTags(limit: Int) = emptyList<SmartTagRow>()
        override suspend fun inboxTagKeys(itemId: String) = emptySet<String>()
    }

    private class NoNetworkEmbeddings : SymmetricEmbeddingSource {
        var calls = 0
        override suspend fun embedSources(texts: List<String>): EmbeddingResult {
            calls++
            return EmbeddingResult.Failure(EmbeddingFailureReason.ASSET_MISSING, "test")
        }
    }

    private fun ready(snapshot: InboxItemEntity): BatchProposal {
        return BatchProposal(
            snapshot = snapshot,
            status = InboxSmartBatchItemStatus.READY,
            proposal = InboxSmartTriageProposal(
                itemId = snapshot.id,
                itemRevision = snapshot.updatedAt,
                primaryAction = InboxSmartActionKind.KEEP_IN_INBOX,
                reason = "안전하게 보관합니다.",
                projectCandidates = emptyList(),
                tagSuggestions = emptyList(),
                semanticAvailable = false,
            ),
        )
    }

    @Test
    fun `bulk action is enabled only for two through twelve and explains the upper bound`() {
        assertFalse(InboxSmartBatchReviewPolicy.canStart(1))
        assertTrue((2..12).all(InboxSmartBatchReviewPolicy::canStart))
        assertFalse(InboxSmartBatchReviewPolicy.canStart(13))
        assertEquals("정리 제안은 한 번에 최대 12개까지 가능합니다.", InboxSmartBatchReviewPolicy.bulkActionLabel(13))
        assertEquals("정리 제안 (2개 이상 선택)", InboxSmartBatchReviewPolicy.bulkActionLabel(1))
        assertEquals("정리 제안", InboxSmartBatchReviewPolicy.bulkActionLabel(2))
    }

    @Test
    fun `controller freezes full snapshots and rejects invalid batch sizes`() = runTest {
        val source = FakeProposalSource { rows -> rows.map(::ready) }
        val controller = InboxSmartBatchReviewController(source, this, newRunId = { "run-1" })
        val one = item("one")
        assertFalse(controller.start(listOf(one)))
        assertFalse(controller.start((1..13).map { item("$it") }))
        assertEquals(0, source.calls)

        val two = item("two")
        val selected = mutableListOf(one, two)
        assertTrue(controller.start(selected))
        selected[0] = item("one", revision = 11L, title = "다른 내용")
        advanceUntilIdle()

        assertEquals(listOf(one, two), controller.state.value.sessionKey?.items)
        assertEquals(listOf(one, two), controller.state.value.proposals.map { it.snapshot })
        assertEquals(2, controller.state.value.proposals.size)
    }

    @Test
    fun `exact snapshot changed before generation is stale`() = runTest {
        val frozen = item("a")
        val changed = frozen.copy(title = "수정된 제목", updatedAt = 11L)
        val source = FakeTriageDataSource(mutableMapOf(frozen.id to changed)).apply {
            scriptedReads[frozen.id] = ArrayDeque(listOf(changed))
        }
        val embeddings = NoNetworkEmbeddings()
        val coordinator = InboxSmartTriageCoordinator(source)

        val result = coordinator.proposeSnapshot(frozen, embeddings)

        assertEquals(InboxSmartTriageResult.Stale, result)
        assertEquals(0, embeddings.calls)
    }

    @Test
    fun `exact snapshot changed during generation is stale and not returned ready`() = runTest {
        val frozen = item("a")
        val changed = frozen.copy(content = "내용이 바뀜", updatedAt = 11L)
        val source = FakeTriageDataSource(mutableMapOf(frozen.id to changed)).apply {
            scriptedReads[frozen.id] = ArrayDeque(listOf(frozen, changed))
        }
        val coordinator = InboxSmartTriageCoordinator(source)

        val result = coordinator.proposeSnapshot(frozen, NoNetworkEmbeddings())

        assertEquals(InboxSmartTriageResult.Stale, result)
        assertEquals(2, source.reads)
    }

    @Test
    fun `batch preserves valid B when A is stale and every proposal revision matches`() = runTest {
        val stale = item("a")
        val valid = item("b", revision = 20L)
        val source = FakeTriageDataSource(mutableMapOf(
            stale.id to stale.copy(updatedAt = 11L), valid.id to valid,
        )).apply {
            scriptedReads[stale.id] = ArrayDeque(listOf(stale.copy(updatedAt = 11L)))
        }
        val batch = InboxSmartBatchReviewCoordinator(
            InboxSmartTriageCoordinator(source), NoNetworkEmbeddings(),
        )

        val result = batch.proposeBatch(listOf(stale, valid))

        assertEquals(listOf(InboxSmartBatchItemStatus.STALE, InboxSmartBatchItemStatus.READY), result.map { it.status })
        assertNull(result[0].proposal)
        assertEquals(valid.id, result[1].proposal?.itemId)
        assertEquals(valid.updatedAt, result[1].proposal?.itemRevision)
    }

    @Test
    fun `oversized batch returns before reading sources`() = runTest {
        val rows = (1..13).map { item("$it") }
        val source = FakeTriageDataSource(rows.associateBy { it.id }.toMutableMap())
        val coordinator = InboxSmartBatchReviewCoordinator(
            InboxSmartTriageCoordinator(source), NoNetworkEmbeddings(),
        )

        assertTrue(coordinator.proposeBatch(rows).isEmpty())
        assertEquals(0, source.reads)
    }

    @Test
    fun `batch generation is sequential and remains read only and local`() = runTest {
        val rows = listOf(item("a"), item("b"), item("c"))
        val source = FakeTriageDataSource(rows.associateBy { it.id }.toMutableMap())
        val embeddings = NoNetworkEmbeddings()
        val coordinator = InboxSmartBatchReviewCoordinator(
            InboxSmartTriageCoordinator(source), embeddings,
        )

        val result = coordinator.proposeBatch(rows)

        assertEquals(3, result.size)
        assertEquals(listOf(10L, 10L, 10L), result.map { it.proposal?.itemRevision })
        assertEquals(6, source.reads)
        assertEquals(1, source.maxConcurrentReads)
        assertEquals(0, embeddings.calls)
    }

    @Test
    fun `triage data source exposes reads only so proposal generation has no write owner`() {
        assertEquals(
            setOf("getInboxItem", "recentProjects", "recentKnowledgeTitles", "recentTaskTitles", "existingTags", "inboxTagKeys"),
            InboxSmartTriageDataSource::class.java.declaredMethods.map { it.name }.toSet(),
        )
    }

    @Test
    fun `opening source freshness check rejects a changed item`() = runTest {
        val snapshot = item("a")
        val proposal = ready(snapshot).proposal!!
        val changed = snapshot.copy(title = "새 제목", updatedAt = 11L)
        val source = FakeTriageDataSource(mutableMapOf(snapshot.id to changed))

        assertNull(InboxSmartTriageCoordinator.freshItemFor(source, proposal, snapshot))
    }

    @Test
    fun `skip changes one item and preserves other review states`() = runTest {
        val rows = listOf(item("a"), item("b"), item("c"))
        val controller = InboxSmartBatchReviewController(
            FakeProposalSource { selected -> selected.map(::ready) }, this, newRunId = { "run" },
        )
        assertTrue(controller.start(rows))
        advanceUntilIdle()
        val session = controller.state.value.sessionKey
        controller.markSkipped("b", session)

        assertEquals(
            listOf(InboxSmartBatchItemStatus.READY, InboxSmartBatchItemStatus.SKIPPED, InboxSmartBatchItemStatus.READY),
            controller.state.value.proposals.map { it.status },
        )
    }

    @Test
    fun `mismatched revision is downgraded and never published as ready`() = runTest {
        val a = item("a", revision = 10L)
        val b = item("b", revision = 20L)
        val mismatched = ready(a).copy(proposal = ready(a).proposal!!.copy(itemRevision = 99L))
        val controller = InboxSmartBatchReviewController(
            FakeProposalSource { listOf(mismatched, ready(b)) }, this,
        )
        controller.start(listOf(a, b))
        advanceUntilIdle()

        assertEquals(InboxSmartBatchItemStatus.STALE, controller.state.value.proposals[0].status)
        assertNull(controller.state.value.proposals[0].proposal)
        assertEquals(InboxSmartBatchItemStatus.READY, controller.state.value.proposals[1].status)
    }

    @Test
    fun `dismiss cancels job clears session and late completion cannot publish`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelled = false
        val source = FakeProposalSource { rows ->
            entered.complete(Unit)
            try {
                withContext(NonCancellable) { release.await() }
            } finally {
                cancelled = !currentCoroutineContext().isActive
            }
            rows.map(::ready)
        }
        val controller = InboxSmartBatchReviewController(source, this)
        controller.start(listOf(item("a"), item("b")))
        runCurrent()
        assertNotNull(controller.state.value.sessionKey)
        assertTrue(entered.isCompleted)

        controller.dismiss()
        assertNull(controller.state.value.sessionKey)
        assertTrue(controller.state.value.proposals.isEmpty())
        release.complete(Unit)
        advanceUntilIdle()

        assertTrue(cancelled)
        assertNull(controller.state.value.sessionKey)
        assertTrue(controller.state.value.proposals.isEmpty())
    }

    @Test
    fun `composition disposal cancels the same active owner`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        val source = FakeProposalSource {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val controller = InboxSmartBatchReviewController(source, this)
        controller.start(listOf(item("a"), item("b")))
        runCurrent()
        assertTrue(entered.isCompleted)

        controller.dispose()
        runCurrent()

        assertTrue(cancelled)
        assertNull(controller.state.value.sessionKey)
    }

    @Test
    fun `old session result cannot overwrite newer session`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val source = FakeProposalSource { rows ->
            if (rows.first().id == "old-a") {
                firstEntered.complete(Unit)
                withContext(NonCancellable) { firstRelease.await() }
            } else {
                secondEntered.complete(Unit)
                secondRelease.await()
            }
            rows.map(::ready)
        }
        var run = 0
        val controller = InboxSmartBatchReviewController(source, this) { "run-${++run}" }
        controller.start(listOf(item("old-a"), item("old-b")))
        runCurrent()
        val newRows = listOf(item("new-a"), item("new-b"))
        controller.start(newRows)
        runCurrent()
        assertTrue(firstEntered.isCompleted)
        assertTrue(secondEntered.isCompleted)
        val newKey = controller.state.value.sessionKey

        firstRelease.complete(Unit)
        runCurrent()
        assertEquals(newKey, controller.state.value.sessionKey)
        assertTrue(controller.state.value.loading)
        assertTrue(controller.state.value.proposals.isEmpty())

        secondRelease.complete(Unit)
        advanceUntilIdle()
        assertEquals(newKey, controller.state.value.sessionKey)
        assertEquals(newRows, controller.state.value.proposals.map { it.snapshot })
    }

    @Test
    fun `proposal source cancellation propagates`() = runTest {
        val dataSource = FakeTriageDataSource(mutableMapOf()).apply {
            getFailure = CancellationException("test cancellation")
        }
        val controller = InboxSmartBatchReviewCoordinator(
            InboxSmartTriageCoordinator(dataSource),
            NoNetworkEmbeddings(),
        )
        try {
            controller.proposeBatch(listOf(item("a"), item("b")))
            assertTrue("expected cancellation", false)
        } catch (_: CancellationException) {
            // The production coordinator does not convert cancellation to UNAVAILABLE.
        }
    }
}
