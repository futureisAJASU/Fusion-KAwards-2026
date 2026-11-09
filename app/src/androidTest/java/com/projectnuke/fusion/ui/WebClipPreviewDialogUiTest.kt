package com.projectnuke.fusion.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.projectnuke.fusion.data.ProjectEntity
import com.projectnuke.fusion.knowledge.WebClipDocument
import com.projectnuke.fusion.knowledge.WebClipCapture
import com.projectnuke.fusion.knowledge.WebClipFailure
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WebClipPreviewDialogUiTest {
    private val compose = createComposeRule()
    @get:Rule val composeHostRules = composeHostTestRules(compose)

    @Test fun largeTextReadingKeepsEverySaveDestinationAndExplicitCallbacks() {
        var inbox = 0
        var knowledge = 0
        var project: Long? = null
        var closes = 0
        var saving by mutableStateOf(false)
        val url = "https://example.com/article"
        compose.setContentOnResumedTestHost {
            FusionTheme(FusionThemeMode.BLACK) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    WebClipPreviewDialog(
                        state = WebClipPreviewState.Ready("request", url,
                            WebClipCapture.fromGuardedFetch(WebClipDocument(url, "원문", "원문 미리보기", "긴 본문입니다.\n".repeat(150), true, 1L))),
                        projects = listOf(ProjectEntity(7L, "자료 프로젝트", createdAt = 1L, updatedAt = 1L)),
                        saving = saving, onDismiss = { closes++ }, onRetry = {},
                        onSaveToInbox = { inbox++ }, onSaveToKnowledge = { knowledge++ }, onSaveToProject = { project = it },
                    )
                }
            }
        }
        compose.runOnIdle { assertEquals(0, inbox); assertEquals(0, knowledge); assertNull(project) }
        compose.onNodeWithText("Inbox에 저장").assertIsDisplayed().performClick()
        compose.onNodeWithText("저장 지식으로 저장").assertIsDisplayed().performClick()
        compose.onNodeWithText("자료 프로젝트").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, inbox); assertEquals(1, knowledge); assertEquals(7L, project) }
        compose.runOnIdle { saving = true }
        compose.onNodeWithText("저장 중…").assertIsNotEnabled()
        compose.onNodeWithText("저장 지식으로 저장").assertIsNotEnabled()
        compose.onNodeWithText("자료 프로젝트").assertIsNotEnabled()
        compose.onNodeWithText("닫기").assertIsNotEnabled()
        compose.onNodeWithContentDescription("이전 화면으로").performClick()
        compose.runOnIdle { assertEquals(0, closes); saving = false }
        compose.onNodeWithText("닫기").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun failedPreviewRetainsRetryWithoutSaveActions() {
        var retries = 0
        compose.setContentOnResumedTestHost {
            FusionTheme {
                WebClipPreviewDialog(WebClipPreviewState.Failed("request", "https://example.com", WebClipFailure.NETWORK),
                    emptyList(), false, {}, { retries++ }, {}, {}, {})
            }
        }
        compose.onNodeWithText("다시 가져오기").assertIsDisplayed().performClick()
        compose.onNodeWithText("Inbox에 저장").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
