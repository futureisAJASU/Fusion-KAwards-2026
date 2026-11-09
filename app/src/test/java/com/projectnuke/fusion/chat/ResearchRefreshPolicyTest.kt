package com.projectnuke.fusion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRefreshPolicyTest {
    private fun receipt(
        scope: ProjectSourceScope = ProjectSourceScope(ProjectSourceScopeKind.PROJECT, 7),
        projectContextId: Long? = 7L,
        projectContextUsed: Boolean = true,
        selectionProjectIds: List<Long> = emptyList(),
    ) = AnswerContextReceipt.build(
        route = AnswerContextRoute.LOCAL_MODEL,
        interactionMode = ChatInteractionMode.RESEARCH.name,
        projectContextUsed = projectContextUsed,
        projectContextId = projectContextId,
        sourceScopeKind = scope.kind.name,
        sourceScopeProjectId = scope.projectId,
        sourceScopeFileIds = scope.fileIds,
        selectionProjectIds = selectionProjectIds,
    )

    @Test
    fun `refresh seeds an editable evidence-aware request and freezes the source scope`() {
        val sourceScope = ProjectSourceScope(ProjectSourceScopeKind.SELECTED_FILES, 7, listOf(21, 22))
        val result = ResearchRefreshPolicy.prepare(
            receipt = receipt(scope = sourceScope),
            sourceConversationId = 30,
            sourceMessageId = 40,
            question = "  current memory bandwidth  ",
            visiblePreviousAnswer = "Previous answer text",
        ) as ResearchRefreshPreparation.Ready

        assertEquals(30L, result.plan.sourceConversationId)
        assertEquals(40L, result.plan.sourceMessageId)
        assertEquals(sourceScope, result.plan.sourceScope)
        assertTrue(result.plan.prompt.contains("현재 정보로 이전 Research 결과를 다시 확인"))
        assertTrue(result.plan.prompt.contains("달라진 점"))
        assertTrue(result.plan.prompt.contains("자료를 찾지 못한 사실만으로"))
        assertTrue(result.plan.prompt.contains("[이전 Research 답변 · 참고 자료]"))
        assertFalse(result.plan.prompt.isBlank())
    }

    @Test
    fun `refresh rejects stale or multi project scope instead of broadening`() {
        val mismatch = ResearchRefreshPolicy.prepare(
            receipt = receipt(projectContextId = 8L),
            sourceConversationId = 30,
            sourceMessageId = 40,
            question = "question",
            visiblePreviousAnswer = "answer",
        )
        assertEquals(ResearchRefreshPreparation.MissingFrozenScope, mismatch)

        val multiple = ResearchRefreshPolicy.prepare(
            receipt = receipt(selectionProjectIds = listOf(7, 8)),
            sourceConversationId = 30,
            sourceMessageId = 40,
            question = "question",
            visiblePreviousAnswer = "answer",
        )
        assertEquals(ResearchRefreshPreparation.MultiProjectScopeUnsupported, multiple)
    }

    @Test
    fun `refresh requires research mode frozen scope and bounded question`() {
        assertEquals(
            ResearchRefreshPreparation.NotResearch,
            ResearchRefreshPolicy.prepare(
                receipt = receipt().copy(interactionMode = ChatInteractionMode.NORMAL.name),
                sourceConversationId = 30, sourceMessageId = 40, question = "q", visiblePreviousAnswer = "a",
            ),
        )
        assertEquals(
            ResearchRefreshPreparation.MissingFrozenScope,
            ResearchRefreshPolicy.prepare(
                receipt = receipt().copy(schemaVersion = 1),
                sourceConversationId = 30, sourceMessageId = 40, question = "q", visiblePreviousAnswer = "a",
            ),
        )
        assertEquals(
            ResearchRefreshPreparation.InvalidQuestion,
            ResearchRefreshPolicy.prepare(
                receipt = receipt(), sourceConversationId = 30, sourceMessageId = 40,
                question = "q".repeat(ResearchRefreshPolicy.MAX_QUESTION_CHARS + 1), visiblePreviousAnswer = "a",
            ),
        )
    }

    @Test
    fun `scope restoration never converts old or selected multi project receipts to all sources`() {
        assertEquals(null, ResearchRefreshPolicy.restoreSourceScope(receipt().copy(schemaVersion = 1)))
        assertEquals(null, ResearchRefreshPolicy.restoreSourceScope(receipt(selectionProjectIds = listOf(7, 8))))
        assertEquals(
            ProjectSourceScope(ProjectSourceScopeKind.PROJECT, 7),
            ResearchRefreshPolicy.restoreSourceScope(receipt()),
        )
    }
}
