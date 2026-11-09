package com.projectnuke.fusion.inbox

import com.projectnuke.fusion.data.InboxItemEntity
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.data.ProjectTaskEntity
import com.projectnuke.fusion.data.ProjectTaskInsertResult
import com.projectnuke.fusion.data.ReviewCardInsertResult
import com.projectnuke.fusion.data.StudyReviewCardEntity
import com.projectnuke.fusion.retrieval.EmbeddingFailureReason
import com.projectnuke.fusion.retrieval.EmbeddingResult
import com.projectnuke.fusion.retrieval.SymmetricEmbeddingSource
import com.projectnuke.fusion.study.QuickReviewCardCaptureController
import com.projectnuke.fusion.study.QuickReviewCardDraft
import com.projectnuke.fusion.study.QuickReviewCardPolicy
import com.projectnuke.fusion.study.QuickReviewCardPreparation
import com.projectnuke.fusion.study.QuickReviewCardProposal
import com.projectnuke.fusion.study.QuickReviewCardWriteResult
import com.projectnuke.fusion.study.QuickReviewCardWriter
import com.projectnuke.fusion.study.ReviewCardOrigin
import com.projectnuke.fusion.tasks.ProjectTaskCaptureController
import com.projectnuke.fusion.tasks.ProjectTaskDraft
import com.projectnuke.fusion.tasks.ProjectTaskPolicy
import com.projectnuke.fusion.tasks.ProjectTaskPreparation
import com.projectnuke.fusion.tasks.ProjectTaskProposal
import com.projectnuke.fusion.tasks.ProjectTaskWriteResult
import com.projectnuke.fusion.tasks.ProjectTaskWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Corrective v1 regression tests.
 *
 * Dense-embedding realism: unrelated candidates score clearly positive
 * cosine here (0.55 / 0.40), never convenient zero. Positive cosine alone
 * must admit nothing.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InboxSmartAdmissionTest {

    // ---------- fakes ----------

    private class FakeDataSource(
        var item: InboxItemEntity? = null,
        var projects: List<SmartProjectRow> = emptyList(),
        var knowledgeTitles: Map<Long, List<String>> = emptyMap(),
        var taskTitles: Map<Long, List<String>> = emptyMap(),
        var tags: List<SmartTagRow> = emptyList(),
        var linkedKeys: Set<String> = emptySet(),
        var knowledgeFailure: Throwable? = null,
        var taskFailure: Throwable? = null,
        var tagKeysFailure: Throwable? = null,
    ) : InboxSmartTriageDataSource {
        override suspend fun getInboxItem(id: String): InboxItemEntity? = item?.takeIf { it.id == id }
        override suspend fun recentProjects(limit: Int): List<SmartProjectRow> = projects.take(limit)
        override suspend fun recentKnowledgeTitles(projectId: Long, limit: Int): List<String> {
            knowledgeFailure?.let { throw it }
            return knowledgeTitles[projectId].orEmpty().take(limit)
        }
        override suspend fun recentTaskTitles(projectId: Long, limit: Int): List<String> {
            taskFailure?.let { throw it }
            return taskTitles[projectId].orEmpty().take(limit)
        }
        override suspend fun existingTags(limit: Int): List<SmartTagRow> = tags.take(limit)
        override suspend fun inboxTagKeys(itemId: String): Set<String> {
            tagKeysFailure?.let { throw it }
            return linkedKeys
        }
    }

    private class FakeEmbeddings(val handler: (List<String>) -> EmbeddingResult) : SymmetricEmbeddingSource {
        override suspend fun embedSources(texts: List<String>): EmbeddingResult = handler(texts)
    }

    companion object {
        private val V_ITEM = floatArrayOf(1f, 0f, 0f, 0f)
        private fun vec(cosine: Float): FloatArray {
            val y = kotlin.math.sqrt((1 - cosine * cosine).toDouble()).toFloat()
            return floatArrayOf(cosine, y, 0f, 0f)
        }

        /** Realistic dense vectors: unrelated candidates are clearly positive. */
        private fun realisticHandler(texts: List<String>): EmbeddingResult = EmbeddingResult.Success(
            texts.mapIndexed { index, text ->
                if (index == 0) V_ITEM else realisticVector(text)
            },
            4,
        )

        private fun realisticVector(text: String): FloatArray = when {
            text.contains("일번프로젝트") -> vec(0.90f)
            text.contains("관련프로젝트") -> vec(0.75f)
            text.contains("중간프로젝트") -> vec(0.62f)
            text.contains("무관프로젝트") -> vec(0.55f)
            text.contains("Fusion관련") -> vec(0.75f)
            text.contains("무관태그") -> vec(0.40f)
            else -> vec(0.55f)
        }
    }

    private fun realisticEmbeddings() = FakeEmbeddings(::realisticHandler)

    private fun item(
        id: String = "inbox-1",
        type: String = "NOTE",
        title: String = "제목",
        content: String = "내용",
        managedFileId: String? = null,
        updatedAt: Long = 100L,
    ) = InboxItemEntity(
        id = id,
        captureType = type,
        title = title,
        content = content,
        originalUri = if (type == "URL" || type == "WEB_CLIP") "https://example.com/a" else null,
        managedFileId = managedFileId,
        managedMimeType = managedFileId?.let { "application/pdf" },
        sourceHandoffEventId = null,
        createdAt = 100L,
        updatedAt = updatedAt,
        processingState = "READY",
    )

    private fun fusionNote(extra: String = ""): InboxItemEntity {
        val body = ("Fusion 온디바이스AI Fusion관련 정리 기록. " +
            "로컬 검색과 임베딩으로 자료를 정리하는 긴 참고 기록입니다. $extra ").repeat(10)
        return item(title = "Fusion 정리 기록", content = body)
    }

    private fun relevantProject(id: Long = 1L) = SmartProjectRow(id, "관련프로젝트 Fusion", "Fusion 안내")
    private fun unrelatedProject(id: Long = 2L) = SmartProjectRow(id, "무관프로젝트 요리", "요리 안내")

    private fun readyOf(result: InboxSmartTriageResult): InboxSmartTriageProposal {
        assertTrue("expected Ready, got $result", result is InboxSmartTriageResult.Ready)
        return (result as InboxSmartTriageResult.Ready).proposal
    }

    // ---------- realistic semantic admission (required 1-9) ----------

    @Test
    fun `positive cosine alone does not admit a Project`() = runTest {
        val source = FakeDataSource(
            item = fusionNote(),
            projects = listOf(unrelatedProject()),
            knowledgeTitles = mapOf(2L to listOf("요리 자료")),
        )
        val proposal = readyOf(InboxSmartTriageCoordinator(source).propose("inbox-1", realisticEmbeddings()))
        assertTrue(proposal.projectCandidates.isEmpty())
    }

    @Test
    fun `positive cosine alone does not admit a tag`() = runTest {
        val source = FakeDataSource(
            item = fusionNote(),
            tags = listOf(SmartTagRow("unrel", "무관태그")),
        )
        val proposal = readyOf(InboxSmartTriageCoordinator(source).propose("inbox-1", realisticEmbeddings()))
        assertTrue(proposal.tagSuggestions.isEmpty())
    }

    @Test
    fun `unrelated positive Project does not force file DOCUMENT to PROJECT`() = runTest {
        val source = FakeDataSource(
            item = item(type = "DOCUMENT", title = "문서", content = "본문", managedFileId = "m-1"),
            projects = listOf(unrelatedProject()),
            knowledgeTitles = mapOf(2L to listOf("요리 자료")),
        )
        val proposal = readyOf(InboxSmartTriageCoordinator(source).propose("inbox-1", realisticEmbeddings()))
        assertTrue(proposal.projectCandidates.isEmpty())
        assertEquals(InboxSmartActionKind.KEEP_IN_INBOX, proposal.primaryAction)
    }

    @Test
    fun `clearly relevant Project is admitted`() = runTest {
        val source = FakeDataSource(
            item = fusionNote(),
            projects = listOf(relevantProject(), unrelatedProject()),
            knowledgeTitles = mapOf(1L to listOf("Fusion 자료"), 2L to listOf("요리 자료")),
        )
        val proposal = readyOf(InboxSmartTriageCoordinator(source).propose("inbox-1", realisticEmbeddings()))
        assertEquals(listOf(1L), proposal.projectCandidates.map { it.projectId })
        assertTrue(proposal.semanticAvailable)
    }

    @Test
    fun `strong WEB_CLIP relevance may still outrank SAVED_KNOWLEDGE`() = runTest {
        val clip = item(type = "WEB_CLIP", title = "Fusion 기사", content = ("Fusion 온디바이스AI 관련 긴 기사 본문. ").repeat(20))
        val source = FakeDataSource(
            item = clip,
            projects = listOf(relevantProject(7), unrelatedProject()),
            knowledgeTitles = mapOf(7L to listOf("Fusion 자료"), 2L to listOf("요리 자료")),
        )
        val proposal = readyOf(InboxSmartTriageCoordinator(source).propose("inbox-1", realisticEmbeddings()))
        assertEquals(InboxSmartActionKind.PROJECT, proposal.primaryAction)
        assertEquals(7L, proposal.projectCandidates.first().projectId)
    }

    @Test
    fun `weak WEB_CLIP Project does not outrank Saved Knowledge`() = runTest {
        val clip = item(type = "WEB_CLIP", title = "Fusion 기사", content = ("Fusion 관련 긴 기사 본문. ").repeat(20))
        val source = FakeDataSource(
            item = clip,
            projects = listOf(unrelatedProject()),
            knowledgeTitles = mapOf(2L to listOf("요리 자료")),
        )
        val proposal = readyOf(InboxSmartTriageCoordinator(source).propose("inbox-1", realisticEmbeddings()))
        assertEquals(InboxSmartActionKind.SAVED_KNOWLEDGE, proposal.primaryAction)
        assertTrue(proposal.projectCandidates.isEmpty())
    }

    @Test
    fun `candidate ordering remains semantic among admitted candidates`() = runTest {
        val source = FakeDataSource(
            item = fusionNote(),
            projects = listOf(
                SmartProjectRow(3, "중간프로젝트 Fusion", "Fusion 안내"),
                SmartProjectRow(9, "관련프로젝트 Fusion", "Fusion 안내"),
                SmartProjectRow(1, "일번프로젝트 Fusion", "Fusion 안내"),
                SmartProjectRow(2, "무관프로젝트 요리", "요리 안내"),
            ),
            knowledgeTitles = mapOf(
                1L to listOf("Fusion 자료"),
                3L to listOf("Fusion 자료"),
                9L to listOf("Fusion 자료"),
                2L to listOf("요리 자료"),
            ),
        )
        val proposal = readyOf(InboxSmartTriageCoordinator(source).propose("inbox-1", realisticEmbeddings()))
        // Semantic order kept; the unrelated positive is rejected.
        assertEquals(listOf(1L, 9L, 3L), proposal.projectCandidates.map { it.projectId })
    }

    @Test
    fun `relevant existing tag admitted while unrelated positive tag rejected`() = runTest {
        val source = FakeDataSource(
            item = fusionNote(),
            tags = listOf(SmartTagRow("rel", "Fusion관련"), SmartTagRow("unrel", "무관태그")),
        )
        val proposal = readyOf(InboxSmartTriageCoordinator(source).propose("inbox-1", realisticEmbeddings()))
        assertEquals(listOf("Fusion관련"), proposal.tagSuggestions.map { it.value })
    }

    @Test
    fun `admission signals separate rank from eligibility`() {
        val signals = InboxSemanticAdmissionPolicy.projectSignals(
            scores = listOf(0.75f, 0.55f),
            corroborated = listOf(true, false),
        )
        assertEquals(1, signals[0].rank)
        assertEquals(2, signals[1].rank)
        assertEquals(0.20f, signals[0].separationFromNext!!, 0.001f)
        assertNull(signals[1].separationFromNext)
        assertEquals(listOf(0), InboxSemanticAdmissionPolicy.admittedProjectPositions(signals))
        // Rank bound: separated and corroborated, yet rank 4+ never admitted.
        val crowded = InboxSemanticAdmissionPolicy.projectSignals(
            scores = listOf(0.90f, 0.80f, 0.70f, 0.60f, 0.50f),
            corroborated = listOf(true, true, true, true, true),
        )
        assertEquals(listOf(0, 1, 2), InboxSemanticAdmissionPolicy.admittedProjectPositions(crowded))
        // Unseparated top rank is not admitted even when corroborated.
        val flat = InboxSemanticAdmissionPolicy.projectSignals(
            scores = listOf(0.75f, 0.75f),
            corroborated = listOf(true, true),
        )
        assertTrue(InboxSemanticAdmissionPolicy.admittedProjectPositions(flat).isEmpty())
        assertFalse(InboxSemanticAdmissionPolicy.strongTopProject(flat))
    }

    // ---------- reviewed actions: controllers perform zero insert before Save ----------

    private class FakeTaskWriter(
        var result: ProjectTaskWriteResult = ProjectTaskWriteResult.Stored,
    ) : ProjectTaskWriter {
        val calls = mutableListOf<ProjectTaskProposal>()
        override suspend fun save(proposal: ProjectTaskProposal, archiveAccessIncluded: Boolean): ProjectTaskWriteResult {
            calls += proposal
            return result
        }
    }

    private class FakeReviewWriter(
        var result: QuickReviewCardWriteResult = QuickReviewCardWriteResult.Stored,
    ) : QuickReviewCardWriter {
        val calls = mutableListOf<QuickReviewCardProposal>()
        override suspend fun save(proposal: QuickReviewCardProposal, archiveAccessIncluded: Boolean): QuickReviewCardWriteResult {
            calls += proposal
            return result
        }
    }

    private class FakeTaskStore(
        val inbox: MutableMap<String, InboxItemEntity> = mutableMapOf(),
        val projects: MutableSet<Long> = mutableSetOf(),
        val tasks: MutableList<ProjectTaskEntity> = mutableListOf(),
        var failDelete: Boolean = false,
    ) : InboxTaskSettlementStore {
        var inserts = 0
        var deletes = 0
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        override suspend fun getInboxItem(id: String): InboxItemEntity? = inbox[id]
        override suspend fun getProjectById(id: Long): ProjectEntity? =
            projects.find { it == id }?.let { ProjectEntity(it, "P$it", "", 1L, 1L) }
        override suspend fun insertTask(task: ProjectTaskEntity): ProjectTaskInsertResult {
            inserts++
            tasks += task
            return ProjectTaskInsertResult.Inserted(1L)
        }
        override suspend fun deleteInboxItem(id: String): Int {
            deletes++
            if (failDelete) return 0
            return if (inbox.remove(id) != null) 1 else 0
        }
    }

    private class FakeReviewStore(
        val inbox: MutableMap<String, InboxItemEntity> = mutableMapOf(),
        val projects: MutableSet<Long> = mutableSetOf(),
        val cards: MutableList<StudyReviewCardEntity> = mutableListOf(),
    ) : InboxReviewCardSettlementStore {
        var inserts = 0
        var deletes = 0
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        override suspend fun getInboxItem(id: String): InboxItemEntity? = inbox[id]
        override suspend fun getProjectById(id: Long): ProjectEntity? =
            projects.find { it == id }?.let { ProjectEntity(it, "P$it", "", 1L, 1L) }
        override suspend fun insertReviewCard(card: StudyReviewCardEntity): ReviewCardInsertResult {
            inserts++
            cards += card
            return ReviewCardInsertResult.Inserted
        }
        override suspend fun deleteInboxItem(id: String): Int {
            deletes++
            return if (inbox.remove(id) != null) 1 else 0
        }
    }

    private fun taskProposal(projectId: Long, title: String, note: String): ProjectTaskProposal {
        val prepared = ProjectTaskPolicy.prepare(
            ProjectTaskDraft(projectId, "P", title, note), title, note, 1000L,
        )
        assertTrue(prepared is ProjectTaskPreparation.Ready)
        return (prepared as ProjectTaskPreparation.Ready).proposal
    }

    private fun reviewProposal(front: String, back: String): QuickReviewCardProposal {
        val prepared = QuickReviewCardPolicy.prepare(
            QuickReviewCardDraft(front = front, back = back, sourceKind = ReviewCardOrigin.USER_TEXT),
            front, back, 1000L,
        )
        assertTrue(prepared is QuickReviewCardPreparation.Ready)
        return (prepared as QuickReviewCardPreparation.Ready).proposal
    }

    @Test
    fun `REVIEW_CARD action performs zero insert before explicit Save`() = runTest {
        val writer = FakeReviewWriter()
        val controller = QuickReviewCardCaptureController(writer, this)
        controller.begin(
            QuickReviewCardDraft(front = "T", back = "C", sourceKind = ReviewCardOrigin.USER_TEXT),
            archiveAccessIncluded = false,
        )
        advanceUntilIdle()
        assertTrue(writer.calls.isEmpty())
    }

    @Test
    fun `dismissing Review Card editor leaves Inbox unchanged`() = runTest {
        val stored = FakeReviewStore(inbox = mutableMapOf("inbox-1" to item()))
        val gateway = InboxReviewCardSettlementGateway(stored)
        val controller = QuickReviewCardCaptureController(gateway, this)
        gateway.snapshot = stored.inbox["inbox-1"]
        controller.begin(
            QuickReviewCardDraft(front = "T", back = "C", sourceKind = ReviewCardOrigin.USER_TEXT),
            archiveAccessIncluded = false,
        )
        controller.dismiss()
        advanceUntilIdle()
        assertEquals(0, stored.inserts)
        assertEquals(1, stored.inbox.size)
    }

    @Test
    fun `editing front and back changes the saved reviewed copy`() = runTest {
        val writer = FakeReviewWriter()
        val controller = QuickReviewCardCaptureController(writer, this)
        controller.begin(
            QuickReviewCardDraft(front = "원본 앞", back = "원본 뒤", sourceKind = ReviewCardOrigin.USER_TEXT),
            archiveAccessIncluded = false,
        )
        controller.updateFront("고친 질문?")
        controller.updateBack("고친 답.")
        controller.save(now = 2000L)
        advanceUntilIdle()
        assertEquals(1, writer.calls.size)
        assertEquals("고친 질문?", writer.calls.single().front)
        assertEquals("고친 답.", writer.calls.single().back)
    }

    @Test
    fun `successful Review Card save consumes only the exact Inbox revision`() = runTest {
        val frozen = item()
        val stored = FakeReviewStore(inbox = mutableMapOf("inbox-1" to frozen, "other" to item(id = "other")))
        val gateway = InboxReviewCardSettlementGateway(stored)
        gateway.snapshot = frozen
        val result = gateway.save(reviewProposal("앞", "뒤"), archiveAccessIncluded = false)
        assertEquals(QuickReviewCardWriteResult.Stored, result)
        assertEquals(1, stored.inserts)
        assertEquals("뒤", stored.cards.single().back)
        assertFalse(stored.inbox.containsKey("inbox-1"))
        assertTrue(stored.inbox.containsKey("other"))
    }

    @Test
    fun `stale Inbox revision prevents Review Card settlement`() = runTest {
        val frozen = item()
        val stored = FakeReviewStore(inbox = mutableMapOf("inbox-1" to frozen))
        val gateway = InboxReviewCardSettlementGateway(stored)
        gateway.snapshot = frozen
        // Item edited while the editor was open.
        stored.inbox["inbox-1"] = frozen.copy(content = frozen.content + " 추가", updatedAt = 101L)
        val result = gateway.save(reviewProposal("앞", "뒤"), archiveAccessIncluded = false)
        assertEquals(QuickReviewCardWriteResult.SourceUnavailable, result)
        assertEquals(0, stored.inserts)
        assertEquals(0, stored.deletes)
        assertEquals(1, stored.inbox.size)
    }

    @Test
    fun `deleted Inbox prevents Review Card settlement`() = runTest {
        val frozen = item()
        val stored = FakeReviewStore(inbox = mutableMapOf("inbox-1" to frozen))
        val gateway = InboxReviewCardSettlementGateway(stored)
        gateway.snapshot = frozen
        stored.inbox.clear()
        val result = gateway.save(reviewProposal("앞", "뒤"), archiveAccessIncluded = false)
        assertEquals(QuickReviewCardWriteResult.SourceUnavailable, result)
        assertEquals(0, stored.inserts)
    }

    @Test
    fun `PROJECT_TASK selection performs zero Task insert`() = runTest {
        val writer = FakeTaskWriter()
        val controller = ProjectTaskCaptureController(writer, this)
        controller.begin(ProjectTaskDraft(5L, "P5", "제목", "내용"), archiveAccessIncluded = false)
        advanceUntilIdle()
        assertTrue(writer.calls.isEmpty())
        assertEquals("제목", controller.state.value.title)
        assertEquals("내용", controller.state.value.note)
    }

    @Test
    fun `user may edit title and note before Task Save`() = runTest {
        val writer = FakeTaskWriter()
        val controller = ProjectTaskCaptureController(writer, this)
        controller.begin(ProjectTaskDraft(5L, "P5", "원본 제목", "원본 메모"), archiveAccessIncluded = false)
        controller.updateTitle("고친 제목")
        controller.updateNote("고친 메모")
        controller.save()
        advanceUntilIdle()
        assertEquals(1, writer.calls.size)
        assertEquals("고친 제목", writer.calls.single().title)
        assertEquals("고친 메모", writer.calls.single().note)
    }

    @Test
    fun `dismissing Task editor leaves Inbox unchanged`() = runTest {
        val stored = FakeTaskStore(
            inbox = mutableMapOf("inbox-1" to item()),
            projects = mutableSetOf(5L),
        )
        val gateway = InboxTaskSettlementGateway(stored)
        val controller = ProjectTaskCaptureController(gateway, this)
        gateway.snapshot = stored.inbox["inbox-1"]
        controller.begin(ProjectTaskDraft(5L, "P5", "제목", "내용"), archiveAccessIncluded = false)
        controller.dismiss()
        advanceUntilIdle()
        assertEquals(0, stored.inserts)
        assertEquals(1, stored.inbox.size)
    }

    @Test
    fun `successful Task save consumes only the exact Inbox revision`() = runTest {
        val frozen = item()
        val stored = FakeTaskStore(
            inbox = mutableMapOf("inbox-1" to frozen, "other" to item(id = "other")),
            projects = mutableSetOf(5L),
        )
        val gateway = InboxTaskSettlementGateway(stored)
        gateway.snapshot = frozen
        val result = gateway.save(taskProposal(5L, "할 일", "메모"), archiveAccessIncluded = false)
        assertEquals(ProjectTaskWriteResult.Stored, result)
        assertEquals(1, stored.inserts)
        assertEquals("할 일", stored.tasks.single().title)
        assertFalse(stored.inbox.containsKey("inbox-1"))
        assertTrue(stored.inbox.containsKey("other"))
    }

    @Test
    fun `recommended Project may be changed before Task Save`() = runTest {
        val frozen = item()
        val stored = FakeTaskStore(
            inbox = mutableMapOf("inbox-1" to frozen),
            projects = mutableSetOf(5L, 9L),
        )
        val gateway = InboxTaskSettlementGateway(stored)
        gateway.snapshot = frozen
        val result = gateway.save(taskProposal(9L, "할 일", "메모"), archiveAccessIncluded = false)
        assertEquals(ProjectTaskWriteResult.Stored, result)
        assertEquals(9L, stored.tasks.single().projectId)
    }

    @Test
    fun `stale Inbox revision prevents Task settlement`() = runTest {
        val frozen = item()
        val stored = FakeTaskStore(
            inbox = mutableMapOf("inbox-1" to frozen),
            projects = mutableSetOf(5L),
        )
        val gateway = InboxTaskSettlementGateway(stored)
        gateway.snapshot = frozen
        stored.inbox["inbox-1"] = frozen.copy(title = "바뀐 제목", updatedAt = 102L)
        val result = gateway.save(taskProposal(5L, "할 일", "메모"), archiveAccessIncluded = false)
        assertEquals(ProjectTaskWriteResult.SourceUnavailable, result)
        assertEquals(0, stored.inserts)
        assertEquals(0, stored.deletes)
        assertEquals(1, stored.inbox.size)
    }

    @Test
    fun `gateway without frozen snapshot fails closed`() = runTest {
        val taskStore = FakeTaskStore(
            inbox = mutableMapOf("inbox-1" to item()),
            projects = mutableSetOf(5L),
        )
        assertEquals(
            ProjectTaskWriteResult.SourceUnavailable,
            InboxTaskSettlementGateway(taskStore).save(taskProposal(5L, "T", "N"), false),
        )
        assertEquals(0, taskStore.inserts)
        val reviewStore = FakeReviewStore(inbox = mutableMapOf("inbox-1" to item()))
        assertEquals(
            QuickReviewCardWriteResult.SourceUnavailable,
            InboxReviewCardSettlementGateway(reviewStore).save(reviewProposal("F", "B"), false),
        )
        assertEquals(0, reviewStore.inserts)
    }

    // ---------- cancellation (required 22-26) ----------

    @Test
    fun `recentKnowledgeTitles cancellation propagates`() = runTest {
        val source = FakeDataSource(
            item = fusionNote(),
            projects = listOf(relevantProject()),
            knowledgeFailure = CancellationException("stop"),
        )
        try {
            InboxSmartTriageCoordinator(source).propose("inbox-1", realisticEmbeddings())
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `recentTaskTitles cancellation propagates`() = runTest {
        val source = FakeDataSource(
            item = fusionNote(),
            projects = listOf(relevantProject()),
            taskFailure = CancellationException("stop"),
        )
        try {
            InboxSmartTriageCoordinator(source).propose("inbox-1", realisticEmbeddings())
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `inboxTagKeys cancellation propagates`() = runTest {
        val source = FakeDataSource(
            item = fusionNote(),
            projects = listOf(relevantProject()),
            tagKeysFailure = CancellationException("stop"),
        )
        try {
            InboxSmartTriageCoordinator(source).propose("inbox-1", realisticEmbeddings())
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `recoverable read failures still fall back safely`() = runTest {
        val source = FakeDataSource(
            item = fusionNote(),
            projects = listOf(relevantProject()),
            knowledgeFailure = RuntimeException("io"),
            taskFailure = RuntimeException("io"),
            tagKeysFailure = RuntimeException("io"),
        )
        val proposal = readyOf(InboxSmartTriageCoordinator(source).propose("inbox-1", realisticEmbeddings()))
        // Thinner profiles still ground on the Project name; deterministic
        // primary survives; no mutation, no crash.
        assertEquals(InboxSmartActionKind.SAVED_KNOWLEDGE, proposal.primaryAction)
        assertTrue(proposal.semanticAvailable)
    }
}
