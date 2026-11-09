package com.projectnuke.fusion.knowledge

import com.projectnuke.fusion.data.SavedKnowledgeEntity
import com.projectnuke.fusion.data.SavedKnowledgeNoteSourceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectKnowledgeHealthRepositoryTest {
    @Test
    fun `assistant answer requires exact conversation message tuple and assistant role`() = runBlocking {
        val row = row("answer", "ASSISTANT_ANSWER", conversationId = 12, messageId = 34)
        val exact = ProjectKnowledgeAssistantSourceProof(12, false, 34, 12, "assistant")
        assertTrue(ProjectKnowledgeHealthPolicy.assistantStatus(row, exact, archiveAllowed = false) ==
            ProjectKnowledgeHealthPolicy.AssistantStatus.AVAILABLE)

        listOf(
            exact.copy(messageId = 35),
            exact.copy(messageConversationId = 99),
            exact.copy(messageRole = "user"),
            exact.copy(conversationId = 99),
        ).forEach { wrong ->
            assertTrue(ProjectKnowledgeHealthPolicy.assistantStatus(row, wrong, false) ==
                ProjectKnowledgeHealthPolicy.AssistantStatus.MISSING)
        }
        val observations = repository(listOf(row), assistantProof = exact).scanProjectHealth(1)
        assertTrue(observations.isEmpty())
    }

    @Test
    fun `archived assistant source is restricted while missing tuple is unavailable`() = runBlocking {
        val row = row("answer", "ASSISTANT_ANSWER", conversationId = 12, messageId = 34)
        val archivedExact = ProjectKnowledgeAssistantSourceProof(12, true, 34, 12, "assistant")
        val restricted = repository(listOf(row), assistantProof = archivedExact).scanProjectHealth(1).single()
        assertEquals(ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.ARCHIVE_RESTRICTED, restricted.kind)
        assertEquals("보관함이 잠겨 원본을 열 수 없음", restricted.statement)
        assertEquals("접근 제한된 자료", restricted.knowledgeTitle)
        assertTrue(restricted.archiveProtected)
        assertTrue(repository(listOf(row), assistantProof = archivedExact, archiveAllowed = { true })
            .scanProjectHealth(1).isEmpty())

        val missing = repository(listOf(row), assistantProof = ProjectKnowledgeAssistantSourceProof(null, false, null, null, null))
            .scanProjectHealth(1).single()
        assertEquals(ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.SOURCE_UNAVAILABLE, missing.kind)
        assertEquals("원본 답변을 찾을 수 없음", missing.statement)
    }

    @Test
    fun `web clip distinguishes absent from malformed URL`() = runBlocking {
        val absent = repository(listOf(row("absent", "WEB_CLIP", sourceUrl = null))).scanProjectHealth(1).single()
        val malformed = repository(listOf(row("malformed", "WEB_CLIP", sourceUrl = "https://"))).scanProjectHealth(1).single()

        assertEquals("웹 출처 주소가 없음", absent.statement)
        assertEquals(ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.WEB_SOURCE_MISSING, absent.kind)
        assertEquals("웹 출처 주소 형식이 올바르지 않음", malformed.statement)
        assertEquals(ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.WEB_SOURCE_INVALID, malformed.kind)
        assertTrue(repository(listOf(row("valid", "WEB_CLIP", sourceUrl = "https://example.org/article")))
            .scanProjectHealth(1).isEmpty())
    }

    @Test
    fun `document highlight requires source and file ownership by expected project`() = runBlocking {
        val row = row("doc", "DOCUMENT_HIGHLIGHT")
        val owned = ProjectKnowledgeDocumentSourceProof(
            sourceProjectId = 1,
            sourceFileId = 7,
            fileProjectId = 1,
            projectExists = true,
            fileAvailable = true,
        )
        val notOwned = owned.copy(fileProjectId = 2)

        assertTrue(repository(listOf(row), documentProof = owned).scanProjectHealth(1).isEmpty())
        val observation = repository(listOf(row), documentProof = notOwned).scanProjectHealth(1).single()
        assertEquals("원본 문서를 찾을 수 없음", observation.statement)
        assertFalse(ProjectKnowledgeHealthPolicy.documentSourceAvailable(1, owned.copy(sourceProjectId = 2)))
        assertFalse(ProjectKnowledgeHealthPolicy.documentSourceAvailable(1, owned.copy(fileAvailable = false)))
        assertFalse(ProjectKnowledgeHealthPolicy.documentSourceAvailable(1, owned.copy(projectExists = false)))
    }

    @Test
    fun `linked note source is checked and independent note wording is informational`() = runBlocking {
        val linked = row("linked", "USER_NOTE")
        val source = noteSource(linked.id)
        val available = repository(listOf(linked), noteLinks = listOf(source), noteAccessible = { _, _ -> true })
            .scanProjectHealth(1)
        assertTrue(available.isEmpty())

        val unavailable = repository(listOf(linked), noteLinks = listOf(source), noteAccessible = { _, _ -> false })
            .scanProjectHealth(1).single()
        assertEquals(ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.NOTE_LINK_UNAVAILABLE, unavailable.kind)
        assertEquals("연결된 출처 일부를 열 수 없음", unavailable.statement)

        val independent = repository(listOf(linked)).scanProjectHealth(1).single()
        assertEquals(ProjectKnowledgeHealthPolicy.ProjectKnowledgeHealthKind.INDEPENDENT_COPY, independent.kind)
        assertEquals("원본과 별도로 저장된 사본", independent.statement)
    }

    @Test
    fun `note sources are bounded to eight`() = runBlocking {
        val note = row("note", "USER_NOTE")
        val links = (0 until 12).map { noteSource(note.id, id = it.toLong() + 1) }
        val source = FakeDataSource(listOf(note), noteRows = links, accessible = { _, _ -> true })

        val observations = ProjectKnowledgeHealthRepository(source) { false }.scanProjectHealth(1)

        assertEquals(8, source.noteLimit)
        assertTrue(observations.isEmpty())
    }

    @Test
    fun `health navigation is exact project bound and rechecks access`() = runBlocking {
        val item = row("knowledge", "USER_NOTE")
        var archiveAllowed = false
        val source = FakeDataSource(
            listOf(item),
            accessible = { _, _ -> true },
            rowAccess = { _, _ -> false },
        )
        val repository = ProjectKnowledgeHealthRepository(source) { archiveAllowed }

        assertNull(repository.resolveCurrentTarget(item.id, 2))
        assertNull(repository.resolveCurrentTarget(item.id, 1))
        source.rowAccess = { _, allowed -> allowed }
        archiveAllowed = true
        assertEquals(item, repository.resolveCurrentTarget(item.id, 1))
        assertTrue(source.accessChecks.last())
        source.saved = item.copy(projectId = 2)
        assertNull(repository.resolveCurrentTarget(item.id, 1))
    }

    @Test
    fun `scan cancellation propagates from assistant document and note reads`() = runBlocking {
        expectCancellation {
            repository(listOf(row("answer", "ASSISTANT_ANSWER")), assistantFailure = CancellationException("assistant read"))
                .scanProjectHealth(1)
        }
        expectCancellation {
            repository(listOf(row("doc", "DOCUMENT_HIGHLIGHT")), documentFailure = CancellationException("document read"))
                .scanProjectHealth(1)
        }
        val note = row("note", "USER_NOTE")
        expectCancellation {
            repository(listOf(note), noteReadFailure = CancellationException("note source read"))
                .scanProjectHealth(1)
        }
        expectCancellation {
            repository(listOf(note), noteLinks = listOf(noteSource(note.id)), noteAccessFailure = CancellationException("note access"))
                .scanProjectHealth(1)
        }
    }

    @Test
    fun `failed scan remains failed rather than becoming empty success`() = runBlocking {
        val source = FakeDataSource(emptyList(), rowsFailure = IllegalStateException("database read failed"))
        val repository = ProjectKnowledgeHealthRepository(source) { false }

        assertEquals(ProjectKnowledgeHealthLoadState.Failed, repository.loadState(1))
        assertEquals(ProjectKnowledgeHealthLoadState.Ready(emptyList()), ProjectKnowledgeHealthRepository(FakeDataSource(emptyList())) { false }.loadState(1))
    }

    @Test
    fun `capped knowledge health scan reports that unscanned rows remain`() = runBlocking {
        val rows = (1..ProjectKnowledgeHealthPolicy.MAX_KNOWLEDGE_HEALTH_SCAN + 1).map { index ->
            row("row-$index", "WEB_CLIP", sourceUrl = "https://example.org/$index")
        }
        val state = ProjectKnowledgeHealthRepository(FakeDataSource(rows)) { false }.loadState(1)

        assertTrue(state is ProjectKnowledgeHealthLoadState.Ready)
        state as ProjectKnowledgeHealthLoadState.Ready
        assertTrue(state.scanLimitReached)
        assertFalse(state.findingsTruncated)
        assertTrue(state.observations.isEmpty())
    }

    @Test
    fun `knowledge health reports findings hidden by visible result cap`() = runBlocking {
        val rows = (1..ProjectKnowledgeHealthPolicy.MAX_KNOWLEDGE_HEALTH_VISIBLE + 1).map { index ->
            row("bad-$index", "WEB_CLIP", sourceUrl = "invalid-$index")
        }
        val state = ProjectKnowledgeHealthRepository(FakeDataSource(rows)) { false }.loadState(1)

        assertTrue(state is ProjectKnowledgeHealthLoadState.Ready)
        state as ProjectKnowledgeHealthLoadState.Ready
        assertFalse(state.scanLimitReached)
        assertTrue(state.findingsTruncated)
        assertEquals(ProjectKnowledgeHealthPolicy.MAX_KNOWLEDGE_HEALTH_VISIBLE, state.observations.size)
    }

    @Test
    fun `document resolver failure does not report a healthy result`() = runBlocking {
        val state = repository(
            listOf(row("doc", "DOCUMENT_HIGHLIGHT")),
            documentFailure = IllegalStateException("managed file resolver failed"),
        ).loadState(1)

        assertEquals(ProjectKnowledgeHealthLoadState.Failed, state)
    }

    private fun repository(
        rows: List<SavedKnowledgeEntity>,
        assistantProof: ProjectKnowledgeAssistantSourceProof = ProjectKnowledgeAssistantSourceProof(null, false, null, null, null),
        documentProof: ProjectKnowledgeDocumentSourceProof? = null,
        noteLinks: List<SavedKnowledgeNoteSourceEntity> = emptyList(),
        noteAccessible: (SavedKnowledgeNoteSourceEntity, Boolean) -> Boolean = { _, _ -> true },
        archiveAllowed: () -> Boolean = { false },
        assistantFailure: Exception? = null,
        documentFailure: Exception? = null,
        noteReadFailure: Exception? = null,
        noteAccessFailure: Exception? = null,
    ) = ProjectKnowledgeHealthRepository(
        FakeDataSource(
            rows,
            assistant = { assistantFailure?.let { throw it }; assistantProof },
            document = { documentFailure?.let { throw it }; documentProof },
            noteRows = noteLinks,
            accessible = { source, allowed -> noteAccessFailure?.let { throw it }; noteAccessible(source, allowed) },
            rowsFailure = null,
        ).apply { this.noteReadFailure = noteReadFailure },
        archiveAllowed,
    )

    private class FakeDataSource(
        private val rows: List<SavedKnowledgeEntity>,
        var assistant: suspend (SavedKnowledgeEntity) -> ProjectKnowledgeAssistantSourceProof = {
            ProjectKnowledgeAssistantSourceProof(null, false, null, null, null)
        },
        var document: suspend (SavedKnowledgeEntity) -> ProjectKnowledgeDocumentSourceProof? = { null },
        private val noteRows: List<SavedKnowledgeNoteSourceEntity> = emptyList(),
        var accessible: suspend (SavedKnowledgeNoteSourceEntity, Boolean) -> Boolean = { _, _ -> true },
        private var rowsFailure: Exception? = null,
        var rowAccess: suspend (SavedKnowledgeEntity, Boolean) -> Boolean = { _, _ -> true },
    ) : ProjectKnowledgeHealthDataSource {
        var noteReadFailure: Exception? = null
        var saved: SavedKnowledgeEntity? = rows.firstOrNull()
        var accessChecks = mutableListOf<Boolean>()
        var noteLimit: Int = 0

        override suspend fun projectItems(projectId: Long, limit: Int): List<SavedKnowledgeEntity> {
            rowsFailure?.let { throw it }
            return rows.take(limit)
        }

        override suspend fun assistantSourceProof(row: SavedKnowledgeEntity) = assistant(row)
        override suspend fun documentSourceProof(row: SavedKnowledgeEntity) = document(row)
        override suspend fun noteSources(noteId: String, limit: Int): List<SavedKnowledgeNoteSourceEntity> {
            noteLimit = limit
            noteReadFailure?.let { throw it }
            return noteRows.filter { it.noteId == noteId }.take(limit)
        }
        override suspend fun noteSourceAccessible(source: SavedKnowledgeNoteSourceEntity, archiveAllowed: Boolean) = accessible(source, archiveAllowed)
        override suspend fun savedKnowledge(id: String): SavedKnowledgeEntity? = saved?.takeIf { it.id == id }
        override suspend fun rowAccessible(row: SavedKnowledgeEntity, archiveAllowed: Boolean): Boolean {
            accessChecks += archiveAllowed
            return rowAccess(row, archiveAllowed)
        }
    }

    private fun row(
        id: String,
        provenance: String,
        projectId: Long? = 1,
        conversationId: Long? = null,
        messageId: Long? = null,
        sourceUrl: String? = null,
    ) = SavedKnowledgeEntity(id, "PROJECT", projectId, id, "body", provenance, conversationId, messageId, 1, 1, sourceUrl)

    private fun noteSource(noteId: String, id: Long = 1) = SavedKnowledgeNoteSourceEntity(
        id = id,
        noteId = noteId,
        orderIndex = id.toInt(),
        sourceKey = "source-$id",
        sourceKind = "CONVERSATION",
        conversationId = id,
        sourceTitle = "Source $id",
        createdAt = 1,
    )

    private suspend fun expectCancellation(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(true)
        }
    }
}
