package com.projectnuke.fusion.inbox

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class InboxBulkActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun smartReviewIsReachableInTheRealBulkMenuAndStartsItsOwner() {
        var started = false
        compose.setContent { MaterialTheme {
            var expanded by remember { mutableStateOf(false) }
            InboxBulkActions(
                selectedCount = 2,
                triageInFlight = false,
                expanded = expanded,
                onExpandedChange = { expanded = it },
                onMoveToProject = {},
                onMoveToKnowledge = {},
                onAddTag = {},
                onSmartReview = { started = true },
                onDelete = {},
            )
        } }

        compose.onNodeWithText("작업 (2)").performClick()
        compose.onNodeWithText("Project에 이동").assertExists()
        compose.onNodeWithText("저장 지식으로 이동").assertExists()
        compose.onNodeWithText("태그 추가").assertExists()
        compose.onNodeWithText("정리 제안").assertExists().assertIsEnabled().performClick()
        compose.onNodeWithText("삭제").assertExists()
        compose.runOnIdle { assert(started) }
    }

    @Test
    fun oneAndMoreThanTwelveSelectedCannotStartAndShowTheReason() {
        var selectedCount by mutableStateOf(1)
        var expanded by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            InboxBulkActions(
                selectedCount = selectedCount,
                triageInFlight = false,
                expanded = expanded,
                onExpandedChange = { expanded = it },
                onMoveToProject = {},
                onMoveToKnowledge = {},
                onAddTag = {},
                onSmartReview = {},
                onDelete = {},
            )
        } }
        compose.onNodeWithText("작업 (1)").performClick()
        compose.onNodeWithText("정리 제안 (2개 이상 선택)").assertExists().assertIsNotEnabled()

        compose.runOnIdle { selectedCount = 13 }
        compose.onNodeWithText("정리 제안은 한 번에 최대 12개까지 가능합니다.")
            .assertExists().assertIsNotEnabled()
    }
}
