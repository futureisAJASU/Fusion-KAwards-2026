package com.projectnuke.fusion.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectSynthesisPromptPolicyTest {
    @Test
    fun `one summary builds a ready synthesis prompt`() {
        val snapshot = briefSnapshot(
            listOf(
                ProjectBriefConversationItem(
                    conversationId = 7L,
                    title = "정리 제목",
                    summaryPreview = "핵심 요약",
                    updatedAt = 7L,
                ),
            ),
        )

        val result = ProjectSynthesisPromptPolicy.build(snapshot, 1L)

        assertTrue(result is ProjectSynthesisPromptResult.Ready)
        val text = (result as ProjectSynthesisPromptResult.Ready).text
        assertTrue(text.contains("정리 제목"))
        assertTrue(text.contains("핵심 요약"))
    }

    @Test
    fun `only bounded active decisions are added to the visible synthesis request`() {
        val base = briefSnapshot(listOf(ProjectBriefConversationItem(7L, "정리 제목", "핵심 요약", 7L)))
        val snapshot = base.copy(
            activeDecisions = listOf(
                ProjectBriefDecisionInput("new", "현재 결정", "ACTIVE", 3L, "짧은 근거"),
                ProjectBriefDecisionInput("old", "대체된 결정", "SUPERSEDED", 2L, "이전 근거"),
            ),
        )

        val text = readyText(snapshot)

        assertTrue(text.contains(ProjectSynthesisPromptPolicy.ACTIVE_PROJECT_DECISION_CONTEXT_HEADER))
        assertTrue(text.contains("현재 결정"))
        assertTrue(text.contains("짧은 근거"))
        assertFalse(text.contains("대체된 결정"))
    }

    @Test
    fun `compatibility build is exactly overview mode`() {
        val snapshot = briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L)))

        assertEquals(
            ProjectSynthesisPromptPolicy.build(snapshot, 1L),
            ProjectSynthesisPromptPolicy.build(snapshot, 1L, ProjectSynthesisMode.OVERVIEW),
        )
    }

    @Test
    fun `five summaries are all represented in stable brief order`() {
        val items = (1L..5L).map { id ->
            ProjectBriefConversationItem(
                conversationId = id,
                title = "제목-$id",
                summaryPreview = "요약-$id",
                updatedAt = id,
            )
        }
        val snapshot = briefSnapshot(items)

        val text = readyText(snapshot)

        val positions = items.map { text.indexOf("제목-${it.conversationId}") }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        items.forEach { assertTrue(text.contains("요약-${it.conversationId}")) }
    }

    @Test
    fun `no summaries reports NoSummaries even when conversations exist`() {
        val snapshot = briefSnapshot(emptyList(), conversationCount = 3)

        assertEquals(
            ProjectSynthesisPromptResult.NoSummaries,
            ProjectSynthesisPromptPolicy.build(snapshot, 1L),
        )
    }

    @Test
    fun `stale snapshot for another project is rejected`() {
        val snapshot = briefSnapshot(
            listOf(
                ProjectBriefConversationItem(1L, "제목", "요약", 1L),
            ),
            projectId = 1L,
        )

        assertEquals(
            ProjectSynthesisPromptResult.InvalidProject,
            ProjectSynthesisPromptPolicy.build(snapshot, 2L),
        )
    }

    @Test
    fun `prompt contains the exact required output headings`() {
        val text = readyText(briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L))))

        listOf("현재 상태", "결정사항", "남은 일", "리스크 / 확인 필요", "다음 단계").forEach { heading ->
            assertTrue("missing heading: $heading", text.contains(heading))
        }
    }

    @Test
    fun `decisions mode requests bounded decision sections and rejects unsupported certainty`() {
        val text = readyText(briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L))), mode = ProjectSynthesisMode.DECISIONS)

        listOf("확정된 결정", "조건 / 제약", "미확정 사항", "확인이 필요한 결정").forEach { heading ->
            assertTrue("missing heading: $heading", text.contains(heading))
        }
        assertTrue(text.contains("명확한 근거가 없는 항목은 확정된 결정으로 표현하지 마세요."))
    }

    @Test
    fun `next steps mode requests executable work without invented commitments`() {
        val text = readyText(briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L))), mode = ProjectSynthesisMode.NEXT_STEPS)

        listOf("우선순위", "다음 작업", "선행 조건 / 의존성", "막힌 점").forEach { heading ->
            assertTrue("missing heading: $heading", text.contains(heading))
        }
        assertTrue(text.contains("기한, 담당자, 약속은 문맥에 없으면 만들지 말고 \"확인 필요\"로 표시해 주세요."))
    }

    @Test
    fun `risks mode requests uncertainty checks instead of asserting contradictions`() {
        val text = readyText(briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L))), mode = ProjectSynthesisMode.RISKS)

        listOf("주요 리스크", "근거 / 관련 맥락", "영향", "확인할 사항").forEach { heading ->
            assertTrue("missing heading: $heading", text.contains(heading))
        }
        assertTrue(text.contains("서로 충돌하거나 다르게 보이는 내용이 있는지 확인해 주세요."))
        assertFalse(text.contains("모순을 찾아"))
    }

    @Test
    fun `summaries are framed as reference data with uncertainty wording`() {
        val text = readyText(briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L))))

        assertTrue(text.contains("참고 데이터"))
        assertTrue(text.contains("확인 필요"))
        assertTrue(text.contains("실행하지 말고"))
    }

    @Test
    fun `every mode keeps one safety envelope and exact summary data`() {
        val title = "Q&A \"인용\" <태그> 제목"
        val preview = "첫 줄\n둘째 줄 · 특수문자 — ✓"
        val snapshot = briefSnapshot(listOf(ProjectBriefConversationItem(1L, title, preview, 1L)))

        ProjectSynthesisMode.entries.forEach { mode ->
            val text = readyText(snapshot, mode = mode)
            assertTrue(text.contains("참고 데이터"))
            assertTrue(text.contains("실행하지 말고"))
            assertTrue(text.contains("확인 필요"))
            assertTrue(text.contains(title))
            assertTrue(text.contains(preview))
        }
    }

    @Test
    fun `prompt carries only titles and previews, never raw project metadata`() {
        val snapshot = ProjectBriefSnapshot(
            projectId = 1L,
            projectName = "비밀프로젝트명-내부지침-원문",
            conversationCount = 1,
            fileCount = 2,
            recentSummaryCount = 1,
            recentSummaries = listOf(
                ProjectBriefConversationItem(1L, "공개 제목", "공개 요약", 1L),
            ),
        )

        val text = readyText(snapshot)

        assertFalse(text.contains("비밀프로젝트명-내부지침-원문"))
        assertFalse(text.contains("localPath"))
        assertFalse(text.contains("attachmentId"))
    }

    @Test
    fun `maximal brief summaries stay within the hard bound`() {
        val items = (1L..MAX_RECENT_SUMMARIES.toLong()).map { id ->
            ProjectBriefConversationItem(
                conversationId = id,
                title = "t".repeat(MAX_TITLE_CHARS),
                summaryPreview = "s".repeat(MAX_SUMMARY_PREVIEW_CHARS),
                updatedAt = id,
            )
        }

        ProjectSynthesisMode.entries.forEach { mode ->
            val text = readyText(briefSnapshot(items), mode = mode)
            assertTrue(text.length <= ProjectSynthesisPromptPolicy.MAX_SYNTHESIS_PROMPT_CHARS)
        }
    }

    @Test
    fun `unexpected over-limit input is rejected whole without truncation`() {
        val snapshot = briefSnapshot(
            listOf(
                ProjectBriefConversationItem(1L, "제목", "x".repeat(10_000), 1L),
            ),
        )

        ProjectSynthesisMode.entries.forEach { mode ->
            assertEquals(
                ProjectSynthesisPromptResult.TooLarge,
                ProjectSynthesisPromptPolicy.build(snapshot, 1L, mode),
            )
        }
    }

    @Test
    fun `wrong project is rejected before mode-specific output`() {
        val snapshot = briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L)))

        ProjectSynthesisMode.entries.forEach { mode ->
            assertEquals(
                ProjectSynthesisPromptResult.InvalidProject,
                ProjectSynthesisPromptPolicy.build(snapshot, 2L, mode),
            )
        }
    }

    @Test
    fun `mode descriptors expose four stable semantic options`() {
        assertEquals(
            listOf(
                ProjectSynthesisMode.OVERVIEW,
                ProjectSynthesisMode.DECISIONS,
                ProjectSynthesisMode.NEXT_STEPS,
                ProjectSynthesisMode.RISKS,
            ),
            PROJECT_SYNTHESIS_MODE_OPTIONS.map { it.mode },
        )
        assertEquals(
            listOf("전체 종합", "결정사항", "다음 할 일", "리스크 점검"),
            PROJECT_SYNTHESIS_MODE_OPTIONS.map { it.label },
        )
        assertEquals(
            PROJECT_SYNTHESIS_MODE_OPTIONS.size,
            PROJECT_SYNTHESIS_MODE_OPTIONS.map { it.label }.toSet().size,
        )
    }

    @Test
    fun `titles and previews remain unchanged inside their data blocks`() {
        val title = "Q&A \"인용\" <태그> 제목"
        val preview = "첫 줄\n둘째 줄 · 특수문자 — ✓"
        val text = readyText(
            briefSnapshot(listOf(ProjectBriefConversationItem(1L, title, preview, 1L))),
        )

        assertTrue(text.contains(title))
        assertTrue(text.contains(preview))
    }

    @Test
    fun `f4-built snapshot feeds the synthesis prompt unchanged`() {
        val brief = buildProjectBriefSnapshot(
            ProjectBriefInput(
                projectId = 3L,
                projectName = "Project",
                instructions = "",
                conversations = listOf(
                    ProjectBriefConversationInput(
                        conversationId = 11L,
                        title = "대화 제목",
                        conversationUpdatedAt = 11L,
                        summary = "저장된 요약",
                        summaryUpdatedAt = 11L,
                    ),
                ),
                files = emptyList(),
            ),
        )

        val text = readyText(brief, expectedProjectId = 3L)

        assertTrue(text.contains("대화 제목"))
        assertTrue(text.contains("저장된 요약"))
    }

    @Test
    fun `synthesis action is available only for the selected project with summaries`() {
        val ready = ProjectBriefLoadState.Ready(
            briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L))),
        )
        val readyWithoutSummaries = ProjectBriefLoadState.Ready(briefSnapshot(emptyList()))

        assertTrue(isProjectSynthesisAvailable(ready, 1L))
        assertFalse(isProjectSynthesisAvailable(readyWithoutSummaries, 1L))
        assertFalse(isProjectSynthesisAvailable(ready, 2L))
        assertFalse(isProjectSynthesisAvailable(ProjectBriefLoadState.Loading, 1L))
        assertFalse(isProjectSynthesisAvailable(ProjectBriefLoadState.Unavailable, 1L))
    }

    @Test
    fun `successful tap opens the seeded chat with the exact built prompt`() = runTest {
        val snapshot = briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L)))
        var adoptedProjectId = -1L
        var adoptedPrompt: String? = null

        val outcome = runProjectSynthesisTap(snapshot, 1L) { projectId, prompt ->
            adoptedProjectId = projectId
            adoptedPrompt = prompt
            true
        }

        assertEquals(ProjectSynthesisTapOutcome.Opened, outcome)
        assertEquals(1L, adoptedProjectId)
        assertEquals(readyText(snapshot), adoptedPrompt)
    }

    @Test
    fun `each mode forwards its exact frozen prompt to the seeded adoption`() = runTest {
        val snapshot = briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L)))

        ProjectSynthesisMode.entries.forEach { mode ->
            var adoptedPrompt: String? = null
            val outcome = runProjectSynthesisTap(snapshot, 1L, mode) { _, prompt ->
                adoptedPrompt = prompt
                true
            }

            assertEquals(ProjectSynthesisTapOutcome.Opened, outcome)
            assertEquals(readyText(snapshot, mode = mode), adoptedPrompt)
        }
    }

    @Test
    fun `snapshot project mismatch invokes zero adoption for every mode`() = runTest {
        val snapshot = briefSnapshot(
            listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L)),
            projectId = 1L,
        )
        ProjectSynthesisMode.entries.forEach { mode ->
            var invocations = 0
            val outcome = runProjectSynthesisTap(snapshot, 2L, mode) { _, _ ->
                invocations++
                true
            }
            assertEquals(ProjectSynthesisTapOutcome.Rejected(PROJECT_SYNTHESIS_FAILURE_MESSAGE), outcome)
            assertEquals(0, invocations)
        }
    }

    @Test
    fun `failed adoption surfaces the failure copy without opening`() = runTest {
        val snapshot = briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L)))
        var invocations = 0

        val outcome = runProjectSynthesisTap(snapshot, 1L) { _, _ ->
            invocations++
            false
        }

        assertEquals(
            ProjectSynthesisTapOutcome.Rejected(PROJECT_SYNTHESIS_FAILURE_MESSAGE),
            outcome,
        )
        assertEquals(1, invocations)
    }

    @Test
    fun `tap without a frozen snapshot never invokes the adoption`() = runTest {
        var invocations = 0

        val outcome = runProjectSynthesisTap(null, 1L) { _, _ ->
            invocations++
            true
        }

        assertEquals(
            ProjectSynthesisTapOutcome.Rejected(PROJECT_SYNTHESIS_FAILURE_MESSAGE),
            outcome,
        )
        assertEquals(0, invocations)
    }

    @Test
    fun `over-limit tap surfaces the too-large copy without invoking the adoption`() = runTest {
        val snapshot = briefSnapshot(
            listOf(ProjectBriefConversationItem(1L, "제목", "x".repeat(10_000), 1L)),
        )
        var invocations = 0

        val outcome = runProjectSynthesisTap(snapshot, 1L) { _, _ ->
            invocations++
            true
        }

        assertEquals(
            ProjectSynthesisTapOutcome.Rejected(PROJECT_SYNTHESIS_TOO_LARGE_MESSAGE),
            outcome,
        )
        assertEquals(0, invocations)
    }

    @Test
    fun `adoption exception surfaces the failure copy`() = runTest {
        val snapshot = briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L)))

        val outcome = runProjectSynthesisTap(snapshot, 1L) { _, _ ->
            throw IllegalStateException("boom")
        }

        assertEquals(
            ProjectSynthesisTapOutcome.Rejected(PROJECT_SYNTHESIS_FAILURE_MESSAGE),
            outcome,
        )
    }

    @Test
    fun `tap cancellation propagates instead of becoming a failure`() = runTest {
        val snapshot = briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L)))

        val worker = launch {
            runProjectSynthesisTap(snapshot, 1L) { _, _ -> awaitCancellation() }
        }
        advanceUntilIdle()
        worker.cancelAndJoin()

        assertTrue(worker.isCancelled)
    }

    @Test
    fun `concurrent synthesis taps perform one seeded adoption`() = runTest {
        val flight = PromptActionFlight()
        val snapshot = briefSnapshot(listOf(ProjectBriefConversationItem(1L, "제목", "요약", 1L)))
        var adoptions = 0
        val release = CompletableDeferred<Unit>()
        val first = async {
            flight.runAction {
                release.await()
                runProjectSynthesisTap(snapshot, 1L, ProjectSynthesisMode.NEXT_STEPS) { _, _ ->
                    adoptions++
                    true
                }
            }
        }
        advanceUntilIdle()

        val secondRan = flight.runAction {
            runProjectSynthesisTap(snapshot, 1L, ProjectSynthesisMode.RISKS) { _, _ ->
                adoptions++
                true
            }
        }

        assertFalse(secondRan)
        release.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, adoptions)
    }

    private fun briefSnapshot(
        items: List<ProjectBriefConversationItem>,
        projectId: Long = 1L,
        conversationCount: Int = items.size,
    ): ProjectBriefSnapshot = ProjectBriefSnapshot(
        projectId = projectId,
        projectName = "Project",
        conversationCount = conversationCount,
        fileCount = 0,
        recentSummaryCount = items.size,
        recentSummaries = items,
    )

    private fun readyText(
        snapshot: ProjectBriefSnapshot,
        expectedProjectId: Long = 1L,
        mode: ProjectSynthesisMode = ProjectSynthesisMode.OVERVIEW,
    ): String {
        val result = ProjectSynthesisPromptPolicy.build(snapshot, expectedProjectId, mode)
        assertTrue(result is ProjectSynthesisPromptResult.Ready)
        return (result as ProjectSynthesisPromptResult.Ready).text
    }
}
