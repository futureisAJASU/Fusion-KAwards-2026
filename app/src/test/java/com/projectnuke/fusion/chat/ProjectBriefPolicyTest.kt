package com.projectnuke.fusion.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.projectnuke.fusion.test.PlatformSharedPreferences
import com.projectnuke.fusion.ui.loadConversationSummaries
import com.projectnuke.fusion.ui.loadConversationSummariesResult
import com.projectnuke.fusion.ui.saveConversationSummary
import com.projectnuke.fusion.util.SharedPreferencesDurability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectBriefPolicyTest {
    @Test
    fun `empty project has no conversations or summaries`() {
        val snapshot = buildProjectBriefSnapshot(input())

        assertEquals(0, snapshot.conversationCount)
        assertEquals(0, snapshot.recentSummaryCount)
        assertTrue(snapshot.recentSummaries.isEmpty())
    }

    @Test
    fun `usable summary emits one item`() {
        val snapshot = buildProjectBriefSnapshot(
            input(conversation(id = 7L, summary = "  saved summary  ")),
        )

        assertEquals(1, snapshot.recentSummaryCount)
        assertEquals(1, snapshot.recentSummaries.size)
        assertEquals("saved summary", snapshot.recentSummaries.single().summaryPreview)
    }

    @Test
    fun `six summaries retain only five previews while count stays truthful`() {
        val snapshot = buildProjectBriefSnapshot(
            input(*(1L..6L).map { id -> conversation(id = id, summary = "summary-$id") }.toTypedArray()),
        )

        assertEquals(6, snapshot.recentSummaryCount)
        assertEquals(MAX_RECENT_SUMMARIES, snapshot.recentSummaries.size)
    }

    @Test
    fun `recent order uses the newer conversation or summary timestamp`() {
        val snapshot = buildProjectBriefSnapshot(
            input(
                conversation(id = 1L, conversationUpdatedAt = 20L, summaryUpdatedAt = 200L, summary = "one"),
                conversation(id = 2L, conversationUpdatedAt = 300L, summaryUpdatedAt = 30L, summary = "two"),
                conversation(id = 3L, conversationUpdatedAt = 40L, summaryUpdatedAt = 400L, summary = "three"),
            ),
        )

        assertEquals(listOf(3L, 2L, 1L), snapshot.recentSummaries.map { it.conversationId })
        assertEquals(listOf(400L, 300L, 200L), snapshot.recentSummaries.map { it.updatedAt })
    }

    @Test
    fun `equal relevant timestamps use descending conversation ID`() {
        val snapshot = buildProjectBriefSnapshot(
            input(
                conversation(id = 2L, conversationUpdatedAt = 100L, summary = "two"),
                conversation(id = 9L, conversationUpdatedAt = 100L, summary = "nine"),
                conversation(id = 4L, conversationUpdatedAt = 100L, summary = "four"),
            ),
        )

        assertEquals(listOf(9L, 4L, 2L), snapshot.recentSummaries.map { it.conversationId })
    }

    @Test
    fun `missing and blank summaries are not emitted`() {
        val snapshot = buildProjectBriefSnapshot(
            input(
                conversation(id = 1L, summary = null),
                conversation(id = 2L, summary = " \n\t "),
                conversation(id = 3L, summary = "usable"),
            ),
        )

        assertEquals(listOf(3L), snapshot.recentSummaries.map { it.conversationId })
    }

    @Test
    fun `title and preview bounds do not split surrogate pairs`() {
        val title = "t".repeat(MAX_TITLE_CHARS - 1) + "😀"
        val summary = "s".repeat(MAX_SUMMARY_PREVIEW_CHARS - 1) + "😀"
        val item = buildProjectBriefSnapshot(
            input(conversation(id = 1L, title = title, summary = summary)),
        ).recentSummaries.single()

        assertTrue(item.title.length <= MAX_TITLE_CHARS)
        assertTrue(item.summaryPreview.length <= MAX_SUMMARY_PREVIEW_CHARS)
        assertFalse(item.title.last().isHighSurrogate())
        assertFalse(item.summaryPreview.last().isHighSurrogate())
    }

    @Test
    fun `counts reflect supplied conversations and files`() {
        val snapshot = buildProjectBriefSnapshot(
            inputWithFiles(
                conversations = (1L..7L).map { id -> conversation(id = id, summary = if (id == 1L) "one" else null) },
                files = listOf(
                    ProjectBriefFileInput("a.txt", "text/plain"),
                    ProjectBriefFileInput("b.txt", "text/plain"),
                    ProjectBriefFileInput("c.pdf", "application/pdf"),
                    ProjectBriefFileInput("d.json", "application/json"),
                ),
            ),
        )

        assertEquals(7, snapshot.conversationCount)
        assertEquals(4, snapshot.fileCount)
        assertEquals(1, snapshot.recentSummaryCount)
    }

    @Test
    fun `duplicate conversation IDs are retained once deterministically`() {
        val snapshot = buildProjectBriefSnapshot(
            input(
                conversation(id = 5L, title = "first", summary = "first summary"),
                conversation(id = 5L, title = "second", summary = "second summary"),
            ),
        )

        assertEquals(1, snapshot.conversationCount)
        assertEquals(1, snapshot.recentSummaries.size)
        assertEquals("first", snapshot.recentSummaries.single().title)
    }

    @Test
    fun `candidate selection is bounded and deterministic`() {
        val candidates = selectProjectBriefSummaryCandidates(
            (1L..100L).map { id -> conversation(id = id, conversationUpdatedAt = id) },
        )

        assertEquals(MAX_BRIEF_SUMMARY_CANDIDATES, candidates.size)
        assertEquals((100L downTo 69L).toList(), candidates.map { it.conversationId })
    }

    @Test
    fun `project ownership comes from supplied current membership`() {
        val snapshot = buildProjectBriefSnapshot(
            ProjectBriefInput(
                projectId = 10L,
                projectName = "A",
                instructions = "private",
                conversations = listOf(conversation(id = 1L, summary = "A summary")),
                files = emptyList(),
            ),
        )
        val removedConversationSnapshot = buildProjectBriefSnapshot(
            ProjectBriefInput(
                projectId = 10L,
                projectName = "A",
                instructions = "private",
                conversations = emptyList(),
                files = emptyList(),
            ),
        )

        assertEquals(listOf(1L), snapshot.recentSummaries.map { it.conversationId })
        assertTrue(removedConversationSnapshot.recentSummaries.isEmpty())
    }

    @Test
    fun `presentation states distinguish ready and both empty cases`() {
        val ready = buildProjectBriefSnapshot(input(conversation(id = 1L, summary = "summary")))
        val noSummaries = buildProjectBriefSnapshot(input(conversation(id = 1L)))
        val empty = buildProjectBriefSnapshot(input())

        assertEquals(ProjectBriefContentState.READY, projectBriefContentState(ProjectBriefLoadState.Ready(ready)))
        assertEquals(ProjectBriefContentState.NO_SUMMARIES, projectBriefContentState(ProjectBriefLoadState.Ready(noSummaries)))
        assertEquals(ProjectBriefContentState.PROJECT_EMPTY, projectBriefContentState(ProjectBriefLoadState.Ready(empty)))
        assertEquals(ProjectBriefContentState.UNAVAILABLE, projectBriefContentState(ProjectBriefLoadState.Unavailable))
        assertEquals(ProjectBriefContentState.LOADING, projectBriefContentState(ProjectBriefLoadState.Loading))
        assertEquals(1L, ready.recentSummaries.single().conversationId)
    }

    @Test
    fun `brief additions stay deterministically bounded and retain decision status`() {
        val snapshot = buildProjectBriefSnapshot(
            inputWithFiles(emptyList()).copy(
                savedKnowledge = (1..8).map { ProjectBriefKnowledgeInput("k$it", "knowledge-$it", it.toLong()) },
                openTasks = (1..8).map { ProjectBriefTaskInput(it.toLong(), "task-$it", it.toLong()) },
                decisions = (1..8).map { id -> ProjectBriefDecisionInput("d$id", "decision-$id", if (id == 2) "SUPERSEDED" else "ACTIVE", id.toLong()) },
            ),
        )

        assertEquals(listOf("k8", "k7", "k6"), snapshot.recentKnowledge.map { it.id })
        assertEquals(listOf(8L, 7L, 6L), snapshot.openTasks.map { it.id })
        assertEquals(listOf("d8", "d7", "d6"), snapshot.recentDecisions.map { it.id })
    }

    @Test
    fun `recent change summary uses fixed intervals chronological order and a strict row bound`() {
        val now = 40L * 24 * 60 * 60 * 1000
        val changes = (1..20).map { index ->
            ProjectBriefChangeInput("c$index", "자료", "item-$index", now - index * 60_000L)
        } + ProjectBriefChangeInput("boundary", "대화", "week boundary", now - 7L * 24 * 60 * 60 * 1000) +
            ProjectBriefChangeInput("future", "대화", "future", now + 1L)
        val snapshot = buildProjectBriefSnapshot(input()).copy(changeCandidates = changes)

        val week = projectBriefRecentChanges(snapshot, now, 7)
        val month = projectBriefRecentChanges(snapshot, now, 30)

        assertEquals(MAX_BRIEF_CHANGE_ROWS, week.size)
        assertEquals("c1", week.first().key)
        assertFalse(week.any { it.key == "future" })
        assertEquals(MAX_BRIEF_CHANGE_ROWS, month.size)
        assertEquals(1, projectBriefRecentChanges(snapshot.copy(changeCandidates = listOf(changes[20])), now, 7).size)
        assertTrue(projectBriefRecentChanges(snapshot, now, 14).isEmpty())
    }

    @Test
    fun `batch loader reads only requested IDs`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        saveConversationSummary(context, 1L, "one")
        saveConversationSummary(context, 99L, "unrelated")
        prefs.getStringCalls.set(0)

        val loaded = loadConversationSummaries(context, listOf(1L))

        assertEquals(listOf(1L), loaded.map { it.conversationId })
        assertEquals(1, prefs.getStringCalls.get())
    }

    @Test
    fun `batch loader skips missing malformed and unknown entries`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        saveConversationSummary(context, 2L, "two")
        prefs.values["summary_3"] = 17
        SharedPreferencesDurability.markUnknown(prefs, "summary_4")

        val result = loadConversationSummariesResult(context, listOf(1L, 2L, 3L, 4L))

        assertEquals(listOf(2L), result.summaries.map { it.conversationId })
        assertEquals(setOf(4L), result.durabilityUnknownConversationIds)
    }

    @Test
    fun `batch loader never reads more than the brief candidate bound`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)

        loadConversationSummaries(context, (1L..100L).toList())

        assertEquals(MAX_BRIEF_SUMMARY_CANDIDATES, prefs.getStringCalls.get())
    }

    @Test
    fun `policy order is independent of loader request order`() {
        val prefs = PlatformSharedPreferences()
        val context = TestContext(prefs)
        saveConversationSummary(context, 1L, "one")
        saveConversationSummary(context, 2L, "two")
        val loaded = loadConversationSummaries(context, listOf(2L, 1L))
        val byId = loaded.associateBy { it.conversationId }

        val snapshot = buildProjectBriefSnapshot(
            input(
                conversation(id = 1L, conversationUpdatedAt = Long.MAX_VALUE, summary = byId[1L]?.summary, summaryUpdatedAt = byId[1L]?.updatedAt),
                conversation(id = 2L, conversationUpdatedAt = Long.MIN_VALUE, summary = byId[2L]?.summary, summaryUpdatedAt = byId[2L]?.updatedAt),
            ),
        )

        assertEquals(listOf(1L, 2L), snapshot.recentSummaries.map { it.conversationId })
    }

    private fun input(
        vararg conversationInputs: ProjectBriefConversationInput,
    ): ProjectBriefInput = inputWithFiles(conversationInputs.toList())

    private fun inputWithFiles(
        conversations: List<ProjectBriefConversationInput>,
        files: List<ProjectBriefFileInput> = emptyList(),
    ): ProjectBriefInput = ProjectBriefInput(
        projectId = 1L,
        projectName = "Project",
        instructions = "",
        conversations = conversations,
        files = files,
    )

    private fun conversation(
        id: Long,
        title: String = "conversation-$id",
        conversationUpdatedAt: Long = id,
        summary: String? = null,
        summaryUpdatedAt: Long? = null,
    ): ProjectBriefConversationInput = ProjectBriefConversationInput(
        conversationId = id,
        title = title,
        conversationUpdatedAt = conversationUpdatedAt,
        summary = summary,
        summaryUpdatedAt = summaryUpdatedAt,
    )

    private class TestContext(
        private val prefs: SharedPreferences,
    ) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }
}
