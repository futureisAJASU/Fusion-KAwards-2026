package com.projectnuke.fusion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.projectnuke.fusion.chat.SelectionSourceKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FusionSearchSurfaceTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)
    private val target = KnowledgeTarget.Conversation(1)
    private fun source(access: Boolean) = object : FusionKnowledgeSource {
        override suspend fun search(query: String) = listOf(
            KnowledgeCandidate("c:1", KnowledgeKind.CONVERSATION, "검색한 대화", "짧은 내용", "대화", target, "대화 발췌"))
        override suspend fun accessible(target: KnowledgeTarget) = access
    }

    private fun coveredSource() = object : FusionKnowledgeSource {
        override suspend fun search(query: String) = listOf(
            KnowledgeCandidate("c:1", KnowledgeKind.CONVERSATION, "검색한 대화", "짧은 내용", "대화", target),
        )
        override suspend fun searchWithStatus(query: String, filters: KnowledgeSearchFilters) =
            KnowledgeSearchOutcome(
                candidates = search(query),
                savedKnowledgeResultLimitReached = true,
                savedKnowledgeAdditionalResults = true,
            )
        override suspend fun accessible(target: KnowledgeTarget) = true
    }

    private fun scanLimitedSource() = object : FusionKnowledgeSource {
        override suspend fun search(query: String) = emptyList<KnowledgeCandidate>()
        override suspend fun searchWithStatus(query: String, filters: KnowledgeSearchFilters) =
            KnowledgeSearchOutcome(
                candidates = emptyList(),
                savedKnowledgeScanIncomplete = true,
                savedKnowledgeRawScanBudgetExhausted = true,
            )
        override suspend fun accessible(target: KnowledgeTarget) = false
    }

    private fun candidateCappedSource() = object : FusionKnowledgeSource {
        override suspend fun search(query: String) = listOf(
            KnowledgeCandidate("c:1", KnowledgeKind.CONVERSATION, "검색한 대화", "짧은 내용", "대화", target),
        )
        override suspend fun searchWithStatus(query: String, filters: KnowledgeSearchFilters) =
            KnowledgeSearchOutcome(
                candidates = search(query),
                savedKnowledgeResultLimitReached = true,
            )
        override suspend fun accessible(target: KnowledgeTarget) = true
    }

    private fun multiSource(answers: MutableList<Boolean>) = object : FusionKnowledgeSource {
        override suspend fun search(query: String) = listOf(
            KnowledgeCandidate("c:1", KnowledgeKind.CONVERSATION, "검색한 대화", "짧은 내용", "대화", target, "대화 발췌"),
            KnowledgeCandidate("m:m1", KnowledgeKind.MEMORY, "저장된 메모리", "메모 내용", "메모리",
                KnowledgeTarget.Memory("m1"), "메모 발췌"),
        )
        override suspend fun accessible(target: KnowledgeTarget): Boolean =
            if (answers.isEmpty()) true else answers.removeAt(0)
    }
    @Test fun searchRowsNavigateOnlyAfterAdmission() {
        var selected: KnowledgeTarget? = null
        compose.setContentOnResumedTestHost { MaterialTheme {
            FusionSearchSurface(false, {}, { selected = it }, source(true))
        } }
        compose.onNode(hasSetTextAction()).performTextInput("검색")
        compose.waitUntil(5000) { compose.onAllNodesWithText("검색한 대화").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("검색한 대화").performClick()
        compose.runOnIdle { assertEquals(target, selected) }
    }

    @Test fun resultLimitWarningReachesTheSearchSurface() {
        compose.setContentOnResumedTestHost { MaterialTheme {
            FusionSearchSurface(false, {}, {}, coveredSource())
        } }
        compose.onNode(hasSetTextAction()).performTextInput("검색")
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(
                "접근 가능한 저장 지식 일치 항목이 더 있어요. 검색어 또는 필터를 좁혀 주세요.",
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(
            "접근 가능한 저장 지식 일치 항목이 더 있어요. 검색어 또는 필터를 좁혀 주세요.",
        ).assertIsDisplayed()
        compose.onNodeWithText("최대 96개").assertDoesNotExist()
    }

    @Test fun scanBudgetUncertaintyDoesNotRenderAsAnExhaustiveNoResultsState() {
        compose.setContentOnResumedTestHost { MaterialTheme {
            FusionSearchSurface(false, {}, {}, scanLimitedSource())
        } }
        compose.onNode(hasSetTextAction()).performTextInput("검색")
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("현재 확인한 범위에서 검색 결과를 찾지 못했어요.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("현재 확인한 범위에서 검색 결과를 찾지 못했어요.").assertIsDisplayed()
        compose.onNodeWithText("저장 지식의 일부만 확인했어요. 확인하지 못한 항목에 일치하는 결과가 있을 수 있어요.").assertIsDisplayed()
        compose.onNodeWithText("검색 결과가 없습니다.").assertDoesNotExist()
    }

    @Test fun candidateRetrievalLimitUsesUserVisibleRankingCopy() {
        compose.setContentOnResumedTestHost { MaterialTheme {
            FusionSearchSurface(false, {}, {}, candidateCappedSource())
        } }
        compose.onNode(hasSetTextAction()).performTextInput("검색")
        compose.onNodeWithText("저장 지식은 유형별 관련도 순으로 일부 결과를 보여 드려요.").assertIsDisplayed()
        compose.onNodeWithText("최대 96개").assertDoesNotExist()
    }
    @Test fun deletedResultHasClearFailureAndNoNavigation() {
        var selected: KnowledgeTarget? = null
        compose.setContentOnResumedTestHost { MaterialTheme {
            FusionSearchSurface(false, {}, { selected = it }, source(false))
        } }
        compose.onNode(hasSetTextAction()).performTextInput("검색")
        compose.waitUntil(5000) { compose.onAllNodesWithText("검색한 대화").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("검색한 대화").performClick()
        compose.onNodeWithText("항목이 삭제되었거나 지금 열 수 없습니다.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, selected) }
    }

    @Test fun heterogeneousSelectionHandsOffOneFrozenSnapshotAfterRevalidation() {
        val accessible = mutableListOf(true, true, true, true)
        var handoff: com.projectnuke.fusion.chat.SelectionContextSnapshot? = null
        compose.setContentOnResumedTestHost { MaterialTheme {
            FusionSearchSurface(false, {}, {}, multiSource(accessible), onQuestionWithSelection = { handoff = it })
        } }
        compose.onNode(hasSetTextAction()).performTextInput("검색")
        compose.waitUntil(5000) { compose.onAllNodesWithText("저장된 메모리").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("선택").performClick()
        compose.onNodeWithText("검색한 대화").performClick()
        compose.onNodeWithText("저장된 메모리").performClick()
        compose.onNodeWithText("2개 선택").assertIsDisplayed()
        compose.onNodeWithText("선택한 자료로 질문").assertIsDisplayed()
        compose.onNodeWithText("선택한 자료로 질문").performClick()
        compose.waitUntil(5000) { handoff != null }
        compose.runOnIdle {
            val selected = handoff!!.sources
            assertEquals(2, selected.size)
            assertEquals(setOf(SelectionSourceKind.CONVERSATION, SelectionSourceKind.MEMORY), selected.map { it.identity.kind }.toSet())
            assertEquals(setOf("대화 발췌", "메모 발췌"), selected.map { it.excerpt }.toSet())
        }
    }

    @Test fun removedSourceAfterSelectionBlocksHandoffWithoutWidening() {
        val accessible = mutableListOf(true, false)
        var handoff: com.projectnuke.fusion.chat.SelectionContextSnapshot? = null
        compose.setContentOnResumedTestHost { MaterialTheme {
            FusionSearchSurface(false, {}, {}, multiSource(accessible), onQuestionWithSelection = { handoff = it })
        } }
        compose.onNode(hasSetTextAction()).performTextInput("검색")
        compose.waitUntil(5000) { compose.onAllNodesWithText("검색한 대화").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("선택").performClick()
        compose.onNodeWithText("검색한 대화").performClick()
        compose.onNodeWithText("선택한 자료로 질문").performClick()
        compose.onNodeWithText("항목이 삭제되었거나 지금 열 수 없습니다.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, handoff) }
    }

    @Test fun boundedComparisonUsesTheSameRevalidatedSourceSnapshot() {
        val accessible = mutableListOf(true, true, true, true)
        var compared: com.projectnuke.fusion.chat.SelectionContextSnapshot? = null
        compose.setContentOnResumedTestHost { MaterialTheme {
            FusionSearchSurface(
                false, {}, {}, multiSource(accessible),
                onCompareWithSelection = { compared = it },
            )
        } }
        compose.onNode(hasSetTextAction()).performTextInput("검색")
        compose.waitUntil(5000) { compose.onAllNodesWithText("저장된 메모리").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("선택").performClick()
        compose.onNodeWithText("검색한 대화").performClick()
        compose.onNodeWithText("저장된 메모리").performClick()
        compose.onNodeWithText("추가 작업").performClick()
        compose.onNodeWithText("자료 비교").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { compared != null }
        compose.runOnIdle {
            assertEquals(2, compared!!.sources.size)
            assertEquals(setOf("대화 발췌", "메모 발췌"), compared!!.sources.map { it.excerpt }.toSet())
        }
    }

    @Test fun selectionCancelResetsTheSelectedSet() {
        compose.setContentOnResumedTestHost { MaterialTheme {
            FusionSearchSurface(false, {}, {}, source(true))
        } }
        compose.onNode(hasSetTextAction()).performTextInput("검색")
        compose.waitUntil(5000) { compose.onAllNodesWithText("검색한 대화").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("선택").performClick()
        compose.onNodeWithText("검색한 대화").performClick()
        compose.onNodeWithText("1개 선택").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("선택").assertIsDisplayed()
    }
}
