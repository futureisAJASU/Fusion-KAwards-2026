package com.projectnuke.fusion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProjectSnapshotActionsTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)

    @Test
    fun projectActionsStayBehindOverflowAndWaitForExportChoice() {
        val actions = mutableListOf<String>()
        val showOptions = mutableStateOf(false)
        var included: Boolean? = null
        compose.setContentOnResumedTestHost {
            MaterialTheme {
                if (showOptions.value) {
                    ProjectSnapshotExportOptionsDialog(onDismiss = {}, onExport = { included = it })
                } else {
                    ProjectActionsOverflow(
                        enabled = true,
                        onExport = { actions += "export"; showOptions.value = true },
                        onImport = { actions += "import" },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("프로젝트 스냅샷 메뉴").performClick()
        compose.onNodeWithText("프로젝트 내보내기").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("export"), actions) }
        compose.onNodeWithText("자료 포함").performClick()
        compose.runOnIdle { assertEquals(true, included) }
    }

    @Test
    fun projectReviewActionStaysBehindTheExistingOverflow() {
        var reviews = 0
        compose.setContentOnResumedTestHost {
            MaterialTheme {
                ProjectActionsOverflow(enabled = true, onExport = {}, onImport = {}, onConsistencyReview = { reviews++ })
            }
        }

        compose.onNodeWithContentDescription("프로젝트 작업 메뉴").performClick()
        compose.onNodeWithText("일관성 점검").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(1, reviews) }
    }
}
